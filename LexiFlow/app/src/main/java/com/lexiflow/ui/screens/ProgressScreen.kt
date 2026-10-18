package com.lexiflow.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    LaunchedEffect(Unit) {
        weekly = container.stats.weekly()
        missions = container.gamification.weeklyMissions()
        risk = container.stats.forgettingRisk()
    }
    val today = container.clock.today()
    val days = (13 downTo 0).map { today.minusDays(it.toLong()).toString() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("progress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("İlerleme Paneli", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val p = progress
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("Seri", "${p?.streak?.current ?: 0} gün", Modifier.weight(1f), "Rekor: ${p?.streak?.longest ?: 0}")
            StatTile("Seviye", "${p?.level?.level ?: 1}", Modifier.weight(1f), "${p?.level?.xpIntoLevel ?: 0}/${p?.level?.xpForNextLevel ?: 50} XP")
            StatTile("Olgun kart", "$mature", Modifier.weight(1f), "REVIEW durumunda")
        }
        Text("Seri: günlük XP hedefinin tamamlandığı yerel günler. Gün bitmeden seri bozulmaz; saat dilimi değişse de geçmiş günler değişmez.", style = MaterialTheme.typography.bodySmall)
        SectionCard("XP (son 14 gün)") { DayBarChart(xp, days) }
        SectionCard("FSRS tekrarları (son 14 gün)") { DayBarChart(reviews, days) }
        SectionCard("Başarılı aktif kullanım (son 14 gün)") { DayBarChart(activePer, days, color = MaterialTheme.colorScheme.tertiary) }
        weekly?.let { w ->
            SectionCard("Bu hafta") {
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
        SectionCard("Beceriler (FSRS'den ayrı)") {
            Text("Tanıma FSRS ile, aşağıdaki aktif beceriler ayrı planlayıcıyla ölçülür.", style = MaterialTheme.typography.bodySmall)
            Skill.entries.filter { it != Skill.RECOGNITION }.forEach { sk ->
                val a = skills.firstOrNull { it.skill == sk.name }
                ScoreBar("${sk.labelTr} (${a?.practiced ?: 0} kart)", ((a?.avgMastery ?: 0.0) * 100).roundToInt())
            }
        }
        SectionCard("Haftalık görevler") {
            missions.forEach { m ->
                Text("${if (m.done) "✓" else "○"} ${m.def.titleTr} (${m.value.coerceAtMost(m.def.target)}/${m.def.target})")
                LinearProgressIndicator(progress = { (m.value.toFloat() / m.def.target).coerceIn(0f, 1f) }, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
        SectionCard("Unutma riski en yüksek kelimeler") {
            if (risk.isEmpty()) Text("Henüz tekrar edilmiş kart yok.")
            risk.forEach { Text("${it.card.lemma} — hatırlama olasılığı %${(it.retrievability * 100).roundToInt()}") }
            if (risk.isNotEmpty()) TextButton(onClick = { nav.navigate(Routes.stories(true)) }) { Text("Bu kelimelerle hikâye") }
            TextButton(onClick = { nav.navigate(Routes.drill(StudyMode.WEAK_WORDS)) }) { Text("Zayıf kelimeleri çalış") }
        }
        SectionCard("Rozetler") {
            achievements.forEach { (def, at) -> Text("${if (at != null) "🏅" else "🔒"} ${def.titleTr} — ${def.descriptionTr}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
