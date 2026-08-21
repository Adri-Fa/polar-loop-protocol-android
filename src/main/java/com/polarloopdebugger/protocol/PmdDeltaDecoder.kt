package com.polarloopdebugger.protocol

import kotlin.math.ceil

/** Result of decoding one compressed fb005c82 notification (PPG TYPE_13 or ACC TYPE_1).
 * samples[0] is the frame's reference sample; each following sample is a reconstructed delta. */
data class DeltaFrameResult(
    val measurementType: Int,
    val timestamp: Long,
    val frameType: Int,
    val compressed: Boolean,
    val typeId: Int,
    val samples: List<LongArray>,
    val consumed: Int,
    val total: Int,
)

/**
 * One delta block: cumulative-delta samples packed LSB-first as one flat bitstream across
 * [region], sign-extended per value.
 */
fun parseDeltaBlock(region: ByteArray, channels: Int, bitWidth: Int, totalBits: Int): List<LongArray> {
    val bits = BooleanArray(region.size * 8)
    for (byteIdx in region.indices) {
        val byte = region[byteIdx].toInt() and 0xFF
        for (i in 0 until 8) bits[byteIdx * 8 + i] = ((byte shr i) and 1) == 1
    }
    val samples = mutableListOf<LongArray>()
    var pos = 0
    while (pos < totalBits) {
        val values = LongArray(channels)
        for (ch in 0 until channels) {
            var v = 0L
            for (i in 0 until bitWidth) if (bits[pos + i]) v = v or (1L shl i)
            if ((v and (1L shl (bitWidth - 1))) != 0L) v -= (1L shl bitWidth)
            pos += bitWidth
            values[ch] = v
        }
        samples.add(values)
    }
    return samples
}

/**
 * Decode one compressed fb005c82 notification (PPG TYPE_13 or ACC TYPE_1). Delta blocks are
 * variable-width -- a frame can mix e.g. 12-bit and 8-bit blocks -- so each block's own
 * [bit_width, count] pair is read as encountered.
 *
 * Byte-alignment of each delta block is assumed (block length = ceil(total_bits / 8), next
 * block's header starts on the following byte) -- this is the one assumption PROTOCOL.md flags
 * as not spelled out explicitly, but it's what the reference Python decoder implements and it's
 * been validated byte-exact against real captures (3092/3092 PPG, 6340/6340 ACC samples).
 *
 * FACTOR scaling (setting id 5) is intentionally NOT applied here -- see DEFAULT_FACTOR;
 * hardcoded to 1.0 since no non-default value has ever been observed for PPG/ACC on this device.
 */
fun decodeDeltaFrame(data: ByteArray, channels: Int, resolutionBits: Int): DeltaFrameResult {
    val resolutionBytes = resolutionBits / 8
    val measurementType = data[0].toInt() and 0xFF
    val timestamp = readTimestampNs(data.copyOfRange(1, 9))
    val frameType = data[9].toInt() and 0xFF
    val content = data.copyOfRange(10, data.size)

    val ref = LongArray(channels) { i ->
        signedInt(content.copyOfRange(i * resolutionBytes, (i + 1) * resolutionBytes))
    }
    var offset = channels * resolutionBytes
    val samples = mutableListOf(ref)

    while (offset < content.size) {
        val bitWidth = content[offset].toInt() and 0xFF
        val count = content[offset + 1].toInt() and 0xFF
        offset += 2
        val totalBits = count * bitWidth * channels
        val length = ceil(totalBits / 8.0).toInt()
        val block = content.copyOfRange(offset, offset + length)
        offset += length
        for (delta in parseDeltaBlock(block, channels, bitWidth, totalBits)) {
            val last = samples.last()
            samples.add(LongArray(channels) { i -> last[i] + delta[i] })
        }
    }

    return DeltaFrameResult(
        measurementType = measurementType,
        timestamp = timestamp,
        frameType = frameType,
        compressed = (frameType and 0x80) != 0,
        typeId = frameType and 0x7F,
        samples = samples,
        consumed = 10 + offset,
        total = data.size,
    )
}
