package com.nikita.sleepcycle.bridge

// File purpose: pure mapping from raw Gadgetbridge `ALARM` rows to BandAlarmSlot, plus slot-availability
// helpers used by the band alarm protocol and the setup checklist. Facts verified against Gadgetbridge
// 0.94.0's DeviceAlarmReceiver.java and a real export: SET_ALARM claims the first slot that is disabled
// AND has a null/empty title, and only logs an error (the sender is never told) when none is free.
// DISMISS_ALARM by title matches by substring, disables the slot and clears its title, freeing it again.
// DeviceAlarmReceiver.updateAlarm only ever writes enabled/hour/minute/repetition/title - it never touches
// SMART_WAKEUP - so a slot that once held a smart alarm keeps that flag (and its wake window) forever, even
// after we clear its title and reuse it. Verified on the owner's real Honor Band 5 export: position 0 always
// reads SMART_WAKEUP=1 - Gadgetbridge's own HuaweiCoordinator.forcedSmartWakeup forces the smart checkbox on
// for alarm position 0 on this device family and the owner cannot uncheck it in Gadgetbridge's UI - so this
// slot can never be a normal alarm; it must be parked (titled, left disabled) rather than "fixed".

/** One row of the exported `ALARM` table, in plain types so it needs no Android/SQLite import to test. */
data class RawBandAlarmRow(
    val deviceId: Int,
    val position: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val title: String?,
    val smartWakeup: Boolean,
    val repetition: Int,
    /** The band's own smart-wakeup window in minutes (`SMART_WAKEUP_INTERVAL`), or null when the table does not report one. Only meaningful while [smartWakeup] is true. */
    val smartWakeupWindowMinutes: Int? = null
)

/** One alarm slot on the band, as Gadgetbridge sees it. */
data class BandAlarmSlot(
    val position: Int,
    val enabled: Boolean,
    val hour: Int,
    val minute: Int,
    val title: String?,
    val smartWakeup: Boolean,
    val repetition: Int,
    /** The band's own smart-wakeup window in minutes, or null when the table does not report one. Only meaningful while [smartWakeup] is true. */
    val smartWakeupWindowMinutes: Int? = null
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
            repetition = it.repetition,
            smartWakeupWindowMinutes = it.smartWakeupWindowMinutes
        )
    }

/** [slot] looks free the way SET_ALARM decides it - disabled, with a null or empty title - regardless of the smart flag. Only [isFreeBandAlarmSlot] and [listPoachableSmartWakeupBandAlarmSlots] should call this directly. */
private fun isDisabledAndUntitled(slot: BandAlarmSlot): Boolean = !slot.enabled && slot.title.isNullOrEmpty()

/**
 * A slot is usable by us exactly when SET_ALARM would claim it AND it is not one of the band's own
 * smart-wakeup slots: a smart slot can never be a normal alarm (see this file's header), so it is excluded
 * here exactly as if it already carried a foreign title, even though its own title is blank.
 */
private fun isFreeBandAlarmSlot(slot: BandAlarmSlot): Boolean = isDisabledAndUntitled(slot) && !slot.smartWakeup

/** How many alarm slots SET_ALARM could claim right now, excluding any smart-wakeup slot. Drives the setup checklist's shortage message. */
fun countFreeBandAlarmSlots(slots: List<BandAlarmSlot>): Int = slots.count(::isFreeBandAlarmSlot)

/** Free slots plus slots already carrying one of [ourTitles], excluding a smart-wakeup slot either way: both can be (re)claimed by dismiss-then-set, a smart slot never can. */
fun countFreeOrOwnedBandAlarmSlots(slots: List<BandAlarmSlot>, ourTitles: Set<String>): Int =
    slots.count { isFreeBandAlarmSlot(it) || (it.title in ourTitles && !it.smartWakeup) }

/**
 * Smart-wakeup slots that SET_ALARM could still claim right now (disabled, untitled) - the live danger this
 * check exists for: such a slot looks exactly like a normal free slot to Gadgetbridge's own picker (first
 * disabled AND untitled), but the band decides on its own to buzz up to its window ahead of the time we ask
 * for. The fix is never to "clear" it (it is already untitled) - it must be given a title and left disabled
 * instead, so Gadgetbridge's picker skips over it for good. See the setup checklist for the owner-facing line.
 */
fun listPoachableSmartWakeupBandAlarmSlots(slots: List<BandAlarmSlot>): List<BandAlarmSlot> =
    slots.filter { it.smartWakeup && isDisabledAndUntitled(it) }

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
