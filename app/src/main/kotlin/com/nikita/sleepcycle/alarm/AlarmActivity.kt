package com.nikita.sleepcycle.alarm

// File purpose: full-screen alarm activity - shows over the lock screen. N1 (owner decision, 2026-09-21):
// ONE action, "Stop", which silences the sound and vibration only. D6's second action, "I'm awake", also
// ended the night outright through the endNight path (recording awakeConfirmedAt) - removed because a
// mis-tap on a lock screen at 03:00 then cancelled every alarm left in the night, including the repeating
// out-of-bed nudge whose whole purpose (L1) is that it cannot be got rid of by accident. Ending a night now
// takes the night screen's own button, behind its own confirmation, which is the point: the owner has to be
// awake enough to open the app. H6: "Stop" does NOT promise the night keeps running - most of the time it
// does, and D5/rule 7's nap mode still applies, but not when this is the alarm that just spent the D5/G8 nap
// cap (H2). L2 (owner decision, 2026-09-21) CORRECTS what this used to say about that case: this ring has
// already armed its own out-of-bed nudge by the time this screen shows it, so the tick that reaches FINISHED
// finds a pending nudge and DEFERS its end-of-night bookkeeping (NightController.finishNightIfNeeded) rather
// than running it - tracking has NOT already stopped. "Stop" then leaves a night that is still nominally live,
// with nothing left to book it another tick, until the nudge chain itself ends (or goes stale past L3.1's
// bound). D4: when this screen is the out-of-bed nudge rather than the wake alarm, [isOutOfBed] changes the
// wording - everything else about the screen, and every stop/end path, is identical either way. Not part of
// the ui/ package: it is system-integration glue the alarm flow needs, not app navigation.
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.components.ScreenContainer

class AlarmActivity : ComponentActivity() {
    /**
     * W18: which ring this is, in words, so a 03:40 ring is not a guess between the nap alarm, the morning
     * alarm and a leftover debug test. It replaces the bare `isOutOfBed` flag this screen used to hold:
     * [readAlarmLabel] falls back to that same flag, so nothing is lost for an intent from an older build.
     */
    private var label by mutableStateOf(AlarmLabel.MORNING)

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
        label = intent.readAlarmLabel()
        setContent {
            AlarmStopScreen(label = label, onStop = ::stopOnly)
        }
    }

    /** F10: re-reads the firing intent's own label for a new firing delivered to this same singleInstance activity, so the wording never goes stale when the nudge arrives while the wake screen (or a previous nudge screen) is still on top. [label] is Compose state, so the running screen recomposes with the new wording immediately - no new setContent call needed. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        label = intent.readAlarmLabel()
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    /**
     * D6 "Stop": silences the sound and vibration only. Usually the night keeps running and D5/rule 7's nap
     * mode still applies - H6: EXCEPT when this ring is the one that just spent the D5/G8 nap cap (H2). L2
     * (owner decision, 2026-09-21) CORRECTS what this used to say: the night has NOT already finished by the
     * time this button is tapped - this ring armed a nudge, so the tick reaching FINISHED deferred instead
     * (NightController.finishNightIfNeeded). "The night keeps running" still does not mean much here, since
     * nothing books this night another tick until the nudge chain itself ends.
     */
    private fun stopOnly() {
        stopAlarmRinging(this)
        finish()
    }

}

/**
 * The alarm's ring screen, kept full-bleed like every other screen but clearing the status bar, navigation
 * bar and display cutout the same way, via the shared [ScreenContainer]. [label] (W18) names the ring and
 * chooses the headline; the single action behaves the same for every ring, the debug test one included (N1 -
 * the test ring used to be the one case that differed, precisely because the removed second action would have
 * ended a real night running in the background during a daylight ring test).
 */
@Composable
private fun AlarmStopScreen(label: AlarmLabel, onStop: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ScreenContainer(scrollable = false) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = stringResource(alarmLabelNameRes(label)), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(alarmLabelInstructionRes(label)), style = MaterialTheme.typography.headlineLarge)
                Button(onClick = onStop) {
                    Text(text = stringResource(R.string.alarm_action_stop))
                }
            }
        }
    }
}
