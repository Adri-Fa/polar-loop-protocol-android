package com.polarloopdebugger.ble

import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PftpDeviceNotification
import com.polarloopdebugger.protocol.PftpDeviceNotificationReader
import com.polarloopdebugger.protocol.PftpResult
import com.polarloopdebugger.protocol.hex
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Single-threaded, step-by-step [GattQueue] behavior. Concurrency is GattQueueStressTest's job. */
class GattQueueTest {

    /**
     * A hand-cranked callback thread: posted work runs on [drain], delayed work on [advance].
     * [leaky] makes removeCallbacks a no-op, the v1.1.1-v1.1.4 bug class (a new Handler per call).
     */
    private class ManualPort(var maxWrite: Int = 495) : GattQueue.Port {
        val writes = mutableListOf<OutgoingFrame>()
        val logs = mutableListOf<String>()
        var acceptWrites = true
        var leaky = false
        private val ready = ArrayDeque<Runnable>()
        private val delayed = mutableListOf<Pair<Long, Runnable>>()
        private var now = 0L

        override fun post(r: Runnable) { ready.addLast(r) }
        override fun postDelayed(r: Runnable, delayMs: Long) { delayed += (now + delayMs) to r }
        override fun removeCallbacks(r: Runnable) { if (!leaky) delayed.removeAll { it.second === r } }
        override fun write(frame: OutgoingFrame) = acceptWrites.also { if (it) writes += frame }
        override fun read(characteristic: String) = true
        override fun maxWriteSize() = maxWrite
        override fun log(message: String) { logs += message }

        val pendingTimers get() = delayed.size

        fun drain() { while (ready.isNotEmpty()) ready.removeFirst().run() }
        fun advance(ms: Long) {
            now += ms
            while (true) {
                val due = delayed.filter { it.first <= now }.minByOrNull { it.first } ?: break
                delayed.remove(due)
                due.second.run()
                drain()
            }
        }
    }

    private val port = ManualPort()
    private val queue = GattQueue(port, packetTimeoutMs = 1_000)
    private val results = mutableListOf<PftpResult>()

    private fun get(path: String) = queue.request(OutgoingFrame.PftpGet(path)) { results += it }
    private fun ack() { queue.onWriteComplete(true); port.drain() }
    private fun packet(h: String) { queue.onPacket(hex(h)); port.drain() }

    @Test
    fun `next request waits for LAST, not for a quiet gap`() {
        get("/A"); get("/B"); port.drain()
        assertEquals(1, port.writes.size)
        ack()
        packet("06 01")
        port.advance(999) // well past the old 400ms quiet gap, still under the packet timeout
        assertEquals(1, port.writes.size)
        packet("13 02")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("01 02"))), results)
        assertEquals(listOf("/A", "/B"), port.writes.map { it.toString() })
    }

    @Test
    fun `a response that ends before its write ack still holds the queue until the ack`() {
        get("/A"); get("/B"); port.drain()
        packet("02 09")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("09"))), results)
        assertEquals(1, port.writes.size) // a stale ack must not complete /B
        ack()
        assertEquals(2, port.writes.size)
    }

    @Test
    fun `silence past the packet timeout is Timeout, then the stream is cancelled before moving on`() {
        get("/A"); get("/B"); port.drain(); ack()
        packet("06 01")
        port.advance(1_000)
        assertEquals(listOf<PftpResult>(PftpResult.Timeout), results)
        assertEquals(OutgoingFrame.StreamCancel, port.writes.last())
        ack() // cancel acked
        assertEquals("/B", port.writes.last().toString())
    }

    @Test
    fun `sequence mismatch sends 00 00 00, reports Cancelled, and drops the rest of the stream`() {
        get("/A"); get("/B"); port.drain(); ack()
        packet("06 01")
        packet("26 02") // sequence 2, expected 1
        assertEquals(OutgoingFrame.StreamCancel, port.writes.last())
        assertTrue((results.single() as PftpResult.Cancelled).reason.contains("sequence 2, expected 1"))
        packet("33 03") // late packet of the cancelled stream
        ack()
        assertEquals(1, results.size)
        assertEquals("/B", port.writes.last().toString())
    }

    @Test
    fun `next-bit mismatch before the write ack cancels once the ack lands`() {
        get("/A"); port.drain()
        packet("03 01") // first packet with next=1
        assertEquals(1, port.writes.size) // can't write the cancel while our own write is outstanding
        ack()
        assertEquals(OutgoingFrame.StreamCancel, port.writes.last())
    }

    @Test
    fun `a request too big for the negotiated MTU fails clearly and writes nothing`() {
        port.maxWrite = 20 // ATT default MTU 23
        get("/U/0/AUTOS/AUTOS000.BPB"); port.drain()
        assertTrue(port.writes.isEmpty())
        val reason = (results.single() as PftpResult.Cancelled).reason
        assertTrue(reason, reason.contains("30 bytes") && reason.contains("allows 20"))
    }

    @Test
    fun `a rejected write is Cancelled and the queue keeps going`() {
        port.acceptWrites = false
        get("/A"); port.drain()
        port.acceptWrites = true
        get("/B"); port.drain()
        assertTrue(results.single() is PftpResult.Cancelled)
        assertEquals("/B", port.writes.single().toString())
    }

    @Test
    fun `packets with nothing in flight are dropped`() {
        packet("02 01")
        get("/A"); port.drain(); ack()
        packet("02 02")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("02"))), results)
    }

    @Test
    fun `cancelAll gives every queued and in-flight request exactly one Cancelled`() {
        var writeDone: Boolean? = null
        var read: ByteArray? = byteArrayOf()
        get("/A"); get("/B")
        queue.write(OutgoingFrame.StartSync) { writeDone = it }
        queue.read("battery") { read = it }
        port.drain()
        queue.cancelAll("disconnected"); port.drain()
        assertEquals(List(2) { PftpResult.Cancelled("disconnected") }, results)
        assertEquals(false, writeDone)
        assertEquals(null, read)
        ack(); packet("02 01") // late GATT callbacks after the cancel change nothing
        assertEquals(2, results.size)
    }

    @Test
    fun `session-control write completes on its ack and never overlaps a streaming response`() {
        var done = false
        get("/A"); queue.write(OutgoingFrame.StopSyncCompleted) { done = it }; port.drain(); ack()
        packet("06 01")
        assertEquals(1, port.writes.size)
        packet("13 02")
        assertEquals(OutgoingFrame.StopSyncCompleted, port.writes.last())
        ack()
        assertTrue(done)
    }

    @Test
    fun `a late timer from a finished request can never time out the next one`() {
        port.leaky = true
        get("/A"); get("/B"); port.drain(); ack() // /A's timer: due at 1000
        port.advance(900)
        packet("02 01") // /A done; /B written at 900
        ack()
        port.advance(100) // /A's stale timer runs while /B is in flight
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("01"))), results)
        assertEquals(listOf("/A", "/B"), port.writes.map { it.toString() }) // no StreamCancel
        packet("02 02")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("01")), PftpResult.Content(hex("02"))), results)
    }

    @Test
    fun `a re-armed timer replaces its request's earlier one, and the live one still fires`() {
        port.leaky = true
        get("/A"); port.drain(); ack() // due at 1000
        port.advance(900)
        packet("06 01") // re-armed: due at 1900
        port.advance(100) // the first arm runs, stale
        assertTrue(results.isEmpty())
        port.advance(800)
        packet("13 02")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("01 02"))), results)
        get("/B"); port.drain(); ack()
        port.advance(1_000)
        assertEquals(PftpResult.Timeout, results.last())
    }

    @Test
    fun `a request's timer is cancelled when the request completes`() {
        get("/A"); port.drain(); ack()
        assertEquals(1, port.pendingTimers)
        packet("06 01")
        assertEquals(1, port.pendingTimers) // re-armed, not added
        packet("13 02")
        assertEquals(0, port.pendingTimers)
        get("/B"); port.drain(); queue.onWriteComplete(false); port.drain() // write failure ends it too
        assertEquals(0, port.pendingTimers)
    }

    @Test
    fun `an error frame with nothing in flight is logged, ignored, and never completes the next request`() {
        packet("00 C8 00") // 200, nothing in flight
        assertTrue(port.logs.single(), port.logs.single().contains("dropped"))
        get("/A"); port.drain(); ack()
        packet("02 05")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("05"))), results)
    }

    @Test
    fun `an error frame after a request is answered, before its write ack, is ignored too`() {
        get("/A"); get("/B"); port.drain()
        packet("02 09") // /A answered, its write not yet acked: still in flight, nothing pending
        packet("00 C8 00")
        assertTrue(port.logs.single(), port.logs.single().contains("dropped"))
        ack()
        packet("02 0A")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("09")), PftpResult.Content(hex("0A"))), results)
    }

    /**
     * PolarGattClient sends fb005c52 frames to its PftpDeviceNotificationReader, never into the
     * queue (checked in its source below). Fed to the queue, either message here would break the GET:
     * the first is a sequence-0 frame mid-stream, the second's LAST would end it early.
     */
    @Test
    fun `a fb005c52 message arriving mid-GET leaves the GET's result unchanged`() {
        val client = File("src/main/java/com/polarloopdebugger/ble/PolarGattClient.kt").readText()
        val branch = { name: String -> client.substringAfter("$name -> {").substringBefore("\n            }") }
        assertTrue(branch("PFTP_EVENT"), "deviceNotifications.accept(value)" in branch("PFTP_EVENT") && "queue" !in branch("PFTP_EVENT"))
        assertTrue(branch("PFTP_REQUEST_RESPONSE"), "queue.onPacket(value)" in branch("PFTP_REQUEST_RESPONSE"))

        val notifications = mutableListOf<PftpDeviceNotification>()
        val reader = PftpDeviceNotificationReader(port.logs::add) { 0L }
        get("/A"); port.drain(); ack()
        packet("06 01")
        reader.accept(hex("02 13 08 01 10 01"))?.let(notifications::add) // one-frame EXERCISE_STATUS
        reader.accept(hex("06 12 0A 01")) // REST_API_EVENT, first frame
        packet("17 02")
        reader.accept(hex("13 05"))?.let(notifications::add) // its LAST
        assertTrue(results.isEmpty())
        packet("23 03")
        assertEquals(listOf<PftpResult>(PftpResult.Content(hex("01 02 03"))), results)
        assertEquals(listOf(19, 18), notifications.map { it.id })
        assertTrue(port.logs.toString(), port.logs.isEmpty())
    }
}
