package com.lexiflow.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.pronunciation.PronunciationResult
import com.lexiflow.core.pronunciation.PronunciationScorer
import com.lexiflow.core.pronunciation.Reliability
import com.lexiflow.core.pronunciation.WordStatus
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.repo.TurnFeedback
import com.lexiflow.data.settings.Accent
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.components.EvaluationView
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.MicButton
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.ScoreBar
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import com.lexiflow.ui.theme.Warning
import kotlinx.coroutines.launch
import kotlin.random.Random

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeakingScreen(container: AppContainer, nav: NavController, voiceMode: Boolean) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    var topic by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableLongStateOf(-1L) }
    val turns by remember(sessionId) { container.speaking.observeTurns(sessionId) }.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var voiceMs by remember { mutableLongStateOf(0L) }
    var usedVoice by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var targets by remember { mutableStateOf<List<CardDetail>>(emptyList()) }
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    LaunchedEffect(Unit) { targets = container.speaking.targetWords() }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(if (voiceMode) "AI Speaking" else "Yazılı Speaking", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("speaking"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (settings?.aiAvailable != true) NoticeBanner("Çevrim dışı koç: sorular ve geri bildirim cihazdaki kurallarla üretilir (niyet özeti ve serbest sohbet için sunucu gerekir).")
            if (voiceMode) Text("Sesli cevaplar Android konuşma tanıma hizmetiyle metne çevrilir; LexiFlow ham ses saklamaz. Metin geçmişi ${settings?.keepSpeakingDays ?: 30} gün tutulur (Ayarlar'dan değiştir/sil).", style = MaterialTheme.typography.bodySmall)
            if (targets.isNotEmpty()) {
                Text("Bu konuşmada doğal gelirse kullan:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { targets.forEach { AssistChip(onClick = {}, label = { Text(it.lemma) }) } }
            }
            if (sessionId < 0) {
                Text("Konu seç", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    container.speaking.topics.forEach { t -> FilterChip(topic == t, { topic = t }, { Text(t) }) }
                }
                Button(enabled = topic != null, onClick = {
                    scope.launch { val (id, opener) = container.speaking.start(topic!!); sessionId = id; if (voiceMode) tts.speak(opener.lineSequence().first(), uk) }
                }, modifier = Modifier.testTag("speaking_start")) { Text("Konuşmayı başlat") }
            } else {
                turns.forEach { t ->
                    val coach = t.role == "coach"
                    Card(colors = CardDefaults.cardColors(containerColor = if (coach) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(if (coach) "Koç" else "Sen${if (t.inputMode == "VOICE") " 🎤" else ""}", style = MaterialTheme.typography.labelMedium)
                            Text(t.text)
                            if (coach) OutlinedButton(onClick = { tts.speak(t.text.lineSequence().first(), uk) }) { Text("🔊") }
                        }
                    }
                    if (!coach) container.speaking.decodeFeedback(t.feedbackJson)?.let { TurnFeedbackView(it) }
                }
                notice?.let { NoticeBanner(it, warning = true) }
                if (voiceMode) {
                    MicButton("Konuş", languageTag = if (uk) "en-GB" else "en-US", preferOffline = settings?.preferOfflineSpeech ?: true) { hyps, audio ->
                        input = hyps.firstOrNull()?.text.orEmpty(); voiceMs = audio.durationMs; usedVoice = true
                    }
                }
                OutlinedTextField(input, { input = it; if (!voiceMode) usedVoice = false }, label = { Text(if (voiceMode) "Tanınan metin (düzeltebilirsin)" else "Cevabını yaz") },
                    modifier = Modifier.fillMaxWidth().testTag("speaking_input"), minLines = 2)
                Button(enabled = input.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        val reply = container.speaking.answer(sessionId, input, usedVoice, voiceMs, topic ?: "Daily life")
                        notice = reply.noticeTr
                        if (voiceMode) tts.speak(reply.coachText, uk)
                        input = ""; usedVoice = false; voiceMs = 0; busy = false
                    }
                }, modifier = Modifier.testTag("speaking_send")) { Text(if (busy) "Değerlendiriliyor…" else "Gönder") }
            }
        }
    }
}

@Composable
private fun TurnFeedbackView(f: TurnFeedback) {
    SectionCard("Geri bildirim${if (f.engine == "AI") " (AI)" else " (kurallar)"}") {
        f.ai?.let { ai ->
            Text("Ne demek istedin: ${ai.intendedMeaningTr}", fontWeight = FontWeight.Medium)
            ai.corrections.take(3).forEach { c -> Text("✗ ${c.original} → ✓ ${c.corrected}\n${c.explanationTr}", style = MaterialTheme.typography.bodySmall) }
            ai.naturalAlternative?.let { Text("Daha doğal: $it") }
        }
        ScoreBar("Gramer", f.evaluation.scores.grammar)
        ScoreBar("Kelime seçimi", f.evaluation.scores.wordUsage)
        ScoreBar("Doğallık", f.evaluation.scores.naturalness)
        ScoreBar("Anlam açıklığı (sezgisel)", f.clarity)
        Text("Hedef kelimeler: ${if (f.targetsUsed.isEmpty()) "kullanılmadı" else f.targetsUsed.joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (f.ai == null) {
            f.evaluation.keyPoints.take(3).forEach { com.lexiflow.ui.components.IssueCard(it) }
            if (f.evaluation.corrected != f.evaluation.text && f.evaluation.keyPoints.any { it.suggestion != null }) Text("Önerilen: ${f.evaluation.corrected}")
        }
    }
}

@Composable
fun PronunciationResultView(result: PronunciationResult) {
    SectionCard("Sonuç · ${result.reliability.labelTr}") {
        if (result.reliability == Reliability.UNUSABLE) {
            Text("Kesin puan verilmedi.", color = Danger)
        } else {
            result.intelligibility?.let { ScoreBar("Anlaşılırlık (tanıyıcının anladığı kelimeler)", it) }
            if (result.reliability == Reliability.LOW) Text("Güven düşük: puanı yaklaşık kabul et.", color = Warning)
        }
        result.words.forEach { w ->
            val color = when (w.status) { WordStatus.RECOGNIZED -> Success; WordStatus.UNCERTAIN -> Warning; else -> Danger }
            Text("${w.target} — ${w.status.labelTr}${w.heard?.takeIf { it != w.target }?.let { " (duyulan: $it)" } ?: ""}", color = color, fontWeight = FontWeight.Medium)
            w.tipTr?.let { Text("   $it", style = MaterialTheme.typography.bodySmall) }
        }
        result.fluencyTr?.let { Text(it) }
        result.notesTr.forEach { Text("ℹ $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PronunciationScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    var tab by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<List<CardDetail>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var sentenceMode by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PronunciationResult?>(null) }
    var pair by remember { mutableStateOf(PronunciationScorer.MINIMAL_PAIRS.first()) }
    var played by remember { mutableStateOf<String?>(null) }
    var pairFeedback by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        container.contentLoader.ensureSeeded()
        cards = container.db.cardDao().randomStudied(15).ifEmpty { container.db.cardDao().randomAny(15) }
    }
    LaunchedEffect(cards, index, sentenceMode) {
        val c = cards.getOrNull(index) ?: return@LaunchedEffect
        target = if (sentenceMode) container.db.wordDao().examplesForWord(c.card.wordId).randomOrNull()?.en ?: c.lemma else c.lemma
        result = null
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Telaffuz Laboratuvarı", onBack = { nav.popBackStack() })
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Kelime / cümle") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Minimal çiftler") })
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("pronunciation"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoticeBanner("Dürüst kapsam: konuşma tanıma (ASR) bir kelimenin ANLAŞILIP anlaşılmadığını söyler; fonem düzeyinde kesin puan değildir. Güven düşükse sonuç \"belirsiz\" gösterilir. Bilinen minimal çift karışıklıklarında (ship/sheep gibi) sese özel ipucu verilir. ELSA'nın özel modelleri kullanılmaz.")
            if (tab == 0) {
                val c = cards.getOrNull(index)
                if (c == null) Text("Kelime yükleniyor…") else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!sentenceMode, { sentenceMode = false }, { Text("Kelime") })
                        FilterChip(sentenceMode, { sentenceMode = true }, { Text("Cümle") })
                    }
                    SectionCard {
                        Text(target, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("pron_target"))
                        Text(listOfNotNull(if (uk) c.ipaUk else c.ipaUs, c.stress?.let { "Vurgu: $it" }).joinToString(" · "))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { tts.speak(target, uk) }) { Text("🔊 Dinle") }
                            OutlinedButton(onClick = { tts.speak(target, uk, slow = true) }) { Text("🐢 Yavaş") }
                        }
                    }
                    MicButton("Söyle", languageTag = if (uk) "en-GB" else "en-US", preferOffline = settings?.preferOfflineSpeech ?: true) { hyps, audio ->
                        scope.launch { result = container.pronunciation.score(target, hyps, audio, c.card.id, if (sentenceMode) "PRON_SENTENCE" else "PRON_WORD") }
                    }
                    result?.let { PronunciationResultView(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { result = null }) { Text("Tekrar dene") }
                        Button(onClick = { index = (index + 1) % cards.size.coerceAtLeast(1) }) { Text("Sonraki") }
                    }
                }
            } else {
                Text("Türkçe konuşanların zorlandığı ses çiftleri", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PronunciationScorer.MINIMAL_PAIRS.distinctBy { it.first + it.second }.take(16).forEach { p ->
                        FilterChip(pair == p, { pair = p; played = null; pairFeedback = null }, { Text("${p.first}/${p.second}") })
                    }
                }
                SectionCard("${pair.first} — ${pair.second}") {
                    Text(pair.third)
                    Text("1) Dinle: hangisini duydun?", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { val w = if (Random.nextBoolean()) pair.first else pair.second; played = w; pairFeedback = null; tts.speak(w, uk) }) { Text("🔊 Rastgele birini çal") }
                    if (played != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(pair.first, pair.second).forEach { w ->
                            Button(onClick = { pairFeedback = if (w == played) "✓ Doğru: \"$played\"" else "✗ Çalınan \"$played\" idi. Tekrar dinle." }) { Text(w) }
                        }
                    }
                    pairFeedback?.let { Text(it, fontWeight = FontWeight.Bold) }
                    Text("2) Söyle: ikisini de ayrı ayrı söyle.", style = MaterialTheme.typography.labelLarge)
                    listOf(pair.first, pair.second).forEach { w ->
                        MicButton("\"$w\" söyle", languageTag = if (uk) "en-GB" else "en-US") { hyps, audio ->
                            scope.launch { result = container.pronunciation.score(w, hyps, audio, null, "MINIMAL_PAIR") }
                        }
                    }
                }
                result?.let { PronunciationResultView(it) }
            }
        }
    }
}

@Composable
fun ShadowingScreen(container: AppContainer, nav: NavController) {
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsState(initial = null)
    val tts = rememberTts()
    val uk = settings?.accent == Accent.UK
    var items by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<PronunciationResult?>(null) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.contentLoader.ensureSeeded()
        val cards = container.db.cardDao().randomStudied(12).ifEmpty { container.db.cardDao().randomAny(12) }
        items = cards.mapNotNull { c -> container.db.wordDao().examplesForWord(c.card.wordId).filter { it.en.split(" ").size in 5..14 }.randomOrNull()?.let { c.card.id to it.en } }
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar("Shadowing", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("shadowing"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Cümleyi dinle, hemen ardından aynı ritimle tekrar et. Anlaşılırlık ve konuşma hızı ölçülür; vurgu/tonlama akustik olarak ölçülmez.", style = MaterialTheme.typography.bodySmall)
            val item = items.getOrNull(index)
            if (item == null) Text("Cümle hazırlanıyor… (önce birkaç kelimeyi tekrar etmen gerekebilir)") else {
                SectionCard {
                    Text(item.second, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { tts.speak(item.second, uk, slow) }) { Text("🔊 Dinle") }
                        FilterChip(slow, { slow = !slow }, { Text("Yavaş") })
                    }
                }
                MicButton("Tekrar et", languageTag = if (uk) "en-GB" else "en-US", preferOffline = settings?.preferOfflineSpeech ?: true) { hyps, audio ->
                    scope.launch { result = container.pronunciation.score(item.second, hyps, audio, item.first, "SHADOWING") }
                }
                result?.let { PronunciationResultView(it) }
                Button(onClick = { index = (index + 1) % items.size; result = null }) { Text("Sonraki cümle") }
            }
        }
    }
}
