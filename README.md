# Amazon Invoice Merger

Android app that merges multiple same-day Amazon.in tax invoice PDFs into one
consolidated invoice — replicating the original Amazon layout (A4, logo,
bordered table, CGST/SGST split rows, signatory block, payment details).

## How it works

1. Pick one or more Amazon invoice PDFs (text-based, not scanned images)
2. App extracts order/seller/billing/shipping/payment info + all line items
   from each PDF using **coordinate-based parsing** (reads actual text
   position on the page, not just raw text) — this makes it robust to the
   different ways Amazon's invoice generator lays out text across invoice
   template versions
3. **A single PDF can contain multiple invoices** (e.g. one Amazon order
   fulfilled by multiple sellers bundles each seller's invoice into the same
   PDF download) — the app automatically detects and separates these by
   checking which pages have their own "Invoice Number :" field
4. Items are merged by matching **ASIN + unit price**:
   - Same ASIN + same price → combined into one row, quantities summed,
     amounts recalculated
   - Same ASIN + different price → kept as separate rows
5. Both **CGST+SGST** (intra-state, 2 tax rows) and **IGST** (inter-state,
   1 tax row) invoices are supported, including **fractional tax rates**
   like 2.5% (common for packaged food items)
6. Header/seller/billing/shipping/order/**payment** metadata (Payment
   Transaction ID, Date & Time, Mode of Payment) is taken from the **first**
   invoice found (across all selected files, in order). If the first
   invoice has no payment section at all (this happens on some fully
   zero-tax orders), the payment table is simply omitted from the output
   rather than showing placeholder text.
7. Grand total and "Amount in Words" are recalculated for the merged invoice
8. The output PDF replicates Amazon's exact typography: **Nimbus Sans**
   (a metric-compatible, freely-licensed Helvetica substitute) for all
   labels and text, and **Playfair Display** for every numeric/monetary
   value in the table — matching the real invoice's font choices, sizes,
   and bold/regular weighting throughout (including the
   bold-label-plus-regular-value style used for fields like
   "Order Number: 171-...")
9. Output PDF is saved to your phone's Downloads folder and a share sheet pops up

## Fonts

Two font families are bundled in `app/src/main/assets/fonts/` (both
freely licensed for embedding — see the `*-LICENSE.txt` files alongside
them):

- **Nimbus Sans** (Regular + Bold) — a metric-compatible Helvetica clone,
  used for all labels, addresses, descriptions, and table header text
- **Playfair Display** (Regular + Bold) — used for every numeric/monetary
  value inside the line-items table (unit price, qty, net amount, tax
  rate/amount, total amount), matching Amazon's actual invoice typography
  exactly. Bold is used specifically for the TOTAL row's figures.

If you ever need to swap a font, just replace the `.ttf` file at the same
path/filename — the renderer loads them by path, not by any font-matching
heuristic.

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

## Known limitations (v2)

- Only works with **text-based** Amazon invoice PDFs (not scanned/photographed ones)
- Handles both intra-state (CGST+SGST, 2 tax rows) and inter-state (IGST,
  1 tax row) invoices, and fractional tax rates (e.g. 2.5%), detected
  automatically per line item — no manual selection needed
- Handles PDFs with multiple invoices bundled together (multi-seller orders)
  by detecting pages with their own Invoice Number field
- Table column boundaries (where "description" ends and "unit price"
  begins, etc.) are now detected fresh from each invoice's own header row
  rather than using fixed pixel positions — this was a real bug in an
  earlier version (different invoices had table columns shifted by 10-15+
  points, depending on content) and is now fixed. If a future invoice has
  an even more unusual table structure (e.g. missing a "Total" or "Unit"
  header label entirely), the parser falls back to a default boundary,
  which could misread fields — send the PDF if a merge looks wrong
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
- "Payment Transaction ID", "Date & Time", and "Mode of Payment" in the
  merged output are taken directly from the **first** source invoice (by
  design) — these reflect that one invoice's payment, not a true
  consolidated record of all payments across merged invoices. If the first
  invoice has no payment section at all (seen on some ₹0-tax orders), the
  payment table is omitted from the output entirely rather than showing
  placeholder text.

## Architecture (for future edits)

- `WordExtractor.kt` — custom PdfBox text stripper that extracts every word
  with its x/y position on the page (not just plain text), which everything
  else is built on
- `InvoiceParser.kt` — uses word positions to: split the header into seller
  vs. billing/shipping columns (works whether the source PDF's text columns
  are cleanly separated or interleaved on the same line), detect multiple
  invoices within one PDF, derive the line-items table's column boundaries
  fresh from each invoice's own header row (Amazon's table column widths
  shift between invoices, so a fixed boundary that works for one invoice
  can misread fields on another), and parse the table itself (handles tax
  type/amount tokens appearing in varying order, and values that get
  concatenated with no separator in some PDF generations)
- `InvoiceMerger.kt` — groups/sums line items by (ASIN, unitPrice); also
  contains `AmountToWords` (Indian numbering system: Crore/Lakh/Thousand)
- `InvoicePdfRenderer.kt` — draws the merged invoice to a new PDF via
  Android's `PdfDocument`/`Canvas`, replicating Amazon's layout
- `MainActivity.kt` — file picker UI, ties parser → merger → renderer together

For small fixes, edit directly via GitHub's web editor (per Akshansh's usual
workflow) and push to `main` — the Actions workflow rebuilds the APK
automatically. If a future invoice fails to parse correctly, the most useful
thing to check first is `InvoiceParser.kt`'s column boundary constants
(`splitX`, `descriptionColMaxX`, `totalColMinX`) — these were tuned against
two real invoice samples and may need slight adjustment for a new template.
