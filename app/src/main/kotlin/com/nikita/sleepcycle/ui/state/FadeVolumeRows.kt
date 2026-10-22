package com.nikita.sleepcycle.ui.state

// File purpose: owner spec, 2026-10-22 (PER-135) - the Settings screen's starting and ending volume rows. The
// fade keeps one pair per output; the screen shows ONE pair, the one of the output media plays on now (the same
// reading as the live volume line, re-read every 500 ms, so it switches while Settings is open), and names that
// output in the Bedtime audio header. An edit there changes only that output's pair. Owner: "those settings get
// remembered per device and automatically switched depending on whether I'm listening from the speaker" or
// Bluetooth; "Changing them, given the mode, only records them for this specific mode".

import com.nikita.sleepcycle.night.BedtimeAudioSettings
import com.nikita.sleepcycle.night.FadeOutput
import com.nikita.sleepcycle.night.FadeVolumeKind
import com.nikita.sleepcycle.night.canStepFadeVolume
import com.nikita.sleepcycle.night.snapFadeVolumePercent
import com.nikita.sleepcycle.night.stepFadeVolume

/** Which output's pair the fade uses for media on [output]: Bluetooth's own, the phone speaker's for the speaker, wired headphones and anything else. */
fun fadeOutputFor(output: MediaOutput): FadeOutput = when (output) {
    MediaOutput.BLUETOOTH -> FadeOutput.BLUETOOTH
    MediaOutput.SPEAKER, MediaOutput.HEADPHONES -> FadeOutput.PHONE_SPEAKER
}

/** The starting and ending volume rows as shown: [output]'s pair, each snapped to a phone step, and which of the four buttons can move it. */
data class FadeVolumeRowsState(
    val output: FadeOutput,
    val startPercent: Int,
    val endPercent: Int,
    val canLowerStart: Boolean,
    val canRaiseStart: Boolean,
    val canLowerEnd: Boolean,
    val canRaiseEnd: Boolean,
)

/** The rows for media playing on [mediaOutput], on a phone with [maxStep] media volume steps. */
fun buildFadeVolumeRows(audio: BedtimeAudioSettings, mediaOutput: MediaOutput, maxStep: Int): FadeVolumeRowsState {
    val output = fadeOutputFor(mediaOutput)
    val volumes = audio.volumesFor(output)
    return FadeVolumeRowsState(
        output = output,
        startPercent = snapFadeVolumePercent(FadeVolumeKind.START, volumes.startPercent, maxStep),
        endPercent = snapFadeVolumePercent(FadeVolumeKind.END, volumes.endPercent, maxStep),
        canLowerStart = canStepFadeVolume(FadeVolumeKind.START, volumes.startPercent, -1, maxStep),
        canRaiseStart = canStepFadeVolume(FadeVolumeKind.START, volumes.startPercent, +1, maxStep),
        canLowerEnd = canStepFadeVolume(FadeVolumeKind.END, volumes.endPercent, -1, maxStep),
        canRaiseEnd = canStepFadeVolume(FadeVolumeKind.END, volumes.endPercent, +1, maxStep),
    )
}

/** [audio] after one tap in [direction] on the [kind] row while media plays on [mediaOutput]: only that output's pair changes. */
fun withFadeVolumeStepped(audio: BedtimeAudioSettings, mediaOutput: MediaOutput, kind: FadeVolumeKind, direction: Int, maxStep: Int): BedtimeAudioSettings {
    val output = fadeOutputFor(mediaOutput)
    val volumes = audio.volumesFor(output)
    val changed = when (kind) {
        FadeVolumeKind.START -> volumes.copy(startPercent = stepFadeVolume(kind, volumes.startPercent, direction, maxStep))
        FadeVolumeKind.END -> volumes.copy(endPercent = stepFadeVolume(kind, volumes.endPercent, direction, maxStep))
    }
    return audio.withVolumesFor(output, changed)
}
