package com.nikita.sleepcycle.night

// File purpose: V10 REPLACES the original version of this file. That version re-implemented
// DebugScreenController's own setSpeed/applyClockJump arithmetic locally instead of calling it, which is
// exactly why V6's clobber (two un-atomic DataStore writes from a stale snapshot) and V7's split store
// (DebugOptions.speed and ClockWarp.speed drifting apart) were both invisible to it - neither bug lives in the
// pure warp arithmetic that file modelled, they live in the impure write path and the persisted shape around
// it. Its "reset always lands on NONE" test also looped four times asserting `classify(null) == NONE`, using
// the loop variable only in the failure message - a tautology that could not fail.
//
// This version:
// - drives [computeSpeedChangeWarp]/[computeJumpWarp], the ACTUAL functions
//   [com.nikita.sleepcycle.ui.DebugScreenController.setSpeed]/[applyClockJump] call (extracted FROM those
//   methods, not re-derived), chained across realistic multi-step sessions - the state-machine coverage the
//   original file had, now anchored to the real code.
// - exercises V5's own re-arm gate, [shouldRearmPendingNudge], and V4's own toggle gate,
//   [isSimulatedBandDataToggleAllowed] - both genuinely new pure decision seams
//   [com.nikita.sleepcycle.ui.DebugScreenController] and [rearmAfterSpeedChange] call.
// - reproduces V7's actual bug through [decodeNightState]/[encodeNightState] - the only Context-free way to
//   exercise the persisted shape at all in this codebase (see the note below).
//
// A note on what this file does NOT do, and why: [com.nikita.sleepcycle.ui.DebugScreenController] itself -
// and V6's own writeDebugOptions/updateDebugOptions race - cannot be driven directly from a JVM unit test in
// this repository. Both are built on `Context.preferencesDataStore`, and this module has no Robolectric (or
// any other Android test harness) dependency to fake a Context with - `app/build.gradle.kts`'s test deps are
// JUnit Jupiter and org.json only. Per this task's own instruction ("if driving a path needs a Context, extract
// the decision it turns on into a pure function and test that"), V6's fix (an atomic read-modify-write inside
// DataStore's own edit{}) is verified by code construction/inspection instead of by an automated test - see the
// fix round's own report for detail. Introducing Robolectric to close this gap is a real, reasonable follow-up,
// but it is a build-dependency change outside this fix round's own scope.

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.json.JSONObject
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/** One of the four legal states U1/U2 define, purely for readable assertions below - not re-derived arithmetic. */
private enum class WarpState { NONE, JUMP_AT_1X, SPEED_ONLY, JUMP_PLUS_SPEED }

private fun classify(warp: ClockWarp?): WarpState = when {
    warp == null -> WarpState.NONE
    warp.speed == 1 -> WarpState.JUMP_AT_1X
    warp.anchorVirtual == warp.anchorReal -> WarpState.SPEED_ONLY
    else -> WarpState.JUMP_PLUS_SPEED
}

class ClockWarpTransitionsTest {
    private val t0 = Instant.parse("2026-09-17T20:00:00Z")

    // ---- Transition: set speed - via computeSpeedChangeWarp, the function setSpeed itself calls -------------

    @Test
    fun `set speed 1 from NONE stays NONE`() {
        val warp = computeSpeedChangeWarp(currentWarp = null, speed = 1, realNow = t0)
        assertEquals(WarpState.NONE, classify(warp))
        assertNull(warp)
    }

    @Test
    fun `set speed 60 from NONE moves to SPEED_ONLY, anchored at the current instant`() {
        val warp = computeSpeedChangeWarp(currentWarp = null, speed = 60, realNow = t0)
        assertEquals(WarpState.SPEED_ONLY, classify(warp))
        assertEquals(ClockWarp(60, t0, t0), warp)
    }

    @Test
    fun `set speed 1 again from SPEED_ONLY with real time elapsed moves to JUMP_AT_1X - running fast IS a jump`() {
        val speedOnly = computeSpeedChangeWarp(currentWarp = null, speed = 60, realNow = t0)
        val tenSecondsLater = t0.plus(Duration.ofSeconds(10))

        val warp = computeSpeedChangeWarp(speedOnly, speed = 1, realNow = tenSecondsLater)

        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        // 10 real seconds at 60x is 10 virtual minutes - the drift accumulated while running fast is preserved.
        assertEquals(t0.plus(Duration.ofMinutes(10)), warp?.anchorVirtual)
    }

    @Test
    fun `set speed 1 again from SPEED_ONLY with NO real time elapsed collapses back to NONE`() {
        val speedOnly = computeSpeedChangeWarp(currentWarp = null, speed = 60, realNow = t0)
        val warp = computeSpeedChangeWarp(speedOnly, speed = 1, realNow = t0)
        assertEquals(WarpState.NONE, classify(warp))
    }

    @Test
    fun `set a non-1 speed from JUMP_AT_1X moves to JUMP_PLUS_SPEED, keeping the drift`() {
        val jumpAt1x = ClockWarp(speed = 1, anchorReal = t0, anchorVirtual = t0.plus(Duration.ofHours(5)))
        val warp = computeSpeedChangeWarp(jumpAt1x, speed = 600, realNow = t0)
        assertEquals(WarpState.JUMP_PLUS_SPEED, classify(warp))
        assertEquals(t0.plus(Duration.ofHours(5)), warp?.anchorVirtual)
    }

    @Test
    fun `set speed 1 from JUMP_PLUS_SPEED moves to JUMP_AT_1X, never discarding the jump`() {
        val jumpPlusSpeed = ClockWarp(speed = 60, anchorReal = t0, anchorVirtual = t0.plus(Duration.ofHours(5)))
        val oneMinuteLater = t0.plus(Duration.ofMinutes(1))
        val warp = computeSpeedChangeWarp(jumpPlusSpeed, speed = 1, realNow = oneMinuteLater)
        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        assertEquals(t0.plus(Duration.ofHours(6)), warp?.anchorVirtual)
    }

    // ---- Transition: apply jump - via computeJumpWarp, the function applyClockJump itself calls -------------

    @Test
    fun `a jump from NONE at speed 1 moves to JUMP_AT_1X`() {
        val newVirtual = t0.plus(Duration.ofHours(7))
        val warp = computeJumpWarp(currentWarp = null, newVirtual = newVirtual, realNow = t0)
        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        assertEquals(ClockWarp(1, t0, newVirtual), warp)
    }

    @Test
    fun `a jump while SPEED_ONLY keeps the speed and moves to JUMP_PLUS_SPEED`() {
        val speedOnly = ClockWarp(speed = 60, anchorReal = t0, anchorVirtual = t0)
        val newVirtual = t0.plus(Duration.ofHours(7))
        val warp = computeJumpWarp(speedOnly, newVirtual, realNow = t0)
        assertEquals(WarpState.JUMP_PLUS_SPEED, classify(warp))
        assertEquals(60, warp?.speed)
        assertEquals(newVirtual, warp?.anchorVirtual)
    }

    @Test
    fun `a second jump while already JUMP_AT_1X re-anchors to the new instant, still JUMP_AT_1X`() {
        val jumpAt1x = ClockWarp(speed = 1, anchorReal = t0, anchorVirtual = t0.plus(Duration.ofHours(5)))
        val newVirtual = t0.plus(Duration.ofHours(1))
        val warp = computeJumpWarp(jumpAt1x, newVirtual, realNow = t0)
        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        assertEquals(newVirtual, warp?.anchorVirtual)
    }

    // ---- A realistic multi-step session, chained through the SAME functions the controller calls ------------

    @Test
    fun `a realistic session - set 60x, run for a while, drop to 1x, jump, raise to 600x - stays legal at every step`() {
        var warp: ClockWarp? = null
        assertEquals(WarpState.NONE, classify(warp))

        warp = computeSpeedChangeWarp(warp, speed = 60, realNow = t0)
        assertEquals(WarpState.SPEED_ONLY, classify(warp))

        val afterRunning = t0.plus(Duration.ofMinutes(2))
        warp = computeSpeedChangeWarp(warp, speed = 1, realNow = afterRunning)
        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        assertEquals(t0.plus(Duration.ofHours(2)), warp?.anchorVirtual)

        val jumpTarget = t0.plus(Duration.ofHours(9))
        warp = computeJumpWarp(warp, jumpTarget, realNow = afterRunning)
        assertEquals(WarpState.JUMP_AT_1X, classify(warp))
        assertEquals(jumpTarget, warp?.anchorVirtual)

        warp = computeSpeedChangeWarp(warp, speed = 600, realNow = afterRunning)
        assertEquals(WarpState.JUMP_PLUS_SPEED, classify(warp))
        assertEquals(jumpTarget, warp?.anchorVirtual)

        // "Reset to real time" writes a literal null - nothing to compute, see DebugScreenController.clearClockWarp.
        warp = null
        assertEquals(WarpState.NONE, classify(warp))
    }

    // ---- V4: isSimulatedBandDataToggleAllowed - the switch is disabled outright while a night is active -----

    @Test
    fun `the simulated band data toggle is refused while a night is active`() {
        assertFalse(isSimulatedBandDataToggleAllowed(nightActive = true))
        assertTrue(isSimulatedBandDataToggleAllowed(nightActive = false))
    }

    // ---- V5: shouldRearmPendingNudge - the gate rearmAfterSpeedChange uses ------------------------------------

    @Test
    fun `a pending nudge still in the virtual future is re-armed`() {
        val now = t0
        val pendingNudgeAt = t0.plus(Duration.ofMinutes(5))
        assertTrue(shouldRearmPendingNudge(pendingNudgeAt, now))
    }

    @Test
    fun `an overdue pending nudge is left alone, same convention as BootReceiver's own reboot recovery`() {
        val now = t0
        val pendingNudgeAt = t0.minus(Duration.ofMinutes(1))
        assertFalse(shouldRearmPendingNudge(pendingNudgeAt, now))
    }

    @Test
    fun `no pending nudge at all is never re-armed`() {
        assertFalse(shouldRearmPendingNudge(pendingNudgeAt = null, now = t0))
    }

    @Test
    fun `a nudge due at exactly now is not re-armed - isAfter, not isAfter-or-equal`() {
        assertFalse(shouldRearmPendingNudge(pendingNudgeAt = t0, now = t0))
    }

    // ---- V7: one source of truth for speed - reproduced through the actual persisted-state round trip --------
    //
    // This is the one genuine pre-fix regression in this file achievable without a Context: DebugOptions.speed
    // used to be its own persisted field, independent of the warp. Process death between writeDebugOptions and
    // writeClockWarp (both un-atomic, separate coroutines/keys) could leave a state file with a stale `speed`
    // that disagrees with `warp` - exactly what this test constructs by hand. V7 makes speed DERIVED
    // (`warp?.speed ?: 1`), so decoding a JSON blob that still carries a disagreeing `speed` key must be
    // ignored, not decoded into the object.

    @Test
    fun `V7 a night state whose debugOptions blob carries a stale speed disagreeing with its warp reads the WARP's speed, not the stale field`() {
        val json = fullNightStateJson().apply {
            put(
                "debugOptions",
                JSONObject().apply {
                    put("simulatedBandData", true)
                    // A stale 60 left over from a process death between the two un-atomic writes V7 fixes -
                    // warp itself is null, meaning the clock is NOT actually warped.
                    put("speed", 60)
                    put("warp", JSONObject.NULL)
                }
            )
        }

        val state = decodeNightState(json.toString())

        assertEquals(1, state.debugOptions.speed, "V7: speed must be derived from warp (null here), never the stale persisted field")
        assertNull(state.debugOptions.warp)
    }

    @Test
    fun `V7 a night state's debugOptions speed always matches its own warp's speed, even when a stale field disagrees`() {
        val warp = ClockWarp(600, t0, t0)
        val json = fullNightStateJson().apply {
            put(
                "debugOptions",
                JSONObject().apply {
                    put("simulatedBandData", true)
                    // Stale 60 again, but this time a REAL warp at 600 is also present - the derived speed must
                    // follow the warp (600), never the stale field (60) and never silently average or prefer
                    // whichever was written last.
                    put("speed", 60)
                    put("warp", JSONObject().apply { put("speed", 600); put("anchorReal", t0.toString()); put("anchorVirtual", t0.toString()) })
                }
            )
        }

        val state = decodeNightState(json.toString())

        assertEquals(600, state.debugOptions.speed)
        assertEquals(warp, state.debugOptions.warp)
    }

    /** The minimum well-formed night_state.json this file's own decodeNightState requires, before `debugOptions` is overwritten by each test above. */
    private fun fullNightStateJson(): JSONObject = JSONObject().apply {
        put("startedAt", t0.toString())
        put("settings", JSONObject().apply { put("deadline", JSONObject.NULL); put("pickedCycles", 4) })
        put("lastPlan", JSONObject.NULL)
        put("lastSyncAt", JSONObject.NULL)
        put("lastSyncOk", JSONObject.NULL)
        put("lastSegments", org.json.JSONArray())
        put("lastExportFileModifiedAt", JSONObject.NULL)
        put("lastSyncFailureCause", JSONObject.NULL)
        put("phoneAlarmFiredFor", JSONObject.NULL)
        put("debugOptions", JSONObject.NULL)
        put("awakeConfirmedAt", JSONObject.NULL)
        put("napAlarmsUsed", 0)
        put("wakeAlarmFiredAt", JSONObject.NULL)
        put("morningAlarmAt", JSONObject.NULL)
        put("lastNapAlarmFiredAt", JSONObject.NULL)
    }
}
