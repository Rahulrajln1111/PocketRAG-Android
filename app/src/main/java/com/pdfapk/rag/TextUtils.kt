package com.pdfapk.rag

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Text normalisation shared by the chunker, BM25 and the prompt builder.
 *
 * The goal is not linguistic purity: extraction from PDFs produces mangled
 * line breaks, ligatures, hyphenation and repeated headers. All of that has to
 * be cleaned before chunking, otherwise a query for "encryption" never matches
 * "encryp-\ntion".
 */
object TextUtils {

    private val WHITESPACE = Regex("[ \\t\\u00A0\\u2000-\\u200B]+")
    private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n\\t]]")
    private val SOFT_HYPHEN = "\u00AD"
    private val LINEBREAK_HYPHEN = Regex("(\\p{L})-[ \\t]*\\n[ \\t]*(\\p{Ll})")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val DIACRITICS = Regex("\\p{M}+")

    private val STOPWORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can",
        "had", "her", "was", "one", "our", "out", "has", "have", "this", "that",
        "with", "from", "they", "will", "would", "there", "their", "what",
        "which", "when", "where", "who", "how", "than", "then", "them", "these",
        "those", "into", "onto", "over", "under", "such", "some", "more", "most",
        "other", "only", "own", "same", "too", "very", "just", "about", "been",
        "being", "does", "did", "doing", "should", "could", "may", "might",
        "must", "shall", "its", "it's", "a", "an", "of", "to", "in", "on", "at",
        "by", "is", "are", "be", "as", "if", "or", "we", "he", "she", "it", "i"
    )

    /**
     * Clean one page of extracted text.
     *
     * - fixes "encryp-\ntion" -> "encryption"
     * - drops soft hyphens and control characters
     * - collapses runs of spaces but keeps paragraph breaks
     */
    fun cleanPage(raw: String): String {
        var text = raw.replace(SOFT_HYPHEN, "")
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        text = text.replace(LINEBREAK_HYPHEN) { match ->
            match.groupValues[1] + match.groupValues[2]
        }
        text = CONTROL.replace(text, "")
        val lines = text.split('\n').map { WHITESPACE.replace(it, " ").trim() }
        // Blank runs become one paragraph break, single breaks stay single.
        val out = StringBuilder()
        var blankRun = 0
        for (line in lines) {
            if (line.isEmpty()) {
                blankRun++
                continue
            }
            if (out.isNotEmpty()) {
                out.append(if (blankRun > 0) "\n\n" else "\n")
            }
            out.append(line)
            blankRun = 0
        }
        return out.toString().trim()
    }

    /** NFKC + lowercase + diacritics stripped; used for matching, not display. */
    fun fold(text: String): String {
        // NFD first: "é" must decompose into "e" + combining acute, otherwise
        // the combining-mark filter has nothing to remove.
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).lowercase()
        val stripped = DIACRITICS.replace(decomposed, "")
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    /** All word-ish tokens, including digits and non-Latin scripts. */
    fun words(text: String): List<String> =
        WORD.findAll(text).map { it.value }.toList()

    /**
     * Terms for BM25: folded, length >= 2, stopwords removed.
     *
     * Numbers are kept: version strings, article numbers and error codes are
     * exactly what keyword search is good at and dense vectors are bad at.
     */
    fun terms(text: String): List<String> =
        words(fold(text))
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toList()

    /**
     * Cheap token estimate for the LLM context budget.
     *
     * WordPiece splits long words and punctuation, so real token counts are
     * consistently above the word count. 1.35x is a deliberately conservative
     * factor: over-estimating wastes a little context, under-estimating
     * overflows the window and the model silently loses the question.
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        val words = words(text).size
        val punctuation = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        return max(1, ceil(words * 1.35 + punctuation * 0.25).toInt())
    }

    /** Truncate to a token budget without cutting mid-word. */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (estimateTokens(text) <= maxTokens) return text
        val words = words(text)
        val keep = max(1, (maxTokens / 1.35).toInt())
        if (keep >= words.size) return text
        return words.take(keep).joinToString(" ") + " …"
    }

    /** Split into sentences for previews and summarisation heuristics. */
    fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?。！？])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
