package com.filesearch.android.engine

import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Zip entry bytes are truncated here; untrusted input must never hold RAM hostage. */
const val ZIP_ENTRY_CAP_BYTES = 32 * 1024 * 1024

private const val IO_BUFFER_SIZE = 8192
private const val HEX_RADIX = 16
private const val RTF_UNICODE_MODULO = 65536
private const val DIGIT_DUMP_NON_LETTER_FRACTION = 0.7
private const val DIGIT_DUMP_LETTER_CEILING = 0.1

enum class DocFormat { TXT, MD, HTML, DOCX, ODT, RTF }

fun formatFor(fileName: String): DocFormat? {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt" -> DocFormat.TXT
        "md" -> DocFormat.MD
        "html", "htm" -> DocFormat.HTML
        "docx" -> DocFormat.DOCX
        "odt" -> DocFormat.ODT
        "rtf" -> DocFormat.RTF
        else -> null
    }
}

/** Null means unreadable (missing entry / corrupt zip) so Indexer can mark failed per design §11. */
fun extractText(
    format: DocFormat,
    bytes: ByteArray,
): String? =
    when (format) {
        DocFormat.TXT, DocFormat.MD -> bytes.toString(Charsets.UTF_8)
        DocFormat.HTML -> stripHtml(bytes.toString(Charsets.UTF_8))
        DocFormat.DOCX -> zipEntry(bytes, "word/document.xml")?.let { stripTags(it) }
        DocFormat.ODT -> zipEntry(bytes, "content.xml")?.let { stripTags(it) }
        DocFormat.RTF -> stripRtf(bytes.toString(Charsets.UTF_8))
    }

/** Zip entry text truncated at [ZIP_ENTRY_CAP_BYTES], or null when missing/corrupt — never throws. */
private fun zipEntry(
    bytes: ByteArray,
    name: String,
): String? =
    try {
        ZipInputStream(bytes.inputStream()).use { zip -> readNamedEntry(zip, name) }
    } catch (_: IOException) {
        // Corrupt/truncated zip: the file is marked failed upstream, never crashes the queue.
        null
    }

private fun readNamedEntry(
    zip: ZipInputStream,
    name: String,
): String? {
    var entry = zip.nextEntry
    while (entry != null) {
        if (entry.name == name) return readCapped(zip, ZIP_ENTRY_CAP_BYTES).toString(Charsets.UTF_8)
        zip.closeEntry()
        entry = zip.nextEntry
    }
    return null
}

internal fun readCapped(
    stream: InputStream,
    cap: Int,
): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(IO_BUFFER_SIZE)
    var total = 0
    while (total < cap) {
        val n = stream.read(buf, 0, minOf(buf.size, cap - total))
        if (n <= 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

private fun stripHtml(html: String): String {
    var s = html.replace(Regex("(?is)<script.*?</script>"), "")
    s = s.replace(Regex("(?is)<style.*?</style>"), "")
    return unescape(stripTags(s))
}

private fun stripTags(s: String): String = s.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

private fun unescape(s: String): String =
    s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

// Best-effort one pass: \uN + \'hh decoded first, then control words go, braces go, text stays.
private fun stripRtf(rtf: String): String {
    var s = decodeRtfEscapes(rtf)
    s = s.replace(Regex("\\\\[a-zA-Z]+-?\\d*[ ]?"), " ")
    return s.replace(Regex("[{}\\\\]"), "")
        .replace(Regex("\\s+"), " ").trim()
}

private fun decodeRtfEscapes(s: String): String {
    var out = Regex("""\\'([0-9a-fA-F]{2})""").replace(s) { m -> m.groupValues[1].toInt(HEX_RADIX).toChar().toString() }
    out =
        Regex("""\\u(-?\d+)(.)?""").replace(out) { m ->
            var code = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            if (code < 0) code += RTF_UNICODE_MODULO
            val fallback = m.groupValues[2]
            val ch = code.toChar().toString()
            if (fallback.isEmpty() || isRtfGroupDelimiter(fallback)) ch + fallback else ch
        }
    return out
}

/** Group delimiters stay after a \uN escape; any other char is the RTF fallback and is dropped. */
private fun isRtfGroupDelimiter(s: String): Boolean = s == "\\" || s == "{" || s == "}"

data class Chunk(
    val index: Int,
    val startChar: Int,
    val endChar: Int,
    val text: String,
    val vectorWanted: Boolean,
)

/**
 * ~1800 chars with 200 overlap, broken at whitespace. Deterministic: same input
 * always yields identical starts, so later passes resume exactly.
 */
fun chunk(
    text: String,
    chunkSize: Int = 1800,
    overlap: Int = 200,
    maxChunks: Int = 5000,
): List<Chunk> {
    if (text.isBlank()) return emptyList()
    val size = chunkSize.coerceAtLeast(1)
    val hop = overlap.coerceIn(0, size - 1).let { size - it }
    val out = ArrayList<Chunk>()
    var start = 0
    while (start < text.length && out.size < maxChunks.coerceAtLeast(1)) {
        var end = minOf(start + size, text.length)
        if (end < text.length) {
            val ws = text.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '\r'), end - 1)
            if (ws > start) end = ws
        }
        if (end <= start) end = minOf(start + 1, text.length)
        val slice = text.substring(start, end)
        out.add(Chunk(out.size, start, end, slice, vectorWanted(slice)))
        if (end >= text.length) break
        val next = end - (size - hop)
        start = if (next <= start) start + 1 else next
    }
    return out
}

/** Digit-dump (tables of numbers/IDs) is keyword-only: no vector. */
fun isDigitDump(text: String): Boolean {
    val chars = text.filterNot { it.isWhitespace() }
    if (chars.isEmpty()) return false
    val letters = chars.count { it.isLetter() }
    return (chars.length - letters).toDouble() / chars.length >= DIGIT_DUMP_NON_LETTER_FRACTION &&
        letters.toDouble() / chars.length < DIGIT_DUMP_LETTER_CEILING
}

fun vectorWanted(text: String): Boolean = !isDigitDump(text)
