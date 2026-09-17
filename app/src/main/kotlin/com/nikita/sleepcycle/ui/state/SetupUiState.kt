package com.nikita.sleepcycle.ui.state

/** One row of the setup checklist: which requirement, and whether it is currently met. */
data class SetupChecklistItem(
    val kind: SetupItemKind,
    val complete: Boolean,
)

/** Everything the setup checklist screen shows. The Gadgetbridge settings list itself is static UI copy, not state. */
data class SetupUiState(
    val deviceMacText: String,
    val items: List<SetupChecklistItem>,
    val allComplete: Boolean,
    val connectionTest: ConnectionTestState,
    val canRunConnectionTest: Boolean,
)
