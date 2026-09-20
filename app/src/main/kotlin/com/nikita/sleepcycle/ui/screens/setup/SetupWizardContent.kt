package com.nikita.sleepcycle.ui.screens.setup

// File purpose: picks the right page composable for [page] and wraps it in [SetupWizardScaffold]. The only
// file that needs to know both "which page" and "what that page's title/content is" - every individual page
// file only needs to know its own content.

import androidx.compose.runtime.Composable
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.state.SetupUiState

/** Every action a wizard page might need, gathered in one place so [SetupWizardContent]'s signature stays short. */
data class SetupWizardActions(
    val onDeviceMacChange: (String) -> Unit,
    val onPickExportFile: () -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onOpenFullScreenIntentSettings: () -> Unit,
    val onOpenBatteryOptimizationSettings: () -> Unit,
    val onRunConnectionTest: () -> Unit,
    val onOpenDebug: () -> Unit,
    val onExitWizard: () -> Unit,
    val onPreviousPage: () -> Unit,
    val onNextPage: () -> Unit,
)

private fun titleRes(page: SetupWizardPage): Int = when (page) {
    SetupWizardPage.WHAT_YOU_NEED -> R.string.setup_wizard_what_you_need_title
    SetupWizardPage.BAND_ADDRESS -> R.string.setup_wizard_band_address_title
    SetupWizardPage.GADGETBRIDGE_INTENTS -> R.string.setup_wizard_gadgetbridge_intents_title
    SetupWizardPage.GADGETBRIDGE_AUTO_EXPORT -> R.string.setup_wizard_gadgetbridge_auto_export_title
    SetupWizardPage.EXPORT_FILE -> R.string.setup_wizard_export_file_title
    SetupWizardPage.PHONE_PERMISSIONS -> R.string.setup_wizard_phone_permissions_title
    SetupWizardPage.TEST_CONNECTION -> R.string.setup_wizard_test_connection_title
}

/** Renders [page] inside the shared wizard chrome. */
@Composable
fun SetupWizardContent(state: SetupUiState, page: SetupWizardPage, actions: SetupWizardActions) {
    SetupWizardScaffold(
        page = page,
        titleRes = titleRes(page),
        isFirstPage = page == SETUP_WIZARD_PAGE_ORDER.first(),
        showNext = page != SetupWizardPage.TEST_CONNECTION,
        onBack = actions.onExitWizard,
        onPreviousPage = actions.onPreviousPage,
        onNextPage = actions.onNextPage,
    ) {
        when (page) {
            SetupWizardPage.WHAT_YOU_NEED -> WhatYouNeedPage(state)
            SetupWizardPage.BAND_ADDRESS -> BandAddressPage(state, actions.onDeviceMacChange)
            SetupWizardPage.GADGETBRIDGE_INTENTS -> GadgetbridgeIntentsPage()
            SetupWizardPage.GADGETBRIDGE_AUTO_EXPORT -> GadgetbridgeAutoExportPage()
            SetupWizardPage.EXPORT_FILE -> ExportFilePage(state, actions.onPickExportFile)
            SetupWizardPage.PHONE_PERMISSIONS -> PhonePermissionsPage(
                state,
                actions.onRequestNotificationPermission,
                actions.onOpenFullScreenIntentSettings,
                actions.onOpenBatteryOptimizationSettings,
            )
            SetupWizardPage.TEST_CONNECTION -> TestConnectionPage(state, actions.onRunConnectionTest, actions.onOpenDebug, actions.onExitWizard)
        }
    }
}
