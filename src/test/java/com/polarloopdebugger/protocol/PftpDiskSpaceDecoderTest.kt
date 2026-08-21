package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic fixture -- structurally matches a real GET_DISK_SPACE response's wire format
 * (see PftpDiskSpaceDecoder's doc comment), but the fragment counts below aren't from any
 * real device.
 */
class PftpDiskSpaceDecoderTest {

    @Test
    fun `decodes a GET_DISK_SPACE response`() {
        // field 1 (fragment_size=4096): 08 [varint 4096 = 80 20]
        // field 2 (total_fragments=5000): 10 [varint 5000 = 88 27]
        // field 3 (free_fragments=2500): 18 [varint 2500 = C4 13]
        val fixture = hex("08 80 20 10 88 27 18 C4 13")
        val result = decodeDiskSpaceResult(fixture)

        assertEquals(4096L, result.fragmentSize)
        assertEquals(5000L, result.totalFragments)
        assertEquals(2500L, result.freeFragments)
        assertEquals(20_480_000L, result.totalBytes)
        assertEquals(10_240_000L, result.freeBytes)
    }
}
