package com.nikita.sleepcycle

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import com.nikita.sleepcycle.ui.screens.PastNightScreen
import com.nikita.sleepcycle.ui.screens.SettingsScreen
import com.nikita.sleepcycle.ui.screens.SetupScreen
import com.nikita.sleepcycle.ui.screens.night.NightScreen
import com.nikita.sleepcycle.ui.state.Screen
import com.nikita.sleepcycle.ui.theme.SleepCycleAlarmTheme

/** Entry point activity: hosts the one Compose UI tree, driven entirely by [NightViewModel]'s UiState. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force the "dark" bar style (light icons) unconditionally, rather than the default auto style that
        // follows the SYSTEM light/dark setting: this app is always dark, so auto style would draw dark
        // (hard to read) icons over our near-black background whenever the phone itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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
    val setupWizardPage by viewModel.setupWizardPage.collectAsState()
    val pastNight by viewModel.pastNight.collectAsState()

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
                    is Screen.Settings -> SettingsScreen(
                        onOpenSetup = viewModel::openSetup,
                        onOpenDebug = viewModel::openDebug,
                        onBack = viewModel::closeSettings,
                    )
                    is Screen.Setup -> SetupScreen(
                        state = uiState.setup,
                        wizardPage = setupWizardPage,
                        onDeviceMacChange = viewModel::setDeviceMac,
                        onExportUriPicked = viewModel::setExportUri,
                        onRunConnectionTest = viewModel::runSetupCheckAction,
                        onWizardNext = viewModel::setupWizardNext,
                        onWizardBack = viewModel::setupWizardBack,
                        onRunSetupAgain = viewModel::runSetupWizardAgain,
                        onExitWizard = viewModel::exitSetupWizard,
                        onBack = viewModel::closeSetup,
                        onOpenDebug = viewModel::openDebug,
                    )
                    is Screen.BeforeBed -> BeforeBedScreen(
                        state = uiState.beforeBed,
                        onOpenSettings = viewModel::openSettings,
                        onOpenLogs = viewModel::openLogs,
                        onDeadlineEnabledChange = viewModel::setDeadlineEnabled,
                        onDeadlineTimeChange = viewModel::setDeadlineTime,
                        onSleepLengthPicked = viewModel::setPickedCycles,
                        onStartNight = viewModel::requestStartNight,
                        onConfirmDebugNightStart = viewModel::confirmDebugNightStart,
                        onCancelDebugNightStart = viewModel::cancelDebugNightStart,
                    )
                    is Screen.Night -> uiState.night?.let { nightState ->
                        NightScreen(
                            state = nightState,
                            debug = uiState.debug,
                            onRequestEndNight = viewModel::requestEndNight,
                            onConfirmEndNight = viewModel::confirmEndNight,
                            onCancelEndNight = viewModel::cancelEndNight,
                            onDone = viewModel::finishMorningReport,
                            onSpeedChange = viewModel::setSpeed,
                            onSetSimulatedAsleep = viewModel::setSimulatedAsleep,
                            onOpenDebug = viewModel::openDebug,
                        )
                    }
                    is Screen.Logs -> LogsScreen(
                        state = uiState.logs,
                        onOpenLog = viewModel::openNightLog,
                        onShareLog = { log -> shareNightLogFile(context, log) },
                        onDeleteLog = viewModel::deleteNightLog,
                        onBack = viewModel::closeLogs,
                    )
                    is Screen.PastNight -> pastNight?.let { night ->
                        PastNightScreen(state = night, onBack = viewModel::closePastNight)
                    }
                    is Screen.Debug -> DebugScreen(
                        state = uiState.debug,
                        onSimulatedBandDataChange = viewModel::setSimulatedBandData,
                        onSpeedChange = viewModel::setSpeed,
                        onSetSimulatedAsleep = viewModel::setSimulatedAsleep,
                        onClearSimulatedSleep = viewModel::clearSimulatedSleep,
                        onApplyClockJump = viewModel::applyClockJump,
                        onResetToRealTime = viewModel::resetClockToRealTime,
                        onRingTestAlarm = viewModel::ringDebugTestAlarm,
                        onBack = viewModel::closeDebug,
                    )
                }
            }
        }
    }
}
