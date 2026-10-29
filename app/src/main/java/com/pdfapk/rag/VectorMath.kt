package com.pdfapk.rag

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Small, allocation-conscious vector helpers.
 *
 * All stored vectors are L2-normalised, which turns cosine similarity into a
 * plain dot product and lets the index compare int8 codes without rescaling.
 */
object VectorMath {

    /** Normalises in place; returns the same array for chaining. */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (value in vector) sum += value.toDouble() * value
        val norm = sqrt(sum)
        if (norm < 1e-8) return vector
        val inverse = (1.0 / norm).toFloat()
        for (i in vector.indices) vector[i] *= inverse
        return vector
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Dot product of a float query against int8 codes with a per-vector scale. */
    fun dotInt8(query: FloatArray, codes: ByteArray, offset: Int, scale: Float): Float {
        var sum = 0f
        for (i in query.indices) sum += query[i] * codes[offset + i].toFloat()
        return sum * scale
    }

    /** Cosine similarity for unnormalised inputs (used by tests and diagnostics). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var ab = 0f
        var aa = 0f
        var bb = 0f
        for (i in a.indices) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        val denominator = sqrt(aa) * sqrt(bb)
        return if (denominator < 1e-8f) 0f else ab / denominator
    }

    /**
     * Symmetric scalar quantisation to int8.
     *
     * Halves the index size and keeps the hot inner loop in cache, which is
     * what actually matters on a phone. The accuracy loss is negligible for
     * retrieval because only the ranking is needed, not the exact distance.
     */
    fun quantize(vector: FloatArray): Quantized {
        var maxAbs = 0f
        for (value in vector) {
            val a = abs(value)
            if (a > maxAbs) maxAbs = a
        }
        val scale = if (maxAbs < 1e-8f) 1f else maxAbs / 127f
        val codes = ByteArray(vector.size)
        for (i in vector.indices) {
            codes[i] = (vector[i] / scale).toInt().coerceIn(-127, 127).toByte()
        }
        return Quantized(codes, scale)
    }

    /** Squared euclidean distance; used by k-means on normalised vectors. */
    fun squaredDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += d * d
        }
        return sum
    }
}

data class Quantized(val codes: ByteArray, val scale: Float)
