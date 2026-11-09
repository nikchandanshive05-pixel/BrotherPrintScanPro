package com.brother.printscan.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Scan mode configurations matching Brother SANE protocol specifications.
 */
enum class ScanColorMode(val code: String) {
    MONOCHROME_PACKBITS("TEXT"),
    GRAYSCALE("GRAY64"),
    TRUE_COLOR_JPEG("CGRAY")
}

enum class ScanResolution(val dpi: Int) {
    DPI_100(100),
    DPI_200(200),
    DPI_300(300),
    DPI_600(600)
}

sealed class ScanResult {
    data class Success(val bitmap: Bitmap) : ScanResult()
    data class Failure(val error: String) : ScanResult()
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
        private const val BUFFER_SIZE = 32768
    }

    /**
     * Executes scan session with hardware handshaking, command transmission, and stream reception.
     * Follows the exact SANE brother4 sequence reverse-engineered from libsane-brother4.so.1.0.7:
     * 1. Handshake Loop: 0xC0 BREQ_GET_OPEN with recovery 0xC0 BREQ_GET_CLOSE.
     * 2. Residual drain: Clears any leftover bytes on Endpoint IN 0x84.
     * 3. Start Command: Transmits \x1bX\n scan command to Bulk OUT Endpoint 0x03.
     * 4. Motor Warmup: Waits 2s for lamp calibration and carriage motor movement.
     * 5. Raster Reception: Streams data chunks from Bulk IN Endpoint 0x84 until scanner finishes.
     * 6. Block Dechunking: Unpacks brscan4 [tag, len_lo, len_hi] blocks into clean JPEG/PackBits.
     * 7. Session Teardown: Sends 0xC0 BREQ_GET_CLOSE.
     */
    suspend fun performScan(
        resolution: ScanResolution = ScanResolution.DPI_300,
        mode: ScanColorMode = ScanColorMode.TRUE_COLOR_JPEG,
        onProgress: (Int) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating Brother Scan: ${resolution.dpi} DPI, Mode: $mode on OUT 0x${endpointOut.address.toString(16)}, IN 0x${endpointIn.address.toString(16)}")
            onProgress(5)

            // Step 1: Execute Brother Driver Handshake Loop
            val handshakeSuccess = performDriverHandshakeLoop()
            Log.d(TAG, "Driver handshake loop result: $handshakeSuccess")
            onProgress(10)

            // Step 2: Clear residual bytes on IN endpoint to ensure a clean channel
            var drainedTotal = 0
            val drainBuf = ByteArray(1024)
            while (true) {
                val d = connection.bulkTransfer(endpointIn, drainBuf, drainBuf.size, 50)
                if (d <= 0) break
                drainedTotal += d
            }
            if (drainedTotal > 0) {
                Log.d(TAG, "Drained $drainedTotal stale residual bytes before starting scan.")
            }
            onProgress(15)

            // Step 3: Build & Transmit Official brscan Start Command (\x1bX\n)
            val startCmd = buildBrScanStartCommand(resolution, mode)
            Log.d(TAG, "Sending scan start command (${startCmd.size} bytes):\n${String(startCmd, Charsets.US_ASCII)}")

            var bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
            Log.d(TAG, "Bytes written to bulk OUT endpoint 0x${endpointOut.address.toString(16)}: $bytesWritten")

            if (bytesWritten < 0) {
                // Clear halt on endpoint and retry once
                clearEndpointHalt(endpointOut.address)
                delay(300)
                bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
                Log.d(TAG, "Retry write returned: $bytesWritten")
            }

            if (bytesWritten < 0) {
                val err = "Failed to write scan command to bulk OUT 0x${endpointOut.address.toString(16)} (rc=$bytesWritten)"
                Log.e(TAG, err)
                tryCloseHandshake()
                return@withContext ScanResult.Failure(err)
            }

            onProgress(20)
            // Wait for scanner mechanical carriage motor initialization and lamp calibration
            // libsane-brother4 uses sleep(3)
            delay(2000)

            // Step 4: Stream Scanned Raster Data from Bulk IN Endpoint
            val rawStream = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytesRead = 0
            var consecutiveTimeouts = 0
            var scanning = true

            while (scanning) {
                // Generous timeout for first packet while carriage starts moving, 5s for subsequent packets
                val timeoutMs = if (totalBytesRead == 0) 30000 else 5000
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)

                if (bytesRead > 0) {
                    consecutiveTimeouts = 0
                    totalBytesRead += bytesRead
                    rawStream.write(buffer, 0, bytesRead)

                    // Update UI progress smoothly (20% to 92%)
                    val currentProgress = (20 + (totalBytesRead / 40000)).coerceAtMost(92)
                    onProgress(currentProgress)

                    // Check if device sent terminal marker in a short packet (Page End 0x80..0x86)
                    if (bytesRead <= 4) {
                        val firstByte = buffer[0].toInt() and 0xFF
                        if (firstByte in 0x80..0x86) {
                            Log.d(TAG, "Detected standalone Page End marker (0x${firstByte.toString(16)}) in short packet ($bytesRead bytes). Scan complete!")
                            scanning = false
                            break
                        }
                    }
                } else {
                    consecutiveTimeouts++
                    Log.d(TAG, "Bulk read returned $bytesRead (timeouts: $consecutiveTimeouts, total read: $totalBytesRead)")
                    // Once data has been transferred and device goes silent, the page is done
                    if (totalBytesRead > 0 && consecutiveTimeouts >= 2) {
                        Log.d(TAG, "Scanner finished transmission ($totalBytesRead total bytes received).")
                        scanning = false
                    } else if (totalBytesRead == 0 && consecutiveTimeouts >= 2) {
                        val err = "Timed out waiting for scanner carriage to return data."
                        Log.e(TAG, err)
                        scanning = false
                    }
                }
            }

            onProgress(95)
            val rawBytes = rawStream.toByteArray()
            Log.d(TAG, "Total raw scan bytes received from hardware: ${rawBytes.size}")

            // Step 5: Send close teardown
            tryCloseHandshake()

            if (rawBytes.isEmpty()) {
                val err = "Zero bytes received from scanner on IN 0x${endpointIn.address.toString(16)}"
                Log.e(TAG, err)
                return@withContext ScanResult.Failure(err)
            }

            // Step 6: Decode Raw Payload
            val bitmap = when (mode) {
                ScanColorMode.TRUE_COLOR_JPEG -> decodeColorJpegStream(rawBytes)
                ScanColorMode.MONOCHROME_PACKBITS -> decodeMonoPackBitsStream(rawBytes, resolution)
                ScanColorMode.GRAYSCALE -> decodeGrayscaleStream(rawBytes, resolution)
            }

            if (bitmap == null) {
                val err = "Failed to decode ${rawBytes.size} bytes into image ($mode)"
                Log.e(TAG, err)
                return@withContext ScanResult.Failure(err)
            }

            onProgress(100)
            ScanResult.Success(bitmap)
        } catch (e: Exception) {
            val err = "Scan exception: ${e.message ?: e.javaClass.simpleName}"
            Log.e(TAG, err, e)
            tryCloseHandshake()
            ScanResult.Failure(err)
        }
    }

    /**
     * Executes the exact OpenDevice handshake loop disassembled from libsane-brother4.so:
     * Loop up to 5 times:
     *   controlTransfer(0xC0 or 0xC1, 0x01, 0x02, 0 or 1, buf, 5, 2000)
     *   Check header: buf[0] == 5, buf[1] == 0x10, buf[2] == 1, buf[3..4] == 2
     *   If not matching:
     *     controlTransfer(0xC0, 0x02, 0x02, 0, buf, 5, 1000)
     *     delay(300)
     */
    private suspend fun performDriverHandshakeLoop(): Boolean {
        val openBuf = ByteArray(5)

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
                    // Fast non-blocking drain of leftover buffer
                    val drainBuf = ByteArray(512)
                    val drained = connection.bulkTransfer(endpointIn, drainBuf, drainBuf.size, 100)
                    if (drained > 0) {
                        Log.d(TAG, "Drained $drained residual bytes from IN endpoint.")
                        val qCmd = byteArrayOf(0x1B, 0x51, 0x0A, 0x80.toByte())
                        connection.bulkTransfer(endpointOut, qCmd, qCmd.size, 1000)
                    }
                    return true
                }
            }

            // Recovery branch from libsane-brother4: Send BREQ_GET_CLOSE and wait 300ms
            connection.controlTransfer(0xC0, 0x02, 0x02, 0, openBuf, openBuf.size, 1000)
            delay(300)
        }

        return false
    }

    private fun clearEndpointHalt(endpointAddress: Int) {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, endpointAddress, null, 0, 1000)
        } catch (e: Exception) {
            Log.w(TAG, "clearEndpointHalt error: ${e.message}")
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
     * Builds official Brother SANE start command verified against libsane-brother4 MakeupScanStartCmd:
     * Color:
     *   \x1bX\n R=300,300\n M=CGRAY\n C=JPEG\n J=MID\n B=50\n N=50\n A=0,0,2481,3507\n \x80
     * B&W:
     *   \x1bX\n R=300,300\n M=TEXT\n C=RLENGTH\n B=50\n N=50\n A=0,0,2481,3507\n \x80
     * Grayscale:
     *   \x1bX\n R=300,300\n M=GRAY64\n C=RLENGTH\n B=50\n N=50\n A=0,0,2481,3507\n \x80
     */
    private fun buildBrScanStartCommand(resolution: ScanResolution, mode: ScanColorMode): ByteArray {
        val dpi = resolution.dpi
        // A4 page boundaries in scan dots: 8.27 in x 11.69 in
        val dotsX = (8.27f * dpi).toInt()
        val dotsY = (11.69f * dpi).toInt()

        val sb = StringBuilder()
        sb.append("\u001BX\n")
        sb.append("R=$dpi,$dpi\n")

        when (mode) {
            ScanColorMode.TRUE_COLOR_JPEG -> {
                sb.append("M=CGRAY\n")
                sb.append("C=JPEG\n")
                sb.append("J=MID\n")
            }
            ScanColorMode.MONOCHROME_PACKBITS -> {
                sb.append("M=TEXT\n")
                sb.append("C=RLENGTH\n")
            }
            ScanColorMode.GRAYSCALE -> {
                sb.append("M=GRAY64\n")
                sb.append("C=RLENGTH\n")
            }
        }

        sb.append("B=50\n")
        sb.append("N=50\n")
        sb.append("A=0,0,$dotsX,$dotsY\n")

        val asciiBytes = sb.toString().toByteArray(Charsets.US_ASCII)
        val fullCmd = ByteArray(asciiBytes.size + 1)
        System.arraycopy(asciiBytes, 0, fullCmd, 0, asciiBytes.size)
        fullCmd[fullCmd.size - 1] = 0x80.toByte() // Brother Terminator marker

        return fullCmd
    }

    /**
     * Unpacks Brother brscan4 stream blocks disassembled from libsane-brother4 make_cache_block.
     * Each block on Endpoint 0x84: [tag: 1 byte] [len_lo: 1 byte] [len_hi: 1 byte] [payload: len bytes]
     * For model type 1 (DCP-B7500D), tag is 0x64 ('d') for data chunks.
     * End of stream is signaled by tag in 0x80..0x86.
     */
    private fun dechunkBrotherBlocks(rawBytes: ByteArray): ByteArray {
        val n = rawBytes.size
        if (n < 4) return rawBytes

        // If the stream is already pure unchunked JPEG (starts with 0xFF 0xD8)
        if (rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) {
            Log.d(TAG, "Stream begins directly with JPEG SOI marker (no block headers)")
            return rawBytes
        }

        val out = ByteArrayOutputStream()
        var i = 0
        var blockCount = 0

        while (i < n) {
            val tag = rawBytes[i].toInt() and 0xFF

            // Terminal markers in Brother SANE driver
            if (tag in 0x80..0x86) {
                Log.d(TAG, "Brother stream terminator tag 0x${tag.toString(16)} encountered at offset $i (extracted $blockCount blocks)")
                break
            }

            // Need at least 3 bytes for [tag, len_lo, len_hi]
            if (i + 3 > n) {
                val remaining = n - i
                Log.w(TAG, "Incomplete block header at offset $i (remaining $remaining bytes)")
                break
            }

            val lenLo = rawBytes[i + 1].toInt() and 0xFF
            val lenHi = rawBytes[i + 2].toInt() and 0xFF
            val chunkLen = lenLo or (lenHi shl 8)

            val payloadStart = i + 3
            val payloadEnd = (payloadStart + chunkLen).coerceAtMost(n)

            if (payloadEnd > payloadStart) {
                out.write(rawBytes, payloadStart, payloadEnd - payloadStart)
            }

            blockCount++
            i += 3 + chunkLen
        }

        val payload = out.toByteArray()
        Log.d(TAG, "Brother block dechunking: raw=$n bytes, blocks=$blockCount -> payload=${payload.size} bytes")
        return if (payload.isNotEmpty()) payload else rawBytes
    }

    private fun decodeColorJpegStream(data: ByteArray): Bitmap? {
        val payload = dechunkBrotherBlocks(data)

        // Find JPEG SOI marker 0xFF 0xD8
        var startIndex = -1
        for (i in 0 until payload.size - 1) {
            if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD8.toByte()) {
                startIndex = i
                break
            }
        }

        val offset = if (startIndex >= 0) startIndex else 0
        val length = payload.size - offset
        Log.d(TAG, "Decoding JPEG: total bytes=${payload.size}, SOI offset=$offset, length=$length")

        val bitmap = BitmapFactory.decodeByteArray(payload, offset, length)
        if (bitmap == null) {
            // Also try decoding the raw unmodified bytes in case framing differed
            Log.w(TAG, "Dechunked JPEG decode returned null, trying raw bytes fallback...")
            var rawSoi = -1
            for (i in 0 until data.size - 1) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) {
                    rawSoi = i
                    break
                }
            }
            if (rawSoi >= 0) {
                return BitmapFactory.decodeByteArray(data, rawSoi, data.size - rawSoi)
            }
        }
        return bitmap
    }

    private fun decodeMonoPackBitsStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val payload = dechunkBrotherBlocks(data)
        val decompressed = PackBitsDecoder.decompress(payload)
        if (decompressed.isEmpty()) {
            Log.e(TAG, "PackBits decompression yielded 0 bytes from ${payload.size} payload bytes")
            return null
        }

        val width = (8.27f * resolution.dpi).toInt()
        val rowBytes = (width + 7) / 8
        val height = if (rowBytes > 0) (decompressed.size / rowBytes).coerceAtLeast(1) else 1

        Log.d(TAG, "Decoding Mono PackBits: width=$width, height=$height, rowBytes=$rowBytes, decompressed=${decompressed.size} bytes")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        var byteOffset = 0
        for (y in 0 until height) {
            if (byteOffset + rowBytes > decompressed.size) break
            for (x in 0 until width) {
                val byteVal = decompressed[byteOffset + (x / 8)].toInt() and 0xFF
                val bitVal = (byteVal shr (7 - (x % 8))) and 1
                // Brother SANE monochrome: 0 = black (ink), 1 = white (paper)
                pixels[y * width + x] = if (bitVal == 0) -0x1000000 else -0x1
            }
            byteOffset += rowBytes
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun decodeGrayscaleStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val payload = dechunkBrotherBlocks(data)
        val width = (8.27f * resolution.dpi).toInt()
        val height = (payload.size / width).coerceAtLeast(1)

        Log.d(TAG, "Decoding Grayscale: width=$width, height=$height, payload=${payload.size} bytes")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val limit = (width * height).coerceAtMost(payload.size)
        for (i in 0 until limit) {
            val gray = payload[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
