package com.pdfapk.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Persists chat messages per PDF file.
 *
 * Each PDF gets its own JSON file named by a SHA-256 hash of its URI.
 * This avoids filesystem issues with long/special-character URIs.
 *
 * Files are stored in filesDir/chat_history/ and are automatically
 * cleaned up when the app is uninstalled (since they're in app-internal storage).
 */
class ChatHistoryManager(private val context: Context) {

    companion object {
        private const val TAG = "ChatHistoryManager"
        private const val DIR_NAME = "chat_history"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Serializable
    data class ChatHistorySource(
        val pageIndex: Int,
        val score: Float = 0f,
        val preview: String = ""
    )

    @Serializable
    data class ChatHistoryMessage(
        val role: String,  // "USER", "ASSISTANT", "SYSTEM"
        val content: String,
        val sources: List<ChatHistorySource> = emptyList()
    )

    private val historyDir: File
        get() = File(context.filesDir, DIR_NAME).also { it.mkdirs() }

    private fun fileForPdf(pdfUri: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(pdfUri.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(historyDir, "$hash.json")
    }

    /**
     * Load saved messages for a PDF. Returns empty list if none saved.
     */
    suspend fun loadHistory(pdfUri: String): List<ChatHistoryMessage> = withContext(Dispatchers.IO) {
        val file = fileForPdf(pdfUri)
        if (!file.exists()) return@withContext emptyList()
        try {
            val text = file.readText()
            json.decodeFromString<List<ChatHistoryMessage>>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load chat history, corrupt file? Deleting.", e)
            file.delete()
            emptyList()
        }
    }

    /**
     * Save messages for a PDF, overwriting any previous history.
     */
    suspend fun saveHistory(pdfUri: String, messages: List<ChatHistoryMessage>) {
        withContext(Dispatchers.IO) {
            try {
                val file = fileForPdf(pdfUri)
                file.writeText(json.encodeToString(messages))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save chat history", e)
            }
        }
    }

    /**
     * Delete saved history for a PDF.
     */
    suspend fun deleteHistory(pdfUri: String) = withContext(Dispatchers.IO) {
        fileForPdf(pdfUri).delete()
    }

    /**
     * Returns total number of history files (for debugging).
     */
    fun historyFileCount(): Int = historyDir.listFiles()?.size ?: 0
}
