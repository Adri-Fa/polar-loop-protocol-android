package com.polarloopdebugger.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** fb005c52 reassembly and params decoding. All frames are synthetic: no Loop message was ever captured. */
class PftpDeviceNotificationTest {

    private val logs = mutableListOf<String>()
    private var now = 1_000L
    private val reader = PftpDeviceNotificationReader(logs::add) { now }

    private fun frame(h: String) = reader.accept(hex(h))

    @Test
    fun `a one-frame EXERCISE_STATUS`() {
        val n = frame("02 13 08 01 10 01")!! // LAST, seq 0, next 0; id 19
        assertEquals(19, n.id)
        assertEquals("EXERCISE_STATUS", n.name)
        assertArrayEquals(hex("08 01 10 01"), n.params)
        assertEquals(1_000L, n.receivedAtMillis)
        assertEquals("PftpDeviceNotification(19 EXERCISE_STATUS, 4 B)", n.toString())
        assertEquals(
            ExerciseStatusNotification(ExerciseState.RUNNING, ExerciseStatusNotification.ExerciseType.TRAINING),
            decodeExerciseStatusNotification(n.params),
        )
        assertTrue(logs.isEmpty())
    }

    @Test
    fun `a multi-frame REST_API_EVENT is delivered whole, stamped at its LAST frame`() {
        assertNull(frame("06 12 0A 05 01 02")) // MORE, seq 0, next 0; id 18
        now = 1_450L
        assertNull(frame("17 03 04 05")) // MORE, seq 1
        now = 1_900L
        val n = frame("23 10 01")!! // LAST, seq 2
        assertEquals("REST_API_EVENT", n.name)
        assertArrayEquals(hex("0A 05 01 02 03 04 05 10 01"), n.params)
        assertEquals(1_900L, n.receivedAtMillis)
    }

    @Test
    fun `a continuation with nothing open is logged and dropped`() {
        assertNull(frame("13 01 02"))
        assertTrue(logs.single(), logs.single().contains("no notification open"))
        assertEquals(19, frame("02 13 08 03")!!.id) // the next message is unaffected
    }

    @Test
    fun `a first frame while one is open drops the open one`() {
        assertNull(frame("06 12 0A 05 01 02"))
        val n = frame("02 13 08 03")!!
        assertEquals(19, n.id)
        assertTrue(logs.single(), logs.single().contains("notification 18 still open (4 B)"))
        assertNull(frame("13 03 04")) // the dropped message's continuation finds nothing open
    }

    @Test
    fun `an out-of-sequence continuation, a status-0 frame and a new link each drop the open message`() {
        assertNull(frame("06 12 0A 05")); assertNull(frame("27 01")) // sequence 2, expected 1
        assertNull(frame("06 12 0A 05")); assertNull(frame("00 CA 00"))
        assertNull(frame("06 12 0A 05")); reader.reset()
        assertNull(frame("13 01")) // nothing open after the reset
        assertEquals(3, logs.size) // the reset itself is silent
        assertTrue(logs[0], logs[0].contains("out of sync (sequence 2, expected 1)"))
        assertTrue(logs[1], logs[1].contains("status 0"))
        assertTrue(logs[2], logs[2].contains("no notification open"))
    }

    @Test
    fun `log lines carry sizes, never params`() {
        frame("06 12 AB CD"); frame("02 13 EF"); frame("17 AB")
        assertTrue(logs.toString(), logs.none { Regex("""\bAB\b|\bCD\b|\bEF\b""").containsMatchIn(it) })
    }

    @Test
    fun `FILESYSTEM_MODIFIED params`() {
        val path = "/U/0/20240101/ACT/"
        val params = hex("08 01 12") + byteArrayOf(path.length.toByte()) + path.toByteArray()
        assertEquals(FilesystemModified(FilesystemModified.Action.UPDATED, path), decodeFilesystemModified(params))
        assertEquals(FilesystemModified(null, null), decodeFilesystemModified(ByteArray(0)))
    }

    @Test
    fun `TRAINING_SESSION_STATUS params`() {
        assertEquals(true, decodeTrainingSessionStatus(hex("08 01")))
        assertEquals(false, decodeTrainingSessionStatus(hex("08 00")))
        assertNull(decodeTrainingSessionStatus(ByteArray(0)))
    }

    @Test
    fun `SYNC_REQUIRED params, repeated triggers`() {
        assertEquals(
            listOf(SyncTrigger(SyncTrigger.Source.USER, null), SyncTrigger(SyncTrigger.Source.FEATURE, "hr")),
            decodeSyncRequired(hex("0A 02 08 01 0A 06 08 03 12 02 68 72")),
        )
        assertEquals(emptyList<SyncTrigger>(), decodeSyncRequired(ByteArray(0)))
    }

    @Test
    fun `AUTOSYNC_STATUS params`() {
        assertEquals(AutoSyncStatus(false, "ok"), decodeAutoSyncStatus(hex("08 00 12 02 6F 6B")))
    }

    @Test
    fun `EXERCISE_STATUS params, unknown enum values as null`() {
        assertEquals(
            ExerciseStatusNotification(ExerciseState.OFF, ExerciseStatusNotification.ExerciseType.NIGHTLY_RECHARGE),
            decodeExerciseStatusNotification(hex("08 03 10 02")),
        )
        assertEquals(ExerciseStatusNotification(null, null), decodeExerciseStatusNotification(hex("08 09 10 07")))
    }

    @Test
    fun `ids match pftp_notification proto, 6 unused`() {
        assertEquals((0..19).toList() - 6, PftpDeviceNotificationId.entries.map { it.id })
        assertNull(PftpDeviceNotificationId.nameOf(6))
        assertEquals("PftpDeviceNotification(6 ?, 0 B)", PftpDeviceNotification(6, ByteArray(0), 0).toString())
    }

    @Test
    fun `a notification past 64 KiB is dropped, holds no more than the cap, and the next one decodes`() {
        // MORE frames of 500 B each, sequence 0..15 wrapping: the open notification grows until the cap drops it.
        val chunk = ByteArray(499) { 0x55 }
        assertNull(reader.accept(byteArrayOf(0x06, 0x12) + chunk)) // first: MORE, seq 0, id 18
        var seq = 1
        var dropped = false
        for (i in 0 until 200) {
            val header = (((seq and 0x0F) shl 4) or 0x07).toByte() // MORE, next = 1
            assertNull(reader.accept(byteArrayOf(header) + chunk))
            seq++
            if (logs.any { "over 64 KiB" in it }) { dropped = true; break }
        }
        assertTrue("dropped at the cap", dropped)
        val size = Regex("over 64 KiB \\((\\d+) B\\)").find(logs.single { "over 64 KiB" in it })!!.groupValues[1].toInt()
        assertTrue("held $size B", size in PftpDeviceNotificationReader.MAX_NOTIFICATION_BYTES + 1..PftpDeviceNotificationReader.MAX_NOTIFICATION_BYTES + chunk.size)
        // Its later frames find nothing open; a new one-frame message decodes normally.
        assertNull(reader.accept(byteArrayOf((((seq and 0x0F) shl 4) or 0x03).toByte()) + chunk))
        val n = frame("02 13 08 01 10 01")!!
        assertEquals("EXERCISE_STATUS", n.name)
        assertEquals(64 * 1024, PftpDeviceNotificationReader.MAX_NOTIFICATION_BYTES)
    }
}
