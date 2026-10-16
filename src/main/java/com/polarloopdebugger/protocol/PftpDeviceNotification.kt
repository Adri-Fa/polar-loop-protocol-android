package com.polarloopdebugger.protocol

import java.io.ByteArrayOutputStream

/** One PbPFtpDevToHostNotification (polar-ble-sdk pftp_notification.proto), RFC76 frames reassembled. */
class PftpDeviceNotification(
    val id: Int, // first payload byte of the first frame
    val params: ByteArray, // everything after it, across MORE frames, RFC76 headers stripped (protobuf)
    val receivedAtMillis: Long, // System.currentTimeMillis() when the LAST frame arrived
) {
    val name: String? get() = PftpDeviceNotificationId.nameOf(id)
    override fun toString() = "PftpDeviceNotification($id ${name ?: "?"}, ${params.size} B)" // size only
}

/** PbPFtpDevToHostNotification, pftp_notification.proto. */
enum class PftpDeviceNotificationId(val id: Int) {
    FILESYSTEM_MODIFIED(0), INTERNAL_TEST_EVENT(1), IDLING(2), BATTERY_STATUS(3), INACTIVITY_ALERT(4),
    TRAINING_SESSION_STATUS(5), SYNC_REQUIRED(7), AUTOSYNC_STATUS(8), PNS_DH_NOTIFICATION_RESPONSE(9),
    PNS_SETTINGS(10), START_GPS_MEASUREMENT(11), STOP_GPS_MEASUREMENT(12), KEEP_BACKGROUND_ALIVE(13),
    POLAR_SHELL_DH_DATA(14), MEDIA_CONTROL_REQUEST_DH(15), MEDIA_CONTROL_COMMAND_DH(16),
    MEDIA_CONTROL_ENABLED(17), REST_API_EVENT(18), EXERCISE_STATUS(19);

    companion object {
        fun nameOf(id: Int): String? = entries.find { it.id == id }?.name
    }
}

/**
 * Reassembles fb005c52 frames into [PftpDeviceNotification]s, as polar-ble-sdk's
 * BlePsFtpClient.waitForNotification does: RFC76 header bit 0 = next (0 on the first frame), bits
 * 1-2 = status (1 LAST, 3 MORE), bits 4-7 = sequence; the first frame's first payload byte is the id.
 * A first frame while one is still open drops the open one, and a continuation with nothing open
 * (or out of sequence) is dropped; each is logged, sizes only.
 *
 * These frames are unsolicited: they never touch GattQueue, so they can't end or extend a PFTP
 * response in flight. One instance per client, used on its callback thread only.
 */
class PftpDeviceNotificationReader(
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var open: ByteArrayOutputStream? = null
    private var openId = 0
    private var expectedSequence = 0

    /** The notification [frame] completes, or null. */
    fun accept(frame: ByteArray): PftpDeviceNotification? {
        val header = PftpFrameTrace.of(PFTP_EVENT_UUID, frame) ?: return null
        if (header.status != STATUS_LAST && header.status != STATUS_MORE) {
            log("fb005c52 frame with RFC76 status ${header.status}, dropped${dropOpen()}")
            return null
        }
        val body: ByteArrayOutputStream
        if (header.next == 0) {
            open?.let { log("fb005c52 notification $openId still open (${it.size()} B) when the next began, dropped") }
            open = null
            if (frame.size < 2) {
                log("fb005c52 first frame with no notification id, dropped")
                return null
            }
            openId = frame[1].toInt() and 0xFF
            body = ByteArrayOutputStream().apply { write(frame, 2, frame.size - 2) }
        } else {
            body = open ?: run {
                log("fb005c52 continuation with no notification open, dropped (${frame.size} bytes)")
                return null
            }
            if (header.sequence != expectedSequence) {
                log("fb005c52 notification $openId out of sync (sequence ${header.sequence}, expected $expectedSequence)${dropOpen()}")
                return null
            }
            body.write(frame, 1, frame.size - 1)
        }
        expectedSequence = (header.sequence + 1) and 0x0F
        if (header.status == STATUS_MORE) {
            open = body
            return null
        }
        open = null
        return PftpDeviceNotification(openId, body.toByteArray(), clock())
    }

    /** A new link: nothing half-received on the old one may be glued to its frames. */
    fun reset() {
        open = null
    }

    private fun dropOpen(): String {
        val dropped = open ?: return ""
        open = null
        return "; notification $openId (${dropped.size()} B) dropped with it"
    }

    private companion object {
        const val STATUS_LAST = 1
        const val STATUS_MORE = 3
    }
}

// -- params decoders, one per message loopCompanion reports (pftp_notification.proto field numbers). --
// Absent optional fields, and enum values the proto lacks, decode as null.

/** PbPFtpFilesystemModifiedParams. */
data class FilesystemModified(val action: Action?, val path: String?) {
    enum class Action(val id: Int) { CREATED(0), UPDATED(1), REMOVED(2) }
}

/** PbPFtpSyncTrigger, one of PbPFtpSyncRequiredParams' sync_triggers. */
data class SyncTrigger(val source: Source?, val featureName: String?) {
    enum class Source(val id: Int) { NO_SOURCE(0), USER(1), TIMED(2), FEATURE(3) }
}

/** PbPFtpAutoSyncStatusParams. */
data class AutoSyncStatus(val succeeded: Boolean?, val description: String?)

/** PbPftpDHExerciseStatus. Its state enum has the same values as GET_EXERCISE_STATUS's, [ExerciseState]. */
data class ExerciseStatusNotification(val exerciseState: ExerciseState?, val exerciseType: ExerciseType?) {
    enum class ExerciseType(val id: Int) { OTHER(0), TRAINING(1), NIGHTLY_RECHARGE(2) }
}

/** 1 action (CREATED/UPDATED/REMOVED), 2 path. */
fun decodeFilesystemModified(params: ByteArray): FilesystemModified {
    var action: FilesystemModified.Action? = null
    var path: String? = null
    forEachField(params) { f ->
        when (f.fieldNumber) {
            1 -> action = f.varint?.let { v -> FilesystemModified.Action.entries.find { it.id.toLong() == v } }
            2 -> path = f.text()
        }
    }
    return FilesystemModified(action, path)
}

/** 1 inprogress. */
fun decodeTrainingSessionStatus(params: ByteArray): Boolean? {
    var inProgress: Boolean? = null
    forEachField(params) { f -> if (f.fieldNumber == 1) inProgress = f.flag() }
    return inProgress
}

/** 1 repeated sync_triggers {1 source (NO_SOURCE/USER/TIMED/FEATURE), 2 feature_name}. */
fun decodeSyncRequired(params: ByteArray): List<SyncTrigger> = buildList {
    forEachField(params) { trigger ->
        if (trigger.fieldNumber != 1) return@forEachField
        var source: SyncTrigger.Source? = null
        var featureName: String? = null
        forEachField(trigger.bytes ?: throw PftpProtocolException("sync_triggers is not a message")) { f ->
            when (f.fieldNumber) {
                1 -> source = f.varint?.let { v -> SyncTrigger.Source.entries.find { it.id.toLong() == v } }
                2 -> featureName = f.text()
            }
        }
        add(SyncTrigger(source, featureName))
    }
}

/** 1 succeeded, 2 description. */
fun decodeAutoSyncStatus(params: ByteArray): AutoSyncStatus {
    var succeeded: Boolean? = null
    var description: String? = null
    forEachField(params) { f ->
        when (f.fieldNumber) {
            1 -> succeeded = f.flag()
            2 -> description = f.text()
        }
    }
    return AutoSyncStatus(succeeded, description)
}

/** 1 exercise_state (OTHER/RUNNING/PAUSED/OFF), 2 exercise_type (OTHER/TRAINING/NIGHTLY_RECHARGE). */
fun decodeExerciseStatusNotification(params: ByteArray): ExerciseStatusNotification {
    var state: ExerciseState? = null
    var type: ExerciseStatusNotification.ExerciseType? = null
    forEachField(params) { f ->
        when (f.fieldNumber) {
            1 -> state = f.varint?.let { ExerciseState.fromId(it.toInt()) }
            2 -> type = f.varint?.let { v -> ExerciseStatusNotification.ExerciseType.entries.find { it.id.toLong() == v } }
        }
    }
    return ExerciseStatusNotification(state, type)
}

private inline fun forEachField(data: ByteArray, block: (ProtoField) -> Unit) {
    val reader = ProtoReader(data)
    while (reader.hasMore()) block(reader.readField())
}

private fun ProtoField.flag(): Boolean? = varint?.let { it != 0L }
private fun ProtoField.text(): String? = bytes?.toString(Charsets.UTF_8)
