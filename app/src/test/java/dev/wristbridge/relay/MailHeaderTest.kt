package dev.wristbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Date

/**
 * Header encoding, which is where a notification's text actually becomes the
 * thing shown on the watch face. A subject that a reader cannot parse is not a
 * cosmetic problem here: the subject *is* the message.
 */
class MailHeaderTest {

    private val encodedWord = Regex("""=\?UTF-8\?B\?([A-Za-z0-9+/=]+)\?=""")

    /** Decodes a header value the way a mail client would. */
    private fun decodeHeader(value: String): String {
        if (!value.contains("=?")) return value
        return encodedWord.findAll(value)
            .joinToString("") {
                String(Base64.getDecoder().decode(it.groupValues[1]), Charsets.UTF_8)
            }
    }

    @Test
    fun `plain ascii is left readable`() {
        assertEquals("See you at six", OutgoingMail.encodeHeaderValue("See you at six"))
    }

    @Test
    fun `an emoji subject round-trips`() {
        val subject = "Hey, are you coming to dinner? Running ten minutes late 👋"
        val encoded = OutgoingMail.encodeHeaderValue(subject)
        assertEquals(subject, decodeHeader(encoded))
    }

    @Test
    fun `no encoded-word exceeds the RFC 2047 limit of 75 characters`() {
        val subject = "Hey, are you coming to dinner tonight? Running about ten minutes late 👋"
        val encoded = OutgoingMail.encodeHeaderValue(subject)
        val words = encodedWord.findAll(encoded).map { it.value }.toList()
        assertTrue("expected the value to be split", words.size > 1)
        for (word in words) {
            assertTrue("encoded-word is ${word.length} chars: $word", word.length <= 75)
        }
    }

    @Test
    fun `a long non-latin subject round-trips intact`() {
        val subject = "дякую за все, побачимось завтра вранці коло восьмої, не запізнюйся"
        assertEquals(subject, decodeHeader(OutgoingMail.encodeHeaderValue(subject)))
    }

    @Test
    fun `a subject of nothing but emoji round-trips`() {
        val subject = "👋👍🎉✅🚀💡🔥🌍🎯📌"
        assertEquals(subject, decodeHeader(OutgoingMail.encodeHeaderValue(subject)))
    }

    @Test
    fun `a four-byte character is never split across two encoded-words`() {
        // Each word must decode on its own, so a surrogate pair may not straddle
        // a boundary. Sweeping lengths walks a boundary through the string.
        for (count in 1..40) {
            val subject = "x".repeat(count) + "👋".repeat(20)
            assertEquals("length $count", subject, decodeHeader(OutgoingMail.encodeHeaderValue(subject)))
        }
    }

    @Test
    fun `folding uses CRLF followed by whitespace`() {
        val encoded = OutgoingMail.encodeHeaderValue("é".repeat(120))
        if (encoded.contains("=?") && encoded.count { it == '\n' } > 0) {
            assertTrue("fold must be CRLF + space", encoded.contains("\r\n "))
            assertTrue("no bare LF allowed", !encoded.contains("\n") || encoded.contains("\r\n"))
        }
    }

    @Test
    fun `carriage returns in a subject cannot inject a header`() {
        // "Bcc:" surviving as literal text is fine and expected; it is only
        // dangerous preceded by a line break, which would start a new header.
        // The invariant is therefore that every CRLF is a legal fold, meaning
        // it is followed by whitespace, and that no bare CR or LF exists.
        val hostile = "Normal subject\r\nBcc: attacker@example.com"
        val encoded = OutgoingMail.encodeHeaderValue(hostile)
        assertLegalHeaderValue(encoded)
        assertTrue("text should be preserved", encoded.contains("Bcc: attacker@example.com"))
    }

    @Test
    fun `a line break hidden inside non-ascii text cannot inject a header`() {
        // Takes the encoded path rather than the plain-ascii shortcut.
        val hostile = "Résumé\r\nBcc: attacker@example.com 👋"
        assertLegalHeaderValue(OutgoingMail.encodeHeaderValue(hostile))
    }

    @Test
    fun `a line break in a display name cannot inject a header`() {
        assertLegalHeaderValue(OutgoingMail.encodeDisplayName("Alice\r\nBcc: x@y.com"))
        assertLegalHeaderValue(OutgoingMail.encodeDisplayName("Ǻlice\r\nBcc: x@y.com"))
    }

    /** Every CR must be part of a CRLF that begins a fold; no bare LF at all. */
    private fun assertLegalHeaderValue(value: String) {
        var i = 0
        while (i < value.length) {
            when (value[i]) {
                '\r' -> {
                    assertTrue("bare CR in: $value", i + 2 < value.length)
                    assertTrue("CR not followed by LF in: $value", value[i + 1] == '\n')
                    assertTrue(
                        "fold not followed by whitespace in: $value",
                        value[i + 2] == ' ' || value[i + 2] == '\t',
                    )
                    i += 3
                }
                '\n' -> throw AssertionError("bare LF in: $value")
                else -> i += 1
            }
        }
    }

    @Test
    fun `display name with a quote is escaped`() {
        val encoded = OutgoingMail.encodeDisplayName("""Sam "Sammy" O'Neill""")
        assertTrue("must be quoted", encoded.startsWith("\"") && encoded.endsWith("\""))
        assertTrue("inner quote must be escaped", encoded.contains("\\\""))
    }

    // ---- Whole-message shape ----------------------------------------------

    private fun sampleMail(subject: String = "hello", body: String = "world") = OutgoingMail(
        from = "me@icloud.com",
        fromDisplayName = "Signal · Alice",
        to = listOf("me@icloud.com"),
        subject = subject,
        body = body,
        sentAt = Date(0),
        replyToken = "abc123",
    )

    @Test
    fun `rendered message terminates so the SMTP dot is unambiguous`() {
        // The transport appends ".\r\n"; without a trailing CRLF here the
        // terminator would land on the same line as the last body octet.
        assertTrue(sampleMail().render().endsWith("\r\n"))
    }

    @Test
    fun `headers are separated from the body by a blank line`() {
        assertTrue(sampleMail().render().contains("\r\n\r\n"))
    }

    @Test
    fun `no body line can be mistaken for the end of data`() {
        // Base64 has no "." in its alphabet, which is why dot-stuffing is not
        // implemented. This pins that assumption.
        val rendered = sampleMail(body = "\n.\n..\n. end").render()
        val body = rendered.substringAfter("\r\n\r\n")
        for (line in body.split("\r\n")) {
            assertTrue("body line starts with a dot: $line", !line.startsWith("."))
        }
    }

    @Test
    fun `the reply token becomes the message id so replies can be matched`() {
        assertTrue(sampleMail().render().contains("Message-ID: <abc123@wristbridge.local>"))
    }

    @Test
    fun `an empty body still renders a valid message`() {
        val rendered = sampleMail(body = "").render()
        assertTrue(rendered.contains("\r\n\r\n"))
        assertTrue(rendered.endsWith("\r\n"))
    }

    @Test
    fun `base64 body lines stay within the line-length limit`() {
        val rendered = sampleMail(body = "y".repeat(5000)).render()
        for (line in rendered.split("\r\n")) {
            assertTrue("line of ${line.length} exceeds 998", line.length <= 998)
        }
    }
}
