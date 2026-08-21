package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic fixture: structurally matches an ACT/ASAMPL0.BPB capture, truncated to a few repeated
 * entries (met_samples/steps_samples/activity_info) at clean field boundaries -- each
 * length-delimited field is self-contained, so slicing at those boundaries keeps the fixture
 * byte-exact for what it does include. Also keeps one occurrence of unmapped fields 6 and 12 (filler
 * bytes only -- see below) to confirm they're silently skipped rather than breaking the parse.
 * Dates/values/the field-12 filler string are fake; the real capture had a real device id embedded
 * in that same unmapped field 12, invisible to a review of just the assertions below.
 */
class PftpActivityDecoderTest {

    @Test
    fun `decodes ACT ASAMPL0 BPB synthetic bytes`() {
        val fixture = hex(
            "0A-15-0A-07-08-E8-0F-10-03-18-0A-12-08-08-00-10-00-18-00-20-00-20-78-12-08-08-00-10-00-18-1E-20-00" +
                " 1A-08-08-00-10-01-18-00-20-00" +
                " 32-27-0D-00-00-80-3F-12-15-0A-07-08-E8-0F-10-03-18-0A-12-08-08-00-10-00-18-00-20-00-20-78-18-FF-FF-FF-FF-FF-FF-FF-FF-FF-01" +
                " 62-0E-0A-0A-0A-08-30-30-30-30-30-30-30-30-10-01" +
                " 2A-1E-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00" +
                " 25-00-00-40-3F-25-00-00-40-3F-25-00-00-40-3F" +
                " 2A-1E-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00-00" +
                " 3A-1E-08-01-12-15-0A-07-08-E8-0F-10-03-18-0A-12-08-08-00-10-00-18-00-20-00-20-78-1D-00-00-80-3F" +
                " 3A-1E-08-02-12-15-0A-07-08-E8-0F-10-03-18-0A-12-08-08-02-10-0A-18-00-20-00-20-78-1D-00-00-80-3F",
        )

        val activity = decodeActivitySamplesFile(fixture)

        assertEquals(PbLocalDateTime(PbDate(2024, 3, 10), PbTime(0, 0, 0, 0), 120), activity.startTime)
        assertEquals(PbDuration(0, 0, 30, 0), activity.metRecordingInterval)
        assertEquals(PbDuration(0, 1, 0, 0), activity.stepsRecordingInterval)
        assertEquals(listOf(0.75f, 0.75f, 0.75f), activity.metSamples)
        assertEquals(60, activity.stepsSamples.size)
        assertEquals(60, activity.stepsSamples.count { it == 0 })

        assertEquals(2, activity.activityInfo.size)
        assertEquals(
            PbActivityInfo(1, PbLocalDateTime(PbDate(2024, 3, 10), PbTime(0, 0, 0, 0), 120), 1.0f),
            activity.activityInfo[0],
        )
        assertEquals(
            PbActivityInfo(2, PbLocalDateTime(PbDate(2024, 3, 10), PbTime(2, 10, 0, 0), 120), 1.0f),
            activity.activityInfo[1],
        )
    }
}
