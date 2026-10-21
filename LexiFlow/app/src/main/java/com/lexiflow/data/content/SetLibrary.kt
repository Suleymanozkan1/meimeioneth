package com.lexiflow.data.content

import android.content.Context
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.importer.DuplicateKind
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.repo.WordRepository
import androidx.room.withTransaction
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
    /** Single CEFR level the library groups the set under (most common level of its words). */
    val group: String = "",
    val isLevelPack: Boolean = false,
    /** Deck converted from the learner's own Anki collection (assets/anki). */
    val isAnki: Boolean = false,
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
    private val localContent: LocalContentIndex,
) {
    private val topical: List<WordSetInfo> by lazy {
        val files = context.assets.list("sets").orEmpty().toSet()
        if ("index.json" !in files) return@lazy emptyList()
        LexiJson.decodeFromString(ListSerializer(WordSetInfo.serializer()), readAsset("sets/index.json"))
            .filter { "${it.id}.json" in files }
            .map { info -> info.copy(group = mainLevel(setWords(info.id))) }
    }

    /** Decks converted from Anki (assets/anki); their level group comes from the index. */
    private val anki: List<WordSetInfo> by lazy {
        val files = context.assets.list(ANKI_DIR).orEmpty().toSet()
        if ("index.json" !in files) return@lazy emptyList()
        LexiJson.decodeFromString(ListSerializer(WordSetInfo.serializer()), readAsset("$ANKI_DIR/index.json"))
            .filter { "${it.id}.json" in files }
            .map { it.copy(isAnki = true) }
    }

    /**
     * Topical sets (each grouped under the level most of its words have) plus one level pack per CEFR level with
     * every LexiFlow word of that level from the sets and the dictionary, plus the Anki decks. Sorted A1 → C2,
     * level pack first.
     */
    fun sets(): List<WordSetInfo> {
        val packs = LEVELS.mapNotNull { level ->
            val n = levelPackWords(level).size
            if (n == 0) null else WordSetInfo(
                id = LEVEL_PACK_PREFIX + level.lowercase(), title = "$level Seviye Paketi", category = "Seviye paketi", level = level,
                description = "LexiFlow'daki bütün $level kelimeleri tek destede: açıklama, örnek cümleler ve notlarla.",
                wordCount = n, group = level, isLevelPack = true,
            )
        }
        return (packs + topical + anki).sortedWith(compareBy<WordSetInfo>({ LEVELS.indexOf(it.group).let { i -> if (i < 0) LEVELS.size else i } }, { !it.isLevelPack }))
    }

    fun words(id: String): List<SeedWord> {
        require(id.matches(Regex("[a-z0-9-]+"))) { "invalid set id" }
        if (id.startsWith(LEVEL_PACK_PREFIX)) return levelPackWords(id.removePrefix(LEVEL_PACK_PREFIX).uppercase())
        return setWords(id)
    }

    private fun setWords(id: String): List<SeedWord> =
        LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), readAsset(if (id.startsWith(ANKI_PREFIX)) "$ANKI_DIR/$id.json" else "sets/$id.json"))

    /** Words of one level from the sets and the dictionary (the built-in deck is always installed already). */
    private fun levelPackWords(level: String): List<SeedWord> =
        localContent.entries.filter { (path, w) -> !path.startsWith("content/") && w.cefr == level }.map { it.second }

    private fun mainLevel(list: List<SeedWord>): String =
        list.mapNotNull { it.cefr?.takeIf { l -> l in LEVELS } }.groupingBy { it }.eachCount()
            .maxWithOrNull(compareBy<Map.Entry<String, Int>>({ it.value }, { -LEVELS.indexOf(it.key) }))?.key ?: ""

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
        // One transaction: level packs hold hundreds of words.
        db.withTransaction { words(id).forEach { w ->
            val meaning = w.senses.firstOrNull()?.meaningTr.orEmpty()
            if (DuplicateDetector.classify(w.lemma, meaning, index) == DuplicateKind.EXACT) {
                skipped += w.lemma
                return@forEach
            }
            words.insertWord(deckId, w.toNewWord(), source = "SET", sourceRef = "set:$id")
            index.getOrPut(DuplicateDetector.normalizeWord(w.lemma)) { mutableListOf() } += w.senses.map { it.meaningTr }
            added++
        } }
        return SetInstallReport(deckId, added, skipped)
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        const val SOURCE_PREFIX = "SET:"
        const val LEVEL_PACK_PREFIX = "level-"
        const val ANKI_DIR = "anki"
        const val ANKI_PREFIX = "anki-"
        val LEVELS = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    }
}
