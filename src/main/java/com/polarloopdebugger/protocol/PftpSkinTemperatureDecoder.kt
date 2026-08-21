package com.polarloopdebugger.protocol

/** One continuous-log entry: {1: elapsed milliseconds since file start, 2: temperature (Celsius)}. */
data class SkinTemperatureSample(val elapsedMillis: Long, val celsius: Float)

data class SkinTemperatureLog(val deviceId: String, val samples: List<SkinTemperatureSample> = emptyList())

/**
 * Decodes SKINTEMP/TEMPCONT.BPB. No `.proto` in the vendored polar-ble-sdk documents this file --
 * unlike SLEEP/DSUM/NR/ACT, there's no `// TEMPCONT.BPB=Pb...` header comment anywhere, and its
 * wire shape doesn't match `sleep_skin_temperature_result.proto`'s single-value
 * PbSleepSkinTemperatureResult (that message has no repeated samples or device_id at all). This
 * decoder instead reflects the real shape confirmed against capture17
 * (/U/0/<date>/SKINTEMP/TEMPCONT.BPB): {1: varint (always 1, unmapped), 2: device_id string,
 * 3: varint (always 1, unmapped), 4: repeated {1: varint elapsed_ms (monotonic, ~5 min apart),
 * 2: float32 celsius}} -- readings sat in a plausible 31-34C skin-temperature range.
 *
 * SKINCONT/TEMPCONT.BPB (skin *contact*, not temperature) looks similar at a glance but is a
 * different, sparser schema (per-sample fields are {1: elapsed_ms, 2: optional flag}, no float) --
 * intentionally not decoded here.
 */
fun decodeSkinTemperatureLogFile(data: ByteArray): SkinTemperatureLog {
    val reader = ProtoReader(data)
    var deviceId: String? = null
    val samples = mutableListOf<SkinTemperatureSample>()
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            2 -> deviceId = String(field.bytes!!, Charsets.UTF_8)
            4 -> samples.add(decodeSkinTemperatureSample(field.bytes!!))
        }
    }
    return SkinTemperatureLog(
        requireNotNull(deviceId) { "SKINTEMP/TEMPCONT.BPB missing device_id (field 2)" },
        samples,
    )
}

private fun decodeSkinTemperatureSample(data: ByteArray): SkinTemperatureSample {
    val reader = ProtoReader(data)
    var elapsedMillis = 0L
    var celsius = 0f
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> elapsedMillis = field.varint!!
            2 -> celsius = field.bytes!!.toFloat32()
        }
    }
    return SkinTemperatureSample(elapsedMillis, celsius)
}
