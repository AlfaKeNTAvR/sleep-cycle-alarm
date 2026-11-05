package com.nikita.sleepcycle.ui.state

// File purpose: Q2 - the Night screen's alarm editor, pure: opening it prefilled from the running night, the
// picker changes (Before bed's own rules, with the running night's fit), and what it shows. Saving goes through
// NightController.kt's changeNightSettings.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.NightSettingsDecision
import com.nikita.sleepcycle.night.NightSettingsRefusal
import com.nikita.sleepcycle.night.NightState
import com.nikita.sleepcycle.night.decideNightSettingsChange
import com.nikita.sleepcycle.night.nightSleepLengthFits
import com.nikita.sleepcycle.night.sleepLengthCycleOptions
import com.nikita.sleepcycle.night.sleepLengthFor
import com.nikita.sleepcycle.ui.format.formatClockTime
import com.nikita.sleepcycle.ui.format.formatSleepLengthLabel
import com.nikita.sleepcycle.ui.format.nextOccurrenceOfDeadline
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Why the editor cannot save, or why its last save did not apply. Each leaves the night as it was. */
enum class AlarmEditorProblem {
    /** The wake-by time is at or before now. */
    DEADLINE_PASSED,

    /** The morning alarm rang (or I'm up was pressed) while the editor was open. */
    MORNING_ALARM_RANG,

    /** The new alarm could not be set or saved. */
    NOT_SAVED,
}

/**
 * The open editor. [choice] holds the choice being edited in Before bed's own shape, so its picker rules apply
 * unchanged; only its deadline switch and time, picked length and No alarm are read. [problem] is the last
 * save's refusal, cleared by the next change.
 */
data class AlarmEditorDraft(val choice: AppSettings, val problem: AlarmEditorProblem? = null, val saving: Boolean = false)

/** What the editor shows: the same pickers as Before bed, the alarm the choice would give, and any problem. */
data class AlarmEditorUiState(
    val deadlineEnabled: Boolean,
    val deadlineTime: LocalTime,
    val sleepLengthOptions: List<SleepLengthOption>,
    val noAlarmSelected: Boolean,
    /** The alarm the choice gives, e.g. "05:15"; null for No alarm or while a problem stands. */
    val newAlarmTimeLabel: String?,
    /** No whole cycle fits before the wake-by time, so the alarm is the wake-by time itself. */
    val newAlarmAtWakeBy: Boolean = false,
    val problem: AlarmEditorProblem? = null,
    val saveEnabled: Boolean,
)

/** Opens the editor on the running night's own settings. [appSettings] supplies the remembered wake-by time when the night has none. */
fun openAlarmEditorDraft(appSettings: AppSettings, night: NightSettings, zone: ZoneId): AlarmEditorDraft =
    AlarmEditorDraft(
        appSettings.copy(
            deadlineEnabled = night.deadline != null,
            lastDeadline = night.deadline?.atZone(zone)?.toLocalTime() ?: appSettings.lastDeadline,
            pickedCycles = night.pickedCycles,
            noAlarm = night.noAlarm,
        )
    )

/**
 * The wake-by instant [choice] means for a night started at [startedAt]: the first time its clock time comes
 * round after the night began, the same instant Start night gave it. A time that came round earlier tonight is
 * therefore in the past, not tomorrow. Null when the switch is off or No alarm is picked.
 */
fun editedDeadline(choice: AppSettings, startedAt: Instant, zone: ZoneId): Instant? =
    if (choice.deadlineEnabled && !choice.noAlarm) nextOccurrenceOfDeadline(choice.lastDeadline ?: DEFAULT_DEADLINE_TIME, startedAt, zone) else null

/** The night settings [choice] stands for. */
fun nightSettingsForChoice(choice: AppSettings, startedAt: Instant, zone: ZoneId): NightSettings =
    NightSettings(deadline = editedDeadline(choice, startedAt, zone), pickedCycles = choice.pickedCycles, noAlarm = choice.noAlarm)

/** "Wake me by" switched in the editor: Before bed's rule, with the lengths that fit this night. */
fun withEditorDeadlineSwitched(choice: AppSettings, enabled: Boolean, state: NightState, now: Instant, zone: ZoneId): AppSettings {
    val switched = withDeadlineSwitched(choice, enabled) { longestFittingTonight(it.copy(noAlarm = false), sleepLengthCycleOptions.max(), state, now, zone) }
    return withPickedLengthFittingTonight(switched, state, now, zone)
}

/** The wake-by time changed in the editor. */
fun withEditorDeadlineTime(choice: AppSettings, time: LocalTime, state: NightState, now: Instant, zone: ZoneId): AppSettings =
    withPickedLengthFittingTonight(choice.copy(lastDeadline = time), state, now, zone)

/**
 * Like Before bed, a picked length that no longer fits the wake-by time falls back to the longest one that does
 * (and stays when none does). Only on a wake-by change: opening the editor never moves the night's own length.
 */
private fun withPickedLengthFittingTonight(choice: AppSettings, state: NightState, now: Instant, zone: ZoneId): AppSettings {
    if (choice.noAlarm || !choice.deadlineEnabled) return choice
    return choice.copy(pickedCycles = longestFittingTonight(choice, choice.pickedCycles, state, now, zone))
}

private fun longestFittingTonight(choice: AppSettings, current: Int, state: NightState, now: Instant, zone: ZoneId): Int {
    val deadline = editedDeadline(choice, state.startedAt, zone)
    return resolvePickedCycles(current) { nightSleepLengthFits(state, it, deadline, now, zone) }
}

/** Builds what the open editor shows for the running night [state] at [now]. */
fun buildAlarmEditorUiState(draft: AlarmEditorDraft, state: NightState, now: Instant, zone: ZoneId): AlarmEditorUiState {
    val choice = draft.choice
    val settings = nightSettingsForChoice(choice, state.startedAt, zone)
    val options = sleepLengthCycleOptions.map { cycles ->
        SleepLengthOption(
            cycles = cycles,
            hoursLabel = formatSleepLengthLabel(sleepLengthFor(cycles, state.debugOptions)),
            selected = !choice.noAlarm && cycles == choice.pickedCycles,
            available = nightSleepLengthFits(state, cycles, settings.deadline, now, zone),
        )
    }
    val decision = decideNightSettingsChange(state, settings, now, zone)
    val plan = when (decision) {
        is NightSettingsDecision.Accepted -> decision.plan
        NightSettingsDecision.Unchanged -> state.lastPlan
        is NightSettingsDecision.Refused -> null
    }
    val problem = (decision as? NightSettingsDecision.Refused)?.refusal?.let(::problemFor) ?: draft.problem
    return AlarmEditorUiState(
        deadlineEnabled = choice.deadlineEnabled,
        deadlineTime = choice.lastDeadline ?: DEFAULT_DEADLINE_TIME,
        sleepLengthOptions = options,
        noAlarmSelected = choice.noAlarm,
        newAlarmTimeLabel = plan?.wakeAt?.takeIf { problem == null }?.let { formatClockTime(it, zone) },
        newAlarmAtWakeBy = plan?.mode == AlarmMode.DEADLINE_ONLY,
        problem = problem,
        saveEnabled = decision !is NightSettingsDecision.Refused && !draft.saving,
    )
}

/** The editor's wording for a refusal from the night layer. */
fun problemFor(refusal: NightSettingsRefusal): AlarmEditorProblem = when (refusal) {
    NightSettingsRefusal.DEADLINE_PASSED -> AlarmEditorProblem.DEADLINE_PASSED
    NightSettingsRefusal.MORNING_ALARM_RANG, NightSettingsRefusal.NIGHT_FINISHED -> AlarmEditorProblem.MORNING_ALARM_RANG
}
