package com.polarloopdebugger.protocol

import org.junit.Assert.assertThrows
import org.junit.Test

class ProtoReaderTest {

    @Test
    fun `truncated varint tag raises PftpProtocolException, not a raw array index crash`() {
        // 0xCA has its continuation bit set, so readVarint() itself consumes both bytes fine
        // (tag = field 9, LENGTH_DELIMITED) -- the crash was one level up, in readField()'s
        // follow-up readVarint() call for the length, with nothing left in the buffer.
        val reader = ProtoReader(byteArrayOf(0xCA.toByte(), 0x00))
        assertThrows(PftpProtocolException::class.java) { reader.readField() }
    }

    @Test
    fun `length-delimited field longer than the remaining buffer raises PftpProtocolException`() {
        // tag byte 0x0A = field 1, LENGTH_DELIMITED; length byte says 5 bytes follow, but none do.
        val reader = ProtoReader(byteArrayOf(0x0A, 0x05))
        assertThrows(PftpProtocolException::class.java) { reader.readField() }
    }
}
