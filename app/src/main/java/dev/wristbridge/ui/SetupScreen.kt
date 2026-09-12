package dev.wristbridge.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.wristbridge.data.Settings
import dev.wristbridge.relay.OutgoingMail
import dev.wristbridge.relay.RelayListenerService
import dev.wristbridge.relay.ReplyPollService
import dev.wristbridge.relay.SendQuota
import dev.wristbridge.relay.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Done(val ok: Boolean, val message: String) : TestState
}

@Composable
fun SetupScreen(settings: Settings, snapshot: Settings.Snapshot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf(snapshot.account) }
    var password by remember { mutableStateOf("") }
    var destination by remember { mutableStateOf(snapshot.destination) }
    var revealPassword by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    val usedToday by SendQuota.get(context).usedToday.collectAsState()

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* the relay works either way; the notice just stays hidden */ }

    /**
     * Runs a network check off the main thread and folds both success and the
     * server's own error text into one result the UI can show.
     */
    fun runCheck(sendMail: Boolean) {
        settings.setAccount(account)
        if (password.isNotBlank()) settings.setPassword(password)
        settings.setDestination(destination)

        val current = settings.snapshot
        val secret = settings.password()
        if (current.account.isBlank() || secret.isNullOrBlank()) {
            test = TestState.Done(false, "Enter your iCloud address and app-specific password first.")
            return
        }

        test = TestState.Running
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val client = SmtpClient(
                        host = RelayListenerService.ICLOUD_SMTP_HOST,
                        port = RelayListenerService.ICLOUD_SMTP_PORT,
                        username = current.account,
                        password = secret,
                    )
                    if (sendMail) {
                        client.send(
                            OutgoingMail(
                                from = current.account,
                                fromDisplayName = "Wristbridge · Test",
                                to = listOf(current.effectiveDestination),
                                subject = "If you can read this on your wrist, it works.",
                                body = "This is a test from Wristbridge on your Pixel.\n\n" +
                                    "It reached your Apple Watch through iCloud Mail, with no " +
                                    "iPhone involved.\n\nvia Wristbridge",
                            )
                        )
                    } else {
                        client.verifyCredentials()
                    }
                }
            }
            test = result.fold(
                onSuccess = {
                    TestState.Done(
                        true,
                        if (sendMail) {
                            "Sent. Check your wrist; it should arrive within a few seconds."
                        } else {
                            "iCloud accepted the login."
                        },
                    )
                },
                onFailure = { TestState.Done(false, it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))

        SectionCard(title = "iCloud account") {
            Hint(
                "This is the account your Apple Watch is signed into. Wristbridge " +
                    "sends mail as you, to you. Nothing passes through any server " +
                    "except Apple's."
            )
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("iCloud address") },
                placeholder = { Text("you@icloud.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (snapshot.hasPassword) "Replace app-specific password" else "App-specific password") },
                placeholder = { Text("xxxx-xxxx-xxxx-xxxx") },
                singleLine = true,
                visualTransformation = if (revealPassword) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { revealPassword = !revealPassword }) {
                        Text(if (revealPassword) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                if (snapshot.hasPassword) {
                    "A password is already saved. Leave this blank to keep it."
                } else {
                    "Not your Apple ID password. Generate one at account.apple.com → " +
                        "Sign-In and Security → App-Specific Passwords. Hyphens are " +
                        "stripped automatically."
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { openUrl(context, "https://account.apple.com") }) {
                    Text("Open account.apple.com")
                }
                if (snapshot.hasPassword) {
                    TextButton(
                        onClick = {
                            settings.forgetPassword()
                            password = ""
                        }
                    ) { Text("Forget password") }
                }
            }
        }

        SectionCard(title = "Deliver to") {
            OutlinedTextField(
                value = destination,
                onValueChange = { destination = it },
                label = { Text("Destination address (optional)") },
                placeholder = { Text(account.ifBlank { "defaults to your iCloud address" }) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(
                "Leave blank to send to yourself. Some people prefer a second iCloud " +
                    "alias here so relayed notifications stay out of their main inbox. " +
                    "The watch still shows them as long as the alias is on the same account."
            )
        }

        SectionCard(title = "Reply from your wrist") {
            Hint(
                "Android exposes a notification's Reply button to other devices. " +
                    "It is how a Wear OS watch answers a message. Wristbridge holds " +
                    "onto it, watches iCloud for your reply, and sends your answer " +
                    "back through the original app."
            )
            ToggleSetting(
                label = "Enable reply channel",
                detail = "Reply to the mail on your watch and it goes out as a normal " +
                    "message from the app it came from.",
                checked = snapshot.replyChannelEnabled,
                onCheckedChange = { wanted ->
                    settings.setReplyChannelEnabled(wanted)
                    // The poller runs in the foreground, so on Android 13+ it
                    // needs permission to show its ongoing notice. Asked here,
                    // where the prompt has context, rather than at launch.
                    if (wanted && notificationPermission != null) {
                        permissionLauncher.launch(arrayOf(notificationPermission))
                    }
                    ReplyPollService.sync(context)
                },
            )
            if (snapshot.replyChannelEnabled) {
                NumberSetting(
                    label = "Check for replies every",
                    suffix = "seconds",
                    value = snapshot.replyPollSeconds,
                    onValueChange = {
                        settings.setReplyPollSeconds(it)
                        ReplyPollService.sync(context)
                    },
                    steps = listOf(30, 60, 120, 300),
                )
                Hint(
                    "Two honest caveats. This keeps a quiet ongoing notification in " +
                        "your shade, because the poll has to run in the foreground to " +
                        "be timely. And a reply only works while the original " +
                        "notification still exists on the phone. Swipe it away and " +
                        "Android revokes the reply permission with it."
                )
            }
        }

        SectionCard(title = "Check it works") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { runCheck(sendMail = false) },
                    enabled = test != TestState.Running,
                ) { Text("Test login") }
                OutlinedButton(
                    onClick = { runCheck(sendMail = true) },
                    enabled = test != TestState.Running,
                ) { Text("Send to watch") }
                if (test == TestState.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            when (val state = test) {
                is TestState.Done -> Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (state.ok) null else FontFamily.Monospace,
                    color = if (state.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                else -> Unit
            }
        }

        SectionCard(title = "Tuning") {
            Hint(
                "These keep a chatty app from filling your inbox and draining the " +
                    "phone. The defaults are deliberately conservative."
            )
            NumberSetting(
                label = "Collapse repeats within",
                suffix = "seconds",
                value = snapshot.minQuietSeconds,
                onValueChange = settings::setMinQuietSeconds,
                steps = listOf(0, 10, 20, 60, 300),
            )
            NumberSetting(
                label = "Maximum per hour",
                suffix = "messages",
                value = snapshot.maxPerHour,
                onValueChange = settings::setMaxPerHour,
                steps = listOf(20, 60, 120, 250, 500),
            )
            NumberSetting(
                label = "Maximum per day",
                suffix = "messages",
                value = snapshot.maxPerDay,
                onValueChange = settings::setMaxPerDay,
                steps = listOf(100, 300, 500, 900),
            )
            Hint(
                "Apple allows ${SendQuota.ICLOUD_DAILY_LIMIT} messages a day from an " +
                    "iCloud account, and soft-blocks accounts that look like bulk " +
                    "senders. The default leaves plenty of room for your real email. " +
                    "$usedToday used so far today."
            )
            ToggleSetting(
                label = "Forward ongoing notifications",
                detail = "Music players, downloads, navigation. Usually noise on a watch.",
                checked = snapshot.includeOngoing,
                onCheckedChange = settings::setIncludeOngoing,
            )
            ToggleSetting(
                label = "Respect \"local only\" flag",
                detail = "Skip notifications an app marked as not for other devices.",
                checked = snapshot.respectLocalOnly,
                onCheckedChange = settings::setRespectLocalOnly,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NumberSetting(
    label: String,
    suffix: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    steps: List<Int>,
) {
    Column {
        Text("$label: $value $suffix", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEach { step ->
                if (step == value) {
                    Button(onClick = { onValueChange(step) }) { Text("$step") }
                } else {
                    OutlinedButton(onClick = { onValueChange(step) }) { Text("$step") }
                }
            }
        }
    }
}

@Composable
private fun ToggleSetting(
    label: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
