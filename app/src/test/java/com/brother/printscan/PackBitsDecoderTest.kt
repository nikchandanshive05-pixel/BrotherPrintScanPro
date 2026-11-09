package com.brother.printscan

import com.brother.printscan.protocol.PackBitsDecoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PackBitsDecoderTest {

    @Test
    fun testLiteralRun() {
        // Header 0x02 -> 3 literal bytes
        val input = byteArrayOf(0x02, 0x10, 0x20, 0x30)
        val expected = byteArrayOf(0x10, 0x20, 0x30)
        val result = PackBitsDecoder.decompress(input)
        assertArrayEquals(expected, result)
    }

    @Test
    fun testRepeatedRun() {
        // Header -2 (0xFE in byte) -> (1 - (-2)) = 3 repeated bytes
        val input = byteArrayOf((-2).toByte(), 0x55)
        val expected = byteArrayOf(0x55, 0x55, 0x55)
        val result = PackBitsDecoder.decompress(input)
        assertArrayEquals(expected, result)
    }

    @Test
    fun testMixedSequence() {
        // 2 literals (header 1), then 4 repetitions of 0xAA (header -3), then 1 literal (header 0)
        val input = byteArrayOf(
            0x01, 0x0A, 0x0B,
            (-3).toByte(), 0xAA.toByte(),
            0x00, 0x0C
        )
        val expected = byteArrayOf(
            0x0A, 0x0B,
            0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(), 0xAA.toByte(),
            0x0C
        )
        val result = PackBitsDecoder.decompress(input)
        assertArrayEquals(expected, result)
    }

    @Test
    fun testStatusMarkerEscape() {
        // 0x80 (-128) is a no-op marker in PackBits
        val input = byteArrayOf((-128).toByte(), 0x00, 0x42)
        val expected = byteArrayOf(0x42)
        val result = PackBitsDecoder.decompress(input)
        assertArrayEquals(expected, result)
    }
}
