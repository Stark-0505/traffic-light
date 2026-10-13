package com.leekleak.trafficlight.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.leekleak.trafficlight.MainActivity
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.database.UsageQuery
import com.leekleak.trafficlight.model.NetworkUsageManager
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.valueOfOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import timber.log.Timber
import java.time.LocalDate

/**
 * Shows today's usage for one network (Mobile Data by default, or whatever was chosen in
 * [WidgetConfigureActivity]) - no local quota/plan to configure, since the app no longer
 * tracks one. This mirrors Overview's own headline figure rather than a separate concept.
 */
class Widget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition

    override val sizeMode: SizeMode = SizeMode.Exact

    override val previewSizeMode = SizeMode.Responsive(
        setOf(
            DpSize(250.dp, 200.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val koinInstance = KoinPlatform.getKoin()
        val networkUsageManager: NetworkUsageManager = koinInstance.get()
        val appPreferenceRepo: AppPreferenceRepo = koinInstance.get()

        val state = getAppWidgetState(context, stateDefinition, id)
        val network = state[NETWORK]?.let { valueOfOrNull<DataType>(it) } ?: DataType.Mobile

        val sizeMetric = appPreferenceRepo.sizeMetric.first()
        val usage = withContext(Dispatchers.IO) {
            networkUsageManager.totalDayUsage(UsageQuery(network), LocalDate.now())
        }
        val dataSize = DataSize(usage)
        // provideGlance() is a plain suspend function, not a @Composable one - the widget's
        // content only enters composition inside provideContent {} below. DataSize's
        // @Composable formattedParts() reads the user's unit preference from a Compose
        // CompositionLocal, which isn't available here. toStringParts() is the same
        // formatting logic as a plain function that takes the preference directly - and
        // sizeMetric (fetched above) was already being read from prefs but never applied
        // before, so this also fixes the widget silently ignoring the user's chosen unit.
        val (usageString, decimalString, unitString) = dataSize.toStringParts(extraPrecision = true, metric = sizeMetric)

        var stateChanged = false
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
            val mutable = prefs.toMutablePreferences()
            stateChanged = mutable.recordRendered("$usageString$decimalString $unitString", network.name)
            if (!stateChanged) Timber.i("Skipping widget refresh")
            mutable
        }

        if (!stateChanged) return

        Timber.i("Updating widget")
        provideContent {
            GlanceTheme {
                BoxBackground {
                    WidgetContent(
                        usageString = "$usageString$decimalString",
                        unitString = unitString,
                        network = network,
                    )
                }
            }
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme {
                Preview()
            }
        }
    }

    @OptIn(ExperimentalGlancePreviewApi::class)
    @Composable
    @Preview(widthDp = 400, heightDp = 200)
    private fun Preview() {
        BoxBackground {
            WidgetContent(usageString = "8.5", unitString = "GB", network = DataType.Mobile)
        }
    }

    @Composable
    private fun WidgetContent(usageString: String, unitString: String, network: DataType) {
        val size = LocalSize.current
        val usageFontSize = (size.width.value / 4).coerceIn(32f, 72f).sp
        val unitFontSize = (usageFontSize.value * 0.56).sp

        Column(
            modifier = GlanceModifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = GlanceModifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    text = usageString,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = usageFontSize),
                    maxLines = 1,
                )
                Text(
                    text = unitString,
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = unitFontSize),
                    maxLines = 1,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(if (network == DataType.Wifi) R.drawable.wifi else R.drawable.cellular),
                    contentDescription = null,
                    modifier = GlanceModifier.padding(end = 4.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant)
                )
                Text(
                    text = LocalContext.current.getString(R.string.today),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                )
            }
        }
    }

    @Composable
    private fun BoxBackground(content: @Composable (() -> Unit)) {
        val cornerRadius = 24.dp
        Box(
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primary)
                .padding(2.dp)
                .cornerRadius(cornerRadius)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.surface)
                    .fillMaxSize()
                    .cornerRadius(cornerRadius)
            ) {
                content()
            }
        }
    }

    companion object {
        val NETWORK = stringPreferencesKey("widget_network")
        val LAST_NETWORK = stringPreferencesKey("last_network")
        val LAST_USAGE = stringPreferencesKey("last_usage")
    }
}
