package com.brother.printscan.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PdfDocumentExporter {

    // Standard A4 dimensions in typographic points (72 points per inch)
    // 8.27 in x 11.69 in => ~595 x 842 points
    private const val A4_PAGE_WIDTH = 595
    private const val A4_PAGE_HEIGHT = 842

    suspend fun exportToPdf(
        context: Context,
        pages: List<Bitmap>,
        documentTitle: String = "Scanned_Document"
    ): File? = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) return@withContext null

        val pdfDocument = PdfDocument()

        try {
            for ((index, bitmap) in pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_PAGE_WIDTH, A4_PAGE_HEIGHT, index + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                val canvas: Canvas = page.canvas

                // Maintain aspect ratio while fitting into A4
                val bmpWidth = bitmap.width.toFloat()
                val bmpHeight = bitmap.height.toFloat()

                val scale = (A4_PAGE_WIDTH / bmpWidth).coerceAtMost(A4_PAGE_HEIGHT / bmpHeight)
                val targetW = bmpWidth * scale
                val targetH = bmpHeight * scale

                val left = (A4_PAGE_WIDTH - targetW) / 2f
                val top = (A4_PAGE_HEIGHT - targetH) / 2f
                val destRect = RectF(left, top, left + targetW, top + targetH)

                canvas.drawBitmap(bitmap, null, destRect, null)
                pdfDocument.finishPage(page)
            }

            // Save PDF to app documents directory
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val docsDir = File(context.filesDir, "scans").apply { mkdirs() }
            val pdfFile = File(docsDir, "${documentTitle}_$timeStamp.pdf")

            FileOutputStream(pdfFile).use { out ->
                pdfDocument.writeTo(out)
            }

            pdfFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            pdfDocument.close()
        }
    }
}
