package com.pdfapk.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblyTest {

    private fun hit(id: Int, page: Int, text: String, score: Float = 0.5f) = Hit(
        chunk = Chunk(id = id, pageIndex = page, text = text),
        vectorScore = score,
        keywordScore = 0.1f,
        fusedScore = score
    )

    @Test
    fun `excerpts carry one-based page labels`() {
        val hits = listOf(
            hit(0, 2, "The contract renews automatically each year."),
            hit(1, 4, "Termination requires sixty days written notice.")
        )

        val assembled = PromptAssembly.question("How do I terminate?", hits)

        assertTrue(assembled.user.contains("[p.3]"))
        assertTrue(assembled.user.contains("[p.5]"))
        assertTrue(assembled.usedChunks.size == 2)
        assertTrue(assembled.system.contains("ONLY"))
        assertTrue(assembled.user.contains("Question: How do I terminate?"))
    }

    @Test
    fun `no hits produces an explicit no-evidence prompt`() {
        val assembled = PromptAssembly.question("What is the refund window?", emptyList())

        assertTrue(assembled.user.contains("No relevant excerpts"))
        assertTrue(assembled.user.contains("could not find"))
        assertTrue(assembled.usedChunks.isEmpty())
    }

    @Test
    fun `budget is respected even for one very long chunk`() {
        val longText = (1..2000).joinToString(" ") { "word$it" }
        val assembled = PromptAssembly.question(
            question = "Summarise the section",
            hits = listOf(hit(0, 0, longText)),
            maxPromptTokens = 150
        )

        assertTrue(assembled.estimatedTokens <= 160)
        assertEquals(1, assembled.usedChunks.size)
        assertTrue(assembled.user.length < longText.length)
    }

    @Test
    fun `only the last three turns are included`() {
        val history = listOf(
            PromptAssembly.Turn("USER", "FIRST_TURN_MARKER"),
            PromptAssembly.Turn("ASSISTANT", "second"),
            PromptAssembly.Turn("USER", "third"),
            PromptAssembly.Turn("ASSISTANT", "FOURTH_TURN_MARKER")
        )

        val assembled = PromptAssembly.question(
            question = "And then?",
            hits = listOf(hit(0, 1, "Some passage about renewal.")),
            history = history
        )

        assertFalse(assembled.user.contains("FIRST_TURN_MARKER"))
        assertTrue(assembled.user.contains("FOURTH_TURN_MARKER"))
    }

    @Test
    fun `summary covers the beginning and the end of a long document`() {
        val pages = (0 until 12).map { index ->
            "Page $index content. " + (1..120).joinToString(" ") { "token$it" }
        }

        val prompt = PromptAssembly.summary(pages, "medium", maxPromptTokens = 600)

        assertTrue(prompt.isPartial)
        assertTrue(prompt.user.contains("excerpted selection"))
        assertTrue(prompt.user.contains("[p.1]"))
        assertTrue(prompt.user.contains("[p.12]"))
        assertTrue(prompt.pagesUsed < 12)
    }

    @Test
    fun `summary of a short document is not marked partial`() {
        val pages = listOf("Short paragraph about the invoice.", "Second short paragraph.")

        val prompt = PromptAssembly.summary(pages, "short", maxPromptTokens = 4000)

        assertFalse(prompt.isPartial)
        assertTrue(prompt.user.contains("[p.1]"))
        assertTrue(prompt.user.contains("[p.2]"))
        assertFalse(prompt.user.contains("excerpted selection"))
    }

    @Test
    fun `empty document summary says there is no text`() {
        val prompt = PromptAssembly.summary(listOf("", "  "), "medium")
        assertEquals(0, prompt.pagesUsed)
        assertTrue(prompt.user.contains("no extractable text"))
    }
}
