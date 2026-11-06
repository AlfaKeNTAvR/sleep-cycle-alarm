package com.nikita.sleepcycle.night

// File purpose: P7 (2026-11-06) - the pure rules for when the app asks Gadgetbridge to turn the band's automatic
// heart rate back on: at the bedtime test, at Start night, after a reconnect, and on a night tick that finds the
// band not measuring (at most once per HEART_RATE_SETTING_RESEND_INTERVAL). No Android here; the broadcast is
// bridge/GadgetbridgeCommands.kt's sendAutomaticHeartRateOn.

import com.nikita.sleepcycle.bridge.BandReconnectResult
import com.nikita.sleepcycle.bridge.StaleReason
import java.time.Duration
import java.time.Instant

/** P7: why the setting was sent. [logValue] is the `trigger` field of `heart_rate_setting_sent`. */
enum class HeartRateSettingTrigger(val logValue: String) {
    /** A Before bed connection test (automatic or tapped) found the band connected. */
    BEDTIME_TEST("bedtime_test"),

    /** Start night, on a real-band night. */
    START_NIGHT("start_night"),

    /** A reconnect got Gadgetbridge's BLUETOOTH_CONNECTED reply, at night or in the test. */
    AFTER_RECONNECT("after_reconnect"),

    /** A night tick's sync found heart-rate rows with no real value ([StaleReason.NO_HEART_RATE_VALUES]). */
    NOT_MEASURING("not_measuring"),
}

/**
 * P7: the least time between two [HeartRateSettingTrigger.NOT_MEASURING] sends in one night. On the owner's phone
 * the band measured again within about 4 min of one send (2026-11-06), so a tick 5 to 15 min later that still
 * finds nothing means it did not reach the band. 30 min, like the reconnect cooldown, still tries a few times a
 * night without re-sending on every tick. Any send counts, so a Start night or after-reconnect send starts it too.
 */
val HEART_RATE_SETTING_RESEND_INTERVAL: Duration = Duration.ofMinutes(30)

/** P7: after a reconnect, only once Gadgetbridge said the band is connected again; before that the band cannot get it. */
fun shouldSendHeartRateSettingAfterReconnect(result: BandReconnectResult): Boolean = result.connectedReplyReceived

/**
 * P7: whether a night tick whose (final) sync is [outcome] sends the setting: the band syncs but measures nothing,
 * and nothing was sent in the last [HEART_RATE_SETTING_RESEND_INTERVAL]. From the night's first tick: unlike the
 * warning (P6's grace), sending is harmless. A last send in the future (clock set back) does not block.
 */
fun shouldSendHeartRateSettingForTick(outcome: SyncOutcome, lastSentAt: Instant?, now: Instant): Boolean =
    outcome.staleReason == StaleReason.NO_HEART_RATE_VALUES &&
        reconnectCooldownAllows(lastSentAt, now, HEART_RATE_SETTING_RESEND_INTERVAL)

/**
 * P7: whether a connection test report shows the band connected: a pass, or a sync that brought recent heart-rate
 * rows with no real value. A failed sync or [StaleReason.NO_NEW_SAMPLES] does not: the band may be out of reach.
 */
fun reportShowsBandConnected(report: SetupCheckReport): Boolean =
    report.isReady || report.staleReason == StaleReason.NO_HEART_RATE_VALUES

/**
 * P7: whether a connection test sends the setting once its attempts are over: the band is known connected, and
 * this test's own reconnect did not already send it (it sends right after a reconnect's reply).
 */
fun shouldSendHeartRateSettingAfterTest(run: ConnectionTestRun): Boolean =
    run.reconnectResult?.connectedReplyReceived != true && reportShowsBandConnected(run.report)

/** P7: whether the test sent the setting at all: after its reconnect's reply, or once its attempts were over. */
fun heartRateSettingSentDuringTest(run: ConnectionTestRun): Boolean =
    run.reconnectResult?.let(::shouldSendHeartRateSettingAfterReconnect) == true || shouldSendHeartRateSettingAfterTest(run)

/**
 * P7: Start night sends it on a real-band night with a band address. The band is not checked first: Start night
 * needs a recent passing test, and when the band is not connected Gadgetbridge only stores the value it already has.
 */
fun shouldSendHeartRateSettingAtStartNight(simulatedBandData: Boolean, deviceMac: String?): Boolean =
    !simulatedBandData && !deviceMac.isNullOrBlank()

/** P7: the Warn only line once the app has asked the band to turn heart rate back on. */
internal const val NO_HEART_RATE_READING_YET_RESENT_SETUP_LINE =
    "No heart-rate reading yet. The app asked the band to turn heart rate back on; readings should appear within a few minutes. If none arrives after you start, the app will warn you."

/** P7: the Require a reading failure once the app has asked; the manual toggle stays as the fallback. */
internal const val BAND_NOT_MEASURING_RESENT_SETUP_LINE =
    "Band connected but not measuring heart rate. The app asked the band to turn heart rate back on; test again in a few minutes. If it still fails, in Gadgetbridge band settings, switch \"Enable automatic heart rate measuring\" off and on."

/**
 * P7: [report] after this test sent the setting: flagged, and its "not measuring" band line (either mode) says the
 * app already asked the band and readings should come within a few minutes. Other lines are unchanged.
 */
fun withHeartRateSettingSent(report: SetupCheckReport): SetupCheckReport = report.copy(
    lines = report.lines.map { line ->
        when (line.text) {
            NO_HEART_RATE_READING_YET_SETUP_LINE -> line.copy(text = NO_HEART_RATE_READING_YET_RESENT_SETUP_LINE)
            BAND_NOT_MEASURING_SETUP_LINE -> line.copy(text = BAND_NOT_MEASURING_RESENT_SETUP_LINE)
            else -> line
        }
    },
    heartRateSettingSent = true,
)
