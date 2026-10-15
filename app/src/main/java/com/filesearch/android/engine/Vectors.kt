package com.filesearch.android.engine

import kotlin.math.sqrt

/**
 * fp16 storage helpers + block cosine scoring over MRL-256 vectors.
 *
 * Vectors are stored fp16 (L2-normed, 256d v1) and scored float32 — float16
 * compute is forbidden (silent NaNs); the LiteRT QAT files already handle it
 * (docs/architecture.md §2). Query and doc dims must match; a fingerprint
 * change re-embeds everything instead of mixing dims.
 */
object Vectors {
    /** Float32 -> IEEE-754 half bits (round-half-up; overflow saturates to Inf). */
    fun floatToFp16Bits(f: Float): Short {
        val bits = f.toBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xff
        val mant = bits and 0x7fffff
        return when {
            exp == 0xff -> {
                // Inf/NaN: keep payload so NaN stays NaN.
                val payload = mant ushr 13
                val h = sign or 0x7c00 or payload
                if (payload == 0 && mant != 0) (h or 0x0200).toShort() else h.toShort()
            }
            exp == 0 -> sign.toShort() // zero or f32-subnormal: flush to signed zero
            else -> {
                val e16 = exp - 127 + 15
                if (e16 >= 31) {
                    (sign or 0x7c00).toShort()
                } else if (e16 <= 0) {
                    // Half-subnormal (or underflow to zero): exact power-of-two scale.
                    val full = mant or 0x800000
                    val shift = 14 - e16
                    if (shift >= 25) {
                        sign.toShort()
                    } else {
                        val half = 1 shl (shift - 1)
                        val rest = full and ((1 shl shift) - 1)
                        var m = full ushr shift
                        if (rest > half || (rest == half && m and 1 == 1)) m++
                        (sign or (m and 0x3ff)).toShort()
                    }
                } else {
                    var m = mant + 0x1000 // round the 13 dropped bits
                    var e = e16
                    if (m and 0x800000 != 0) {
                        m = 0
                        e++ // mantissa carry
                    }
                    if (e >= 31) (sign or 0x7c00).toShort() else (sign or (e shl 10) or (m ushr 13)).toShort()
                }
            }
        }
    }

    /** IEEE-754 half bits -> Float32 (exact for all finite values). */
    fun fp16BitsToFloat(h: Short): Float {
        val bits = h.toInt() and 0xffff
        val sign = (bits and 0x8000) shl 16
        val exp = (bits ushr 10) and 0x1f
        val mant = bits and 0x3ff
        return when {
            exp == 0 ->
                if (mant == 0) {
                    Float.fromBits(sign)
                } else {
                    // Subnormal: mant * 2^-24, exact in float32.
                    val s = if (sign != 0) -1.0f else 1.0f
                    s * mant.toFloat() * 5.9604645e-8f
                }
            exp == 0x1f -> Float.fromBits(sign or 0x7f800000 or (mant shl 13)) // Inf/NaN
            else -> Float.fromBits(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
        }
    }

    /**
     * Slice to [dim] (MRL truncation) and L2 re-normalise. Query and docs must
     * share the dim (256d v1); larger targets than the input are rejected.
     */
    fun truncateRenorm(
        v: FloatArray,
        dim: Int = 256,
    ): FloatArray {
        require(dim > 0) { "truncateRenorm: dim must be positive, was $dim" }
        require(v.size >= dim) {
            "truncateRenorm: vector dim ${v.size} < target $dim (query/doc dims must match)"
        }
        val out = v.copyOf(dim)
        var sum = 0.0
        for (x in out) sum += (x * x).toDouble()
        val norm = sqrt(sum)
        if (norm == 0.0) return out
        for (i in out.indices) out[i] = (out[i] / norm).toFloat()
        return out
    }

    /** Cosine similarity; zero vectors score 0.0. Dims must match. */
    fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        require(a.size == b.size) {
            "cosine: dim mismatch ${a.size} vs ${b.size} (query/doc must share dim)"
        }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / sqrt(na * nb)
    }

    /** Naive block scorer (no JNI): one cosine per row, same order as [matrix]. */
    fun topScores(
        query: FloatArray,
        matrix: List<FloatArray>,
    ): DoubleArray = DoubleArray(matrix.size) { cosine(query, matrix[it]) }
}
