package com.lexiflow.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class DeckSummary(
    @Embedded val deck: DeckEntity,
    val wordCount: Int,
    val cardCount: Int,
    val dueCount: Int,
    val newCount: Int,
)

/** Card joined with the word/sense fields every study screen needs. */
data class CardDetail(
    @Embedded val card: CardEntity,
    val lemma: String,
    val pos: String?,
    val cefr: String?,
    val ipaUk: String?,
    val ipaUs: String?,
    val stress: String?,
    val richJson: String,
    val senseLabel: String,
    val meaningTr: String,
    val definitionEn: String?,
    val deckName: String,
)

data class DayCount(val localDate: String, val count: Int)
data class SkillAverage(val skill: String, val avgMastery: Double, val practiced: Int)
data class LemmaMeaning(val lemmaKey: String, val meaningTr: String)

private const val CARD_DETAIL_SELECT = """
    SELECT c.*, w.lemma AS lemma, w.pos AS pos, w.cefr AS cefr, w.ipaUk AS ipaUk, w.ipaUs AS ipaUs, w.stress AS stress,
           w.richJson AS richJson, s.label AS senseLabel, s.meaningTr AS meaningTr, s.definitionEn AS definitionEn,
           d.name AS deckName
    FROM cards c JOIN senses s ON s.id = c.senseId JOIN words w ON w.id = c.wordId JOIN decks d ON d.id = c.deckId
"""

@Dao
interface DeckDao {
    @Insert suspend fun insert(deck: DeckEntity): Long
    @Update suspend fun update(deck: DeckEntity)
    @Query("DELETE FROM decks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM decks WHERE id = :id") suspend fun get(id: Long): DeckEntity?
    @Query("SELECT * FROM decks WHERE name = :name LIMIT 1") suspend fun byName(name: String): DeckEntity?
    @Query("SELECT * FROM decks WHERE source LIKE :pattern ORDER BY id") suspend fun bySourcePrefix(pattern: String): List<DeckEntity>
    @Query("SELECT COUNT(*) FROM decks") suspend fun count(): Int

    @Query(
        """
        SELECT d.*,
          (SELECT COUNT(*) FROM words w WHERE w.deckId = d.id) AS wordCount,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id) AS cardCount,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id AND c.state != 0 AND c.dueAt <= :now AND c.suspended = 0) AS dueCount,
          (SELECT COUNT(*) FROM cards c WHERE c.deckId = d.id AND c.state = 0 AND c.suspended = 0) AS newCount
        FROM decks d WHERE d.archived = 0 ORDER BY d.createdAt
        """,
    )
    fun observeSummaries(now: Long): Flow<List<DeckSummary>>
}

@Dao
interface WordDao {
    @Insert suspend fun insertWord(word: WordEntity): Long
    @Insert suspend fun insertSense(sense: SenseEntity): Long
    @Insert suspend fun insertExample(example: ExampleEntity): Long
    @Insert suspend fun insertExamples(examples: List<ExampleEntity>)
    @Update suspend fun updateWord(word: WordEntity)
    @Update suspend fun updateSense(sense: SenseEntity)
    @Update suspend fun updateExample(example: ExampleEntity)
    @Query("DELETE FROM words WHERE id = :id") suspend fun deleteWord(id: Long)
    @Query("DELETE FROM examples WHERE id = :id") suspend fun deleteExample(id: Long)

    @Query("SELECT * FROM words WHERE id = :id") suspend fun get(id: Long): WordEntity?
    @Query("SELECT * FROM words WHERE id = :id") fun observe(id: Long): Flow<WordEntity?>
    @Query("SELECT * FROM senses WHERE wordId = :wordId ORDER BY position") suspend fun senses(wordId: Long): List<SenseEntity>
    @Query("SELECT * FROM senses WHERE wordId = :wordId ORDER BY position") fun observeSenses(wordId: Long): Flow<List<SenseEntity>>
    @Query("SELECT * FROM senses WHERE id = :id") suspend fun sense(id: Long): SenseEntity?
    @Query("SELECT * FROM examples WHERE senseId = :senseId ORDER BY id") suspend fun examplesForSense(senseId: Long): List<ExampleEntity>
    @Query("SELECT * FROM examples WHERE wordId = :wordId ORDER BY senseId, id") fun observeExamples(wordId: Long): Flow<List<ExampleEntity>>
    @Query("SELECT * FROM examples WHERE wordId = :wordId ORDER BY senseId, id") suspend fun examplesForWord(wordId: Long): List<ExampleEntity>
    @Query("SELECT * FROM examples WHERE id = :id") suspend fun example(id: Long): ExampleEntity?
    @Query("UPDATE examples SET lastShownAt = :at, timesShown = timesShown + 1 WHERE id = :id") suspend fun markShown(id: Long, at: Long)
    @Query("SELECT COUNT(*) FROM examples WHERE wordId = :wordId") suspend fun exampleCount(wordId: Long): Int

    @Query("SELECT * FROM words WHERE deckId = :deckId ORDER BY lemma COLLATE NOCASE") fun observeInDeck(deckId: Long): Flow<List<WordEntity>>
    @Query("SELECT * FROM words WHERE lemma LIKE '%' || :q || '%' ORDER BY lemma COLLATE NOCASE LIMIT 100") fun search(q: String): Flow<List<WordEntity>>
    @Query("SELECT * FROM words WHERE source = 'USER' OR tags LIKE '%wordbook%' ORDER BY updatedAt DESC") fun observePersonal(): Flow<List<WordEntity>>
    @Query("SELECT w.lemmaKey AS lemmaKey, s.meaningTr AS meaningTr FROM words w JOIN senses s ON s.wordId = w.id")
    suspend fun allLemmaMeanings(): List<LemmaMeaning>
    @Query("SELECT * FROM words WHERE lemmaKey = :key") suspend fun byLemmaKey(key: String): List<WordEntity>
    @Query("SELECT COUNT(*) FROM words") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM words WHERE source = 'IMPORT'") suspend fun importedCount(): Int
    @Query("SELECT DISTINCT meaningTr FROM senses ORDER BY RANDOM() LIMIT :n") suspend fun randomMeanings(n: Int): List<String>
    @Query("SELECT DISTINCT lemma FROM words ORDER BY RANDOM() LIMIT :n") suspend fun randomLemmas(n: Int): List<String>
}

@Dao
interface CardDao {
    @Insert suspend fun insert(card: CardEntity): Long
    @Update suspend fun update(card: CardEntity)
    @Query("SELECT * FROM cards WHERE id = :id") suspend fun get(id: Long): CardEntity?
    @Query("SELECT * FROM cards WHERE senseId = :senseId") suspend fun bySense(senseId: Long): CardEntity?
    @Query("SELECT * FROM cards WHERE wordId = :wordId") suspend fun byWord(wordId: Long): List<CardEntity>
    @Query("SELECT * FROM cards WHERE wordId = :wordId") fun observeByWord(wordId: Long): Flow<List<CardEntity>>

    @Query("$CARD_DETAIL_SELECT WHERE c.id = :id") suspend fun detail(id: Long): CardDetail?
    @Query("$CARD_DETAIL_SELECT WHERE c.id IN (:ids)") suspend fun details(ids: List<Long>): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.state != 0 AND c.suspended = 0 AND c.dueAt <= :now AND (:deckId IS NULL OR c.deckId = :deckId) ORDER BY c.dueAt LIMIT :limit")
    suspend fun dueReviews(now: Long, limit: Int, deckId: Long? = null): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.state = 0 AND c.suspended = 0 AND (:deckId IS NULL OR c.deckId = :deckId) ORDER BY c.createdAt, c.id LIMIT :limit")
    suspend fun newCards(limit: Int, deckId: Long? = null): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.suspended = 0 AND c.state != 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomStudied(limit: Int): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.suspended = 0 ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomAny(limit: Int): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.suspended = 0 AND c.state != 0")
    suspend fun allStudied(): List<CardDetail>

    @Query("$CARD_DETAIL_SELECT WHERE c.suspended = 0 AND c.state != 0 AND (c.lapses >= 2 OR c.difficulty >= 7) ORDER BY c.lapses DESC, c.difficulty DESC LIMIT :limit")
    suspend fun weakByFsrs(limit: Int): List<CardDetail>

    @Query("SELECT COUNT(*) FROM cards WHERE state != 0 AND suspended = 0 AND dueAt <= :now") fun observeDueCount(now: Long): Flow<Int>
    @Query("SELECT COUNT(*) FROM cards WHERE state = 0 AND suspended = 0") fun observeNewCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM cards WHERE state = 2") fun observeMatureCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM cards WHERE state != 0") suspend fun studiedCount(): Int
    @Query("SELECT COUNT(*) FROM cards") suspend fun count(): Int
}

@Dao
interface ReviewLogDao {
    @Insert suspend fun insert(log: ReviewLogEntity): Long
    @Insert suspend fun insertAll(logs: List<ReviewLogEntity>)
    @Query("SELECT * FROM review_logs WHERE cardId = :cardId ORDER BY reviewedAt") suspend fun forCard(cardId: Long): List<ReviewLogEntity>
    @Query("SELECT localDate, COUNT(*) AS count FROM review_logs WHERE localDate >= :fromDate GROUP BY localDate ORDER BY localDate")
    fun observePerDay(fromDate: String): Flow<List<DayCount>>
    @Query("SELECT COUNT(*) FROM review_logs WHERE localDate = :date") fun observeCountOn(date: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since") suspend fun countSince(since: Long): Int
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since AND stateBefore = 2") suspend fun matureReviewsSince(since: Long): Int
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt >= :since AND stateBefore = 2 AND rating > 1") suspend fun matureSuccessSince(since: Long): Int
    @Query("SELECT COUNT(DISTINCT cardId) FROM review_logs WHERE reviewedAt >= :since AND stateBefore = 0") suspend fun newLearnedSince(since: Long): Int
    @Query("SELECT COUNT(DISTINCT cardId) FROM review_logs WHERE reviewedAt >= :since") suspend fun distinctReviewedSince(since: Long): Int
    @Query("SELECT COUNT(*) FROM review_logs") suspend fun total(): Int
    @Query("SELECT COUNT(*) FROM review_logs WHERE reviewedAt - :overdueGraceMs > 0 AND elapsedDays > scheduledDays AND scheduledDays > 0 AND reviewedAt >= :since")
    suspend fun overdueClearedSince(since: Long, overdueGraceMs: Long = 0): Int
}

@Dao
interface SkillDao {
    @Upsert suspend fun upsert(state: SkillStateEntity)
    @Query("SELECT * FROM skill_states WHERE cardId = :cardId AND skill = :skill") suspend fun get(cardId: Long, skill: String): SkillStateEntity?
    @Query("SELECT * FROM skill_states WHERE cardId = :cardId") suspend fun forCard(cardId: Long): List<SkillStateEntity>
    @Query("SELECT * FROM skill_states WHERE cardId = :cardId") fun observeForCard(cardId: Long): Flow<List<SkillStateEntity>>
    @Query("SELECT * FROM skill_states WHERE skill = :skill") suspend fun allForSkill(skill: String): List<SkillStateEntity>
    @Query("SELECT * FROM skill_states WHERE flaggedCantUse = 1") fun observeFlagged(): Flow<List<SkillStateEntity>>
    @Query("SELECT skill, AVG(mastery) AS avgMastery, SUM(CASE WHEN attempts > 0 THEN 1 ELSE 0 END) AS practiced FROM skill_states GROUP BY skill")
    fun observeAverages(): Flow<List<SkillAverage>>
}

@Dao
interface PracticeDao {
    @Insert suspend fun insert(log: PracticeLogEntity): Long
    @Query("SELECT * FROM practice_logs WHERE cardId = :cardId ORDER BY at DESC LIMIT 50") suspend fun forCard(cardId: Long): List<PracticeLogEntity>
    @Query("SELECT localDate, COUNT(*) AS count FROM practice_logs WHERE localDate >= :fromDate AND score >= 0.7 GROUP BY localDate ORDER BY localDate")
    fun observeSuccessPerDay(fromDate: String): Flow<List<DayCount>>
    @Query("SELECT COUNT(*) FROM practice_logs WHERE at >= :since AND skill = :skill AND score >= 0.7") suspend fun successesSince(since: Long, skill: String): Int
    @Query("SELECT COUNT(*) FROM practice_logs WHERE at >= :since AND skill = :skill") suspend fun attemptsSince(since: Long, skill: String): Int
    @Query("SELECT COUNT(*) FROM practice_logs WHERE at >= :since AND source = :source") suspend fun countBySourceSince(since: Long, source: String): Int
    @Query("SELECT COUNT(*) FROM practice_logs WHERE skill IN ('PRODUCTION','SPEAKING') AND score >= 0.7") suspend fun productionSuccessTotal(): Int
    @Query("UPDATE practice_logs SET answerText = NULL") suspend fun clearAnswerTexts()
    @Query("SELECT COUNT(*) FROM practice_logs WHERE skill = 'PRODUCTION' AND score >= 0.7 AND hintUsed = 0 AND cardId = :cardId") suspend fun unaidedProductions(cardId: Long): Int
}

@Dao
interface StoryDao {
    @Insert suspend fun insert(story: StoryEntity): Long
    @Update suspend fun update(story: StoryEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertWords(words: List<StoryWordEntity>)
    @Insert suspend fun insertAttempt(attempt: StoryAttemptEntity): Long
    @Query("SELECT * FROM stories ORDER BY createdAt DESC") fun observeAll(): Flow<List<StoryEntity>>
    @Query("SELECT * FROM stories WHERE id = :id") suspend fun get(id: Long): StoryEntity?
    @Query("SELECT * FROM story_words WHERE storyId = :id") suspend fun words(id: Long): List<StoryWordEntity>
    @Query("SELECT COUNT(*) FROM stories WHERE completedAt IS NOT NULL") suspend fun completedCount(): Int
    @Query("SELECT COUNT(*) FROM stories WHERE completedAt >= :since") suspend fun completedSince(since: Long): Int
    @Query("DELETE FROM stories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface SpeakingDao {
    @Insert suspend fun insertSession(s: SpeakingSessionEntity): Long
    @Update suspend fun updateSession(s: SpeakingSessionEntity)
    @Insert suspend fun insertTurn(t: SpeakingTurnEntity): Long
    @Query("SELECT * FROM speaking_turns WHERE sessionId = :id ORDER BY at") fun observeTurns(id: Long): Flow<List<SpeakingTurnEntity>>
    @Query("SELECT * FROM speaking_turns WHERE sessionId = :id ORDER BY at") suspend fun turns(id: Long): List<SpeakingTurnEntity>
    @Query("SELECT * FROM speaking_sessions ORDER BY startedAt DESC") fun observeSessions(): Flow<List<SpeakingSessionEntity>>
    @Query("SELECT COUNT(*) FROM speaking_turns WHERE role = 'learner'") suspend fun learnerTurnCount(): Int
    @Query("SELECT COUNT(*) FROM speaking_turns WHERE role = 'learner' AND at >= :since") suspend fun learnerTurnsSince(since: Long): Int
    @Query("SELECT COALESCE(SUM(speakingMs), 0) FROM speaking_turns WHERE at >= :since") suspend fun speakingMsSince(since: Long): Long
    @Query("DELETE FROM speaking_sessions") suspend fun deleteAll()
    @Query("DELETE FROM speaking_sessions WHERE startedAt < :before") suspend fun deleteOlderThan(before: Long)
}

@Dao
interface PronunciationDao {
    @Insert suspend fun insert(a: PronunciationAttemptEntity): Long
    @Query("SELECT * FROM pronunciation_attempts ORDER BY at DESC LIMIT 50") fun observeRecent(): Flow<List<PronunciationAttemptEntity>>
    @Query("SELECT COUNT(*) FROM pronunciation_attempts") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM pronunciation_attempts WHERE at >= :since") suspend fun countSince(since: Long): Int
    @Query("DELETE FROM pronunciation_attempts") suspend fun deleteAll()
    @Query("DELETE FROM pronunciation_attempts WHERE at < :before") suspend fun deleteOlderThan(before: Long)
    @Query(
        "UPDATE pronunciation_attempts SET cardId = NULL WHERE cardId IN " +
            "(SELECT id FROM cards WHERE deckId = :deckId OR wordId IN (SELECT id FROM words WHERE deckId = :deckId))",
    )
    suspend fun detachDeck(deckId: Long)
}

@Dao
interface GamificationDao {
    @Insert suspend fun insertXp(tx: XpTransactionEntity): Long
    @Query("SELECT COALESCE(SUM(xp), 0) FROM xp_transactions") fun observeTotalXp(): Flow<Int>
    @Query("SELECT COALESCE(SUM(xp), 0) FROM xp_transactions") suspend fun totalXp(): Int
    @Query("SELECT COALESCE(SUM(xp), 0) FROM xp_transactions WHERE localDate = :date") suspend fun xpOn(date: String): Int
    @Query("SELECT localDate, SUM(xp) AS count FROM xp_transactions WHERE localDate >= :fromDate GROUP BY localDate ORDER BY localDate")
    fun observeXpPerDay(fromDate: String): Flow<List<DayCount>>
    @Query("SELECT MAX(dayXp) FROM (SELECT SUM(xp) AS dayXp FROM xp_transactions GROUP BY localDate)") suspend fun bestDayXp(): Int?

    @Upsert suspend fun upsertDay(day: ActivityDayEntity)
    @Query("SELECT * FROM activity_days WHERE localDate = :date") suspend fun day(date: String): ActivityDayEntity?
    @Query("SELECT * FROM activity_days WHERE localDate = :date") fun observeDay(date: String): Flow<ActivityDayEntity?>
    @Query("SELECT * FROM activity_days ORDER BY localDate") fun observeDays(): Flow<List<ActivityDayEntity>>
    @Query("SELECT * FROM activity_days ORDER BY localDate") suspend fun days(): List<ActivityDayEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun unlock(a: AchievementEntity): Long
    @Query("SELECT * FROM achievements") fun observeAchievements(): Flow<List<AchievementEntity>>

    @Upsert suspend fun upsertChallenge(c: DailyChallengeEntity)
    @Query("SELECT * FROM daily_challenges WHERE localDate = :date") suspend fun challenge(date: String): DailyChallengeEntity?
    @Query("SELECT * FROM daily_challenges WHERE localDate = :date") fun observeChallenge(date: String): Flow<DailyChallengeEntity?>
}

@Dao
interface CompetitionDao {
    @Insert suspend fun insert(c: CompetitionEntity): Long
    @Insert suspend fun insertSubmissions(s: List<CompetitionSubmissionEntity>)
    @Query("SELECT * FROM competitions ORDER BY at DESC LIMIT 30") fun observeRecent(): Flow<List<CompetitionEntity>>
    @Query("SELECT * FROM competition_submissions WHERE competitionId = :id") suspend fun submissions(id: Long): List<CompetitionSubmissionEntity>
    @Query("SELECT COUNT(*) FROM competitions") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM competitions WHERE at >= :since") suspend fun countSince(since: Long): Int
    @Query("SELECT MAX(overall) FROM competition_submissions WHERE isMe = 1") suspend fun bestScore(): Int?
}

@Dao
interface SyncDao {
    @Insert suspend fun enqueue(item: SyncQueueEntity): Long
    @Query("SELECT * FROM sync_queue ORDER BY createdAt LIMIT :limit") suspend fun pending(limit: Int = 20): List<SyncQueueEntity>
    @Query("DELETE FROM sync_queue WHERE id = :id") suspend fun remove(id: Long)
    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastError = :error WHERE id = :id") suspend fun markFailed(id: Long, error: String)
}

@Dao
interface ContentDao {
    @Insert suspend fun insertSource(s: ContentSourceEntity): Long
    @Insert suspend fun insertContexts(c: List<CulturalContextEntity>)
    @Query("SELECT * FROM content_sources WHERE externalKey = :key LIMIT 1") suspend fun sourceByKey(key: String): ContentSourceEntity?
    @Query("SELECT * FROM content_sources WHERE id = :id") suspend fun source(id: Long): ContentSourceEntity?
    @Query("SELECT * FROM content_sources ORDER BY kind, title") fun observeSources(): Flow<List<ContentSourceEntity>>
    @Query("SELECT cc.* FROM cultural_contexts cc JOIN content_sources s ON s.id = cc.sourceId WHERE s.kind IN (:kinds) ORDER BY cc.lastShownAt IS NOT NULL, cc.lastShownAt, cc.id")
    suspend fun contextsForKinds(kinds: List<String>): List<CulturalContextEntity>
    @Query("SELECT * FROM cultural_contexts WHERE id = :id") suspend fun context(id: Long): CulturalContextEntity?
    @Query("SELECT * FROM cultural_contexts WHERE targetLemmaKey = :key") suspend fun contextsForLemma(key: String): List<CulturalContextEntity>
    @Query("UPDATE cultural_contexts SET lastShownAt = :at WHERE id = :id") suspend fun markShown(id: Long, at: Long)
    @Query("DELETE FROM content_sources WHERE id = :id") suspend fun deleteSource(id: Long)
    @Query("SELECT COUNT(*) FROM content_sources WHERE origin = 'BUILTIN'") suspend fun builtinCount(): Int
}

@Dao
interface LockScreenDao {
    @Insert suspend fun insert(q: LockScreenQuestionEntity): Long
    @Update suspend fun update(q: LockScreenQuestionEntity)
    @Query("SELECT * FROM lockscreen_questions WHERE id = :id") suspend fun get(id: Long): LockScreenQuestionEntity?
    /** Returns 1 when this call recorded the answer, 0 when the question was already answered. */
    @Query("UPDATE lockscreen_questions SET answeredAt = :at, chosenIndex = :chosen, outcome = :outcome, channel = :channel WHERE id = :id AND answeredAt IS NULL")
    suspend fun markAnswered(id: Long, at: Long, chosen: Int?, outcome: String, channel: String): Int
    @Query("SELECT COUNT(*) FROM lockscreen_questions WHERE createdAt >= :since") suspend fun countSince(since: Long): Int
    @Query("SELECT COUNT(*) FROM lockscreen_questions") suspend fun total(): Int
    @Query("SELECT * FROM lockscreen_questions ORDER BY createdAt DESC LIMIT 1") suspend fun latest(): LockScreenQuestionEntity?
    @Query("SELECT * FROM lockscreen_questions ORDER BY createdAt DESC LIMIT 30") fun observeRecent(): Flow<List<LockScreenQuestionEntity>>
    /** Open questions of a deck that is being deleted (answered ones stay as history). */
    @Query(
        "DELETE FROM lockscreen_questions WHERE answeredAt IS NULL AND cardId IN " +
            "(SELECT id FROM cards WHERE deckId = :deckId OR wordId IN (SELECT id FROM words WHERE deckId = :deckId))",
    )
    suspend fun deleteOpenForDeck(deckId: Long)
}

@Dao
interface TransactionDao {
    @Transaction
    @Query("SELECT 1")
    suspend fun ping(): Int
}
