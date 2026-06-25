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
        const val MARGIN_LEFT = 36f
        const val MARGIN_RIGHT = 36f
        const val MARGIN_TOP = 30f
        const val MARGIN_BOTTOM = 40f
        const val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT
    }

    private val titlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 13f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val subtitlePaint = Paint().apply {
        color = Color.BLACK
        textSize = 10f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }
    private val boldLabelPaint = Paint().apply {
        color = Color.BLACK
        textSize = 9f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val regularPaint = Paint().apply {
        color = Color.BLACK
        textSize = 9f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }
    private val tableHeaderPaint = Paint().apply {
        color = Color.BLACK
        textSize = 8.5f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val tableCellPaint = Paint().apply {
        color = Color.BLACK
        textSize = 8f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }
    private val tableCellSmallPaint = Paint().apply {
        color = Color.BLACK
        textSize = 7f
        typeface = Typeface.DEFAULT
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
        textSize = 6.5f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }

    // Table column widths (sum = CONTENT_WIDTH = 523)
    private val colSlNo = 22f
    private val colDesc = 195f
    private val colUnitPrice = 50f
    private val colQty = 28f
    private val colNetAmt = 55f
    private val colTaxRate = 32f
    private val colTaxType = 38f
    private val colTaxAmt = 48f
    private val colTotalAmt = 55f

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
            val logoWidth = 110f
            val aspect = logoBitmap.height.toFloat() / logoBitmap.width.toFloat()
            val logoHeight = logoWidth * aspect
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

        y += 50f
        return y
    }

    private fun drawSellerBillingShipping(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val master = invoice.master
        var leftY = startY
        var rightY = startY

        val leftX = MARGIN_LEFT
        val rightX = MARGIN_LEFT + 280f

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
            canvas.drawText("PAN No: $it", leftX, leftY, boldLabelPaint)
            leftY += 11f
        }
        master.gstNo?.let {
            canvas.drawText("GST Registration No: $it", leftX, leftY, boldLabelPaint)
            leftY += 11f
        }

        canvas.drawText("Billing Address :", rightX, rightY, boldLabelPaint)
        rightY += 12f
        master.billingName?.let {
            canvas.drawText(it, rightX, rightY, boldLabelPaint)
            rightY += 11f
        }
        for (line in master.billingAddressLines) {
            rightY = drawWrappedText(canvas, line, rightX, rightY, 240f, regularPaint)
        }
        master.billingStateCode?.let {
            canvas.drawText("State/UT Code: $it", rightX, rightY, regularPaint)
            rightY += 14f
        }

        canvas.drawText("Shipping Address :", rightX, rightY, boldLabelPaint)
        rightY += 12f
        master.shippingName?.let {
            canvas.drawText(it, rightX, rightY, boldLabelPaint)
            rightY += 11f
        }
        for (line in master.shippingAddressLines) {
            rightY = drawWrappedText(canvas, line, rightX, rightY, 240f, regularPaint)
        }
        master.shippingStateCode?.let {
            canvas.drawText("State/UT Code: $it", rightX, rightY, regularPaint)
            rightY += 14f
        }

        var y = maxOf(leftY, rightY) + 4f

        master.placeOfSupply?.let {
            canvas.drawText("Place of supply: $it", leftX, y, boldLabelPaint)
            y += 11f
        }
        master.placeOfDelivery?.let {
            canvas.drawText("Place of delivery: $it", leftX, y, boldLabelPaint)
            y += 14f
        }

        return y
    }

    private fun drawOrderInvoiceMeta(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val master = invoice.master
        var y = startY
        val leftX = MARGIN_LEFT
        val rightX = MARGIN_LEFT + 280f

        canvas.drawText("Order Number: ${master.orderNumber}", leftX, y, boldLabelPaint)
        canvas.drawText("Invoice Number : ${master.invoiceNumber}", rightX, y, boldLabelPaint)
        y += 11f
        canvas.drawText("Order Date: ${master.orderDate}", leftX, y, boldLabelPaint)
        canvas.drawText("Invoice Details : ${master.invoiceDetails}", rightX, y, boldLabelPaint)
        y += 11f
        canvas.drawText("Invoice Date : ${master.invoiceDate}", rightX, y, boldLabelPaint)
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
        // minimum lines = number of tax rows (1 for IGST, 2 for CGST+SGST)
        val minLines = maxOf(descLines, item.taxRows.size, 1)
        return minLines * 9.5f + 8f
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

        x = MARGIN_LEFT
        canvas.drawText(item.slNo.toString(), x + 6f, y + 12f, tableCellPaint)
        x += colSlNo

        var descY = y + 9f
        for (line in descLines) {
            canvas.drawText(line, x + 3f, descY, tableCellSmallPaint)
            descY += 9.5f
        }
        x += colDesc

        canvas.drawText(formatCurrency(item.unitPrice), x + rightAlignOffset(colUnitPrice, item.unitPrice), y + 12f, tableCellPaint)
        x += colUnitPrice

        canvas.drawText(item.qty.toString(), x + colQty / 2f - 4f, y + 12f, tableCellPaint)
        x += colQty

        canvas.drawText(formatCurrency(item.netAmount), x + rightAlignOffset(colNetAmt, item.netAmount), y + 12f, tableCellPaint)
        x += colNetAmt

        // Draw N tax sub-rows (1 for IGST, 2 for CGST+SGST), each occupying
        // an equal vertical slice of the row, with divider lines between them.
        val taxRowCount = item.taxRows.size
        val subRowHeight = rowHeight / taxRowCount

        val taxRateX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 3f
            canvas.drawText("${taxRow.rate}%", taxRateX + 4f, textBaselineY, tableCellPaint)
            if (idx > 0) canvas.drawLine(taxRateX, subY, taxRateX + colTaxRate, subY, borderPaint)
        }
        x += colTaxRate

        val taxTypeX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 3f
            canvas.drawText(taxRow.type, taxTypeX + 4f, textBaselineY, tableCellPaint)
            if (idx > 0) canvas.drawLine(taxTypeX, subY, taxTypeX + colTaxType, subY, borderPaint)
        }
        x += colTaxType

        val taxAmtX = x
        for ((idx, taxRow) in item.taxRows.withIndex()) {
            val subY = y + idx * subRowHeight
            val textBaselineY = subY + subRowHeight / 2f + 3f
            canvas.drawText(formatCurrency(taxRow.amount), taxAmtX + rightAlignOffset(colTaxAmt, taxRow.amount), textBaselineY, tableCellPaint)
            if (idx > 0) canvas.drawLine(taxAmtX, subY, taxAmtX + colTaxAmt, subY, borderPaint)
        }
        x += colTaxAmt

        canvas.drawText(formatCurrency(item.totalAmount), x + rightAlignOffset(colTotalAmt, item.totalAmount), y + rowHeight / 2f + 4f, tableCellPaint)

        return y + rowHeight
    }

    private fun rightAlignOffset(colWidth: Float, value: Double): Float {
        val text = formatCurrency(value)
        val textWidth = tableCellPaint.measureText(text)
        return colWidth - textWidth - 4f
    }

    private fun drawGrandTotalRow(canvas: Canvas, invoice: MergedInvoice, startY: Float): Float {
        val rowHeight = 18f
        val y = startY

        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_WIDTH, y + rowHeight, borderPaint)
        canvas.drawText("TOTAL:", MARGIN_LEFT + 6f, y + 12f, boldLabelPaint)

        val taxX = MARGIN_LEFT + colSlNo + colDesc + colUnitPrice + colQty + colNetAmt
        val totalTaxText = formatCurrency(invoice.grandTax)
        canvas.drawText(
            totalTaxText,
            taxX + colTaxRate + colTaxType + colTaxAmt - boldLabelPaint.measureText(totalTaxText) - 4f,
            y + 12f,
            boldLabelPaint
        )

        val grandTotalText = formatCurrency(invoice.grandTotal)
        canvas.drawText(
            grandTotalText,
            MARGIN_LEFT + CONTENT_WIDTH - boldLabelPaint.measureText(grandTotalText) - 4f,
            y + 12f,
            boldLabelPaint
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
        var y = startY
        val rowHeight = 28f
        val colWidth = CONTENT_WIDTH / 4f

        canvas.drawRect(MARGIN_LEFT, y, MARGIN_LEFT + CONTENT_WIDTH, y + rowHeight, borderPaint)
        var x = MARGIN_LEFT
        val labels = listOf(
            "Payment Transaction ID:" to "Merged Invoice (${invoice.sourceInvoiceCount} orders)",
            "Date & Time:" to invoice.master.invoiceDate,
            "Invoice Value:" to formatCurrency(invoice.grandTotal),
            "Mode of Payment:" to "See source invoices"
        )
        for ((label, value) in labels) {
            canvas.drawRect(x, y, x + colWidth, y + rowHeight, borderPaint)
            canvas.drawText(label, x + 3f, y + 11f, boldLabelPaint)
            canvas.drawText(value, x + 3f, y + 22f, regularPaint)
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

    private fun formatCurrency(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return "₹" + String.format("%,.2f", rounded)
    }
}
