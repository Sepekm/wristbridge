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
import androidx.compose.runtime.LaunchedEffect
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
    var showAll by remember { mutableStateOf(false) }

    // Enumerating and labelling every package is slow enough to jank the first
    // frame, so it happens off the main thread and the list renders when ready.
    // Reloads when showAll flips. The previous list stays on screen while the
    // new one loads, which reads better than blanking it.
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    LaunchedEffect(showAll) {
        apps = withContext(Dispatchers.IO) { loadApps(context, showAll) }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show every installed app", style = MaterialTheme.typography.bodyLarge)
                    Hint(
                        "Off by default, showing only apps with an icon. Turn it on " +
                            "to reach something that notifies you without appearing " +
                            "in your app list."
                    )
                }
                Switch(checked = showAll, onCheckedChange = { showAll = it })
            }
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
 * Lists apps that can be forwarded.
 *
 * The default is anything with a launcher entry, which is what almost everyone
 * wants and keeps the list readable. But plenty of things that notify have no
 * launcher icon at all, from authenticator helpers to work-profile and system
 * components, and restricting the list to launchable apps made those
 * impossible to choose rather than merely awkward. [includeAll] opens it up.
 */
private fun loadApps(context: Context, includeAll: Boolean): List<InstalledApp> {
    val pm = context.packageManager
    return runCatching {
        val packages = if (includeAll) {
            pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
        } else {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.applicationInfo }
        }
        packages.asSequence()
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }.getOrDefault(emptyList())
}
