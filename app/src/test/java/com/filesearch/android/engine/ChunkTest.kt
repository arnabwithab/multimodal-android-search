package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkTest {

    private fun zipBytes(entryName: String, content: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    // formatFor

    @Test
    fun `supported extensions map to formats`() {
        assertEquals(DocFormat.TXT, formatFor("note.txt"))
        assertEquals(DocFormat.MD, formatFor("readme.md"))
        assertEquals(DocFormat.HTML, formatFor("page.html"))
        assertEquals(DocFormat.DOCX, formatFor("report.docx"))
        assertEquals(DocFormat.ODT, formatFor("letter.odt"))
        assertEquals(DocFormat.RTF, formatFor("old.rtf"))
    }

    @Test
    fun `formatFor is case insensitive`() {
        assertEquals(DocFormat.TXT, formatFor("NOTE.TXT"))
        assertEquals(DocFormat.DOCX, formatFor("Report.DocX"))
    }

    @Test
    fun `legacy doc and unknown names are unsupported`() {
        assertNull(formatFor("legacy.doc"))
        assertNull(formatFor("scan.pdf"))
        assertNull(formatFor("noextension"))
    }

    // extractText

    @Test
    fun `txt and md decode as utf8`() {
        assertEquals("héllo wörld", extractText(DocFormat.TXT, "héllo wörld".toByteArray(Charsets.UTF_8)))
        assertEquals("# Title\nbody", extractText(DocFormat.MD, "# Title\nbody".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `html strips scripts styles and tags and unescapes entities`() {
        val html = "<html><head><style>p{color:red}</style><script>alert(1)</script></head>" +
            "<body><p>Fish &amp; chips &lt;3 &quot;yum&quot; &#39;yes&#39;</p></body></html>"
        assertEquals("Fish & chips <3 \"yum\" 'yes'", extractText(DocFormat.HTML, html.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `html keeps space across block boundaries`() {
        val text = extractText(DocFormat.HTML, "<p>Fish</p><p>chips</p>".toByteArray(Charsets.UTF_8))
        assertTrue(text.contains("Fish chips"), "merged blocks: '$text'")
    }

    @Test
    fun `docx keeps space across paragraphs`() {
        val xml = "<?xml version=\"1.0\"?><w:document><w:body>" +
            "<w:p><w:t>Fish</w:t></w:p><w:p><w:t>chips</w:t></w:p></w:body></w:document>"
        val text = extractText(DocFormat.DOCX, zipBytes("word/document.xml", xml))
        assertTrue(text.contains("Fish chips"), "merged paragraphs: '$text'")
    }

    @Test
    fun `docx reads word document xml from zip built in test`() {
        val xml = "<?xml version=\"1.0\"?><w:document><w:body><w:p><w:t>Hello docx</w:t></w:p></w:body></w:document>"
        assertEquals("Hello docx", extractText(DocFormat.DOCX, zipBytes("word/document.xml", xml)))
    }

    @Test
    fun `odt reads content xml from zip built in test`() {
        val xml = "<?xml version=\"1.0\"?><office:body><office:p>Hello odt</office:p></office:body>"
        assertEquals("Hello odt", extractText(DocFormat.ODT, zipBytes("content.xml", xml)))
    }

    @Test
    fun `docx and odt with missing entry return empty without throwing`() {
        assertEquals("", extractText(DocFormat.DOCX, zipBytes("other.xml", "<a/>")))
        assertEquals("", extractText(DocFormat.ODT, "not a zip at all".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `rtf strips control words and braces`() {
        val rtf = "{\\rtf1\\ansi\\deff0 Hello \\b bold\\b0 and \\i italic\\i0.}"
        val text = extractText(DocFormat.RTF, rtf.toByteArray(Charsets.UTF_8))
        assertTrue(text.contains("Hello") && text.contains("bold") && text.contains("italic"))
        assertFalse(text.contains("{") || text.contains("\\"))
    }

    // chunk

    @Test
    fun `chunk sizes and overlap math on a known string`() {
        val chunks = chunk("abcdefghij", chunkSize = 4, overlap = 1)
        assertEquals(listOf(0, 3, 6), chunks.map { it.startChar })
        assertEquals(listOf(4, 7, 10), chunks.map { it.endChar })
        assertEquals(listOf("abcd", "defg", "ghij"), chunks.map { it.text })
        assertEquals(listOf(0, 1, 2), chunks.map { it.index })
        assertTrue(chunks.all { it.vectorWanted })
    }

    @Test
    fun `chunk breaks at whitespace instead of cutting mid word`() {
        val text = "aa bb cc dd"
        val chunks = chunk(text, chunkSize = 5, overlap = 0)
        assertTrue(chunks.isNotEmpty())
        // Every boundary except the end of text must sit on whitespace.
        for (c in chunks) {
            if (c.endChar < text.length) assertTrue(text[c.endChar].isWhitespace(), "cut mid-word at ${c.endChar}")
        }
        // Nothing lost: boundaries tile the text with zero overlap.
        assertEquals(0, chunks.first().startChar)
        assertEquals(text.length, chunks.last().endChar)
    }

    @Test
    fun `chunk is deterministic across passes`() {
        val text = ("The quick brown fox jumps over the lazy dog. ".repeat(60))
        val first = chunk(text).map { it.startChar }
        val second = chunk(text).map { it.startChar }
        assertEquals(first, second)
    }

    @Test
    fun `chunk caps at 5000`() {
        val chunks = chunk("z".repeat(300_000), chunkSize = 50, overlap = 0)
        assertEquals(5000, chunks.size)
    }

    @Test
    fun `blank text yields no chunks`() {
        assertEquals(emptyList(), chunk(""))
        assertEquals(emptyList(), chunk("   \n\t  "))
    }

    // digit dumps

    @Test
    fun `digit dump is keyword only`() {
        assertTrue(isDigitDump("12345 67890 42-17 #99 007.5 ".repeat(20)))
        assertFalse(isDigitDump("Call me at 555-1234 tomorrow morning please"))
        assertFalse(isDigitDump(""))
    }

    @Test
    fun `letters at ten percent block digit dump`() {
        // 70% digits hits the first bar but 30% letters fails the second.
        assertFalse(isDigitDump("1234567abc"))
    }

    @Test
    fun `digit dump file gets no vectors on any chunk`() {
        val text = "48291 773 007 42-17 99.5 ".repeat(100)
        val chunks = chunk(text, chunkSize = 100, overlap = 10)
        assertTrue(chunks.isNotEmpty())
        assertFalse(vectorWanted(text))
        assertTrue(chunks.all { !it.vectorWanted })
    }

    // multilingual (no script branching: one path for every script)

    @Test
    fun `multilingual text chunks on the same path`() {
        val text = ("বাংলা নোট Arabic ملاحظة 中文笔记 ไทยโน้ต English note mixed 123 ".repeat(40))
        val chunks = chunk(text, chunkSize = 120, overlap = 20)
        assertTrue(chunks.size > 1)
        assertTrue(vectorWanted(text))
        assertTrue(chunks.all { it.vectorWanted })
        // Every char is covered by at least one chunk.
        val covered = BooleanArray(text.length)
        for (c in chunks) for (i in c.startChar until c.endChar) covered[i] = true
        assertTrue(covered.all { it })
        assertEquals(chunks.map { it.startChar }, chunk(text, chunkSize = 120, overlap = 20).map { it.startChar })
    }
}
