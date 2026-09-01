package com.leekleak.trafficlight.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.database.DataType
import com.leekleak.trafficlight.ui.settings.NavigatePreference
import com.leekleak.trafficlight.ui.theme.AppTheme
import com.leekleak.trafficlight.util.categoryTitle
import com.leekleak.trafficlight.widget.Widget.Companion.NETWORK
import kotlinx.coroutines.launch

/** Lets the user pick which network the widget shows - Mobile Data or Wi-Fi. */
class WidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_CANCELED, resultValue)

        setContent {
            AppTheme {
                Scaffold { paddingValues ->
                    Content(appWidgetId, resultValue, paddingValues)
                }
            }
        }
    }

    @Composable
    private fun Content(appWidgetId: Int, resultValue: Intent, paddingValues: PaddingValues) {
        val scope = rememberCoroutineScope()

        fun onSelect(network: DataType) {
            val glanceManager = GlanceAppWidgetManager(this@WidgetConfigureActivity)
            val glanceId = glanceManager.getGlanceIdBy(appWidgetId)

            scope.launch {
                updateAppWidgetState(this@WidgetConfigureActivity, glanceId) { prefs ->
                    prefs[NETWORK] = network.name
                }
                setResult(RESULT_OK, resultValue)
                startAlarmManager(this@WidgetConfigureActivity)
                Widget().update(this@WidgetConfigureActivity, glanceId)
                finish()
            }
        }

        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = paddingValues
        ) {
            categoryTitle { stringResource(R.string.add_widget) }
            items(listOf(DataType.Mobile, DataType.Wifi)) { network ->
                NavigatePreference(
                    title = stringResource(network.nameRes),
                    icon = painterResource(network.iconRes),
                    onClick = { onSelect(network) },
                    showControl = false
                )
            }
        }
    }
}
