package com.polarloopdebugger.protocol

/**
 * PFTP command builders (writes to fb005c51 and fb005c53).
 *
 * HARD CONSTRAINT, enforced here at the code level, not just by convention: this file must never
 * gain a function capable of constructing a `PUT` (`08 01`) or `REMOVE` (`08 03`) PbPFtpOperation.
 * [buildGetRequest] is the only function that builds a request frame, and it hardcodes the
 * command byte to [PFTP_COMMAND_GET] -- there is no parameter, enum, or branch anywhere in this
 * file that could select a different command. The library only ever sends these bytes wrapped in
 * an [OutgoingFrame]; OutgoingFrameAllowListTest pins every byte string that type can produce.
 *
 * [buildDiskSpaceQuery] and [buildExerciseStatusQuery] build PbPFtpQuery frames (a structurally
 * different, id-based wire mechanism, see PftpConstants) rather than PbPFtpOperations. Each takes
 * no parameters and returns one fixed byte array, so neither can be repurposed to send any other
 * PbPFtpQuery id.
 *
 * Real deletion/write support (REMOVE/PUT/MERGE) is intentionally out of scope for this phase --
 * a deliberate human decision, not a placeholder. See README.md.
 */

/**
 * `02 [len:u16 LE] 08 00 12 [pathlen:varint] [UTF-8 path]` -- confirmed framing, PROTOCOL.md
 * "Request/response wire framing (fb005c51, handle 0x0031)". Pinned against three real captured
 * frames in PftpCommandsTest (342, 459, 465).
 */
fun buildGetRequest(path: String): ByteArray {
    val pathBytes = path.toByteArray(Charsets.UTF_8)
    // Serialized PbPFtpOperation{command=GET, path=path}: field 1 tag+value (08 00), field 2
    // tag+length-delimited string (12 <varint len> <path>). The command byte is the literal constant
    // PFTP_COMMAND_GET -- there is no way to pass a different value in.
    val message = byteArrayOf(0x08, PFTP_COMMAND_GET.toByte(), 0x12) + encodeVarint(pathBytes.size) + pathBytes
    // RFC60 request header (BlePsFtpUtils.makeCompleteMessageStream): 15-bit LE length, top bit
    // clear -- a set top bit would mark the frame as a query instead.
    val len = message.size
    require(len <= 0x7FFF) { "PbPFtpOperation of $len bytes exceeds RFC60's 15-bit length field" }
    val header = byteArrayOf(0x02, (len and 0xFF).toByte(), (len shr 8).toByte())
    return header + message
}

private fun encodeVarint(value: Int): ByteArray {
    val out = ArrayList<Byte>(2)
    var v = value
    while (v >= 0x80) {
        out.add(((v and 0x7F) or 0x80).toByte())
        v = v ushr 7
    }
    out.add(v.toByte())
    return out.toByteArray()
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
 * and this file's existing RFC76 `0x02` air-packet header convention). See PftpDiskSpaceDecoder for
 * the response side, capture-validated in capture19.
 */
fun buildDiskSpaceQuery(): ByteArray = DISK_SPACE_QUERY_BYTES.copyOf()

/**
 * `02 19 80` -- PbPFtpQuery.GET_EXERCISE_STATUS (id=25), no params, same framing as
 * [buildDiskSpaceQuery]. CAPTURE-VALIDATED: byte-identical to the request Polar Flow itself sends
 * the Loop (capture9 frame 345), which answered `02 08 03` -- exercise_state OFF.
 */
fun buildExerciseStatusQuery(): ByteArray = EXERCISE_STATUS_QUERY_BYTES.copyOf()

/**
 * `00 00 00` -- the RFC76 stream cancel BlePsFtpClient.readResponse writes on a sequence-number
 * mismatch: header 0x00 (first packet, status 0, sequence 0) plus a zero error code. It aborts the
 * response the device is streaming; it carries no command and touches no stored data.
 */
fun buildStreamCancel(): ByteArray = STREAM_CANCEL_BYTES.copyOf()
