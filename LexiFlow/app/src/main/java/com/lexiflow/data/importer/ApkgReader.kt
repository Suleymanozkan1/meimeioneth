package com.lexiflow.data.importer

import android.database.sqlite.SQLiteDatabase
import com.lexiflow.core.fsrs.HistoricalReview
import com.lexiflow.core.fsrs.Rating
import com.lexiflow.core.importer.DelimitedParser
import com.lexiflow.core.importer.ParsedTable
import com.lexiflow.core.importer.RowError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Extra data an .apkg row carries besides its fields. */
data class ApkgRowMeta(val ankiNoteId: Long, val history: List<HistoricalReview>, val suspended: Boolean)

data class ApkgResult(
    val table: ParsedTable,
    val meta: List<ApkgRowMeta>,
    val deckName: String?,
    val notesTotal: Int,
    val skippedOtherNoteTypes: Int,
    val mediaFiles: Int,
    val reviewsFound: Int,
)

class UnsupportedApkgException(message: String) : Exception(message)

/**
 * Reads Anki .apkg / .colpkg packages that contain a legacy SQLite collection (collection.anki2 or
 * collection.anki21). Newer packages that only contain the zstd-compressed collection.anki21b are not
 * decoded; the user is told to re-export with "Support older Anki versions" enabled. Media files are
 * counted and reported, not imported.
 */
object ApkgReader {
    /** Upper bound for the extracted collection: real decks are a few MB; a zip bomb must not fill the device. */
    const val MAX_COLLECTION_BYTES = 200L * 1024 * 1024

    fun read(input: InputStream, workDir: File): ApkgResult {
        workDir.mkdirs()
        // Clean up whatever happens (bad zip, too large, disk full, unreadable SQLite): never leave a partial file.
        try {
            return extractAndRead(input, workDir)
        } finally {
            workDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun copyBounded(from: InputStream, to: File) {
        to.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = from.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_COLLECTION_BYTES) throw UnsupportedApkgException("Koleksiyon dosyası çok büyük (en fazla 200 MB açılır).")
                out.write(buf, 0, n)
            }
        }
    }

    private fun extractAndRead(input: InputStream, workDir: File): ApkgResult {
        val found = mutableMapOf<String, File>()
        var hasAnki21b = false
        var mediaCount = 0
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/')
                when {
                    name == "collection.anki21" || name == "collection.anki2" -> {
                        val out = File(workDir, name)
                        copyBounded(zip, out)
                        found[name] = out
                    }
                    name == "collection.anki21b" -> hasAnki21b = true
                    name.isNotEmpty() && name.all { it.isDigit() } -> mediaCount++
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // In new-format packages collection.anki2 is only a stub ("please update Anki"), so it is not used.
        val file = found["collection.anki21"] ?: found["collection.anki2"]?.takeIf { !hasAnki21b } ?: throw UnsupportedApkgException(
            if (hasAnki21b) "Bu paket yalnızca yeni Anki formatını (collection.anki21b, zstd sıkıştırmalı) içeriyor. Anki'de Dışa Aktar → \"Support older Anki versions\" (Eski Anki sürümlerini destekle) seçeneğini işaretleyip tekrar dışa aktar ya da \"Notes in Plain Text\" (.txt) olarak dışa aktar."
            else "Paket içinde Anki koleksiyonu bulunamadı. Dosyanın geçerli bir .apkg olduğundan emin ol veya CSV/TXT olarak dışa aktar.",
        )
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return readCollection(db, mediaCount)
        } finally {
            db.close()
        }
    }

    private fun readCollection(db: SQLiteDatabase, mediaCount: Int): ApkgResult {
        val fieldNames = noteTypeFields(db)
        // Choose the most common note type; other note types are reported, not silently mixed.
        val counts = mutableMapOf<Long, Int>()
        db.rawQuery("SELECT mid, COUNT(*) FROM notes GROUP BY mid", null).use { c -> while (c.moveToNext()) counts[c.getLong(0)] = c.getInt(1) }
        val total = counts.values.sum()
        val mid = counts.maxByOrNull { it.value }?.key ?: return emptyResult(mediaCount)
        val header = (fieldNames[mid] ?: emptyList()).ifEmpty { listOf("Front", "Back") } + "Tags"

        val rows = mutableListOf<List<String>>()
        val meta = mutableListOf<ApkgRowMeta>()
        val errors = mutableListOf<RowError>()
        var reviews = 0
        db.rawQuery("SELECT id, flds, tags FROM notes WHERE mid = ? ORDER BY id", arrayOf(mid.toString())).use { c ->
            var line = 1
            while (c.moveToNext()) {
                line++
                val noteId = c.getLong(0)
                val fields = c.getString(1).split('\u001f').map { DelimitedParser.cleanCell(it) }
                val padded = (fields + List((header.size - 1 - fields.size).coerceAtLeast(0)) { "" }).take(header.size - 1)
                rows += padded + c.getString(2).trim()
                val (history, suspended) = historyFor(db, noteId)
                reviews += history.size
                meta += ApkgRowMeta(noteId, history, suspended)
            }
        }
        val deck = deckName(db)
        return ApkgResult(ParsedTable('\u001f', header, rows, errors), meta, deck, total, total - (counts[mid] ?: 0), mediaCount, reviews)
    }

    private fun emptyResult(media: Int) = ApkgResult(ParsedTable('\u001f', listOf("Front", "Back"), emptyList(), emptyList()), emptyList(), null, 0, 0, media, 0)

    /** Anki revlog: ease 1-4 = Again/Hard/Good/Easy; type 4 (manual reschedule) and ease 0 are not answers. */
    private fun historyFor(db: SQLiteDatabase, noteId: Long): Pair<List<HistoricalReview>, Boolean> {
        var cardId: Long? = null
        var suspended = false
        db.rawQuery("SELECT id, queue FROM cards WHERE nid = ? ORDER BY ord LIMIT 1", arrayOf(noteId.toString())).use { c ->
            if (c.moveToFirst()) { cardId = c.getLong(0); suspended = c.getInt(1) == -1 }
        }
        val id = cardId ?: return emptyList<HistoricalReview>() to false
        val out = mutableListOf<HistoricalReview>()
        db.rawQuery("SELECT id, ease, type FROM revlog WHERE cid = ? ORDER BY id", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) {
                val ease = c.getInt(1)
                val type = c.getInt(2)
                if (ease in 1..4 && type in 0..3) out += HistoricalReview(c.getLong(0), Rating.of(ease))
            }
        }
        return out to suspended
    }

    private fun noteTypeFields(db: SQLiteDatabase): Map<Long, List<String>> {
        val result = mutableMapOf<Long, List<String>>()
        // Schema 11: JSON in col.models
        runCatching {
            db.rawQuery("SELECT models FROM col LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    val text = c.getString(0)
                    if (!text.isNullOrBlank() && text != "{}") {
                        val obj = Json.parseToJsonElement(text).jsonObject
                        obj.forEach { (k, v) ->
                            val flds = (v as JsonObject)["flds"]?.jsonArray ?: return@forEach
                            result[k.toLong()] = flds.map { it.jsonObject }.sortedBy { it["ord"]?.jsonPrimitive?.int ?: 0 }
                                .map { it["name"]?.jsonPrimitive?.content ?: "Field" }
                        }
                    }
                }
            }
        }
        // Schema 18 legacy-compatible exports: separate notetypes/fields tables.
        if (result.isEmpty()) runCatching {
            db.rawQuery("SELECT ntid, name FROM fields ORDER BY ntid, ord", null).use { c ->
                val map = mutableMapOf<Long, MutableList<String>>()
                while (c.moveToNext()) map.getOrPut(c.getLong(0)) { mutableListOf() } += c.getString(1)
                result.putAll(map)
            }
        }
        return result
    }

    private fun deckName(db: SQLiteDatabase): String? = runCatching {
        var did: Long? = null
        db.rawQuery("SELECT did FROM cards GROUP BY did ORDER BY COUNT(*) DESC LIMIT 1", null).use { c -> if (c.moveToFirst()) did = c.getLong(0) }
        val id = did ?: return@runCatching null
        var name: String? = null
        runCatching {
            db.rawQuery("SELECT decks FROM col LIMIT 1", null).use { c ->
                if (c.moveToFirst()) {
                    val obj = Json.parseToJsonElement(c.getString(0)).jsonObject
                    name = obj[id.toString()]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }
            }
        }
        if (name == null) runCatching {
            db.rawQuery("SELECT name FROM decks WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) name = c.getString(0) }
        }
        name?.replace("\u001f", "::")
    }.getOrNull()
}
