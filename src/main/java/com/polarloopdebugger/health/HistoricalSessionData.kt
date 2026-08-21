package com.polarloopdebugger.health

import com.polarloopdebugger.protocol.ActivitySamples
import com.polarloopdebugger.protocol.AutomaticSampleSession
import com.polarloopdebugger.protocol.DailySummary
import com.polarloopdebugger.protocol.PbDate
import com.polarloopdebugger.protocol.SkinTemperatureLog
import com.polarloopdebugger.protocol.SleepAnalysisResult

/**
 * Already-decoded historical data for one sync session, gathered as files stream in.
 *
 * Deliberately excludes Nightly Recharge -- that data stays app-local and is never written to
 * Health Connect (no HC record type maps to it, and none should be invented for it).
 */
data class HistoricalSessionData(
    val sleepResults: List<SleepAnalysisResult> = emptyList(),
    val activitySamples: List<ActivitySamples> = emptyList(),
    val dailySummaries: List<DailySummary> = emptyList(),
    val skinTemperatureLogs: List<Pair<PbDate, SkinTemperatureLog>> = emptyList(),
    val autosSessions: List<AutomaticSampleSession> = emptyList(),
)

/** Mutable accumulator mirroring the file's existing `diskSpaceJson`-style accumulation pattern. */
class HistoricalSessionDataBuilder {
    private val sleepResults = mutableListOf<SleepAnalysisResult>()
    private val activitySamples = mutableListOf<ActivitySamples>()
    private val dailySummaries = mutableListOf<DailySummary>()
    private val skinTemperatureLogs = mutableListOf<Pair<PbDate, SkinTemperatureLog>>()
    private val autosSessions = mutableListOf<AutomaticSampleSession>()

    fun addSleep(result: SleepAnalysisResult) {
        sleepResults += result
    }

    fun addActivity(samples: ActivitySamples) {
        activitySamples += samples
    }

    fun addDailySummary(summary: DailySummary) {
        dailySummaries += summary
    }

    /** [pathDate] is the calendar date parsed out of the file's path, e.g. `/U/0/<date>/SKINTEMP/...`. */
    fun addSkinTemperature(pathDate: PbDate, log: SkinTemperatureLog) {
        skinTemperatureLogs += pathDate to log
    }

    fun addAutos(session: AutomaticSampleSession) {
        autosSessions += session
    }

    fun clear() {
        sleepResults.clear()
        activitySamples.clear()
        dailySummaries.clear()
        skinTemperatureLogs.clear()
        autosSessions.clear()
    }

    fun snapshot(): HistoricalSessionData = HistoricalSessionData(
        sleepResults = sleepResults.toList(),
        activitySamples = activitySamples.toList(),
        dailySummaries = dailySummaries.toList(),
        skinTemperatureLogs = skinTemperatureLogs.toList(),
        autosSessions = autosSessions.toList(),
    )
}
