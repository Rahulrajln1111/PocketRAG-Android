package com.pdfapk.rag

/**
 * Text-to-vector abstraction.
 *
 * Kept separate from the ONNX implementation so the retrieval chain can be
 * unit-tested with a deterministic fake embedder, and so a different model
 * (GGUF via llama.cpp, or Gecko via MediaPipe) can be swapped in without
 * touching the index, the ranking or the prompt code.
 */
interface Embedder {

    /** Stable identifier of model + dtype; part of the index version. */
    val id: String

    /** Vector length produced by this model. */
    val dimensions: Int

    /** Embeds a batch. Implementations must return L2-normalised vectors. */
    suspend fun embed(texts: List<String>): List<FloatArray>

    fun close()
}
