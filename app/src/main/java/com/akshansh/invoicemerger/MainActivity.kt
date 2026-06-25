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
    }

    private fun refreshFileList() {
        adapter.notifyDataSetChanged()
        selectedCountText.text = if (selectedFiles.isEmpty()) {
            "No files selected"
        } else {
            "${selectedFiles.size} file(s) selected"
        }
        mergeButton.isEnabled = selectedFiles.size >= 2
        statusText.text = ""
    }

    private fun performMerge() {
        statusText.text = "Reading and parsing invoices..."
        mergeButton.isEnabled = false

        try {
            val parser = InvoiceParser(this)
            val parsedInvoices = selectedFiles.map { (uri, name) ->
                try {
                    val text = parser.extractText(uri)
                    parser.parseInvoice(text)
                } catch (e: Exception) {
                    throw ParseException("Failed to parse '$name': ${e.message}")
                }
            }

            statusText.text = "Merging ${parsedInvoices.size} invoices..."
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
            mergeButton.isEnabled = selectedFiles.size >= 2
        }
    }

    /**
     * Loads the amazon.in logo from app assets. The logo PNG should be placed
     * at app/src/main/assets/amazon_logo.png by Akshansh before building.
     */
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

    private fun offerToShare(uri: Uri) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share merged invoice"))
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
