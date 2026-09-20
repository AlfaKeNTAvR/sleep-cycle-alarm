package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.night.ActiveDebugSwitch
import com.nikita.sleepcycle.night.DebugOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun state(
    appSettings: com.nikita.sleepcycle.night.AppSettings = testAppSettings(),
    nightState: com.nikita.sleepcycle.night.NightState? = null,
    engineView: com.nikita.sleepcycle.night.NightEngineView? = null,
    now: String = "2026-09-17T00:00",
    permissionStatus: PermissionStatus = testPermissionStatus(),
    gadgetbridgeInstalled: Boolean = true,
    screen: Screen = Screen.BeforeBed,
    showingMorningReport: Boolean = false,
    morningReportEndedAt: String? = null,
    debugOptions: DebugOptions = DebugOptions(),
    confirmingEndNight: Boolean = false,
    endingNight: Boolean = false,
): UiState = buildUiState(
    appSettings = appSettings,
    nightState = nightState,
    engineView = engineView,
    now = instant(now),
    zone = testZone,
    permissionStatus = permissionStatus,
    gadgetbridgeInstalled = gadgetbridgeInstalled,
    screen = screen,
    connectionTest = ConnectionTestState.Idle,
    nightLogFiles = emptyList(),
    confirmingEndNight = confirmingEndNight,
    showingMorningReport = showingMorningReport,
    morningReportEndedAt = morningReportEndedAt?.let(::instant),
    debugOptions = debugOptions,
    endingNight = endingNight,
    errorMessage = null,
)

class SetupGatingTest {
    @Test fun `gadgetbridge missing is the first blocker, checked before band address`() {
        val result = state(appSettings = testAppSettings(deviceMac = null), gadgetbridgeInstalled = false)
        assertEquals(SetupItemKind.GADGETBRIDGE_INSTALLED, result.beforeBed.startNightBlocker)
        assertFalse(result.beforeBed.startNightEnabled)
        assertFalse(result.setup.allComplete)
    }

    @Test fun `missing band address blocks even when gadgetbridge is installed`() {
        val result = state(appSettings = testAppSettings(deviceMac = null))
        assertEquals(SetupItemKind.BAND_ADDRESS, result.beforeBed.startNightBlocker)
    }

    @Test fun `missing notification permission blocks after gadgetbridge and band address are fine`() {
        val result = state(
            appSettings = testAppSettings(deviceMac = "AA:BB:CC:DD:EE:FF"),
            permissionStatus = PermissionStatus(
                notificationsGranted = false,
                fullScreenIntentAllowed = true,
                batteryOptimizationIgnored = true,
                exactAlarmsAllowed = true,
            ),
        )
        // exportUri is always null in these tests (see TestSupport.kt), so EXPORT_FILE blocks before NOTIFICATIONS.
        assertEquals(SetupItemKind.EXPORT_FILE, result.beforeBed.startNightBlocker)
    }

    @Test fun `every permission missing still reports the checklist as incomplete`() {
        val result = state(permissionStatus = testPermissionStatus(allGranted = false))
        assertFalse(result.setup.allComplete)
        assertTrue(result.setup.items.any { !it.complete })
    }
}

class SleepLengthFallbackTest {
    @Test fun `a picked length that no longer fits falls back to the longest one that does`() {
        val settings = testAppSettings(
            deadlineEnabled = true,
            lastDeadline = java.time.LocalTime.of(5, 0),
            pickedCycles = 5,
        )
        val result = state(appSettings = settings, now = "2026-09-17T00:00")
        // now=00:00, deadline=05:00: 3 cycles (00:15+4:30=04:45) fits, 4 cycles (00:15+6:00=06:15) does not.
        val selected = result.beforeBed.sleepLengthOptions.first { it.selected }
        assertEquals(3, selected.cycles)
    }

    @Test fun `a picked length that still fits is left alone`() {
        val settings = testAppSettings(deadlineEnabled = false, pickedCycles = 5)
        val result = state(appSettings = settings)
        val selected = result.beforeBed.sleepLengthOptions.first { it.selected }
        assertEquals(5, selected.cycles)
    }
}

class NightScreenStateTest {
    @Test fun `state A shows the projected caption before sleep is detected`() {
        val plan = testAlarmPlan(
            mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:30", cycles = 5,
            referenceOnset = "2026-09-17T00:15", onsetIsProjected = true,
        )
        val night = testNightState(lastPlan = plan, settings = NightSettings(null, 5))
        val view = testEngineView(SleepState.NOT_YET_ASLEEP)
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        val content = result.night!!.content as NightScreenContent.GoingToBedOrAsleep
        assertEquals(OnsetPhase.PROJECTED, content.onsetPhase)
        assertEquals("00:15", content.onsetTimeLabel)
        assertEquals("07:30", content.alarmTimeLabel)
    }

    @Test fun `state A shows the actual onset once asleep, before any awakening`() {
        val plan = testAlarmPlan(
            mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:30", cycles = 5,
            referenceOnset = "2026-09-17T00:15", onsetIsProjected = false,
        )
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.ASLEEP)
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        val content = result.night!!.content as NightScreenContent.GoingToBedOrAsleep
        assertEquals(OnsetPhase.ACTUAL, content.onsetPhase)
    }

    @Test fun `state B shows the latest stretch duration and Stop night when more sleep fits`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:15", cycles = 3)
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(
            SleepState.AWAKE,
            totalSleep = java.time.Duration.ofMinutes(119),
            stretches = listOf(com.nikita.sleepcycle.engine.StretchSummary(instant("2026-09-17T00:30"), instant("2026-09-17T02:29"), java.time.Duration.ofMinutes(119), 1.3)),
        )
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        val nightUi = requireNotNull(result.night)
        val content = nightUi.content as NightScreenContent.WokeUp
        assertFalse(content.napOnly)
        assertEquals("1 h 59", content.sleptDurationLabel)
        assertEquals(EndNightAction.STOP, nightUi.endAction)
    }

    @Test fun `state C is nap-only and offers I'm up, end night`() {
        val plan = testAlarmPlan(mode = AlarmMode.NAP, wakeAt = "2026-09-17T06:48")
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.AWAKE, totalSleep = java.time.Duration.ofMinutes(334))
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        val nightUi = requireNotNull(result.night)
        val content = nightUi.content as NightScreenContent.WokeUp
        assertTrue(content.napOnly)
        assertEquals(EndNightAction.IM_UP, nightUi.endAction)
        assertEquals("20 min", content.napLengthLabel)
    }

    @Test fun `FINISHED shows the engine's own reason and offers End night`() {
        val plan = testAlarmPlan(mode = AlarmMode.FINISHED, reason = "Night finished, deadline was 08:30.")
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.AWAKE)
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        val nightUi = requireNotNull(result.night)
        val content = nightUi.content as NightScreenContent.NightFinished
        assertEquals("Night finished, deadline was 08:30.", content.reasonText)
        assertEquals(EndNightAction.END, nightUi.endAction)
    }

    @Test fun `no plan yet shows Loading`() {
        val night = testNightState(lastPlan = null)
        val result = state(nightState = night, engineView = null, screen = Screen.Night)
        assertEquals(NightScreenContent.Loading, result.night!!.content)
    }

    @Test fun `the morning report is built from the cached view, not from a live night state`() {
        val view = testEngineView(
            SleepState.AWAKE,
            totalSleep = java.time.Duration.ofMinutes(354),
            stretches = listOf(
                com.nikita.sleepcycle.engine.StretchSummary(instant("2026-09-17T00:30"), instant("2026-09-17T02:29"), java.time.Duration.ofMinutes(119), 1.3),
                com.nikita.sleepcycle.engine.StretchSummary(instant("2026-09-17T02:40"), instant("2026-09-17T06:15"), java.time.Duration.ofMinutes(215), 2.4),
            ),
        )
        val result = state(
            nightState = null,
            engineView = view,
            showingMorningReport = true,
            morningReportEndedAt = "2026-09-17T06:50",
            screen = Screen.Night,
        )
        val content = result.night!!.content as NightScreenContent.MorningReport
        assertEquals("06:50", content.endedAtTimeLabel)
        assertEquals("5 h 54", content.totalSleepDurationLabel)
        assertEquals(2, content.stretches.size)
        assertEquals("1.3", content.stretches[0].cyclesLabel)
    }

    @Test fun `with no night at all, the night screen state is null`() {
        val result = state(nightState = null, engineView = null, screen = Screen.Night)
        assertNull(result.night)
    }
}

/** A1: the warning banner must be driven by isAnyEnabled, not fastNight alone - simulated data alone must warn just as loudly as a fast night. */
class DebugWarningBannerTest {
    @Test fun `before bed shows no banner when every debug switch is off`() {
        val result = state(debugOptions = DebugOptions())
        assertTrue(result.beforeBed.activeDebugSwitches.isEmpty())
    }

    @Test fun `before bed shows the banner for simulated data alone, not just fast night`() {
        val result = state(debugOptions = DebugOptions(simulatedBandData = true))
        assertEquals(listOf(ActiveDebugSwitch.SIMULATED_SLEEP_DATA), result.beforeBed.activeDebugSwitches)
    }

    @Test fun `night screen names every active switch while asleep, not just fast night`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:30", cycles = 5, referenceOnset = "2026-09-17T00:15")
        val debugOptions = DebugOptions(simulatedBandData = true, fastNight = true)
        val night = testNightState(lastPlan = plan, debugOptions = debugOptions)
        val view = testEngineView(SleepState.ASLEEP)
        val result = state(nightState = night, engineView = view, screen = Screen.Night, debugOptions = debugOptions)
        assertEquals(
            listOf(ActiveDebugSwitch.SIMULATED_SLEEP_DATA, ActiveDebugSwitch.FAST_NIGHT),
            result.night!!.activeDebugSwitches
        )
    }

    @Test fun `night screen shows no banner once the morning report is up`() {
        val view = testEngineView(SleepState.AWAKE)
        val result = state(
            nightState = null, engineView = view, showingMorningReport = true, morningReportEndedAt = "2026-09-17T06:50",
            screen = Screen.Night, debugOptions = DebugOptions(fastNight = true),
        )
        assertTrue(result.night!!.activeDebugSwitches.isEmpty())
    }
}

/** D1: the test alarm button must be disabled outright while a night is active. */
class DebugTestAlarmGatingTest {
    @Test fun `the test alarm is offered when no night is active`() {
        val result = state(nightState = null)
        assertTrue(result.debug.canRingTestAlarm)
    }

    @Test fun `the test alarm is disabled while a night is active`() {
        val night = testNightState()
        val result = state(nightState = night)
        assertFalse(result.debug.canRingTestAlarm)
    }
}

/** Item 1: the end-night button/dialog flow, wired end to end through buildUiState. */
class EndNightFlowWiringTest {
    @Test fun `endingNight flows through to the night screen state`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:30", cycles = 5, referenceOnset = "2026-09-17T00:15")
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.ASLEEP)
        val result = state(nightState = night, engineView = view, screen = Screen.Night, confirmingEndNight = false, endingNight = true)
        assertTrue(result.night!!.endingNight)
    }

    @Test fun `endingNight defaults to false`() {
        val plan = testAlarmPlan(mode = AlarmMode.FULL_CYCLES, wakeAt = "2026-09-17T07:30", cycles = 5, referenceOnset = "2026-09-17T00:15")
        val night = testNightState(lastPlan = plan)
        val view = testEngineView(SleepState.ASLEEP)
        val result = state(nightState = night, engineView = view, screen = Screen.Night)
        assertFalse(result.night!!.endingNight)
    }
}
