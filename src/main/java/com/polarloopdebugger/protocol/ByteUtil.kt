package com.polarloopdebugger.protocol

import java.math.BigInteger

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

/** Little-endian bytes -> unsigned magnitude, widened to Double (exact for anything realistic;
 * SECURITY's 16-byte field is the only one that would need this beyond 8 bytes, and it's unused). */
fun littleEndianUnsignedToDouble(bytes: ByteArray): Double =
    BigInteger(1, bytes.reversedArray()).toDouble()

/** Little-endian bytes -> signed two's-complement Long. Used for PPG/ACC reference samples
 * (24-bit and 16-bit). */
fun signedInt(bytes: ByteArray): Long {
    var v = 0L
    for (i in bytes.indices) v = v or ((bytes[i].toLong() and 0xFF) shl (8 * i))
    val nbits = bytes.size * 8
    if (nbits < 64 && (v and (1L shl (nbits - 1))) != 0L) v -= (1L shl nbits)
    return v
}

/** Little-endian 8-byte unsigned timestamp -> Long. Fits safely: real epoch-ns values are well
 * under 2^63 until the year 2262. */
fun readTimestampNs(bytes: ByteArray): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((bytes[i].toLong() and 0xFF) shl (8 * i))
    return v
}

fun littleEndianUInt16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/** Inverse of [littleEndianUnsignedToDouble]: unsigned magnitude -> `size` little-endian bytes. */
fun doubleToLittleEndianUnsigned(value: Double, size: Int): ByteArray {
    val big = BigInteger.valueOf(value.toLong())
    return ByteArray(size) { i -> big.shiftRight(8 * i).toByte() }
}
