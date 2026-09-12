package dev.wristbridge.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wristbridge.relay.RelayLog
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ActivityScreen() {
    val entries by RelayLog.entries.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        SectionCard(title = "Recent activity") {
            Hint(
                "Live view of what the relay did, newest first. Cleared when the app " +
                    "process restarts. This log is held in memory and never " +
                    "written to disk, though relayed text does of course reach Apple."
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = RelayLog::clear) { Text("Clear") }
            }
        }

        if (entries.isEmpty()) {
            Text(
                text = "Nothing yet. Once the relay is on and a selected app posts a " +
                    "notification, it shows up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(entries) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = when (entry.outcome) {
                            RelayLog.Outcome.SENT -> "→"
                            RelayLog.Outcome.FAILED -> "✕"
                            RelayLog.Outcome.SKIPPED -> "·"
                        },
                        color = when (entry.outcome) {
                            RelayLog.Outcome.SENT -> Color(0xFF2E9E5B)
                            RelayLog.Outcome.FAILED -> MaterialTheme.colorScheme.error
                            RelayLog.Outcome.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${entry.appLabel} · ${timeFormat.format(entry.at)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
                        entry.detail?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
