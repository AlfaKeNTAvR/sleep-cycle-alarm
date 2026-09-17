package com.nikita.sleepcycle.night

// File purpose: D3 - mergePhoneAlarmFiredFor is the pure half of the fix: markPhoneAlarmFired now writes only
// a tiny independent file (never the whole NightState), and loadNightState always re-merges it in, so it is
// always the freshest value regardless of what stale copy a concurrent tick's own blob still carries.

import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PhoneAlarmFiredStoreTest {
    private fun state(phoneAlarmFiredFor: Instant?): NightState = NightState(
        startedAt = Instant.parse("2026-09-17T00:00:00Z"),
        settings = NightSettings(null, 5, false),
        lastPlan = null,
        requestedBandAlarm = null,
        confirmedBandAlarm = null,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null,
        phoneAlarmFiredFor = phoneAlarmFiredFor
    )

    @Test
    fun `a null store leaves the state's own value untouched`() {
        val original = state(phoneAlarmFiredFor = Instant.parse("2026-09-17T07:15:00Z"))

        val merged = mergePhoneAlarmFiredFor(original, store = null)

        assertEquals(original, merged)
    }

    @Test
    fun `a present store value always overrides the state's own, even when the state already has one`() {
        val staleInBlob = Instant.parse("2026-09-16T07:15:00Z")
        val freshInStore = Instant.parse("2026-09-17T08:07:00Z")
        val original = state(phoneAlarmFiredFor = staleInBlob)

        val merged = mergePhoneAlarmFiredFor(original, store = freshInStore)

        assertEquals(freshInStore, merged.phoneAlarmFiredFor)
    }

    @Test
    fun `a present store value fills in a state that had none`() {
        val freshInStore = Instant.parse("2026-09-17T08:07:00Z")
        val original = state(phoneAlarmFiredFor = null)

        val merged = mergePhoneAlarmFiredFor(original, store = freshInStore)

        assertEquals(freshInStore, merged.phoneAlarmFiredFor)
    }

    @Test
    fun `no store and no state value stays null`() {
        val merged = mergePhoneAlarmFiredFor(state(phoneAlarmFiredFor = null), store = null)

        assertNull(merged.phoneAlarmFiredFor)
    }
}
