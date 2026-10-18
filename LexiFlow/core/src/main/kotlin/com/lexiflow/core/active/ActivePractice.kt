package com.lexiflow.core.active

import kotlin.math.max
import kotlin.math.min

/**
 * Skill dimensions tracked per word sense. FSRS schedules RECOGNITION (the classic flashcard);
 * the remaining dimensions are scheduled by [ActivePracticeScheduler], which never touches FSRS state.
 */
enum class Skill(val labelTr: String) {
    RECOGNITION("Tanıma (FSRS kartı)"),
    MEANING_RECALL("Anlamı hatırlama (İng → Tr)"),
    RECALL("Türkçeden İngilizce üretme"),
    PRODUCTION("Cümlede kullanma"),
    SPEAKING("Konuşmada kullanma"),
    PRONUNCIATION("Telaffuz"),
    CONTEXT("Farklı bağlamlarda doğru kullanım"),
    ;

    companion object {
        /** Skills that measure active use (shown separately from FSRS statistics). */
        val ACTIVE = listOf(RECALL, PRODUCTION, SPEAKING, CONTEXT)
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: RECALL
    }
}

/** Hint ladder: practice starts with strong hints and fades them as the learner succeeds. */
enum class HintLevel(val level: Int, val labelTr: String) {
    NONE(0, "İpucu yok"),
    FIRST_LETTER(1, "İlk harf"),
    MEANING_AND_LETTER(2, "Anlam + ilk harf"),
    FULL_CONTEXT(3, "Anlam + ilk harf + örnek bağlam");

    companion object {
        fun of(level: Int) = entries.first { it.level == level.coerceIn(0, 3) }
    }
}

data class SkillState(
    val skill: Skill,
    /** Exponential moving average of effective scores, 0..1. */
    val mastery: Double = 0.0,
    val attempts: Int = 0,
    val successes: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val hintLevel: Int = HintLevel.FULL_CONTEXT.level,
    /** Number of distinct contexts (modes or sentences) in which the learner produced the word correctly. */
    val distinctContexts: Int = 0,
    val lastPracticedAt: Long? = null,
    val dueAt: Long = 0L,
    /** Set when the learner says "I recognise it but cannot use it". Cleared after unaided success. */
    val flaggedCantUse: Boolean = false,
)

data class PracticeResult(
    /** Quality of the answer, 0..1, from the evaluator. */
    val score: Double,
    /** Hint level that was visible while answering. */
    val hintUsed: Int,
    /** True when the answer used the word in a context not seen before (new sentence / mode). */
    val newContext: Boolean = false,
    val at: Long,
)

/**
 * Separate, documented scheduler for active-use skills (see docs/ACTIVE_RECALL.md).
 *
 * Rules:
 * - Effective score = score x (1 - 0.15 x hintUsed). An answer found only with hints counts less.
 * - mastery <- mastery + 0.35 x (effective - mastery).
 * - Success (effective >= 0.7): hint level drops by one, consecutive++, next due grows 1d, 3d, 7d, 16d, 35d...
 *   scaled by mastery and by how many distinct contexts were mastered.
 * - Partial (0.4..0.7): due again in 12 hours, hint level unchanged.
 * - Failure: hint level rises by one and the item is due again in 20 minutes (same session).
 * - "Recognise but can't use" flag: production/speaking mastery is capped at 0.3, hints restored, due now.
 */
class ActivePracticeScheduler {

    fun update(state: SkillState, result: PracticeResult): SkillState {
        val score = result.score.coerceIn(0.0, 1.0)
        val effective = score * (1.0 - 0.15 * result.hintUsed.coerceIn(0, 3))
        val mastery = (state.mastery + ALPHA * (effective - state.mastery)).coerceIn(0.0, 1.0)
        val success = effective >= SUCCESS
        val partial = !success && effective >= PARTIAL
        val consecutive = if (success) state.consecutiveSuccesses + 1 else 0
        val contexts = state.distinctContexts + if (success && result.newContext) 1 else 0
        val hint = when {
            success -> max(0, state.hintLevel - 1)
            partial -> state.hintLevel
            else -> min(3, state.hintLevel + 1)
        }
        val interval = when {
            success -> {
                val base = SUCCESS_LADDER_DAYS[min(consecutive - 1, SUCCESS_LADDER_DAYS.lastIndex)]
                val contextBonus = 1.0 + 0.1 * min(contexts, 5)
                (base * (0.5 + mastery) * contextBonus * DAY_MS).toLong()
            }
            partial -> 12 * HOUR_MS
            else -> 20 * MINUTE_MS
        }
        val unaided = success && result.hintUsed == 0
        return state.copy(
            mastery = mastery,
            attempts = state.attempts + 1,
            successes = state.successes + if (success) 1 else 0,
            consecutiveSuccesses = consecutive,
            hintLevel = hint,
            distinctContexts = contexts,
            lastPracticedAt = result.at,
            dueAt = result.at + interval,
            flaggedCantUse = state.flaggedCantUse && !unaided,
        )
    }

    /** Learner says: "I know this word when I see it, but it doesn't come to mind when I speak." */
    fun flagCantUse(state: SkillState, now: Long): SkillState {
        val capped = if (state.skill == Skill.PRODUCTION || state.skill == Skill.SPEAKING || state.skill == Skill.RECALL) {
            min(state.mastery, 0.3)
        } else state.mastery
        return state.copy(
            mastery = capped,
            flaggedCantUse = true,
            hintLevel = max(state.hintLevel, HintLevel.MEANING_AND_LETTER.level),
            consecutiveSuccesses = 0,
            dueAt = now,
        )
    }

    /**
     * Priority for the active-production queue. Higher = practice sooner.
     * Combines: overdue time, low mastery, the recognition/production gap and the "can't use" flag.
     */
    fun priority(production: SkillState, recognitionRetrievability: Double?, now: Long): Double {
        val overdueDays = max(0.0, (now - production.dueAt).toDouble() / DAY_MS)
        val due = if (now >= production.dueAt) 1.0 else 0.0
        val gap = max(0.0, (recognitionRetrievability ?: 0.0) - production.mastery)
        val flag = if (production.flaggedCantUse) 1.5 else 0.0
        return due * (1.0 + min(overdueDays, 10.0) * 0.1) + (1.0 - production.mastery) + gap + flag
    }

    companion object {
        const val ALPHA = 0.35
        const val SUCCESS = 0.7
        const val PARTIAL = 0.4
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
        val SUCCESS_LADDER_DAYS = doubleArrayOf(1.0, 3.0, 7.0, 16.0, 35.0, 75.0)
    }
}

/** Builds the hint visible at a given level for a target word. */
object HintBuilder {
    fun hint(level: Int, word: String, meaningTr: String, contextWithBlank: String?): List<String> {
        val parts = mutableListOf<String>()
        if (level >= HintLevel.MEANING_AND_LETTER.level) parts += "Anlam: $meaningTr"
        if (level >= HintLevel.FIRST_LETTER.level && word.isNotEmpty()) {
            parts += "İlk harf: ${word.first()}… (${word.length} harf)"
        }
        if (level >= HintLevel.FULL_CONTEXT.level && !contextWithBlank.isNullOrBlank()) parts += "Bağlam: $contextWithBlank"
        return parts
    }
}
