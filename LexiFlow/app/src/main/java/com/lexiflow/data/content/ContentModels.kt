package com.lexiflow.data.content

import kotlinx.serialization.Serializable

@Serializable
data class ConfusedWord(val word: String, val noteTr: String)

/** Rich, non-scheduled word information stored as JSON in words.richJson. */
@Serializable
data class RichInfo(
    val collocations: List<String> = emptyList(),
    val phrasalVerbs: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val commonMistakesTr: List<String> = emptyList(),
    val confusedWith: List<ConfusedWord> = emptyList(),
    val grammarNoteTr: String? = null,
)

@Serializable
data class SeedExample(
    val en: String,
    val tr: String? = null,
    val context: String = "daily",
    val answer: String,
    val acceptedAlternatives: List<String> = emptyList(),
    val noteTr: String? = null,
)

@Serializable
data class SeedSense(
    val label: String,
    val meaningTr: String,
    val definitionEn: String? = null,
    val examples: List<SeedExample> = emptyList(),
)

@Serializable
data class SeedWord(
    val lemma: String,
    val pos: String? = null,
    val cefr: String? = null,
    val ipaUk: String? = null,
    val ipaUs: String? = null,
    val syllables: String? = null,
    val stress: String? = null,
    val register: String? = null,
    val collocations: List<String> = emptyList(),
    val phrasalVerbs: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val commonMistakesTr: List<String> = emptyList(),
    val confusedWith: List<ConfusedWord> = emptyList(),
    val grammarNoteTr: String? = null,
    val senses: List<SeedSense> = emptyList(),
) {
    fun rich() = RichInfo(collocations, phrasalVerbs, wordFamily, synonyms, antonyms, commonMistakesTr, confusedWith, grammarNoteTr)
}

@Serializable
data class SeedCultural(
    val id: String,
    val kind: String,
    val styleTag: String? = null,
    val title: String,
    val creator: String? = null,
    val year: Int? = null,
    val sourceUrl: String? = null,
    val location: String? = null,
    val licenseStatus: String,
    val licenseNote: String,
    val isQuote: Boolean,
    val excerpt: String,
    val targetWord: String,
    val answer: String,
    val meaningInContextTr: String,
    val tone: String? = null,
    val grammarNoteTr: String? = null,
    val explanationTr: String? = null,
    val productionPromptTr: String? = null,
)
