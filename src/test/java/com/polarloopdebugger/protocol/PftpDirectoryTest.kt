package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PROTOCOL.md's frame 464 (root `/` listing) section gives the fully decoded name+size for every
 * entry but not the raw captured hex itself. This fixture is hand-encoded from those decoded
 * values against the confirmed `PbPFtpDirectory{repeated PbPFtpEntry entries=1}` /
 * `PbPFtpEntry{name=1,size=2}` schema (pftp_response.proto) -- same reconstruction rigor as the
 * AUTOS005.BPB fixture in PftpAutosDecoderTest, just without an independent total-length
 * cross-check since PROTOCOL.md doesn't cite one for this frame.
 */
class PftpDirectoryTest {

    @Test
    fun `decodes root listing into 16 entries with correct sizes and directory flags`() {
        val fixture = hex(
            """
            0A 0D 0A 09 44 41 54 41 43 4F 4C 4C 2F 10 00
            0A 12 0A 0C 45 52 52 4F 52 4C 4F 47 2E 42 50 42 10 9A 90 01
            0A 07 0A 03 4E 52 2F 10 00
            0A 10 0A 0C 4F 46 46 5F 54 52 49 47 2E 42 49 4E 10 68
            0A 09 0A 05 52 45 53 54 2F 10 00
            0A 0E 0A 0A 53 44 4C 4F 47 53 2E 42 50 42 10 18
            0A 0B 0A 07 53 44 4C 4F 47 53 2F 10 00
            0A 15 0A 10 53 4B 49 4E 5F 54 45 4D 50 5F 42 4C 2E 42 49 4E 10 DC 02
            0A 0F 0A 0B 53 59 53 43 4F 4E 46 2E 42 50 42 10 08
            0A 0E 0A 0A 53 59 53 4C 4F 47 2E 42 50 42 10 08
            0A 08 0A 04 53 59 53 2F 10 00
            0A 06 0A 02 55 2F 10 00
            0A 11 0A 0C 50 52 4F 44 44 41 54 41 2E 42 49 4E 10 98 01
            0A 0F 0A 0A 44 45 56 49 43 45 2E 42 50 42 10 DB 01
            0A 0F 0A 0A 53 59 53 4C 4F 47 2E 54 58 54 10 BF 22
            0A 10 0A 0C 50 4D 44 46 49 4C 45 53 2E 54 58 54 10 00
            """.replace("\n", " "),
        )

        val expected = listOf(
            PftpEntry("DATACOLL/", 0, true),
            PftpEntry("ERRORLOG.BPB", 18458, false),
            PftpEntry("NR/", 0, true),
            PftpEntry("OFF_TRIG.BIN", 104, false),
            PftpEntry("REST/", 0, true),
            PftpEntry("SDLOGS.BPB", 24, false),
            PftpEntry("SDLOGS/", 0, true),
            PftpEntry("SKIN_TEMP_BL.BIN", 348, false),
            PftpEntry("SYSCONF.BPB", 8, false),
            PftpEntry("SYSLOG.BPB", 8, false),
            PftpEntry("SYS/", 0, true),
            PftpEntry("U/", 0, true),
            PftpEntry("PRODDATA.BIN", 152, false),
            PftpEntry("DEVICE.BPB", 219, false),
            PftpEntry("SYSLOG.TXT", 4415, false),
            PftpEntry("PMDFILES.TXT", 0, false),
        )

        assertEquals(expected, decodeDirectoryListing(fixture))
    }

    /**
     * Regression for a real first-device-test bug: every actual PFTP GET response is prefixed
     * with one [PFTP_RESPONSE_ENVELOPE_MARKER] byte before its protobuf payload -- undocumented
     * anywhere before this was found. Feeding the raw response straight to [decodeDirectoryListing]
     * (as HistoricalSyncActivity used to) makes the first field read as tag `0x02` -> field number
     * 0, which no real PbPFtpDirectory can ever produce (protobuf field numbers start at 1) --
     * decodeDirectoryListing correctly rejects it, but the exception used to escape all the way to
     * a swallowed `decoded: null` instead of a real listing. This is exactly why the real capture
     * showed "DIRECTORY LISTINGS: 0" despite 8 directory paths being queried.
     *
     * Structurally matches the real capture's raw bytes; the file size below is a placeholder,
     * not the real device's file size.
     */
    @Test
    fun `raw directory response is rejected until the envelope byte is stripped`() {
        // Real /U/0/AUTOS/ response bytes verbatim from the raw capture, except the size varint
        // (synthetic placeholder, see class doc).
        val raw = hex("02 0A 12 0A 0C 41 55 54 4F 53 30 30 31 2E 42 50 42 10 80 FA 01")

        assertThrows(IllegalArgumentException::class.java) { decodeDirectoryListing(raw) }

        val stripped = raw.copyOfRange(1, raw.size)
        assertEquals(listOf(PftpEntry("AUTOS001.BPB", 32000, false)), decodeDirectoryListing(stripped))
    }

    /**
     * Same capture, a date-folder response -- a real 5-entry listing that also only parses once
     * the envelope byte is stripped. This is the folder Historical Sync would have shown as real
     * "directory listing, 5 entries" instead of `raw`/null, and it's what a corrected re-run
     * against this exact device state produces.
     */
    @Test
    fun `raw date-folder response decodes to 5 subdirectories once stripped`() {
        val raw = hex(
            """
            02 0A 08 0A 04 41 43 54 2F 10 00 0A 09 0A 05 44 53 55 4D 2F 10 00
            0A 0D 0A 09 53 4B 49 4E 43 4F 4E 54 2F 10 00
            0A 0D 0A 09 53 4B 49 4E 54 45 4D 50 2F 10 00
            0A 0D 0A 09 53 4C 45 45 50 57 41 4B 2F 10 00
            """.replace("\n", " "),
        )

        val stripped = raw.copyOfRange(1, raw.size)
        val expected = listOf(
            PftpEntry("ACT/", 0, true),
            PftpEntry("DSUM/", 0, true),
            PftpEntry("SKINCONT/", 0, true),
            PftpEntry("SKINTEMP/", 0, true),
            PftpEntry("SLEEPWAK/", 0, true),
        )
        assertEquals(expected, decodeDirectoryListing(stripped))
    }
}
