package com.polarloopdebugger.protocol

import com.polarloopdebugger.protocol.PftpResponseReader.Step
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RFC76 header rules, one at a time. Real-capture coverage is in CaptureFixtureTest; the device
 * errors here other than 103 are synthesized -- no capture on hand contains a 100, 200 or 202.
 */
class PftpResponseReaderTest {

    private fun read(vararg packets: String): List<Step> {
        val reader = PftpResponseReader()
        return packets.map { reader.accept(hex(it)) }
    }

    @Test
    fun `single LAST packet is the whole body`() {
        assertEquals(listOf(Step.Done(PftpResult.Content(hex("0A 0B")))), read("02 0A 0B"))
    }

    @Test
    fun `MORE packets accumulate until LAST, with sequence and next bit advancing`() {
        val steps = read("06 01 02", "17 03", "23 04")
        assertEquals(Step.NeedMore, steps[0])
        assertEquals(Step.NeedMore, steps[1])
        assertEquals(Step.Done(PftpResult.Content(hex("01 02 03 04"))), steps[2])
    }

    @Test
    fun `sequence number wraps from 15 back to 0`() {
        val reader = PftpResponseReader()
        assertEquals(Step.NeedMore, reader.accept(hex("06 00")))
        for (seq in 1..15) assertEquals(Step.NeedMore, reader.accept(byteArrayOf(((seq shl 4) or 0x07).toByte(), 0)))
        val last = reader.accept(hex("03 00"))
        assertTrue(last is Step.Done)
        assertEquals(17, ((last as Step.Done).result as PftpResult.Content).bytes.size)
    }

    @Test
    fun `status 0 with code 0 is an empty success`() {
        assertEquals(listOf(Step.Done(PftpResult.Empty)), read("00 00 00"))
    }

    @Test
    fun `LAST with no payload is an empty success too`() {
        assertEquals(listOf(Step.Done(PftpResult.Empty)), read("02"))
    }

    @Test
    fun `status 0 with a nonzero code reports the code and its PbPFtpError name`() {
        assertEquals(Step.Done(PftpResult.DeviceError(103, "NO_SUCH_FILE_OR_DIRECTORY")), read("00 67 00").single())
    }

    @Test
    fun `synthesized device errors 100, 200 and 202`() {
        assertEquals(Step.Done(PftpResult.DeviceError(100, "UNIDENTIFIED_HOST_ERROR")), read("00 64 00").single())
        assertEquals(Step.Done(PftpResult.DeviceError(200, "UNIDENTIFIED_DEVICE_ERROR")), read("00 C8 00").single())
        assertEquals(Step.Done(PftpResult.DeviceError(202, "SYSTEM_BUSY")), read("00 CA 00").single())
    }

    @Test
    fun `an error mid-stream discards what arrived before it`() {
        assertEquals(Step.Done(PftpResult.DeviceError(202, "SYSTEM_BUSY")), read("06 01 02", "11 CA 00")[1])
    }

    @Test
    fun `error code is little-endian u16, unknown codes keep a null name`() {
        assertEquals(Step.Done(PftpResult.DeviceError(0x0130, null)), read("00 30 01").single())
    }

    @Test
    fun `sequence mismatch is out of sync`() {
        assertTrue(read("06 01", "27 02")[1] is Step.OutOfSync)
        assertTrue(read("12 01").single() is Step.OutOfSync) // first packet must be sequence 0
    }

    @Test
    fun `next-bit mismatch is out of sync`() {
        assertTrue(read("03 01").single() is Step.OutOfSync) // first packet with next=1
        assertTrue(read("06 01", "16 02")[1] is Step.OutOfSync) // later packet with next=0
    }

    @Test
    fun `undefined status 2 and a truncated error frame are out of sync`() {
        assertTrue(read("04 01").single() is Step.OutOfSync)
        assertTrue(read("00 67").single() is Step.OutOfSync)
    }

    @Test
    fun `zero-length packets are skipped, as the SDK skips them`() {
        val reader = PftpResponseReader()
        assertEquals(Step.NeedMore, reader.accept(ByteArray(0)))
        assertEquals(Step.Done(PftpResult.Content(hex("09"))), reader.accept(hex("02 09")))
    }

    @Test
    fun `Content compares by bytes and never prints them`() {
        assertEquals(PftpResult.Content(hex("01 02")), PftpResult.Content(hex("01 02")))
        assertEquals("Content(2 bytes)", PftpResult.Content(hex("01 02")).toString())
        assertArrayEquals(hex("01 02"), PftpResult.Content(hex("01 02")).bytes)
    }
}
