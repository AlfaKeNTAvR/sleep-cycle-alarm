package com.nikita.sleepcycle.alarm

// File purpose: W18 (owner request) - which alarm is ringing, in words. Four rings reach the same screen and
// the same notification, and until now only the out-of-bed nudge was distinguishable from the rest, so a ring
// at 03:40 gave the owner no way to tell a nap alarm from the morning alarm from a leftover debug test.
//
// This is WORDING ONLY. It rides alongside the existing EXTRA_ALARM_IS_TEST / EXTRA_ALARM_IS_OUT_OF_BED_NUDGE
// booleans rather than replacing them, and nothing branches on it except a string lookup. Those two booleans
// still drive every real decision (PhoneAlarmReceiver's night-state bookkeeping, which request code is armed,
// what gets cancelled), and deliberately keep doing so: this landed hours before a real night, and rewriting
// the receiver's control flow to derive them from this label instead is a change worth making with the lights
// on. Folding the two booleans into this enum is the obvious follow-up once that is no longer true.

import android.content.Intent
import androidx.annotation.StringRes
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.night.firedAlarmIsWakeAlarm
import java.time.Instant

/** Which of the app's four rings this is, for the ring screen's and the notification's own wording. */
enum class AlarmLabel { MORNING, NAP, OUT_OF_BED, TEST }

/** Names the ring on the firing intent; absent on an intent from an older build, which [readAlarmLabel] covers. */
const val EXTRA_ALARM_LABEL = "alarmLabel"

/**
 * The label an alarm armed for [armedFor] by a plan of [mode] should ring under. FULL_CYCLES and
 * DEADLINE_ONLY are both the morning alarm as far as the owner is concerned, and FINISHED never arms an alarm
 * at all (AlarmPlan.wakeAt is null by construction there), so it can only be reached as a caller's fallback.
 *
 * H8: NAP mode alone no longer decides, because a NAP plan can now carry the night's own still-pending
 * morning alarm as its `wakeAt` (lying awake in the last minutes before it is rule 7 - see WakeAlarm.kt's
 * `awakeNapTarget`), and that alarm must not ring calling itself a nap. Delegates to [firedAlarmIsWakeAlarm],
 * the same predicate PhoneAlarmReceiver attributes the firing with, so the name an alarm rings under and the
 * bookkeeping it lands in can never disagree.
 */
fun alarmLabelFor(mode: AlarmMode, armedFor: Instant, morningAlarmAt: Instant?): AlarmLabel =
    if (firedAlarmIsWakeAlarm(mode, armedFor, morningAlarmAt)) AlarmLabel.MORNING else AlarmLabel.NAP

/** The alarm's own name, shown above the ring screen's headline and as the notification's title. */
@StringRes
fun alarmLabelNameRes(label: AlarmLabel): Int = when (label) {
    AlarmLabel.MORNING -> R.string.alarm_label_morning
    AlarmLabel.NAP -> R.string.alarm_label_nap
    AlarmLabel.OUT_OF_BED -> R.string.alarm_label_out_of_bed
    AlarmLabel.TEST -> R.string.alarm_label_test
}

/** What the ring asks for, under its name: the nudge wants the owner out of bed, everything else wants them awake. */
@StringRes
fun alarmLabelInstructionRes(label: AlarmLabel): Int =
    if (label == AlarmLabel.OUT_OF_BED) R.string.alarm_instruction_out_of_bed else R.string.alarm_instruction_wake

/**
 * Reads the label back, falling back to what the older booleans imply when the extra is missing - an alarm
 * armed by a previous build can still be pending when this one is installed over it, and it must not ring
 * under a wrong name.
 */
fun Intent?.readAlarmLabel(): AlarmLabel {
    val name = this?.getStringExtra(EXTRA_ALARM_LABEL)
    if (name != null) return runCatching { AlarmLabel.valueOf(name) }.getOrDefault(AlarmLabel.MORNING)
    return when {
        this?.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false) == true -> AlarmLabel.OUT_OF_BED
        this?.getBooleanExtra(EXTRA_ALARM_IS_TEST, false) == true -> AlarmLabel.TEST
        else -> AlarmLabel.MORNING
    }
}
