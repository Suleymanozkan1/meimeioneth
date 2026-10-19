package com.lexiflow.data.repo

import androidx.room.withTransaction
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.CardEntity
import com.lexiflow.data.db.DeckEntity
import com.lexiflow.data.db.ExampleEntity
import com.lexiflow.data.db.SenseEntity
import com.lexiflow.data.db.WordEntity
import com.lexiflow.util.Clock
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

data class NewExample(
    val en: String,
    val tr: String? = null,
    val context: String = "daily",
    val answer: String,
    val alternatives: List<String> = emptyList(),
    val noteTr: String? = null,
)

data class NewSense(val label: String, val meaningTr: String, val definitionEn: String? = null, val examples: List<NewExample> = emptyList())

data class NewWord(
    val lemma: String,
    val pos: String? = null,
    val cefr: String? = null,
    val ipaUk: String? = null,
    val ipaUs: String? = null,
    val syllables: String? = null,
    val stress: String? = null,
    val register: String? = null,
    val rich: RichInfo = RichInfo(),
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val senses: List<NewSense>,
)

/** Decks, words, senses, examples and their recognition cards. One card is created per sense. */
class WordRepository(private val db: AppDatabase, private val clock: Clock) {

    private val words = db.wordDao()
    private val cards = db.cardDao()
    private val decks = db.deckDao()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDecks() = com.lexiflow.util.ticks().flatMapLatest { decks.observeSummaries(clock.now()) }
    fun observeWords(deckId: Long) = words.observeInDeck(deckId)
    fun observeWord(id: Long) = words.observe(id)
    fun observeSenses(wordId: Long) = words.observeSenses(wordId)
    fun observeExamples(wordId: Long) = words.observeExamples(wordId)
    fun observeCards(wordId: Long) = cards.observeByWord(wordId)
    fun search(q: String) = words.search(q)
    fun observePersonal() = words.observePersonal()

    suspend fun deckByName(name: String) = decks.byName(name)

    suspend fun createDeck(name: String, source: String, description: String = ""): Long {
        val clean = name.trim().ifBlank { "Yeni deste" }.take(80)
        decks.byName(clean)?.let { return it.id }
        return decks.insert(DeckEntity(name = clean, description = description, source = source, createdAt = clock.now()))
    }

    /**
     * Deletes a deck with its words, senses, examples, cards and review history (foreign-key cascades).
     * Tables that point at cards without a foreign key are cleaned in the same transaction.
     */
    suspend fun deleteDeck(deckId: Long) = db.withTransaction {
        db.lockScreenDao().deleteOpenForDeck(deckId)
        db.pronunciationDao().detachDeck(deckId)
        decks.delete(deckId)
    }

    suspend fun personalDeckId(): Long = createDeck(PERSONAL_DECK, "USER", "Kendi eklediğin kelimeler")

    /** Inserts a word with all senses, examples and one NEW card per sense. Returns the word id. */
    suspend fun insertWord(deckId: Long, w: NewWord, source: String, sourceRef: String? = null): Long = db.withTransaction {
        val now = clock.now()
        val wordId = words.insertWord(
            WordEntity(
                deckId = deckId,
                lemma = w.lemma.trim(),
                lemmaKey = DuplicateDetector.normalizeWord(w.lemma),
                pos = w.pos, cefr = w.cefr, ipaUk = w.ipaUk, ipaUs = w.ipaUs, syllables = w.syllables, stress = w.stress,
                register = w.register,
                richJson = LexiJson.encodeToString(RichInfo.serializer(), w.rich),
                notes = w.notes,
                tags = w.tags.joinToString(" "),
                source = source,
                sourceRef = sourceRef,
                createdAt = now,
                updatedAt = now,
            ),
        )
        w.senses.forEachIndexed { i, s -> addSenseInternal(wordId, deckId, s, i, if (source == "BUILTIN") "BUILTIN" else source) }
        wordId
    }

    suspend fun addSense(wordId: Long, sense: NewSense, origin: String = "USER"): Long = db.withTransaction {
        val word = words.get(wordId) ?: error("word $wordId not found")
        val position = words.senses(wordId).size
        addSenseInternal(wordId, word.deckId, sense, position, origin)
    }

    private suspend fun addSenseInternal(wordId: Long, deckId: Long, s: NewSense, position: Int, origin: String): Long {
        val senseId = words.insertSense(SenseEntity(wordId = wordId, label = s.label, meaningTr = s.meaningTr, definitionEn = s.definitionEn, position = position))
        val valid = s.examples.filter { it.en.isNotBlank() && it.answer.isNotBlank() && it.en.contains(it.answer, ignoreCase = true) }
        words.insertExamples(valid.map { e -> e.toEntity(senseId, wordId, origin) })
        cards.insert(CardEntity(senseId = senseId, wordId = wordId, deckId = deckId, createdAt = clock.now(), dueAt = clock.now()))
        return senseId
    }

    suspend fun addExamples(senseId: Long, examples: List<NewExample>, origin: String): Int {
        val sense = words.sense(senseId) ?: return 0
        val existing = words.examplesForSense(senseId).map { it.en.trim().lowercase() }.toSet()
        val fresh = examples.filter { it.en.trim().lowercase() !in existing && it.en.contains(it.answer, ignoreCase = true) }
            .distinctBy { it.en.trim().lowercase() }
        words.insertExamples(fresh.map { it.toEntity(senseId, sense.wordId, origin) })
        return fresh.size
    }

    suspend fun updateWord(word: WordEntity) = words.updateWord(word.copy(updatedAt = clock.now()))
    suspend fun updateSense(sense: SenseEntity) = words.updateSense(sense)
    suspend fun updateExample(example: ExampleEntity) = words.updateExample(example)
    suspend fun deleteExample(id: Long) = words.deleteExample(id)

    /** Deletes a word and returns a snapshot that [restoreWord] can re-insert (undo). History is not restored. */
    suspend fun deleteWord(id: Long): NewWord? {
        val w = words.get(id) ?: return null
        val snapshot = snapshot(w)
        words.deleteWord(id)
        lastDeleted = w.deckId to snapshot
        return snapshot
    }

    private var lastDeleted: Pair<Long, NewWord>? = null

    suspend fun undoDelete(): Long? {
        val (deckId, w) = lastDeleted ?: return null
        lastDeleted = null
        return insertWord(deckId, w, "USER")
    }

    private suspend fun snapshot(w: WordEntity): NewWord {
        val senses = words.senses(w.id).map { s ->
            NewSense(s.label, s.meaningTr, s.definitionEn, words.examplesForSense(s.id).map {
                NewExample(it.en, it.tr, it.context, it.answer, decodeAlternatives(it.alternativesJson), it.noteTr)
            })
        }
        return NewWord(w.lemma, w.pos, w.cefr, w.ipaUk, w.ipaUs, w.syllables, w.stress, w.register, rich(w.richJson), w.notes,
            w.tags.split(" ").filter { it.isNotBlank() }, senses)
    }

    suspend fun lemmaMeaningIndex(): Map<String, List<String>> =
        words.allLemmaMeanings().groupBy({ it.lemmaKey }, { it.meaningTr })

    suspend fun wordsByLemma(lemma: String) = words.byLemmaKey(DuplicateDetector.normalizeWord(lemma))

    companion object {
        const val BUILTIN_DECK = "LexiFlow B1–B2 Çekirdek"
        const val PERSONAL_DECK = "Kişisel Kelime Defterim"

        fun rich(json: String): RichInfo = runCatching { LexiJson.decodeFromString(RichInfo.serializer(), json) }.getOrDefault(RichInfo())

        fun decodeAlternatives(json: String): List<String> =
            runCatching { LexiJson.decodeFromString(ListSerializer(String.serializer()), json) }.getOrDefault(emptyList())

        fun encodeAlternatives(list: List<String>): String = LexiJson.encodeToString(ListSerializer(String.serializer()), list)
    }
}

internal fun NewExample.toEntity(senseId: Long, wordId: Long, origin: String) = ExampleEntity(
    senseId = senseId,
    wordId = wordId,
    en = en.trim(),
    tr = tr?.trim(),
    context = context,
    answer = answer.trim(),
    alternativesJson = WordRepository.encodeAlternatives(alternatives),
    noteTr = noteTr,
    origin = origin,
)
