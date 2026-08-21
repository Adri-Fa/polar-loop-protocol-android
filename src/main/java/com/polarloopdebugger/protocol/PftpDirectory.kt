package com.polarloopdebugger.protocol

/** PbPFtpEntry{name, size} -- created/modified/touched timestamps are skipped, not needed here. */
data class PftpEntry(val name: String, val size: Long, val isDirectory: Boolean)

/**
 * Decodes a PbPFtpDirectory (`repeated PbPFtpEntry entries = 1`) response body, per
 * PROTOCOL.md's "Virtual-filesystem inventory" section. Unknown entry fields (created/modified/
 * touched PbSystemDateTime submessages) are skipped generically via ProtoReader rather than
 * parsed -- this decoder only needs name+size.
 */
fun decodeDirectoryListing(data: ByteArray): List<PftpEntry> {
    val reader = ProtoReader(data)
    val entries = mutableListOf<PftpEntry>()
    while (reader.hasMore()) {
        val field = reader.readField()
        require(field.fieldNumber == 1 && field.wireType == WireType.LENGTH_DELIMITED) {
            "expected PbPFtpDirectory.entries (field 1), got field ${field.fieldNumber}"
        }
        entries.add(decodeEntry(field.bytes!!))
    }
    return entries
}

private fun decodeEntry(data: ByteArray): PftpEntry {
    val reader = ProtoReader(data)
    var name: String? = null
    var size: Long? = null
    while (reader.hasMore()) {
        val field = reader.readField()
        when (field.fieldNumber) {
            1 -> name = String(field.bytes!!, Charsets.UTF_8)
            2 -> size = field.varint
            // 3/4/5 (created/modified/touched) intentionally skipped -- not needed.
        }
    }
    val entryName = requireNotNull(name) { "PbPFtpEntry missing required name (field 1)" }
    val entrySize = requireNotNull(size) { "PbPFtpEntry missing required size (field 2)" }
    return PftpEntry(entryName, entrySize, entryName.endsWith("/"))
}
