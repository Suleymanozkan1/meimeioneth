package com.lexiflow.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lexiflow.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class LockPool(val labelTr: String) { LEARNED("Sadece öğrendiklerim"), MIXED("Karışık (yeni kelimeler dahil)") }
enum class LockDifficulty(val labelTr: String) { EASY("Kolay"), NORMAL("Normal"), HARD("Zor") }
enum class Accent(val labelTr: String) { US("Amerikan"), UK("İngiliz") }

/** User profile + app settings (AppSettings / UserProfile). Credentials are only the anonymous server token. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val displayName: String = "",
    val cefr: String = "B1",
    val dailyGoalXp: Int = 40,
    val newCardsPerDay: Int = 10,
    val desiredRetention: Double = 0.9,
    val lockScreenEnabled: Boolean = false,
    val lockQuestionsPerDay: Int = 8,
    val lockIntervalMinutes: Int = 90,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 8,
    val lockPool: LockPool = LockPool.LEARNED,
    val lockDifficulty: LockDifficulty = LockDifficulty.NORMAL,
    val lockShowTurkish: Boolean = true,
    val lockHideOnSecureScreen: Boolean = false,
    val backendUrl: String = BuildConfig.DEFAULT_BACKEND_URL,
    val aiEnabled: Boolean = false,
    val serverToken: String? = null,
    val playerId: String? = null,
    val saveAnswerTexts: Boolean = true,
    val keepSpeakingDays: Int = 30,
    val keepPronunciationDays: Int = 30,
    val preferOfflineSpeech: Boolean = true,
    /** FSRS card front: Turkish meaning + example with a blank (recall); false shows the word first (recognition). */
    val reviewMeaningFirst: Boolean = true,
    val accent: Accent = Accent.US,
    val lockQuestionCounter: Long = 0,
    val lastLockQuestionAt: Long = 0,
    val builtinContentVersion: Int = 0,
) {
    val backendConfigured: Boolean get() = backendUrl.isNotBlank()
    val aiAvailable: Boolean get() = backendConfigured && aiEnabled && serverToken != null
}

class SettingsRepository(private val context: Context) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val name = stringPreferencesKey("display_name")
        val cefr = stringPreferencesKey("cefr")
        val goal = intPreferencesKey("daily_goal_xp")
        val newPerDay = intPreferencesKey("new_cards_per_day")
        val retention = doublePreferencesKey("desired_retention")
        val lockEnabled = booleanPreferencesKey("lock_enabled")
        val lockPerDay = intPreferencesKey("lock_per_day")
        val lockInterval = intPreferencesKey("lock_interval_min")
        val quietStart = intPreferencesKey("quiet_start")
        val quietEnd = intPreferencesKey("quiet_end")
        val lockPool = stringPreferencesKey("lock_pool")
        val lockDifficulty = stringPreferencesKey("lock_difficulty")
        val lockTurkish = booleanPreferencesKey("lock_turkish")
        val lockHide = booleanPreferencesKey("lock_hide")
        val backend = stringPreferencesKey("backend_url")
        val ai = booleanPreferencesKey("ai_enabled")
        val token = stringPreferencesKey("server_token")
        val playerId = stringPreferencesKey("player_id")
        val saveAnswers = booleanPreferencesKey("save_answers")
        val keepSpeaking = intPreferencesKey("keep_speaking_days")
        val keepPron = intPreferencesKey("keep_pron_days")
        val offlineSpeech = booleanPreferencesKey("prefer_offline_speech")
        val reviewMeaningFirst = booleanPreferencesKey("review_meaning_first")
        val accent = stringPreferencesKey("accent")
        val lockCounter = longPreferencesKey("lock_counter")
        val lastLockAt = longPreferencesKey("last_lock_at")
        val contentVersion = intPreferencesKey("builtin_content_version")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { fromPrefs(it) }

    private fun fromPrefs(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            displayName = p[K.name] ?: d.displayName,
            cefr = p[K.cefr] ?: d.cefr,
            dailyGoalXp = p[K.goal] ?: d.dailyGoalXp,
            newCardsPerDay = p[K.newPerDay] ?: d.newCardsPerDay,
            desiredRetention = p[K.retention] ?: d.desiredRetention,
            lockScreenEnabled = p[K.lockEnabled] ?: d.lockScreenEnabled,
            lockQuestionsPerDay = p[K.lockPerDay] ?: d.lockQuestionsPerDay,
            lockIntervalMinutes = p[K.lockInterval] ?: d.lockIntervalMinutes,
            quietStartHour = p[K.quietStart] ?: d.quietStartHour,
            quietEndHour = p[K.quietEnd] ?: d.quietEndHour,
            lockPool = p[K.lockPool]?.let { runCatching { LockPool.valueOf(it) }.getOrNull() } ?: d.lockPool,
            lockDifficulty = p[K.lockDifficulty]?.let { runCatching { LockDifficulty.valueOf(it) }.getOrNull() } ?: d.lockDifficulty,
            lockShowTurkish = p[K.lockTurkish] ?: d.lockShowTurkish,
            lockHideOnSecureScreen = p[K.lockHide] ?: d.lockHideOnSecureScreen,
            backendUrl = p[K.backend] ?: d.backendUrl,
            aiEnabled = p[K.ai] ?: d.aiEnabled,
            serverToken = p[K.token],
            playerId = p[K.playerId],
            saveAnswerTexts = p[K.saveAnswers] ?: d.saveAnswerTexts,
            keepSpeakingDays = p[K.keepSpeaking] ?: d.keepSpeakingDays,
            keepPronunciationDays = p[K.keepPron] ?: d.keepPronunciationDays,
            preferOfflineSpeech = p[K.offlineSpeech] ?: d.preferOfflineSpeech,
            reviewMeaningFirst = p[K.reviewMeaningFirst] ?: d.reviewMeaningFirst,
            accent = p[K.accent]?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: d.accent,
            lockQuestionCounter = p[K.lockCounter] ?: 0,
            lastLockQuestionAt = p[K.lastLockAt] ?: 0,
            builtinContentVersion = p[K.contentVersion] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = readFrom(p)
            val n = transform(old)
            p[K.onboarding] = n.onboardingDone
            p[K.name] = n.displayName
            p[K.cefr] = n.cefr
            p[K.goal] = n.dailyGoalXp.coerceIn(10, 300)
            p[K.newPerDay] = n.newCardsPerDay.coerceIn(0, 100)
            p[K.retention] = n.desiredRetention.coerceIn(0.7, 0.97)
            p[K.lockEnabled] = n.lockScreenEnabled
            p[K.lockPerDay] = n.lockQuestionsPerDay.coerceIn(1, 30)
            p[K.lockInterval] = n.lockIntervalMinutes.coerceIn(15, 720)
            p[K.quietStart] = n.quietStartHour.coerceIn(0, 23)
            p[K.quietEnd] = n.quietEndHour.coerceIn(0, 23)
            p[K.lockPool] = n.lockPool.name
            p[K.lockDifficulty] = n.lockDifficulty.name
            p[K.lockTurkish] = n.lockShowTurkish
            p[K.lockHide] = n.lockHideOnSecureScreen
            p[K.backend] = n.backendUrl.trim()
            p[K.ai] = n.aiEnabled
            if (n.serverToken != null) p[K.token] = n.serverToken else p.remove(K.token)
            if (n.playerId != null) p[K.playerId] = n.playerId else p.remove(K.playerId)
            p[K.saveAnswers] = n.saveAnswerTexts
            p[K.keepSpeaking] = n.keepSpeakingDays.coerceIn(0, 365)
            p[K.keepPron] = n.keepPronunciationDays.coerceIn(0, 365)
            p[K.offlineSpeech] = n.preferOfflineSpeech
            p[K.reviewMeaningFirst] = n.reviewMeaningFirst
            p[K.accent] = n.accent.name
            p[K.lockCounter] = n.lockQuestionCounter
            p[K.lastLockAt] = n.lastLockQuestionAt
            p[K.contentVersion] = n.builtinContentVersion
        }
    }

    private fun readFrom(p: Preferences): AppSettings = fromPrefs(p)
}
