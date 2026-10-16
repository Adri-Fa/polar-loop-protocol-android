package com.polarloopdebugger.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Log lines end up in bugreports: none the library writes may carry a full Bluetooth address. */
class LogAddressMaskingTest {

    @Test
    fun `an address is masked down to its last byte, in either case`() {
        assertEquals("connecting to XX:XX:XX:XX:XX:5F...", maskBluetoothAddresses("connecting to 0A:1B:2C:3D:4E:5F..."))
        assertEquals("XX:XX:XX:XX:XX:5f, XX:XX:XX:XX:XX:A0", maskBluetoothAddresses("0a:1b:2c:3d:4e:5f, 00:11:22:33:44:A0"))
    }

    @Test
    fun `times, paths, hex dumps, UUIDs and masked addresses are left alone`() {
        for (s in listOf(
            "at 10:15:08 GET /U/0/20240101/ACT/",
            "raw=0A 1B 2C 3D 4E 5F",
            "CCCD fb005c52-02e7-f387-1cad-8acd2d8df0c8 handle=0x0034 value=01 00",
            "connecting to XX:XX:XX:XX:XX:5F...",
        )) assertEquals(s, maskBluetoothAddresses(s))
    }

    private class LogCall(val file: String, val callee: String, val args: String) {
        override fun toString() = "$file: $callee($args)"
    }

    /** Every `log(...)`/`onLog(...)` call under src/main, with its whole argument list (calls can span lines). */
    private val logCalls: List<LogCall> by lazy {
        val call = Regex("""(?<!fun )((?:[A-Za-z_][A-Za-z0-9_]*|this@[A-Za-z]+)\.)?\b(onLog|log)\(""")
        File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            val code = file.readLines()
                .filter { line -> line.trim().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") } }
                .joinToString("\n")
            call.findAll(code).map { m -> LogCall(file.name, m.value.dropLast(1), argsFrom(code, m.range.last + 1)) }
        }.toList()
    }

    // Text from [start] up to the parenthesis that closes the call opened just before it.
    private fun argsFrom(code: String, start: Int): String {
        var depth = 1
        for (i in start until code.length) {
            if (code[i] == '(') depth++
            if (code[i] == ')' && --depth == 0) return code.substring(start, i)
        }
        error("unclosed call at $start")
    }

    @Test
    fun `listener onLog is only reached through the masking choke point`() {
        assertEquals(
            setOf(
                // PolarGattClient.log, the one place a line leaves the client...
                "PolarGattClient.kt: listener.onLog(maskBluetoothAddresses(message))",
                // ...and ConnectionManager's fan-out of those same lines to its listeners.
                "ConnectionManager.kt: it.onLog(message)",
            ),
            logCalls.filter { it.callee.endsWith("onLog") }.map { it.toString() }.toSet(),
        )
    }

    @Test
    fun `no log call in src-main passes an unmasked address`() {
        assertTrue("scan found too few calls: $logCalls", logCalls.size > 25)
        assertTrue(logCalls.any { "connecting to" in it.args })
        val mac = Regex("""([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")
        // BluetoothDevice prints as its address, so a device in a template counts as one too.
        val addressSource = Regex("""(?i)address|\$\{?device\b|\.device\b""")
        for (c in logCalls) {
            val unmasked = c.args.replace(Regex("""maskBluetoothAddresses\([^()]*(\([^()]*\))*[^()]*\)"""), "")
            assertTrue("literal address in $c", !mac.containsMatchIn(unmasked))
            assertTrue("unmasked address in $c", !addressSource.containsMatchIn(unmasked))
        }
    }
}
