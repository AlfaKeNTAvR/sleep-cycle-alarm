package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.ActiveDebugSwitch
import java.time.LocalTime

/** One "Sleep up to" chip: its cycle count, its hour label ("7.5 h"), and whether it is picked / still fits. */
data class SleepLengthOption(
    val cycles: Int,
    val hoursLabel: String,
    val selected: Boolean,
    val available: Boolean,
)

/** Everything the "Before bed" screen shows. */
data class BeforeBedUiState(
    val bandReady: Boolean,
    val deadlineEnabled: Boolean,
    val deadlineTime: LocalTime,
    val sleepLengthOptions: List<SleepLengthOption>,
    val phoneBackupRowVisible: Boolean,
    val phoneBackupEnabled: Boolean,
    val startNightEnabled: Boolean,
    val startNightBlocker: SetupItemKind?,
    /** True when the checklist itself is complete but no setup check has passed recently enough (see [SETUP_CHECK_VALIDITY_WINDOW]); mutually exclusive with [startNightBlocker] being non-null. */
    val startNightBlockedBySetupCheck: Boolean,
    /** True when the band has only one usable alarm slot and neither the deadline nor the phone backup is on, so nothing would be guaranteed to ring (see [singleSlotNightNeedsPhoneAlarm]). Disables "Start night" until one of the two switches is turned on. */
    val startNightBlockedByNoPhoneAlarm: Boolean = false,
    /** A1: every debug switch currently live: shows the amber warning banner naming all of them when non-empty. */
    val activeDebugSwitches: List<ActiveDebugSwitch> = emptyList(),
    /** True while "Start night" is asking the owner to confirm a simulated night (any debug option on) before it actually starts. */
    val confirmingDebugNightStart: Boolean = false,
    /** Item 4: true when the deadline switch and the phone-backup switch are both off, so nothing on the phone will ring tonight - shown as an amber line above "Start night". Never blocks starting. */
    val noPhoneAlarmWarning: Boolean = false,
)
