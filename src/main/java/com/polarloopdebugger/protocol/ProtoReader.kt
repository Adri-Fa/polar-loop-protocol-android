package com.polarloopdebugger.protocol

/** Wire type of a decoded protobuf field, per the standard proto2 wire format. */
enum class WireType(val id: Int) {
    VARINT(0),
    FIXED64(1),
    LENGTH_DELIMITED(2),
    FIXED32(5);

    companion object {
        fun fromId(id: Int): WireType =
            entries.find { it.id == id } ?: throw PftpProtocolException("unsupported wire type $id")
    }
}

/** One decoded (tag, value) pair. Exactly one of [varint]/[bytes] is set, per [wireType]. */
data class ProtoField(val fieldNumber: Int, val wireType: WireType, val varint: Long?, val bytes: ByteArray?)

/**
 * Minimal hand-rolled protobuf wire-format reader: tag/varint/length-delimited, no schema, no
 * generated code. Same style/precedent as the existing PMD TLV reader (parseSettingsTlv) -- this
 * is the "preferred approach for consistency" per the historical-sync task brief, reused here for
 * directory listings, AUTOS decoding, and the generic undecoded-file field walk.
 */
class ProtoReader(private val data: ByteArray, private var offset: Int = 0) {

    fun hasMore(): Boolean = offset < data.size

    fun position(): Int = offset

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = data[offset].toInt() and 0xFF
            offset++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /** One (fieldNumber, wireType, value) step, advancing past whatever wire type dictates. */
    fun readField(): ProtoField {
        val tag = readVarint()
        val fieldNumber = (tag shr 3).toInt()
        val wireType = WireType.fromId((tag and 0x7).toInt())
        return when (wireType) {
            WireType.VARINT -> ProtoField(fieldNumber, wireType, readVarint(), null)
            WireType.LENGTH_DELIMITED -> {
                val len = readVarint().toInt()
                val bytes = data.copyOfRange(offset, offset + len)
                offset += len
                ProtoField(fieldNumber, wireType, null, bytes)
            }
            WireType.FIXED64 -> {
                val bytes = data.copyOfRange(offset, offset + 8)
                offset += 8
                ProtoField(fieldNumber, wireType, null, bytes)
            }
            WireType.FIXED32 -> {
                val bytes = data.copyOfRange(offset, offset + 4)
                offset += 4
                ProtoField(fieldNumber, wireType, null, bytes)
            }
        }
    }

    companion object {
        /** proto2 `sint32`/`sint64` zigzag decoding, used by PbPpIntervalSamples's delta fields. */
        fun zigzagDecode(v: Long): Long = (v ushr 1) xor -(v and 1)
    }
}

/** Little-endian FIXED32 bytes -> Float, per protobuf's `float` wire encoding. */
fun ByteArray.toFloat32(): Float = Float.fromBits(
    (this[0].toInt() and 0xFF) or
        ((this[1].toInt() and 0xFF) shl 8) or
        ((this[2].toInt() and 0xFF) shl 16) or
        ((this[3].toInt() and 0xFF) shl 24),
)
