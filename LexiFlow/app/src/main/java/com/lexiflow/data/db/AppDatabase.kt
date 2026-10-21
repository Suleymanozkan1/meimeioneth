package com.lexiflow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    version = AppDatabase.VERSION,
    exportSchema = true,
    entities = [
        DeckEntity::class, WordEntity::class, SenseEntity::class, ExampleEntity::class, CardEntity::class,
        ReviewLogEntity::class, SkillStateEntity::class, PracticeLogEntity::class,
        StoryEntity::class, StoryWordEntity::class, StoryAttemptEntity::class,
        SpeakingSessionEntity::class, SpeakingTurnEntity::class, PronunciationAttemptEntity::class,
        XpTransactionEntity::class, ActivityDayEntity::class, AchievementEntity::class, DailyChallengeEntity::class,
        CompetitionEntity::class, CompetitionSubmissionEntity::class, SyncQueueEntity::class,
        ContentSourceEntity::class, CulturalContextEntity::class, LockScreenQuestionEntity::class, PriorityItemEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao
    abstract fun wordDao(): WordDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun skillDao(): SkillDao
    abstract fun practiceDao(): PracticeDao
    abstract fun storyDao(): StoryDao
    abstract fun speakingDao(): SpeakingDao
    abstract fun pronunciationDao(): PronunciationDao
    abstract fun gamificationDao(): GamificationDao
    abstract fun competitionDao(): CompetitionDao
    abstract fun syncDao(): SyncDao
    abstract fun contentDao(): ContentDao
    abstract fun lockScreenDao(): LockScreenDao
    abstract fun priorityDao(): PriorityDao

    companion object {
        const val VERSION = 3
        const val NAME = "lexiflow.db"

        /** v1 -> v2: cultural content (film/TV/music) with licence metadata, and lock-screen question log. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `content_sources` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `creator` TEXT, `year` INTEGER, `sourceUrl` TEXT, `styleTag` TEXT, `licenseStatus` TEXT NOT NULL, " +
                        "`licenseNote` TEXT NOT NULL, `origin` TEXT NOT NULL, `externalKey` TEXT, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cultural_contexts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceId` INTEGER NOT NULL, " +
                        "`excerpt` TEXT NOT NULL, `isQuote` INTEGER NOT NULL, `location` TEXT, `targetLemma` TEXT NOT NULL, `targetLemmaKey` TEXT NOT NULL, " +
                        "`answer` TEXT NOT NULL, `meaningInContextTr` TEXT NOT NULL, `tone` TEXT, `grammarNoteTr` TEXT, `explanationTr` TEXT, " +
                        "`productionPromptTr` TEXT, `lastShownAt` INTEGER, " +
                        "FOREIGN KEY(`sourceId`) REFERENCES `content_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cultural_contexts_sourceId` ON `cultural_contexts` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cultural_contexts_targetLemmaKey` ON `cultural_contexts` (`targetLemmaKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lockscreen_questions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, " +
                        "`exampleId` INTEGER, `type` TEXT NOT NULL, `prompt` TEXT NOT NULL, `optionsJson` TEXT NOT NULL, `correctIndex` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, `answeredAt` INTEGER, `chosenIndex` INTEGER, `outcome` TEXT, " +
                        "`channel` TEXT NOT NULL DEFAULT 'NOTIFICATION')",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lockscreen_questions_cardId` ON `lockscreen_questions` (`cardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_lockscreen_questions_createdAt` ON `lockscreen_questions` (`createdAt`)")
            }
        }

        /** v2 -> v3: autopilot misses that the lock screen asks first. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `priority_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`cardId` INTEGER, `ruleId` TEXT, `reason` TEXT NOT NULL, `localDate` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `servedAt` INTEGER)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_priority_items_localDate` ON `priority_items` (`localDate`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_priority_items_cardId` ON `priority_items` (`cardId`)")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
