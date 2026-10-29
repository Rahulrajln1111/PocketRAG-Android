package com.pdfapk.rag

/**
 * Turns extracted PDF pages into retrievable chunks.
 *
 * Design rules, all of them driven by citation quality rather than chunk
 * counts:
 *
 * 1. **Never cross a page boundary.** A chunk that spans pages 4-5 cannot be
 *    cited as `[p.4]` or `[p.5]` without lying, and the PDF viewer can only
 *    jump to one page.
 * 2. **Never split mid-sentence if it can be avoided.** Boundaries are placed
 *    at line breaks, which is where PDFs already break paragraphs.
 * 3. **Overlap by whole lines.** The overlap is what saves a chunk when the
 *    answer straddles the boundary; 30-60 words is enough and keeps the index
 *    from doubling in size.
 * 4. **Headings are chunk boundaries.** Section headings are the cheapest
 *    semantic structure a PDF gives you, so a heading starts a new chunk and
 *    is prepended to the one it introduces.
 * 5. **Repeated headers/footers are removed first.** Otherwise the page
 *    number and the company name appear in every chunk and dominate both
 *    BM25 and the embedding.
 */
class Chunker(
    private val targetWords: Int = 220,
    private val overlapWords: Int = 40,
    private val minWords: Int = 20,
    private val maxHeadingWords: Int = 14
) {

    private data class Line(
        val text: String,
        val start: Int,
        val end: Int,
        val isHeading: Boolean,
        val words: Int
    )

    /**
     * Cleans every page and strips lines that repeat at the top or bottom of
     * most pages (running headers, footers, page numbers, watermarks).
     */
    fun cleanPages(pages: List<String>): List<String> {
        val cleaned = pages.map { TextUtils.cleanPage(it) }
        if (cleaned.size < 3) return cleaned

        val edgeLines = cleaned.map { page ->
            val lines = page.split('\n')
            val first = lines.indexOfFirst { it.isNotBlank() }
            val last = lines.indexOfLast { it.isNotBlank() }
            listOfNotNull(
                if (first >= 0) lines[first] else null,
                if (last >= 0) lines[last] else null
            )
        }
        val threshold = maxOf(2, (cleaned.size * 0.4).toInt())
        val counts = edgeLines.flatten()
            .map { TextUtils.fold(it).trim() }
            .groupingBy { it }
            .eachCount()

        if (counts.values.none { it >= threshold }) return cleaned

        return cleaned.map { page ->
            val lines = page.split('\n')
            lines.filterIndexed { index, line ->
                val isEdge = index == lines.indexOfFirst { it.isNotBlank() } ||
                    index == lines.indexOfLast { it.isNotBlank() }
                val repeated = (counts[TextUtils.fold(line).trim()] ?: 0) >= threshold
                !(isEdge && repeated)
            }.joinToString("\n").trim()
        }
    }

    /** Cleans the pages and returns the chunks of the whole document. */
    fun chunk(pages: List<String>): List<Chunk> {
        val cleaned = cleanPages(pages)
        var nextId = 0
        val out = ArrayList<Chunk>()

        for ((pageIndex, page) in cleaned.withIndex()) {
            if (page.isBlank()) continue
            out += chunkPage(page, pageIndex, startId = nextId)
            nextId = out.size
        }
        return out
    }

    private fun chunkPage(page: String, pageIndex: Int, startId: Int): List<Chunk> {
        val lines = linesOf(page)
        if (lines.isEmpty()) return emptyList()

        val chunks = ArrayList<Chunk>()
        var pending = ArrayList<Line>()
        var pendingWords = 0
        var heading: String? = null

        fun flush() {
            if (pending.isEmpty()) return
            val words = pending.sumOf { it.words }
            if (words < 3) {
                pending = ArrayList()
                pendingWords = 0
                return
            }
            chunks += Chunk(
                id = startId + chunks.size,
                pageIndex = pageIndex,
                text = pending.joinToString("\n") { it.text },
                heading = heading,
                charStart = pending.first().start,
                charEnd = pending.last().end
            )
            // Keep trailing lines as overlap for the next chunk.
            var keep = ArrayList<Line>()
            var kept = 0
            for (line in pending.asReversed()) {
                if (kept + line.words > overlapWords && keep.isNotEmpty()) break
                keep.add(0, line)
                kept += line.words
            }
            pending = keep
            pendingWords = kept
        }

        // A single PDF line can hold a whole paragraph (common after text
        // extraction). Break those into word windows first, otherwise one line
        // becomes one giant chunk and the "overlap" duplicates it verbatim.
        val segments = lines.flatMap { explode(it) }

        for (line in segments) {
            if (line.isHeading) {
                // A heading ends the previous section and starts a new chunk.
                if (pendingWords >= minWords) flush()
                heading = line.text
            }
            pending.add(line)
            pendingWords += line.words
            if (pendingWords >= targetWords) flush()
        }

        // Tail: merge a tiny last chunk into the previous one on this page.
        val tailWords = pending.sumOf { it.words }
        if (pending.isNotEmpty() && tailWords < minWords && chunks.isNotEmpty()) {
            val previous = chunks.removeAt(chunks.size - 1)
            val merged = Chunk(
                id = previous.id,
                pageIndex = previous.pageIndex,
                text = (previous.text + "\n" + pending.joinToString("\n") { it.text }).trim(),
                heading = previous.heading,
                charStart = previous.charStart,
                charEnd = pending.last().end
            )
            chunks += merged
        } else if (pending.isNotEmpty()) {
            flush()
        }
        return chunks
    }

    /**
     * Splits an over-long line into pieces of at most [targetWords] words,
     * keeping character offsets so citations stay accurate.
     */
    private fun explode(line: Line): List<Line> {
        if (line.words <= targetWords) return listOf(line)
        val matches = Regex("\\S+").findAll(line.text).toList()
        if (matches.isEmpty()) return listOf(line)
        val out = ArrayList<Line>(matches.size / targetWords + 1)
        var index = 0
        while (index < matches.size) {
            val end = minOf(index + targetWords, matches.size)
            val startChar = matches[index].range.first
            val endChar = matches[end - 1].range.last + 1
            out.add(
                Line(
                    text = line.text.substring(startChar, endChar),
                    start = line.start + startChar,
                    end = line.start + endChar,
                    isHeading = false,
                    words = end - index
                )
            )
            index = end
        }
        return out
    }

    /** Splits a page into non-empty lines with offsets and heading flags. */
    private fun linesOf(page: String): List<Line> {
        val out = ArrayList<Line>()
        var offset = 0
        for (raw in page.split('\n')) {
            val start = offset
            offset += raw.length + 1
            val text = raw.trim()
            if (text.isEmpty()) continue
            val words = TextUtils.words(text).size
            out += Line(
                text = text,
                start = start,
                end = start + raw.length,
                isHeading = isHeading(text, words),
                words = words
            )
        }
        return out
    }

    /**
     * Heuristic heading detection. PDFs carry no reliable style information
     * once the text is extracted, so this keys on shapes that are almost
     * always headings: numbered sections, ALL CAPS lines and short
     * title-case lines that do not end in sentence punctuation.
     */
    private fun isHeading(text: String, words: Int): Boolean {
        if (words == 0 || words > maxHeadingWords) return false
        if (text.length > 90) return false
        if (text.last() in ".,;:!?") return false
        if (NumberedSection.matches(text)) return true
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        // Single-word all-caps lines are headings too ("INTRODUCTION",
        // "APPENDIX"); requiring two words missed the most common case.
        return letters == letters.uppercase()
    }

    private companion object {
        val NumberedSection = Regex("^(\\d+(\\.\\d+)*|[IVXLC]+)\\.?\\s+\\p{L}.*")
    }
}
