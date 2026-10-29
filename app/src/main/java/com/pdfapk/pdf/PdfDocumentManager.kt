package com.pdfapk.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Renders PDF pages as bitmaps for in-app preview using Android's built-in PdfRenderer.
 */
class PdfDocumentManager(private val context: Context) {

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null

    private fun openFileDescriptor(uri: Uri): ParcelFileDescriptor {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw FileNotFoundException("No path in URI: $uri")
            val file = File(path)
            if (!file.exists()) throw FileNotFoundException("File not found: $uri")
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException("Cannot open: $uri")
        }
    }

    /**
     * Open a PDF and return its page count.
     */
    suspend fun open(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            close()
            fileDescriptor = openFileDescriptor(uri)
            renderer = PdfRenderer(fileDescriptor!!)
            Result.success(renderer!!.pageCount)
        } catch (e: SecurityException) {
            Result.failure(
                SecurityException(
                    "Permission denied. Try opening the PDF from the app's file picker again."
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Render a single page as a Bitmap.
     * @param pageIndex Zero-based page index.
     * @param scale Factor to scale the rendering (1.0 = original, 2.0 = 2x for Retina).
     */
    suspend fun renderPage(pageIndex: Int, scale: Float = 1.5f): Result<Bitmap> =
        withContext(Dispatchers.IO) {
            try {
                val r = renderer ?: return@withContext Result.failure(
                    IllegalStateException("No document open")
                )
                val page = r.openPage(pageIndex)

                val width = (page.width * scale).toInt()
                val height = (page.height * scale).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                Result.success(bitmap)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun close() {
        try { renderer?.close() } catch (_: Exception) {}
        try { fileDescriptor?.close() } catch (_: Exception) {}
        renderer = null
        fileDescriptor = null
    }


}
