package com.lexiflow.data.repo

import com.lexiflow.data.content.LocalContentIndex
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.content.WordFamilyIndex
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.remote.RemoteError
import com.lexiflow.data.settings.SettingsRepository

/** What the share sheet shows before saving: the word guessed from the text, the sentence and a meaning. */
data class QuickAddDraft(val word: String, val tokens: List<String>, val sentence: String?, val meaningSuggestion: String?)

data class QuickAddResult(val wordId: Long?, val noticeTr: String, val ok: Boolean)

/**
 * "Paylaş → LexiFlow'a ekle": puts a word selected in another app into the personal wordbook. The card is enriched
 * from LexiFlow's own dictionary (definition, examples, word family) and, when the AI server is connected, by AI.
 */
class QuickAddRepository(
    private val db: AppDatabase,
    private val words: WordRepository,
    private val localContent: LocalContentIndex,
    private val families: WordFamilyIndex,
    private val enrichment: EnrichmentRepository,
    private val settings: SettingsRepository,
) {
    fun draft(sharedText: String): QuickAddDraft {
        val text = sharedText.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
        val tokens = Regex("[A-Za-z][A-Za-z'\\-]*").findAll(text).map { it.value }.toList()
        val word = when {
            tokens.isEmpty() -> ""
            tokens.size <= 3 -> tokens.joinToString(" ").let { if (it == "I") it else it.lowercase() }
            else -> ""
        }
        // A longer selection is kept as the example sentence; the learner taps the word in it.
        val sentence = text.takeIf { tokens.size > 3 }
        return QuickAddDraft(word, tokens.distinctBy { it.lowercase() }.take(40), sentence, word.takeIf { it.isNotBlank() }?.let { meaningOf(it) })
    }

    /** Turkish meaning LexiFlow already knows for [word] (first sense), if any. */
    fun meaningOf(word: String): String? = localContent.find(word)?.senses?.firstOrNull()?.meaningTr

    suspend fun add(rawWord: String, rawMeaning: String, sentence: String?): QuickAddResult {
        val word = rawWord.trim().take(60)
        if (word.isBlank() || !word.any { it.isLetter() }) return QuickAddResult(null, "Önce bir kelime seç.", false)
        // Already in a deck: just make it part of the wordbook, keeping its cards and review history.
        words.wordsByLemma(word).firstOrNull()?.let { existing ->
            if ("wordbook" !in existing.tags.split(' ')) words.updateWord(existing.copy(tags = (existing.tags + " wordbook").trim()))
            return QuickAddResult(existing.id, "\"${existing.lemma}\" zaten kayıtlıydı; Kişisel Kelime Defteri'ne eklendi (tekrar geçmişi korundu).", true)
        }
        val s = settings.current()
        val seed = localContent.find(word)
        var meaning = rawMeaning.trim().take(200)
        var aiDraft: com.lexiflow.core.ai.EnrichResponse? = null
        var aiNote: String? = null
        if (meaning.isBlank()) meaning = seed?.senses?.firstOrNull()?.meaningTr.orEmpty()
        if (meaning.isBlank() && s.aiAvailable) {
            try {
                aiDraft = enrichment.draft(word)
                meaning = aiDraft.senses.firstOrNull()?.meaningTr.orEmpty()
            } catch (e: RemoteError) { aiNote = e.messageTr }
        }
        if (meaning.isBlank()) {
            return QuickAddResult(null, "Bu kelimenin Türkçe anlamını yaz" + (aiNote?.let { " (AI şu an cevap vermedi: $it)" } ?: if (!s.aiAvailable) " (AI sunucusu bağlı değil, anlamı otomatik bulunamadı)." else "."), false)
        }
        val seedSense = seed?.let { localContent.matchingSense(it, meaning) }
        val own = sentence?.takeIf { it.contains(word, ignoreCase = true) }?.let { sen ->
            val answer = Regex("(?i)(?<![A-Za-z])" + Regex.escape(word) + "(?![A-Za-z])").find(sen)?.value ?: word
            NewExample(sen.take(300), null, "user", answer)
        }
        val seedExamples = seedSense?.examples.orEmpty().take(6).map { NewExample(it.en, it.tr, it.context, it.answer, it.acceptedAlternatives, it.noteTr) }
        val rich = families.enrich(word, seed?.rich() ?: RichInfo())
        val wordId = words.insertWord(
            words.personalDeckId(),
            NewWord(word, pos = seed?.pos?.takeIf { seedSense != null }, cefr = seed?.cefr, ipaUs = seed?.ipaUs?.takeIf { seedSense != null },
                ipaUk = seed?.ipaUk?.takeIf { seedSense != null }, rich = rich, tags = listOf("wordbook", "shared"),
                senses = listOf(NewSense(meaning.take(60), meaning, seedSense?.definitionEn, listOfNotNull(own) + seedExamples))),
            source = "USER",
        )
        val parts = mutableListOf("\"$word\" Kişisel Kelime Defteri'ne eklendi.")
        if (seedSense != null) parts += "LexiFlow sözlüğünden açıklama ve ${seedExamples.size} örnek cümle eklendi."
        if (rich.wordFamily.isNotEmpty() || rich.phrasalVerbs.isNotEmpty()) parts += "Kelime ailesi eklendi."
        if (s.aiAvailable) {
            try {
                val added = aiDraft?.let { enrichment.apply(wordId, it) } ?: enrichment.enrich(wordId)
                parts += "AI kartı zenginleştirdi (+$added örnek)."
            } catch (e: RemoteError) {
                parts += "AI zenginleştirmesi şu an yapılamadı: ${e.messageTr}"
            }
        } else {
            parts += "AI sunucusu bağlı olmadığı için AI zenginleştirmesi yapılmadı; Ayarlar'dan bağlayınca kelime sayfasındaki \"AI ile zenginleştir\" ile ekleyebilirsin."
        }
        return QuickAddResult(wordId, parts.joinToString(" "), true)
    }

    companion object { const val MAX_TEXT = 500 }
}
