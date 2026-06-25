package com.akshansh.invoicemerger

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * Extracts raw text and structured data from an Amazon invoice PDF.
 *
 * Parsing strategy mirrors the tested Python prototype: extract full text,
 * then use a line-based approach for line items (rather than one giant regex)
 * to avoid cross-item matching bugs when product descriptions wrap across
 * multiple lines in the PDF's text layout.
 */
class InvoiceParser(private val context: Context) {

    /** Reads raw text from a PDF at the given content Uri. */
    fun extractText(uri: Uri): String {
        val inputStream: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Could not open file: $uri")

        inputStream.use { stream ->
            PDDocument.load(stream).use { document ->
                val stripper = PDFTextStripper()
                return stripper.getText(document)
            }
        }
    }

    /** Parses full invoice text into structured data. */
    fun parseInvoice(fullText: String): ParsedInvoice {
        val orderNumber = Regex("Order Number:\\s*([\\w\\-]+)").find(fullText)?.groupValues?.get(1)
            ?: throw ParseException("Could not find Order Number")
        val orderDate = Regex("Order Date:\\s*([\\d.]+)").find(fullText)?.groupValues?.get(1)
            ?: throw ParseException("Could not find Order Date")
        val invoiceNumber = Regex("Invoice Number\\s*:\\s*([\\w\\-]+)").find(fullText)?.groupValues?.get(1)
            ?: throw ParseException("Could not find Invoice Number")
        val invoiceDetails = Regex("Invoice Details\\s*:\\s*([\\w\\-]+)").find(fullText)?.groupValues?.get(1) ?: ""
        val invoiceDate = Regex("Invoice Date\\s*:\\s*([\\d.]+)").find(fullText)?.groupValues?.get(1) ?: orderDate

        val panNo = Regex("PAN No:\\s*(\\w+)").find(fullText)?.groupValues?.get(1)
        val gstNo = Regex("GST Registration No:\\s*(\\w+)").find(fullText)?.groupValues?.get(1)

        val sellerNameMatch = Regex("Sold By\\s*:\\s*\\n([^\\n]+)").find(fullText)
        val sellerName = sellerNameMatch?.groupValues?.get(1)?.trim()

        val placeOfSupply = Regex("Place of supply:\\s*(\\w+)").find(fullText)?.groupValues?.get(1)
        val placeOfDelivery = Regex("Place of delivery:\\s*(\\w+)").find(fullText)?.groupValues?.get(1)

        // Seller address: lines between "Sold By :" name line and "PAN No:"
        val sellerBlockMatch = Regex("Sold By\\s*:\\s*\\n(.*?)\\nPAN No:", RegexOption.DOT_MATCHES_ALL)
            .find(fullText)
        val sellerAddressLines = sellerBlockMatch?.groupValues?.get(1)
            ?.split("\n")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "*" }
            ?.drop(1) // drop the name line itself, already captured as sellerName
            ?: emptyList()

        // Billing address block
        val billingMatch = Regex("Billing Address\\s*:\\s*\\n(.*?)\\nState/UT Code:\\s*(\\w+)", RegexOption.DOT_MATCHES_ALL)
            .find(fullText)
        val billingLines = billingMatch?.groupValues?.get(1)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        val billingName = billingLines.firstOrNull()
        val billingAddressLines = billingLines.drop(1)
        val billingStateCode = billingMatch?.groupValues?.get(2)

        // Shipping address block
        val shippingMatch = Regex("Shipping Address\\s*:\\s*\\n(.*?)\\nState/UT Code:\\s*(\\w+)", RegexOption.DOT_MATCHES_ALL)
            .find(fullText)
        val shippingLinesRaw = shippingMatch?.groupValues?.get(1)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
        // Amazon prints the recipient name twice in shipping block (matches sample) - keep as-is for fidelity
        val shippingName = shippingLinesRaw.firstOrNull()
        val shippingAddressLines = shippingLinesRaw.drop(1)
        val shippingStateCode = shippingMatch?.groupValues?.get(2)

        val items = parseLineItems(fullText)

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
            items = items
        )
    }

    /**
     * Parses line items using a block-based approach: each item starts with
     * a line beginning with "<digits> <text>" (the Sl.No), and all subsequent
     * lines belong to that item until the next Sl.No line or end of table.
     */
    private fun parseLineItems(fullText: String): List<InvoiceLineItem> {
        val lines = fullText.split("\n")

        var startIdx = -1
        var endIdx = lines.size
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (startIdx == -1 && (trimmed.startsWith("Sl.") || (trimmed.contains("Description") && trimmed.contains("Qty")))) {
                startIdx = i + 1
            }
            if (trimmed.startsWith("TOTAL:")) {
                endIdx = i
                break
            }
        }
        if (startIdx == -1) throw ParseException("Could not locate line items table")

        val tableLines = lines.subList(startIdx, endIdx)

        // Group lines into blocks, one per item, starting at lines matching "^\d+\s+"
        val itemStartRegex = Regex("^(\\d+)\\s+.*")
        val blocks = mutableListOf<MutableList<String>>()
        for (line in tableLines) {
            if (itemStartRegex.matches(line.trim())) {
                blocks.add(mutableListOf(line))
            } else if (blocks.isNotEmpty()) {
                blocks.last().add(line)
            }
            // stray lines before first item are ignored
        }

        return blocks.mapNotNull { block -> parseItemBlock(block) }
    }

    private fun parseItemBlock(blockLines: List<String>): InvoiceLineItem? {
        val text = blockLines.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ")

        val slMatch = Regex("^(\\d+)\\s+").find(text) ?: return null
        val slNo = slMatch.groupValues[1]
        val rest = text.substring(slMatch.range.last + 1)

        // ASIN: 10-char alphanumeric code. Line wrapping can place numbers
        // inside what looks like the parens, so match the code directly.
        val asinCandidates = Regex("\\b([A-Z0-9]{10})\\b").findAll(rest).map { it.groupValues[1] }.toList()
        if (asinCandidates.isEmpty()) return null
        val asin = asinCandidates[0]

        // Description = everything before first ASIN occurrence, with any
        // leaked price/tax numeric fragment (from line-wrap artifacts) stripped.
        val firstAsinPos = rest.indexOf(asin)
        var desc = if (firstAsinPos >= 0) rest.substring(0, firstAsinPos) else rest
        desc = desc.replace(
            Regex("₹[\\d,]+\\.\\d{2}\\s+\\d+\\s+₹[\\d,]+\\.\\d{2}\\s+\\d+%\\s+\\w+\\s+₹[\\d,]+\\.\\d{2}\\s+₹[\\d,]+\\.\\d{2}"),
            ""
        )
        desc = desc.replace(Regex("\\s+"), " ").trim()
        desc = desc.trim().trimEnd('|').trim()

        val hsn = Regex("HSN:(\\d+)").find(rest)?.groupValues?.get(1)

        val numsMatch = Regex(
            "₹([\\d,]+\\.\\d{2})\\s+(\\d+)\\s+₹([\\d,]+\\.\\d{2})\\s+(\\d+)%\\s+(\\w+)\\s+₹([\\d,]+\\.\\d{2})\\s+₹([\\d,]+\\.\\d{2})"
        ).find(rest) ?: return null

        val unitPrice = numsMatch.groupValues[1].replace(",", "").toDouble()
        val qty = numsMatch.groupValues[2].toInt()
        val netAmount = numsMatch.groupValues[3].replace(",", "").toDouble()
        val totalAmount = numsMatch.groupValues[7].replace(",", "").toDouble()

        // Tax rows: CGST+SGST invoices have 2 "<rate>% <TYPE> ₹<amt>" occurrences;
        // IGST (inter-state) invoices have only 1. Detect dynamically rather
        // than assuming a fixed count.
        val allTaxMatches = Regex("(\\d+)%\\s+(\\w+)\\s+₹([\\d,]+\\.\\d{2})").findAll(rest).toList()
        if (allTaxMatches.isEmpty()) return null

        val taxRows = allTaxMatches.take(2).map { tm ->
            TaxRow(
                rate = tm.groupValues[1].toInt(),
                type = tm.groupValues[2],
                amount = tm.groupValues[3].replace(",", "").toDouble()
            )
        }

        return InvoiceLineItem(
            slNo = slNo,
            description = desc,
            asin = asin,
            hsn = hsn,
            unitPrice = unitPrice,
            qty = qty,
            netAmount = netAmount,
            taxRows = taxRows,
            totalAmount = totalAmount
        )
    }
}

class ParseException(message: String) : Exception(message)
