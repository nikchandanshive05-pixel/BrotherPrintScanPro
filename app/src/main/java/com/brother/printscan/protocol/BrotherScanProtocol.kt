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
 * Scan mode configurations matching Brother SANE brscan4 protocol.
 */
enum class ScanColorMode(val code: String) {
    MONOCHROME_PACKBITS("TEXT"),
    GRAYSCALE("GRAY64"),
    TRUE_COLOR("CGRAY")
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
 * Brother DCP-B7500D USB SANE Scanner Protocol.
 *
 * Protocol reverse-engineered from libsane-brother4.so.1.0.7 and ext_20.ini:
 *   1. BREQ_GET_OPEN handshake (control transfer 0xC0, bRequest=0x01, wValue=0x02)
 *   2. Query parameters (ESC I + settings)
 *   3. Start scan (ESC X + settings)
 *   4. Read raster data blocks from Bulk IN 0x84
 *   5. BREQ_GET_CLOSE teardown (control transfer 0xC0, bRequest=0x02, wValue=0x02)
 *   6. Dechunk Brother block format: [tag][len_lo][len_hi][payload...]
 *   7. Decode raw raster (RLENGTH = PackBits compressed RGB/Gray/Mono)
 *
 * Key fix: DCP-B7500D does NOT support C=JPEG over USB scanning.
 * All modes use C=RLENGTH (PackBits compression).
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
     * Executes a complete scan session.
     */
    suspend fun performScan(
        resolution: ScanResolution = ScanResolution.DPI_300,
        mode: ScanColorMode = ScanColorMode.TRUE_COLOR,
        onProgress: (Int) -> Unit = {}
    ): ScanResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating scan: ${resolution.dpi} DPI, Mode=$mode, OUT=0x${endpointOut.address.toString(16)}, IN=0x${endpointIn.address.toString(16)}")
            onProgress(5)

            // Clear any stale halt conditions on both endpoints
            clearEndpointHalt(endpointIn.address)
            clearEndpointHalt(endpointOut.address)

            // Step 1: BREQ_GET_OPEN handshake
            val handshakeOk = performDriverHandshakeLoop()
            Log.d(TAG, "Handshake result: $handshakeOk")
            if (!handshakeOk) {
                val err = "Scanner handshake failed: Printer is busy or locked. Please tap 'Reset Printer' first, or turn printer OFF and ON."
                Log.e(TAG, err)
                tryCloseHandshake()
                return@withContext ScanResult.Failure(err)
            }
            onProgress(10)

            // Step 2: Query parameters (ESC I)
            val negOk = performParameterNegotiation(resolution, mode)
            Log.d(TAG, "Parameter negotiation: $negOk")
            if (!negOk) {
                val err = "Scanner parameter negotiation failed. Please re-plug the USB OTG cable."
                Log.e(TAG, err)
                tryCloseHandshake()
                return@withContext ScanResult.Failure(err)
            }
            onProgress(15)

            // Drain residual bytes
            val drainBuf = ByteArray(512)
            var drained = 0
            while (true) {
                val d = connection.bulkTransfer(endpointIn, drainBuf, drainBuf.size, 50)
                if (d <= 0) break
                drained += d
            }
            if (drained > 0) Log.d(TAG, "Drained $drained residual bytes")

            // Step 3: Send scan start command (ESC X)
            val startCmd = buildScanStartCommand(resolution, mode)
            Log.d(TAG, "Sending scan start (${startCmd.size} bytes): ${String(startCmd, Charsets.US_ASCII).replace('\u0080', '.')}")

            var bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
            if (bytesWritten < 0) {
                clearEndpointHalt(endpointOut.address)
                delay(300)
                bytesWritten = connection.bulkTransfer(endpointOut, startCmd, startCmd.size, 5000)
            }
            if (bytesWritten < 0) {
                val err = "Failed to send scan start command (rc=$bytesWritten)"
                Log.e(TAG, err)
                tryCloseHandshake()
                return@withContext ScanResult.Failure(err)
            }

            onProgress(20)
            // Wait for scanner motor initialization (libsane-brother4 uses sleep(3))
            delay(3000)

            // Step 4: Read raster data
            val rawStream = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var totalBytesRead = 0
            var consecutiveTimeouts = 0
            var idleCycles = 0
            var scanning = true

            while (scanning) {
                val timeoutMs = if (totalBytesRead < 1000) 10000 else 4000
                val bytesRead = connection.bulkTransfer(endpointIn, buffer, buffer.size, timeoutMs)

                if (bytesRead > 0) {
                    consecutiveTimeouts = 0
                    idleCycles = 0
                    totalBytesRead += bytesRead
                    rawStream.write(buffer, 0, bytesRead)

                    val progress = (20 + (totalBytesRead / 40000)).coerceAtMost(92)
                    onProgress(progress)

                    // Check for terminal marker (0x80..0x86) in short packets
                    if (bytesRead <= 4 && totalBytesRead > 50000) {
                        val tag = buffer[0].toInt() and 0xFF
                        if (tag in 0x80..0x86) {
                            Log.d(TAG, "Page End marker 0x${tag.toString(16)} at $totalBytesRead bytes. Scan complete!")
                            scanning = false
                        }
                    }
                } else if (bytesRead == 0) {
                    idleCycles++
                    delay(50)
                    if (totalBytesRead <= 20000 && idleCycles >= 300) {
                        Log.e(TAG, "Scanner not responding ($totalBytesRead bytes in 15s)")
                        scanning = false
                    } else if (totalBytesRead > 20000 && idleCycles >= 80) {
                        Log.d(TAG, "Scanner finished: $totalBytesRead bytes (4s idle)")
                        scanning = false
                    }
                } else {
                    consecutiveTimeouts++
                    Log.d(TAG, "Bulk read timeout #$consecutiveTimeouts (total=$totalBytesRead)")
                    if (totalBytesRead > 20000 && consecutiveTimeouts >= 2) {
                        Log.d(TAG, "Scanner done on timeout: $totalBytesRead bytes")
                        scanning = false
                    } else if (totalBytesRead <= 20000 && consecutiveTimeouts >= 3) {
                        Log.e(TAG, "Timed out waiting for scan data")
                        scanning = false
                    }
                }
            }

            onProgress(95)
            val rawBytes = rawStream.toByteArray()
            Log.d(TAG, "Total raw scan bytes: ${rawBytes.size}")

            // Step 5: Close session
            tryCloseHandshake()

            if (rawBytes.size < 5000) {
                val err = "Insufficient scan data (${rawBytes.size} bytes). Scanner carriage did not move. Please check: 1) Printer LCD shows 'Ready', 2) USB OTG cable is firm."
                Log.e(TAG, err)
                return@withContext ScanResult.Failure(err)
            }

            // Step 6: Decode
            val bitmap = when (mode) {
                ScanColorMode.TRUE_COLOR -> decodeColorStream(rawBytes, resolution)
                ScanColorMode.MONOCHROME_PACKBITS -> decodeMonoPackBitsStream(rawBytes, resolution)
                ScanColorMode.GRAYSCALE -> decodeGrayscaleStream(rawBytes, resolution)
            }

            if (bitmap == null) {
                val err = "Failed to decode ${rawBytes.size} scan bytes ($mode)"
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
     * Parameter negotiation: ESC I query.
     */
    private suspend fun performParameterNegotiation(resolution: ScanResolution, mode: ScanColorMode): Boolean {
        try {
            val dpi = resolution.dpi
            val queryCmd = "\u001BI\nR=$dpi,$dpi\nM=${mode.code}\n\u0080".toByteArray(Charsets.US_ASCII)
            val written = connection.bulkTransfer(endpointOut, queryCmd, queryCmd.size, 3000)
            Log.d(TAG, "Query ESC I written: $written bytes")
            if (written < 0) return false

            val responseBuf = ByteArray(512)
            var bytesRead = 0
            for (attempt in 0..4) {
                bytesRead = connection.bulkTransfer(endpointIn, responseBuf, responseBuf.size, 1000)
                if (bytesRead > 0) break
                delay(100)
            }
            if (bytesRead > 0) {
                val hex = responseBuf.take(bytesRead).joinToString(" ") { "%02X".format(it) }
                Log.d(TAG, "Query response ($bytesRead bytes): $hex")
                return true
            } else {
                Log.w(TAG, "Query read returned $bytesRead")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Query error: ${e.message}")
        }
        return false
    }

    /**
     * BREQ_GET_OPEN handshake loop (libsane-brother4 OpenDevice).
     */
    private suspend fun performDriverHandshakeLoop(): Boolean {
        val buf = ByteArray(5)

        for (retry in 0..4) {
            var rc = connection.controlTransfer(0xC0, 0x01, 0x02, 0, buf, buf.size, 2000)
            if (rc < 0) {
                rc = connection.controlTransfer(0xC1, 0x01, 0x02, 1, buf, buf.size, 2000)
            }
            Log.d(TAG, "Handshake #$retry: rc=$rc, data=${buf.joinToString { "0x%02x".format(it) }}")

            if (rc >= 0 && buf.size >= 5) {
                val b0 = buf[0].toInt() and 0xFF
                val b1 = buf[1].toInt() and 0xFF
                val b2 = buf[2].toInt() and 0xFF
                val wVal = (buf[3].toInt() and 0xFF) or ((buf[4].toInt() and 0xFF) shl 8)

                if (b0 == 5 && b1 == 0x10 && b2 == 1 && wVal == 2) {
                    Log.d(TAG, "Handshake OK! Scanner ready.")
                    // Drain leftover
                    val drain = ByteArray(512)
                    val d = connection.bulkTransfer(endpointIn, drain, drain.size, 100)
                    if (d > 0) {
                        Log.d(TAG, "Drained $d residual bytes after handshake")
                        // Send ESC Q to clear
                        val qCmd = byteArrayOf(0x1B, 0x51, 0x0A, 0x80.toByte())
                        connection.bulkTransfer(endpointOut, qCmd, qCmd.size, 1000)
                    }
                    return true
                }
            }

            // Recovery: BREQ_GET_CLOSE + wait
            connection.controlTransfer(0xC0, 0x02, 0x02, 0, buf, buf.size, 1000)
            delay(500)
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
            Log.w(TAG, "Close error: ${e.message}")
        }
    }

    /**
     * Build Brother SANE scan start command (ESC X).
     * Key fix: DCP-B7500D uses C=RLENGTH for ALL modes over USB (not JPEG).
     */
    private fun buildScanStartCommand(resolution: ScanResolution, mode: ScanColorMode): ByteArray {
        val dpi = resolution.dpi
        val dotsX = (8.27f * dpi).toInt()
        val dotsY = (11.69f * dpi).toInt()

        val sb = StringBuilder()
        sb.append("\u001BX\n")
        sb.append("R=$dpi,$dpi\n")
        sb.append("M=${mode.code}\n")
        // Always use RLENGTH (PackBits) for USB — DCP-B7500D does NOT support C=JPEG over USB
        sb.append("C=RLENGTH\n")
        sb.append("B=50\n")
        sb.append("N=50\n")
        sb.append("A=0,0,$dotsX,$dotsY\n")

        val ascii = sb.toString().toByteArray(Charsets.US_ASCII)
        val cmd = ByteArray(ascii.size + 1)
        System.arraycopy(ascii, 0, cmd, 0, ascii.size)
        cmd[cmd.size - 1] = 0x80.toByte()
        return cmd
    }

    /**
     * Dechunk Brother block format: [tag][len_lo][len_hi][payload...]
     * Tag 0x64 ('d') = data block. Tags 0x80-0x86 = end markers.
     */
    private fun dechunkBrotherBlocks(rawBytes: ByteArray): ByteArray {
        val n = rawBytes.size
        if (n < 4) return rawBytes

        // If stream starts with JPEG SOI, it's already unchunked
        if (rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xD8.toByte()) {
            Log.d(TAG, "Stream is raw JPEG (no block headers)")
            return rawBytes
        }

        val out = ByteArrayOutputStream()
        var i = 0
        var blocks = 0

        while (i < n) {
            val tag = rawBytes[i].toInt() and 0xFF

            if (tag in 0x80..0x86) {
                Log.d(TAG, "Stream terminator 0x${tag.toString(16)} at offset $i ($blocks blocks)")
                break
            }

            if (i + 3 > n) break

            val chunkLen = (rawBytes[i + 1].toInt() and 0xFF) or ((rawBytes[i + 2].toInt() and 0xFF) shl 8)
            val start = i + 3
            val end = (start + chunkLen).coerceAtMost(n)

            if (end > start) {
                out.write(rawBytes, start, end - start)
            }
            blocks++
            i += 3 + chunkLen
        }

        val payload = out.toByteArray()
        Log.d(TAG, "Dechunked: raw=$n -> payload=${payload.size} ($blocks blocks)")
        return if (payload.isNotEmpty()) payload else rawBytes
    }

    private fun decodeColorStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val payload = dechunkBrotherBlocks(data)

        // Try JPEG decode first
        var soiIndex = -1
        for (i in 0 until payload.size - 1) {
            if (payload[i] == 0xFF.toByte() && payload[i + 1] == 0xD8.toByte()) {
                soiIndex = i
                break
            }
        }
        if (soiIndex >= 0) {
            val bitmap = BitmapFactory.decodeByteArray(payload, soiIndex, payload.size - soiIndex)
            if (bitmap != null) {
                Log.d(TAG, "Decoded JPEG: ${bitmap.width}x${bitmap.height}")
                return bitmap
            }
        }

        // PackBits decompress (RLENGTH mode)
        val decompressed = PackBitsDecoder.decompress(payload)
        val rasterBytes = if (decompressed.size > payload.size) decompressed else payload

        val width = (8.27f * resolution.dpi).toInt()
        val lineBytes = width * 3
        val height = if (lineBytes > 0) (rasterBytes.size / lineBytes).coerceAtLeast(1) else 1

        Log.d(TAG, "Decoding Color RGB: ${width}x$height, ${rasterBytes.size} bytes")

        if (rasterBytes.size < 5000) {
            Log.e(TAG, "Insufficient color data: ${rasterBytes.size} bytes")
            return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var src = 0
        for (i in 0 until width * height) {
            if (src + 2 >= rasterBytes.size) break
            val r = rasterBytes[src].toInt() and 0xFF
            val g = rasterBytes[src + 1].toInt() and 0xFF
            val b = rasterBytes[src + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            src += 3
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun decodeMonoPackBitsStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val payload = dechunkBrotherBlocks(data)
        val decompressed = PackBitsDecoder.decompress(payload)
        if (decompressed.isEmpty()) {
            Log.e(TAG, "PackBits decompression yielded 0 bytes")
            return null
        }

        val width = (8.27f * resolution.dpi).toInt()
        val rowBytes = (width + 7) / 8
        val height = if (rowBytes > 0) (decompressed.size / rowBytes).coerceAtLeast(1) else 1

        Log.d(TAG, "Decoding Mono: ${width}x$height, decompressed=${decompressed.size}")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var byteOffset = 0
        for (y in 0 until height) {
            if (byteOffset + rowBytes > decompressed.size) break
            for (x in 0 until width) {
                val byteVal = decompressed[byteOffset + (x / 8)].toInt() and 0xFF
                val bitVal = (byteVal shr (7 - (x % 8))) and 1
                pixels[y * width + x] = if (bitVal == 0) -0x1000000 else -0x1
            }
            byteOffset += rowBytes
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun decodeGrayscaleStream(data: ByteArray, resolution: ScanResolution): Bitmap? {
        val payload = dechunkBrotherBlocks(data)
        val decompressed = PackBitsDecoder.decompress(payload)
        val raster = if (decompressed.size > payload.size) decompressed else payload

        if (raster.size < 5000) {
            Log.e(TAG, "Insufficient grayscale data: ${raster.size} bytes")
            return null
        }

        val width = (8.27f * resolution.dpi).toInt()
        val height = (raster.size / width).coerceAtLeast(1)

        Log.d(TAG, "Decoding Grayscale: ${width}x$height, ${raster.size} bytes")

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val limit = (width * height).coerceAtMost(raster.size)
        for (i in 0 until limit) {
            val g = raster[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
