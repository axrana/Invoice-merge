package com.akshansh.invoicemerger

import kotlin.math.roundToInt

/**
 * Merges multiple parsed Amazon invoices into one consolidated invoice.
 *
 * - Header/seller/billing/shipping/order info: taken from the FIRST invoice in the list.
 * - Line items: grouped by (ASIN, unitPrice) pair. Matching groups have their
 *   quantities summed and net/tax/total amounts recalculated from scratch
 *   (qty * unitPrice, not summed net amounts) to avoid compounding rounding
 *   errors across multiple source invoices.
 * - Items with the same ASIN but a DIFFERENT unit price are kept as separate rows.
 */
object InvoiceMerger {

    fun merge(invoices: List<ParsedInvoice>): MergedInvoice {
        require(invoices.isNotEmpty()) { "Need at least one invoice to merge" }
        val master = invoices.first()

        // Preserve first-seen order of (asin, unitPrice) groups
        data class Key(val asin: String, val unitPrice: Double)

        val order = mutableListOf<Key>()
        val qtyByKey = mutableMapOf<Key, Int>()
        val descByKey = mutableMapOf<Key, String>()
        val hsnByKey = mutableMapOf<Key, String?>()
        // tax structure (rates + types, e.g. [9% CGST, 9% SGST] or [18% IGST])
        // is taken from the FIRST occurrence of each key and assumed stable
        // across same-ASIN+price items (this should always hold true since
        // tax rate/type depends on the product+route, not the specific order)
        val taxStructureByKey = mutableMapOf<Key, List<Pair<Int, String>>>()

        for (invoice in invoices) {
            for (item in invoice.items) {
                val key = Key(item.asin, round2(item.unitPrice))
                if (key !in qtyByKey) {
                    order.add(key)
                    qtyByKey[key] = 0
                    descByKey[key] = item.description
                    hsnByKey[key] = item.hsn
                    taxStructureByKey[key] = item.taxRows.map { it.rate to it.type }
                }
                qtyByKey[key] = qtyByKey.getValue(key) + item.qty
                if (hsnByKey[key].isNullOrEmpty() && !item.hsn.isNullOrEmpty()) {
                    hsnByKey[key] = item.hsn
                }
            }
        }

        var grandNet = 0.0
        var grandTax = 0.0
        var grandTotal = 0.0

        val mergedItems = order.mapIndexed { index, key ->
            val qty = qtyByKey.getValue(key)
            val unitPrice = key.unitPrice
            val netAmount = round2(unitPrice * qty)
            val taxStructure = taxStructureByKey.getValue(key)

            val taxRows = taxStructure.map { (rate, type) ->
                TaxRow(rate = rate, type = type, amount = round2(netAmount * rate / 100.0))
            }
            val totalTaxForItem = round2(taxRows.sumOf { it.amount })
            val totalAmount = round2(netAmount + totalTaxForItem)

            grandNet += netAmount
            grandTax += totalTaxForItem
            grandTotal += totalAmount

            MergedLineItem(
                slNo = index + 1,
                description = descByKey.getValue(key),
                asin = key.asin,
                hsn = hsnByKey[key],
                unitPrice = unitPrice,
                qty = qty,
                netAmount = netAmount,
                taxRows = taxRows,
                totalAmount = totalAmount
            )
        }

        grandNet = round2(grandNet)
        grandTax = round2(grandTax)
        grandTotal = round2(grandTotal)

        return MergedInvoice(
            master = master,
            items = mergedItems,
            grandNet = grandNet,
            grandTax = grandTax,
            grandTotal = grandTotal,
            amountInWords = AmountToWords.convert(grandTotal),
            sourceInvoiceCount = invoices.size
        )
    }

    private fun round2(value: Double): Double {
        return Math.round(value * 100.0) / 100.0
    }
}

/**
 * Converts a rupee amount to words in the Indian numbering system
 * (Crore/Lakh/Thousand/Hundred), matching Amazon's exact wording style
 * e.g. "Seven Hundred Seventy-nine only".
 *
 * Verified against the real sample invoice: 779.00 -> "Seven Hundred Seventy-nine only"
 */
object AmountToWords {

    private val ones = arrayOf(
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        "Ten", "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen",
        "Seventeen", "Eighteen", "Nineteen"
    )
    private val tens = arrayOf(
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    )

    fun convert(amount: Double): String {
        val rupees = amount.toInt()
        val paise = ((amount - rupees) * 100).roundToInt()

        val words = StringBuilder(numberToWords(rupees))
        if (paise > 0) {
            words.append(" and ").append(numberToWords(paise)).append(" paise")
        }
        words.append(" only")
        return words.toString()
    }

    private fun twoDigit(n: Int): String {
        if (n < 20) return ones[n]
        val tensPart = tens[n / 10]
        val onesPart = n % 10
        return if (onesPart == 0) tensPart else "$tensPart-${ones[onesPart].lowercase()}"
    }

    private fun threeDigit(n: Int): String {
        return if (n >= 100) {
            val hundredPart = ones[n / 100] + " Hundred"
            val remainder = n % 100
            if (remainder != 0) "$hundredPart ${twoDigit(remainder)}" else hundredPart
        } else {
            twoDigit(n)
        }
    }

    private fun numberToWords(n: Int): String {
        if (n == 0) return "Zero"

        var remaining = n
        val parts = mutableListOf<String>()

        val crore = remaining / 10000000
        remaining %= 10000000
        val lakh = remaining / 100000
        remaining %= 100000
        val thousand = remaining / 1000
        remaining %= 1000
        val hundred = remaining

        if (crore > 0) parts.add("${threeDigit(crore)} Crore")
        if (lakh > 0) parts.add("${threeDigit(lakh)} Lakh")
        if (thousand > 0) parts.add("${threeDigit(thousand)} Thousand")
        if (hundred > 0) parts.add(threeDigit(hundred))

        return parts.joinToString(" ")
    }
}
