package com.pdfapk.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks [WordPiece] against the ids produced by the reference implementation
 * (`transformers.AutoTokenizer.from_pretrained("sentence-transformers/all-MiniLM-L6-v2")`)
 * for the real 30,522-token vocabulary shipped in assets.
 *
 * This is the test that matters most for retrieval quality: a tokenizer that
 * is "close enough" produces plausible-looking but wrong embeddings, with no
 * error anywhere. The expected ids below were captured from HuggingFace and
 * are the ground truth, not a re-run of our own algorithm.
 */
class TokenizerFidelityTest {

    private val tokenizer: WordPiece by lazy {
        val file = File("src/main/assets/minilm_vocab.txt")
        assertTrue("vocabulary asset missing at ${file.absolutePath}", file.exists())
        file.inputStream().use { WordPiece.from(it) }
    }

    private fun ids(text: String): List<Long> {
        val encoded = tokenizer.encode(text, limit = 128)
        val out = ArrayList<Long>()
        for (i in encoded.inputIds.indices) {
            if (encoded.attentionMask[i] == 1L) out.add(encoded.inputIds[i])
        }
        return out
    }

    @Test
    fun `vocabulary loads with the expected size`() {
        assertEquals(30_522, tokenizer.size)
    }

    @Test
    fun `simple sentence matches the reference ids`() {
        assertEquals(listOf(101L, 7592L, 2088L, 102L), ids("Hello world"))
    }

    @Test
    fun `punctuation and repeated words match the reference ids`() {
        assertEquals(
            listOf(
                101L, 1996L, 25416L, 8630L, 3343L, 4473L, 4228L, 2420L,
                2005L, 1037L, 2440L, 25416L, 8630L, 1012L, 102L
            ),
            ids("The refund policy allows thirty days for a full refund.")
        )
    }

    @Test
    fun `accented words are folded exactly like the reference tokenizer`() {
        assertEquals(
            listOf(101L, 13746L, 2139L, 3900L, 24728L, 102L),
            ids("Résumé déjà vu")
        )
    }

    @Test
    fun `digits and decimal points match the reference ids`() {
        assertEquals(
            listOf(
                101L, 1999L, 6767L, 6610L, 16798L, 2549L, 2003L, 2349L,
                2006L, 1018L, 1012L, 1016L, 102L
            ),
            ids("Invoice 2024 is due on 4.2")
        )
    }

    @Test
    fun `multi piece words match the reference ids`() {
        assertEquals(
            listOf(101L, 21999L, 6309L, 24357L, 2296L, 13568L, 2420L, 102L),
            ids("encryption keys rotate every ninety days")
        )
    }

    @Test
    fun `countTokens equals the reference length without specials`() {
        assertEquals(13, tokenizer.countTokens("The refund policy allows thirty days for a full refund."))
        assertEquals(4, tokenizer.countTokens("Résumé déjà vu"))
    }
}
