package com.pdfapk.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfapk.ui.components.ModelDownloadCard
import com.pdfapk.viewmodel.MainViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPdfSelected: (String) -> Unit,
    onSettings: () -> Unit,
    onDownloadModel: () -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    val isModelDownloaded by mainViewModel.isModelDownloaded.collectAsStateWithLifecycle()
    val isFirstLaunch by mainViewModel.isFirstLaunch.collectAsStateWithLifecycle()
    val downloadState by mainViewModel.downloadState.collectAsStateWithLifecycle()
    val connectionMode by mainViewModel.connectionMode.collectAsStateWithLifecycle()
    val recentPdfs by mainViewModel.recentPdfs.collectAsStateWithLifecycle()

    // Handle first-launch auto-download suggestion
    var showFirstLaunch by remember { mutableStateOf(isFirstLaunch) }

    // PDF picker launcher — copy to temp file to avoid URI permission issues
    val context = LocalContext.current
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Copy the PDF to a temp file in the app's cache directory.
            // Content URI permissions don't survive across Activity -> ViewModel
            // boundaries, so working with a local file is the most reliable approach.
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                    ?: return@let
                val tempFile = File.createTempFile("pdf_", ".pdf", context.cacheDir)
                inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val fileUri = tempFile.toURI().toString()
                // Extract original file name from the picker URI for better display
                val displayName = it.lastPathSegment?.substringAfterLast('/') ?: tempFile.name
                // Save to recent PDFs history
                mainViewModel.addRecentPdf(fileUri, displayName)
                onPdfSelected(fileUri)
            } catch (_: Exception) {
                // Fallback: pass the original URI (may fail on some devices)
                onPdfSelected(it.toString())
            }
        }
    }

    // Mark first launch as complete
    LaunchedEffect(isFirstLaunch) {
        if (isFirstLaunch) {
            mainViewModel.completeFirstLaunch()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PocketRAG",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Open PDF") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Hero section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "📄",
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "On-Device PDF RAG",
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Summarize • Explain • Translate",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Model download card (only in local mode and not already downloaded)
            if (connectionMode == "local" && (!isModelDownloaded || downloadState.isDownloading)) {
                item {
                    ModelDownloadCard(
                        state = downloadState,
                        onStartDownload = { mainViewModel.downloadModel() }
                    )
                }
            }

            // Recent PDFs section
            if (recentPdfs.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent PDFs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }

                items(recentPdfs) { entry ->
                    Card(
                        onClick = { onPdfSelected(entry.uri) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.title.removeSuffix(".pdf"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "${
                                    java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                                }, ${
                                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp))
                                }",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Feature cards
            item {
                Text(
                    text = "Features",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            item {
                FeatureCard(
                    icon = "📝",
                    title = "Smart Summarization",
                    description = "Get concise summaries of any PDF — from quick 3-bullet overviews to detailed section-by-section breakdowns."
                )
            }

            item {
                FeatureCard(
                    icon = "💡",
                    title = "Explain with Examples",
                    description = "Highlight any passage and get clear explanations with real-world examples and analogies."
                )
            }

            item {
                FeatureCard(
                    icon = "🌍",
                    title = "Professional Translation",
                    description = "Translate any part of your PDF into 50+ languages with formal, professional-grade output."
                )
            }

            item {
                FeatureCard(
                    icon = "💬",
                    title = "Chat with Your PDF",
                    description = "Ask questions, request clarifications, or dive deeper into any topic — all in natural conversation."
                )
            }

            // Bottom spacer for FAB
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun FeatureCard(
    icon: String,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineLarge
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
