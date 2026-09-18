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
 *
 * While nothing of ours is on the simulated band, the table still carries one genuinely free slot. A table
 * with no usable slot at all is a band that can take no alarm (see `countUsableBandAlarmSlots`), and the
 * protocol rightly withholds every SET against such a table - which would leave a dry-run night with no band
 * alarm from its very first tick. One free slot models the owner's real band, whose single usable slot is
 * either free or holding our own alarm, so a dry run exercises the same single-slot protocol a real night does.
 */
fun simulatedDryRunBandAlarmSlots(state: NightState): List<BandAlarmSlot> {
    val slots = mutableMapOf<String, BandAlarmSlot>()
    state.confirmedBandAlarm?.let { commitment ->
        if (commitment.title !in state.pendingDismissTitles) slots[commitment.title] = simulatedSlotFor(commitment, position = 0)
    }
    state.requestedBandAlarm?.let { commitment ->
        slots[commitment.title] = simulatedSlotFor(commitment, position = 1)
    }
    if (slots.isEmpty()) return listOf(SIMULATED_FREE_SLOT)
    return slots.values.toList()
}

/** The one slot this app may use on the simulated band while nothing of ours is in it: disabled and untitled, the only shape Gadgetbridge's own picker claims. */
private val SIMULATED_FREE_SLOT = BandAlarmSlot(
    position = 2,
    enabled = false,
    hour = 0,
    minute = 0,
    title = null,
    smartWakeup = false,
    repetition = 0,
)

private fun simulatedSlotFor(commitment: BandAlarmCommitment, position: Int): BandAlarmSlot = BandAlarmSlot(
    position = position,
    enabled = true,
    hour = commitment.hour,
    minute = commitment.minute,
    title = commitment.title,
    smartWakeup = false,
    repetition = 0,
)
