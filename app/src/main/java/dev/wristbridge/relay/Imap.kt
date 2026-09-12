package dev.wristbridge.relay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A minimal IMAP4rev1 client, enough to find replies the watch sent and read
 * their text. Written against the raw protocol for the same reason as
 * [SmtpClient]: no mail dependency, and full control of the socket.
 *
 * Reading is done over a byte stream rather than a BufferedReader because IMAP
 * literals ("{412}" followed by exactly 412 bytes) need precise byte counting
 * that a line-oriented reader cannot give.
 */
class ImapClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {

    class ImapException(message: String) : IOException(message)

    data class Message(
        val uid: Long,
        val inReplyTo: String?,
        val subject: String?,
        val from: String?,
        val bodyText: String,
    )

    inner class Session internal constructor(private val socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output: OutputStream = socket.getOutputStream()
        private var counter = 0

        private fun nextTag(): String = "a%04d".format(++counter)

        private fun readLineRaw(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) return if (out.size() == 0) null else out.toString("UTF-8")
                if (b == '\n'.code) break
                out.write(b)
                // A server that never sends a line ending would otherwise grow
                // this until the process dies.
                if (out.size() > MAX_LINE_BYTES) {
                    throw ImapException("Server sent an oversized line; giving up.")
                }
            }
            return out.toString("UTF-8").removeSuffix("\r")
        }

        private fun readExactly(count: Int): String {
            // The size comes from the server, so it is allocated only after it
            // has been judged sane. Without this a malformed or hostile literal
            // header could ask for gigabytes in a single stroke.
            if (count < 0 || count > MAX_LITERAL_BYTES) {
                throw ImapException("Server announced an implausible literal of $count bytes.")
            }
            val buffer = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(buffer, read, count - read)
                if (n == -1) throw ImapException("Connection closed mid-literal")
                read += n
            }
            return String(buffer, StandardCharsets.UTF_8)
        }

        /**
         * Reads every line of a response up to and including the tagged
         * completion, splicing literal payloads in where they appear.
         */
        private fun readUntilTagged(tag: String): String {
            val sb = StringBuilder()
            while (true) {
                val line = readLineRaw() ?: throw ImapException("Connection closed")
                sb.append(line).append("\n")

                LITERAL_SUFFIX.find(line)?.let { match ->
                    val size = match.groupValues[1].toIntOrNull() ?: 0
                    sb.append(readExactly(size)).append("\n")
                }

                // A server that never sends the tagged completion would keep
                // this growing for as long as it cared to talk.
                if (sb.length > MAX_RESPONSE_CHARS) {
                    throw ImapException("Server response too large; giving up.")
                }

                if (line.startsWith("$tag ")) {
                    if (!line.startsWith("$tag OK")) {
                        throw ImapException(line.substringAfter("$tag ").trim())
                    }
                    return sb.toString()
                }
            }
        }

        internal fun greet() {
            val line = readLineRaw() ?: throw ImapException("No greeting from server")
            if (!line.startsWith("* OK")) throw ImapException("Unexpected greeting: $line")
        }

        fun command(raw: String): String {
            val tag = nextTag()
            output.write("$tag $raw\r\n".toByteArray(StandardCharsets.UTF_8))
            output.flush()
            return readUntilTagged(tag)
        }

        internal fun login() {
            try {
                command("LOGIN ${quote(username)} ${quote(password)}")
            } catch (e: ImapException) {
                throw ImapException(
                    "iCloud rejected the IMAP login. This needs the same " +
                        "app-specific password as sending.\n\nServer said: ${e.message}"
                )
            }
        }

        /** Selects the inbox and returns its UIDVALIDITY, when the server states one. */
        fun selectInbox(): Long? {
            val response = command("SELECT INBOX")
            return UID_VALIDITY.find(response)?.groupValues?.get(1)?.toLongOrNull()
        }

        /**
         * Finds replies to mail this app sent, by matching the In-Reply-To
         * header against our own Message-ID domain. Nothing else in the
         * mailbox can match, so unrelated mail is never touched.
         */
        fun findReplies(): List<Long> {
            val response = command("UID SEARCH HEADER IN-REPLY-TO ${quote(MESSAGE_ID_DOMAIN)}")
            return response.lineSequence()
                .firstOrNull { it.startsWith("* SEARCH") }
                ?.removePrefix("* SEARCH")
                ?.trim()
                ?.split(' ')
                ?.mapNotNull { it.trim().toLongOrNull() }
                .orEmpty()
        }

        fun fetch(uid: Long): Message? {
            val headers = command(
                "UID FETCH $uid (BODY.PEEK[HEADER.FIELDS " +
                    "(IN-REPLY-TO SUBJECT FROM CONTENT-TYPE CONTENT-TRANSFER-ENCODING)])"
            )
            val body = command("UID FETCH $uid (BODY.PEEK[TEXT])")

            val inReplyTo = HEADER_IN_REPLY_TO.find(headers)?.groupValues?.get(1)?.trim()
            val subject = HEADER_SUBJECT.find(headers)?.groupValues?.get(1)?.trim()
            val from = HEADER_FROM.find(headers)?.groupValues?.get(1)?.trim()
            // The top-level headers travel separately from BODY[TEXT], so they
            // are handed over as the fallback for a body with no MIME parts of
            // its own to describe it.
            val text = MimeText.extractPlainText(body, messageHeaders = headers)

            if (inReplyTo.isNullOrBlank()) return null
            return Message(uid, inReplyTo, subject, from, text)
        }

        /** Marks a handled reply read, so the unread count does not creep up. */
        fun markSeen(uid: Long) {
            runCatching { command("UID STORE $uid +FLAGS (\\Seen)") }
        }

        internal fun logout() {
            runCatching { command("LOGOUT") }
        }
    }

    fun <T> withSession(block: (Session) -> T): T {
        val plain = Socket()
        try {
            plain.soTimeout = readTimeoutMs
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = readTimeoutMs
            tls.useClientMode = true
            tls.sslParameters = tls.sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS"
            }
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                throw ImapException("TLS certificate does not match $host; refusing to connect.")
            }

            val session = Session(tls)
            session.greet()
            session.login()
            try {
                return block(session)
            } finally {
                session.logout()
            }
        } finally {
            runCatching { plain.close() }
        }
    }

    fun verifyCredentials() {
        withSession { it.selectInbox() }
    }

    companion object {
        const val ICLOUD_IMAP_HOST = "imap.mail.me.com"
        const val ICLOUD_IMAP_PORT = 993

        /** Matches the Message-ID domain [OutgoingMail] stamps on every relay. */
        const val MESSAGE_ID_DOMAIN = "wristbridge.local"

        /**
         * Ceilings on anything the far end gets to size.
         *
         * The link is TLS-verified to Apple, so these are not expected to fire.
         * They exist so a malformed or hostile response fails loudly rather
         * than exhausting memory on the user's phone.
         */
        /** SELECT reports the mailbox's UIDVALIDITY in an untagged OK response. */
        private val UID_VALIDITY = Regex("""(?i)\[UIDVALIDITY\s+(\d+)]""")

        private const val MAX_LINE_BYTES = 64 * 1024
        private const val MAX_LITERAL_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024

        private val LITERAL_SUFFIX = Regex("""\{(\d+)}$""")
        private val HEADER_IN_REPLY_TO =
            Regex("""(?im)^In-Reply-To:\s*(.+)$""")
        private val HEADER_SUBJECT = Regex("""(?im)^Subject:\s*(.+)$""")
        private val HEADER_FROM = Regex("""(?im)^From:\s*(.+)$""")
        private val ANGLE_ADDRESS = Regex("""<([^>]+)>""")

        /**
         * Pulls the bare address out of a From header, which may be either
         * `someone@example.com` or `Their Name <someone@example.com>`.
         */
        fun addressOf(header: String?): String? {
            if (header.isNullOrBlank()) return null
            val angled = ANGLE_ADDRESS.find(header)?.groupValues?.get(1)
            // Locale.ROOT, not the device locale: in Turkish "I" folds to a
            // dotless "ı", so a default-locale fold would stop an address
            // matching itself and quietly disable the reply channel.
            return (angled ?: header).trim().trim('"').trim().lowercase(Locale.ROOT)
        }

        fun quote(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
