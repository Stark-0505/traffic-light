package com.leekleak.trafficlight.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.core.net.toUri
import com.leekleak.trafficlight.R
import kotlin.enums.enumEntries

/**
 * Small, genuinely miscellaneous helpers with no natural home elsewhere - date/time
 * formatting lives in DateTimeFormatting.kt, and screen-agnostic Compose components (the
 * ones every screen's toolbar/category header/toggle button is built from) live in
 * SharedUiComponents.kt. Both are in this same package, so nothing outside this package
 * needed to change when they moved out of this file.
 */

enum class NetworkType {
    Cellular,
    Wifi,
}

fun String.clipAndPad(length: Int): String {
    return if (this.length >= length) {
        this.substring(0, length)
    } else {
        this.padEnd(length, ' ')
    }
}

inline val Dp.px: Float
    @Composable get() = with(LocalDensity.current) { this@px.toPx() }

inline val Int.toDp: Dp
    @Composable get() = with(LocalDensity.current) { this@toDp.toDp() }

inline val Dp.toSp: TextUnit
    @Composable get() = with(LocalDensity.current) { this@toSp.toSp() }

inline fun <reified T : Enum<T>> valueOfOrNull(name: String): T? {
    return enumEntries<T>().find { it.name.equals(name, ignoreCase = true) }
}

fun openLink(activity: Activity?, link: String) {
    activity?.startActivity(
        Intent(
            Intent.ACTION_VIEW,
            link.toUri()
        )
    )
}

@DrawableRes
fun simIconRes(number: Int): Int {
    return when (number) {
        0 -> R.drawable.sim_card_1
        1 -> R.drawable.sim_card_2
        else -> R.drawable.sim_card
    }
}

fun convertFontFamilyToTypeface(context: Context, fontFamily: FontFamily): android.graphics.Typeface {
    val resolver = createFontFamilyResolver(context)

    val result = resolver.resolve(
        fontFamily = fontFamily
    )

    return result.value as android.graphics.Typeface
}
