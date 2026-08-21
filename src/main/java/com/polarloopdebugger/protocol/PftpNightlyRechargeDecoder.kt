package com.polarloopdebugger.protocol

data class NightlyRecoveryStatus(
    val createdTimestamp: PbSystemDateTime,
    val modifiedTimestamp: PbSystemDateTime? = null,
    val ansStatus: Float? = null,
    val recoveryIndicator: Int? = null,
    val recoveryIndicatorSubLevel: Int? = null,
    val ansRate: Int? = null,
    val meanNightlyRecoveryRRI: Int? = null,
    val meanNightlyRecoveryRMSSD: Int? = null,
    val meanNightlyRecoveryRespirationInterval: Int? = null,
    val meanBaselineRRI: Int? = null,
    val sdBaselineRRI: Int? = null,
    val meanBaselineRMSSD: Int? = null,
    val sdBaselineRMSSD: Int? = null,
    val meanBaselineRespirationInterval: Int? = null,
    val sdBaselineRespirationInterval: Int? = null,
    val sleepTip: String? = null,
    val vitalityTip: String? = null,
    val exerciseTip: String? = null,
    val sleepResultDate: PbDate? = null,
)

/**
 * Decodes NR/NR.BPB (PbNightlyRecoveryStatus) -- verified byte-exact against a real capture
 * (capture17, /U/0/<date>/NR/NR.BPB): all 15 populated fields (score_rate_OBSOLETE=7 and
 * modified_timestamp=2 absent, both optional) matched nightly_recovery.proto's field numbers and
 * wire types, and created_timestamp/sleep_result_date cross-check against the same date's SLEEP
 * and DSUM files.
 */
fun decodeNightlyRecoveryFile(data: ByteArray): NightlyRecoveryStatus {
    val reader = ProtoReader(data)
    var created: PbSystemDateTime? = null
    var modified: PbSystemDateTime? = null
    var ansStatus: Float? = null
    var recoveryIndicator: Int? = null
    var recoveryIndicatorSubLevel: Int? = null
    var ansRate: Int? = null
    var meanRRI: Int? = null
    var meanRMSSD: Int? = null
    var meanRespiration: Int? = null
    var meanBaselineRRI: Int? = null
    var sdBaselineRRI: Int? = null
    var meanBaselineRMSSD: Int? = null
    var sdBaselineRMSSD: Int? = null
    var meanBaselineRespiration: Int? = null
    var sdBaselineRespiration: Int? = null
    var sleepTip: String? = null
    var vitalityTip: String? = null
    var exerciseTip: String? = null
    var sleepResultDate: PbDate? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> created = decodePbSystemDateTime(field.bytes!!)
            2 -> modified = decodePbSystemDateTime(field.bytes!!)
            3 -> ansStatus = field.bytes!!.toFloat32()
            4 -> recoveryIndicator = field.varint!!.toInt()
            5 -> recoveryIndicatorSubLevel = field.varint!!.toInt()
            6 -> ansRate = field.varint!!.toInt()
            8 -> meanRRI = field.varint!!.toInt()
            9 -> meanRMSSD = field.varint!!.toInt()
            10 -> meanRespiration = field.varint!!.toInt()
            11 -> meanBaselineRRI = field.varint!!.toInt()
            12 -> sdBaselineRRI = field.varint!!.toInt()
            13 -> meanBaselineRMSSD = field.varint!!.toInt()
            14 -> sdBaselineRMSSD = field.varint!!.toInt()
            15 -> meanBaselineRespiration = field.varint!!.toInt()
            16 -> sdBaselineRespiration = field.varint!!.toInt()
            17 -> sleepTip = String(field.bytes!!, Charsets.UTF_8)
            18 -> vitalityTip = String(field.bytes!!, Charsets.UTF_8)
            19 -> exerciseTip = String(field.bytes!!, Charsets.UTF_8)
            20 -> sleepResultDate = decodePbDate(field.bytes!!)
        }
    }
    return NightlyRecoveryStatus(
        requireNotNull(created) { "PbNightlyRecoveryStatus missing required created_timestamp" },
        modified,
        ansStatus,
        recoveryIndicator,
        recoveryIndicatorSubLevel,
        ansRate,
        meanRRI,
        meanRMSSD,
        meanRespiration,
        meanBaselineRRI,
        sdBaselineRRI,
        meanBaselineRMSSD,
        sdBaselineRMSSD,
        meanBaselineRespiration,
        sdBaselineRespiration,
        sleepTip,
        vitalityTip,
        exerciseTip,
        sleepResultDate,
    )
}
