package com.lexiflow.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Voice settings shared by every [Tts] instance; kept in sync with AppSettings by LexiFlowApp. */
object TtsPreferences {
    /** 1.0 = normal speed; the learner picks 0.6–1.4 in Settings. */
    @Volatile var rate: Float = 1.0f
    /** Chosen voice per accent; null picks the most natural installed voice. */
    @Volatile var voiceUs: String? = null
    @Volatile var voiceUk: String? = null
}

/** A voice the learner can choose in Settings. */
data class VoiceOption(val name: String, val labelTr: String, val quality: Int, val needsNetwork: Boolean)

/**
 * Reference audio via the device's Text-to-Speech engine (US or UK English). It uses the most natural voice the
 * engine has for the accent (highest quality, installed, offline first) unless the learner picked one, and the
 * learner's speed. Listening, dictation and shadowing all go through this class.
 */
class Tts(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile var ready = false
        private set

    override fun onInit(status: Int) { ready = status == TextToSpeech.SUCCESS }

    fun speak(text: String, uk: Boolean = false, slow: Boolean = false): Boolean {
        if (!ready) return false
        val locale = if (uk) Locale.UK else Locale.US
        val voice = pickVoice(uk)
        if (voice != null) {
            tts.voice = voice
        } else {
            val result = tts.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        }
        tts.setSpeechRate((TtsPreferences.rate * if (slow) SLOW_FACTOR else 1f).coerceIn(0.3f, 2f))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
        return true
    }

    /** Installed English voices of the accent, most natural first. Empty until the engine is ready. */
    fun voices(uk: Boolean): List<VoiceOption> = candidates(uk).map { v ->
        val q = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "çok yüksek kalite"
            v.quality >= Voice.QUALITY_HIGH -> "yüksek kalite"
            else -> "normal kalite"
        }
        VoiceOption(v.name, "${v.name} · $q${if (v.isNetworkConnectionRequired) " · internet gerekir" else ""}", v.quality, v.isNetworkConnectionRequired)
    }

    private fun candidates(uk: Boolean): List<Voice> {
        if (!ready) return emptyList()
        val country = if (uk) "GB" else "US"
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "en" && it.locale.country == country }
            .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.latency }.thenBy { it.name })
    }

    private fun pickVoice(uk: Boolean): Voice? {
        val all = candidates(uk)
        val chosen = if (uk) TtsPreferences.voiceUk else TtsPreferences.voiceUs
        return all.firstOrNull { it.name == chosen } ?: all.firstOrNull { !it.isNetworkConnectionRequired } ?: all.firstOrNull()
    }

    fun shutdown() = tts.shutdown()

    companion object { const val SLOW_FACTOR = 0.7f }
}
