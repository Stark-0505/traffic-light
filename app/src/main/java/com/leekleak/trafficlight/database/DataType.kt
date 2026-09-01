package com.leekleak.trafficlight.database

import android.content.Context
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_WIFI
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.model.AppManager.Companion.allApp
import com.leekleak.trafficlight.model.DataUID

/**
 * Data Type
 *
 * [queryIndex] deliberately uses the deprecated `ConnectivityManager.TYPE_MOBILE`/
 * `TYPE_WIFI` int constants, not their `NetworkCapabilities.TRANSPORT_*` replacements:
 * [android.app.usage.NetworkStatsManager.querySummary] and
 * [android.app.usage.NetworkStatsManager.querySummaryForDevice] both take a `networkType`
 * parameter typed to this specific legacy constant space, so there is no
 * non-deprecated value to pass instead - this is a real, unavoidable API constraint of
 * NetworkStatsManager itself, not a stale leftover.
 */
@Suppress("DEPRECATION")
enum class DataType(
    @StringRes val nameRes: Int,
    @DrawableRes val iconRes: Int,
    val queryIndex: Int?
) {
    Mobile(R.string.cellular, R.drawable.cellular, TYPE_MOBILE),
    Wifi(R.string.wifi, R.drawable.wifi, TYPE_WIFI),
    None(R.string.none, R.drawable.close, null);
}

/**
 *
 * Data Direction
 *
 */
enum class DataDirection(
    @StringRes val nameRes: Int,
    @DrawableRes val iconRes: Int
) {
    Bidirectional(R.string.bidirectional, R.drawable.mobiledata_arrows),
    Download(R.string.download, R.drawable.arrow_downward_alt),
    Upload(R.string.upload, R.drawable.arrow_upward_alt);
}

/**
 *
 * Query
 *
 */
data class UsageQuery (
    val dataType: DataType,
    val dataDirection: DataDirection = DataDirection.Bidirectional,
    val dataUID: DataUID = allApp
) {
    fun toString(context: Context): String {
        if (dataType == DataType.None) return context.getString(dataType.nameRes)
        val parts = buildList {
            add(context.getString(dataType.nameRes))
            if (dataDirection != DataDirection.Bidirectional)
                add(context.getString(dataDirection.nameRes))
            if (dataUID.uidQuery != null)
                add(dataUID.getName(context))
        }

        val isRtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return (if (isRtl) parts.reversed() else parts).joinToString(" · ")
    }
}
