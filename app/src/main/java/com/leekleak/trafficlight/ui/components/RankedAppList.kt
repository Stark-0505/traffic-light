package com.leekleak.trafficlight.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.ui.theme.googleSans
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.formatted

/**
 * A scannable, ranked list of apps by usage: rank, icon, name, amount and a proportional bar.
 *
 * Replaces the old bubble-style `AppGraph`. One row = one app; the bar length communicates
 * relative share at a glance without requiring the user to read every number.
 *
 * @param maxEntries how many rows to show. The list passed in is expected to already be
 * sorted by usage descending (as the data layer returns it).
 * @param showShare when true, shows each app's percentage share of [totalOverride] (or the
 * sum of the visible entries when no override is given) next to its amount.
 */
@Composable
fun RankedAppList(
    apps: List<AppUsage>,
    accentColor: Color,
    modifier: Modifier = Modifier,
    maxEntries: Int = 5,
    showShare: Boolean = false,
    totalOverride: Long? = null,
) {
    val entries = remember(apps, maxEntries) { apps.take(maxEntries) }

    if (entries.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_usage_detected),
                color = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        return
    }

    val maxUsage = remember(entries) { entries.maxOf { it.usage.totalUsage }.coerceAtLeast(1L) }
    val total = remember(entries, totalOverride) { totalOverride ?: entries.sumOf { it.usage.totalUsage } }.coerceAtLeast(1L)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        entries.forEachIndexed { index, entry ->
            RankedAppRow(
                rank = index + 1,
                entry = entry,
                fraction = entry.usage.totalUsage.toFloat() / maxUsage.toFloat(),
                share = if (showShare) entry.usage.totalUsage.toFloat() / total.toFloat() else null,
                accentColor = accentColor,
            )
        }
    }
}

@Composable
private fun RankedAppRow(
    rank: Int,
    entry: AppUsage,
    fraction: Float,
    share: Float?,
    accentColor: Color,
) {
    val animatedFraction by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(400))
    val rankFont = remember { googleSans(weight = 700f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rank.toString(),
                fontFamily = rankFont,
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelMedium
            )
        }
        entry.app.GetIcon(modifier = Modifier.size(28.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    modifier = Modifier.weight(1f, fill = false),
                    text = entry.app.getName(LocalContext.current),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (share != null) {
                        Text(
                            text = "${(share * 100).let { if (it < 1f && it > 0f) "<1" else it.toInt().toString() }}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Text(
                        text = DataSize(entry.usage.totalUsage).formatted(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(accentColor.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction.coerceIn(0.02f, 1f))
                        .clip(MaterialTheme.shapes.small)
                        .background(accentColor)
                )
            }
        }
    }
}
