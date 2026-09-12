package dev.wristbridge.relay

import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Base64

/**
 * Pulls the human-written part out of a mail body.
 *
 * A reply composed on an Apple Watch comes back as multipart/alternative with
 * quoted-printable text, followed by the quoted original. Only the top, what
 * the person actually dictated or scribbled, is wanted.
 */
object MimeText {

    /**
     * Takes a raw IMAP FETCH response and returns the decoded, de-quoted reply.
     */
    /**
     * @param messageHeaders the message's own headers, fetched separately.
     *        IMAP's BODY[TEXT] excludes them, so a single-part body would
     *        otherwise give no way to tell how it was encoded and
     *        quoted-printable escapes would survive into the reply verbatim.
     */
    fun extractPlainText(fetchResponse: String, messageHeaders: String = ""): String {
        val raw = literalPayload(fetchResponse)
        val part = preferredTextPart(raw, messageHeaders)
        return stripQuotedReply(part).trim()
    }

    /**
     * The body arrives as an IMAP literal: a line ending in "{1234}" followed
     * by that many bytes. Everything after that line, minus the response
     * framing, is the payload.
     */
    private fun literalPayload(response: String): String {
        val lines = response.lines()
        val start = lines.indexOfFirst { LITERAL_SUFFIX.containsMatchIn(it) }
        if (start == -1) return response
        val remainder = lines.drop(start + 1)
        // Trailing ")" and the tagged completion line are protocol, not content.
        return remainder
            .dropLastWhile { it.isBlank() || it == ")" || TAGGED_OK.containsMatchIn(it) }
            .joinToString("\n")
    }

    /**
     * Finds the text/plain section of a multipart body and decodes it. Falls
     * back to treating the whole payload as text when there are no boundaries,
     * which is what a plain-text-only reply looks like.
     */
    private fun preferredTextPart(raw: String, messageHeaders: String): String {
        val boundary = detectBoundary(raw) ?: detectBoundary(messageHeaders)
        if (boundary == null) {
            // No parts, so the message's own headers describe the whole body.
            // Falling back to the payload keeps older callers working.
            val encoding = CONTENT_TRANSFER_ENCODING.find(messageHeaders)
                ?.groupValues?.get(1)?.trim()?.lowercase(Locale.ROOT)
                ?: guessEncoding(raw)
            return decode(raw, encoding)
        }

        val sections = raw.split("--$boundary")
        val plain = sections.firstOrNull { section ->
            CONTENT_TYPE_PLAIN.containsMatchIn(section)
        } ?: sections.firstOrNull { it.isNotBlank() } ?: return ""

        // A MIME part is headers, a blank line, then content.
        val separator = plain.indexOf("\n\n").takeIf { it >= 0 }
            ?: plain.indexOf("\r\n\r\n").takeIf { it >= 0 }
            ?: return ""
        val headers = plain.substring(0, separator)
        val content = plain.substring(separator).trimStart('\r', '\n')
        return decode(content, guessEncoding(headers))
    }

    /**
     * IMAP's BODY[TEXT] deliberately omits the top-level headers, so the
     * `boundary=` parameter that would name the separator is not in what we
     * fetched. When it is absent, the boundary is inferred from the delimiter
     * lines themselves: the token that appears at least twice (once opening a
     * part, once closing it) is the real one.
     */
    private fun detectBoundary(raw: String): String? {
        BOUNDARY_LINE.find(raw)?.groupValues?.get(1)?.let { return it }

        val counts = HashMap<String, Int>()
        for (line in raw.lineSequence()) {
            val match = DELIMITER.matchEntire(line.trim()) ?: continue
            val token = match.groupValues[1].removeSuffix("--")
            if (token.isNotEmpty()) counts[token] = (counts[token] ?: 0) + 1
        }
        return counts.entries.filter { it.value >= 2 }.maxByOrNull { it.value }?.key
    }

    private fun guessEncoding(headers: String): String =
        // Locale.ROOT: a Turkish device folds the I in "QUOTED-PRINTABLE" to a
        // dotless character, the comparison below then fails, and the reply
        // arrives as undecoded mojibake.
        CONTENT_TRANSFER_ENCODING.find(headers)?.groupValues?.get(1)?.trim()
            ?.lowercase(Locale.ROOT)
            ?: "7bit"

    private fun decode(content: String, encoding: String): String = when (encoding) {
        // The MIME decoder ignores the line breaks base64 bodies are wrapped at.
        "base64" -> runCatching {
            String(Base64.getMimeDecoder().decode(content), StandardCharsets.UTF_8)
        }.getOrDefault(content)

        "quoted-printable" -> decodeQuotedPrintable(content)
        else -> content
    }

    /** RFC 2045 §6.7: "=XX" hex escapes, and "=" at end of line as a soft break. */
    private fun decodeQuotedPrintable(input: String): String {
        val out = StringBuilder()
        val bytes = ArrayList<Byte>(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c == '=' && i + 1 < input.length && input[i + 1] == '\n' -> i += 2
                c == '=' && i + 2 < input.length && input[i + 1] == '\r' && input[i + 2] == '\n' -> i += 3
                c == '=' && i + 2 < input.length -> {
                    val hex = input.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                    if (value != null) {
                        bytes.add(value.toByte())
                        i += 3
                    } else {
                        bytes.add(c.code.toByte())
                        i += 1
                    }
                }
                else -> {
                    // Non-ASCII should not appear literally, but pass it through
                    // as UTF-8 rather than mangling it if it does.
                    c.toString().toByteArray(StandardCharsets.UTF_8).forEach(bytes::add)
                    i += 1
                }
            }
        }
        out.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
        return out.toString()
    }

    /**
     * Cuts everything from the first sign of the quoted original onward.
     * Mail clients differ, so this matches the several shapes they use rather
     * than assuming one.
     */
    fun stripQuotedReply(text: String): String {
        val lines = text.lines()
        val cut = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.startsWith(">") ||
                ON_WROTE.containsMatchIn(trimmed) ||
                trimmed.startsWith("-----Original Message-----") ||
                trimmed.startsWith("Sent from my") ||
                // Our own footer, which otherwise gets echoed back into the
                // originating app as part of the reply. Matching a fixed
                // literal avoids clipping a genuine line of someone's text.
                trimmed.startsWith("via Wristbridge")
        }
        val kept = if (cut >= 0) lines.take(cut) else lines
        return kept.joinToString("\n").trim()
    }

    private val LITERAL_SUFFIX = Regex("""\{\d+}\s*$""")
    private val TAGGED_OK = Regex("""^a\d+ (OK|NO|BAD)""")
    private val BOUNDARY_LINE = Regex("""(?i)boundary="?([^";\r\n]+?)"?\s*$""", RegexOption.MULTILINE)
    private val DELIMITER = Regex("""^--(\S+)$""")
    private val CONTENT_TYPE_PLAIN = Regex("""(?i)Content-Type:\s*text/plain""")
    private val CONTENT_TRANSFER_ENCODING =
        Regex("""(?i)Content-Transfer-Encoding:\s*([A-Za-z0-9-]+)""")

    /** "On 12 Sep 2026, at 14:03, Alice wrote:" and its many variants. */
    private val ON_WROTE = Regex("""(?i)^On .{0,120}\bwrote:\s*$""")

}
