package com.polarloopdebugger.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class PftpDecodeDispatchTest {

    @Test
    fun `directory paths always classify as DIRECTORY`() {
        assertEquals(PftpContentKind.DIRECTORY, classifyPftpPath("/"))
        assertEquals(PftpContentKind.DIRECTORY, classifyPftpPath("/U/0/AUTOS/"))
        assertEquals(PftpContentKind.DIRECTORY, classifyPftpPath("/U/0/20240310/"))
        assertEquals(PftpContentKind.DIRECTORY, classifyPftpPath("/U/0/20240310/ACT/"))
    }

    @Test
    fun `REST paths classify as REST_JSON`() {
        assertEquals(PftpContentKind.REST_JSON, classifyPftpPath("/REST/SERVICE.API"))
        assertEquals(PftpContentKind.REST_JSON, classifyPftpPath("/REST/SLEEP.API"))
    }

    @Test
    fun `AUTOS files classify as AUTOS regardless of index width`() {
        assertEquals(PftpContentKind.AUTOS, classifyPftpPath("/U/0/AUTOS/AUTOS000.BPB"))
        assertEquals(PftpContentKind.AUTOS, classifyPftpPath("/U/0/AUTOS/AUTOS008.BPB"))
        assertEquals(PftpContentKind.AUTOS, classifyPftpPath("/U/0/AUTOS/AUTOS1234.BPB"))
    }

    @Test
    fun `SLEEPRES BPB classifies as SLEEP`() {
        assertEquals(PftpContentKind.SLEEP, classifyPftpPath("/U/0/20240310/SLEEP/SLEEPRES.BPB"))
    }

    @Test
    fun `ASAMPLn BPB under ACT classifies as ACTIVITY`() {
        assertEquals(PftpContentKind.ACTIVITY, classifyPftpPath("/U/0/20240310/ACT/ASAMPL0.BPB"))
        assertEquals(PftpContentKind.ACTIVITY, classifyPftpPath("/U/0/20240310/ACT/ASAMPL12.BPB"))
        // ASAMPL-named file outside /ACT/ shouldn't match -- the real signal is the directory, not just the name.
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/ASAMPL0.BPB"))
    }

    @Test
    fun `DSUM BPB classifies as DAILY_SUMMARY`() {
        assertEquals(PftpContentKind.DAILY_SUMMARY, classifyPftpPath("/U/0/20240310/DSUM/DSUM.BPB"))
    }

    @Test
    fun `NR BPB classifies as NIGHTLY_RECHARGE`() {
        assertEquals(PftpContentKind.NIGHTLY_RECHARGE, classifyPftpPath("/U/0/20240310/NR/NR.BPB"))
    }

    @Test
    fun `SKINTEMP TEMPCONT BPB classifies as SKIN_TEMPERATURE`() {
        assertEquals(PftpContentKind.SKIN_TEMPERATURE, classifyPftpPath("/U/0/20240310/SKINTEMP/TEMPCONT.BPB"))
        // SKINCONT (skin contact) has an identical filename but is a different, undecoded schema -- GENERIC.
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/SKINCONT/TEMPCONT.BPB"))
    }

    @Test
    fun `disk space sentinel path classifies as DISK_SPACE`() {
        assertEquals(PftpContentKind.DISK_SPACE, classifyPftpPath(DISK_SPACE_PSEUDO_PATH))
    }

    @Test
    fun `everything else without a confirmed schema is GENERIC`() {
        // DEVICE.BPB has no confirmed decoder in this codebase yet -- only AUTOS/SLEEP do.
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/DEVICE.BPB"))
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/ACT/ID.BPB"))
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/S/PHYSDATA.BPB"))
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/OFF_TRIG.BIN"))
        // ID.BPB is a generic per-directory PFTP sync marker, not sleep content -- GENERIC, not SLEEP.
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/SLEEP/ID.BPB"))
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/SLEEPSCO/ID.BPB"))
        assertEquals(PftpContentKind.GENERIC, classifyPftpPath("/U/0/20240310/SLEEPWAK/SLEEW000.BPB"))
    }
}
