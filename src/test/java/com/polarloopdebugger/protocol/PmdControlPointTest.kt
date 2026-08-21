package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures ported 1:1 from archive-python-app/tests/test_pmd_decoder.py, itself byte-exact
 * validated against real device captures (capture4/6/8 referenced in that file's comments).
 */
class PmdControlPointTest {

    @Test
    fun `ACC GET_MEASUREMENT_SETTINGS response decodes TLV despite more_frames=0`() {
        // capture4: pins the CONFIRMED QUIRK -- TLV present even though more_frames_flag == 0x00.
        val data = hex("F0-01-02-00-00-00-01-32-00-01-01-10-00-02-01-08-00-04-01-03")
        val r = parseControlPointResponse(data)

        assertEquals(OpCode.GET_MEASUREMENT_SETTINGS, r.opCode)
        assertEquals(MeasurementType.ACC, r.measurementType)
        assertEquals(ErrorCode.SUCCESS, r.status)
        assertEquals(0, r.moreFrames)
        assertEquals(
            mapOf(
                SettingType.SAMPLE_RATE to listOf(50.0),
                SettingType.RESOLUTION to listOf(16.0),
                SettingType.RANGE to listOf(8.0),
                SettingType.CHANNELS to listOf(3.0),
            ),
            r.settings,
        )
    }

    @Test
    fun `PPI GET_MEASUREMENT_SETTINGS response has no configurable settings`() {
        val data = hex("F0-01-03-00-00")
        val r = parseControlPointResponse(data)

        assertEquals(OpCode.GET_MEASUREMENT_SETTINGS, r.opCode)
        assertEquals(MeasurementType.PPI, r.measurementType)
        assertEquals(ErrorCode.SUCCESS, r.status)
        assertEquals(emptyMap<SettingType, List<Double>>(), r.settings)
        assertEquals(0, r.settingsRaw.size)
    }

    @Test
    fun `STOP response has no TLV`() {
        // capture8: STOP PPI -> genuine SUCCESS, same 5-byte shape for every type.
        val data = hex("F0-03-03-00-00")
        val r = parseControlPointResponse(data)

        assertEquals(OpCode.STOP_MEASUREMENT, r.opCode)
        assertEquals(MeasurementType.PPI, r.measurementType)
        assertTrue(r.ok)
        assertEquals(emptyMap<SettingType, List<Double>>(), r.settings)
    }

    @Test
    fun `START response has no FACTOR field or any TLV`() {
        // capture6: genuine SUCCESS, no FACTOR (or any) parameter follows.
        val data = hex("F0-02-02-00-00")
        val r = parseControlPointResponse(data)

        assertEquals(OpCode.REQUEST_MEASUREMENT_START, r.opCode)
        assertEquals(MeasurementType.ACC, r.measurementType)
        assertTrue(r.ok)
        assertEquals(emptyMap<SettingType, List<Double>>(), r.settings)
    }

    @Test
    fun `PPG START command echoes the GET response's TLV verbatim`() {
        val settingsTlv = hex("00-01-16-00-01-01-18-00-04-01-02")
        val command = buildRequestMeasurementStart(MeasurementType.PPG, settingsTlv)
        assertEquals(
            hex("02-01-00-01-16-00-01-01-18-00-04-01-02").toList(),
            command.toList(),
        )
    }

    @Test
    fun `Skin Temperature GET response has a multi-value SAMPLE_RATE field`() {
        // Real hardware capture: 3 candidate rates (1, 2, 4 Hz), not the single fixed value
        // every other measurement type returns.
        val data = hex("F0-01-07-00-00-00-03-01-00-02-00-04-00-01-01-20-00-04-01-01")
        val r = parseControlPointResponse(data)

        assertEquals(MeasurementType.SKIN_TEMPERATURE, r.measurementType)
        assertEquals(
            mapOf(
                SettingType.SAMPLE_RATE to listOf(1.0, 2.0, 4.0),
                SettingType.RESOLUTION to listOf(32.0),
                SettingType.CHANNELS to listOf(1.0),
            ),
            r.settings,
        )
    }

    @Test
    fun `buildStartSettingsTlv picks one value per field instead of echoing the multi-value GET TLV`() {
        // Echoing r.settingsRaw verbatim here is exactly what got REQUEST_MEASUREMENT_START
        // rejected with ERROR_INVALID_PARAMETER on real hardware (4/4 attempts, same capture).
        val data = hex("F0-01-07-00-00-00-03-01-00-02-00-04-00-01-01-20-00-04-01-01")
        val r = parseControlPointResponse(data)

        val startTlv = buildStartSettingsTlv(r.settings)

        assertEquals(hex("00-01-01-00-01-01-20-00-04-01-01").toList(), startTlv.toList())
        assertEquals(
            hex("02-07-00-01-01-00-01-01-20-00-04-01-01").toList(),
            buildRequestMeasurementStart(MeasurementType.SKIN_TEMPERATURE, startTlv).toList(),
        )
    }
}
