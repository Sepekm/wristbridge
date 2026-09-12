package dev.wristbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wristbridge.ble.BleLinkService
import dev.wristbridge.data.Settings
import dev.wristbridge.health.HealthStore
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun WatchScreen(settings: Settings, snapshot: Settings.Snapshot) {
    val context = LocalContext.current
    val link by BleLinkService.observable.collectAsState()
    val healthStore = remember { HealthStore.get(context) }
    val samples by healthStore.samples.collectAsState()
    val timeFormat = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    // Advertising over BLE needs a runtime grant on Android 12 and later.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            settings.setBleLinkEnabled(true)
            BleLinkService.start(context)
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        SectionCard(title = "Direct watch link") {
            Hint(
                "This is the half that needs the watchOS app installed. When your " +
                    "watch is in Bluetooth range, notifications go straight to it " +
                    "instantly, with no mail in your inbox, and health data comes " +
                    "back the other way. Out of range, the iCloud relay takes over."
            )
            StatusRow(
                ok = snapshot.bleLinkEnabled && link.advertising,
                label = "Discoverable by your watch",
                detail = when {
                    !snapshot.bleLinkEnabled -> "Off"
                    link.lastError != null -> link.lastError!!
                    link.advertising -> "Advertising"
                    else -> "Starting…"
                },
                trailing = {
                    Switch(
                        checked = snapshot.bleLinkEnabled,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                settings.setBleLinkEnabled(false)
                                BleLinkService.stop(context)
                            } else if (hasBlePermissions(context)) {
                                settings.setBleLinkEnabled(true)
                                BleLinkService.start(context)
                            } else {
                                permissions.launch(blePermissions())
                            }
                        },
                    )
                },
            )
            StatusRow(
                ok = link.connectedWatch != null,
                label = "Watch connected",
                detail = link.connectedWatch ?: "No watch has connected yet",
            )
            Hint(
                "The first watch to connect is remembered, and after that only " +
                    "that watch is accepted. The link also requires Bluetooth " +
                    "pairing, so nothing is readable by another device in range."
            )
            TextButton(
                onClick = { BleLinkService.instance?.forgetPairedWatch() }
            ) { Text("Forget this watch") }
            if (link.messagesIn > 0 || link.messagesOut > 0) {
                Hint("${link.messagesOut} sent · ${link.messagesIn} received this session")
            }
        }

        SectionCard(title = "From your watch") {
            if (samples.isEmpty()) {
                Hint(
                    "Nothing yet. Health data only arrives once the watchOS app is " +
                        "installed and connected. See watch/README.md for how to " +
                        "build and install it."
                )
            } else {
                val latest = healthStore.latestByKind
                for ((kind, sample) in latest.entries.sortedBy { HealthStore.Kind.label(it.key) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                HealthStore.Kind.label(kind),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                timeFormat.format(sample.startedAt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatValue(sample.value) + " " + sample.unit,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Hint("${samples.size} samples stored")
                TextButton(onClick = healthStore::clear) { Text("Clear health data") }
            }
        }

        SectionCard(title = "Installing the watch app") {
            Hint(
                "The watchOS app is in watch/ in this repository. It needs a Mac " +
                    "with Xcode to build, and an iPhone connected to that Mac once, " +
                    "which is how Developer Mode is usually made to appear."
            )
            Hint(
                "Apple's free signing lasts 7 days, after which the app stops " +
                    "launching and must be rebuilt from the Mac. A paid Apple " +
                    "Developer Program membership signs it for a year. That is " +
                    "the difference between a weekly chore and a one-time setup."
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun formatValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

/**
 * Everything needed before the watch link can run: the Bluetooth grants, plus
 * notification permission so its foreground service can show the ongoing
 * notice Android requires of it.
 */
internal fun blePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

internal fun hasBlePermissions(context: android.content.Context): Boolean =
    blePermissions().all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
