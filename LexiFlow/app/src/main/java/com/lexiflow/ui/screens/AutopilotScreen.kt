package com.lexiflow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.autopilot.AutopilotPlanner
import com.lexiflow.core.autopilot.Phase
import com.lexiflow.core.autopilot.StepKind
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.repo.AutopilotEstimate
import com.lexiflow.data.repo.AutopilotSession
import com.lexiflow.data.settings.Accent
import com.lexiflow.di.AppContainer
import com.lexiflow.domain.Exercise
import com.lexiflow.domain.ExerciseGrader
import com.lexiflow.domain.ExerciseType
import com.lexiflow.domain.GradeResult
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.Chip
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.GradientProgress
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Moss
import com.lexiflow.ui.theme.Ochre
import com.lexiflow.ui.theme.Sky
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutopilotUiState(
    val loading: Boolean = true,
    val session: AutopilotSession? = null,
    /** Exercise state of the current drill step (same model as the drill screen). */
    val drill: DrillUiState = DrillUiState(loading = false),
    /** New words of a WORD_INTRO step and the index of the card shown. */
    val introCards: List<CardDetail> = emptyList(),
    val introExamples: Map<Long, List<ExampleEntity>> = emptyMap(),
    val introIndex: Int = 0,
    /** Target words of a module step (story, speaking, pronunciation). */
    val moduleWords: List<String> = emptyList(),
    val moduleOpened: Boolean = false,
)

/**
 * Runs today's autopilot plan step by step. Drill steps reuse the exercise UI but grade in the background
 * (no Again/Hard/Good/Easy); module steps open the existing screen and come back here.
 */
class AutopilotViewModel(private val c: AppContainer, private val quickMinutes: Int? = null) : ViewModel(), ExerciseActions {
    val state = MutableStateFlow(AutopilotUiState())

    init {
        viewModelScope.launch {
            c.contentLoader.ensureSeeded()
            val session = c.autopilot.startOrResume(quickMinutes)
            loadStep(session)
        }
    }

    private suspend fun loadStep(session: AutopilotSession?) {
        val step = session?.current
        if (session == null || step == null) {
            state.value = AutopilotUiState(loading = false, session = session)
            return
        }
        when {
            step.kind == StepKind.WORD_INTRO -> {
                val cards = c.db.cardDao().details(step.cardIds)
                val examples = cards.associate { it.card.id to c.db.wordDao().examplesForWord(it.card.wordId).filter { e -> e.senseId == it.card.senseId } }
                if (cards.isEmpty()) return loadStep(c.autopilot.completeStep(skipped = true))
                state.value = AutopilotUiState(loading = false, session = session, introCards = cards, introExamples = examples)
            }
            step.kind.isModule -> {
                val words = c.db.cardDao().details(step.cardIds.filter { it > 0 }).map { it.lemma }
                state.value = AutopilotUiState(loading = false, session = session, moduleWords = words)
            }
            else -> {
                val exercises = c.autopilot.exercises(step, session.silent)
                if (exercises.isEmpty()) return loadStep(c.autopilot.completeStep(skipped = true))
                state.value = AutopilotUiState(loading = false, session = session, drill = DrillUiState(loading = false, exercises = exercises))
            }
        }
    }

    private fun updateDrill(f: (DrillUiState) -> DrillUiState) = state.update { it.copy(drill = f(it.drill)) }

    override fun setInput(v: String) = updateDrill { it.copy(input = v.take(1500)) }
    override fun moreHint() = updateDrill { it.copy(extraHints = it.extraHints + 1) }
    override fun addToken(i: Int) = updateDrill { d -> d.copy(arranged = d.arranged + d.current!!.tokens[i]) }
    override fun removeToken(i: Int) = updateDrill { d -> d.copy(arranged = d.arranged.toMutableList().also { it.removeAt(i) }) }
    override fun voice(text: String) = updateDrill { it.copy(input = text, usedVoice = true) }
    override fun submitChoice(i: Int) { grade(state.value.drill.current?.options?.getOrNull(i).orEmpty()) { ExerciseGrader.gradeChoice(it, i) } }
    override fun dontKnow() { grade("") { ex -> GradeResult(0.0, "WRONG", false, "Doğru cevap: ${ex.expected}", ex.expected) } }
    override fun submitText() {
        grade(typedAnswer(state.value.drill)) { ex ->
            val d = state.value.drill
            when {
                ex.type == ExerciseType.SENTENCE_ORDER -> ExerciseGrader.gradeOrder(ex, d.arranged)
                ex.type == ExerciseType.DICTATION -> ExerciseGrader.gradeDictation(ex, d.input)
                ex.isFreeText -> ExerciseGrader.gradeFreeText(ex, d.input)
                else -> ExerciseGrader.gradeTyped(ex, d.input)
            }
        }
    }

    /** Grading happens here, silently: correctness, hints and time decide the FSRS rating. */
    private fun grade(answer: String, block: (Exercise) -> GradeResult) {
        viewModelScope.launch {
            val d = state.value.drill
            val ex = d.current ?: return@launch
            if (d.result != null) return@launch
            val r = block(ex)
            c.mistakes.recordExercise(ex, r, answer, voice = d.usedVoice, source = "AUTOPILOT")
            updateDrill { it.copy(result = r, correct = it.correct + if (r.correct) 1 else 0) }
            val kind = state.value.session?.current?.kind ?: return@launch
            c.autopilot.recordAnswer(kind, ex, r, d.extraHints, System.currentTimeMillis() - d.startedAt)
        }
    }

    override fun askAi() {
        viewModelScope.launch {
            val d = state.value.drill
            val ex = d.current ?: return@launch
            updateDrill { it.copy(aiLoading = true, aiNotice = null) }
            try {
                val fb = c.backend.evaluate(EvaluateRequest(d.input, listOf(ex.lemma).filter { ex.cardId != null }, "AUTOPILOT", c.settings.current().cefr, ex.prompt))
                updateDrill { it.copy(ai = fb, aiLoading = false) }
            } catch (e: RemoteError) {
                updateDrill { it.copy(aiNotice = e.messageTr, aiLoading = false) }
            }
        }
    }

    override fun flagAi() {
        viewModelScope.launch {
            val d = state.value.drill
            val ex = d.current ?: return@launch
            val old = d.ai ?: return@launch
            updateDrill { it.copy(ai = null, aiLoading = true, aiNotice = null) }
            val r = c.aiFeedback.flagFeedback(old, EvaluateRequest(d.input, listOf(ex.lemma).filter { ex.cardId != null }, "AUTOPILOT", c.settings.current().cefr, ex.prompt))
            updateDrill { it.copy(ai = r.feedback, aiLoading = false, aiNotice = r.noticeTr) }
        }
    }

    override fun cantUse() {
        viewModelScope.launch {
            val id = state.value.drill.current?.cardId ?: return@launch
            c.active.flagCantUse(id, source = "AUTOPILOT")
            updateDrill { it.copy(note = "Aktif üretim listene eklendi.") }
        }
    }

    override fun next() {
        val d = state.value.drill
        if (d.index + 1 < d.exercises.size) {
            updateDrill { it.copy(index = it.index + 1, input = "", arranged = emptyList(), extraHints = 0, result = null, usedVoice = false, ai = null, aiNotice = null, note = null, startedAt = System.currentTimeMillis()) }
        } else {
            completeStep(skipped = false)
        }
    }

    fun nextIntro() {
        val s = state.value
        if (s.introIndex + 1 < s.introCards.size) state.update { it.copy(introIndex = it.introIndex + 1) } else completeStep(skipped = false)
    }

    fun moduleOpened() = state.update { it.copy(moduleOpened = true) }

    fun completeStep(skipped: Boolean) {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            loadStep(c.autopilot.completeStep(skipped))
        }
    }
}

private fun moduleRoute(kind: StepKind): String = when (kind) {
    StepKind.PRONUNCIATION -> Routes.PRONUNCIATION
    StepKind.SHADOWING -> Routes.SHADOWING
    StepKind.FORGETTING_STORY -> Routes.stories(fc = true)
    StepKind.AI_STORY -> Routes.stories(fc = false)
    StepKind.SCREEN_CONTEXT -> Routes.culture("SCREEN")
    StepKind.MUSIC_CONTEXT -> Routes.culture("MUSIC")
    StepKind.AI_SPEAKING -> Routes.speaking(true)
    else -> Routes.speaking(false)
}

private fun moduleHint(kind: StepKind): String = when (kind) {
    StepKind.PRONUNCIATION -> "Yeni kelimeleri ve cümlelerini sesli söyle; minimal çiftleri dene."
    StepKind.SHADOWING -> "Cümleyi dinle, hemen ardından aynı ritimle tekrar et."
    StepKind.FORGETTING_STORY -> "Unutulmak üzere olan kelimelerinle kısa bir hikâye oku ve sorularını cevapla."
    StepKind.AI_STORY -> "Bu kelimelerle bir hikâye oluştur ve sorularını cevapla."
    StepKind.SCREEN_CONTEXT, StepKind.MUSIC_CONTEXT -> "Bir sahnede veya dizede kelimeyi bağlamından tahmin et."
    StepKind.AI_SPEAKING -> "Koçla kısa bir sesli sohbet yap; aşağıdaki kelimeleri kullanmaya çalış."
    else -> "Koçla yazarak kısa bir sohbet yap; aşağıdaki kelimeleri kullanmaya çalış."
}

@Composable
fun AutopilotScreen(container: AppContainer, nav: NavController, quickMinutes: Int? = null) {
    val vm: AutopilotViewModel = viewModel(key = "autopilot_$quickMinutes", factory = viewModelFactory { initializer { AutopilotViewModel(container, quickMinutes) } })
    val s by vm.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Akıllı Günlük Seans", onBack = { nav.popBackStack() })
        val session = s.session
        when {
            s.loading -> LoadingBox("Bugünün planı hazırlanıyor…")
            session == null || session.finished -> AutopilotSummary(session) { nav.popBackStack() }
            else -> {
                val step = session.current!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("autopilot"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Stage, step and time left.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientProgress(session.index.toFloat() / session.steps.size, Modifier.weight(1f))
                        Text("≈ ${(session.remainingSeconds + 59) / 60} dk", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip("Aşama ${step.phase.ordinal + 1}/${Phase.entries.size}", Sky)
                        Text(step.phase.labelTr, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("autopilot_phase"))
                        if (session.silent) Chip("Sessiz mod", Ochre)
                        if (session.quick) Chip("${session.budgetMinutes} dk", Moss)
                    }
                    Text(step.kind.labelTr, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("autopilot_step"))
                    when {
                        step.kind == StepKind.WORD_INTRO -> {
                            val card = s.introCards.getOrNull(s.introIndex)
                            if (card != null) {
                                Text("Yeni kelime ${s.introIndex + 1}/${s.introCards.size}", style = MaterialTheme.typography.labelLarge)
                                SectionCard {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(card.lemma, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).testTag("autopilot_intro_word"))
                                        IconButton(onClick = { tts.speak(card.lemma, uk) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Telaffuzu dinle") }
                                    }
                                    Text(listOfNotNull(card.pos, if (uk) card.ipaUk ?: card.ipaUs else card.ipaUs ?: card.ipaUk, card.cefr).joinToString(" · "),
                                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                WordBackSide(card, s.introExamples[card.card.id].orEmpty(), tts, uk, rememberWordFamily(container, card.lemma, card.richJson))
                                Button(onClick = { vm.nextIntro() }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("autopilot_intro_next")) {
                                    Text(if (s.introIndex + 1 < s.introCards.size) "Sonraki kelime" else "Devam")
                                }
                            }
                        }
                        step.kind.isModule -> {
                            SectionCard(step.kind.labelTr) {
                                Text(moduleHint(step.kind))
                                if (s.moduleWords.isNotEmpty()) Text("Hedef kelimeler: " + s.moduleWords.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                                if (step.kind == StepKind.AI_STORY || step.kind == StepKind.AI_SPEAKING) {
                                    if (settings?.aiAvailable != true) NoticeBanner("AI sunucusu bağlı değil: hikâye şablonla oluşturulur, konuşma koçu çevrim dışı değerlendirme kullanır.", warning = true)
                                }
                            }
                            if (!s.moduleOpened) {
                                Button(onClick = { vm.moduleOpened(); nav.navigate(moduleRoute(step.kind)) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("autopilot_module_open")) { Text("Başla") }
                            } else {
                                Button(onClick = { vm.completeStep(skipped = false) }, modifier = Modifier.fillMaxWidth().height(52.dp).testTag("autopilot_module_done")) { Text("Tamamladım, devam") }
                                OutlinedButton(onClick = { nav.navigate(moduleRoute(step.kind)) }, modifier = Modifier.fillMaxWidth()) { Text("Tekrar aç") }
                            }
                            TextButton(onClick = { vm.completeStep(skipped = true) }, modifier = Modifier.testTag("autopilot_skip")) { Text("Bu adımı atla") }
                        }
                        else -> {
                            val d = s.drill
                            d.current?.let { ex ->
                                Text("${d.index + 1}/${d.exercises.size} · ${d.correct} doğru", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ExerciseBody(ex, d, vm, tts, uk, settings)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutopilotSummary(session: AutopilotSession?, onDone: () -> Unit) {
    if (session == null) {
        EmptyState("Bugün için çalışılacak kelime yok. Bir deste ekle veya hazır setlerden birini seç.", "Ana sayfa", onDone)
        return
    }
    val pct = if (session.answered == 0) 100 else session.correct * 100 / session.answered
    val minutes = ((session.finishedAt ?: session.startedAt) - session.startedAt) / 60_000
    EmptyState(
        "Seans tamamlandı: ${session.answered} cevap, %$pct doğru, $minutes dk. " +
            "Yanlış yaptığın kelimeler Zayıf Kelimeler'e eklendi ve bugün kilit ekranında önce sorulacak. Günlük görev tamamlandı; " +
            "bugünkü minimum görev de tamam, serin devam ediyor.",
        "Ana sayfa", onDone,
    )
}

/** Top card of the home screen: today's calculated time, the time override (5–120 min), silent mode and start. */
@Composable
fun AutopilotCard(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    var estimate by remember { mutableStateOf<AutopilotEstimate?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(settings?.autopilotMinutes, settings?.autopilotSilent) {
        estimate = runCatching { container.autopilot.estimate() }.getOrNull()
        estimate?.let { sliderValue = it.budgetMinutes.toFloat() }
    }
    SectionCard("Otomatik Mod", icon = Icons.Default.AutoMode, modifier = Modifier.testTag("autopilot_card")) {
        val e = estimate
        if (e == null) {
            Text("Bugünün planı hesaplanıyor…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Text("Hesaplanan süre: ${e.calculatedMinutes} dk (${e.reviewWords} eski + ${e.newWords} yeni kelime)",
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("autopilot_calculated"))
        if (e.customBudget) Text("Seçtiğin süre: ${e.budgetMinutes} dk", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        val running = e.session?.takeIf { !it.finished }
        val done = e.session?.takeIf { it.finished }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sessiz ortam / konuşamam", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = settings?.autopilotSilent == true, modifier = Modifier.testTag("autopilot_silent"), onCheckedChange = { v ->
                scope.launch { container.settings.update { it.copy(autopilotSilent = v) }; if (running == null) container.autopilot.reset() }
            })
        }
        if (adjusting) {
            Text("Süre: ${sliderValue.toInt()} dk", style = MaterialTheme.typography.labelLarge)
            Slider(value = sliderValue, onValueChange = { sliderValue = it.toInt().toFloat() },
                valueRange = AutopilotPlanner.MIN_BUDGET_MINUTES.toFloat()..AutopilotPlanner.MAX_BUDGET_MINUTES.toFloat(),
                modifier = Modifier.testTag("autopilot_slider"))
            Text(
                when {
                    sliderValue.toInt() < e.calculatedMinutes -> "Kısa süre: önce zamanı gelen ve zayıf kelimeler; yeni kelimeler azalır, hızlı alıştırmalar seçilir."
                    sliderValue.toInt() > e.calculatedMinutes -> "Uzun süre: kalan zaman kelime defteri, aktif üretim, hikâye ve konuşmayla dolar."
                    else -> "Hesaplanan süre."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    adjusting = false
                    scope.launch { container.settings.update { it.copy(autopilotMinutes = sliderValue.toInt()) }; if (running == null) container.autopilot.reset() }
                }, modifier = Modifier.testTag("autopilot_slider_save")) { Text("Kaydet") }
                TextButton(onClick = {
                    adjusting = false
                    scope.launch { container.settings.update { it.copy(autopilotMinutes = 0) }; if (running == null) container.autopilot.reset() }
                }) { Text("Hesaplanana dön") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { nav.navigate(Routes.AUTOPILOT) }, modifier = Modifier.weight(1f).height(48.dp).testTag("autopilot_start")) {
                Text(
                    when {
                        running != null -> "Devam et · ${(running.remainingSeconds + 59) / 60} dk"
                        done != null -> "Yeni seans başlat"
                        else -> "Başlat · ${e.budgetMinutes} dk"
                    },
                )
            }
            if (!adjusting) OutlinedButton(onClick = { adjusting = true }, modifier = Modifier.testTag("autopilot_adjust")) { Text("Süreyi ayarla") }
        }
        if (done == null && running?.quick != true) {
            TextButton(onClick = { nav.navigate(Routes.AUTOPILOT_QUICK) }, modifier = Modifier.testTag("autopilot_quick")) {
                Text("⏱ Bugün sadece 5 dk var")
            }
            Text("Kısa seans: önce zamanı gelen ve zayıf kelimeler, hızlı alıştırmalar. Bitirirsen serin bozulmaz.", style = MaterialTheme.typography.bodySmall)
        }
        if (done != null) {
            Spacer(Modifier.width(4.dp))
            Chip("Bugünün seansı tamamlandı", Moss)
        }
        if (running != null) Text("Süre veya sessiz mod değişikliği yeni seansta geçerli olur.", style = MaterialTheme.typography.bodySmall)
    }
}
