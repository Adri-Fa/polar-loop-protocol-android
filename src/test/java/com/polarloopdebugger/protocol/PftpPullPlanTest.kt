package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class PftpPullPlanTest {

    // Arbitrary date -- rootPullPaths is a pure function of `today`, so any date exercises the
    // same day-window logic. Not tied to any real capture.
    private val today = LocalDate.of(2024, 3, 10)

    @Test
    fun `default 7-day window walks back from today`() {
        val paths = rootPullPaths(today)

        assertEquals(
            listOf(
                "/DEVICE.BPB",
                "/U/0/AUTOS/",
                "/U/0/20240310/",
                "/U/0/20240309/",
                "/U/0/20240308/",
                "/U/0/20240307/",
                "/U/0/20240306/",
                "/U/0/20240305/",
                "/U/0/20240304/",
            ),
            paths,
        )
    }

    @Test
    fun `recentDays=0 pulls only the fixed paths`() {
        assertEquals(listOf("/DEVICE.BPB", "/U/0/AUTOS/"), rootPullPaths(today, recentDays = 0))
    }

    @Test
    fun `recentDays is configurable, not hardcoded`() {
        val paths = rootPullPaths(today, recentDays = 2)
        assertEquals(listOf("/DEVICE.BPB", "/U/0/AUTOS/", "/U/0/20240310/", "/U/0/20240309/"), paths)
    }

    @Test
    fun `negative recentDays rejected`() {
        assertThrows(IllegalArgumentException::class.java) { rootPullPaths(today, recentDays = -1) }
    }
}
