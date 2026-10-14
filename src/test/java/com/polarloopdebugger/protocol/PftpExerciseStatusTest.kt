package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PftpExerciseStatusTest {

    /** capture9 frame 345: Polar Flow sent `02 19 80`, the Loop answered `02 08 03`. */
    @Test
    fun `real Loop answer to GET_EXERCISE_STATUS decodes as OFF and opens the gate`() {
        assertEquals(hex("02 19 80").toList(), buildExerciseStatusQuery().toList())
        val result = (PftpResponseReader().accept(hex("02 08 03")) as PftpResponseReader.Step.Done).result
        assertEquals(PftpResult.Content(hex("08 03")), result)
        assertEquals(ExerciseState.OFF, decodeExerciseState(hex("08 03")))
        assertEquals(SyncGate.Ready, syncGateOf(result))
    }

    @Test
    fun `running or paused exercise closes the gate`() {
        assertEquals(SyncGate.ExerciseRunning, syncGateOf(PftpResult.Content(hex("08 01"))))
        assertEquals(SyncGate.ExerciseRunning, syncGateOf(PftpResult.Content(hex("08 02 10 01"))))
        assertEquals(SyncGate.Ready, syncGateOf(PftpResult.Content(hex("08 00"))))
    }

    @Test
    fun `device errors surface their code as Busy`() {
        assertEquals(SyncGate.Busy(202), syncGateOf(PftpResult.DeviceError(202)))
        assertEquals(SyncGate.Busy(201), syncGateOf(PftpResult.DeviceError(201)))
    }

    @Test
    fun `no usable answer is Busy with no code, never Ready`() {
        assertEquals(SyncGate.Busy(null), syncGateOf(PftpResult.Timeout))
        assertEquals(SyncGate.Busy(null), syncGateOf(PftpResult.Cancelled("disconnected")))
        assertEquals(SyncGate.Busy(null), syncGateOf(PftpResult.Empty)) // exercise_state is required
        assertEquals(SyncGate.Busy(null), syncGateOf(PftpResult.Content(hex("08 07")))) // unknown state
        assertEquals(SyncGate.Busy(null), syncGateOf(PftpResult.Content(hex("10 01")))) // state missing
    }
}
