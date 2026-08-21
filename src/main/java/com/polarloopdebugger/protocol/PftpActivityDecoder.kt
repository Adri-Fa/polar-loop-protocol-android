package com.polarloopdebugger.protocol

/** PbDuration{hours=1,minutes=2,seconds=3,millis=4}. Shared with [decodeDailySummaryFile]. */
data class PbDuration(val hours: Int, val minutes: Int, val seconds: Int, val millis: Int)

/** PbActivityInfo{value=1 (ActivityClass enum, raw int), time_stamp=2, factor=3 (float32)}. */
data class PbActivityInfo(val activityClass: Int, val timeStamp: PbLocalDateTime?, val factor: Float?)

data class ActivitySamples(
    val startTime: PbLocalDateTime,
    val metRecordingInterval: PbDuration,
    val stepsRecordingInterval: PbDuration,
    val metSamples: List<Float> = emptyList(),
    val stepsSamples: List<Int> = emptyList(),
    val activityInfo: List<PbActivityInfo> = emptyList(),
)

/**
 * Decodes ACT/ASAMPLn.BPB (PbActivitySamples) -- verified byte-exact against a real capture
 * (capture17, /U/0/<date>/ACT/ASAMPL0.BPB): start_time/met_recording_interval/
 * steps_recording_interval/met_samples/steps_samples/activity_info all matched their documented
 * field numbers and wire types (including activity_info's nested value=SLEEP/time_stamp/factor=1.0
 * for the file's first entry). The file also carries fields 6 and 12 on the wire that aren't in the
 * vendored act_samples.proto -- silently skipped, same as any other unrecognized field.
 */
fun decodeActivitySamplesFile(data: ByteArray): ActivitySamples {
    val reader = ProtoReader(data)
    var startTime: PbLocalDateTime? = null
    var metInterval: PbDuration? = null
    var stepsInterval: PbDuration? = null
    val metSamples = mutableListOf<Float>()
    val stepsSamples = mutableListOf<Int>()
    val activityInfo = mutableListOf<PbActivityInfo>()
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> startTime = decodePbLocalDateTime(field.bytes!!)
            2 -> metInterval = decodePbDuration(field.bytes!!)
            3 -> stepsInterval = decodePbDuration(field.bytes!!)
            4 -> metSamples.add(field.bytes!!.toFloat32())
            5 -> stepsSamples.addAll(readPackedVarints(field.bytes!!).map { it.toInt() })
            7 -> activityInfo.add(decodePbActivityInfo(field.bytes!!))
        }
    }
    return ActivitySamples(
        requireNotNull(startTime) { "PbActivitySamples missing required start_time" },
        requireNotNull(metInterval) { "PbActivitySamples missing required met_recording_interval" },
        requireNotNull(stepsInterval) { "PbActivitySamples missing required steps_recording_interval" },
        metSamples,
        stepsSamples,
        activityInfo,
    )
}

/** Not private -- reused by [decodeDailySummaryFile]'s PbActivityGoalSummary/PbActivityClassTimes. */
fun decodePbDuration(data: ByteArray): PbDuration {
    val reader = ProtoReader(data)
    var hours = 0
    var minutes = 0
    var seconds = 0
    var millis = 0
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> hours = field.varint!!.toInt()
            2 -> minutes = field.varint!!.toInt()
            3 -> seconds = field.varint!!.toInt()
            4 -> millis = field.varint!!.toInt()
        }
    }
    return PbDuration(hours, minutes, seconds, millis)
}

private fun decodePbActivityInfo(data: ByteArray): PbActivityInfo {
    val reader = ProtoReader(data)
    var activityClass = 0
    var timeStamp: PbLocalDateTime? = null
    var factor: Float? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> activityClass = field.varint!!.toInt()
            2 -> timeStamp = decodePbLocalDateTime(field.bytes!!)
            3 -> factor = field.bytes!!.toFloat32()
        }
    }
    return PbActivityInfo(activityClass, timeStamp, factor)
}
