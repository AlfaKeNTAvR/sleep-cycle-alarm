package com.nikita.sleepcycle.alarm

// File purpose: full-screen alarm activity - shows over the lock screen with one Stop button, no snooze in v1.
// Not part of the ui/ package: it is system-integration glue the alarm flow needs, not app navigation.

import android.app.KeyguardManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
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

@Composable
private fun AlarmStopScreen(onStop: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
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
