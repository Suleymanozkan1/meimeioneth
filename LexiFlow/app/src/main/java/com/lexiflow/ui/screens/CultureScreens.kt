package com.lexiflow.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.exercise.AnswerChecker
import com.lexiflow.core.exercise.ClozeBuilder
import com.lexiflow.core.exercise.Verdict
import com.lexiflow.core.language.Evaluation
import com.lexiflow.data.db.ContentSourceEntity
import com.lexiflow.data.db.CulturalContextEntity
import com.lexiflow.data.importer.readAtMost
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.EmptyState
import com.lexiflow.ui.components.EvaluationView
import com.lexiflow.ui.components.LexiTopBar
import com.lexiflow.ui.components.LoadingBox
import com.lexiflow.ui.components.NoticeBanner
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Success
import kotlinx.coroutines.launch

private val MUSIC_STYLES = listOf("song", "chorus", "verse", "ballad", "music", "rap", "soul", "hymn", "country", "pop", "folk")

private fun isMusic(source: ContentSourceEntity) = source.kind == "MUSIC" ||
    (source.kind == "ORIGINAL_SCENE" && MUSIC_STYLES.any { source.styleTag.orEmpty().lowercase().contains(it) })

fun licenseLabel(status: String) = when (status) {
    "PUBLIC_DOMAIN" -> "Kamu malı · kısa alıntı"
    "PARAPHRASE" -> "Kendi sözlerimizle anlatım (alıntı değil)"
    "ORIGINAL_LEXIFLOW" -> "LexiFlow özgün içerik (gerçek bir eserden değil)"
    "USER_PROVIDED_PERSONAL_USE" -> "Senin metnin · yalnızca kişisel kullanım"
    else -> status
}

@Composable
fun CultureScreen(container: AppContainer, nav: NavController, kind: String) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<Pair<CulturalContextEntity, ContentSourceEntity>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val music = kind == "MUSIC"
    LaunchedEffect(reload) {
        container.contentLoader.ensureSeeded()
        val all = container.culture.items(listOf("MUSIC", "FILM", "TV", "ORIGINAL_SCENE", "USER_TRANSCRIPT"))
        items = all.filter { (_, s) -> if (music) isMusic(s) else !isMusic(s) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            // Bounded read: only the first ~1 MB is used anyway (500k characters below).
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readAtMost(1_000_000).toString(Charsets.UTF_8) }
            }.getOrNull().orEmpty()
            val n = container.culture.importTranscript(title.ifBlank { uri.lastPathSegment ?: "Altyazı" }, "USER_TRANSCRIPT", text.take(500_000))
            notice = if (n == 0) "Metinde destelerindeki kelimelerden hiçbiri bulunamadı." else "$n bağlam cümlesi eklendi."
            reload++
        }
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(if (music) "Müzik Bağlamı" else "Film / Dizi Bağlamı", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("culture")) {
            item {
                NoticeBanner("Telif kuralları: Modern şarkı sözü veya film/dizi repliği kullanılmaz. İçerikler kamu malı kısa alıntılar, kamu malı filmlerin kendi sözlerimizle anlatımı veya açıkça etiketlenmiş LexiFlow özgün sahneleridir. Kendi altyazı/metinlerini yalnızca kişisel çalışma için ekleyebilirsin.")
            }
            notice?.let { item { NoticeBanner(it) } }
            val list = items
            if (list == null) item { LoadingBox() }
            else if (list.isEmpty()) item { EmptyState("Bu kategoride içerik yok.") }
            else items(list) { (c, s) ->
                SectionCard(s.title, Modifier.clickable { nav.navigate(Routes.cultureItem(c.id)) }) {
                    Text(listOfNotNull(s.styleTag, s.creator, s.year?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    AssistChip(onClick = {}, label = { Text(licenseLabel(s.licenseStatus)) })
                    Text("Hedef kelime: ${c.targetLemma}", fontWeight = FontWeight.Medium)
                }
            }
            if (!music) item {
                SectionCard("Kendi metnini ekle (altyazı .srt / .txt)") {
                    Text("Sahip olduğun veya kullanım iznin olan bir altyazıdan, destelerindeki kelimeleri içeren kısa cümleler çıkarılır. Metin cihazında kalır; paylaşılmaz.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it }, label = { Text("Eser adı (isteğe bağlı)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pasted, { pasted = it }, label = { Text("Metni yapıştır") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Button(enabled = pasted.isNotBlank(), onClick = {
                        scope.launch {
                            val n = container.culture.importTranscript(title, "USER_TRANSCRIPT", pasted.take(500_000))
                            notice = if (n == 0) "Metinde destelerindeki kelimelerden hiçbiri bulunamadı." else "$n bağlam cümlesi eklendi."
                            pasted = ""; reload++
                        }
                    }) { Text("Metinden bağlam çıkar") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/x-subrip", "*/*")) }) { Text(".srt / .txt dosyası seç") }
                }
            }
        }
    }
}

@Composable
fun CultureItemScreen(container: AppContainer, nav: NavController, contextId: Long) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Pair<CulturalContextEntity, ContentSourceEntity>?>(null) }
    var guess by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var sentence by remember { mutableStateOf("") }
    var eval by remember { mutableStateOf<Evaluation?>(null) }
    var hasCard by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(contextId) {
        val c = container.db.contentDao().context(contextId)
        val s = c?.let { container.db.contentDao().source(it.sourceId) }
        if (c != null && s != null) { data = c to s; hasCard = container.culture.cardFor(c) != null; container.culture.markShown(c.id) }
    }
    Column(Modifier.fillMaxSize()) {
        LexiTopBar(data?.second?.title ?: "Bağlam", onBack = { nav.popBackStack() })
        val (c, s) = data ?: run { LoadingBox(); return@Column }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Kaynak") {
                Text(listOfNotNull(s.title, s.creator, s.year?.toString(), c.location).joinToString(" · "))
                AssistChip(onClick = {}, label = { Text(licenseLabel(s.licenseStatus)) })
                Text(s.licenseNote, style = MaterialTheme.typography.bodySmall)
                s.sourceUrl?.let { Text("Kaynak: $it", style = MaterialTheme.typography.bodySmall) }
            }
            SectionCard(if (c.isQuote) "Alıntı" else "Sahne") {
                Text(if (verdict == null) ClozeBuilder.build(c.excerpt, c.answer)?.prompt ?: c.excerpt else c.excerpt, style = MaterialTheme.typography.titleMedium)
                if (verdict == null) {
                    Text("1) Boşluğa hangi kelime geliyor? Bağlamdan tahmin et.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(guess, { guess = it }, label = { Text("Kelime") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("culture_guess"))
                    Button(enabled = guess.isNotBlank(), onClick = {
                        val v = AnswerChecker.check(guess, c.answer, lemma = c.targetLemma).verdict
                        verdict = v
                        scope.launch { container.culture.recordCloze(c, v == Verdict.CORRECT || v == Verdict.ALTERNATIVE || v == Verdict.WRONG_FORM) }
                    }) { Text("Kontrol et") }
                }
            }
            verdict?.let { v ->
                SectionCard("Açıklama") {
                    val ok = v == Verdict.CORRECT || v == Verdict.ALTERNATIVE || v == Verdict.WRONG_FORM
                    Text(if (ok) "✓ ${c.answer}" else "Doğru cevap: ${c.answer}", color = if (ok) Success else Danger, fontWeight = FontWeight.Bold)
                    Text("Bağlamdaki anlamı: ${c.meaningInContextTr}")
                    c.tone?.let { Text("Ton: $it") }
                    c.explanationTr?.let { Text(it) }
                    c.grammarNoteTr?.let { Text("Gramer: $it", style = MaterialTheme.typography.bodySmall) }
                }
                SectionCard("2) Kendi cümleni kur") {
                    Text(c.productionPromptTr ?: "\"${c.targetLemma}\" kelimesini kendi cümlende kullan.")
                    OutlinedTextField(sentence, { sentence = it }, label = { Text("Cümlen") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = sentence.isNotBlank(), onClick = { scope.launch { eval = container.culture.recordProduction(c, sentence) } }) { Text("Değerlendir") }
                }
                eval?.let { EvaluationView(it) }
                if (!hasCard) {
                    OutlinedButton(onClick = { scope.launch { container.culture.addToWordbook(c); hasCard = true; notice = "\"${c.targetLemma}\" kelime defterine eklendi; FSRS tekrarlarına girecek." } }) { Text("Kelimeyi defterime ekle") }
                }
                notice?.let { NoticeBanner(it) }
            }
        }
    }
}
