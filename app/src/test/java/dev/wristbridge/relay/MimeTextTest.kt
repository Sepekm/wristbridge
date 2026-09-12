package dev.wristbridge.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply path cannot be exercised against a live mailbox from a test, so
 * these pin the parsing against the shapes iCloud actually returns. The
 * fixtures are real IMAP FETCH framing, including the literal byte-count that
 * precedes the payload.
 */
class MimeTextTest {

    @Test
    fun `extracts quoted-printable reply from multipart body`() {
        // What an Apple Watch reply looks like coming back: multipart with no
        // top-level Content-Type (BODY[TEXT] omits it), quoted-printable text,
        // then the quoted original.
        val response = """
            * 5 FETCH (UID 1234 BODY[TEXT] {412}
            --Apple-Mail-9D2F1A
            Content-Transfer-Encoding: quoted-printable
            Content-Type: text/plain;
            	charset=utf-8

            On my way=E2=80=A6 ten minutes.

            > On 12 Sep 2026, at 14:03, Wristbridge wrote:
            > Are you coming?

            --Apple-Mail-9D2F1A--
            )
            a0004 OK FETCH completed
        """.trimIndent()

        assertEquals("On my way\u2026 ten minutes.", MimeText.extractPlainText(response))
    }

    @Test
    fun `extracts plain single-part reply and strips the attribution line`() {
        val response = """
            * 6 FETCH (UID 1240 BODY[TEXT] {180}
            Yes, that works for me.

            On 12 Sep 2026, at 09:15, Signal · Alice wrote:

            Shall we move it to four?
            )
            a0005 OK FETCH completed
        """.trimIndent()

        assertEquals("Yes, that works for me.", MimeText.extractPlainText(response))
    }

    @Test
    fun `decodes base64 part`() {
        val encoded = java.util.Base64.getEncoder()
            .encodeToString("Sounds good 👍".toByteArray(Charsets.UTF_8))
        val response = """
            * 7 FETCH (UID 1250 BODY[TEXT] {200}
            --b1
            Content-Type: text/plain; charset=utf-8
            Content-Transfer-Encoding: base64

            $encoded

            --b1--
            )
            a0006 OK FETCH completed
        """.trimIndent()

        assertEquals("Sounds good 👍", MimeText.extractPlainText(response))
    }

    @Test
    fun `prefers the plain text part over html`() {
        val response = """
            * 8 FETCH (UID 1260 BODY[TEXT] {320}
            --mixed-42
            Content-Type: text/html; charset=utf-8

            <html><body><p>Not this one</p></body></html>
            --mixed-42
            Content-Type: text/plain; charset=utf-8

            This one.
            --mixed-42--
            )
            a0007 OK FETCH completed
        """.trimIndent()

        assertEquals("This one.", MimeText.extractPlainText(response))
    }

    @Test
    fun `honours an explicitly declared boundary`() {
        val response = """
            * 9 FETCH (UID 1270 BODY[] {300}
            Content-Type: multipart/alternative; boundary="XYZ-99"

            --XYZ-99
            Content-Type: text/plain

            Declared boundary wins.
            --XYZ-99--
            )
            a0008 OK FETCH completed
        """.trimIndent()

        assertEquals("Declared boundary wins.", MimeText.extractPlainText(response))
    }

    @Test
    fun `strips our own relay footer so it is never echoed back`() {
        val text = """
            Got it.

            via Wristbridge: Signal at 14:03 (org.thoughtcrime.securesms)
        """.trimIndent()

        assertEquals("Got it.", MimeText.stripQuotedReply(text))
    }

    @Test
    fun `keeps a reply that reads like an attribution line`() {
        val text = """
            Sent from work at 17:30, see you then.

            via Wristbridge: Signal at 14:03 (org.thoughtcrime.securesms)
        """.trimIndent()

        assertEquals("Sent from work at 17:30, see you then.", MimeText.stripQuotedReply(text))
    }

    @Test
    fun `strips Original Message style quoting`() {
        val text = """
            Confirmed.

            -----Original Message-----
            From: Wristbridge
        """.trimIndent()

        assertEquals("Confirmed.", MimeText.stripQuotedReply(text))
    }

    @Test
    fun `keeps multi-line replies intact`() {
        val text = """
            First line.
            Second line.

            > quoted
        """.trimIndent()

        assertEquals("First line.\nSecond line.", MimeText.stripQuotedReply(text))
    }

    @Test
    fun `empty reply does not crash`() {
        val response = "* 1 FETCH (UID 1 BODY[TEXT] {0}\n\n)\na0001 OK FETCH completed"
        assertTrue(MimeText.extractPlainText(response).isEmpty())
    }

    @Test
    fun `quoted-printable soft line breaks are joined`() {
        val response = """
            * 2 FETCH (UID 2 BODY[TEXT] {150}
            Content-Transfer-Encoding: quoted-printable

            This is a very long line that the mail client has wrapped with a soft=
             break in the middle.
            )
            a0002 OK FETCH completed
        """.trimIndent()

        val result = MimeText.extractPlainText(response)
        assertTrue("soft break should be removed: $result", result.contains("soft break"))
    }
}
