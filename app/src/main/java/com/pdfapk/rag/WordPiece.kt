package com.pdfapk.rag

import java.io.InputStream

/** Fixed-length tensors for one embedding request. */
data class Encoded(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray
) {
    val length: Int get() = inputIds.size
}

/**
 * WordPiece tokenizer matching `sentence-transformers/all-MiniLM-L6-v2`.
 *
 * The tokenizer is the part of on-device embedding that people get wrong: a
 * regex split is not WordPiece, and a mismatch between the tokenizer used at
 * training time and at inference silently degrades every vector. This
 * implements the uncased BERT rules that MiniLM was trained with:
 *
 * - NFKC, lowercase, diacritics stripped
 * - whitespace and punctuation split, CJK characters split individually
 * - greedy longest-match-first subwords with the `##` continuation prefix
 * - `[CLS]` ... `[SEP]`, padded to a fixed length
 *
 * The vocabulary (`vocab.txt`, 30,522 entries) ships in assets, so this works
 * offline without a tokenizer runtime.
 */
class WordPiece(
    private val vocab: Map<String, Int>,
    val maxLength: Int = 256
) {

    private val clsId: Int = vocab["[CLS]"] ?: 101
    private val sepId: Int = vocab["[SEP]"] ?: 102
    private val padId: Int = vocab["[PAD]"] ?: 0
    private val unkId: Int = vocab["[UNK]"] ?: 100

    val size: Int get() = vocab.size

    /** Encodes one string into fixed-size int64 tensors for ONNX Runtime. */
    fun encode(text: String, limit: Int = maxLength): Encoded {
        val pieces = tokenize(text).take(maxOf(1, limit - 2))
        val ids = LongArray(limit)
        val mask = LongArray(limit)
        ids[0] = clsId.toLong()
        mask[0] = 1
        var cursor = 1
        for (piece in pieces) {
            if (cursor >= limit - 1) break
            ids[cursor] = piece.toLong()
            mask[cursor] = 1
            cursor++
        }
        ids[cursor] = sepId.toLong()
        mask[cursor] = 1
        // Remaining positions stay 0 (the [PAD] id) with mask 0.
        return Encoded(
            inputIds = ids,
            attentionMask = mask,
            tokenTypeIds = LongArray(limit)
        )
    }

    /** Number of WordPiece tokens, without the two special tokens. */
    fun countTokens(text: String): Int = tokenize(text).size

    /** Raw vocabulary ids (no padding, no specials). */
    fun tokenize(text: String): List<Int> {
        val out = ArrayList<Int>()
        for (token in basicTokens(text)) {
            wordPiece(token, out)
        }
        return out
    }

    private fun wordPiece(token: String, out: MutableList<Int>) {
        if (token.length > 100) {                 // BERT's hard limit
            out.add(unkId)
            return
        }
        var start = 0
        var added = false
        while (start < token.length) {
            var end = token.length
            var current = -1
            while (start < end) {
                val piece = if (start == 0) token.substring(start, end)
                else "##" + token.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    current = id
                    break
                }
                end--
            }
            if (current == -1) {
                out.clear()
                out.add(unkId)
                return
            }
            out.add(current)
            added = true
            start = end
        }
        if (!added) out.add(unkId)
    }

    private fun basicTokens(text: String): List<String> {
        val folded = TextUtils.fold(text)
        val out = ArrayList<String>()
        val word = StringBuilder()

        fun flush() {
            if (word.isNotEmpty()) {
                out.add(word.toString())
                word.setLength(0)
            }
        }

        for (ch in folded) {
            when {
                ch.isWhitespace() -> flush()
                isCjk(ch) -> {
                    flush()
                    out.add(ch.toString())
                }
                ch.isLetterOrDigit() -> word.append(ch)
                else -> {
                    flush()
                    out.add(ch.toString())
                }
            }
        }
        flush()
        return out
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3040..0x30FF) ||
            (code in 0xAC00..0xD7AF) || (code in 0x3400..0x4DBF)
    }

    companion object {
        /**
         * Loads a BERT-style vocabulary: one token per line, line number is the
         * id. Blank and duplicate lines are ignored (both occur in the wild).
         */
        fun fromLines(lines: Sequence<String>): WordPiece {
            val vocab = HashMap<String, Int>(40000)
            lines.forEachIndexed { index, raw ->
                val token = raw.trimEnd('\n', '\r')
                if (token.isNotEmpty()) vocab[token] = index
            }
            return WordPiece(vocab)
        }

        fun from(input: InputStream): WordPiece =
            input.bufferedReader().use { reader ->
                fromLines(reader.lineSequence())
            }
    }
}
