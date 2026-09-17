package com.nikita.sleepcycle.ui.components

// File purpose: the tappable deadline time readout (big serif numerals) that opens a standard Material time picker.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(pickerState.hour, pickerState.minute))
                    showPicker = false
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_cancel)) } },
            text = { TimePicker(state = pickerState) },
        )
    }
}
