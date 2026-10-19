package com.lexiflow.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.lexiflow.core.pronunciation.AsrHypothesis
import com.lexiflow.core.pronunciation.AudioSignals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class SpeechState {
    data object Idle : SpeechState()
    data class Listening(val rmsDb: Float, val partial: String) : SpeechState()
    data class Result(val hypotheses: List<AsrHypothesis>, val audio: AudioSignals) : SpeechState()
    data class Error(val messageTr: String) : SpeechState()
    /** Stop was pressed; waiting for the recogniser's final text. */
    data object Processing : SpeechState()
}

/**
 * Wraps Android's SpeechRecognizer. Audio is handled by the system recogniser; LexiFlow never records or
 * stores raw audio. With "prefer offline" the on-device model is requested where the device supports it,
 * otherwise the system service may process speech on its servers (disclosed in Settings > Privacy).
 */
class SpeechInput(private val context: Context) {
    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L
    private var peakRms = -100f
    private var speechDetected = false
    private var lastPartial = ""
    private var stopping = false
    private var session = 0
    private var language = "en-US"
    private var offlineRetryLeft = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun start(languageTag: String = "en-US", preferOffline: Boolean = true) {
        if (!isAvailable()) { _state.value = SpeechState.Error("Bu cihazda konuşma tanıma hizmeti yok. Yazarak cevap verebilirsin."); return }
        language = languageTag
        offlineRetryLeft = preferOffline
        listen(preferOffline)
    }

    private fun listen(preferOffline: Boolean) {
        release()
        val id = ++session
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        peakRms = -100f
        speechDetected = false
        lastPartial = ""
        stopping = false
        startedAt = 0L
        // Shown at once so the stop button works even before the service reports "ready".
        _state.value = SpeechState.Listening(0f, "")
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (id == session) startedAt = SystemClock.elapsedRealtime() }
            override fun onBeginningOfSpeech() { speechDetected = true }
            override fun onRmsChanged(rmsdB: Float) {
                if (id != session) return
                if (rmsdB > peakRms) peakRms = rmsdB
                val cur = _state.value
                if (cur is SpeechState.Listening) _state.value = cur.copy(rmsDb = rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (id != session) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) lastPartial = text
                if (!stopping) _state.value = SpeechState.Listening((_state.value as? SpeechState.Listening)?.rmsDb ?: 0f, text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (id != session) return
                when {
                    // What was heard before the learner pressed stop is still a usable answer.
                    lastPartial.isNotBlank() && (stopping || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ->
                        finish(SpeechState.Result(listOf(AsrHypothesis(lastPartial, null)), signals(true)))
                    stopping && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ->
                        finish(SpeechState.Idle)
                    // The on-device model is missing for this language: ask the system service again without the offline preference.
                    offlineRetryLeft && !stopping && error in OFFLINE_UNAVAILABLE -> { offlineRetryLeft = false; main.post { if (id == session) listen(preferOffline = false) } }
                    else -> finish(SpeechState.Error(errorText(error)))
                }
            }
            override fun onResults(results: Bundle?) {
                if (id != session) return
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                    .ifEmpty { listOfNotNull(lastPartial.takeIf { it.isNotBlank() }) }
                if (texts.isEmpty()) { finish(if (stopping) SpeechState.Idle else SpeechState.Error(errorText(SpeechRecognizer.ERROR_NO_MATCH))); return }
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                // Many recognisers return 0 or -1 when they do not compute confidence: treat as unknown.
                val known = conf != null && conf.any { it > 0f }
                val hyps = texts.mapIndexed { i, t -> AsrHypothesis(t, if (known) conf!!.getOrNull(i)?.takeIf { it >= 0f } else null) }
                finish(SpeechState.Result(hyps, signals(hyps.isNotEmpty())))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        runCatching { r.startListening(intent) }.onFailure { finish(SpeechState.Error("Konuşma tanıma başlatılamadı. Yazarak cevap verebilirsin.")) }
        // A recogniser that never answers must not leave the button stuck on "listening".
        main.postDelayed({ if (id == session && _state.value is SpeechState.Listening && startedAt == 0L) finish(SpeechState.Error("Konuşma tanıma hizmeti yanıt vermedi. Tekrar dene veya yazarak cevap ver.")) }, START_TIMEOUT_MS)
    }

    private fun signals(detected: Boolean): AudioSignals {
        val duration = if (startedAt > 0) SystemClock.elapsedRealtime() - startedAt else 0L
        return AudioSignals(duration, peakRms.takeIf { it > -100f }, speechDetected || detected)
    }

    /**
     * Ends listening and asks for the result of what was said so far. The recogniser is only destroyed after it
     * answered (destroying it right away dropped the result and left the screen on "listening" forever).
     */
    fun stop() {
        val r = recognizer ?: run { if (_state.value is SpeechState.Listening) _state.value = SpeechState.Idle; return }
        if (stopping) { cancel(); return } // second tap: give up immediately
        stopping = true
        offlineRetryLeft = false
        _state.value = SpeechState.Processing
        runCatching { r.stopListening() }
        val id = session
        main.postDelayed({
            if (id != session || recognizer == null) return@postDelayed
            finish(if (lastPartial.isNotBlank()) SpeechState.Result(listOf(AsrHypothesis(lastPartial, null)), signals(true)) else SpeechState.Idle)
        }, STOP_TIMEOUT_MS)
    }

    /** Stops at once without a result (screen closed). */
    fun cancel() {
        session++
        release()
        if (_state.value is SpeechState.Listening || _state.value is SpeechState.Processing) _state.value = SpeechState.Idle
    }

    private fun finish(next: SpeechState) {
        session++
        release()
        _state.value = next
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        stopping = false
    }

    fun reset() { _state.value = SpeechState.Idle }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Konuşma anlaşılamadı. Biraz daha net ve yakından tekrar dene."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı. Mikrofona doğru konuş."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tanıma hizmeti ağa ulaşamadı. Çevrim dışı İngilizce dil paketini indir veya yazarak cevap ver."
        SpeechRecognizer.ERROR_AUDIO -> "Ses kaydı hatası."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Tanıyıcı meşgul, birkaç saniye sonra dene."
        SpeechRecognizer.ERROR_CLIENT -> "Konuşma tanıma durduruldu. Tekrar dene."
        SpeechRecognizer.ERROR_SERVER, 11 -> "Tanıma hizmetinde sorun oluştu. Tekrar dene veya yazarak cevap ver."
        12, 13 -> "Bu cihazda İngilizce konuşma tanıma dili yüklü değil. Google uygulaması > Ayarlar > Ses bölümünden İngilizce dil paketini indir."
        10 -> "Çok fazla istek gönderildi; biraz sonra tekrar dene."
        else -> "Konuşma tanıma hatası ($code)."
    }

    private companion object {
        const val START_TIMEOUT_MS = 6_000L
        const val STOP_TIMEOUT_MS = 3_000L
        /** ERROR_SERVER, ERROR_LANGUAGE_NOT_SUPPORTED (12) and ERROR_LANGUAGE_UNAVAILABLE (13): no usable offline model. */
        val OFFLINE_UNAVAILABLE = setOf(SpeechRecognizer.ERROR_SERVER, 12, 13)
    }
}
