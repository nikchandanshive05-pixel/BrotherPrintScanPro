package com.brother.printscan.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.brother.printscan.processing.ImageProcessor
import com.brother.printscan.processing.PdfDocumentExporter
import com.brother.printscan.protocol.BrotherPrintProtocol
import com.brother.printscan.protocol.BrotherScanProtocol
import com.brother.printscan.protocol.ScanColorMode
import com.brother.printscan.protocol.ScanResolution
import com.brother.printscan.usb.BrotherUsbManager
import com.brother.printscan.usb.UsbConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val originalBitmap: Bitmap,
    val displayBitmap: Bitmap,
    val isBwFilterApplied: Boolean = false
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager = BrotherUsbManager(application)
    val connectionState: StateFlow<UsbConnectionState> = usbManager.connectionState

    private val _scannedPages = MutableStateFlow<List<ScannedPage>>(emptyList())
    val scannedPages: StateFlow<List<ScannedPage>> = _scannedPages.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _operationProgress = MutableStateFlow(0)
    val operationProgress: StateFlow<Int> = _operationProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        usbManager.register()
    }

    override fun onCleared() {
        super.onCleared()
        usbManager.unregister()
    }

    fun startScan(resolution: ScanResolution, mode: ScanColorMode) {
        val conn = usbManager.activeConnection
        val inEp = usbManager.scanEndpointIn
        val outEp = usbManager.scanEndpointOut

        if (conn == null || inEp == null || outEp == null) {
            _statusMessage.value = "Scanner endpoints not ready. Re-plug OTG cable."
            return
        }

        // Claim Interface 1 (Scanner) exclusively before initiating bulk transfers
        val claimed = usbManager.claimScannerInterface()
        if (!claimed) {
            _statusMessage.value = "Failed to claim scanner USB interface. Re-plug OTG cable."
            return
        }

        viewModelScope.launch {
            try {
                _isBusy.value = true
                _statusMessage.value = "Scanning flatbed at ${resolution.dpi} DPI..."
                _operationProgress.value = 0

                val protocol = BrotherScanProtocol(conn, inEp, outEp)
                val bitmap = protocol.performScan(resolution, mode) { progress ->
                    _operationProgress.value = progress
                }

                if (bitmap != null) {
                    val newPage = ScannedPage(originalBitmap = bitmap, displayBitmap = bitmap)
                    _scannedPages.value = _scannedPages.value + newPage
                    _statusMessage.value = "Scan completed successfully!"
                } else {
                    _statusMessage.value = "Scan failed or was interrupted."
                }
            } finally {
                usbManager.releaseScannerInterface()
                _isBusy.value = false
            }
        }
    }

    fun toggleBwFilter(pageId: String) {
        val currentList = _scannedPages.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == pageId }
        if (index != -1) {
            val page = currentList[index]
            val newApplied = !page.isBwFilterApplied
            val newDisplay = if (newApplied) {
                ImageProcessor.applyHighContrastDocumentFilter(page.originalBitmap)
            } else {
                page.originalBitmap
            }
            currentList[index] = page.copy(
                isBwFilterApplied = newApplied,
                displayBitmap = newDisplay
            )
            _scannedPages.value = currentList
        }
    }

    fun rotatePage(pageId: String) {
        val currentList = _scannedPages.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == pageId }
        if (index != -1) {
            val page = currentList[index]
            val rotatedOrig = ImageProcessor.rotate(page.originalBitmap, 90f)
            val rotatedDisp = ImageProcessor.rotate(page.displayBitmap, 90f)
            currentList[index] = page.copy(
                originalBitmap = rotatedOrig,
                displayBitmap = rotatedDisp
            )
            _scannedPages.value = currentList
        }
    }

    fun removePage(pageId: String) {
        _scannedPages.value = _scannedPages.value.filter { it.id != pageId }
    }

    fun clearAllPages() {
        _scannedPages.value = emptyList()
    }

    fun exportAndSharePdf(context: Context, documentTitle: String) {
        val pages = _scannedPages.value.map { it.displayBitmap }
        if (pages.isEmpty()) {
            Toast.makeText(context, "No scanned pages to export", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            _isBusy.value = true
            _statusMessage.value = "Generating A4 PDF..."
            val pdfFile = PdfDocumentExporter.exportToPdf(context, pages, documentTitle)
            _isBusy.value = false

            if (pdfFile != null && pdfFile.exists()) {
                _statusMessage.value = "PDF Exported: ${pdfFile.name}"
                sharePdfFile(context, pdfFile)
            } else {
                _statusMessage.value = "Failed to export PDF"
            }
        }
    }

    private fun sharePdfFile(context: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Scanned PDF"))
    }

    fun printCurrentPage(bitmap: Bitmap, copies: Int = 1) {
        val conn = usbManager.activeConnection
        val outEp = usbManager.printEndpointOut

        if (conn == null || outEp == null) {
            _statusMessage.value = "Printer not connected over USB"
            return
        }

        val claimed = usbManager.claimPrinterInterface()
        if (!claimed) {
            _statusMessage.value = "Failed to claim printer USB interface"
            return
        }

        viewModelScope.launch {
            try {
                _isBusy.value = true
                _statusMessage.value = "Sending print job to Brother printer..."
                _operationProgress.value = 0

                val printProtocol = BrotherPrintProtocol(conn, outEp)
                val success = printProtocol.printBitmap(bitmap, copies) { progress ->
                    _operationProgress.value = progress
                }

                _statusMessage.value = if (success) "Print job completed!" else "Print failed"
            } finally {
                _isBusy.value = false
            }
        }
    }
}
