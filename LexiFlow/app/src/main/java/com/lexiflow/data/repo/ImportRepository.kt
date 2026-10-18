package com.lexiflow.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.lexiflow.core.fsrs.HistoryReplay
import com.lexiflow.core.importer.DelimitedParser
import com.lexiflow.core.importer.DuplicateDetector
import com.lexiflow.core.importer.DuplicateKind
import com.lexiflow.core.importer.ImportCandidate
import com.lexiflow.core.importer.ParsedTable
import com.lexiflow.core.importer.RowError
import com.lexiflow.core.importer.TargetField
import com.lexiflow.core.language.Morphology
import com.lexiflow.data.content.RichInfo
import com.lexiflow.data.db.AppDatabase
import com.lexiflow.data.db.ReviewLogEntity
import com.lexiflow.data.importer.ApkgReader
import com.lexiflow.data.importer.ApkgRowMeta
import com.lexiflow.util.Clock
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class ImportFormat(val labelTr: String) { CSV_TSV("CSV / TSV / Anki düz metin"), APKG("Anki paketi (.apkg / .colpkg)") }

data class ImportPreview(
    val fileName: String,
    val format: ImportFormat,
    val encoding: String,
    val table: ParsedTable,
    val mapping: List<TargetField>,
    val suggestedDeckName: String,
    val apkgMeta: List<ApkgRowMeta>? = null,
    val infoTr: List<String> = emptyList(),
)

data class ImportRowPreview(val candidate: ImportCandidate, val duplicate: DuplicateKind)

data class ImportReport(
    val deckName: String,
    val imported: Int,
    val newSenses: Int,
    val duplicatesSkipped: List<String>,
    val errors: List<RowError>,
    val historyCards: Int,
    val historyReviews: Int,
    val notesTr: List<String>,
)

/**
 * Import pipeline: read file -> detect encoding -> parse -> map fields -> preview with duplicate status ->
 * import with a full report. Invalid rows are reported, never silently dropped. Review history is only
 * created from real Anki revlog rows (replayed through FSRS); nothing is invented.
 */
class ImportRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val words: WordRepository,
    private val study: StudyRepository,
    private val gamification: GamificationRepository,
    private val clock: Clock,
) {
    suspend fun load(uri: Uri): ImportPreview {
        val name = displayName(uri)
        val lower = name.lowercase()
        if (lower.endsWith(".apkg") || lower.endsWith(".colpkg")) {
            val result = context.contentResolver.openInputStream(uri)!!.use { ApkgReader.read(it, File(context.cacheDir, "apkg_import")) }
            val info = buildList {
                add("${result.notesTotal} not bulundu; ${result.table.rows.size} not aynı not tipinde.")
                if (result.skippedOtherNoteTypes > 0) add("${result.skippedOtherNoteTypes} not farklı not tipinde olduğu için bu içe aktarmaya dahil edilmedi (ayrı CSV olarak aktarabilirsin).")
                add("${result.reviewsFound} gerçek tekrar kaydı (revlog) bulundu; FSRS durumu bu geçmişten yeniden hesaplanacak.")
                if (result.mediaFiles > 0) add("${result.mediaFiles} medya dosyası (ses/görsel) içe aktarılmaz; metin alanlarındaki [sound:] etiketleri temizlenir.")
            }
            return ImportPreview(name, ImportFormat.APKG, "SQLite (UTF-8)", result.table,
                DelimitedParser.suggestMapping(result.table.header, result.table.header?.size ?: 2).let { fixTagsColumn(it, result.table.header) },
                result.deckName ?: name.substringBeforeLast('.'), result.meta, info)
        }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        if (bytes.size > MAX_BYTES) throw IllegalArgumentException("Dosya çok büyük (en fazla 20 MB).")
        val (text, encoding) = decode(bytes)
        val table = DelimitedParser.parse(text)
        val width = table.header?.size ?: table.rows.maxOfOrNull { it.size } ?: 0
        return ImportPreview(name, ImportFormat.CSV_TSV, encoding, table, DelimitedParser.suggestMapping(table.header, width), name.substringBeforeLast('.'))
    }

    private fun fixTagsColumn(mapping: List<TargetField>, header: List<String>?): List<TargetField> {
        if (header == null) return mapping
        val m = mapping.toMutableList()
        if (m.none { it == TargetField.WORD } && m.isNotEmpty()) m[0] = TargetField.WORD
        if (m.none { it == TargetField.MEANING_TR } && m.size > 2) m[1] = TargetField.MEANING_TR
        m[m.lastIndex] = TargetField.TAGS
        return m
    }

    suspend fun previewRows(preview: ImportPreview, mapping: List<TargetField>, limit: Int = 50): Pair<List<ImportRowPreview>, List<RowError>> {
        val (cands, errors) = DelimitedParser.toCandidates(preview.table, mapping)
        val index = words.lemmaMeaningIndex()
        return cands.take(limit).map { ImportRowPreview(it, DuplicateDetector.classify(it.word, it.meaningTr.ifBlank { it.definitionEn ?: "" }, index)) } to errors
    }

    suspend fun import(preview: ImportPreview, mapping: List<TargetField>, deckName: String): ImportReport {
        val (cands, errors) = DelimitedParser.toCandidates(preview.table, mapping)
        val notes = mutableListOf<String>()
        val dupes = mutableListOf<String>()
        var imported = 0
        var newSenses = 0
        var historyCards = 0
        var historyReviews = 0
        val sched = study.scheduler()
        val source = if (preview.format == ImportFormat.APKG) "IMPORT_APKG" else "IMPORT_CSV"
        val defaultDeck = words.createDeck(deckName, source, "İçe aktarıldı: ${preview.fileName}")
        val index = words.lemmaMeaningIndex().mapValues { it.value.toMutableList() }.toMutableMap()
        // Map candidate line -> apkg row meta (row index = line - 2 because the header is line 1).
        val metaByLine = preview.apkgMeta?.withIndex()?.associate { (i, m) -> (i + 2) to m }.orEmpty()

        db.withTransaction {
            for (c in cands) {
                val meaning = c.meaningTr.ifBlank { c.definitionEn ?: "" }
                val key = DuplicateDetector.normalizeWord(c.word)
                val kind = DuplicateDetector.classify(c.word, meaning, index)
                if (kind == DuplicateKind.EXACT) { dupes += "${c.word} — $meaning (satır ${c.lineNumber})"; continue }
                val deckId = c.deckName?.let { words.createDeck(it, source) } ?: defaultDeck
                val example = c.example?.let { buildExample(it, c.exampleTr, c.word) }
                if (c.example != null && example == null) notes += "Satır ${c.lineNumber}: örnek cümle \"${c.word}\" kelimesini içermediği için not alanına taşındı."
                val sense = NewSense(label = meaning.take(60), meaningTr = c.meaningTr, definitionEn = c.definitionEn, examples = listOfNotNull(example))
                val senseId: Long
                if (kind == DuplicateKind.SAME_WORD_NEW_SENSE) {
                    val existing = words.wordsByLemma(c.word).first()
                    senseId = words.addSense(existing.id, sense, origin = "IMPORT")
                    newSenses++
                } else {
                    val notesText = listOfNotNull(c.notes, if (c.example != null && example == null) "Örnek: ${c.example}" else null).joinToString("\n").ifBlank { null }
                    val wordId = words.insertWord(
                        deckId,
                        NewWord(c.word, pos = c.pos, ipaUs = c.ipa, rich = RichInfo(), notes = notesText, tags = c.tags, senses = listOf(sense)),
                        source = "IMPORT",
                        sourceRef = metaByLine[c.lineNumber]?.let { "anki-note:${it.ankiNoteId}" } ?: "${preview.fileName}:${c.lineNumber}",
                    )
                    senseId = db.wordDao().senses(wordId).first().id
                    imported++
                }
                index.getOrPut(key) { mutableListOf() } += meaning
                metaByLine[c.lineNumber]?.let { meta ->
                    val card = db.cardDao().bySense(senseId) ?: return@let
                    if (meta.history.isNotEmpty()) {
                        val replayed = HistoryReplay.replay(meta.history, sched)
                        db.cardDao().update(card.withScheduling(replayed).copy(suspended = meta.suspended))
                        db.reviewLogDao().insertAll(meta.history.map { h ->
                            ReviewLogEntity(cardId = card.id, reviewedAt = h.reviewedAt, localDate = clock.localDate(h.reviewedAt), rating = h.rating.value,
                                stateBefore = -1, elapsedDays = 0, scheduledDays = 0, intervalMillis = 0, stabilityAfter = replayed.stability,
                                difficultyAfter = replayed.difficulty, source = "IMPORT_ANKI", mode = "ANKI_HISTORY")
                        })
                        historyCards++
                        historyReviews += meta.history.size
                    } else if (meta.suspended) {
                        db.cardDao().update(card.copy(suspended = true))
                    }
                }
            }
        }
        if (imported + newSenses > 0) gamification.recordActivity(ActivityDelta())
        if (historyCards > 0) notes += "İçe aktarılan Anki tekrar kayıtları (stateBefore = -1) istatistiklerde \"Anki geçmişi\" olarak tutulur; FSRS durumu bu kayıtlardan hesaplandı."
        notes += "İçe aktarılan kelimelerde dosyadaki örnek cümle (varsa) korunur. Kelime başına 10+ örnek için Kelime Detayı → \"AI ile zenginleştir\" (sunucu gerekir) veya örnekleri elle ekle."
        return ImportReport(deckName, imported, newSenses, dupes, errors, historyCards, historyReviews, notes)
    }

    private fun buildExample(sentence: String, tr: String?, word: String): NewExample? {
        val idx = Morphology.findUsage(sentence, word)
        if (idx < 0) return null
        val token = Morphology.tokens(sentence)[idx]
        val m = Regex("(?i)(?<![A-Za-z])" + Regex.escape(token) + "(?![A-Za-z])").find(sentence) ?: return null
        val answer = if (word.contains(' ')) word else m.value
        return NewExample(sentence, tr, context = "user", answer = if (sentence.contains(answer, true)) answer else m.value)
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "import"
        }
        return uri.lastPathSegment ?: "import"
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024

        /** UTF-8 (with/without BOM), UTF-16 (BOM), otherwise Windows-1254 (Turkish) fallback. */
        fun decode(bytes: ByteArray): Pair<String, String> {
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF") to "UTF-16LE"
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF") to "UTF-16BE"
            return try {
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") to "UTF-8"
            } catch (e: CharacterCodingException) {
                String(bytes, Charset.forName("windows-1254")) to "Windows-1254 (Türkçe)"
            }
        }
    }
}
