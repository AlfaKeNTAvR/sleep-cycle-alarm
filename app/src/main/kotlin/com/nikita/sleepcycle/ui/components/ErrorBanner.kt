package com.nikita.sleepcycle.ui.components

// File purpose: the plain-English error banner shown at the top of the app for a failure the owner needs to
// see but that does not warrant its own screen (D3), e.g. setExportUri failing to take the file permission.
// Tapping it dismisses it.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.theme.ErrorRed
import com.nikita.sleepcycle.ui.theme.NightOnBackground

/**
 * A dismissible one-line banner for a plain-English error message. Renders nothing when [message] is null.
 * Sits above every screen's own [ScreenContainer] in MainActivity, so it handles its own top and horizontal
 * safe-drawing inset here (status bar, display cutout) - the colored background still spans full-bleed
 * behind the status bar, only the text is pushed clear of it.
 */
@Composable
fun ErrorBanner(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    Text(
        text = "$message ${stringResource(R.string.error_banner_dismiss_hint)}",
        style = MaterialTheme.typography.bodySmall,
        color = NightOnBackground,
        modifier = Modifier
            .fillMaxWidth()
            .background(ErrorRed)
            .clickable(onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(12.dp),
    )
}
