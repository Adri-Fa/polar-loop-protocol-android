package com.polarloopdebugger.protocol

/**
 * Which decode strategy applies to a PFTP GET response, purely from the *request path* -- no
 * response bytes inspected here. Mirrors PROTOCOL.md's three confirmed response shapes (JSON /
 * directory listing / opaque file), plus AUTOS as the one file type with a confirmed field-level
 * schema (see PftpAutosDecoder). Everything else degrades to [PftpGenericDump] -- per the task
 * brief, "only AUTOS has a confirmed schema; everything else degrades to raw/generic display."
 */
enum class PftpContentKind {
    DIRECTORY, REST_JSON, AUTOS, SLEEP, ACTIVITY, DAILY_SUMMARY, NIGHTLY_RECHARGE, SKIN_TEMPERATURE, DISK_SPACE, GENERIC
}

fun classifyPftpPath(path: String): PftpContentKind = when {
    path == DISK_SPACE_PSEUDO_PATH -> PftpContentKind.DISK_SPACE
    path.endsWith("/") -> PftpContentKind.DIRECTORY
    path.startsWith("/REST/") -> PftpContentKind.REST_JSON
    AUTOS_FILENAME.matches(path.substringAfterLast('/')) -> PftpContentKind.AUTOS
    path.endsWith("/SLEEP/SLEEPRES.BPB") -> PftpContentKind.SLEEP
    ACT_FILENAME.matches(path.substringAfterLast('/')) && path.contains("/ACT/") -> PftpContentKind.ACTIVITY
    path.endsWith("/DSUM/DSUM.BPB") -> PftpContentKind.DAILY_SUMMARY
    path.endsWith("/NR/NR.BPB") -> PftpContentKind.NIGHTLY_RECHARGE
    path.endsWith("/SKINTEMP/TEMPCONT.BPB") -> PftpContentKind.SKIN_TEMPERATURE
    else -> PftpContentKind.GENERIC
}

// e.g. "AUTOS000.BPB", "AUTOS123.BPB" -- PROTOCOL.md "/U/0/AUTOS/AUTOS%03d.BPB=PbAutomaticSampleSessions".
private val AUTOS_FILENAME = Regex("AUTOS\\d+\\.BPB")

// e.g. "ASAMPL0.BPB" -- only one seen per date so far (capture17), but numbered like AUTOS.
private val ACT_FILENAME = Regex("ASAMPL\\d+\\.BPB")
