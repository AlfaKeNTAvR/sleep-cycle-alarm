package com.nikita.sleepcycle.ui.state

// File purpose: the one definition of "setup is complete", shared by the Setup checklist and the Before-bed
// screen's "Start night" gating, so the two can never disagree.

import com.nikita.sleepcycle.night.AppSettings
import com.nikita.sleepcycle.night.BandCommandMode
import com.nikita.sleepcycle.night.DebugOptions
import java.time.Duration
import java.time.Instant

/** The band's Bluetooth MAC address, prefilled on first launch; the owner's one band. */
const val DEFAULT_BAND_MAC = "AA:BB:CC:DD:EE:FF"

/** How long a passing "Test connection" result stays good enough to start a night on. Band slots, permissions and the export file can all drift after the check, so a stale pass is not trusted forever. */
val SETUP_CHECK_VALIDITY_WINDOW: Duration = Duration.ofHours(24)

/** True when [lastSetupCheckPassedAt] is set and still within [SETUP_CHECK_VALIDITY_WINDOW] of [now]. A timestamp in the future (clock skew) is never trusted. */
fun setupCheckIsRecent(lastSetupCheckPassedAt: Instant?, now: Instant): Boolean {
    if (lastSetupCheckPassedAt == null) return false
    val elapsed = Duration.between(lastSetupCheckPassedAt, now)
    return !elapsed.isNegative && elapsed <= SETUP_CHECK_VALIDITY_WINDOW
}

/** Whether "Start night" is enabled, and, if not, whether that is specifically because the setup check is missing or stale rather than the checklist itself being incomplete (the two reasons are mutually exclusive and shown differently on the Before-bed screen). */
data class StartNightGate(val enabled: Boolean, val blockedBySetupCheck: Boolean)

/**
 * True only when the Debug screen's simulated band data AND dry-run band commands are both on - the one
 * combination where the app never talks to Gadgetbridge or the band for real this night, so it can relax
 * which setup items and which setup-check recency it requires (see [isSetupItemRequired] and
 * [startNightGate]). Neither switch alone relaxes anything: fast night changes only timing, and either
 * simulated data or dry-run commands alone still touches the real band through the other one.
 */
fun debugOptionsRelaxSetup(debugOptions: DebugOptions): Boolean =
    debugOptions.simulatedBandData && debugOptions.bandCommandMode == BandCommandMode.DRY_RUN

/** The phone-side setup items debug options never bypass (per the task spec: notifications, full-screen alarm, battery, and the always-granted exact-alarms item). */
private val ALWAYS_REQUIRED_SETUP_ITEMS: Set<SetupItemKind> = setOf(
    SetupItemKind.NOTIFICATIONS, SetupItemKind.FULL_SCREEN_INTENT, SetupItemKind.BATTERY_OPTIMIZATION, SetupItemKind.EXACT_ALARMS
)

/** Whether [kind] is still required to start a night, given [debugOptions]. The band/Gadgetbridge items (installed, address, export file) relax together when [debugOptionsRelaxSetup]; the phone-side items in [ALWAYS_REQUIRED_SETUP_ITEMS] never do. */
fun isSetupItemRequired(kind: SetupItemKind, debugOptions: DebugOptions): Boolean =
    kind in ALWAYS_REQUIRED_SETUP_ITEMS || !debugOptionsRelaxSetup(debugOptions)

/** Combines checklist completeness with setup-check recency into one gate for "Start night". Pure and total: never touches Android or the engine. [debugOptions] relaxes the setup-check-recency requirement when [debugOptionsRelaxSetup] - there is nothing for a real "Test connection" pass to have verified. */
fun startNightGate(checklistComplete: Boolean, lastSetupCheckPassedAt: Instant?, now: Instant, debugOptions: DebugOptions = DebugOptions()): StartNightGate {
    if (debugOptionsRelaxSetup(debugOptions)) return StartNightGate(enabled = checklistComplete, blockedBySetupCheck = false)
    val recent = setupCheckIsRecent(lastSetupCheckPassedAt, now)
    return StartNightGate(enabled = checklistComplete && recent, blockedBySetupCheck = checklistComplete && !recent)
}

/** Every setup item, in checklist order; also the order [startNightBlocker] checks them in. */
val SETUP_ITEM_ORDER: List<SetupItemKind> = listOf(
    SetupItemKind.GADGETBRIDGE_INSTALLED,
    SetupItemKind.BAND_ADDRESS,
    SetupItemKind.EXPORT_FILE,
    SetupItemKind.NOTIFICATIONS,
    SetupItemKind.FULL_SCREEN_INTENT,
    SetupItemKind.BATTERY_OPTIMIZATION,
    SetupItemKind.EXACT_ALARMS,
)

/** Whether one setup item is currently satisfied. */
fun isSetupItemComplete(
    kind: SetupItemKind,
    appSettings: AppSettings,
    permissionStatus: PermissionStatus,
    gadgetbridgeInstalled: Boolean,
): Boolean = when (kind) {
    SetupItemKind.GADGETBRIDGE_INSTALLED -> gadgetbridgeInstalled
    SetupItemKind.BAND_ADDRESS -> !appSettings.deviceMac.isNullOrBlank()
    SetupItemKind.EXPORT_FILE -> appSettings.exportUri != null
    SetupItemKind.NOTIFICATIONS -> permissionStatus.notificationsGranted
    SetupItemKind.FULL_SCREEN_INTENT -> permissionStatus.fullScreenIntentAllowed
    SetupItemKind.BATTERY_OPTIMIZATION -> permissionStatus.batteryOptimizationIgnored
    SetupItemKind.EXACT_ALARMS -> permissionStatus.exactAlarmsAllowed
}

/** True once every setup item [isSetupItemRequired] by [debugOptions] is green. */
fun isSetupComplete(
    appSettings: AppSettings,
    permissionStatus: PermissionStatus,
    gadgetbridgeInstalled: Boolean,
    debugOptions: DebugOptions = DebugOptions(),
): Boolean = SETUP_ITEM_ORDER.filter { isSetupItemRequired(it, debugOptions) }.all { isSetupItemComplete(it, appSettings, permissionStatus, gadgetbridgeInstalled) }

/** The first incomplete, still-required setup item, used as "Start night"'s one-line blocked reason; null once [isSetupComplete] is true. */
fun startNightBlocker(
    appSettings: AppSettings,
    permissionStatus: PermissionStatus,
    gadgetbridgeInstalled: Boolean,
    debugOptions: DebugOptions = DebugOptions(),
): SetupItemKind? = SETUP_ITEM_ORDER
    .filter { isSetupItemRequired(it, debugOptions) }
    .firstOrNull { !isSetupItemComplete(it, appSettings, permissionStatus, gadgetbridgeInstalled) }
