package com.pdfapk.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfapk.llm.LlmEngine

@Composable
fun ModelDownloadCard(
    state: LlmEngine.DownloadState,
    onStartDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(durationMillis = 300),
        label = "downloadProgress"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (state.isComplete) "✅" else if (state.isDownloading) "📥" else if (state.error != null) "❌" else "🤖",
                    style = MaterialTheme.typography.headlineMedium
                )
                Column {
                    Text(
                        text = "AI Model",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Qwen 2.5 1.5B (Q4_K_M) — ~1 GB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress or action
            when {
                state.isComplete -> {
                    Text(
                        text = "Model downloaded and ready!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                state.isDownloading -> {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${state.bytesDownloaded / (1024 * 1024)} MB / ${state.totalBytes / (1024 * 1024)} MB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.error != null -> {
                    Text(
                        text = "Download failed: ${state.error}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = onStartDownload) {
                        Text("Retry Download")
                    }
                }

                else -> {
                    Text(
                        text = "Download the AI model to enable summarization, explanation, and translation features. Requires internet for first download only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Download Model (1 GB)")
                    }
                }
            }
        }
    }
}
