package com.pdfapk.pdf

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Extracts plain text from PDF documents using PDFBox-Android.
 *
 * Provides page-count and per-page text retrieval for the
 * PDF viewer experience.
 *
 * PDFBoxResourceLoader.init() must be called once before first use
 * (e.g. from Application.onCreate).
 */
class PdfExtractor(private val appContext: Context) {

    companion object {
        private const val TAG = "PdfExtractor"

        /**
         * One-time initialisation of PDFBox for Android.
         * Call from Application.onCreate().
         */
        fun init(context: Context) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    data class PdfResult(
        val pageCount: Int,
        val fullText: String,
        val pages: List<String>,
        val title: String? = null
    )

    private fun openInputStream(uri: Uri): InputStream {
        return if (uri.scheme == "file") {
            val path = uri.path ?: throw FileNotFoundException("No path in URI: $uri")
            FileInputStream(File(path))
        } else {
            appContext.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("Cannot open file: $uri")
        }
    }

    /**
     * Extract all text from a PDF given its URI.
     */
    suspend fun extract(uri: Uri): Result<PdfResult> = withContext(Dispatchers.IO) {
        try {
            openInputStream(uri).use { inputStream ->
                val pdDocument = PDDocument.load(inputStream)
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.addMoreFormatting = true

                val pageCount = pdDocument.numberOfPages
                val pages = mutableListOf<String>()

                for (i in 1..pageCount) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val pageText = stripper.getText(pdDocument).trim()
                    pages.add(pageText)
                }

                stripper.startPage = 1
                stripper.endPage = pageCount
                val fullText = stripper.getText(pdDocument).trim()

                // Try to extract a title from PDF metadata
                val title = try {
                    pdDocument.documentInformation.title?.takeIf { it.isNotBlank() }
                } catch (_: Exception) { null }

                pdDocument.close()

                Log.i(TAG, "Extracted $pageCount pages, ${fullText.length} chars")
                Result.success(PdfResult(pageCount, fullText, pages, title))
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF extraction failed", e)
            Result.failure(e)
        }
    }

    /**
     * Extract text from a specific page range.
     */
    suspend fun extractPages(uri: Uri, startPage: Int, endPage: Int): Result<List<String>> =
        withContext(Dispatchers.IO) {
            try {
                openInputStream(uri).use { inputStream ->
                    val pdDocument = PDDocument.load(inputStream)
                    val stripper = PDFTextStripper()
                    stripper.sortByPosition = true

                    val pages = mutableListOf<String>()
                    val maxPage = pdDocument.numberOfPages
                    for (i in startPage..endPage.coerceAtMost(maxPage)) {
                        stripper.startPage = i
                        stripper.endPage = i
                        pages.add(stripper.getText(pdDocument).trim())
                    }

                    pdDocument.close()
                    Result.success(pages)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Page extraction failed", e)
                Result.failure(e)
            }
        }
}
