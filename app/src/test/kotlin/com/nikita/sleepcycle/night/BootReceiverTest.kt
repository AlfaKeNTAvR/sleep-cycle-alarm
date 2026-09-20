package com.nikita.sleepcycle.night

// File purpose: F3 - the reboot-recovery test the first fix round was asked for and did not write.
// BootReceiver.handleBoot itself needs a real Context/AlarmManager and cannot be exercised from a plain JVM
// test, so shouldResumeNightServiceOnBoot is BootReceiver.kt's own pure extraction of the one decision this
// test cares about: does tracking resume at all, regardless of what the persisted plan's wakeAt says. Mirrors
// PhoneAlarmArmingTest's style for shouldArmPhoneAlarm.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BootReceiverTest {
    private fun state(lastPlan: AlarmPlan?): NightState = NightState(
        startedAt = Instant.parse("2026-09-16T21:00:00Z"),
        settings = NightSettings(deadline = null, pickedCycles = 5),
        lastPlan = lastPlan,
        lastSyncAt = null,
        lastSyncOk = null,
        lastSegments = emptyList(),
        lastExportFileModifiedAt = null,
        lastSyncFailureCause = null
    )

    @Test
    fun `F3 a night state whose wakeAt is already in the past still resumes ticking`() {
        val pastWakePlan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            wakeAt = Instant.parse("2026-09-17T06:30:00Z"),
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T22:00:00Z"),
            onsetIsProjected = false,
            reason = "r"
        )

        assertTrue(shouldResumeNightServiceOnBoot(state(pastWakePlan)))
    }

    @Test
    fun `F3 an old-build state file whose plan decoded with wakeAt null still resumes ticking`() {
        val nullWakeAtPlan = AlarmPlan(
            mode = AlarmMode.FULL_CYCLES,
            wakeAt = null,
            cycles = 5,
            referenceOnset = Instant.parse("2026-09-16T22:00:00Z"),
            onsetIsProjected = false,
            reason = "r"
        )

        assertTrue(shouldResumeNightServiceOnBoot(state(nullWakeAtPlan)))
        // A state file so old the plan itself failed to decode at all (lastPlan == null) resumes just the same.
        assertTrue(shouldResumeNightServiceOnBoot(state(lastPlan = null)))
    }

    @Test
    fun `no night in progress does not resume ticking`() {
        assertFalse(shouldResumeNightServiceOnBoot(null))
    }
}
