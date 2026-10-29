package com.pdfapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.pdfapk.ui.navigation.PDFApkNavGraph
import com.pdfapk.ui.theme.PDFApkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming intent (opening a PDF from file manager)
        val pdfUri = intent?.data

        setContent {
            PDFApkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    PDFApkNavGraph(
                        navController = navController,
                        initialPdfUri = pdfUri?.toString()
                    )
                }
            }
        }
    }
}
