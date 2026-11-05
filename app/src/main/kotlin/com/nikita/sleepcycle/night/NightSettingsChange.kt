package com.nikita.sleepcycle.night

// File purpose: changing a running night's wake-by time and sleep length from the Night screen (decision Q2).
// The pure half: whether a change may be made, the plan it gives, and which sleep lengths still fit. The
// Android half (arming, saving, logging) is changeNightSettings in NightController.kt.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.computeAlarmPlan
import com.nikita.sleepcycle.engine.morningAlarmHasRung
import java.time.Instant
import java.time.ZoneId

/** Why a change to the running night's settings was not made. Each keeps the old settings and the alarm armed for them. */
enum class NightSettingsRefusal {
    /** The morning alarm has rung (or I'm up was pressed): the follow-ups own the rest of the night. */
    MORNING_ALARM_RANG,

    /** The night reached FINISHED: there is nothing left to plan. */
    NIGHT_FINISHED,

    /** The new wake-by time is at or before now. */
    DEADLINE_PASSED,
}

/** What [decideNightSettingsChange] says about a change. */
sealed interface NightSettingsDecision {
    /** The same settings the night already runs: nothing to do. */
    data object Unchanged : NightSettingsDecision

    data class Refused(val refusal: NightSettingsRefusal) : NightSettingsDecision

    /** Apply it: [updatedState] carries the new settings and [plan] as its last plan; the night's history is untouched. */
    data class Accepted(val updatedState: NightState, val plan: AlarmPlan) : NightSettingsDecision
}

/** Whether the Night screen offers the change at all: a live night whose morning alarm has not rung yet. */
fun canChangeNightSettings(state: NightState): Boolean = refusalForNight(state) == null

private fun refusalForNight(state: NightState): NightSettingsRefusal? = when {
    state.lastPlan?.mode == AlarmMode.FINISHED -> NightSettingsRefusal.NIGHT_FINISHED
    morningAlarmHasRung(state.wakeAlarmFiredAt, state.morningAlarmAt, state.phoneAlarmFiredFor) -> NightSettingsRefusal.MORNING_ALARM_RANG
    else -> null
}

/**
 * The plan [settings] would give this night at [now]: the engine's normal planning from the night's last known
 * band data and its fired-alarm facts, exactly what the next tick computes. Measured from the real onset once
 * asleep, from now + the fall-asleep estimate before.
 */
fun planWithNightSettings(state: NightState, settings: NightSettings, now: Instant, zone: ZoneId): AlarmPlan =
    computeAlarmPlan(
        state.lastSegments, settings, now, state.morningAlarmAt, zone, resolveEngineConfig(state),
        state.wakeAlarmFiredAt, state.napAlarmsUsed, state.lastNapAlarmFiredAt, state.phoneAlarmFiredFor
    )

/**
 * Decides a change of the running night's settings to [settings] at [now]. Refused once the morning alarm has
 * rung or the night finished, and for a wake-by time already passed; otherwise re-planned at once from the
 * night's own data. The night keeps every other field (stretches, fired alarms, nap count, the bedtime fade).
 * Leaving No alarm closes an open Are you up? ask, which only a No-alarm night asks.
 */
fun decideNightSettingsChange(state: NightState, settings: NightSettings, now: Instant, zone: ZoneId): NightSettingsDecision {
    if (settings == state.settings) return NightSettingsDecision.Unchanged
    refusalForNight(state)?.let { return NightSettingsDecision.Refused(it) }
    if (settings.deadline?.isAfter(now) == false) return NightSettingsDecision.Refused(NightSettingsRefusal.DEADLINE_PASSED)
    val plan = planWithNightSettings(state, settings, now, zone)
    val updated = state.copy(
        settings = settings,
        lastPlan = plan,
        morningAlarmAt = morningAlarmAfterSettingsChange(state.morningAlarmAt, plan),
        areYouUp = if (settings.noAlarm) state.areYouUp else state.areYouUp?.takeUnless { it.isOpen },
    )
    return NightSettingsDecision.Accepted(updated, plan)
}

/**
 * The night's morning alarm time after a settings change. A tick's latch (latchMorningAlarmAt) keeps the old
 * value under a NAP plan; after a change that would leave the old morning time standing even when it is now
 * after the new wake-by time or belongs to a longer night. So the change re-latches to whatever the new plan
 * arms, of any mode; a No-alarm night has none; a plan with nothing to arm keeps the old value.
 */
internal fun morningAlarmAfterSettingsChange(previous: Instant?, plan: AlarmPlan): Instant? = when (plan.mode) {
    AlarmMode.NO_ALARM -> null
    AlarmMode.FINISHED -> previous
    AlarmMode.FULL_CYCLES, AlarmMode.DEADLINE_ONLY, AlarmMode.NAP -> plan.wakeAt ?: previous
}

/**
 * The Night screen's "Sleep up to" chip availability, the mid-night counterpart of [sleepLengthIsAvailable]:
 * true when the whole of [cycles] still owed tonight fits before [deadline], counted the way the engine counts
 * it (from the real onset once asleep, minus the sleep already had). No deadline: always. A deadline at or
 * before [now]: never.
 */
fun nightSleepLengthFits(state: NightState, cycles: Int, deadline: Instant?, now: Instant, zone: ZoneId): Boolean {
    if (deadline == null) return true
    if (!deadline.isAfter(now)) return false
    val plan = planWithNightSettings(state, NightSettings(deadline, cycles), now, zone)
    return plan.cycles >= plan.owedCycles
}
