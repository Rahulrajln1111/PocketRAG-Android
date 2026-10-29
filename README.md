# PocketRAG

**An on-device RAG assistant for chatting with PDFs on Android.**

PocketRAG turns a PDF into a searchable knowledge source. It extracts and chunks the document by page, builds a hybrid dense and keyword index on the phone, retrieves relevant passages, and generates grounded answers with page citations. Users can run generation locally with Qwen through `llama.cpp`, or use Groq for cloud inference.

> The retrieval pipeline remains on-device after the one-time embedding-model download. In cloud mode, only the assembled prompt is sent to Groq.

## Highlights

- **Page-aware RAG:** keeps each chunk within one page so citations remain traceable.
- **Hybrid retrieval:** combines MiniLM semantic search with BM25 keyword search.
- **Custom vector index:** persists an IVF index with `int8` vector codes in app storage.
- **Rank fusion and guardrails:** uses Reciprocal Rank Fusion, a relevance threshold, and page diversity.
- **Grounded answers:** builds token-budgeted prompts with `[p.N]` source labels and refuses unsupported questions.
- **Local or cloud generation:** runs Qwen 2.5 1.5B locally through `llama.cpp`, or streams responses from the Groq API.
- **Document tools:** supports question answering, summarization, explanation, and translation.
- **Native Android UI:** built with Kotlin, Jetpack Compose, Coroutines, and `StateFlow`.
- **Persistent state:** caches document indexes, user settings, recent PDFs, and per-document chat history.

## RAG Pipeline

```text
PDF
 └─ PDFBox text extraction by page
     └─ cleaning + heading-aware chunks with overlap
         ├─ MiniLM int8 embeddings ──> persisted IVF index
         └─ BM25 keyword index

Question ──> MiniLM query embedding
             └─ dense search + BM25
                 └─ Reciprocal Rank Fusion
                     └─ relevance floor + page diversity
                         └─ top passages with [p.N] labels
                             └─ Qwen via llama.cpp or Groq
                                 └─ grounded answer + tappable citations
```

### Retrieval choices

| Stage | Implementation | Purpose |
|---|---|---|
| Extraction | PDFBox Android | Extract text separately for every page |
| Chunking | 220-word target, 40-word overlap | Preserve context without crossing citation boundaries |
| Embeddings | `all-MiniLM-L6-v2`, ONNX int8, 384 dimensions | Run semantic retrieval on-device |
| Dense index | Custom IVF, k-means partitions, int8 codes | Keep the index compact and persistent |
| Sparse index | BM25 (`k1=1.2`, `b=0.75`) | Recover exact terms, identifiers, and names |
| Fusion | Reciprocal Rank Fusion (`k=60`) | Combine rankings without score calibration |
| Guardrails | cosine floor and at most two chunks per page | Reject weak context and avoid redundant evidence |
| Prompt assembly | 1,600-token evidence budget | Fit focused excerpts into the local model context |

The detailed design, index format, trade-offs, and evaluation plan are documented in [`docs/RAG.md`](docs/RAG.md).

## Architecture

```text
Jetpack Compose UI
        │
Android ViewModels + StateFlow
        │
        ├── PDF extraction and rendering
        ├── RagEngine
        │    ├── Chunker
        │    ├── ONNX MiniLM embedder
        │    ├── IVF vector index
        │    ├── BM25 + hybrid search
        │    └── prompt assembly
        ├── Local generation: llama.cpp JNI + Qwen GGUF
        └── Cloud generation: Groq streaming API
```

`RagEngine` owns the index lifecycle for the open document. It loads a compatible cached index when available, otherwise chunks and embeds the PDF before persisting the result. `ChatViewModel` retrieves evidence before generation and attaches source metadata to each answer.

## Tech Stack

- **Language/UI:** Kotlin, Jetpack Compose, Material 3
- **State and concurrency:** ViewModel, `StateFlow`, Kotlin Coroutines
- **PDF processing:** PDFBox Android, Android `PdfRenderer`
- **Embeddings:** ONNX Runtime Android, quantized `all-MiniLM-L6-v2`
- **Retrieval:** custom IVF vector index, BM25, Reciprocal Rank Fusion
- **Local LLM:** Qwen 2.5 1.5B Instruct Q4_K_M, `llama.cpp`, JNI/NDK
- **Cloud LLM:** Groq OpenAI-compatible API, OkHttp streaming
- **Persistence:** DataStore and app-internal index/chat caches
- **Testing:** JUnit and Kotlin Coroutines Test

## Project Structure

```text
app/src/main/
├── cpp/llama_bridge.cpp       # JNI bridge for llama.cpp
├── assets/minilm_vocab.txt    # WordPiece vocabulary
└── java/com/pdfapk/
    ├── data/                  # Settings and chat history
    ├── llm/                   # Local and Groq generation clients
    ├── pdf/                   # Extraction and page rendering
    ├── rag/                   # Chunking, embeddings, indexes, retrieval, prompts
    ├── ui/                    # Compose screens and components
    └── viewmodel/             # UI state and orchestration

app/src/test/java/com/pdfapk/rag/
├── TextAndChunkerTest.kt
├── WordPieceTest.kt
├── TokenizerFidelityTest.kt
├── RetrievalTest.kt
└── PromptAssemblyTest.kt
```

## Getting Started

### Prerequisites

- Android Studio with JDK 17
- Android SDK 35
- Android NDK `25.1.8937393`
- CMake 3.22.1 or newer
- An ARM64 Android device running Android 10 or newer
- Internet access for the first native build and model downloads

### Build

```bash
git clone git@github.com:Rahulrajln1111/PocketRAG-Android.git
cd PocketRAG-Android
./gradlew assembleDebug
```

The native build fetches the pinned `llama.cpp` revision through CMake. Install the generated APK from:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Run the app

1. Open a text-based PDF.
2. Wait for the one-time MiniLM download and document indexing.
3. Ask a question and use the citation chips to open supporting pages.
4. For offline generation, download the Qwen model from the app settings.
5. For cloud generation, select Groq mode and provide your own API key.

API keys and model files are not committed to the repository. The current app stores the Groq key in private app DataStore; a production release should move it to encrypted storage or a backend token broker.

## Tests

Run the JVM retrieval and prompt tests with:

```bash
./gradlew :app:testDebugUnitTest
```

The suite covers text cleaning, page-bounded chunking, WordPiece tokenization and reference-token fidelity, BM25 ranking, IVF recall and persistence, rank fusion, relevance filtering, page diversity, citation prompts, and token budgets.

## Design Constraints

- Scanned PDFs require OCR and currently return a clear no-text error.
- Table extraction loses visual row and column relationships.
- MiniLM is English-centric; multilingual retrieval needs a multilingual embedding model.
- Local answer quality is limited by the 1.5B model, so retrieval filtering and citation checks act as guardrails rather than guarantees.
- Retrieval quality needs evaluation against a labelled document-question set before publishing recall or faithfulness claims.

## Why this project matters

PocketRAG demonstrates an end-to-end LLM feature rather than a thin chat API wrapper. It includes document ingestion, context engineering, embeddings, vector and keyword retrieval, index persistence, prompt budgeting, model routing, streaming, citations, failure handling, and automated tests in a constrained mobile environment.
