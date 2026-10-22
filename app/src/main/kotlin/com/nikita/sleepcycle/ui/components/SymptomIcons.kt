package com.nikita.sleepcycle.ui.components

// File purpose: the nine symptom icons of the "why Okay or Bad" dialog (owner spec, 2026-10-04), and each
// symptom's name. Outline icons on a 24-unit grid, stroke 1.7, round caps and joins, no fill - the paths are
// the approved design's SVG paths verbatim (Kept napping and Heavy head, added 2026-10-21, drawn here in the
// same style), so a redesigned icon is a one-string change here. Drawn in one
// color and tinted by whoever shows them (a selected tile in its rating's color).

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.R
import com.nikita.sleepcycle.night.RatingSymptom

private const val ICON_GRID = 24f
private const val ICON_STROKE_WIDTH = 1.7f

private const val STILL_SLEEPY_PATH = "M5 13c1.5 1.5 3.5 1.5 5 0 M14 13c1.5 1.5 3.5 1.5 5 0 M14 4h4l-4 5h4 M8 19c2 1.3 6 1.3 8 0"
private const val WOKE_EARLY_PATH = "M12 21a7 7 0 1 0 0-14a7 7 0 0 0 0 14z M12 10v4l-2 2 M4 5l3-2 M20 5l-3-2"
private const val SLOW_TO_FALL_ASLEEP_PATH = "M7 3h10 M7 21h10 M8 3c0 5 8 5 8 9s-8 4-8 9 M16 3c0 5-8 5-8 9s8 4 8 9"
private const val HEADACHE_PATH = "M6 20v-3a7 7 0 1 1 11-5.7l1.5 3.2h-2v4.5h-3.5v1 M13 4l-2 3h3l-2 3"
private const val SLEEPY_AFTERNOON_PATH = "M4 18h16 M7 18a5 5 0 0 1 10 0 M12 8V6 M5.5 11.5L4 10 M18.5 11.5L20 10 M15 3h4l-4 4h4"
private const val LOW_ENERGY_PATH = "M3 8h15v8H3z M18 11h2v2h-2 M6 11v2"
private const val HARD_TO_FOCUS_PATH = "M12 20a8 8 0 1 0 0-16 M8.5 18.5a8 8 0 0 1-3.8-10 M12 15a3 3 0 1 0 0-6 M18 15l3 3 M3 4l2 2"
// Owner change, 2026-10-21: Kept napping is a going-round-again arrow around a z; Heavy head is the Headache
// head with a sinking arrow inside it.
private const val KEPT_NAPPING_PATH = "M20 12a8 8 0 1 1-8-8c2.24 0 4.38.89 5.99 2.44L20 8 M20 3v5h-5 M10 9.5h4l-4 5h4"
private const val HEAVY_HEAD_PATH = "M6 20v-3a7 7 0 1 1 11-5.7l1.5 3.2h-2v4.5h-3.5v1 M12 5.5v6 M9.5 9l2.5 2.5 2.5-2.5"

/** All nine built once, on first use - an ImageVector is immutable, so one copy serves every screen. */
private val symptomIcons: Map<RatingSymptom, ImageVector> by lazy {
    RatingSymptom.entries.associateWith { strokeIcon(it.name, symptomPath(it)) }
}

/** [symptom]'s outline icon. */
fun symptomIcon(symptom: RatingSymptom): ImageVector = symptomIcons.getValue(symptom)

/** The name shown under each icon, on the dialog tiles and the line under a rating alike. */
fun symptomLabelRes(symptom: RatingSymptom): Int = when (symptom) {
    RatingSymptom.STILL_SLEEPY -> R.string.symptom_still_sleepy
    RatingSymptom.WOKE_BEFORE_ALARM -> R.string.symptom_woke_before_alarm
    RatingSymptom.SLOW_TO_FALL_ASLEEP -> R.string.symptom_slow_to_fall_asleep
    RatingSymptom.HEADACHE -> R.string.symptom_headache
    RatingSymptom.SLEEPY_IN_AFTERNOON -> R.string.symptom_sleepy_in_afternoon
    RatingSymptom.LOW_ENERGY -> R.string.symptom_low_energy
    RatingSymptom.HARD_TO_FOCUS -> R.string.symptom_hard_to_focus
    RatingSymptom.KEPT_NAPPING -> R.string.symptom_kept_napping
    RatingSymptom.HEAVY_HEAD -> R.string.symptom_heavy_head
}

private fun symptomPath(symptom: RatingSymptom): String = when (symptom) {
    RatingSymptom.STILL_SLEEPY -> STILL_SLEEPY_PATH
    RatingSymptom.WOKE_BEFORE_ALARM -> WOKE_EARLY_PATH
    RatingSymptom.SLOW_TO_FALL_ASLEEP -> SLOW_TO_FALL_ASLEEP_PATH
    RatingSymptom.HEADACHE -> HEADACHE_PATH
    RatingSymptom.SLEEPY_IN_AFTERNOON -> SLEEPY_AFTERNOON_PATH
    RatingSymptom.LOW_ENERGY -> LOW_ENERGY_PATH
    RatingSymptom.HARD_TO_FOCUS -> HARD_TO_FOCUS_PATH
    RatingSymptom.KEPT_NAPPING -> KEPT_NAPPING_PATH
    RatingSymptom.HEAVY_HEAD -> HEAVY_HEAD_PATH
}

/** A stroke-only icon from one SVG path string; the stroke color is a placeholder that the Icon's tint replaces. */
private fun strokeIcon(name: String, pathData: String): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = ICON_GRID.dp,
    defaultHeight = ICON_GRID.dp,
    viewportWidth = ICON_GRID,
    viewportHeight = ICON_GRID,
).addPath(
    pathData = addPathNodes(pathData),
    fill = null,
    stroke = SolidColor(Color.White),
    strokeLineWidth = ICON_STROKE_WIDTH,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()
