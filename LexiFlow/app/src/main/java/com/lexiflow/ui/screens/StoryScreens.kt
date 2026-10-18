package com.lexiflow.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.ai.QuestionType
import com.lexiflow.core.exercise.AnswerChecker
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Verdict
import com.lexiflow.core.language.Evaluation
import com.lexiflow.core.language.EvaluationTask
import com.lexiflow.core.language.Morphology
import com.lexiflow.core.language.SentenceEvaluator
import com.lexiflow.core.language.TargetWord
import com.lexiflow.core.story.StoryCandidate
import com.lexiflow.core.story.StoryPlanner
import com.lexiflow.data.repo.LoadedStory
import com.lexiflow.data.repo.StoryOptions
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.EvaluationView
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.components.onMain
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoriesScreen(container: AppContainer, nav: NavController, forgettingCurve: Boolean) {
    val scope = rememberCoroutineScope()
    val stories by remember { container.stories.observeStories() }.collectAsState(initial = emptyList())
    val settings by container.settings.settings.collectAsState(initial = null)
    var theme by remember { mutableStateOf("Daily life") }
    var count by remember { mutableStateOf(5f) }
    var length by remember { mutableStateOf(180f) }
    var cefr by remember(settings?.cefr) { mutableStateOf(settings?.cefr ?: "B1") }
    var grammar by remember { mutableStateOf<String?>(null) }
    var difficulty by remember { mutableStateOf("normal") }
    var fc by remember { mutableStateOf(forgettingCurve) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<StoryCandidate>>(emptyList()) }
    LaunchedEffect(fc, count) {
        val all = container.stories.candidates()
        preview = if (fc) StoryPlanner.select(all, count.roundToInt(), System.currentTimeMillis()) else emptyList()
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(if (forgettingCurve) "Unutma Riski Hikâyeleri" else "AI Hikâyeleri", onBack = { nav.popBackStack() })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("stories"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (settings?.aiAvailable != true) NoticeBanner("AI bağlantısı yok: çevrim dışı \"şablon hikâye\" oluşturulur (kartlarındaki insan yazımı örnek cümlelerle). Özgün AI hikâyeleri için Ayarlar'dan sunucu bağla.")
            SectionCard("Yeni hikâye") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Unutma eğrisine göre kelime seç", Modifier.weight(1f))
                    Switch(checked = fc, onCheckedChange = { fc = it })
                }
                if (fc) {
                    Text("FSRS'nin tahmin ettiği hatırlama olasılığı (R) düşen, tekrarı geciken, aktif kullanımı zayıf veya \"kullanamıyorum\" işaretli kelimeler seçilir. Son 6 saatte tekrar edilenler atlanır. Hikâye okumak FSRS tekrarı sayılmaz; soruların sonucu aktif beceri puanına işlenir.", style = MaterialTheme.typography.bodySmall)
                    preview.forEach { Text("• ${it.lemma} — R=%${((it.retrievability ?: 0.0) * 100).roundToInt()} · aktif %${(it.productionMastery * 100).roundToInt()}", style = MaterialTheme.typography.bodySmall) }
                    if (preview.isEmpty()) Text("Henüz tekrar edilmiş kelime yok. Önce FSRS tekrarı yap.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Tema")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { container.stories.themes.forEach { t -> FilterChip(theme == t, { theme = t }, { Text(t) }) } }
                Text("Hedef kelime sayısı: ${count.roundToInt()}")
                Slider(count, { count = it }, valueRange = 2f..8f, steps = 5)
                Text("Uzunluk: ~${length.roundToInt()} kelime")
                Slider(length, { length = it }, valueRange = 80f..400f)
                Text("Seviye")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("A2", "B1", "B2", "C1").forEach { l -> FilterChip(cefr == l, { cefr = l }, { Text(l) }) } }
                Text("Gramer odağı (isteğe bağlı)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Past simple", "Present perfect", "Conditionals", "Passive voice", "Reported speech").forEach { g -> FilterChip(grammar == g, { grammar = if (grammar == g) null else g }, { Text(g) }) }
                }
                Text("Zorluk")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("easy", "normal", "challenging").forEach { d -> FilterChip(difficulty == d, { difficulty = d }, { Text(d) }) } }
                message?.let { NoticeBanner(it, warning = true) }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("story_generate"), onClick = {
                    scope.launch {
                        busy = true; message = null
                        try {
                            val g = container.stories.generate(StoryOptions(theme, count.roundToInt(), length.roundToInt(), cefr, grammar, difficulty, fc))
                            g.noticeTr?.let { message = it }
                            onMain { nav.navigate(Routes.story(g.id)) }
                        } catch (e: Exception) { message = e.message }
                        busy = false
                    }
                }) { Text(if (busy) "Hikâye hazırlanıyor…" else "Hikâye oluştur") }
            }
            SectionCard("Hikâyelerim") {
                if (stories.isEmpty()) Text("Henüz hikâye yok.")
                stories.forEach { s ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.story(s.id)) }.padding(vertical = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontWeight = FontWeight.SemiBold)
                            Text("${s.theme} · ${s.cefr} · ${if (s.origin == "AI") "AI" else "Şablon"}${if (s.forgettingCurve) " · unutma eğrisi" else ""}${s.bestScore?.let { " · en iyi %$it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (s.completedAt != null) "✓" else "→")
                    }
                }
            }
        }
    }
}

@Composable
fun StoryReaderScreen(container: AppContainer, nav: NavController, storyId: Long) {
    val scope = rememberCoroutineScope()
    var story by remember { mutableStateOf<LoadedStory?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var qIndex by remember { mutableIntStateOf(-1) }
    var answer by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackOk by remember { mutableStateOf(false) }
    var eval by remember { mutableStateOf<Evaluation?>(null) }
    var correct by remember { mutableIntStateOf(0) }
    val answered = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(storyId) { story = container.stories.load(storyId); loaded = true }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(story?.story?.title ?: "Hikâye", onBack = { nav.popBackStack() })
        val st = story
        if (!loaded) { LoadingBox(); return@Column }
        if (st == null) { EmptyState("Hikâye açılamadı."); return@Column }
        val lemmas = st.words.map { it.lemma }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag("story_reader"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.entity.origin != "AI") NoticeBanner("Çevrim dışı şablon hikâye: senin kartlarındaki örnek cümleler bir anlatı çerçevesine yerleştirildi.")
            SectionCard {
                Text(if (!revealed) "1) Önce bağlamdan anlamı tahmin et: hedef kelimeler gizli." else "Hedef kelimeler vurgulandı.", style = MaterialTheme.typography.labelLarge)
                st.story.paragraphs.forEach { p ->
                    if (!revealed) {
                        var masked = p
                        lemmas.forEach { masked = ClozeBuilder.maskLemma(masked, it) }
                        Text(masked, style = MaterialTheme.typography.bodyLarge)
                    } else Text(highlight(p, lemmas), style = MaterialTheme.typography.bodyLarge)
                }
                if (!revealed) OutlinedButton(onClick = { revealed = true }) { Text("2) Kelimeleri göster") }
            }
            if (revealed) {
                SectionCard("Kelimeler") {
                    st.words.forEach { w -> Text("• ${w.lemma} (${w.occurrences} kez)${if (w.senseHint.isNotBlank()) " — ${w.senseHint}" else ""}") }
                    st.story.grammarNoteTr?.let { Text("Gramer: $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (qIndex < 0) Button(onClick = { qIndex = 0 }, modifier = Modifier.fillMaxWidth().testTag("story_start_questions")) { Text("Sorulara başla (${st.story.questions.size})") }
            }
            val q = st.story.questions.getOrNull(qIndex)
            if (q != null) {
                SectionCard("Soru ${qIndex + 1}/${st.story.questions.size}") {
                    Text(q.prompt, fontWeight = FontWeight.SemiBold)
                    val done = answered.containsKey(qIndex)
                    when (q.type) {
                        QuestionType.MULTIPLE_CHOICE, QuestionType.MEANING_IN_CONTEXT -> q.options.forEachIndexed { i, o ->
                            OutlinedButton(enabled = !done, onClick = {
                                val ok = i == q.answerIndex
                                answered[qIndex] = ok; if (ok) correct++
                                feedbackOk = ok; feedback = if (ok) "Doğru!" else "Doğru cevap: ${q.options.getOrNull(q.answerIndex ?: -1)}"
                                scope.launch { container.stories.recordQuestion(st, q, if (ok) 1.0 else 0.0, null) }
                            }, modifier = Modifier.fillMaxWidth()) { Text(o) }
                        }
                        else -> {
                            OutlinedTextField(answer, { answer = it }, enabled = !done, label = { Text("Cevabın") }, modifier = Modifier.fillMaxWidth())
                            if (!done) Button(enabled = answer.isNotBlank(), onClick = {
                                val ok: Boolean
                                if (q.type == QuestionType.CLOZE) {
                                    val v = AnswerChecker.check(answer, q.answer ?: "", lemma = q.targetLemma).verdict
                                    ok = v == Verdict.CORRECT || v == Verdict.ALTERNATIVE
                                    feedback = if (ok) "Doğru!" else "Doğru cevap: ${q.answer}"
                                    eval = null
                                } else {
                                    val targets = if (q.type == QuestionType.RETELL) lemmas else listOfNotNull(q.targetLemma ?: q.answer)
                                    val e = SentenceEvaluator.evaluate(answer, EvaluationTask(targets.map { TargetWord(it) }, requireAllTargets = q.type != QuestionType.RETELL, minWords = if (q.type == QuestionType.RETELL) 15 else 5))
                                    eval = e
                                    val used = e.targetUsage.count { it.used }
                                    ok = used >= (if (q.type == QuestionType.RETELL) minOf(2, targets.size) else 1) && e.scores.grammar >= 60
                                    feedback = if (ok) "Güzel! Hedef kelimeleri kullandın." else "Hedef kelimeleri doğru biçimde kullanmayı dene."
                                }
                                feedbackOk = ok
                                answered[qIndex] = ok; if (ok) correct++
                                scope.launch { container.stories.recordQuestion(st, q, if (ok) 1.0 else 0.3, answer) }
                            }) { Text("Kontrol et") }
                        }
                    }
                    feedback?.takeIf { done }?.let { Text(it, color = if (feedbackOk) Success else Danger, fontWeight = FontWeight.Bold) }
                }
                eval?.takeIf { answered.containsKey(qIndex) }?.let { EvaluationView(it, showScores = false) }
                if (answered.containsKey(qIndex)) {
                    Button(onClick = {
                        answer = ""; feedback = null; eval = null
                        if (qIndex + 1 >= st.story.questions.size) {
                            scope.launch { container.stories.finish(storyId, correct, st.story.questions.size) }
                            qIndex = st.story.questions.size
                        } else qIndex++
                    }, modifier = Modifier.testTag("story_next")) { Text("Sonraki") }
                }
            }
            if (qIndex >= st.story.questions.size && st.story.questions.isNotEmpty()) {
                SectionCard("Hikâye tamamlandı") {
                    Text("$correct / ${st.story.questions.size} doğru. Sonuçlar ilgili kelimelerin aktif kullanım puanına işlendi; FSRS tekrar planı değişmedi.")
                    TextButton(onClick = { nav.popBackStack() }) { Text("Hikâyelere dön") }
                }
            }
        }
    }
}

private fun highlight(text: String, lemmas: List<String>) = buildAnnotatedString {
    val forms = lemmas.flatMap { Morphology.forms(it) }.toSet()
    Regex("[A-Za-z']+|[^A-Za-z']+").findAll(text).forEach { m ->
        if (m.value.lowercase() in forms) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color(0xFF4F46E5))) { append(m.value) }
        else append(m.value)
    }
}
