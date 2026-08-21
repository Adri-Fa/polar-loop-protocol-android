package com.polarloopdebugger.protocol

/**
 * PFTP command builders (writes to fb005c51 and fb005c53).
 *
 * HARD CONSTRAINT, enforced here at the code level, not just by convention: this file must never
 * gain a function capable of constructing a `PUT` (`08 01`) or `REMOVE` (`08 03`) PbPFtpOperation.
 * [buildGetRequest] is the only function that builds a request frame, and it hardcodes the
 * command byte to [PFTP_COMMAND_GET] -- there is no parameter, enum, or branch anywhere in this
 * file that could select a different command. See PftpCommandsTest's reflection-based regression
 * test, which fails the build if a new "build*" function is ever added without being reviewed
 * against this guarantee.
 *
 * [buildDiskSpaceQuery] is the one exception to "request frame" above -- it builds a PbPFtpQuery
 * frame (a structurally different, id-based wire mechanism, see PftpConstants.DISK_SPACE_QUERY_BYTES),
 * not a PbPFtpOperation. It takes no parameters and returns a single fixed byte array, so it can
 * never be repurposed to send any other PbPFtpQuery id.
 *
 * Real deletion/write support (REMOVE/PUT/MERGE) is intentionally out of scope for this phase --
 * a deliberate human decision, not a placeholder. See README.md.
 */

/**
 * `02 [len:u16 LE] 08 00 12 [pathlen:u8] [UTF-8 path]` -- confirmed framing, PROTOCOL.md
 * "Request/response wire framing (fb005c51, handle 0x0031)". Pinned against three real captured
 * frames in PftpCommandsTest (342, 459, 465).
 */
fun buildGetRequest(path: String): ByteArray {
    val pathBytes = path.toByteArray(Charsets.UTF_8)
    require(pathBytes.size <= 0xFF) { "path too long for one-byte length prefix: $path" }
    // Serialized PbPFtpOperation{command=GET, path=path}: field 1 tag+value (08 00), field 2
    // tag+length-delimited string (12 <len> <path>). The command byte is the literal constant
    // PFTP_COMMAND_GET -- there is no way to pass a different value in.
    val message = byteArrayOf(0x08, PFTP_COMMAND_GET.toByte(), 0x12, pathBytes.size.toByte()) + pathBytes
    val len = message.size
    val header = byteArrayOf(0x02, (len and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte())
    return header + message
}

/** `0208` -- PROTOCOL.md session-control cadence table, frame 436. */
fun buildInitializeSession(): ByteArray = SESSION_INITIALIZE_BYTES.copyOf()

/** `0200` -- PROTOCOL.md session-control cadence table, frame 437. */
fun buildStartSync(): ByteArray = SESSION_START_SYNC_BYTES.copyOf()

/**
 * `02010801` -- STOP_SYNC + PbPFtpStopSyncParams{completed=true}, PROTOCOL.md frame 5834. There is
 * deliberately no `completed=false` variant: this app only ever finishes a configured pull
 * cleanly, never aborts mid-sync.
 */
fun buildStopSyncCompleted(): ByteArray = SESSION_STOP_SYNC_COMPLETED_BYTES.copyOf()

/** `0209` -- PROTOCOL.md session-control cadence table, frame 5835. */
fun buildTerminateSession(): ByteArray = SESSION_TERMINATE_BYTES.copyOf()

/**
 * `02 05 80` -- PbPFtpQuery.GET_DISK_SPACE (id=5), no params. Traced directly from the real SDK
 * source (pftp_request.proto's `GET_DISK_SPACE = 5`, BlePsFtpUtils.kt's MessageType.QUERY framing,
 * and this file's existing RFC76 `0x02` air-packet header convention) -- NOT captured on a real
 * device. See PftpDiskSpaceDecoder for the response side, which is schema-derived only and still
 * needs a real-device capture to validate.
 */
fun buildDiskSpaceQuery(): ByteArray = DISK_SPACE_QUERY_BYTES.copyOf()
