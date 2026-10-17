package dev.finalish.core.media

import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Readable previews of Word, Excel and PowerPoint files, built on the phone.
 * Frame.io stores Office files without a preview rendition, so the app reads the
 * document's XML itself (nothing leaves the device): paragraphs and headings,
 * the first sheet as a table, each slide's text.
 */
sealed interface OfficeDoc {
    data class Block(val text: String, val style: Style) {
        enum class Style { Title, Heading, Body, Bullet, PageBreak }
    }

    data class Document(val blocks: List<Block>) : OfficeDoc
    data class Sheet(val name: String, val rows: List<List<String>>, val truncated: Boolean, val otherSheets: Int) : OfficeDoc
    data class Slides(val slides: List<List<String>>) : OfficeDoc
}

object OfficePreview {
    private val TEXT = setOf("txt", "md", "srt", "vtt", "log", "json", "xml", "yaml", "yml")
    private val TABLE = setOf("csv", "tsv")
    val EXTENSIONS = setOf("docx", "xlsx", "pptx") + TEXT + TABLE

    private const val MAX_ROWS = 300
    private const val MAX_COLS = 26
    private const val MAX_TEXT_BYTES = 512 * 1024

    // A crafted .docx/.xlsx/.pptx can inflate a few KB into gigabytes (a zip bomb): read at most
    // this much of any one part, and of all parts together; more fails the preview like a bad file.
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024

    fun read(file: File, ext: String): OfficeDoc? = runCatching {
        when (ext.lowercase()) {
            in TEXT -> return@runCatching text(head(file))
            in TABLE -> return@runCatching table(head(file), if (ext.equals("tsv", true)) '\t' else ',', ext.uppercase())
        }
        ZipFile(file).use { zip ->
            var total = 0L
            fun xml(name: String): String? = zip.getEntry(name)?.let { e ->
                val bytes = zip.getInputStream(e).use { it.readNBytesCompat(MAX_ENTRY_BYTES + 1) }
                check(bytes.size <= MAX_ENTRY_BYTES) { "$name is too large to preview" }
                total += bytes.size
                check(total <= MAX_TOTAL_BYTES) { "Document is too large to preview" }
                bytes.decodeToString()
            }
            when (ext.lowercase()) {
                "docx" -> xml("word/document.xml")?.let(::document)
                "xlsx" -> sheet(::xml, zip.entries().asSequence().map { it.name }.toList())
                "pptx" -> {
                    val names = zip.entries().asSequence().map { it.name }
                        .filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                        .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                        .toList()
                    OfficeDoc.Slides(names.mapNotNull { n -> xml(n)?.let(::slideText) })
                }
                else -> null
            }
        }
    }.getOrNull()

    /** The start of a text file: enough for a preview, never the whole of a huge log. */
    private fun head(file: File): String = file.inputStream().use { input ->
        val bytes = input.readNBytesCompat(MAX_TEXT_BYTES)
        bytes.decodeToString().removePrefix("\uFEFF")
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Plain text: one block per paragraph (blank-line separated), lines kept. */
    internal fun text(body: String): OfficeDoc.Document = OfficeDoc.Document(
        body.removePrefix("\uFEFF").replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { it.trimEnd() }.filter { it.isNotBlank() }
            .map { OfficeDoc.Block(it, OfficeDoc.Block.Style.Body) },
    )

    /** CSV/TSV as a sheet; quoted fields may hold separators, quotes ("") and line breaks. */
    internal fun table(body: String, sep: Char, name: String): OfficeDoc.Sheet {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var truncated = false
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && body.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == sep -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row.take(MAX_COLS)
                    row = mutableListOf()
                    if (rows.size >= MAX_ROWS) { truncated = true; break }
                }
                else -> cell.append(c)
            }
            i++
        }
        if (!truncated && (cell.isNotEmpty() || row.isNotEmpty())) { row += cell.toString(); rows += row.take(MAX_COLS) }
        val width = rows.maxOfOrNull { it.size } ?: 0
        return OfficeDoc.Sheet(name, rows.map { r -> List(width) { r.getOrElse(it) { "" } } }, truncated, 0)
    }

    private fun parser(xml: String): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser().apply { setInput(xml.reader()) }

    /** Word: one block per paragraph; heading styles and lists kept, page breaks marked. */
    internal fun document(xml: String): OfficeDoc.Document {
        val p = parser(xml)
        val blocks = mutableListOf<OfficeDoc.Block>()
        val text = StringBuilder()
        var style = OfficeDoc.Block.Style.Body
        var inText = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "w:p" -> { text.clear(); style = OfficeDoc.Block.Style.Body }
                    "w:pStyle" -> {
                        val v = p.getAttributeValue(null, "w:val").orEmpty().lowercase()
                        style = when {
                            v == "title" -> OfficeDoc.Block.Style.Title
                            v.startsWith("heading") || v.startsWith("titolo") -> OfficeDoc.Block.Style.Heading
                            "list" in v -> OfficeDoc.Block.Style.Bullet
                            else -> style
                        }
                    }
                    "w:numPr" -> if (style == OfficeDoc.Block.Style.Body) style = OfficeDoc.Block.Style.Bullet
                    "w:t" -> inText = true
                    "w:tab" -> text.append('\t')
                    "w:br" -> if (p.getAttributeValue(null, "w:type") == "page") {
                        if (text.isNotBlank()) blocks += OfficeDoc.Block(text.toString().trim(), style)
                        text.clear()
                        blocks += OfficeDoc.Block("", OfficeDoc.Block.Style.PageBreak)
                    } else text.append('\n')
                }
                XmlPullParser.TEXT -> if (inText) text.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inText = false
                    "w:p" -> if (text.isNotBlank()) blocks += OfficeDoc.Block(text.toString().trim(), style)
                }
            }
        }
        return OfficeDoc.Document(blocks)
    }

    /** Excel: the first sheet as rows of cell texts (shared strings resolved). */
    private fun sheet(xml: (String) -> String?, entries: List<String>): OfficeDoc.Sheet? {
        val shared = xml("xl/sharedStrings.xml")?.let(::sharedStrings).orEmpty()
        val sheets = xml("xl/workbook.xml")?.let(::workbookSheets).orEmpty()
        val path = firstSheetPath(sheets.firstOrNull()?.second, xml("xl/_rels/workbook.xml.rels"), entries) ?: return null
        val body = xml(path) ?: return null
        val (rows, truncated) = cells(body, shared)
        return OfficeDoc.Sheet(sheets.firstOrNull()?.first ?: "Sheet1", rows, truncated, (sheets.size - 1).coerceAtLeast(0))
    }

    /** The workbook's sheets in tab order, as (name, relationship id). */
    internal fun workbookSheets(xml: String): List<Pair<String, String?>> {
        val p = parser(xml)
        return buildList {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG || p.name != "sheet") continue
                // The parser isn't namespace aware: the relationship id is "r:id" (any prefix).
                val rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).endsWith(":id") }?.let(p::getAttributeValue)
                add(p.getAttributeValue(null, "name").orEmpty() to rid)
            }
        }
    }

    /**
     * The zip path of the first sheet: its relationship target in workbook.xml.rels (relative to
     * xl/, or absolute from the package root), else the lowest-numbered xl/worksheets/sheetN.xml.
     * The first tab isn't always sheet1.xml once sheets have been reordered or deleted.
     */
    internal fun firstSheetPath(rid: String?, rels: String?, entries: List<String>): String? {
        val target = if (rid != null && rels != null) {
            val p = parser(rels)
            var found: String? = null
            while (found == null && p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" && p.getAttributeValue(null, "Id") == rid) {
                    found = p.getAttributeValue(null, "Target")
                }
            }
            found?.let { if (it.startsWith("/")) it.removePrefix("/") else "xl/$it" }
        } else null
        if (target != null && target in entries) return target
        return entries.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
    }

    internal fun sharedStrings(xml: String): List<String> {
        val p = parser(xml)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "si" -> cur.clear(); "t" -> inT = true }
                XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> out += cur.toString() }
            }
        }
        return out
    }

    internal fun cells(xml: String, shared: List<String>): Pair<List<List<String>>, Boolean> {
        val p = parser(xml)
        val rows = sortedMapOf<Int, MutableMap<Int, String>>()
        var truncated = false
        var row = 0; var col = 0; var type: String? = null
        val value = StringBuilder()
        var inValue = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (row + 1)
                    "c" -> {
                        col = columnIndex(p.getAttributeValue(null, "r").orEmpty())
                        type = p.getAttributeValue(null, "t")
                        value.clear()
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = if (type == "s") raw.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty() else raw
                        if (row > MAX_ROWS || col >= MAX_COLS) truncated = true
                        else if (text.isNotEmpty()) rows.getOrPut(row) { mutableMapOf() }[col] = text
                    }
                }
            }
        }
        val width = rows.values.flatMap { it.keys }.maxOrNull()?.plus(1) ?: 0
        return rows.values.map { r -> List(width) { r[it].orEmpty() } } to truncated
    }

    /** "C12" → 2 (A = 0). */
    private fun columnIndex(ref: String): Int =
        ref.takeWhile { it.isLetter() }.uppercase().fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    /** PowerPoint: a slide's paragraphs, in order. */
    internal fun slideText(xml: String): List<String> {
        val p = parser(xml)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inT = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) { "a:p" -> cur.clear(); "a:t" -> inT = true }
                XmlPullParser.TEXT -> if (inT) cur.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) { "a:t" -> inT = false; "a:p" -> if (cur.isNotBlank()) out += cur.toString().trim() }
            }
        }
        return out
    }
}
