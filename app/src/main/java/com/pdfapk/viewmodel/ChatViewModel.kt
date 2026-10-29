package com.pdfapk.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfapk.data.ChatHistoryManager
import com.pdfapk.llm.GroqClient
import com.pdfapk.llm.LlmEngine
import com.pdfapk.llm.PromptTemplates
import com.pdfapk.rag.IndexStore
import com.pdfapk.rag.PromptAssembly
import com.pdfapk.rag.RagEngine
import com.pdfapk.rag.SourceRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val llmEngine = LlmEngine(application)
    private val historyManager = ChatHistoryManager(application)
    private val ragEngine = RagEngine(application)

    private var sharedViewModel: MainViewModel? = null
    private var groqClient: GroqClient? = null
    private var lastGroqApiKey: String = ""
    private var currentPdfUri: String? = null
    private var indexedDocKey: String = ""

    /** Index state for the document currently open, surfaced in the chat UI. */
    val ragState: StateFlow<RagEngine.State> = ragEngine.state

    fun setSharedViewModel(vm: MainViewModel) {
        sharedViewModel = vm
    }

    data class ChatMessage(
        val role: Role,
        val content: String,
        val isLoading: Boolean = false,
        val sources: List<SourceRef> = emptyList()
    ) {
        enum class Role { USER, ASSISTANT, SYSTEM }
    }

    data class ChatState(
        val messages: List<ChatMessage> = emptyList(),
        val isGenerating: Boolean = false,
        val currentPartial: String = "",
        val error: String? = null
    )

    sealed class Action {
        data class Summarize(val pages: List<String>, val length: String = "medium") : Action()
        data class Explain(val passage: String) : Action()
        data class Translate(
            val text: String,
            val targetLanguage: String,
            val formality: String = "professional"
        ) : Action()
        data class Ask(val question: String) : Action()
        data class ExplainSelected(val selectedText: String, val question: String = "") : Action()
    }

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    // ------------------------------------------------------------------
    // INDEX
    // ------------------------------------------------------------------

    /**
     * Builds (or loads) the retrieval index for the open document.
     *
     * Called once per PDF from the chat screen. Safe to call when pages are
     * still empty - the caller retries once extraction completes.
     */
    fun prepareIndex(pdfUri: String, pages: List<String>) {
        val textPages = pages.filter { it.isNotBlank() }
        if (textPages.isEmpty()) return
        val key = IndexStore.sha256(pdfUri)
        if (key == indexedDocKey && ragEngine.isReady) return
        indexedDocKey = key
        viewModelScope.launch {
            ragEngine.prepare(key, pages)
        }
    }

    // ------------------------------------------------------------------
    // HISTORY
    // ------------------------------------------------------------------

    fun loadHistory(pdfUri: String) {
        currentPdfUri = pdfUri
        viewModelScope.launch {
            val saved = historyManager.loadHistory(pdfUri)
            if (saved.isNotEmpty()) {
                val messages = saved.map { h ->
                    val role = try {
                        ChatMessage.Role.valueOf(h.role)
                    } catch (_: Exception) {
                        ChatMessage.Role.ASSISTANT
                    }
                    ChatMessage(
                        role = role,
                        content = h.content,
                        sources = h.sources.map { SourceRef(it.pageIndex, it.score, it.preview) }
                    )
                }
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    private suspend fun persistHistory() {
        val uri = currentPdfUri ?: return
        val messages = _state.value.messages
            .filter { !it.isLoading }
            .map { message ->
                ChatHistoryManager.ChatHistoryMessage(
                    role = message.role.name,
                    content = message.content,
                    sources = message.sources.map {
                        ChatHistoryManager.ChatHistorySource(
                            pageIndex = it.pageIndex,
                            score = it.score,
                            preview = it.preview
                        )
                    }
                )
            }
        if (messages.isNotEmpty()) {
            historyManager.saveHistory(uri, messages)
        }
    }

    // ------------------------------------------------------------------
    // ACTIONS
    // ------------------------------------------------------------------

    fun submitAction(action: Action) {
        val userLabel = when (action) {
            is Action.Summarize -> "📝 Summarize this document"
            is Action.Explain -> "💡 Explain with example"
            is Action.Translate -> "🌍 Translate to ${action.targetLanguage}"
            is Action.Ask -> action.question
            is Action.ExplainSelected -> "💡 Explain selected text"
        }

        val loadingMessage = ChatMessage(
            role = ChatMessage.Role.ASSISTANT,
            content = "",
            isLoading = true
        )

        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                role = ChatMessage.Role.USER,
                content = userLabel
            ) + loadingMessage,
            isGenerating = true,
            error = null,
            currentPartial = ""
        )

        viewModelScope.launch {
            // Retrieval happens before generation; it is local and cheap.
            val turns = historyTurns()

            if (action is Action.Ask) {
                val retrieval = runCatching { ragEngine.retrieve(action.question) }.getOrNull()
                if (retrieval == null) {
                    finishWithError(
                        "The document index is not ready yet. Wait for indexing to finish, then ask again."
                    )
                    return@launch
                }
                if (retrieval.isEmpty) {
                    // Nothing cleared the relevance floor: answer honestly and
                    // cheaply instead of asking the model to fill the gap.
                    finishWithMessage(
                        "I could not find that in this document.\n\n" +
                            "I searched all ${retrieval.totalChunks} passages and none were close " +
                            "enough to the question. Try naming a section, a term used in the text, " +
                            "or asking about a specific page.",
                        emptyList()
                    )
                    return@launch
                }

                val assembled = PromptAssembly.question(
                    question = action.question,
                    hits = retrieval.hits,
                    history = turns,
                    maxPromptTokens = RAG_PROMPT_BUDGET
                )
                generate(assembled.system, assembled.user, retrieval.sources)
                return@launch
            }

            val (systemPrompt, userPrompt) = when (action) {
                is Action.Summarize -> {
                    val prompt = PromptAssembly.summary(action.pages, action.length)
                    prompt.system to prompt.user
                }
                is Action.Explain -> PromptTemplates.explainWithExample(action.passage)
                is Action.Translate -> PromptTemplates.translate(
                    action.text, action.targetLanguage, action.formality
                )
                is Action.ExplainSelected -> PromptTemplates.explainSelectedText(
                    action.selectedText, action.question
                )
                is Action.Ask -> error("handled above")
            }
            generate(systemPrompt, userPrompt, emptyList())
        }
    }

    /** Runs the selected backend and appends the answer with its citations. */
    private suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        sources: List<SourceRef>
    ) {
        val vm = sharedViewModel
        val isOnline = vm?.connectionMode?.value == "online"
        val apiKey = vm?.groqApiKey?.value ?: ""
        val model = vm?.groqModel?.value ?: "llama-3.3-70b-versatile"

        val result: Result<String>

        if (isOnline && apiKey.isNotBlank()) {
            if (groqClient == null || apiKey != lastGroqApiKey) {
                groqClient = GroqClient(apiKey)
                lastGroqApiKey = apiKey
            }
            result = groqClient!!.generate(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                config = GroqClient.ChatConfig(
                    model = model,
                    stream = true,
                    maxTokens = 2048
                ),
                onPartial = { partial ->
                    _state.value = _state.value.copy(currentPartial = partial)
                }
            )
        } else if (isOnline) {
            result = Result.failure(
                Exception("Please set your Groq API key in Settings to use online mode.")
            )
        } else {
            if (!llmEngine.isLoaded()) {
                val loadResult = llmEngine.loadModel()
                if (loadResult.isFailure) {
                    finishWithError(
                        "Local model not loaded. Download it in Settings or switch to Online mode."
                    )
                    return
                }
            }

            result = llmEngine.generate(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                onPartial = { partial ->
                    _state.value = _state.value.copy(currentPartial = partial)
                },
                onComplete = {}
            )
        }

        result.fold(
            onSuccess = { response ->
                // A grounded answer must carry citations; if the model ignored
                // the instruction, append the sources it was given so the user
                // can still verify the claim.
                val text = if (sources.isNotEmpty() && !CITATION.containsMatchIn(response)) {
                    response.trimEnd() + "\n\nSources: " +
                        sources.joinToString(", ") { "[p.${it.pageIndex + 1}]" }
                } else response
                finishWithMessage(text, sources)
            },
            onFailure = { error ->
                finishWithError(error.message ?: "Generation failed")
            }
        )
    }

    private suspend fun finishWithMessage(text: String, sources: List<SourceRef>) {
        val messages = _state.value.messages.toMutableList()
        if (messages.lastOrNull()?.isLoading == true) {
            messages.removeAt(messages.lastIndex)
        }
        messages.add(
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = text,
                sources = sources
            )
        )
        _state.value = _state.value.copy(
            messages = messages,
            isGenerating = false,
            currentPartial = "",
            error = null
        )
        persistHistory()
    }

    private suspend fun finishWithError(message: String) {
        val messages = _state.value.messages.toMutableList()
        if (messages.lastOrNull()?.isLoading == true) {
            messages.removeAt(messages.lastIndex)
        }
        _state.value = _state.value.copy(
            messages = messages,
            isGenerating = false,
            currentPartial = "",
            error = message
        )
        persistHistory()
    }

    /** Last few turns, oldest first, for follow-up questions. */
    private fun historyTurns(): List<PromptAssembly.Turn> =
        _state.value.messages
            .filter { !it.isLoading && it.role != ChatMessage.Role.SYSTEM }
            .takeLast(4)
            .map {
                PromptAssembly.Turn(
                    role = it.role.name,
                    content = it.content
                )
            }

    fun clearMessages() {
        _state.value = ChatState()
        llmEngine.resetContext()
        viewModelScope.launch {
            val uri = currentPdfUri
            if (uri != null) {
                historyManager.deleteHistory(uri)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ragEngine.reset()
    }

    private companion object {
        /**
         * Token budget for retrieved excerpts. The local model has an 8k
         * context and we reserve ~2k for the answer, so 1.6k of excerpts is a
         * deliberate choice: four focused chunks beat twelve diluted ones.
         */
        const val RAG_PROMPT_BUDGET = 1600

        val CITATION = Regex("\\[p\\.\\s?\\d+]")
    }
}
