package com.leekleak.trafficlight.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.model.AppPeriodRow
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.components.FilterSection
import com.leekleak.trafficlight.ui.components.HazeScaffold
import com.leekleak.trafficlight.ui.components.NetworkToggle
import com.leekleak.trafficlight.ui.components.PeriodToggle
import com.leekleak.trafficlight.ui.components.UsageTableHeader
import com.leekleak.trafficlight.ui.components.UsageTableRow
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.formatted
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.time.LocalDate

/**
 * One app's usage over time - opened from the Apps table. Reuses History's Day/Week/Month
 * grouping and the same [UsageTableHeader]/[UsageTableRow] table style rather than
 * inventing a second table design.
 */
@Composable
fun AppDetail(uid: Int) {
    val viewModel: AppDetailVM = koinViewModel()
    val navigator: Navigator = koinInject()
    val context = LocalContext.current

    LaunchedEffect(uid) { viewModel.setUid(uid) }

    val app by viewModel.app.collectAsStateWithLifecycle()
    val network by viewModel.networkFlow.collectAsStateWithLifecycle()
    val grouping by viewModel.groupingFlow.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val currentTotal by viewModel.currentPeriodTotal.collectAsStateWithLifecycle()

    HazeScaffold(
        title = app?.getName(context) ?: "",
        scrollState = null,
        hazeState = null,
        backAction = BackAction.Normal(navigator),
    ) { paddingValues ->
        val sidePadding = remember(paddingValues) { paddingValues.calculateLeftPadding(LayoutDirection.Ltr) }
        val listContentPadding = PaddingValues(sidePadding, 0.dp, sidePadding, paddingValues.calculateBottomPadding())

        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            // Same hierarchy as History/Apps: Network and Period each on their own row,
            // keeping the design language consistent across screens.
            Column(
                modifier = Modifier.padding(horizontal = sidePadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                FilterSection(label = stringResource(R.string.network)) {
                    NetworkToggle(network, viewModel::setNetwork)
                }
                FilterSection(label = stringResource(R.string.period)) {
                    PeriodToggle(grouping, viewModel::setGrouping)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.total_usage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = DataSize(currentTotal).formatted(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (rows.all { it.totals.total == 0L }) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_usage_detected),
                        color = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                UsageTableHeader(
                    leadingLabel = stringResource(R.string.date),
                    modifier = Modifier.padding(horizontal = sidePadding)
                )
                HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
                LazyColumn(contentPadding = listContentPadding) {
                    items(rows, key = { it.start.toEpochDay() }) { row ->
                        AppDetailRow(row, grouping, network)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetailRow(row: AppPeriodRow, grouping: HistoryGrouping, network: DataType) {
    val today = remember { LocalDate.now() }
    val label = when {
        grouping == HistoryGrouping.Day && row.start == today -> stringResource(R.string.today)
        grouping == HistoryGrouping.Day && row.start == today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> grouping.rawLabel(row.start)
    }
    val accentColor = if (network == DataType.Wifi) colorScheme.tertiary else colorScheme.primary

    UsageTableRow(
        download = row.totals.download,
        upload = row.totals.upload,
        total = row.totals.total,
        totalColor = accentColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
}
