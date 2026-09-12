package dev.wristbridge.relay

import java.util.Base64
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Strips anything that could terminate an SMTP command or a header line.
 *
 * A stray CR or LF inside an address would otherwise let the remainder of the
 * string be read as a fresh command, which is the classic header-injection
 * shape. Angle brackets go too, since the caller adds its own.
 */
internal fun sanitizeAddress(value: String): String =
    value.filterNot { it == '\r' || it == '\n' || it == '<' || it == '>' }.trim()

/**
 * A minimal SMTP client speaking just enough of RFC 5321 to submit mail to
 * iCloud, with no third-party mail dependency.
 *
 * The flow is the standard submission handshake: greet on a plaintext socket,
 * upgrade with STARTTLS, re-greet inside TLS, authenticate, then send one
 * message. iCloud requires a TLS upgrade before it will advertise AUTH, so the
 * plaintext leg never carries credentials.
 */
class SmtpClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
) {

    /** A failure carrying the server's own words, so the UI can show them verbatim. */
    class SmtpException(message: String, val code: Int = -1) : IOException(message)

    private class Session(val socket: Socket) {
        val reader: BufferedReader =
            BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        val output: OutputStream = socket.getOutputStream()

        fun write(line: String) {
            output.write((line + "\r\n").toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }

        /**
         * Reads one SMTP reply, folding multi-line continuations ("250-FOO")
         * into the single logical response the caller expects.
         */
        fun read(): Pair<Int, String> {
            val lines = mutableListOf<String>()
            var code = -1
            while (true) {
                val line = reader.readLine()
                    ?: throw SmtpException("Server closed the connection unexpectedly.")
                lines += line
                if (line.length < 3) throw SmtpException("Malformed SMTP reply: $line")
                code = line.substring(0, 3).toIntOrNull()
                    ?: throw SmtpException("Malformed SMTP reply: $line")
                // A hyphen in the fourth column means more lines follow.
                if (line.length == 3 || line[3] != '-') break
            }
            return code to lines.joinToString("\n")
        }

        fun expect(vararg accepted: Int): String {
            val (code, text) = read()
            if (code !in accepted) throw SmtpException(text, code)
            return text
        }

        fun command(line: String, vararg accepted: Int): String {
            write(line)
            return expect(*accepted)
        }
    }

    /**
     * Opens a session, authenticates, and returns without sending mail.
     * Used by the "Test connection" button so setup problems surface as the
     * server's actual error rather than a silently dropped notification.
     */
    fun verifyCredentials() {
        session { /* connecting and authenticating is the whole test */ }
    }

    fun send(message: OutgoingMail) {
        session { s ->
            s.command("MAIL FROM:<${sanitizeAddress(message.from)}>", 250)
            for (recipient in message.to) {
                s.command("RCPT TO:<${sanitizeAddress(recipient)}>", 250, 251)
            }
            s.command("DATA", 354)
            s.output.write(message.render().toByteArray(StandardCharsets.UTF_8))
            s.output.flush()
            s.write(".")
            s.expect(250)
            runCatching { s.command("QUIT", 221) }
        }
    }

    private fun session(block: (Session) -> Unit) {
        val plain = Socket()
        try {
            plain.soTimeout = readTimeoutMs
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)

            val greeting = Session(plain)
            greeting.expect(220)
            greeting.command("EHLO $EHLO_NAME", 250)
            greeting.command("STARTTLS", 220)

            val tls = upgradeToTls(plain)
            val s = Session(tls)
            val capabilities = s.command("EHLO $EHLO_NAME", 250)
            authenticate(s, capabilities)
            block(s)
        } finally {
            runCatching { plain.close() }
        }
    }

    private fun upgradeToTls(plain: Socket): SSLSocket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(plain, host, port, true) as SSLSocket
        tls.soTimeout = readTimeoutMs
        tls.useClientMode = true
        // Set SNI explicitly; some stacks omit it when wrapping an existing socket.
        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        tls.startHandshake()

        // createSocket() on an existing socket does not verify the hostname on
        // its own. endpointIdentificationAlgorithm above covers modern Android,
        // but verify again so a stack that ignored it cannot pass silently.
        val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
        if (!verifier.verify(host, tls.session)) {
            runCatching { tls.close() }
            throw SmtpException("TLS certificate does not match $host; refusing to send.")
        }
        return tls
    }

    private fun authenticate(s: Session, capabilities: String) {
        val mechanisms = capabilities.lineSequence()
            .mapNotNull { line -> line.substringAfter("AUTH ", "").takeIf { it.isNotBlank() } }
            .flatMap { it.split(' ') }
            .map { it.trim().uppercase(Locale.US) }
            .toSet()

        try {
            when {
                mechanisms.isEmpty() || "PLAIN" in mechanisms -> {
                    val token = b64("\u0000$username\u0000$password")
                    s.command("AUTH PLAIN $token", 235)
                }
                "LOGIN" in mechanisms -> {
                    s.command("AUTH LOGIN", 334)
                    s.command(b64(username), 334)
                    s.command(b64(password), 235)
                }
                else -> throw SmtpException(
                    "Server offers no supported login method (advertised: ${mechanisms.joinToString()})."
                )
            }
        } catch (e: SmtpException) {
            // 535 is the near-universal "bad credentials" reply. The cause here is
            // almost always a regular Apple ID password instead of an app-specific one.
            if (e.code == 535) {
                throw SmtpException(
                    "iCloud rejected the login. Make sure you used an app-specific " +
                        "password generated at account.apple.com, not your normal " +
                        "Apple ID password.\n\nServer said: ${e.message}",
                    e.code,
                )
            }
            throw e
        }
    }

    private companion object {
        /**
         * Submission servers only need a syntactically valid name here, and a
         * fixed one leaks less than the device hostname.
         */
        const val EHLO_NAME = "wristbridge.local"

        fun b64(value: String): String =
            Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }
}

/**
 * One message, already reduced to the fields iCloud needs.
 *
 * The body is base64-encoded rather than sent raw, which sidesteps both
 * dot-stuffing (RFC 5321 §4.5.2) and line-length limits without having to
 * implement quoted-printable.
 */
data class OutgoingMail(
    val from: String,
    val fromDisplayName: String,
    val to: List<String>,
    val subject: String,
    val body: String,
    val sentAt: Date = Date(),
    /**
     * Stamped into the Message-ID when the source notification can be replied
     * to. A reply from the watch carries it back in In-Reply-To, which is how
     * [ReplyRegistry] finds the notification to answer.
     */
    val replyToken: String? = null,
) {
    fun render(): String {
        val localPart = replyToken ?: java.util.UUID.randomUUID().toString()
        val headers = buildString {
            append("From: ").append(encodeDisplayName(fromDisplayName))
                .append(" <").append(sanitizeAddress(from)).append(">\r\n")
            append("To: ")
                .append(to.joinToString(", ") { "<${sanitizeAddress(it)}>" })
                .append("\r\n")
            append("Subject: ").append(encodeHeaderValue(subject)).append("\r\n")
            append("Date: ").append(rfc5322Date(sentAt)).append("\r\n")
            append("Message-ID: <").append(localPart).append("@")
                .append(ImapClient.MESSAGE_ID_DOMAIN).append(">\r\n")
            // Tags the mail so a mail-rule on the Apple side can file or flag it.
            append("X-Wristbridge: 1\r\n")
            append("MIME-Version: 1.0\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
        }
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray(StandardCharsets.UTF_8))
        return headers + "\r\n" + encoded.chunked(76).joinToString("\r\n") + "\r\n"
    }

    companion object {
        private val DATE_FORMAT: SimpleDateFormat
            get() = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
                .apply { timeZone = TimeZone.getDefault() }

        fun rfc5322Date(date: Date): String = DATE_FORMAT.format(date)

        /**
         * RFC 2047 encoded-word, so emoji and non-Latin scripts survive the
         * header. Plain-ASCII values are left readable.
         */
        fun encodeHeaderValue(raw: String): String {
            val cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim()
            if (cleaned.all { it.code in 32..126 }) return cleaned
            val encoded = Base64.getEncoder()
                .encodeToString(cleaned.toByteArray(StandardCharsets.UTF_8))
            return "=?UTF-8?B?$encoded?="
        }

        fun encodeDisplayName(raw: String): String {
            val cleaned = raw.replace('\r', ' ').replace('\n', ' ').trim()
            if (cleaned.all { it.code in 32..126 }) {
                return "\"" + cleaned.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            }
            return encodeHeaderValue(cleaned)
        }
    }
}
