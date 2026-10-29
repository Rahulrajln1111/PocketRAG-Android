package com.pdfapk.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfapk.ui.components.PdfPagePreview
import com.pdfapk.viewmodel.PdfViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    pdfUri: String,
    onBack: () -> Unit,
    onChat: () -> Unit,
    startPage: Int = 0,
    pdfViewModel: PdfViewModel = viewModel()
) {
    val state by pdfViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(pdfUri) {
        pdfViewModel.loadPdf(pdfUri)
    }

    // Deep link from a citation chip: open the cited page once the PDF is ready.
    var appliedStartPage by remember(pdfUri, startPage) { mutableStateOf(false) }
    LaunchedEffect(state.pageCount, startPage) {
        if (!appliedStartPage && state.pageCount > 0 && startPage > 0) {
            pdfViewModel.renderPage(startPage.coerceIn(0, state.pageCount - 1))
            appliedStartPage = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title ?: "PDF Viewer",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = "${state.currentPage + 1}/${state.pageCount}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { pdfViewModel.renderPage(state.currentPage + 1) },
                        enabled = state.currentPage < state.pageCount - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(
                        onClick = { onChat() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📝 Summarize", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = { onChat() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("💡 Explain", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick = { onChat() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🌍 Translate", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Loading PDF…")
                        }
                    }
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Failed to load PDF",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = state.error ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { pdfViewModel.loadPdf(pdfUri) }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                else -> {
                    PdfPagePreview(
                        bitmap = state.pageBitmap,
                        pageNumber = state.currentPage,
                        totalPages = state.pageCount,
                        onPageChange = { pdfViewModel.renderPage(it) }
                    )
                }
            }
        }
    }
}
