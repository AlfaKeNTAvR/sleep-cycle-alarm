package com.nikita.sleepcycle.ui.components

// File purpose: owner spec, 2026-10-22 (PER-133, option C "quiet text line") - the live media volume, read from
// the phone and drawn as one quiet centred line above the main buttons of Before bed and the Night screen:
// an output icon, then "Speaker 52%" with the percent bold. Also the shared reading the amber simulation block
// (DebugQuickControls.kt) shows its own volume from, so the two can never show different numbers. Whether the
// line shows at all is decided in ui/state/LiveVolumeLine.kt.

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.mediaVolumePercent
import com.nikita.sleepcycle.ui.state.MediaOutput
import com.nikita.sleepcycle.ui.state.mediaOutputFor
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import kotlinx.coroutines.delay

private val LineTextSize = 14.sp
internal val VolumeIconSize = 16.dp
internal val VolumeIconGap = 6.dp
private const val ICON_GRID_UNITS = 24f
private const val ICON_STROKE_UNITS = 2f

/** How often the shown media volume and output are re-read, in real milliseconds. */
private const val VOLUME_POLL_INTERVAL_MS = 500L

/** What media playback is routed by: the device that plays music, the stream the bedtime fade turns down. */
internal val MEDIA_PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/** One read of the phone's media output and its volume as a percent of the stream's range ([mediaVolumePercent]). */
internal data class MediaVolumeReading(val output: MediaOutput, val percent: Int)

/**
 * Reads the media volume and the device media plays on. Android 13+ names that device directly; before it, every
 * connected output is listed and [mediaOutputFor] picks the one media would take. No audio service reads as
 * Speaker at 0%, never a crash on a screen the owner glances at in the dark. Only the device TYPE is read: a
 * Bluetooth device's address is never touched, let alone shown or logged.
 */
internal fun readMediaVolume(audioManager: AudioManager?): MediaVolumeReading {
    if (audioManager == null) return MediaVolumeReading(MediaOutput.SPEAKER, 0)
    val percent = mediaVolumePercent(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    val deviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        audioManager.getAudioDevicesForAttributes(MEDIA_PLAYBACK_ATTRIBUTES).map { it.type }
    } else {
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
    }
    return MediaVolumeReading(mediaOutputFor(deviceTypes), percent)
}

/**
 * [readMediaVolume], re-read every [VOLUME_POLL_INTERVAL_MS] while shown, so key presses, the fade's own steps and
 * a switch to headphones or Bluetooth all show up within that interval.
 */
@Composable
internal fun rememberMediaVolumeReading(): MediaVolumeReading {
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val reading by produceState(initialValue = readMediaVolume(audioManager), audioManager) {
        while (true) {
            value = readMediaVolume(audioManager)
            delay(VOLUME_POLL_INTERVAL_MS)
        }
    }
    return reading
}

/**
 * The live volume line (option C): the output's icon, then its name in the muted color and the percent bold, e.g.
 * "Speaker 52%". Callers place it directly above the screen's main button(s) and only when their UI state says so.
 */
@Composable
fun LiveVolumeLine(modifier: Modifier = Modifier) {
    val reading = rememberMediaVolumeReading()
    val outputName = stringResource(
        when (reading.output) {
            MediaOutput.SPEAKER -> R.string.live_volume_output_speaker
            MediaOutput.HEADPHONES -> R.string.live_volume_output_headphones
            MediaOutput.BLUETOOTH -> R.string.live_volume_output_bluetooth
        }
    )
    val percentText = stringResource(R.string.live_volume_percent, reading.percent)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(VolumeIconGap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaOutputIcon(reading.output, NightOnSurfaceMuted)
        Text(
            text = buildAnnotatedString {
                append(outputName)
                append(" ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = NightOnBackground)) { append(percentText) }
            },
            fontSize = LineTextSize,
            color = NightOnSurfaceMuted,
        )
    }
}

/** The output's mark in [color], drawn as strokes on the 24-unit icon grid like IconButtons.kt's marks. */
@Composable
internal fun MediaOutputIcon(output: MediaOutput, color: Color) {
    Canvas(modifier = Modifier.size(VolumeIconSize)) {
        val unit = size.minDimension / ICON_GRID_UNITS
        val stroke = Stroke(width = ICON_STROKE_UNITS * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (output) {
            MediaOutput.SPEAKER -> drawSpeaker(unit, stroke, color)
            MediaOutput.HEADPHONES -> drawHeadphones(unit, stroke, color)
            MediaOutput.BLUETOOTH -> drawBluetooth(unit, stroke, color)
        }
    }
}

/** A speaker cone with one sound arc. */
private fun DrawScope.drawSpeaker(unit: Float, stroke: Stroke, color: Color) {
    val body = Path().apply {
        moveTo(11f * unit, 5f * unit)
        lineTo(6f * unit, 9f * unit)
        lineTo(2f * unit, 9f * unit)
        lineTo(2f * unit, 15f * unit)
        lineTo(6f * unit, 15f * unit)
        lineTo(11f * unit, 19f * unit)
        close()
    }
    drawPath(body, color = color, style = stroke)
    drawArc(
        color = color, startAngle = -45f, sweepAngle = 90f, useCenter = false,
        topLeft = Offset(10.5f * unit, 7f * unit), size = Size(10f * unit, 10f * unit), style = stroke,
    )
}

/** A headband with two ear cups. */
private fun DrawScope.drawHeadphones(unit: Float, stroke: Stroke, color: Color) {
    drawArc(
        color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(4f * unit, 5f * unit), size = Size(16f * unit, 16f * unit), style = stroke,
    )
    val cupSize = Size(4f * unit, 7f * unit)
    val cupCorner = CornerRadius(1.5f * unit)
    drawRoundRect(color = color, topLeft = Offset(4f * unit, 13f * unit), size = cupSize, cornerRadius = cupCorner, style = stroke)
    drawRoundRect(color = color, topLeft = Offset(16f * unit, 13f * unit), size = cupSize, cornerRadius = cupCorner, style = stroke)
}

/** The Bluetooth rune. */
private fun DrawScope.drawBluetooth(unit: Float, stroke: Stroke, color: Color) {
    val rune = Path().apply {
        moveTo(7f * unit, 7f * unit)
        lineTo(17f * unit, 17f * unit)
        lineTo(12f * unit, 22f * unit)
        lineTo(12f * unit, 2f * unit)
        lineTo(17f * unit, 7f * unit)
        lineTo(7f * unit, 17f * unit)
    }
    drawPath(rune, color = color, style = stroke)
}
