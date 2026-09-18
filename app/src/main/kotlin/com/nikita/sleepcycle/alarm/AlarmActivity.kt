package com.nikita.sleepcycle.alarm

// File purpose: full-screen alarm activity - shows over the lock screen with one Stop button, no snooze in v1.
// Not part of the ui/ package: it is system-integration glue the alarm flow needs, not app navigation.

import android.app.KeyguardManager
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.components.ScreenContainer

class AlarmActivity : ComponentActivity() {
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
        setContent {
            AlarmStopScreen(onStop = ::stopAndFinish)
        }
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
    }

    private fun stopAndFinish() {
        stopAlarmRinging(this)
        finish()
    }
}

/** The alarm's Stop screen, kept full-bleed like every other screen but clearing the status bar, navigation bar and display cutout the same way, via the shared [ScreenContainer]. */
@Composable
private fun AlarmStopScreen(onStop: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        ScreenContainer(scrollable = false) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Wake up", style = MaterialTheme.typography.headlineLarge)
                Button(onClick = onStop) {
                    Text(text = "Stop")
                }
            }
        }
    }
}
