package com.lexiflow.core.pronunciation

import com.lexiflow.core.language.Morphology
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One recognition hypothesis from the speech recogniser (n-best list). Confidence may be unknown (null). */
@Serializable
data class AsrHypothesis(val text: String, val confidence: Float? = null)

/** Audio-level signals measured on device; they say nothing about phonemes, only about recording quality. */
@Serializable
data class AudioSignals(
    val durationMs: Long,
    /** Peak RMS in dB reported by the recogniser (SpeechRecognizer onRmsChanged), null if unavailable. */
    val peakRmsDb: Float? = null,
    val speechDetected: Boolean = true,
)

@Serializable
enum class WordStatus(val labelTr: String) {
    RECOGNIZED("Anlaşıldı"),
    UNCERTAIN("Belirsiz"),
    MISHEARD("Farklı duyuldu"),
    MISSING("Duyulmadı"),
}

@Serializable
data class WordFeedback(
    val target: String,
    val status: WordStatus,
    val heard: String? = null,
    /** A specific, explainable hint (e.g. a known minimal pair). Null when nothing reliable can be said. */
    val tipTr: String? = null,
)

@Serializable
enum class Reliability(val labelTr: String) { GOOD("Güvenilir"), LOW("Düşük güven"), UNUSABLE("Kayıt değerlendirilemedi") }

@Serializable
data class PronunciationResult(
    val words: List<WordFeedback>,
    /** Share of target words the recogniser understood, 0..100. Null when the recording is unusable. */
    val intelligibility: Int?,
    val reliability: Reliability,
    val wordsPerMinute: Int?,
    val fluencyTr: String?,
    val notesTr: List<String>,
)

/**
 * Word-level pronunciation feedback from ASR output.
 *
 * Honest scope (see docs/PRONUNCIATION.md): ASR tells us whether a word was *understood*, not which
 * phoneme was wrong. We therefore report intelligibility per word, flag uncertainty when confidence or
 * audio quality is low, and only give phoneme-specific tips when the recogniser heard a known minimal-pair
 * partner (e.g. "sheep" for "ship"), which is a strong, explainable signal. Stress and rhythm are not
 * measured acoustically; we only report speaking rate from duration.
 */
object PronunciationScorer {

    fun score(target: String, hypotheses: List<AsrHypothesis>, audio: AudioSignals): PronunciationResult {
        val targetTokens = Morphology.tokens(target)
        val notes = mutableListOf<String>()
        val unusable = !audio.speechDetected || hypotheses.isEmpty() || hypotheses.all { it.text.isBlank() } || audio.durationMs < 300
        if (unusable) {
            notes += "Ses net alınamadı. Mikrofona daha yakın ve sessiz bir ortamda tekrar dene."
            return PronunciationResult(targetTokens.map { WordFeedback(it, WordStatus.MISSING) }, null, Reliability.UNUSABLE, null, null, notes)
        }
        val best = hypotheses.first()
        val alternatives = hypotheses.drop(1)
        val bestTokens = Morphology.tokens(best.text)
        val alignment = align(targetTokens, bestTokens)
        val altTokenSets = alternatives.map { Morphology.tokens(it.text).toSet() }

        val words = alignment.map { (t, heard) ->
            val tip = heard?.let { minimalPairTip(t, it) }
            when {
                heard == t || (heard != null && Morphology.forms(t).contains(heard)) -> WordFeedback(t, WordStatus.RECOGNIZED, heard)
                altTokenSets.any { t in it } -> WordFeedback(t, WordStatus.UNCERTAIN, heard,
                    tip ?: "Tanıyıcı bu kelimeyi alternatif sonuçlarda duydu; söyleyişin net ama tam emin değil.")
                heard != null -> WordFeedback(t, WordStatus.MISHEARD, heard, tip)
                else -> WordFeedback(t, WordStatus.MISSING, null, "Bu kelime kayıtta duyulmadı. Kelimeyi atlamadan, biraz daha yavaş söyle.")
            }
        }

        val confidence = best.confidence
        val quiet = audio.peakRmsDb != null && audio.peakRmsDb < 2f
        val reliability = when {
            quiet -> Reliability.LOW
            confidence == null -> Reliability.LOW
            confidence < 0.5f -> Reliability.LOW
            else -> Reliability.GOOD
        }
        if (confidence == null) notes += "Bu cihazın tanıyıcısı güven skoru vermiyor; sonuçları yaklaşık kabul et."
        if (quiet) notes += "Ses seviyesi düşüktü; sonuç belirsiz olabilir."
        val recognized = words.count { it.status == WordStatus.RECOGNIZED } + 0.5 * words.count { it.status == WordStatus.UNCERTAIN }
        val intelligibility = if (targetTokens.isEmpty()) 0 else (recognized / targetTokens.size * 100).roundToInt()

        val wpm = if (audio.durationMs > 0 && bestTokens.isNotEmpty()) (bestTokens.size / (audio.durationMs / 60000.0)).roundToInt() else null
        val fluency = when {
            wpm == null || targetTokens.size < 4 -> null
            wpm < 70 -> "Konuşma hızın yavaş (~$wpm kelime/dk). Kelimeleri bağlayarak akıcı söylemeyi dene."
            wpm > 190 -> "Çok hızlı konuştun (~$wpm kelime/dk). Biraz yavaşlamak anlaşılırlığı artırır."
            else -> "Konuşma hızın doğal aralıkta (~$wpm kelime/dk)."
        }
        notes += "Vurgu ve tonlama akustik olarak ölçülmüyor; kelime kartındaki vurgu işaretini (ˈ) ve referans sesi kullan."
        return PronunciationResult(words, intelligibility, reliability, wpm, fluency, notes)
    }

    /** Word-level Levenshtein alignment. Returns each target token paired with the heard token (or null). */
    fun align(target: List<String>, heard: List<String>): List<Pair<String, String?>> {
        val n = target.size; val m = heard.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val sub = if (wordsMatch(target[i - 1], heard[j - 1])) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + sub)
        }
        val out = ArrayList<Pair<String, String?>>()
        var i = n; var j = m
        while (i > 0) {
            if (j > 0 && dp[i][j] == dp[i - 1][j - 1] + (if (wordsMatch(target[i - 1], heard[j - 1])) 0 else 1)) {
                out += target[i - 1] to heard[j - 1]; i--; j--
            } else if (dp[i][j] == dp[i - 1][j] + 1) {
                out += target[i - 1] to null; i--
            } else {
                j--
            }
        }
        return out.reversed()
    }

    private fun wordsMatch(a: String, b: String) = a == b || Morphology.forms(a).contains(b)

    fun minimalPairTip(target: String, heard: String): String? {
        val pair = MINIMAL_PAIRS.firstOrNull { (a, b, _) -> (a == target && b == heard) || (b == target && a == heard) } ?: return null
        return "Olası ses karışıklığı: \"$target\" yerine \"$heard\" duyuldu. ${pair.third}"
    }

    /** Minimal pairs that are hard for Turkish speakers, with the contrast explained. */
    val MINIMAL_PAIRS: List<Triple<String, String, String>> = listOf(
        Triple("ship", "sheep", "/ɪ/ kısa ve gevşek, /iː/ uzun ve gergin bir sestir."),
        Triple("live", "leave", "/ɪ/ (live) kısa, /iː/ (leave) uzundur."),
        Triple("fill", "feel", "/ɪ/ kısa, /iː/ uzun söylenir."),
        Triple("sit", "seat", "/ɪ/ kısa, /iː/ uzun söylenir."),
        Triple("full", "fool", "/ʊ/ kısa, /uː/ uzundur."),
        Triple("bad", "bed", "/æ/ ağız daha açık, /e/ daha kapalıdır."),
        Triple("man", "men", "/æ/ ağız daha açık, /e/ daha kapalıdır."),
        Triple("cat", "cut", "/æ/ öndeki açık ünlü, /ʌ/ ortadaki kısa ünlüdür."),
        Triple("think", "sink", "/θ/ dil ucu dişlerin arasında söylenir; /s/ değildir."),
        Triple("think", "tink", "/θ/ dil ucu dişlerin arasındadır; /t/ gibi patlatılmaz."),
        Triple("three", "tree", "/θ/ dil dişlerin arasında; /t/ değil."),
        Triple("thank", "tank", "/θ/ dil dişlerin arasında; /t/ değil."),
        Triple("this", "dis", "/ð/ titreşimli, dil dişlerin arasında söylenir."),
        Triple("they", "day", "/ð/ titreşimli, dil dişlerin arasında söylenir."),
        Triple("very", "berry", "/v/ alt dudak üst dişlere değer; /b/ iki dudakla yapılır."),
        Triple("vest", "west", "/v/ alt dudak-üst diş; /w/ dudaklar yuvarlanır, dişler değmez."),
        Triple("vine", "wine", "/v/ alt dudak-üst diş; /w/ dudaklar yuvarlanır."),
        Triple("walk", "work", "/ɔː/ (walk) yuvarlak ve uzun; /ɜː/ (work) ortada ve r tınılıdır."),
        Triple("law", "low", "/ɔː/ tek ünlü, /əʊ/ kayan bir ünlüdür."),
        Triple("hat", "hut", "/æ/ ve /ʌ/ farklı ünlülerdir."),
        Triple("bit", "beat", "/ɪ/ kısa, /iː/ uzundur."),
        Triple("pull", "pool", "/ʊ/ kısa, /uː/ uzundur."),
        Triple("thin", "tin", "/θ/ dil dişlerin arasındadır."),
        Triple("sheet", "seat", "/ʃ/ (ş) ve /s/ farklı seslerdir."),
        Triple("chip", "ship", "/tʃ/ (ç) ve /ʃ/ (ş) farklı seslerdir."),
        Triple("light", "right", "/l/ dil ucu üst damakta; /r/ dil hiçbir yere değmeden geriye kıvrılır."),
        Triple("collect", "correct", "/l/ ve /r/ farklı dil konumlarıyla söylenir."),
        Triple("wet", "vet", "/w/ dudaklar yuvarlanır; /v/ alt dudak-üst diş."),
    )
}
