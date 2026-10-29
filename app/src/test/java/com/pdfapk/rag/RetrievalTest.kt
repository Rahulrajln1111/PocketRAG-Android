package com.pdfapk.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random

class RetrievalTest {

    private fun chunk(id: Int, page: Int, text: String) =
        Chunk(id = id, pageIndex = page, text = text)

    // ------------------------------------------------------------------ BM25

    @Test
    fun `bm25 ranks the chunk containing the query term first`() {
        val chunks = listOf(
            chunk(0, 0, "The refund policy allows thirty days for a full refund."),
            chunk(1, 0, "Shipping is handled by external carriers."),
            chunk(2, 1, "Encryption keys rotate every ninety days.")
        )
        val bm25 = Bm25(chunks)

        assertEquals(0, bm25.search("refund", 3).first().first)
        assertEquals(2, bm25.search("ninety", 3).first().first)
    }

    @Test
    fun `bm25 ignores stopword-only and unknown queries`() {
        val bm25 = Bm25(listOf(chunk(0, 0, "alpha beta gamma")))
        assertTrue(bm25.search("the and of", 5).isEmpty())
        assertTrue(bm25.search("zzzz", 5).isEmpty())
    }

    @Test
    fun `bm25 scores are positive and ordered`() {
        val bm25 = Bm25(
            listOf(
                chunk(0, 0, "invoice total due date payment"),
                chunk(1, 0, "payment terms net thirty"),
                chunk(2, 0, "unrelated marketing copy about cats")
            )
        )
        val results = bm25.search("payment invoice", 3)
        assertTrue(results.size >= 2)
        assertTrue(results[0].second >= results[1].second)
        assertTrue(results.all { it.second > 0f })
    }

    // ------------------------------------------------------------- IVF index

    private fun syntheticClusters(
        dim: Int = 32,
        clusters: Int = 6,
        perCluster: Int = 20,
        seed: Long = 11L
    ): Pair<List<FloatArray>, List<FloatArray>> {
        val random = Random(seed)
        val vectors = ArrayList<FloatArray>()
        for (c in 0 until clusters) {
            repeat(perCluster) {
                val vector = FloatArray(dim) { random.nextFloat() * 0.05f }
                vector[c] = 1.0f
                vectors.add(vector)
            }
        }
        val queries = ArrayList<FloatArray>()
        repeat(12) {
            val c = random.nextInt(clusters)
            val query = FloatArray(dim) { random.nextFloat() * 0.05f }
            query[c] = 1.0f
            queries.add(query)
        }
        return vectors to queries
    }

    @Test
    fun `ivf recall against brute force stays high`() {
        val (vectors, queries) = syntheticClusters()
        val index = IvfIndex(dim = 32)
        index.build(vectors, vectors.indices.toList())

        assertTrue(index.isTrained)
        assertEquals(vectors.size, index.size)
        assertTrue(index.nlist >= 2)

        var overlap = 0
        var expected = 0
        for (query in queries) {
            val approximate = index.search(query, 3, nprobe = 2).map { it.chunkId }.toSet()
            val exact = index.searchExact(query, 3).map { it.chunkId }.toSet()
            overlap += approximate.intersect(exact).size
            expected += exact.size
        }
        val recall = overlap.toDouble() / expected
        assertTrue("IVF recall too low: $recall", recall >= 0.85)
    }

    @Test
    fun `index survives a save load round trip`() {
        val (vectors, queries) = syntheticClusters()
        val index = IvfIndex(dim = 32)
        index.build(vectors, vectors.indices.toList())

        val buffer = ByteArrayOutputStream()
        index.save(buffer)
        val restored = IvfIndex.load(ByteArrayInputStream(buffer.toByteArray()))

        assertEquals(index.size, restored.size)
        assertEquals(index.nlist, restored.nlist)
        assertEquals(index.dim, restored.dim)

        for (query in queries.take(4)) {
            val before = index.search(query, 3).map { it.chunkId }
            val after = restored.search(query, 3).map { it.chunkId }
            assertEquals(before, after)
        }
    }

    @Test
    fun `search returns at most k and never more than the index`() {
        val (vectors, _) = syntheticClusters(perCluster = 3, clusters = 2)
        val index = IvfIndex(dim = 32)
        index.build(vectors, vectors.indices.toList())

        assertEquals(6, index.size)
        assertTrue(index.search(vectors[0], 4).size <= 4)
        // Probing every list is equivalent to an exact scan.
        assertEquals(6, index.search(vectors[0], 50, nprobe = index.nlist).size)
    }

    @Test
    fun `recommended list count grows sublinearly`() {
        assertEquals(1, IvfIndex.recommendedNlist(1))
        assertTrue(IvfIndex.recommendedNlist(200) > IvfIndex.recommendedNlist(20))
        assertTrue(IvfIndex.recommendedNlist(100_000) <= 128)
    }

    // ----------------------------------------------------------- hybrid rank

    private fun hybridFixture(): Triple<List<Chunk>, IvfIndex, Bm25> {
        val chunks = listOf(
            chunk(0, 0, "The refund policy allows thirty days for a full refund."),
            chunk(1, 0, "Returns must be sent to the warehouse with the refund form."),
            chunk(2, 1, "Shipping times depend on the carrier."),
            chunk(3, 2, "Encryption keys rotate every ninety days.")
        )
        val vectors = listOf(
            floatArrayOf(0f, 1f, 0f, 0f),
            floatArrayOf(0f, 0.9f, 0.1f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f)
        )
        val index = IvfIndex(dim = 4)
        index.build(vectors, chunks.map { it.id })
        return Triple(chunks, index, Bm25(chunks))
    }

    @Test
    fun `fusion keeps a dense hit and a keyword hit`() {
        val (chunks, index, bm25) = hybridFixture()
        val search = HybridSearch(chunks, index, bm25, minVectorScore = 0.5f, maxChunksPerPage = 1)

        val hits = search.search("refund", floatArrayOf(1f, 0f, 0f, 0f), k = 2)

        assertEquals(2, hits.size)
        assertTrue(hits.any { it.vectorRank >= 0 })
        assertTrue(hits.any { it.keywordRank >= 0 })
        assertEquals(hits.map { it.chunk.pageIndex }.distinct().size, hits.size)
    }

    @Test
    fun `page diversity limits chunks per page`() {
        val (chunks, index, bm25) = hybridFixture()
        val permissive = HybridSearch(chunks, index, bm25, minVectorScore = 0f, maxChunksPerPage = 2)
        val strict = HybridSearch(chunks, index, bm25, minVectorScore = 0f, maxChunksPerPage = 1)

        val many = permissive.search("refund returns", floatArrayOf(0f, 1f, 0f, 0f), k = 4)
        val few = strict.search("refund returns", floatArrayOf(0f, 1f, 0f, 0f), k = 4)

        assertTrue(many.count { it.chunk.pageIndex == 0 } <= 2)
        assertTrue(few.count { it.chunk.pageIndex == 0 } <= 1)
    }

    @Test
    fun `below the floor nothing is returned`() {
        val (chunks, index, bm25) = hybridFixture()
        val search = HybridSearch(chunks, index, bm25, minVectorScore = 0.5f)

        // Orthogonal to every chunk vector, and no keyword overlap.
        val hits = search.search("zzzz", floatArrayOf(0f, 0f, 0f, 1f), k = 4)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `sources merge chunks that share a page`() {
        val (chunks, index, bm25) = hybridFixture()
        val search = HybridSearch(chunks, index, bm25, minVectorScore = 0f, maxChunksPerPage = 2)
        val hits = search.search("refund returns", floatArrayOf(0f, 1f, 0f, 0f), k = 4)

        val sources = search.sourcesOf(hits)
        assertEquals(sources.map { it.pageIndex }.distinct().size, sources.size)
        assertTrue(sources.all { it.preview.isNotBlank() })
    }
}
