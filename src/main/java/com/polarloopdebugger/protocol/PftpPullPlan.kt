package com.polarloopdebugger.protocol

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The CONFIGURABLE root list of paths Historical Sync pulls each run (task brief: "a defined,
 * CONFIGURABLE list of paths...don't hardcode a single fixed traversal"). Every directory path
 * (trailing "/") gets listed and recursed into at runtime by the sync engine (see
 * HistoricalSyncActivity) -- this function only returns the fixed starting points, not the full
 * dynamic traversal (which depends on what the device actually reports).
 *
 * Date-folder naming (`yyyyMMdd/`, e.g. "<date>/") matches PROTOCOL.md's confirmed capture9
 * inventory exactly ("one subtree per calendar date").
 */
fun rootPullPaths(today: LocalDate, recentDays: Int = DEFAULT_RECENT_DAYS): List<String> {
    require(recentDays >= 0) { "recentDays must be >= 0, got $recentDays" }
    val dateFolders = (0 until recentDays).map { offset ->
        "/U/0/${today.minusDays(offset.toLong()).format(DATE_FOLDER_FORMAT)}/"
    }
    return listOf("/DEVICE.BPB", "/U/0/AUTOS/") + dateFolders
}

/** Last-7-days default, per the task brief's "e.g. last 7 days" suggestion. */
const val DEFAULT_RECENT_DAYS = 7

private val DATE_FOLDER_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
