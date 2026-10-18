package com.lexiflow

import android.app.Application
import com.lexiflow.di.AppContainer
import com.lexiflow.notifications.LockScreenWorker
import com.lexiflow.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LexiFlowApp : Application() {
    lateinit var container: AppContainer
        private set
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationHelper.createChannels(this)
        appScope.launch {
            container.contentLoader.ensureSeeded()
            val s = container.settings.current()
            runCatching { LockScreenWorker.schedule(this@LexiFlowApp, s.lockScreenEnabled) }
                .onFailure { android.util.Log.w("LexiFlowApp", "WorkManager not available", it) }
            container.maintenance.applyRetention()
        }
    }
}
