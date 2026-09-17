package com.nikita.sleepcycle.ui.state

import java.time.LocalTime

/** The one immutable state the whole UI renders from. Every composable is a pure function of this plus callbacks. */
data class UiState(
    val screen: Screen,
    val setup: SetupUiState,
    val beforeBed: BeforeBedUiState,
    val night: NightUiState?,
    val logs: LogsUiState,
    val debug: DebugUiState,
    val errorMessage: String?,
) {
    companion object {
        /** A blank, safe-to-render state for before the first real settings/night data has loaded. */
        fun initial(): UiState = UiState(
            screen = Screen.Setup,
            setup = SetupUiState(
                deviceMacText = "",
                items = emptyList(),
                allComplete = false,
                connectionTest = ConnectionTestState.Idle,
                canRunConnectionTest = false,
            ),
            beforeBed = BeforeBedUiState(
                bandReady = false,
                deadlineEnabled = false,
                deadlineTime = LocalTime.of(8, 0),
                sleepLengthOptions = emptyList(),
                phoneBackupRowVisible = false,
                phoneBackupEnabled = false,
                startNightEnabled = false,
                startNightBlocker = null,
                startNightBlockedBySetupCheck = false,
            ),
            night = null,
            logs = LogsUiState(emptyList()),
            debug = DebugUiState(
                simulatedBandData = false,
                fastNight = false,
                dryRunBandCommands = false,
                simulatedTimeline = emptyList(),
                canFallAsleep = true,
                canWakeUp = false,
                canFallBackAsleep = false,
                canClearSimulatedSleep = false,
                canRingTestAlarm = true,
            ),
            errorMessage = null,
        )
    }
}
