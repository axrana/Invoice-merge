package com.akshansh.invoicemerger

/**
 * A single tax row (e.g. "9% CGST ₹6.48" or "2.5% CGST ₹16.76" or "18% IGST ₹18.00").
 * An invoice line item has either two of these (CGST+SGST, intra-state)
 * or one (IGST, inter-state). Rate is a Double since some product
 * categories (e.g. packaged food) use fractional GST rates like 2.5%.
 */
data class TaxRow(
    val rate: Double,
    val type: String,
    val amount: Double
)

/**
 * A single line item as extracted from one source invoice PDF.
 */
data class InvoiceLineItem(
    val slNo: String,
    val description: String,
    val asin: String,
    val hsn: String?,
    val unitPrice: Double,
    val qty: Int,
    val netAmount: Double,
    val taxRows: List<TaxRow>,
    val totalAmount: Double
)

/**
 * All data extracted from a single source invoice PDF.
 */
data class ParsedInvoice(
    val orderNumber: String,
    val orderDate: String,
    val invoiceNumber: String,
    val invoiceDetails: String,
    val invoiceDate: String,
    val sellerName: String?,
    val sellerAddressLines: List<String>,
    val panNo: String?,
    val gstNo: String?,
    val billingName: String?,
    val billingAddressLines: List<String>,
    val billingStateCode: String?,
    val shippingName: String?,
    val shippingAddressLines: List<String>,
    val shippingStateCode: String?,
    val placeOfSupply: String?,
    val placeOfDelivery: String?,
    val items: List<InvoiceLineItem>
)

/**
 * A single merged/consolidated line item after grouping by (ASIN, unitPrice).
 */
data class MergedLineItem(
    val slNo: Int,
    val description: String,
    val asin: String,
    val hsn: String?,
    val unitPrice: Double,
    val qty: Int,
    val netAmount: Double,
    val taxRows: List<TaxRow>,
    val totalAmount: Double
)

/**
 * The final consolidated invoice, ready for rendering to PDF.
 * Header/seller/billing/shipping/order metadata comes from the FIRST source invoice.
 */
data class MergedInvoice(
    val master: ParsedInvoice,
    val items: List<MergedLineItem>,
    val grandNet: Double,
    val grandTax: Double,
    val grandTotal: Double,
    val amountInWords: String,
    val sourceInvoiceCount: Int
)
