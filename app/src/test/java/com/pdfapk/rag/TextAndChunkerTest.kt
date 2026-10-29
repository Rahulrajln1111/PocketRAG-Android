package com.pdfapk.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAndChunkerTest {

    // ---------------------------------------------------------------- text

    @Test
    fun `hyphenated line breaks are rejoined`() {
        val cleaned = TextUtils.cleanPage("The encryp-\ntion key is rotated.")
        assertTrue(cleaned.contains("encryption"))
        assertFalse(cleaned.contains("encryp-\n"))
    }

    @Test
    fun `whitespace and blank lines are normalised`() {
        val cleaned = TextUtils.cleanPage("A   b\n\n\n\nC\t\tD")
        assertEquals("A b\n\nC D", cleaned)
    }

    @Test
    fun `folding strips case and diacritics`() {
        assertEquals("resume", TextUtils.fold("Résumé"))
    }

    @Test
    fun `terms drop stopwords but keep numbers`() {
        val terms = TextUtils.terms("The invoice 2024 is due, per section 4.2")
        assertFalse(terms.contains("the"))
        assertFalse(terms.contains("is"))
        assertTrue(terms.contains("invoice"))
        assertTrue(terms.contains("2024"))
    }

    @Test
    fun `token estimate grows with length and truncation respects budget`() {
        val short = "one two three"
        val long = (1..400).joinToString(" ") { "word$it" }
        assertTrue(TextUtils.estimateTokens(long) > TextUtils.estimateTokens(short))

        val truncated = TextUtils.truncateToTokens(long, 50)
        assertTrue(TextUtils.estimateTokens(truncated) <= 80)
        assertTrue(truncated.endsWith("…"))
    }

    // ------------------------------------------------------------- chunker

    private fun page(index: Int, body: String): String =
        "ACME Corp - Confidential\nPage ${index + 1}\n$body\nPage ${index + 1} of 4"

    @Test
    fun `chunks never cross a page boundary`() {
        val pages = (0 until 4).map { page(it, "Body of page $it. " .repeat(120)) }
        val chunks = Chunker().chunk(pages)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.pageIndex in 0..3 })
        for (chunk in chunks) {
            assertTrue(
                "chunk on page ${chunk.pageIndex} leaked another page",
                chunk.text.contains("Body of page ${chunk.pageIndex}")
            )
        }
    }

    @Test
    fun `repeated headers and footers are stripped`() {
        val pages = (0 until 4).map { page(it, "Content number $it. " .repeat(60)) }
        val chunks = Chunker().chunk(pages)

        assertTrue(chunks.isNotEmpty())
        assertFalse(chunks.any { it.text.contains("ACME Corp") })
    }

    @Test
    fun `long pages are split with overlap`() {
        val body = (1..800).joinToString(" ") { "token$it" }
        val chunks = Chunker(targetWords = 200, overlapWords = 40).chunk(listOf(body))

        assertTrue("expected several chunks, got ${chunks.size}", chunks.size >= 3)
        val firstWords = TextUtils.words(chunks[0].text)
        val secondWords = TextUtils.words(chunks[1].text)
        val tail = firstWords.takeLast(20)
        assertTrue(
            "expected overlapping words between consecutive chunks",
            tail.any { it in secondWords }
        )
    }

    @Test
    fun `headings start a chunk and are recorded`() {
        val text = buildString {
            append("INTRODUCTION\n")
            append("This section explains the purpose of the document. ".repeat(12))
            append("\n2.1 SCOPE\n")
            append("This section describes the scope. ".repeat(12))
        }
        val chunks = Chunker(targetWords = 120, overlapWords = 20).chunk(listOf(text))

        assertTrue(chunks.size >= 2)
        assertEquals("INTRODUCTION", chunks.first().heading)
        assertTrue(chunks.any { it.heading == "2.1 SCOPE" })
    }

    @Test
    fun `pages without text produce no chunks`() {
        val chunks = Chunker().chunk(listOf("", "   ", "\n\n"))
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunk ids are unique and sequential`() {
        val pages = (0 until 5).map { page(it, "Sentence about topic $it. ".repeat(80)) }
        val chunks = Chunker().chunk(pages)
        assertEquals(chunks.size, chunks.map { it.id }.distinct().size)
        assertEquals(chunks.indices.toList(), chunks.map { it.id })
    }
}
