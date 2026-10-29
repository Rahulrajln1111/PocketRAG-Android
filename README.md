# PDF Apk — AI-Powered PDF Assistant for Android

<div align="center">

**Summarize • Explain • Translate • Chat** — all on-device or via cloud AI.

</div>

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Tech Stack & Dependencies](#tech-stack--dependencies)
4. [Project Structure](#project-structure)
5. [How Each Feature Works](#how-each-feature-works)
6. [Challenges Faced & Solutions](#challenges-faced--solutions)
7. [What You Need to Learn](#what-you-need-to-learn)
8. [Step-by-Step: Build a Similar Project](#step-by-step-build-a-similar-project)
9. [Learning Resources](#learning-resources)
10. [Future Improvements](#future-improvements)

---

## Project Overview

PDF Apk is an Android app that lets you open any PDF document and interact with it using AI. You can:

- **📝 Summarize** — Get short/medium/detailed summaries
- **💡 Explain** — Get explanations with real-world examples
- **🌍 Translate** — Translate to any language with professional/casual tone
- **💬 Chat** — Ask questions about the document in natural language. **Conversations are saved per PDF** — come back anytime and pick up where you left off.
- **📍 Citations** — answers are grounded in retrieved passages and cite the page they came from (`[p.12]`); tap a citation to jump there.

The AI can run **entirely offline** (using Qwen 2.5 1.5B via llama.cpp) or **online** (using Groq Cloud with Llama 3 70B). The choice is yours.

Question answering is a real retrieval pipeline, not a truncated copy of the
document: page-aware chunking → on-device embeddings → an IVF vector index +
BM25 → rank fusion → a budgeted prompt with page labels. See
**[docs/RAG.md](docs/RAG.md)** for the chain, the index format and the
limitations.

---

## Architecture

### High-Level Design

```
┌─────────────────────────────────────────────────────────┐
│                     UI Layer (Compose)                    │
│  HomeScreen → PdfViewerScreen → ChatScreen → Settings   │
└────────────────────────┬────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────┐
│                 ViewModel Layer                           │
│  MainViewModel  │  PdfViewModel  │  ChatViewModel        │
└────────┬────────┴───────┬────────┴────────┬─────────────┘
         │                │                 │
┌────────▼────┐   ┌──────▼─────┐   ┌───────▼──────────┐
│SettingsMgr  │   │PdfExtractor│   │LlmEngine (local) │
│(DataStore)  │   │PdfRenderer │   │GroqClient (cloud)│
│             │   │            │   │PromptTemplates   │
└─────────────┘   └────────────┘   └──────────────────┘
```

### Layers

1. **UI Layer** — Jetpack Compose screens. Each screen is a composable function. Navigation is handled by Jetpack Navigation Compose with animated transitions.

2. **ViewModel Layer** — Android ViewModels that hold UI state via `StateFlow` and bridge the UI to the data/LLM layers. Each screen gets its own ViewModel.

3. **Data/Services Layer** — Pure Kotlin classes that do the actual work:
   - `SettingsManager` — persists all user preferences using Jetpack DataStore
   - `PdfExtractor` — extracts text from PDFs using PDFBox-Android
   - `PdfDocumentManager` — renders PDF pages as bitmaps using Android's PdfRenderer
   - `LlmEngine` — wraps a llama.cpp JNI bridge for on-device inference
   - `GroqClient` — lightweight HTTP client for Groq Cloud API (OpenAI-compatible)
   - `PromptTemplates` — builds structured prompts for each action mode
   - `RagEngine` — orchestrates chunking, embedding, indexing and retrieval for the open document
   - `IvfIndex` — the on-device vector index (k-means partitions, int8 codes, binary persistence)
   - `HybridSearch` — fuses dense and BM25 results, applies the relevance floor and page diversity

### Data Flow

```
User taps "Send" in Chat
        │
        ▼
ChatScreen → ChatViewModel.submitAction()
        │
        ├── Action.Translate → PromptTemplates.translate(text, "Hindi", "professional")
        │                            │
        │                            ▼
        │              Pair<systemPrompt, userPrompt>
        │                            │
        ├── Online? ──── yes ────► GroqClient.generate() ───► Groq Cloud API
        │                                                    │
        ├── Local? ──── yes ────► LlmEngine.generate() ───► llama.cpp native
        │                                                    │
        ▼                                                    ▼
  ChatViewModel receives Result<String>
        │
        ▼
  ChatState flow updated → Compose recomposes UI
```

Question answering (`Action.Ask`) takes a different path: `RagEngine.retrieve()`
embeds the question, searches the document index, fuses the results with BM25
and assembles a cited prompt before the same `generate()` call. The full chain
is in **[docs/RAG.md](docs/RAG.md)**.

---

## Tech Stack & Dependencies

### Core
| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.1.0 | Main programming language |
| Android Gradle Plugin | 8.7.3 | Build system |
| Jetpack Compose BOM | 2024.12.01 | Declarative UI toolkit |
| compileSdk / targetSdk | 35 | Latest Android APIs |
| minSdk | 29 (Android 10) | Backward compatibility |

### UI (Compose)
| Dependency | Purpose |
|------------|---------|
| `compose-bom:2024.12.01` | Bill of Materials — version all Compose libs together |
| `material3` | Material 3 Design system (cards, buttons, dialogs) |
| `material-icons-extended` | Extended icon set (Description, DeleteSweep, etc.) |
| `navigation-compose:2.8.5` | Screen-to-screen navigation with type-safe routes |
| `activity-compose:1.9.3` | Compose integration with Activity lifecycle |
| `lifecycle-runtime-compose:2.8.7` | `collectAsStateWithLifecycle()` for lifecycle-aware flows |
| `lifecycle-viewmodel-compose:2.8.7` | `viewModel()` composable function |
| `core-splashscreen:1.0.1` | Splash screen API (Android 12+ splash + fallback) |

### PDF Handling
| Dependency | Purpose |
|------------|---------|
| `pdfbox-android:2.0.27.0` | Text extraction from PDFs (Apache licensed) |
| Android's `PdfRenderer` (built-in) | Render PDF pages as bitmaps for preview |
| `documentfile:1.0.1` | Work with document URIs (for opening PDFs from storage) |

### AI / LLM
| Component | Purpose |
|-----------|---------|
| **llama.cpp** (NDK/JNI) | Run Qwen 2.5 1.5B on-device via C++ native code |
| **Groq Cloud API** | Cloud inference with Llama 3 70B / 8B (fast GPU) |
| **ONNX Runtime Android** `1.22.0` | On-device inference for the embedding model (16 KB-aligned native libs) |
| **all-MiniLM-L6-v2** (int8 ONNX, 23 MB) | 384-dim sentence embeddings for retrieval |
| **IVF index + BM25** (in-repo, Kotlin) | Vector search and keyword search, fused with RRF |
| `okhttp:4.12.0` | HTTP client for Groq API + model/embedder downloads |
| `kotlinx-serialization-json:1.7.3` | JSON parsing for API responses and settings |
| `PromptTemplates` | Hand-crafted prompts for summarization, explanation, translation |

### Persistence
| Dependency | Purpose |
|------------|---------|
| `datastore-preferences:1.1.1` | Key-value preferences (replacement for SharedPreferences) |

---

## Project Structure

```
app/src/main/java/com/pdfapk/
├── PDFApkApplication.kt        # Application class — init PDFBox + LlmEngine
├── MainActivity.kt             # Single activity, entry point
│
├── data/
│   └── SettingsManager.kt      # DataStore-backed preferences (connection mode,
│                                 API key, model choice, recent PDFs, etc.)
│
├── llm/
│   ├── GroqClient.kt           # HTTP client for Groq Cloud (streaming SSE)
│   ├── LlmEngine.kt            # llama.cpp JNI wrapper (load/generate/download)
│   └── PromptTemplates.kt      # Prompt builders for each action mode
│
├── pdf/
│   ├── PdfExtractor.kt         # Text extraction with PDFBox
│   └── PdfDocumentManager.kt   # Page rendering with PdfRenderer
│
├── rag/
│   ├── RagEngine.kt            # Orchestrates index build/load + retrieval
│   ├── Chunker.kt              # Page-aware chunking, headings, overlap
│   ├── TextUtils.kt            # Cleaning, folding, token estimation
│   ├── WordPiece.kt            # MiniLM tokenizer (assets/minilm_vocab.txt)
│   ├── Embedder.kt             # Embedder interface (ONNX today, GGUF later)
│   ├── OnnxEmbedder.kt         # MiniLM int8 inference + mean pooling
│   ├── EmbeddingModelStore.kt  # One-time model download with progress
│   ├── Bm25.kt                 # Keyword index over chunks
│   ├── IvfIndex.kt             # Vector DB: k-means IVF, int8, binary format
│   ├── HybridSearch.kt         # RRF fusion, relevance floor, page diversity
│   ├── PromptAssembly.kt       # Budgeted prompts with [p.N] labels
│   ├── IndexStore.kt           # Per-document cache + version invalidation
│   └── Model.kt                # Chunk / Hit / SourceRef / IndexStats
│
├── ui/
│   ├── components/
│   │   ├── MessageBubble.kt       # Chat message UI (user + AI + streaming)
│   │   ├── ModelDownloadCard.kt   # Download progress card
│   │   ├── PdfPagePreview.kt      # Rendered PDF page display
│   │   └── TopBar.kt              # Reusable top app bar
│   ├── navigation/
│   │   └── NavGraph.kt            # All routes and navigation logic
│   ├── screens/
│   │   ├── ChatScreen.kt          # The chat/AI interaction screen
│   │   ├── HomeScreen.kt          # Home with recent PDFs + feature cards
│   │   ├── ModelDownloadScreen.kt # Model download progress screen
│   │   ├── PdfViewerScreen.kt     # PDF page-by-page viewer
│   │   └── SettingsScreen.kt      # All settings
│   └── theme/
│       ├── Color.kt               # Brand colors + light/dark schemes
│       ├── Theme.kt               # Material 3 theme with dynamic colors
│       └── Type.kt                # Typography scale
│
└── viewmodel/
    ├── ChatViewModel.kt        # Chat state, retrieval, AI routing, citations
    ├── MainViewModel.kt        # App-wide state (model, settings, recent PDFs)
    └── PdfViewModel.kt         # PDF loading, page rendering, text extraction

app/src/test/java/com/pdfapk/rag/
├── TextAndChunkerTest.kt       # Cleaning, chunk boundaries, overlap, headings
├── WordPieceTest.kt            # Tokenizer: subwords, specials, padding, UNK
├── RetrievalTest.kt            # BM25, IVF recall vs brute force, RRF, floor
└── PromptAssemblyTest.kt       # Citations, token budget, summary coverage
```

---

## How Each Feature Works

### 1. PDF Opening & Text Extraction

```
User taps "Open PDF"
        │
        ▼
ActivityResultContracts.OpenDocument() launches system file picker
        │
        ▼
User selects a PDF (content:// URI)
        │
        ▼
App copies the file to a temp file in cacheDir/
(This avoids Android's URI permission issues!)
        │
        ▼
tempFile.toURI().toString() → "file:///data/.../cache/pdf_12345.pdf"
        │
        ▼
PdfExtractor.extract(uri):
  1. Opens InputStream (handles both file:// and content://)
  2. Loads with PDDocument.load()
  3. Uses PDFTextStripper to extract text per-page
  4. Returns PdfResult(pageCount, fullText, pages, title)
        │
        ▼
PdfDocumentManager.open(uri):
  1. Gets ParcelFileDescriptor (handles both schemes)
  2. Creates PdfRenderer instance
  3. renderPage(i) → Bitmap for visual preview
```

**Why copy to temp file?** Android's `content://` URI permissions are tied to the receiving activity. When you pass the URI to a ViewModel, the permission is lost. Copying to a local file sidesteps this entirely.

### 2. AI — Local Mode (On-Device)

```
Model file (Qwen2.5-1.5B-Instruct-Q4_K_M.gguf, ~1GB)
Downloaded from HuggingFace to filesDir/models/
        │
        ▼
LlmEngine.loadModel():
  1. Checks if model file exists
  2. Calls nativeLoadModel(modelPath) — JNI → llama.cpp C++
  3. llama.cpp loads GGUF file into memory
  4. Sets _isModelLoaded = true
        │
        ▼
LlmEngine.generate(systemPrompt, userPrompt, onPartial):
  1. Creates GenerationCallback (JNI interface)
  2. Calls nativeGenerate() → llama.cpp runs inference
  3. C++ calls back onToken(partial, isComplete) for each token
  4. Kotlin receives partial text → updates StateFlow → UI recomposes
```

**The model:** Qwen 2.5 1.5B in Q4_K_M quantization. This is a 4-bit quantized version that runs ~1.5GB → ~900MB on disk. It fits in ~1GB RAM and generates about 20-40 tokens/second on modern phones.

**llama.cpp** is compiled as a native library using Android NDK + CMake. The JNI bridge exposes: `loadModel`, `unloadModel`, `generate`, `resetContext`, `isModelLoaded`, `getModelInfo`.

### 3. AI — Online Mode (Groq Cloud)

```
User enters Groq API key in Settings
        │
        ▼
GroqClient(apiKey) created
        │
        ▼
GroqClient.generate(systemPrompt, userPrompt, config, onPartial):
  1. Builds JSON payload (OpenAI-compatible format)
  2. POST to https://api.groq.com/openai/v1/chat/completions
  3. If stream=true: reads SSE events line by line
     - Each "data: {...}" line contains a delta token
     - Accumulates full text and calls onPartial() for each chunk
  4. If stream=false: parses the full JSON response
  5. Returns Result.success(fullText) or Result.failure(error)
```

**Streaming with SSE:** Groq uses Server-Sent Events. The response is a stream of lines:
```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: [DONE]
```

This gives real-time word-by-word streaming to the UI.

### 4. Prompt Engineering

Each action mode has a dedicated system prompt and user prompt template:

| Mode | System Prompt | User Prompt |
|------|--------------|-------------|
| Summarize | Professional document analyst | Document text + length instruction |
| Explain | Expert tutor with examples | Passage text + request for examples |
| Translate | Professional translator | Text + target language + formality |
| Chat/Ask | PDF-specialist assistant | Question + document context |

Example — Summarize prompt:
```
System: You are a professional document analyst...
User:   Document content:
        ---
        [PDF text up to 12,000 chars]
        ---
        Summarize in a well-structured paragraph of 4-6 sentences...
```

Key decisions:
- System prompts are role-based and prescriptive ("Follow these rules")
- User prompts include the actual document text truncated to ~12K chars
- No meta-commentary: prompts say "Just give the summary, nothing else"

### 5. Settings Persistence

SettingsManager uses Jetpack DataStore (Preferences), a modern replacement for SharedPreferences:

```
SettingsManager ──► DataStore <file> ──► Flow<T>
                          │
                    On read: map { prefs -> prefs[KEY] ?: default }
                    On write: edit { prefs -> prefs[KEY] = value }
```

Stored settings:
- `model_downloaded`, `model_path` — model management
- `connection_mode` — "local" or "online"
- `groq_api_key`, `groq_model` — Groq config (key stored in plain DataStore currently; consider EncryptedSharedPreferences for production)
- `default_summary_length`, `default_target_lang`, `translation_formality` — default settings
- `theme_mode` — "system", "light", "dark"
- `first_launch` — onboarding flag
- `recent_pdfs` — JSON-encoded list of recent entries (up to 5)

**Model name migration:** When Groq decommissioned `llama3-70b-8192`, we added a migration in the flow that transparently maps old names to current ones:
```kotlin
val groqModel: Flow<String> = context.dataStore.data.map { prefs ->
    val saved = prefs[Keys.GROQ_MODEL] ?: "llama-3.3-70b-versatile"
    when (saved) {
        "llama3-70b-8192", "llama3-8b-8192" -> "llama-3.3-70b-versatile"
        else -> saved
    }
}
```

This is a **transparent migration** — the stored value stays old, but the flow always emits the corrected value. No migration code needed on first launch.

### 6. State Management

Each ViewModel exposes a `StateFlow<T>` that the UI collects:

```kotlin
// In ViewModel
private val _state = MutableStateFlow(InitialState)
val state: StateFlow<State> = _state.asStateFlow()

// In Composable
val state by viewModel.state.collectAsStateWithLifecycle()
```

This is the standard Android unidirectional data flow pattern:
1. **UI events** (button clicks, text input) → ViewModel methods
2. **ViewModel** processes → launches coroutine → updates StateFlow
3. **StateFlow emission** → Compose recomposes affected parts of UI

For shared state (like connection mode, API key), `MainViewModel` exposes `StateFlow` from `SettingsManager` flows using `stateIn()`:

```kotlin
val connectionMode: StateFlow<String> = settings.connectionMode
    .stateIn(viewModelScope, SharingStarted.Eagerly, "local")
```

---

## Challenges Faced & Solutions

### 1. Android URI Permission Denial

**Problem:** After picking a PDF with `ActivityResultContracts.OpenDocument()`, the returned `content://` URI worked fine in the composable. But when passed to a ViewModel (which survives configuration changes), the URI permission was lost, causing `SecurityException: Permission Denial`.

**Solution:** Copy the PDF to a temp file in the app's cache directory immediately when the URI is received:

```kotlin
val inputStream = context.contentResolver.openInputStream(it)
val tempFile = File.createTempFile("pdf_", ".pdf", context.cacheDir)
inputStream.use { input ->
    tempFile.outputStream().use { output ->
        input.copyTo(output)
    }
}
val fileUri = tempFile.toURI().toString()  // "file://..." — no permissions needed
```

This also has the side benefit of making the file accessible across process restarts and screens.

### 2. App Crash from Keyboard Padding (imePadding + Scaffold)

**Problem:** We added `Modifier.imePadding()` to the Column inside a Scaffold to push the input bar above the keyboard. But during rapid streaming recomposition (when Groq sends tokens), Compose's layout system crashed with `MeasureAndLayoutDelegate.remeasureAndRelayoutIfNeeded`.

**Root cause:** `imePadding()` on a Column inside a Scaffold's content area conflicts with Scaffold's own padding handling during rapid state updates.

**Solution:** Remove `imePadding()` from the Column and apply it only to the input **Surface** at the bottom:

```kotlin
Surface(
    modifier = Modifier.imePadding(),  // Only on the input bar, not the Column
    ...
) { ... }
```

Also set `android:windowSoftInputMode="adjustResize"` in the manifest as a fallback.

### 3. Java 21 API Crash on Android 10 (List.removeLast())

**Problem:** The app crashed on Android 10 (API 29) with `NoSuchMethodError` on `List.removeLast()`. This is a Java 21 API that doesn't exist on Android 10.

**Solution:** Replace with the universally compatible alternative:

```kotlin
// ❌ Java 21+ — crashes on Android 10
messages.removeLast()

// ✅ Works on all Android versions
messages.removeAt(messages.lastIndex)
```

**Lesson:** Always target `minSdk = 29` when testing. Use Android Studio's lint checks for API level compatibility. When using any `java.util.List` method added after Java 8, verify compatibility with your minSdk.

### 4. Groq Decommissioned Model Names

**Problem:** The default model `llama3-70b-8192` was decommissioned by Groq. Users got HTTP 400 errors even with valid API keys.

**Solution:** 
1. Queried Groq's API to discover current model names
2. Updated all default values and model lists
3. Added a **transparent migration** in the SettingsManager flow — any saved old model name is automatically mapped to the new one without user action

### 5. DataStore Jank from Every Keystroke

**Problem:** The settings target language input saved to DataStore on **every keystroke**, causing janky recomposition as the UI fought with the async save.

**Solution:** Buffer the input locally and save with a 500ms debounce:

```kotlin
var langInput by remember(defaultLang) { mutableStateOf(defaultLang) }

LaunchedEffect(langInput) {
    if (langInput != defaultLang) {
        delay(500)  // ← Natural debounce: previous coroutine is cancelled
        mainViewModel.settings.setDefaultTargetLanguage(langInput)
    }
}
```

The `delay(500)` creates a natural debounce because the `LaunchedEffect` restarts (cancelling the previous coroutine) whenever `langInput` changes.

### 6. Chat Target Language Out of Sync

**Problem:** The ChatScreen's target language had a hardcoded `"Hindi"` that never read from the saved setting. Users could change the default in Settings, but the Chat field would still show "Hindi".

**Solution:** Read the saved default from the settings flow and pre-fill it when switching to translate mode:

```kotlin
val savedTargetLanguage by mainViewModel.settings.defaultTargetLanguage
    .collectAsStateWithLifecycle("English")

var targetLanguage by remember { mutableStateOf(savedTargetLanguage) }

LaunchedEffect(selectedMode) {
    if (selectedMode == "translate") {
        targetLanguage = savedTargetLanguage
    }
}
```

### 7. Temp File Cleanup Destroying Recent PDFs History

**Problem:** The PDF picker had cleanup code that deleted all old temp files when a new PDF was opened. This made the "Recent PDFs" history feature useless — tapping a recent PDF would open a file that no longer exists.

**Solution:** Removed the cleanup code entirely. Temp files in the cache directory are small (~1-5 MB each) and Android's system can clean them up automatically if storage is low.

---

## What You Need to Learn

To build a project like this from scratch, here's a structured learning path:

### 1. Kotlin Language
- **Basics:** Variables, functions, classes, null safety, collections
- **Advanced:** Coroutines (`launch`, `async`, `withContext`), Flows (`StateFlow`, `collect`, `map`), sealed classes
- **Why:** The entire app is Kotlin. Coroutines are essential for async work (loading PDFs, AI inference, network calls).

### 2. Jetpack Compose UI
- **Basics:** `@Composable` functions, `Column`/`Row`/`Box`, `Text`, `Button`, `Image`
- **State:** `remember`, `mutableStateOf`, `collectAsStateWithLifecycle()`
- **Material 3:** `Scaffold`, `Card`, `TopAppBar`, `BottomAppBar`, `OutlinedTextField`, `FilterChip`
- **Lists:** `LazyColumn` with `items()`
- **Animation:** `AnimatedVisibility`, `animateFloatAsState`
- **Why:** Compose is the modern Android UI toolkit. Everything you see in the app is built with it.

### 3. Android Architecture Components
- **ViewModel + AndroidViewModel:** Managing UI state across configuration changes
- **StateFlow + MutableStateFlow:** Reactive state management
- **DataStore Preferences:** Persistent key-value storage (replaces SharedPreferences)
- **Navigation Compose:** Screen-to-screen navigation with type-safe arguments
- **Lifecycle-aware collection:** `collectAsStateWithLifecycle()` ties flows to screen lifecycle

### 4. PDF Handling
- **PDFBox-Android:** Extracting text from PDFs page-by-page
- **PdfRenderer (Android built-in):** Rendering PDF pages as bitmaps
- **Content URIs vs File URIs:** Understanding Android's scoped storage model
- **Why:** These are the two main approaches for PDF handling on Android. PDFBox gives text; PdfRenderer gives visuals.

### 5. LLM Inference
- **llama.cpp + JNI:** Running quantized GGUF models on-device via native C++
- **NDK + CMake:** Compiling C++ code for Android (arm64-v8a)
- **Quantization concepts:** Q4_K_M, what it means, trade-offs (size vs quality)
- **Prompt engineering:** System prompts, few-shot examples, context window limits

### 6. Cloud AI APIs
- **OpenAI-compatible API format:** Structure of chat completion requests/responses
- **Server-Sent Events (SSE):** Streaming responses for real-time token display
- **API key management:** Secure storage, key validation
- **Rate limiting:** Groq's free tier has ~30 req/min — handle 429 errors gracefully

### 7. Android-Specific Skills
- **Gradle build system:** AGP, Kotlin plugins, Compose compiler, dependency management
- **NDK + JNI:** Calling C++ code from Kotlin for llama.cpp
- **ProGuard/R8:** Minification rules for release builds
- **ABI filtering:** arm64-v8a only for faster builds
- **Android permissions:** INTERNET, READ_EXTERNAL_STORAGE, scoped storage on API 30+
- **Edge-to-edge:** `enableEdgeToEdge()`, system bars, `imePadding()`
- **DataStore:** Multi-threaded access, flow-based reads, suspend writes

### 8. Build & Release Notes
- **`local.properties`** — When cloning the repo, create this file with `sdk.dir=/path/to/Android/Sdk` (or let Android Studio generate it on first open).
- **ProGuard/R8** — The release build enables minification. You'll need ProGuard rules to keep JNI methods (llama.cpp), Kotlin serialization, and PDFBox classes. Default rules from `proguard-android-optimize.txt` won't cover these.
- **ABI filtering** — The project only builds for `arm64-v8a`. To support older 32-bit devices, add `armeabi-v7a` to the `abiFilters` list (slower build, larger APK).

### 9. Important Gotchas (Learn These Early)
- Java 21 APIs don't exist on old Android — always test on your target API level
- `content://` URI permissions don't survive ViewModel boundaries
- `imePadding()` conflicts with Scaffold's own padding — apply only to bottom elements
- DataStore writes on every keystroke cause jank — always debounce text inputs
- Groq/Llama APIs change model names — don't hardcode, use migration flows
- Compose `remember` captures values at first composition — key it correctly
- Cache files can be cleaned by the system at any time — don't rely on them permanently

---

## Step-by-Step: Build a Similar Project

If you want to build this from scratch, follow these steps:

### Phase 1: Setup (Day 1)
1. Create a new Android project in Android Studio (Empty Compose Activity)
2. Set `minSdk = 29`, `compileSdk = 35`, use Kotlin 2.1+
3. Add Compose BOM and all UI dependencies
4. Set up navigation with NavHost + NavController
5. Create your theme (colors, typography)

### Phase 2: PDF Features (Day 2-3)
1. Add PDFBox-Android to `build.gradle.kts`
2. Create `PdfExtractor` class — open URI, load PDF, extract text per page
3. Handle both `file://` and `content://` URI schemes
4. Create `PdfDocumentManager` — use `PdfRenderer` to render page bitmaps
5. Build `PdfViewerScreen` — show page bitmap with next/prev navigation
6. Build `HomeScreen` — "Open PDF" button with file picker
7. **Critical:** Copy picked PDF to cache temp file immediately

### Phase 3: Settings & Persistence (Day 3-4)
1. Add DataStore Preferences dependency
2. Create `SettingsManager` — define all preference keys and flows
3. Add suspend setters for each preference
4. Create `MainViewModel` — expose settings as StateFlows
5. Build `SettingsScreen` — connection mode, API key, model selection, defaults
6. Add recent PDFs history with JSON serialization

### Phase 4: On-Device AI (Day 5-10)
1. Download `llama.cpp` source
2. Create a JNI wrapper (`llama_bridge.cpp`)
3. Write Kotlin `LlmEngine` — JNI declarations, load/generate/unload
4. Set up CMakeLists.txt for NDK compilation
5. Add abiFilters = "arm64-v8a" in build.gradle.kts
6. Download Qwen 2.5 1.5B Q4_K_M GGUF from Hugging Face
7. Implement model download with OkHttp + progress tracking
8. Test: run inference and display output

This is the hardest part. Alternatives to skip native compilation:
- Use **ML Kit's LLM** (Google's solution, easier but less customizable)
- Use **MLC-LLM** (another wrapper)
- Use **Groq-only mode** (no local AI needed)

### Phase 5: Cloud AI (Day 3-4)
1. Add OkHttp dependency
2. Create `GroqClient` — POST to `/chat/completions`
3. Implement SSE streaming — read line by line, parse "data: {...}"
4. Handle both streaming and non-streaming modes
5. Add `validateApiKey()` — test key against `/models` endpoint

### Phase 6: Prompt Engineering (Day 1-2)
1. Create `PromptTemplates` object
2. Write system prompts for each mode
3. Build user prompts that include document text
4. Handle context window limits (truncate text to ~10-12K chars)
5. Test with different models and refine prompts

### Phase 7: Chat UI (Day 2-3)
1. Build `ChatScreen` — input bar, message list, action mode selector
2. Create `ChatMessageBubble` — styled differently for user vs AI
3. Add streaming support — `StreamingMessageBubble` with cursor
4. Implement `ChatViewModel` — hold chat state, dispatch actions
5. Add auto-scroll to bottom on new messages
6. Handle errors gracefully — show in Snackbar
7. Add keyboard-safe input bar with `imePadding()`

### Phase 8: Polish (Day 2-3)
1. Add animations (fade, slide between screens)
2. Add dark theme support with dynamic colors
3. Handle edge cases (no PDF loaded, no API key, model not downloaded)
4. Test on Android 10-15 devices
5. Test both local and online modes
6. Fix any Java API compatibility issues
7. Add ProGuard rules for release build

---

## Interview Questions (Based on This Project)

If you build a project like this and go for an Android developer interview, here are the questions you're likely to face — organized by topic with answers drawn directly from this project.

---

### 📱 Android Fundamentals

**Q: What's the difference between `minSdk`, `targetSdk`, and `compileSdk`?**

- **minSdk (29):** The lowest Android version the app supports. API 29 = Android 10. Any device below this can't install the app.
- **targetSdk (35):** The Android version the app was tested against. Android uses this to decide which compatibility behaviors to enable.
- **compileSdk (35):** The SDK version used to compile the app. You can use APIs up to this level. 

*In this project:* We set `minSdk = 29` for broad compatibility but discovered that Java 21 APIs like `List.removeLast()` crash on Android 10. We had to replace them with `removeAt(lastIndex)`.

**Q: What is scoped storage and how does it affect PDF access?**

Since Android 10 (API 29), apps can't directly access files by absolute path. Instead, users pick files via system pickers, which return `content://` URIs with temporary permissions. This is why we copy the PDF to a temp file in the app's cache directory immediately after picking.

**Q: What permissions does this app need and why?**

- `INTERNET` — For Groq Cloud API calls and model download
- `READ_EXTERNAL_STORAGE` — For older devices (up to API 32) to read PDFs
- `READ_MEDIA_DOCUMENTS` — For API 33+ to read documents via MediaStore
- `WRITE_EXTERNAL_STORAGE` — Only up to API 29 (deprecated in favor of scoped storage)

Note: The modern approach is to use `ActivityResultContracts.OpenDocument()` which doesn't require any storage permissions — the system picker handles access.

---

### 🏗️ Architecture & Design

**Q: Why use ViewModel + StateFlow instead of LiveData?**

`StateFlow` is Kotlin-native, works seamlessly with coroutines, and integrates perfectly with Compose via `collectAsStateWithLifecycle()`. `LiveData` is Java-based and doesn't support coroutines as naturally. Google now recommends StateFlow for new projects.

**Q: Explain the unidirectional data flow pattern in this app.**

1. **UI events** (button click, text input) call ViewModel methods
2. **ViewModel** processes the event, launches a coroutine, and updates a `StateFlow`
3. **StateFlow emission** triggers Compose recomposition
4. **UI** re-renders with the new state

Benefits: predictable state, easy debugging, no circular dependencies.

**Q: Why did you separate `PdfExtractor` (text) from `PdfDocumentManager` (rendering)?**

They use completely different libraries and serve different purposes:
- `PdfExtractor` uses PDFBox-Android to extract plain text for the AI to process
- `PdfDocumentManager` uses Android's built-in `PdfRenderer` to generate page bitmaps for visual preview

Separating them follows the Single Responsibility Principle and makes each class easier to test and maintain.

**Q: How do you share data between ViewModels?**

The `ChatViewModel` needs access to connection mode, API key, and model selection from `MainViewModel`. We use a `setSharedViewModel(vm)` method called from the Composable via `LaunchedEffect(Unit)`:
```kotlin
LaunchedEffect(Unit) {
    chatViewModel.setSharedViewModel(mainViewModel)
}
```
This avoids tight coupling while allowing shared access to app-wide state.

---

### 🎨 Jetpack Compose

**Q: How does Compose handle state changes and recomposition?**

Compose tracks reads from `State<T>` objects (like `StateFlow` collected via `collectAsStateWithLifecycle()`). When the state value changes, Compose automatically recomposes only the composables that read that state. This is called **recomposition** and is the core of Compose's reactivity model.

**Q: What's the problem with `imePadding()` inside a Scaffold?**

`imePadding()` applied to a Column inside a Scaffold's content area conflicts with Scaffold's own padding handling. During rapid recomposition (like streaming AI text), this causes a layout crash. The fix is to apply `imePadding()` only to the bottom input Surface, not the entire Column.

**Q: Why use `LaunchedEffect` vs `rememberCoroutineScope`?**

- `LaunchedEffect` is tied to the composable's lifecycle — it automatically cancels when the composable leaves the composition. Use it for one-shot operations (loading data on screen open).
- `rememberCoroutineScope` gives you a scope to launch coroutines from callbacks (like button clicks).

*Example in this project:* Loading chat history uses `LaunchedEffect(pdfUri)` because it should run once when the screen opens. Saving settings uses `scope.launch { }` from a button click.

**Q: How does `remember` work and when should you provide keys?**

`remember { value }` remembers a value across recompositions. Providing a key like `remember(key) { value }` recreates the value when the key changes. This is important for the target language field — without the `remember(defaultLang)` key, changing the default in Settings wouldn't update the local state.

---

### 📄 PDF Handling

**Q: Why can't you just pass a `content://` URI to a ViewModel?**

Android's `content://` URI permissions are tied to the **Activity** that received the intent. When the URI is passed to a ViewModel (which outlives the Activity across configuration changes), the permission is lost. Accessing the URI from a different context throws `SecurityException: Permission Denial`.

**Q: How does PDFBox-Android extract text from a PDF?**

PDFBox-Android (a port of Apache PDFBox) loads the PDF with `PDDocument.load()`, then uses `PDFTextStripper` to extract text. We configure it with `sortByPosition = true` (maintains reading order) and `addMoreFormatting = true` (preserves paragraph breaks). We extract per-page and also concatenate all pages for AI processing.

**Q: What's the difference between `PdfRenderer` and PDFBox?**

- `PdfRenderer` is a **built-in Android API** (API 21+) that renders PDF pages as **bitmaps** for visual display. Fast, no extra dependencies.
- PDFBox-Android is a **third-party library** that extracts **plain text** from PDFs. Slower, more memory-intensive, but can read text content.

We use BOTH — PdfRenderer for the page preview, PDFBox for text extraction.

---

### 🤖 AI & LLM Integration

**Q: How does streaming work with Groq Cloud API?**

Groq uses **Server-Sent Events (SSE)** — the response is a stream of HTTP chunks:
```
data: {"choices":[{"delta":{"content":"Hello"}}]}
data: {"choices":[{"delta":{"content":" world"}}]}
data: [DONE]
```
We read line by line, parse each `data:` JSON, extract the `delta.content` field, and accumulate it. Each new chunk triggers `onPartial()` which updates a `StateFlow`, causing the UI to recompose with the latest partial text.

**Q: How does LlmEngine communicate with llama.cpp?**

Through **JNI (Java Native Interface)**. The Kotlin code declares `external` functions like `nativeLoadModel()`, `nativeGenerate()`, etc. These are implemented in a C++ bridge file compiled with the Android NDK. The C++ bridge calls llama.cpp's C API. A `GenerationCallback` interface allows C++ to call back into Kotlin with each generated token.

**Q: What is Q4_K_M quantization?**

Quantization reduces the precision of model weights to make them smaller and faster. Q4_K_M means:
- **Q4:** 4-bit quantization (down from 16-bit)
- **K_M:** K-quant with "medium" size — a smart mixed-precision scheme that keeps important layers at higher precision

Result: Qwen 2.5 1.5B goes from ~3GB (FP16) to ~900MB (Q4_K_M) with minimal quality loss.

**Q: How do you handle the context window limit of LLMs?**

We truncate PDF text to ~12,000 characters before sending it to the LLM. If the text is longer, we add a note: `[... N more characters truncated]`. This is done in `PdfExtractor.getTruncatedText()` and respected by all prompt templates.

**Q: Why did Groq return HTTP 400 and how did you fix it?**

The model name `llama3-70b-8192` was decommissioned by Groq. We fixed it by:
1. Querying Groq's `/models` endpoint to find current model names
2. Updating all defaults to `llama-3.3-70b-versatile`
3. Adding a **transparent migration** in `SettingsManager` — any saved old model name is automatically mapped to the new one in the Flow

---

### 💾 Persistence & State

**Q: Why DataStore instead of SharedPreferences?**

DataStore is Google's modern replacement for SharedPreferences with:
- **Flow-based reads** — reactive, no need to manually refresh
- **Suspend writes** — no ANR risk
- **Type safety** — uses typed keys
- **Consistency** — atomic reads and writes with transaction guarantees

**Q: How do you store recent PDFs and chat history?**

- **Recent PDFs:** JSON-encoded list in DataStore (up to 5 entries, each with URI, title, timestamp)
- **Chat history:** JSON files in `filesDir/chat_history/` named by SHA-256 hash of the PDF URI (avoids filesystem issues with special characters)

**Q: Why not use Room/SQLite for chat history?**

For this use case, JSON files are sufficient:
- Each PDF has at most a few hundred messages
- We always load/save the entire conversation (no complex queries needed)
- JSON files are human-readable for debugging
- Room adds unnecessary complexity and dependency weight

---

### ⚠️ Challenges & Problem Solving

**Q: What was the hardest bug you fixed in this project?**

The app crashed on Android 10 with `NoSuchMethodError: List.removeLast()`. This took time to diagnose because:
1. It only happened on older devices, not the emulator
2. `removeLast()` is a Java 21 API that looks perfectly fine in code
3. Android 10 (API 29) only supports up to Java 8 APIs

The fix: replace with `messages.removeAt(messages.lastIndex)` which has been available since API 1.

**Q: How would you make the chat history sync across devices?**

Currently chat history is stored in app-internal storage (per-device). To sync:
1. Add a user account system (Firebase Auth, Supabase, or similar)
2. Store chat history in a cloud database (Firestore, Supabase, or a custom backend)
3. Add pull-to-refresh to sync from cloud when opening a PDF

**Q: What security concerns exist in this app?**

- **Groq API key:** Currently stored in plain DataStore. Should use EncryptedSharedPreferences or Android Keystore.
- **PDF content:** Sent to Groq Cloud when in online mode. Users with sensitive documents should use local mode only.
- **Temp files:** Copied to cache directory where other apps with storage permissions could potentially read them.

**Q: How would you optimize the app's performance?**

- **Lazy loading:** Only extract text from visible pages, not the entire PDF at once
- **Streaming:** Already implemented for AI responses
- **Model caching:** Keep llama.cpp model loaded in memory across screen navigations
- **Debounce:** Already implemented for settings (500ms debounce to avoid DataStore jank)
- **Image caching:** Cache rendered page bitmaps to avoid re-rendering

### 🛠️ Build System & Gradle

**Q: What's the difference between `implementation` and `api` in build.gradle.kts?**

- `implementation`: The dependency is available to the module that declares it, but NOT transitively to consumers. Faster build times. Use by default.
- `api`: The dependency leaks through to consumers. Used when you expose the dependency's types in your public API.

*In this project:* All dependencies use `implementation`. For example, `material-icons-extended` is an `implementation` dependency because it's only used internally by our UI code.

**Q: What is a Compose BOM and why use it?**

The Compose Bill of Materials (`compose-bom:2024.12.01`) is a curated set of Compose library versions that are guaranteed to work together. Instead of specifying each Compose version individually (like `ui:1.7.6`, `material3:1.3.1`), you declare one BOM version and all Compose libs resolve to compatible versions automatically.

**Q: Why `abiFilters "arm64-v8a"` only?**

This excludes 32-bit architectures (armeabi-v7a, x86, x86_64) to reduce build time and APK size. arm64-v8a covers ~98% of modern Android devices. To support older devices, add `armeabi-v7a` but expect 3x slower builds.

**Q: What does ProGuard/R8 do and why does this project need custom rules?**

ProGuard/R8 shrinks, obfuscates, and optimizes code during release builds. It can strip classes/methods that appear unused — but it doesn't understand JNI (native methods called from C++), Kotlin serialization (reflective), or PDFBox (loaded via ServiceLoader). Release builds would break without rules keeping these classes.

### 🧪 Testing

**Q: How would you unit test ChatViewModel?**

ChatViewModel depends on LlmEngine and GroqClient. In tests, you'd:
1. Mock both dependencies (e.g., with MockK)
2. Call `submitAction()` with a test action
3. Verify the state Flow emits expected messages
4. Test both success and failure paths
5. Test that `persistHistory()` is called on success

**Q: How would you test the PDF text extraction?**

Create small test PDFs with known content, place them in `androidTest/assets/`, and verify:
- Correct page count
- Expected text content per page
- Proper handling of Unicode, tables, and images
- Truncation at 12K chars works correctly

**Q: How do you test a Compose UI component?**

Use Compose UI Test library (`ui-test-manifest` and `ui-test-junit4`):
- `ComposeTestRule` — set content with the composable
- `onNodeWithText("Hello")` — find elements by text
- `performClick()` — simulate user interaction
- `assertIsDisplayed()` — verify visibility
- Test edge cases: empty state, loading, error, streaming

### 🧵 Coroutines & Concurrency

**Q: What's the difference between `launch` and `async`?**

- `launch`: Fire-and-forget coroutine. Returns `Job`. Used for events (saving to DB, updating state).
- `async`: Returns `Deferred<T>` that can be awaited with `.await()`. Used when you need a result.

*In this project:* `viewModelScope.launch { }` is used throughout because ViewModel actions are fire-and-forget events.

**Q: Why use `Dispatchers.IO` for file operations?**

`Dispatchers.IO` is designed for blocking I/O operations (file reads, network calls). It has a larger thread pool optimized for I/O. Using `Dispatchers.Default` (CPU-bound work) or the main thread for I/O would cause thread starvation or ANRs.

*Example:* `PdfExtractor.extract()` uses `withContext(Dispatchers.IO)` to offload PDF parsing off the main thread.

**Q: What is structured concurrency?**

The principle that coroutines should be launched within a defined scope. When the scope is cancelled, all its child coroutines are cancelled. This prevents leaks and ensures resources are cleaned up.

*In this project:* All coroutines are launched within `viewModelScope`. When the ViewModel is cleared (screen destroyed), all running operations are automatically cancelled.

### 🧭 Navigation

**Q: How do you pass PDF URIs between screens?**

PDF URIs (like `file:///data/.../cache/pdf_12345.pdf`) contain special characters (/ , : , .). Navigation Compose requires URL encoding:
```kotlin
fun pdfViewer(pdfUri: String): String =
    "pdf_viewer/${URLEncoder.encode(pdfUri, "UTF-8")}"
```
And decoding on the receiving end:
```kotlin
val pdfUri = URLDecoder.decode(
    backStackEntry.arguments?.getString("pdfUri") ?: "",
    "UTF-8"
)
```

**Q: How does Navigation Compose scope ViewModels?**

Each NavBackStackEntry gets its own ViewModelStore by default. Navigating to the same screen creates a new ViewModel. To share a ViewModel across screens, use:
- `navGraphViewModels()` — scoped to the NavGraph, survives navigation within the graph
- Hilt `hiltViewModel()` — automatic scoping via Dagger

*In this project:* Each screen gets its own ViewModel instance via `viewModel()`. Shared state is passed through `MainViewModel` which is shared via the custom `setSharedViewModel()` pattern.

---

## Learning Resources

### Kotlin
- [Kotlin Official Docs](https://kotlinlang.org/docs/home.html)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)
- [Kotlin Flows](https://kotlinlang.org/docs/flow.html)

### Jetpack Compose
- [Compose Pathway](https://developer.android.com/courses/jetpack-compose/course)
- [Compose State](https://developer.android.com/develop/ui/compose/state)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)

### Android Architecture
- [Guide to Android App Architecture](https://developer.android.com/topic/architecture)
- [ViewModel Overview](https://developer.android.com/topic/libraries/architecture/viewmodel)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [Navigation Compose](https://developer.android.com/develop/ui/compose/navigation)

### PDF Handling
- [PDFBox-Android GitHub](https://github.com/TomRoush/PdfBox-Android)
- [Android PdfRenderer Docs](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer)
- [Scoped Storage](https://developer.android.com/training/data-storage/scoped)

### LLM / AI
- [llama.cpp GitHub](https://github.com/ggerganov/llama.cpp)
- [Qwen 2.5 GGUF on Hugging Face](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF)
- [Groq Cloud API Docs](https://console.groq.com/docs)
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)

### Android NDK
- [NDK Guide](https://developer.android.com/ndk/guides)
- [JNI Tips](https://developer.android.com/training/articles/perf-jni)
- [CMake for Android](https://developer.android.com/ndk/guides/cmake)

---

## Future Improvements

If you want to extend this project, here are ideas:

- **✅ Chat history per PDF** — Already implemented: conversations are saved as JSON files and reload automatically when reopening any PDF
- **Per-PDF chat context** — Save chat messages and reload them when reopening a PDF
- **Bottom navigation** — Add bottom nav bar for quick switching between Home, Chat, Settings
- **Full-text search** — Search within PDF text
- **Highlight & annotate** — Long-press to select text, highlight, add notes
- **Export** — Export summaries/translations as text files
- **Multiple AI providers** — Support Together AI, DeepInfra, GitHub Models (all OpenAI-compatible)
- **Encrypted API keys** — Use EncryptedSharedPreferences for Groq API key
- **Larger local models** — Support Qwen 2.5 7B / Llama 3.2 3B (with GPU acceleration)
- **Bookmarks** — Save page bookmarks per PDF
- **Dark mode toggle** — Add explicit light/dark/system toggle in Settings

---

<div align="center">

Built with ❤️ using Jetpack Compose, llama.cpp, Groq Cloud & Qwen 2.5

</div>
