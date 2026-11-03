package com.nikita.sleepcycle.bridge

// File purpose: P5 (PER-130 part 2) - makes Gadgetbridge drop and re-open the band's Bluetooth link through its
// Intent API, and checks whether that API answers at all. No decisions about WHEN to do it here (see
// night/BandReconnectPolicy.kt); the band's MAC goes only into the broadcasts, never into a log or a result.

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

/**
 * P5: the pause between DISCONNECT and CONNECT, so Gadgetbridge has finished tearing the link down before it is
 * asked to open it again. The owner's phone disconnected at once over adb (2026-11-03); 3 s leaves margin
 * without making the owner wait noticeably at bedtime.
 */
val BAND_RECONNECT_DISCONNECT_PAUSE: Duration = Duration.ofSeconds(3)

/**
 * P5: how long to wait for BLUETOOTH_CONNECTED after CONNECT. The band re-initialized in 1 to 4 s on the owner's
 * phone (2026-11-03); 30 s is several times that, for a band at the edge of range. When it does not come, the
 * caller syncs once more anyway and judges by that: the reply itself has not yet been seen on the phone.
 */
val BAND_RECONNECT_REPLY_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * P5: how long the Intent API check waits for BLUETOOTH_CONNECTED. It only runs right after a passing connection
 * test, so the band is connected and Gadgetbridge answers CONNECT at once; 5 s is a generous margin for a reply
 * that should take milliseconds, and the only cost of a miss is a hint line.
 */
val INTENT_API_CHECK_TIMEOUT: Duration = Duration.ofSeconds(5)

/** What one reconnect did: whether Gadgetbridge said the band is connected again, and how long it took (REAL ms). */
data class BandReconnectResult(val connectedReplyReceived: Boolean, val durationMs: Long)

/**
 * P5: DISCONNECT, wait [BAND_RECONNECT_DISCONNECT_PAUSE], CONNECT, then wait up to [BAND_RECONNECT_REPLY_TIMEOUT]
 * for BLUETOOTH_CONNECTED naming this band. Never throws for a missing reply: that is reported as
 * [BandReconnectResult.connectedReplyReceived] false, and the caller's next sync decides whether it worked.
 */
suspend fun reconnectBand(context: Context, deviceMac: String): BandReconnectResult {
    // Real time: measures how long the Bluetooth round trip itself took, like BandDataSync's own durations.
    val startedAt = Instant.now()
    sendBluetoothDisconnect(context, deviceMac)
    delay(BAND_RECONNECT_DISCONNECT_PAUSE.toMillis())
    val replyArrived = awaitBandConnected(context, deviceMac, BAND_RECONNECT_REPLY_TIMEOUT)
    return BandReconnectResult(replyArrived, Duration.between(startedAt, Instant.now()).toMillis())
}

/**
 * P5: sends CONNECT to an already connected band and waits [INTENT_API_CHECK_TIMEOUT] for its reply. True when
 * BLUETOOTH_CONNECTED came back, meaning Gadgetbridge's Intent API Bluetooth toggle is on and the app can
 * reconnect the band. CONNECT on a connected band only logs "device already connected" in Gadgetbridge.
 */
suspend fun checkBluetoothIntentApiAnswers(context: Context, deviceMac: String): Boolean =
    awaitBandConnected(context, deviceMac, INTENT_API_CHECK_TIMEOUT)

private suspend fun awaitBandConnected(context: Context, deviceMac: String, timeout: Duration): Boolean =
    awaitGadgetbridgeBroadcast(
        context,
        setOf(ACTION_BLUETOOTH_CONNECTED),
        timeout,
        accept = { intent -> isForBand(intent, deviceMac) }
    ) { sendBluetoothConnect(context, deviceMac) } != null

/** A reply for another Gadgetbridge device must not count as this band's. A reply with no address is taken as ours: the owner has one band. */
private fun isForBand(intent: Intent, deviceMac: String): Boolean {
    val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return true
    return address.equals(deviceMac, ignoreCase = true)
}
