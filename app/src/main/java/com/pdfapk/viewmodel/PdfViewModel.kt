package com.pdfapk.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfapk.pdf.PdfDocumentManager
import com.pdfapk.pdf.PdfExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PdfViewModel(application: Application) : AndroidViewModel(application) {

    private val pdfExtractor = PdfExtractor(application)
    private val pdfRenderer = PdfDocumentManager(application)

    data class PdfState(
        val isLoading: Boolean = false,
        val pageCount: Int = 0,
        val currentPage: Int = 0,
        val fullText: String = "",
        val pages: List<String> = emptyList(),
        val title: String? = null,
        val error: String? = null,
        val pageBitmap: Bitmap? = null,
        val uri: String? = null
    )

    private val _state = MutableStateFlow(PdfState())
    val state: StateFlow<PdfState> = _state.asStateFlow()

    fun loadPdf(uriString: String) {
        viewModelScope.launch {
            _state.value = PdfState(isLoading = true, uri = uriString)
            val uri = Uri.parse(uriString)

            // Extract text
            pdfExtractor.extract(uri).fold(
                onSuccess = { result ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        pageCount = result.pageCount,
                        fullText = result.fullText,
                        pages = result.pages,
                        title = result.title ?: uri.lastPathSegment ?: "PDF Document",
                        error = null
                    )
                    // Open PDF for rendering
                    pdfRenderer.open(uri)
                    renderPage(0)
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = error.message ?: "Failed to load PDF"
                    )
                }
            )
        }
    }

    fun renderPage(index: Int) {
        viewModelScope.launch {
            pdfRenderer.renderPage(index).fold(
                onSuccess = { bitmap ->
                    _state.value = _state.value.copy(
                        currentPage = index,
                        pageBitmap = bitmap
                    )
                },
                onFailure = { /* silently fail — text extraction still works */ }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        pdfRenderer.close()
    }
}
