package com.pdfapk.rag

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/**
 * Runs `all-MiniLM-L6-v2` (int8 ONNX) on the device.
 *
 * 384 dimensions, 256-token window, ~23 MB on disk, a few milliseconds per
 * short passage on an arm64 phone. It is a deliberately small model: the
 * embedding only has to rank chunks, while the 1.5B chat model does the
 * reading. Using the chat model for embeddings would cost 20x the memory and
 * produce worse vectors - instruct-tuned LMs are not trained for similarity.
 *
 * Mean pooling over the attention mask (not the [CLS] token) matches how the
 * sentence-transformers model was trained; skipping the mask would let padding
 * leak into every vector.
 */
class OnnxEmbedder(
    private val vocab: WordPiece,
    modelFile: File,
    private val batchSize: Int = 8,
    threads: Int = 2
) : Embedder {

    override val id: String = MODEL_ID
    override val dimensions: Int = DIMENSIONS

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputNames: Set<String>
    private val usesTokenTypes: Boolean

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputNames = session.inputNames
        usesTokenTypes = inputNames.contains("token_type_ids")
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            if (texts.isEmpty()) return@withContext emptyList()
            val out = ArrayList<FloatArray>(texts.size)
            for (batch in texts.chunked(batchSize)) {
                out += embedBatch(batch)
            }
            out
        }

    private fun embedBatch(batch: List<String>): List<FloatArray> {
        val maxLen = vocab.maxLength
        val encoded = batch.map { vocab.encode(it, maxLen) }
        val count = encoded.size
        val ids = LongArray(count * maxLen)
        val mask = LongArray(count * maxLen)
        val types = LongArray(count * maxLen)

        encoded.forEachIndexed { row, item ->
            item.inputIds.copyInto(ids, row * maxLen)
            item.attentionMask.copyInto(mask, row * maxLen)
            item.tokenTypeIds.copyInto(types, row * maxLen)
        }

        val shape = longArrayOf(count.toLong(), maxLen.toLong())
        val inputs = HashMap<String, OnnxTensor>(4)
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape)
            inputs["attention_mask"] = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape)
            if (usesTokenTypes) {
                inputs["token_type_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(types), shape)
            }
            // Some exports use different names; feed whatever the session declares.
            val feed = HashMap<String, OnnxTensor>(inputs.size)
            for (name in inputNames) {
                inputs[name]?.let { feed[name] = it }
            }

            session.run(feed).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result[0].value as Array<Array<FloatArray>>
                return List(count) { row -> pool(hidden[row], encoded[row]) }
            }
        } finally {
            for (tensor in inputs.values) tensor.close()
        }
    }

    /** Attention-masked mean pooling, then L2 normalisation. */
    private fun pool(hidden: Array<FloatArray>, encoded: Encoded): FloatArray {
        val vector = FloatArray(dimensions)
        var tokens = 0
        for (position in hidden.indices) {
            if (encoded.attentionMask.getOrElse(position) { 0L } == 0L) continue
            val row = hidden[position]
            for (i in 0 until minOf(dimensions, row.size)) vector[i] += row[i]
            tokens++
        }
        if (tokens > 0) {
            val inverse = 1f / tokens
            for (i in vector.indices) vector[i] *= inverse
        }
        return VectorMath.normalize(vector)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val MODEL_ID = "minilm-l6-v2-int8"
        const val DIMENSIONS = 384
    }
}
