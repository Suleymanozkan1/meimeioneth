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
    /** Every entry with the asset file it came from (content/, sets/ or lexicon/), first occurrence of a lemma wins. */
    val entries: List<Pair<String, SeedWord>> by lazy { load() }
    private val byLemma: Map<String, SeedWord> by lazy { entries.associate { (_, w) -> DuplicateDetector.normalizeWord(w.lemma) to w } }

    fun find(lemma: String): SeedWord? = byLemma[DuplicateDetector.normalizeWord(lemma)]

    /**
     * The sense of [word] that matches [meaningTr]: the one sharing a listed meaning, otherwise the only sense.
     * Returns null when the word has several senses and none matches, so examples of another meaning are never attached.
     */
    fun matchingSense(word: SeedWord, meaningTr: String): SeedSense? {
        val wanted = DuplicateDetector.meaningItems(meaningTr)
        val best = word.senses.maxByOrNull { s -> DuplicateDetector.meaningItems(s.meaningTr).count { it in wanted } }
        val shared = best?.let { s -> DuplicateDetector.meaningItems(s.meaningTr).count { it in wanted } } ?: 0
        if (shared > 0) return best
        if (word.senses.size == 1) return word.senses.first()
        // Same root in different wording ("iddia" / "iddia etmek", "faydalanmak" / "faydalı olmak").
        val wantedRoots = roots(meaningTr)
        val byRoot = word.senses.map { s -> s to roots(s.meaningTr).count { it in wantedRoots } }.maxByOrNull { it.second }
        return byRoot?.takeIf { it.second > 0 }?.first
    }

    private fun roots(meaning: String): Set<String> =
        DuplicateDetector.meaningItems(meaning).flatMap { it.split(' ') }
            .filter { it.length >= 4 && it !in setOf("etmek", "olmak", "yapmak", "bir", "şey", "birini", "birine") }
            .map { it.take(5) }.toSet()

    private fun load(): List<Pair<String, SeedWord>> {
        val files = context.assets.list("content").orEmpty().filter { it.startsWith("words_") && it.endsWith(".json") }.map { "content/$it" } +
            context.assets.list("sets").orEmpty().filter { it.endsWith(".json") && it != "index.json" }.map { "sets/$it" } +
            context.assets.list("lexicon").orEmpty().filter { it.endsWith(".json") }.sorted().map { "lexicon/$it" }
        val seen = HashSet<String>()
        val out = ArrayList<Pair<String, SeedWord>>()
        files.forEach { path ->
            val text = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
            LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), text).forEach { w ->
                if (seen.add(DuplicateDetector.normalizeWord(w.lemma))) out += path to w
            }
        }
        return out
    }
}
