package com.polarloopdebugger.health

import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import com.polarloopdebugger.protocol.ActivitySamples
import com.polarloopdebugger.protocol.AutomaticHeartRateSample
import com.polarloopdebugger.protocol.AutomaticSampleSession
import com.polarloopdebugger.protocol.DailySummary
import com.polarloopdebugger.protocol.PbDate
import com.polarloopdebugger.protocol.PbDuration
import com.polarloopdebugger.protocol.PbLocalDateTime
import com.polarloopdebugger.protocol.PbSleepWakePhase
import com.polarloopdebugger.protocol.PbSystemDateTime
import com.polarloopdebugger.protocol.PbTime
import com.polarloopdebugger.protocol.PpiIntervalAutoSample
import com.polarloopdebugger.protocol.PpiIntervalSamples
import com.polarloopdebugger.protocol.SkinTemperatureLog
import com.polarloopdebugger.protocol.SkinTemperatureSample
import com.polarloopdebugger.protocol.SleepAnalysisResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures below are synthetic: fake dates, fake device id, fake sleep/step/skin-temp/PPI values.
 * They are structurally equivalent to (same field shapes, same edge-case relationships as) what
 * was originally decoded from a real capture -- regenerated to avoid publishing anyone's real
 * health data. See PROTOCOL.md for the real wire format this mirrors.
 */
class HealthConnectMapperTest {

    private val device = Device(type = Device.TYPE_FITNESS_BAND, manufacturer = "Polar", model = "Polar Loop Gen 2")

    // synthetic, structurally matches /U/0/<date>/SLEEP/SLEEPRES.BPB
    private val realSleepResult = SleepAnalysisResult(
        sleepStartTime = PbLocalDateTime(PbDate(2024, 3, 9), PbTime(0, 44, 59, 0), timeZoneOffsetMinutes = 120),
        sleepEndTime = PbLocalDateTime(PbDate(2024, 3, 9), PbTime(7, 16, 59, 0), timeZoneOffsetMinutes = 120),
        lastModified = PbSystemDateTime(PbDate(2024, 3, 9), PbTime(7, 16, 59, 0), trusted = true),
        sleepGoalMinutes = 480,
        sleepWakePhases = listOf(
            PbSleepWakePhase(0, -2),
            PbSleepWakePhase(60, -5),
            PbSleepWakePhase(120, -2),
            PbSleepWakePhase(180, -5),
            PbSleepWakePhase(240, -2),
            PbSleepWakePhase(300, -5),
            PbSleepWakePhase(360, -2),
            PbSleepWakePhase(420, -5),
            PbSleepWakePhase(480, -2),
            PbSleepWakePhase(540, -5),
            PbSleepWakePhase(600, -2),
            PbSleepWakePhase(660, -5),
            PbSleepWakePhase(720, -2),
            PbSleepWakePhase(780, -5),
            PbSleepWakePhase(840, -6),
            PbSleepWakePhase(900, -2),
            PbSleepWakePhase(960, -5),
            PbSleepWakePhase(1020, -6),
            PbSleepWakePhase(1080, -2),
            PbSleepWakePhase(1140, -5),
            PbSleepWakePhase(1200, -6),
            PbSleepWakePhase(1260, -5),
        ),
    )

    // synthetic, structurally matches /U/0/<date>/ACT/ASAMPL0.BPB -- steps_samples sum (721) matches daily_summary.steps below
    private val realActivitySamples = ActivitySamples(
        startTime = PbLocalDateTime(PbDate(2024, 3, 10), PbTime(0, 0, 0, 0), timeZoneOffsetMinutes = 120),
        metRecordingInterval = PbDuration(0, 0, 30, 0),
        stepsRecordingInterval = PbDuration(0, 1, 0, 0),
        metSamples = emptyList(),
        stepsSamples = List(10) { 0 } + listOf(15, 0, 6) + List(651) { 0 } + listOf(700),
        activityInfo = emptyList(),
    )

    // synthetic daily_summary for the same fake date as realActivitySamples
    private val realDailySummary = DailySummary(
        date = PbDate(2024, 3, 10),
        steps = 721,
        activityCalories = 110,
        trainingCalories = 45,
        bmrCalories = 790,
    )

    // synthetic, structurally matches /U/0/<date>/SKINTEMP/TEMPCONT.BPB
    private val realSkinTemperatureLog = SkinTemperatureLog(
        deviceId = "00000000",
        samples = listOf(
            SkinTemperatureSample(3000, 31.5f),
            SkinTemperatureSample(303000, 31.4f),
            SkinTemperatureSample(603000, 31.6f),
            SkinTemperatureSample(903000, 32.0f),
            SkinTemperatureSample(1203000, 32.5f),
            SkinTemperatureSample(1503000, 33.0f),
            SkinTemperatureSample(1803000, 33.3f),
            SkinTemperatureSample(2103000, 33.6f),
            SkinTemperatureSample(2403000, 33.8f),
            SkinTemperatureSample(2703000, 34.0f),
            SkinTemperatureSample(3003000, 34.1f),
        ),
    )

    // synthetic, structurally matches /U/0/AUTOS/AUTOS000.BPB
    private val realAutosSession = AutomaticSampleSession(
        day = PbDate(2024, 3, 10),
        hrSamples = listOf(AutomaticHeartRateSample(3, PbTime(0, 5, 59, 386), listOf(65))),
        ppiSamples = listOf(
            PpiIntervalAutoSample(
                triggerType = 1,
                recordingTime = PbTime(0, 5, 3, 391),
                ppi = PpiIntervalSamples(
                    ppi = listOf(812, 798, 805, 795, 820, 810, 802),
                    errorEstimate = listOf(0, 0, 0, 0, 0, 0, 0),
                    status = listOf(0, 0, 0, 0, 0, 0, 0),
                ),
            ),
        ),
    )

    @Test
    fun `sleep phases become non-overlapping stages spanning the session, using SLEEP not SLEEPWAK`() {
        val records = buildHealthConnectRecords(HistoricalSessionData(sleepResults = listOf(realSleepResult)), device)

        assertEquals(1, records.sleepSessions.size)
        val record = records.sleepSessions.single()
        assertEquals(realSleepResult.sleepWakePhases.size, record.stages.size)
        // stages are contiguous and non-overlapping, each within [sessionStart, sessionEnd)
        record.stages.zipWithNext().forEach { (a, b) -> assertEquals(a.endTime, b.startTime) }
        assertEquals(record.startTime, record.stages.first().startTime)
        assertEquals(record.endTime, record.stages.last().endTime)
        // first phase state -2 (WAKE) maps to STAGE_TYPE_AWAKE, not left as UNKNOWN
        assertEquals(SleepSessionRecord.STAGE_TYPE_AWAKE, record.stages.first().stage)
        // a -6 (NONREM3/deep) phase is present and maps to STAGE_TYPE_DEEP
        assertTrue(record.stages.any { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP })
    }

    @Test
    fun `sleep phase timestamps that drift past the session's own start or end are clamped, not crashed on`() {
        // Regression: a real device sync crashed with IllegalArgumentException from
        // SleepSessionRecord's own start/end bounds check when a trailing phase's raw
        // timestamp (here 3605s) landed 5s past the session's reported end (3600s).
        val driftingSleepResult = SleepAnalysisResult(
            sleepStartTime = PbLocalDateTime(PbDate(2024, 3, 9), PbTime(0, 0, 0, 0), timeZoneOffsetMinutes = 120),
            sleepEndTime = PbLocalDateTime(PbDate(2024, 3, 9), PbTime(1, 0, 0, 0), timeZoneOffsetMinutes = 120),
            lastModified = PbSystemDateTime(PbDate(2024, 3, 9), PbTime(1, 0, 0, 0), trusted = true),
            sleepGoalMinutes = 480,
            sleepWakePhases = listOf(
                PbSleepWakePhase(0, -2),
                PbSleepWakePhase(3605, -5),
            ),
        )

        val records = buildHealthConnectRecords(HistoricalSessionData(sleepResults = listOf(driftingSleepResult)), device)

        val record = records.sleepSessions.single()
        assertEquals(record.startTime, record.stages.first().startTime)
        assertEquals(record.endTime, record.stages.last().endTime)
        assertTrue(record.stages.none { it.endTime.isAfter(record.endTime) })
        assertTrue(record.stages.none { it.startTime.isBefore(record.startTime) })
    }

    @Test
    fun `steps are written per non-zero minute, skipping zero-step minutes`() {
        val records = buildHealthConnectRecords(HistoricalSessionData(activitySamples = listOf(realActivitySamples)), device)

        val nonZeroMinutes = realActivitySamples.stepsSamples.count { it > 0 }
        assertEquals(nonZeroMinutes, records.steps.size)
        assertEquals(realActivitySamples.stepsSamples.sum(), records.steps.sumOf { it.count }.toInt())
        assertTrue(records.steps.all { it.count > 0 })
    }

    @Test
    fun `steps sanity check passes when activity sum matches daily summary`() {
        val session = HistoricalSessionData(
            activitySamples = listOf(realActivitySamples),
            dailySummaries = listOf(realDailySummary),
        )
        assertEquals(emptyList<String>(), stepsSanityCheckWarnings(session))
    }

    @Test
    fun `steps sanity check flags a real mismatch`() {
        val mismatched = realDailySummary.copy(steps = realDailySummary.steps!! + 1)
        val session = HistoricalSessionData(
            activitySamples = listOf(realActivitySamples),
            dailySummaries = listOf(mismatched),
        )
        assertEquals(1, stepsSanityCheckWarnings(session).size)
    }

    @Test
    fun `calories sum activity, training and bmr into one TotalCaloriesBurnedRecord per day`() {
        val records = buildHealthConnectRecords(HistoricalSessionData(dailySummaries = listOf(realDailySummary)), device)

        assertEquals(1, records.totalCaloriesBurned.size)
        val expectedKcal = (realDailySummary.activityCalories!! + realDailySummary.trainingCalories!! + realDailySummary.bmrCalories!!).toDouble()
        assertEquals(expectedKcal, records.totalCaloriesBurned.single().energy.inKilocalories, 0.001)
    }

    @Test
    fun `skin temperature deltas are all within the session range and baselined against the log's own mean`() {
        val records = buildHealthConnectRecords(
            HistoricalSessionData(skinTemperatureLogs = listOf(PbDate(2024, 3, 10) to realSkinTemperatureLog)),
            device,
        )

        assertEquals(1, records.skinTemperature.size)
        val record = records.skinTemperature.single()
        assertEquals(realSkinTemperatureLog.samples.size, record.deltas.size)
        record.deltas.forEach {
            assertTrue(!it.time.isBefore(record.startTime) && it.time.isBefore(record.endTime))
        }
    }

    @Test
    fun `heart rate is derived from PPI intervals, not hr_samples`() {
        val records = buildHealthConnectRecords(HistoricalSessionData(autosSessions = listOf(realAutosSession)), device)

        assertEquals(1, records.heartRate.size)
        val burst = realAutosSession.ppiSamples.single().ppi.ppi
        val record = records.heartRate.single()
        assertEquals(burst.size, record.samples.size)
        // first PPI interval is 812ms -> round(60000/812) = 74 bpm
        assertEquals(74L, record.samples.first().beatsPerMinute)
        assertTrue(record.samples.all { it.beatsPerMinute in 1..300 })
    }

    @Test
    fun `clientRecordId is stable across repeated calls on the same input, for Health Connect idempotency`() {
        val session = HistoricalSessionData(
            sleepResults = listOf(realSleepResult),
            activitySamples = listOf(realActivitySamples),
            dailySummaries = listOf(realDailySummary),
        )
        val first = buildHealthConnectRecords(session, device)
        val second = buildHealthConnectRecords(session, device)

        assertEquals(first.sleepSessions.single().metadata.clientRecordId, second.sleepSessions.single().metadata.clientRecordId)
        assertEquals(
            first.steps.map { it.metadata.clientRecordId },
            second.steps.map { it.metadata.clientRecordId },
        )
    }
}
