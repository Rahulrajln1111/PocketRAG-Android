package com.pdfapk.llm

/**
 * Reusable prompt templates for the non-retrieval actions (summarise a
 * passage, explain, translate). Question answering lives in
 * `com.pdfapk.rag.PromptAssembly`, which injects retrieved excerpts with page
 * labels instead of truncating the document.
 *
 * All templates use the ChatML format expected by Qwen 2.5 Instruct.
 * Language codes follow ISO 639-1.
 */
object PromptTemplates {

    // ── System prompts ──────────────────────────────────────────────────────

    private const val SYSTEM_SUMMARIZE = """You are a professional document analyst. Your task is to produce clear, concise, and accurate summaries of document content. Follow these rules:
1. Identify the core topic, purpose, and key takeaways.
2. Use bullet points for clarity when appropriate.
3. Maintain a neutral, factual tone.
4. Output in the same language as the document content.
5. Do NOT include meta commentary like "Here is a summary" — just give the summary."""

    private const val SYSTEM_EXPLAIN = """You are an expert tutor who explains complex topics with clarity and practical examples. Follow these rules:
1. First, briefly restate the concept in simple terms.
2. Provide a concrete, real-world example that illustrates the concept.
3. If applicable, relate the example back to the original document context.
4. Use analogies where helpful.
5. Output in the same language as the document content.
6. Be thorough but avoid unnecessary jargon."""

    private const val SYSTEM_TRANSLATE = """You are a professional translator with native-level fluency in all languages. Follow these rules:
1. Translate the provided text accurately while preserving the original meaning, tone, and nuance.
2. Adapt idioms, cultural references, and metaphors appropriately for the target language.
3. Maintain the professional register and formatting of the original text.
4. If the text contains technical terms, use the standard equivalent in the target language.
5. Output ONLY the translation — no explanations, notes, or meta commentary."""

    // ── Prompt builders ─────────────────────────────────────────────────────

    /**
     * Build a prompt for summarising a PDF.
     * @param pdfText The extracted text from the PDF.
     * @param length "short" (3 bullets), "medium" (paragraph), or "detailed"
     */
    fun summarize(pdfText: String, length: String = "medium"): Pair<String, String> {
        val lengthInstruction = when (length) {
            "short" -> "Summarize in 3-5 bullet points. Be extremely concise."
            "detailed" -> "Provide a detailed summary covering all major sections, key arguments, data points, and conclusions. Use sections with headings."
            else -> "Summarize in a well-structured paragraph of 4-6 sentences, covering the main topic and key takeaways."
        }
        val userPrompt = buildString {
            appendLine("Document content:")
            appendLine("---")
            appendLine(pdfText.take(12000))  // context window limit
            appendLine("---")
            appendLine()
            appendLine(lengthInstruction)
        }
        return SYSTEM_SUMMARIZE to userPrompt
    }

    /**
     * Build a prompt for explaining a specific passage with examples.
     * @param passage The specific text to explain.
     */
    fun explainWithExample(passage: String): Pair<String, String> {
        val userPrompt = buildString {
            appendLine("Explain the following passage from the document and provide a concrete real-world example to illustrate it:")
            appendLine()
            appendLine("\"$passage\"")
        }
        return SYSTEM_EXPLAIN to userPrompt
    }

    /**
     * Build a prompt for translating text into a target language.
     * @param text The text to translate.
     * @param targetLanguage The target language name (e.g. "Hindi", "Spanish", "Arabic").
     * @param formality "professional" or "casual"
     */
    fun translate(
        text: String,
        targetLanguage: String,
        formality: String = "professional"
    ): Pair<String, String> {
        val formalityInstruction = when (formality) {
            "casual" -> "Use a conversational, friendly tone suitable for informal contexts."
            else -> "Use a formal, professional tone suitable for business or academic contexts. Use polite forms and proper register."
        }
        val userPrompt = buildString {
            appendLine("Translate the following text to $targetLanguage.")
            appendLine(formalityInstruction)
            appendLine()
            appendLine("Text to translate:")
            appendLine("---")
            appendLine(text)
            appendLine("---")
            appendLine()
            appendLine("Output ONLY the translation, nothing else.")
        }
        return SYSTEM_TRANSLATE to userPrompt
    }

    /**
     * Build a prompt for asking about a specific highlighted part.
     */
    fun explainSelectedText(selectedText: String, question: String = ""): Pair<String, String> {
        val query = if (question.isBlank()) {
            "Explain this passage and provide practical examples."
        } else {
            question
        }
        val userPrompt = buildString {
            appendLine("Selected text from the document:")
            appendLine("\"$selectedText\"")
            appendLine()
            appendLine(query)
        }
        return SYSTEM_EXPLAIN to userPrompt
    }
}
