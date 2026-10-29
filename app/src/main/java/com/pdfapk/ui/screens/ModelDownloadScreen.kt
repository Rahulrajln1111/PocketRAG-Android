package com.pdfapk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfapk.ui.components.ModelDownloadCard
import com.pdfapk.ui.components.PDFApkTopBar
import com.pdfapk.viewmodel.MainViewModel

@Composable
fun ModelDownloadScreen(
    onBack: () -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    val downloadState by mainViewModel.downloadState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { PDFApkTopBar(title = "Model Download", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "📥",
                style = MaterialTheme.typography.displayLarge
            )

            Text(
                text = "Download AI Model",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "The model runs entirely on your device.\nNo internet required after download.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            ModelDownloadCard(
                state = downloadState,
                onStartDownload = { mainViewModel.downloadModel() }
            )

            // Requirements info
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Requirements",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    RequirementRow("📦", "~1 GB free storage space")
                    RequirementRow("🧠", "2+ GB free RAM")
                    RequirementRow("🌐", "Internet connection (first time only)")
                    RequirementRow("🤖", "Android 10 or later")
                }
            }

            // Model info
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "About the Model",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "This app uses Qwen 2.5 1.5B, a state-of-the-art open-source language model from Alibaba Cloud. It's quantized to Q4_K_M format for efficient mobile deployment, providing excellent multilingual support and strong reasoning capabilities for document analysis.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (downloadState.isComplete) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Done — Start Using the App")
                }
            }
        }
    }
}

@Composable
private fun RequirementRow(icon: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = icon, style = MaterialTheme.typography.bodyLarge)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}
