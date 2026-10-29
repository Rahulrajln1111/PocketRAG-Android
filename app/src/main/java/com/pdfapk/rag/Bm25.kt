package com.pdfapk.rag

import kotlin.math.ln

/**
 * BM25 over the document chunks.
 *
 * Dense vectors are bad at exact tokens: part numbers, error codes, API names,
 * version strings and rare proper nouns. Those are precisely the things users
 * quote when they ask a question about a technical PDF, so keyword search is
 * not a legacy fallback here, it is half of the retrieval quality.
 *
 * This is the classic Robertson/Sparck Jones formulation with Lucene's
 * positive IDF variant, which avoids negative scores for terms that appear in
 * more than half of the chunks.
 */
class Bm25(
    chunks: List<Chunk>,
    private val k1: Double = 1.2,
    private val b: Double = 0.75
) {

    private val termFreqs: List<Map<String, Int>>
    private val lengths: IntArray
    private val postings: Map<String, IntArray>
    private val idf: Map<String, Double>
    private val averageLength: Double

    val size: Int get() = termFreqs.size

    init {
        val freqs = ArrayList<Map<String, Int>>(chunks.size)
        val lens = IntArray(chunks.size)
        val postingsBuilder = HashMap<String, MutableList<Int>>()
        val documentFrequency = HashMap<String, Int>()

        chunks.forEachIndexed { index, chunk ->
            val counts = HashMap<String, Int>()
            for (term in TextUtils.terms(chunk.text) + TextUtils.terms(chunk.heading ?: "")) {
                counts[term] = (counts[term] ?: 0) + 1
            }
            freqs.add(counts)
            lens[index] = counts.values.sum()
            for (term in counts.keys) {
                postingsBuilder.getOrPut(term) { ArrayList() }.add(index)
                documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
            }
        }

        termFreqs = freqs
        lengths = lens
        postings = postingsBuilder.mapValues { (_, ids) -> ids.toIntArray() }
        averageLength = if (lens.isEmpty()) 0.0 else lens.average()
        idf = documentFrequency.mapValues { (_, df) ->
            ln(1.0 + (chunks.size - df + 0.5) / (df + 0.5))
        }
    }

    /** Top-[topK] chunk indices with their BM25 score. */
    fun search(query: String, topK: Int = 12): List<Pair<Int, Float>> {
        if (termFreqs.isEmpty()) return emptyList()
        val terms = TextUtils.terms(query).distinct()
        if (terms.isEmpty()) return emptyList()

        val scores = HashMap<Int, Double>()
        for (term in terms) {
            val ids = postings[term] ?: continue
            val termIdf = idf[term] ?: continue
            for (doc in ids) {
                val tf = termFreqs[doc][term] ?: continue
                val lengthNorm = if (averageLength > 0) lengths[doc] / averageLength else 1.0
                val denominator = tf + k1 * (1 - b + b * lengthNorm)
                scores[doc] = (scores[doc] ?: 0.0) + termIdf * (tf * (k1 + 1)) / denominator
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key to it.value.toFloat() }
    }

    /** BM25 score of one chunk for a query, used by tests and diagnostics. */
    fun score(query: String, chunkIndex: Int): Double {
        if (chunkIndex !in termFreqs.indices) return 0.0
        val terms = TextUtils.terms(query).distinct()
        var total = 0.0
        for (term in terms) {
            val tf = termFreqs[chunkIndex][term] ?: continue
            val termIdf = idf[term] ?: continue
            val lengthNorm = if (averageLength > 0) lengths[chunkIndex] / averageLength else 1.0
            val denominator = tf + k1 * (1 - b + b * lengthNorm)
            total += termIdf * (tf * (k1 + 1)) / denominator
        }
        return total
    }
}
