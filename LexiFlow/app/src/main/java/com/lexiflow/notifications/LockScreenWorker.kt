package com.lexiflow.notifications

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lexiflow.LexiFlowApp
import java.util.concurrent.TimeUnit

/**
 * Runs every 15 minutes (WorkManager's minimum) but only posts when the anti-spam gate allows it:
 * enabled, outside quiet hours, under the daily cap and after the configured interval.
 */
class LockScreenWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as LexiFlowApp).container
        return try {
            container.contentLoader.ensureSeeded()
            val s = container.settings.current()
            val force = inputData.getBoolean(KEY_FORCE, false)
            if (!NotificationHelper.canPost(applicationContext)) return Result.success()
            if (!container.lockScreen.shouldPostNow(s, force)) return Result.success()
            container.maintenance.applyRetention()
            val q = container.lockScreen.createQuestion() ?: return Result.success()
            NotificationHelper.showQuestion(applicationContext, q, s.lockHideOnSecureScreen)
            container.widgetUpdater.refresh()
            Result.success()
        } catch (t: Throwable) {
            Log.e("LockScreenWorker", "failed", t)
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE = "lexiflow_lock_questions"
        private const val UNIQUE_NOW = "lexiflow_lock_question_now"
        const val KEY_FORCE = "force"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(UNIQUE); return }
            val request = PeriodicWorkRequestBuilder<LockScreenWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Sends one question immediately (ignores the interval/quiet hours) — used by "send a test question". */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<LockScreenWorker>().setInputData(workDataOf(KEY_FORCE to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
