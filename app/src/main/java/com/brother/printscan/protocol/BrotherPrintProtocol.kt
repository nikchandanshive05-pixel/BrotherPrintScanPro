package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Brother DCP-B7500D PCL5e Raster Printing Protocol.
 *
 * The DCP-B7500D natively supports PCL6 (PCL XL) and BR-Script3 (PostScript 3).
 * It also supports PCL5e backward-compatibility mode for raster graphics.
 *
 * Protocol flow:
 *   1. UEL + PJL header to set job parameters and enter PCL mode
 *   2. PCL5 printer reset (ESC E)
 *   3. Set resolution, page size, orientation
 *   4. Position cursor to top-left printable area
 *   5. Enter raster graphics mode (ESC*r1A)
 *   6. Set compression mode (0 = uncompressed)
 *   7. Transfer each scanline via ESC*b<N>W<data>
 *   8. End raster (ESC*rC)
 *   9. Form feed (0x0C)
 *  10. PCL reset + UEL/PJL EOJ
 *
 * This replaces the previous broken Brother GDI/HBP implementation which
 * caused garbled output and extra pages because DCP-B7500D does NOT support GDI.
 */
class BrotherPrintProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val TAG = "BrotherPrintProtocol"
        private const val USB_TIMEOUT_MS = 10000
        private const val ESC = 0x1B
    }

    /**
     * Sends PJL RESET + PCL Reset to immediately clear any pending print state.
     */
    suspend fun resetPrinter(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Sending PJL RESET + PCL Reset to clear printer state...")
            val stream = ByteArrayOutputStream()

            // UEL (Universal Exit Language) + PJL Reset
            stream.writeAscii("\u001B%-12345X@PJL\r\n")
            stream.writeAscii("@PJL RESET\r\n")
            stream.writeAscii("@PJL EOJ NAME=\"Android\"\r\n")
            stream.writeAscii("\u001B%-12345X")

            // Also send PCL reset for good measure
            stream.write(ESC)
            stream.write('E'.code)

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
     * Renders a Bitmap to monochrome 1-bit raster and prints via PCL5 raster graphics.
     * Compatible with Brother DCP-B7500D (PCL6/PCL5e) and all Brother laser printers
     * that support PCL backward compatibility.
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

            Log.d(TAG, "Starting PCL5 Raster Print: ${width}x${height}, rowBytes=$rowBytes, Paper=$paperSize, Copies=$copies")
            onProgress(5)

            // Clear any lingering endpoint halt
            clearEndpointHalt(endpointOut.address)

            for (copy in 1..copies) {
                val stream = ByteArrayOutputStream()

                // ==========================================
                // 1. UEL + PJL Job Header
                // ==========================================
                stream.writeAscii("\u001B%-12345X@PJL JOB NAME=\"Android\"\r\n")
                stream.writeAscii("@PJL SET COPIES=${copies}\r\n")
                if (isEcoMode) {
                    stream.writeAscii("@PJL SET ECONOMODE=ON\r\n")
                } else {
                    stream.writeAscii("@PJL SET ECONOMODE=OFF\r\n")
                }
                if (isDuplex) {
                    stream.writeAscii("@PJL SET DUPLEX=ON\r\n")
                    stream.writeAscii("@PJL SET BINDING=LONGEDGE\r\n")
                } else {
                    stream.writeAscii("@PJL SET DUPLEX=OFF\r\n")
                }
                // Enter PCL mode (NOT GDI, NOT RASTER)
                stream.writeAscii("@PJL ENTER LANGUAGE=PCL\r\n")

                // ==========================================
                // 2. PCL5 Printer Reset
                // ==========================================
                stream.writePcl("E")

                // ==========================================
                // 3. Page Setup Commands
                // ==========================================
                // Set number of copies (PCL)
                stream.writePcl("&l${copies}X")
                // Set page size: 26 = A4, 2 = Letter
                if (paperSize.equals("A4", ignoreCase = true)) {
                    stream.writePcl("&l26A")
                } else {
                    stream.writePcl("&l2A")
                }
                // Portrait orientation
                stream.writePcl("&l0O")
                // Set top margin to 0
                stream.writePcl("&l0E")
                // Set left margin to 0
                stream.writePcl("&a0L")

                // ==========================================
                // 4. Raster Graphics Setup
                // ==========================================
                // Set raster resolution to 300 DPI
                stream.writePcl("*t300R")
                // Set source raster width in pixels
                stream.writePcl("*r${width}S")
                // Set source raster height in rows
                stream.writePcl("*r${height}T")
                // Position cursor to top-left (row 0, column 0 in PCL units = 300ths of inch)
                stream.writePcl("*p0Y")
                stream.writePcl("*p0X")
                // Start raster graphics at current cursor position
                stream.writePcl("*r1A")
                // Set compression mode 0 = Uncompressed
                stream.writePcl("*b0M")

                onProgress(10)

                // ==========================================
                // 5. Raster Line Transmission
                // Each line: ESC*b<rowBytes>W followed by rowBytes of 1-bit monochrome data
                // ==========================================
                val pixels = IntArray(width)
                val lineBuffer = ByteArray(rowBytes)

                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

                    // Check if entire line is blank (all white)
                    var isBlank = true
                    java.util.Arrays.fill(lineBuffer, 0.toByte())
                    var byteIdx = 0
                    var currentByte = 0
                    var bitPos = 7

                    for (x in 0 until width) {
                        val color = pixels[x]
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        // Standard luminance: (306*R + 601*G + 117*B) >> 10
                        val gray = (306 * r + 601 * g + 117 * b) shr 10

                        // 1 = black (toner), 0 = white (paper)
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
                        lineBuffer[byteIdx] = currentByte.toByte()
                    }

                    if (isBlank) {
                        // PCL Y-offset: skip 1 blank row
                        stream.writePcl("*b1Y")
                    } else {
                        // Transfer raster data: ESC*b<N>W<data>
                        stream.writePcl("*b${rowBytes}W")
                        stream.write(lineBuffer, 0, rowBytes)
                    }

                    if (y % 200 == 0) {
                        val progress = 10 + ((y * 75) / height)
                        onProgress(progress)
                    }
                }

                // ==========================================
                // 6. End Raster Graphics
                // ==========================================
                stream.writePcl("*rC")

                // ==========================================
                // 7. Form Feed (eject page)
                // ==========================================
                stream.write(0x0C)

                // ==========================================
                // 8. PCL Reset + PJL EOJ
                // ==========================================
                stream.writePcl("E")
                stream.writeAscii("\u001B%-12345X@PJL EOJ NAME=\"Android\"\r\n")
                stream.writeAscii("\u001B%-12345X")

                val jobData = stream.toByteArray()
                Log.d(TAG, "PCL5 Raster stream: ${jobData.size} bytes. Transmitting via Bulk OUT 0x${endpointOut.address.toString(16)}...")

                // ==========================================
                // 9. Transmit to USB Bulk Endpoint (4096-byte chunks)
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

                // Only send 1 copy via USB (PJL SET COPIES handles the rest)
                break
            }

            onProgress(100)
            Log.d(TAG, "Print job completed successfully!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Print failed", e)
            false
        }
    }

    /**
     * Writes a PCL escape sequence: ESC followed by the command string.
     */
    private fun ByteArrayOutputStream.writePcl(cmd: String) {
        write(ESC)
        write(cmd.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Writes raw ASCII string.
     */
    private fun ByteArrayOutputStream.writeAscii(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
    }

    private fun clearEndpointHalt(endpointAddress: Int) {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, endpointAddress, null, 0, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "clearEndpointHalt error: ${e.message}")
        }
    }
}
