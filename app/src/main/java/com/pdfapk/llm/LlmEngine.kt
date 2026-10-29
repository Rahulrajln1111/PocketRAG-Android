package com.pdfapk.llm

import android.content.Context
import android.util.Log
import com.pdfapk.PDFApkApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Callback interface invoked from native code during token generation.
 */
interface GenerationCallback {
    fun onToken(partialText: String, isComplete: Boolean)
}

/**
 * Kotlin wrapper around the llama.cpp JNI bridge.
 *
 * Provides suspend-friendly load / generate / unload operations
 * and a [downloadModel] helper for fetching the GGUF model from Hugging Face.
 */
class LlmEngine(private val context: Context) {

    companion object {
        private const val TAG = "LlmEngine"
        private const val MODEL_FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf"

        init {
            System.loadLibrary("llama_bridge")
        }

        /** Directory where the GGUF model file is stored. */
        val modelDir: File
            get() = File(PDFApkApplication.instance.filesDir, "models")

        val modelFile: File
            get() = File(modelDir, MODEL_FILENAME)
    }

    // ── Model download state ────────────────────────────────────────────────
    data class DownloadState(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
        val error: String? = null,
        val isComplete: Boolean = false
    )

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    // ── Loading state ───────────────────────────────────────────────────────
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    // ── Native JNI methods ──────────────────────────────────────────────────
    private external fun nativeLoadModel(modelPath: String): Boolean
    private external fun nativeIsModelLoaded(): Boolean
    private external fun nativeUnloadModel()
    private external fun nativeGenerate(
        systemPrompt: String,
        userPrompt: String,
        callback: GenerationCallback
    )
    private external fun nativeGetModelInfo(): String
    private external fun nativeResetContext()

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Download the Qwen 2.5 GGUF model from Hugging Face.
     * Emits progress via [downloadState].
     */
    suspend fun downloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = DownloadState(isDownloading = true)

            if (!modelDir.exists()) modelDir.mkdirs()

            val url = URL(MODEL_URL)
            val connection = url.openConnection()
            connection.connect()
            val totalBytes = connection.contentLengthLong
            val inputStream = connection.getInputStream()
            val outputStream = FileOutputStream(modelFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                _downloadState.value = DownloadState(
                    isDownloading = true,
                    progress = progress,
                    bytesDownloaded = totalRead,
                    totalBytes = totalBytes
                )
            }

            outputStream.close()
            inputStream.close()

            _downloadState.value = DownloadState(
                isComplete = true,
                progress = 1f,
                bytesDownloaded = totalRead,
                totalBytes = totalBytes
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            _downloadState.value = DownloadState(error = e.message)
            Result.failure(e)
        }
    }

    /**
     * Load the downloaded model into memory.
     * Must be called after [downloadModel] completes successfully.
     */
    suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!modelFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Model file not found. Please download it first.")
                )
            }
            val loaded = nativeLoadModel(modelFile.absolutePath)
            if (loaded) {
                _isModelLoaded.value = true
                Log.i(TAG, "Model loaded from ${modelFile.absolutePath}")
                Result.success(Unit)
            } else {
                Result.failure(RuntimeException("Failed to load model"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Load failed", e)
            Result.failure(e)
        }
    }

    /** True if the native library has a model loaded and ready. */
    fun isLoaded(): Boolean = nativeIsModelLoaded()

    /**
     * Generate a response using the loaded model.
     *
     * @param systemPrompt The system-level instruction.
     * @param userPrompt  The user's request / context.
     * @param onPartial   Called repeatedly with the latest partial output.
     * @param onComplete  Called once with the full output when generation finishes.
     */
    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        onPartial: (String) -> Unit = {},
        onComplete: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isLoaded()) {
                return@withContext Result.failure(
                    IllegalStateException("Model not loaded")
                )
            }

            val sb = StringBuilder()

            val callback = object : GenerationCallback {
                override fun onToken(partialText: String, isComplete: Boolean) {
                    if (isComplete) {
                        onComplete(partialText)
                    } else {
                        sb.clear()
                        sb.append(partialText)
                        onPartial(partialText)
                    }
                }
            }

            nativeGenerate(systemPrompt, userPrompt, callback)
            Result.success(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Generation failed", e)
            Result.failure(e)
        }
    }

    /** Returns a human-readable string about the current model. */
    fun getModelInfo(): String = nativeGetModelInfo()

    /** Reset the KV cache / conversation context. */
    fun resetContext() = nativeResetContext()

    /** Unload the model from memory. */
    fun unload() {
        nativeUnloadModel()
        _isModelLoaded.value = false
    }

    /** Reset the download state to its initial value. */
    fun resetDownloadState() {
        _downloadState.value = DownloadState()
    }
}
