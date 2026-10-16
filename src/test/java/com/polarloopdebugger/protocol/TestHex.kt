package com.polarloopdebugger.protocol

/** Parses "F0-01-02-00-00" or "F0 01 02 00 00" style fixtures into a ByteArray. */
fun hex(s: String): ByteArray =
    s.trim().split("-", " ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }
        .toByteArray()

/** A capture-fixture packet: "HH+N" = header HH and N (redacted, zero) payload bytes; "HH:CCCC" = verbatim status-0 frame. */
fun fixturePacket(token: String): ByteArray =
    if ('+' in token) {
        val (header, n) = token.split('+')
        ByteArray(n.toInt() + 1).also { it[0] = header.toInt(16).toByte() }
    } else {
        token.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

/** A capture-fixture outcome; null for NONE, a stream that ended without LAST or a status-0 frame. */
fun fixtureResult(expected: String): PftpResult? = when {
    expected == "NONE" -> null
    expected == "EMPTY" -> PftpResult.Empty
    expected.startsWith("CONTENT:") -> PftpResult.Content(ByteArray(expected.removePrefix("CONTENT:").toInt()))
    expected.startsWith("ERROR:") -> PftpResult.DeviceError(expected.removePrefix("ERROR:").toInt())
    else -> error("unknown expectation $expected")
}
