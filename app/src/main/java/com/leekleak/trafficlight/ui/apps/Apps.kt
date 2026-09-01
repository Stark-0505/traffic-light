package com.leekleak.trafficlight.ui.apps

import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.leekleak.trafficlight.database.AppSort
import com.leekleak.trafficlight.database.AppUsage
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.components.FilterSection
import com.leekleak.trafficlight.ui.components.HazeScaffold
import com.leekleak.trafficlight.ui.components.NetworkToggle
import com.leekleak.trafficlight.ui.components.PeriodToggle
import com.leekleak.trafficlight.ui.components.UsageTableHeader
import com.leekleak.trafficlight.ui.components.UsageTableRow
import com.leekleak.trafficlight.ui.navigation.AppDetailKey
import com.leekleak.trafficlight.ui.navigation.NAVBAR_PADDING
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.formatted
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * The first-class "which apps used my data" screen: one network, one Day/Week/Month
 * period, a structured ranked table - not a subsection buried inside Overview or History.
 * Selecting a period genuinely re-queries the data layer (see [AppsVM]); this is never a
 * visual-only filter.
 */
@Composable
fun Apps() {
    val viewModel: AppsVM = koinViewModel()
    val navigator: Navigator = koinInject()
    val context = LocalContext.current

    val network by viewModel.networkFlow.collectAsStateWithLifecycle()
    val grouping by viewModel.groupingFlow.collectAsStateWithLifecycle()
    val sort by viewModel.sortFlow.collectAsStateWithLifecycle()
    val totalUsage by viewModel.totalUsage.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()

    val displayedApps = remember(apps, sort) {
        if (sort == AppSort.AToZ) apps.sortedBy { it.app.getName(context).lowercase() } else apps
    }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    HazeScaffold(
        title = stringResource(R.string.apps),
        scrollState = null,
        hazeState = null,
        backAction = BackAction.None,
        extraPadding = PaddingValues(bottom = navigator.navBarHeight ?: NAVBAR_PADDING),
        actions = { AppsSortMenu(sort, viewModel::setSort) }
    ) { paddingValues ->
        val sidePadding = remember(paddingValues) { paddingValues.calculateLeftPadding(LayoutDirection.Ltr) }
        val listContentPadding = PaddingValues(sidePadding, 0.dp, sidePadding, paddingValues.calculateBottomPadding())

        Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
            // Network and Period each get their own row, matching History's hierarchy,
            // rather than cramming Network + Period + Sort into one line.
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = sidePadding, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.total_usage),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant
                )
                Text(
                    text = DataSize(totalUsage).formatted(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (displayedApps.isEmpty()) {
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
                    leadingLabel = stringResource(R.string.app_column),
                    modifier = Modifier.padding(horizontal = sidePadding)
                )
                HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
                // Computed once per list, not per row: displayedApps.maxOf {} inside the
                // LazyColumn's item lambda used to re-scan the whole list on every row's
                // composition/recomposition.
                val maxUsage = displayedApps.maxOf { it.usage.totalUsage }.coerceAtLeast(1L)
                val accentColor = if (network == DataType.Wifi) colorScheme.tertiary else colorScheme.primary
                LazyColumn(contentPadding = listContentPadding) {
                    items(displayedApps, key = { it.app.uid }) { appUsage ->
                        AppRow(
                            appUsage = appUsage,
                            maxUsage = maxUsage,
                            accentColor = accentColor,
                            onClick = { navigator.goTo(AppDetailKey(appUsage.app.uid)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsSortMenu(current: AppSort, onSelect: (AppSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.filter_list), contentDescription = stringResource(R.string.filter))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(stringResource(sort.nameRes)) },
                    trailingIcon = {
                        if (sort == current) Icon(painterResource(R.drawable.checkmark), contentDescription = null)
                    },
                    onClick = { onSelect(sort); expanded = false }
                )
            }
        }
    }
}

/**
 * One app: icon, name and a proportional usage bar in the flexible leading area, then
 * aligned Download/Upload/Total columns shared with every other usage table in the app
 * (see [UsageTableRow]) - long package-style app names ellipsize rather than breaking
 * column alignment.
 */
@Composable
private fun AppRow(appUsage: AppUsage, maxUsage: Long, accentColor: Color, onClick: () -> Unit) {
    UsageTableRow(
        download = appUsage.usage.usage1,
        upload = appUsage.usage.usage2,
        total = appUsage.usage.totalUsage,
        totalColor = accentColor,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        appUsage.app.GetIcon(modifier = Modifier.size(32.dp))
        Column(modifier = Modifier.padding(start = Spacing.sm).weight(1f)) {
            Text(
                text = appUsage.app.getName(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
            ) {
                val fraction = (appUsage.usage.totalUsage.toFloat() / maxUsage).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                        .height(3.dp)
                        .background(accentColor, MaterialTheme.shapes.small)
                )
            }
        }
    }
    HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
}
