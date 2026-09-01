package com.leekleak.trafficlight.ui.overview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes.Companion.Cookie12Sided
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.copy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.toPath
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.charts.BarGraph
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.integrations.Ad
import com.leekleak.trafficlight.integrations.AdType
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.components.HazeScaffold
import com.leekleak.trafficlight.ui.components.InsightItem
import com.leekleak.trafficlight.ui.components.InsightRow
import com.leekleak.trafficlight.ui.components.RankedAppList
import com.leekleak.trafficlight.ui.navigation.AppsKey
import com.leekleak.trafficlight.ui.navigation.NAVBAR_PADDING
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.navigation.SettingsKey
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.ui.theme.card
import com.leekleak.trafficlight.ui.theme.googleSans
import com.leekleak.trafficlight.util.CategoryTitleText
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.MiniCardState
import com.leekleak.trafficlight.util.formatted
import com.leekleak.trafficlight.util.formattedParts
import com.leekleak.trafficlight.util.ToggleIconButton
import com.leekleak.trafficlight.util.px
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/**
 * Overview is a compact summary, not a dashboard of competing cards. The hierarchy is
 * fixed top-to-bottom: today's headline figure -> network context -> a quick day/week
 * comparison -> the weekly trend -> the highest-usage apps. Each section answers exactly
 * one of the app's core questions, once.
 */
@Composable
fun Overview() {
    val viewModel: OverviewVM = koinViewModel()
    val navigator: Navigator = koinInject()
    val haptic = LocalHapticFeedback.current

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    HazeScaffold(
        title = stringResource(R.string.today),
        backAction = BackAction.None,
        extraPadding = PaddingValues(bottom = navigator.navBarHeight ?: NAVBAR_PADDING),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        actions = {
            IconButton(
                onClick = {
                    navigator.goTo(SettingsKey)
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                }
            ) {
                Icon(painterResource(R.drawable.settings), contentDescription = stringResource(R.string.settings))
            }
        }
    ) {
        PrimaryUsage()
        QuickSummary()
        RecentUsage()
        Ad(AdType.NativeBanner)
        TopApps()
    }
}

/**
 * The headline figure plus the Mobile/Wi-Fi selector. Deliberately compact: a fixed,
 * modest height rather than a large aspect-ratio hero, so it reads as the first line of a
 * summary instead of a decorative cover screen.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PrimaryUsage() {
    val viewModel: OverviewVM = koinViewModel()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val todayUsage by viewModel.todayUsage.collectAsStateWithLifecycle()

    val scheme = colorScheme
    // MaterialShapes.Cookie12Sided only exists behind ExperimentalMaterial3ExpressiveApi -
    // there is no stable Material3 shape API that provides this polygon, so the @OptIn on
    // this function is load-bearing, not incidental. It was briefly dropped in an earlier
    // pass while removing an unrelated experimental ButtonGroup dependency from this same
    // file; that opt-in also happened to be covering this usage, which is why it needs to
    // come back here specifically rather than anywhere broader.
    // Cookie12Sided is an androidx.graphics.shapes.RoundedPolygon. Material3's own
    // androidx.compose.material3.toPath() convenience wrapper is @Composable and can't be
    // called inside remember {}'s calculation lambda (which disallows composable calls) -
    // that mismatch was the actual compile error here, not an issue with `remember` or the
    // surrounding function's own Compose boundary. androidx.graphics.shapes.toPath() is the
    // plain, non-Composable geometry conversion the polygon library itself provides (already
    // used the same way, with the same .asComposePath() follow-up, in
    // ScrollableBarGraphHelper.kt's DrawScope code - not a composable context either), so it
    // has no such restriction and is safe to call here.
    val shape = remember { Cookie12Sided.toPath().asComposePath() }
    val shapeScale = 220.dp.px

    val infiniteTransition = rememberInfiniteTransition()
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(60000, easing = LinearEasing))
    )
    val shapeTransformed = remember(rotation) {
        val matrix = Matrix().apply {
            rotateZ(rotation)
            scale(shapeScale, shapeScale)
            translate(-0.5f, -0.5f)
        }
        shape.copy().apply { transform(matrix) }
    }
    val glowColor by animateColorAsState(
        targetValue = if (query.dataType == DataType.Mobile) colorScheme.primaryContainer else colorScheme.tertiaryContainer
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .drawWithContent {
                drawCircle(
                    Brush.radialGradient(listOf(glowColor.copy(alpha = 0.5f), Color.Transparent))
                )
                translate(size.width * 0.2f, size.height * 0.1f) { drawPath(shapeTransformed, scheme.surfaceContainerHighest.copy(alpha = 0.5f)) }
                drawContent()
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val string = DataSize(todayUsage).formattedParts(extraPrecision = true)
            val fontFamily = remember { googleSans(weight = 600f, width = 60f, roundness = 80f) }
            val labelFontFamily = remember { googleSans(weight = 500f, roundness = 50f) }

            AnimatedContent(string) { text ->
                Text(
                    textAlign = TextAlign.Center,
                    fontFamily = fontFamily,
                    fontSize = 56.sp,
                    text = "${text.first}${text.second} ${text.third}"
                )
            }
            Text(
                fontFamily = labelFontFamily,
                fontSize = 16.sp,
                color = colorScheme.onSurfaceVariant,
                text = stringResource(if (query.dataType == DataType.Wifi) R.string.wifi else R.string.mobile_data)
            )

            Box(modifier = Modifier.padding(top = Spacing.md)) {
                val wifiToggledColors = IconButtonDefaults.iconButtonColors(
                    containerColor = colorScheme.tertiaryContainer,
                    contentColor = colorScheme.onTertiaryContainer
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleIconButton(
                        text = null,
                        selected = query.dataType == DataType.Mobile,
                        onSelect = { viewModel.setDataType(DataType.Mobile) }
                    ) {
                        Icon(painterResource(R.drawable.cellular), contentDescription = stringResource(R.string.cellular))
                    }
                    ToggleIconButton(
                        text = null,
                        selected = query.dataType == DataType.Wifi,
                        toggledColors = wifiToggledColors,
                        onSelect = { viewModel.setDataType(DataType.Wifi) }
                    ) {
                        Icon(painterResource(R.drawable.wifi), contentDescription = stringResource(R.string.wifi))
                    }
                }
            }
        }
    }
}

/**
 * Yesterday / change / this week, in one grouped surface separated by dividers rather than
 * three separate cards - directly answers "how does today compare?" without a dedicated screen.
 */
@Composable
private fun QuickSummary() {
    val viewModel: OverviewVM = koinViewModel()
    val yesterday by viewModel.yesterdayUsage.collectAsStateWithLifecycle()
    val change by viewModel.changePercent.collectAsStateWithLifecycle()
    val weekTotal by viewModel.weekTotal.collectAsStateWithLifecycle()

    // change is read via `by` (a delegated property), so the compiler re-invokes the
    // StateFlow's getValue() on every access and can't smart-cast it to non-null Int
    // between branches below - capture it in a plain local val first.
    val currentChange = change
    val changeState = when {
        currentChange == null -> MiniCardState.NEUTRAL
        currentChange > 10 -> MiniCardState.NEGATIVE
        currentChange < -10 -> MiniCardState.POSITIVE
        else -> MiniCardState.NEUTRAL
    }

    InsightRow(
        items = listOf(
            InsightItem(
                icon = painterResource(R.drawable.history_2),
                label = stringResource(R.string.yesterday),
                value = AnnotatedString(DataSize(yesterday).formatted()),
            ),
            InsightItem(
                icon = when {
                    changeState == MiniCardState.NEGATIVE -> painterResource(R.drawable.trending_up)
                    changeState == MiniCardState.POSITIVE -> painterResource(R.drawable.trending_down)
                    else -> painterResource(R.drawable.trending_flat)
                },
                label = stringResource(R.string.change),
                state = changeState,
                value = AnnotatedString(
                    currentChange?.let { "%+d%%".format(it) } ?: "—"
                ),
            ),
            InsightItem(
                icon = painterResource(R.drawable.calendar_month),
                label = stringResource(R.string.this_week),
                value = AnnotatedString(DataSize(weekTotal).formatted()),
            ),
        )
    )
}

/** A compact weekly trend - one line of context, not a chart that dominates the screen. */
@Composable
private fun RecentUsage() {
    val viewModel: OverviewVM = koinViewModel()
    val data by viewModel.weekUsage.collectAsStateWithLifecycle()

    if (data.isNotEmpty()) {
        CategoryTitleText(stringResource(R.string.this_week))
        Box(modifier = Modifier.card().padding(6.dp)) {
            BarGraph(data)
        }
    }
}

/** Top apps, with a link into the dedicated Apps screen for the full breakdown. */
@Composable
private fun TopApps() {
    val viewModel: OverviewVM = koinViewModel()
    val navigator: Navigator = koinInject()
    val topAppsList by viewModel.topApps.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val accentColor = if (query.dataType == DataType.Wifi) colorScheme.tertiary else colorScheme.primary

    // A Box with a single contentAlignment positions every direct child the same way, so
    // the heading and the action button - neither of which had its own Modifier.align -
    // used to both land centered at the end and render on top of each other. A Row with
    // SpaceBetween puts each at its own end instead.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryTitleText(stringResource(R.string.top_apps))
        TextButton(onClick = { navigator.goTo(AppsKey) }) {
            Text(stringResource(R.string.see_all), fontWeight = FontWeight.Medium)
        }
    }
    Box(
        modifier = Modifier
            .card()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
    ) {
        AnimatedContent(topAppsList) { list ->
            RankedAppList(apps = list, accentColor = accentColor, maxEntries = 3)
        }
    }
}
