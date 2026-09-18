package com.nikita.sleepcycle.ui.components

// File purpose: the tappable deadline time readout (big serif numerals) that opens a standard Material time picker.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.ui.theme.MediumNumeralStyle
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceDisabled
import java.time.LocalTime

/** A tappable "HH:mm" readout in the big serif numeral style; tapping it (while [enabled]) opens a time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediumTimeText(time: LocalTime, enabled: Boolean, onTimeChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    Text(
        text = "%02d:%02d".format(time.hour, time.minute),
        style = MediumNumeralStyle,
        color = if (enabled) NightOnBackground else NightOnSurfaceDisabled,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { showPicker = true },
    )
    if (showPicker) {
        val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        // Explicit colors throughout: Material 3's own TimePicker/AlertDialog defaults reach for colorScheme
        // slots (e.g. primaryContainer) this theme did not always define, which rendered as stock Material
        // purple instead of the app's dark-amber palette (see Theme.kt for the underlying colorScheme fix).
        // Passing everything here explicitly keeps this dialog correct even if a future colorScheme edit
        // leaves a slot unset again.
        val buttonColors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        AlertDialog(
            onDismissRequest = { showPicker = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            confirmButton = {
                TextButton(
                    colors = buttonColors,
                    onClick = {
                        onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                        showPicker = false
                    },
                ) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = {
                TextButton(colors = buttonColors, onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            text = {
                TimePicker(
                    state = pickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = MaterialTheme.colorScheme.surfaceVariant,
                        clockDialSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        clockDialUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        selectorColor = MaterialTheme.colorScheme.primary,
                        containerColor = MaterialTheme.colorScheme.surface,
                        periodSelectorBorderColor = MaterialTheme.colorScheme.outline,
                        periodSelectorSelectedContainerColor = MaterialTheme.colorScheme.primary,
                        periodSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        periodSelectorUnselectedContainerColor = Color.Transparent,
                        periodSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        // The selected hour/minute block: the one slot the owner saw render purple.
                        timeSelectorSelectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        timeSelectorSelectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        timeSelectorUnselectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        timeSelectorUnselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            },
        )
    }
}
