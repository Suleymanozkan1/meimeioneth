package com.lexiflow.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.lexiflow.LexiFlowApp
import com.lexiflow.MainActivity
import com.lexiflow.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget: "Günün kelimesi" (one word per local day, from today's due or new cards), its meaning on tap
 * ("Anlamı göster"), another word, and "Tekrara başla" which opens the FSRS review in one tap.
 * Nothing personal (own sentences) is shown.
 */
class WordWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { WidgetUpdater(context).render(reveal = false, next = false) } finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REVEAL || intent.action == ACTION_NEXT) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { WidgetUpdater(context).render(reveal = intent.action == ACTION_REVEAL, next = intent.action == ACTION_NEXT) } finally { pending.finish() }
            }
        }
    }

    companion object {
        const val ACTION_REVEAL = "com.lexiflow.widget.REVEAL"
        const val ACTION_NEXT = "com.lexiflow.widget.NEXT"
    }
}

class WidgetUpdater(private val context: Context) {
    private val prefs = context.getSharedPreferences("widget", Context.MODE_PRIVATE)

    /** Periodic refresh: keeps the word of the day (a new one comes with the new day) and updates the due count. */
    suspend fun refresh() = render(reveal = false, next = false)

    suspend fun render(reveal: Boolean, next: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WordWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val container = (context.applicationContext as LexiFlowApp).container
        val db = container.db
        val today = java.time.LocalDate.now().toString()
        var cardId = prefs.getLong("card", -1)
        // A new day brings a new word of the day; "Başka kelime" changes it for the rest of the day.
        if (next || prefs.getString("day", null) != today || cardId < 0 || db.cardDao().get(cardId) == null) {
            val pick = db.cardDao().dueReviews(System.currentTimeMillis(), 10).randomOrNull() ?: db.cardDao().newCards(1).firstOrNull()
                ?: db.cardDao().randomStudied(1).firstOrNull()
            cardId = pick?.card?.id ?: -1
            prefs.edit().putLong("card", cardId).putString("day", today).apply()
        }
        val due = db.cardDao().dueReviews(System.currentTimeMillis(), 500).size
        val detail = if (cardId >= 0) db.cardDao().detail(cardId) else null
        val example = detail?.let { d -> db.wordDao().examplesForWord(d.card.wordId).firstOrNull { it.senseId == d.card.senseId } }
        val showTr = container.settings.current().lockShowTurkish
        val views = RemoteViews(context.packageName, R.layout.widget_word)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse("lexiflow://review"), context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (detail == null) {
            views.setTextViewText(R.id.widget_word, "LexiFlow")
            views.setTextViewText(R.id.widget_detail, "Kelime eklemek için dokun")
        } else {
            views.setTextViewText(R.id.widget_word, detail.lemma)
            val text = if (reveal) buildString {
                if (showTr) append(detail.meaningTr) else append(detail.definitionEn ?: detail.meaningTr)
                example?.let { append("\n").append(it.en) }
            } else (detail.ipaUs ?: detail.ipaUk ?: "Anlamını hatırlıyor musun?")
            views.setTextViewText(R.id.widget_detail, text)
        }
        views.setTextViewText(R.id.widget_review, if (due > 0) "▶ Tekrara başla · $due kart" else "▶ Yeni kelimelerle başla")
        views.setOnClickPendingIntent(R.id.widget_review, PendingIntent.getActivity(context, 4, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        views.setOnClickPendingIntent(R.id.widget_reveal, broadcast(WordWidgetProvider.ACTION_REVEAL, 1))
        views.setOnClickPendingIntent(R.id.widget_next, broadcast(WordWidgetProvider.ACTION_NEXT, 2))
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        manager.updateAppWidget(ids, views)
    }

    private fun broadcast(action: String, code: Int): PendingIntent =
        PendingIntent.getBroadcast(context, code, Intent(context, WordWidgetProvider::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
