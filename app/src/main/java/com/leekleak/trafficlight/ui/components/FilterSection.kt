package com.leekleak.trafficlight.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.util.ToggleIconButton

/**
 * A small caption above a group of filter controls (a network toggle, a period toggle...).
 * Used by History, Apps and App Detail to give each control group its own row with
 * breathing room instead of cramming several filters into one crowded line.
 */
@Composable
fun FilterSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

/**
 * The Mobile/Wi-Fi toggle shared by every screen that filters by network (Overview,
 * History, Apps, App Detail) - previously four separate copies of the same two buttons.
 * `FlowRow` rather than `Row`: at large accessibility font scales the two labelled
 * buttons can outgrow a narrow screen's width, and a plain `Row` would then push the
 * second button off-screen instead of wrapping it to a second line.
 */
@Composable
fun NetworkToggle(network: DataType, onSelect: (DataType) -> Unit) {
    val wifiToggledColors = IconButtonDefaults.iconButtonColors(
        containerColor = colorScheme.tertiaryContainer,
        contentColor = colorScheme.onTertiaryContainer
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ToggleIconButton(
            text = stringResource(R.string.cellular),
            selected = network == DataType.Mobile,
            onSelect = { onSelect(DataType.Mobile) }
        ) {
            Icon(painterResource(R.drawable.cellular), contentDescription = null)
        }
        ToggleIconButton(
            text = stringResource(R.string.wifi),
            selected = network == DataType.Wifi,
            toggledColors = wifiToggledColors,
            onSelect = { onSelect(DataType.Wifi) }
        ) {
            Icon(painterResource(R.drawable.wifi), contentDescription = null)
        }
    }
}

/**
 * The Day/Week/Month toggle shared by History, Apps and App Detail - see [NetworkToggle]
 * for why this wraps in a `FlowRow` instead of a `Row`.
 */
@Composable
fun PeriodToggle(grouping: HistoryGrouping, onSelect: (HistoryGrouping) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        HistoryGrouping.entries.forEach { g ->
            ToggleIconButton(
                text = stringResource(g.nameRes),
                selected = grouping == g,
                onSelect = { onSelect(g) }
            ) {}
        }
    }
}
