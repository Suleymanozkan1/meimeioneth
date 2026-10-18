package com.lexiflow.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reference audio via the device's Text-to-Speech engine (US or UK English). */
class Tts(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile var ready = false
        private set

    override fun onInit(status: Int) { ready = status == TextToSpeech.SUCCESS }

    fun speak(text: String, uk: Boolean = false, slow: Boolean = false): Boolean {
        if (!ready) return false
        val result = tts.setLanguage(if (uk) Locale.UK else Locale.US)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setSpeechRate(if (slow) 0.7f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        return true
    }

    fun shutdown() = tts.shutdown()
}
