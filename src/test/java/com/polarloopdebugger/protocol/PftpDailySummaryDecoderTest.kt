package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic fixture: structurally matches a DSUM/DSUM.BPB capture (all fields populated, same shape). */
class PftpDailySummaryDecoderTest {

    @Test
    fun `decodes DSUM DSUM BPB synthetic bytes`() {
        val fixture = hex(
            "0A-07-08-E8-0F-10-03-18-09-10-A0-38-18-B0-09-20-98-02-28-D6-0D" +
                " 32-28-0D-00-00-C8-43-15-00-40-AA-43-1A-08-08-01-10-14-18-00-20-00-22-08-08-00-10-19-18-00-20-00-2A-08-08-00-10-0A-18-00-20-00" +
                " 3A-50-0A-08-08-00-10-00-18-00-20-00-12-08-08-06-10-1E-18-00-20-00-1A-08-08-08-10-00-18-00-20-00-22-08-08-05-10-2D-18-00-20-00-2A-08-08-00-10-28-18-00-20-00-32-08-08-00-10-00-18-00-20-00-3A-08-08-00-10-1E-18-00-20-00-42-08-08-00-10-00-18-00-20-00" +
                " 45-00-44-9C-45",
        )

        val summary = decodeDailySummaryFile(fixture)

        assertEquals(PbDate(2024, 3, 9), summary.date)
        assertEquals(7200, summary.steps)
        assertEquals(1200, summary.activityCalories)
        assertEquals(280, summary.trainingCalories)
        assertEquals(1750, summary.bmrCalories)

        val goal = summary.activityGoalSummary!!
        assertEquals(400.0f, goal.activityGoal)
        assertEquals(340.5f, goal.achievedActivity)
        assertEquals(PbDuration(1, 20, 0, 0), goal.timeToGoUp)
        assertEquals(PbDuration(0, 25, 0, 0), goal.timeToGoWalk)
        assertEquals(PbDuration(0, 10, 0, 0), goal.timeToGoJog)

        val classTimes = summary.activityClassTimes!!
        assertEquals(PbDuration(0, 0, 0, 0), classTimes.nonWear)
        assertEquals(PbDuration(6, 30, 0, 0), classTimes.sleep)
        assertEquals(PbDuration(8, 0, 0, 0), classTimes.sedentary)
        assertEquals(PbDuration(5, 45, 0, 0), classTimes.lightActivity)
        assertEquals(PbDuration(0, 40, 0, 0), classTimes.continuousModerate)
        assertEquals(PbDuration(0, 0, 0, 0), classTimes.intermittentModerate)
        assertEquals(PbDuration(0, 30, 0, 0), classTimes.continuousVigorous)
        assertEquals(PbDuration(0, 0, 0, 0), classTimes.intermittentVigorous)

        assertEquals(5000.5f, summary.activityDistance)
        assertEquals(null, summary.dailyBalanceFeedback)
        assertEquals(null, summary.readinessForSpeedAndStrengthTraining)
    }
}
