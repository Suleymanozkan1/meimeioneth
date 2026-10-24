package com.lexiflow.di

import android.content.Context
import com.lexiflow.data.content.BuiltinContentLoader
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.remote.BackendClient
import com.lexiflow.data.repo.ActiveRecallRepository
import com.lexiflow.data.repo.CompetitionRepository
import com.lexiflow.data.repo.CultureRepository
import com.lexiflow.data.repo.EnrichmentRepository
import com.lexiflow.data.repo.GamificationRepository
import com.lexiflow.data.repo.ImportRepository
import com.lexiflow.data.repo.LockScreenRepository
import com.lexiflow.data.repo.MaintenanceRepository
import com.lexiflow.data.repo.PronunciationRepository
import com.lexiflow.data.repo.SessionRepository
import com.lexiflow.data.repo.SpeakingRepository
import com.lexiflow.data.repo.StatsRepository
import com.lexiflow.data.repo.StoryRepository
import com.lexiflow.data.repo.StudyRepository
import com.lexiflow.data.repo.WordRepository
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock
import com.lexiflow.widget.WidgetUpdater

/** Manual dependency container (small app, no DI framework needed). */
class AppContainer(context: Context, val db: AppDatabase = AppDatabase.build(context), val clock: Clock = Clock.System) {
    val appContext: Context = context.applicationContext
    val settings = SettingsRepository(appContext)
    val backend = BackendClient(settings)
    val gamification = GamificationRepository(db, settings, clock)
    val mistakes = com.lexiflow.data.repo.MistakeRepository(db, clock)
    val words = WordRepository(db, clock)
    val study = StudyRepository(db, settings, gamification, clock)
    val active = ActiveRecallRepository(db, settings, gamification, study, clock)
    val sessions = SessionRepository(db, active, gamification, clock)
    val localContent = com.lexiflow.data.content.LocalContentIndex(appContext)
    val families = com.lexiflow.data.content.WordFamilyIndex(appContext, localContent)
    val importer = ImportRepository(appContext, db, words, study, gamification, clock, localContent, families)
    val deckTools = com.lexiflow.data.repo.DeckToolsRepository(db, words, localContent, families)
    val lockScreen = LockScreenRepository(db, settings, study, active, clock)
    val autopilot = com.lexiflow.data.repo.AutopilotRepository(appContext, db, settings, study, active, sessions, clock, gamification)
    val stories = StoryRepository(db, settings, backend, study, active, gamification, clock)
    val speaking = SpeakingRepository(db, settings, backend, active, gamification, clock, mistakes)
    val pronunciation = PronunciationRepository(db, active, clock)
    val culture = CultureRepository(db, words, active, clock)
    val competition = CompetitionRepository(db, backend, gamification, clock)
    val stats = StatsRepository(db, study, active, clock)
    val maintenance = MaintenanceRepository(db, settings, clock)
    val enrichment = EnrichmentRepository(db, words, backend, settings)
    val aiFeedback = com.lexiflow.data.repo.AiContentFeedbackRepository(db, settings, backend, stories, enrichment, clock)
    val quickAdd = com.lexiflow.data.repo.QuickAddRepository(db, words, localContent, families, enrichment, settings)
    val contentLoader = BuiltinContentLoader(appContext, db, words, settings, clock)
    val setLibrary = com.lexiflow.data.content.SetLibraryRepository(appContext, db, words, localContent)
    val widgetUpdater = WidgetUpdater(appContext)
}
