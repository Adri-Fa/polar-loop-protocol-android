package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PmdPpiDecoderTest {

    /** Reference fixture from the Python decoder's own self-check: two samples, hr=[62,60],
     * ppInMs=[1002,954], frame trailing timestamp = 2_000_000_000 ns. The wire data carries no
     * per-sample timestamp -- only the LAST sample's timestamp equals the frame timestamp; every
     * earlier sample's timestamp is walked backward by the NEXT sample's own ppInMs. */
    @Test
    fun `PPI per-sample timestamps walk backward from the frame trailing timestamp`() {
        fun sampleBytes(hr: Int, ppInMs: Int, ppErrorEstimate: Int, status: Int): ByteArray =
            byteArrayOf(hr.toByte()) +
                byteArrayOf((ppInMs and 0xFF).toByte(), ((ppInMs shr 8) and 0xFF).toByte()) +
                byteArrayOf((ppErrorEstimate and 0xFF).toByte(), ((ppErrorEstimate shr 8) and 0xFF).toByte()) +
                byteArrayOf(status.toByte())

        val content = sampleBytes(62, 1002, 0, 0) + sampleBytes(60, 954, 0, 0)
        val frameTimestamp = 2_000_000_000L
        val timestampBytes = ByteArray(8) { i -> ((frameTimestamp shr (8 * i)) and 0xFF).toByte() }
        val frame = byteArrayOf(MeasurementType.PPI.id.toByte()) + timestampBytes +
            byteArrayOf(0x00) + content

        val result = decodePpiFrame(frame)

        assertEquals(2, result.samples.size)
        assertEquals(62, result.samples[0].hr)
        assertEquals(1002, result.samples[0].ppInMs)
        assertEquals(60, result.samples[1].hr)
        assertEquals(954, result.samples[1].ppInMs)

        assertEquals(2_000_000_000L, result.samples[1].timestamp)
        assertEquals(2_000_000_000L - 954_000_000L, result.samples[0].timestamp)
    }
}
