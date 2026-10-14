package com.polarloopdebugger.protocol

/**
 * PFTP (Polar File Transfer Protocol) wire constants for the Historical Sync feature.
 *
 * Ported from PROTOCOL.md's "Historical/Offline Sync Protocol (fb005c51/52/53)" section
 * (capture9/capture10, confirmed against the official polarofficial/polar-ble-sdk .proto
 * schema: pftp_request.proto, pftp_response.proto, pftp_notification.proto).
 *
 * READ-ONLY GUARANTEE: this file and [PftpCommands] together are the only code in this app that
 * can construct a PbPFtpOperation wire frame. There is no `Command` enum here with PUT/MERGE/
 * REMOVE values -- only the literal GET command byte (0x00) is ever written, hardcoded directly
 * into buildGetRequest. OutgoingFrameAllowListTest pins every byte string the library can emit.
 *
 * This also covers the two PbPFtpQuery ids this app can send (GET_DISK_SPACE=5 and
 * GET_EXERCISE_STATUS=25): a Query is a structurally different wire mechanism than an Operation (a
 * numeric id, not a path-carrying command), but the same discipline applies -- there is no id
 * parameter or enum anywhere in this app that could select any other PbPFtpQuery value
 * (SET_SYSTEM_TIME, PREPARE_FIRMWARE_UPDATE, START/STOP_EXERCISE, etc.), only fixed byte arrays.
 *
 * REQUEST_SYNCHRONIZATION (13) is deliberately absent: Polar's only documented effect of the call
 * that sends it is that it "will cause the device to flush the latest data to files"
 * (polar-ble-sdk PolarBleApi.sendInitializationAndStartSyncNotifications), i.e. it changes stored
 * data.
 */

// The "Unknown Service" (0xFEEE, Polar Electro Oy SIG UUID) grouping fb005c51/52/53 -- see
// PROTOCOL.md Open Question 2 for why this grouping itself is unconfirmed (no fresh GATT
// discovery was ever captured). Matched here by property-signature only, same as PolarGattClient.
const val PFTP_SERVICE_UUID = "0000feee-0000-1000-8000-00805f9b34fb"

// Request/response channel: client writes a path, device notifies back the response.
const val PFTP_REQUEST_RESPONSE_UUID = "fb005c51-02e7-f387-1cad-8acd2d8df0c8"

// Unsolicited device->app event push (e.g. sleep_recording_state). Not used for GET traffic;
// logged if seen, per PROTOCOL.md's "surface every request/response in a visible log" ask.
const val PFTP_EVENT_UUID = "fb005c52-02e7-f387-1cad-8acd2d8df0c8"

// Session-control channel: short (2-4 byte) writes only, never carries path/JSON data.
const val PFTP_SESSION_CONTROL_UUID = "fb005c53-02e7-f387-1cad-8acd2d8df0c8"

// PbPFtpHostToDevNotification enum values actually used by this app (pftp_notification.proto).
// RESET(2) is never sent -- not needed for a read-only pull.
private const val NOTIFICATION_START_SYNC = 0x00
private const val NOTIFICATION_STOP_SYNC = 0x01
private const val NOTIFICATION_INITIALIZE_SESSION = 0x08
private const val NOTIFICATION_TERMINATE_SESSION = 0x09

// The ONLY PbPFtpOperation.Command value this app is able to express. Deliberately not an enum
// with GET/PUT/MERGE/REMOVE entries -- see PftpCommands.buildGetRequest.
internal const val PFTP_COMMAND_GET = 0x00

// The ONLY PbPFtpQuery id value this app is able to express (pftp_request.proto's
// PbPFtpQuery.GET_DISK_SPACE = 5). MessageType.QUERY framing (BlePsFtpUtils.kt in the real SDK):
// byte0 = id & 0xFF, byte1 = ((id shr 8) and 0x7F) or 0x80 -- the 0x80 marker bit is what tells
// the device this is a query, not a request. For id=5 that's byte0=0x05, byte1=0x80. The leading
// 0x02 is the RFC76 first/only-packet air header, same convention as every other frame in this
// file. No PbPFtpQuery params are sent (GET_DISK_SPACE takes none).
internal val DISK_SPACE_QUERY_BYTES = byteArrayOf(0x02, 0x05, 0x80.toByte())

// PbPFtpQuery.GET_EXERCISE_STATUS = 25 (0x19), same query framing, no params.
internal val EXERCISE_STATUS_QUERY_BYTES = byteArrayOf(0x02, 0x19, 0x80.toByte())

// RFC76 stream cancel (BlePsFtpClient.readResponse): status-0 header byte + zero error code.
internal val STREAM_CANCEL_BYTES = byteArrayOf(0x00, 0x00, 0x00)

// Label the disk-space query carries in logs/trace callbacks (Listener.onPftpRequestSent) and the
// key classifyPftpPath maps to DISK_SPACE. Guaranteed never to collide with a real device path --
// every real PFTP path starts with "/", this deliberately does not.
const val DISK_SPACE_PSEUDO_PATH = "\$DISK_SPACE"

internal val SESSION_INITIALIZE_BYTES = byteArrayOf(0x02, NOTIFICATION_INITIALIZE_SESSION.toByte())
internal val SESSION_START_SYNC_BYTES = byteArrayOf(0x02, NOTIFICATION_START_SYNC.toByte())
internal val SESSION_TERMINATE_BYTES = byteArrayOf(0x02, NOTIFICATION_TERMINATE_SESSION.toByte())

// PbPFtpStopSyncParams{required bool completed = 1}, serialized as field 1 varint `08 01` = true.
// completed=false is never constructed -- this app only ever finishes a pull cleanly.
internal val SESSION_STOP_SYNC_COMPLETED_BYTES =
    byteArrayOf(0x02, NOTIFICATION_STOP_SYNC.toByte(), 0x08, 0x01)

class PftpProtocolException(message: String) : Exception(message)
