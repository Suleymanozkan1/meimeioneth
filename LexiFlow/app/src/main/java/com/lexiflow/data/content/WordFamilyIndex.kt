package com.lexiflow.data.content

import android.content.Context
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class FamilyItem(val word: String, val pos: String, val tr: String) {
    /** How it is stored in [RichInfo.wordFamily] and shown on the card: "decision (n) — karar". */
    fun label() = "$word ($pos) — $tr"
}

@Serializable
data class PhrasalItem(val phrase: String, val tr: String) {
    fun label() = "$phrase — $tr"
}

@Serializable
data class FamilyEntry(val lemma: String, val family: List<FamilyItem> = emptyList(), val phrasalVerbs: List<PhrasalItem> = emptyList())

/** What the "Kelime ailesi" section of a card shows. */
data class WordFamilyInfo(val family: List<String>, val phrasalVerbs: List<String>, val collocations: List<String>) {
    val isEmpty get() = family.isEmpty() && phrasalVerbs.isEmpty() && collocations.isEmpty()
}

/**
 * Derivatives (decide → decision, decisive), phrasal verbs and collocations for a lemma, from LexiFlow's own
 * assets/families file, the word-family notes of the bundled Anki decks and the phrasal-verb deck.
 */
class WordFamilyIndex(private val context: Context, private val localContent: LocalContentIndex) {
    private val curated: Map<String, FamilyEntry> by lazy {
        runCatching {
            val text = context.assets.open("families/families.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            LexiJson.decodeFromString(ListSerializer(FamilyEntry.serializer()), text).associateBy { key(it.lemma) }
        }.getOrDefault(emptyMap())
    }

    /** Phrasal verbs of the bundled phrasal-verb deck, by their verb ("give" → give up, give in …). */
    private val phrasalDeck: Map<String, List<PhrasalItem>> by lazy {
        runCatching {
            val text = context.assets.open("anki/anki-phrasal-verbs.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), text)
                .filter { it.lemma.trim().contains(' ') }
                .map { w -> key(w.lemma.trim().substringBefore(' ')) to PhrasalItem(w.lemma.trim(), w.senses.firstOrNull()?.meaningTr.orEmpty().substringBefore(';').take(60)) }
                .filter { it.second.tr.isNotBlank() }
                .groupBy({ it.first }, { it.second })
        }.getOrDefault(emptyMap())
    }

    /** Anki decks write families as "research (v), researcher (n)"; a split "(v, adj)" is put back together. */
    private val ankiFamilies: Map<String, List<String>> by lazy {
        val out = HashMap<String, List<String>>()
        runCatching {
            context.assets.list("anki").orEmpty().filter { it.endsWith(".json") && it != "index.json" }.forEach { f ->
                val text = context.assets.open("anki/$f").bufferedReader(Charsets.UTF_8).use { it.readText() }
                LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), text).forEach { w ->
                    val items = cleanAnkiFamily(w.wordFamily)
                    if (items.isNotEmpty()) out.putIfAbsent(key(w.lemma), items)
                }
            }
        }
        out
    }

    fun lookup(lemma: String, rich: RichInfo = RichInfo()): WordFamilyInfo {
        val k = key(lemma)
        val c = curated[k]
        val family = rich.wordFamily.ifEmpty { c?.family?.map { it.label() } ?: ankiFamilies[k].orEmpty() }
        val phrasal = (rich.phrasalVerbs + (c?.phrasalVerbs?.map { it.label() }.orEmpty()) + phrasalDeck[k].orEmpty().map { it.label() })
            .distinctBy { it.substringBefore(" — ").lowercase() }.take(5)
        val collocations = rich.collocations.ifEmpty { localContent.find(lemma)?.collocations.orEmpty() }.take(6)
        return WordFamilyInfo(family.take(5), if (lemma.trim().contains(' ')) emptyList() else phrasal, collocations)
    }

    /** Fills empty family / phrasal-verb / collocation fields of [rich]; filled fields are kept as they are. */
    fun enrich(lemma: String, rich: RichInfo): RichInfo {
        val info = lookup(lemma, rich)
        return rich.copy(
            wordFamily = rich.wordFamily.ifEmpty { info.family },
            phrasalVerbs = rich.phrasalVerbs.ifEmpty { info.phrasalVerbs },
            collocations = rich.collocations.ifEmpty { info.collocations },
        )
    }

    private fun key(lemma: String) = DuplicateDetector.normalizeWord(lemma)

    companion object {
        fun cleanAnkiFamily(raw: List<String>): List<String> {
            val joined = raw.joinToString(", ")
            return Regex("([A-Za-z][A-Za-z'\\-]*)\\s*\\(([^)]*)\\)").findAll(joined)
                .map { "${it.groupValues[1]} (${it.groupValues[2].replace(Regex("\\s+"), " ").trim()})" }
                .distinct().toList()
        }
    }
}
