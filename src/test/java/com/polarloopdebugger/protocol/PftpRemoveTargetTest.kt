package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.random.Random

/**
 * The only two things a REMOVE can name. Dates here are synthetic (a leap day, a year end), not
 * from any capture.
 */
class PftpRemoveTargetTest {

    @Test
    fun `a day folder with a real calendar date is accepted, and only as itself`() {
        val t = PftpRemoveTarget.of("/U/0/20240229/")
        assertTrue(t is PftpRemoveTarget.DayFolder)
        assertEquals("/U/0/20240229/", t!!.path)
        assertEquals(LocalDate.of(2024, 2, 29), (t as PftpRemoveTarget.DayFolder).date)
        assertEquals("REMOVE /U/0/20240229/", t.toString())
        assertNotNull(PftpRemoveTarget.of("/U/0/20991231/"))
        assertNotNull(PftpRemoveTarget.of("/U/0/00010101/"))
    }

    @Test
    fun `a 24-7 file with exactly three digits is accepted`() {
        val t = PftpRemoveTarget.of("/U/0/AUTOS/AUTOS004.BPB")
        assertTrue(t is PftpRemoveTarget.AutosFile)
        assertEquals(4, (t as PftpRemoveTarget.AutosFile).index)
        assertEquals("/U/0/AUTOS/AUTOS004.BPB", t.path)
        assertEquals(0, (PftpRemoveTarget.of("/U/0/AUTOS/AUTOS000.BPB") as PftpRemoveTarget.AutosFile).index)
        assertEquals(999, (PftpRemoveTarget.of("/U/0/AUTOS/AUTOS999.BPB") as PftpRemoveTarget.AutosFile).index)
    }

    @Test
    fun `everything else is rejected explicitly`() {
        val rejected = listOf(
            "/U/0/S/", "/U/0/S/20240229/", "/U/0/S/20240229/E/120000/", // sessions
            "/SYS/", "/SYS/DEVICE.BPB", "/DEVICE.BPB", "DEVICE.BPB", "/U/0/USERID.BPB", "USERID.BPB",
            "/U/0/", "/U/0", "/U/", "/", "",
            "/U/0/../20240229/", "/U/0/20240229/../", "/U/0/AUTOS/../AUTOS004.BPB", "..", "/U/0/AUTOS/..",
            "/u/0/20240229/", "/U/0/autos/AUTOS004.BPB", "/U/0/AUTOS/autos004.bpb", "/U/0/AUTOS/AUTOS004.bpb",
            "/U/0/20240229/ ", "/U/0/20240229//", "/U/0/20240229/DSUM/", "/U/0/20240229/DSUM/DSUM.BPB", "/U/0/20240229",
            "/U/0/AUTOS/AUTOS004.BPB ", "/U/0/AUTOS/AUTOS004.BPB/", "/U/0/AUTOS/AUTOS004.BPBX", "/U/0/AUTOS/AUTOS004",
            "/U/0/AUTOS/AUTOS04.BPB", "/U/0/AUTOS/AUTOS0004.BPB", "/U/0/AUTOS/", "/U/0/AUTOS", "/U/0/AUTOS/AUTOS.BPB",
            "/U/0/20261340/", "/U/0/20260230/", "/U/0/20230229/", "/U/0/20260001/", "/U/0/20260100/", "/U/0/2026102/", "/U/0/202610200/",
            "/U/1/20240229/", "/U/0/AUTOS/AUTOS004.BPB\u0000", "\u0000/U/0/20240229/", " /U/0/20240229/", "/U/0/２０２４０２２９/",
            "/U/0/20240229/\n", "/U/0/AUTOS/AUTOS00４.BPB", "/U/0/AUTOS/AUTOS-04.BPB", "/U/0/AUTOS/AUTOS+04.BPB",
        )
        for (p in rejected) assertNull("accepted: '$p'", PftpRemoveTarget.of(p))
    }

    private val strictDay = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)

    /** Independent of the implementation: what a path must be for a REMOVE of it to be allowed. */
    private fun shouldAccept(p: String): Boolean {
        Regex("/U/0/([0-9]{8})/").matchEntire(p)?.let { m ->
            return try { LocalDate.parse(m.groupValues[1], strictDay); true } catch (e: Exception) { false }
        }
        return Regex("/U/0/AUTOS/AUTOS[0-9]{3}\\.BPB").matches(p)
    }

    @Test
    fun `thousands of random paths are rejected, and mutations of valid ones only pass when still exactly valid`() {
        val random = Random(1404)
        val alphabet = "/U0AUTOSBPB.0123456789abcdefghijklmnopqrstuvwxyzSYSE_ -\u0000é"
        var accepted = 0
        repeat(5000) {
            val p = String(CharArray(random.nextInt(0, 40)) { alphabet[random.nextInt(alphabet.length)] })
            val t = PftpRemoveTarget.of(p)
            assertEquals("random '$p'", shouldAccept(p), t != null)
            if (t != null) { accepted++; assertEquals(p, t.path) }
        }
        repeat(1000) {
            val p = String(CharArray(random.nextInt(0, 300)) { random.nextInt(0, 0x800).toChar() })
            assertNull("unicode '$p'", PftpRemoveTarget.of(p))
        }
        // One-character mutations of the two valid shapes: insert, delete or replace.
        val seeds = listOf("/U/0/20240229/", "/U/0/AUTOS/AUTOS004.BPB")
        var mutantsAccepted = 0
        repeat(5000) {
            val s = seeds[random.nextInt(seeds.size)]
            val i = random.nextInt(s.length + 1)
            val c = alphabet[random.nextInt(alphabet.length)]
            val p = when (random.nextInt(3)) {
                0 -> s.substring(0, i) + c + s.substring(i)
                1 -> if (i < s.length) s.removeRange(i, i + 1) else s
                else -> if (i < s.length) s.substring(0, i) + c + s.substring(i + 1) else s
            }
            val t = PftpRemoveTarget.of(p)
            assertEquals("mutant '$p'", shouldAccept(p), t != null)
            if (t != null) { mutantsAccepted++; assertEquals(p, t.path) }
        }
        // The accepted mutants are the digit swaps that still spell a real date or a 3-digit index.
        assertTrue("random accepted $accepted", accepted < 5)
        assertTrue("mutants accepted $mutantsAccepted", mutantsAccepted in 1..2500)
    }

    @Test
    fun `targets with the same path are equal`() {
        assertEquals(PftpRemoveTarget.of("/U/0/AUTOS/AUTOS004.BPB"), PftpRemoveTarget.of("/U/0/AUTOS/AUTOS004.BPB"))
        assertTrue(PftpRemoveTarget.of("/U/0/20240229/") != PftpRemoveTarget.of("/U/0/20240228/"))
    }
}
