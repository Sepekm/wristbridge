package dev.wristbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `a stranger's address does not match the account`() {
        // The check in ReplyPollService is set membership, so what matters is
        // that a different sender parses to a different value.
        val account = "me@icloud.com"
        val stranger = ImapClient.addressOf("Someone Else <attacker@example.com>")
        assert(stranger != account) { "stranger compared equal to the account" }
    }
}
