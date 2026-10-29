package com.pdfapk.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfapk.ui.components.ChatMessageBubble
import com.pdfapk.ui.components.PDFApkTopBar
import com.pdfapk.ui.components.StreamingMessageBubble
import com.pdfapk.rag.RagEngine
import com.pdfapk.viewmodel.ChatViewModel
import com.pdfapk.viewmodel.MainViewModel
import com.pdfapk.viewmodel.PdfViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    pdfUri: String,
    onBack: () -> Unit,
    onOpenPage: (Int) -> Unit = {},
    chatViewModel: ChatViewModel = viewModel(),
    pdfViewModel: PdfViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel()
) {
    val chatState by chatViewModel.state.collectAsStateWithLifecycle()
    val pdfState by pdfViewModel.state.collectAsStateWithLifecycle()
    val ragState by chatViewModel.ragState.collectAsStateWithLifecycle()

    // Share MainViewModel with ChatViewModel for connection mode + Groq access
    LaunchedEffect(Unit) {
        chatViewModel.setSharedViewModel(mainViewModel)
    }

    // Load PDF text if not already loaded, and load chat history for this PDF
    LaunchedEffect(pdfUri) {
        if (pdfState.fullText.isEmpty()) {
            pdfViewModel.loadPdf(pdfUri)
        }
        // Load saved chat history for this PDF
        chatViewModel.loadHistory(pdfUri)
    }

    // Build the retrieval index once the extracted pages are available.
    LaunchedEffect(pdfUri, pdfState.pages.size) {
        if (pdfState.pages.isNotEmpty()) {
            chatViewModel.prepareIndex(pdfUri, pdfState.pages)
        }
    }

    // Read the saved default target language from settings
    val savedTargetLanguage by mainViewModel.settings.defaultTargetLanguage.collectAsStateWithLifecycle("English")

    // Input state
    var inputText by remember { mutableStateOf("") }
    var showModeSelector by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf("chat") }  // chat, summarize, explain, translate
    var targetLanguage by remember { mutableStateOf(savedTargetLanguage) }

    // When switching to translate mode, pre-fill with the saved default
    LaunchedEffect(selectedMode) {
        if (selectedMode == "translate") {
            targetLanguage = savedTargetLanguage
        }
    }

    // Auto-scroll to bottom
    val listState = rememberLazyListState()
    LaunchedEffect(chatState.messages.size, chatState.currentPartial) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.size - 1)
        }
    }

    val listStateForScrolling = listState

    Scaffold(
        topBar = {
            PDFApkTopBar(
                title = pdfState.title ?: "AI Chat",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { chatViewModel.clearMessages() }) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear chat")
                    }
                    // Mode quick-select
                    IconButton(onClick = { showModeSelector = !showModeSelector }) {
                        Icon(Icons.Default.Tune, contentDescription = "Mode")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Mode selector dropdown
            AnimatedVisibility(visible = showModeSelector) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Action Mode",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedMode == "summarize",
                                onClick = { selectedMode = "summarize"; showModeSelector = false },
                                label = { Text("📝 Summarize") }
                            )
                            FilterChip(
                                selected = selectedMode == "explain",
                                onClick = { selectedMode = "explain"; showModeSelector = false },
                                label = { Text("💡 Explain") }
                            )
                            FilterChip(
                                selected = selectedMode == "translate",
                                onClick = { selectedMode = "translate"; showModeSelector = false },
                                label = { Text("🌍 Translate") }
                            )
                            FilterChip(
                                selected = selectedMode == "chat",
                                onClick = { selectedMode = "chat"; showModeSelector = false },
                                label = { Text("💬 Chat") }
                            )
                        }

                        // Language selector for translation mode
                        if (selectedMode == "translate") {
                            OutlinedTextField(
                                value = targetLanguage,
                                onValueChange = { targetLanguage = it },
                                label = { Text("Target Language") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // Retrieval status: indexing progress, index stats, or a failure.
            when (val rag = ragState) {
                is RagEngine.State.Preparing -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { rag.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rag.stage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is RagEngine.State.Ready -> {
                    Text(
                        text = "🔎 " + rag.stats.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                is RagEngine.State.Failed -> {
                    Text(
                        text = "Search index unavailable: ${rag.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                else -> Unit
            }

            // Messages list
            LazyColumn(
                state = listStateForScrolling,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                // Welcome message
                if (chatState.messages.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "💬",
                                style = MaterialTheme.typography.displayMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Ask me anything about this PDF!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Try: \"What are the payment terms?\" or \"Summarize this document\" or \"Translate to Spanish\". Answers cite the page they came from.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(chatState.messages, key = { it.hashCode() }) { message ->
                    ChatMessageBubble(
                        message = message,
                        onSourceClick = onOpenPage
                    )
                }

                // Streaming partial output
                if (chatState.isGenerating && chatState.currentPartial.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingMessageBubble(text = chatState.currentPartial)
                    }
                }
            }

            // Error display
            if (chatState.error != null) {
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(chatState.error ?: "")
                }
            }

            // Input bar — imePadding pushes it above the keyboard
            Surface(
                modifier = Modifier.imePadding(),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                when (selectedMode) {
                                    "summarize" -> "Summarize this PDF…"
                                    "explain" -> "Explain what part?…"
                                    "translate" -> "Translate to $targetLanguage…"
                                    else -> "Ask anything…"
                                }
                            )
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = MaterialTheme.shapes.extraLarge
                    )

                    // Send button
                    FilledIconButton(
                        onClick = {
                            val text = inputText.trim()
                            if (text.isNotEmpty() || selectedMode != "chat") {
                                val firstPages = pdfState.pages
                                    .filter { it.isNotBlank() }
                                    .take(3)
                                    .joinToString("\n\n")

                                when (selectedMode) {
                                    "summarize" -> {
                                        chatViewModel.submitAction(
                                            ChatViewModel.Action.Summarize(pdfState.pages)
                                        )
                                    }
                                    "explain" -> {
                                        chatViewModel.submitAction(
                                            ChatViewModel.Action.Explain(
                                                if (text.isNotEmpty()) text else firstPages
                                            )
                                        )
                                    }
                                    "translate" -> {
                                        chatViewModel.submitAction(
                                            ChatViewModel.Action.Translate(
                                                text = if (text.isNotEmpty()) text else firstPages,
                                                targetLanguage = targetLanguage
                                            )
                                        )
                                    }
                                    else -> {
                                        chatViewModel.submitAction(
                                            ChatViewModel.Action.Ask(question = text)
                                        )
                                    }
                                }
                                inputText = ""
                            }
                        },
                        enabled = !chatState.isGenerating &&
                                (inputText.isNotBlank() || selectedMode != "chat"),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
