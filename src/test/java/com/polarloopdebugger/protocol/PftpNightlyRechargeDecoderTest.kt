package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic fixture: structurally matches an NR/NR.BPB capture (all fields populated, same shape). */
class PftpNightlyRechargeDecoderTest {

    @Test
    fun `decodes NR NR BPB synthetic bytes`() {
        val fixture = hex(
            "0A-15-0A-07-08-E8-0F-10-03-18-09-12-08-08-17-10-2D-18-0A-20-00-18-00" +
                " 1D-8B-6C-97-40" +
                " 20-03-28-1B-30-06" +
                " 40-B6-07-48-2A-50-D8-1D-58-84-07-60-3C-68-28-70-07-78-A6-1D-80-01-50" +
                " 8A-01-02-2D-31-92-01-02-2D-31-9A-01-02-2D-31" +
                " A2-01-07-08-E8-0F-10-03-18-09",
        )

        val nr = decodeNightlyRecoveryFile(fixture)

        assertEquals(PbSystemDateTime(PbDate(2024, 3, 9), PbTime(23, 45, 10, 0), false), nr.createdTimestamp)
        assertEquals(null, nr.modifiedTimestamp)
        assertEquals(4.73199987411499f, nr.ansStatus)
        assertEquals(3, nr.recoveryIndicator)
        assertEquals(27, nr.recoveryIndicatorSubLevel)
        assertEquals(6, nr.ansRate)
        assertEquals(950, nr.meanNightlyRecoveryRRI)
        assertEquals(42, nr.meanNightlyRecoveryRMSSD)
        assertEquals(3800, nr.meanNightlyRecoveryRespirationInterval)
        assertEquals(900, nr.meanBaselineRRI)
        assertEquals(60, nr.sdBaselineRRI)
        assertEquals(40, nr.meanBaselineRMSSD)
        assertEquals(7, nr.sdBaselineRMSSD)
        assertEquals(3750, nr.meanBaselineRespirationInterval)
        assertEquals(80, nr.sdBaselineRespirationInterval)
        assertEquals("-1", nr.sleepTip)
        assertEquals("-1", nr.vitalityTip)
        assertEquals("-1", nr.exerciseTip)
        assertEquals(PbDate(2024, 3, 9), nr.sleepResultDate)
    }
}
