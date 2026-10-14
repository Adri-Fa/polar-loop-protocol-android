package com.polarloopdebugger.ble

import com.polarloopdebugger.protocol.OutgoingFrame
import com.polarloopdebugger.protocol.PftpResponseReader
import com.polarloopdebugger.protocol.PftpResult

/**
 * The single serial queue every GATT operation goes through: CCCD setup, PMD commands, the battery
 * read, PFTP session-control notifications, and PFTP GETs/queries. Exactly one op is in flight at a
 * time. A PFTP request stays in flight until its response has ended by its RFC76 headers (LAST, or
 * a status-0 frame), timed out, or been cancelled -- AND its own write has been acked -- so nothing,
 * fb005c53 included, is ever written while a response is still streaming on fb005c51.
 *
 * Pure Kotlin (no android.*) so this guarantee is unit-tested directly (GattQueueStressTest);
 * PolarGattClient adapts [Port] to BluetoothGatt and a Handler.
 *
 * Threading: every public method only posts to [Port.post], and every posted body runs under this
 * object's lock -- normally all on one thread, but after PolarGattClient.dispose() recreates its
 * thread the old one may still be finishing cancelAll, and the lock keeps the two apart. Callbacks
 * are invoked on the posting thread.
 */
internal class GattQueue(private val port: Port, private val packetTimeoutMs: Long) {

    interface Port {
        fun post(r: Runnable)
        fun postDelayed(r: Runnable, delayMs: Long)
        fun removeCallbacks(r: Runnable)

        /** Starts one GATT write. false = rejected synchronously; no [onWriteComplete] will follow. */
        fun write(frame: OutgoingFrame): Boolean

        /** Starts one GATT characteristic read. false = rejected synchronously. */
        fun read(characteristic: String): Boolean

        /** Bytes one write may carry: negotiated ATT MTU - 3. */
        fun maxWriteSize(): Int

        fun log(message: String)
    }

    private sealed class Op
    private class Write(val frame: OutgoingFrame, val onDone: (Boolean) -> Unit) : Op()
    private class Read(val characteristic: String, val onDone: (ByteArray?) -> Unit) : Op()
    private class Run(val block: () -> Unit) : Op()
    private class Request(val frame: OutgoingFrame, val onResult: (PftpResult) -> Unit) : Op() {
        val reader = PftpResponseReader()
        var writeAcked = false
        var answered = false
        var cancelPending = false // stream broken: StreamCancel goes out once our own write is acked
        var cancelling = false // StreamCancel written, waiting for its ack
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private val timeout = Runnable { synchronized(this) { onTimeout() } }

    fun write(frame: OutgoingFrame, onDone: (Boolean) -> Unit) = submit(Write(frame, onDone))
    fun read(characteristic: String, onDone: (ByteArray?) -> Unit) = submit(Read(characteristic, onDone))
    fun run(block: () -> Unit) = submit(Run(block))
    fun request(frame: OutgoingFrame, onResult: (PftpResult) -> Unit) = submit(Request(frame, onResult))

    private fun submit(op: Op) = locked {
        queue.addLast(op)
        runNext()
    }

    private fun locked(block: () -> Unit) = port.post { synchronized(this) { block() } }

    // -- Completion events, forwarded by PolarGattClient from BluetoothGattCallback. --

    fun onWriteComplete(success: Boolean) = locked {
        when (val op = current) {
            is Write -> {
                current = null
                if (!success) port.log("ERROR: write of ${op.frame} failed")
                op.onDone(success)
            }
            is Request -> when {
                op.cancelling -> current = null
                else -> {
                    op.writeAcked = true
                    if (!success && !op.answered) {
                        port.removeCallbacks(timeout)
                        answer(op, PftpResult.Cancelled("write of ${op.frame} failed"))
                        current = null
                    } else if (op.cancelPending) {
                        sendCancel(op)
                    } else if (op.answered) {
                        current = null
                    }
                }
            }
            else -> port.log("write completion with no write in flight, ignored")
        }
        runNext()
    }

    fun onReadComplete(value: ByteArray?) = locked {
        val op = current
        if (op is Read) {
            current = null
            op.onDone(value)
        } else {
            port.log("read completion with no read in flight, ignored")
        }
        runNext()
    }

    /** One fb005c51 notification packet. */
    fun onPacket(packet: ByteArray) = locked {
        val op = current
        if (op !is Request || op.answered) {
            port.log("fb005c51 packet with no response pending, dropped (${packet.size} bytes)")
            return@locked
        }
        port.removeCallbacks(timeout)
        when (val step = op.reader.accept(packet)) {
            PftpResponseReader.Step.NeedMore -> port.postDelayed(timeout, packetTimeoutMs)
            is PftpResponseReader.Step.Done -> {
                answer(op, step.result)
                if (op.writeAcked) current = null
            }
            is PftpResponseReader.Step.OutOfSync -> {
                port.log("ERROR: PFTP response for ${op.frame} out of sync (${step.reason}), cancelling stream")
                answer(op, PftpResult.Cancelled("response out of sync: ${step.reason}; stream cancelled"))
                op.cancelPending = true
                if (op.writeAcked) sendCancel(op)
            }
        }
        runNext()
    }

    /** Disconnect or dispose: every in-flight and queued op gets its one result now. */
    fun cancelAll(reason: String) = locked {
        port.removeCallbacks(timeout)
        val ops = listOfNotNull(current) + queue
        current = null
        queue.clear()
        for (op in ops) when (op) {
            is Request -> if (!op.answered) answer(op, PftpResult.Cancelled(reason))
            is Write -> op.onDone(false)
            is Read -> op.onDone(null)
            is Run -> Unit
        }
    }

    private fun runNext() {
        while (current == null) {
            val op = queue.removeFirstOrNull() ?: return
            current = op
            when (op) {
                is Write -> send(op.frame)?.let { failure ->
                    port.log("ERROR: $failure")
                    current = null
                    op.onDone(false)
                }
                is Read -> if (!port.read(op.characteristic)) {
                    port.log("ERROR: read of ${op.characteristic} rejected")
                    current = null
                    op.onDone(null)
                }
                is Run -> {
                    current = null
                    op.block()
                }
                is Request -> {
                    val failure = send(op.frame)
                    if (failure != null) {
                        current = null
                        answer(op, PftpResult.Cancelled(failure))
                    } else {
                        port.postDelayed(timeout, packetTimeoutMs)
                    }
                }
            }
        }
    }

    /** null once the write is under way, else why it could not start. */
    private fun send(frame: OutgoingFrame): String? {
        val size = frame.bytes.size
        val max = port.maxWriteSize()
        if (size > max) return "$frame is $size bytes but the negotiated ATT MTU allows $max per write"
        return if (port.write(frame)) null else "write of $frame rejected (not connected?)"
    }

    private fun sendCancel(op: Request) {
        op.cancelPending = false
        val failure = send(OutgoingFrame.StreamCancel)
        if (failure == null) {
            op.cancelling = true
        } else {
            port.log("ERROR: $failure")
            current = null
        }
    }

    private fun onTimeout() {
        val op = current as? Request ?: return
        if (op.answered) return
        port.log("ERROR: no PFTP packet for ${op.frame} within ${packetTimeoutMs}ms, cancelling stream")
        answer(op, PftpResult.Timeout)
        // The device may only be stalled: cancel its stream (the SDK doesn't, but nothing else stops
        // a late response from streaming into the next request) before the queue moves on.
        if (op.writeAcked) {
            sendCancel(op)
        } else {
            // ponytail: our own write never acked means the link is already dead -- release now;
            // onConnectionStateChange's cancelAll follows within the supervision timeout. A late
            // ack would be credited to the next op.
            current = null
        }
        runNext()
    }

    private fun answer(op: Request, result: PftpResult) {
        op.answered = true
        op.onResult(result)
    }
}
