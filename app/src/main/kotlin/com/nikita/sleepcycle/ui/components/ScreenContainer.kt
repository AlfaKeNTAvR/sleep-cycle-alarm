package com.nikita.sleepcycle.ui.components

// File purpose: the shared root layout every full screen (Setup, Before bed, Night, Debug, Logs) mounts
// under. Folds the screen's standard padding together with the safe-drawing window insets (status bar,
// navigation bar, display cutout) in one place, so a new screen cannot forget to handle them. Scrollable
// screens keep the padding inside the scroll (it behaves like LazyColumn's contentPadding: the scroll area
// itself stays full-bleed, but the last item still clears the navigation bar). ErrorBanner, the one piece of
// chrome that sits above this container in MainActivity, handles its own top inset separately.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import com.nikita.sleepcycle.ui.theme.ScreenContentGap
import com.nikita.sleepcycle.ui.theme.ScreenHorizontalPadding

/**
 * The shared full-screen root: fills the window, optionally scrolls, and pads its content by the usual
 * screen margins plus the safe-drawing insets. [scrollable] screens ([androidx.compose.foundation.verticalScroll])
 * scroll behind the system bars with [bottomPadding] acting as trailing content padding; non-scrollable
 * screens that pin a primary button to the bottom via a weighted [androidx.compose.foundation.layout.Spacer]
 * get the same padding as an ordinary outer inset, which is what keeps that button above the navigation bar.
 */
@Composable
fun ScreenContainer(
    scrollable: Boolean,
    bottomPadding: Dp = ScreenContentGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(scrollModifier)
            .padding(
                start = ScreenHorizontalPadding + insets.calculateStartPadding(layoutDirection),
                end = ScreenHorizontalPadding + insets.calculateEndPadding(layoutDirection),
            )
            .padding(
                // One ordinary spacing step below the real status bar inset - not the old fixed
                // ScreenTopPadding, which was sized to approximate a status bar gap by itself and, added on
                // top of the real inset once edge-to-edge insets were wired in, doubled it.
                top = ScreenContentGap + insets.calculateTopPadding(),
                bottom = bottomPadding + insets.calculateBottomPadding(),
            ),
        verticalArrangement = Arrangement.spacedBy(ScreenContentGap),
        content = content,
    )
}
