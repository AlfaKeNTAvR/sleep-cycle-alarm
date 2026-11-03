package com.nikita.sleepcycle.night

// File purpose: P5 (PER-130 part 2) - the pure rules for the automatic band reconnect: which results call for
// one, the night's cooldown between attempts, what a connection test does after a failed first attempt, and
// when to check that Gadgetbridge's Intent API answers. No Android here; the broadcasts are bridge/BandReconnect.kt.

import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.StaleReason
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/** P5: why a reconnect was tried. [logValue] is the `trigger` field of the reconnect log events. */
enum class ReconnectTrigger(val logValue: String) {
    /** A sync step failed or timed out (2026-09-30: every activity_sync timed out until the band was reconnected). */
    SYNC_FAILED("sync_failed"),

    /** The sync finished but nothing new arrived ([StaleReason.NO_NEW_SAMPLES]). */
    NO_NEW_SAMPLES("no_new_samples"),
}

/**
 * P5: the least time between two reconnects in one night. A band out of range (or Gadgetbridge with its Intent
 * API off) fails every tick, every 5 to 15 min; reconnecting each time would tear the link down all night for
 * nothing. 30 min still tries again a few times a night, which matches how long the P1 band stayed stuck.
 */
val BAND_RECONNECT_COOLDOWN: Duration = Duration.ofMinutes(30)

/**
 * P5: whether one night tick's sync result calls for a reconnect. A failed sync or [StaleReason.NO_NEW_SAMPLES]
 * does; [StaleReason.NO_HEART_RATE_VALUES] never does: the band is connected and syncing, and Gadgetbridge sends
 * the heart-rate setting only when it is toggled, never on reconnect (P4). A fresh or simulated result does not.
 */
fun reconnectTriggerFor(outcome: SyncOutcome): ReconnectTrigger? = when {
    outcome.syncOk -> null
    outcome.failureCause != null -> ReconnectTrigger.SYNC_FAILED
    outcome.staleReason == StaleReason.NO_NEW_SAMPLES -> ReconnectTrigger.NO_NEW_SAMPLES
    else -> null
}

/**
 * P5: the same rule for a connection test report: a pass, or "not measuring heart rate", calls for none. Any other
 * failure (a timed out attempt, a failed step, an attempt that threw) counts as a failed sync.
 */
fun reconnectTriggerFor(report: SetupCheckReport): ReconnectTrigger? = when {
    report.isReady -> null
    report.staleReason == StaleReason.NO_HEART_RATE_VALUES -> null
    report.staleReason == StaleReason.NO_NEW_SAMPLES -> ReconnectTrigger.NO_NEW_SAMPLES
    else -> ReconnectTrigger.SYNC_FAILED
}

/**
 * P5: true when no reconnect was tried this night, or the last one is at least [cooldown] before [now]. A last
 * attempt AFTER [now] (the phone's clock was set back) is not trusted and allows one, so a clock change can never
 * block reconnects for the rest of the night.
 */
fun reconnectCooldownAllows(lastAttemptAt: Instant?, now: Instant, cooldown: Duration = BAND_RECONNECT_COOLDOWN): Boolean {
    if (lastAttemptAt == null) return true
    val elapsed = Duration.between(lastAttemptAt, now)
    return elapsed.isNegative || elapsed >= cooldown
}

/** P5: what one night tick does about its sync result. A tick makes at most one decision, so at most one reconnect. */
sealed interface TickReconnectDecision {
    /** The result is fine, or a reconnect would not help it. */
    data object NotNeeded : TickReconnectDecision

    /** A reconnect would help, but one was tried less than [BAND_RECONNECT_COOLDOWN] ago. */
    data class InCooldown(val trigger: ReconnectTrigger, val lastAttemptAt: Instant) : TickReconnectDecision

    /** Reconnect, then sync once more and use that result for the tick. */
    data class Reconnect(val trigger: ReconnectTrigger) : TickReconnectDecision
}

/** P5: [reconnectTriggerFor] and [reconnectCooldownAllows] together, for one tick's first sync [outcome]. */
fun decideTickReconnect(outcome: SyncOutcome, lastAttemptAt: Instant?, now: Instant): TickReconnectDecision {
    val trigger = reconnectTriggerFor(outcome) ?: return TickReconnectDecision.NotNeeded
    if (!reconnectCooldownAllows(lastAttemptAt, now)) return TickReconnectDecision.InCooldown(trigger, checkNotNull(lastAttemptAt))
    return TickReconnectDecision.Reconnect(trigger)
}

/** P5: the record a reconnect attempt leaves in [NightState]; the tick keeps the previous one when it tried none. */
fun lastReconnectAttemptAfterTick(previous: Instant?, attemptedThisTick: Instant?): Instant? = attemptedThisTick ?: previous

/** P5: what a connection test did after its first attempt, for the caller to show and log. */
data class ConnectionTestRun(
    val report: SetupCheckReport,
    /** Why a reconnect was tried before the second attempt; null when none was. */
    val reconnectTrigger: ReconnectTrigger? = null,
    val reconnectResult: BandReconnectResult? = null,
)

/**
 * P2 + P5: runs [attempt] (numbered 1, then 2) and, when the first one fails:
 * - for a failure a reconnect can fix ([reconnectTriggerFor]), automatic or tapped: [reconnect] once, then try
 *   again straight away. The reconnect's own pause and reply wait replace P2's [pause].
 * - otherwise, an [automatic] test pauses [pause] and tries once more (P2), and a tapped one stops.
 * The second report is returned whatever it is, so a test makes at most two attempts and one reconnect.
 */
suspend fun runConnectionTestAttempts(
    automatic: Boolean,
    pause: Duration,
    attempt: suspend (attemptNumber: Int) -> SetupCheckReport,
    reconnect: suspend (ReconnectTrigger) -> BandReconnectResult,
): ConnectionTestRun {
    val first = attempt(1)
    if (first.isReady) return ConnectionTestRun(first)
    val trigger = reconnectTriggerFor(first)
    if (trigger != null) {
        val reconnectResult = reconnect(trigger)
        return ConnectionTestRun(attempt(2), trigger, reconnectResult)
    }
    if (!automatic) return ConnectionTestRun(first)
    delay(pause.toMillis())
    return ConnectionTestRun(attempt(2))
}

/**
 * P5: whether to check that Gadgetbridge's Intent API answers, after [run]. Only after a pass, when the band is
 * known to be connected, so a missing reply means the toggle is off and not that the band is out of range; and
 * not when this test's own reconnect already got its reply, which proves the toggle is on.
 */
fun shouldCheckIntentApi(run: ConnectionTestRun): Boolean =
    run.report.isReady && run.reconnectResult?.connectedReplyReceived != true

/**
 * P5: the hint shown when the Intent API check got no reply. It never blocks Start night: the night still runs,
 * only without the automatic reconnect, and the manual fix stays on screen.
 */
const val BLUETOOTH_INTENT_API_HINT =
    "Turn on Gadgetbridge Settings > Developer options > Intent API > Bluetooth so the app can reconnect the band."

/** P5: [report] with the Intent API hint added as a warning line and flagged for Before bed. */
fun withBluetoothIntentApiHint(report: SetupCheckReport): SetupCheckReport = report.copy(
    lines = report.lines + SetupCheckLine(BLUETOOTH_INTENT_API_HINT, SetupCheckLineSeverity.WARNING),
    bluetoothIntentApiLooksOff = true,
)
