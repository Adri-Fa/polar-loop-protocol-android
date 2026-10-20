package com.polarloopdebugger.protocol

/**
 * PFTP command builders (writes to fb005c51 and fb005c53).
 *
 * HARD CONSTRAINT, enforced here at the code level, not just by convention: this file must never
 * gain a function capable of constructing a `PUT` (`08 01`) or `MERGE` (`08 02`) PbPFtpOperation,
 * nor a `REMOVE` (`08 03`) of an arbitrary path. Two functions build operation frames:
 * [buildGetRequest], which hardcodes [PFTP_COMMAND_GET], and [buildRemoveRequest], which
 * hardcodes [PFTP_COMMAND_REMOVE] and accepts only a [PftpRemoveTarget] -- a type that can hold
 * exactly two path shapes (a day folder, a 24/7 file) and nothing else. There is no parameter,
 * enum, or branch anywhere in this file that could select any other command or widen those
 * shapes. The library only ever sends these bytes wrapped in an [OutgoingFrame];
 * OutgoingFrameAllowListTest pins every byte string that type can produce.
 *
 * [buildDiskSpaceQuery], [buildExerciseStatusQuery] and [buildRequestSynchronization] build
 * PbPFtpQuery frames (a structurally different, id-based wire mechanism, see PftpConstants) rather
 * than PbPFtpOperations. Each takes no parameters and returns one fixed byte array, so none can be
 * repurposed to send any other PbPFtpQuery id.
 *
 * PUT and MERGE stay impossible to build -- a deliberate human decision, not a placeholder. REMOVE
 * (v1.4.0) is sent only when the app asks for it; a source tripwire in OutgoingFrameAllowListTest
 * fails if any code path inside the library calls it. See README.md.
 */

/**
 * `02 [len:u16 LE] 08 00 12 [pathlen:varint] [UTF-8 path]` -- confirmed framing, PROTOCOL.md
 * "Request/response wire framing (fb005c51, handle 0x0031)". Pinned against three real captured
 * frames in PftpCommandsTest (342, 459, 465).
 */
fun buildGetRequest(path: String): ByteArray = buildOperation(PFTP_COMMAND_GET, path)

/**
 * `02 [len:u16 LE] 08 03 12 [pathlen:varint] [UTF-8 path]` -- the same framing as a GET with
 * command 3. CAPTURE-VALIDATED against Polar Flow's own REMOVEs in capture9: a day folder
 * (frame 5680, header `02 12 00 08 03 12 0e`) and a 24/7 file (frame 5091, header
 * `02 1b 00 08 03 12 17`), each answered by the Loop with `00 00 00` (status-0 frame, error code
 * OPERATION_SUCCEEDED, i.e. PftpResult.Empty). The path can only come from a [PftpRemoveTarget].
 */
fun buildRemoveRequest(target: PftpRemoveTarget): ByteArray = buildOperation(PFTP_COMMAND_REMOVE, target.path)

private fun buildOperation(command: Int, path: String): ByteArray {
    val pathBytes = path.toByteArray(Charsets.UTF_8)
    // Serialized PbPFtpOperation{command, path}: field 1 tag+value (08 cc), field 2 tag+length-
    // delimited string (12 <varint len> <path>). `command` is one of the two literal constants
    // above; this function is private, so nothing else can pass a value in.
    val message = byteArrayOf(0x08, command.toByte(), 0x12) + encodeVarint(pathBytes.size) + pathBytes
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
 * `02 0D 80` -- PbPFtpQuery.REQUEST_SYNCHRONIZATION (id=13), no params. CAPTURE-VALIDATED:
 * byte-identical to the request Polar Flow sends the Loop before its own sync (capture9 frame 424),
 * which the Loop answered with a payload-less LAST packet. Polar documents the call that sends it
 * as making the device "flush the latest data to files", so the library never sends it on its own:
 * only PolarGattClient.requestSynchronization, called by the app, does.
 */
fun buildRequestSynchronization(): ByteArray = REQUEST_SYNCHRONIZATION_BYTES.copyOf()

/**
 * `00 00 00` -- the RFC76 stream cancel BlePsFtpClient.readResponse writes on a sequence-number
 * mismatch: header 0x00 (first packet, status 0, sequence 0) plus a zero error code. It aborts the
 * response the device is streaming; it carries no command and touches no stored data.
 */
fun buildStreamCancel(): ByteArray = STREAM_CANCEL_BYTES.copyOf()
