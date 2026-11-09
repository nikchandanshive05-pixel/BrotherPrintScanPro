package com.brother.printscan.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

object ImageProcessor {

    /**
     * Converts a bitmap to high-contrast document Black & White.
     * Enhances readability of scanned forms, books, and receipts.
     */
    fun applyHighContrastDocumentFilter(src: Bitmap, threshold: Int = 145): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

            pixels[i] = if (luminance > threshold) -0x1 else -0x1000000 // White vs Black
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Converts an image to smooth grayscale.
     */
    fun applyGrayscale(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()

        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        return output
    }

    /**
     * Crops a rectangular portion of the document.
     */
    fun crop(src: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        val safeX = x.coerceIn(0, src.width - 1)
        val safeY = y.coerceIn(0, src.height - 1)
        val safeW = width.coerceIn(1, src.width - safeX)
        val safeH = height.coerceIn(1, src.height - safeY)
        return Bitmap.createBitmap(src, safeX, safeY, safeW, safeH)
    }

    /**
     * Rotates bitmap by 90-degree increments.
     */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
