package com.nikita.sleepcycle.ui.screens.setup

// File purpose: the dot-plus-label-plus-detail row for one setup requirement. Shared by the one-page
// checklist and any wizard page that shows a [SetupChecklistItem] (Phone permissions, Export file), so the
// two can never drift apart in how a requirement is presented.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikita.sleepcycle.ui.screens.setupItemTextResources
import com.nikita.sleepcycle.ui.state.SetupChecklistItem
import com.nikita.sleepcycle.ui.theme.ConnectedDot
import com.nikita.sleepcycle.ui.theme.ErrorRed

/** One row: a green/red status dot, the requirement's label, and a one-line detail. */
@Composable
fun SetupItemStatusRow(item: SetupChecklistItem) {
    val (labelRes, detailRes) = setupItemTextResources(item.kind)
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(10.dp)
                .background(if (item.complete) ConnectedDot else ErrorRed, CircleShape),
        )
        Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(detailRes), style = MaterialTheme.typography.bodySmall)
        }
    }
}
