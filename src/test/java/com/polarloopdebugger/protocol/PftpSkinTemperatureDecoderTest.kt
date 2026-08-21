package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic fixture: structurally matches a SKINTEMP/TEMPCONT.BPB capture (11 samples in the real capture, truncated to 3 here -- same shape). */
class PftpSkinTemperatureDecoderTest {

    @Test
    fun `decodes SKINTEMP TEMPCONT BPB synthetic bytes`() {
        val fixture = hex(
            "08-01-12-08-30-30-30-30-30-30-30-30-18-03" +
                " 22-08-08-84-20-15-66-66-00-42" +
                " 22-09-08-E4-C7-12-15-33-33-00-42" +
                " 22-09-08-C4-EF-24-15-CD-CC-00-42",
        )

        val log = decodeSkinTemperatureLogFile(fixture)

        assertEquals("00000000", log.deviceId)
        assertEquals(3, log.samples.size)
        assertEquals(SkinTemperatureSample(4100, 32.099998474121094f), log.samples[0])
        assertEquals(SkinTemperatureSample(304100, 32.04999923706055f), log.samples[1])
        assertEquals(SkinTemperatureSample(604100, 32.20000076293945f), log.samples[2])
    }
}
