package com.pdfapk.rag

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Owns the embedding model on disk.
 *
 * The model is fetched once (~23 MB) and cached in internal storage, the same
 * pattern the chat GGUF uses. The vocabulary (232 KB) ships inside the APK
 * because it is small, deterministic, and a mismatched vocabulary would yield
 * silently wrong vectors.
 */
class EmbeddingModelStore(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val vocab: WordPiece by lazy {
        context.assets.open(VOCAB_ASSET).use { WordPiece.from(it) }
    }

    val modelFile: File
        get() = File(File(context.filesDir, "models").apply { mkdirs() }, MODEL_FILENAME)

    fun isDownloaded(): Boolean =
        modelFile.exists() && modelFile.length() >= MIN_VALID_BYTES

    /**
     * Downloads the model if it is missing. Progress is reported as 0..1 of
     * the download, not of the whole indexing job - the caller maps it.
     */
    suspend fun ensureDownloaded(onProgress: (Float) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            if (isDownloaded()) return@withContext Result.success(modelFile)

            val partial = File(modelFile.parentFile, "$MODEL_FILENAME.part")
            try {
                val request = Request.Builder().url(MODEL_URL).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Model download failed: HTTP ${response.code}")
                        )
                    }
                    val total = response.body?.contentLength() ?: -1L
                    var written = 0L
                    partial.delete()
                    response.body?.byteStream()?.use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (partial.length() < MIN_VALID_BYTES) {
                    partial.delete()
                    return@withContext Result.failure(
                        IllegalStateException("Model download was truncated (${partial.length()} bytes)")
                    )
                }
                if (!partial.renameTo(modelFile)) {
                    partial.delete()
                    return@withContext Result.failure(
                        IllegalStateException("Could not move the downloaded model into place")
                    )
                }
                Log.i(TAG, "Embedding model ready: ${modelFile.length()} bytes")
                Result.success(modelFile)
            } catch (e: Exception) {
                partial.delete()
                Log.e(TAG, "Embedding model download failed", e)
                Result.failure(e)
            }
        }

    fun delete() {
        modelFile.delete()
        File(modelFile.parentFile, "$MODEL_FILENAME.part").delete()
    }

    companion object {
        private const val TAG = "EmbeddingModelStore"
        const val MODEL_FILENAME = "minilm-l6-v2-int8.onnx"
        const val VOCAB_ASSET = "minilm_vocab.txt"
        const val MODEL_URL =
            "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"

        /** The int8 export is ~22.9 MB; anything much smaller is a failed download. */
        private const val MIN_VALID_BYTES = 20_000_000L
    }
}
