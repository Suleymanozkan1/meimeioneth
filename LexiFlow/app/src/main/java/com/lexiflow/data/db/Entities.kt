package com.lexiflow.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room schema. Conventions (see docs/DATABASE.md):
 * - All timestamps are epoch milliseconds UTC. Day-based aggregates use the local date (yyyy-MM-dd) captured
 *   at the moment of the activity, so changing time zone never rewrites history.
 * - Enums are stored as their name (TEXT) for readability in exports.
 * - FSRS state lives on the card (1:1) to avoid duplication; review history is append-only in review_logs.
 * - Active-use skills are a separate table (skill_states) so they never alter FSRS state.
 */

@Entity(tableName = "decks", indices = [Index(value = ["name"])])
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    /** BUILTIN | IMPORT_CSV | IMPORT_APKG | USER */
    val source: String,
    val createdAt: Long,
    val archived: Boolean = false,
)

/** A word (Anki "note" equivalent). Rich, non-scheduled information is kept as JSON in [richJson]. */
@Entity(
    tableName = "words",
    foreignKeys = [ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("deckId"), Index("lemmaKey")],
)
data class WordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long,
    val lemma: String,
    /** Normalised lemma for duplicate detection and lookups. */
    val lemmaKey: String,
    val pos: String? = null,
    val cefr: String? = null,
    val ipaUk: String? = null,
    val ipaUs: String? = null,
    val syllables: String? = null,
    val stress: String? = null,
    val register: String? = null,
    val richJson: String = "{}",
    val notes: String? = null,
    val tags: String = "",
    /** BUILTIN | IMPORT | USER | AI */
    val source: String,
    /** e.g. original Anki note id or CSV line, for traceability. */
    val sourceRef: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One meaning of a word. Each sense is its own learning unit (card). */
@Entity(
    tableName = "senses",
    foreignKeys = [ForeignKey(entity = WordEntity::class, parentColumns = ["id"], childColumns = ["wordId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("wordId")],
)
data class SenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val label: String,
    val meaningTr: String,
    val definitionEn: String? = null,
    val position: Int = 0,
)

/** Example sentence. Fill-in-the-blank exercises are derived from [answer]; attempts go to practice_logs. */
@Entity(
    tableName = "examples",
    foreignKeys = [ForeignKey(entity = SenseEntity::class, parentColumns = ["id"], childColumns = ["senseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("senseId"), Index("wordId")],
)
data class ExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senseId: Long,
    val wordId: Long,
    val en: String,
    val tr: String? = null,
    /** daily | friends | work | academic | other_sense | grammar | collocation | phrasal | idiom | question | negative | spoken | user | story */
    val context: String = "daily",
    /** Exact surface form of the target word in [en]. */
    val answer: String,
    val alternativesJson: String = "[]",
    val noteTr: String? = null,
    /** BUILTIN | IMPORT | AI | USER */
    val origin: String,
    val lastShownAt: Long? = null,
    val timesShown: Int = 0,
)

/** Recognition card for one sense, scheduled by FSRS-6. */
@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(entity = SenseEntity::class, parentColumns = ["id"], childColumns = ["senseId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = DeckEntity::class, parentColumns = ["id"], childColumns = ["deckId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["senseId"], unique = true), Index("deckId"), Index("dueAt"), Index("wordId")],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val senseId: Long,
    val wordId: Long,
    val deckId: Long,
    /** 0 NEW, 1 LEARNING, 2 REVIEW, 3 RELEARNING */
    val state: Int = 0,
    val step: Int? = null,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val dueAt: Long = 0,
    val lastReviewAt: Long? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
    val scheduledDays: Int = 0,
    val suspended: Boolean = false,
    val createdAt: Long,
)

/** Append-only FSRS review history (separate from active-production history). */
@Entity(
    tableName = "review_logs",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("reviewedAt"), Index("localDate")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val reviewedAt: Long,
    val localDate: String,
    val rating: Int,
    val stateBefore: Int,
    val elapsedDays: Int,
    val scheduledDays: Int,
    val intervalMillis: Long,
    val stabilityAfter: Double,
    val difficultyAfter: Double,
    val retrievabilityBefore: Double? = null,
    val durationMs: Long = 0,
    /** APP | NOTIFICATION | WIDGET | IMPORT_ANKI */
    val source: String,
    val mode: String = "FSRS_REVIEW",
)

/** Active-use skill state per card (ActiveRecallState). */
@Entity(
    tableName = "skill_states",
    primaryKeys = ["cardId", "skill"],
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("skill", "dueAt")],
)
data class SkillStateEntity(
    val cardId: Long,
    val skill: String,
    val mastery: Double = 0.0,
    val attempts: Int = 0,
    val successes: Int = 0,
    val consecutiveSuccesses: Int = 0,
    val hintLevel: Int = 3,
    val distinctContexts: Int = 0,
    val lastPracticedAt: Long? = null,
    val dueAt: Long = 0,
    val flaggedCantUse: Boolean = false,
)

/** Every active practice attempt (ActiveRecallAttempt), including lock-screen and story questions. */
@Entity(
    tableName = "practice_logs",
    foreignKeys = [ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("cardId"), Index("at"), Index("localDate"), Index("skill")],
)
data class PracticeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val skill: String,
    val mode: String,
    val at: Long,
    val localDate: String,
    val score: Double,
    val hintUsed: Int,
    val verdict: String,
    val exampleId: Long? = null,
    val durationMs: Long = 0,
    /** Learner's own text; only stored when the learner keeps "save my answers" on. */
    val answerText: String? = null,
    /** APP | LOCKSCREEN | STORY | SPEAKING | CULTURE | COMPETITION | WIDGET */
    val source: String = "APP",
)

@Entity(tableName = "stories", indices = [Index("createdAt")])
data class StoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val theme: String,
    val cefr: String,
    /** Serialised StoryResponse (validated). */
    val bodyJson: String,
    /** AI | OFFLINE_TEMPLATE */
    val origin: String,
    val forgettingCurve: Boolean,
    val createdAt: Long,
    val completedAt: Long? = null,
    val bestScore: Int? = null,
)

@Entity(
    tableName = "story_words",
    primaryKeys = ["storyId", "cardId"],
    foreignKeys = [
        ForeignKey(entity = StoryEntity::class, parentColumns = ["id"], childColumns = ["storyId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId")],
)
data class StoryWordEntity(val storyId: Long, val cardId: Long, val lemma: String, val occurrences: Int, val senseHint: String = "")

@Entity(
    tableName = "story_attempts",
    foreignKeys = [ForeignKey(entity = StoryEntity::class, parentColumns = ["id"], childColumns = ["storyId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("storyId")],
)
data class StoryAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storyId: Long,
    val at: Long,
    val correct: Int,
    val total: Int,
)

@Entity(tableName = "speaking_sessions")
data class SpeakingSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topic: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /** AI | OFFLINE_COACH */
    val engine: String,
)

@Entity(
    tableName = "speaking_turns",
    foreignKeys = [ForeignKey(entity = SpeakingSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class SpeakingTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** coach | learner */
    val role: String,
    val text: String,
    /** VOICE | TEXT */
    val inputMode: String = "TEXT",
    val feedbackJson: String? = null,
    val at: Long,
    val speakingMs: Long = 0,
)

/** Only the transcript and the result are stored. Raw audio is never written to disk by LexiFlow. */
@Entity(tableName = "pronunciation_attempts", indices = [Index("at")])
data class PronunciationAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long? = null,
    val target: String,
    val heard: String,
    val intelligibility: Int? = null,
    val reliability: String,
    val resultJson: String,
    val at: Long,
    val mode: String = "PRONUNCIATION_LAB",
)

@Entity(tableName = "xp_transactions", indices = [Index("localDate")])
data class XpTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val localDate: String,
    val reason: String,
    val xp: Int,
    val refId: Long? = null,
)

/** One row per local day with activity (StreakRecord source). */
@Entity(tableName = "activity_days")
data class ActivityDayEntity(
    @PrimaryKey val localDate: String,
    val xp: Int = 0,
    val reviews: Int = 0,
    val newCards: Int = 0,
    val productions: Int = 0,
    val speakingMs: Long = 0,
    val goalXp: Int = 30,
    val goalMet: Boolean = false,
    val tzId: String,
)

@Entity(tableName = "achievements")
data class AchievementEntity(@PrimaryKey val id: String, val unlockedAt: Long)

@Entity(tableName = "daily_challenges")
data class DailyChallengeEntity(
    @PrimaryKey val localDate: String,
    val cardIdsJson: String,
    val completedAt: Long? = null,
    val score: Int? = null,
)

@Entity(tableName = "competitions", indices = [Index("at")])
data class CompetitionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    /** ONLINE | BOT_PRACTICE */
    val kind: String,
    val roomId: String? = null,
    val wordsJson: String,
    val at: Long,
    val rank: Int? = null,
    val players: Int,
)

@Entity(
    tableName = "competition_submissions",
    foreignKeys = [ForeignKey(entity = CompetitionEntity::class, parentColumns = ["id"], childColumns = ["competitionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("competitionId")],
)
data class CompetitionSubmissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val competitionId: Long,
    val playerLabel: String,
    val isMe: Boolean,
    val text: String,
    val overall: Int,
    val breakdownJson: String,
    val corrected: String,
)

/** Outbound operations created offline and sent when the network is back (e.g. moderation reports). */
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val payloadJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

// ---- Added in schema version 2 (see MIGRATION_1_2) ----

/** Origin and licence of cultural content (film / TV / music / user-provided transcripts). */
@Entity(tableName = "content_sources")
data class ContentSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** MUSIC | FILM | TV | ORIGINAL_SCENE | USER_TRANSCRIPT */
    val kind: String,
    val title: String,
    val creator: String? = null,
    val year: Int? = null,
    val sourceUrl: String? = null,
    val styleTag: String? = null,
    /** PUBLIC_DOMAIN | PARAPHRASE | ORIGINAL_LEXIFLOW | USER_PROVIDED_PERSONAL_USE */
    val licenseStatus: String,
    val licenseNote: String,
    /** BUILTIN | USER */
    val origin: String,
    val externalKey: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "cultural_contexts",
    foreignKeys = [ForeignKey(entity = ContentSourceEntity::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sourceId"), Index("targetLemmaKey")],
)
data class CulturalContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val excerpt: String,
    val isQuote: Boolean,
    val location: String? = null,
    val targetLemma: String,
    val targetLemmaKey: String,
    val answer: String,
    val meaningInContextTr: String,
    val tone: String? = null,
    val grammarNoteTr: String? = null,
    val explanationTr: String? = null,
    val productionPromptTr: String? = null,
    val lastShownAt: Long? = null,
)

/** Questions delivered on the lock screen / notification shade and how they were answered. */
@Entity(tableName = "lockscreen_questions", indices = [Index("cardId"), Index("createdAt")])
data class LockScreenQuestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val exampleId: Long? = null,
    /** EN_TO_TR | TR_TO_EN | CLOZE | PRODUCTION */
    val type: String,
    val prompt: String,
    val optionsJson: String = "[]",
    val correctIndex: Int? = null,
    val createdAt: Long,
    val answeredAt: Long? = null,
    val chosenIndex: Int? = null,
    /** KNOW | HARD | CANT_USE | CORRECT | WRONG | DISMISSED */
    val outcome: String? = null,
    @ColumnInfo(defaultValue = "NOTIFICATION") val channel: String = "NOTIFICATION",
)

/**
 * Items the learner got wrong in today's autopilot session (a card or a grammar rule). The lock screen asks these
 * first; weak-word lists treat recent card misses as weak.
 */
@Entity(tableName = "priority_items", indices = [Index("localDate"), Index("cardId")])
data class PriorityItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CARD | GRAMMAR */
    val kind: String,
    val cardId: Long? = null,
    val ruleId: String? = null,
    val reason: String,
    val localDate: String,
    val createdAt: Long,
    val servedAt: Long? = null,
)

// ---- Added in schema version 4 (see MIGRATION_3_4) ----

/**
 * Error log: every wrong (or partly wrong) answer in cloze, sentence building, word production, speaking, dictation
 * and grammar, in one table. [errorType] is a [com.lexiflow.domain.MistakeType] name; [ruleId] links grammar
 * mistakes to their Gramer topic, [cardId] links word mistakes to the word's review.
 */
@Entity(tableName = "mistakes", indices = [Index("at"), Index("localDate"), Index("cardId"), Index("ruleId")])
data class MistakeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long? = null,
    val lemma: String,
    /** CLOZE | SENTENCE | PRODUCTION | TRANSLATION | SPEAKING | DICTATION | GRAMMAR | RECALL | LISTENING */
    val mode: String,
    val errorType: String,
    val ruleId: String? = null,
    val userAnswer: String,
    val correctAnswer: String,
    val at: Long,
    val localDate: String,
    /** APP | AUTOPILOT | SPEAKING */
    val source: String = "APP",
)

/** "Yanlış/yetersiz" marks on AI content. The flagged content is removed from the cache and generated again. */
@Entity(tableName = "ai_content_flags", indices = [Index("at"), Index("contentKey")])
data class AiContentFlagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** STORY | EXPLANATION | FEEDBACK | SENTENCE | ENRICHMENT */
    val kind: String,
    /** Stable key of the flagged item (story id, card id + text hash, ...). */
    val contentKey: String,
    val contentText: String,
    val reason: String = "WRONG_OR_INSUFFICIENT",
    val at: Long,
    /** True once a replacement was generated (or the cached copy was dropped). */
    val regenerated: Boolean = false,
)
