package com.pdfapk.rag

/**
 * Builds the two prompts the chain actually needs: a grounded question prompt
 * (retrieved excerpts, page labels, conversation turns) and a coverage-aware
 * summary prompt (beginning + sampled middle + end).
 *
 * The budget is enforced here rather than by slicing characters. The old
 * `take(12000)` was wrong in both directions: it wasted thousands of tokens on
 * the first pages of a 300-page report and silently dropped the rest.
 */
object PromptAssembly {

    const val GROUNDED_SYSTEM = """You are a document analyst answering questions about one PDF.
Rules:
1. Use ONLY the numbered excerpts provided. Do not use outside knowledge.
2. Cite the page of every claim in square brackets, exactly like [p.12].
3. If the excerpts do not contain the answer, reply: "I could not find that in this document." Do not guess.
4. Be concise: answer the question, then add at most two sentences of context.
5. If the question refers to something in the conversation, resolve it from the conversation."""

    const val SUMMARY_SYSTEM = """You are a document analyst. Summarise the provided document text.
Rules:
1. Cover the topic, the main argument or purpose, and the key conclusions.
2. Use bullet points for distinct takeaways when they help.
3. Do not invent content that is not in the text.
4. Write in the same language as the document."""

    /** One prior chat turn, oldest first. */
    data class Turn(val role: String, val content: String)

    data class Assembled(
        val system: String,
        val user: String,
        val usedChunks: List<Chunk>,
        val estimatedTokens: Int
    )

    data class SummaryPrompt(
        val system: String,
        val user: String,
        val pagesUsed: Int,
        val totalPages: Int,
        val estimatedTokens: Int
    ) {
        val isPartial: Boolean get() = pagesUsed < totalPages
    }

    /**
     * Assembles a grounded question prompt.
     *
     * @param maxPromptTokens budget for excerpts + history + question. The
     *   caller passes whatever is left after reserving room for the answer.
     */
    fun question(
        question: String,
        hits: List<Hit>,
        history: List<Turn> = emptyList(),
        maxPromptTokens: Int = 1600
    ): Assembled {
        val builder = StringBuilder()
        var used = TextUtils.estimateTokens(GROUNDED_SYSTEM) + TextUtils.estimateTokens(question) + 40

        val historyTurns = history.takeLast(3)
        if (historyTurns.isNotEmpty()) {
            val block = StringBuilder("Conversation so far:\n")
            for (turn in historyTurns) {
                val label = if (turn.role.equals("USER", true)) "User" else "Assistant"
                block.append(label).append(": ")
                    .append(TextUtils.truncateToTokens(turn.content, 120))
                    .append('\n')
            }
            val blockTokens = TextUtils.estimateTokens(block.toString())
            if (used + blockTokens < maxPromptTokens) {
                builder.append(block).append('\n')
                used += blockTokens
            }
        }

        val usedChunks = ArrayList<Chunk>()
        if (hits.isEmpty()) {
            builder.append("No relevant excerpts were found in the document.\n")
                .append("Reply exactly: \"I could not find that in this document.\"\n\n")
        } else {
            builder.append("Document excerpts, each labelled with its page:\n")
            val seen = HashSet<String>()
            for (hit in hits) {
                val chunk = hit.chunk
                val key = chunk.text.trim()
                if (key.isEmpty() || !seen.add(key)) continue
                val label = "[p.${chunk.pageIndex + 1}]\n"
                val body = TextUtils.truncateToTokens(chunk.text, 320)
                val cost = TextUtils.estimateTokens(label + body) + 8
                if (used + cost > maxPromptTokens) {
                    if (usedChunks.isEmpty()) {
                        // Always include at least one excerpt, truncated to fit.
                        val room = maxOf(64, maxPromptTokens - used)
                        builder.append(label)
                            .append(TextUtils.truncateToTokens(chunk.text, room))
                            .append("\n---\n")
                        usedChunks.add(chunk)
                        used = maxPromptTokens
                    }
                    break
                }
                builder.append(label).append(body).append("\n---\n")
                usedChunks.add(chunk)
                used += cost
            }
        }

        builder.append('\n')
            .append("Question: ").append(question).append('\n')
            .append("Answer using only the excerpts above and cite pages as [p.N].")

        return Assembled(
            system = GROUNDED_SYSTEM,
            user = builder.toString(),
            usedChunks = usedChunks,
            estimatedTokens = used
        )
    }

    /**
     * Assembles a summary prompt that covers the whole document instead of
     * discarding everything after the first few pages: the opening pages, an
     * evenly spaced sample of the middle, and the closing page - each labelled
     * with its page range.
     */
    fun summary(
        pages: List<String>,
        length: String = "medium",
        maxPromptTokens: Int = 4000
    ): SummaryPrompt {
        val instruction = when (length) {
            "short" -> "Summarise in 3-5 bullet points. Be extremely concise."
            "detailed" -> "Provide a detailed summary covering all major sections, key arguments, data points and conclusions, using headings."
            else -> "Summarise in a well-structured paragraph of 4-6 sentences covering the topic and key takeaways."
        }

        val nonEmpty = pages.withIndex().filter { it.value.isNotBlank() }
        if (nonEmpty.isEmpty()) {
            return SummaryPrompt(
                system = SUMMARY_SYSTEM,
                user = "The document contains no extractable text.\n\n$instruction",
                pagesUsed = 0,
                totalPages = pages.size,
                estimatedTokens = 0
            )
        }

        val budget = maxOf(400, maxPromptTokens - TextUtils.estimateTokens(SUMMARY_SYSTEM) - 80)
        val selected = LinkedHashSet<Int>()
        var used = 0
        val body = StringBuilder()

        fun addPage(index: Int, maxTotal: Int, truncate: Boolean): Boolean {
            if (index in selected) return true
            val text = pages[index]
            if (text.isBlank()) return true
            val cost = TextUtils.estimateTokens(text) + 6
            val room = maxTotal - used
            if (cost > room) {
                if (!truncate || room < 60) return false
                body.append("[p.${index + 1}]\n")
                    .append(TextUtils.truncateToTokens(text, room))
                    .append("\n---\n")
                used = maxTotal
                selected.add(index)
                return true
            }
            body.append("[p.${index + 1}]\n").append(text).append("\n---\n")
            used += cost
            selected.add(index)
            return true
        }

        val lastIndex = nonEmpty.last().index
        // Reserve room for the closing page: without it, budget pressure eats
        // the end of the document and the summary silently stops mid-way.
        val closingReserve = minOf(240, TextUtils.estimateTokens(pages[lastIndex]) + 6)
        val bodyBudget = maxOf(120, budget - closingReserve)
        val headTarget = (bodyBudget * 0.65).toInt()

        // 1. Opening pages.
        for ((index, _) in nonEmpty) {
            if (used >= headTarget) break
            if (!addPage(index, bodyBudget, truncate = false)) break
        }

        // 2. Evenly sampled middle.
        val step = maxOf(1, nonEmpty.size / 8)
        var i = step
        while (i < nonEmpty.size - 1) {
            if (used >= bodyBudget) break
            if (!addPage(nonEmpty[i].index, bodyBudget, truncate = false)) break
            i += step
        }

        // 3. Closing page (truncated to whatever is left, if needed).
        addPage(lastIndex, budget, truncate = true)

        val coverageNote = if (selected.size < nonEmpty.size) {
            "Note: this is an excerpted selection of ${selected.size} of ${nonEmpty.size} text pages " +
                "(beginning, sampled middle, end). Say so if the summary cannot cover the whole document.\n\n"
        } else ""

        val user = buildString {
            append(coverageNote)
            append("Document text:\n")
            append(body)
            append('\n')
            append(instruction)
        }

        return SummaryPrompt(
            system = SUMMARY_SYSTEM,
            user = user,
            pagesUsed = selected.size,
            totalPages = nonEmpty.size,
            estimatedTokens = used + TextUtils.estimateTokens(coverageNote) + 40
        )
    }
}
