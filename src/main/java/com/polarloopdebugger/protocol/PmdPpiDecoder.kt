package com.polarloopdebugger.protocol

data class PpiSample(
    val hr: Int,
    val ppInMs: Int,
    val ppErrorEstimate: Int,
    val blockerBit: Int,
    val skinContactStatus: Int,
    val skinContactSupported: Int,
    var timestamp: Long = 0,
)

data class PpiFrameResult(
    val measurementType: Int,
    val timestamp: Long,
    val frameType: Int,
    val samples: List<PpiSample>,
    val consumed: Int,
    val total: Int,
)

/**
 * Decode one raw (never compressed) fb005c82 PPI notification: TYPE_0, 6 bytes/sample (hr,
 * ppInMs LE, ppErrorEstimate LE, status bits). There is no per-sample timestamp in the wire data
 * -- the frame's trailing timestamp belongs to the LAST sample, so per-sample timestamps are
 * computed backward: timestamp[i] = timestamp[i+1] - ppInMs[i+1] * 1_000_000 ns.
 */
fun decodePpiFrame(data: ByteArray): PpiFrameResult {
    val measurementType = data[0].toInt() and 0xFF
    val timestamp = readTimestampNs(data.copyOfRange(1, 9))
    val frameType = data[9].toInt() and 0xFF
    val content = data.copyOfRange(10, data.size)

    val samples = mutableListOf<PpiSample>()
    var offset = 0
    while (offset < content.size) {
        val s = content.copyOfRange(offset, offset + 6)
        val status = s[5].toInt() and 0xFF
        samples.add(
            PpiSample(
                hr = s[0].toInt() and 0xFF,
                ppInMs = littleEndianUInt16(s, 1),
                ppErrorEstimate = littleEndianUInt16(s, 3),
                blockerBit = status and 0x01,
                skinContactStatus = (status shr 1) and 0x01,
                skinContactSupported = (status shr 2) and 0x01,
            )
        )
        offset += 6
    }

    var ts = timestamp
    for (s in samples.asReversed()) {
        s.timestamp = ts
        ts -= s.ppInMs.toLong() * 1_000_000L
    }

    return PpiFrameResult(measurementType, timestamp, frameType, samples, 10 + offset, data.size)
}
