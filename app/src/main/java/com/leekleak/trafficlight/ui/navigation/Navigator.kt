package com.leekleak.trafficlight.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavKey

class Navigator(startDestination: NavKey) {
    val backStack : SnapshotStateList<NavKey> = mutableStateListOf(startDestination)
    val current: NavKey
        get() = backStack.last()

    // The floating bottom nav's *actual* rendered footprint (toolbar + its own margins),
    // reported by NavigationManager once it's laid out. Main screens use this - falling
    // back to a static estimate for the first frame, before anything has measured it - so
    // their scroll/list bottom padding always matches the real overlay instead of an
    // assumed constant that can drift from the true size and leave content under the bar.
    var navBarHeight: Dp? by mutableStateOf(null)

    fun goTo(destination: NavKey){
        backStack.add(destination)
    }

    fun setTo(destination: NavKey){
        Snapshot.withMutableSnapshot {
            backStack.clear()
            backStack.add(destination)
        }
    }

    fun goBack(){
        if (backStack.size != 1) backStack.removeLastOrNull()
    }
}