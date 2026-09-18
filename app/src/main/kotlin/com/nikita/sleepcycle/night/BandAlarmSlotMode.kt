package com.nikita.sleepcycle.night

// File purpose: which band alarm protocol applies this tick, decided from the alarm table alone - never from
// a setting the owner could get wrong. Gadgetbridge picks the slot itself (the first that is disabled AND
// untitled), so we cannot target one; all we can do is count how many slots are usable at all. A slot the
// band forces to be a smart alarm is never usable (see BandAlarmMapping.kt's header - position 0 on the
// owner's Honor Band 5 is forced smart and is excluded there), which is why the owner's band has exactly one
// usable slot and the single-slot protocol exists.

import com.nikita.sleepcycle.bridge.BandAlarmSlot
import com.nikita.sleepcycle.bridge.countUsableBandAlarmSlots

/** Below this many usable slots, the alarm cannot be moved by setting the replacement before clearing the old one. Shared with the setup check, which warns about a one-slot band before bed. */
const val MIN_SLOTS_FOR_ALTERNATING_TITLES = 2

/** Which band alarm protocol this tick runs, from the table alone (see [chooseBandAlarmSlotMode]). */
enum class BandAlarmSlotMode {
    /** Two or more usable slots: a move SETs the replacement under the other title and only dismisses the old one once the new one is confirmed. */
    ALTERNATING_TITLES,

    /** Exactly one usable slot: a move must DISMISS our own title and re-SET it under the same title in the same tick, so the band is briefly without an alarm. */
    SINGLE_SLOT,

    /**
     * No table could be read this tick, so nothing is known about the slots: an existing commitment is frozen
     * rather than chased, and is never dismissed to make room for a new time (BandAlarmBlindMode.kt). The one
     * dismissal that still goes out blind is the FINISHED teardown in BandAlarmDecision.kt: once the engine
     * wants no alarm at all there is nothing left to protect, and a stale title left on the band would ring
     * tomorrow morning.
     */
    BLIND
}

/**
 * The protocol for this tick: [BandAlarmSlotMode.BLIND] when [slots] is null (no table this tick), otherwise
 * by [countUsableBandAlarmSlots] - free non-smart slots plus the ones holding one of our own ENABLED alarms,
 * which we can reclaim by dismiss-then-set. Zero usable slots also lands here as [BandAlarmSlotMode.SINGLE_SLOT];
 * that mode's own [NO_FREE_SLOT][BandAlarmOutcome.NO_FREE_SLOT] guard is what withholds a SET nothing could place.
 */
fun chooseBandAlarmSlotMode(slots: List<BandAlarmSlot>?): BandAlarmSlotMode {
    if (slots == null) return BandAlarmSlotMode.BLIND
    val usableSlots = countUsableBandAlarmSlots(slots, ALL_BAND_ALARM_TITLES.toSet())
    return if (usableSlots >= MIN_SLOTS_FOR_ALTERNATING_TITLES) {
        BandAlarmSlotMode.ALTERNATING_TITLES
    } else {
        BandAlarmSlotMode.SINGLE_SLOT
    }
}
