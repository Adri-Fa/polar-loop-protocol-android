package com.polarloopdebugger.protocol

/** PbPftpGetExerciseStatusResult.PbExerciseState, pftp_response.proto. */
enum class ExerciseState(val id: Int) {
    OTHER(0),
    RUNNING(1),
    PAUSED(2),
    OFF(3);

    companion object {
        fun fromId(id: Int): ExerciseState? = entries.find { it.id == id }
    }
}

/**
 * Decodes `required PbExerciseState exercise_state = 1` from a GET_EXERCISE_STATUS response body.
 * The other fields (type, sport, start time, data-merge path) are skipped. Capture-validated
 * against one real Loop answer (capture9 frame 345: body `08 03`, OFF); RUNNING/PAUSED bodies are
 * schema-derived only.
 */
fun decodeExerciseState(data: ByteArray): ExerciseState {
    val reader = ProtoReader(data)
    var state: Long? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        if (field.fieldNumber == 1 && field.wireType == WireType.VARINT) state = field.varint
    }
    val id = state ?: throw PftpProtocolException("PbPftpGetExerciseStatusResult missing required exercise_state")
    return ExerciseState.fromId(id.toInt()) ?: throw PftpProtocolException("unknown exercise_state $id")
}

/** Whether a sync can start now -- see [syncGateOf]. */
sealed interface SyncGate {
    data object Ready : SyncGate

    /** An exercise is running or paused; Polar's sync guideline says the device answers sync with 202 then. */
    data object ExerciseRunning : SyncGate

    /**
     * Not ready. [code] is the PbPFtpError the device answered with (e.g. 202 SYSTEM_BUSY, or 201
     * NOT_IMPLEMENTED if the firmware lacks the query), or null when there was no usable answer:
     * timeout, cancelled, or a status body that doesn't decode.
     */
    data class Busy(val code: Int?) : SyncGate
}

/** Maps a GET_EXERCISE_STATUS result onto [SyncGate]; anything not provably idle is not Ready. */
fun syncGateOf(result: PftpResult): SyncGate = when (result) {
    is PftpResult.Content -> try {
        when (decodeExerciseState(result.bytes)) {
            ExerciseState.RUNNING, ExerciseState.PAUSED -> SyncGate.ExerciseRunning
            ExerciseState.OFF, ExerciseState.OTHER -> SyncGate.Ready
        }
    } catch (e: PftpProtocolException) {
        SyncGate.Busy(null)
    }
    // exercise_state is required, so an empty body is malformed rather than "idle".
    PftpResult.Empty -> SyncGate.Busy(null)
    is PftpResult.DeviceError -> SyncGate.Busy(result.code)
    PftpResult.Timeout, is PftpResult.Cancelled -> SyncGate.Busy(null)
}
