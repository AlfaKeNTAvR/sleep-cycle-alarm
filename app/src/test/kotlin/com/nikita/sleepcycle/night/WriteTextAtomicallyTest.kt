package com.nikita.sleepcycle.night

// File purpose: the temp-file-then-rename write the small night stores share (ISSUES.md #13, round2 #5). The
// media fade record, the fade re-arm marker, the fired-nap count and the morning report used to be written in
// place, so a kill mid-write left a truncated file: for the fade, the owner's own volume was never put back.
// A kill mid-write cannot be staged in a unit test; what can be is the contract that makes it safe - the live
// file is only ever replaced whole, and a write that fails leaves the previous content readable.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WriteTextAtomicallyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a write replaces the whole file and leaves no temp file behind`() {
        val target = File(dir, "media_fade.txt")
        target.writeText("10 4 2026-10-03T01:00:00Z and a much longer old line")

        writeTextAtomically(target, "10 4 2026-10-03T01:00:00Z parked")

        assertEquals("10 4 2026-10-03T01:00:00Z parked", target.readText())
        assertFalse(File(dir, "media_fade.txt.tmp").exists())
    }

    @Test
    fun `a write that fails leaves the previous content readable`() {
        val target = File(dir, "nap_alarms_used.txt")
        target.writeText("1")
        // The write cannot complete: a directory sits where its temp file goes.
        File(dir, "nap_alarms_used.txt.tmp").mkdirs()

        assertThrows<Exception> { writeTextAtomically(target, "2") }

        assertEquals("1", target.readText())
    }
}
