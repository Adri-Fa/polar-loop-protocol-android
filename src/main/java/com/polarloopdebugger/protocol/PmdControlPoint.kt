package com.polarloopdebugger.protocol

/** One control-point response: [0xF0, op_code, measurement_type, status, more_frames, ...TLV]. */
data class ControlPointResponse(
    val opCode: OpCode,
    val measurementType: MeasurementType,
    val status: ErrorCode,
    val moreFrames: Int,
    val settings: Map<SettingType, List<Double>>,
    val settingsRaw: ByteArray,
) {
    val ok: Boolean get() = status == ErrorCode.SUCCESS
}

/**
 * Repeating (setting_type byte, count byte, count x field_size bytes) until the input is
 * exhausted. Pinned by the ACC GET_MEASUREMENT_SETTINGS fixture in PmdControlPointTest.
 */
fun parseSettingsTlv(data: ByteArray): Map<SettingType, List<Double>> {
    val settings = LinkedHashMap<SettingType, List<Double>>()
    var offset = 0
    while (offset < data.size) {
        val settingId = data[offset].toInt() and 0xFF
        val count = data[offset + 1].toInt() and 0xFF
        offset += 2
        val settingType = SettingType.fromId(settingId)
            ?: throw PmdProtocolException("unknown setting type $settingId at offset ${offset - 2}")
        val fieldSize = SETTING_FIELD_SIZE.getValue(settingType)
        val values = ArrayList<Double>(count)
        repeat(count) {
            val chunk = data.copyOfRange(offset, offset + fieldSize)
            values.add(littleEndianUnsignedToDouble(chunk))
            offset += fieldSize
        }
        settings[settingType] = values
    }
    return settings
}

/**
 * Parse an indication on fb005c81: [0xF0, op_code, measurement_type, status, more_frames_flag,
 * ...TLV]. CONFIRMED QUIRK: TLV presence is determined by whether bytes remain after the 5-byte
 * header, NOT by more_frames_flag -- ACC's own GET response has more=0x00 with a full trailing
 * TLV. See the pinned fixture test for the exact byte sequence.
 */
fun parseControlPointResponse(data: ByteArray): ControlPointResponse {
    if (data.size < 5 || (data[0].toInt() and 0xFF) != CONTROL_POINT_RESPONSE_PREFIX) {
        throw PmdProtocolException("not a control point response: ${data.toHex()}")
    }
    val opCode = OpCode.fromId(data[1].toInt() and 0xFF)
        ?: throw PmdProtocolException("unknown op code ${data[1]}")
    val measurementType = MeasurementType.fromId(data[2].toInt() and 0xFF)
        ?: throw PmdProtocolException("unknown measurement type ${data[2]}")
    val status = ErrorCode.fromId(data[3].toInt() and 0xFF)
        ?: throw PmdProtocolException("unknown status code ${data[3]}")
    val more = data[4].toInt() and 0xFF
    val tlvRaw = if (data.size > 5) data.copyOfRange(5, data.size) else ByteArray(0)
    val settings = if (tlvRaw.isNotEmpty()) parseSettingsTlv(tlvRaw) else emptyMap()
    return ControlPointResponse(opCode, measurementType, status, more, settings, tlvRaw)
}

// ---------------------------------------------------------------------------
// Control point command builders (writes to fb005c81)
// ---------------------------------------------------------------------------

fun buildGetMeasurementSettings(type: MeasurementType): ByteArray =
    byteArrayOf(OpCode.GET_MEASUREMENT_SETTINGS.id.toByte(), type.id.toByte())

/**
 * settingsTlv must be a single-value-per-field TLV, e.g. from [buildStartSettingsTlv]. Echoing
 * the GET response's raw TLV verbatim breaks whenever a field has multiple candidate values
 * (see [buildStartSettingsTlv], confirmed on real hardware for Skin Temperature's SAMPLE_RATE).
 */
fun buildRequestMeasurementStart(type: MeasurementType, settingsTlv: ByteArray): ByteArray =
    byteArrayOf(OpCode.REQUEST_MEASUREMENT_START.id.toByte(), type.id.toByte()) + settingsTlv

/**
 * GET_MEASUREMENT_SETTINGS can return MULTIPLE candidate values per field, not just one -- e.g.
 * Skin Temperature reports SAMPLE_RATE: [1, 2, 4] Hz. REQUEST_MEASUREMENT_START rejects a
 * multi-value TLV with ERROR_INVALID_PARAMETER (confirmed on real hardware: 4/4 Skin Temperature
 * Start attempts failed this way while echoing the 3-value SAMPLE_RATE verbatim -- see
 * PROTOCOL.md). This re-encodes the GET response as count=1 per field, picking the lowest
 * candidate value (arbitrary but deterministic, and always a valid menu entry).
 */
fun buildStartSettingsTlv(settings: Map<SettingType, List<Double>>): ByteArray {
    var tlv = ByteArray(0)
    for ((type, values) in settings) {
        val fieldSize = SETTING_FIELD_SIZE.getValue(type)
        tlv += byteArrayOf(type.id.toByte(), 1) + doubleToLittleEndianUnsigned(values.min(), fieldSize)
    }
    return tlv
}

fun buildStopMeasurement(type: MeasurementType): ByteArray =
    byteArrayOf(OpCode.STOP_MEASUREMENT.id.toByte(), type.id.toByte())
