package com.lexiflow.core.importer

/** Fields a LexiFlow card can receive from an imported column. */
enum class TargetField(val labelTr: String) {
    IGNORE("Yok say"),
    WORD("İngilizce kelime"),
    MEANING_TR("Türkçe anlam"),
    DEFINITION_EN("İngilizce açıklama"),
    EXAMPLE("Örnek cümle"),
    EXAMPLE_TR("Örnek çeviri"),
    NOTES("Ek açıklama"),
    TAGS("Etiketler"),
    POS("Kelime türü"),
    IPA("IPA"),
    DECK("Deste adı"),
}

data class ParsedTable(
    val delimiter: Char,
    val header: List<String>?,
    val rows: List<List<String>>,
    val errors: List<RowError>,
    /** Real 1-based source line of each row (comments, blank lines and multi-line cells accounted for). */
    val lineNumbers: List<Int> = emptyList(),
    /** Column roles declared by Anki export headers (#tags column:3, #deck column:4, #guid column:1 …). */
    val columnHints: Map<Int, TargetField> = emptyMap(),
)

data class RowError(val lineNumber: Int, val messageTr: String, val raw: String)

data class ImportCandidate(
    val lineNumber: Int,
    val word: String,
    val meaningTr: String,
    val definitionEn: String?,
    val example: String?,
    val exampleTr: String?,
    val notes: String?,
    val tags: List<String>,
    val pos: String?,
    val ipa: String?,
    val deckName: String?,
    /** Index of the source row in [ParsedTable.rows] (used to attach per-row metadata such as Anki history). */
    val rowIndex: Int = -1,
)

/**
 * RFC-4180 style CSV/TSV parser (quoted fields, escaped quotes, embedded newlines, BOM, CRLF),
 * plus Anki "Notes in Plain Text" headers (#separator:tab, #html:true, #tags column:N ...).
 */
object DelimitedParser {

    fun detectDelimiter(text: String): Char {
        Regex("^#separator:(\\S+)", RegexOption.MULTILINE).find(text)?.let { m ->
            return when (m.groupValues[1].lowercase()) {
                "tab" -> '\t'; "comma" -> ','; "semicolon" -> ';'; "pipe" -> '|'; "space" -> ' '; "colon" -> ':'
                else -> m.groupValues[1].first()
            }
        }
        val sample = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.take(20).toList()
        val candidates = listOf('\t', ';', ',', '|')
        return candidates.maxByOrNull { d -> sample.sumOf { line -> line.count { it == d } } } ?: ','
    }

    fun parse(rawText: String, delimiter: Char? = null, hasHeader: Boolean? = null): ParsedTable {
        val text = rawText.removePrefix("\uFEFF")
        val d = delimiter ?: detectDelimiter(text)
        val records = mutableListOf<Pair<Int, List<String>>>()
        val errors = mutableListOf<RowError>()
        var line = 1
        var recordStart = 1
        val field = StringBuilder()
        val current = mutableListOf<String>()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        var commentLine = false

        fun endRecord() {
            current += field.toString(); field.clear()
            if (!(current.size == 1 && current[0].isBlank())) records += recordStart to current.toList()
            current.clear()
        }

        while (i < text.length) {
            val c = text[i]
            if (atFieldStart && current.isEmpty() && field.isEmpty() && c == '#' && !inQuotes) {
                commentLine = true
            }
            if (commentLine) {
                if (c == '\n') { commentLine = false; line++; recordStart = line }
                i++; continue
            }
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> { if (c == '\n') line++; field.append(c) }
                }
                c == '"' && atFieldStart -> { inQuotes = true; atFieldStart = false }
                c == d -> { current += field.toString(); field.clear(); atFieldStart = true }
                c == '\r' -> {}
                c == '\n' -> { endRecord(); line++; recordStart = line; atFieldStart = true }
                else -> { field.append(c); atFieldStart = false }
            }
            i++
        }
        if (inQuotes) errors += RowError(recordStart, "Kapanmamış tırnak işareti; satır sonuna kadar tek alan olarak okundu.", field.toString().take(80))
        if (field.isNotEmpty() || current.isNotEmpty()) endRecord()

        val rows = records.map { it.second.map(::cleanCell) }
        // Anki 2.1.55+ "Notes in Plain Text" can declare the header as a directive: #columns:Front\tBack\tTags
        val columnsDirective = Regex("^#columns:(.*)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?.split(d)?.map { it.trim() }
        val headerDetected = columnsDirective == null && (hasHeader ?: looksLikeHeader(rows.firstOrNull()))
        val header = columnsDirective ?: if (headerDetected) rows.firstOrNull() else null
        val body = if (headerDetected) records.drop(1) else records
        val width = (header?.size ?: rows.maxOfOrNull { it.size }) ?: 0
        val outRows = mutableListOf<List<String>>()
        val lines = mutableListOf<Int>()
        body.forEach { (ln, cells) ->
            val cleaned = cells.map(::cleanCell)
            if (cleaned.all { it.isBlank() }) return@forEach
            if (header != null && cleaned.size != width) {
                errors += RowError(ln, "Sütun sayısı başlıkla uyuşmuyor (${cleaned.size} / $width).", cleaned.joinToString(d.toString()).take(80))
            }
            outRows += cleaned
            lines += ln
        }
        return ParsedTable(d, header, outRows, errors, lines, columnHints(text))
    }

    /** Removes simple HTML that Anki exports (<br>, <b>, &nbsp;) while keeping the text. */
    fun cleanCell(value: String): String = value
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)\\[sound:[^]]+]"), "")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .trim()

    /** Anki column directives are 1-based. GUID and note-type columns are never vocabulary. */
    fun columnHints(text: String): Map<Int, TargetField> {
        val hints = mutableMapOf<Int, TargetField>()
        Regex("^#(tags|deck|guid|notetype) column:(\\d+)", RegexOption.MULTILINE).findAll(text).forEach { m ->
            val idx = m.groupValues[2].toInt() - 1
            hints[idx] = when (m.groupValues[1]) { "tags" -> TargetField.TAGS; "deck" -> TargetField.DECK; else -> TargetField.IGNORE }
        }
        return hints
    }

    /**
     * A header needs at least two known column names: single words like "back" or "deck" are also vocabulary,
     * so one match alone would silently swallow a data row. The import screen also offers a manual toggle.
     */
    private fun looksLikeHeader(first: List<String>?): Boolean {
        if (first == null) return false
        val known = setOf("word", "front", "back", "english", "meaning", "turkish", "türkçe", "anlam", "example", "tags", "tag", "definition", "kelime", "notes", "deck", "translation", "sentence", "örnek")
        return first.count { it.trim().lowercase() in known } >= 2
    }

    /** Suggests a mapping from column headers (or column order when there is no header). */
    fun suggestMapping(header: List<String>?, columnCount: Int, hints: Map<Int, TargetField> = emptyMap()): List<TargetField> {
        if (header == null) {
            // Positional defaults skip columns that Anki declared (guid / notetype / tags / deck).
            val free = mutableListOf(TargetField.WORD, TargetField.MEANING_TR, TargetField.EXAMPLE)
            return (0 until columnCount).map { i ->
                hints[i] ?: if (free.isNotEmpty()) free.removeAt(0) else TargetField.IGNORE
            }
        }
        val used = mutableSetOf<TargetField>()
        return header.map { h ->
            val k = h.trim().lowercase()
            val f = when {
                k in setOf("word", "front", "english", "kelime", "term", "expression") -> TargetField.WORD
                k in setOf("back", "meaning", "turkish", "türkçe", "anlam", "translation", "çeviri") -> TargetField.MEANING_TR
                k.contains("definition") || k == "açıklama" -> TargetField.DEFINITION_EN
                k.contains("example") && (k.contains("tr") || k.contains("translation")) -> TargetField.EXAMPLE_TR
                k.contains("example") || k.contains("sentence") || k == "örnek" -> TargetField.EXAMPLE
                k in setOf("notes", "note", "extra", "not") -> TargetField.NOTES
                k in setOf("tags", "tag", "etiket") -> TargetField.TAGS
                k in setOf("pos", "part of speech", "type", "tür") -> TargetField.POS
                k in setOf("ipa", "pronunciation", "phonetic") -> TargetField.IPA
                k in setOf("deck", "deste") -> TargetField.DECK
                else -> TargetField.IGNORE
            }
            if (f != TargetField.IGNORE && f in used) TargetField.IGNORE else f.also { used += it }
        }.mapIndexed { i, f -> hints[i] ?: f }
    }

    /** Converts parsed rows into candidates; invalid rows are reported, never silently dropped. */
    fun toCandidates(table: ParsedTable, mapping: List<TargetField>): Pair<List<ImportCandidate>, List<RowError>> {
        val errors = table.errors.toMutableList()
        val out = mutableListOf<ImportCandidate>()
        val startLine = if (table.header != null) 2 else 1
        table.rows.forEachIndexed { idx, row ->
            fun get(f: TargetField): String? {
                val i = mapping.indexOf(f)
                return if (i >= 0 && i < row.size) row[i].trim().ifBlank { null } else null
            }
            val line = table.lineNumbers.getOrNull(idx) ?: (startLine + idx)
            val word = get(TargetField.WORD)
            val meaning = get(TargetField.MEANING_TR) ?: get(TargetField.DEFINITION_EN)
            when {
                word == null -> errors += RowError(line, "Kelime alanı boş.", row.joinToString(" | ").take(80))
                meaning == null -> errors += RowError(line, "Anlam alanı boş (Türkçe anlam veya İngilizce açıklama gerekli).", row.joinToString(" | ").take(80))
                word.length > 120 -> errors += RowError(line, "Kelime alanı çok uzun (120 karakter sınırı).", word.take(80))
                else -> out += ImportCandidate(
                    lineNumber = line,
                    word = word,
                    meaningTr = get(TargetField.MEANING_TR) ?: "",
                    definitionEn = get(TargetField.DEFINITION_EN),
                    example = get(TargetField.EXAMPLE),
                    exampleTr = get(TargetField.EXAMPLE_TR),
                    notes = get(TargetField.NOTES),
                    tags = get(TargetField.TAGS)?.split(Regex("[\\s,;]+"))?.filter { it.isNotBlank() } ?: emptyList(),
                    pos = get(TargetField.POS),
                    ipa = get(TargetField.IPA),
                    deckName = get(TargetField.DECK),
                    rowIndex = idx,
                )
            }
        }
        return out to errors
    }
}

enum class DuplicateKind(val labelTr: String) {
    NONE("Yeni"),
    EXACT("Aynı kelime ve aynı anlam zaten var"),
    SAME_WORD_NEW_SENSE("Kelime var ama bu farklı bir anlam — ayrı kart olarak eklenir"),
}

/**
 * Duplicate detection that never merges different senses of the same word:
 * a duplicate is only "exact" when both the normalised word and a normalised meaning match.
 */
object DuplicateDetector {
    fun normalizeWord(word: String) = word.trim().lowercase().replace(Regex("\\s+"), " ").removePrefix("to ")

    fun normalizeMeaning(meaning: String) = meaning.trim().lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun classify(word: String, meaning: String, existing: Map<String, List<String>>): DuplicateKind {
        val meanings = existing[normalizeWord(word)] ?: return DuplicateKind.NONE
        val m = normalizeMeaning(meaning)
        val same = meanings.any { other ->
            val o = normalizeMeaning(other)
            o == m || (m.isNotEmpty() && o.isNotEmpty() && meaningTokensOverlap(o, m) >= 0.8)
        }
        return if (same) DuplicateKind.EXACT else DuplicateKind.SAME_WORD_NEW_SENSE
    }

    private fun meaningTokensOverlap(a: String, b: String): Double {
        val x = a.split(' ').filter { it.isNotBlank() }.toSet()
        val y = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }
}
