package com.nikita.sleepcycle.bridge

// File purpose: waits for one of a set of Gadgetbridge result broadcasts, with a timeout, without missing a fast reply.

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import kotlin.coroutines.resume

private const val LOG_TAG = "GadgetbridgeBroadcastWait"

/**
 * Registers a receiver for [actions] first, then runs [send], then waits up to [timeout].
 * Returns the action that arrived, or null on timeout. Registering before sending avoids missing
 * a reply that comes back faster than we could register for it.
 */
suspend fun awaitGadgetbridgeBroadcast(
    context: Context,
    actions: Set<String>,
    timeout: Duration,
    send: () -> Unit
): String? = withTimeoutOrNull(timeout.toMillis()) {
    suspendCancellableCoroutine { continuation ->
        lateinit var receiver: BroadcastReceiver
        receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedContext: Context, intent: Intent) {
                unregisterReceiverQuietly(context, this)
                if (continuation.isActive) continuation.resume(intent.action)
            }
        }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        continuation.invokeOnCancellation { unregisterReceiverQuietly(context, receiver) }
        send()
    }
}

/**
 * Unregistering can race with the receiver having already unregistered itself on delivery
 * (cancellation fires right after onReceive). That race is expected, not an error, so it is
 * logged at debug level rather than treated as a failure.
 */
private fun unregisterReceiverQuietly(context: Context, receiver: BroadcastReceiver) {
    try {
        context.unregisterReceiver(receiver)
    } catch (error: IllegalArgumentException) {
        Log.d(LOG_TAG, "receiver was already unregistered", error)
    }
}
