package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import com.brother.printscan.usb.UsbConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Scan mode configurations matching Brother SANE protocol specifications.
 */
enum class ScanColorMode(val code: String) {
    MONOCHROME_PACKBITS("TEXT"),
    GRAYSCALE("GRAY256"),
    TRUE_COLOR_JPEG("CGRAY")
}

enum class ScanResolution(val dpi: Int) {
    DPI_100(100),
    DPI_200(200),
    DPI_300(300),
    DPI_600(600)
}

/**
 * Reverse-Engineered Brother Hardware Protocol Implementation.
 * Directly extracted from Brother Linux driver binary (libsane-brother4.so.1.0.7 / ext_20.ini).
 */
class BrotherScanProtocol(
    private val connection: UsbDeviceConnection,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) {
    companion object {
        private const val TAG = "BrotherScanProtocol"
        private const val USB_TIMEOUT_MS = 15000
        private const val BUFFER_SIZE = 32768
    }

    /**
     * Executes scan session with hardware handshaking, command transmission, and stream reception.
     * Follows the exact SANE brother4 sequence reverse-engineered from libsane-brother4.so.1.0.7:
     * 1. Handshake Loop: 0xC0 BREQ_GET_OPEN with recovery 0xC0 BREQ_GET_CLOSE + Q-Command proc.
     * 2. Parameter Negotiation: Sends \x1bI\n query command and reads scanner response.
     * 3. Start Command: Transmits \x1bX\n scan command to Endpoint 0x03.
     * 4. Raster Reception: Streams data from Bulk IN Endpoint 0x84 until 0x80 Page End marker.
     * 5. Session Teardown: Sends 0xC0 BREQ_GET_CLOSE.
     */
    suspend fun performScan(
        resolution: ScanResolution = ScanResolution.DPI_300,
        mode: ScanColorMode = ScanColorMode.TRUE_COLOR_JPEG,
        onProgress: (Int) -> Unit = {}
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating Brother Scan: ${resolution.dpi} DPI, Mode: $mode on OUT 0x${endpointOut.address.toString(16)}, IN 0x${endpointIn.address.toString(16)}")
            onProgress(5)

            // Step 1: Execute Brother Driver Handshake Loop
            val handshakeSuccess = performDriverHandshakeLoop()
            Log.d(TAG, "Driver handshake loop result: $handshakeSuccess")
            onProgress(10)

            // Step 2: Negotiate Parameters via \x1bI\n Query Command
            performParameterNegotiation(resolution, mode)
            onProgress(18)

            // Step 3: Build & Transmit Official brscan Start Command
            val startCmd = buildBrScanStartCommand(resolution, mode)
            Log.d(TAG, "Sending scan start command (${startCmd.size} bytes):\n${String(startCmd, Charsets.US_ASCII)}")

            var bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
            Log.d(TAG, "Bytes written to bulk OUT endpoint 0x${endpointOut.address.toString(16)}: $bytesWritten")

            if (bytesWritten < 0) {
                // Retry once after 300ms delay
                delay(300)
                bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
                Log.d(TAG, "Retry write returned: $bytesWritten")
            }

            if (bytesWritten < 0) {
                Log.e(TAG, "Failed to write scan command to bulk OUT endpoint")
                tryCloseHandshake()
                return@withContext null
            }

            onProgress(25)
            // Wait 1.5s for scanner mechanical motor initialization and lamp calibration
            delay(1500)

            // Step 4: Stream Scanned Raster Data from Bulk IN Endpoint
            val rawStream = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var scanning = true
            var totalBytesRead = 0
            var emptyReads = 0

            while (scanning) {
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, USB_TIMEOUT_MS)
                if (bytesRead > 0) {
                    emptyReads = 0
                    totalBytesRead += bytesRead

                    // Scan for in-stream status marker (0x80 = Page End)
                    for (i in 0 until bytesRead) {
                        val b = buffer[i]
                        if (b == UsbConstants.STATUS_PAGE_END) {
                            Log.d(TAG, "Detected Page End (0x80) marker at byte offset: $totalBytesRead")
                            scanning = false
                            break
                        } else if (b == UsbConstants.STATUS_CANCEL_1 || b == UsbConstants.STATUS_CANCEL_2) {
                            Log.w(TAG, "Detected cancel status from scanner")
                            scanning = false
                            break
                        }
                    }

                    rawStream.write(buffer, 0, bytesRead)
                    val currentProgress = (25 + (totalBytesRead / 50000)).coerceAtMost(92)
                    onProgress(currentProgress)
                } else {
                    emptyReads++
                    Log.d(TAG, "Bulk read returned $bytesRead (empty streak: $emptyReads)")
                    if (emptyReads > 3 || totalBytesRead > 0) {
                        scanning = false
                    }
                }
            }

            onProgress(95)
            val rawBytes = rawStream.toByteArray()
            Log.d(TAG, "Total raw scan bytes received: ${rawBytes.size}")

            // Step 5: Send close teardown
            tryCloseHandshake()

            if (rawBytes.isEmpty()) {
                Log.e(TAG, "Zero bytes received from scanner.")
                return@withContext null
            }

            // Step 6: Decode Raw Payload
            val bitmap = when (mode) {
                ScanColorMode.TRUE_COLOR_JPEG -> decodeColorJpegStream(rawBytes)
                ScanColorMode.MONOCHROME_PACKBITS -> decodeMonoPackBitsStream(rawBytes, resolution)
                ScanColorMode.GRAYSCALE -> decodeGrayscaleStream(rawBytes, resolution)
            }

            onProgress(100)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Exception during scan operation", e)
            tryCloseHandshake()
            null
        }
    }

    /**
     * Executes the exact OpenDevice handshake loop disassembled from libsane-brother4.so:
     * Loop up to 5 times:
     *   controlTransfer(0xC0 or 0xC1, 0x01, 0x02, 0 or 1, buf, 5, 2000)
     *   Check header: buf[0] == 5, buf[1] == 0x10, buf[2] == 1, buf[3..4] == 2
     *   If not matching:
     *     controlTransfer(0xC0, 0x02, 0x02, 0, buf, 5, 2000)
     *     delay(500)
     *     Send Q-Command '\x1bQ\n\x80' to Bulk OUT
     *     Read response from Bulk IN
     */
    private suspend fun performDriverHandshakeLoop(): Boolean {
        val openBuf = ByteArray(5)
        val qCmd = byteArrayOf(0x1B, 0x51, 0x0A, 0x80.toByte()) // \x1bQ\n\x80

        for (retry in 0..4) {
            // First try 0xC0 (Device recipient, index 0)
            var rc = connection.controlTransfer(0xC0, 0x01, 0x02, 0, openBuf, openBuf.size, 2000)
            if (rc < 0) {
                // Try 0xC1 (Interface recipient, index 1)
                rc = connection.controlTransfer(0xC1, 0x01, 0x02, 1, openBuf, openBuf.size, 2000)
            }
            Log.d(TAG, "Handshake retry $retry: BREQ_GET_OPEN rc=$rc, data=${openBuf.joinToString { "0x%02x".format(it) }}")

            if (rc >= 0 && openBuf.size >= 5) {
                val b0 = openBuf[0].toInt() and 0xFF
                val b1 = openBuf[1].toInt() and 0xFF
                val b2 = openBuf[2].toInt() and 0xFF
                val wVal = (openBuf[3].toInt() and 0xFF) or ((openBuf[4].toInt() and 0xFF) shl 8)

                if (b0 == 5 && b1 == 0x10 && b2 == 1 && wVal == 2) {
                    Log.d(TAG, "Handshake successful! Scanner ready.")
                    return true
                }
            }

            // Recovery branch: Send BREQ_GET_CLOSE and Q-command
            connection.controlTransfer(0xC0, 0x02, 0x02, 0, openBuf, openBuf.size, 2000)
            delay(300)

            // Send Q-command to bulk OUT and drain response from bulk IN
            connection.bulkTransfer(endpointOut, qCmd, qCmd.size, 2000)
            val drainBuf = ByteArray(512)
            connection.bulkTransfer(endpointIn, drainBuf, drainBuf.size, 1000)
            delay(100)
        }

        return false
    }

    /**
     * Executes QueryScanInfo (\x1bI\n) negotiation matching libsane-brother4 QueryScanInfoProc.
     */
    private fun performParameterNegotiation(resolution: ScanResolution, mode: ScanColorMode) {
        try {
            val dpi = resolution.dpi
            val queryCmd = "\u001BI\nR=$dpi,$dpi\nM=${mode.code}\n\u0080".toByteArray(Charsets.US_ASCII)
            val written = connection.bulkTransfer(endpointOut, queryCmd, queryCmd.size, 2000)
            Log.d(TAG, "Parameter negotiation query (\u001BI) written: $written bytes")

            val responseBuf = ByteArray(512)
            val bytesRead = connection.bulkTransfer(endpointIn, responseBuf, responseBuf.size, 2000)
            if (bytesRead > 0) {
                val responseStr = String(responseBuf, 0, bytesRead, Charsets.US_ASCII)
                Log.d(TAG, "Parameter negotiation response ($bytesRead bytes): $responseStr")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parameter negotiation error (non-fatal): ${e.message}")
        }
    }

    private fun tryCloseHandshake() {
        try {
            val data = ByteArray(5)
            connection.controlTransfer(0xC0, 0x02, 0x02, 0, data, data.size, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "Close control msg error: ${e.message}")
        }
    }

    /**
     * Builds official Brother SANE start command:
     * \x1bX\n
     * R=300,300\n
     * M=TEXT\n (or CGRAY / GRAY256)
     * C=RLENGTH\n (PackBits)
     * B=50\n
     * N=50\n
     * U=OFF\n
     * P=OFF\n
     * A=0,0,2481,3507\n
     * \x80
     */
    private fun buildBrScanStartCommand(resolution: ScanResolution, mode: ScanColorMode): ByteArray {
        val dpi = resolution.dpi
        // A4 page boundaries in scan dots: 8.27 in x 11.69 in
        val dotsX = (8.27f * dpi).toInt()
        val dotsY = (11.69f * dpi).toInt()

        val compression = if (mode == ScanColorMode.MONOCHROME_PACKBITS) "RLENGTH" else "NONE"

        val sb = StringBuilder()
        sb.append("\u001BX\n")
        sb.append("R=$dpi,$dpi\n")
        sb.append("M=${mode.code}\n")
        sb.append("C=$compression\n")
        sb.append("B=50\n")
        sb.append("N=50\n")
        sb.append("A=0,0,$dotsX,$dotsY\n")

        val asciiBytes = sb.toString().toByteArray(Charsets.US_ASCII)
        val fullCmd = ByteArray(asciiBytes.size + 1)
        System.arraycopy(asciiBytes, 0, fullCmd, 0, asciiBytes.size)
        fullCmd[fullCmd.size - 1] = 0x80.toByte() // Brother Terminator marker

        return fullCmd
    }

    private fun decodeColorJpegStream(data: ByteArray): Bitmap? {
        var startIndex = -1
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                startIndex = i
                break
            }
        }

        val offset = if (startIndex >= 0) startIndex else 0
        return BitmapFactory.decodeByteArray(data, offset, data.size - offset)
    }

    private fun decodeMonoPackBitsStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val decompressed = PackBitsDecoder.decompress(data)
        if (decompressed.isEmpty()) return null

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
                pixels[pixelIdx++] = if (isBlack) -0x1000000 else -0x1
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
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
