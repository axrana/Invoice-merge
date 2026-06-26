package com.akshansh.invoicemerger

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val selectedFiles = mutableListOf<Pair<Uri, String>>()
    private lateinit var adapter: FileListAdapter

    private lateinit var pickFilesButton: Button
    private lateinit var mergeButton: Button
    private lateinit var debugExportButton: Button
    private lateinit var selectedCountText: TextView
    private lateinit var statusText: TextView
    private lateinit var fileListRecycler: RecyclerView

    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // some providers don't support persistable permissions; ignore
                }
                val name = queryFileName(uri) ?: "invoice_${selectedFiles.size + 1}.pdf"
                selectedFiles.add(uri to name)
            }
            refreshFileList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // PdfBox-Android requires resource loader init before any PDDocument use
        PDFBoxResourceLoader.init(applicationContext)

        pickFilesButton = findViewById(R.id.pickFilesButton)
        mergeButton = findViewById(R.id.mergeButton)
        debugExportButton = findViewById(R.id.debugExportButton)
        selectedCountText = findViewById(R.id.selectedCountText)
        statusText = findViewById(R.id.statusText)
        fileListRecycler = findViewById(R.id.fileListRecycler)

        adapter = FileListAdapter(selectedFiles) { position ->
            selectedFiles.removeAt(position)
            refreshFileList()
        }
        fileListRecycler.layoutManager = LinearLayoutManager(this)
        fileListRecycler.adapter = adapter

        pickFilesButton.setOnClickListener {
            pickFilesLauncher.launch(arrayOf("application/pdf"))
        }

        mergeButton.setOnClickListener {
            performMerge()
        }

        debugExportButton.setOnClickListener {
            exportRawTextPositions()
        }
    }

    private fun refreshFileList() {
        adapter.notifyDataSetChanged()
        selectedCountText.text = if (selectedFiles.isEmpty()) {
            "No files selected"
        } else {
            "${selectedFiles.size} file(s) selected"
        }
        mergeButton.isEnabled = selectedFiles.size >= 1
        debugExportButton.isEnabled = selectedFiles.size >= 1
        statusText.text = ""
    }

    private fun performMerge() {
        statusText.text = "Reading and parsing invoices..."
        mergeButton.isEnabled = false

        try {
            val parser = InvoiceParser(this)
            val parsedInvoices = selectedFiles.flatMap { (uri, name) ->
                try {
                    parser.parseAllInvoices(uri)
                } catch (e: Exception) {
                    throw ParseException("Failed to parse '$name': ${e.message}")
                }
            }

            statusText.text = "Merging ${parsedInvoices.size} invoice(s)..."
            val merged = InvoiceMerger.merge(parsedInvoices)

            statusText.text = "Rendering merged PDF..."
            val logoBitmap = loadLogoBitmap()

            val outputFileName = "Merged_Invoice_${merged.master.invoiceNumber}_${timestamp()}.pdf"
            val outputUri = createOutputFile(outputFileName)

            val renderer = InvoicePdfRenderer(this)
            renderer.render(merged, logoBitmap, outputUri)

            statusText.text = "Done! Saved as $outputFileName"
            Toast.makeText(this, "Merged invoice created", Toast.LENGTH_SHORT).show()

            offerToShare(outputUri)

        } catch (e: ParseException) {
            statusText.text = "Error: ${e.message}"
            Toast.makeText(this, "Parsing failed - see status message", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            statusText.text = "Unexpected error: ${e.message}"
            Toast.makeText(this, "Merge failed - see status message", Toast.LENGTH_LONG).show()
        } finally {
            mergeButton.isEnabled = selectedFiles.size >= 1
        }
    }

    /**
     * Loads the amazon.in logo from app assets. The logo PNG should be placed
     * at app/src/main/assets/amazon_logo.png by Akshansh before building.
     */
    /**
     * Debug helper: dumps the raw character-level text positions (as seen
     * by PdfBox-Android on THIS device) for the first selected PDF to a
     * text file, saved to Downloads. Used to diagnose extraction
     * discrepancies between this on-device library and the Python
     * prototype used to validate the parsing logic - share the resulting
     * file to compare exactly what coordinates PdfBox-Android reports for
     * a problem invoice.
     */
    private fun exportRawTextPositions() {
        if (selectedFiles.isEmpty()) return
        statusText.text = "Exporting raw text positions..."

        try {
            val (uri, name) = selectedFiles.first()
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open $name")

            val dumps = inputStream.use { stream ->
                com.tom_roush.pdfbox.pdmodel.PDDocument.load(stream).use { document ->
                    val extractor = WordExtractor()
                    extractor.dumpRawCharsByPage(document)
                }
            }

            val outputFileName = "debug_raw_chars_${timestamp()}.txt"
            val outputUri = createTextOutputFile(outputFileName)
            contentResolver.openOutputStream(outputUri)?.use { out ->
                dumps.forEachIndexed { pageIdx, pageText ->
                    out.write("=== PAGE ${pageIdx + 1} (source: $name) ===\n".toByteArray())
                    out.write("text\tx0\tx1\ttop\n".toByteArray())
                    out.write(pageText.toByteArray())
                    out.write("\n\n".toByteArray())
                }
            }

            statusText.text = "Exported to Downloads/$outputFileName"
            offerToShare(outputUri, "text/plain")

        } catch (e: Exception) {
            statusText.text = "Debug export failed: ${e.message}"
            Toast.makeText(this, "Export failed - see status message", Toast.LENGTH_LONG).show()
        }
    }

    private fun createTextOutputFile(fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Could not create output file in Downloads")
    }

    private fun loadLogoBitmap(): android.graphics.Bitmap? {
        return try {
            assets.open("amazon_logo.png").use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            null // renderer falls back to text "amazon.in" if logo not found
        }
    }

    private fun createOutputFile(fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Could not create output file in Downloads")
    }

    private fun offerToShare(uri: Uri, mimeType: String = "application/pdf") {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share file"))
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) it.getString(nameIndex) else null
        }
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
