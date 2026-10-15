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
    private const val F32_SIGN_SHIFT = 16
    private const val F32_SIGN_MASK = 0x8000
    private const val F32_EXP_SHIFT = 23
    private const val F32_EXP_MASK = 0xff
    private const val F32_MANT_MASK = 0x7fffff
    private const val F32_EXP_BIAS = 127
    private const val F32_HIDDEN_BIT = 0x800000
    private const val F32_INF_BITS = 0x7f800000
    private const val F16_EXP_BIAS = 15
    private const val F16_EXP_MASK = 0x1f
    private const val F16_EXP_MAX = 31
    private const val F16_EXP_SHIFT = 10
    private const val F16_INF_BITS = 0x7c00
    private const val F16_MANT_MASK = 0x3ff
    private const val F16_QNAN_BIT = 0x0200
    private const val F16_ROUND_BIT = 0x1000
    private const val F16_SUBNORMAL_SHIFT_BASE = 14
    private const val F16_SUBNORMAL_MAX_SHIFT = 25
    private const val F32_TO_F16_MANT_SHIFT = 13
    private const val U16_MASK = 0xffff
    private const val F16_SUBNORMAL_SCALE = 5.9604645e-8f // 2^-24, exact in float32

    /** Float32 -> IEEE-754 half bits (round-half-up; overflow saturates to Inf). */
    fun floatToFp16Bits(f: Float): Short {
        val bits = f.toBits()
        val sign = (bits ushr F32_SIGN_SHIFT) and F32_SIGN_MASK
        val exp = (bits ushr F32_EXP_SHIFT) and F32_EXP_MASK
        val mant = bits and F32_MANT_MASK
        return when {
            exp == F32_EXP_MASK -> {
                // Inf/NaN: keep payload so NaN stays NaN.
                val payload = mant ushr F32_TO_F16_MANT_SHIFT
                val h = sign or F16_INF_BITS or payload
                if (payload == 0 && mant != 0) (h or F16_QNAN_BIT).toShort() else h.toShort()
            }
            exp == 0 -> sign.toShort() // zero or f32-subnormal: flush to signed zero
            else -> {
                val e16 = exp - F32_EXP_BIAS + F16_EXP_BIAS
                when {
                    e16 >= F16_EXP_MAX -> (sign or F16_INF_BITS).toShort()
                    e16 <= 0 -> subnormalHalfBits(sign, mant, e16)
                    else -> normalHalfBits(sign, mant, e16)
                }
            }
        }
    }

    /** Half-subnormal arm (or underflow to zero): exact power-of-two scale. */
    private fun subnormalHalfBits(
        sign: Int,
        mant: Int,
        e16: Int,
    ): Short {
        val full = mant or F32_HIDDEN_BIT
        val shift = F16_SUBNORMAL_SHIFT_BASE - e16
        if (shift >= F16_SUBNORMAL_MAX_SHIFT) return sign.toShort()
        val half = 1 shl (shift - 1)
        val rest = full and ((1 shl shift) - 1)
        var m = full ushr shift
        if (rest > half || (rest == half && m and 1 == 1)) m++
        return (sign or (m and F16_MANT_MASK)).toShort()
    }

    /** Normal-range arm with round-half-up on the 13 dropped bits. */
    private fun normalHalfBits(
        sign: Int,
        mant: Int,
        e16: Int,
    ): Short {
        var m = mant + F16_ROUND_BIT
        var e = e16
        if (m and F32_HIDDEN_BIT != 0) {
            m = 0
            e++ // mantissa carry
        }
        return if (e >= F16_EXP_MAX) {
            (sign or F16_INF_BITS).toShort()
        } else {
            (sign or (e shl F16_EXP_SHIFT) or (m ushr F32_TO_F16_MANT_SHIFT)).toShort()
        }
    }

    /** IEEE-754 half bits -> Float32 (exact for all finite values). */
    fun fp16BitsToFloat(h: Short): Float {
        val bits = h.toInt() and U16_MASK
        val sign = (bits and F32_SIGN_MASK) shl F32_SIGN_SHIFT
        val exp = (bits ushr F16_EXP_SHIFT) and F16_EXP_MASK
        val mant = bits and F16_MANT_MASK
        return when {
            exp == 0 ->
                if (mant == 0) {
                    Float.fromBits(sign)
                } else {
                    // Subnormal: mant * 2^-24, exact in float32.
                    val s = if (sign != 0) -1.0f else 1.0f
                    s * mant.toFloat() * F16_SUBNORMAL_SCALE
                }
            exp == F16_EXP_MASK -> Float.fromBits(sign or F32_INF_BITS or (mant shl F32_TO_F16_MANT_SHIFT)) // Inf/NaN
            else -> Float.fromBits(sign or ((exp - F16_EXP_BIAS + F32_EXP_BIAS) shl F32_EXP_SHIFT) or (mant shl F32_TO_F16_MANT_SHIFT))
        }
    }

    /**
     * Slice to [dim] (MRL truncation) and L2 re-normalise. Query and docs must
     * share the dim (256d v1); larger targets than the input are rejected.
     *
     * Measured caveat (Oct 2026 CPU probe, `google/embeddinggemma-300m` real
     * weights): naive truncation drifts mean 0.074 / max 0.177 vs full-dim
     * cosine, so production MUST use the model's native MRL-256 output; this
     * function is for tests/fallback only.
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
