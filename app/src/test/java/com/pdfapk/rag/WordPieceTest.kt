package com.pdfapk.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordPieceTest {

    private val vocab = mapOf(
        "[PAD]" to 0,
        "[UNK]" to 100,
        "[CLS]" to 101,
        "[SEP]" to 102,
        "hello" to 7592,
        "world" to 2088,
        "he" to 2022,
        "##llo" to 8283,
        "##s" to 2015,
        "!" to 999
    )

    private val tokenizer = WordPiece(vocab, maxLength = 8)

    @Test
    fun `known words map to their ids`() {
        assertEquals(listOf(7592, 2088), tokenizer.tokenize("Hello world"))
    }

    @Test
    fun `unknown whole word falls back to subword pieces`() {
        // "hellos" is not in the vocabulary: longest match is "hello" + "##s".
        assertEquals(listOf(7592, 2015), tokenizer.tokenize("hellos"))
    }

    @Test
    fun `completely unknown word becomes UNK`() {
        assertEquals(listOf(100), tokenizer.tokenize("zzzz"))
    }

    @Test
    fun `punctuation is its own token`() {
        assertEquals(listOf(7592, 999), tokenizer.tokenize("hello!"))
    }

    @Test
    fun `encode adds specials and pads to the fixed length`() {
        val encoded = tokenizer.encode("hello world")

        assertEquals(8, encoded.length)
        assertEquals(101L, encoded.inputIds[0])          // [CLS]
        assertEquals(7592L, encoded.inputIds[1])
        assertEquals(2088L, encoded.inputIds[2])
        assertEquals(102L, encoded.inputIds[3])          // [SEP]
        assertEquals(0L, encoded.inputIds[4])            // [PAD]
        assertEquals(
            listOf(1, 1, 1, 1, 0, 0, 0, 0),
            encoded.attentionMask.map { it.toInt() }
        )
        assertTrue(encoded.tokenTypeIds.all { it == 0L })
    }

    @Test
    fun `long input is truncated to the window`() {
        val text = (1..200).joinToString(" ") { "hello" }
        val encoded = tokenizer.encode(text)

        assertEquals(8, encoded.length)
        // CLS + 6 pieces + SEP fill the window exactly.
        assertEquals(102L, encoded.inputIds[7])
        assertTrue(encoded.attentionMask.all { it == 1L })
    }

    @Test
    fun `countTokens counts pieces without specials`() {
        assertEquals(2, tokenizer.countTokens("hello world"))
        assertEquals(2, tokenizer.countTokens("hellos"))
        assertEquals(0, tokenizer.countTokens(""))
    }
}
