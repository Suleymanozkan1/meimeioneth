package com.lexiflow.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.lexiflow.MainActivity
import com.lexiflow.R
import com.lexiflow.core.exercise.QuickQuestionType
import com.lexiflow.data.repo.LockAnswerResult
import com.lexiflow.data.repo.LockQuestion
import com.lexiflow.data.repo.SelfRating

/**
 * Lock-screen word questions as standard notifications. We never draw over the keyguard or bypass the
 * secure lock screen: Android itself decides what is visible there (see docs/LOCK_SCREEN.md).
 * Buttons use broadcast PendingIntents, which Android lets the user tap from the lock screen without
 * unlocking (setAuthenticationRequired(false) is the default); inline text replies may require unlocking.
 */
object NotificationHelper {
    const val CHANNEL_QUESTIONS = "lock_questions"
    const val QUESTION_NOTIFICATION_ID = 4101
    const val KEY_TEXT_REPLY = "reply_text"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_QUESTIONS, "Kilit ekranı kelime soruları", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Telefonu açtığında kısa İngilizce kelime soruları. Ayarlardan sıklık ve sessiz saatler değiştirilebilir."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openIntent(context: Context, questionId: Long): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("lexiflow://lockquestion/$questionId"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, (questionId % 100000).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(context: Context, action: String, questionId: Long, value: Int, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, QuestionActionReceiver::class.java).apply {
            this.action = action
            putExtra(QuestionActionReceiver.EXTRA_QUESTION_ID, questionId)
            putExtra(QuestionActionReceiver.EXTRA_VALUE, value)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (questionId * 10 + value).toInt() + action.hashCode(), intent, flags)
    }

    private fun base(context: Context, hidden: Boolean): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_QUESTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(if (hidden) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (hidden) setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_QUESTIONS).setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle("LexiFlow").setContentText("Yeni bir kelime sorusu var").build(),
                )
            }

    // Permission is checked by canPost() at the start of the function.
    @SuppressLint("MissingPermission")
    fun showQuestion(context: Context, q: LockQuestion, hidden: Boolean) {
        if (!canPost(context)) return
        val type = QuickQuestionType.valueOf(q.entity.type)
        val builder = base(context, hidden)
            .setContentTitle("LexiFlow · ${type.labelTr}")
            .setContentText(q.entity.prompt)
            .setStyle(NotificationCompat.BigTextStyle().bigText(q.entity.prompt))
            .setContentIntent(openIntent(context, q.entity.id))
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60 * 60_000L)
        if (type == QuickQuestionType.PRODUCTION) {
            val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY).setLabel("Cümleni yaz").build()
            builder.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_notification, "Cümle yaz",
                    actionIntent(context, QuestionActionReceiver.ACTION_REPLY, q.entity.id, 0, mutable = true))
                    .addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build(),
            )
            builder.addAction(R.drawable.ic_notification, "Uygulamada aç", openIntent(context, q.entity.id))
        } else {
            q.options.take(3).forEachIndexed { i, opt ->
                builder.addAction(R.drawable.ic_notification, opt.take(28), actionIntent(context, QuestionActionReceiver.ACTION_ANSWER, q.entity.id, i))
            }
        }
        NotificationManagerCompat.from(context).notify(QUESTION_NOTIFICATION_ID, builder.build())
    }

    // Permission is checked by canPost() at the start of the function.
    @SuppressLint("MissingPermission")
    fun showResult(context: Context, questionId: Long, result: LockAnswerResult, hidden: Boolean) {
        if (!canPost(context)) return
        val builder = base(context, hidden)
            .setContentTitle(result.titleTr)
            .setContentText(result.bodyTr.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(result.bodyTr))
            .setContentIntent(openIntent(context, questionId))
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
        if (result.canSelfRate) {
            SelfRating.entries.forEach { r ->
                builder.addAction(R.drawable.ic_notification, r.labelTr, actionIntent(context, QuestionActionReceiver.ACTION_RATE, questionId, r.ordinal))
            }
        } else {
            builder.addAction(R.drawable.ic_notification, "Anladım", actionIntent(context, QuestionActionReceiver.ACTION_DISMISS, questionId, 0))
            builder.addAction(R.drawable.ic_notification, "Uygulamada çalış", openIntent(context, questionId))
        }
        NotificationManagerCompat.from(context).notify(QUESTION_NOTIFICATION_ID, builder.build())
    }

    // Permission is checked by canPost() at the start of the function.
    @SuppressLint("MissingPermission")
    fun showThanks(context: Context, text: String, hidden: Boolean) {
        if (!canPost(context)) return
        val n = base(context, hidden).setContentTitle("Kaydedildi").setContentText(text).setTimeoutAfter(8_000L).setAutoCancel(true).build()
        NotificationManagerCompat.from(context).notify(QUESTION_NOTIFICATION_ID, n)
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(QUESTION_NOTIFICATION_ID)
}
