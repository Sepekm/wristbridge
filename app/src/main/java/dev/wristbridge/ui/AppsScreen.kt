package dev.wristbridge.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wristbridge.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class InstalledApp(val packageName: String, val label: String)

@Composable
fun AppsScreen(settings: Settings, snapshot: Settings.Snapshot) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // Enumerating and labelling every package is slow enough to jank the first
    // frame, so it happens off the main thread and the list renders when ready.
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionCard(title = "Apps to forward") {
            Hint(
                "Start narrow. Pick the two or three apps you actually want on your " +
                    "wrist. Every selected app sends mail, and a chatty one will make " +
                    "itself known quickly."
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val loaded = apps
        if (loaded == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
            }
            return@Column
        }

        val filtered = remember(loaded, query, snapshot.relayedPackages) {
            val needle = query.trim().lowercase(Locale.getDefault())
            loaded
                .filter { needle.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(needle) }
                // Selected apps float to the top so the current choice is never
                // buried under a few hundred packages.
                .sortedWith(
                    compareByDescending<InstalledApp> { it.packageName in snapshot.relayedPackages }
                        .thenBy { it.label.lowercase(Locale.getDefault()) }
                )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = app.packageName in snapshot.relayedPackages,
                        onCheckedChange = { settings.setPackageRelayed(app.packageName, it) },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Lists apps the user could plausibly want forwarded: anything with a launcher
 * entry. Framework packages without a launcher icon are excluded because they
 * would bury the list without ever producing a notification worth relaying.
 */
private fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }.getOrDefault(emptyList())
}
