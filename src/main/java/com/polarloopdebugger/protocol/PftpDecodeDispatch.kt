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

/**
 * Outcome of reading a PFTP GET response through [decodePftpResponse]: either the decoded file
 * content, or a clean signal that the device has none -- never a parse exception for this case.
 */
sealed class PftpDecodeResult<out T> {
    data class Content<T>(val value: T) : PftpDecodeResult<T>()
    object NoData : PftpDecodeResult<Nothing>()
}

// The 2-byte PFTP status response (0x67 0x00) a device sends instead of file content when there's
// nothing at the requested path (confirmed on Daily Summary/Nightly Recharge/Sleep/Skin
// Temperature GETs for a day with no data) -- distinct from real payloads (seen as small as ~239
// bytes) and from truncated/corrupted data. 0x67's low 3 bits are protobuf wire type 7, which
// doesn't exist in the spec, so every decodeXFile function in this package throws "unsupported
// wire type 7" if handed this directly (each just does `ProtoReader(data)` then
// `while (reader.hasMore())`, with no shape check first).
private val PFTP_NO_DATA_STUB = byteArrayOf(0x67, 0x00)

/**
 * Shared PFTP response read entry point -- route response bytes through here rather than calling
 * a decodeXFile function directly, so the "no data at this path" stub is recognized once for every
 * decoder in this package, present and future, instead of each one crashing on it independently.
 */
fun <T> decodePftpResponse(data: ByteArray, decode: (ByteArray) -> T): PftpDecodeResult<T> =
    if (data.contentEquals(PFTP_NO_DATA_STUB)) PftpDecodeResult.NoData else PftpDecodeResult.Content(decode(data))
