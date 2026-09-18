package com.nikita.sleepcycle.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Platform serif for large clock numerals (stands in for Fraunces without bundling a font), platform sans for
// everything else (stands in for Manrope). Default Material 3 type scale otherwise.
private val SerifNumeralFamily = FontFamily.Serif
private val TextFamily = FontFamily.Default

val Typography = Typography(
    bodyLarge = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    titleLarge = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    labelLarge = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp),
)

/** The big serif numeral style used for the night screen's hero clock time / duration. */
val HeroNumeralStyle = TextStyle(fontFamily = SerifNumeralFamily, fontWeight = FontWeight.Normal, fontSize = 88.sp, lineHeight = 92.sp)

/** A medium serif numeral, used for the "Wake me by" time picker and small alarm-time readouts. */
val MediumNumeralStyle = TextStyle(fontFamily = SerifNumeralFamily, fontWeight = FontWeight.Normal, fontSize = 64.sp, lineHeight = 68.sp)

/** A small serif numeral, used for alarm times inside a settings-style row. */
val SmallNumeralStyle = TextStyle(fontFamily = SerifNumeralFamily, fontWeight = FontWeight.Normal, fontSize = 22.sp)

/** The glyph size inside [com.nikita.sleepcycle.ui.components.IconGlyphButton]: visually close to a 28 dp icon, per the Material minimum of a 24 dp icon inside a 48 dp touch target - larger still, since these are the app's two most-tapped navigation icons. */
val IconGlyphStyle = TextStyle(fontFamily = TextFamily, fontWeight = FontWeight.Medium, fontSize = 28.sp)
