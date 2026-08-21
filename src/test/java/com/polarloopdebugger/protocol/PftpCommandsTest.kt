package com.polarloopdebugger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * HARD REGRESSION GUARD, not optional: reflectively enumerates every `build*` function in
     * PftpCommands.kt and proves none of them can produce a PUT (`08 01`) or REMOVE (`08 03`)
     * PbPFtpOperation command byte. If a new builder is ever added, this test fails the build
     * until it's reviewed and explicitly added to [knownBuilderNames].
     *
     * Session-control frames (built with no args) legitimately contain byte sequences that look
     * like `08 01` -- e.g. buildStopSyncCompleted's PbPFtpStopSyncParams{completed=true} -- but
     * those are never PbPFtpOperation command bytes because a PbPFtpOperation always carries the
     * path field's tag (`0x12`) right after its command byte, and session-control frames never
     * contain a `0x12` byte at all. So this only interprets a byte as an operation's command byte
     * when it's immediately followed by `0x12` -- i.e. only for genuine PbPFtpOperation requests.
     */
    @Test
    fun `no build function can construct PUT or REMOVE`() {
        val clazz = Class.forName("com.polarloopdebugger.protocol.PftpCommandsKt")
        val builders = clazz.declaredMethods.filter { it.name.startsWith("build") }
        assertTrue("expected the known PFTP builders to exist", builders.isNotEmpty())

        val knownBuilderNames = setOf(
            "buildGetRequest",
            "buildInitializeSession",
            "buildStartSync",
            "buildStopSyncCompleted",
            "buildTerminateSession",
            "buildDiskSpaceQuery",
        )

        for (method in builders) {
            assertTrue(
                "a new PFTP command builder '${method.name}' was added -- it MUST be reviewed against " +
                    "the read-only (GET-only) guarantee and added to this test's coverage before merging",
                knownBuilderNames.contains(method.name),
            )

            val result: ByteArray = when {
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) ->
                    method.invoke(null, "/SOME/TEST/PATH.BIN") as ByteArray
                method.parameterTypes.isEmpty() -> method.invoke(null) as ByteArray
                else -> error(
                    "PFTP builder '${method.name}' has an unrecognized parameter shape " +
                        "${method.parameterTypes.toList()} -- this test doesn't know how to exercise it " +
                        "safely; add explicit coverage before merging",
                )
            }

            for (i in result.indices) {
                if (result[i] == 0x12.toByte() && i > 0) {
                    assertEquals(
                        "builder '${method.name}' produced a non-GET PbPFtpOperation command byte",
                        PFTP_COMMAND_GET.toByte(),
                        result[i - 1],
                    )
                }
            }
        }
    }
}
