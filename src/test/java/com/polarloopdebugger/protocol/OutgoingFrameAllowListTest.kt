package com.polarloopdebugger.protocol

import com.polarloopdebugger.protocol.OutgoingFrame.DiskSpaceQuery
import com.polarloopdebugger.protocol.OutgoingFrame.EnableNotifications
import com.polarloopdebugger.protocol.OutgoingFrame.ExerciseStatusQuery
import com.polarloopdebugger.protocol.OutgoingFrame.InitializeSession
import com.polarloopdebugger.protocol.OutgoingFrame.PftpGet
import com.polarloopdebugger.protocol.OutgoingFrame.PmdGetSettings
import com.polarloopdebugger.protocol.OutgoingFrame.PmdStart
import com.polarloopdebugger.protocol.OutgoingFrame.PmdStop
import com.polarloopdebugger.protocol.OutgoingFrame.StartSync
import com.polarloopdebugger.protocol.OutgoingFrame.StopSyncCompleted
import com.polarloopdebugger.protocol.OutgoingFrame.StreamCancel
import com.polarloopdebugger.protocol.OutgoingFrame.TerminateSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * HARD REGRESSION GUARD for the read-only guarantee. The library writes only [OutgoingFrame]s, so
 * this file is the complete list of byte strings it can ever emit, per characteristic. [allowed]'s
 * `when` is exhaustive over the sealed type: a new frame type does not compile until it is listed
 * here, and [everyFrame] must build at least one of every subclass.
 */
class OutgoingFrameAllowListTest {

    // ----------------------------------------------------------------------------------------
    // THE ALLOW-LIST
    // ----------------------------------------------------------------------------------------

    /** fb005c53 (PFTP session control): INITIALIZE_SESSION, START_SYNC, STOP_SYNC{completed=true}, TERMINATE_SESSION. */
    private val sessionControl = setOf("02 08", "02 00", "02 01 08 01", "02 09")

    /** fb005c51 (PFTP request/response), fixed frames: GET_DISK_SPACE, GET_EXERCISE_STATUS, stream cancel. */
    private val requestResponseFixed = setOf("02 05 80", "02 19 80", "00 00 00")

    /**
     * fb005c51, the one variable frame -- a GET: `02 [len:u16 LE, bit 15 clear] 08 00 12 [n:varint]
     * [n UTF-8 path bytes]`, len = everything after the 3-byte header.
     */
    private fun isGet(b: ByteArray, path: String): Boolean {
        if (b.size < 7 || b[0] != 0x02.toByte()) return false
        val len = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
        if (len and 0x8000 != 0 || len != b.size - 3) return false
        if (b[3] != 0x08.toByte() || b[4] != 0x00.toByte() || b[5] != 0x12.toByte()) return false
        val reader = ProtoReader(b, 6)
        val n = reader.readVarint().toInt()
        val pathBytes = b.copyOfRange(reader.position(), b.size)
        return pathBytes.size == n && pathBytes.contentEquals(path.toByteArray(Charsets.UTF_8))
    }

    /** fb005c81 (PMD control point): `01 tt` GET_SETTINGS, `02 tt [settings TLV]` START, `03 tt` STOP. */
    private fun isPmd(b: ByteArray): Boolean {
        val types = MeasurementType.entries.map { it.id }
        if (b.size < 2 || (b[1].toInt() and 0xFF) !in types) return false
        return when (b[0].toInt()) {
            0x01, 0x03 -> b.size == 2
            0x02 -> true
            else -> false
        }
    }

    /** CCCD (0x2902) values: indications on fb005c81, notifications on the other four. */
    private val cccd = mapOf(
        PMD_CONTROL_POINT_UUID to "02 00",
        PMD_DATA_UUID to "01 00",
        HR_MEASUREMENT_UUID to "01 00",
        PFTP_REQUEST_RESPONSE_UUID to "01 00",
        PFTP_EVENT_UUID to "01 00",
    )

    private fun allowed(f: OutgoingFrame): Boolean {
        val b = f.bytes
        val h = b.toHex()
        return when (f) {
            is PftpGet -> f.characteristic == PFTP_REQUEST_RESPONSE_UUID && isGet(b, f.path)
            DiskSpaceQuery, ExerciseStatusQuery, StreamCancel ->
                f.characteristic == PFTP_REQUEST_RESPONSE_UUID && h in requestResponseFixed
            InitializeSession, StartSync, StopSyncCompleted, TerminateSession ->
                f.characteristic == PFTP_SESSION_CONTROL_UUID && h in sessionControl
            is PmdGetSettings, is PmdStart, is PmdStop -> f.characteristic == PMD_CONTROL_POINT_UUID && isPmd(b)
            is EnableNotifications -> cccd[f.characteristic] == h
        }
    }

    // ----------------------------------------------------------------------------------------
    // Everything the library can build
    // ----------------------------------------------------------------------------------------

    private val paths: List<String> = run {
        val random = Random(76)
        val fuzzed = List(1000) {
            String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0x800).toChar() })
        }
        listOf(
            "/", "/DEVICE.BPB", "/U/0/AUTOS/AUTOS000.BPB", "", "/U/0/20260728/E/171729/00/SAMPLES.BPB",
            "\u0008\u0001", "\u0008\u0003", "\u0012\u0000/x", // bytes that spell PUT/REMOVE inside the path
            "x".repeat(127), "x".repeat(128), "x".repeat(16_383), "é".repeat(100),
        ) + fuzzed
    }

    private val tlvs = listOf(ByteArray(0), hex("00-01-16-00-01-01-18-00-04-01-02"), hex("08 03"), hex("FF FF FF"))

    private fun everyFrame(): List<OutgoingFrame> =
        listOf(DiskSpaceQuery, ExerciseStatusQuery, StreamCancel, InitializeSession, StartSync, StopSyncCompleted, TerminateSession) +
            paths.map(::PftpGet) +
            MeasurementType.entries.flatMap { t -> listOf(PmdGetSettings(t), PmdStop(t)) + tlvs.map { PmdStart(t, it) } } +
            NotifyTarget.entries.map(::EnableNotifications)

    private fun on(characteristic: String) = everyFrame().filter { it.characteristic == characteristic }

    // ----------------------------------------------------------------------------------------

    @Test
    fun `every frame the library can build is on the allow-list`() {
        for (f in everyFrame()) assertTrue("not allow-listed: $f = ${f.bytes.take(16)}", allowed(f))
    }

    @Test
    fun `the enumeration covers every OutgoingFrame subclass`() {
        assertEquals(
            OutgoingFrame::class.java.permittedSubclasses.toSet(),
            everyFrame().map { it.javaClass }.toSet(),
        )
    }

    @Test
    fun `the allow-list is exact -- every fixed entry is actually emitted`() {
        assertEquals(sessionControl, on(PFTP_SESSION_CONTROL_UUID).map { it.bytes.toHex() }.toSet())
        assertEquals(
            requestResponseFixed,
            on(PFTP_REQUEST_RESPONSE_UUID).filter { it !is PftpGet && it !is EnableNotifications }.map { it.bytes.toHex() }.toSet(),
        )
        assertEquals(cccd, everyFrame().filterIsInstance<EnableNotifications>().associate { it.characteristic to it.bytes.toHex() })
    }

    @Test
    fun `fb005c53 can never carry 02 02 (RESET)`() {
        val frames = on(PFTP_SESSION_CONTROL_UUID)
        assertTrue(frames.none { it.bytes.contentEquals(hex("02 02")) })
        // Byte 1 is the PbPFtpHostToDevNotification id: START_SYNC, STOP_SYNC, INITIALIZE_SESSION, TERMINATE_SESSION only.
        assertEquals(setOf(0, 1, 8, 9), frames.map { it.bytes[1].toInt() }.toSet())
    }

    @Test
    fun `fb005c51 can never carry a PUT, MERGE or REMOVE command`() {
        for (f in on(PFTP_REQUEST_RESPONSE_UUID).filter { it !is EnableNotifications }) {
            val b = f.bytes
            if ((b[0].toInt() shr 1) and 3 == 0) {
                assertEquals("only the stream cancel is a status-0 frame", StreamCancel, f)
                continue
            }
            assertEquals(0x02, b[0].toInt()) // first and only packet, sequence 0, LAST
            if (b[2].toInt() and 0x80 != 0) {
                // Query: RFC60 id, never an operation. Only GET_DISK_SPACE (5) and GET_EXERCISE_STATUS (25).
                val id = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0x7F) shl 8)
                assertTrue("query id $id", id == 5 || id == 25)
                continue
            }
            // PbPFtpOperation: command (field 1) must be GET = 0, whatever bytes the path holds.
            val reader = ProtoReader(b.copyOfRange(3, b.size))
            val fields = buildList { while (reader.hasMore()) add(reader.readField()) }
            assertEquals(listOf(1, 2), fields.map { it.fieldNumber })
            assertEquals("command of $f", 0L, fields[0].varint)
        }
    }

    @Test
    fun `fb005c81 can never carry a PMD opcode outside 1 to 3`() {
        assertEquals(setOf(1, 2, 3), OpCode.entries.map { it.id }.toSet())
        for (f in on(PMD_CONTROL_POINT_UUID).filter { it !is EnableNotifications }) {
            assertTrue("opcode ${f.bytes[0]} in $f", f.bytes[0].toInt() in 1..3)
        }
    }

    @Test
    fun `frames are immutable -- mutating bytes can't turn START_SYNC into RESET`() {
        StartSync.bytes[1] = 0x02
        assertEquals("02 00", StartSync.bytes.toHex())
    }
}
