package com.polarloopdebugger.protocol

/**
 * PMD wire protocol constants for the Polar Loop (Gen 2).
 *
 * Ported from the reference Python implementation, which was byte-exact validated against real
 * device captures (37/37 control-point frames, 3092/3092 PPG samples, 6340/6340 ACC samples):
 * archive-python-app/pmd_constants.py and pmd_decoder.py. See PROTOCOL.md for the worked examples
 * these are derived from.
 */

const val PMD_SERVICE_UUID = "fb005c80-02e7-f387-1cad-8acd2d8df0c8"
const val PMD_CONTROL_POINT_UUID = "fb005c81-02e7-f387-1cad-8acd2d8df0c8"
const val PMD_DATA_UUID = "fb005c82-02e7-f387-1cad-8acd2d8df0c8"

const val HR_SERVICE_UUID = "0000180d-0000-1000-8000-00805f9b34fb"
const val HR_MEASUREMENT_UUID = "00002a37-0000-1000-8000-00805f9b34fb"

/** Standard Battery Level (Battery Service 0x180F), Read + Notify on the Loop -- PROTOCOL.md "Battery Service". */
const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"

/** Standard Client Characteristic Configuration Descriptor, used to enable notify/indicate. */
const val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

// Spec minimum is 232; 498 is the value confirmed working against this device. Must be requested
// (BluetoothGatt.requestMtu) and negotiated (onMtuChanged) BEFORE REQUEST_MEASUREMENT_START, or
// the device replies ERROR_INVALID_MTU.
const val REQUIRED_ATT_MTU = 498

const val CONTROL_POINT_RESPONSE_PREFIX = 0xF0

enum class OpCode(val id: Int) {
    GET_MEASUREMENT_SETTINGS(0x01),
    REQUEST_MEASUREMENT_START(0x02),
    STOP_MEASUREMENT(0x03);

    companion object {
        fun fromId(id: Int): OpCode? = entries.find { it.id == id }
    }
}

enum class MeasurementType(val id: Int) {
    ECG(0),
    PPG(1),
    ACC(2),
    PPI(3),
    GYRO(5), // untested on this device -- PROTOCOL.md Open Question 6, not wired into the UI
    MAGNETOMETER(6), // untested on this device -- PROTOCOL.md Open Question 6, not wired into the UI
    SKIN_TEMPERATURE(7);

    companion object {
        fun fromId(id: Int): MeasurementType? = entries.find { it.id == id }
    }
}

enum class ErrorCode(val id: Int) {
    SUCCESS(0),
    INVALID_OP_CODE(1),
    INVALID_MEASUREMENT_TYPE(2),
    NOT_SUPPORTED(3),
    INVALID_LENGTH(4),
    INVALID_PARAMETER(5),
    ALREADY_IN_STATE(6),
    INVALID_RESOLUTION(7),
    INVALID_SAMPLE_RATE(8),
    INVALID_RANGE(9),
    INVALID_MTU(10),
    INVALID_NUMBER_OF_CHANNELS(11),
    INVALID_STATE(12),
    DEVICE_IN_CHARGER(13);

    companion object {
        fun fromId(id: Int): ErrorCode? = entries.find { it.id == id }
    }
}

enum class SettingType(val id: Int) {
    SAMPLE_RATE(0),
    RESOLUTION(1),
    RANGE(2),
    RANGE_MILLIUNIT(3),
    CHANNELS(4),
    FACTOR(5),
    SECURITY(6);

    companion object {
        fun fromId(id: Int): SettingType? = entries.find { it.id == id }
    }
}

/** Field size in bytes of ONE value for a TLV entry's array, keyed by [SettingType]. */
val SETTING_FIELD_SIZE: Map<SettingType, Int> = mapOf(
    SettingType.SAMPLE_RATE to 2,
    SettingType.RESOLUTION to 2,
    SettingType.RANGE to 2,
    SettingType.RANGE_MILLIUNIT to 4,
    SettingType.CHANNELS to 1,
    SettingType.FACTOR to 4,
    SettingType.SECURITY to 16,
)

/** (channels, resolutionBits) reference-block layout for delta-compressed measurement types. */
val DELTA_LAYOUT: Map<MeasurementType, Pair<Int, Int>> = mapOf(
    MeasurementType.PPG to (3 to 24), // ppg_ch0, ppg_ch1, status
    MeasurementType.ACC to (3 to 16), // x, y, z
)

/** frame_type & 0x7F sub-type, constant per measurement type on this device. */
val FRAME_SUBTYPE: Map<MeasurementType, Int> = mapOf(
    MeasurementType.PPG to 0x0D,
    MeasurementType.ACC to 0x01,
    MeasurementType.PPI to 0x00,
    MeasurementType.SKIN_TEMPERATURE to 0x00,
)

// FACTOR (setting id 5) has never been observed non-default on this device -- PROTOCOL.md Open
// Question 5. TODO: apply the real value if GET_MEASUREMENT_SETTINGS ever reports one for PPG/ACC.
const val DEFAULT_FACTOR = 1.0

class PmdProtocolException(message: String) : Exception(message)
