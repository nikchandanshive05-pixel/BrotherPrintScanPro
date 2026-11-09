package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Handles raw document printing to Brother Laser Printers over USB Bulk transfers.
 * Implements Brother Raster / standard PCL stream formatting.
 */
class BrotherPrintProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val TAG = "BrotherPrintProtocol"
        private const val USB_TIMEOUT_MS = 10000
    }

    /**
     * Converts a bitmap into monochrome raster lines and streams to the Brother printer.
     */
    suspend fun printBitmap(
        bitmap: Bitmap,
        copies: Int = 1,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting print job: ${bitmap.width}x${bitmap.height}, Copies: $copies")
            onProgress(10)

            for (c in 1..copies) {
                val stream = ByteArrayOutputStream()

                // Universal Brother / PJL initialization header
                stream.write("\u001B%-12345X@PJL\r\n".toByteArray(Charsets.US_ASCII))
                stream.write("@PJL SET JOBNAME = \"Mobile OTG Document\"\r\n".toByteArray(Charsets.US_ASCII))
                stream.write("@PJL ENTER LANGUAGE = RASTER\r\n".toByteArray(Charsets.US_ASCII))

                // Brother Raster Mode Initialization
                stream.write(byteArrayOf(0x1B, 0x69, 0x61, 0x01)) // ESC i a (Switch to raster mode)

                val width = bitmap.width
                val height = bitmap.height
                val rowBytes = (width + 7) / 8

                val pixels = IntArray(width)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

                    // Raster line transfer command: 'g' (0x67) + length low/high
                    stream.write(0x67)
                    stream.write(0x00)
                    stream.write(rowBytes and 0xFF)
                    stream.write((rowBytes shr 8) and 0xFF)

                    // Pack 8 pixels per byte (1 = black, 0 = white for Brother laser engine)
                    var currentByte = 0
                    var bitPos = 7
                    for (x in 0 until width) {
                        val color = pixels[x]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                        if (luminance < 128) {
                            currentByte = currentByte or (1 shl bitPos)
                        }

                        bitPos--
                        if (bitPos < 0) {
                            stream.write(currentByte)
                            currentByte = 0
                            bitPos = 7
                        }
                    }
                    if (bitPos != 7) {
                        stream.write(currentByte)
                    }

                    if (y % 100 == 0) {
                        val progress = 10 + ((y * 70) / height)
                        onProgress(progress)
                    }
                }

                // Page Form Feed (0x0C)
                stream.write(0x0C)
                // PJL Job Terminate
                stream.write("\u001B%-12345X".toByteArray(Charsets.US_ASCII))

                val jobData = stream.toByteArray()
                Log.d(TAG, "Streaming page raster: ${jobData.size} bytes")

                // Transmit in chunks over USB Bulk Out
                val chunkSize = 4096
                var offset = 0
                while (offset < jobData.size) {
                    val length = (jobData.size - offset).coerceAtMost(chunkSize)
                    val chunk = jobData.copyOfRange(offset, offset + length)
                    val written = connection.bulkTransfer(endpointOut, chunk, length, USB_TIMEOUT_MS)
                    if (written < 0) {
                        Log.e(TAG, "Bulk transfer error while printing")
                        return@withContext false
                    }
                    offset += length
                }
            }

            onProgress(100)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Print job failed", e)
            false
        }
    }
}
