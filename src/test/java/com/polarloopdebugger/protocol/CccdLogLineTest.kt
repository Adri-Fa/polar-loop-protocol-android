package com.polarloopdebugger.protocol

import com.polarloopdebugger.protocol.OutgoingFrame.EnableNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one line PolarGattClient.enableAllNotifications logs per characteristic, before its CCCD write. */
class CccdLogLineTest {

    @Test
    fun `format is UUID, 4-digit hex handle, then the exact CCCD value`() {
        assertEquals(
            "CCCD fb005c52-02e7-f387-1cad-8acd2d8df0c8 handle=0x0034 value=01 00",
            EnableNotifications(NotifyTarget.PFTP_EVENT).logLine(0x34),
        )
        assertEquals(
            "CCCD fb005c81-02e7-f387-1cad-8acd2d8df0c8 handle=0x00AB value=02 00",
            EnableNotifications(NotifyTarget.PMD_CONTROL_POINT).logLine(0xAB),
        )
        assertEquals(
            "CCCD 00002a37-0000-1000-8000-00805f9b34fb handle=0xFFFF value=01 00",
            EnableNotifications(NotifyTarget.HR_MEASUREMENT).logLine(0xFFFF),
        )
    }

    @Test
    fun `value is 02 00 for the one indication target, 01 00 for every notification target`() {
        for (target in NotifyTarget.entries) {
            val expected = if (target.indicate) "02 00" else "01 00"
            assertTrue(EnableNotifications(target).logLine(1).endsWith(" value=$expected"))
        }
        assertEquals(listOf(NotifyTarget.PMD_CONTROL_POINT), NotifyTarget.entries.filter { it.indicate })
    }

    /**
     * The line's only inputs are a closed enum and an Int. Every target at every 16-bit ATT handle
     * matches a fully anchored pattern: there is no room in it for payload bytes or an address.
     */
    @Test
    fun `no target at any handle can produce payload bytes or a device address`() {
        val line = Regex("""CCCD [0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12} handle=0x[0-9A-F]{4} value=0[12] 00""")
        val address = Regex("""([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")
        for (target in NotifyTarget.entries) for (handle in 0..0xFFFF) {
            val logged = EnableNotifications(target).logLine(handle)
            assertTrue(logged, line.matches(logged))
            assertFalse(logged, address.containsMatchIn(logged))
            assertEquals(logged, "0x%04X".format(handle), logged.substringAfter("handle=").substringBefore(' '))
        }
    }
}
