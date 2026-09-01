package com.leekleak.trafficlight.ui.navigation

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.ui.NavDisplay
import com.leekleak.trafficlight.R
import org.koin.compose.koinInject
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

val NAVBAR_PADDING = FloatingToolbarDefaults.ContainerSize + FloatingToolbarDefaults.ScreenOffset - 8.dp

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3ExpressiveApi::class, KoinExperimentalAPI::class)
@Composable
fun NavigationManager() {
    val navigator: Navigator = koinInject()
    val backStack = navigator.backStack

    var showBottomBar by remember { mutableStateOf(false) }
    val currentEntry = navigator.backStack.lastOrNull()

    LaunchedEffect(currentEntry) {
        showBottomBar = mainScreens.contains(currentEntry)
    }

    val entryProvider = koinEntryProvider<Any>()

    val density = LocalDensity.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp)
                    .then(
                        // Report the bar's real, laid-out footprint (pill + its own 8dp
                        // margins, already excluding the system nav-bar inset consumed by
                        // navigationBarsPadding() above) back to the main screens that
                        // reserve space for it - only while it's actually showing, so a
                        // screen transition never latches the collapsed/exiting size.
                        if (showBottomBar) {
                            Modifier.onGloballyPositioned { coordinates ->
                                navigator.navBarHeight = with(density) { coordinates.size.height.toDp() }
                            }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically {it} + fadeIn(tween()),
                    exit = slideOutVertically {it} + fadeOut(tween())
                ) {
                    HorizontalFloatingToolbar(
                        modifier = Modifier.border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                            MaterialTheme.shapes.extraLargeIncreased
                        ),
                        expanded = true,
                        colors = FloatingToolbarDefaults.standardFloatingToolbarColors().copy(
                            toolbarContainerColor = MaterialTheme.colorScheme.background,
                        ),
                        content = {
                            NavigationButton(navigator, OverviewKey, stringResource(R.string.overview), R.drawable.overview)
                            NavigationButton(navigator, HistoryKey, stringResource(R.string.history), R.drawable.history)
                            NavigationButton(navigator, AppsKey, stringResource(R.string.apps), R.drawable.apps)
                        },
                    )
                }
            }
        }
    ) {
        NavDisplay(
            backStack = navigator.backStack,
            onBack = { navigator.goBack() },
            entryProvider = entryProvider,
            transitionSpec = {
                if (backStack.size == 1) fadeIn(tween()) togetherWith fadeOut(tween())
                else {
                    slideInHorizontally { it } togetherWith
                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
                }
            },
            popTransitionSpec = {
                slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn(tween()) togetherWith
                slideOutHorizontally { it }
            },
            predictivePopTransitionSpec = {
                slideInHorizontally { -it/2 } + scaleIn(initialScale = 0.7f) + fadeIn(tween()) togetherWith
                slideOutHorizontally { it }
            }
        )
    }
}


@Composable
fun NavigationButton(navigator: Navigator, route: NavKey, name: String, icon: Int) {
    val selected = navigator.current == route
    val horizontalPadding by animateDpAsState(if (selected) 24.dp else 12.dp)
    val haptic = LocalHapticFeedback.current
    Button (
        colors =
            if (navigator.current == route){
                ButtonDefaults.filledTonalButtonColors()
            } else {
                ButtonDefaults.textButtonColors()
            },
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            navigator.setTo(route)
        },
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = horizontalPadding)
    ) {
        Row {
            Icon(
                painter = painterResource(icon),
                contentDescription = route.toString()
            )
            AnimatedVisibility(navigator.current == route) {
                Text(
                    modifier = Modifier.padding(start = 4.dp),
                    text = name
                )
            }
        }
    }
}