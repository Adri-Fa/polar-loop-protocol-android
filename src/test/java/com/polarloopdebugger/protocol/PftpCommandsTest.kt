package com.polarloopdebugger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PftpCommandsTest {

    // Real captured GET request frames, PROTOCOL.md "Request/response wire framing" section.

    @Test
    fun `GET request frame 342 - REST SERVICE API`() {
        val expected = hex("02 15 00 08 00 12 11 2f 52 45 53 54 2f 53 45 52 56 49 43 45 2e 41 50 49")
        assertArrayEquals(expected, buildGetRequest("/REST/SERVICE.API"))
    }

    @Test
    fun `GET request frame 459 - root`() {
        val expected = hex("02 05 00 08 00 12 01 2f")
        assertArrayEquals(expected, buildGetRequest("/"))
    }

    @Test
    fun `GET request frame 465 - DEVICE BPB`() {
        val expected = hex("02 0f 00 08 00 12 0b 2f 44 45 56 49 43 45 2e 42 50 42")
        assertArrayEquals(expected, buildGetRequest("/DEVICE.BPB"))
    }

    @Test
    fun `session control frames`() {
        assertArrayEquals(hex("02 08"), buildInitializeSession())
        assertArrayEquals(hex("02 00"), buildStartSync())
        assertArrayEquals(hex("02 01 08 01"), buildStopSyncCompleted())
        assertArrayEquals(hex("02 09"), buildTerminateSession())
    }

    /**
     * GET_DISK_SPACE query frame, traced from real SDK source (pftp_request.proto's
     * `GET_DISK_SPACE = 5` + BlePsFtpUtils.kt's MessageType.QUERY framing + this app's existing
     * RFC76 `0x02` air-packet header) -- NOT captured on a real device. Only the request bytes are
     * pinned here; the response decode (PftpDiskSpaceDecoder) is separately flagged as
     * schema-derived only, still needing a real-device capture to validate.
     */
    @Test
    fun `GET_DISK_SPACE query frame`() {
        assertArrayEquals(hex("02 05 80"), buildDiskSpaceQuery())
    }

    @Test
    fun `GET path length is a varint -- 127 bytes takes one byte, 128 takes two`() {
        val p127 = "/" + "x".repeat(126)
        val f127 = buildGetRequest(p127)
        assertArrayEquals(hex("02 83 00 08 00 12 7F"), f127.copyOfRange(0, 7))
        assertEquals(7 + 127, f127.size)

        val p128 = "/" + "x".repeat(127)
        val f128 = buildGetRequest(p128)
        // RFC60 length 0x0085 = 3 + 2-byte varint + 128; varint 128 = 80 01.
        assertArrayEquals(hex("02 85 00 08 00 12 80 01"), f128.copyOfRange(0, 8))
        assertArrayEquals(p128.toByteArray(), f128.copyOfRange(8, f128.size))
    }

    /** capture9 frame 424: Polar Flow wrote exactly these 3 bytes; the Loop answered with an empty LAST. */
    @Test
    fun `REQUEST_SYNCHRONIZATION query frame matches Polar Flow's`() {
        assertArrayEquals(hex("02 0D 80"), buildRequestSynchronization())
        assertEquals(PftpResponseReader.Step.Done(PftpResult.Empty), PftpResponseReader().accept(hex("02")))
    }

    /**
     * REMOVE frames, framed like a GET with command 3. The 7-byte headers are byte-identical to
     * Polar Flow's own REMOVEs in capture9: a day folder (frame 5680, path length 14) and a 24/7
     * file (frame 5091, path length 23). The paths here are synthetic; only the framing is pinned.
     */
    @Test
    fun `REMOVE request frames match Polar Flow's framing`() {
        val day = buildRemoveRequest(PftpRemoveTarget.of("/U/0/20240229/")!!)
        assertArrayEquals(hex("02 12 00 08 03 12 0e"), day.copyOfRange(0, 7))
        assertArrayEquals("/U/0/20240229/".toByteArray(), day.copyOfRange(7, day.size))
        assertEquals(21, day.size)

        val autos = buildRemoveRequest(PftpRemoveTarget.of("/U/0/AUTOS/AUTOS004.BPB")!!)
        assertArrayEquals(hex("02 1b 00 08 03 12 17"), autos.copyOfRange(0, 7))
        assertArrayEquals("/U/0/AUTOS/AUTOS004.BPB".toByteArray(), autos.copyOfRange(7, autos.size))
        assertEquals(30, autos.size)

        // Only the command byte differs from a GET of the same path.
        val get = buildGetRequest("/U/0/20240229/")
        assertEquals(get.size, day.size)
        assertEquals(listOf(4), get.indices.filter { get[it] != day[it] })
        assertEquals(0x03, day[4].toInt())
    }

    /** capture9's REMOVEs were each answered with `00 00 00`: a status-0 frame, OPERATION_SUCCEEDED, Empty. */
    @Test
    fun `the Loop's answer to a REMOVE parses as Empty`() {
        assertEquals(PftpResponseReader.Step.Done(PftpResult.Empty), PftpResponseReader().accept(hex("00 00 00")))
    }

    @Test
    fun `stream cancel frame`() {
        assertArrayEquals(hex("00 00 00"), buildStreamCancel())
    }
}
