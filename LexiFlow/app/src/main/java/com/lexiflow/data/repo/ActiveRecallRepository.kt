package com.lexiflow.data.repo

import com.lexiflow.core.active.ActivePracticeScheduler
import com.lexiflow.core.active.PracticeResult
import com.lexiflow.core.active.Skill
import com.lexiflow.core.active.SkillState
import com.lexiflow.core.gamification.XpReason
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardDetail
import com.lexiflow.data.db.PracticeLogEntity
import com.lexiflow.data.db.SkillStateEntity
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock

fun SkillStateEntity.toCore() = SkillState(
    Skill.of(skill), mastery, attempts, successes, consecutiveSuccesses, hintLevel, distinctContexts, lastPracticedAt, dueAt, flaggedCantUse,
)

fun SkillState.toEntity(cardId: Long) = SkillStateEntity(
    cardId, skill.name, mastery, attempts, successes, consecutiveSuccesses, hintLevel, distinctContexts, lastPracticedAt, dueAt, flaggedCantUse,
)

/** Where the learner is on the passive -> active ladder for one card. */
enum class ActiveStage(val labelTr: String) {
    RECALL("1. Türkçeden İngilizceyi hatırla"),
    CLOZE("2. Boşluk doldur"),
    SENTENCE("3. İpucusuz cümle yaz"),
    SPEAKING("4. Sesli kullan"),
    RETEST("5. Farklı bağlamda yeniden test"),
    MASTERED("Aktif kullanımda"),
}

data class ActiveItem(val card: CardDetail, val stage: ActiveStage, val states: Map<Skill, SkillState>, val priority: Double)

/**
 * Active-use practice (separate from FSRS). Writes skill_states + practice_logs and never touches the
 * card's FSRS fields. See docs/ACTIVE_RECALL.md for the rules.
 */
class ActiveRecallRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val gamification: GamificationRepository,
    private val study: StudyRepository,
    private val clock: Clock,
) {
    private val scheduler = ActivePracticeScheduler()
    private val skills = db.skillDao()
    private val practice = db.practiceDao()

    suspend fun state(cardId: Long, skill: Skill): SkillState = skills.get(cardId, skill.name)?.toCore() ?: SkillState(skill)

    suspend fun states(cardId: Long): Map<Skill, SkillState> {
        val stored = skills.forCard(cardId).associate { Skill.of(it.skill) to it.toCore() }
        return Skill.entries.filter { it != Skill.RECOGNITION }.associateWith { stored[it] ?: SkillState(it) }
    }

    fun observeStates(cardId: Long) = skills.observeForCard(cardId)
    fun observeAverages() = skills.observeAverages()
    fun observeFlagged() = skills.observeFlagged()

    /**
     * Records one active attempt. [score] 0..1 comes from the evaluator; [hintUsed] is the visible hint level.
     * Returns the updated state.
     */
    suspend fun record(
        cardId: Long,
        skill: Skill,
        score: Double,
        hintUsed: Int,
        mode: String,
        verdict: String,
        exampleId: Long? = null,
        answerText: String? = null,
        source: String = "APP",
        durationMs: Long = 0,
        awardXp: Boolean = true,
    ): SkillState {
        val now = clock.now()
        val previous = practice.forCard(cardId).filter { it.score >= ActivePracticeScheduler.SUCCESS }
        val newContext = previous.none { it.mode == mode && it.exampleId == exampleId }
        val updated = scheduler.update(state(cardId, skill), PracticeResult(score, hintUsed, newContext, now))
        skills.upsert(updated.toEntity(cardId))
        val keepText = settings.current().saveAnswerTexts
        practice.insert(
            PracticeLogEntity(
                cardId = cardId, skill = skill.name, mode = mode, at = now, localDate = clock.localDate(now), score = score,
                hintUsed = hintUsed, verdict = verdict, exampleId = exampleId, durationMs = durationMs,
                answerText = if (keepText) answerText?.take(1000) else null, source = source,
            ),
        )
        val success = score >= ActivePracticeScheduler.SUCCESS
        val reason = when (skill) {
            Skill.PRODUCTION -> if (success) XpReason.PRODUCTION_SUCCESS else XpReason.PRODUCTION_ATTEMPT
            Skill.SPEAKING -> if (success) XpReason.PRODUCTION_SUCCESS else XpReason.PRODUCTION_ATTEMPT
            Skill.PRONUNCIATION -> XpReason.PRONUNCIATION_ATTEMPT
            Skill.RECALL, Skill.CONTEXT -> if (success) XpReason.ACTIVE_RECALL_SUCCESS else null
            Skill.MEANING_RECALL, Skill.RECOGNITION -> if (success) XpReason.REVIEW else null
        }
        // Unaided success earns full XP; hinted answers earn less (rewards real recall).
        val multiplier = if (hintUsed == 0) 1.0 else 0.5
        if (reason != null && awardXp) {
            gamification.award(reason, cardId, ActivityDelta(productions = if (success && skill in Skill.ACTIVE) 1 else 0), multiplier)
        }
        return updated
    }

    /** "I recognise it but can't use it": push the card into the active-production queue. */
    suspend fun flagCantUse(cardId: Long, source: String = "APP") {
        val now = clock.now()
        listOf(Skill.RECALL, Skill.PRODUCTION, Skill.SPEAKING).forEach { s ->
            skills.upsert(scheduler.flagCantUse(state(cardId, s), now).toEntity(cardId))
        }
        practice.insert(
            PracticeLogEntity(cardId = cardId, skill = Skill.PRODUCTION.name, mode = "CANT_USE_SIGNAL", at = now, localDate = clock.localDate(now),
                score = 0.0, hintUsed = 0, verdict = "CANT_USE", source = source),
        )
    }

    suspend fun stageFor(cardId: Long, states: Map<Skill, SkillState>): ActiveStage {
        val recall = states.getValue(Skill.RECALL)
        val context = states.getValue(Skill.CONTEXT)
        val production = states.getValue(Skill.PRODUCTION)
        val speaking = states.getValue(Skill.SPEAKING)
        return when {
            recall.mastery < 0.6 || (recall.flaggedCantUse && recall.successes == 0) -> ActiveStage.RECALL
            context.mastery < 0.6 -> ActiveStage.CLOZE
            production.mastery < 0.6 || production.hintLevel > 0 -> ActiveStage.SENTENCE
            speaking.mastery < 0.5 -> ActiveStage.SPEAKING
            !isActiveMastered(cardId, production) -> ActiveStage.RETEST
            else -> ActiveStage.MASTERED
        }
    }

    /** Never declare mastery from a single answer: needs >= 3 distinct contexts on >= 2 different days. */
    suspend fun isActiveMastered(cardId: Long, production: SkillState): Boolean {
        if (production.mastery < 0.75 || production.distinctContexts < 3) return false
        val days = practice.forCard(cardId)
            .filter { (it.skill == Skill.PRODUCTION.name || it.skill == Skill.SPEAKING.name) && it.score >= 0.7 }
            .map { it.localDate }.toSet()
        return days.size >= 2
    }

    /** Cards for the active-production queue, highest priority first. */
    suspend fun productionQueue(limit: Int = 20, onlyDue: Boolean = true): List<ActiveItem> {
        val now = clock.now()
        val studied = db.cardDao().allStudied()
        // Passive words (never used correctly without hints) come before words that are already active.
        val activeIds = practice.activeCardIds().toSet()
        val items = studied.map { c ->
            val st = states(c.card.id)
            val production = st.getValue(Skill.PRODUCTION)
            val r = study.retrievability(c.card)
            ActiveItem(c, stageFor(c.card.id, st), st, scheduler.priority(production, r, now) + if (c.card.id in activeIds) 0.0 else PASSIVE_BOOST)
        }
        return items
            .filter { it.stage != ActiveStage.MASTERED }
            .filter { !onlyDue || it.states.values.any { s -> s.dueAt <= now } }
            .sortedByDescending { it.priority }
            .take(limit)
    }

    /** Weak words: FSRS lapses/difficulty, low active mastery, or the "can't use" flag. */
    suspend fun weakWords(limit: Int = 30): List<ActiveItem> {
        val byFsrs = db.cardDao().weakByFsrs(limit).map { it.card.id }.toSet()
        // Words missed in the autopilot during the last week count as weak too.
        val missed = db.priorityDao().recentCardIds(clock.now() - 7 * 86_400_000L).toSet()
        return db.cardDao().allStudied().map { c ->
            val st = states(c.card.id)
            val prod = st.getValue(Skill.PRODUCTION)
            val weakness = (if (c.card.id in byFsrs) 1.0 else 0.0) + (1 - prod.mastery) * (if (prod.attempts > 0) 1.0 else 0.5) +
                (if (prod.flaggedCantUse) 1.5 else 0.0) + (1 - (study.retrievability(c.card) ?: 1.0)) + (if (c.card.id in missed) 1.0 else 0.0)
            ActiveItem(c, stageFor(c.card.id, st), st, weakness)
        }.filter { it.priority >= 1.0 }.sortedByDescending { it.priority }.take(limit)
    }

    suspend fun clearAnswerTexts() = practice.clearAnswerTexts()

    companion object { private const val PASSIVE_BOOST = 1.0 }
}
