package com.nikita.sleepcycle.bridge

// File purpose: pure mapping from raw Gadgetbridge `ALARM` rows to BandAlarmSlot, plus slot-availability
// helpers used by the band alarm protocol and the setup checklist. Facts verified against Gadgetbridge
// 0.94.0's DeviceAlarmReceiver.java and a real export: SET_ALARM claims the first slot that is disabled
// AND has a null/empty title, and only logs an error (the sender is never told) when none is free.
// DISMISS_ALARM by title matches by substring, disables the slot and clears its title, freeing it again.

/** One row of the exported `ALARM` table, in plain types so it needs no Android/SQLite import to test. */
data class RawBandAlarmRow(
    val deviceId: Int,
    val position: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val title: String?,
    val smartWakeup: Boolean,
    val repetition: Int
)

/** One alarm slot on the band, as Gadgetbridge sees it. */
data class BandAlarmSlot(
    val position: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val title: String?,
    val smartWakeup: Boolean,
    val repetition: Int
)

/** Keeps rows for [deviceId] and maps the rest to [BandAlarmSlot]. */
fun mapRawBandAlarmRowsToSlots(rows: List<RawBandAlarmRow>, deviceId: Int): List<BandAlarmSlot> =
    rows.filter { it.deviceId == deviceId }.map {
        BandAlarmSlot(
            position = it.position,
            enabled = it.enabled,
            hour = it.hour,
            minute = it.minute,
            title = it.title,
            smartWakeup = it.smartWakeup,
            repetition = it.repetition
        )
    }

/** A slot is free exactly when SET_ALARM would claim it: disabled, with a null or empty title. */
private fun isFreeBandAlarmSlot(slot: BandAlarmSlot): Boolean = !slot.enabled && slot.title.isNullOrEmpty()

/** How many alarm slots SET_ALARM could claim right now. Drives the setup checklist's shortage message. */
fun countFreeBandAlarmSlots(slots: List<BandAlarmSlot>): Int = slots.count(::isFreeBandAlarmSlot)

/** Free slots plus slots already carrying one of [ourTitles]: both can be (re)claimed by dismiss-then-set. */
fun countFreeOrOwnedBandAlarmSlots(slots: List<BandAlarmSlot>, ourTitles: Set<String>): Int =
    slots.count { isFreeBandAlarmSlot(it) || it.title in ourTitles }

/** Enabled alarms that are not ours, e.g. a smart alarm the owner set by hand: they will still ring regardless of this app. */
fun listOtherEnabledBandAlarms(slots: List<BandAlarmSlot>, ourTitles: Set<String>): List<BandAlarmSlot> =
    slots.filter { it.enabled && it.title !in ourTitles }

/** One plain-English description of an alarm slot that is not ours, for the setup checklist. */
fun describeOtherBandAlarm(slot: BandAlarmSlot): String {
    val time = "%02d:%02d".format(slot.hour, slot.minute)
    val title = slot.title?.takeIf { it.isNotEmpty() }
    return if (title != null) "$time ($title)" else time
}

/**
 * Slots whose title is not exactly one of [ourTitles] but CONTAINS one as a substring, e.g. "Backup SCA-A".
 * Our own reconciliation always matches by exact title (see BandAlarmDecision.kt), so it never confuses such
 * a slot for one of ours - but Gadgetbridge's own DISMISS_ALARM matches by substring, so the day we dismiss
 * "SCA-A" it will also clear this slot's title. The setup checklist surfaces this so it is fixed before bed,
 * not discovered as a mysteriously vanished alarm.
 */
fun findTitlesConflictingWithOurs(slots: List<BandAlarmSlot>, ourTitles: Set<String>): List<BandAlarmSlot> =
    slots.filter { slot ->
        val title = slot.title
        !title.isNullOrEmpty() && title !in ourTitles && ourTitles.any { title.contains(it) }
    }
