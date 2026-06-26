package com.akshansh.invoicemerger

import org.junit.Assert.assertEquals
import org.junit.Test

class InvoiceLogicTest {

    @Test
    fun testAmountToWords() {
        assertEquals("Seven Hundred Seventy-nine only", AmountToWords.convert(779.0))
        assertEquals("Ten and Twenty-five paise only", AmountToWords.convert(10.25))
        assertEquals("One Crore only", AmountToWords.convert(10000000.0))
        assertEquals("One Lakh only", AmountToWords.convert(100000.0))
        assertEquals("One Thousand only", AmountToWords.convert(1000.0))
        assertEquals("Zero only", AmountToWords.convert(0.0))
    }

    @Test
    fun testInvoiceMerger() {
        val item1 = InvoiceLineItem(
            slNo = "1",
            description = "Item A",
            asin = "ASIN1",
            hsn = "1234",
            unitPrice = 100.0,
            qty = 2,
            netAmount = 200.0,
            taxRows = listOf(TaxRow(9.0, "CGST", 18.0), TaxRow(9.0, "SGST", 18.0)),
            totalAmount = 236.0
        )

        val item2 = InvoiceLineItem(
            slNo = "1",
            description = "Item A",
            asin = "ASIN1",
            hsn = "1234",
            unitPrice = 100.0,
            qty = 3,
            netAmount = 300.0,
            taxRows = listOf(TaxRow(9.0, "CGST", 27.0), TaxRow(9.0, "SGST", 27.0)),
            totalAmount = 354.0
        )

        val item3 = InvoiceLineItem(
            slNo = "2",
            description = "Item B",
            asin = "ASIN2",
            hsn = "5678",
            unitPrice = 50.0,
            qty = 1,
            netAmount = 50.0,
            taxRows = listOf(TaxRow(18.0, "IGST", 9.0)),
            totalAmount = 59.0
        )

        val masterInvoice = ParsedInvoice(
            orderNumber = "ORDER1",
            orderDate = "01.01.2023",
            invoiceNumber = "INV1",
            invoiceDetails = "DETAILS1",
            invoiceDate = "01.01.2023",
            sellerName = "Seller 1",
            sellerAddressLines = listOf("Address 1"),
            panNo = "PAN1",
            gstNo = "GST1",
            billingName = "Billing 1",
            billingAddressLines = listOf("Billing Address 1"),
            billingStateCode = "27",
            shippingName = "Shipping 1",
            shippingAddressLines = listOf("Shipping Address 1"),
            shippingStateCode = "27",
            placeOfSupply = "Maharashtra",
            placeOfDelivery = "Maharashtra",
            paymentTransactionId = "TXN1",
            paymentDateTime = "01.01.2023 10:00:00",
            modeOfPayment = "Credit Card",
            items = listOf(item1, item3)
        )

        val secondInvoice = masterInvoice.copy(
            invoiceNumber = "INV2",
            items = listOf(item2)
        )

        val merged = InvoiceMerger.merge(listOf(masterInvoice, secondInvoice))

        assertEquals(2, merged.items.size)

        val mergedItemA = merged.items.find { it.asin == "ASIN1" }!!
        assertEquals(5, mergedItemA.qty)
        assertEquals(100.0, mergedItemA.unitPrice, 0.001)
        assertEquals(500.0, mergedItemA.netAmount, 0.001)
        assertEquals(2, mergedItemA.taxRows.size)
        assertEquals(45.0, mergedItemA.taxRows[0].amount, 0.001)
        assertEquals(45.0, mergedItemA.taxRows[1].amount, 0.001)
        assertEquals(590.0, mergedItemA.totalAmount, 0.001)

        val mergedItemB = merged.items.find { it.asin == "ASIN2" }!!
        assertEquals(1, mergedItemB.qty)
        assertEquals(50.0, mergedItemB.unitPrice, 0.001)

        assertEquals(550.0, merged.grandNet, 0.001)
        assertEquals(99.0, merged.grandTax, 0.001)
        assertEquals(649.0, merged.grandTotal, 0.001)
        assertEquals("Six Hundred Forty-nine only", merged.amountInWords)
    }
}
