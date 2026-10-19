package com.lexiflow.data.repo

import androidx.room.withTransaction
import com.lexiflow.data.content.LocalContentIndex
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.db.AppDatabase

data class EnrichReport(val words: Int, val examples: Int, val levels: Int)

/** Deck maintenance for imported decks: add LexiFlow's own content to bare cards and split a deck by level. */
class DeckToolsRepository(
    private val db: AppDatabase,
    private val words: WordRepository,
    private val localContent: LocalContentIndex,
) {
    /**
     * For words LexiFlow knows: fills the level, word type, IPA and notes when empty, and gives senses that have no
     * definition and no example our definition and example sentences for the same meaning. The learner's own
     * meanings, examples and review history are never changed.
     */
    suspend fun enrichDeck(deckId: Long): EnrichReport = db.withTransaction {
        val dao = db.wordDao()
        var enrichedWords = 0
        var examples = 0
        var levels = 0
        for (w in dao.inDeck(deckId)) {
            val seed = localContent.find(w.lemma) ?: continue
            var touched = false
            var senseMatched = false
            for (s in dao.senses(w.id)) {
                if (!s.definitionEn.isNullOrBlank() || dao.examplesForSense(s.id).isNotEmpty()) continue
                val match = localContent.matchingSense(seed, s.meaningTr) ?: continue
                senseMatched = true
                words.updateSense(s.copy(definitionEn = match.definitionEn))
                examples += words.addExamples(s.id, match.examples.take(6).map { e -> NewExample(e.en, e.tr, e.context, e.answer, e.acceptedAlternatives, e.noteTr) }, origin = "LEXIFLOW")
                touched = true
            }
            val level = seed.cefr?.takeIf { it in ImportRepository.CEFR_LEVELS }
            val emptyRich = WordRepository.rich(w.richJson) == RichInfo()
            val updated = w.copy(
                cefr = w.cefr ?: level,
                pos = w.pos ?: seed.pos.takeIf { senseMatched },
                ipaUs = w.ipaUs ?: seed.ipaUs.takeIf { senseMatched },
                ipaUk = w.ipaUk ?: seed.ipaUk.takeIf { senseMatched },
                richJson = if (emptyRich && senseMatched) com.lexiflow.core.ai.LexiJson.encodeToString(RichInfo.serializer(), seed.rich()) else w.richJson,
            )
            if (w.cefr == null && level != null) levels++
            if (updated != w) { words.updateWord(updated); touched = true }
            if (touched) enrichedWords++
        }
        EnrichReport(enrichedWords, examples, levels)
    }

    /**
     * Moves every word with a known level into "<deck> · A1/A2/…" (cards and history go with it). Words without a
     * level stay; an emptied deck is removed.
     */
    suspend fun splitByLevel(deckId: Long): Map<String, Int> = db.withTransaction {
        val deck = db.deckDao().get(deckId) ?: return@withTransaction emptyMap()
        val byLevel = db.wordDao().inDeck(deckId).filter { it.cefr in ImportRepository.CEFR_LEVELS }.groupBy { it.cefr!! }
        val moved = sortedMapOf<String, Int>()
        for ((level, list) in byLevel) {
            val target = words.createDeck("${deck.name} · $level", deck.source, "Seviye $level · ${deck.name}")
            if (target == deckId) continue
            list.map { it.id }.chunked(500).forEach { ids ->
                db.wordDao().moveWords(ids, target)
                db.cardDao().moveCardsOfWords(ids, target)
            }
            moved[level] = list.size
        }
        if (db.wordDao().inDeck(deckId).isEmpty() && moved.isNotEmpty()) words.deleteDeck(deckId)
        moved
    }
}
