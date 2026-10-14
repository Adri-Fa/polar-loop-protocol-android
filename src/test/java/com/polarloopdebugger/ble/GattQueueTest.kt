package com.polarloopdebugger.ble

import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PftpResult
import com.polarloopdebugger.protocol.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Single-threaded, step-by-step [GattQueue] behavior. Concurrency is GattQueueStressTest's job. */
class GattQueueTest {

    /** A hand-cranked callback thread: posted work runs on [drain], delayed work on [advance]. */
    private class ManualPort(var maxWrite: Int = 495) : GattQueue.Port {
        val writes = mutableListOf<OutgoingFrame>()
        var acceptWrites = true
        private val ready = ArrayDeque<Runnable>()
        private val delayed = mutableListOf<Pair<Long, Runnable>>()
        private var now = 0L

        override fun post(r: Runnable) { ready.addLast(r) }
        override fun postDelayed(r: Runnable, delayMs: Long) { delayed += (now + delayMs) to r }
        override fun removeCallbacks(r: Runnable) { delayed.removeAll { it.second === r } }
        override fun write(frame: OutgoingFrame) = acceptWrites.also { if (it) writes += frame }
        override fun read(characteristic: String) = true
        override fun maxWriteSize() = maxWrite
        override fun log(message: String) {}

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
}
