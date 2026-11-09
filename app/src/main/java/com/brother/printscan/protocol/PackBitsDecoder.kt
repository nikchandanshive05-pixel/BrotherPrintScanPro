package com.brother.printscan.protocol

import java.io.ByteArrayOutputStream

/**
 * Pure Kotlin PackBits (Run-Length Encoding) decoder for Brother monochrome scans.
 * Wire specification:
 *  - Header n (0 to 127): Followed by (n + 1) literal uncompressed bytes.
 *  - Header n (-1 to -127): Followed by 1 byte to be repeated (1 - n) times.
 *  - Header -128 (0x80): No-op / padding (used as status marker in Brother protocol).
 */
object PackBitsDecoder {

    fun decompress(input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(input.size * 2)
        var i = 0
        val len = input.size

        while (i < len) {
            val header = input[i].toInt()
            i++

            if (header >= 0) {
                // Copy next (header + 1) bytes literally
                val count = header + 1
                if (i + count <= len) {
                    output.write(input, i, count)
                    i += count
                } else {
                    // Truncated packet safeguard
                    val remaining = len - i
                    if (remaining > 0) {
                        output.write(input, i, remaining)
                    }
                    break
                }
            } else if (header != -128) {
                // Repeat next single byte (1 - header) times
                val count = 1 - header
                if (i < len) {
                    val byteToRepeat = input[i]
                    i++
                    for (k in 0 until count) {
                        output.write(byteToRepeat.toInt())
                    }
                } else {
                    break
                }
            }
            // header == -128 (0x80) is a no-op / status escape
        }

        return output.toByteArray()
    }
}
