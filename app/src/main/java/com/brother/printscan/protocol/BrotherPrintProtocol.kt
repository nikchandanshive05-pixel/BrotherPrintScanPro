package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Brother GDI / HBP (Host-Based Printing) Protocol Implementation.
 * Reverse-Engineered directly from NokoPrint (com.nokoprint.core.u) for Brother Laser Printers
 * (DCP-B7500D, DCP-L2500D, HL-L2300D series, etc. / application/vnd.brother-hbp).
 */
class BrotherPrintProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val TAG = "BrotherPrintProtocol"
        private const val USB_TIMEOUT_MS = 10000
        private const val ESC = 0x1B.toByte()
    }

    /**
     * Sends PJL RESET and EOJ to immediately clear any pending "Receiving Data" lockup.
     */
    suspend fun resetPrinter(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending PJL RESET to clear printer state...")
            val stream = ByteArrayOutputStream()
            stream.write("\u001B%-12345X@PJL\r\n".toByteArray(Charsets.US_ASCII))
            stream.write("@PJL RESET\r\n".toByteArray(Charsets.US_ASCII))
            stream.write("@PJL EOJ NAME=\"Android\"\r\n".toByteArray(Charsets.US_ASCII))
            stream.write("\u001B%-12345X".toByteArray(Charsets.US_ASCII))
            
            val resetBytes = stream.toByteArray()
            clearEndpointHalt(endpointOut.address)
            val rc = connection.bulkTransfer(endpointOut, resetBytes, resetBytes.size, 3000)
            Log.d(TAG, "Reset command sent: $rc bytes")
            rc >= 0
        } catch (e: Exception) {
            Log.w(TAG, "Reset failed: ${e.message}")
            false
        }
    }

    /**
     * Converts a bitmap into Brother GDI raster stream matching NokoPrint com.nokoprint.core.u.
     * Compatible with Brother DCP-B7500D, DCP-L2500D, HL-L2300D and all Brother GDI / HBP models.
     */
    suspend fun printBitmap(
        bitmap: Bitmap,
        copies: Int = 1,
        paperSize: String = "A4",
        isDuplex: Boolean = false,
        isEcoMode: Boolean = false,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val width = bitmap.width
            val height = bitmap.height
            val rowBytes = (width + 7) / 8
            val rasterWidthDots = rowBytes * 8

            Log.d(TAG, "Starting Brother GDI Print: ${width}x${height} (rowBytes=$rowBytes, rasterDots=$rasterWidthDots), Paper: $paperSize, Copies: $copies")
            onProgress(5)

            // Clear any lingering endpoint halt
            clearEndpointHalt(endpointOut.address)

            for (copy in 1..copies) {
                val stream = ByteArrayOutputStream()

                // ==========================================
                // 1. Brother PJL Job Initialization Header
                // ==========================================
                stream.write("\u001B%-12345X@PJL JOB NAME=\"Android\"\r\n".toByteArray(Charsets.US_ASCII))
                if (isEcoMode) {
                    stream.write("@PJL SET ECONOMODE=ON\r\n".toByteArray(Charsets.US_ASCII))
                } else {
                    stream.write("@PJL SET ECONOMODE=OFF\r\n".toByteArray(Charsets.US_ASCII))
                }

                if (isDuplex) {
                    stream.write("@PJL SET DUPLEX=ON\r\n".toByteArray(Charsets.US_ASCII))
                    stream.write("@PJL SET BINDING=LONGEDGE\r\n".toByteArray(Charsets.US_ASCII))
                } else {
                    stream.write("@PJL SET DUPLEX=OFF\r\n".toByteArray(Charsets.US_ASCII))
                }

                // Crucial: Brother Laser GDI Printers require LANGUAGE=GDI (NOT RASTER or PCL)
                stream.write("@PJL ENTER LANGUAGE=GDI\r\n".toByteArray(Charsets.US_ASCII))

                // ==========================================
                // 2. Brother GDI Page Setup Sequence
                // (Extracted from NokoPrint com.nokoprint.core.u lines 0x04e8..0x079e)
                // ==========================================
                stream.write(ESC.toInt())
                stream.write("&&0S".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                stream.write("&r${width}W".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                stream.write("&r${height}L".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                stream.write("&&0Y".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                if (paperSize.equals("A4", ignoreCase = true)) {
                    stream.write("&l26A".toByteArray(Charsets.US_ASCII)) // A4 Paper
                } else {
                    stream.write("&l2A".toByteArray(Charsets.US_ASCII))  // Letter Paper
                }

                stream.write(ESC.toInt())
                stream.write("&l2H".toByteArray(Charsets.US_ASCII)) // Auto Paper Source Tray

                stream.write(ESC.toInt())
                stream.write("&&11W".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                stream.write("*r${height}T".toByteArray(Charsets.US_ASCII))

                stream.write(ESC.toInt())
                stream.write("*r${rasterWidthDots}S".toByteArray(Charsets.US_ASCII))

                // Margin adjustment: Left margin
                stream.write(ESC.toInt())
                if (paperSize.equals("A4", ignoreCase = true)) {
                    stream.write("&a96L".toByteArray(Charsets.US_ASCII)) // 96 for standard 600dpi
                } else {
                    stream.write("&a102L".toByteArray(Charsets.US_ASCII))
                }

                // Top margin
                stream.write(ESC.toInt())
                stream.write("&l100E".toByteArray(Charsets.US_ASCII))

                // Start Raster Data
                stream.write(ESC.toInt())
                stream.write("&&0T".toByteArray(Charsets.US_ASCII))

                // ==========================================
                // 3. Raster Line Transmission
                // ==========================================
                val pixels = IntArray(width)
                val lineBuffer = ByteArray(rowBytes)
                val header16 = ByteArray(16)

                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

                    var isBlank = true
                    var currentByte = 0
                    var bitPos = 7
                    var byteIdx = 0

                    for (x in 0 until width) {
                        val color = pixels[x]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        // Fast integer luminance matching NokoPrint: (306*R + 601*G + 117*B) >> 10
                        val gray = (306 * r + 601 * g + 117 * b) shr 10

                        // 1 = black pixel (print toner), 0 = white pixel (blank paper)
                        if (gray < 128) {
                            currentByte = currentByte or (1 shl bitPos)
                            isBlank = false
                        }

                        bitPos--
                        if (bitPos < 0) {
                            lineBuffer[byteIdx++] = currentByte.toByte()
                            currentByte = 0
                            bitPos = 7
                        }
                    }
                    if (bitPos != 7 && byteIdx < rowBytes) {
                        lineBuffer[byteIdx++] = currentByte.toByte()
                    }

                    if (isBlank) {
                        // NokoPrint optimization: blank line is sent as ESC *b0W
                        stream.write(ESC.toInt())
                        stream.write("*b0W".toByteArray(Charsets.US_ASCII))
                    } else {
                        // 16-byte HBP Raster Block Header:
                        // [0..1] = num_lines (1 in little endian)
                        // [2..3] = 0
                        // [4..5] = compression mode (0 = uncompressed raw bitmap)
                        // [6..7] = 0
                        // [8..9] = 0
                        // [10..11] = payload length (rowBytes in little endian)
                        // [12..15] = 0
                        header16[0] = 0x01
                        header16[1] = 0x00
                        header16[2] = 0x00
                        header16[3] = 0x00
                        header16[4] = 0x00
                        header16[5] = 0x00
                        header16[6] = 0x00
                        header16[7] = 0x00
                        header16[8] = 0x00
                        header16[9] = 0x00
                        header16[10] = (rowBytes and 0xFF).toByte()
                        header16[11] = ((rowBytes shr 8) and 0xFF).toByte()
                        header16[12] = 0x00
                        header16[13] = 0x00
                        header16[14] = 0x00
                        header16[15] = 0x00

                        stream.write(header16)
                        stream.write(lineBuffer, 0, rowBytes)
                    }

                    if (y % 150 == 0) {
                        val progress = 5 + ((y * 80) / height)
                        onProgress(progress)
                    }
                }

                // ==========================================
                // 4. Form Feed / Page Eject (ESC &&1R)
                // ==========================================
                stream.write(ESC.toInt())
                stream.write("&&1R".toByteArray(Charsets.US_ASCII))

                // ==========================================
                // 5. Job Closure (PJL EOJ)
                // ==========================================
                stream.write(ESC.toInt())
                stream.write("%-12345X".toByteArray(Charsets.US_ASCII))
                stream.write("@PJL EOJ NAME=\"Android\"\r\n".toByteArray(Charsets.US_ASCII))
                stream.write(ESC.toInt())
                stream.write("%-12345X".toByteArray(Charsets.US_ASCII))

                val jobData = stream.toByteArray()
                Log.d(TAG, "Brother GDI Stream size: ${jobData.size} bytes. Transmitting via Bulk OUT 0x${endpointOut.address.toString(16)}...")

                // ==========================================
                // 6. Transmit to USB Bulk Endpoint (4096-byte chunks)
                // ==========================================
                val chunkSize = 4096
                var offset = 0
                while (offset < jobData.size) {
                    val length = (jobData.size - offset).coerceAtMost(chunkSize)
                    val chunk = jobData.copyOfRange(offset, offset + length)
                    var written = connection.bulkTransfer(endpointOut, chunk, length, USB_TIMEOUT_MS)
                    if (written < 0) {
                        Log.w(TAG, "Bulk transfer stall at offset $offset (rc=$written), clearing halt and retrying...")
                        clearEndpointHalt(endpointOut.address)
                        kotlinx.coroutines.delay(100)
                        written = connection.bulkTransfer(endpointOut, chunk, length, USB_TIMEOUT_MS)
                    }
                    if (written < 0) {
                        Log.e(TAG, "Fatal USB Bulk write error: rc=$written at $offset / ${jobData.size}")
                        return@withContext false
                    }
                    offset += length
                    
                    val progress = 85 + ((offset * 15) / jobData.size)
                    onProgress(progress)
                }
            }

            onProgress(100)
            Log.d(TAG, "Print job completed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Print failed", e)
            false
        }
    }

    private fun clearEndpointHalt(endpointAddress: Int) {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, endpointAddress, null, 0, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "clearEndpointHalt error: ${e.message}")
        }
    }
}
