# Amazon Invoice Merger

Android app that merges multiple same-day Amazon.in tax invoice PDFs into one
consolidated invoice — replicating the original Amazon layout (A4, logo,
bordered table, CGST/SGST split rows, signatory block, payment details).

## How it works

1. Pick 2+ Amazon invoice PDFs (text-based, not scanned images)
2. App extracts order/seller/billing/shipping info + all line items from each
3. Items are merged by matching **ASIN + unit price**:
   - Same ASIN + same price → combined into one row, quantities summed,
     amounts recalculated
   - Same ASIN + different price → kept as separate rows
4. Header/seller/billing/shipping/order metadata is taken from the **first**
   selected invoice
5. Grand total and "Amount in Words" are recalculated for the merged invoice
6. Output PDF is saved to your phone's Downloads folder and a share sheet pops up

## Before building: add your logo

Drop your `amazon.in` logo PNG into:

```
app/src/main/assets/amazon_logo.png
```

(must be named exactly `amazon_logo.png`). If this file is missing, the app
falls back to plain text "amazon.in" in the header — it will still work, just
without the logo image.

## Building the APK

This repo builds automatically via GitHub Actions on every push to `main`.

1. After pushing changes (e.g. adding your logo, or any edits via GitHub's
   web editor), go to the **Actions** tab on GitHub
2. Wait for the "Build APK" workflow to finish (green checkmark)
3. Click into the workflow run → scroll to **Artifacts** → download
   `app-debug-apk`
4. Unzip it, transfer `app-debug.apk` to your phone, and install
   (you'll need to allow "install from unknown sources" the first time)

## Known limitations (v1)

- Only works with **text-based** Amazon invoice PDFs (not scanned/photographed ones)
- Handles both intra-state (CGST+SGST, 2 tax rows) and inter-state (IGST,
  1 tax row) invoices, detected automatically per line item — no manual
  selection needed
- **Rounding edge case:** in rare cases, the recalculated tax amount on a
  merged row may differ from Amazon's original by ₹0.01–0.02. This happens
  because Amazon's internal calculation uses unrounded intermediate values
  we don't have access to (we only see the rounded unit price on the PDF),
  so recalculating tax from the displayed unit price can occasionally round
  differently. The grand total is correct; only the individual tax-row split
  on a recalculated row might be off by a paisa or two in edge cases.
- If invoices have different sellers/GSTINs, the app silently uses the
  first invoice's seller details for the merged output (by design, per your
  spec) — it does not warn you about mismatches.
- "Payment Transaction ID" and "Mode of Payment" in the merged output are
  placeholders ("Merged Invoice (N orders)" / "See source invoices") since
  a consolidated invoice spanning multiple original payments can't carry one
  single transaction ID truthfully.

## Architecture (for future edits)

- `InvoiceParser.kt` — extracts text from PDF (PdfBox-Android) and parses it
  into structured data (regex-based, line-by-line block parsing for line items)
- `InvoiceMerger.kt` — groups/sums line items by (ASIN, unitPrice); also
  contains `AmountToWords` (Indian numbering system: Crore/Lakh/Thousand)
- `InvoicePdfRenderer.kt` — draws the merged invoice to a new PDF via
  Android's `PdfDocument`/`Canvas`, replicating Amazon's layout
- `MainActivity.kt` — file picker UI, ties parser → merger → renderer together

For small fixes, edit directly via GitHub's web editor (per Akshansh's usual
workflow) and push to `main` — the Actions workflow rebuilds the APK
automatically.
