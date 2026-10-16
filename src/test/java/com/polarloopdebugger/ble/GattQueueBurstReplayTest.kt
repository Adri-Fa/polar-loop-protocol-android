package com.polarloopdebugger.ble

import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PftpResult
import com.polarloopdebugger.protocol.fixturePacket
import com.polarloopdebugger.protocol.fixtureResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays loopCompanion's v1.1.4 burst (captures/lc-v114-burst.txt) through [GattQueue]: every
 * request enqueued at once, in LC's order, as LoopSync.resolve() does after the listing. On the
 * wire, v1.1.4 wrote GETs while multi-frame answers were still streaming, three answers were cut
 * after one frame, and the Loop answered SYSTEM_BUSY from then on.
 *
 * Timings are synthetic: the fake Loop acks a write after [ACK_MS], starts its answer [FIRST_MS]
 * after the ack, and sends the frames of one answer [GAP_MS] apart -- more than v1.1.x's 400 ms
 * quiet gap, less than the packet timeout. That timeout is [TIMEOUT_MS], not the library's 10 s:
 * the replay runs about 6 s, so with 10 s no timer could come due while a later request is in
 * flight, and the leaky variant would prove nothing. Every gap stays under it.
 */
class GattQueueBurstReplayTest {

    private class Exchange(val source: String, val frame: OutgoingFrame, val expected: PftpResult, val packets: List<ByteArray>)

    private val exchanges = javaClass.getResourceAsStream("/captures/lc-v114-burst.txt")!!.bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(" ")
            val frame = if (parts[1] == "QUERY5") OutgoingFrame.DiskSpaceQuery else OutgoingFrame.PftpGet(parts[1])
            Exchange(parts[0], frame, fixtureResult(parts[2])!!, parts.drop(3).map(::fixturePacket))
        }

    /**
     * The Loop as modelled here, on one virtual clock shared with the queue's timers. It answers
     * requests in the order they reach it, one answer at a time, each with its fixture packets.
     * [leaky] makes removeCallbacks a no-op (the v1.1.1-v1.1.4 bug class). [strayAfter]: after
     * that exchange's final packet, the Loop sends one extra `00 C8 00` (200) before its next answer.
     */
    private class Loop(val exchanges: List<Exchange>, val leaky: Boolean = false, val strayAfter: String? = null) : GattQueue.Port {
        inner class Answer(val exchange: Exchange) {
            var acked = false
            var sent = 0
            var cancelled = false
            val complete get() = sent == exchange.packets.size
        }

        val queue = GattQueue(this, TIMEOUT_MS)
        val results = linkedMapOf<String, MutableList<PftpResult>>()
        val answers = mutableListOf<Answer>()
        val violations = mutableListOf<String>()
        val logs = mutableListOf<String>()
        private val owed = ArrayDeque<Answer>() // answers the Loop still has to send, oldest first

        private class Timed(val at: Long, val order: Long, val r: Runnable)
        private val ready = ArrayDeque<Runnable>()
        private val scheduled = mutableListOf<Timed>()
        private var now = 0L
        private var order = 0L

        private fun at(delayMs: Long, r: Runnable) { scheduled += Timed(now + delayMs, order++, r) }

        override fun post(r: Runnable) { ready.addLast(r) }
        override fun postDelayed(r: Runnable, delayMs: Long) = at(delayMs, r)
        override fun removeCallbacks(r: Runnable) { if (!leaky) scheduled.removeAll { it.r === r } }
        override fun read(characteristic: String) = error("no reads in this replay")
        override fun maxWriteSize() = 495
        override fun log(message: String) { logs += message }

        override fun write(frame: OutgoingFrame): Boolean {
            if (frame == OutgoingFrame.StreamCancel) {
                owed.removeFirstOrNull()?.cancelled = true // the Loop drops the stream in progress
                at(ACK_MS) { queue.onWriteComplete(true) }
                return true
            }
            val exchange = exchanges[answers.size]
            assertEquals("requests must go out in the order they were queued", exchange.frame.toString(), frame.toString())
            answers.lastOrNull()?.let { previous ->
                if (!previous.cancelled && !(previous.acked && previous.complete)) {
                    violations += "${exchange.source} written while ${previous.exchange.source}'s answer was still open"
                }
            }
            val answer = Answer(exchange)
            answers += answer
            owed.addLast(answer)
            at(ACK_MS) {
                answer.acked = true
                queue.onWriteComplete(true)
                if (owed.firstOrNull() === answer) at(FIRST_MS) { send(answer) }
            }
            return true
        }

        private fun send(answer: Answer) {
            if (answer.cancelled) return
            queue.onPacket(answer.exchange.packets[answer.sent++])
            if (!answer.complete) {
                at(GAP_MS) { send(answer) }
                return
            }
            owed.removeFirst()
            if (answer.exchange.source == strayAfter) at(STRAY_MS) { queue.onPacket(fixturePacket("00:c800")) }
            owed.firstOrNull()?.takeIf { it.acked }?.let { next -> at(FIRST_MS) { send(next) } }
        }

        fun replay(): Loop {
            for (e in exchanges) queue.request(e.frame) { results.getOrPut(e.source) { mutableListOf() } += it }
            drain()
            while (scheduled.isNotEmpty()) {
                val next = scheduled.minWith(compareBy({ it.at }, { it.order }))
                scheduled.remove(next)
                now = next.at
                next.r.run()
                drain()
            }
            return this
        }

        private fun drain() { while (ready.isNotEmpty()) ready.removeFirst().run() }
    }

    private fun assertReplayHolds(loop: Loop) {
        // 1. One in flight: nothing written before the previous request's final packet and its write ack.
        assertEquals(emptyList<String>(), loop.violations)
        // 2. No cut-offs: TEMPCONT's 5-frame answer and the three streams cut on the wire all reach LAST.
        for (source in listOf("420", "433", "452", "465")) {
            val answer = loop.answers.single { it.exchange.source == source }
            assertTrue(source, answer.complete && !answer.cancelled)
            assertEquals(source, 1, (answer.exchange.packets.last()[0].toInt() shr 1) and 3) // LAST
        }
        // 3. Exactly one result per request, the fixture's outcome for that request.
        assertEquals(exchanges.map { it.source }, loop.results.keys.toList())
        for (e in exchanges) assertEquals(e.source, listOf(e.expected), loop.results.getValue(e.source))
    }

    @Test
    fun `the fixture is the burst -- 29 GETs and the disk-space query, three multi-frame answers completed`() {
        assertEquals(30, exchanges.size)
        assertEquals(4, exchanges.count { it.packets.size > 1 })
        assertEquals(listOf(103, 103, 100, 103, 103, 100, 100, 200) + List(8) { 202 },
            exchanges.mapNotNull { (it.expected as? PftpResult.DeviceError)?.code })
    }

    @Test
    fun `replayed against the queue, every request is alone in flight and gets its own answer`() {
        assertReplayHolds(Loop(exchanges).replay())
    }

    /**
     * Variant A: removeCallbacks never removes anything, so every timer the queue ever armed runs,
     * [TIMEOUT_MS] after it was armed: while later requests are in flight, and mid-stream in the
     * multi-frame answers. A timer acts only for its own request while it is current and still that
     * request's live timer, so none of them times anything out, and the replay holds as with a
     * working port. (v1.3.0 shared one timer across requests and failed this.)
     */
    @Test
    fun `with a leaky port, no stale timer releases or cancels a later request`() {
        val loop = Loop(exchanges, leaky = true).replay()
        assertReplayHolds(loop)
        assertTrue(loop.logs.none { "cancelling stream" in it })
    }

    /**
     * Variant B documents a limit, not a guarantee: a device that sends more answers than requests.
     * The queue releases 436 on its final packet and writes 446 at once, so the stray 200 that
     * follows lands while 446 is in flight and is taken as its answer -- PFTP answers carry no request
     * id. With nothing in flight it would be dropped (GattQueueTest pins that). The queue then writes
     * 447 while the Loop still owes 446's real answer, and every later answer lands one request late.
     * One result per request still holds; the last answer finds nothing pending and is dropped.
     */
    @Test
    fun `a stray status-0 frame after a final packet is taken as the next request's answer`() {
        val loop = Loop(exchanges, strayAfter = "436").replay()
        val after = exchanges.dropWhile { it.source != "446" }
        assertEquals(listOf<PftpResult>(PftpResult.DeviceError(200)), loop.results.getValue("446"))
        assertEquals("447 written while 446's answer was still open", loop.violations.first())
        assertEquals(after.size - 1, loop.violations.size)
        for ((late, owner) in after.drop(1).zip(after)) {
            assertEquals(late.source, listOf(owner.expected), loop.results.getValue(late.source))
        }
        assertEquals(exchanges.map { it.source }, loop.results.keys.toList())
        assertTrue(loop.results.values.all { it.size == 1 })
        assertEquals(1, loop.logs.count { "no response pending, dropped" in it })
    }

    private companion object {
        const val ACK_MS = 5L
        const val FIRST_MS = 50L
        const val GAP_MS = 450L
        const val STRAY_MS = 10L
        const val TIMEOUT_MS = 1_000L
    }
}
