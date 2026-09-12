package dev.wristbridge.relay

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns an Android notification into the mail shape that reads best on a watch.
 *
 * watchOS renders a Mail push as a large sender line, then the subject, then a
 * short body preview. So the sender line carries the app and who it is from,
 * and the subject carries the message itself, which lands on the wrist looking
 * close to a native notification rather than an email.
 */
object NotificationMapper {

    data class Extracted(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val postedAt: Date,
    ) {
        /**
         * Identity for de-duplication: the same app saying the same thing.
         * Deliberately excludes the timestamp so reposts collapse.
         */
        val dedupeKey: String get() = "$packageName|$title|$text"
    }

    fun extract(sbn: StatusBarNotification, appLabel: String): Extracted? {
        val extras = sbn.notification?.extras ?: return null

        val title = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
        ).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

        // BIG_TEXT holds the expanded body where one exists and is usually the
        // fuller version of EXTRA_TEXT, so it wins when both are present.
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        ).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            ?: joinMessagingStyleLines(extras).orEmpty()

        if (title.isEmpty() && text.isEmpty()) return null

        return Extracted(
            packageName = sbn.packageName,
            appLabel = appLabel,
            title = title,
            text = text,
            postedAt = Date(sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()),
        )
    }

    /**
     * MessagingStyle notifications (most chat apps) can leave EXTRA_TEXT empty
     * and carry the conversation in a parcelable array instead.
     */
    private fun joinMessagingStyleLines(extras: android.os.Bundle): String? {
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES) ?: return null
        return lines.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    }

    fun toMail(
        item: Extracted,
        from: String,
        to: String,
        replyToken: String? = null,
    ): OutgoingMail {
        // Sender line: "Signal · Alice" reads correctly on the wrist even when
        // watchOS truncates it, because the app name comes first.
        val sender = if (item.title.isNotEmpty()) {
            "${item.appLabel} · ${item.title}"
        } else {
            item.appLabel
        }

        val subject = item.text.ifEmpty { item.title }
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
            .let { if (it.length > SUBJECT_LIMIT) it.take(SUBJECT_LIMIT - 1).trimEnd() + "…" else it }

        val body = buildString {
            if (item.title.isNotEmpty()) appendLine(item.title)
            if (item.text.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine(item.text)
            }
            if (replyToken != null) {
                appendLine()
                appendLine("Reply to this mail and your answer is sent back through ${item.appLabel}.")
            }
            appendLine()
            append(
                "via Wristbridge: ${item.appLabel} at " +
                    "${TIME_FORMAT.format(item.postedAt)} (${item.packageName})"
            )
        }

        return OutgoingMail(
            from = from,
            fromDisplayName = sender,
            to = listOf(to),
            subject = subject.ifBlank { item.appLabel },
            body = body,
            sentAt = item.postedAt,
            replyToken = replyToken,
        )
    }

    /**
     * Subjects longer than this get truncated by mail clients anyway, and a
     * watch shows far less than this.
     */
    private const val SUBJECT_LIMIT = 180

    private val TIME_FORMAT: SimpleDateFormat
        get() = SimpleDateFormat("HH:mm", Locale.getDefault())
}
