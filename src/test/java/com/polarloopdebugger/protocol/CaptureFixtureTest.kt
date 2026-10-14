package com.polarloopdebugger.protocol

import com.polarloopdebugger.protocol.PftpResponseReader.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs [PftpResponseReader] over real fb005c51 response streams from src/test/resources/captures/
 * (redacted: headers, packet lengths and status-0 frames are verbatim, payload bytes are zeros --
 * see each file's header). Every line carries the outcome an independent parse of the original
 * capture produced; the reader must agree on all of them.
 */
class CaptureFixtureTest {

    private class Exchange(val source: String, val request: String, val expected: String, val packets: List<ByteArray>)

    private fun load(name: String): List<Exchange> =
        javaClass.getResourceAsStream("/captures/$name")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(" ")
                Exchange(parts[0], parts[1], parts[2], parts.drop(3).map(::packet))
            }

    // "HH+N" = header HH and N (redacted, zero) payload bytes; "HH:CCCC" = verbatim status-0 frame.
    private fun packet(token: String): ByteArray =
        if ('+' in token) {
            val (header, n) = token.split('+')
            ByteArray(n.toInt() + 1).also { it[0] = header.toInt(16).toByte() }
        } else {
            token.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

    private fun expectedOf(expected: String): PftpResult? = when {
        expected == "NONE" -> null
        expected == "EMPTY" -> PftpResult.Empty
        expected.startsWith("CONTENT:") -> PftpResult.Content(ByteArray(expected.removePrefix("CONTENT:").toInt()))
        expected.startsWith("ERROR:") -> PftpResult.DeviceError(expected.removePrefix("ERROR:").toInt())
        else -> error("unknown expectation $expected")
    }

    /** The result, or null if the stream ended without a terminating packet. Fails on anything after it. */
    private fun parse(e: Exchange): PftpResult? {
        val reader = PftpResponseReader()
        e.packets.forEachIndexed { i, packet ->
            when (val step = reader.accept(packet)) {
                Step.NeedMore -> Unit
                is Step.Done -> {
                    assertEquals("${e.source}: packets after the terminator", e.packets.size - 1, i)
                    return step.result
                }
                is Step.OutOfSync -> throw AssertionError("${e.source}: ${step.reason}")
            }
        }
        return null
    }

    @Test
    fun `every Polar Flow response in capture9 parses to the independently derived outcome`() {
        val exchanges = load("capture9-flow-sync.txt")
        assertEquals(332, exchanges.size)
        for (e in exchanges) assertEquals("${e.source} ${e.request}", expectedOf(e.expected), parse(e))
    }

    @Test
    fun `capture9's multi-packet MORE-LAST streams reassemble to their full size`() {
        val multi = load("capture9-flow-sync.txt").filter { it.packets.size > 1 }
        assertEquals(34, multi.size)
        for (e in multi) {
            assertTrue(e.packets.dropLast(1).all { (it[0].toInt() shr 1) and 3 == 3 }) // all MORE before the LAST
            assertEquals(e.packets.sumOf { it.size - 1 }, (parse(e) as PftpResult.Content).bytes.size)
        }
        // AUTOS000.BPB, PROTOCOL.md's frame-1571 row: 1081 packets, 368,307 bytes on the wire, so
        // the 4-bit sequence number wraps 67 times inside one response.
        val autos000 = multi.single { it.source == "1571" }
        assertEquals(1081, autos000.packets.size)
        assertEquals(PftpResult.Content(ByteArray(368_307 - 1081)), parse(autos000))
    }

    @Test
    fun `capture9's empty successes -- 00 00 00 frames and zero-byte LAST packets -- are Empty`() {
        val empty = load("capture9-flow-sync.txt").filter { it.expected == "EMPTY" }
        assertEquals(64, empty.count { it.packets.single().contentEquals(hex("00 00 00")) })
        assertEquals(11, empty.count { it.packets.single().contentEquals(hex("02")) })
        for (e in empty) assertEquals(PftpResult.Empty, parse(e))
    }

    @Test
    fun `error 103 frames from capture9 and the debugger exports carry their PbPFtpError name`() {
        val errors = (load("capture9-flow-sync.txt") + load("debugger-exports.txt")).filter { it.expected.startsWith("ERROR") }
        assertEquals(39 + 18, errors.size)
        for (e in errors) assertEquals(PftpResult.DeviceError(103, "NO_SUCH_FILE_OR_DIRECTORY"), parse(e))
    }

    /**
     * capture12's AUTOS001.BPB: the listing said 57,710 bytes, the old 400ms-quiet-gap reader
     * declared the response finished at 57,558 -- the stream's last packet is a MORE, no LAST ever
     * arrived. capture13's AUTOS001 is the same file, complete: LAST, exactly its listed 69,513
     * bytes. (The captures on hand hold no cut-off AUTOS000; this AUTOS001 is the one that was cut.)
     */
    @Test
    fun `the cut-off AUTOS001 stream never completes, the complete one does`() {
        val autos = load("debugger-exports.txt").filter { it.request == "AUTOS001" }
        val cut = autos.single { it.source == "capture12" }
        assertEquals(57_558, cut.packets.sumOf { it.size - 1 })
        assertEquals(null, parse(cut))
        assertEquals(PftpResult.Content(ByteArray(69_513)), parse(autos.single { it.source == "capture13" }))
    }
}
