package com.polarloopdebugger.protocol

/**
 * Best-effort generic protobuf field walk for any `.BPB`/`.BIN` file whose schema isn't confirmed
 * yet (everything except AUTOSNNN.BPB). No assumed field names -- just tag/wire-type/value, so
 * nothing pulled from the device is silently dropped even when we can't decode it properly.
 * LENGTH_DELIMITED values are recursed into speculatively (real protobuf messages nest); if that
 * recursion doesn't parse as a clean field sequence, the raw bytes are reported instead.
 */
fun dumpFieldsGeneric(data: ByteArray, depth: Int = 0): String {
    val reader = ProtoReader(data)
    val lines = mutableListOf<String>()
    val indent = "  ".repeat(depth)
    try {
        while (reader.hasMore()) {
            val field = reader.readField()
            when (field.wireType) {
                WireType.VARINT ->
                    lines.add("$indent#${field.fieldNumber} varint = ${field.varint}")
                WireType.FIXED32, WireType.FIXED64 ->
                    lines.add("$indent#${field.fieldNumber} ${field.wireType} = ${field.bytes!!.toHex()}")
                WireType.LENGTH_DELIMITED -> {
                    val bytes = field.bytes!!
                    val nested = tryDumpNested(bytes, depth + 1)
                    lines.add(
                        if (nested != null) {
                            "$indent#${field.fieldNumber} message (${bytes.size} bytes) {\n$nested\n$indent}"
                        } else {
                            "$indent#${field.fieldNumber} bytes (${bytes.size}) = ${bytes.toHex()}"
                        },
                    )
                }
            }
        }
    } catch (e: Exception) {
        lines.add("$indent<parse error at byte offset, truncating: ${e.message}>")
    }
    return lines.joinToString("\n")
}

private fun tryDumpNested(bytes: ByteArray, depth: Int): String? {
    if (bytes.isEmpty()) return null
    return try {
        val dump = dumpFieldsGeneric(bytes, depth)
        if (dump.contains("parse error")) null else dump
    } catch (e: Exception) {
        null
    }
}
