package com.polarloopdebugger.protocol

import java.io.ByteArrayOutputStream

/** The one result every PFTP request (GET, query, or session-control notification) ends with. */
sealed interface PftpResult {
    /** A non-empty response body, RFC76 headers already stripped. */
    class Content(val bytes: ByteArray) : PftpResult {
        override fun equals(other: Any?) = other is Content && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
        // Size only -- never a hex dump, so logging a result can't block a thread on a 300 KB file.
        override fun toString() = "Content(${bytes.size} bytes)"
    }

    /** Success with no body: a status-0 frame with error code 0, or a LAST packet carrying nothing. */
    data object Empty : PftpResult

    /** The device answered with a nonzero PbPFtpError; [name] is null for codes pftp_error.proto lacks. */
    data class DeviceError(val code: Int, val name: String? = PftpError.nameOf(code)) : PftpResult

    /** No packet arrived within the per-packet timeout. */
    data object Timeout : PftpResult

    /** The host ended the request: framing error (stream cancel sent), disconnect, dispose, or a write that couldn't go out. */
    data class Cancelled(val reason: String) : PftpResult
}

/** PbPFtpError, pftp_error.proto (polar-ble-sdk). */
enum class PftpError(val code: Int) {
    OPERATION_SUCCEEDED(0),
    REBOOTING(1),
    TRY_AGAIN(2),
    UNIDENTIFIED_HOST_ERROR(100),
    INVALID_COMMAND(101),
    INVALID_PARAMETER(102),
    NO_SUCH_FILE_OR_DIRECTORY(103),
    DIRECTORY_EXISTS(104),
    FILE_EXISTS(105),
    OPERATION_NOT_PERMITTED(106),
    NO_SUCH_USER(107),
    TIMEOUT(108),
    UNIDENTIFIED_DEVICE_ERROR(200),
    NOT_IMPLEMENTED(201),
    SYSTEM_BUSY(202),
    INVALID_CONTENT(203),
    CHECKSUM_FAILURE(204),
    DISK_FULL(205),
    PREREQUISITE_NOT_MET(206),
    INSUFFICIENT_BUFFER(207),
    WAIT_FOR_IDLING(208),
    BATTERY_TOO_LOW(209);

    companion object {
        fun nameOf(code: Int): String? = entries.find { it.code == code }?.name
    }
}

/**
 * One incoming PFTP frame's RFC76 header, for tracing -- never its payload. [characteristic] is
 * fb005c51 or fb005c52, [status] 0..3 (1 LAST, 3 MORE, 0 error/response), [next] 0 on a message's
 * first frame, [length] the whole frame in bytes, header included, and [errorCode] a status-0
 * frame's little-endian u16 (null on other frames, or one too short to carry it).
 */
data class PftpFrameTrace(
    val characteristic: String,
    val status: Int,
    val sequence: Int,
    val next: Int,
    val length: Int,
    val errorCode: Int?,
) {
    companion object {
        /** null for a zero-length frame: it has no header, and every reader drops it. */
        fun of(characteristic: String, frame: ByteArray): PftpFrameTrace? {
            if (frame.isEmpty()) return null
            val header = frame[0].toInt() and 0xFF
            val status = (header shr 1) and 0x03
            val code = if (status == 0 && frame.size >= 3) littleEndianUInt16(frame, 1) else null
            return PftpFrameTrace(characteristic, status, (header shr 4) and 0x0F, header and 0x01, frame.size, code)
        }
    }
}

/**
 * Reassembles one PFTP response from fb005c51 notification packets by reading every packet's
 * 1-byte RFC76 header, exactly as Polar's SDK does (BlePsFtpUtils.processRfc76MessageFrameHeader,
 * BlePsFtpUtils.kt:166-183; BlePsFtpClient.readResponse):
 *
 * - bit 0 = next (0 on the first packet, 1 on every later one), bits 1-2 = status, bits 4-7 =
 *   sequence number (0..15, wrapping, starting at 0);
 * - status 3 (MORE) appends the payload and waits for more; status 1 (LAST) appends and ends;
 * - status 0 carries a little-endian u16 error code: nonzero is a device error, zero is success;
 * - a sequence or next-bit mismatch (or the undefined status 2) means the stream is broken -- the
 *   caller must write [buildStreamCancel] and report it.
 *
 * Completion is decided by the headers alone, never by a quiet gap. One instance per request.
 */
class PftpResponseReader {
    sealed interface Step {
        data object NeedMore : Step
        data class Done(val result: PftpResult) : Step
        /** Framing broken: send the stream cancel and report [reason]; nothing more to read. */
        data class OutOfSync(val reason: String) : Step
    }

    private var expectedSequence = 0
    private var expectedNext = 0
    private val payload = ByteArrayOutputStream()

    fun accept(packet: ByteArray): Step {
        // BlePsFtpClient.processServiceData drops zero-length packets without touching the stream.
        if (packet.isEmpty()) return Step.NeedMore
        val header = packet[0].toInt() and 0xFF
        val next = header and 0x01
        val status = (header shr 1) and 0x03
        val sequence = (header shr 4) and 0x0F
        if (sequence != expectedSequence) return Step.OutOfSync("sequence $sequence, expected $expectedSequence")
        if (next != expectedNext) return Step.OutOfSync("next bit $next, expected $expectedNext")
        expectedSequence = (expectedSequence + 1) and 0x0F
        expectedNext = 1
        return when (status) {
            STATUS_MORE -> {
                payload.write(packet, 1, packet.size - 1)
                Step.NeedMore
            }
            STATUS_LAST -> {
                payload.write(packet, 1, packet.size - 1)
                Step.Done(body())
            }
            STATUS_ERROR_OR_RESPONSE -> {
                if (packet.size < 3) return Step.OutOfSync("status-0 frame of ${packet.size} bytes has no error code")
                // The SDK masks the high byte away ((b2 shl 8) and 0xFF == 0); every PbPFtpError
                // code fits in the low byte, so reading the full u16 only differs for codes it lacks.
                val code = (packet[1].toInt() and 0xFF) or ((packet[2].toInt() and 0xFF) shl 8)
                // Code 0 ends the stream successfully with whatever arrived before it, as the SDK's
                // readResponse returns its output stream as-is -- empty in every captured case.
                Step.Done(if (code == 0) body() else PftpResult.DeviceError(code))
            }
            else -> Step.OutOfSync("undefined RFC76 status $status")
        }
    }

    private fun body(): PftpResult =
        if (payload.size() == 0) PftpResult.Empty else PftpResult.Content(payload.toByteArray())

    private companion object {
        const val STATUS_ERROR_OR_RESPONSE = 0
        const val STATUS_LAST = 1
        const val STATUS_MORE = 3
    }
}
