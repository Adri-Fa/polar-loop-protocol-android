package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic fixture: structurally matches an AUTOS BPB ppi_samples entry (one PpiIntervalAutoSample
 * nested inside AutomaticSampleSession.field3). Reconstructs the wire deltas directly, since ppi and
 * errorEstimate are packed zigzag-delta fields, not raw values.
 */
class PftpAutosDecoderTest {

    @Test
    fun `decodes AUTOS BPB synthetic reconstructed bytes`() {
        val fixture = hex(
            "0A-07-08-E8-0F-10-03-18-0B" +
                " 1A-2B-08-01-12-09-08-00-10-05-18-0C-20-AC-02-1A-1C-0A-08-D8-0C-1B-0E-13-32-13-0F-12-07-14-04-0D-06-0E-0A-15-1A-07-03-03-03-03-03-03-03",
        )

        val session = decodeAutosFile(fixture)

        assertEquals(PbDate(2024, 3, 11), session.day)
        assertEquals(1, session.ppiSamples.size)
        val sample = session.ppiSamples.single()
        assertEquals(1, sample.triggerType)
        assertEquals(PbTime(0, 5, 12, 300), sample.recordingTime)
        assertEquals(listOf(812, 798, 805, 795, 820, 810, 802), sample.ppi.ppi)
        assertEquals(listOf(10, 12, 5, 8, 15, 20, 9), sample.ppi.errorEstimate)
        assertEquals(listOf(3, 3, 3, 3, 3, 3, 3), sample.ppi.status)
    }
}
