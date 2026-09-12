package dev.wristbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Covers the two places a hostile or malformed address could change behaviour:
 * building SMTP commands, and deciding whose replies to act on.
 */
class AddressSafetyTest {

    // ---- sanitizeAddress ---------------------------------------------------

    @Test
    fun `ordinary address passes through unchanged`() {
        assertEquals("someone@icloud.com", sanitizeAddress("someone@icloud.com"))
    }

    @Test
    fun `carriage return and newline cannot smuggle an SMTP command`() {
        // Without stripping, the server would read RCPT TO as a new command.
        val hostile = "victim@icloud.com\r\nRCPT TO:<attacker@example.com>"
        val safe = sanitizeAddress(hostile)
        assertEquals("victim@icloud.comRCPT TO:attacker@example.com", safe)
        assert(!safe.contains('\r')) { "CR survived: $safe" }
        assert(!safe.contains('\n')) { "LF survived: $safe" }
    }

    @Test
    fun `bare newline is stripped too`() {
        val safe = sanitizeAddress("a@b.com\nSubject: injected")
        assert(!safe.contains('\n')) { "LF survived: $safe" }
    }

    @Test
    fun `angle brackets are removed since the caller adds its own`() {
        assertEquals("a@b.com", sanitizeAddress("<a@b.com>"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("a@b.com", sanitizeAddress("  a@b.com  "))
    }

    // ---- ImapClient.addressOf ---------------------------------------------

    @Test
    fun `extracts address from a display-name From header`() {
        assertEquals(
            "someone@icloud.com",
            ImapClient.addressOf("Their Name <someone@icloud.com>"),
        )
    }

    @Test
    fun `handles a bare address`() {
        assertEquals("someone@icloud.com", ImapClient.addressOf("someone@icloud.com"))
    }

    @Test
    fun `comparison is case insensitive`() {
        assertEquals("someone@icloud.com", ImapClient.addressOf("SomeOne@iCloud.COM"))
    }

    @Test
    fun `quoted display name does not leak into the address`() {
        assertEquals(
            "someone@icloud.com",
            ImapClient.addressOf("\"Name, With Comma\" <someone@icloud.com>"),
        )
    }

    @Test
    fun `missing header yields null so the reply is refused`() {
        assertNull(ImapClient.addressOf(null))
        assertNull(ImapClient.addressOf("   "))
    }

    @Test
    fun `address folding is unaffected by a Turkish device locale`() {
        // Turkish folds "I" to a dotless "ı". With the device locale, an address
        // containing a capital I would stop matching itself and the reply
        // channel would silently refuse every reply on such a phone.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("ian@icloud.com", ImapClient.addressOf("IAN@ICLOUD.COM"))
            assertEquals(
                "kristina.i@icloud.com",
                ImapClient.addressOf("Kristina <Kristina.I@iCloud.com>"),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `quoted-printable is recognised under a Turkish locale`() {
        // "QUOTED-PRINTABLE" contains an I, so the same trap applies to the
        // transfer-encoding lookup; missing it leaves the reply as mojibake.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            // Realistic shape: BODY[TEXT] carries no headers at all, and the
            // encoding is only knowable from the separately fetched headers.
            val body = """
                * 2 FETCH (UID 2 BODY[TEXT] {40}
                On my way=E2=80=A6 ten minutes.
                )
                a0002 OK FETCH completed
            """.trimIndent()
            val headers = """
                * 2 FETCH (UID 2 BODY[HEADER.FIELDS (...)] {120}
                Subject: Re: Signal
                Content-Type: text/plain; charset=utf-8
                Content-Transfer-Encoding: QUOTED-PRINTABLE
                )
                a0001 OK FETCH completed
            """.trimIndent()
            assertEquals(
                "On my way… ten minutes.",
                MimeText.extractPlainText(body, messageHeaders = headers),
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a stranger's address does not match the account`() {
        // The check in ReplyPollService is set membership, so what matters is
        // that a different sender parses to a different value.
        val account = "me@icloud.com"
        val stranger = ImapClient.addressOf("Someone Else <attacker@example.com>")
        assert(stranger != account) { "stranger compared equal to the account" }
    }
}
