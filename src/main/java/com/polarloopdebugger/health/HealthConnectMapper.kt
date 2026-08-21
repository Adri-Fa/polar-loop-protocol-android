package com.polarloopdebugger.health

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.kilocalories
import com.polarloopdebugger.protocol.ActivitySamples
import com.polarloopdebugger.protocol.AutomaticSampleSession
import com.polarloopdebugger.protocol.DailySummary
import com.polarloopdebugger.protocol.PbDate
import com.polarloopdebugger.protocol.PbDuration
import com.polarloopdebugger.protocol.PbLocalDateTime
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToLong

/**
 * Health Connect records mapped from one historical sync session's already-decoded data.
 *
 * This is entirely a local-phone-storage write path (Health Connect's on-device database) and is
 * unrelated to the Polar Loop's read-only device access guarantee -- nothing here ever sends a
 * PUT/REMOVE to the device. See README's Health Connect section.
 */
data class HealthConnectRecords(
    val sleepSessions: List<SleepSessionRecord> = emptyList(),
    val skinTemperature: List<SkinTemperatureRecord> = emptyList(),
    val heartRate: List<HeartRateRecord> = emptyList(),
    val steps: List<StepsRecord> = emptyList(),
    val totalCaloriesBurned: List<TotalCaloriesBurnedRecord> = emptyList(),
)

/**
 * Pure mapping from decoded historical data to Health Connect records. Deliberately has no
 * knowledge of *when* it's called (manual button today, a possible future auto-trigger later) --
 * callers own that decision.
 *
 * Not written: `hr_samples` (superseded by higher-resolution PPI-derived heart rate -- checked
 * point-by-point against capture18/19's 4 AUTOS day-files: PPI bursts covered 659 of 660
 * `hr_samples` timestamps exactly, the one miss landing ~2s into a ~5.5s gap between two bursts,
 * i.e. independent-clock jitter rather than a real coverage gap -- so writing both would only add
 * duplicate, lower-resolution data) and Nightly Recharge (no Health Connect record type fits it;
 * it stays app-local).
 */
fun buildHealthConnectRecords(session: HistoricalSessionData, device: Device): HealthConnectRecords {
    return HealthConnectRecords(
        sleepSessions = session.sleepResults.mapNotNull { mapSleep(it, device) },
        skinTemperature = session.skinTemperatureLogs.mapNotNull { (date, log) -> mapSkinTemperature(date, log, device) },
        heartRate = session.autosSessions.flatMap { mapHeartRate(it, device) },
        steps = session.activitySamples.flatMap { mapSteps(it, device) },
        totalCaloriesBurned = session.dailySummaries.mapNotNull { mapCalories(it, device) },
    )
}

/**
 * Internal-only cross-check (not written to Health Connect): compares `activity`'s per-minute
 * step sum against `daily_summary`'s aggregate for the same date, per the brief's sanity-check
 * requirement. Returns one human-readable warning per mismatching date.
 */
fun stepsSanityCheckWarnings(session: HistoricalSessionData): List<String> {
    val summaryByDate = session.dailySummaries.associateBy { it.date.isoKey() }
    return session.activitySamples.mapNotNull { activity ->
        val date = activity.startTime.date.isoKey()
        val summary = summaryByDate[date] ?: return@mapNotNull null
        val summarySteps = summary.steps ?: return@mapNotNull null
        val activitySum = activity.stepsSamples.sum()
        if (activitySum != summarySteps) {
            "Steps mismatch on $date: activity samples sum to $activitySum, daily summary reports $summarySteps"
        } else {
            null
        }
    }
}

private fun mapSleep(result: com.polarloopdebugger.protocol.SleepAnalysisResult, device: Device): SleepSessionRecord? {
    val (startInstant, startOffset) = result.sleepStartTime.toInstantAndOffset()
    val (endInstant, endOffset) = result.sleepEndTime.toInstantAndOffset()
    if (!startInstant.isBefore(endInstant)) return null

    val sortedPhases = result.sleepWakePhases.sortedBy { it.secondsFromSleepStart }
    val stages = sortedPhases.mapIndexedNotNull { index, phase ->
        val rawStart = startInstant.plusSeconds(phase.secondsFromSleepStart.toLong())
        val rawEnd = if (index + 1 < sortedPhases.size) {
            startInstant.plusSeconds(sortedPhases[index + 1].secondsFromSleepStart.toLong())
        } else {
            endInstant
        }
        // Device phase timestamps can round/drift a few seconds past the reported sleep
        // start/end; clamp into range so no stage ever violates SleepSessionRecord's own
        // start/end bounds requirement (see HealthConnectMapperTest for the regression case).
        val stageStart = maxOf(rawStart, startInstant)
        val stageEnd = minOf(rawEnd, endInstant)
        if (!stageStart.isBefore(stageEnd)) return@mapIndexedNotNull null
        SleepSessionRecord.Stage(stageStart, stageEnd, sleepStageType(phase.sleepWakeState))
    }

    return SleepSessionRecord(
        startTime = startInstant,
        startZoneOffset = startOffset,
        endTime = endInstant,
        endZoneOffset = endOffset,
        metadata = Metadata.autoRecorded(device, clientRecordId = "sleep-${result.sleepStartTime.isoKey()}"),
        stages = stages,
    )
}

private fun sleepStageType(polarState: Int): Int = when (polarState) {
    -2 -> SleepSessionRecord.STAGE_TYPE_AWAKE
    -3 -> SleepSessionRecord.STAGE_TYPE_REM
    -5 -> SleepSessionRecord.STAGE_TYPE_LIGHT
    -6 -> SleepSessionRecord.STAGE_TYPE_DEEP
    else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
}

// ponytail: Polar has no on-device skin-temperature baseline concept, so the log's own sample
// mean is used as the baseline and every sample becomes a delta against it. Revisit if Health
// Connect readers expect a device-reported baseline instead.
private fun mapSkinTemperature(
    date: PbDate,
    log: com.polarloopdebugger.protocol.SkinTemperatureLog,
    device: Device,
): SkinTemperatureRecord? {
    if (log.samples.isEmpty()) return null
    val zone = ZoneId.systemDefault()
    val midnight = date.toLocalDate().atStartOfDay(zone)
    val baselineCelsius = log.samples.map { it.celsius }.average()

    val deltas = log.samples.map { sample ->
        SkinTemperatureRecord.Delta(
            time = midnight.toInstant().plusMillis(sample.elapsedMillis),
            delta = TemperatureDelta.celsius(sample.celsius - baselineCelsius),
        )
    }
    val startTime = deltas.minOf { it.time }
    val endTime = deltas.maxOf { it.time }.plusSeconds(1)

    return SkinTemperatureRecord(
        startTime = startTime,
        startZoneOffset = zone.rules.getOffset(startTime),
        endTime = endTime,
        endZoneOffset = zone.rules.getOffset(endTime),
        metadata = Metadata.autoRecorded(device, clientRecordId = "skintemp-${date.isoKey()}"),
        deltas = deltas,
        baseline = Temperature.celsius(baselineCelsius),
    )
}

private fun mapSteps(activity: ActivitySamples, device: Device): List<StepsRecord> {
    val (startInstant, offset) = activity.startTime.toInstantAndOffset()
    val intervalSeconds = activity.stepsRecordingInterval.toSeconds()
    if (intervalSeconds <= 0) return emptyList()

    return activity.stepsSamples.mapIndexedNotNull { index, count ->
        if (count <= 0) return@mapIndexedNotNull null
        val minuteStart = startInstant.plusSeconds(index * intervalSeconds)
        val minuteEnd = minuteStart.plusSeconds(intervalSeconds)
        StepsRecord(
            startTime = minuteStart,
            startZoneOffset = offset,
            endTime = minuteEnd,
            endZoneOffset = offset,
            count = count.toLong(),
            metadata = Metadata.autoRecorded(device, clientRecordId = "steps-${activity.startTime.isoKey()}-$index"),
        )
    }
}

private fun mapCalories(summary: DailySummary, device: Device): TotalCaloriesBurnedRecord? {
    val activityCalories = summary.activityCalories ?: return null
    val bmrCalories = summary.bmrCalories ?: return null
    val trainingCalories = summary.trainingCalories ?: 0
    val totalKcal = (activityCalories + trainingCalories + bmrCalories).toDouble()

    val zone = ZoneId.systemDefault()
    val startTime = summary.date.toLocalDate().atStartOfDay(zone).toInstant()
    val endTime = summary.date.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    return TotalCaloriesBurnedRecord(
        startTime = startTime,
        startZoneOffset = zone.rules.getOffset(startTime),
        endTime = endTime,
        endZoneOffset = zone.rules.getOffset(endTime),
        energy = totalKcal.kilocalories,
        metadata = Metadata.autoRecorded(device, clientRecordId = "calories-${summary.date.isoKey()}"),
    )
}

private fun mapHeartRate(session: AutomaticSampleSession, device: Device): List<HeartRateRecord> {
    val zone = ZoneId.systemDefault()
    val dayStart = session.day.toLocalDate().atStartOfDay(zone).toInstant()

    return session.ppiSamples.mapNotNull { burst ->
        val burstStart = dayStart.plusSeconds(burst.recordingTime.toSecondsOfDay()).plusNanos(burst.recordingTime.millis * 1_000_000L)
        var elapsedMs = 0L
        val samples = burst.ppi.ppi.mapNotNull { intervalMs ->
            if (intervalMs <= 0) return@mapNotNull null
            elapsedMs += intervalMs
            val bpm = (60_000.0 / intervalMs).roundToLong().coerceIn(1, 300)
            HeartRateRecord.Sample(time = burstStart.plusMillis(elapsedMs), beatsPerMinute = bpm)
        }
        if (samples.isEmpty()) return@mapNotNull null

        HeartRateRecord(
            startTime = samples.first().time,
            startZoneOffset = zone.rules.getOffset(samples.first().time),
            endTime = samples.last().time,
            endZoneOffset = zone.rules.getOffset(samples.last().time),
            samples = samples,
            metadata = Metadata.autoRecorded(
                device,
                clientRecordId = "hr-${session.day.isoKey()}-${burst.recordingTime.isoKey()}",
            ),
        )
    }
}

private fun PbDate.toLocalDate(): LocalDate = LocalDate.of(year, month, day)

private fun PbDate.isoKey(): String = toLocalDate().toString()

private fun com.polarloopdebugger.protocol.PbTime.toSecondsOfDay(): Long =
    hour * 3600L + minute * 60L + seconds

private fun com.polarloopdebugger.protocol.PbTime.isoKey(): String =
    LocalTime.of(hour, minute, seconds).toString() + String.format(".%03d", millis)

private fun PbDuration.toSeconds(): Long = hours * 3600L + minutes * 60L + seconds

private fun PbLocalDateTime.isoKey(): String =
    "${date.isoKey()}T${LocalTime.of(time.hour, time.minute, time.seconds)}"

/**
 * [PbLocalDateTime.timeZoneOffsetMinutes] when the device recorded one, otherwise the system's
 * current offset -- Polar historical files don't always carry an explicit zone.
 */
private fun PbLocalDateTime.toInstantAndOffset(): Pair<Instant, ZoneOffset> {
    val localDateTime = java.time.LocalDateTime.of(
        date.toLocalDate(),
        LocalTime.of(time.hour, time.minute, time.seconds, time.millis * 1_000_000),
    )
    val offset = timeZoneOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) }
        ?: ZoneId.systemDefault().rules.getOffset(localDateTime)
    return localDateTime.toInstant(offset) to offset
}
