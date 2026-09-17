package com.nikita.sleepcycle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikita.sleepcycle.ui.NightViewModel
import com.nikita.sleepcycle.ui.components.ErrorBanner
import com.nikita.sleepcycle.ui.shareNightLogFile
import com.nikita.sleepcycle.ui.screens.BeforeBedScreen
import com.nikita.sleepcycle.ui.screens.DebugScreen
import com.nikita.sleepcycle.ui.screens.LogsScreen
import com.nikita.sleepcycle.ui.screens.SetupScreen
import com.nikita.sleepcycle.ui.screens.night.NightScreen
import com.nikita.sleepcycle.ui.state.Screen
import com.nikita.sleepcycle.ui.theme.SleepCycleAlarmTheme

/** Entry point activity: hosts the one Compose UI tree, driven entirely by [NightViewModel]'s UiState. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SleepCycleAlarmTheme {
                SleepCycleApp()
            }
        }
    }
}

@Composable
private fun SleepCycleApp(viewModel: NightViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LifecycleResumeEffect(Unit) {
        viewModel.setScreenVisible(true)
        viewModel.onResumed()
        onPauseOrDispose { viewModel.setScreenVisible(false) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            ErrorBanner(message = uiState.errorMessage, onDismiss = viewModel::clearErrorMessage)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (uiState.screen) {
                    is Screen.Setup -> SetupScreen(
                        state = uiState.setup,
                        onDeviceMacChange = viewModel::setDeviceMac,
                        onExportUriPicked = viewModel::setExportUri,
                        onRunConnectionTest = viewModel::runSetupCheckAction,
                        onBack = viewModel::closeSetupOrLogs,
                        onOpenDebug = viewModel::openDebug,
                    )
                    is Screen.BeforeBed -> BeforeBedScreen(
                        state = uiState.beforeBed,
                        onOpenSetup = viewModel::openSetup,
                        onOpenLogs = viewModel::openLogs,
                        onDeadlineEnabledChange = viewModel::setDeadlineEnabled,
                        onDeadlineTimeChange = viewModel::setDeadlineTime,
                        onSleepLengthPicked = viewModel::setPickedCycles,
                        onPhoneBackupChange = viewModel::setPhoneBackupEnabled,
                        onStartNight = viewModel::requestStartNight,
                        onConfirmDebugNightStart = viewModel::confirmDebugNightStart,
                        onCancelDebugNightStart = viewModel::cancelDebugNightStart,
                    )
                    is Screen.Night -> uiState.night?.let { nightState ->
                        NightScreen(
                            state = nightState,
                            onRequestEndNight = viewModel::requestEndNight,
                            onConfirmEndNight = viewModel::confirmEndNight,
                            onCancelEndNight = viewModel::cancelEndNight,
                            onDone = viewModel::finishMorningReport,
                            onOpenDebug = viewModel::openDebug,
                        )
                    }
                    is Screen.Logs -> LogsScreen(
                        state = uiState.logs,
                        onShareLog = { log -> shareNightLogFile(context, log) },
                        onBack = viewModel::closeSetupOrLogs,
                    )
                    is Screen.Debug -> DebugScreen(
                        state = uiState.debug,
                        onSimulatedBandDataChange = viewModel::setSimulatedBandData,
                        onFastNightChange = viewModel::setFastNight,
                        onDryRunBandCommandsChange = viewModel::setDryRunBandCommands,
                        onFellAsleep = viewModel::fellAsleepNow,
                        onWokeUp = viewModel::wokeUpNow,
                        onFellBackAsleep = viewModel::fellBackAsleepNow,
                        onClearSimulatedSleep = viewModel::clearSimulatedSleep,
                        onRingTestAlarm = viewModel::ringDebugTestAlarm,
                        onBack = viewModel::closeDebug,
                    )
                }
            }
        }
    }
}
