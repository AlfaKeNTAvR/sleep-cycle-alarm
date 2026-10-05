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

/** P2: the latest connection test (automatic or tapped) as the Before-bed screen shows it - a quiet line above "Start night", never a dialog. */
sealed interface BandCheckStatus {
    /** No test this session, or the last one passed: nothing extra is shown. */
    data object None : BandCheckStatus

    /** A test is running right now (bounded by CONNECTION_TEST_DEADLINE). */
    data object Checking : BandCheckStatus

    /** The last test failed; [reason] is its first action-needed line, shown in amber with a "Test again" button. */
    data class Failed(val reason: String) : BandCheckStatus
}

/**
 * P2: what the status line in the screen's top corner says about the band. [NOT_RESPONDING] was added after the
 * owner saw the prototype read "Band connected" directly above an amber "Band check failed": the corner used to
 * report only whether the band was set up, which is not what "connected" means to someone reading it.
 */
enum class BandStatus {
    /** Set up, and a connection test passed within [CONNECTED_STATUS_MAX_AGE]; shown with that pass's time. */
    CONNECTED,

    /**
     * Set up, but no pass within [CONNECTED_STATUS_MAX_AGE] (or none at all). Owner-reported on the phone,
     * 2026-09-30: the band was disconnected while the corner still said "Band connected" from an old pass - the app
     * only knows what its last check found, so an old pass reads "Not synced yet", never "connected".
     */
    NOT_CHECKED_RECENTLY,

    /** Set up, and a connection test is running right now: the corner does not claim an answer before it has one (owner-reported on the phone, 2026-09-30). */
    CHECKING,

    /** Set up, but the latest connection test failed. */
    NOT_RESPONDING,

    /** Gadgetbridge missing, or no band address or export file chosen yet. */
    SETUP_INCOMPLETE,
}

/** Everything the "Before bed" screen shows. */
data class BeforeBedUiState(
    val bandStatus: BandStatus,
    /** P2 (owner request, 2026-09-30): the clock time of the last passed connection test, e.g. "23:06", shown in brackets after "Band connected"; null when none has passed. */
    val bandCheckedAtLabel: String? = null,
    val deadlineEnabled: Boolean,
    val deadlineTime: LocalTime,
    val sleepLengthOptions: List<SleepLengthOption>,
    val startNightEnabled: Boolean,
    val startNightBlocker: SetupItemKind?,
    /** True when the checklist itself is complete but no setup check has passed recently enough (see [SETUP_CHECK_VALIDITY_WINDOW]); mutually exclusive with [startNightBlocker] being non-null. */
    val startNightBlockedBySetupCheck: Boolean,
    /** A1: every debug switch currently live: shows the amber warning banner naming all of them when non-empty. */
    val activeDebugSwitches: List<ActiveDebugSwitch> = emptyList(),
    /** T12 (amended): the live simulated-clock reading, e.g. "03:15" or "03:15, 60x" - null when not warped. Formats [ActiveDebugSwitch.SIMULATED_TIME]'s own banner label; see [com.nikita.sleepcycle.night.formatSimulatedTimeValue]. */
    val simulatedTimeValue: String? = null,
    /** True while "Start night" is asking the owner to confirm a simulated night (any debug option on) before it actually starts. */
    val confirmingDebugNightStart: Boolean = false,
    /** P2: the latest connection test's state. Shown alongside, never instead of, the Start-night gating above: a failure here does not itself disable "Start night". */
    val bandCheck: BandCheckStatus = BandCheckStatus.None,
    /** Owner request, 2026-10-05: a failed band check offers "Open Gadgetbridge" above Start night, to reconnect the band there. */
    val offerOpenGadgetbridge: Boolean = false,
)
