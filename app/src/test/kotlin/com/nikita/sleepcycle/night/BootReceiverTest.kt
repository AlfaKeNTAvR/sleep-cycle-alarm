package com.nikita.sleepcycle.night

// File purpose: F3 - the reboot-recovery test the first fix round was asked for and did not write.
// BootReceiver.handleBoot itself needs a real Context/AlarmManager and cannot be exercised from a plain JVM
// test, so shouldResumeNightServiceOnBoot is BootReceiver.kt's own pure extraction of the one decision this
// test cares about: does tracking resume at all, regardless of what the persisted plan's wakeAt says. Mirrors
// PhoneAlarmArmingTest's style for shouldArmPhoneAlarm.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.EngineConfig
import com.nikita.sleepcycle.engine.NightSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---- J5 (owner-reported, 2026-09-21): restoredOutOfBedNudgeAt, the overdue-nudge decision ---------------

    private val config = EngineConfig()

    @Test
    fun `J5 a nudge overdue by a minute at boot is re-armed shortly after boot, not dropped`() {
        // The owner's own traced sequence: the morning alarm fires at 06:30 and persists a nudge for 06:45, the
        // phone powers off at 06:40 and boots at 06:46, and the band reads awake from 06:31 onward. Pre-J5 the
        // nudge was restored only while still in the future, so this one was dropped outright - and nothing else
        // can ring, because the morning alarm has already fired and rule 7's AWAKE branch therefore produces no
        // target of its own. The promised follow-up simply never came.
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")
        val bootedAt = Instant.parse("2026-09-21T06:46:00Z")

        assertEquals(Instant.parse("2026-09-21T06:48:00Z"), restoredOutOfBedNudgeAt(pendingNudgeAt, bootedAt, config))
    }

    @Test
    fun `J5 a nudge still in the future at boot is restored unchanged, at its own original instant`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")
        val bootedAt = Instant.parse("2026-09-21T06:41:00Z")

        assertEquals(pendingNudgeAt, restoredOutOfBedNudgeAt(pendingNudgeAt, bootedAt, config))
    }

    @Test
    fun `J5 a nudge overdue by exactly the out-of-bed delay is still restored - the bound is inclusive`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")
        val bootedAt = pendingNudgeAt.plus(config.outOfBedDelay)

        assertEquals(bootedAt.plus(config.minAlarmLead), restoredOutOfBedNudgeAt(pendingNudgeAt, bootedAt, config))
    }

    @Test
    fun `J5 a nudge more overdue than the out-of-bed delay is dropped deliberately, never rung hours later`() {
        val pendingNudgeAt = Instant.parse("2026-09-21T06:45:00Z")

        assertNull(restoredOutOfBedNudgeAt(pendingNudgeAt, pendingNudgeAt.plus(config.outOfBedDelay).plusSeconds(1), config))
        // The case the bound really exists for: a phone left off for hours must not boot into an unexplained ring.
        assertNull(restoredOutOfBedNudgeAt(pendingNudgeAt, Instant.parse("2026-09-21T12:00:00Z"), config))
    }
}
