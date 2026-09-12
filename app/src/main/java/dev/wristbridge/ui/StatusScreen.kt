package dev.wristbridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wristbridge.data.Settings
import dev.wristbridge.relay.RelayLog
import dev.wristbridge.relay.ReplyPollService

@Composable
fun StatusScreen(
    settings: Settings,
    snapshot: Settings.Snapshot,
    notificationAccess: Boolean,
    batteryExempt: Boolean,
    onOpenNotificationAccess: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onGoToSetup: () -> Unit,
    onGoToApps: () -> Unit,
) {
    val context = LocalContext.current
    val sentCount by RelayLog.sentCount.collectAsState()
    val ready = notificationAccess && snapshot.isConfigured && snapshot.relayedPackages.isNotEmpty()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(8.dp))

        SectionCard(title = if (ready) "Relay ready" else "Finish setup") {
            StatusRow(
                ok = snapshot.relayEnabled && ready,
                label = "Relay notifications to your watch",
                detail = when {
                    !ready -> "Complete the steps below first"
                    snapshot.relayEnabled -> "Running, $sentCount relayed this session"
                    else -> "Paused"
                },
                trailing = {
                    Switch(
                        checked = snapshot.relayEnabled,
                        enabled = ready,
                        onCheckedChange = {
                            settings.setRelayEnabled(it)
                            // The reply poller only runs while the relay does.
                            ReplyPollService.sync(context)
                        },
                    )
                },
            )
        }

        SectionCard(title = "Setup steps") {
            StatusRow(
                ok = notificationAccess,
                label = "Notification access",
                detail = if (notificationAccess) {
                    "Granted"
                } else {
                    "Wristbridge needs to read notifications to forward them"
                },
                trailing = {
                    if (!notificationAccess) {
                        TextButton(onClick = onOpenNotificationAccess) { Text("Grant") }
                    }
                },
            )
            StatusRow(
                ok = snapshot.isConfigured,
                label = "iCloud account",
                detail = if (snapshot.isConfigured) {
                    snapshot.account
                } else {
                    "Add your iCloud address and an app-specific password"
                },
                trailing = {
                    if (!snapshot.isConfigured) {
                        TextButton(onClick = onGoToSetup) { Text("Set up") }
                    }
                },
            )
            StatusRow(
                ok = snapshot.relayedPackages.isNotEmpty(),
                label = "Apps to forward",
                detail = when (val count = snapshot.relayedPackages.size) {
                    0 -> "No apps selected yet"
                    1 -> "1 app selected"
                    else -> "$count apps selected"
                },
                trailing = { TextButton(onClick = onGoToApps) { Text("Choose") } },
            )
            StatusRow(
                ok = batteryExempt,
                label = "Unrestricted battery",
                detail = if (batteryExempt) {
                    "Granted, the relay will not be frozen in the background"
                } else {
                    "Optional, but Android may delay notifications without it"
                },
                trailing = {
                    if (!batteryExempt) {
                        TextButton(onClick = onRequestBatteryExemption) { Text("Allow") }
                    }
                },
            )
        }

        SectionCard(title = "On the watch") {
            Hint(
                "Your Apple Watch must be activated, signed into the same iCloud " +
                    "account, and on Wi-Fi or cellular. Open the Mail app on the watch " +
                    "once so it syncs, and make sure Mail notifications are turned on " +
                    "in the watch's Settings › Notifications."
            )
            Hint(
                "Apple documents that SMS and third-party notifications need the " +
                    "paired iPhone powered on. iCloud Mail appears not to, which is " +
                    "what this relies on, but Apple does not document it either way. " +
                    "The Send to watch test in Setup is how you confirm it for your " +
                    "own watch."
            )
        }

        if (!ready) {
            SectionCard(title = "Quick check") {
                Text(
                    "Not sure the watch side is alive? Send yourself a test from the " +
                        "Setup tab. It arrives as mail, exactly like a relayed " +
                        "notification will.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onGoToSetup) { Text("Go to Setup") }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
