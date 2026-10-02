package com.nikita.sleepcycle.ui.components

// File purpose: a small AlertDialog wrapper for the app's yes/no confirmations (End night, I'm up, deleting a log).

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/** A yes/no confirmation dialog. Colors are explicit rather than left to Material defaults - see TimePickerField.kt for why. */
@Composable
fun ConfirmDialog(
    title: String,
    /** Null shows the title alone, centred - the "I'm up" dialog, owner request 2026-10-02. */
    message: String?,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            if (message == null) Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) else Text(title)
        },
        text = message?.let { { Text(it) } },
        // Owner request, 2026-10-02: in every dialog, cancel on the left edge and confirm on the right edge,
        // instead of both sitting together on the right.
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DismissButton(dismissText, onDismiss)
                ConfirmButton(confirmText, onConfirm)
            }
        },
    )
}

@Composable
private fun ConfirmButton(text: String, onClick: () -> Unit) {
    TextButton(
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        onClick = onClick,
    ) { Text(text) }
}

@Composable
private fun DismissButton(text: String, onClick: () -> Unit) {
    TextButton(
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        onClick = onClick,
    ) { Text(text) }
}
