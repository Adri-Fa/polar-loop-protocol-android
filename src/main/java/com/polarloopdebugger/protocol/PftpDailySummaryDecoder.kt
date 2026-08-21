package com.polarloopdebugger.protocol

/** PbActivityGoalSummary{activity_goal=1(float),achieved_activity=2(float),time_to_go_{up,walk,jog}=3,4,5}. */
data class PbActivityGoalSummary(
    val activityGoal: Float,
    val achievedActivity: Float,
    val timeToGoUp: PbDuration? = null,
    val timeToGoWalk: PbDuration? = null,
    val timeToGoJog: PbDuration? = null,
)

/** PbActivityClassTimes -- one PbDuration per activity class, fields 1-8 in ActivityClass order. */
data class PbActivityClassTimes(
    val nonWear: PbDuration,
    val sleep: PbDuration,
    val sedentary: PbDuration,
    val lightActivity: PbDuration,
    val continuousModerate: PbDuration,
    val intermittentModerate: PbDuration,
    val continuousVigorous: PbDuration,
    val intermittentVigorous: PbDuration,
)

data class DailySummary(
    val date: PbDate,
    val steps: Int? = null,
    val activityCalories: Int? = null,
    val trainingCalories: Int? = null,
    val bmrCalories: Int? = null,
    val activityGoalSummary: PbActivityGoalSummary? = null,
    val activityClassTimes: PbActivityClassTimes? = null,
    val activityDistance: Float? = null,
    // Raw enum ints (PbDailyBalanceFeedback / PbReadinessForSpeedAndStrengthTraining) -- not
    // observed in any real capture yet (both optional), so their name mapping is unverified.
    val dailyBalanceFeedback: Int? = null,
    val readinessForSpeedAndStrengthTraining: Int? = null,
)

/**
 * Decodes DSUM/DSUM.BPB (PbDailySummary) -- verified byte-exact against a real capture (capture17,
 * /U/0/<date>/DSUM/DSUM.BPB): every field, including both nested PbActivityGoalSummary and
 * PbActivityClassTimes submessages, matched dailysummary.proto's field numbers and wire types.
 */
fun decodeDailySummaryFile(data: ByteArray): DailySummary {
    val reader = ProtoReader(data)
    var date: PbDate? = null
    var steps: Int? = null
    var activityCalories: Int? = null
    var trainingCalories: Int? = null
    var bmrCalories: Int? = null
    var activityGoalSummary: PbActivityGoalSummary? = null
    var activityClassTimes: PbActivityClassTimes? = null
    var activityDistance: Float? = null
    var dailyBalanceFeedback: Int? = null
    var readiness: Int? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> date = decodePbDate(field.bytes!!)
            2 -> steps = field.varint!!.toInt()
            3 -> activityCalories = field.varint!!.toInt()
            4 -> trainingCalories = field.varint!!.toInt()
            5 -> bmrCalories = field.varint!!.toInt()
            6 -> activityGoalSummary = decodePbActivityGoalSummary(field.bytes!!)
            7 -> activityClassTimes = decodePbActivityClassTimes(field.bytes!!)
            8 -> activityDistance = field.bytes!!.toFloat32()
            9 -> dailyBalanceFeedback = field.varint!!.toInt()
            10 -> readiness = field.varint!!.toInt()
        }
    }
    return DailySummary(
        requireNotNull(date) { "PbDailySummary missing required date" },
        steps,
        activityCalories,
        trainingCalories,
        bmrCalories,
        activityGoalSummary,
        activityClassTimes,
        activityDistance,
        dailyBalanceFeedback,
        readiness,
    )
}

private fun decodePbActivityGoalSummary(data: ByteArray): PbActivityGoalSummary {
    val reader = ProtoReader(data)
    var activityGoal = 0f
    var achievedActivity = 0f
    var timeToGoUp: PbDuration? = null
    var timeToGoWalk: PbDuration? = null
    var timeToGoJog: PbDuration? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> activityGoal = field.bytes!!.toFloat32()
            2 -> achievedActivity = field.bytes!!.toFloat32()
            3 -> timeToGoUp = decodePbDuration(field.bytes!!)
            4 -> timeToGoWalk = decodePbDuration(field.bytes!!)
            5 -> timeToGoJog = decodePbDuration(field.bytes!!)
        }
    }
    return PbActivityGoalSummary(activityGoal, achievedActivity, timeToGoUp, timeToGoWalk, timeToGoJog)
}

private fun decodePbActivityClassTimes(data: ByteArray): PbActivityClassTimes {
    val reader = ProtoReader(data)
    val d = arrayOfNulls<PbDuration>(8)
    while (reader.hasMore()) {
        val field = reader.readField()
        if (field.fieldNumber in 1..8) d[field.fieldNumber - 1] = decodePbDuration(field.bytes!!)
    }
    val zero = PbDuration(0, 0, 0, 0)
    return PbActivityClassTimes(
        d[0] ?: zero,
        d[1] ?: zero,
        d[2] ?: zero,
        d[3] ?: zero,
        d[4] ?: zero,
        d[5] ?: zero,
        d[6] ?: zero,
        d[7] ?: zero,
    )
}
