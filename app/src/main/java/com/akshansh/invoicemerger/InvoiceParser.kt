package com.akshansh.invoicemerger

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts structured invoice data from Amazon invoice PDFs using
 * coordinate-based (x/y position) parsing rather than plain linear text.
 *
 * This approach is necessary because Amazon invoice PDFs vary in how their
 * two-column header (seller info | billing/shipping info) gets extracted:
 * sometimes the columns are cleanly separated in the underlying text,
 * sometimes they're interleaved on the same visual line. Coordinate-based
 * splitting (by x-position) handles both cases uniformly. The same applies
 * to the line-items table, where tax-type labels can sit a few points off
 * the row's main baseline, and net-amount/tax-rate/tax-type tokens can be
 * concatenated with no separator depending on the PDF generator used for
 * that particular invoice template version.
 *
 * Also handles PDFs that contain MULTIPLE complete invoices (e.g. a single
 * order fulfilled by multiple sellers, where Amazon bundles one PDF per
 * seller into a single download) by detecting which pages start a new
 * invoice (presence of their own "Invoice Number :" field) versus which
 * pages are a continuation of the previous invoice (e.g. a trailing page
 * with just the signatory block and disclaimer text).
 */
class InvoiceParser(private val context: Context) {

    private val headerTokens = setOf(
        "Sl.", "Description", "Qty", "No", "Unit", "Net", "Tax", "Total",
        "Price", "Amount", "Rate", "Type", "AmountRateType", "AmountAmount"
    )

    fun parseAllInvoices(uri: Uri): List<ParsedInvoice> {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw ParseException("Could not open file: $uri")

        inputStream.use { stream ->
            PDDocument.load(stream).use { document ->
                val extractor = WordExtractor()
                val wordsByPage = extractor.extractWordsByPage(document)
                val rawPages = wordsByPage.map { words -> splitPageIntoColumns(words) }

                data class MutablePage(
                    var left: String,
                    var right: String,
                    var restWords: MutableList<PositionedWord>,
                    val descriptionMaxX: Float,
                    val totalMinX: Float
                )

                val invoicePages = mutableListOf<MutablePage>()
                for (page in rawPages) {
                    val hasInvoiceNumber = Regex("Invoice Number\\s*:\\s*[\\w\\-]+").containsMatchIn(page.right)
                    if (hasInvoiceNumber || invoicePages.isEmpty()) {
                        invoicePages.add(
                            MutablePage(
                                page.left, page.right, page.restWords.toMutableList(),
                                page.descriptionMaxX, page.totalMinX
                            )
                        )
                    } else {
                        val prev = invoicePages.last()
                        if (page.left.isNotBlank()) prev.left = prev.left + "\n" + page.left
                        if (page.right.isNotBlank()) prev.right = prev.right + "\n" + page.right
                        prev.restWords.addAll(page.restWords)
                        // keep the first page's column bounds (a continuation
                        // page has no table header of its own to derive bounds from)
                    }
                }

                return invoicePages.map { page ->
                    val header = parseHeaderFields(page.left, page.right)
                    val items = parseLineItemsFromWords(page.restWords, page.descriptionMaxX, page.totalMinX)
                    val (txnId, dateTime, mode) = parsePaymentFields(page.restWords)
                    header.copy(
                        items = items,
                        paymentTransactionId = txnId,
                        paymentDateTime = dateTime,
                        modeOfPayment = mode
                    )
                }
            }
        }
    }

    private data class PageColumns(
        val left: String,
        val right: String,
        val restWords: List<PositionedWord>,
        val descriptionMaxX: Float,
        val totalMinX: Float
    )

    /**
     * Derives table column boundaries from THIS PAGE's own header row,
     * rather than using fixed pixel constants. Amazon's invoice table
     * column widths shift between invoices (the Description column appears
     * to auto-size based on content), so a fixed boundary that works for
     * one invoice can misclassify tokens (e.g. unit price wrongly read as
     * part of the description) on another.
     */
    private fun getDynamicColumnBounds(
        words: List<PositionedWord>,
        defaultDescMax: Float = 335f,
        defaultTotalMin: Float = 510f
    ): Pair<Float, Float> {
        val descWord = words.firstOrNull { it.text == "Description" } ?: return defaultDescMax to defaultTotalMin
        val headerTop = descWord.top
        val headerWords = words.filter { kotlin.math.abs(it.top - headerTop) <= 12f }

        val unitWord = headerWords.firstOrNull { it.text == "Unit" }
        val totalWord = headerWords.firstOrNull { it.text == "Total" }

        val descriptionMaxX = unitWord?.let { it.x0 - 5f } ?: defaultDescMax
        val totalMinX = totalWord?.let { it.x0 - 5f } ?: defaultTotalMin

        return descriptionMaxX to totalMinX
    }

    private fun splitPageIntoColumns(words: List<PositionedWord>, splitX: Float = 300f): PageColumns {
        val tableStartTop = words.firstOrNull { it.text == "Description" }?.top ?: 99999f
        val cutoff = tableStartTop - 8f

        val (descriptionMaxX, totalMinX) = getDynamicColumnBounds(words)

        val headerWords = words.filter { it.top < cutoff }
        val restWords = words.filter { it.top >= cutoff }

        val headerRows = groupWordsByRow(headerWords)

        val leftLines = mutableListOf<String>()
        val rightLines = mutableListOf<String>()
        for (row in headerRows) {
            val leftWords = row.filter { it.x0 < splitX }.sortedBy { it.x0 }
            val rightWords = row.filter { it.x0 >= splitX }.sortedBy { it.x0 }
            if (leftWords.isNotEmpty()) leftLines.add(leftWords.joinToString(" ") { it.text })
            if (rightWords.isNotEmpty()) rightLines.add(rightWords.joinToString(" ") { it.text })
        }

        return PageColumns(
            leftLines.joinToString("\n"),
            rightLines.joinToString("\n"),
            restWords,
            descriptionMaxX,
            totalMinX
        )
    }

    private fun groupWordsByRow(words: List<PositionedWord>, tolerance: Float = 4f): List<List<PositionedWord>> {
        val sorted = words.sortedBy { it.top }
        val rows = mutableListOf<MutableList<PositionedWord>>()
        var current = mutableListOf<PositionedWord>()
        var groupMaxTop: Float? = null

        for (w in sorted) {
            if (groupMaxTop == null || (w.top - groupMaxTop) <= tolerance) {
                current.add(w)
                groupMaxTop = if (groupMaxTop == null) w.top else max(groupMaxTop, w.top)
            } else {
                rows.add(current)
                current = mutableListOf(w)
                groupMaxTop = w.top
            }
        }
        if (current.isNotEmpty()) rows.add(current)
        return rows
    }

    /**
     * Extracts Payment Transaction ID, Date & Time, and Mode of Payment
     * from the footer region. Handles both layouts seen in real invoices:
     * labels and values all on one row, or labels on one row with values
     * wrapping to the next. Some invoices (notably ones with a ₹0.00 total,
     * e.g. fully-discounted items) omit this section entirely, in which
     * case all three fields are returned as null.
     *
     * Returns (transactionId, dateTime, modeOfPayment).
     */
    private fun parsePaymentFields(words: List<PositionedWord>): Triple<String?, String?, String?> {
        val paymentWord = words.firstOrNull { w ->
            w.text == "Payment" && words.any { it.text == "Transaction" && kotlin.math.abs(it.top - w.top) < 2f && it.x0 > w.x0 }
        } ?: return Triple(null, null, null)

        val labelRowTop = paymentWord.top
        val relevantWords = words.filter { it.top >= labelRowTop - 1f && it.top <= labelRowTop + 12f }

        val dateLabel = relevantWords.firstOrNull { it.text == "Date" && kotlin.math.abs(it.top - labelRowTop) < 2f }
        val invoiceLabel = relevantWords.firstOrNull { it.text == "Invoice" && kotlin.math.abs(it.top - labelRowTop) < 2f }
        val modeLabel = relevantWords.firstOrNull { it.text == "Mode" && kotlin.math.abs(it.top - labelRowTop) < 2f }

        val colBounds = mutableListOf(paymentWord.x0)
        var dateColIdx = -1
        var invoiceColIdx = -1
        var modeColIdx = -1
        dateLabel?.let { dateColIdx = colBounds.size; colBounds.add(it.x0) }
        invoiceLabel?.let { invoiceColIdx = colBounds.size; colBounds.add(it.x0) }
        modeLabel?.let { modeColIdx = colBounds.size; colBounds.add(it.x0) }
        colBounds.add(9999f)

        val labelTokens = setOf(
            "Payment", "Transaction", "ID:", "Date", "&", "Time:",
            "Invoice", "Value:", "Mode", "of", "Payment:"
        )

        fun wordsInCol(idx: Int): String? {
            if (idx < 0 || idx >= colBounds.size - 1) return null
            val lo = colBounds[idx]
            val hi = colBounds[idx + 1]
            val colWords = relevantWords
                .filter { it.x0 >= lo && it.x0 < hi && it.text !in labelTokens }
                .sortedWith(compareBy({ it.top }, { it.x0 }))
            return colWords.joinToString(" ") { it.text }.ifEmpty { null }
        }

        val txnId = wordsInCol(0)
        val dateTime = wordsInCol(dateColIdx)
        val mode = wordsInCol(modeColIdx)

        return Triple(txnId, dateTime, mode)
    }

    private fun parseHeaderFields(left: String, right: String): ParsedInvoice {
        val sellerMatch = Regex("Sold By\\s*:\\s*\\n?([^\\n]+)").find(left)
        val sellerName = sellerMatch?.groupValues?.get(1)?.trim()

        val panNo = Regex("PAN No:\\s*(\\w+)").find(left)?.groupValues?.get(1)
        val gstNo = Regex("GST Registration No:\\s*(\\w+)").find(left)?.groupValues?.get(1)
        val orderNumber = Regex("Order Number:\\s*([\\w\\-]+)").find(left)?.groupValues?.get(1)
            ?: throw ParseException("Could not find Order Number")
        val orderDate = Regex("Order Date:\\s*([\\d.]+)").find(left)?.groupValues?.get(1) ?: ""

        val sellerLinesRaw = left.split("\n")
        val sellerAddressLines = if (sellerName != null) {
            val startIdx = sellerLinesRaw.indexOfFirst { it.contains(sellerName) } + 1
            val endIdx = sellerLinesRaw.indexOfFirst { it.startsWith("PAN No") }
            if (startIdx > 0 && endIdx > startIdx) {
                sellerLinesRaw.subList(startIdx, endIdx)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != "*" }
            } else emptyList()
        } else emptyList()

        val invoiceNumber = Regex("Invoice Number\\s*:\\s*([\\w\\-]+)").find(right)?.groupValues?.get(1)
            ?: throw ParseException("Could not find Invoice Number")
        val invoiceDetails = Regex("Invoice Details\\s*:\\s*([\\w\\-]+)").find(right)?.groupValues?.get(1) ?: ""
        val invoiceDate = Regex("Invoice Date\\s*:\\s*([\\d.]+)").find(right)?.groupValues?.get(1) ?: orderDate
        val placeOfSupply = Regex("Place of supply:\\s*(\\w+)").find(right)?.groupValues?.get(1)
        val placeOfDelivery = Regex("Place of delivery:\\s*(\\w+)").find(right)?.groupValues?.get(1)

        val billingMatch = Regex(
            "Billing Address\\s*:\\s*\\n(.*?)\\nState/UT Code:\\s*(\\w+)", RegexOption.DOT_MATCHES_ALL
        ).find(right)
        val billingLines = billingMatch?.groupValues?.get(1)
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val billingName = billingLines.firstOrNull()
        val billingAddressLines = billingLines.drop(1)
        val billingStateCode = billingMatch?.groupValues?.get(2)

        val shippingMatch = Regex(
            "Shipping Address\\s*:\\s*\\n(.*?)\\nState/UT Code:\\s*(\\w+)", RegexOption.DOT_MATCHES_ALL
        ).find(right)
        val shippingLines = shippingMatch?.groupValues?.get(1)
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val shippingName = shippingLines.firstOrNull()
        val shippingAddressLines = shippingLines.drop(1)
        val shippingStateCode = shippingMatch?.groupValues?.get(2)

        return ParsedInvoice(
            orderNumber = orderNumber,
            orderDate = orderDate,
            invoiceNumber = invoiceNumber,
            invoiceDetails = invoiceDetails,
            invoiceDate = invoiceDate,
            sellerName = sellerName,
            sellerAddressLines = sellerAddressLines,
            panNo = panNo,
            gstNo = gstNo,
            billingName = billingName,
            billingAddressLines = billingAddressLines,
            billingStateCode = billingStateCode,
            shippingName = shippingName,
            shippingAddressLines = shippingAddressLines,
            shippingStateCode = shippingStateCode,
            placeOfSupply = placeOfSupply,
            placeOfDelivery = placeOfDelivery,
            paymentTransactionId = null,
            paymentDateTime = null,
            modeOfPayment = null,
            items = emptyList()
        )
    }

    private fun parseLineItemsFromWords(
        restWords: List<PositionedWord>,
        descriptionColMaxX: Float = 335f,
        totalColMinX: Float = 510f
    ): List<InvoiceLineItem> {
        var rows = groupWordsByRow(restWords).map { row -> row.sortedBy { it.x0 } }

        rows = rows.filter { row -> row.map { it.text }.toSet().let { !headerTokens.containsAll(it) } }

        val totalRowIdx = rows.indexOfFirst { row -> row.any { it.text.trim() == "TOTAL:" } }
        if (totalRowIdx >= 0) {
            rows = rows.subList(0, totalRowIdx)
        }

        val blocks = mutableListOf<MutableList<List<PositionedWord>>>()
        for (row in rows) {
            val firstWord = row.firstOrNull()
            val isItemStart = firstWord != null &&
                firstWord.x0 < 56f &&
                Regex("^\\d+$").matches(firstWord.text.trim())
            if (isItemStart) {
                blocks.add(mutableListOf(row))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(row)
            }
        }

        return blocks.mapNotNull { block -> parseItemBlock(block, descriptionColMaxX, totalColMinX) }
    }

    private val taxTypes = setOf("CGST", "SGST", "IGST", "UTGST")

    /**
     * Classifies a token by CONTENT PATTERN first (money, rate%, tax type,
     * ASIN, HSN), falling back to position-based classification only for
     * plain integers/text that don't match any structured pattern.
     *
     * This is deliberately more robust than pure position-based
     * classification: the exact column x-position where "description ends
     * and the data columns begin" can shift slightly between invoices, or
     * even between text-extraction libraries reading the exact same PDF
     * (a real failure mode found in testing - PdfBox-Android and the
     * Python prototype's library reported a unit price's x-position
     * differently by a few points, enough to tip a fixed threshold the
     * wrong way). The CONTENT of a unit price (₹X.XX) or ASIN (10-char
     * code) is unambiguous no matter where it visually sits, so content
     * is checked first; position is only used as a tiebreaker for bare
     * integers, which are genuinely ambiguous (could be Qty, or could be
     * part of a product name like "12 In 1 Power Breakfast").
     */
    private fun classifyToken(text: String, x0: Float, descriptionColMaxX: Float): Pair<String, String> {
        if (Regex("^₹[\\d,]+\\.\\d{2}$").matches(text)) return "amount" to text
        Regex("^(\\d+(?:\\.\\d+)?)%$").find(text)?.let { return "rate" to it.groupValues[1] }
        if (text in taxTypes) return "type" to text
        if (Regex("^[A-Z0-9]{10}$").matches(text)) return "asin" to text
        Regex("^HSN:(\\d+)$").find(text)?.let { return "hsn" to it.groupValues[1] }
        Regex("^(\\d+)$").find(text)?.let {
            return if (x0 >= descriptionColMaxX) "int" to it.groupValues[1] else "description" to text
        }
        return if (x0 < descriptionColMaxX) "description" to text else "unknown" to text
    }

    /**
     * Splits a token that merges multiple fields with no separator
     * (e.g. "₹670.482.5%CGST" or "B01IBTD4HC₹36.00") into atomic
     * content-classified pieces.
     */
    private fun splitSquishedToken(text: String): List<Pair<String, String>> {
        val parts = mutableListOf<Pair<String, String>>()
        val pattern = Regex(
            "(₹[\\d,]+\\.\\d{2})" +
                "|(\\d+(?:\\.\\d+)?%)" +
                "|([A-Z]{3,6}(?![a-zA-Z0-9]))" +
                "|([A-Z0-9]{10}(?![a-zA-Z0-9]))" +
                "|(HSN:\\d+)" +
                "|(\\d+(?!\\.\\d|\\d*%))"
        )
        for (m in pattern.findAll(text)) {
            when {
                m.groups[1] != null -> parts.add(m.groupValues[1] to "amount")
                m.groups[2] != null -> parts.add(m.groupValues[2].trimEnd('%') to "rate")
                m.groups[3] != null -> parts.add(m.groupValues[3] to "type")
                m.groups[4] != null -> parts.add(m.groupValues[4] to "asin")
                m.groups[5] != null -> parts.add(m.groupValues[5] to "hsn")
                m.groups[6] != null -> parts.add(m.groupValues[6] to "int")
            }
        }
        return parts
    }

    private fun parseItemBlock(
        rows: List<List<PositionedWord>>,
        descriptionColMaxX: Float,
        totalColMinX: Float = 510f
    ): InvoiceLineItem? {
        var slNo: String? = null
        val descTokens = mutableListOf<String>()
        val dataTokens = mutableListOf<Triple<String, String, Float>>() // value, kind, x0
        var hsn: String? = null
        var asin: String? = null

        for (row in rows) {
            for (w in row) {
                val text = w.text.trim()
                if (text.isEmpty()) continue

                if (slNo == null && w.x0 < 56f && Regex("^\\d+$").matches(text)) {
                    slNo = text
                    continue
                }

                val (kind, value) = classifyToken(text, w.x0, descriptionColMaxX)

                when (kind) {
                    "description" -> descTokens.add(value)
                    "asin" -> if (asin == null) asin = value
                    "hsn" -> hsn = value
                    "unknown" -> {
                        // Could be a squished multi-field token (e.g.
                        // "B01IBTD4HC₹36.00") not caught by single-pattern
                        // matching - try splitting it.
                        val split = splitSquishedToken(text)
                        if (split.isNotEmpty()) {
                            for ((pieceVal, pieceKind) in split) {
                                when (pieceKind) {
                                    "asin" -> if (asin == null) asin = pieceVal
                                    "hsn" -> hsn = pieceVal
                                    else -> dataTokens.add(Triple(pieceVal, pieceKind, w.x0))
                                }
                            }
                        }
                        // else: truly unrecognized text in the data zone - ignore
                    }
                    else -> dataTokens.add(Triple(value, kind, w.x0))
                }
            }
        }

        if (slNo == null || asin == null) return null

        var description = descTokens.joinToString(" ").trim()
        description = Regex("\\s+").replace(description, " ")
        description = Regex("\\s*\\(\\s*\\)\\s*$").replace(description, "")
        description = Regex("\\s*\\|\\s*$").replace(description, "").trim()

        val amounts = dataTokens.filter { it.second == "amount" }.map { it.first to it.third }
        val ints = dataTokens.filter { it.second == "int" }.map { it.first }
        val rates = dataTokens.filter { it.second == "rate" }.map { it.first }
        val types = dataTokens.filter { it.second == "type" }.map { it.first }

        if (amounts.size < 3 || ints.isEmpty() || rates.isEmpty() || types.isEmpty()) return null

        val totalCandidatesIdx = amounts.indices.filter { i -> amounts[i].second >= totalColMinX }
        val totalIdx = if (totalCandidatesIdx.isEmpty()) {
            amounts.size - 1
        } else {
            totalCandidatesIdx.maxByOrNull { i -> amounts[i].second }!!
        }

        val totalAmountStr = amounts[totalIdx].first
        val remainingAmounts = amounts.filterIndexed { i, _ -> i != totalIdx }
        val remainingSorted = remainingAmounts.sortedBy { it.second }
        val remainingValues = remainingSorted.map { it.first }

        if (remainingValues.size < 2) return null

        fun parseAmt(s: String) = s.replace("₹", "").replace(",", "").toDouble()

        val unitPrice = parseAmt(remainingValues[0])
        val netAmount = parseAmt(remainingValues[1])
        val taxAmountValues = remainingValues.drop(2)
        val totalAmount = parseAmt(totalAmountStr)

        val n = min(rates.size, min(types.size, taxAmountValues.size))
        val taxRows = (0 until n).map { i ->
            TaxRow(
                rate = rates[i].toDouble(),
                type = types[i],
                amount = parseAmt(taxAmountValues[i])
            )
        }
        if (taxRows.isEmpty()) return null

        return InvoiceLineItem(
            slNo = slNo,
            description = description,
            asin = asin,
            hsn = hsn,
            unitPrice = unitPrice,
            qty = ints[0].toInt(),
            netAmount = netAmount,
            taxRows = taxRows,
            totalAmount = totalAmount
        )
    }
}

class ParseException(message: String) : Exception(message)
