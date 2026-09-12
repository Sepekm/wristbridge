package dev.wristbridge.relay

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import java.util.UUID

/**
 * Remembers which Android notification each relayed mail came from, so a reply
 * written on the watch can be pushed back into the app that sent it.
 *
 * Android exposes a notification's "Reply" button as a [RemoteInput] on a
 * [PendingIntent], the same mechanism Wear OS uses. Holding onto that intent
 * lets the reply land in WhatsApp or Signal as if it were typed on the phone.
 *
 * The intent stays valid only while the notification is alive: dismiss it on
 * the phone and the reply can no longer be delivered. That is a constraint of
 * the platform, not a choice here.
 */
object ReplyRegistry {

    private const val MAX_ENTRIES = 200
    private const val EXPIRY_MS = 24 * 60 * 60 * 1000L

    /**
     * Far longer than anything anyone dictates to a watch, and far short of
     * the Binder transaction limit a reply has to cross.
     */
    private const val MAX_REPLY_CHARS = 4000

    data class Pending(
        val token: String,
        val packageName: String,
        val appLabel: String,
        val action: Notification.Action,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val entries = LinkedHashMap<String, Pending>()

    /**
     * Registers a notification if it can be replied to, returning the token to
     * stamp on the outgoing mail's Message-ID. Returns null when the
     * notification has no reply action, in which case the mail is one-way.
     */
    @Synchronized
    fun register(sbn: StatusBarNotification, appLabel: String): String? {
        val action = replyAction(sbn) ?: return null
        val token = UUID.randomUUID().toString().replace("-", "").take(24)

        prune()
        entries[token] = Pending(
            token = token,
            packageName = sbn.packageName,
            appLabel = appLabel,
            action = action,
        )
        return token
    }

    @Synchronized
    fun consume(token: String): Pending? {
        prune()
        return entries.remove(token)
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        entries.entries.removeAll { it.value.createdAt < cutoff }
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** The first action carrying free-form text input is the Reply button. */
    private fun replyAction(sbn: StatusBarNotification): Notification.Action? =
        sbn.notification?.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    /**
     * Fires the stored reply action with [text]. Returns a human-readable
     * failure reason, or null on success.
     */
    fun deliver(context: Context, pending: Pending, text: String): String? {
        val remoteInputs = pending.action.remoteInputs
            ?: return "Notification no longer accepts replies"
        val target = pending.action.actionIntent
            ?: return "Notification no longer accepts replies"

        // The reply arrives from off-device, so its length is not ours to
        // trust. Everything here crosses a Binder transaction, which has a
        // hard limit of about a megabyte shared with everything else in
        // flight; overrunning it throws from deep inside the system, on
        // whichever thread called us. No real reply approaches this.
        val bounded = if (text.length > MAX_REPLY_CHARS) text.take(MAX_REPLY_CHARS) else text

        val intent = Intent()
        val results = Bundle()
        for (input in remoteInputs) {
            results.putCharSequence(input.resultKey, bounded)
        }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)

        return try {
            target.send(context, 0, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            // Almost always means the notification was swiped away on the phone.
            "The original notification is gone, so ${pending.appLabel} would not accept it"
        } catch (e: SecurityException) {
            "${pending.appLabel} refused the reply: ${e.message}"
        } catch (e: Exception) {
            // One of these runs on a Bluetooth callback thread, where an escaping
            // exception takes the process down rather than failing one reply.
            "${pending.appLabel} could not accept the reply: ${e.javaClass.simpleName}"
        }
    }
}
