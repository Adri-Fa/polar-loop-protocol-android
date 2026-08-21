package com.polarloopdebugger.protocol

/** Standard Bluetooth Heart Rate Measurement (0x2A37) characteristic value. HR needs no PMD
 * control-point command -- it streams automatically once notifications are enabled. */
data class HrMeasurement(
    val bpm: Int,
    val sensorContactSupported: Boolean,
    val sensorContactDetected: Boolean,
    val energyExpendedJoules: Int?,
    val rrIntervalsMs: List<Int>,
)

/** Parses the standard HRS flags byte: bit0 = 8-bit vs 16-bit BPM, bit1/2 = sensor contact,
 * bit3 = energy expended present, bit4 = RR-intervals present (each a uint16 in 1/1024s units). */
fun parseHrMeasurement(data: ByteArray): HrMeasurement {
    val flags = data[0].toInt() and 0xFF
    var offset = 1

    val bpm: Int
    if (flags and 0x01 != 0) {
        bpm = littleEndianUInt16(data, offset)
        offset += 2
    } else {
        bpm = data[offset].toInt() and 0xFF
        offset += 1
    }

    val sensorContactDetected = (flags and 0x02) != 0
    val sensorContactSupported = (flags and 0x04) != 0

    var energyExpended: Int? = null
    if (flags and 0x08 != 0) {
        energyExpended = littleEndianUInt16(data, offset)
        offset += 2
    }

    val rrIntervals = mutableListOf<Int>()
    if (flags and 0x10 != 0) {
        while (offset + 1 < data.size) {
            val raw = littleEndianUInt16(data, offset)
            rrIntervals.add(raw * 1000 / 1024) // 1/1024s units -> ms
            offset += 2
        }
    }

    return HrMeasurement(bpm, sensorContactSupported, sensorContactDetected, energyExpended, rrIntervals)
}
