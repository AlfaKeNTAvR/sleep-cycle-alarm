package com.nikita.sleepcycle.ui.components

// File purpose: the "possible wake-ups" bar row - one bar per cycle option, the planned one lit solid, the rest dim.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.state.WakeTimelineEntry
import com.nikita.sleepcycle.ui.theme.AmberAccent
import com.nikita.sleepcycle.ui.theme.NightOnBackground
import com.nikita.sleepcycle.ui.theme.NightOnSurfaceMuted
import com.nikita.sleepcycle.ui.theme.TimelineBarCornerRadius
import com.nikita.sleepcycle.ui.theme.TimelineBarHeight

private val TimelineColumnGap = 4.dp

/** One row of bars, one per wake-up option, the planned one lit solid amber; a matching row of time/hour labels below. */
@Composable
fun WakeTimeline(entries: List<WakeTimelineEntry>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TimelineBarHeight)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TimelineColumnGap)) {
            entries.forEach { entry ->
                val color = if (entry.isPlanned) AmberAccent else AmberAccent.copy(alpha = 0.35f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(TimelineBarHeight)
                        .background(color, RoundedCornerShape(TimelineBarCornerRadius)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TimelineColumnGap)) {
            entries.forEach { entry ->
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.timeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (entry.isPlanned) FontWeight.Bold else FontWeight.Medium,
                        color = if (entry.isPlanned) NightOnBackground else NightOnSurfaceMuted,
                    )
                    Text(text = entry.hoursLabel, style = MaterialTheme.typography.bodySmall, color = NightOnSurfaceMuted)
                }
            }
        }
    }
}
