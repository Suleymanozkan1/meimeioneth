package com.lexiflow.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
}

/**
 * Wraps Android's SpeechRecognizer. Audio is handled by the system recogniser; LexiFlow never records or
 * stores raw audio. With "prefer offline" the on-device model is requested where the device supports it,
 * otherwise the system service may process speech on its servers (disclosed in Settings > Privacy).
 */
class SpeechInput(private val context: Context) {
    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state
    private var recognizer: SpeechRecognizer? = null
    private var startedAt = 0L
    private var peakRms = -100f
    private var speechDetected = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Must be called on the main thread. */
    fun start(languageTag: String = "en-US", preferOffline: Boolean = true) {
        if (!isAvailable()) { _state.value = SpeechState.Error("Bu cihazda konuşma tanıma hizmeti yok. Yazarak cevap verebilirsin."); return }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        peakRms = -100f
        speechDetected = false
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { startedAt = SystemClock.elapsedRealtime(); _state.value = SpeechState.Listening(0f, "") }
            override fun onBeginningOfSpeech() { speechDetected = true }
            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > peakRms) peakRms = rmsdB
                val cur = _state.value
                if (cur is SpeechState.Listening) _state.value = cur.copy(rmsDb = rmsdB)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = SpeechState.Listening((_state.value as? SpeechState.Listening)?.rmsDb ?: 0f, text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) { _state.value = SpeechState.Error(errorText(error)) }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val conf = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                // Many recognisers return 0 or -1 when they do not compute confidence: treat as unknown.
                val known = conf != null && conf.any { it > 0f }
                val hyps = texts.mapIndexed { i, t -> AsrHypothesis(t, if (known) conf!!.getOrNull(i)?.takeIf { it >= 0f } else null) }
                val duration = if (startedAt > 0) SystemClock.elapsedRealtime() - startedAt else 0L
                _state.value = SpeechState.Result(hyps, AudioSignals(duration, peakRms.takeIf { it > -100f }, speechDetected || hyps.isNotEmpty()))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.let { runCatching { it.stopListening(); it.destroy() } }
        recognizer = null
    }

    fun reset() { _state.value = SpeechState.Idle }

    private fun errorText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> "Konuşma anlaşılamadı. Biraz daha net ve yakından tekrar dene."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ses algılanmadı. Mikrofona doğru konuş."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni gerekli."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tanıma hizmeti ağa ulaşamadı. Çevrim dışı İngilizce dil paketini indir veya yazarak cevap ver."
        SpeechRecognizer.ERROR_AUDIO -> "Ses kaydı hatası."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Tanıyıcı meşgul, birkaç saniye sonra dene."
        else -> "Konuşma tanıma hatası ($code)."
    }
}
