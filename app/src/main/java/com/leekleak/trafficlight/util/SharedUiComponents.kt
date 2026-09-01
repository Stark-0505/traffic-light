package com.leekleak.trafficlight.util

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.ui.components.BackAction
import com.leekleak.trafficlight.ui.navigation.Navigator
import com.leekleak.trafficlight.ui.theme.LocalBlurEnabled
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import org.koin.compose.koinInject

/** Semantic outcome shown by a small "up/down vs. before" indicator (e.g. Overview's change card). */
enum class MiniCardState {
    POSITIVE,
    NEUTRAL,
    NEGATIVE
}

val TOP_BAR_HEIGHT: Dp = 52.dp

@Composable
fun PageTitle(
    backAction: BackAction = BackAction.None,
    hazeState: HazeState? = null,
    text: String?,
    customElement: @Composable (RowScope.() -> Unit)? = null,
){
    val blurEnabled = LocalBlurEnabled.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                hazeState?.let {
                    Modifier.hazeBlur(
                        input = HazeInput.Sources(it),
                        style = HazeMaterials.ultraThin().then {
                            progressive(HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f))
                            blurEnabled(blurEnabled)
                        }
                    )
                } ?: Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 6.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row (modifier = Modifier.height(TOP_BAR_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
                if (backAction is BackAction.Normal) {
                    IconButton(onClick = { backAction.navigator.goBack() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.go_back),
                        )
                    }
                }
                text?.let {
                    Text(
                        modifier = Modifier.padding(8.dp),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        overflow = TextOverflow.Ellipsis,
                        text = text
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            customElement?.let { it() }
        }
    }
}

fun LazyListScope.categoryTitle(text: @Composable (() -> String)){
    item { CategoryTitleText(text()) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CategoryTitleText(text: String, backButton: Boolean = false) {
    val navigator: Navigator = koinInject()
    Row (modifier = Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically){
        if (backButton) {
            IconButton(onClick = { navigator.goBack() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = stringResource(R.string.go_back),
                )
            }
        }
        Text(
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            text = text
        )
    }
}

fun LazyListScope.categoryTitleSmall(text: @Composable (() -> String)) = item { CategoryTitleSmallText(text()) }

@Composable
fun CategoryTitleSmallText(text: String) {
    Text(
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = colorScheme.tertiary
    )
}

@Composable
fun SearchField(textFieldState: TextFieldState) {
    Row(
        modifier = Modifier
            .padding(8.dp)
            .fillMaxWidth()
            .background(colorScheme.surfaceContainerHigh, shapes.extraLarge)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.search),
            contentDescription = null
        )
        BasicTextField(
            modifier = Modifier.fillMaxWidth(),
            state = textFieldState,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colorScheme.onSurface),
            cursorBrush = SolidColor(colorScheme.onSurface)
        )
    }
}

/**
 * A single toggle button used by every Network/Period selector in the app (Overview,
 * History, Apps, App Detail).
 *
 * Deliberately NOT built on Material3's `IconButton`: `IconButton` measures its content
 * inside a fixed ~40dp square sized for a single centered icon, and clips drawing to that
 * square. Once a caller adds a text label next to the icon, the label gets measured and
 * clipped to that same fixed box - the exact "icon plus one visible letter" clipping this
 * button used to produce for every Network/Period toggle in the app. Building this as a
 * plain clickable `Row` instead lets width follow the icon+label content, while
 * `heightIn(min = 48.dp)` still guarantees an accessible touch target for icon-only
 * (period-less, label-less) usages.
 */
@Composable
fun ToggleIconButton(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    showBadge: Boolean = false,
    toggledColors: IconButtonColors? = null,
    icon: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val press by source.collectIsPressedAsState()
    val cornerRadius by animateDpAsState(if (press || selected) 12.dp else 24.dp)
    val containerColor by animateColorAsState(
        targetValue = if (selected) toggledColors?.containerColor ?: colorScheme.primaryContainer
        else colorScheme.surfaceContainer
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) toggledColors?.contentColor ?: colorScheme.onPrimaryContainer
        else colorScheme.onSurfaceVariant
    )
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = {
                    onSelect()
                    haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                }
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            if (showBadge) {
                BadgedBox({ Badge() }) { icon() }
            } else icon()
            text?.let { Text(it, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
fun EqualHeightRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val spacingPx = spacing.roundToPx()
        val colWidth = (constraints.maxWidth - spacingPx) / 2
        val colConstraints = constraints.copy(
            minWidth = colWidth, maxWidth = colWidth,
            minHeight = 0, maxHeight = Constraints.Infinity
        )

        // Pass 1: measure natural height
        val firstHeight = subcompose("first_measure", first)
            .sumOf { it.measure(colConstraints).height }
        val secondHeight = subcompose("second_measure", second)
            .sumOf { it.measure(colConstraints).height }
        val maxHeight = maxOf(firstHeight, secondHeight)

        // Pass 2: re-measure at equal height
        val fixedConstraints = Constraints.fixed(colWidth, maxHeight)
        val firstPlaceables = subcompose("first_place", first)
            .map { it.measure(fixedConstraints) }
        val secondPlaceables = subcompose("second_place", second)
            .map { it.measure(fixedConstraints) }

        layout(constraints.maxWidth, maxHeight) {
            firstPlaceables.forEach { it.placeRelative(0, 0) }
            secondPlaceables.forEach { it.placeRelative(colWidth + spacingPx, 0) }
        }
    }
}

@Composable
fun SlideAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    val visibleState = remember { MutableTransitionState(visible) }.apply { targetState = visible }

    AnimatedVisibility(
        visibleState = visibleState,
        modifier = modifier,
        enter = fadeIn(tween()) + slideInVertically() + expandVertically(),
        exit = fadeOut(tween()) + slideOutVertically() + shrinkVertically(),
        content = content
    )
}

@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(pass = PointerEventPass.Initial)
            focusManager.clearFocus()
        }
    }
}
