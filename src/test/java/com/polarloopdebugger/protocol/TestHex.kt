package com.polarloopdebugger.protocol

/** Parses "F0-01-02-00-00" or "F0 01 02 00 00" style fixtures into a ByteArray. */
fun hex(s: String): ByteArray =
    s.trim().split("-", " ").filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
