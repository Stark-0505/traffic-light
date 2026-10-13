package com.leekleak.trafficlight

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.setWidgetPreviews
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import com.leekleak.trafficlight.database.AppPreferenceRepo
import com.leekleak.trafficlight.integrations.PlayServicesProvider
import com.leekleak.trafficlight.services.notifications.NotificationService
import com.leekleak.trafficlight.ui.app.App
import com.leekleak.trafficlight.ui.theme.AppTheme
import com.leekleak.trafficlight.widget.WidgetReceiver
import com.leekleak.trafficlight.widget.registerWidgetPreviews
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject

class MainActivity : ComponentActivity() {

    private val appPreferenceRepo: AppPreferenceRepo by inject()
    private val playServicesProvider: PlayServicesProvider by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // savedInstanceState != null means a configuration change or process-death restore of
        // an activity that already did this on its original launch; repeating it on every
        // rotation would just re-run the (rate-limited) preview registration.
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            lifecycleScope.launch {
                registerWidgetPreviews {
                    GlanceAppWidgetManager(applicationContext).setWidgetPreviews<WidgetReceiver>()
                }
            }
        }

        lifecycleScope.launch {
            appPreferenceRepo.notification.collectLatest {
                NotificationService.startService(applicationContext, this)
            }
        }

        lifecycleScope.launch {
            playServicesProvider.onAppLaunch(this@MainActivity)
        }

        setContent {
            val imageLoader: ImageLoader = koinInject()
            setSingletonImageLoaderFactory { imageLoader }

            AppTheme {
                App()
            }
        }
    }
}