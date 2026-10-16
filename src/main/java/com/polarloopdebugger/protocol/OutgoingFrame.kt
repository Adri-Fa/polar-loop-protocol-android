package com.polarloopdebugger.protocol

/**
 * Every byte string this library can write to a device, as a closed set. PolarGattClient's only
 * write path takes an OutgoingFrame -- there is no raw-ByteArray write anywhere -- so the exhaustive
 * `when` in OutgoingFrameAllowListTest is a compile-time-checked list of everything the library can
 * emit: adding a subclass breaks that test's compilation until the new frame is reviewed against the
 * read-only guarantee.
 *
 * [characteristic] is the UUID written to, or for [EnableNotifications] the UUID whose CCCD
 * (0x2902) descriptor is written. [bytes] returns a fresh copy, so no caller can mutate a frame
 * (e.g. turn [StartSync]'s `02 00` into RESET's `02 02`) before it is sent.
 */
sealed class OutgoingFrame(val characteristic: String, private val encoded: ByteArray) {
    val bytes: ByteArray get() = encoded.copyOf()

    // -- fb005c51: PFTP requests. Each one is answered by a response on the same characteristic. --

    class PftpGet(val path: String) : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildGetRequest(path)) {
        override fun toString() = path
    }

    data object DiskSpaceQuery : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildDiskSpaceQuery()) {
        override fun toString() = DISK_SPACE_PSEUDO_PATH
    }

    data object ExerciseStatusQuery : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildExerciseStatusQuery())

    /** Opt-in only (PolarGattClient.requestSynchronization); the library never sends it itself. */
    data object RequestSynchronization : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildRequestSynchronization())

    /** Only ever written in reply to a broken response stream, see PftpResponseReader. */
    data object StreamCancel : OutgoingFrame(PFTP_REQUEST_RESPONSE_UUID, buildStreamCancel())

    // -- fb005c53: PFTP host-to-device notifications. No response; the write ack completes them. --

    data object InitializeSession : OutgoingFrame(PFTP_SESSION_CONTROL_UUID, buildInitializeSession())
    data object StartSync : OutgoingFrame(PFTP_SESSION_CONTROL_UUID, buildStartSync())
    data object StopSyncCompleted : OutgoingFrame(PFTP_SESSION_CONTROL_UUID, buildStopSyncCompleted())
    data object TerminateSession : OutgoingFrame(PFTP_SESSION_CONTROL_UUID, buildTerminateSession())

    // -- fb005c81: PMD control point, opcodes 1-3 only (OpCode has no other entries). --

    class PmdGetSettings(val type: MeasurementType) :
        OutgoingFrame(PMD_CONTROL_POINT_UUID, buildGetMeasurementSettings(type)) {
        override fun toString() = "GET_MEASUREMENT_SETTINGS ${type.name}"
    }

    class PmdStart(val type: MeasurementType, settingsTlv: ByteArray) :
        OutgoingFrame(PMD_CONTROL_POINT_UUID, buildRequestMeasurementStart(type, settingsTlv)) {
        override fun toString() = "REQUEST_MEASUREMENT_START ${type.name}"
    }

    class PmdStop(val type: MeasurementType) : OutgoingFrame(PMD_CONTROL_POINT_UUID, buildStopMeasurement(type)) {
        override fun toString() = "STOP_MEASUREMENT ${type.name}"
    }

    // -- CCCD (0x2902) descriptor writes, only on the characteristics this library listens to. --

    class EnableNotifications(val target: NotifyTarget) :
        OutgoingFrame(target.uuid, if (target.indicate) CCCD_ENABLE_INDICATION else CCCD_ENABLE_NOTIFICATION) {
        override fun toString() = "enable ${if (target.indicate) "indications" else "notifications"} on ${target.name}"

        /**
         * The line logged before this CCCD write: UUID, the characteristic's ATT handle (its
         * instanceId) and the value written. A wire trace shows the write on the CCCD's own handle,
         * usually [handle] + 1. Nothing but the target and the handle reaches it: no payload, no address.
         */
        fun logLine(handle: Int) = "CCCD ${target.uuid} handle=${"0x%04X".format(handle)} value=${bytes.toHex()}"
    }
}

/** The characteristics whose CCCD this library may write, and how. */
enum class NotifyTarget(val uuid: String, val indicate: Boolean) {
    PMD_CONTROL_POINT(PMD_CONTROL_POINT_UUID, indicate = true),
    PMD_DATA(PMD_DATA_UUID, indicate = false),
    HR_MEASUREMENT(HR_MEASUREMENT_UUID, indicate = false),
    PFTP_REQUEST_RESPONSE(PFTP_REQUEST_RESPONSE_UUID, indicate = false),
    PFTP_EVENT(PFTP_EVENT_UUID, indicate = false),
}

// BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE / ENABLE_INDICATION_VALUE, kept here so this
// file stays free of android.* and the allow-list test can run on the JVM.
private val CCCD_ENABLE_NOTIFICATION = byteArrayOf(0x01, 0x00)
private val CCCD_ENABLE_INDICATION = byteArrayOf(0x02, 0x00)
