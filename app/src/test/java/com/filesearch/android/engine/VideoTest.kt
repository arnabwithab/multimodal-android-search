package com.filesearch.android.engine

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VideoTest {

    @Test
    fun `ahash of identical arrays is equal`() {
        val gray = ByteArray(64) { it.toByte() }
        assertEquals(VideoKeyframes.ahash(gray), VideoKeyframes.ahash(gray.copyOf()))
    }

    @Test
    fun `ahash of inverted halves has max distance`() {
        val dark = ByteArray(64) { if (it < 32) 0 else 0xFF.toByte() }
        val light = ByteArray(64) { if (it < 32) 0xFF.toByte() else 0 }
        assertEquals(64, VideoKeyframes.hamming(VideoKeyframes.ahash(dark), VideoKeyframes.ahash(light)))
    }

    @Test
    fun `ahash rejects wrong size`() {
        assertFailsWith<IllegalArgumentException> { VideoKeyframes.ahash(ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { VideoKeyframes.ahash(ByteArray(65)) }
    }

    @Test
    fun `hamming known values`() {
        assertEquals(0, VideoKeyframes.hamming(0L, 0L))
        assertEquals(64, VideoKeyframes.hamming(0L, -1L))
        assertEquals(4, VideoKeyframes.hamming(0xAL, 0x5L))
        assertEquals(1, VideoKeyframes.hamming(0L, 1L))
    }

    @Test
    fun `selectFrames keeps shot changes and drops same-shot runs`() {
        val hashes = List(10) { 0L } + listOf(-1L) + List(10) { -1L }
        assertEquals(listOf(0, 10), VideoKeyframes.selectFrames(hashes))
    }

    @Test
    fun `selectFrames drops near-duplicates within threshold`() {
        val base = 0L
        val near = 0b1111L // 4 bits differ -> same shot
        assertEquals(listOf(0), VideoKeyframes.selectFrames(listOf(base, near, base)))
    }

    @Test
    fun `selectFrames cap respected`() {
        val hashes = List(2500) { if (it % 2 == 0) 0L else -1L }
        // Long duration so the ~1-per-3s budget does not thin below MAX_FRAMES.
        val kept = VideoKeyframes.selectFrames(hashes, durationSec = 7500.0)
        assertEquals(VideoKeyframes.MAX_FRAMES, kept.size)
    }

    @Test
    fun `selectFrames thins fast cuts to about one per 3s`() {
        val hashes = List(30) { if (it % 2 == 0) 0L else -1L }
        val kept = VideoKeyframes.selectFrames(hashes) // 30s -> budget 10
        assertEquals(10, kept.size)
        assertEquals(0, kept.first())
    }

    @Test
    fun `selectFrames uniform frames keep only first`() {
        val gray = ByteArray(64) { 127 }
        val hashes = List(20) { VideoKeyframes.ahash(gray) }
        assertEquals(listOf(0), VideoKeyframes.selectFrames(hashes))
    }

    @Test
    fun `planSpans end-to-next-start plus last-to-duration`() {
        val frames = VideoKeyframes.planSpans(listOf(0, 3, 10), 12.0)
        assertEquals(
            listOf(
                VideoKeyframes.VideoFrame(0, 0.0, 3.0),
                VideoKeyframes.VideoFrame(3, 3.0, 10.0),
                VideoKeyframes.VideoFrame(10, 10.0, 12.0),
            ),
            frames,
        )
    }

    @Test
    fun `empty input yields empty`() {
        assertEquals(emptyList(), VideoKeyframes.selectFrames(emptyList()))
        assertEquals(emptyList(), VideoKeyframes.planSpans(emptyList(), 60.0))
    }

    @Test
    fun `planSpans clamps last span when duration precedes kept timestamp`() {
        val frames = VideoKeyframes.planSpans(listOf(0, 10), 5.0)
        assertEquals(VideoKeyframes.VideoFrame(0, 0.0, 10.0), frames[0])
        assertEquals(VideoKeyframes.VideoFrame(10, 10.0, 10.0), frames[1])
    }

    @Test
    fun `planSpans rejects non-positive fps and unsorted kept indices`() {
        assertFailsWith<IllegalArgumentException> { VideoKeyframes.planSpans(listOf(0), 5.0, fps = 0) }
        assertFailsWith<IllegalArgumentException> { VideoKeyframes.planSpans(listOf(3, 1), 5.0) }
        assertFailsWith<IllegalArgumentException> { VideoKeyframes.planSpans(listOf(1, 1), 5.0) }
    }
}
