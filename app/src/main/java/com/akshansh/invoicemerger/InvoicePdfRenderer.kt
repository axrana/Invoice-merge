package com.akshansh.invoicemerger

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.OutputStream

/**
 * Renders a [MergedInvoice] to a PDF replicating Amazon's tax invoice layout:
 * A4 page size, amazon.in logo top-left, title top-right, seller/billing/
 * shipping 2-column block, bordered line-items table with split CGST/SGST
 * tax rows, grand total row, and a footer page with signatory + amount in
 * words + payment details table + legal disclaimer text.
 *
 * Coordinates are in PDF points (1/72 inch). A4 = 595 x 842 pts.
 */
class InvoicePdfRenderer(private val context: Context) {

    companion object {
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val MARGIN_LEFT = 42.75f
        const val MARGIN_RIGHT = 42.75f
        const val MARGIN_TOP = 30f
        const val MARGIN_BOTTOM = 40f
        const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
    }

    // ---- Bundled fonts replicating Amazon's exact invoice typography ----
    // Amazon's invoices use Helvetica (labels/text) and Playfair Display
    // (all numeric/monetary table values). Helvetica itself isn't freely
    // bundleable, so Nimbus Sans is used as a metric-compatible substitute
    // (GPL/AGPL with a document-embedding exception - see assets/fonts/).
    private val nimbusSansRegular: Typeface = Typeface.createFromAsset(context.assets, "fonts/NimbusSans-Regular.ttf")
    private val nimbusSansBold: Typeface = Typeface.createFromAsset(context.assets, "fonts/NimbusSans-Bold.ttf")
    private val playfairRegular: Typeface = Typeface.createFromAsset(context.assets, "fonts/PlayfairDisplay-Regular.ttf")
    private val playfairBold: Typeface = Typeface.createFromAsset(context.assets, "fonts/PlayfairDisplay-Bold.ttf")

    // ---- Paint styles, sizes matched exactly to real Amazon invoice PDFs ----
    // (verified via character-level font/size inspection of real samples)
    private val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 13.5f
        typeface = nimbusSansBold
        isAntiAlias = true
    }
    private val subtitlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 13.5f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }
    private val boldLabelPaint = Paint().apply {
        color = Color.BLACK
        textSize = 11.2f
        typeface = nimbusSansBold
        isAntiAlias = true
    }
    private val regularPaint = Paint().apply {
        color = Color.BLACK
        textSize = 11.2f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }
    private val tableHeaderPaint = Paint().apply {
        color = Color.BLACK
        textSize = 9f
        typeface = nimbusSansBold
        isAntiAlias = true
    }
    // Table description/HSN text stays Helvetica-equivalent (Nimbus Sans)
    private val tableCellPaint = Paint().apply {
        color = Color.BLACK
        textSize = 9f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }
    private val tableCellSmallPaint = Paint().apply {
        color = Color.BLACK
        textSize = 8f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }
    // All numeric/monetary values inside table rows use Playfair Display
    // (Regular for normal rows, Bold for the TOTAL row), matching Amazon's
    // exact invoice typography.
    private val tableNumericPaint = Paint().apply {
        color = Color.BLACK
        textSize = 12.8f
        typeface = playfairRegular
        isAntiAlias = true
    }
    private val tableNumericBoldPaint = Paint().apply {
        color = Color.BLACK
        textSize = 12.8f
        typeface = playfairBold
        isAntiAlias = true
    }
    private val borderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 0.75f
        isAntiAlias = true
    }
    private val footerPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 5.6f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }
    private val paymentTableBoldPaint = Paint().apply {
        color = Color.BLACK
        textSize = 7.3f
        typeface = nimbusSansBold
        isAntiAlias = true
    }
    private val paymentTableRegularPaint = Paint().apply {
        color = Color.BLACK
        textSize = 7.3f
        typeface = nimbusSansRegular
        isAntiAlias = true
    }

    // Table column widths (sum = CONTENT_WIDTH = 523)
    // Table column widths. Numeric columns are sized with headroom for
    // MERGED invoice totals, which can exceed any single source invoice's
    // values (e.g. quantities and amounts compound across multiple merged
    // orders) - the widths measured from one real sample invoice were too
    // narrow once larger merged totals overflowed their columns in
    // practice. Description is narrower than a single real invoice's
    // column to compensate, which means longer product names wrap to more
    // lines - an acceptable tradeoff since description text can wrap
    // freely, while a numeric value overflowing its column visually
    // collides with the neighboring column's content.
    // Sum = CONTENT_WIDTH ≈ 509.7
    private val colSlNo = 21f
    private val colDesc = 163.7f
    private val colUnitPrice = 55f
    private val colQty = 22f
    private val colNetAmt = 62f
    private val colTaxRate = 28f
    private val colTaxType = 32f
    private val colTaxAmt = 58f
    private val colTotalAmt = 68f

    fun render(invoice: MergedInvoice, logoBitmap: Bitmap?, outputUri: Uri) {
        val document = PdfDocument()
        var pageNumber = 1
        var page = document.startPage(
            PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
        )
        var canvas = page.canvas
        var y = drawHeader(canvas, logoBitmap)

        y = drawSellerBillingShipping(canvas, invoice, y)
        y = drawOrderInvoiceMeta(canvas, invoice, y)
        y = drawTableHeader(canvas, y)

        var rowIndex = 0
        val items = invoice.items
        while (rowIndex < items.size) {
            val item = items[rowIndex]
            val rowHeight = estimateRowHeight(item)

            if (y + rowHeight > PAGE_HEIGHT - MARGIN_BOTTOM - 60f) {
                drawDisclaimerFooter(canvas, pageNumber)
                document.finishPage(page)
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
                )
                canvas = page.canvas
                y = drawHeader(canvas, logoBitmap)
                y = drawTableHeader(canvas, y)
            }

            y = drawItemRow(canvas, item, y)
            rowIndex++
        }

        y = drawGrandTotalRow(canvas, invoice, y)

        val footerBlockHeight = 150f
        if (y + footerBlockHeight > PAGE_HEIGHT - MARGIN_BOTTOM - 30f) {
            drawDisclaimerFooter(canvas, pageNumber)
            document.finishPage(page)
            pageNumber++
            page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNumber).create()
            )
            canvas = page.canvas
            y = drawHeader(canvas, logoBitmap)
        }

        y = drawAmountInWordsBlock(canvas, invoice, y)
        y = drawSignatoryBlock(canvas, invoice, y)
        y = drawReverseChargeLine(canvas, y)
        y = drawPaymentDetailsTable(canvas, invoice, y)

        drawDisclaimerFooter(canvas, pageNumber)
        document.finishPage(page)

        writeToUri(document, outputUri)
        document.close()
    }

    private fun writeToUri(document: PdfDocument, uri: Uri) {
        val outputStream: OutputStream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Could not open output stream for $uri")
        outputStream.use { document.writeTo(it) }
    }

    private fun drawHeader(canvas: Canvas, logoBitmap: Bitmap?): Float {
        var y = MARGIN_TOP

        if (logoBitmap != null) {
            // Sized to match the real Amazon invoice's logo dimensions
            // exactly (202.5 x 57 pt, measured from a real invoice PDF),
            // rather than preserving the bundled asset's natural aspect
            // ratio - visual fidelity to the original document matters
            // more here than the asset's own proportions.
            // Width matches the real Amazon invoice's measured logo width
            // (202.5pt); height is derived from the bitmap's OWN aspect
            // ratio (now that the asset is cropped to its visible content,
            // with transparent padding removed) rather than a second fixed
            // value, which previously caused visible stretching/distortion
            // when the asset's natural proportions didn't match.
            val logoWidth = 202.5f
            val logoHeight = logoWidth * (logoBitmap.height.toFloat() / logoBitmap.width.toFloat())
            val destRect = RectF(MARGIN_LEFT, y, MARGIN_LEFT + logoWidth, y + logoHeight)
            canvas.drawBitmap(logoBitmap, null, destRect, null)
        } else {
            canvas.drawText("amazon.in", MARGIN_LEFT, y + 14f, titlePaint)
        }

        val titleText = "Tax Invoice/Bill of Supply/Cash Memo"
        val titleWidth = titlePaint.measureText(titleText)
        canvas.drawText(titleText, PAGE_WIDTH - MARGIN_RIGHT - titleWidth, y + 12f, titlePaint)

        val subtitleText = "(Original for Recipient)"
        val subtitleWidth = subtitlePaint.measureText(subtitleText)
        canvas.drawText(subtitleText, PAGE_WIDTH - MARGIN_RIGHT - subtitleWidth, y + 26f, subtitlePaint)

        y += 70f
        return y
    }

    /**
     * Draws a "Label: value" pair where the label is bold and the value
     * is regular weight, matching Amazon's exact style for fields like
     * "Order Number: 171-...", "State/UT Code: 27", etc.
     */
    private fun drawLabelValue(canvas: Canvas, label: String, value: String, x: Float, y: Float): Float {
        canvas.drawText(label, x, y, boldLabelPaint)
        val labelWidth = boldLabelPaint.measureText(label)
        canvas.drawText(value, x + labelWidth, y, regularPaint)
        return labelWidth + regularPaint.measureText(value)
    }

    /**
     * Draws text right-aligned to [rightEdgeX] (the text's right edge
     * lands exactly at that x-coordinate, growing leftward).
     */
    private fun drawTextRightAligned(canvas: Canvas, text: String, rightEdgeX: Float, y: Float, paint: Paint) {
        val width = paint.measureText(text)
        canvas.drawText(text, rightEdgeX - width, y, paint)
    }

    /**
     * Draws a "Label: value" pair right-aligned as a whole unit to
     * [rightEdgeX] - both label (bold) and value (regular) are measured
     * together so the VALUE's right edge lands at rightEdgeX, matching
     * Amazon's right-aligned header style for fields like
     * "State/UT Code: 27" in the billing/shipping column.
     */
    private fun drawLabelValueRightAligned(canvas: Canvas, label: String, value: String, rightEdgeX: Float, y: Float) {
        val valueWidth = regularPaint.measureText(value)
        val labelWidth = boldLabelPaint.measureText(label)
        val labelX = rightEdgeX - valueWidth - labelWidth
        canvas.drawText(label, labelX, y, boldLabelPaint)
        canvas.drawText(value, labelX + labelWidth, y, regularPaint)
    }

    private fun drawSellerBillingShipping(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val master = invoice.master
        var leftY = startY
        var rightY = startY

        val leftX = MARGIN_LEFT
        val rightEdge = PAGE_WIDTH - MARGIN_RIGHT

        canvas.drawText("Sold By :", leftX, leftY, boldLabelPaint)
        leftY += 12f
        master.sellerName?.let {
            canvas.drawText(it, leftX, leftY, boldLabelPaint)
            leftY += 11f
        }
        for (line in master.sellerAddressLines) {
            leftY = drawWrappedText(canvas, line, leftX, leftY, 260f, regularPaint)
        }
        leftY += 6f
        master.panNo?.let {
            drawLabelValue(canvas, "PAN No: ", it, leftX, leftY)
            leftY += 11f
        }
        master.gstNo?.let {
            drawLabelValue(canvas, "GST Registration No: ", it, leftX, leftY)
            leftY += 11f
        }

        drawTextRightAligned(canvas, "Billing Address :", rightEdge, rightY, boldLabelPaint)
        rightY += 12f
        master.billingName?.let {
            drawTextRightAligned(canvas, it, rightEdge, rightY, boldLabelPaint)
            rightY += 11f
        }
        for (line in master.billingAddressLines) {
            rightY = drawWrappedTextRightAligned(canvas, line, rightEdge, rightY, 240f, regularPaint)
        }
        master.billingStateCode?.let {
            drawLabelValueRightAligned(canvas, "State/UT Code: ", it, rightEdge, rightY)
            rightY += 14f
        }

        drawTextRightAligned(canvas, "Shipping Address :", rightEdge, rightY, boldLabelPaint)
        rightY += 12f
        master.shippingName?.let {
            drawTextRightAligned(canvas, it, rightEdge, rightY, boldLabelPaint)
            rightY += 11f
        }
        for (line in master.shippingAddressLines) {
            rightY = drawWrappedTextRightAligned(canvas, line, rightEdge, rightY, 240f, regularPaint)
        }
        master.shippingStateCode?.let {
            drawLabelValueRightAligned(canvas, "State/UT Code: ", it, rightEdge, rightY)
            rightY += 14f
        }

        // Place of supply/delivery sit on the RIGHT side, directly below
        // Shipping Address (matching the real invoice layout) - not on
        // the left side as in an earlier version of this renderer.
        master.placeOfSupply?.let {
            drawLabelValueRightAligned(canvas, "Place of supply: ", it, rightEdge, rightY)
            rightY += 11f
        }
        master.placeOfDelivery?.let {
            drawLabelValueRightAligned(canvas, "Place of delivery: ", it, rightEdge, rightY)
            rightY += 14f
        }

        return maxOf(leftY, rightY) + 4f
    }

    private fun drawOrderInvoiceMeta(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val master = invoice.master
        var y = startY
        val leftX = MARGIN_LEFT
        val rightEdge = PAGE_WIDTH - MARGIN_RIGHT

        drawLabelValue(canvas, "Order Number: ", master.orderNumber, leftX, y)
        drawLabelValueRightAligned(canvas, "Invoice Number : ", master.invoiceNumber, rightEdge, y)
        y += 11f
        drawLabelValue(canvas, "Order Date: ", master.orderDate, leftX, y)
        drawLabelValueRightAligned(canvas, "Invoice Details : ", master.invoiceDetails, rightEdge, y)
        y += 11f
        drawLabelValueRightAligned(canvas, "Invoice Date : ", master.invoiceDate, rightEdge, y)
        y += 18f

        return y
    }

    private fun drawTableHeader(canvas: Canvas, startY: Float): Float {
        val y = startY
        val rowHeight = 22f
        var x = MARGIN_LEFT

        val cols = listOf(
            "Sl.\nNo" to colSlNo,
            "Description" to colDesc,
            "Unit\nPrice" to colUnitPrice,
            "Qty" to colQty,
            "Net\nAmount" to colNetAmt,
            "Tax\nRate" to colTaxRate,
            "Tax\nType" to colTaxType,
            "Tax\nAmount" to colTaxAmt,
            "Total\nAmount" to colTotalAmt
        )

        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_WIDTH, y + rowHeight, borderPaint)
        for ((label, width) in cols) {
            canvas.drawRect(x, y, x + width, y + rowHeight, borderPaint)
            val lines = label.split("\n")
            var ty = y + 9f
            for (line in lines) {
                val tw = tableHeaderPaint.measureText(line)
                canvas.drawText(line, x + (width - tw) / 2f, ty, tableHeaderPaint)
                ty += 9f
            }
            x += width
        }

        return y + rowHeight
    }

    private fun estimateRowHeight(item: MergedLineItem): Float {
        val descLines = wrapTextLines(item.description, colDesc - 6f, tableCellPaint).size +
            (if (item.hsn != null) 1 else 0)
        // minimum lines = number of tax rows (1 for IGST, 2 for CGST+SGST).
        // Per-line height bumped to 13f (from 9.5f) to properly fit the
        // 12.8pt Playfair Display numeric text used for all table values.
        val minLines = maxOf(descLines, item.taxRows.size, 1)
        return minLines * 13f + 8f
    }

    private fun drawItemRow(canvas: Canvas, item: MergedLineItem, startY: Float): Float {
        val descLines = wrapTextLines(item.description, colDesc - 6f, tableCellPaint).toMutableList()
        if (item.hsn != null) descLines.add("HSN:${item.hsn}")

        val rowHeight = estimateRowHeight(item)
        val y = startY

        var x = MARGIN_LEFT
        val widths = listOf(colSlNo, colDesc, colUnitPrice, colQty, colNetAmt, colTaxRate, colTaxType, colTaxAmt, colTotalAmt)
        for (wCol in widths) {
            canvas.drawRect(x, y, x + wCol, y + rowHeight, borderPaint)
            x += wCol
        }

        // Sl.No and all monetary/numeric values use Playfair Display,
        // matching Amazon's exact invoice typography. Tax type labels
        // (CGST/SGST/IGST) and description/HSN text stay Nimbus Sans
        // (Helvetica-equivalent).
        x = MARGIN_LEFT
        canvas.drawText(item.slNo.toString(), x + 6f, y + 13f, tableNumericPaint)
        x += colSlNo

        var descY = y + 9f
        for (line in descLines) {
            canvas.drawText(line, x + 3f, descY, tableCellSmallPaint)
            descY += 9.5f
        }
        x += colDesc

        canvas.drawText(formatCurrency(item.unitPrice), x + rightAlignOffset(colUnitPrice, item.unitPrice, tableNumericPaint), y + 13f, tableNumericPaint)
        x += colUnitPrice

        canvas.drawText(item.qty.toString(), x + colQty / 2f - 4f, y + 13f, tableNumericPaint)
        x += colQty

        canvas.drawText(formatCurrency(item.netAmount), x + rightAlignOffset(colNetAmt, item.netAmount, tableNumericPaint), y + 13f, tableNumericPaint)
        x += colNetAmt

        // Draw N tax sub-rows (1 for IGST, 2 for CGST+SGST), each occupying
        // an equal vertical slice of the row. No internal divider lines
        // are drawn between sub-rows (e.g. CGST/SGST) - the real Amazon
        // invoice does not draw one either; the tax cell is one continuous
        // box with stacked lines of text inside it.
        val taxRowCount = item.taxRows.size
        val subRowHeight = rowHeight / taxRowCount

        val taxRateX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 4f
            canvas.drawText("${formatRate(taxRow.rate)}%", taxRateX + 4f, textBaselineY, tableNumericPaint)
        }
        x += colTaxRate

        val taxTypeX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 3f
            canvas.drawText(taxRow.type, taxTypeX + 4f, textBaselineY, tableCellPaint)
        }
        x += colTaxType

        val taxAmtX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 4f
            canvas.drawText(formatCurrency(taxRow.amount), taxAmtX + rightAlignOffset(colTaxAmt, taxRow.amount, tableNumericPaint), textBaselineY, tableNumericPaint)
        }
        x += colTaxAmt

        canvas.drawText(formatCurrency(item.totalAmount), x + rightAlignOffset(colTotalAmt, item.totalAmount, tableNumericPaint), y + rowHeight / 2f + 5f, tableNumericPaint)

        return y + rowHeight
    }

    private fun rightAlignOffset(colWidth: Float, value: Double, paint: Paint): Float {
        val text = formatCurrency(value)
        val textWidth = paint.measureText(text)
        return colWidth - textWidth - 4f
    }

    private fun drawGrandTotalRow(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val rowHeight = 18f
        val y = startY

        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_WIDTH, y + rowHeight, borderPaint)
        canvas.drawText("TOTAL:", MARGIN_LEFT + 6f, y + 13f, tableHeaderPaint)

        val taxX = MARGIN_LEFT + colSlNo + colDesc + colUnitPrice + colQty + colNetAmt
        val totalTaxText = formatCurrency(invoice.grandTax)
        canvas.drawText(
            totalTaxText,
            taxX + colTaxRate + colTaxType + colTaxAmt - tableNumericBoldPaint.measureText(totalTaxText) - 4f,
            y + 14f,
            tableNumericBoldPaint
        )

        val grandTotalText = formatCurrency(invoice.grandTotal)
        canvas.drawText(
            grandTotalText,
            MARGIN_LEFT + CONTENT_WIDTH - tableNumericBoldPaint.measureText(grandTotalText) - 4f,
            y + 14f,
            tableNumericBoldPaint
        )

        return y + rowHeight + 10f
    }

    private fun drawAmountInWordsBlock(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        var y = startY
        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + 300f, y + 32f, borderPaint)
        canvas.drawText("Amount in Words:", MARGIN_LEFT + 4f, y + 12f, boldLabelPaint)
        canvas.drawText(invoice.amountInWords, MARGIN_LEFT + 4f, y + 24f, boldLabelPaint)
        y += 40f
        return y
    }

    private fun drawSignatoryBlock(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        var y = startY
        val sellerName = invoice.master.sellerName ?: ""
        val text = "For $sellerName:"
        val tw = boldLabelPaint.measureText(text)
        canvas.drawText(text, PAGE_WIDTH - MARGIN_RIGHT - tw, y, boldLabelPaint)
        y += 30f
        val sigText = "Authorized Signatory"
        val stw = boldLabelPaint.measureText(sigText)
        canvas.drawText(sigText, PAGE_WIDTH - MARGIN_RIGHT - stw, y, boldLabelPaint)
        y += 16f
        return y
    }

    private fun drawReverseChargeLine(canvas: Canvas, startY: Float): Float {
        var y = startY
        canvas.drawText("Whether tax is payable under reverse charge - No", MARGIN_LEFT, y, regularPaint)
        y += 16f
        return y
    }

    private fun drawPaymentDetailsTable(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val master = invoice.master

        // Some invoices (e.g. fully-discounted ₹0 orders) have no payment
        // transaction section at all. Skip drawing the table entirely in
        // that case rather than showing misleading placeholder text.
        if (master.paymentTransactionId == null && master.paymentDateTime == null && master.modeOfPayment == null) {
            return startY
        }

        var y = startY
        val rowHeight = 28f
        val colWidth = CONTENT_WIDTH / 4f

        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_WIDTH, y + rowHeight, borderPaint)
        var x = MARGIN_LEFT
        val labels = listOf(
            "Payment Transaction ID:" to (master.paymentTransactionId ?: ""),
            "Date & Time:" to (master.paymentDateTime ?: ""),
            "Invoice Value:" to formatCurrency(invoice.grandTotal),
            "Mode of Payment:" to (master.modeOfPayment ?: "")
        )
        for ((label, value) in labels) {
            canvas.drawRect(x, y, x + colWidth, y + rowHeight, borderPaint)
            canvas.drawText(label, x + 3f, y + 11f, paymentTableBoldPaint)
            canvas.drawText(value, x + 3f, y + 22f, paymentTableRegularPaint)
            x += colWidth
        }
        y += rowHeight + 8f
        return y
    }

    private fun drawDisclaimerFooter(canvas: Canvas, pageNumber: Int) {
        val y = PAGE_HEIGHT - MARGIN_BOTTOM
        val line1 = "*ASSPL-Amazon Seller Services Pvt. Ltd. (FSSAI License No. 10014043001078), ARIPL-Amazon Retail India Pvt. Ltd. (only where Amazon Retail India Pvt. Ltd. fulfillment center is co-located)"
        val line2 = "Customers desirous of availing input GST credit are requested to create a Business account and purchase on Amazon.in/business from Business eligible offers"
        val line3 = "Please note that this invoice is not a demand for payment"

        canvas.drawText(line1, MARGIN_LEFT, y - 24f, footerPaint)
        canvas.drawText(line2, MARGIN_LEFT, y - 16f, footerPaint)
        canvas.drawText(line3, MARGIN_LEFT, y - 8f, footerPaint)
        canvas.drawText("Page $pageNumber", PAGE_WIDTH - MARGIN_RIGHT - 40f, y, footerPaint)
    }

    private fun drawWrappedText(canvas: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, paint: Paint): Float {
        val lines = wrapTextLines(text, maxWidth, paint)
        var y = startY
        for (line in lines) {
            canvas.drawText(line, x, y, paint)
            y += 11f
        }
        return y
    }

    /** Same as [drawWrappedText] but each wrapped line is right-aligned to [rightEdgeX]. */
    private fun drawWrappedTextRightAligned(canvas: Canvas, text: String, rightEdgeX: Float, startY: Float, maxWidth: Float, paint: Paint): Float {
        val lines = wrapTextLines(text, maxWidth, paint)
        var y = startY
        for (line in lines) {
            drawTextRightAligned(canvas, line, rightEdgeX, y, paint)
            y += 11f
        }
        return y
    }

    private fun wrapTextLines(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (text.isBlank()) return emptyList()
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(candidate) <= maxWidth) {
                currentLine = StringBuilder(candidate)
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }

    private fun formatRate(rate: Double): String {
        return if (rate == rate.toInt().toDouble()) rate.toInt().toString() else rate.toString()
    }

    private fun formatCurrency(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return "₹" + String.format("%,.2f", rounded)
    }
}
