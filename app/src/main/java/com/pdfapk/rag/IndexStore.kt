package com.pdfapk.rag

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
 * Persists one index per document under `filesDir/rag/<docKey>/`.
 *
 * The version string is a hash of the chunker version and the embedder id.
 * Change either - chunk size, model, dtype - and the cache is rebuilt instead
 * of silently mixing vectors from two different spaces, which would degrade
 * retrieval without any visible error.
 */
class IndexStore(private val context: Context) {

    @Serializable
    private data class StoredChunk(
        val id: Int,
        val pageIndex: Int,
        val text: String,
        val heading: String? = null,
        val charStart: Int = 0,
        val charEnd: Int = 0
    )

    @Serializable
    private data class StoredMeta(
        val version: String,
        val embedderId: String,
        val chunkerVersion: String,
        val pageCount: Int,
        val chunkCount: Int,
        val sourceChars: Int,
        val builtAt: Long
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Cache key for a document: its URI plus current chunker/embedder version. */
    fun versionFor(embedderId: String): String =
        sha256("$CHUNKER_VERSION|$embedderId").take(16)

    private fun dirFor(docKey: String): File =
        File(File(context.filesDir, "rag").apply { mkdirs() }, docKey)

    suspend fun load(
        docKey: String,
        embedderId: String,
        pageCount: Int,
        sourceChars: Int
    ): IndexedDocument? = withContext(Dispatchers.IO) {
        val dir = dirFor(docKey)
        val metaFile = File(dir, "meta.json")
        val chunksFile = File(dir, "chunks.json")
        val vectorsFile = File(dir, "vectors.ivf")
        if (!metaFile.exists() || !chunksFile.exists() || !vectorsFile.exists()) return@withContext null

        try {
            val meta = json.decodeFromString<StoredMeta>(metaFile.readText())
            val expected = versionFor(embedderId)
            if (meta.version != expected ||
                meta.pageCount != pageCount ||
                meta.sourceChars != sourceChars
            ) {
                Log.i(TAG, "Index cache stale for $docKey, rebuilding")
                dir.deleteRecursively()
                return@withContext null
            }

            val stored = json.decodeFromString<List<StoredChunk>>(chunksFile.readText())
            val index = vectorsFile.inputStream().use { IvfIndex.load(it) }
            if (stored.size != index.size) {
                dir.deleteRecursively()
                return@withContext null
            }

            val chunks = stored.map {
                Chunk(
                    id = it.id,
                    pageIndex = it.pageIndex,
                    text = it.text,
                    heading = it.heading,
                    charStart = it.charStart,
                    charEnd = it.charEnd
                )
            }
            IndexedDocument(
                docKey = docKey,
                chunks = chunks,
                index = index,
                version = meta.version,
                pageCount = meta.pageCount,
                builtAtMillis = meta.builtAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached index for $docKey, rebuilding", e)
            dir.deleteRecursively()
            null
        }
    }

    suspend fun save(
        docKey: String,
        chunks: List<Chunk>,
        index: IvfIndex,
        embedderId: String,
        pageCount: Int,
        sourceChars: Int
    ): IndexedDocument = withContext(Dispatchers.IO) {
        val dir = dirFor(docKey)
        dir.mkdirs()
        val version = versionFor(embedderId)
        val builtAt = System.currentTimeMillis()

        val stored = chunks.map {
            StoredChunk(
                id = it.id,
                pageIndex = it.pageIndex,
                text = it.text,
                heading = it.heading,
                charStart = it.charStart,
                charEnd = it.charEnd
            )
        }
        File(dir, "chunks.json").writeText(json.encodeToString(stored))
        File(dir, "vectors.ivf").outputStream().use { index.save(it) }
        File(dir, "meta.json").writeText(
            json.encodeToString(
                StoredMeta(
                    version = version,
                    embedderId = embedderId,
                    chunkerVersion = CHUNKER_VERSION,
                    pageCount = pageCount,
                    chunkCount = chunks.size,
                    sourceChars = sourceChars,
                    builtAt = builtAt
                )
            )
        )

        IndexedDocument(
            docKey = docKey,
            chunks = chunks,
            index = index,
            version = version,
            pageCount = pageCount,
            builtAtMillis = builtAt
        )
    }

    suspend fun delete(docKey: String) = withContext(Dispatchers.IO) {
        dirFor(docKey).deleteRecursively()
    }

    /** Disk usage of the RAG cache, for the settings screen. */
    fun cacheBytes(): Long {
        val root = File(context.filesDir, "rag")
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    companion object {
        private const val TAG = "IndexStore"

        /** Bump when the chunker changes; invalidates every cached index. */
        const val CHUNKER_VERSION = "chunker-1"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
