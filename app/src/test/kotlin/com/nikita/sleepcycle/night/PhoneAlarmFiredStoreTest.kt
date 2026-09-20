package com.nikita.sleepcycle.night

// File purpose: D3/F2/F6/H2 - mergeAlarmFiredStores is the pure half of the fix: PhoneAlarmReceiver now writes
// only four tiny independent files (never the whole NightState), and loadNightState always re-merges them
// in, so they are always the freshest values regardless of what stale copy a concurrent tick's own blob still
// carries.

import com.nikita.sleepcycle.engine.MAX_NAP_ALARMS
import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class PhoneAlarmFiredStoreTest {
    private fun state(
        phoneAlarmFiredFor: Instant? = null, wakeAlarmFiredAt: Instant? = null, napAlarmsUsed: Int = 0,
        lastNapAlarmFiredAt: Instant? = null
    ): NightState = NightState(
        startedAt = Instant.parse("2026-09-17T00:00:00Z"),
        settings = NightSettings(null, 5),
        lastPlan = null,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null,
        phoneAlarmFiredFor = phoneAlarmFiredFor,
        wakeAlarmFiredAt = wakeAlarmFiredAt,
        napAlarmsUsed = napAlarmsUsed,
        lastNapAlarmFiredAt = lastNapAlarmFiredAt
    )

    @Test
    fun `four absent stores leave the state's own values untouched`() {
        val original = state(
            phoneAlarmFiredFor = Instant.parse("2026-09-17T07:15:00Z"),
            wakeAlarmFiredAt = Instant.parse("2026-09-17T07:00:00Z"),
            napAlarmsUsed = 1,
            lastNapAlarmFiredAt = Instant.parse("2026-09-17T07:30:00Z")
        )

        val merged = mergeAlarmFiredStores(original, phoneAlarmFiredFor = null, wakeAlarmFiredAt = null, napAlarmsUsed = null, lastNapAlarmFiredAt = null)

        assertEquals(original, merged)
    }

    @Test
    fun `present store values always override the state's own, even when the state already has some`() {
        val original = state(
            phoneAlarmFiredFor = Instant.parse("2026-09-16T07:15:00Z"),
            wakeAlarmFiredAt = Instant.parse("2026-09-16T07:00:00Z"),
            napAlarmsUsed = 1,
            lastNapAlarmFiredAt = Instant.parse("2026-09-16T07:30:00Z")
        )
        val freshFired = Instant.parse("2026-09-17T08:07:00Z")
        val freshWake = Instant.parse("2026-09-17T07:00:00Z")
        val freshNap = Instant.parse("2026-09-17T07:30:00Z")

        val merged = mergeAlarmFiredStores(original, phoneAlarmFiredFor = freshFired, wakeAlarmFiredAt = freshWake, napAlarmsUsed = 2, lastNapAlarmFiredAt = freshNap)

        assertEquals(freshFired, merged.phoneAlarmFiredFor)
        assertEquals(freshWake, merged.wakeAlarmFiredAt)
        assertEquals(2, merged.napAlarmsUsed)
        assertEquals(freshNap, merged.lastNapAlarmFiredAt)
    }

    @Test
    fun `present store values fill in a state that had none`() {
        val freshFired = Instant.parse("2026-09-17T08:07:00Z")
        val freshNap = Instant.parse("2026-09-17T07:30:00Z")

        val merged = mergeAlarmFiredStores(state(), phoneAlarmFiredFor = freshFired, wakeAlarmFiredAt = null, napAlarmsUsed = 1, lastNapAlarmFiredAt = freshNap)

        assertEquals(freshFired, merged.phoneAlarmFiredFor)
        assertNull(merged.wakeAlarmFiredAt)
        assertEquals(1, merged.napAlarmsUsed)
        assertEquals(freshNap, merged.lastNapAlarmFiredAt)
    }

    @Test
    fun `no stores and no state values stay at their defaults`() {
        val merged = mergeAlarmFiredStores(state(), phoneAlarmFiredFor = null, wakeAlarmFiredAt = null, napAlarmsUsed = null, lastNapAlarmFiredAt = null)

        assertNull(merged.phoneAlarmFiredFor)
        assertNull(merged.wakeAlarmFiredAt)
        assertEquals(0, merged.napAlarmsUsed)
        assertNull(merged.lastNapAlarmFiredAt)
    }

    @Test
    fun `F2 a napAlarmsUsed above the cap is clamped defensively, whether it comes from the store or the state`() {
        val fromStore = mergeAlarmFiredStores(state(), phoneAlarmFiredFor = null, wakeAlarmFiredAt = null, napAlarmsUsed = MAX_NAP_ALARMS + 5, lastNapAlarmFiredAt = null)
        assertEquals(MAX_NAP_ALARMS, fromStore.napAlarmsUsed)

        val fromState = mergeAlarmFiredStores(
            state(napAlarmsUsed = MAX_NAP_ALARMS + 5), phoneAlarmFiredFor = null, wakeAlarmFiredAt = null, napAlarmsUsed = null, lastNapAlarmFiredAt = null
        )
        assertEquals(MAX_NAP_ALARMS, fromState.napAlarmsUsed)
    }
}
