package com.lexiflow.data.repo

import android.content.Context
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.autopilot.AutopilotPlan
import com.lexiflow.core.autopilot.AutopilotPlanner
import com.lexiflow.core.autopilot.ImplicitGrader
import com.lexiflow.core.autopilot.Phase
import com.lexiflow.core.autopilot.PlanCard
import com.lexiflow.core.autopilot.PlannerInput
import com.lexiflow.core.autopilot.StepKind
import com.lexiflow.core.fsrs.CardState
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.language.GrammarDrills
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.PriorityItemEntity
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.domain.Exercise
import com.lexiflow.domain.ExerciseType
import com.lexiflow.domain.GradeResult
import com.lexiflow.util.Clock
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SessionStep(val phase: Phase, val kind: StepKind, val cardIds: List<Long>, val seconds: Int, val done: Boolean = false, val skipped: Boolean = false)

/** Today's autopilot session, saved so it can be resumed after leaving the app. */
@Serializable
data class AutopilotSession(
    val date: String,
    val budgetMinutes: Int,
    val calculatedMinutes: Int,
    val silent: Boolean,
    val constrained: Boolean,
    val steps: List<SessionStep>,
    val startedAt: Long,
    val answered: Int = 0,
    val correct: Int = 0,
    /** Cards that already got their FSRS review in this session (one review per card per day). */
    val reviewed: List<Long> = emptyList(),
    val finishedAt: Long? = null,
) {
    val index: Int get() = steps.indexOfFirst { !it.done && !it.skipped }.let { if (it < 0) steps.size else it }
    val current: SessionStep? get() = steps.getOrNull(index)
    val remainingSeconds: Int get() = steps.drop(index).sumOf { it.seconds }
    val finished: Boolean get() = finishedAt != null || index >= steps.size
}

data class AutopilotEstimate(
    val calculatedMinutes: Int,
    val budgetMinutes: Int,
    val reviewWords: Int,
    val newWords: Int,
    val customBudget: Boolean,
    val silent: Boolean,
    val session: AutopilotSession?,
)

/**
 * "Akıllı Günlük Seans": gathers today's work, asks [AutopilotPlanner] for a plan within the chosen time, builds the
 * exercises of each step and grades answers implicitly (no Again/Hard/Good/Easy buttons). Wrong answers make the
 * word weak and put it (or the grammar point) at the front of today's lock-screen questions.
 */
class AutopilotRepository(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val study: StudyRepository,
    private val active: ActiveRecallRepository,
    private val sessions: SessionRepository,
    private val clock: Clock,
) {
    private val file = File(context.filesDir, "autopilot_session.json")

    suspend fun input(budgetMinutes: Int?, silent: Boolean): PlannerInput {
        val now = clock.now()
        val s = settings.current()
        fun CardDetail.plan() = PlanCard(card.id, card.stability)
        val due = db.cardDao().dueReviews(now, 500).filter { it.card.state != CardState.NEW.value }
        val weak = active.weakWords(30).map { it.card }
        val learnedToday = db.reviewLogDao().newLearnedSince(clock.startOfToday())
        val newTarget = (s.newCardsPerDay - learnedToday).coerceAtLeast(0)
        val newCards = if (newTarget > 0) db.cardDao().newCards(newTarget) else emptyList()
        val wordbook = db.cardDao().wordbookCards(60)
        val studied = db.cardDao().allStudied()
        val atRisk = studied.filter { it.card.dueAt > now }
            .mapNotNull { c -> study.retrievability(c.card)?.takeIf { it < AT_RISK_RETRIEVABILITY }?.let { c to it } }
            .sortedBy { it.second }.take(10).map { it.first }
        return PlannerInput(
            due = due.map { it.plan() },
            weak = weak.map { it.plan() },
            newWords = newCards.map { it.plan() },
            wordbookNew = wordbook.filter { it.card.state == CardState.NEW.value }.take(5).map { it.plan() },
            activeQueue = active.productionQueue(12, onlyDue = true).map { it.card.plan() },
            atRisk = atRisk.map { it.plan() },
            wordbookReview = wordbook.filter { it.card.state != CardState.NEW.value }.map { it.plan() },
            budgetMinutes = budgetMinutes,
            silent = silent,
            aiAvailable = s.aiAvailable,
        )
    }

    suspend fun plan(budgetMinutes: Int?, silent: Boolean): AutopilotPlan = AutopilotPlanner.plan(input(budgetMinutes, silent))

    /** What the home card shows: the calculated time, the chosen time and today's session if one is running. */
    suspend fun estimate(): AutopilotEstimate {
        val s = settings.current()
        val custom = s.autopilotMinutes.takeIf { it > 0 }
        val plan = plan(custom, s.autopilotSilent)
        return AutopilotEstimate(plan.calculatedMinutes, plan.budgetMinutes, plan.reviewWords, plan.newWords, custom != null, s.autopilotSilent, todaySession())
    }

    fun todaySession(): AutopilotSession? = load()?.takeIf { it.date == clock.localDate(clock.now()) }

    /** Continues today's unfinished session, or plans a new one with the current settings. */
    suspend fun startOrResume(): AutopilotSession {
        val s = settings.current()
        todaySession()?.takeIf { !it.finished }?.let { return it }
        val plan = plan(s.autopilotMinutes.takeIf { it > 0 }, s.autopilotSilent)
        val session = AutopilotSession(
            date = clock.localDate(clock.now()),
            budgetMinutes = plan.budgetMinutes,
            calculatedMinutes = plan.calculatedMinutes,
            silent = s.autopilotSilent,
            constrained = plan.constrained,
            steps = plan.steps.map { SessionStep(it.phase, it.kind, it.cardIds, it.seconds) },
            startedAt = clock.now(),
        )
        save(session)
        return session
    }

    /** Throws away today's session (e.g. after changing the time) so the next start plans again. */
    fun reset() { file.delete() }

    /** Exercises for a drill step; module steps (stories, speaking, pronunciation) return an empty list. */
    suspend fun exercises(step: SessionStep, silent: Boolean): List<Exercise> {
        if (step.kind == StepKind.GRAMMAR) {
            val missed = db.priorityDao().recentGrammarRules(clock.startOfToday() - 7 * DAY_MS)
            val drills = (GrammarDrills.all.filter { it.ruleId in missed } + GrammarDrills.all.shuffled(kotlin.random.Random(clock.now())))
                .distinctBy { it.ruleId }.take(step.cardIds.size.coerceAtLeast(1))
            return drills.map { com.lexiflow.domain.ExerciseBuilder.grammarDrill(it) }
        }
        val cards = db.cardDao().details(step.cardIds.filter { it > 0 }).associateBy { it.card.id }
        val pairs = step.cardIds.mapNotNull { id -> cards[id]?.let { it to exerciseType(step.kind, it, silent) } }
        return sessions.buildFor(pairs)
    }

    private suspend fun exerciseType(kind: StepKind, card: CardDetail, silent: Boolean): ExerciseType = when (kind) {
        StepKind.EN_TO_TR -> ExerciseType.EN_TO_TR
        StepKind.TR_TO_EN -> ExerciseType.TR_TO_EN
        StepKind.LISTENING -> ExerciseType.LISTENING_CHOICE
        StepKind.CONTEXT_CHALLENGE -> ExerciseType.CONTEXT_CHOICE
        // Young words get options, mature ones type the answer (hints stay behind the 💡 button).
        StepKind.CLOZE -> if (card.card.stability < AutopilotPlanner.PASSIVE_BELOW_DAYS) ExerciseType.CLOZE_CHOICE else ExerciseType.CLOZE_TYPED
        StepKind.SENTENCE_BUILDER -> ExerciseType.SENTENCE_ORDER
        StepKind.TRANSLATION -> ExerciseType.TRANSLATION
        StepKind.DICTATION -> ExerciseType.DICTATION
        StepKind.WORD_PRODUCTION -> ExerciseType.PRODUCTION
        else -> AutopilotPlanner.kindForMaturity(card.card.stability, silent).let { if (it == StepKind.EN_TO_TR) ExerciseType.EN_TO_TR else ExerciseType.CLOZE_TYPED }
    }

    /**
     * Grades one answer without asking the learner: the FSRS rating comes from correctness, hints and time. A card is
     * reviewed in FSRS at most once per session and only when it is due or new (no early reviews); every answer is
     * also an active-recall attempt. Wrong answers mark the word (or grammar point) for today's lock-screen questions.
     */
    suspend fun recordAnswer(kind: StepKind, ex: Exercise, result: GradeResult, hintsUsed: Int, durationMs: Long): Rating? {
        var session = todaySession()
        val cardId = ex.cardId
        var rating: Rating? = null
        if (cardId != null) {
            val card = db.cardDao().get(cardId)
            val fsrsDue = card != null && (card.state == CardState.NEW.value || card.dueAt <= clock.now())
            val alreadyReviewed = session?.reviewed?.contains(cardId) == true
            rating = ImplicitGrader.rate(result.correct, hintsUsed, durationMs, kind.unitSeconds * 1000L)
            if (fsrsDue && !alreadyReviewed) {
                study.answer(cardId, rating, durationMs, source = "AUTOPILOT", mode = "AUTOPILOT_${kind.name}")
                session = session?.copy(reviewed = session.reviewed + cardId)
            }
            active.record(cardId, ex.type.skill, result.score, hintsUsed, "AUTOPILOT_${ex.type.name}", result.verdict, ex.exampleId,
                answerText = null, source = "AUTOPILOT", durationMs = durationMs, awardXp = !(fsrsDue && !alreadyReviewed))
            if (!result.correct) addPriority(PriorityItemEntity(kind = "CARD", cardId = cardId, reason = "AUTOPILOT_${kind.name}", localDate = today(), createdAt = clock.now()))
        } else if (ex.type == ExerciseType.ERROR_CORRECTION && !result.correct) {
            GrammarDrills.all.firstOrNull { it.wrong == ex.prompt }?.let { d ->
                addPriority(PriorityItemEntity(kind = "GRAMMAR", ruleId = d.ruleId, reason = "AUTOPILOT_GRAMMAR", localDate = today(), createdAt = clock.now()))
            }
        }
        session?.let { save(it.copy(answered = it.answered + 1, correct = it.correct + if (result.correct) 1 else 0)) }
        return rating
    }

    /** Marks the current step done (or skipped) and returns the updated session. */
    suspend fun completeStep(skipped: Boolean = false): AutopilotSession? {
        val s = todaySession() ?: return null
        val i = s.index
        if (i >= s.steps.size) return s
        val steps = s.steps.toMutableList().also { it[i] = it[i].copy(done = !skipped, skipped = skipped) }
        var next = s.copy(steps = steps)
        if (next.index >= steps.size && next.finishedAt == null) {
            next = next.copy(finishedAt = clock.now())
            // The session's synthesis completes today's challenge.
            sessions.completeDailyChallenge(if (next.answered == 0) 100 else next.correct * 100 / next.answered)
        }
        save(next)
        return next
    }

    private suspend fun addPriority(item: PriorityItemEntity) {
        val dao = db.priorityDao()
        val exists = if (item.cardId != null) dao.openForCard(item.localDate, item.cardId) else dao.openForRule(item.localDate, item.ruleId ?: "")
        if (exists == 0) dao.insert(item)
    }

    private fun today() = clock.localDate(clock.now())

    private fun load(): AutopilotSession? = runCatching { LexiJson.decodeFromString(AutopilotSession.serializer(), file.readText()) }.getOrNull()

    private fun save(s: AutopilotSession) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(LexiJson.encodeToString(AutopilotSession.serializer(), s))
        tmp.renameTo(file)
    }

    companion object {
        const val AT_RISK_RETRIEVABILITY = 0.8
        private const val DAY_MS = 86_400_000L
    }
}
