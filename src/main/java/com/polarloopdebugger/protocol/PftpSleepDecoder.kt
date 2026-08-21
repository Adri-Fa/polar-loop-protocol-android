package com.polarloopdebugger.protocol

/** PbLocalDateTime{date=1,time=2,OBSOLETE_trusted=3 (unused),time_zone_offset=4 (minutes)}. */
data class PbLocalDateTime(val date: PbDate, val time: PbTime, val timeZoneOffsetMinutes: Int? = null)

/** PbSystemDateTime{date=1,time=2,trusted=3}. */
data class PbSystemDateTime(val date: PbDate, val time: PbTime, val trusted: Boolean)

data class PbLocalDateTimeRange(val startTime: PbLocalDateTime?, val endTime: PbLocalDateTime?)

/**
 * PbSleepWakePhase{seconds_from_sleep_start=1, sleepwake_state=2}. State is a proto2 enum with
 * negative values (0=UNKNOWN, -2=WAKE, -3=REM, -5=NONREM12, -6=NONREM3) -- these wire-encode as
 * 10-byte sign-extended varints, not zigzag, which [ProtoReader.readVarint]'s plain 64-bit
 * accumulation already decodes correctly (`.toInt()` truncation preserves the sign).
 */
data class PbSleepWakePhase(val secondsFromSleepStart: Int, val sleepWakeState: Int)

/** PbSleepCycle{seconds_from_sleep_start=1, sleep_depth_start=2 (float32, range [0.0, 2.0])}. */
data class PbSleepCycle(val secondsFromSleepStart: Int, val sleepDepthStart: Float)

data class SleepAnalysisResult(
    val sleepStartTime: PbLocalDateTime,
    val sleepEndTime: PbLocalDateTime,
    val lastModified: PbSystemDateTime,
    val sleepGoalMinutes: Int,
    val sleepWakePhases: List<PbSleepWakePhase> = emptyList(),
    val snoozeTimes: List<PbLocalDateTime> = emptyList(),
    val alarmTime: PbLocalDateTime? = null,
    val sleepStartOffsetSeconds: Int = 0,
    val sleepEndOffsetSeconds: Int = 0,
    val userSleepRating: Int? = null,
    val recordingDeviceId: String? = null,
    val batteryRanOut: Boolean = false,
    val sleepCycles: List<PbSleepCycle> = emptyList(),
    val sleepResultDate: PbDate? = null,
    val createdTimestamp: PbSystemDateTime? = null,
    val originalSleepRange: PbLocalDateTimeRange? = null,
)

/**
 * Decodes `SLEEP/SLEEPRES.BPB` (PbSleepAnalysisResult), the one file `PolarSleepUtils.kt`
 * (official SDK) reads for sleep results -- verified byte-exact against two real per-date captures
 * (2238 and 1870 bytes, zero unknown fields, including the negative sleepwake_state enum values).
 *
 * `SLEEP/ID.BPB` and `SLEEPSCO/ID.BPB` are a separate, generic per-directory PFTP sync-marker file
 * (same {revision_id, PbLocalDateTime, PbLocalDateTime} shape in both directories) that
 * `PolarSleepUtils.kt` never reads -- intentionally not decoded here.
 * `SLEEPWAK/SLEEW*.BPB` is a pre-sleep wake/movement event queue that the official app GETs then
 * REMOVEs -- also outside `PolarSleepApi` and not decoded here.
 */
fun decodeSleepResultFile(data: ByteArray): SleepAnalysisResult {
    val reader = ProtoReader(data)
    var sleepStartTime: PbLocalDateTime? = null
    var sleepEndTime: PbLocalDateTime? = null
    var lastModified: PbSystemDateTime? = null
    var sleepGoalMinutes = 0
    val sleepWakePhases = mutableListOf<PbSleepWakePhase>()
    val snoozeTimes = mutableListOf<PbLocalDateTime>()
    var alarmTime: PbLocalDateTime? = null
    var sleepStartOffsetSeconds = 0
    var sleepEndOffsetSeconds = 0
    var userSleepRating: Int? = null
    var recordingDeviceId: String? = null
    var batteryRanOut = false
    val sleepCycles = mutableListOf<PbSleepCycle>()
    var sleepResultDate: PbDate? = null
    var createdTimestamp: PbSystemDateTime? = null
    var originalSleepRange: PbLocalDateTimeRange? = null

    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> sleepStartTime = decodePbLocalDateTime(field.bytes!!)
            2 -> sleepEndTime = decodePbLocalDateTime(field.bytes!!)
            3 -> lastModified = decodePbSystemDateTime(field.bytes!!)
            4 -> sleepGoalMinutes = field.varint!!.toInt()
            5 -> sleepWakePhases.add(decodePbSleepWakePhase(field.bytes!!))
            6 -> snoozeTimes.add(decodePbLocalDateTime(field.bytes!!))
            7 -> alarmTime = decodePbLocalDateTime(field.bytes!!)
            8 -> sleepStartOffsetSeconds = field.varint!!.toInt()
            9 -> sleepEndOffsetSeconds = field.varint!!.toInt()
            10 -> userSleepRating = field.varint!!.toInt()
            11 -> recordingDeviceId = decodeDeviceId(field.bytes!!)
            12 -> batteryRanOut = field.varint != 0L
            13 -> sleepCycles.add(decodePbSleepCycle(field.bytes!!))
            14 -> sleepResultDate = decodePbDate(field.bytes!!)
            15 -> createdTimestamp = decodePbSystemDateTime(field.bytes!!)
            16 -> originalSleepRange = decodePbLocalDateTimeRange(field.bytes!!)
        }
    }
    return SleepAnalysisResult(
        requireNotNull(sleepStartTime) { "PbSleepAnalysisResult missing required sleep_start_time" },
        requireNotNull(sleepEndTime) { "PbSleepAnalysisResult missing required sleep_end_time" },
        requireNotNull(lastModified) { "PbSleepAnalysisResult missing required last_modified" },
        sleepGoalMinutes,
        sleepWakePhases,
        snoozeTimes,
        alarmTime,
        sleepStartOffsetSeconds,
        sleepEndOffsetSeconds,
        userSleepRating,
        recordingDeviceId,
        batteryRanOut,
        sleepCycles,
        sleepResultDate,
        createdTimestamp,
        originalSleepRange,
    )
}

/** Not private -- reused by [decodeActivitySamplesFile]/[decodeDailySummaryFile]/[decodeNightlyRecoveryFile]. */
fun decodePbLocalDateTime(data: ByteArray): PbLocalDateTime {
    val reader = ProtoReader(data)
    var date: PbDate? = null
    var time: PbTime? = null
    var tzOffset: Int? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> date = decodePbDate(field.bytes!!)
            2 -> time = decodePbTime(field.bytes!!)
            4 -> tzOffset = field.varint!!.toInt()
        }
    }
    return PbLocalDateTime(
        requireNotNull(date) { "PbLocalDateTime missing required date" },
        requireNotNull(time) { "PbLocalDateTime missing required time" },
        tzOffset,
    )
}

/** Not private -- reused by [decodeNightlyRecoveryFile]. */
fun decodePbSystemDateTime(data: ByteArray): PbSystemDateTime {
    val reader = ProtoReader(data)
    var date: PbDate? = null
    var time: PbTime? = null
    var trusted = false
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> date = decodePbDate(field.bytes!!)
            2 -> time = decodePbTime(field.bytes!!)
            3 -> trusted = field.varint != 0L
        }
    }
    return PbSystemDateTime(
        requireNotNull(date) { "PbSystemDateTime missing required date" },
        requireNotNull(time) { "PbSystemDateTime missing required time" },
        trusted,
    )
}

private fun decodePbLocalDateTimeRange(data: ByteArray): PbLocalDateTimeRange {
    val reader = ProtoReader(data)
    var start: PbLocalDateTime? = null
    var end: PbLocalDateTime? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> start = decodePbLocalDateTime(field.bytes!!)
            2 -> end = decodePbLocalDateTime(field.bytes!!)
        }
    }
    return PbLocalDateTimeRange(start, end)
}

private fun decodePbSleepWakePhase(data: ByteArray): PbSleepWakePhase {
    val reader = ProtoReader(data)
    var seconds = 0
    var state = 0
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> seconds = field.varint!!.toInt()
            2 -> state = field.varint!!.toInt()
        }
    }
    return PbSleepWakePhase(seconds, state)
}

private fun decodePbSleepCycle(data: ByteArray): PbSleepCycle {
    val reader = ProtoReader(data)
    var seconds = 0
    var depth = 0f
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> seconds = field.varint!!.toInt()
            2 -> depth = field.bytes!!.toFloat32()
        }
    }
    return PbSleepCycle(seconds, depth)
}

private fun decodeDeviceId(data: ByteArray): String? {
    val reader = ProtoReader(data)
    var id: String? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        if (field.fieldNumber == 1) id = String(field.bytes!!, Charsets.UTF_8)
    }
    return id
}
