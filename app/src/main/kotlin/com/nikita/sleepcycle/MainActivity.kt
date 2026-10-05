package com.nikita.sleepcycle

import android.content.Intent
import com.nikita.sleepcycle.bridge.openGadgetbridge
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.nikita.sleepcycle.ui.NightViewModel
import com.nikita.sleepcycle.ui.components.ErrorBanner
import com.nikita.sleepcycle.ui.components.RatingSymptomsDialog
import com.nikita.sleepcycle.ui.shareNightLogFile
import com.nikita.sleepcycle.ui.screens.BeforeBedScreen
import com.nikita.sleepcycle.ui.screens.LogsScreen
import com.nikita.sleepcycle.ui.screens.PastNightScreen
import com.nikita.sleepcycle.ui.screens.SettingsScreen
import com.nikita.sleepcycle.ui.screens.SetupScreen
import com.nikita.sleepcycle.ui.screens.night.NightScreen
import com.nikita.sleepcycle.ui.state.Screen
import com.nikita.sleepcycle.ui.theme.SleepCycleAlarmTheme

/** Entry point activity: hosts the one Compose UI tree, driven entirely by [NightViewModel]'s UiState. */
class MainActivity : ComponentActivity() {
    private val viewModel: NightViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Owner spec, 2026-10-04: the later question's Okay or Bad button opens the app through this intent. Only
        // a fresh launch acts on it: a rotation (saved state) or a reopen from Recents (which redelivers the same
        // intent) must not record the rating and open the dialog a second time.
        val launchedFromRecents = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromRecents) viewModel.openFromIntent(intent)
        // Force the "dark" bar style (light icons) unconditionally, rather than the default auto style that
        // follows the SYSTEM light/dark setting: this app is always dark, so auto style would draw dark
        // (hard to read) icons over our near-black background whenever the phone itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            SleepCycleAlarmTheme {
                SleepCycleApp(viewModel)
            }
        }
    }

    /** The app already open when Okay or Bad is tapped on the later question: the same intent arrives here instead. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.openFromIntent(intent)
    }
}

@Composable
private fun SleepCycleApp(viewModel: NightViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val setupWizardPage by viewModel.setupWizardPage.collectAsState()
    val pastNight by viewModel.pastNight.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val simulatedStartTime by viewModel.simulatedStartTime.collectAsState()
    val morningRating by viewModel.morningRating.collectAsState()
    val symptomsDialog by viewModel.symptomsDialog.collectAsState()

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
                    is Screen.Settings -> settings?.let { saved ->
                        SettingsScreen(
                            afterAlarm = saved.afterAlarm,
                            bedtimeAudio = saved.bedtimeAudio,
                            sleepRating = saved.sleepRating,
                            onAfterAlarmChange = viewModel::setAfterAlarmSettings,
                            onBedtimeAudioChange = viewModel::setBedtimeAudioSettings,
                            onSleepRatingChange = viewModel::setSleepRatingSettings,
                            debug = uiState.debug,
                            onSimulatedBandDataChange = viewModel::setSimulatedBandData,
                            simulatedStartTime = simulatedStartTime,
                            onSimulatedStartTimeChange = viewModel::setSimulatedStartTime,
                            onRingTestAlarm = viewModel::ringDebugTestAlarm,
                            onOpenSetup = viewModel::openSetup,
                            onOpenGadgetbridge = { openGadgetbridge(context) },
                            onBack = viewModel::closeSettings,
                        )
                    }
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
                        // The wizard's Debug button: the Debug controls are a Settings section now (2026-10-02).
                        onOpenDebug = viewModel::openSettings,
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
                        onTestAgain = viewModel::runSetupCheckAction,
                        onOpenGadgetbridge = { openGadgetbridge(context) },
                    )
                    is Screen.Night -> uiState.night?.let { nightState ->
                        NightScreen(
                            state = nightState,
                            debug = uiState.debug,
                            onRequestEndNight = viewModel::requestEndNight,
                            onConfirmEndNight = viewModel::confirmEndNight,
                            onCancelEndNight = viewModel::cancelEndNight,
                            onStartNap = viewModel::startNap,
                            onRequestImUp = viewModel::requestImUp,
                            onConfirmImUp = viewModel::confirmImUp,
                            onCancelImUp = viewModel::cancelImUp,
                            onDone = viewModel::finishMorningReport,
                            morningRating = morningRating,
                            onRateMorning = viewModel::rateMorning,
                            onEditMorningSymptoms = viewModel::editMorningSymptoms,
                            onSpeedChoice = viewModel::setSpeedChoice,
                            onSetSimulatedAsleep = viewModel::setSimulatedAsleep,
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
                        PastNightScreen(
                            state = night,
                            onBack = viewModel::closePastNight,
                            onRate = viewModel::ratePastNight,
                            onEditSymptoms = viewModel::editPastNightSymptoms,
                        )
                    }
                }
            }
        }
        // Owner spec, 2026-10-04: the "why Okay or Bad" dialog, over whichever screen asked for it - the morning
        // report, Past night, or any screen the later question's Okay or Bad button opened the app on.
        symptomsDialog?.let { dialog ->
            RatingSymptomsDialog(
                state = dialog,
                onToggle = viewModel::toggleSymptom,
                onSave = viewModel::saveSymptoms,
                onSkip = viewModel::skipSymptoms,
            )
        }
    }
}
