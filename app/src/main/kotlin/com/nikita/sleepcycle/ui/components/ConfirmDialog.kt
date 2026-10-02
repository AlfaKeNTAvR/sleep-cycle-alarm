package com.nikita.sleepcycle.ui.components

// File purpose: a small AlertDialog wrapper for the "Stop night" / "I'm up, end night" confirmation.

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
        confirmButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                onClick = onConfirm,
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                onClick = onDismiss,
            ) { Text(dismissText) }
        },
    )
}
