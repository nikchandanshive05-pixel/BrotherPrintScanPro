package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.brother.printscan.usb.UsbConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Scan mode configurations.
 */
enum class ScanColorMode(val code: Int) {
    MONOCHROME_PACKBITS(1),
    GRAYSCALE(2),
    TRUE_COLOR_JPEG(4)
}

enum class ScanResolution(val dpi: Int) {
    DPI_100(100),
    DPI_200(200),
    DPI_300(300),
    DPI_600(600)
}

/**
 * Direct implementation of the Brother scanner protocol.
 * Handles handshaking, configuration commands, and streaming packet assembly.
 */
class BrotherScanProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val TAG = "BrotherScanProtocol"
        private const val USB_TIMEOUT_MS = 10000
        private const val BUFFER_SIZE = 16384
    }

    /**
     * Executes a complete scan operation and returns the decoded Android Bitmap.
     * @param resolution Target resolution (100, 200, 300, 600 DPI)
     * @param mode Monochrome (PackBits), Grayscale, or Color (JPEG stream)
     * @param onProgress Callback with progress percentage (0..100)
     */
    suspend fun performScan(
        resolution: ScanResolution = ScanResolution.DPI_300,
        mode: ScanColorMode = ScanColorMode.TRUE_COLOR_JPEG,
        onProgress: (Int) -> Unit = {}
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting scan session: ${resolution.dpi} DPI, Mode: $mode")
            onProgress(5)

            // Step 1: Send Initialization & Parameter Commands
            val initCmd = buildInitCommand(resolution, mode)
            val bytesSent = connection.bulkTransfer(endpointOut, initCmd, initCmd.size, USB_TIMEOUT_MS)
            if (bytesSent < 0) {
                Log.e(TAG, "Failed to send initialization command to scanner")
                return@withContext null
            }
            onProgress(15)

            // Step 2: Read Scanned Data Stream
            val rawStream = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var scanning = true
            var bytesReadTotal = 0

            while (scanning) {
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, USB_TIMEOUT_MS)
                if (bytesRead > 0) {
                    bytesReadTotal += bytesRead

                    // Inspect in-stream status codes
                    for (i in 0 until bytesRead) {
                        val b = buffer[i]
                        if (b == UsbConstants.STATUS_PAGE_END) {
                            Log.d(TAG, "Received Page End status marker (0x80)")
                            scanning = false
                            break
                        } else if (b == UsbConstants.STATUS_CANCEL_1 || b == UsbConstants.STATUS_CANCEL_2) {
                            Log.w(TAG, "Received Cancel marker from scanner")
                            scanning = false
                            break
                        }
                    }

                    rawStream.write(buffer, 0, bytesRead)
                    // Estimate progress up to 90%
                    val currentProgress = (15 + (bytesReadTotal / 40000)).coerceAtMost(90)
                    onProgress(currentProgress)
                } else {
                    // Timeout or end of transfer
                    Log.d(TAG, "Bulk transfer ended with code: $bytesRead")
                    scanning = false
                }
            }

            onProgress(92)
            val rawBytes = rawStream.toByteArray()
            Log.d(TAG, "Total raw bytes received: ${rawBytes.size}")

            // Step 3: Decode Scan Frame into Bitmap
            val bitmap = when (mode) {
                ScanColorMode.TRUE_COLOR_JPEG -> decodeColorStream(rawBytes)
                ScanColorMode.MONOCHROME_PACKBITS -> decodeMonoPackBitsStream(rawBytes, resolution)
                ScanColorMode.GRAYSCALE -> decodeGrayscaleStream(rawBytes, resolution)
            }

            // Step 4: Issue clean session teardown
            sendCloseCommand()
            onProgress(100)

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error during scan execution", e)
            sendCloseCommand()
            null
        }
    }

    private fun buildInitCommand(resolution: ScanResolution, mode: ScanColorMode): ByteArray {
        val out = ByteArrayOutputStream()
        // Standard ESC/S Brother scanner initiation preamble
        out.write(byteArrayOf(0x1B, 0x73, 0x00)) // ESC s NUL
        out.write("R=${resolution.dpi},M=${mode.code}\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun sendCloseCommand() {
        try {
            // Brother BREQ_GET_CLOSE command sequence
            val closeCmd = byteArrayOf(0x1B, 0x00, 0x00, 0x00)
            connection.bulkTransfer(endpointOut, closeCmd, closeCmd.size, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Teardown command error: ${e.message}")
        }
    }

    private fun decodeColorStream(data: ByteArray): Bitmap? {
        // Find JPEG SOI marker (0xFF, 0xD8)
        var startIndex = -1
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                startIndex = i
                break
            }
        }

        if (startIndex == -1) {
            // Direct decode attempt
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }

        return BitmapFactory.decodeByteArray(data, startIndex, data.size - startIndex)
    }

    private fun decodeMonoPackBitsStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val decompressed = PackBitsDecoder.decompress(data)
        if (decompressed.isEmpty()) return null

        // Standard A4 width at target DPI: 8.27 inches * DPI
        val width = (8.27f * resolution.dpi).toInt()
        val height = ((decompressed.size * 8) / width).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        var pixelIdx = 0
        for (byte in decompressed) {
            val unsignedByte = byte.toInt() and 0xFF
            for (bit in 7 downTo 0) {
                if (pixelIdx >= pixels.size) break
                val isBlack = ((unsignedByte shr bit) and 1) == 0
                pixels[pixelIdx++] = if (isBlack) -0x1000000 else -0x1 // 0xFF000000 vs 0xFFFFFFFF
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun decodeGrayscaleStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val width = (8.27f * resolution.dpi).toInt()
        val height = (data.size / width).coerceAtLeast(1)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val limit = (width * height).coerceAtMost(data.size)
        for (i in 0 until limit) {
            val gray = data[i].toInt() and 0xFF
            // 0xFF000000 | (gray << 16) | (gray << 8) | gray
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
