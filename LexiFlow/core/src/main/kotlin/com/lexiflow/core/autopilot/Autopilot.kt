package com.lexiflow.core.autopilot

import com.lexiflow.core.fsrs.Rating
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Exercise kinds the daily autopilot can schedule, with their average completion time per item. */
enum class StepKind(val labelTr: String, val unitSeconds: Int, val usesVoice: Boolean = false, val isModule: Boolean = false) {
    EN_TO_TR("İngilizceden Türkçeye", 10),
    TR_TO_EN("Türkçeden İngilizceye", 10),
    WORD_INTRO("Yeni kelime kartı", 15),
    CLOZE("Boşluk Doldurma", 20),
    CONTEXT_CHALLENGE("Bağlam Meydan Okuması", 20),
    LISTENING("Dinleme", 20),
    PRONUNCIATION("Telaffuz Laboratuvarı", 20, usesVoice = true, isModule = true),
    SENTENCE_BUILDER("Cümle Oluşturma", 40),
    TRANSLATION("Çeviri Pratiği", 40),
    WORD_PRODUCTION("Kelime Üretimi", 40),
    GRAMMAR("Gramer", 40),
    SHADOWING("Shadowing", 40, usesVoice = true, isModule = true),
    DICTATION("Dikte", 40),
    FORGETTING_STORY("Unutma Riski Hikâyesi", 90, isModule = true),
    AI_STORY("AI Hikâyesi", 90, isModule = true),
    SCREEN_CONTEXT("Film / Dizi Bağlamı", 90, isModule = true),
    MUSIC_CONTEXT("Müzik Bağlamı", 90, isModule = true),
    AI_SPEAKING("AI Speaking", 150, usesVoice = true, isModule = true),
    WRITTEN_SPEAKING("Yazılı Speaking", 150, isModule = true),
    ;

    /** Module steps cost one unit per content/dialogue, drills one unit per word. */
    fun seconds(items: Int): Int = if (isModule) unitSeconds else unitSeconds * items

    /** "Sessiz mod": nothing that needs speaking aloud. */
    fun silentReplacement(): StepKind = when (this) {
        AI_SPEAKING -> WRITTEN_SPEAKING
        PRONUNCIATION -> DICTATION
        SHADOWING -> WORD_PRODUCTION
        else -> this
    }
}

/** The five stages of a daily session and their default share of the time budget. */
enum class Phase(val labelTr: String, val share: Double) {
    WARMUP("Kritik tekrar ve ısınma", 0.25),
    NEW_WORDS("Yeni kelimeler ve ses", 0.15),
    CONTEXT("Bağlamsal kavrama", 0.20),
    ACTIVE("Pasiften aktife", 0.25),
    SYNTHESIS("Final sentezi", 0.15),
}

/** One card known to the planner. Lower [stability] (FSRS days) means a less mature word. */
data class PlanCard(val cardId: Long, val stability: Double = 0.0)

data class PlannerInput(
    /** Due FSRS cards, most urgent first. */
    val due: List<PlanCard>,
    /** Often-forgotten / weakly used words, weakest first. */
    val weak: List<PlanCard>,
    /** Today's new-word target (daily words), in order. */
    val newWords: List<PlanCard>,
    /** New words from the personal wordbook. */
    val wordbookNew: List<PlanCard> = emptyList(),
    /** "I recognise it but can't use it" words. */
    val activeQueue: List<PlanCard> = emptyList(),
    /** Studied words with falling retrievability (forgetting-curve stories). */
    val atRisk: List<PlanCard> = emptyList(),
    /** Older wordbook words used to fill extra time. */
    val wordbookReview: List<PlanCard> = emptyList(),
    val grammarAvailable: Boolean = true,
    /** User-chosen budget in minutes (5–120); null = use the calculated time. */
    val budgetMinutes: Int? = null,
    val silent: Boolean = false,
    val aiAvailable: Boolean = false,
)

data class PlannedStep(val phase: Phase, val kind: StepKind, val cardIds: List<Long>, val seconds: Int)

data class AutopilotPlan(
    /** Time the day's workload needs (due + weak + new words), capped at [MAX_CALCULATED_MINUTES]. */
    val calculatedMinutes: Int,
    val budgetMinutes: Int,
    val steps: List<PlannedStep>,
    val reviewWords: Int,
    val newWords: Int,
    /** True when the budget is below the calculated time and the plan was compressed. */
    val constrained: Boolean,
) {
    val plannedSeconds: Int get() = steps.sumOf { it.seconds }
    fun phaseSeconds(phase: Phase): Int = steps.filter { it.phase == phase }.sumOf { it.seconds }
}

/**
 * Builds the "Akıllı Günlük Seans": estimates how long today's work takes (never more than 90 minutes), then fills
 * the chosen budget (5–120 minutes) with five stages. A shorter budget keeps the most urgent reviews and weak words,
 * cuts new words proportionally and swaps long production steps for fast ones; a longer budget adds the wordbook,
 * active path, forgetting-risk stories and speaking after the regular work.
 */
object AutopilotPlanner {
    const val MAX_CALCULATED_MINUTES = 90
    const val MIN_BUDGET_MINUTES = 5
    const val MAX_BUDGET_MINUTES = 120

    /** FSRS stability (days) below which a word is practised passively, and above which actively. */
    const val PASSIVE_BELOW_DAYS = 3.0
    const val ACTIVE_FROM_DAYS = 21.0

    private const val STORY_WORDS = 6

    fun plan(input: PlannerInput): AutopilotPlan {
        val reviewCards = (input.due + input.weak).distinctBy { it.cardId }
        val calculatedSeconds = fullWorkload(input, reviewCards)
        val calculated = ceil(calculatedSeconds / 60.0).toInt().coerceIn(MIN_BUDGET_MINUTES, MAX_CALCULATED_MINUTES)
        val budget = (input.budgetMinutes ?: calculated).coerceIn(MIN_BUDGET_MINUTES, MAX_BUDGET_MINUTES)
        val budgetSeconds = budget * 60
        val constrained = budgetSeconds < calculatedSeconds

        // Constrained: new words shrink with the budget; reviews keep priority.
        val ratio = min(1.0, budgetSeconds.toDouble() / max(1, calculatedSeconds))
        val newTarget = if (constrained) (input.newWords.size * ratio).roundToInt() else input.newWords.size
        val newCards = (input.newWords.take(newTarget) + input.wordbookNew).distinctBy { it.cardId }

        // Phase budgets; when time is short the warm-up may take up to half to cover urgent reviews.
        val warmupNeed = reviewCards.size * StepKind.EN_TO_TR.unitSeconds
        val warmupShare = if (constrained) max(Phase.WARMUP.share, min(0.5, warmupNeed.toDouble() / budgetSeconds)) else Phase.WARMUP.share
        val restScale = (1.0 - warmupShare) / (1.0 - Phase.WARMUP.share)
        val shares = Phase.entries.associateWith { if (it == Phase.WARMUP) warmupShare else it.share * restScale }

        val steps = mutableListOf<PlannedStep>()
        var carry = 0
        fun phaseBudget(p: Phase) = (budgetSeconds * shares.getValue(p)).roundToInt() + carry
        fun add(p: Phase, kind: StepKind, cards: List<PlanCard>, limit: Int): Int {
            val k = if (input.silent) kind.silentReplacement() else kind
            if (k.isModule) {
                if (k.unitSeconds > limit) return 0
                steps += PlannedStep(p, k, cards.map { it.cardId }, k.unitSeconds)
                return k.unitSeconds
            }
            val n = min(cards.size, limit / k.unitSeconds)
            if (n <= 0) return 0
            steps += PlannedStep(p, k, cards.take(n).map { it.cardId }, k.seconds(n))
            return k.seconds(n)
        }

        // 1) Critical review and warm-up: recognition for young words, recall for mature ones.
        run {
            var left = phaseBudget(Phase.WARMUP)
            val (young, mature) = reviewCards.partition { it.stability < PASSIVE_BELOW_DAYS }
            // Most urgent first across both groups: take the prefix that fits, then split by maturity.
            val fit = reviewCards.take(left / StepKind.EN_TO_TR.unitSeconds).map { it.cardId }.toSet()
            left -= add(Phase.WARMUP, StepKind.EN_TO_TR, young.filter { it.cardId in fit }, left)
            left -= add(Phase.WARMUP, StepKind.TR_TO_EN, mature.filter { it.cardId in fit }, left)
            carry = max(0, left)
        }
        // 2) New words: rich card, then hearing and saying them.
        run {
            var left = phaseBudget(Phase.NEW_WORDS)
            val perWord = StepKind.WORD_INTRO.unitSeconds + StepKind.LISTENING.unitSeconds
            // Room for one pronunciation round after the cards and listening.
            val fitting = newCards.take(max(0, (left - StepKind.PRONUNCIATION.unitSeconds) / perWord))
            left -= add(Phase.NEW_WORDS, StepKind.WORD_INTRO, fitting, left)
            left -= add(Phase.NEW_WORDS, StepKind.LISTENING, fitting, left)
            if (fitting.isNotEmpty()) left -= add(Phase.NEW_WORDS, StepKind.PRONUNCIATION, fitting, left)
            carry = max(0, left)
        }
        val introduced = steps.filter { it.kind == StepKind.WORD_INTRO }.flatMap { it.cardIds }.toSet()
        // 3) Context: one story/scene with the new and at-risk words, then gap-fill and context questions.
        run {
            var left = phaseBudget(Phase.CONTEXT)
            val targets = (newCards.filter { it.cardId in introduced } + input.atRisk + reviewCards.filter { it.stability >= PASSIVE_BELOW_DAYS })
                .distinctBy { it.cardId }
            if (!constrained && targets.isNotEmpty()) {
                val story = when {
                    input.atRisk.isNotEmpty() -> StepKind.FORGETTING_STORY
                    input.aiAvailable -> StepKind.AI_STORY
                    else -> StepKind.SCREEN_CONTEXT
                }
                left -= add(Phase.CONTEXT, story, targets.take(STORY_WORDS), left)
            }
            // Recognising the word for a definition first, then finding it in a sentence.
            left -= add(Phase.CONTEXT, StepKind.CONTEXT_CHALLENGE, targets, left / 2)
            left -= add(Phase.CONTEXT, StepKind.CLOZE, targets, left)
            carry = max(0, left)
        }
        // 4) Passive → active: the "can't use it yet" words, mature words first.
        run {
            var left = phaseBudget(Phase.ACTIVE)
            val active = (input.activeQueue + reviewCards.filter { it.stability >= ACTIVE_FROM_DAYS }).distinctBy { it.cardId }
            if (constrained) {
                // Fast steps only: as many words as possible per minute.
                val clozeSeconds = add(Phase.ACTIVE, StepKind.CLOZE, active, left / 2)
                left -= clozeSeconds
                left -= add(Phase.ACTIVE, StepKind.CONTEXT_CHALLENGE, active.drop(clozeSeconds / StepKind.CLOZE.unitSeconds), left)
            } else {
                val quarter = left / 4
                left -= add(Phase.ACTIVE, StepKind.SENTENCE_BUILDER, active, quarter)
                left -= add(Phase.ACTIVE, StepKind.TRANSLATION, active, quarter)
                left -= add(Phase.ACTIVE, StepKind.DICTATION, active.ifEmpty { newCards.filter { it.cardId in introduced } }, quarter)
                if (input.grammarAvailable) left -= add(Phase.ACTIVE, StepKind.GRAMMAR, List(max(1, left / StepKind.GRAMMAR.unitSeconds)) { PlanCard(-1) }, left)
            }
            carry = max(0, left)
        }
        // 5) Synthesis: use today's words without hints, or talk about them.
        run {
            var left = phaseBudget(Phase.SYNTHESIS)
            val today = steps.flatMap { it.cardIds }.filter { it >= 0 }.distinct().map { PlanCard(it) }
            if (!constrained && left >= StepKind.AI_SPEAKING.unitSeconds && today.isNotEmpty()) {
                left -= add(Phase.SYNTHESIS, if (input.aiAvailable) StepKind.AI_SPEAKING else StepKind.WRITTEN_SPEAKING, today.take(5), left)
            }
            left -= add(Phase.SYNTHESIS, StepKind.WORD_PRODUCTION, today.takeLast(max(1, today.size / 2)), left)
            carry = max(0, left)
        }
        // Extra time beyond the day's work: wordbook, active path, forgetting-risk story, speaking.
        if (!constrained) {
            var left = budgetSeconds - steps.sumOf { it.seconds }
            if (left > 0) left -= add(Phase.WARMUP, StepKind.TR_TO_EN, input.wordbookReview, left / 3)
            if (left > 0) left -= add(Phase.ACTIVE, StepKind.SENTENCE_BUILDER, input.activeQueue.drop(1), left / 2)
            if (left >= StepKind.FORGETTING_STORY.unitSeconds && (input.atRisk.isNotEmpty() || input.wordbookReview.isNotEmpty())) {
                left -= add(Phase.CONTEXT, StepKind.FORGETTING_STORY, (input.atRisk + input.wordbookReview).take(STORY_WORDS), left)
            }
            if (left >= StepKind.AI_SPEAKING.unitSeconds && steps.none { it.kind == StepKind.AI_SPEAKING || it.kind == StepKind.WRITTEN_SPEAKING }) {
                add(Phase.SYNTHESIS, if (input.aiAvailable) StepKind.AI_SPEAKING else StepKind.WRITTEN_SPEAKING, reviewCards.take(5), left)
            }
        }

        val ordered = steps.sortedBy { it.phase.ordinal }
        return AutopilotPlan(
            calculatedMinutes = calculated,
            budgetMinutes = budget,
            steps = ordered,
            reviewWords = ordered.filter { it.phase == Phase.WARMUP }.sumOf { it.cardIds.size },
            newWords = introduced.size,
            constrained = constrained,
        )
    }

    /** Seconds the whole day's work takes with no cuts (used for "Hesaplanan süre"). */
    private fun fullWorkload(input: PlannerInput, reviewCards: List<PlanCard>): Int {
        val newCount = (input.newWords + input.wordbookNew).distinctBy { it.cardId }.size
        val warmup = reviewCards.size * StepKind.EN_TO_TR.unitSeconds
        val newWords = if (newCount == 0) 0 else newCount * (StepKind.WORD_INTRO.unitSeconds + StepKind.LISTENING.unitSeconds) + StepKind.PRONUNCIATION.unitSeconds
        val contextWords = newCount + input.atRisk.size + reviewCards.count { it.stability >= PASSIVE_BELOW_DAYS }
        val context = if (contextWords == 0) 0 else StepKind.FORGETTING_STORY.unitSeconds + contextWords * StepKind.CLOZE.unitSeconds
        val activeWords = (input.activeQueue + reviewCards.filter { it.stability >= ACTIVE_FROM_DAYS }).distinctBy { it.cardId }.size
        val active = activeWords * (StepKind.SENTENCE_BUILDER.unitSeconds + StepKind.TRANSLATION.unitSeconds) +
            if (input.grammarAvailable) 2 * StepKind.GRAMMAR.unitSeconds else 0
        val touched = reviewCards.size + newCount
        val synthesis = if (touched == 0) 0 else StepKind.WRITTEN_SPEAKING.unitSeconds + min(touched, 5) * StepKind.WORD_PRODUCTION.unitSeconds
        return warmup + newWords + context + active + synthesis
    }

    /** The exercise a word gets from its maturity when the autopilot practises it outside a fixed stage. */
    fun kindForMaturity(stability: Double, silent: Boolean = false): StepKind = when {
        stability < PASSIVE_BELOW_DAYS -> StepKind.EN_TO_TR
        stability < ACTIVE_FROM_DAYS -> StepKind.CLOZE
        else -> if (silent) StepKind.WORD_PRODUCTION else StepKind.SENTENCE_BUILDER
    }
}

/**
 * FSRS rating from how an answer went, so the autopilot never shows Again/Hard/Good/Easy buttons:
 * wrong = Again; right after a hint, a retry or slowly = Hard; right and fast without help = Easy; otherwise Good.
 */
object ImplicitGrader {
    fun rate(correct: Boolean, hintsUsed: Int, responseMs: Long, expectedMs: Long, firstAttempt: Boolean = true): Rating = when {
        !correct -> Rating.AGAIN
        hintsUsed > 0 || !firstAttempt || responseMs > expectedMs * 2 -> Rating.HARD
        responseMs in 1..(expectedMs / 2) -> Rating.EASY
        else -> Rating.GOOD
    }
}
