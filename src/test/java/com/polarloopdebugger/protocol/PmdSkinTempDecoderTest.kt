package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PmdSkinTempDecoderTest {

    @Test
    fun `skin temperature body decodes to known float32 value`() {
        val body = hex("00-F8-03-42")
        val frame = byteArrayOf(MeasurementType.SKIN_TEMPERATURE.id.toByte()) +
            ByteArray(8) + // timestamp = 0
            byteArrayOf(0x00) +
            body

        val frameResult = decodeDataFrame(frame)
        check(frameResult is DataFrame.SkinTemp)
        assertEquals(32.9921875f, frameResult.result.temperature, 0.0f)
    }
}
