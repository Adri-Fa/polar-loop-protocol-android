package com.polarloopdebugger.protocol

import com.polarloopdebugger.protocol.OutgoingFrame.DayFolderRemoval
import com.polarloopdebugger.protocol.OutgoingFrame.DiskSpaceQuery
import com.polarloopdebugger.protocol.OutgoingFrame.EnableNotifications
import com.polarloopdebugger.protocol.OutgoingFrame.ExerciseStatusQuery
import com.polarloopdebugger.protocol.OutgoingFrame.InitializeSession
import com.polarloopdebugger.protocol.OutgoingFrame.PftpGet
import com.polarloopdebugger.protocol.OutgoingFrame.PftpRemove
import com.polarloopdebugger.protocol.OutgoingFrame.PmdGetSettings
import com.polarloopdebugger.protocol.OutgoingFrame.PmdStart
import com.polarloopdebugger.protocol.OutgoingFrame.PmdStop
import com.polarloopdebugger.protocol.OutgoingFrame.RequestSynchronization
import com.polarloopdebugger.protocol.OutgoingFrame.SetLocalTime
import com.polarloopdebugger.protocol.OutgoingFrame.SetSystemTime
import com.polarloopdebugger.protocol.OutgoingFrame.StartSync
import com.polarloopdebugger.protocol.OutgoingFrame.StopSyncCompleted
import com.polarloopdebugger.protocol.OutgoingFrame.StreamCancel
import com.polarloopdebugger.protocol.OutgoingFrame.TerminateSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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

    /**
     * fb005c51 (PFTP request/response), fixed frames: GET_DISK_SPACE, GET_EXERCISE_STATUS,
     * REQUEST_SYNCHRONIZATION (opt-in only, see the last test), stream cancel.
     */
    private val requestResponseFixed = setOf("02 05 80", "02 19 80", "02 0D 80", "00 00 00")

    /**
     * fb005c51, the one variable frame -- a GET: `02 [len:u16 LE, bit 15 clear] 08 00 12 [n:varint]
     * [n UTF-8 path bytes]`, len = everything after the 3-byte header.
     */
    private fun isGet(b: ByteArray, path: String): Boolean = isOperation(b, 0x00, path)

    /**
     * fb005c51, the other variable frame -- a REMOVE (v1.4.0): the same framing with command
     * `08 03`, and a path that is exactly a day folder or a 24/7 file (PftpRemoveTarget's two
     * shapes, re-derived here independently of that class).
     */
    private fun isRemove(b: ByteArray, target: PftpRemoveTarget): Boolean = isRemoveShape(target.path) && isOperation(b, 0x03, target.path)

    private fun isRemoveShape(p: String): Boolean {
        Regex("/U/0/([0-9]{8})/").matchEntire(p)?.let { m ->
            val strict = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT)
            return try { java.time.LocalDate.parse(m.groupValues[1], strict); true } catch (e: Exception) { false }
        }
        return Regex("/U/0/AUTOS/AUTOS[0-9]{3}\\.BPB").matches(p)
    }

    /**
     * fb005c51 (v1.5.0, opt-in): a REMOVE of exactly `/U/0/YYYYMMDD` with a real date and no
     * trailing slash, the SDK's form; re-derived here independently of SdkDayFolder.
     */
    private fun isDayFolderRemoval(b: ByteArray, folder: SdkDayFolder): Boolean {
        val m = Regex("/U/0/([0-9]{8})").matchEntire(folder.path) ?: return false
        val strict = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(java.time.format.ResolverStyle.STRICT)
        val date = try { LocalDate.parse(m.groupValues[1], strict) } catch (e: Exception) { return false }
        return date == folder.date && isOperation(b, 0x03, folder.path)
    }

    /**
     * fb005c51 (v1.5.0, opt-in): query [id] (3 SET_LOCAL_TIME or 1 SET_SYSTEM_TIME), `02 [id] 80`
     * then exactly three fields: 1 PbDate{year, month, day}, 2 PbTime{hour, minute, seconds,
     * millis}, 3 a varint (the offset, or trusted = 1). Decoded here independently and compared with
     * the value the frame was built from.
     */
    private fun isTimeQuery(b: ByteArray, id: Int, t: LocalDateTime, field3: Long): Boolean {
        if (b.size < 3 || b[0] != 0x02.toByte() || (b[1].toInt() and 0xFF) != id || b[2] != 0x80.toByte()) return false
        val r = ProtoReader(b.copyOfRange(3, b.size))
        val fields = buildList { while (r.hasMore()) add(r.readField()) }
        if (fields.map { it.fieldNumber } != listOf(1, 2, 3)) return false
        fun ints(bytes: ByteArray?): List<Long> {
            val inner = ProtoReader(bytes ?: return emptyList())
            return buildList { while (inner.hasMore()) inner.readField().let { add(it.fieldNumber.toLong() * 1_000_000_000L + it.varint!!) } }
        }
        val date = ints(fields[0].bytes).map { it % 1_000_000_000L }
        val time = ints(fields[1].bytes).map { it % 1_000_000_000L }
        return date == listOf(t.year.toLong(), t.monthValue.toLong(), t.dayOfMonth.toLong()) &&
            time == listOf(t.hour.toLong(), t.minute.toLong(), t.second.toLong(), (t.nano / 1_000_000).toLong()) &&
            fields[2].varint == field3
    }

    private fun isOperation(b: ByteArray, command: Int, path: String): Boolean {
        if (b.size < 7 || b[0] != 0x02.toByte()) return false
        val len = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8)
        if (len and 0x8000 != 0 || len != b.size - 3) return false
        if (b[3] != 0x08.toByte() || b[4] != command.toByte() || b[5] != 0x12.toByte()) return false
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
            is PftpRemove -> f.characteristic == PFTP_REQUEST_RESPONSE_UUID && isRemove(b, f.target)
            is DayFolderRemoval -> f.characteristic == PFTP_REQUEST_RESPONSE_UUID && isDayFolderRemoval(b, f.folder)
            // The offset goes out as a protobuf int32: a western one is the varint of its 64-bit two's complement.
            is SetLocalTime -> f.characteristic == PFTP_REQUEST_RESPONSE_UUID && isTimeQuery(b, 3, f.setting.dateTime, f.setting.offsetMinutes.toLong())
            is SetSystemTime -> f.characteristic == PFTP_REQUEST_RESPONSE_UUID && isTimeQuery(b, 1, LocalDateTime.ofInstant(f.setting.instant, ZoneOffset.UTC), 1L)
            DiskSpaceQuery, ExerciseStatusQuery, RequestSynchronization, StreamCancel ->
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
            "/", "/DEVICE.BPB", "/U/0/AUTOS/AUTOS000.BPB", "", "/U/0/20240310/E/120000/00/SAMPLES.BPB",
            "\u0008\u0001", "\u0008\u0003", "\u0012\u0000/x", // bytes that spell PUT/REMOVE inside the path
            "x".repeat(127), "x".repeat(128), "x".repeat(16_383), "é".repeat(100),
        ) + fuzzed
    }

    private val tlvs = listOf(ByteArray(0), hex("00-01-16-00-01-01-18-00-04-01-02"), hex("08 03"), hex("FF FF FF"))

    /** Every REMOVE target that can exist: the two shapes, from synthetic paths. */
    private val removeTargets: List<PftpRemoveTarget> =
        listOf("/U/0/20240229/", "/U/0/20991231/", "/U/0/AUTOS/AUTOS000.BPB", "/U/0/AUTOS/AUTOS999.BPB")
            .map { PftpRemoveTarget.of(it)!! }

    /** Day folders in the SDK's form (v1.5.0): the edges of the range, a leap day, random dates. */
    private val sdkDayFolders: List<SdkDayFolder> = run {
        val random = Random(150)
        (listOf(LocalDate.of(2000, 1, 1), LocalDate.of(2099, 12, 31), LocalDate.of(2024, 2, 29)) +
            List(200) { LocalDate.ofEpochDay(random.nextLong(LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 1, 1).toEpochDay())) })
            .map { SdkDayFolder.of(it)!! }
    }

    /** Times (v1.5.0): midnight with zero millis, the last millisecond of a year, every offset edge, random ones. */
    private val localTimes: List<LocalTimeSetting> = run {
        val random = Random(3)
        listOf(
            LocalTimeSetting.of(LocalDateTime.of(2026, 10, 25, 0, 0, 0, 0), 0)!!,
            LocalTimeSetting.of(LocalDateTime.of(2099, 12, 31, 23, 59, 59, 999_000_000), 840)!!,
            LocalTimeSetting.of(LocalDateTime.of(2000, 1, 1, 12, 0), -720)!!,
            LocalTimeSetting.of(LocalDateTime.of(2026, 3, 29, 2, 30, 15, 7_000_000), -300)!!,
        ) + List(200) {
            LocalTimeSetting.of(LocalDateTime.ofEpochSecond(random.nextLong(946_684_800, 4_102_444_799), random.nextInt(0, 1_000) * 1_000_000, ZoneOffset.UTC), random.nextInt(-48, 57) * 15)!!
        }
    }

    private val systemTimes: List<SystemTimeSetting> = run {
        val random = Random(1)
        listOf(Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2099-12-31T23:59:59.999Z"), Instant.parse("2026-10-25T01:00:00Z")).map { SystemTimeSetting.of(it)!! } +
            List(200) { SystemTimeSetting.of(Instant.ofEpochMilli(random.nextLong(946_684_800_000, 4_102_444_799_000)))!! }
    }

    private fun everyFrame(): List<OutgoingFrame> =
        listOf(
            DiskSpaceQuery, ExerciseStatusQuery, RequestSynchronization, StreamCancel,
            InitializeSession, StartSync, StopSyncCompleted, TerminateSession,
        ) +
            paths.map(::PftpGet) +
            removeTargets.map(::PftpRemove) +
            sdkDayFolders.map(::DayFolderRemoval) + localTimes.map(::SetLocalTime) + systemTimes.map(::SetSystemTime) +
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
            on(PFTP_REQUEST_RESPONSE_UUID).filter { it !is PftpGet && it !is PftpRemove && it !is DayFolderRemoval && it !is SetLocalTime && it !is SetSystemTime && it !is EnableNotifications }.map { it.bytes.toHex() }.toSet(),
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
    fun `fb005c51 can never carry a PUT or MERGE command, and a REMOVE only of a PftpRemoveTarget`() {
        for (f in on(PFTP_REQUEST_RESPONSE_UUID).filter { it !is EnableNotifications }) {
            val b = f.bytes
            if ((b[0].toInt() shr 1) and 3 == 0) {
                assertEquals("only the stream cancel is a status-0 frame", StreamCancel, f)
                continue
            }
            assertEquals(0x02, b[0].toInt()) // first and only packet, sequence 0, LAST
            if (b[2].toInt() and 0x80 != 0) {
                // Query: RFC60 id, never an operation. Only GET_DISK_SPACE (5), REQUEST_SYNCHRONIZATION
                // (13), GET_EXERCISE_STATUS (25), and (v1.5.0) SET_LOCAL_TIME (3) and SET_SYSTEM_TIME (1)
                // from their own frames only.
                val id = (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0x7F) shl 8)
                assertTrue("query id $id", id in setOf(1, 3, 5, 13, 25))
                if (id == 3) assertTrue("query 3 from $f", f is SetLocalTime)
                if (id == 1) assertTrue("query 1 from $f", f is SetSystemTime)
                continue
            }
            // PbPFtpOperation: command (field 1) is GET = 0 whatever bytes the path holds, or
            // REMOVE = 3 only from a PftpRemove, whose path is its target's. Never PUT = 1 or MERGE = 2.
            val reader = ProtoReader(b.copyOfRange(3, b.size))
            val fields = buildList { while (reader.hasMore()) add(reader.readField()) }
            assertEquals(listOf(1, 2), fields.map { it.fieldNumber })
            val command = fields[0].varint
            if (f is PftpRemove) {
                assertEquals("command of $f", 3L, command)
                assertEquals(f.target.path, String(fields[1].bytes!!, Charsets.UTF_8))
            } else if (f is DayFolderRemoval) {
                assertEquals("command of $f", 3L, command)
                assertEquals(f.folder.path, String(fields[1].bytes!!, Charsets.UTF_8))
            } else {
                assertEquals("command of $f", 0L, command)
            }
            assertTrue("PUT/MERGE in $f", command != 1L && command != 2L)
        }
    }

    /**
     * REMOVE is buildable from a PftpRemoveTarget and from nothing else. A target exists only for
     * the two shapes, so the set of REMOVE frames is exactly {day folders} ∪ {24/7 files}; here a
     * REMOVE frame is reassembled for every rejected path and shown to be unbuildable.
     */
    @Test
    fun `a REMOVE of anything but a day folder or a 24-7 file cannot be built`() {
        val notTargets = paths + listOf("/U/0/S/", "/SYS/", "/DEVICE.BPB", "/U/0/USERID.BPB", "/U/0/", "/", "/U/0/../20240229/", "/u/0/20240229/", "/U/0/20261340/")
        // The fuzz list holds one valid 24/7 path on purpose; the independent shape check says which.
        for (p in notTargets) assertEquals("REMOVE buildable for '$p'", isRemoveShape(p), PftpRemoveTarget.of(p) != null)
        assertEquals(1, notTargets.count(::isRemoveShape))
        assertEquals(setOf(PftpRemoveTarget.DayFolder::class.java, PftpRemoveTarget.AutosFile::class.java), PftpRemoveTarget::class.java.permittedSubclasses.toSet())
        // No other way to a REMOVE frame: PftpRemove's only constructor takes the target type.
        assertEquals(listOf(PftpRemoveTarget::class.java), PftpRemove::class.java.constructors.single().parameterTypes.toList())
    }

    /**
     * The library never REMOVEs on its own. Every non-comment line under src/main that names the
     * command, its bytes, its builder, its frame or its target type must be one of these: the
     * constant, the builder, the frame, the target type's own file, and the two public opt-in
     * methods. A new call site anywhere in the library fails here.
     */
    @Test
    fun `the library never sends REMOVE on its own`() {
        val mentions = File("src/main/java").walk().filter { it.extension == "kt" && it.name != "PftpRemoveTarget.kt" }.flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("*") && !it.startsWith("/*") && !it.startsWith("//") }
                .filter { it.contains("PftpRemove") || it.contains("buildRemoveRequest") || it.contains("PFTP_COMMAND_REMOVE") || it.contains("sendPftpRemove") }
                .map { "${file.name}: $it" }
        }.toSet()
        assertEquals(
            setOf(
                "PftpConstants.kt: internal const val PFTP_COMMAND_REMOVE = 0x03",
                "PftpCommands.kt: fun buildRemoveRequest(target: PftpRemoveTarget): ByteArray = buildOperation(PFTP_COMMAND_REMOVE, target.path)",
                // v1.5.0's day-folder removal shares the command constant; it has its own tripwire below.
                "PftpCommands.kt: fun buildDayFolderRemoval(folder: SdkDayFolder): ByteArray = buildOperation(PFTP_COMMAND_REMOVE, folder.path)",
                "OutgoingFrame.kt: class PftpRemove(val target: PftpRemoveTarget) : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildRemoveRequest(target)) {",
                "PolarGattClient.kt: import com.polarloopdebugger.protocol.PftpRemoveTarget",
                "PolarGattClient.kt: fun sendPftpRemove(target: PftpRemoveTarget, onResult: (PftpResult) -> Unit) = request(OutgoingFrame.PftpRemove(target), onResult)",
                "ConnectionManager.kt: import com.polarloopdebugger.protocol.PftpRemoveTarget",
                "ConnectionManager.kt: fun sendPftpRemove(target: PftpRemoveTarget, onResult: (PftpResult) -> Unit) = client.sendPftpRemove(target, onResult)",
            ),
            mentions,
        )
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

    /**
     * REQUEST_SYNCHRONIZATION is opt-in: Polar documents it as making the device flush data to
     * files, so only the app may send it. A source tripwire: every non-comment line under src/main
     * that names it must be one of these -- its bytes, builder, frame, and the two public opt-in
     * methods. A new call site anywhere in the library (checkSyncGate, a GET, ...) fails here.
     */
    @Test
    fun `the library never sends REQUEST_SYNCHRONIZATION on its own`() {
        val mentions = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("*") && !it.startsWith("/*") && !it.startsWith("//") }
                .filter { it.contains("RequestSynchronization", ignoreCase = true) || "REQUEST_SYNCHRONIZATION" in it }
                .map { "${file.name}: $it" }
        }.toSet()
        assertEquals(
            setOf(
                "PftpConstants.kt: internal val REQUEST_SYNCHRONIZATION_BYTES = byteArrayOf(0x02, 0x0D, 0x80.toByte())",
                "PftpCommands.kt: fun buildRequestSynchronization(): ByteArray = REQUEST_SYNCHRONIZATION_BYTES.copyOf()",
                "OutgoingFrame.kt: data object RequestSynchronization : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildRequestSynchronization())",
                "PolarGattClient.kt: fun requestSynchronization(onResult: (PftpResult) -> Unit) = request(OutgoingFrame.RequestSynchronization, onResult)",
                "ConnectionManager.kt: fun requestSynchronization(onResult: (PftpResult) -> Unit) = client.requestSynchronization(onResult)",
            ),
            mentions,
        )
    }

    // ----------------------------------------------------------------------------------------
    // v1.5.0: the day folder in the SDK's form, and setting the clock. Each its own opt-in.
    // ----------------------------------------------------------------------------------------

    private fun mentions(names: List<String>, ownFile: String) =
        File("src/main/java").walk().filter { it.extension == "kt" && it.name != ownFile }.flatMap { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("*") && !it.startsWith("/*") && !it.startsWith("//") && !it.startsWith("import ") }
                .filter { line -> names.any { it in line } }
                .map { "${file.name}: $it" }
        }.toSet()

    @Test
    fun `a day-folder removal is exactly the SDK's form, from an SdkDayFolder only`() {
        for (f in sdkDayFolders) {
            assertTrue("no trailing slash: ${f.path}", Regex("/U/0/[0-9]{8}").matches(f.path))
            assertEquals(f.date.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE), f.path.removePrefix("/U/0/"))
        }
        assertEquals(null, SdkDayFolder.of(LocalDate.of(1999, 12, 31)))
        assertEquals(null, SdkDayFolder.of(LocalDate.of(2100, 1, 1)))
        // A header like Polar Flow's day-folder REMOVE (capture9 frame 5680, `02 12 00 08 03 12 0e`), one byte shorter: no slash.
        assertEquals("02 11 00 08 03 12 0D", DayFolderRemoval(SdkDayFolder.of(LocalDate.of(2024, 2, 29))!!).bytes.copyOfRange(0, 7).toHex())
        assertEquals("/U/0/20240229", String(DayFolderRemoval(SdkDayFolder.of(LocalDate.of(2024, 2, 29))!!).bytes.copyOfRange(7, 20), Charsets.UTF_8))
        // No way in but the closed type: the frame's only constructor takes it, and SdkDayFolder has no public constructor.
        assertEquals(listOf(SdkDayFolder::class.java), DayFolderRemoval::class.java.constructors.single().parameterTypes.toList())
        assertTrue("no constructor callable from source", SdkDayFolder::class.java.constructors.all { it.isSynthetic })
    }

    @Test
    fun `the library never sends a day-folder removal on its own`() {
        assertEquals(
            setOf(
                "PftpCommands.kt: fun buildDayFolderRemoval(folder: SdkDayFolder): ByteArray = buildOperation(PFTP_COMMAND_REMOVE, folder.path)",
                "OutgoingFrame.kt: class DayFolderRemoval(val folder: SdkDayFolder) : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildDayFolderRemoval(folder)) {",
                "PolarGattClient.kt: fun sendDayFolderRemoval(folder: SdkDayFolder, onResult: (PftpResult) -> Unit) = request(OutgoingFrame.DayFolderRemoval(folder), onResult)",
                "ConnectionManager.kt: fun sendDayFolderRemoval(folder: SdkDayFolder, onResult: (PftpResult) -> Unit) = client.sendDayFolderRemoval(folder, onResult)",
            ),
            mentions(listOf("SdkDayFolder", "DayFolderRemoval"), ownFile = "PftpDayFolderRemoval.kt"),
        )
    }

    @Test
    fun `the time frames take only the closed settings, which refuse what no zone uses`() {
        assertEquals(listOf(LocalTimeSetting::class.java), SetLocalTime::class.java.constructors.single().parameterTypes.toList())
        assertEquals(listOf(SystemTimeSetting::class.java), SetSystemTime::class.java.constructors.single().parameterTypes.toList())
        assertTrue("no constructor callable from source", LocalTimeSetting::class.java.constructors.all { it.isSynthetic })
        assertTrue("no constructor callable from source", SystemTimeSetting::class.java.constructors.all { it.isSynthetic })
        val t = LocalDateTime.of(2026, 11, 14, 12, 0)
        for (bad in listOf(-735, 855, 7, 61, Int.MAX_VALUE, Int.MIN_VALUE)) assertEquals("offset $bad", null, LocalTimeSetting.of(t, bad))
        assertEquals(null, LocalTimeSetting.of(LocalDateTime.of(1999, 12, 31, 23, 59), 0))
        assertEquals(null, SystemTimeSetting.of(Instant.parse("2100-01-01T00:00:00Z")))
        // Synthetic values, encoded by hand; header and size like Flow's (3 + 22 B each).
        val local = SetLocalTime(LocalTimeSetting.of(LocalDateTime.of(2025, 4, 18, 9, 41, 7, 512_000_000), 60)!!).bytes
        val system = SetSystemTime(SystemTimeSetting.of(Instant.parse("2025-04-18T08:41:07.640Z"))!!).bytes
        assertEquals("02 03 80" to 25, local.copyOfRange(0, 3).toHex() to local.size)
        assertEquals("02 01 80" to 25, system.copyOfRange(0, 3).toHex() to system.size)
        assertEquals("02 03 80 0A 07 08 E9 0F 10 04 18 12 12 09 08 09 10 29 18 07 20 80 04 18 3C", local.toHex())
        assertEquals("02 01 80 0A 07 08 E9 0F 10 04 18 12 12 09 08 08 10 29 18 07 20 80 05 18 01", system.toHex())
        // A western offset is a negative int32: ten varint bytes, as protobuf writes it.
        val west = SetLocalTime(LocalTimeSetting.of(t, -300)!!).bytes
        assertEquals("18 D4 FD FF FF FF FF FF FF FF 01", west.copyOfRange(west.size - 11, west.size).toHex())
    }

    @Test
    fun `the library never sets the time on its own`() {
        assertEquals(
            setOf(
                "PftpConstants.kt: internal const val QUERY_SET_SYSTEM_TIME = 1",
                "PftpConstants.kt: internal const val QUERY_SET_LOCAL_TIME = 3",
                "PftpCommands.kt: fun buildSetLocalTime(setting: LocalTimeSetting): ByteArray =",
                "PftpCommands.kt: query(QUERY_SET_LOCAL_TIME, dateTimeFields(setting.dateTime) + varintField(3, setting.offsetMinutes.toLong()))",
                "PftpCommands.kt: fun buildSetSystemTime(setting: SystemTimeSetting): ByteArray =",
                "PftpCommands.kt: query(QUERY_SET_SYSTEM_TIME, dateTimeFields(setting.utc) + varintField(3, 1))",
                "OutgoingFrame.kt: class SetLocalTime(val setting: LocalTimeSetting) : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildSetLocalTime(setting)) {",
                "OutgoingFrame.kt: class SetSystemTime(val setting: SystemTimeSetting) : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildSetSystemTime(setting)) {",
                "PolarGattClient.kt: fun sendPftpSetTime(local: LocalTimeSetting, system: SystemTimeSetting, onResult: (local: PftpResult, system: PftpResult) -> Unit) {",
                "PolarGattClient.kt: request(OutgoingFrame.SetLocalTime(local)) { localResult = it }",
                "PolarGattClient.kt: request(OutgoingFrame.SetSystemTime(system)) { onResult(localResult, it) }",
                "ConnectionManager.kt: fun sendPftpSetTime(local: LocalTimeSetting, system: SystemTimeSetting, onResult: (local: PftpResult, system: PftpResult) -> Unit) =",
                "ConnectionManager.kt: client.sendPftpSetTime(local, system, onResult)",
            ),
            mentions(listOf("SetLocalTime", "SetSystemTime", "LocalTimeSetting", "SystemTimeSetting", "QUERY_SET_", "sendPftpSetTime"), ownFile = "PftpTimeSetting.kt"),
        )
    }
}
