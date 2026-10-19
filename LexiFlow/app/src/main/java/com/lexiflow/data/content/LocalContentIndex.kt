package com.lexiflow.data.content

import android.content.Context
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import kotlinx.serialization.builtins.ListSerializer

/**
 * All original LexiFlow word content shipped in the app (the built-in deck, the ready-made sets and the dictionary
 * in assets/lexicon), indexed by lemma. Imported decks often have only "word = meaning"; for words LexiFlow already covers, the importer adds
 * our own definition, example sentences and notes so those cards get gap-fill questions and context.
 */
class LocalContentIndex(private val context: Context) {
    private val byLemma: Map<String, SeedWord> by lazy { load() }

    fun find(lemma: String): SeedWord? = byLemma[DuplicateDetector.normalizeWord(lemma)]

    /**
     * The sense of [word] that matches [meaningTr]: the one sharing a listed meaning, otherwise the only sense.
     * Returns null when the word has several senses and none matches, so examples of another meaning are never attached.
     */
    fun matchingSense(word: SeedWord, meaningTr: String): SeedSense? {
        val wanted = DuplicateDetector.meaningItems(meaningTr)
        val best = word.senses.maxByOrNull { s -> DuplicateDetector.meaningItems(s.meaningTr).count { it in wanted } }
        val shared = best?.let { s -> DuplicateDetector.meaningItems(s.meaningTr).count { it in wanted } } ?: 0
        return when {
            shared > 0 -> best
            word.senses.size == 1 -> word.senses.first()
            else -> null
        }
    }

    private fun load(): Map<String, SeedWord> {
        val files = context.assets.list("content").orEmpty().filter { it.startsWith("words_") && it.endsWith(".json") }.map { "content/$it" } +
            context.assets.list("sets").orEmpty().filter { it.endsWith(".json") && it != "index.json" }.map { "sets/$it" } +
            context.assets.list("lexicon").orEmpty().filter { it.endsWith(".json") }.sorted().map { "lexicon/$it" }
        val out = HashMap<String, SeedWord>()
        files.forEach { path ->
            val text = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), text).forEach { w ->
                out.putIfAbsent(DuplicateDetector.normalizeWord(w.lemma), w)
            }
        }
        return out
    }
}
