package com.pdfapk

import android.app.Application
import com.pdfapk.pdf.PdfExtractor

class PDFApkApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialise PDFBox for Android (required before first PDF load)
        PdfExtractor.init(this)
    }

    companion object {
        lateinit var instance: PDFApkApplication
            private set
    }
}
