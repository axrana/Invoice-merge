package com.akshansh.invoicemerger

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.max

/**
 * A single extracted word with its bounding box, in PDF point coordinates
 * with (0,0) at the top-left of the page (matching pdfplumber's convention,
 * which is what the parsing logic in [InvoiceParser] was designed and
 * validated against).
 */
data class PositionedWord(
    val text: String,
    val x0: Float,
    val x1: Float,
    val top: Float
)

private data class PositionedChar(
    val text: String,
    val x0: Float,
    val x1: Float,
    val top: Float
)

/**
 * Extracts words with bounding-box coordinates from a PDF, analogous to
 * pdfplumber's `page.extract_words()` which the parsing logic in
 * [InvoiceParser] was designed and validated against.
 *
 * Strategy (mirrors the validated Python prototype exactly):
 * 1. Collect every character with its position via writeString().
 * 2. Group characters into visual ROWS using range-extending tolerance on
 *    `top` (a character joins the current row if its top is within
 *    `rowTolerance` of the row's current running-max top — this bridges
 *    small baseline jitter, e.g. a tax-type label sitting 2-3pt lower than
 *    the rest of its row, without merging genuinely distinct rows or
 *    drifting cumulatively across a chain of close values).
 * 3. Within each row, split into WORDS on explicit space characters AND on
 *    any positional gap wider than ~60% of the previous character's width
 *    (handles cases where a PDF generator positions adjacent text with no
 *    actual space glyph between them).
 */
class WordExtractor : PDFTextStripper() {

    private val pageChars = mutableListOf<MutableList<PositionedChar>>()
    private var currentPageChars = mutableListOf<PositionedChar>()

    init {
        sortByPosition = true
    }

    /** Extracts words for every page in the document. Index 0 = page 1. */
    fun extractWordsByPage(document: PDDocument): List<List<PositionedWord>> {
        pageChars.clear()
        for (pageIndex in 0 until document.numberOfPages) {
            currentPageChars = mutableListOf()
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            val dummy = StringWriter()
            writeText(document, dummy)
            pageChars.add(currentPageChars)
        }
        return pageChars.map { chars -> buildWordsForPage(chars) }
    }

    override fun writeString(string: String, textPositions: MutableList<TextPosition>) {
        for (pos in textPositions) {
            val charText = pos.unicode ?: continue
            val x0 = pos.xDirAdj
            val x1 = x0 + pos.widthDirAdj
            val top = pos.yDirAdj - pos.heightDir
            currentPageChars.add(PositionedChar(charText, x0, x1, top))
        }
    }

    private fun buildWordsForPage(chars: List<PositionedChar>): List<PositionedWord> {
        if (chars.isEmpty()) return emptyList()

        // Step 1: group into rows using range-extending tolerance
        val sortedByTop = chars.sortedBy { it.top }
        val rows = mutableListOf<MutableList<PositionedChar>>()
        var currentRow = mutableListOf<PositionedChar>()
        var groupMaxTop: Float? = null
        val rowTolerance = 4f

        for (c in sortedByTop) {
            if (groupMaxTop == null || (c.top - groupMaxTop) <= rowTolerance) {
                currentRow.add(c)
                groupMaxTop = if (groupMaxTop == null) c.top else max(groupMaxTop, c.top)
            } else {
                rows.add(currentRow)
                currentRow = mutableListOf(c)
                groupMaxTop = c.top
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        // Step 2: within each row, split into words
        val words = mutableListOf<PositionedWord>()
        for (row in rows) {
            val sortedRow = row.sortedBy { it.x0 }
            var wordChars = mutableListOf<PositionedChar>()
            var lastEndX: Float? = null
            var lastWidth: Float? = null

            fun flush() {
                if (wordChars.isNotEmpty()) {
                    val text = wordChars.joinToString("") { it.text }
                    words.add(
                        PositionedWord(
                            text = text,
                            x0 = wordChars.first().x0,
                            x1 = wordChars.last().x1,
                            top = wordChars.minOf { it.top }
                        )
                    )
                    wordChars = mutableListOf()
                }
            }

            for (c in sortedRow) {
                if (c.text == " " || c.text.isBlank()) {
                    flush()
                    lastEndX = c.x1
                    continue
                }
                val gap = if (lastEndX != null) c.x0 - lastEndX else 0f
                val charWidth = c.x1 - c.x0
                val threshold = max((lastWidth ?: charWidth) * 0.6f, 2.5f)
                if (lastEndX != null && gap > threshold) {
                    flush()
                }
                wordChars.add(c)
                lastEndX = c.x1
                lastWidth = charWidth
            }
            flush()
        }

        return words
    }
}

