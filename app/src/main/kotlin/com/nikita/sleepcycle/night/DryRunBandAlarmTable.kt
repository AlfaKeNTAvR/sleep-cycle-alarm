package com.nikita.sleepcycle.night

// File purpose: the pure "what would the band's alarm table show" function for dry-run band commands. Dry
// run never sends a real SET/DISMISS, so there is no real table to read back - this simulates one instead,
// built from what was requested and confirmed as of the END of the previous tick, so a command sent this
// tick is only reflected starting next tick. That is what "confirmed after one tick" means: the same
// read-back-then-confirm flow BandAlarmDecision.kt runs against a real table runs unchanged against this one.

import com.nikita.sleepcycle.bridge.BandAlarmSlot

/**
 * The simulated band alarm table for a dry-run tick: a slot for whatever [NightState.confirmedBandAlarm]
 * already held (unless a dismiss for it is pending, in which case dry run treats that dismiss as already
 * having landed - dismissals confirm after one tick too), plus a slot for whatever [NightState.requestedBandAlarm]
 * is pending. Feeding this into [decideBandAlarmCommands] as this tick's `slots` promotes a pending request to
 * confirmed exactly the way a real read-back would.
 */
fun simulatedDryRunBandAlarmSlots(state: NightState): List<BandAlarmSlot> {
    val slots = mutableMapOf<String, BandAlarmSlot>()
    state.confirmedBandAlarm?.let { commitment ->
        if (commitment.title !in state.pendingDismissTitles) slots[commitment.title] = simulatedSlotFor(commitment, position = 0)
    }
    state.requestedBandAlarm?.let { commitment ->
        slots[commitment.title] = simulatedSlotFor(commitment, position = 1)
    }
    return slots.values.toList()
}

private fun simulatedSlotFor(commitment: BandAlarmCommitment, position: Int): BandAlarmSlot = BandAlarmSlot(
    position = position,
    enabled = true,
    hour = commitment.hour,
    minute = commitment.minute,
    title = commitment.title,
    smartWakeup = false,
    repetition = 0,
)
