package com.pdfapk.rag

/**
 * Fuses dense (IVF) and sparse (BM25) retrieval into the handful of chunks
 * that actually go into the prompt.
 *
 * Three decisions worth defending in a review:
 *
 * 1. **Reciprocal Rank Fusion, not score averaging.** Cosine similarity and
 *    BM25 live on different scales, so averaging them needs per-corpus
 *    calibration. RRF only uses ranks (`1/(60+rank)`), which is scale-free and
 *    is what Elasticsearch/Weaviate do by default.
 * 2. **A relevance floor on the vector score.** Below the floor the honest
 *    answer is "this is not in the document", not six unrelated chunks. BM25
 *    hits bypass the floor because keyword matches are exact by construction.
 * 3. **Page diversity.** Two adjacent chunks from page 12 add almost no
 *    information but consume a quarter of an 8k-token window on a 1.5B model,
 *    so at most [maxChunksPerPage] chunks per page survive.
 */
class HybridSearch(
    private val chunks: List<Chunk>,
    private val index: IvfIndex,
    private val bm25: Bm25,
    private val minVectorScore: Float = 0.28f,
    private val maxChunksPerPage: Int = 2,
    private val rrfK: Int = 60
) {

    private val byId: Map<Int, Chunk> = chunks.associateBy { it.id }

    fun search(
        query: String,
        queryVector: FloatArray,
        k: Int = 4,
        candidates: Int = 12
    ): List<Hit> {
        if (chunks.isEmpty()) return emptyList()

        val vectorHits = index.search(queryVector, candidates)
        val keywordHits = bm25.search(query, candidates)

        val vectorScoreById = HashMap<Int, Float>(vectorHits.size)
        val vectorRankById = HashMap<Int, Int>(vectorHits.size)
        vectorHits.forEachIndexed { rank, hit ->
            vectorScoreById[hit.chunkId] = hit.score
            vectorRankById[hit.chunkId] = rank
        }

        val keywordScoreById = HashMap<Int, Float>(keywordHits.size)
        val keywordRankById = HashMap<Int, Int>(keywordHits.size)
        keywordHits.forEachIndexed { rank, (position, score) ->
            val id = chunks.getOrNull(position)?.id ?: return@forEachIndexed
            keywordScoreById[id] = score
            keywordRankById[id] = rank
        }

        val fused = ArrayList<Hit>(vectorHits.size + keywordHits.size)
        for (id in vectorScoreById.keys + keywordScoreById.keys) {
            val chunk = byId[id] ?: continue
            val vectorScore = vectorScoreById[id] ?: 0f
            val keywordScore = keywordScoreById[id] ?: 0f
            val vectorRank = vectorRankById[id] ?: -1
            val keywordRank = keywordRankById[id] ?: -1

            val passesFloor = vectorScore >= minVectorScore || keywordRank >= 0
            if (!passesFloor) continue

            var score = 0f
            if (vectorRank >= 0) score += 1f / (rrfK + vectorRank + 1)
            if (keywordRank >= 0) score += 1f / (rrfK + keywordRank + 1)

            fused.add(
                Hit(
                    chunk = chunk,
                    vectorScore = vectorScore,
                    keywordScore = keywordScore,
                    fusedScore = score,
                    vectorRank = vectorRank,
                    keywordRank = keywordRank
                )
            )
        }

        val ordered = fused.sortedWith(
            compareByDescending<Hit> { it.fusedScore }
                .thenByDescending { it.vectorScore }
        )

        val perPage = HashMap<Int, Int>()
        val selected = ArrayList<Hit>(k)
        for (hit in ordered) {
            if (selected.size >= k) break
            val used = perPage[hit.chunk.pageIndex] ?: 0
            if (used >= maxChunksPerPage) continue
            perPage[hit.chunk.pageIndex] = used + 1
            selected.add(hit)
        }
        return selected
    }

    /** Groups hits into citation chips, merging chunks that share a page. */
    fun sourcesOf(hits: List<Hit>): List<SourceRef> =
        hits.groupBy { it.chunk.pageIndex }
            .map { (page, pageHits) ->
                val best = pageHits.maxByOrNull { it.fusedScore } ?: pageHits.first()
                SourceRef(
                    pageIndex = page,
                    score = best.fusedScore,
                    preview = pageHits
                        .sortedBy { it.chunk.charStart }
                        .joinToString(" ") { it.chunk.preview(120) }
                )
            }
            .sortedByDescending { it.score }
}
