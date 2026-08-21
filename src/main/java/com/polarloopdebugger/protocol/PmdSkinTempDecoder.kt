package com.polarloopdebugger.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SkinTempFrameResult(
    val measurementType: Int,
    val timestamp: Long,
    val frameType: Int,
    val temperature: Float,
    val consumed: Int,
    val total: Int,
)

/**
 * Decode one raw (never compressed) fb005c82 Skin Temperature notification: TYPE_0, content is
 * exactly one IEEE754 float32 (LE) -- no per-sample count, no reference/delta framing. One
 * notification = one reading.
 */
fun decodeSkinTempFrame(data: ByteArray): SkinTempFrameResult {
    val measurementType = data[0].toInt() and 0xFF
    val timestamp = readTimestampNs(data.copyOfRange(1, 9))
    val frameType = data[9].toInt() and 0xFF
    val content = data.copyOfRange(10, data.size)
    val temperature = ByteBuffer.wrap(content).order(ByteOrder.LITTLE_ENDIAN).float
    return SkinTempFrameResult(
        measurementType, timestamp, frameType, temperature, 10 + content.size, data.size
    )
}
