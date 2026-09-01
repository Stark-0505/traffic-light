package com.leekleak.trafficlight.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.ui.theme.card
import com.leekleak.trafficlight.util.MiniCardState

/**
 * One entry inside an [InsightRow]: a small labelled metric (icon, label, value), optionally
 * colored to reflect a positive/neutral/negative [state]. State is always paired with a
 * distinct icon by the caller so the meaning never rests on color alone.
 */
data class InsightItem(
    val icon: Painter,
    val label: String,
    val value: AnnotatedString,
    val state: MiniCardState = MiniCardState.NEUTRAL,
    val tooltip: String? = null,
)

/**
 * A single grouped surface holding 2-3 closely related metrics, separated by a hairline
 * divider instead of each becoming its own card. Use this for things like
 * "Prediction + Trend" or "Today + Daily budget" that describe one concept from different
 * angles, per the app's "one surface = one concept" rule.
 */
@Composable
fun InsightRow(
    items: List<InsightItem>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .card()
            .padding(vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = Spacing.xs)
                        .width(1.dp),
                    color = colorScheme.onSurface.copy(alpha = 0.12f)
                )
            }
            InsightItemContent(item, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.InsightItemContent(item: InsightItem, modifier: Modifier) {
    val tint = when (item.state) {
        MiniCardState.POSITIVE -> colorScheme.primary
        MiniCardState.NEGATIVE -> colorScheme.error
        MiniCardState.NEUTRAL -> colorScheme.onSurface.copy(alpha = 0.8f)
    }

    val content = @Composable {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = tint
                )
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            AnimatedContent(item.value) { value ->
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    color = tint
                )
            }
        }
    }

    Box(modifier) {
        if (item.tooltip != null) {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    positioning = TooltipAnchorPosition.Above
                ),
                tooltip = { PlainTooltip { Text(item.tooltip) } },
                state = rememberTooltipState()
            ) {
                content()
            }
        } else {
            content()
        }
    }
}
