package com.nikita.sleepcycle.alarm

// File purpose: full-screen alarm activity - shows over the lock screen. D6: two actions, "Stop" (silences
// the sound and vibration only) and "I'm awake" (also ends the night through the existing endNight path,
// recording awakeConfirmedAt). H6: "Stop" does NOT promise the night keeps running - most of the time it
// does, and D5/rule 7's nap mode still applies, but not when this is the alarm that just spent the D5/G8 nap
// cap (H2): by the time this screen is even showing that ring, the tick that reached FINISHED has already run
// its own end-of-night bookkeeping (see NightController.finishNightIfNeeded) and tracking has already
// stopped - "Stop" then only silences a ring that nothing is tracking any more. D4: when this screen
// is the out-of-bed nudge rather than the wake alarm, [isOutOfBed] changes the wording - everything else about
// the screen, and every stop/end path, is identical either way. Not part of the ui/ package: it is
// system-integration glue the alarm flow needs, not app navigation.
//
// F10: launchMode="singleInstance" means a second firing (typically the nudge arriving while the wake screen
// is still open, now routine since F1's restart fix keeps the ring session alive across the two) delivers a
// new Intent to THIS same instance via onNewIntent rather than creating a fresh one - onCreate's own read of
// EXTRA_ALARM_IS_OUT_OF_BED_NUDGE would otherwise never re-run, leaving the screen showing "Wake up" under a
// ring that is, by then, actually the out-of-bed nudge.

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.nikita.sleepcycle.night.endNight
import com.nikita.sleepcycle.night.nowInstant
import com.nikita.sleepcycle.ui.components.ScreenContainer
import kotlinx.coroutines.launch

class AlarmActivity : ComponentActivity() {
    private var isOutOfBed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        // Same forced-dark bar style as MainActivity.enableEdgeToEdge (MainActivity.kt): this screen is
        // full-bleed by default under targetSdk 36's enforced edge-to-edge, so without this the status/nav
        // bar icons would follow the system light/dark setting and can go dark-on-dark over our background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        isOutOfBed = intent?.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false) ?: false
        setContent {
            AlarmStopScreen(isOutOfBed = isOutOfBed, onStop = ::stopOnly, onImAwake = ::confirmAwakeAndFinish)
        }
    }

    /** F10: re-reads EXTRA_ALARM_IS_OUT_OF_BED_NUDGE for a new firing delivered to this same singleInstance activity, so the wording never goes stale when the nudge arrives while the wake screen (or a previous nudge screen) is still on top. [isOutOfBed] is Compose state, so the running screen recomposes with the new wording immediately - no new setContent call needed. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isOutOfBed = intent.getBooleanExtra(EXTRA_ALARM_IS_OUT_OF_BED_NUDGE, false)
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    /**
     * D6 "Stop": silences the sound and vibration only. Usually the night keeps running and D5/rule 7's nap
     * mode still applies - H6: EXCEPT when this ring is the one that just spent the D5/G8 nap cap (H2), in
     * which case the night has already finished and tracking has already stopped by the time this button is
     * even tapped; there is nothing left for "the night keeps running" to mean.
     */
    private fun stopOnly() {
        stopAlarmRinging(this)
        finish()
    }

    /**
     * D6 "I'm awake": silences the ringing and ends the night via the existing endNight path, recording
     * awakeConfirmedAt. endNight's own work runs on NightController's module-level controllerScope, not this
     * Activity's lifecycleScope, so it completes in full even though finish() is called right after launching
     * it here - the same reason NightViewModel.confirmEndNight does not need to wait for it either.
     */
    private fun confirmAwakeAndFinish() {
        stopAlarmRinging(this)
        // T4: virtual - awakeConfirmedAt is recorded into night state, which is entirely in virtual time.
        val now = nowInstant()
        lifecycleScope.launch { endNight(applicationContext, now, awakeConfirmedAt = now) }
        finish()
    }
}

/**
 * The alarm's ring screen, kept full-bleed like every other screen but clearing the status bar, navigation
 * bar and display cutout the same way, via the shared [ScreenContainer]. [isOutOfBed] (D4) only changes the
 * title; both actions behave the same either way (D6).
 */
@Composable
private fun AlarmStopScreen(isOutOfBed: Boolean, onStop: () -> Unit, onImAwake: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ScreenContainer(scrollable = false) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = if (isOutOfBed) "Time to get up" else "Wake up", style = MaterialTheme.typography.headlineLarge)
                Button(onClick = onStop) {
                    Text(text = "Stop")
                }
                Button(onClick = onImAwake) {
                    Text(text = "I'm awake")
                }
            }
        }
    }
}
