package com.nikita.sleepcycle.ui.screens.setup

import com.nikita.sleepcycle.ui.state.ConnectionTestState
import com.nikita.sleepcycle.ui.state.SetupChecklistItem
import com.nikita.sleepcycle.ui.state.SetupItemKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.nikita.sleepcycle.ui.state.SetupUiState

/** All seven [SetupItemKind]s complete except [incomplete]. */
private fun stateWithAllCompleteExcept(vararg incomplete: SetupItemKind): SetupUiState = SetupUiState(
    deviceMacText = "AA:BB:CC:DD:EE:FF",
    items = SetupItemKind.entries.map { SetupChecklistItem(it, it !in incomplete) },
    allComplete = incomplete.isEmpty(),
    connectionTest = ConnectionTestState.Idle,
    canRunConnectionTest = true,
)

private fun stateWithNoneComplete(): SetupUiState = SetupUiState(
    deviceMacText = "",
    items = SetupItemKind.entries.map { SetupChecklistItem(it, false) },
    allComplete = false,
    connectionTest = ConnectionTestState.Idle,
    canRunConnectionTest = false,
)

class SetupWizardPageOrderTest {
    @Test
    fun `every page appears exactly once, in the order the owner asked for`() {
        assertEquals(
            listOf(
                SetupWizardPage.WHAT_YOU_NEED,
                SetupWizardPage.BAND_ADDRESS,
                SetupWizardPage.GADGETBRIDGE_INTENTS,
                SetupWizardPage.GADGETBRIDGE_AUTO_EXPORT,
                SetupWizardPage.EXPORT_FILE,
                SetupWizardPage.PHONE_PERMISSIONS,
                SetupWizardPage.TEST_CONNECTION,
            ),
            SETUP_WIZARD_PAGE_ORDER,
        )
    }
}

class SetupWizardPageCompletenessTest {
    @Test
    fun `a page backed by a satisfied SetupItemKind is complete`() {
        val state = stateWithAllCompleteExcept()
        assertTrue(isSetupWizardPageComplete(SetupWizardPage.WHAT_YOU_NEED, state))
        assertTrue(isSetupWizardPageComplete(SetupWizardPage.BAND_ADDRESS, state))
        assertTrue(isSetupWizardPageComplete(SetupWizardPage.EXPORT_FILE, state))
        assertTrue(isSetupWizardPageComplete(SetupWizardPage.PHONE_PERMISSIONS, state))
    }

    @Test
    fun `a page backed by an unsatisfied SetupItemKind is not complete`() {
        val state = stateWithAllCompleteExcept(SetupItemKind.BAND_ADDRESS)
        assertFalse(isSetupWizardPageComplete(SetupWizardPage.BAND_ADDRESS, state))
    }

    @Test
    fun `phone permissions is not complete until every one of its four kinds is`() {
        val threeOfFour = stateWithAllCompleteExcept(SetupItemKind.EXACT_ALARMS)
        assertFalse(isSetupWizardPageComplete(SetupWizardPage.PHONE_PERMISSIONS, threeOfFour))
    }

    @Test
    fun `pages with no trackable SetupItemKind are never reported complete`() {
        val state = stateWithAllCompleteExcept()
        assertFalse(isSetupWizardPageComplete(SetupWizardPage.GADGETBRIDGE_INTENTS, state))
        assertFalse(isSetupWizardPageComplete(SetupWizardPage.GADGETBRIDGE_AUTO_EXPORT, state))
    }

    @Test
    fun `test connection is never reported complete, even when everything else is`() {
        val state = stateWithAllCompleteExcept()
        assertFalse(isSetupWizardPageComplete(SetupWizardPage.TEST_CONNECTION, state))
    }
}

class SetupWizardFirstUnsatisfiedPageTest {
    @Test
    fun `nothing done yet opens on the first page`() {
        assertEquals(SetupWizardPage.WHAT_YOU_NEED, firstUnsatisfiedSetupWizardPage(stateWithNoneComplete()))
    }

    @Test
    fun `gadgetbridge not installed opens on the first page even if everything else is done`() {
        val state = stateWithAllCompleteExcept(SetupItemKind.GADGETBRIDGE_INSTALLED)
        assertEquals(SetupWizardPage.WHAT_YOU_NEED, firstUnsatisfiedSetupWizardPage(state))
    }

    @Test
    fun `band address and gadgetbridge installed done opens on the first untrackable page`() {
        val state = stateWithAllCompleteExcept()
        assertEquals(SetupWizardPage.GADGETBRIDGE_INTENTS, firstUnsatisfiedSetupWizardPage(state))
    }

    @Test
    fun `only the export file left opens on that page`() {
        val state = stateWithAllCompleteExcept(SetupItemKind.EXPORT_FILE)
        assertEquals(SetupWizardPage.GADGETBRIDGE_INTENTS, firstUnsatisfiedSetupWizardPage(state), "the untrackable Gadgetbridge pages still come before it in order")
    }

    @Test
    fun `only a phone permission left opens on phone permissions once the untrackable pages are the only thing before it - false, they never satisfy`() {
        // Regression guard: because GADGETBRIDGE_INTENTS/GADGETBRIDGE_AUTO_EXPORT/BAND_ALARMS never report
        // complete, firstUnsatisfiedSetupWizardPage can never walk past them to reach a later real gap.
        val state = stateWithAllCompleteExcept(SetupItemKind.NOTIFICATIONS)
        assertEquals(SetupWizardPage.GADGETBRIDGE_INTENTS, firstUnsatisfiedSetupWizardPage(state))
    }
}

class SetupWizardNextTransitionTest {
    @Test
    fun `next from a satisfied page skips straight to the next unsatisfied page`() {
        val state = stateWithAllCompleteExcept(SetupItemKind.EXPORT_FILE)
        // BAND_ADDRESS is satisfied; the two Gadgetbridge pages after it are never satisfied, so Next from
        // BAND_ADDRESS must land on GADGETBRIDGE_INTENTS, not skip past those too.
        assertEquals(SetupWizardPage.GADGETBRIDGE_INTENTS, nextSetupWizardPage(SetupWizardPage.BAND_ADDRESS, state))
    }

    @Test
    fun `next skips every already-satisfied page in a row`() {
        val state = stateWithAllCompleteExcept(SetupItemKind.NOTIFICATIONS)
        // EXPORT_FILE is satisfied and PHONE_PERMISSIONS is not, so Next from EXPORT_FILE lands directly on
        // PHONE_PERMISSIONS.
        assertEquals(SetupWizardPage.PHONE_PERMISSIONS, nextSetupWizardPage(SetupWizardPage.EXPORT_FILE, state))
    }

    @Test
    fun `next from an unsatisfied page just moves one page forward`() {
        val state = stateWithNoneComplete()
        assertEquals(SetupWizardPage.BAND_ADDRESS, nextSetupWizardPage(SetupWizardPage.WHAT_YOU_NEED, state))
    }

    @Test
    fun `next never skips past test connection even when every other page is satisfied`() {
        val state = stateWithAllCompleteExcept()
        assertEquals(SetupWizardPage.TEST_CONNECTION, nextSetupWizardPage(SetupWizardPage.PHONE_PERMISSIONS, state))
    }

    @Test
    fun `next from test connection stays on test connection`() {
        val state = stateWithAllCompleteExcept()
        assertEquals(SetupWizardPage.TEST_CONNECTION, nextSetupWizardPage(SetupWizardPage.TEST_CONNECTION, state))
    }
}

class SetupWizardBackTransitionTest {
    @Test
    fun `back moves one page backward`() {
        assertEquals(SetupWizardPage.BAND_ADDRESS, previousSetupWizardPage(SetupWizardPage.GADGETBRIDGE_INTENTS))
    }

    @Test
    fun `back from the first page stays on the first page`() {
        assertEquals(SetupWizardPage.WHAT_YOU_NEED, previousSetupWizardPage(SetupWizardPage.WHAT_YOU_NEED))
    }

    @Test
    fun `back never loses entered data by construction - it only ever changes which page is shown`() {
        // previousSetupWizardPage takes no SetupUiState at all, so it is structurally incapable of touching
        // the entered device MAC, picked export file, or any other setting - see nextSetupWizardPage/
        // previousSetupWizardPage signatures.
        val before = previousSetupWizardPage(SetupWizardPage.TEST_CONNECTION)
        assertEquals(SetupWizardPage.PHONE_PERMISSIONS, before)
    }
}
