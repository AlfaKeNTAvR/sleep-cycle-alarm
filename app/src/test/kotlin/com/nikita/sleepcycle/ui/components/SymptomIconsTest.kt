package com.nikita.sleepcycle.ui.components

// File purpose: the symptom icons are SVG path strings parsed at runtime, so a typo in one would crash the
// "why Okay or Bad" dialog the moment it opens. Builds every one here instead.

import androidx.compose.ui.graphics.vector.VectorPath
import com.nikita.sleepcycle.night.RatingSymptom
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SymptomIconsTest {
    @Test
    fun `every symptom has an icon whose path parses into strokes`() {
        RatingSymptom.entries.forEach { symptom ->
            val path = symptomIcon(symptom).root.single() as VectorPath
            assertTrue(path.pathData.size > 1, "$symptom's icon has no strokes")
        }
    }
}
