package com.digup.android.engine

import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Deterministic fake: unit-norm vectors seeded by content hash. */
class FakeBackend(val vecDim: Int = 512, val delayMs: Long = 0) : EmbedBackend {
    val seenTexts = Collections.synchronizedList(mutableListOf<String>())
    val seenImages = Collections.synchronizedList(mutableListOf<ByteArray>())
    val seenAudio = Collections.synchronizedList(mutableListOf<ByteArray>())
    val calls = AtomicInteger(0)
    private val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    fun vecFor(s: String): FloatArray = norm(FloatArray(vecDim) {
        Random(s.hashCode().toLong() * 31 + it).nextFloat() * 2 - 1
    })

    fun vecForBytes(b: ByteArray): FloatArray = vecFor(b.contentHashCode().toString())

    private fun norm(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val n = sqrt(sum)
        if (n == 0.0) return v
        for (i in v.indices) v[i] = (v[i] / n).toFloat()
        return v
    }

    private fun <T, R> guarded(input: List<T>, seen: MutableList<T>, map: (T) -> R): List<R> {
        calls.incrementAndGet()
        val cur = active.incrementAndGet()
        maxActive.accumulateAndGet(cur, Math::max)
        try {
            seen.addAll(input)
            if (delayMs > 0) Thread.sleep(delayMs)
            return input.map(map)
        } finally {
            active.decrementAndGet()
        }
    }

    override fun embedTexts(texts: List<String>): List<FloatArray> =
        guarded(texts, seenTexts, ::vecFor)

    override fun embedImages(blobs: List<ByteArray>): List<FloatArray> =
        guarded(blobs, seenImages, ::vecForBytes)

    override fun embedAudio(windows: List<ByteArray>): List<FloatArray> =
        guarded(windows, seenAudio, ::vecForBytes)
}

class EmbedderTest {

    private fun normOf(v: FloatArray): Double {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        return sqrt(sum)
    }

    @Test
    fun `query and docs share dim and unit norm`() {
        val embedder = Embedder(FakeBackend(), dim = 256)
        val q = embedder.embedQuery("beach sunset")
        assertEquals(256, q.size)
        assertEquals(1.0, normOf(q), 1e-6)
        val docs = embedder.embedDocs(listOf("T" to "beach photo", "U" to "receipt"))
        assertEquals(2, docs.size)
        for (d in docs) {
            assertEquals(256, d.size)
            assertEquals(1.0, normOf(d), 1e-6)
        }
    }

    @Test
    fun `query prefix changes vector vs unprefixed`() {
        val fake = FakeBackend()
        val embedder = Embedder(fake, dim = 256)
        val got = embedder.embedQuery("hello world")
        val raw = Vectors.truncateRenorm(fake.vecFor("hello world"), 256)
        assertFalse(got.contentEquals(raw), "prefixed query must differ from raw text vector")
        assertEquals(listOf(QUERY_PREFIX + "hello world"), fake.seenTexts.toList())
    }

    @Test
    fun `docPrefix format and embedDocs uses it`() {
        assertEquals("title: T | text: C", docPrefix("T", "C"))
        val fake = FakeBackend()
        Embedder(fake, dim = 256).embedDocs(listOf("My Title" to "body text"))
        assertEquals(listOf("title: My Title | text: body text"), fake.seenTexts.toList())
    }

    @Test
    fun `media takes no prefix and keeps raw bytes`() {
        val fake = FakeBackend()
        val embedder = Embedder(fake, dim = 256)
        val img = byteArrayOf(1, 2, 3, 4)
        val audio = byteArrayOf(5, 6, 7, 8)
        val iv = embedder.embedImage(img)
        val av = embedder.embedAudio(audio)
        assertEquals(256, iv.size)
        assertEquals(256, av.size)
        assertTrue(fake.seenImages.single().contentEquals(img))
        assertTrue(fake.seenAudio.single().contentEquals(audio))
    }

    @Test
    fun `concurrent calls are serialised behind one lock`() {
        val fake = FakeBackend(delayMs = 10)
        val embedder = Embedder(fake, dim = 256)
        val threads = List(8) { i ->
            Thread { repeat(5) { embedder.embedQuery("q$i-$it") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue(threads.none { it.isAlive }, "threads must finish")
        assertEquals(1, fake.maxActive.get(), "LiteRT session is single-threaded: max-active must be 1")
        assertEquals(40, fake.calls.get())
    }

    @Test
    fun `fingerprint format is exact`() {
        val embedder = Embedder(FakeBackend(), dim = 256)
        assertEquals(
            "embeddinggemma-2-text-270m@r1 cpu int4 256 t512/i4/a2",
            embedder.fingerprint("embeddinggemma-2-text-270m", "r1", "cpu", "int4", "t512/i4/a2"),
        )
        assertEquals("m@rev gpu fp16 256 b", Embedder(FakeBackend(), dim = 256).fingerprint("m", "rev", "gpu", "fp16", "b"))
    }

    @Test
    fun `backend returning wrong dim throws before truncate`() {
        val embedder = Embedder(FakeBackend(vecDim = 4), dim = 256)
        assertFailsWith<IllegalArgumentException> { embedder.embedQuery("too short") }
        assertFailsWith<IllegalArgumentException> { embedder.embedImage(byteArrayOf(1)) }
    }
}
