package com.nikita.sleepcycle.night

// File purpose: P3 - the one pending-follow-up record (OutOfBedNudgeStore.kt) now says whether it is the nudge
// or the owner's own nap. A file written by the build before P3 holds a bare instant and must still read back
// as the nudge it was, so a nudge armed before an app update is not lost or misnamed after it.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PendingFollowUpRecordTest {
    private val at = Instant.parse("2026-09-30T11:25:00Z")

    @Test fun `a nap record reads back as the same nap`() {
        val nap = PendingFollowUp(FollowUpKind.NAP, at)

        assertEquals(nap, parsePendingFollowUp(formatPendingFollowUp(nap)))
    }

    @Test fun `a nudge record reads back as the same nudge`() {
        val nudge = PendingFollowUp(FollowUpKind.NUDGE, at)

        assertEquals(nudge, parsePendingFollowUp(formatPendingFollowUp(nudge)))
    }

    @Test fun `a bare instant written before P3 reads back as a nudge`() {
        assertEquals(PendingFollowUp(FollowUpKind.NUDGE, at), parsePendingFollowUp("2026-09-30T11:25:00Z\n"))
    }

    @Test fun `garbage reads back as nothing pending`() {
        assertNull(parsePendingFollowUp("NAP not-an-instant"))
    }
}
