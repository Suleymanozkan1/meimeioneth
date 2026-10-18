package com.lexiflow.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.lexiflow.LexiFlowApp
import com.lexiflow.data.repo.SelfRating
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Handles answers tapped directly on the notification (lock screen or shade). */
class QuestionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as LexiFlowApp
        val container = app.container
        val id = intent.getLongExtra(EXTRA_QUESTION_ID, -1)
        val value = intent.getIntExtra(EXTRA_VALUE, -1)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val hidden = container.settings.current().lockHideOnSecureScreen
                when (intent.action) {
                    ACTION_ANSWER -> container.lockScreen.answerChoice(id, value)?.let { NotificationHelper.showResult(context, id, it, hidden) }
                    ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(NotificationHelper.KEY_TEXT_REPLY)?.toString().orEmpty()
                        if (text.isNotBlank()) container.lockScreen.answerProduction(id, text)?.let { NotificationHelper.showResult(context, id, it, hidden) }
                    }
                    ACTION_RATE -> {
                        val rating = SelfRating.entries.getOrNull(value) ?: SelfRating.KNOW
                        container.lockScreen.selfRate(id, rating)
                        val msg = if (rating == SelfRating.CANT_USE) "Kelime aktif üretim listene eklendi." else "Cevabın tekrar planına işlendi."
                        NotificationHelper.showThanks(context, msg, hidden)
                    }
                    ACTION_DISMISS -> NotificationHelper.cancel(context)
                }
            } catch (t: Throwable) {
                Log.e("QuestionAction", "Failed to handle notification action", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.lexiflow.action.ANSWER"
        const val ACTION_REPLY = "com.lexiflow.action.REPLY"
        const val ACTION_RATE = "com.lexiflow.action.RATE"
        const val ACTION_DISMISS = "com.lexiflow.action.DISMISS"
        const val EXTRA_QUESTION_ID = "question_id"
        const val EXTRA_VALUE = "value"
    }
}
