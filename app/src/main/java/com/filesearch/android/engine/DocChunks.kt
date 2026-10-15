package com.filesearch.android.engine

import java.util.zip.ZipInputStream

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

// shortcut: corrupt input returns empty, switch to nullable so Indexer can mark failed per design.md §11
fun extractText(
    format: DocFormat,
    bytes: ByteArray,
): String =
    when (format) {
        DocFormat.TXT, DocFormat.MD -> bytes.toString(Charsets.UTF_8)
        DocFormat.HTML -> stripHtml(bytes.toString(Charsets.UTF_8))
        DocFormat.DOCX -> stripTags(zipEntry(bytes, "word/document.xml"))
        DocFormat.ODT -> stripTags(zipEntry(bytes, "content.xml"))
        DocFormat.RTF -> stripRtf(bytes.toString(Charsets.UTF_8))
    }

/** Zip entry text or "" when missing/corrupt — never throws. */
private fun zipEntry(
    bytes: ByteArray,
    name: String,
): String {
    try {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) {
                    return zip.readBytes().toString(
                        Charsets.UTF_8,
                    ) // shortcut: zip entry read uncapped, cap at ~32MB when Indexer wires untrusted input
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } catch (_: Exception) {
        // corrupt zip -> ""
    }
    return ""
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

// Best-effort one pass: control words go, braces go, text stays.
// shortcut: RTF \uN and hex escapes pass through as noise
private fun stripRtf(rtf: String): String =
    rtf.replace(Regex("\\\\[a-zA-Z]+-?\\d*[ ]?"), " ")
        .replace(Regex("[{}\\\\]"), "")
        .replace(Regex("\\s+"), " ").trim()

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
    val wantVector = vectorWanted(text)
    val out = ArrayList<Chunk>()
    var start = 0
    while (start < text.length && out.size < maxChunks.coerceAtLeast(1)) {
        var end = minOf(start + size, text.length)
        if (end < text.length) {
            val ws = text.lastIndexOfAny(charArrayOf(' ', '\t', '\n', '\r'), end - 1)
            if (ws > start) end = ws
        }
        if (end <= start) end = minOf(start + 1, text.length)
        out.add(Chunk(out.size, start, end, text.substring(start, end), wantVector))
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
    return (chars.length - letters).toDouble() / chars.length >= 0.7 && letters.toDouble() / chars.length < 0.1
}

// shortcut: vector flag is per-file, evaluate per-chunk if evals show buried prose
fun vectorWanted(text: String): Boolean = !isDigitDump(text)
