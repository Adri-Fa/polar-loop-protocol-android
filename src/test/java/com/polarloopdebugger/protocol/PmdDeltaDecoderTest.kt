package com.polarloopdebugger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** Packs a list of per-sample channel-value arrays into a flat LSB-first bitstream, matching the
 * device's delta-block wire format (and the reference Python test's `_pack_bits` helper). */
private fun packBits(deltas: List<IntArray>, bitWidth: Int): ByteArray {
    val bits = mutableListOf<Int>()
    for (sample in deltas) {
        for (v in sample) {
            val uv = v and ((1 shl bitWidth) - 1)
            for (i in 0 until bitWidth) bits.add((uv shr i) and 1)
        }
    }
    val out = ByteArray(ceil(bits.size / 8.0).toInt())
    for (i in bits.indices) out[i / 8] = (out[i / 8].toInt() or (bits[i] shl (i % 8))).toByte()
    return out
}

/** Two's-complement little-endian encoding of [value] into [nbytes] bytes. */
private fun signedBytes(value: Int, nbytes: Int): ByteArray {
    val out = ByteArray(nbytes)
    for (i in 0 until nbytes) out[i] = ((value.toLong() shr (8 * i)) and 0xFFL).toByte()
    return out
}

class PmdDeltaDecoderTest {

    /**
     * Hand-build a 3-channel/24-bit (PPG-shaped) frame with two differently sized delta blocks --
     * 12-bit then 8-bit -- and confirm decode reproduces exactly the values encoded. This
     * validates the bit-packing logic is internally consistent; it does NOT validate the
     * byte-alignment-per-block assumption against real hardware (see decodeDeltaFrame's doc
     * comment) -- that's already confirmed via the Python reference tool's byte-exact validation
     * against real capture data (3092/3092 PPG samples).
     */
    @Test
    fun `PPG-shaped 3ch-24bit delta round trip across two different bit widths`() {
        val channels = 3
        val ref = intArrayOf(100, -50, 3)
        val block1 = listOf(intArrayOf(5, -5, 0), intArrayOf(5, -5, 0)) // 12-bit
        val block2 = listOf(intArrayOf(-3, 1, 0)) // 8-bit

        var content = ByteArray(0)
        for (v in ref) content += signedBytes(v, 3)
        content += byteArrayOf(12, block1.size.toByte()) + packBits(block1, 12)
        content += byteArrayOf(8, block2.size.toByte()) + packBits(block2, 8)

        val frame = byteArrayOf(MeasurementType.PPG.id.toByte()) + ByteArray(8) +
            byteArrayOf(0x8D.toByte()) + content

        val result = decodeDeltaFrame(frame, channels, 24)

        val expected = mutableListOf(ref.map { it.toLong() }.toLongArray())
        for (delta in block1 + block2) {
            val last = expected.last()
            expected.add(LongArray(channels) { i -> last[i] + delta[i] })
        }

        assertEquals(expected.size, result.samples.size)
        for (i in expected.indices) assertArrayEquals(expected[i], result.samples[i])
        assertEquals(frame.size, result.consumed)
        assertTrue(result.compressed)
    }

    /** ACC-shaped 3-channel/16-bit round trip, same synthetic-construction approach as the PPG
     * test above, mirroring the Python demo() self-check. */
    @Test
    fun `ACC-shaped 3ch-16bit delta round trip`() {
        val channels = 3
        val ref = intArrayOf(1000, -2000, 500)
        val block1 = listOf(intArrayOf(10, -10, 2), intArrayOf(-4, 4, -1)) // 12-bit
        val block2 = listOf(intArrayOf(7, -7, 0)) // 8-bit

        var content = ByteArray(0)
        for (v in ref) content += signedBytes(v, 2)
        content += byteArrayOf(12, block1.size.toByte()) + packBits(block1, 12)
        content += byteArrayOf(8, block2.size.toByte()) + packBits(block2, 8)

        val frame = byteArrayOf(MeasurementType.ACC.id.toByte()) + ByteArray(8) +
            byteArrayOf(0x81.toByte()) + content

        val result = decodeDeltaFrame(frame, channels, 16)

        val expected = mutableListOf(ref.map { it.toLong() }.toLongArray())
        for (delta in block1 + block2) {
            val last = expected.last()
            expected.add(LongArray(channels) { i -> last[i] + delta[i] })
        }

        assertEquals(expected.size, result.samples.size)
        for (i in expected.indices) assertArrayEquals(expected[i], result.samples[i])
        assertEquals(frame.size, result.consumed)
    }
}
