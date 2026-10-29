# On-device RAG chain

This document describes how a question about a PDF is answered. It replaces the
previous behaviour, which took the **first 12,000 characters** of the document
and hoped the answer was in there.

Everything below runs on the phone. After a one-time 23 MB embedding-model
download, asking a question touches no network at all — including in online
mode, where only the final generation goes to Groq.

## Why retrieval at all

| | Before | After |
|---|---|---|
| Context sent to the model | first 12k characters (≈3k tokens) of page 1-6 | 4 chunks (~1.6k tokens) selected per question |
| Coverage of a 300-page report | ~2% | any page, at ~0.4-2 ms retrieval cost |
| Context window pressure | wasted on the introduction | spent on the two pages that answer the question |
| Long answers | silently cut off | model sees the whole question and its budget |
| Traceability | none | every claim cites `[p.N]`, tap to open the page |
| "Not in the document" | model guesses | explicit refusal when nothing clears the floor |

Citing a page is only possible if a chunk belongs to exactly one page, which is
why chunking is page-aware rather than a sliding window over the whole text.

## The chain

```
PDF ──PDFBox──► pages ──clean──► chunks ──MiniLM int8──► vectors
                                   │                        │
                                   └──────────► BM25        └──► IVF index (int8, persisted)
                                                     │               │
question ──MiniLM──► query vector ──────────────────┴──────► RRF fusion
                                                                     │
                                                     page diversity + relevance floor
                                                                     │
                                            top-4 excerpts with [p.N] labels
                                                                     │
                                       Qwen 2.5 1.5B (or Groq) ──► answer + citations
```

| Stage | File | Key parameters |
|---|---|---|
| Extract | `pdf/PdfExtractor.kt` | per-page text, `PDFTextStripper`, `sortByPosition` |
| Clean | `rag/TextUtils.kt` | de-hyphenation, whitespace, diacritics folding for matching |
| Chunk | `rag/Chunker.kt` | 220 words target, 40-word overlap, page-bounded, heading-aware |
| Embed | `rag/OnnxEmbedder.kt`, `rag/WordPiece.kt` | MiniLM-L6-v2 int8, 384 dims, 256 tokens, mean pooling |
| Index | `rag/IvfIndex.kt`, `rag/IndexStore.kt` | k-means IVF, int8 codes, binary persistence |
| Keyword | `rag/Bm25.kt` | BM25 k1=1.2, b=0.75, Lucene IDF |
| Fuse | `rag/HybridSearch.kt` | RRF k=60, vec floor 0.28, ≤2 chunks/page |
| Prompt | `rag/PromptAssembly.kt` | 1.6k token budget, `[p.N]` labels, refusal rule |
| Orchestrate | `rag/RagEngine.kt` | load-or-build index, progress, retrieval |
| UI | `ui/screens/ChatScreen.kt`, `ui/components/MessageBubble.kt` | progress, index stats, citation chips |

## Chunking rules

1. **One page per chunk.** A chunk spanning two pages cannot be cited honestly.
2. **Boundaries at line breaks**, and over-long extracted lines (a whole
   paragraph on one line is common in PDFs) are exploded into word windows so
   a single line cannot become one 800-word chunk.
3. **40-word overlap** so an answer on a boundary is still retrievable.
4. **Headings start chunks** and are recorded on the chunk, so a hit can say
   which section it came from — and the heading is prepended to the embedding
   text to give the vector a little structure.
5. **Running headers/footers are removed first.** A company name repeated on
   every page would otherwise dominate both BM25 and the embedding.

## Embeddings

`all-MiniLM-L6-v2` exported to ONNX and quantised to int8 (~23 MB, 384 dims).
It is a *sentence-similarity* model; the chat model is not used for embeddings
because instruct-tuned LMs are not trained for retrieval and would cost 20x the
memory.

- Tokenizer: WordPiece implemented in `WordPiece.kt` against the real 30,522
  token vocabulary (`assets/minilm_vocab.txt`). A regex split would silently
  degrade every vector.
- Pooling: attention-masked mean, then L2 normalise — the pooling MiniLM was
  trained with. Using `[CLS]` or ignoring the mask produces wrong geometry.
- Download: `EmbeddingModelStore` fetches the model once with progress, writes
  to a `.part` file and only renames it when the size is plausible.

## The vector database

A self-contained inverted-file index (`IvfIndex`), the same idea as FAISS
`IndexIVFFlat`:

1. **Partition** vectors with k-means (k-means++ seeding, fixed seed, 10
   iterations). `nlist ≈ 2·√N`, capped at 128.
2. **Quantise** each vector to int8 with a per-vector scale (symmetric, max
   absolute value → 127). This halves the footprint and keeps the inner loop
   in cache; retrieval only needs the ranking, not the exact distance.
3. **Search** only the `nprobe ≈ √nlist` nearest centroids, then refine within
   those lists.
4. **Persist** a compact binary (`IVF1`: magic, version, dim, nlist, nprobe,
   ids, scales, codes, centroids). Loading is milliseconds; re-embedding is
   seconds, so the cache is what makes reopening a document instant.

Sizing for a 500-page manual (≈600 chunks, 384 dims):

| Item | Size |
|---|---|
| int8 codes (600 × 384 B) | 230 KB |
| scales + ids | 5 KB |
| centroids (48 × 384 × 4 B) | 74 KB |
| chunk text + JSON | ~0.7 MB |

Retrieval cost is dominated by the probe: 600 chunks over 48 lists is ~12
candidates per list, and `nprobe ≈ √48 ≈ 6` lists are scanned, so a query does
~72 dot products — well under a millisecond on arm64. Brute force would also be
acceptable at this size (600 × 384 multiply-adds), which is why the index is
swappable behind `IvfIndex` — but IVF is what keeps a 1,000-page document or a
multi-document library from degrading linearly.

Why not a real vector database (sqlite-vec, ObjectBox, Qdrant)? The index has
to live in app storage, offline, with no server. Those add a native
dependency, an extra build step and a second persistence model for a workload
that fits in a few hundred kilobytes. The interface is deliberately small, so
replacing `IvfIndex` later is a contained change.

## Hybrid ranking

Dense and sparse retrieval fail in opposite directions, so both run:

- **Dense** finds paraphrase ("termination" ↔ "cancellation"), and is weak on
  exact tokens.
- **BM25** finds part numbers, error codes, API names and rare proper nouns,
  and is blind to paraphrase.

Results are fused with **Reciprocal Rank Fusion**: `score = Σ 1/(60 + rank)`
over the two ranked lists. RRF needs no score calibration, which matters
because cosine and BM25 are not on comparable scales.

Then two filters protect the prompt budget:

- **Relevance floor** on the vector score (0.28 for MiniLM). Nothing above the
  floor and no keyword hit → the app answers *"I could not find that in this
  document."* instead of feeding the model unrelated text.
- **Page diversity**: at most two chunks per page survive, so four slots are
  spent on four different passages rather than four adjacent sentences.

## Prompt budget

The local model has an 8,192-token context and a 2,048-token answer cap.

| Slice | Budget |
|---|---|
| System rules + question | ~200 tokens |
| Last three conversation turns | ~300 tokens |
| Retrieved excerpts | 1,600 tokens (≈4 chunks) |
| Answer | up to 2,048 tokens |

Four focused chunks beat twelve diluted ones on a 1.5B model; this is a
deliberate quality decision, not a limitation of the index.

The summary path uses the same budget arithmetic but a different selection:
opening pages, an evenly sampled middle and the closing page, with the model
told explicitly when it only saw an excerpt rather than the whole document.

## Citations and refusals

- The system prompt requires a `[p.N]` citation for every claim and forbids
  outside knowledge.
- If the model returns text without a citation, the sources it *was* given are
  appended, so a claim can still be checked.
- Citation chips under the answer open the cited page (`PdfViewerScreen` accepts
  a `startPage`), which turns the answer into something verifiable in one tap.
- Empty retrieval produces an immediate, honest refusal without spending 10-20
  seconds of on-device generation.

## Index lifecycle

Cache directory: `filesDir/rag/<sha256(pdfUri)>/` containing `chunks.json`,
`vectors.ivf`, `meta.json`.

The cache is invalidated when any of these change:

- **chunker version** (`IndexStore.CHUNKER_VERSION`)
- **embedder id** (`OnnxEmbedder.MODEL_ID`, includes dtype)
- **page count or total extracted characters** (the file changed on disk)

A stale index is deleted and rebuilt rather than mixed with new vectors —
mixing two embedding spaces degrades retrieval with no visible error.

## Measured behaviour

Unit tests (JVM, no device): `./gradlew :app:testDebugUnitTest` — 36 tests
covering cleaning, chunking, WordPiece, BM25, IVF recall against brute force,
save/load round-trip, RRF fusion, page diversity, the relevance floor, prompt
budgets and summary coverage.

Not measured yet, and therefore not claimed: on-device indexing time per page,
end-to-end answer quality, and recall on a labelled question set. The harness
for the last one is described below.

## Evaluation plan

To make claims about retrieval quality, build the set first:

1. 25-30 question/answer pairs over three documents: a long technical report
   (>100 pages), a scanned PDF, and one non-English document.
2. Per question, record the gold page.
3. Report **context recall@4** (gold page present in the retrieved chunks),
   **answer faithfulness** (the cited chunk supports the claim),
   **refusal rate** on 10 out-of-scope questions, plus index time, retrieval
   latency and time-to-first-token.

The unit tests prove the machinery; only this set can prove the retrieval.

## Known limitations

- **Scanned PDFs** have no text layer. The chain refuses with a clear message;
  OCR (ML Kit) is the next step, not a hidden fallback.
- **Tables** extract as linearised text; column relationships are lost before
  chunking.
- **Multilingual documents** embed with an English-centric model. Gecko or a
  multilingual MiniLM would be the swap, and the `Embedder` interface exists
  for exactly that.
- **Small chat model.** A 1.5B model can ignore instructions under pressure;
  the citation post-check and the relevance floor are guardrails, not
  guarantees.
