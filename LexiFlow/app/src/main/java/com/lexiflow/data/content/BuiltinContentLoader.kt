package com.lexiflow.data.content

import android.content.Context
import android.util.Log
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.ContentSourceEntity
import com.lexiflow.data.db.CulturalContextEntity
import com.lexiflow.data.repo.NewExample
import com.lexiflow.data.repo.NewSense
import com.lexiflow.data.repo.NewWord
import com.lexiflow.data.repo.WordRepository
import com.lexiflow.data.settings.SettingsRepository
import com.lexiflow.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * Seeds the original LexiFlow content shipped in assets/content (authored for this project, see
 * docs/CONTENT_SOURCES_AND_LICENSING.md). Seeding is idempotent and versioned: it never overwrites
 * the learner's edits or review history.
 */
class BuiltinContentLoader(
    private val context: Context,
    private val db: AppDatabase,
    private val words: WordRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Safe to call from several screens at once: the mutex prevents concurrent double-seeding. */
    suspend fun ensureSeeded() = mutex.withLock {
        // Decide from the database itself (not only preferences), so a reset/restored database is re-seeded.
        val needWords = db.deckDao().byName(WordRepository.BUILTIN_DECK) == null
        val needCultural = db.contentDao().builtinCount() == 0
        if (!needWords && !needCultural && settings.current().builtinContentVersion >= CONTENT_VERSION) return@withLock
        runCatching {
            if (needWords) seedWords()
            if (needCultural) seedCultural()
            settings.update { it.copy(builtinContentVersion = CONTENT_VERSION) }
        }.onFailure { Log.e(TAG, "Seeding failed; will retry on next start", it) }
    }

    private fun readAsset(name: String): String = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private suspend fun seedWords() {
        val deckId = words.createDeck(WordRepository.BUILTIN_DECK, "BUILTIN", "LexiFlow için yazılmış özgün içerik: 40 kelime, kelime başına en az 10 örnek cümle.")
        val existing = words.lemmaMeaningIndex().keys
        val files = context.assets.list("content").orEmpty().filter { it.startsWith("words_") && it.endsWith(".json") }.sorted()
        files.forEach { file ->
            val list = LexiJson.decodeFromString(ListSerializer(SeedWord.serializer()), readAsset("content/$file"))
            list.forEach { w ->
                if (DuplicateDetector.normalizeWord(w.lemma) in existing) return@forEach
                words.insertWord(deckId, w.toNewWord(), source = "BUILTIN", sourceRef = "builtin:$file")
            }
        }
    }

    private suspend fun seedCultural() {
        val items = LexiJson.decodeFromString(ListSerializer(SeedCultural.serializer()), readAsset("content/cultural.json"))
        val dao = db.contentDao()
        items.forEach { item ->
            if (dao.sourceByKey("builtin:${item.id}") != null) return@forEach
            val sourceId = dao.insertSource(
                ContentSourceEntity(
                    kind = item.kind, title = item.title, creator = item.creator, year = item.year, sourceUrl = item.sourceUrl,
                    styleTag = item.styleTag, licenseStatus = item.licenseStatus, licenseNote = item.licenseNote, origin = "BUILTIN",
                    externalKey = "builtin:${item.id}", createdAt = clock.now(),
                ),
            )
            dao.insertContexts(
                listOf(
                    CulturalContextEntity(
                        sourceId = sourceId, excerpt = item.excerpt, isQuote = item.isQuote, location = item.location,
                        targetLemma = item.targetWord, targetLemmaKey = DuplicateDetector.normalizeWord(item.targetWord), answer = item.answer,
                        meaningInContextTr = item.meaningInContextTr, tone = item.tone, grammarNoteTr = item.grammarNoteTr,
                        explanationTr = item.explanationTr, productionPromptTr = item.productionPromptTr,
                    ),
                ),
            )
        }
    }

    companion object {
        const val CONTENT_VERSION = 2
        private const val TAG = "ContentLoader"
    }
}

fun SeedWord.toNewWord() = NewWord(
    lemma = lemma, pos = pos, cefr = cefr, ipaUk = ipaUk, ipaUs = ipaUs, syllables = syllables, stress = stress, register = register,
    rich = rich(),
    senses = senses.map { s ->
        NewSense(s.label, s.meaningTr, s.definitionEn, s.examples.map { e -> NewExample(e.en, e.tr, e.context, e.answer, e.acceptedAlternatives, e.noteTr) })
    },
)
