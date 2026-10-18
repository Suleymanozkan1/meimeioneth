package com.lexiflow.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.GradientCard
import com.lexiflow.ui.components.IconBadge
import com.lexiflow.ui.components.StatTile
import com.lexiflow.ui.theme.BrandGradients
import com.lexiflow.ui.theme.Ink
import com.lexiflow.ui.theme.Violet

/** One learning mode in the catalogue: every entry opens a real, working screen. */
data class ModeEntry(val title: String, val goal: String, val route: String, val group: String, val icon: ImageVector)

private const val G_REVIEW = "Kelime ve tekrar"
private const val G_ACTIVE = "Aktif kullanım"
private const val G_CONTEXT = "Bağlam ve hikâye"
private const val G_SPEECH = "Konuşma ve telaffuz"
private const val G_GAME = "Yarışma ve görevler"

val MODE_CATALOG: List<ModeEntry> = listOf(
    ModeEntry("Flashcards", "Kartları serbestçe çevir; zamanlamayı etkilemez.", Routes.review(free = true), G_REVIEW, Icons.Default.Style),
    ModeEntry("FSRS Tekrarı", "Zamanı gelen kartlar: Again / Hard / Good / Easy.", Routes.review(), G_REVIEW, Icons.Default.Autorenew),
    ModeEntry("Günlük Kelimeler", "Bugünün yeni kelimelerini zengin kartlarıyla tanı.", Routes.DAILY_WORDS, G_REVIEW, Icons.Default.Today),
    ModeEntry("Karışık Tekrar", StudyMode.MIXED_REVIEW.goalTr, Routes.drill(StudyMode.MIXED_REVIEW), G_REVIEW, Icons.Default.Shuffle),
    ModeEntry("Kişisel Kelime Defteri", "Kendi eklediğin kelimeler ve cümleler.", Routes.WORDBOOK, G_REVIEW, Icons.Default.Book),
    ModeEntry("Kilit Ekranı Soruları", "Telefonu açınca kısa sorular; ayarlar ve geçmiş.", Routes.LOCKSCREEN, G_REVIEW, Icons.Default.Lock),
    ModeEntry("Türkçeden İngilizceye", StudyMode.TR_TO_EN.goalTr, Routes.drill(StudyMode.TR_TO_EN), G_ACTIVE, Icons.Default.Translate),
    ModeEntry("İngilizceden Türkçeye", StudyMode.EN_TO_TR.goalTr, Routes.drill(StudyMode.EN_TO_TR), G_ACTIVE, Icons.Default.SwapHoriz),
    ModeEntry("Boşluk Doldurma", StudyMode.CLOZE.goalTr, Routes.drill(StudyMode.CLOZE), G_ACTIVE, Icons.Default.EditNote),
    ModeEntry("Cümle Oluşturma", StudyMode.SENTENCE_BUILDER.goalTr, Routes.drill(StudyMode.SENTENCE_BUILDER), G_ACTIVE, Icons.Default.ViewWeek),
    ModeEntry("Kelime Üretimi", StudyMode.WORD_PRODUCTION.goalTr, Routes.drill(StudyMode.WORD_PRODUCTION), G_ACTIVE, Icons.Default.Create),
    ModeEntry("Çeviri Pratiği", StudyMode.TRANSLATION.goalTr, Routes.drill(StudyMode.TRANSLATION), G_ACTIVE, Icons.Default.Language),
    ModeEntry("Bağlam Meydan Okuması", StudyMode.CONTEXT_CHALLENGE.goalTr, Routes.drill(StudyMode.CONTEXT_CHALLENGE), G_ACTIVE, Icons.Default.Psychology),
    ModeEntry("Aktif Üretim Yolu", StudyMode.ACTIVE_PATH.goalTr, Routes.drill(StudyMode.ACTIVE_PATH), G_ACTIVE, Icons.Default.Route),
    ModeEntry("Zayıf Kelimeler", StudyMode.WEAK_WORDS.goalTr, Routes.drill(StudyMode.WEAK_WORDS), G_ACTIVE, Icons.Default.Healing),
    ModeEntry("Gramer", StudyMode.GRAMMAR.goalTr, Routes.drill(StudyMode.GRAMMAR), G_ACTIVE, Icons.Default.Spellcheck),
    ModeEntry("Film / Dizi Bağlamı", "Kamu malı filmlerin anlatımı ve özgün sahnelerle bağlam.", Routes.culture("SCREEN"), G_CONTEXT, Icons.Default.Movie),
    ModeEntry("Müzik Bağlamı", "Kamu malı şarkılar ve özgün dizelerle kelimeler.", Routes.culture("MUSIC"), G_CONTEXT, Icons.Default.MusicNote),
    ModeEntry("AI Hikâyeleri", "Seçtiğin tema ve seviyede hikâye + sorular.", Routes.stories(fc = false), G_CONTEXT, Icons.Default.AutoStories),
    ModeEntry("Unutma Riski Hikâyeleri", "Unutulmak üzere olan kelimelerle kişisel hikâye.", Routes.stories(fc = true), G_CONTEXT, Icons.Default.HourglassBottom),
    ModeEntry("AI Speaking", "Sesli konuşma koçu; hedef kelimelerini kullanır.", Routes.speaking(true), G_SPEECH, Icons.Default.RecordVoiceOver),
    ModeEntry("Yazılı Speaking", "Aynı koçla yazarak konuş; aynı değerlendirme.", Routes.speaking(false), G_SPEECH, Icons.Default.Keyboard),
    ModeEntry("Telaffuz Laboratuvarı", "Kelime/cümle telaffuzu ve minimal çiftler.", Routes.PRONUNCIATION, G_SPEECH, Icons.Default.Mic),
    ModeEntry("Dinleme", StudyMode.LISTENING.goalTr, Routes.drill(StudyMode.LISTENING), G_SPEECH, Icons.Default.Headphones),
    ModeEntry("Shadowing", "Dinle, hemen tekrar et; akıcılık ve anlaşılırlık.", Routes.SHADOWING, G_SPEECH, Icons.Default.Hearing),
    ModeEntry("Dikte", StudyMode.DICTATION.goalTr, Routes.drill(StudyMode.DICTATION), G_SPEECH, Icons.Default.Notes),
    ModeEntry("Online Kelime Yarışması", "5 kelimeyle en iyi cümle; bot antrenmanı da var.", Routes.BATTLE, G_GAME, Icons.Default.EmojiEvents),
    ModeEntry("Günlük Görev", StudyMode.DAILY_CHALLENGE.goalTr, Routes.drill(StudyMode.DAILY_CHALLENGE), G_GAME, Icons.Default.TaskAlt),
    ModeEntry("Sınav Pratiği", StudyMode.EXAM.goalTr, Routes.drill(StudyMode.EXAM), G_GAME, Icons.Default.Quiz),
    ModeEntry("İlerleme Paneli", "Seri, XP, beceri grafikleri, görevler ve rozetler.", Routes.PROGRESS, G_GAME, Icons.Default.Insights),
)

fun groupBrush(group: String): Brush = when (group) {
    G_REVIEW -> BrandGradients.violet
    G_ACTIVE -> BrandGradients.sunset
    G_CONTEXT -> BrandGradients.berry
    G_SPEECH -> BrandGradients.ocean
    else -> BrandGradients.fire
}

fun groupIcon(group: String): ImageVector = when (group) {
    G_REVIEW -> Icons.Default.Style
    G_ACTIVE -> Icons.Default.Bolt
    G_CONTEXT -> Icons.Default.AutoStories
    G_SPEECH -> Icons.Default.RecordVoiceOver
    else -> Icons.Default.EmojiEvents
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Günaydın"
    in 12..17 -> "İyi günler"
    in 18..22 -> "İyi akşamlar"
    else -> "İyi geceler"
}

@Composable
fun HomeScreen(container: AppContainer, nav: NavController) {
    val progress by remember { container.gamification.observeProgress() }.collectAsState(initial = null)
    val due by remember { container.stats.dueCount() }.collectAsState(initial = 0)
    val newCount by remember { container.stats.newCount() }.collectAsState(initial = 0)
    val flagged by remember { container.active.observeFlagged() }.collectAsState(initial = emptyList())
    val challenge by remember { container.sessions.observeDailyChallenge() }.collectAsState(initial = null)
    val settings by container.settings.settings.collectAsState(initial = null)
    var recommendStory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        container.contentLoader.ensureSeeded()
        recommendStory = runCatching { container.stories.shouldRecommend() }.getOrDefault(false)
    }
    val flaggedCount = flagged.map { it.cardId }.toSet().size

    LazyColumn(
        Modifier.fillMaxSize().testTag("home"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            val p = progress
            val goal = p?.goalXp ?: 40
            val today = p?.todayXp ?: 0
            GradientCard(BrandGradients.hero) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${greeting()}${settings?.displayName?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}!", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("Tanı → Hatırla → Doğal kullan", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f))
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HeroChip("🔥 ${p?.streak?.current ?: 0} gün")
                                HeroChip("⭐ Seviye ${p?.level?.level ?: 1}")
                            }
                        }
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { (today.toFloat() / goal).coerceIn(0f, 1f) },
                                modifier = Modifier.size(92.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                                strokeWidth = 9.dp, strokeCap = StrokeCap.Round,
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$today", style = MaterialTheme.typography.titleLarge, color = Color.White)
                                Text("/ $goal XP", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                            }
                        }
                    }
                    Button(
                        onClick = { nav.navigate(Routes.review()) },
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("start_review"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Violet),
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (due > 0) "Tekrara başla · $due kart" else "Yeni kelimelerle başla", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(if (p?.streak?.activeToday == true) "Bugünün hedefi tamam, harika gidiyorsun! 🎉" else "XP; tekrar, aktif üretim, konuşma ve telaffuzdan gelir.",
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Tekrar zamanı", "$due", Modifier.weight(1f), brush = BrandGradients.ocean, icon = Icons.Default.Autorenew)
                StatTile("Yeni kart", "$newCount", Modifier.weight(1f), brush = BrandGradients.sunset, icon = Icons.Default.FiberNew)
                StatTile("Aktif liste", "$flaggedCount", Modifier.weight(1f), brush = BrandGradients.berry, icon = Icons.Default.Bolt)
            }
        }
        if (flaggedCount > 0) item {
            HighlightCard(BrandGradients.berry, Icons.Default.Route, "Aktif üretim listen",
                "$flaggedCount kelimeyi tanıyorsun ama kullanmakta zorlanıyorsun. Adım adım aktif kullanıma taşıyalım.", "Aktif Üretim Yolu") {
                nav.navigate(Routes.drill(StudyMode.ACTIVE_PATH))
            }
        }
        if (recommendStory) item {
            HighlightCard(BrandGradients.sunset, Icons.Default.AutoStories, "Unutma riski: hikâye zamanı",
                "Bazı kelimelerinin hatırlanma olasılığı düşüyor. Onları kısa bir hikâyede yeniden görelim.", "Hikâye oluştur") {
                nav.navigate(Routes.stories(true))
            }
        }
        item {
            val c = challenge
            if (c?.completedAt != null) {
                HighlightCard(BrandGradients.mint, Icons.Default.TaskAlt, "Günlük görev tamamlandı ✓", "Puanın: ${c.score}. Yarın yeni 5 kelime seni bekliyor.", null) {}
            } else {
                HighlightCard(BrandGradients.fire, Icons.Default.TaskAlt, "Günlük görev", "5 kelime: hatırla, boşluk doldur, cümle kur. +30 XP", "Göreve başla") {
                    nav.navigate(Routes.drill(StudyMode.DAILY_CHALLENGE))
                }
            }
        }
        val groups = MODE_CATALOG.groupBy { it.group }
        groups.forEach { (group, entries) ->
            item(key = "h_$group") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    IconBadge(groupIcon(group), groupBrush(group), size = 32.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(group, style = MaterialTheme.typography.titleLarge)
                }
            }
            items(entries.chunked(2), key = { "${group}_${it.first().title}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                    row.forEach { m -> ModeTile(m, Modifier.weight(1f).fillMaxHeight()) { nav.navigate(m.route) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item {
            TextButton(onClick = { nav.navigate(Routes.MODES) }, modifier = Modifier.fillMaxWidth()) { Text("Tüm modların açıklamaları") }
        }
    }
}

@Composable
private fun HeroChip(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
fun HighlightCard(brush: Brush, icon: ImageVector, title: String, body: String, action: String?, onAction: () -> Unit) {
    GradientCard(brush, padding = 18.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
                if (action != null) {
                    Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) { Text(action) }
                }
            }
        }
    }
}

@Composable
fun ModeTile(m: ModeEntry, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 128.dp).testTag("mode_${m.title}"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBadge(m.icon, groupBrush(m.group), size = 40.dp)
            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(m.goal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ModesScreen(nav: NavController) {
    Column {
        com.lexiflow.ui.components.LexiTopBar("Öğrenme modları", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(MODE_CATALOG) { m ->
                Surface(onClick = { nav.navigate(m.route) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(m.icon, groupBrush(m.group))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.title, style = MaterialTheme.typography.titleSmall)
                            Text(m.goal, style = MaterialTheme.typography.bodySmall)
                            Text(m.group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}
