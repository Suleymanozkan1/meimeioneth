package com.lexiflow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.repo.StudyRepository
import com.lexiflow.data.repo.WordRepository
import com.lexiflow.data.settings.Accent
import com.lexiflow.di.AppContainer
import com.lexiflow.speech.Tts
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import com.lexiflow.ui.theme.Warning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val queue: List<CardDetail> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    val examples: List<ExampleEntity> = emptyList(),
    val intervals: Map<Rating, String> = emptyMap(),
    val reviewed: Int = 0,
    val again: Int = 0,
    val notice: String? = null,
    val shownAt: Long = 0,
    val answering: Boolean = false,
) {
    val current: CardDetail? get() = queue.getOrNull(index)
    val done: Boolean get() = !loading && index >= queue.size
}

class ReviewViewModel(private val c: AppContainer, private val free: Boolean, private val deckId: Long?) : ViewModel() {
    val state = MutableStateFlow(ReviewUiState())

    init { load() }

    private fun load() = viewModelScope.launch {
        c.contentLoader.ensureSeeded()
        val queue = if (free) c.db.cardDao().randomAny(30) else c.study.reviewQueue(deckId)
        state.value = ReviewUiState(loading = false, queue = queue, shownAt = System.currentTimeMillis())
        loadExamples()
    }

    private suspend fun loadExamples() {
        val card = state.value.current ?: return
        val ex = c.db.wordDao().examplesForWord(card.card.wordId).filter { it.senseId == card.card.senseId }
        state.update { it.copy(examples = ex) }
    }

    fun reveal() = viewModelScope.launch {
        val card = state.value.current ?: return@launch
        val intervals = if (free) emptyMap() else c.study.preview(card.card).mapValues { StudyRepository.formatInterval(it.value.intervalMillis) }
        state.update { it.copy(revealed = true, intervals = intervals) }
    }

    fun answer(rating: Rating) {
        val s = state.value
        val card = s.current ?: return
        // Guard against double taps: the second tap must not answer the same card again or skip the next one.
        if (s.answering || !s.revealed) return
        state.update { it.copy(answering = true) }
        viewModelScope.launch {
            var requeue: CardDetail? = null
            if (!free) {
                val outcome = c.study.answer(card.card.id, rating, durationMs = System.currentTimeMillis() - s.shownAt)
                // Learning/relearning steps (1-10 min) come back in the same session, like Anki's learn-ahead.
                if (outcome != null && outcome.intervalMillis <= LEARN_AHEAD_MS) requeue = c.db.cardDao().detail(card.card.id)
            }
            state.update {
                it.copy(queue = if (requeue != null) it.queue + requeue else it.queue, index = it.index + 1, revealed = false,
                    reviewed = it.reviewed + 1, again = it.again + if (rating == Rating.AGAIN) 1 else 0, notice = null,
                    shownAt = System.currentTimeMillis(), answering = false)
            }
            loadExamples()
        }
    }

    companion object { const val LEARN_AHEAD_MS = 20 * 60_000L }

    fun next() = viewModelScope.launch {
        state.update { it.copy(index = it.index + 1, revealed = false, notice = null) }
        loadExamples()
    }

    fun cantUse() = viewModelScope.launch {
        val card = state.value.current ?: return@launch
        c.active.flagCantUse(card.card.id)
        state.update { it.copy(notice = "\"${card.lemma}\" aktif üretim listene eklendi: önce Türkçeden hatırlama, sonra boşluk doldurma, ipucusuz cümle ve konuşma. Şimdi kartı tanıma açısından değerlendir.") }
    }
}

@Composable
fun rememberTts(): Tts {
    val ctx = LocalContext.current
    val tts = remember { Tts(ctx) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    return tts
}

@Composable
fun ReviewScreen(container: AppContainer, nav: NavController, free: Boolean, deckId: Long?) {
    val vm: ReviewViewModel = viewModel(key = "review_${free}_$deckId", factory = viewModelFactory { initializer { ReviewViewModel(container, free, deckId) } })
    val s by vm.state.collectAsState()
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(if (free) "Flashcards" else "FSRS Tekrarı", onBack = { nav.popBackStack() })
        when {
            s.loading -> LoadingBox()
            s.queue.isEmpty() -> EmptyState(
                if (free) "Henüz kart yok. Bir deste ekle veya içe aktar." else "Şu an zamanı gelen kart yok. 🎉 Aktif kullanım alıştırmalarıyla devam edebilirsin.",
                "Aktif üretim", onAction = { nav.navigate(Routes.drill(com.lexiflow.data.repo.StudyMode.ACTIVE_PATH)) },
            )
            s.done -> EmptyState("Oturum bitti: ${s.reviewed} kart${if (!free) ", ${s.again} kez 'Again'" else ""}. Unutmak öğrenmenin normal parçası.", "Ana sayfa") { nav.popBackStack() }
            else -> {
                val card = s.current!!
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(progress = { s.index.toFloat() / s.queue.size }, modifier = Modifier.fillMaxWidth())
                    Text("${s.index + 1} / ${s.queue.size} · ${card.deckName}", style = MaterialTheme.typography.labelMedium)
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(card.lemma, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).testTag("review_word"))
                            IconButton(onClick = { tts.speak(card.lemma, uk) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Telaffuzu dinle") }
                        }
                        Text(listOfNotNull(card.pos, if (uk) card.ipaUk ?: card.ipaUs else card.ipaUs ?: card.ipaUk, card.cefr).joinToString(" · "))
                        if (card.senseLabel.isNotBlank() && card.senseLabel != card.meaningTr) Text("Anlam: ${card.senseLabel}", style = MaterialTheme.typography.bodySmall)
                        s.examples.firstOrNull()?.let { Text("“${it.en}”", style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (!s.revealed) {
                        Button(onClick = { vm.reveal() }, modifier = Modifier.fillMaxWidth().testTag("reveal")) { Text("Cevabı göster") }
                    } else {
                        WordBackSide(card, s.examples, tts, uk)
                        s.notice?.let { NoticeBanner(it) }
                        OutlinedButton(onClick = { vm.cantUse() }, modifier = Modifier.fillMaxWidth().testTag("cant_use")) { Text("Tanıyorum ama kullanamıyorum") }
                        if (free) {
                            Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth()) { Text("Sonraki") }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                RatingButton("Again", s.intervals[Rating.AGAIN], Danger, Modifier.weight(1f).testTag("rate_again")) { vm.answer(Rating.AGAIN) }
                                RatingButton("Hard", s.intervals[Rating.HARD], Warning, Modifier.weight(1f).testTag("rate_hard")) { vm.answer(Rating.HARD) }
                                RatingButton("Good", s.intervals[Rating.GOOD], Success, Modifier.weight(1f).testTag("rate_good")) { vm.answer(Rating.GOOD) }
                                RatingButton("Easy", s.intervals[Rating.EASY], MaterialTheme.colorScheme.primary, Modifier.weight(1f).testTag("rate_easy")) { vm.answer(Rating.EASY) }
                            }
                            Text("Again: hatırlamadım · Hard: zorlandım · Good: hatırladım · Easy: çok kolaydı", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(label: String, interval: String?, color: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, fontWeight = FontWeight.Bold)
            if (interval != null) Text(interval, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordBackSide(card: CardDetail, examples: List<ExampleEntity>, tts: Tts, uk: Boolean) {
    val rich = WordRepository.rich(card.richJson)
    SectionCard {
        Text(card.meaningTr, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("review_meaning"))
        card.definitionEn?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        examples.take(3).forEach { ex ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("• ${ex.en}")
                    ex.tr?.let { Text("  $it", style = MaterialTheme.typography.bodySmall) }
                }
                IconButton(onClick = { tts.speak(ex.en, uk) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Cümleyi dinle") }
            }
        }
        RichChips(rich)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RichChips(rich: RichInfo) {
    if (rich.collocations.isNotEmpty()) {
        Text("Collocations", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { rich.collocations.take(8).forEach { AssistChip(onClick = {}, label = { Text(it) }) } }
    }
    if (rich.phrasalVerbs.isNotEmpty()) Text("Phrasal verbs: " + rich.phrasalVerbs.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    if (rich.synonyms.isNotEmpty()) Text("Eş anlamlı: " + rich.synonyms.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    if (rich.antonyms.isNotEmpty()) Text("Zıt anlamlı: " + rich.antonyms.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    if (rich.wordFamily.isNotEmpty()) Text("Kelime ailesi: " + rich.wordFamily.joinToString(", "), style = MaterialTheme.typography.bodySmall)
    rich.grammarNoteTr?.let { Text("Gramer: $it", style = MaterialTheme.typography.bodySmall) }
    rich.commonMistakesTr.take(2).forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) }
    rich.confusedWith.take(2).forEach { Text("↔ ${it.word}: ${it.noteTr}", style = MaterialTheme.typography.bodySmall) }
}

/** Daily new words: meet today's new cards with their full rich card before they enter FSRS learning. */
@Composable
fun DailyWordsScreen(container: AppContainer, nav: NavController) {
    var cards by remember { mutableStateOf<List<CardDetail>?>(null) }
    var examples by remember { mutableStateOf<Map<Long, List<ExampleEntity>>>(emptyMap()) }
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    LaunchedEffect(Unit) {
        container.contentLoader.ensureSeeded()
        val n = container.settings.current().newCardsPerDay.coerceAtLeast(3)
        val list = container.db.cardDao().newCards(n)
        examples = list.associate { it.card.id to container.db.wordDao().examplesForWord(it.card.wordId).filter { e -> e.senseId == it.card.senseId } }
        cards = list
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Günlük Kelimeler", onBack = { nav.popBackStack() })
        val list = cards
        when {
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState("Yeni kelime kalmadı. İçe aktarabilir veya kendi kelimeni ekleyebilirsin.", "İçe aktar") { nav.navigate(Routes.IMPORT) }
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Bugün ${list.size} yeni kelime. İncele, sonra FSRS tekrarında ilk kez değerlendir.", style = MaterialTheme.typography.bodyMedium)
                list.forEach { c ->
                    Text(c.lemma, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    WordBackSide(c, examples[c.card.id].orEmpty(), tts, settings?.accent == Accent.UK)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { nav.navigate(Routes.review()) }, modifier = Modifier.fillMaxWidth()) { Text("Bu kelimelerle tekrara başla") }
            }
        }
    }
}
