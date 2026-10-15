package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-module kind-vocab contract (arch §5, golden.json scripts). READ-ONLY:
 * if any assertion fails, the finding is reported and fixed separately —
 * this file never edits prod. (Prod exposes top-level [jobBatch]/[inferBatch]
 * in Indexer.kt, not an Indexer object; this covers those functions.)
 */
class ContractTest {
    private val segmentKinds = listOf("name", "image", "ocr", "page", "page_image", "chunk", "audio", "frame")

    @Test
    fun `job batch is exactly 8 for image-ish kinds else 1`() {
        val expected =
            mapOf("name" to 1, "image" to 8, "ocr" to 1, "page" to 1, "page_image" to 8, "chunk" to 1, "audio" to 1, "frame" to 8)
        for ((kind, batch) in expected) assertEquals(batch, jobBatch(kind), "kind=$kind")
    }

    @Test
    fun `inference batch is exactly 16 text 4 image 2 audio else 1`() {
        val expected =
            mapOf("name" to 16, "image" to 4, "ocr" to 16, "page" to 16, "page_image" to 4, "chunk" to 16, "audio" to 2, "frame" to 4)
        for ((kind, batch) in expected) assertEquals(batch, inferBatch(kind), "kind=$kind")
        assertEquals(1, inferBatch("zzz_unknown"))
    }

    @Test
    fun `every segment kind outranks unknown in queue order`() {
        val queue = IndexQueue()
        for (kind in segmentKinds) {
            val unknownFirst = queue.order(listOf(QueueItem("b", "zzz_unknown"), QueueItem("a", kind)))
            assertEquals(kind, unknownFirst.first().kind, "kind=$kind")
            val knownFirst = queue.order(listOf(QueueItem("a", kind), QueueItem("b", "zzz_unknown")))
            assertEquals(kind, knownFirst.first().kind, "kind=$kind")
        }
    }

    @Test
    fun `hasWords holds for a sample in each golden script`() {
        val samples =
            mapOf(
                "Latin" to "sunset photo at the beach",
                "Bengali" to "সমুদ্র সৈকতে সূর্যাস্তের ছবি",
                "Arabic" to "صورة غروب الشمس على الشاطئ",
                "Devanagari" to "समुद्र तट पर सूर्यास्त की तस्वीर",
                "CJK" to "海滩日落照片",
                "Japanese" to "ビーチの夕日の写真",
                "Thai" to "รูปพระอาทิตย์ตกที่ชายหาด",
                "mixed" to "beach ছবি sunset সমুদ্র",
            )
        for ((script, s) in samples) assertTrue(SearchText.hasWords(s), "script=$script")
    }

    @Test
    fun `hasWords rejects empty whitespace digits emoji`() {
        val negatives = mapOf("empty" to "", "spaces" to "   \t\n  ", "digits" to "2024 0815 90210", "emoji" to "😀🎉📷")
        for ((kind, s) in negatives) assertEquals(false, SearchText.hasWords(s), "kind=$kind")
    }
}
