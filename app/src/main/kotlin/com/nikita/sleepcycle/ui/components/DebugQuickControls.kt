package com.nikita.sleepcycle.ui.components

// File purpose: W7 (owner request, 2026-09-20) - the two controls that actually drive a simulated night, the
// clock speed and the Asleep toggle, placed on the screen the owner is already looking at. Everything else
// about a simulation is set up once and then left alone: which data source, where the clock starts, clearing
// a leftover timeline. Those stay on the Debug screen. These two get used every few seconds while watching a
// night unfold, and walking to another screen and back for each one made the whole thing tedious.
//
// Deliberately shows nothing unless simulated band data is already on. The controls it holds both REQUIRE
// that switch (U1's own gate), so rendering them without it would put two permanently dead controls on the
// main screen of a real night. Turning the switch on stays a deliberate trip to the Debug screen, which is
// the right weight for "I am about to simulate rather than sleep".
//
// Owner spec, 2026-10-02: the chips are 1x, 60x and Auto (AutoSimulationSpeed.kt), and the Auto chip shows the
// speed Auto is running at. Option B of the design (owner's pick): the amber SIMULATED banner itself becomes
// the control - one amber-outlined block at the top holding the simulated clock, the speed chips and an
// Asleep toggle chip - so the bottom of the Night screen keeps only the real buttons (Nap, I'm up, End night)
// and the card no longer crowds them on the out-of-bed nudge screen.

import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import com.nikita.sleepcycle.night.mediaVolumePercent
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.SpeedChoice
import com.nikita.sleepcycle.ui.state.DebugUiState
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.CardBorderWidth
import com.nikita.sleepcycle.ui.theme.CardCornerRadius
import com.nikita.sleepcycle.ui.theme.ChipGap

private val BlockHorizontalPadding = 12.dp
private val BlockVerticalPadding = 10.dp
private val BlockRowGap = 10.dp
private val TitleLetterSpacing = 1.sp
private val VolumeIconSize = 16.dp
private val VolumeIconGap = 6.dp
private const val ICON_GRID_UNITS = 24f
/** How often the shown media volume is re-read, in real milliseconds. */
private const val VOLUME_POLL_INTERVAL_MS = 500L
/** The Asleep chip carries a longer word than "1x", so it gets a little more of the row. */
private const val ASLEEP_CHIP_WEIGHT = 1.4f

/**
 * The simulation control block (option B), in place of the plain SIMULATED banner, or nothing at all when
 * [state] says simulated band data is off (see this file's own header). [simulatedTimeValue] is the live
 * simulated clock ("03:15"), null while the clock runs at real time with nothing to show.
 */
@Composable
fun DebugQuickControls(
    state: DebugUiState,
    simulatedTimeValue: String?,
    onSpeedChoice: (SpeedChoice) -> Unit,
    onSetSimulatedAsleep: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.simulatedBandData) return
    SimulatedBlock(simulatedTimeValue, modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ChipGap)) {
            state.speedChoices.forEach { choice ->
                val label = speedChoiceLabel(choice, state.autoRunningSpeed)
                SleepLengthChip(
                    label = label,
                    selected = choice == state.selectedSpeed,
                    available = state.simulatedTimeControlEnabled,
                    onClick = { onSpeedChoice(choice) },
                    contentDescription = label,
                    modifier = Modifier.weight(1f),
                )
            }
            val asleepLabel = stringResource(R.string.debug_asleep_toggle_title)
            SleepLengthChip(
                label = asleepLabel,
                selected = state.simulatedAsleep,
                available = state.sleepControlEnabled,
                onClick = { onSetSimulatedAsleep(!state.simulatedAsleep) },
                contentDescription = asleepLabel,
                modifier = Modifier.weight(ASLEEP_CHIP_WEIGHT),
            )
        }
    }
}

/**
 * The amber-outlined simulation block: "SIMULATED" on the left, the clock ([simulatedTimeValue]) in the middle,
 * the live media volume on the right (owner request, 2026-10-02), then [content] - the Night screen's controls.
 * Before bed shows it on its own, without controls.
 */
@Composable
fun SimulatedBlock(simulatedTimeValue: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(CardBorderWidth, AmberAccent, RoundedCornerShape(CardCornerRadius))
            .padding(horizontal = BlockHorizontalPadding, vertical = BlockVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(BlockRowGap),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.debug_switch_simulated_sleep_data),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = TitleLetterSpacing,
                color = AmberAccent,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            if (simulatedTimeValue != null) {
                Text(
                    text = simulatedTimeValue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AmberAccent,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            val volumeText = stringResource(R.string.debug_media_volume_value, rememberMediaVolumePercent())
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(VolumeIconGap),
            ) {
                SpeakerIcon()
                Text(text = volumeText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AmberAccent)
            }
        }
        content()
    }
}

/**
 * The phone's media volume as a percent of its range ([mediaVolumePercent]), re-read every
 * [VOLUME_POLL_INTERVAL_MS] while shown, so key presses, the fade's own steps and a switch to headphones all
 * show up within that interval.
 */
@Composable
private fun rememberMediaVolumePercent(): Int {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val percent by produceState(initialValue = readMediaVolumePercent(audioManager), audioManager) {
        while (true) {
            value = readMediaVolumePercent(audioManager)
            delay(VOLUME_POLL_INTERVAL_MS)
        }
    }
    return percent
}

private fun readMediaVolumePercent(audioManager: AudioManager?): Int =
    if (audioManager == null) 0 else mediaVolumePercent(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

/** A small speaker with one sound wave, drawn on the 24-unit icon grid like IconButtons.kt's marks. */
@Composable
private fun SpeakerIcon() {
    Canvas(modifier = Modifier.size(VolumeIconSize)) {
        val unit = size.minDimension / ICON_GRID_UNITS
        val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(11f * unit, 5f * unit)
            lineTo(6f * unit, 9f * unit)
            lineTo(2f * unit, 9f * unit)
            lineTo(2f * unit, 15f * unit)
            lineTo(6f * unit, 15f * unit)
            lineTo(11f * unit, 19f * unit)
            close()
        }
        drawPath(body, color = AmberAccent, style = stroke)
        drawArc(
            color = AmberAccent, startAngle = -45f, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(10.5f * unit, 7f * unit), size = Size(10f * unit, 10f * unit), style = stroke,
        )
    }
}

/** "1x", "60x", "Auto" - and under Auto, the speed it is running at on a second line under "Auto". */
@Composable
private fun speedChoiceLabel(choice: SpeedChoice, autoRunningSpeed: Int?): String {
    val fixedSpeed = choice.fixedSpeed
    return when {
        fixedSpeed != null -> stringResource(R.string.debug_speed_multiplier_label, fixedSpeed)
        autoRunningSpeed != null -> stringResource(R.string.debug_speed_auto_running, stringResource(R.string.debug_speed_multiplier_label, autoRunningSpeed))
        else -> stringResource(R.string.debug_speed_auto)
    }
}
