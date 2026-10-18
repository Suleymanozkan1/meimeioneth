package com.lexiflow.core.fsrs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * FSRS-6 memory model.
 *
 * This is an independent Kotlin port of the FSRS-6 formulas published by Open Spaced Repetition
 * (BSD-3-Clause). Reference implementation: open-spaced-repetition/fsrs-rs, `src/model_v6.rs` and
 * `src/inference_v6.rs` (crate version 6.6.2, commit 0a573740). Default weights are copied verbatim
 * from `FSRS6_DEFAULT_PARAMETERS`. See docs/REPOSITORIES.md and docs/FSRS.md.
 *
 * Terms:
 * - Stability (S): days until retrievability drops from 100% to 90%.
 * - Difficulty (D): 1..10, how hard it is to raise stability.
 * - Retrievability (R): probability of recall after t days, R(t, S) = (1 + FACTOR * t / S)^DECAY.
 */
class Fsrs(parameters: DoubleArray = DEFAULT_PARAMETERS) {

    val w: DoubleArray = when (parameters.size) {
        21 -> parameters.copyOf()
        19 -> parameters.copyOf(21).also { it[19] = 0.0; it[20] = FSRS5_DECAY }
        else -> throw IllegalArgumentException("FSRS-6 expects 19 or 21 parameters, got ${parameters.size}")
    }

    private val decay: Double get() = -w[20]
    private val factor: Double get() = 0.9.pow(1.0 / decay) - 1.0

    /** Probability of recall after [elapsedDays] for a memory of [stability]. Elapsed days are rounded like fsrs-rs. */
    fun retrievability(elapsedDays: Double, stability: Double): Double {
        val s = max(stability, S_MIN)
        val t = max(elapsedDays, 0.0).let { Math.round(it).toDouble() }
        return (1.0 + factor * t / s).pow(decay)
    }

    /** Same curve without rounding elapsed time; used for continuous "forgetting risk" estimates in the UI. */
    fun retrievabilityContinuous(elapsedDays: Double, stability: Double): Double {
        val s = max(stability, S_MIN)
        return (1.0 + factor * max(elapsedDays, 0.0) / s).pow(decay)
    }

    /** Unrounded optimal interval (days) for [desiredRetention]. */
    fun nextIntervalRaw(stability: Double, desiredRetention: Double): Double {
        val s = max(stability, S_MIN)
        val r = desiredRetention.coerceIn(0.0001, 0.9999)
        return (s / factor * (r.pow(1.0 / decay) - 1.0)).coerceIn(0.0, S_MAX)
    }

    fun initStability(rating: Rating): Double = w[rating.value - 1].coerceIn(S_MIN, S_MAX)

    fun initDifficulty(rating: Rating): Double = rawInitDifficulty(rating.value).coerceIn(D_MIN, D_MAX)

    private fun rawInitDifficulty(rating: Int): Double = w[4] - exp(w[5] * (rating - 1)) + 1.0

    fun nextDifficulty(d: Double, rating: Rating): Double {
        val deltaD = -w[6] * (rating.value - 3.0)
        val linearDamped = d + ((10.0 - d) / 9.0) * deltaD
        val reverted = w[7] * rawInitDifficulty(Rating.EASY.value) + (1.0 - w[7]) * linearDamped
        return reverted.coerceIn(D_MIN, D_MAX)
    }

    fun stabilityAfterSuccess(s: Double, r: Double, d: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        val inc = exp(w[8]) * (11.0 - d) * s.pow(-w[9]) * (exp((1.0 - r) * w[10]) - 1.0) * hardPenalty * easyBonus
        return (s * (inc + 1.0)).coerceIn(S_MIN, S_MAX)
    }

    fun stabilityAfterFailure(s: Double, r: Double, d: Double): Double {
        val newSMin = s / exp(w[17] * w[18])
        val newS = w[11] * d.pow(-w[12]) * ((s + 1.0).pow(w[13]) - 1.0) * exp((1.0 - r) * w[14])
        return min(newS, newSMin).coerceIn(S_MIN, S_MAX)
    }

    /**
     * Same-day review (elapsed rounds to 0 days). Matches the inference path of fsrs-rs (`next_state_scalar`):
     * stability never decreases for Hard/Good/Easy; only Again can lower it.
     */
    fun stabilityShortTerm(s: Double, rating: Rating): Double {
        val sinc = exp(w[17] * (rating.value - 3.0 + w[18])) * s.pow(-w[19])
        val newS = s * if (rating.value >= 2) max(sinc, 1.0) else sinc
        return newS.coerceIn(S_MIN, S_MAX)
    }

    /**
     * Memory state after a review. [state] is null for a card that was never reviewed.
     * Mirrors `next_state_scalar` + first-review initialisation in fsrs-rs.
     */
    fun nextMemoryState(state: MemoryState?, elapsedDays: Double, rating: Rating): MemoryState {
        if (state == null || state.stability <= 0.0) {
            return MemoryState(initStability(rating), initDifficulty(rating))
        }
        val t = Math.round(max(elapsedDays, 0.0)).toDouble()
        val s = state.stability.coerceIn(S_MIN, S_MAX)
        val d = state.difficulty.coerceIn(D_MIN, D_MAX)
        val r = retrievability(t, s)
        val newS = when {
            t == 0.0 -> stabilityShortTerm(s, rating)
            rating == Rating.AGAIN -> stabilityAfterFailure(s, r, d)
            else -> stabilityAfterSuccess(s, r, d, rating)
        }
        return MemoryState(newS, nextDifficulty(d, rating))
    }

    companion object {
        const val S_MIN = 0.0001
        const val S_MAX = 36500.0
        const val D_MIN = 1.0
        const val D_MAX = 10.0
        const val FSRS5_DECAY = 0.5
        const val FSRS6_DECAY = 0.1542
        const val VERSION = "FSRS-6 (fsrs-rs 6.6.2 parameters)"

        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, FSRS6_DECAY,
        )
    }
}

enum class Rating(val value: Int) {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    companion object {
        fun of(value: Int): Rating = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Rating must be 1..4, got $value")
    }
}

enum class CardState(val value: Int) {
    NEW(0), LEARNING(1), REVIEW(2), RELEARNING(3);

    companion object {
        fun of(value: Int): CardState = entries.firstOrNull { it.value == value } ?: NEW
    }
}

data class MemoryState(val stability: Double, val difficulty: Double)
