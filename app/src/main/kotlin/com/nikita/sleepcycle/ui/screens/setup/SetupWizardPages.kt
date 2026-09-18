package com.nikita.sleepcycle.ui.screens.setup

// File purpose: the pure page model behind the Setup wizard - page order, which SetupItemKinds (if any) a page
// depends on, whether a page counts as done, and the Back/Next transitions (including skipping pages that are
// already satisfied). Never touches Android or Compose; SetupScreen.kt and NightViewModel.kt are the only
// callers, and this is exactly the kind of pure logic the task asked to be JVM-testable on its own.

import com.nikita.sleepcycle.ui.state.SetupItemKind
import com.nikita.sleepcycle.ui.state.SetupUiState

/** One page of the Setup wizard, in the order the owner asked for: one requirement per page. */
enum class SetupWizardPage {
    WHAT_YOU_NEED,
    BAND_ADDRESS,
    GADGETBRIDGE_INTENTS,
    GADGETBRIDGE_AUTO_EXPORT,
    EXPORT_FILE,
    PHONE_PERMISSIONS,
    BAND_ALARMS,
    TEST_CONNECTION,
}

/** Every wizard page, in display order; also the order [firstUnsatisfiedSetupWizardPage] and Next/Back walk. */
val SETUP_WIZARD_PAGE_ORDER: List<SetupWizardPage> = SetupWizardPage.entries

/**
 * The [SetupItemKind]s a page's green/grey state depends on. Three pages have none of their own: the
 * Gadgetbridge Intent API switches, the Gadgetbridge auto-export toggle, and the band's own alarm-slot
 * settings are all configured inside another app or on the band itself, so this app has nothing it can read
 * back to confirm they were done. Those pages are never auto-marked complete - see [isSetupWizardPageComplete].
 */
private val PAGE_SETUP_ITEM_KINDS: Map<SetupWizardPage, List<SetupItemKind>> = mapOf(
    SetupWizardPage.WHAT_YOU_NEED to listOf(SetupItemKind.GADGETBRIDGE_INSTALLED),
    SetupWizardPage.BAND_ADDRESS to listOf(SetupItemKind.BAND_ADDRESS),
    SetupWizardPage.GADGETBRIDGE_INTENTS to emptyList(),
    SetupWizardPage.GADGETBRIDGE_AUTO_EXPORT to emptyList(),
    SetupWizardPage.EXPORT_FILE to listOf(SetupItemKind.EXPORT_FILE),
    SetupWizardPage.PHONE_PERMISSIONS to listOf(
        SetupItemKind.NOTIFICATIONS,
        SetupItemKind.FULL_SCREEN_INTENT,
        SetupItemKind.BATTERY_OPTIMIZATION,
        SetupItemKind.EXACT_ALARMS,
    ),
    SetupWizardPage.BAND_ALARMS to emptyList(),
    SetupWizardPage.TEST_CONNECTION to emptyList(),
)

/**
 * Whether [page] is fully satisfied given [state]. [SetupWizardPage.TEST_CONNECTION] is always reported
 * incomplete here on purpose - the owner asked that the final page is never skipped, so it is deliberately
 * excluded from the green/skip logic the other pages use, regardless of [SetupUiState.allComplete].
 */
fun isSetupWizardPageComplete(page: SetupWizardPage, state: SetupUiState): Boolean {
    if (page == SetupWizardPage.TEST_CONNECTION) return false
    val requiredKinds = PAGE_SETUP_ITEM_KINDS.getValue(page)
    if (requiredKinds.isEmpty()) return false
    return requiredKinds.all { kind -> state.items.firstOrNull { it.kind == kind }?.complete == true }
}

/**
 * The first page that is not yet [isSetupWizardPageComplete] - the page the wizard opens on, whether that is
 * a fresh install or "Run setup again" from the checklist. Falls back to the last page if every earlier page
 * were somehow already complete, since [SetupWizardPage.TEST_CONNECTION] itself never reports complete.
 */
fun firstUnsatisfiedSetupWizardPage(state: SetupUiState): SetupWizardPage =
    SETUP_WIZARD_PAGE_ORDER.firstOrNull { !isSetupWizardPageComplete(it, state) } ?: SETUP_WIZARD_PAGE_ORDER.last()

/**
 * The page "Next" lands on from [current]: one page forward, then further forward past any already-satisfied
 * pages, but never past [SetupWizardPage.TEST_CONNECTION] - the owner's rule that the final page is never
 * skipped applies here too, not just to the initial page.
 */
fun nextSetupWizardPage(current: SetupWizardPage, state: SetupUiState): SetupWizardPage {
    val order = SETUP_WIZARD_PAGE_ORDER
    val afterCurrent = order.indexOf(current) + 1
    if (afterCurrent > order.lastIndex) return order.last()
    var index = afterCurrent
    while (index < order.lastIndex && isSetupWizardPageComplete(order[index], state)) {
        index++
    }
    return order[index]
}

/** The page "Back" lands on from [current]: one page back, or [current] itself if already on the first page. */
fun previousSetupWizardPage(current: SetupWizardPage): SetupWizardPage {
    val order = SETUP_WIZARD_PAGE_ORDER
    val index = (order.indexOf(current) - 1).coerceAtLeast(0)
    return order[index]
}
