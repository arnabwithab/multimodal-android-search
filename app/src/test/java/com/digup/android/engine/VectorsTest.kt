package com.digup.android.engine

import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VectorsTest {

    @Test
    fun `fp16 round-trip error below 1e-3 for ordinary magnitudes`() {
        val values = floatArrayOf(
            0.0f, 1.0f, -1.0f, 0.5f, -2.5f, 0.75f, 0.1f,
            100.0f, -100.0f, 0.123f, 1e-4f, -1e-4f, 65504.0f, -65504.0f,
        )
        for (v in values) {
            val back = Vectors.fp16BitsToFloat(Vectors.floatToFp16Bits(v))
            assertTrue(
                kotlin.math.abs(back - v) < 1e-3,
                "round-trip error for $v was ${kotlin.math.abs(back - v)}",
            )
        }
    }

    @Test
    fun `fp16 extremes map to the right bit patterns`() {
        assertEquals(0x0000, Vectors.floatToFp16Bits(0.0f).toInt() and 0xffff)
        assertEquals(0x8000, Vectors.floatToFp16Bits(-0.0f).toInt() and 0xffff)
        assertEquals(0x7c00, Vectors.floatToFp16Bits(Float.POSITIVE_INFINITY).toInt() and 0xffff)
        assertEquals(0xfc00, Vectors.floatToFp16Bits(Float.NEGATIVE_INFINITY).toInt() and 0xffff)
        // overflow of the half range saturates to Inf, never wraps
        assertEquals(0x7c00, Vectors.floatToFp16Bits(1e6f).toInt() and 0xffff)
        // far-subnormal flushes to zero; error (1e-9) stays far below 1e-3
        assertEquals(0x0000, Vectors.floatToFp16Bits(1e-9f).toInt() and 0xffff)
        val nanBits = Vectors.floatToFp16Bits(Float.NaN).toInt() and 0xffff
        assertTrue(nanBits and 0x7c00 == 0x7c00 && nanBits and 0x03ff != 0, "NaN must stay NaN")
        assertEquals(Float.POSITIVE_INFINITY, Vectors.fp16BitsToFloat(0x7c00.toShort()))
        assertEquals(65504.0f, Vectors.fp16BitsToFloat(0x7bff.toShort()))
    }

    @Test
    fun `truncateRenorm slices and returns unit norm`() {
        val out = Vectors.truncateRenorm(floatArrayOf(3.0f, 4.0f), dim = 2)
        assertEquals(0.6f, out[0], 1e-6f)
        assertEquals(0.8f, out[1], 1e-6f)
        var sum = 0.0
        for (x in out) sum += (x * x).toDouble()
        assertEquals(1.0, sqrt(sum), 1e-6)
    }

    @Test
    fun `truncateRenorm keeps first dim entries of a long vector`() {
        val v = FloatArray(300) { if (it == 0) 5.0f else 0.0f }
        val out = Vectors.truncateRenorm(v, dim = 256)
        assertEquals(256, out.size)
        assertEquals(1.0f, out[0], 1e-6f)
        assertEquals(0.0f, out[255], 1e-6f)
    }

    @Test
    fun `truncateRenorm rejects target dim larger than the vector`() {
        assertFailsWith<IllegalArgumentException> {
            Vectors.truncateRenorm(floatArrayOf(1.0f, 2.0f), dim = 256)
        }
    }

    @Test
    fun `cosine agrees with geometry and rejects dim mismatch`() {
        assertEquals(1.0, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 1e-12)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-12)
        assertEquals(-1.0, Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-12)
        assertEquals(0.0, Vectors.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 1e-12)
        assertFailsWith<IllegalArgumentException> {
            Vectors.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f))
        }
    }

    @Test
    fun `topScores matches a naive cosine loop`() {
        val query = floatArrayOf(0.6f, 0.8f, 0.0f, 0.0f)
        val matrix = listOf(
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f),
            floatArrayOf(0.6f, 0.8f, 0f, 0f),
            floatArrayOf(-1f, 0f, 0f, 1f),
        )
        val got = Vectors.topScores(query, matrix)
        assertEquals(matrix.size, got.size)
        for (i in matrix.indices) {
            var dot = 0.0
            var nq = 0.0
            var nb = 0.0
            for (j in query.indices) {
                dot += query[j] * matrix[i][j]
                nq += query[j] * query[j]
                nb += matrix[i][j] * matrix[i][j]
            }
            assertEquals(dot / sqrt(nq * nb), got[i], 1e-12, "row $i")
        }
        assertEquals(emptyList<Double>(), Vectors.topScores(query, emptyList()).toList())
    }
}
