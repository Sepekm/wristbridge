package dev.wristbridge.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.wristbridge.data.Settings
import dev.wristbridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches the iCloud mailbox for replies written on the watch and pushes them
 * back into the app that raised the original notification.
 *
 * This runs in the foreground because the whole value is timeliness: a reply
 * that arrives twenty minutes late is worse than no reply channel at all, and
 * background work on modern Android is deferred far longer than that.
 */
class ReplyPollService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private var poller: Job? = null

    private val settings by lazy { Settings.get(this) }

    private val watermarkPrefs by lazy {
        getSharedPreferences("wristbridge.replies", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (poller?.isActive != true) {
            poller = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val config = settings.snapshot
            if (!config.replyChannelEnabled || !config.isConfigured) {
                stopSelf()
                return
            }

            try {
                pollOnce(config)
            } catch (e: Exception) {
                scope.ensureActive()
                // A failed poll is usually transient (no network, iCloud
                // hiccup). Log it and wait for the next cycle rather than
                // tearing the service down.
                Log.w(TAG, "Reply poll failed", e)
                RelayLog.record(
                    RelayLog.Outcome.FAILED,
                    "Reply channel",
                    "Could not check for replies",
                    e.message,
                )
            }

            delay(config.replyPollSeconds.coerceAtLeast(15) * 1000L)
        }
    }

    private fun pollOnce(config: Settings.Snapshot) {
        val password = settings.password() ?: return

        val client = ImapClient(
            host = ImapClient.ICLOUD_IMAP_HOST,
            port = ImapClient.ICLOUD_IMAP_PORT,
            username = config.account,
            password = password,
        )

        client.withSession { session ->
            session.selectInbox()
            val watermark = watermarkPrefs.getLong(KEY_WATERMARK, 0L)
            val candidates = session.findReplies().filter { it > watermark }.sorted()
            if (candidates.isEmpty()) return@withSession

            var highest = watermark
            for (uid in candidates) {
                highest = maxOf(highest, uid)
                val message = session.fetch(uid) ?: continue
                handleReply(message)
                session.markSeen(uid)
            }
            watermarkPrefs.edit().putLong(KEY_WATERMARK, highest).apply()
        }
    }

    private fun handleReply(message: ImapClient.Message) {
        val token = TOKEN_PATTERN.find(message.inReplyTo.orEmpty())?.groupValues?.get(1)
        if (token == null) {
            Log.w(TAG, "Reply with no usable token")
            return
        }

        val text = message.bodyText.trim()
        if (text.isEmpty()) {
            RelayLog.record(RelayLog.Outcome.SKIPPED, "Reply channel", "Empty reply ignored")
            return
        }

        val pending = ReplyRegistry.consume(token)
        if (pending == null) {
            RelayLog.record(
                RelayLog.Outcome.FAILED,
                "Reply channel",
                text.take(60),
                "That conversation is no longer held in memory: the app restarted, " +
                    "or the notification is over a day old.",
            )
            return
        }

        val failure = ReplyRegistry.deliver(this, pending, text)
        if (failure == null) {
            RelayLog.record(RelayLog.Outcome.SENT, pending.appLabel, "Replied: ${text.take(60)}")
        } else {
            RelayLog.record(RelayLog.Outcome.FAILED, pending.appLabel, text.take(60), failure)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // IMPORTANCE_MIN keeps it collapsed in the shade with no sound or badge;
        // a foreground service must show something, but it need not intrude.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reply channel",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Shown while Wristbridge is watching iCloud for replies from your watch."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Wristbridge reply channel")
            .setContentText("Watching iCloud for replies from your watch")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Wristbridge"
        private const val CHANNEL_ID = "wristbridge.replies"
        private const val NOTIFICATION_ID = 4711
        private const val KEY_WATERMARK = "highest_handled_uid"

        /** Pulls the token out of "<token@wristbridge.local>". */
        private val TOKEN_PATTERN =
            Regex("""<([A-Za-z0-9]+)@${Regex.escape(ImapClient.MESSAGE_ID_DOMAIN)}>""")

        /** Starts or stops the poller to match the current settings. */
        fun sync(context: Context) {
            val config = Settings.get(context).snapshot
            val intent = Intent(context, ReplyPollService::class.java)
            if (config.replyChannelEnabled && config.isConfigured && config.relayEnabled) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
