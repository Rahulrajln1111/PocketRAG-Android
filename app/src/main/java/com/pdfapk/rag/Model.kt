package com.pdfapk.rag

/**
 * Core data model for the retrieval-augmented generation chain.
 *
 * Everything here is plain Kotlin (no Android types) so the whole retrieval
 * path can be unit-tested on the JVM without a device or an emulator.
 */

/**
 * A retrievable unit of the document.
 *
 * [pageIndex] is zero-based and is the only thing the UI needs to jump back to
 * the source; [charStart]/[charEnd] index into the cleaned page text so a
 * future highlighter can underline the exact passage.
 */
data class Chunk(
    val id: Int,
    val pageIndex: Int,
    val text: String,
    val heading: String? = null,
    val charStart: Int = 0,
    val charEnd: Int = 0
) {
    /** Short single-line preview used in the citation chips. */
    fun preview(maxChars: Int = 90): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= maxChars) flat else flat.take(maxChars).trimEnd() + "…"
    }
}

/** One retrieved chunk with the scores that produced it. */
data class Hit(
    val chunk: Chunk,
    val vectorScore: Float,
    val keywordScore: Float,
    val fusedScore: Float,
    val vectorRank: Int = -1,
    val keywordRank: Int = -1
)

/**
 * Grouping of the final hits by page, i.e. what the user sees as a citation.
 * Adjacent chunks from one page are merged into a single source entry.
 */
data class SourceRef(
    val pageIndex: Int,
    val score: Float,
    val preview: String
)

/** Result of a retrieval call: what went into the prompt and why. */
data class RetrievedContext(
    val sources: List<SourceRef>,
    val hits: List<Hit>,
    val promptTokens: Int,
    val bestVectorScore: Float,
    val totalChunks: Int
) {
    val isEmpty: Boolean get() = hits.isEmpty()

    /** True when nothing cleared the relevance floor - answer "not in the document". */
    val isBelowFloor: Boolean get() = hits.isEmpty()
}

/** Everything that is persisted for one indexed document. */
data class IndexedDocument(
    val docKey: String,
    val chunks: List<Chunk>,
    val index: IvfIndex,
    val version: String,
    val pageCount: Int,
    val builtAtMillis: Long
) {
    val chunkCount: Int get() = chunks.size

    val stats: IndexStats
        get() = IndexStats(
            chunkCount = chunks.size,
            pageCount = pageCount,
            nlist = index.nlist,
            nprobe = index.defaultNprobe,
            dim = index.dim,
            bytesOnDisk = index.approximateBytes()
        )
}

/** Human-readable description of the index, shown in the chat header. */
data class IndexStats(
    val chunkCount: Int,
    val pageCount: Int,
    val nlist: Int,
    val nprobe: Int,
    val dim: Int,
    val bytesOnDisk: Long
) {
    fun summary(): String =
        "$chunkCount chunks · $pageCount pages · ${dim}d · IVF " +
            "$nlist lists, probe $nprobe · ${(bytesOnDisk / 1024.0).toInt()} KB"
}

/** Progress callback payload used while indexing a document. */
data class IndexProgress(
    val fraction: Float,
    val stage: String
)
