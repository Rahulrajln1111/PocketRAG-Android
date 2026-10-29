package com.pdfapk.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orchestrates the offline retrieval chain for one open document.
 *
 * Lifecycle: `prepare()` (load a cached index or build one) then `retrieve()`
 * per question. Everything runs on the local models - no network is touched
 * after the embedding model has been downloaded once.
 *
 * The engine is intentionally stateful per document but stateless per
 * question: the index is reused, the query embedding is recomputed each time
 * (a few milliseconds), and no conversation state is kept here.
 */
class RagEngine(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data class Preparing(val progress: Float, val stage: String) : State()
        data class Ready(val stats: IndexStats) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private val modelStore = EmbeddingModelStore(context)
    private val indexStore = IndexStore(context)

    private var embedder: Embedder? = null
    private var document: IndexedDocument? = null
    private var hybrid: HybridSearch? = null

    val isReady: Boolean get() = hybrid != null && embedder != null

    /**
     * Loads the document index from disk or builds it from the extracted pages.
     *
     * Safe to call repeatedly for the same document: the second call is a
     * no-op. A stale cache (different chunker/model version, or changed page
     * text) is deleted and rebuilt instead of being used.
     */
    suspend fun prepare(docKey: String, pages: List<String>): Result<IndexedDocument> =
        mutex.withLock {
            val current = document
            if (current != null && current.docKey == docKey && hybrid != null) {
                _state.value = State.Ready(current.stats)
                return@withLock Result.success(current)
            }

            val sourceChars = pages.sumOf { it.length }
            if (sourceChars == 0) {
                return@withLock fail("This PDF has no extractable text. It is probably a scan.")
            }

            _state.value = State.Preparing(0.02f, "Preparing document")
            document = null
            hybrid = null

            // 1. Reuse a cached index when the version and source match.
            runCatching {
                indexStore.load(
                    docKey = docKey,
                    embedderId = OnnxEmbedder.MODEL_ID,
                    pageCount = pages.size,
                    sourceChars = sourceChars
                )
            }.getOrNull()?.let { cached ->
                attach(cached)
                _state.value = State.Ready(cached.stats)
                return@withLock Result.success(cached)
            }

            // 2. Chunk.
            val chunks = try {
                Chunker().chunk(pages)
            } catch (e: Exception) {
                Log.e(TAG, "Chunking failed", e)
                return@withLock fail("Could not split the document: ${e.message}")
            }
            if (chunks.isEmpty()) {
                return@withLock fail("This PDF has no extractable text. It is probably a scan.")
            }
            _state.value = State.Preparing(0.08f, "Split into ${chunks.size} chunks")

            // 3. Embedding model (downloaded once, then cached on disk).
            val modelFile = modelStore.ensureDownloaded { fraction ->
                val percent = (fraction * 100).toInt()
                _state.value = State.Preparing(
                    0.10f + fraction * 0.35f,
                    if (fraction < 1f) "Downloading embedding model $percent%"
                    else "Embedding model ready"
                )
            }.getOrElse { error ->
                return@withLock fail(
                    "Embedding model unavailable: ${error.message ?: "download failed"}"
                )
            }

            val active = try {
                embedder ?: OnnxEmbedder(modelStore.vocab, modelFile).also { embedder = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Embedding model failed to load", e)
                return@withLock fail("Could not start the embedding model: ${e.message}")
            }

            _state.value = State.Preparing(0.46f, "Loading embedding model")

            // 4. Embed in batches so progress is visible and memory stays flat.
            val vectors = ArrayList<FloatArray>(chunks.size)
            val batches = chunks.chunked(EMBED_BATCH)
            try {
                batches.forEachIndexed { batchIndex, batch ->
                    vectors += active.embed(batch.map { embeddingText(it) })
                    val fraction = (batchIndex + 1).toFloat() / batches.size
                    _state.value = State.Preparing(
                        0.46f + fraction * 0.42f,
                        "Embedding chunks ${batchIndex + 1}/${batches.size}"
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Embedding failed", e)
                return@withLock fail("Embedding failed: ${e.message}")
            }

            // 5. Build the vector index (k-means partitions + int8 codes).
            _state.value = State.Preparing(0.90f, "Building vector index")
            val index = IvfIndex(OnnxEmbedder.DIMENSIONS)
            index.build(vectors, chunks.map { it.id })

            _state.value = State.Preparing(0.96f, "Saving index")
            val saved = try {
                indexStore.save(
                    docKey = docKey,
                    chunks = chunks,
                    index = index,
                    embedderId = OnnxEmbedder.MODEL_ID,
                    pageCount = pages.size,
                    sourceChars = sourceChars
                )
            } catch (e: Exception) {
                Log.e(TAG, "Saving index failed (continuing in memory)", e)
                IndexedDocument(
                    docKey = docKey,
                    chunks = chunks,
                    index = index,
                    version = indexStore.versionFor(OnnxEmbedder.MODEL_ID),
                    pageCount = pages.size,
                    builtAtMillis = System.currentTimeMillis()
                )
            }

            attach(saved)
            _state.value = State.Ready(saved.stats)
            Result.success(saved)
        }

    /**
     * Retrieves the chunks that will be shown to the model.
     *
     * Returns an empty context (not an error) when nothing clears the
     * relevance floor; the caller turns that into an honest refusal.
     */
    suspend fun retrieve(question: String, k: Int = DEFAULT_K): RetrievedContext {
        val active = embedder ?: throw IllegalStateException("Index is not ready")
        val doc = document ?: throw IllegalStateException("Index is not ready")
        val search = hybrid ?: throw IllegalStateException("Index is not ready")
        val query = active.embed(listOf(question)).firstOrNull()
            ?: return RetrievedContext(emptyList(), emptyList(), 0, 0f, doc.chunkCount)
        val hits = search.search(question, query, k = k, candidates = CANDIDATES)
        return RetrievedContext(
            sources = search.sourcesOf(hits),
            hits = hits,
            promptTokens = 0,
            bestVectorScore = hits.maxOfOrNull { it.vectorScore } ?: 0f,
            totalChunks = doc.chunkCount
        )
    }

    /** Frees the ONNX session; the cached index stays on disk. */
    fun reset() {
        embedder?.close()
        embedder = null
        hybrid = null
        document = null
        _state.value = State.Idle
    }

    fun stats(): IndexStats? = document?.stats

    private fun attach(indexed: IndexedDocument) {
        document = indexed
        hybrid = HybridSearch(indexed.chunks, indexed.index, Bm25(indexed.chunks))
    }

    private fun fail(message: String): Result<IndexedDocument> {
        _state.value = State.Failed(message)
        return Result.failure(IllegalStateException(message))
    }

    /** Heading + body: embeddings benefit from the section title. */
    private fun embeddingText(chunk: Chunk): String {
        val heading = chunk.heading
        return if (heading.isNullOrBlank() || chunk.text.startsWith(heading)) chunk.text
        else "$heading\n${chunk.text}"
    }

    companion object {
        private const val TAG = "RagEngine"

        /** Chunks handed to the model. 4 keeps a 1.5B model focused. */
        const val DEFAULT_K = 4

        /** Candidates per retriever before fusion. */
        private const val CANDIDATES = 12

        private const val EMBED_BATCH = 16
    }
}
