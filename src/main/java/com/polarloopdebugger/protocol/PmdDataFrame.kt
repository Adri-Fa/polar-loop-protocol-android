package com.polarloopdebugger.protocol

/** One decoded fb005c82 data notification, tagged by which decoder produced it. */
sealed class DataFrame {
    data class Delta(val result: DeltaFrameResult) : DataFrame()
    data class Ppi(val result: PpiFrameResult) : DataFrame()
    data class SkinTemp(val result: SkinTempFrameResult) : DataFrame()
}

/** Dispatch a fb005c82 notification to the right decoder by measurement type + the frame_type
 * high (compressed) bit. */
fun decodeDataFrame(data: ByteArray): DataFrame {
    val measurementType = MeasurementType.fromId(data[0].toInt() and 0xFF)
        ?: throw PmdProtocolException("unknown measurement type ${data[0]}")
    val frameType = data[9].toInt() and 0xFF
    val compressed = (frameType and 0x80) != 0

    val layout = DELTA_LAYOUT[measurementType]
    if (compressed && layout != null) {
        return DataFrame.Delta(decodeDeltaFrame(data, layout.first, layout.second))
    }
    if (measurementType == MeasurementType.PPI) return DataFrame.Ppi(decodePpiFrame(data))
    if (measurementType == MeasurementType.SKIN_TEMPERATURE) {
        return DataFrame.SkinTemp(decodeSkinTempFrame(data))
    }
    throw PmdProtocolException(
        "unhandled data frame: measurementType=$measurementType frameType=0x${frameType.toString(16)}"
    )
}
