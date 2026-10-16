package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PftpFrameTraceTest {

    private fun trace(h: String) = PftpFrameTrace.of(PFTP_REQUEST_RESPONSE_UUID, hex(h))!!

    @Test
    fun `header fields and length, for MORE, LAST and status-0 frames`() {
        assertEquals(PftpFrameTrace(PFTP_REQUEST_RESPONSE_UUID, 3, 0, 0, 4, null), trace("06 DE AD BE"))
        assertEquals(PftpFrameTrace(PFTP_REQUEST_RESPONSE_UUID, 1, 4, 1, 3, null), trace("43 DE AD"))
        assertEquals(PftpFrameTrace(PFTP_REQUEST_RESPONSE_UUID, 0, 0, 0, 3, 202), trace("00 CA 00"))
        assertEquals(PftpFrameTrace(PFTP_REQUEST_RESPONSE_UUID, 0, 0, 0, 3, 103), trace("00 67 00"))
        assertEquals(259, trace("00 03 01").errorCode) // the full little-endian u16
        assertEquals(PftpFrameTrace(PFTP_EVENT_UUID, 3, 15, 1, 1, null), PftpFrameTrace.of(PFTP_EVENT_UUID, hex("F7")))
    }

    @Test
    fun `a status-0 frame too short for a code has none, and an empty frame has no trace`() {
        assertNull(trace("00 67").errorCode)
        assertNull(PftpFrameTrace.of(PFTP_REQUEST_RESPONSE_UUID, ByteArray(0)))
    }

    @Test
    fun `a trace has no field that could hold payload bytes`() {
        val fields = PftpFrameTrace::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("characteristic", "status", "sequence", "next", "length", "errorCode"), fields.map { it.name }.toSet())
        assertEquals(setOf(String::class.java, Int::class.java, Integer::class.java), fields.map { it.type }.toSet())
        assertEquals(
            "PftpFrameTrace(characteristic=$PFTP_REQUEST_RESPONSE_UUID, status=3, sequence=0, next=0, length=4, errorCode=null)",
            trace("06 DE AD BE").toString(),
        )
    }
}
