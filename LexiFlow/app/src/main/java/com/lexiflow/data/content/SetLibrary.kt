package com.lexiflow.data.content

import android.content.Context
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.importer.DuplicateKind
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.repo.WordRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One ready-made word set in assets/sets (original LexiFlow content, see docs/CONTENT_SOURCES_AND_LICENSING.md). */
@Serializable
data class WordSetInfo(
    val id: String,
    val title: String,
    val category: String,
    val level: String,
    val description: String,
    val wordCount: Int,
)

data class WordSetState(val info: WordSetInfo, val deckId: Long?)

data class SetInstallReport(val deckId: Long, val added: Int, val skippedDuplicates: List<String>)

/**
 * Library of ready-made word sets the learner can add as decks, like shared decks in Anki. Sets are optional:
 * nothing is added to reviews until the learner adds a set. Adding is idempotent: a word with the same meaning
 * that already exists in any deck is skipped, so adding twice (or after an import) never duplicates cards.
 */
class SetLibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val words: WordRepository,
) {
    /** Sets listed in the index whose word file is actually shipped. */
    fun sets(): List<WordSetInfo> {
        val files = context.assets.list("sets").orEmpty().toSet()
        if ("index.json" !in files) return emptyList()
        return LexiJson.decodeFromString(ListSerializer(WordSetInfo.serializer()), readAsset("sets/index.json"))
            .filter { "${it.id}.json" in files }
    }

    fun words(id: String): List<SeedWord> {
        require(id.matches(Regex("[a-z0-9-]+"))) { "invalid set id" }
        return LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), readAsset("sets/$id.json"))
    }

    /** Sets with the deck they were added as (null when not added yet or the deck was deleted). */
    suspend fun states(): List<WordSetState> {
        val decks = db.deckDao().bySourcePrefix("$SOURCE_PREFIX%").associateBy { it.source.removePrefix(SOURCE_PREFIX) }
        return sets().map { WordSetState(it, decks[it.id]?.id) }
    }

    suspend fun install(id: String): SetInstallReport {
        val info = sets().first { it.id == id }
        val deckId = db.deckDao().bySourcePrefix(SOURCE_PREFIX + id).firstOrNull()?.id
            ?: words.createDeck(info.title, SOURCE_PREFIX + id, info.description)
        val index = words.lemmaMeaningIndex().mapValues { it.value.toMutableList() }.toMutableMap()
        var added = 0
        val skipped = mutableListOf<String>()
        words(id).forEach { w ->
            val meaning = w.senses.firstOrNull()?.meaningTr.orEmpty()
            if (DuplicateDetector.classify(w.lemma, meaning, index) == DuplicateKind.EXACT) {
                skipped += w.lemma
                return@forEach
            }
            words.insertWord(deckId, w.toNewWord(), source = "SET", sourceRef = "set:$id")
            index.getOrPut(DuplicateDetector.normalizeWord(w.lemma)) { mutableListOf() } += w.senses.map { it.meaningTr }
            added++
        }
        return SetInstallReport(deckId, added, skipped)
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        const val SOURCE_PREFIX = "SET:"
    }
}
