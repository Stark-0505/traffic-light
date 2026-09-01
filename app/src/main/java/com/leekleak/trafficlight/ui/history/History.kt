package com.leekleak.trafficlight.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.charts.BarGraph
import com.leekleak.trafficlight.charts.model.BarData
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.HistoryGrouping
import com.leekleak.trafficlight.database.HourUsage
import com.leekleak.trafficlight.database.PeriodRow
import com.leekleak.trafficlight.database.PeriodSort
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.components.FilterSection
import com.leekleak.trafficlight.ui.components.HazeScaffold
import com.leekleak.trafficlight.ui.components.NetworkToggle
import com.leekleak.trafficlight.ui.components.PeriodToggle
import com.leekleak.trafficlight.ui.components.RankedAppList
import com.leekleak.trafficlight.ui.components.UsageTableHeader
import com.leekleak.trafficlight.ui.components.UsageTableRow
import com.leekleak.trafficlight.ui.navigation.NAVBAR_PADDING
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.ui.theme.card
import com.leekleak.trafficlight.util.CategoryTitleSmallText
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.formatted
import com.leekleak.trafficlight.util.toLocaleHourString
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * A structured, scannable table of usage grouped by Day/Week/Month, for exactly one
 * network at a time. This is the "when was data used" screen: rows are dense and aligned
 * rather than a stack of large independent cards, per the app's information-first design
 * language. Month rows expand into an analytical overview rather than just another grouping
 * of the same list - see [MonthlyOverviewContent].
 */
@Composable
fun History() {
    val viewModel: HistoryVM = koinViewModel()
    val navigator: Navigator = koinInject()

    val network by viewModel.networkFlow.collectAsStateWithLifecycle()
    val grouping by viewModel.groupingFlow.collectAsStateWithLifecycle()
    val sort by viewModel.sortFlow.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val expandedRow by viewModel.expandedRowFlow.collectAsStateWithLifecycle()
    val hourly by viewModel.expandedHourly.collectAsStateWithLifecycle()
    val monthlyOverview by viewModel.monthlyOverview.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    HazeScaffold(
        title = stringResource(R.string.history),
        scrollState = null,
        hazeState = null,
        backAction = BackAction.None,
        extraPadding = PaddingValues(bottom = navigator.navBarHeight ?: NAVBAR_PADDING),
        actions = {
            SortMenu(sort, viewModel::setSort)
        }
    ) { paddingValues ->
        val sidePadding = remember(paddingValues) { paddingValues.calculateLeftPadding(LayoutDirection.Ltr) }
        val listContentPadding = PaddingValues(sidePadding, 0.dp, sidePadding, paddingValues.calculateBottomPadding())

        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            // Network and Period each get their own row with breathing room, rather than
            // competing for space in one cramped line - Sort stays a compact icon
            // action in the top bar above.
            Column(
                modifier = Modifier
                    .padding(horizontal = sidePadding)
                    .padding(bottom = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                FilterSection(label = stringResource(R.string.network)) {
                    NetworkToggle(network, viewModel::setNetwork)
                }
                FilterSection(label = stringResource(R.string.period)) {
                    PeriodToggle(grouping, viewModel::setGrouping)
                }
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
                val listState = rememberLazyListState()
                LaunchedEffect(listState) {
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                        .distinctUntilChanged()
                        .collect { lastVisibleIndex ->
                            if (lastVisibleIndex != null) viewModel.loadMoreIfNeeded(lastVisibleIndex)
                        }
                }
                LazyColumn(state = listState, contentPadding = listContentPadding) {
                    items(rows, key = { it.start.toEpochDay() }) { row ->
                        val expanded = expandedRow == row.start
                        HistoryRow(
                            row = row,
                            grouping = grouping,
                            network = network,
                            expanded = expanded,
                            hourly = if (expanded && grouping == HistoryGrouping.Day) hourly else emptyList(),
                            // Guarded against the row start too, not just "is expanded": while
                            // a new month's data is still loading, the VM's last-known overview
                            // may briefly still describe a different, previously-expanded month -
                            // never present stale data as current.
                            monthlyOverview = monthlyOverview?.takeIf {
                                expanded && grouping == HistoryGrouping.Month && it.monthRow.start == row.start
                            },
                            onClick = {
                                if (grouping == HistoryGrouping.Day || grouping == HistoryGrouping.Month) {
                                    viewModel.toggleExpanded(row.start)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortMenu(current: PeriodSort, onSelect: (PeriodSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.filter_list), contentDescription = stringResource(R.string.filter))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PeriodSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.nameRes)) },
                    trailingIcon = {
                        if (sort == current) {
                            Icon(painterResource(R.drawable.checkmark), contentDescription = null)
                        }
                    },
                    onClick = { onSelect(sort); expanded = false }
                )
            }
        }
    }
}

/**
 * One period's row: label plus aligned Download/Upload/Total columns (see [UsageTableRow]).
 * Day rows expand inline into an hourly breakdown; Month rows expand into
 * [MonthlyOverviewContent] instead.
 */
@Composable
private fun HistoryRow(
    row: PeriodRow,
    grouping: HistoryGrouping,
    network: DataType,
    expanded: Boolean,
    hourly: List<HourUsage>,
    monthlyOverview: MonthlyOverview?,
    onClick: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val label = when {
        grouping == HistoryGrouping.Day && row.start == today -> stringResource(R.string.today)
        grouping == HistoryGrouping.Day && row.start == today.minusDays(1) -> stringResource(R.string.yesterday)
        else -> grouping.rawLabel(row.start)
    }
    val accentColor = if (network == DataType.Wifi) colorScheme.tertiary else colorScheme.primary
    val expandable = grouping == HistoryGrouping.Day || grouping == HistoryGrouping.Month

    Column {
        UsageTableRow(
            download = row.totals.download,
            upload = row.totals.upload,
            total = row.totals.total,
            totalColor = accentColor,
            modifier = Modifier.clickable(enabled = expandable, onClick = onClick),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Box(modifier = Modifier.card().padding(6.dp).padding(bottom = Spacing.sm)) {
                if (grouping == HistoryGrouping.Day) {
                    if (hourly.isNotEmpty()) {
                        val context = LocalContext.current
                        val barData = remember(hourly) {
                            hourly.map {
                                BarData(
                                    x = it.start.toLocalTime().toLocaleHourString(context, short = true),
                                    y1 = it.usage.usage1,
                                    y2 = 0L
                                )
                            }
                        }
                        BarGraph(barData)
                    }
                } else if (grouping == HistoryGrouping.Month) {
                    if (monthlyOverview != null) {
                        MonthlyOverviewContent(overview = monthlyOverview, accentColor = accentColor)
                    } else {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accentColor)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

/**
 * The Month analytical overview: change vs the previous month, a
 * download/upload breakdown, a within-month weekly split, and the month's top apps. This is
 * what makes selecting Month more than "the same list with bigger buckets".
 */
@Composable
private fun MonthlyOverviewContent(overview: MonthlyOverview, accentColor: Color) {
    Column(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
        overview.previousMonthTotal?.let { previous ->
            val currentTotal = overview.monthRow.totals.total
            val changePercent = (((currentTotal - previous).toDouble() / previous.toDouble()) * 100.0).roundToInt()
            Text(
                text = when {
                    changePercent > 0 -> stringResource(R.string.vs_last_month_increase, changePercent)
                    changePercent < 0 -> stringResource(R.string.vs_last_month_decrease, -changePercent)
                    else -> stringResource(R.string.vs_last_month_flat)
                },
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.md))
        }

        CategoryTitleSmallText(stringResource(R.string.usage_breakdown))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)
        ) {
            BreakdownItem(stringResource(R.string.download), overview.monthRow.totals.download)
            BreakdownItem(stringResource(R.string.upload), overview.monthRow.totals.upload)
        }
        Spacer(Modifier.height(Spacing.md))

        if (overview.weeklyRows.size > 1) {
            CategoryTitleSmallText(stringResource(R.string.weekly_usage))
            val barData = remember(overview.weeklyRows) {
                overview.weeklyRows.mapIndexed { index, week ->
                    BarData(x = "W${index + 1}", y1 = week.totals.total, y2 = 0L)
                }
            }
            BarGraph(barData)
            Spacer(Modifier.height(Spacing.md))
        }

        if (overview.topApps.isNotEmpty()) {
            CategoryTitleSmallText(stringResource(R.string.top_apps))
            RankedAppList(
                apps = overview.topApps,
                accentColor = accentColor,
                modifier = Modifier.padding(horizontal = Spacing.sm),
                maxEntries = 5,
                showShare = true,
                // The device-level month total, not the sum of topApps - some traffic is
                // never attributed to any single app, so this keeps the shown percentages
                // honest instead of quietly implying app usage adds up to 100%.
                totalOverride = overview.monthRow.totals.total,
            )
        }
    }
}

@Composable
private fun BreakdownItem(label: String, value: Long) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurfaceVariant)
        Text(text = DataSize(value).formatted(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
