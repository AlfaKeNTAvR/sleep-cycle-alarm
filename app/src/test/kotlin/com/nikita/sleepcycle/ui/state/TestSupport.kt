package com.nikita.sleepcycle.ui.state

// File purpose: shared builders for buildUiState's inputs, so each test only spells out what it cares about.
// exportUri is always null: android.net.Uri cannot be constructed in a plain JVM unit test (every method,
// including Uri.parse, is a stub that throws outside an Android runtime/Robolectric), so the "export file
// picked" branch of setup completeness is verified on the phone instead - see the task report.

import com.nikita.sleepcycle.engine.AlarmMode
import com.nikita.sleepcycle.engine.AlarmPlan
import com.nikita.sleepcycle.engine.NightSettings
import com.nikita.sleepcycle.engine.NightSummary
import com.nikita.sleepcycle.engine.SleepState
import com.nikita.sleepcycle.engine.StretchSummary
import com.nikita.sleepcycle.engine.WakeOption
import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.DebugOptions
import com.nikita.sleepcycle.night.NightEngineView
import com.nikita.sleepcycle.night.NightState
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal val testZone: ZoneId = ZoneId.of("Europe/Moscow")
internal fun instant(text: String): Instant = LocalDateTime.parse(text).atZone(testZone).toInstant()

internal fun testAppSettings(
    deviceMac: String? = "AA:BB:CC:DD:EE:FF",
    lastDeadline: LocalTime? = null,
    deadlineEnabled: Boolean = false,
    pickedCycles: Int = 5,
    lastSetupCheckPassedAt: Instant? = null,
): AppSettings = AppSettings(
    deviceMac = deviceMac,
    exportUri = null,
    lastDeadline = lastDeadline,
    deadlineEnabled = deadlineEnabled,
    pickedCycles = pickedCycles,
    lastSetupCheckPassedAt = lastSetupCheckPassedAt,
)

internal fun testPermissionStatus(allGranted: Boolean = true): PermissionStatus = PermissionStatus(
    notificationsGranted = allGranted,
    fullScreenIntentAllowed = allGranted,
    batteryOptimizationIgnored = allGranted,
    exactAlarmsAllowed = allGranted,
)

internal fun testAlarmPlan(
    mode: AlarmMode,
    wakeAt: String? = null,
    cycles: Int = 0,
    referenceOnset: String? = null,
    onsetIsProjected: Boolean = false,
    reason: String = "test reason",
): AlarmPlan = AlarmPlan(
    mode = mode,
    wakeAt = wakeAt?.let(::instant),
    cycles = cycles,
    referenceOnset = referenceOnset?.let(::instant),
    onsetIsProjected = onsetIsProjected,
    reason = reason,
)

internal fun testNightState(
    startedAt: String = "2026-09-17T00:00",
    settings: NightSettings = NightSettings(null, 5),
    lastPlan: AlarmPlan? = null,
    lastSyncAt: String? = null,
    lastSyncOk: Boolean? = null,
    lastSyncFailureCause: String? = null,
    debugOptions: DebugOptions = DebugOptions(),
    morningAlarmAt: String? = null,
): NightState = NightState(
    startedAt = instant(startedAt),
    settings = settings,
    lastPlan = lastPlan,
    lastSyncAt = lastSyncAt?.let(::instant),
    lastSyncOk = lastSyncOk,
    lastSegments = emptyList(),
    lastExportFileModifiedAt = null,
    lastSyncFailureCause = lastSyncFailureCause,
    debugOptions = debugOptions,
    morningAlarmAt = morningAlarmAt?.let(::instant),
)

internal fun testEngineView(
    sleepState: SleepState,
    totalSleep: Duration = Duration.ZERO,
    stretches: List<StretchSummary> = emptyList(),
    wakeOptions: List<WakeOption> = emptyList(),
): NightEngineView = NightEngineView(sleepState, NightSummary(totalSleep, stretches), wakeOptions)
