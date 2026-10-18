package com.lexiflow.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.components.StatTile

/** One learning mode in the catalogue: every entry opens a real, working screen. */
data class ModeEntry(val title: String, val goal: String, val route: String, val group: String)

val MODE_CATALOG: List<ModeEntry> = listOf(
    ModeEntry("Flashcards", "Kartları serbestçe çevir; zamanlamayı etkilemez.", Routes.review(free = true), "Kelime ve tekrar"),
    ModeEntry("FSRS Tekrarı", "Zamanı gelen kartlar: Again / Hard / Good / Easy.", Routes.review(), "Kelime ve tekrar"),
    ModeEntry("Günlük Kelimeler", "Bugünün yeni kelimelerini zengin kartlarıyla tanı.", Routes.DAILY_WORDS, "Kelime ve tekrar"),
    ModeEntry("Karışık Tekrar", StudyMode.MIXED_REVIEW.goalTr, Routes.drill(StudyMode.MIXED_REVIEW), "Kelime ve tekrar"),
    ModeEntry("Kişisel Kelime Defteri", "Kendi eklediğin kelimeler ve cümleler.", Routes.WORDBOOK, "Kelime ve tekrar"),
    ModeEntry("Kilit Ekranı Soruları", "Telefonu açınca kısa sorular; ayarlar ve geçmiş.", Routes.LOCKSCREEN, "Kelime ve tekrar"),
    ModeEntry("Türkçeden İngilizceye", StudyMode.TR_TO_EN.goalTr, Routes.drill(StudyMode.TR_TO_EN), "Aktif kullanım"),
    ModeEntry("İngilizceden Türkçeye", StudyMode.EN_TO_TR.goalTr, Routes.drill(StudyMode.EN_TO_TR), "Aktif kullanım"),
    ModeEntry("Boşluk Doldurma", StudyMode.CLOZE.goalTr, Routes.drill(StudyMode.CLOZE), "Aktif kullanım"),
    ModeEntry("Cümle Oluşturma", StudyMode.SENTENCE_BUILDER.goalTr, Routes.drill(StudyMode.SENTENCE_BUILDER), "Aktif kullanım"),
    ModeEntry("Kelime Üretimi", StudyMode.WORD_PRODUCTION.goalTr, Routes.drill(StudyMode.WORD_PRODUCTION), "Aktif kullanım"),
    ModeEntry("Çeviri Pratiği", StudyMode.TRANSLATION.goalTr, Routes.drill(StudyMode.TRANSLATION), "Aktif kullanım"),
    ModeEntry("Bağlam Meydan Okuması", StudyMode.CONTEXT_CHALLENGE.goalTr, Routes.drill(StudyMode.CONTEXT_CHALLENGE), "Aktif kullanım"),
    ModeEntry("Aktif Üretim Yolu", StudyMode.ACTIVE_PATH.goalTr, Routes.drill(StudyMode.ACTIVE_PATH), "Aktif kullanım"),
    ModeEntry("Zayıf Kelimeler", StudyMode.WEAK_WORDS.goalTr, Routes.drill(StudyMode.WEAK_WORDS), "Aktif kullanım"),
    ModeEntry("Gramer", StudyMode.GRAMMAR.goalTr, Routes.drill(StudyMode.GRAMMAR), "Aktif kullanım"),
    ModeEntry("Film / Dizi Bağlamı", "Kamu malı filmlerin anlatımı ve özgün sahnelerle bağlam.", Routes.culture("SCREEN"), "Bağlam ve hikâye"),
    ModeEntry("Müzik Bağlamı", "Kamu malı şarkılar ve özgün dizelerle kelimeler.", Routes.culture("MUSIC"), "Bağlam ve hikâye"),
    ModeEntry("AI Hikâyeleri", "Seçtiğin tema ve seviyede hikâye + sorular.", Routes.stories(fc = false), "Bağlam ve hikâye"),
    ModeEntry("Unutma Riski Hikâyeleri", "Unutulmak üzere olan kelimelerle kişisel hikâye.", Routes.stories(fc = true), "Bağlam ve hikâye"),
    ModeEntry("AI Speaking", "Sesli konuşma koçu; hedef kelimelerini kullanır.", Routes.speaking(true), "Konuşma ve telaffuz"),
    ModeEntry("Yazılı Speaking", "Aynı koçla yazarak konuş; aynı değerlendirme.", Routes.speaking(false), "Konuşma ve telaffuz"),
    ModeEntry("Telaffuz Laboratuvarı", "Kelime/cümle telaffuzu ve minimal çiftler.", Routes.PRONUNCIATION, "Konuşma ve telaffuz"),
    ModeEntry("Dinleme", StudyMode.LISTENING.goalTr, Routes.drill(StudyMode.LISTENING), "Konuşma ve telaffuz"),
    ModeEntry("Shadowing", "Dinle, hemen tekrar et; akıcılık ve anlaşılırlık.", Routes.SHADOWING, "Konuşma ve telaffuz"),
    ModeEntry("Dikte", StudyMode.DICTATION.goalTr, Routes.drill(StudyMode.DICTATION), "Konuşma ve telaffuz"),
    ModeEntry("Online Kelime Yarışması", "5 kelimeyle en iyi cümle; bot antrenmanı da var.", Routes.BATTLE, "Yarışma ve görevler"),
    ModeEntry("Günlük Görev", StudyMode.DAILY_CHALLENGE.goalTr, Routes.drill(StudyMode.DAILY_CHALLENGE), "Yarışma ve görevler"),
    ModeEntry("Sınav Pratiği", StudyMode.EXAM.goalTr, Routes.drill(StudyMode.EXAM), "Yarışma ve görevler"),
    ModeEntry("İlerleme Paneli", "Seri, XP, beceri grafikleri, görevler ve rozetler.", Routes.PROGRESS, "Yarışma ve görevler"),
)

@Composable
fun HomeScreen(container: AppContainer, nav: NavController) {
    val progress by container.gamification.observeProgress().collectAsState(initial = null)
    val due by container.stats.dueCount().collectAsState(initial = 0)
    val newCount by container.stats.newCount().collectAsState(initial = 0)
    val flagged by container.active.observeFlagged().collectAsState(initial = emptyList())
    val challenge by container.sessions.observeDailyChallenge().collectAsState(initial = null)
    var recommendStory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.contentLoader.ensureSeeded()
        recommendStory = runCatching { container.stories.shouldRecommend() }.getOrDefault(false)
    }

    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("LexiFlow", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Tanıyorum → Hatırlıyorum → Doğal kullanıyorum", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            val p = progress
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Günlük seri", "${p?.streak?.current ?: 0} 🔥", Modifier.weight(1f), if (p?.streak?.activeToday == true) "Bugün tamam" else "Bugün hedefini tamamla")
                StatTile("Seviye ${p?.level?.level ?: 1}", p?.level?.titleTr ?: "", Modifier.weight(1f), "${p?.totalXp ?: 0} XP")
            }
        }
        item {
            val p = progress
            SectionCard("Bugünkü hedef") {
                val goal = p?.goalXp ?: 40
                val today = p?.todayXp ?: 0
                LinearProgressIndicator(progress = { (today.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
                Text("$today / $goal XP · XP; tekrar, aktif üretim, konuşma ve telaffuzdan gelir.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { nav.navigate(Routes.review()) }, modifier = Modifier.testTag("start_review")) { Text("Tekrara başla ($due)") }
                    Text("$newCount yeni kart", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (flagged.isNotEmpty()) item {
            SectionCard("Aktif üretim listen") {
                Text("${flagged.map { it.cardId }.toSet().size} kelimeyi tanıyorsun ama kullanmakta zorlanıyorsun. Adım adım aktif kullanıma taşıyalım.")
                OutlinedButton(onClick = { nav.navigate(Routes.drill(StudyMode.ACTIVE_PATH)) }) { Text("Aktif Üretim Yolu") }
            }
        }
        if (recommendStory) item {
            SectionCard("Unutma riski: hikâye önerisi") {
                Text("Bazı kelimelerinin hatırlanma olasılığı düşüyor. Onları kısa bir hikâyede yeniden görelim.")
                OutlinedButton(onClick = { nav.navigate(Routes.stories(true)) }) { Text("Hikâye oluştur") }
            }
        }
        item {
            SectionCard("Günlük görev") {
                val c = challenge
                Text(if (c?.completedAt != null) "Bugünün görevi tamamlandı ✓ (puan ${c.score})" else "5 kelime: hatırla, boşluk doldur, cümle kur. +30 XP")
                if (c?.completedAt == null) OutlinedButton(onClick = { nav.navigate(Routes.drill(StudyMode.DAILY_CHALLENGE)) }) { Text("Göreve başla") }
            }
        }
        val groups = MODE_CATALOG.groupBy { it.group }
        groups.forEach { (group, entries) ->
            item { Text(group, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(entries.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m -> ModeTile(m, Modifier.weight(1f)) { nav.navigate(m.route) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            TextButton(onClick = { nav.navigate(Routes.MODES) }) { Text("Tüm modların açıklamaları") }
        }
    }
}

@Composable
fun ModeTile(m: ModeEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick).testTag("mode_${m.title}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp).height(84.dp)) {
            Text(m.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(m.goal, style = MaterialTheme.typography.bodySmall, maxLines = 3)
        }
    }
}

@Composable
fun ModesScreen(nav: NavController) {
    Column {
        com.lexiflow.ui.components.LexiTopBar("Öğrenme modları", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MODE_CATALOG) { m ->
                SectionCard(m.title) {
                    Text(m.goal)
                    Text(m.group, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { nav.navigate(m.route) }) { Text("Aç") }
                }
            }
        }
    }
}
