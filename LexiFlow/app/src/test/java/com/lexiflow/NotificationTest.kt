package com.lexiflow

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.notifications.NotificationHelper
import com.lexiflow.notifications.QuestionActionReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationTest {
    private val app get() = ApplicationProvider.getApplicationContext<LexiFlowApp>()

    @Test fun questionNotificationHasAnswerButtonsAndRespectsPrivacy() = runBlocking {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val c = app.container
        c.contentLoader.ensureSeeded()
        c.study.reviewQueue().take(3).forEach { c.study.answer(it.card.id, Rating.EASY) }
        NotificationHelper.createChannels(app)
        var q = c.lockScreen.createQuestion()!!
        while (q.options.isEmpty()) q = c.lockScreen.createQuestion()!!
        NotificationHelper.showQuestion(app, q, hidden = true)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = shadowOf(nm).getNotification(NotificationHelper.QUESTION_NOTIFICATION_ID)
        assertNotNull(n)
        assertEquals(3, n.actions.size)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        assertEquals(NotificationHelper.CHANNEL_QUESTIONS, n.channelId)
        assertTrue(NotificationCompat.getContentText(n).toString().isNotBlank())

        // Simulate tapping the correct option on the lock screen.
        val intent = Intent(app, QuestionActionReceiver::class.java).setAction(QuestionActionReceiver.ACTION_ANSWER)
            .putExtra(QuestionActionReceiver.EXTRA_QUESTION_ID, q.entity.id).putExtra(QuestionActionReceiver.EXTRA_VALUE, q.entity.correctIndex!!)
        app.sendBroadcast(intent)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val deadline = System.currentTimeMillis() + 10_000
        while (c.db.lockScreenDao().get(q.entity.id)!!.answeredAt == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("CORRECT", c.db.lockScreenDao().get(q.entity.id)!!.outcome)
        while (shadowOf(nm).getNotification(NotificationHelper.QUESTION_NOTIFICATION_ID)?.actions?.firstOrNull()?.title?.toString() != "Biliyorum" && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val result = shadowOf(nm).getNotification(NotificationHelper.QUESTION_NOTIFICATION_ID)
        assertEquals(listOf("Biliyorum", "Zorlandım", "Tanıyorum ama kullanamıyorum"), result.actions.map { it.title.toString() })
    }
}
