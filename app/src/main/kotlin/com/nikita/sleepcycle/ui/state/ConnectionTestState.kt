package com.nikita.sleepcycle.ui.state

import com.nikita.sleepcycle.night.SetupCheckReport

/** The setup screen's "Test connection" button: idle, running (shows a spinner), or done with the full setup check report. */
sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Running : ConnectionTestState
    data class Done(val report: SetupCheckReport) : ConnectionTestState
}
