package dev.wristbridge.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.wristbridge.data.SecureStore
import dev.wristbridge.data.Settings

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SecureStore.warmUp(this)

        setContent {
            WristbridgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    WristbridgeApp()
                }
            }
        }
    }
}

private enum class Screen(val label: String) {
    STATUS("Status"),
    SETUP("Setup"),
    APPS("Apps"),
    ACTIVITY("Activity"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WristbridgeApp() {
    val context = LocalContext.current
    val settings = remember { Settings.get(context) }
    val snapshot by settings.state.collectAsState()

    var selected by remember { mutableIntStateOf(0) }

    // Notification access is granted in system settings, so re-check whenever
    // the user comes back to the app rather than caching a stale answer.
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = hasNotificationAccess(context)
                batteryExempt = isBatteryExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Wristbridge") }) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = selected) {
                Screen.entries.forEachIndexed { index, screen ->
                    Tab(
                        selected = selected == index,
                        onClick = { selected = index },
                        text = { Text(screen.label) },
                    )
                }
            }
            when (Screen.entries[selected]) {
                Screen.STATUS -> StatusScreen(
                    settings = settings,
                    snapshot = snapshot,
                    notificationAccess = notificationAccess,
                    batteryExempt = batteryExempt,
                    onOpenNotificationAccess = { openNotificationAccess(context) },
                    onRequestBatteryExemption = { requestBatteryExemption(context) },
                    onGoToSetup = { selected = Screen.SETUP.ordinal },
                    onGoToApps = { selected = Screen.APPS.ordinal },
                )
                Screen.SETUP -> SetupScreen(settings = settings, snapshot = snapshot)
                Screen.APPS -> AppsScreen(settings = settings, snapshot = snapshot)
                Screen.ACTIVITY -> ActivityScreen()
            }
        }
    }
}

/**
 * Reads the system's list of approved notification listeners. There is no
 * public API to query this for your own app, so the secure setting is the
 * supported route.
 */
internal fun hasNotificationAccess(context: Context): Boolean {
    val flat = AndroidSettings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners",
    ).orEmpty()
    return flat.split(':').any { entry ->
        entry.substringBefore('/') == context.packageName
    }
}

internal fun openNotificationAccess(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun isBatteryExempt(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun requestBatteryExemption(context: Context) {
    runCatching {
        context.startActivity(
            Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
