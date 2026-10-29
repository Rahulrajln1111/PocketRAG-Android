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
import kotlinx.coroutines.launch
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfapk.ui.components.PDFApkTopBar
import com.pdfapk.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onDownloadModel: () -> Unit,
    mainViewModel: MainViewModel = viewModel()
) {
    val isModelDownloaded by mainViewModel.isModelDownloaded.collectAsStateWithLifecycle()
    val isModelLoaded by mainViewModel.isModelLoaded.collectAsStateWithLifecycle()
    val downloadState by mainViewModel.downloadState.collectAsStateWithLifecycle()
    val defaultLength by mainViewModel.settings.defaultSummaryLength.collectAsStateWithLifecycle("medium")
    val defaultLang by mainViewModel.settings.defaultTargetLanguage.collectAsStateWithLifecycle("English")
    val formality by mainViewModel.settings.translationFormality.collectAsStateWithLifecycle("professional")
    val connectionMode by mainViewModel.connectionMode.collectAsStateWithLifecycle()
    val groqApiKey by mainViewModel.groqApiKey.collectAsStateWithLifecycle()
    val groqModel by mainViewModel.groqModel.collectAsStateWithLifecycle()

    var apiKeyInput by remember(groqApiKey) { mutableStateOf(groqApiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    // Buffer target language locally — save with a short debounce to avoid jank on every keystroke
    var langInput by remember(defaultLang) { mutableStateOf(defaultLang) }
    val scope = rememberCoroutineScope()
    // Debounced save: after 500ms of no typing, persist to DataStore
    LaunchedEffect(langInput) {
        if (langInput != defaultLang) {
            kotlinx.coroutines.delay(500)
            mainViewModel.settings.setDefaultTargetLanguage(langInput)
        }
    }

    Scaffold(
        topBar = { PDFApkTopBar(title = "Settings", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ── Connection Mode ─────────────────────────────────────────────
            SectionHeader("Connection Mode")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Choose how the AI runs:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = connectionMode == "local",
                            onClick = { mainViewModel.setConnectionMode("local") },
                            label = { Text("📱 Local (offline)") },
                            leadingIcon = if (connectionMode == "local") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                        FilterChip(
                            selected = connectionMode == "online",
                            onClick = { mainViewModel.setConnectionMode("online") },
                            label = { Text("☁️ Online (Groq)") },
                            leadingIcon = if (connectionMode == "online") {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }

            // ── Online (Groq) Settings ─────────────────────────────────────
            if (connectionMode == "online") {
                SectionHeader("Groq Cloud (Online)")
                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Get a free API key at console.groq.com",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // API key input
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Groq API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (showApiKey) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        if (showApiKey) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide" else "Show"
                                    )
                                }
                            }
                        )

                        Button(
                            onClick = { mainViewModel.setGroqApiKey(apiKeyInput) },
                            enabled = apiKeyInput.isNotBlank() && apiKeyInput != groqApiKey,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Save Key")
                        }

                        // Model selector
                        Text("Model", style = MaterialTheme.typography.titleSmall)
                        val models = listOf(
                            "llama-3.3-70b-versatile" to "Llama 3.3 70B (best quality, recommended)",
                            "llama-3.1-8b-instant" to "Llama 3.1 8B (fast, lightweight)",
                            "meta-llama/llama-4-scout-17b-16e-instruct" to "Llama 4 Scout 17B (newest)",
                            "qwen/qwen3-32b" to "Qwen 3 32B (great for multilingual)"
                        )
                        models.forEach { (value, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = groqModel == value,
                                    onClick = { mainViewModel.setGroqModel(value) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // ── Local Model Section ─────────────────────────────────────────
            if (connectionMode == "local") {
                SectionHeader("Local AI Model")
                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Qwen 2.5 1.5B (Q4_K_M)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = when {
                                        isModelLoaded -> "✅ Loaded & ready"
                                        isModelDownloaded -> "📥 Downloaded, tap to load"
                                        else -> "❌ Not downloaded"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!isModelDownloaded) {
                                Button(onClick = onDownloadModel) {
                                    Text("Download (~1 GB)")
                                }
                            } else if (!isModelLoaded) {
                                Button(onClick = {
                                    scope.launch { mainViewModel.llmEngine.loadModel() }
                                }) {
                                    Text("Load")
                                }
                            }
                        }

                        if (isModelLoaded) {
                            Text(
                                text = mainViewModel.llmEngine.getModelInfo(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (downloadState.isDownloading) {
                            LinearProgressIndicator(
                                progress = { downloadState.progress },
                                modifier = Modifier.fillMaxWidth().height(6.dp)
                            )
                            Text(
                                text = "${(downloadState.progress * 100).toInt()}% — ${downloadState.bytesDownloaded / (1024 * 1024)} MB / ${downloadState.totalBytes / (1024 * 1024)} MB",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // ── Summarization Defaults ─────────────────────────────────────
            SectionHeader("Summarization")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Default Summary Length", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("short" to "Short", "medium" to "Medium", "detailed" to "Detailed").forEach { (value, label) ->
                            FilterChip(
                                selected = defaultLength == value,
                                onClick = { scope.launch { mainViewModel.settings.setDefaultSummaryLength(value) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // ── Translation Defaults ────────────────────────────────────────
            SectionHeader("Translation")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = langInput,
                        onValueChange = { langInput = it },
                        label = { Text("Default Target Language") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Text("Formality Level", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = formality == "professional",
                            onClick = { scope.launch { mainViewModel.settings.setTranslationFormality("professional") } },
                            label = { Text("Professional") }
                        )
                        FilterChip(
                            selected = formality == "casual",
                            onClick = { scope.launch { mainViewModel.settings.setTranslationFormality("casual") } },
                            label = { Text("Casual") }
                        )
                    }
                }
            }

            // ── About ───────────────────────────────────────────────────────
            SectionHeader("About")
            Card {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("PDF Apk v1.0.0", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Built with ❤️ using Jetpack Compose, llama.cpp, Groq Cloud, and Qwen 2.5",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
