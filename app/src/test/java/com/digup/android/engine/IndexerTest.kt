package com.digup.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IndexerTest {

    @Test
    fun `image-ish kinds batch eight files per job`() {
        for (kind in listOf("image", "screenshot", "frame", "page_image")) {
            assertEquals(8, jobBatch(kind), "kind=$kind")
        }
    }

    @Test
    fun `other and unknown kinds batch one file per job`() {
        for (kind in listOf("pdf", "page", "doc", "chunk", "audio", "video", "name", "ocr", "whatever")) {
            assertEquals(1, jobBatch(kind), "kind=$kind")
        }
    }

    @Test
    fun `inference caps follow arch batching`() {
        assertEquals(16, inferBatch("text"))
        assertEquals(4, inferBatch("image"))
        assertEquals(2, inferBatch("audio"))
    }

    @Test
    fun `none and unknown modalities infer one at a time`() {
        assertEquals(1, inferBatch("none"))
        assertEquals(1, inferBatch("whatever"))
    }

    @Test
    fun `inferBatch maps every arch segment kind`() {
        val cases = mapOf(
            "text" to 16, "chunk" to 16, "page" to 16, "ocr" to 16, "name" to 16,
            "image" to 4, "page_image" to 4, "frame" to 4, "screenshot" to 4,
            "audio" to 2,
            "none" to 1, "whatever" to 1,
        )
        for ((kind, expected) in cases) {
            assertEquals(expected, inferBatch(kind), "kind=$kind")
        }
    }

    @Test
    fun `pdf and page kinds are capped to 30 pages in first pass`() {
        assertEquals(30, firstPassLimit("pdf"))
        assertEquals(30, firstPassLimit("page"))
        assertEquals(30, firstPassLimit("page_image"))
    }

    @Test
    fun `doc and chunk kinds are capped to 40 chunks in first pass`() {
        assertEquals(40, firstPassLimit("doc"))
        assertEquals(40, firstPassLimit("chunk"))
    }

    @Test
    fun `short kinds index whole file now`() {
        for (kind in listOf("image", "screenshot", "audio", "video", "frame", "name", "ocr", "whatever")) {
            assertNull(firstPassLimit(kind), "kind=$kind")
        }
    }

    @Test
    fun `pending resolves on remaining units`() {
        assertEquals(FileState.PARTIAL, nextState(FileState.PENDING, 3))
        assertEquals(FileState.DONE, nextState(FileState.PENDING, 0))
    }

    @Test
    fun `partial resolves on remaining units`() {
        assertEquals(FileState.PARTIAL, nextState(FileState.PARTIAL, 1))
        assertEquals(FileState.DONE, nextState(FileState.PARTIAL, 0))
    }

    @Test
    fun `terminal states pass through untouched`() {
        assertEquals(FileState.DONE, nextState(FileState.DONE, 5))
        assertEquals(FileState.DONE, nextState(FileState.DONE, 0))
        assertEquals(FileState.FAILED, nextState(FileState.FAILED, 5))
        assertEquals(FileState.FAILED, nextState(FileState.FAILED, 0))
        assertEquals(FileState.STOPPED, nextState(FileState.STOPPED, 5))
        assertEquals(FileState.STOPPED, nextState(FileState.STOPPED, 0))
    }

    @Test
    fun `negative unfinished units are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            nextState(FileState.PENDING, -1)
        }
    }

    @Test
    fun `order follows screenshots images pdfs docs audio video`() {
        val queue = IndexQueue()
        val input = listOf("video", "audio", "doc", "pdf", "image", "screenshot")
            .map { QueueItem("$it.dat", it) }
        assertEquals(
            listOf("screenshot", "image", "pdf", "doc", "audio", "video"),
            queue.order(input).map { it.kind },
        )
    }

    @Test
    fun `order is stable within a rank and keeps unknowns last`() {
        val queue = IndexQueue()
        val input = listOf(
            QueueItem("odd.dat", "whatever"),
            QueueItem("a.jpg", "image"),
            QueueItem("myst.dat", "mystery"),
            QueueItem("b.jpg", "image"),
        )
        assertEquals(
            listOf("a.jpg", "b.jpg", "odd.dat", "myst.dat"),
            queue.order(input).map { it.path },
        )
    }

    @Test
    fun `order keeps every input and handles empty`() {
        val queue = IndexQueue()
        assertEquals(emptyList(), queue.order(emptyList()))
        val input = listOf(QueueItem("b", "b-kind"), QueueItem("a", "a-kind"))
        assertEquals(input, queue.order(input))
    }

    @Test
    fun `every known kind ranks ahead of unknown`() {
        val queue = IndexQueue()
        val input = listOf(
            "video", "text", "frame", "audio", "ocr", "doc", "chunk",
            "page", "pdf", "page_image", "image", "screenshot", "name", "whatever",
        ).map { QueueItem("$it.dat", it) }
        assertEquals(
            listOf(
                "screenshot", "page_image", "image", "page", "pdf", "text", "ocr",
                "doc", "chunk", "name", "audio", "video", "frame", "whatever",
            ),
            queue.order(input).map { it.kind },
        )
    }

    @Test
    fun `finishLongFiles returns unfinished kinds in queue rank order`() {
        assertEquals(
            listOf("image", "pdf", "doc", "video"),
            finishLongFiles(mapOf("video" to 3, "doc" to 1, "image" to 2, "pdf" to 5, "audio" to 0)),
        )
    }

    @Test
    fun `finishLongFiles drops done files and empty input`() {
        assertEquals(emptyList(), finishLongFiles(emptyMap()))
        assertEquals(emptyList(), finishLongFiles(mapOf("pdf" to 0, "doc" to 0)))
    }
}
