package com.lexiflow.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.lexiflow.ui.components.Chip
import com.lexiflow.ui.components.GradientCard
import com.lexiflow.ui.components.GradientProgress
import com.lexiflow.ui.theme.BrandGradients
import com.lexiflow.ui.theme.Coral
import com.lexiflow.ui.theme.Mint
import com.lexiflow.ui.theme.Pink
import com.lexiflow.ui.theme.Sky
import com.lexiflow.ui.theme.Sunshine
import com.lexiflow.ui.theme.Violet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.lexiflow.core.active.Skill
import com.lexiflow.core.ai.AiFeedback
import com.lexiflow.core.ai.EvaluateRequest
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.data.settings.Accent
import com.lexiflow.di.AppContainer
import com.lexiflow.domain.Exercise
import com.lexiflow.domain.ExerciseGrader
import com.lexiflow.domain.ExerciseType
import com.lexiflow.domain.GradeResult
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.EvaluationView
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.MicButton
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DrillUiState(
    val loading: Boolean = true,
    val exercises: List<Exercise> = emptyList(),
    val emptyReason: String? = null,
    val index: Int = 0,
    val input: String = "",
    val arranged: List<String> = emptyList(),
    val extraHints: Int = 0,
    val result: GradeResult? = null,
    val usedVoice: Boolean = false,
    val correct: Int = 0,
    val ai: AiFeedback? = null,
    val aiNotice: String? = null,
    val aiLoading: Boolean = false,
    val note: String? = null,
    val secondsLeft: Int? = null,
    val finished: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val current: Exercise? get() = exercises.getOrNull(index)
}

class DrillViewModel(private val c: AppContainer, val mode: StudyMode) : ViewModel() {
    val state = MutableStateFlow(DrillUiState())

    init {
        viewModelScope.launch {
            c.contentLoader.ensureSeeded()
            val session = c.sessions.build(mode)
            state.value = DrillUiState(loading = false, exercises = session.exercises, emptyReason = session.emptyReasonTr, secondsLeft = session.timeLimitSec)
            session.timeLimitSec?.let { startTimer() }
        }
    }

    private fun startTimer() = viewModelScope.launch {
        while (true) {
            delay(1000)
            val left = state.value.secondsLeft ?: break
            if (state.value.finished) break
            if (left <= 1) { finish(); break }
            state.update { it.copy(secondsLeft = left - 1) }
        }
    }

    fun setInput(v: String) = state.update { it.copy(input = v.take(1500)) }
    fun moreHint() = state.update { it.copy(extraHints = it.extraHints + 1) }
    fun addToken(i: Int) = state.update { s -> s.copy(arranged = s.arranged + s.current!!.tokens[i]) }
    fun removeToken(i: Int) = state.update { s -> s.copy(arranged = s.arranged.toMutableList().also { it.removeAt(i) }) }
    fun voice(text: String) = state.update { it.copy(input = text, usedVoice = true) }

    /** Visible hint level at answer time (base level from the skill state + hints the learner asked for). */
    private fun hintUsed(ex: Exercise): Int = (ex.hintLevel + state.value.extraHints).coerceAtMost(3).let { if (ex.isChoice) maxOf(it, 2) else it }

    fun submitChoice(i: Int) = grade { ExerciseGrader.gradeChoice(it, i) }
    fun submitText() = grade { ex ->
        when {
            ex.type == ExerciseType.SENTENCE_ORDER -> ExerciseGrader.gradeOrder(ex, state.value.arranged)
            ex.type == ExerciseType.DICTATION -> ExerciseGrader.gradeDictation(ex, state.value.input)
            ex.isFreeText -> ExerciseGrader.gradeFreeText(ex, state.value.input)
            else -> ExerciseGrader.gradeTyped(ex, state.value.input)
        }
    }

    private fun grade(block: (Exercise) -> GradeResult) = viewModelScope.launch {
        val ex = state.value.current ?: return@launch
        if (state.value.result != null) return@launch
        val r = block(ex)
        state.update { it.copy(result = r, correct = it.correct + if (r.correct) 1 else 0) }
        val skill = if (ex.type == ExerciseType.PRODUCTION && state.value.usedVoice) Skill.SPEAKING else ex.type.skill
        val cardId = ex.cardId
        if (cardId != null) {
            c.active.record(cardId, skill, r.score, hintUsed(ex), "${mode.name}_${ex.type.name}", r.verdict, ex.exampleId,
                answerText = state.value.input.takeIf { ex.isFreeText }, durationMs = System.currentTimeMillis() - state.value.startedAt)
        } else if (r.correct) {
            c.gamification.award(XpReason.ACTIVE_RECALL_SUCCESS)
        }
    }

    fun askAi() = viewModelScope.launch {
        val ex = state.value.current ?: return@launch
        state.update { it.copy(aiLoading = true, aiNotice = null) }
        try {
            val fb = c.backend.evaluate(EvaluateRequest(state.value.input, listOf(ex.lemma).filter { ex.cardId != null }, mode.name, c.settings.current().cefr, ex.prompt))
            state.update { it.copy(ai = fb, aiLoading = false) }
        } catch (e: RemoteError) {
            state.update { it.copy(aiNotice = e.messageTr, aiLoading = false) }
        }
    }

    fun cantUse() = viewModelScope.launch {
        val id = state.value.current?.cardId ?: return@launch
        c.active.flagCantUse(id)
        state.update { it.copy(note = "Aktif üretim listene eklendi.") }
    }

    fun next() {
        val s = state.value
        if (s.index + 1 >= s.exercises.size) { finish(); return }
        state.update { it.copy(index = it.index + 1, input = "", arranged = emptyList(), extraHints = 0, result = null, usedVoice = false, ai = null, aiNotice = null, note = null, startedAt = System.currentTimeMillis()) }
    }

    private fun finish() = viewModelScope.launch {
        if (state.value.finished) return@launch
        state.update { it.copy(finished = true) }
        if (mode == StudyMode.DAILY_CHALLENGE) {
            val s = state.value
            c.sessions.completeDailyChallenge(if (s.exercises.isEmpty()) 0 else s.correct * 100 / s.exercises.size)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrillScreen(container: AppContainer, nav: NavController, mode: StudyMode) {
    val vm: DrillViewModel = viewModel(key = "drill_$mode", factory = viewModelFactory { initializer { DrillViewModel(container, mode) } })
    val s by vm.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(mode.titleTr, onBack = { nav.popBackStack() })
        when {
            s.loading -> LoadingBox()
            s.exercises.isEmpty() -> EmptyState(s.emptyReason ?: "Alıştırma oluşturulamadı.", "FSRS tekrarı") { nav.navigate(Routes.review()) }
            s.finished -> EmptyState(
                "${mode.titleTr} bitti: ${s.correct}/${s.exercises.size} doğru." +
                    (if (mode == StudyMode.DAILY_CHALLENGE) " Günlük görev tamamlandı (+30 XP)." else "") +
                    " Hatalı veya ipuçlu cevaplar daha erken tekrar sıraya alındı.",
                "Ana sayfa",
            ) { nav.popBackStack() }
            else -> {
                val ex = s.current!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("drill"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientProgress(s.index.toFloat() / s.exercises.size, Modifier.weight(1f), brush = BrandGradients.sunset)
                        Text("${s.index + 1}/${s.exercises.size}", style = MaterialTheme.typography.labelLarge, color = Pink)
                        s.secondsLeft?.let { Chip("⏱ ${it / 60}:${"%02d".format(it % 60)}", if (it < 15) Danger else Sky) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Chip(ex.type.labelTr, Violet)
                        Text("🔥 ${s.correct} doğru", style = MaterialTheme.typography.labelMedium, color = Coral)
                    }
                    GradientCard(BrandGradients.hero, padding = 22.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(ex.instructionTr, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.85f))
                            Text(ex.prompt, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("drill_prompt"))
                            ex.secondary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f)) }
                            if (ex.ttsText != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilledTonalButton(onClick = { tts.speak(ex.ttsText, uk) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White)) { Text("🔊 Dinle") }
                                    FilledTonalButton(onClick = { tts.speak(ex.ttsText, uk, slow = true) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.22f), contentColor = Color.White)) { Text("🐢 Yavaş") }
                                }
                            }
                            val hintLevel = (ex.hintLevel + s.extraHints).coerceAtMost(3)
                            val hints = if (s.extraHints > 0 && ex.type == ExerciseType.TR_TO_EN) com.lexiflow.core.active.HintBuilder.hint(hintLevel, ex.lemma, ex.meaningTr, ex.reference?.let { r -> com.lexiflow.core.exercise.ClozeBuilder.maskLemma(r, ex.lemma) }) else ex.hints
                            hints.forEach { Text("💡 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(Color.White.copy(alpha = 0.16f)).padding(10.dp)) }
                        }
                    }
                    val result = s.result
                    when {
                        ex.isChoice -> ex.options.forEachIndexed { i, o ->
                            val colorOk = result != null && i == ex.correctIndex
                            val accent = if (colorOk) Mint else listOf(Violet, Sky, Pink, Sunshine)[i % 4]
                            Surface(
                                onClick = { vm.submitChoice(i) }, enabled = result == null || colorOk,
                                shape = MaterialTheme.shapes.medium, color = if (colorOk) Mint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(2.dp, accent.copy(alpha = if (result != null && !colorOk) 0.25f else 0.7f)),
                                modifier = Modifier.fillMaxWidth().testTag("option_$i"),
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(32.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                                        Text(if (colorOk) "✓" else "${'A' + i}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Text(o, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        ex.type == ExerciseType.SENTENCE_ORDER -> {
                            Text("Senin cümlen:", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)).padding(8.dp)) {
                                s.arranged.forEachIndexed { i, t -> InputChip(selected = true, onClick = { if (result == null) vm.removeToken(i) }, label = { Text(t) }) }
                            }
                            val used = s.arranged.groupingBy { it }.eachCount()
                            val remaining = mutableMapOf<String, Int>()
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ex.tokens.forEachIndexed { i, t ->
                                    val seen = (remaining[t] ?: 0) + 1
                                    remaining[t] = seen
                                    if (seen > (used[t] ?: 0)) AssistChip(onClick = { if (result == null) vm.addToken(i) }, label = { Text(t) })
                                }
                            }
                            if (result == null) Button(onClick = { vm.submitText() }, enabled = s.arranged.size == ex.tokens.size, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("Kontrol et") }
                        }
                        else -> {
                            OutlinedTextField(
                                value = s.input, onValueChange = vm::setInput, enabled = result == null,
                                label = { Text(if (ex.isFreeText || ex.type == ExerciseType.DICTATION) "Cümleni yaz" else "Cevabın") },
                                singleLine = !(ex.isFreeText || ex.type == ExerciseType.DICTATION), modifier = Modifier.fillMaxWidth().testTag("drill_input"),
                                shape = MaterialTheme.shapes.medium,
                            )
                            if (result == null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.submitText() }, enabled = s.input.isNotBlank(), modifier = Modifier.weight(1f).height(52.dp).testTag("drill_submit"), shape = CircleShape) { Text("Kontrol et") }
                                    if (ex.type == ExerciseType.TR_TO_EN && (ex.hintLevel + s.extraHints) < 3) TextButton(onClick = { vm.moreHint() }) { Text("İpucu") }
                                }
                                if (ex.type == ExerciseType.PRODUCTION || ex.type == ExerciseType.TRANSLATION) {
                                    MicButton(languageTag = if (uk) "en-GB" else "en-US", preferOffline = settings?.preferOfflineSpeech ?: true) { hyps, _ ->
                                        hyps.firstOrNull()?.let { vm.voice(it.text) }
                                    }
                                }
                            }
                        }
                    }
                    if (result != null) {
                        SectionCard(accent = if (result.correct) Success else Danger, icon = if (result.correct) Icons.Filled.CheckCircle else Icons.Filled.Cancel, title = if (result.correct) "Harika!" else "Neredeyse") {
                            Text(if (result.correct) "✓ ${result.feedbackTr}" else result.feedbackTr, color = if (result.correct) Success else Danger, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("drill_feedback"))
                            ex.reference?.takeIf { !ex.isChoice || !result.correct }?.let { Text("Cümle: $it") }
                            ex.referenceTr?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            ex.explanationTr?.let { Text("💡 $it", style = MaterialTheme.typography.bodySmall) }
                        }
                        result.evaluation?.let { EvaluationView(it) }
                        if (ex.isFreeText && settings?.aiAvailable == true && s.ai == null) {
                            OutlinedButton(onClick = { vm.askAi() }, enabled = !s.aiLoading) { Text(if (s.aiLoading) "AI inceliyor…" else "AI'dan ayrıntılı geri bildirim al") }
                        }
                        s.aiNotice?.let { NoticeBanner(it, warning = true) }
                        s.ai?.let { AiFeedbackView(it) }
                        s.note?.let { NoticeBanner(it) }
                        if (ex.cardId != null && s.note == null) OutlinedButton(onClick = { vm.cantUse() }, modifier = Modifier.fillMaxWidth(), shape = CircleShape) { Text("🤔 Tanıyorum ama kullanamıyorum") }
                        Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("drill_next"), shape = CircleShape) { Text("Sonraki →", style = MaterialTheme.typography.titleMedium) }
                    }
                    LaunchedEffect(ex) { if (ex.type == ExerciseType.LISTENING_CHOICE || ex.type == ExerciseType.DICTATION) { delay(400); ex.ttsText?.let { tts.speak(it, uk) } } }
                }
            }
        }
    }
}

@Composable
fun AiFeedbackView(fb: AiFeedback) {
    SectionCard("AI geri bildirimi", accent = Violet, icon = Icons.Filled.AutoAwesome) {
        Text("Ne demek istedin: ${fb.intendedMeaningTr}", fontWeight = FontWeight.Medium)
        fb.corrections.take(3).forEach { c ->
            Text("✗ ${c.original} → ✓ ${c.corrected}", fontWeight = FontWeight.SemiBold)
            Text(c.explanationTr, style = MaterialTheme.typography.bodySmall)
        }
        fb.naturalAlternative?.let { Text("Daha doğal: $it") }
        if (fb.alternatives.isNotEmpty()) Text("Diğer doğru seçenekler: " + fb.alternatives.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
        fb.targetWordFeedback.forEach { Text("${if (it.correct) "✓" else "✗"} ${it.lemma}: ${it.commentTr}", style = MaterialTheme.typography.bodySmall) }
        fb.encouragementTr?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
