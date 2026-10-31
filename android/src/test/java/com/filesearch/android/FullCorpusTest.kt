package com.filesearch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F025 full-corpus planning (pure half): batch commits, bucket coverage,
 * per-kind status rows, PDF page plans, first-pass cap. Device I/O
 * (MediaStore cursors, Pdfium, LiteRT) is phone-gated; the planning here
 * pins the contracts the worker and status sheet bind to.
 */
class FullCorpusTest {
    @Test
    fun `batchRanges splits per-50 with a short tail`() {
        assertEquals(
            listOf(0 until 50, 50 until 100, 100 until 120),
            FullCorpus.batchRanges(120),
        )
    }

    @Test
    fun `batchRanges keeps a short corpus whole`() {
        assertEquals(listOf(0 until 30), FullCorpus.batchRanges(30))
    }

    @Test
    fun `batchRanges empties on empty or bad input`() {
        assertTrue(FullCorpus.batchRanges(0).isEmpty())
        assertTrue(FullCorpus.batchRanges(-3).isEmpty())
        assertTrue(FullCorpus.batchRanges(10, 0).isEmpty())
    }

    @Test
    fun `distinctBuckets dedupes blanks and sorts`() {
        assertEquals(
            listOf("Camera", "Download", "Screenshots"),
            FullCorpus.distinctBuckets(
                listOf("Screenshots", null, "", "Camera", "Camera", "Download", "  "),
            ),
        )
    }

    @Test
    fun `corpusKindRows always carries image plus pdf`() {
        val rows = FullCorpus.corpusKindRows(60, 200, 3, 5)
        assertEquals(listOf("image", "pdf"), rows.map { it.kind })
        assertEquals(60L, rows[0].done)
        assertEquals(200L, rows[0].total)
        assertEquals(3L, rows[1].done)
        assertEquals(5L, rows[1].total)
    }

    @Test
    fun `text page plans a segment at its page`() {
        val plan = FullCorpus.pdfPagePlan(2, "word ".repeat(60))
        assertNotNull(plan)
        assertEquals(2, plan!!.page)
    }

    @Test
    fun `short locked or scanned pages plan nothing`() {
        assertNull(FullCorpus.pdfPagePlan(0, "short scan"))
        assertNull(FullCorpus.pdfPagePlan(0, "word ".repeat(60), isLocked = true))
        assertNull(FullCorpus.pdfPagePlan(0, "word ".repeat(60), isScanned = true))
    }

    @Test
    fun `first pass caps at 30 pages`() {
        assertEquals(0 until 30, FullCorpus.firstPassPages(200))
        assertEquals(0 until 5, FullCorpus.firstPassPages(5))
    }
}
