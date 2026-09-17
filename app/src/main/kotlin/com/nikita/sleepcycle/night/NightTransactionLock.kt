package com.nikita.sleepcycle.night

// File purpose: the one mutex that serializes the whole night-tick transaction (load, sync, plan, band,
// phone, save, schedule) across every caller - service ticks, immediate ticks from the UI, and endNight -
// so a tick can never race a state change or resurrect state after the night has ended. Not reentrant:
// nothing that runs inside the lock may call back into it.

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val nightTransactionMutex = Mutex()

/** Runs [block] with the whole-night-transaction lock held, so no two callers ever touch night state at once. */
suspend fun <T> withNightTransactionLock(block: suspend () -> T): T = nightTransactionMutex.withLock { block() }
