package com.lexiflow.core.gamification

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.floor
import kotlin.math.sqrt

/** Learning behaviours that earn XP. Rewards favour active use over passive clicking. */
enum class XpReason(val xp: Int, val labelTr: String) {
    REVIEW(2, "Kart tekrarı"),
    OVERDUE_REVIEW(4, "Gecikmiş tekrarı tamamlama"),
    NEW_CARD(3, "Yeni kelime"),
    ACTIVE_RECALL_SUCCESS(6, "Aktif hatırlama"),
    PRODUCTION_SUCCESS(10, "Kelimeyi kendi cümlende kullanma"),
    PRODUCTION_ATTEMPT(3, "Cümle kurma denemesi"),
    SPEAKING_TURN(8, "Konuşma turu"),
    PRONUNCIATION_ATTEMPT(4, "Telaffuz denemesi"),
    STORY_COMPLETED(20, "Hikâye tamamlama"),
    DAILY_CHALLENGE(30, "Günlük görev"),
    COMPETITION_ROUND(15, "Yarışma turu"),
    MISSION_COMPLETED(50, "Haftalık görev"),
}

/** Streak computed from the set of local calendar days on which the learner met their daily goal. */
data class StreakInfo(val current: Int, val longest: Int, val activeToday: Boolean)

object StreakCalculator {
    /**
     * [activeDays] are local dates (device time zone at the moment of activity, stored with the record).
     * The streak is still "alive" if today has no activity yet but yesterday has: we never break a streak
     * before the day is over, and we never rewrite history when the time zone changes.
     */
    fun compute(activeDays: Set<LocalDate>, today: LocalDate): StreakInfo {
        if (activeDays.isEmpty()) return StreakInfo(0, 0, false)
        val activeToday = today in activeDays
        var cursor = if (activeToday) today else today.minusDays(1)
        var current = 0
        while (cursor in activeDays) { current++; cursor = cursor.minusDays(1) }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        activeDays.sorted().forEach { d ->
            run = if (prev != null && prev!!.plusDays(1) == d) run + 1 else 1
            if (run > longest) longest = run
            prev = d
        }
        return StreakInfo(current, maxOf(longest, current), activeToday)
    }
}

data class LevelInfo(val level: Int, val xpIntoLevel: Int, val xpForNextLevel: Int, val titleTr: String)

object Levels {
    /** Level n requires 50·n² cumulative XP (level 1 at 0 XP). */
    fun fromXp(totalXp: Int): LevelInfo {
        val xp = totalXp.coerceAtLeast(0)
        val level = floor(sqrt(xp / 50.0)).toInt() + 1
        val start = 50 * (level - 1) * (level - 1)
        val next = 50 * level * level
        return LevelInfo(level, xp - start, next - start, title(level))
    }

    private fun title(level: Int) = when {
        level < 3 -> "Başlangıç"
        level < 6 -> "Kelime Avcısı"
        level < 10 -> "Cümle Kurucu"
        level < 15 -> "Akıcı Konuşmacı"
        else -> "LexiFlow Ustası"
    }
}

/** Aggregated counters used to evaluate achievements and missions. */
data class LearningTotals(
    val reviews: Int = 0,
    val wordsLearned: Int = 0,
    val productionSuccesses: Int = 0,
    val speakingTurns: Int = 0,
    val pronunciationAttempts: Int = 0,
    val storiesCompleted: Int = 0,
    val competitionsPlayed: Int = 0,
    val longestStreak: Int = 0,
    val overdueCleared: Int = 0,
    val importedWords: Int = 0,
)

data class AchievementDef(val id: String, val titleTr: String, val descriptionTr: String, val isUnlocked: (LearningTotals) -> Boolean)

object Achievements {
    val all: List<AchievementDef> = listOf(
        AchievementDef("first_review", "İlk adım", "İlk kart tekrarını yap.") { it.reviews >= 1 },
        AchievementDef("reviews_100", "Yüz tekrar", "100 kart tekrarı tamamla.") { it.reviews >= 100 },
        AchievementDef("words_50", "50 kelime", "50 kelimeyi öğrenme aşamasından geçir.") { it.wordsLearned >= 50 },
        AchievementDef("producer_10", "Cümle ustası", "10 kelimeyi kendi cümlende doğru kullan.") { it.productionSuccesses >= 10 },
        AchievementDef("producer_100", "Aktif kelime hazinesi", "100 başarılı aktif üretim.") { it.productionSuccesses >= 100 },
        AchievementDef("speaker_20", "Sohbet başladı", "20 konuşma turu tamamla.") { it.speakingTurns >= 20 },
        AchievementDef("pron_25", "Kulak ve dil", "25 telaffuz denemesi yap.") { it.pronunciationAttempts >= 25 },
        AchievementDef("story_5", "Hikâye okuru", "5 hikâyeyi alıştırmalarıyla bitir.") { it.storiesCompleted >= 5 },
        AchievementDef("battle_1", "Arenaya giriş", "İlk yarışma turunu oyna.") { it.competitionsPlayed >= 1 },
        AchievementDef("streak_7", "7 günlük seri", "7 gün üst üste günlük hedefini tamamla.") { it.longestStreak >= 7 },
        AchievementDef("streak_30", "30 günlük seri", "30 gün üst üste günlük hedefini tamamla.") { it.longestStreak >= 30 },
        AchievementDef("overdue_20", "Borç kapatıldı", "20 gecikmiş tekrarı tamamla.") { it.overdueCleared >= 20 },
        AchievementDef("importer", "Anki'den taşındı", "Kendi destenden kelime içe aktar.") { it.importedWords >= 1 },
    )
}

data class MissionDef(val id: String, val titleTr: String, val target: Int, val progress: (LearningTotals) -> Int)

data class MissionProgress(val def: MissionDef, val value: Int) {
    val done get() = value >= def.target
}

object WeeklyMissions {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Missions rotate deterministically by ISO week so every learner sees a stable set for the week. */
    fun forWeek(weekStart: LocalDate): List<MissionDef> {
        val pool = listOf(
            MissionDef("w_production", "Bu hafta 15 kelimeyi kendi cümlende kullan", 15) { it.productionSuccesses },
            MissionDef("w_speaking", "10 konuşma turu tamamla", 10) { it.speakingTurns },
            MissionDef("w_reviews", "150 kart tekrarı yap", 150) { it.reviews },
            MissionDef("w_story", "2 hikâye tamamla", 2) { it.storiesCompleted },
            MissionDef("w_pron", "15 telaffuz denemesi yap", 15) { it.pronunciationAttempts },
            MissionDef("w_overdue", "10 gecikmiş tekrarı kapat", 10) { it.overdueCleared },
        )
        val seed = weekStart.toEpochDay().toInt()
        val start = Math.floorMod(seed / 7, pool.size)
        return (0 until 3).map { pool[(start + it * 2) % pool.size] }
    }

    fun progress(defs: List<MissionDef>, weekTotals: LearningTotals) = defs.map { MissionProgress(it, it.progress(weekTotals)) }
}
