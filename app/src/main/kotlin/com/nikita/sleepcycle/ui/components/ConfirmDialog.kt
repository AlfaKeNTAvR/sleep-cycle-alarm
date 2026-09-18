package com.nikita.sleepcycle.ui.components

// File purpose: a small AlertDialog wrapper for the "Stop night" / "I'm up, end night" confirmation.

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** A yes/no confirmation dialog. Colors are explicit rather than left to Material defaults - see TimePickerField.kt for why. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
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
        title = { Text(title) },
        text = { Text(message) },
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
