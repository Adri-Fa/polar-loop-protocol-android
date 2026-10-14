package com.polarloopdebugger.ble

import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PFTP_SESSION_CONTROL_UUID
import com.polarloopdebugger.protocol.PftpResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/**
 * Proves the one-in-flight guarantee under real concurrency: 8 producer threads submit GETs,
 * queries and fb005c53 notifications at once, while a fake device on its own "radio" thread
 * streams multi-packet responses with random gaps, acks writes before or after its packets, breaks
 * the sequence (every 50th request) and cuts streams off mid-file (every 400th, capture12-style).
 *
 * The device flags any write that arrives while a response is still streaming to it, while it
 * waits for a stream cancel, or while the previous write is unacked -- and the test then requires
 * zero such writes, exactly one result per request, and each result matching what the device sent.
 */
class GattQueueStressTest {

    private class Device(private val radio: java.util.concurrent.ExecutorService) {
        lateinit var queue: GattQueue
        val violations = ConcurrentLinkedQueue<String>()
        val expected = ConcurrentHashMap<String, PftpResult>() // GET path -> what was sent
        val queryOutcomes = ConcurrentLinkedQueue<PftpResult>()
        val requestWrites = AtomicInteger()
        val cancels = AtomicInteger()
        var expectedCancels = 0 // callback thread only

        @Volatile private var ackPending = false
        @Volatile private var streaming = false
        @Volatile private var awaitingCancel = false
        private val random = Random(52) // callback thread only

        /** Port.write: runs on the callback thread, inside the queue. */
        fun onWrite(frame: OutgoingFrame): Boolean {
            if (ackPending) violations += "$frame written before the previous write was acked"
            val events = mutableListOf<() -> Unit>()
            when {
                frame == OutgoingFrame.StreamCancel -> {
                    if (!awaitingCancel) violations += "stream cancel with nothing to cancel"
                    cancels.incrementAndGet()
                    awaitingCancel = false
                }
                streaming || awaitingCancel -> {
                    val where = if (frame.characteristic == PFTP_SESSION_CONTROL_UUID) "fb005c53" else "fb005c51"
                    violations += "$frame written to $where while a response was still streaming"
                }
                frame.characteristic == PFTP_SESSION_CONTROL_UUID -> Unit
                else -> {
                    streaming = true
                    events += respond(frame, requestWrites.incrementAndGet())
                }
            }
            ackPending = true
            events.add(random.nextInt(events.size + 1)) { ackPending = false; queue.onWriteComplete(true) }
            radio.execute { events.forEach { it() } }
            return true
        }

        private fun respond(frame: OutgoingFrame, n: Int): List<() -> Unit> {
            val packets = mutableListOf<ByteArray>()
            val outcome: PftpResult?
            var stop = { streaming = false } // run right before the last packet is handed over
            fun header(i: Int, status: Int) = (((i and 0x0F) shl 4) or (status shl 1) or (if (i == 0) 0 else 1)).toByte()
            when {
                n % 400 == 0 -> { // cut off mid-file: MOREs, then silence
                    repeat(3) { packets += byteArrayOf(header(it, 3), 1) }
                    outcome = PftpResult.Timeout
                    stop = { streaming = false; awaitingCancel = true }
                    expectedCancels++
                }
                n % 50 == 0 -> { // sequence break: device stops and waits for 00 00 00
                    packets += byteArrayOf(header(0, 3), 1)
                    packets += byteArrayOf(header(5, 3), 2)
                    outcome = null // Cancelled, reason checked by type
                    stop = { streaming = false; awaitingCancel = true }
                    expectedCancels++
                }
                else -> when (random.nextInt(5)) {
                    0 -> { packets += byteArrayOf(0x00, 0x00, 0x00); outcome = PftpResult.Empty }
                    1 -> { packets += byteArrayOf(0x00, 0xCA.toByte(), 0x00); outcome = PftpResult.DeviceError(202) }
                    else -> {
                        val body = mutableListOf<Byte>()
                        val more = random.nextInt(0, 12)
                        for (i in 0..more) {
                            val chunk = random.nextBytes(random.nextInt(0, 40))
                            body += chunk.toList()
                            packets += byteArrayOf(header(i, if (i == more) 1 else 3)) + chunk
                        }
                        outcome = if (body.isEmpty()) PftpResult.Empty else PftpResult.Content(body.toByteArray())
                    }
                }
            }
            when (frame) {
                is OutgoingFrame.PftpGet -> expected[frame.path] = outcome ?: PftpResult.Cancelled("")
                else -> queryOutcomes += outcome ?: PftpResult.Cancelled("")
            }
            val gaps = Random(n)
            return packets.mapIndexed { i, p ->
                {
                    when (gaps.nextInt(100)) {
                        0 -> Thread.sleep(20) // a stall far longer than radio jitter, well under the timeout
                        in 1..5 -> LockSupport.parkNanos(gaps.nextLong(100_000, 2_000_000))
                        else -> Thread.yield()
                    }
                    if (i == packets.lastIndex) stop()
                    queue.onPacket(p)
                }
            }
        }
    }

    /** GattQueue.Port over a single-thread scheduler standing in for the GATT callback Handler. */
    private class ExecutorPort(private val exec: ScheduledExecutorService, private val device: Device) : GattQueue.Port {
        private val scheduled = HashMap<Runnable, MutableList<ScheduledFuture<*>>>() // callback thread only
        override fun post(r: Runnable) = exec.execute(r)
        override fun postDelayed(r: Runnable, delayMs: Long) {
            scheduled.getOrPut(r) { mutableListOf() } += exec.schedule(r, delayMs, TimeUnit.MILLISECONDS)
        }
        override fun removeCallbacks(r: Runnable) {
            scheduled.remove(r)?.forEach { it.cancel(false) }
        }
        override fun write(frame: OutgoingFrame) = device.onWrite(frame)
        override fun read(characteristic: String) = false
        override fun maxWriteSize() = 495
        override fun log(message: String) {}
    }

    private sealed class Kind { object Get : Kind(); object Query : Kind(); object Notify : Kind() }

    @Test(timeout = 120_000)
    fun `concurrent GETs, queries and notifications never overlap a streaming response`() {
        val callbackThread = Executors.newSingleThreadScheduledExecutor()
        val radio = Executors.newSingleThreadExecutor()
        val device = Device(radio)
        val queue = GattQueue(ExecutorPort(callbackThread, device), packetTimeoutMs = 750)
        device.queue = queue

        val producers = 8
        val perProducer = 200
        val total = producers * perProducer
        val counts = Array(total) { AtomicInteger() }
        val results = arrayOfNulls<PftpResult>(total)
        val kinds = arrayOfNulls<Kind>(total)
        val paths = arrayOfNulls<String>(total)
        val allAnswered = CountDownLatch(total)
        val start = CyclicBarrier(producers)

        val threads = (0 until producers).map { p ->
            Thread {
                val random = Random(p)
                start.await()
                for (i in 0 until perProducer) {
                    val id = p * perProducer + i
                    val onResult = { r: PftpResult ->
                        results[id] = r
                        if (counts[id].incrementAndGet() == 1) allAnswered.countDown()
                    }
                    when (random.nextInt(10)) {
                        in 0..5 -> {
                            kinds[id] = Kind.Get
                            paths[id] = "/T/$p/$i"
                            queue.request(OutgoingFrame.PftpGet(paths[id]!!), onResult)
                        }
                        6 -> { kinds[id] = Kind.Query; queue.request(OutgoingFrame.DiskSpaceQuery, onResult) }
                        7 -> { kinds[id] = Kind.Query; queue.request(OutgoingFrame.ExerciseStatusQuery, onResult) }
                        else -> {
                            kinds[id] = Kind.Notify
                            val frame = listOf(
                                OutgoingFrame.InitializeSession, OutgoingFrame.StartSync,
                                OutgoingFrame.StopSyncCompleted, OutgoingFrame.TerminateSession,
                            )[random.nextInt(4)]
                            queue.write(frame) { ok -> onResult(if (ok) PftpResult.Empty else PftpResult.Cancelled("write failed")) }
                        }
                    }
                }
            }.apply { start() }
        }
        threads.forEach { it.join() }
        assertTrue("not every request was answered", allAnswered.await(100, TimeUnit.SECONDS))
        // Let anything that would be a second result, or a stray write, land before checking.
        Thread.sleep(200)
        callbackThread.submit {}.get()
        radio.submit {}.get()
        callbackThread.shutdownNow()
        radio.shutdownNow()

        assertEquals(emptyList<String>(), device.violations.toList())
        assertEquals("results per request", List(total) { 1 }, counts.map { it.get() })

        for (id in 0 until total) when (kinds[id]) {
            Kind.Get -> {
                val sent = device.expected.getValue(paths[id]!!)
                if (sent is PftpResult.Cancelled) assertTrue(results[id] is PftpResult.Cancelled)
                else assertEquals(paths[id], sent, results[id])
            }
            Kind.Notify -> assertEquals(PftpResult.Empty, results[id])
            else -> Unit
        }
        val queryResults = (0 until total).filter { kinds[it] == Kind.Query }.map { results[it]!! }
        fun shape(r: PftpResult) = if (r is PftpResult.Cancelled) "Cancelled" else r.toString()
        assertEquals(device.queryOutcomes.map(::shape).sorted(), queryResults.map(::shape).sorted())

        val requests = kinds.count { it != Kind.Notify }
        assertEquals(requests, device.requestWrites.get())
        assertEquals(requests / 50, device.expectedCancels) // 400 is a multiple of 50: one cancel each
        assertEquals(device.expectedCancels, device.cancels.get())
        assertTrue("stress run too small to mean anything", requests > 1_000)
    }
}
