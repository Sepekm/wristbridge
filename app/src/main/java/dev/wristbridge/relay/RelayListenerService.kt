package dev.wristbridge.relay

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.wristbridge.ble.BleLinkService
import dev.wristbridge.ble.BleProtocol
import dev.wristbridge.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Listens for Android notifications and relays the ones you opted into as mail.
 *
 * Everything expensive happens off the callback thread: [onNotificationPosted]
 * only filters and enqueues, and a single consumer coroutine does the network
 * work so notification bursts are serialised rather than opening a socket each.
 */
class RelayListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Bounded so a runaway app cannot grow the queue without limit; the oldest
     * waiting item is dropped instead, which is the right trade for
     * notifications where freshness is the whole point.
     */
    private val queue = Channel<QueuedRelay>(capacity = 64)

    /** A notification waiting to be sent, plus the token that lets it be replied to. */
    private data class QueuedRelay(
        val item: NotificationMapper.Extracted,
        val replyToken: String?,
    )

    private val settings by lazy { Settings.get(this) }

    /** Last time each de-duplication key was relayed, for the quiet window. */
    private val lastRelayedAt = HashMap<String, Long>()

    /** Send timestamps within the trailing hour, for the rate ceiling. */
    private val recentSends = ArrayDeque<Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification access connected")
        startConsumer()
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "Notification access disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val config = settings.snapshot

        if (!config.relayEnabled || !config.isConfigured) return

        val rejection = reasonToSkip(notification, config)
        if (rejection != null) {
            // Only surface rejections for apps that were opted in, otherwise the
            // log would be nothing but noise from every app on the device.
            if (notification.packageName in config.relayedPackages) {
                RelayLog.record(
                    RelayLog.Outcome.SKIPPED,
                    labelFor(notification.packageName),
                    rejection,
                )
            }
            return
        }

        val extracted = NotificationMapper.extract(notification, labelFor(notification.packageName))
        if (extracted == null) {
            RelayLog.record(
                RelayLog.Outcome.SKIPPED,
                labelFor(notification.packageName),
                "No readable title or text",
            )
            return
        }

        val now = System.currentTimeMillis()
        val quietWindowMs = config.minQuietSeconds * 1000L
        val previous = lastRelayedAt[extracted.dedupeKey]
        if (previous != null && now - previous < quietWindowMs) {
            RelayLog.record(
                RelayLog.Outcome.SKIPPED,
                extracted.appLabel,
                "Repeat within ${config.minQuietSeconds}s",
            )
            return
        }

        if (!withinRateLimit(now, config.maxPerHour)) {
            RelayLog.record(
                RelayLog.Outcome.SKIPPED,
                extracted.appLabel,
                "Hourly limit of ${config.maxPerHour} reached",
            )
            return
        }

        lastRelayedAt[extracted.dedupeKey] = now
        recentSends.addLast(now)

        // Registering has to happen here, while the live notification (and its
        // reply PendingIntent) is still in hand — the queued copy is only data.
        val replyToken = if (config.replyChannelEnabled || config.bleLinkEnabled) {
            ReplyRegistry.register(notification, extracted.appLabel)
        } else {
            null
        }

        val accepted = queue.trySend(QueuedRelay(extracted, replyToken)).isSuccess
        if (!accepted) {
            RelayLog.record(RelayLog.Outcome.SKIPPED, extracted.appLabel, "Queue full")
        }
    }

    /** Returns a human-readable reason, or null when the notification should relay. */
    private fun reasonToSkip(sbn: StatusBarNotification, config: Settings.Snapshot): String? {
        if (sbn.packageName == packageName) return "Own notification"
        if (sbn.packageName !in config.relayedPackages) return "App not selected"

        val notification = sbn.notification ?: return "Empty notification"
        val flags = notification.flags

        if (!config.includeOngoing && (sbn.isOngoing || flags and Notification.FLAG_ONGOING_EVENT != 0)) {
            return "Ongoing notification"
        }
        // Group summaries duplicate the children that follow them.
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return "Group summary"
        // FLAG_LOCAL_ONLY is the app explicitly asking not to be bridged to
        // other devices. Honouring it is the whole point of the flag.
        if (config.respectLocalOnly && flags and Notification.FLAG_LOCAL_ONLY != 0) {
            return "App marked it local-only"
        }

        val ranking = rankingOf(sbn)
        if (ranking != null) {
            // Channel importance, not Notification.priority: priority is a
            // legacy field that most apps leave at DEFAULT regardless of how
            // the user actually configured the channel.
            if (ranking.importance <= NotificationManager.IMPORTANCE_MIN) {
                return "Silent channel"
            }
            // Already suppressed by Do Not Disturb on the phone, so putting it
            // on your wrist would defeat the setting.
            if (!ranking.matchesInterruptionFilter()) return "Suppressed by Do Not Disturb"
        }

        return null
    }

    private fun rankingOf(sbn: StatusBarNotification): Ranking? {
        val ranking = Ranking()
        val map = runCatching { currentRanking }.getOrNull() ?: return null
        return if (map.getRanking(sbn.key, ranking)) ranking else null
    }

    private fun withinRateLimit(now: Long, maxPerHour: Int): Boolean {
        val cutoff = now - 3_600_000L
        while (recentSends.isNotEmpty() && recentSends.first() < cutoff) {
            recentSends.removeFirst()
        }
        return recentSends.size < maxPerHour
    }

    private fun startConsumer() {
        scope.launch {
            for (queued in queue) {
                deliver(queued.item, queued.replyToken)
            }
        }
    }

    private suspend fun deliver(item: NotificationMapper.Extracted, replyToken: String?) {
        val config = settings.snapshot

        // When the watch is in Bluetooth range the direct link is strictly
        // better than mail: instant, no inbox clutter, and it carries the
        // reply affordance natively. Email is the fallback for out of range.
        if (config.bleLinkEnabled && deliverOverBle(item, replyToken)) return

        val password = settings.password()
        if (password == null) {
            RelayLog.record(RelayLog.Outcome.FAILED, item.appLabel, "No password stored")
            return
        }

        val mail = NotificationMapper.toMail(
            item = item,
            from = config.account,
            to = config.effectiveDestination,
            replyToken = replyToken,
        )
        val client = SmtpClient(
            host = ICLOUD_SMTP_HOST,
            port = ICLOUD_SMTP_PORT,
            username = config.account,
            password = password,
        )

        var lastError: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                client.send(mail)
                RelayLog.record(RelayLog.Outcome.SENT, item.appLabel, mail.subject)
                return
            } catch (e: SmtpClient.SmtpException) {
                lastError = e.message
                // A rejected login will not fix itself on retry.
                if (e.code in PERMANENT_CODES) break
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
            }
            if (attempt < MAX_ATTEMPTS) {
                delay(RETRY_BACKOFF_MS * attempt)
            }
        }

        RelayLog.record(
            RelayLog.Outcome.FAILED,
            item.appLabel,
            mail.subject,
            lastError ?: "Unknown error",
        )
    }

    /**
     * Pushes straight to a connected watch. Returns false when no watch is
     * subscribed, which is the signal to fall back to the mail relay.
     */
    private fun deliverOverBle(item: NotificationMapper.Extracted, replyToken: String?): Boolean {
        val link = BleLinkService.instance ?: return false
        val message = BleProtocol.notification(
            id = replyToken.orEmpty(),
            app = item.appLabel,
            title = item.title,
            text = item.text,
            canReply = replyToken != null,
            postedAt = item.postedAt.time,
        )
        val delivered = runCatching { link.broadcast(message) }.getOrDefault(false)
        if (delivered) {
            RelayLog.record(RelayLog.Outcome.SENT, item.appLabel, "→ watch (Bluetooth)")
        }
        return delivered
    }

    private val labelCache = HashMap<String, String>()

    private fun labelFor(packageName: String): String = labelCache.getOrPut(packageName) {
        runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA))
                .toString()
        }.getOrDefault(packageName)
    }

    companion object {
        private const val TAG = "Wristbridge"

        const val ICLOUD_SMTP_HOST = "smtp.mail.me.com"
        const val ICLOUD_SMTP_PORT = 587

        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 2_000L

        /** Auth failures and permanent rejections; retrying only wastes battery. */
        private val PERMANENT_CODES = setOf(500, 501, 503, 530, 535, 550, 553, 554)
    }
}
