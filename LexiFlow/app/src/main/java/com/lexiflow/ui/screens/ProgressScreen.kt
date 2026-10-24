package com.lexiflow.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.lexiflow.ui.components.GradientCard
import com.lexiflow.ui.components.GradientProgress
import com.lexiflow.ui.components.IconBadge
import com.lexiflow.ui.theme.BrandGradients
import com.lexiflow.ui.theme.Coral
import com.lexiflow.ui.theme.Danger
import com.lexiflow.ui.theme.Mint
import com.lexiflow.ui.theme.Pink
import com.lexiflow.ui.theme.Sky
import com.lexiflow.ui.theme.Sunshine
import com.lexiflow.ui.theme.Violet
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lexiflow.core.active.Skill
import com.lexiflow.core.gamification.MissionProgress
import com.lexiflow.data.repo.RiskItem
import com.lexiflow.data.repo.StudyMode
import com.lexiflow.data.repo.WeeklyStats
import com.lexiflow.di.AppContainer
import com.lexiflow.ui.Routes
import com.lexiflow.ui.components.DayBarChart
import com.lexiflow.ui.components.ScoreBar
import com.lexiflow.ui.components.SectionCard
import com.lexiflow.ui.components.StatTile
import com.lexiflow.ui.components.TwoLineChart
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(container: AppContainer, nav: NavController) {
    val progress by remember { container.gamification.observeProgress() }.collectAsState(initial = null)
    val reviews by remember { container.stats.reviewsPerDay() }.collectAsState(initial = emptyList())
    val xp by remember { container.stats.xpPerDay() }.collectAsState(initial = emptyList())
    val activePer by remember { container.stats.activePerDay() }.collectAsState(initial = emptyList())
    val skills by remember { container.stats.skillAverages() }.collectAsState(initial = emptyList())
    val achievements by remember { container.gamification.observeAchievements() }.collectAsState(initial = emptyList())
    val mature by remember { container.stats.matureCount() }.collectAsState(initial = 0)
    var weekly by remember { mutableStateOf<WeeklyStats?>(null) }
    var missions by remember { mutableStateOf<List<MissionProgress>>(emptyList()) }
    var risk by remember { mutableStateOf<List<RiskItem>>(emptyList()) }
    var topMistakes by remember { mutableStateOf<List<com.lexiflow.data.repo.FrequentMistake>?>(null) }
    var usage by remember { mutableStateOf<com.lexiflow.data.repo.ActiveUsage?>(null) }
    LaunchedEffect(Unit) {
        topMistakes = container.mistakes.topThisWeek(5)
        usage = container.stats.activeUsage(30)
        weekly = container.stats.weekly()
        missions = container.gamification.weeklyMissions()
        risk = container.stats.forgettingRisk()
    }
    val today = container.clock.today()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()).toString() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("progress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("İlerleme Paneli", style = MaterialTheme.typography.headlineMedium)
        val p = progress
        GradientCard(BrandGradients.hero) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Seviye ${p?.level?.level ?: 1}", style = MaterialTheme.typography.headlineMedium)
                val into = p?.level?.xpIntoLevel ?: 0
                val next = (p?.level?.xpForNextLevel ?: 50).coerceAtLeast(1)
                GradientProgress(into.toFloat() / next, brush = BrandGradients.fire)
                Text("$into / $next XP · bir sonraki seviyeye ${(next - into).coerceAtLeast(0)} XP", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            }
        }
        Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Seri", "${p?.streak?.current ?: 0} gün", Modifier.weight(1f).fillMaxHeight(), "Rekor: ${p?.streak?.longest ?: 0}", brush = BrandGradients.sunset, icon = Icons.Filled.LocalFireDepartment)
            StatTile("Olgun kart", "$mature", Modifier.weight(1f).fillMaxHeight(), "REVIEW durumunda", brush = BrandGradients.mint, icon = Icons.Filled.Verified)
        }
        usage?.let { u ->
            SectionCard("Aktif ve pasif kelimeler", accent = Mint, icon = Icons.Filled.Create, modifier = Modifier.testTag("active_passive")) {
                Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Aktif", "${u.active}", Modifier.weight(1f).fillMaxHeight().testTag("active_count"), "ipucusuz doğru kullandın", brush = BrandGradients.mint, icon = Icons.Filled.Create)
                    StatTile("Pasif", "${u.passive}", Modifier.weight(1f).fillMaxHeight().testTag("passive_count"), "tanıyorsun, henüz kullanmadın", brush = BrandGradients.violet, icon = Icons.Filled.Psychology)
                }
                Text("Aktif: Kelime Üretimi, Cümle Oluşturma veya Speaking'de ipucu almadan doğru kullanılan kelime. Bu hafta ${u.activatedThisWeek} kelime aktife geçti.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Son 30 gün", style = MaterialTheme.typography.labelLarge)
                TwoLineChart(u.series.map { it.localDate }, u.series.map { it.active }, u.series.map { it.passive }, "Aktif", "Pasif")
                if (u.passive > 0) TextButton(onClick = { nav.navigate(Routes.drill(StudyMode.ACTIVE_PATH)) }) { Text("Pasif kelimeleri aktife taşı") }
            }
        }
        Text("Seri: günlük XP hedefinin ya da minimum görevin (Otomatik Mod seansını, 5 dakikalık kısa seans dahil, bitirmek) tamamlandığı yerel günler. Gün bitmeden seri bozulmaz; saat dilimi değişse de geçmiş günler değişmez.", style = MaterialTheme.typography.bodySmall)
        SectionCard("XP (son 14 gün)", accent = Sunshine, icon = Icons.Filled.Star) { DayBarChart(xp, days, color = Sunshine) }
        SectionCard("FSRS tekrarları (son 14 gün)", accent = Violet, icon = Icons.Filled.Autorenew) { DayBarChart(reviews, days) }
        SectionCard("Başarılı aktif kullanım (son 14 gün)", accent = Mint, icon = Icons.Filled.Create) { DayBarChart(activePer, days, color = Mint) }
        weekly?.let { w ->
            SectionCard("Bu hafta", accent = Sky, icon = Icons.Filled.CalendarMonth) {
                Text("Yeni öğrenilen kelime: ${w.newWords}")
                Text("Tekrar: ${w.reviews} (${w.distinctReviewed} farklı kart)")
                Text("Aktif kullanım: ${w.activeSuccesses} başarılı / ${w.activeAttempts} deneme")
                Text("Konuşma: ${"%.1f".format(w.speakingMinutes)} dk · Telaffuz denemesi: ${w.pronunciationAttempts}")
                Text("Kilit ekranından cevap: ${w.lockScreenAnswers}")
                Text("Zayıf kelime: ${w.weakNow} (bu hafta ${w.weakImproved} tanesinde başarılı kullanım)")
                Text("30 günlük FSRS hatırlama oranı (olgun kartlar): ${w.retention30d?.let { "%${(it * 100).roundToInt()}" } ?: "henüz yeterli veri yok"}")
                Text("Kişisel rekorlar: en iyi gün ${w.bestDayXp} XP · en iyi yarışma ${w.bestBattle ?: "—"}")
            }
        }
        SectionCard("Bu haftanın en sık 5 hatası", accent = Coral, icon = Icons.Filled.Warning, modifier = Modifier.testTag("top_mistakes")) {
            val list = topMistakes
            when {
                list == null -> Text("Hesaplanıyor…", style = MaterialTheme.typography.bodySmall)
                list.isEmpty() -> Text("Bu hafta kayıtlı hata yok. Boşluk doldurma, cümle kurma, kelime üretimi, speaking, dikte ve gramerdeki yanlışlar burada toplanır.", style = MaterialTheme.typography.bodySmall)
                else -> list.forEachIndexed { i, m ->
                    Column(Modifier.fillMaxWidth().testTag("top_mistake_$i"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${i + 1}. ${m.titleTr} · ${m.group.count} kez", style = MaterialTheme.typography.titleSmall)
                        Text("${m.typeTr} · ${m.modeTr}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Senin cevabın: ${m.group.userAnswer}", style = MaterialTheme.typography.bodySmall)
                        Text("Doğrusu: ${m.group.correctAnswer}", style = MaterialTheme.typography.bodySmall, color = Mint)
                        when {
                            m.ruleId != null -> TextButton(onClick = { nav.navigate(Routes.drill(StudyMode.GRAMMAR, m.ruleId)) }, modifier = Modifier.testTag("top_mistake_grammar_$i")) {
                                Text(if (com.lexiflow.data.repo.MistakeRepository.hasDrill(m.ruleId)) "Gramer konusunu çalış: ${m.titleTr}" else "Gramer Antrenörü'nü aç")
                            }
                            m.cardId != null -> TextButton(onClick = { nav.navigate(Routes.drill(StudyMode.WEAK_WORDS, m.cardId.toString())) }, modifier = Modifier.testTag("top_mistake_word_$i")) {
                                Text("\"${m.titleTr}\" kelimesini tekrar et")
                            }
                        }
                    }
                }
            }
        }
        SectionCard("Beceriler (FSRS'den ayrı)", accent = Pink, icon = Icons.Filled.Psychology) {
            Text("Tanıma FSRS ile, aşağıdaki aktif beceriler ayrı planlayıcıyla ölçülür.", style = MaterialTheme.typography.bodySmall)
            Skill.entries.filter { it != Skill.RECOGNITION }.forEach { sk ->
                val a = skills.firstOrNull { it.skill == sk.name }
                ScoreBar("${sk.labelTr} (${a?.practiced ?: 0} kart)", ((a?.avgMastery ?: 0.0) * 100).roundToInt())
            }
        }
        SectionCard("Haftalık görevler", accent = Coral, icon = Icons.Filled.Flag) {
            missions.forEach { m ->
                Text("${if (m.done) "✅" else "🎯"} ${m.def.titleTr} (${m.value.coerceAtMost(m.def.target)}/${m.def.target})", style = MaterialTheme.typography.bodyMedium)
                GradientProgress((m.value.toFloat() / m.def.target).coerceIn(0f, 1f), brush = if (m.done) BrandGradients.mint else BrandGradients.sunset, height = 10.dp)
            }
        }
        SectionCard("Unutma riski en yüksek kelimeler", accent = Danger, icon = Icons.Filled.Warning) {
            if (risk.isEmpty()) Text("Henüz tekrar edilmiş kart yok.")
            risk.forEach { Text("${it.card.lemma} — hatırlama olasılığı %${(it.retrievability * 100).roundToInt()}") }
            if (risk.isNotEmpty()) TextButton(onClick = { nav.navigate(Routes.stories(true)) }) { Text("Bu kelimelerle hikâye") }
            TextButton(onClick = { nav.navigate(Routes.drill(StudyMode.WEAK_WORDS)) }) { Text("Zayıf kelimeleri çalış") }
        }
        SectionCard("Rozetler", accent = Sunshine, icon = Icons.Filled.EmojiEvents) {
            achievements.forEach { (def, at) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.alpha(if (at != null) 1f else 0.5f)) {
                    IconBadge(if (at != null) Icons.Filled.EmojiEvents else Icons.Filled.Lock, if (at != null) BrandGradients.fire else BrandGradients.violet, size = 38.dp)
                    Column(Modifier.weight(1f)) {
                        Text(def.titleTr, style = MaterialTheme.typography.titleSmall)
                        Text(def.descriptionTr, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
