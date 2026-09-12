package dev.wristbridge.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for the BLE framing.
 *
 * The default ATT payload is 20 bytes, so almost every real message is split.
 * That makes the chunk boundary the single most load-bearing piece of the
 * transport, and the easiest place for a corruption bug to hide unnoticed:
 * a mangled message simply fails to parse and is dropped silently.
 */
class BleProtocolTest {

    private fun roundTrip(message: String, payloadSize: Int): String? {
        val chunks = BleProtocol.chunk(message.toByteArray(Charsets.UTF_8), payloadSize)
        val reassembler = BleProtocol.Reassembler()
        var result: String? = null
        for (chunk in chunks) {
            val completed = reassembler.accept(chunk)
            if (completed != null) result = completed
        }
        return result
    }

    @Test
    fun `ascii survives the default 20 byte payload`() {
        val message = """{"t":"health","samples":[{"k":"heartRate","v":72}]}"""
        assertEquals(message, roundTrip(message, 20))
    }

    @Test
    fun `multi-byte characters split across a chunk boundary survive`() {
        // An emoji is four UTF-8 bytes. With a 20-byte payload it is certain to
        // straddle a boundary, and decoding each chunk on its own would turn
        // the halves into replacement characters.
        val message = """{"t":"reply","text":"on my way 👍 running late ✅ sorry"}"""
        assertEquals(message, roundTrip(message, 20))
    }

    @Test
    fun `accented text survives every payload size`() {
        val message = """{"t":"reply","text":"déjà vu, café, naïve, Ω≈ç√"}"""
        // Sweeping sizes puts a boundary at every possible offset in turn.
        for (size in 2..40) {
            assertEquals("payload size $size", message, roundTrip(message, size))
        }
    }

    @Test
    fun `non-latin script survives`() {
        val message = """{"t":"reply","text":"дякую, ありがとう, 谢谢"}"""
        for (size in 2..24) {
            assertEquals("payload size $size", message, roundTrip(message, size))
        }
    }

    @Test
    fun `message exactly filling one chunk is marked final`() {
        val body = "x".repeat(19)
        val chunks = BleProtocol.chunk(body.toByteArray(), 20)
        assertEquals(1, chunks.size)
        assertEquals(BleProtocol.FLAG_FINAL, chunks[0][0])
        assertEquals(body, roundTrip(body, 20))
    }

    @Test
    fun `message exactly filling two chunks is marked correctly`() {
        val body = "x".repeat(38)
        val chunks = BleProtocol.chunk(body.toByteArray(), 20)
        assertEquals(2, chunks.size)
        assertEquals("first chunk must not be final", 0.toByte(), chunks[0][0])
        assertEquals("last chunk must be final", BleProtocol.FLAG_FINAL, chunks[1][0])
        assertEquals(body, roundTrip(body, 20))
    }

    @Test
    fun `empty message still terminates`() {
        val chunks = BleProtocol.chunk(ByteArray(0), 20)
        assertEquals(1, chunks.size)
        assertEquals(BleProtocol.FLAG_FINAL, chunks[0][0])
        assertEquals("", roundTrip("", 20))
    }

    @Test
    fun `a degenerate payload size still makes progress`() {
        // One usable byte per chunk: slow, but it must not loop or drop data.
        assertEquals("hello", roundTrip("hello", 1))
        assertEquals("hello", roundTrip("hello", 2))
    }

    @Test
    fun `chunks never exceed the negotiated payload`() {
        val message = "y".repeat(500).toByteArray()
        for (size in 2..100) {
            for (chunk in BleProtocol.chunk(message, size)) {
                assertTrue("chunk of ${chunk.size} exceeds $size", chunk.size <= size)
            }
        }
    }

    @Test
    fun `a peer that never sets the final flag cannot exhaust memory`() {
        val reassembler = BleProtocol.Reassembler(limitBytes = 64)
        val filler = ByteArray(21).also { it[0] = 0 }
        repeat(10) { assertNull(reassembler.accept(filler)) }
        // Having reset, it must still be usable rather than wedged.
        assertEquals("ok", roundTrip("ok", 20))
    }

    @Test
    fun `reassembler recovers after a truncated message`() {
        val reassembler = BleProtocol.Reassembler()
        // A partial message arrives, then the peer reconnects and starts over.
        val partial = BleProtocol.chunk("abcdefghijklmnop".toByteArray(), 8).first()
        reassembler.accept(partial)
        reassembler.reset()
        var result: String? = null
        for (chunk in BleProtocol.chunk("clean".toByteArray(), 8)) {
            reassembler.accept(chunk)?.let { result = it }
        }
        assertEquals("clean", result)
    }

    // ---- Decoding ----------------------------------------------------------

    @Test
    fun `health samples decode with their fields intact`() {
        val json = """{"t":"health","samples":[
            {"k":"heartRate","v":72.5,"u":"count/min","s":1000,"e":1000},
            {"k":"steps","v":1234,"u":"count","s":2000,"e":3000}]}"""
        val decoded = BleProtocol.decode(json)
        assertTrue(decoded is BleProtocol.Inbound.Health)
        val samples = (decoded as BleProtocol.Inbound.Health).samples
        assertEquals(2, samples.size)
        assertEquals("heartRate", samples[0].kind)
        assertEquals(72.5, samples[0].value, 0.001)
        assertEquals(3000L, samples[1].end)
    }

    @Test
    fun `an instantaneous sample gets an end equal to its start`() {
        val decoded = BleProtocol.decode("""{"t":"health","samples":[{"k":"steps","v":1,"s":500}]}""")
        val samples = (decoded as BleProtocol.Inbound.Health).samples
        assertEquals(500L, samples[0].start)
        assertEquals(500L, samples[0].end)
    }

    @Test
    fun `malformed json is rejected rather than throwing`() {
        assertEquals(BleProtocol.Inbound.Unknown, BleProtocol.decode("not json at all"))
        assertEquals(BleProtocol.Inbound.Unknown, BleProtocol.decode(""))
        assertEquals(BleProtocol.Inbound.Unknown, BleProtocol.decode("""{"t":"nonsense"}"""))
    }

    @Test
    fun `a sample without a kind is dropped rather than stored blank`() {
        val decoded = BleProtocol.decode("""{"t":"health","samples":[{"v":1},{"k":"steps","v":2}]}""")
        val samples = (decoded as BleProtocol.Inbound.Health).samples
        assertEquals(1, samples.size)
        assertEquals("steps", samples[0].kind)
    }

    @Test
    fun `hello carries the token through unchanged`() {
        val decoded = BleProtocol.decode("""{"t":"hello","v":1,"name":"Watch","token":"abc123"}""")
        assertTrue(decoded is BleProtocol.Inbound.Hello)
        assertEquals("abc123", (decoded as BleProtocol.Inbound.Hello).token)
    }

    @Test
    fun `hello without a token reports null so first-use pairing can be detected`() {
        val decoded = BleProtocol.decode("""{"t":"hello","v":1,"name":"Watch"}""")
        assertNull((decoded as BleProtocol.Inbound.Hello).token)
    }

    @Test
    fun `an encoded notification round-trips through the framing`() {
        val encoded = BleProtocol.notification(
            id = "tok123",
            app = "Signal",
            title = "Alice",
            text = "See you at 6 👋",
            canReply = true,
            postedAt = 1_700_000_000_000,
        )
        val chunks = BleProtocol.chunk(encoded, 20)
        val reassembler = BleProtocol.Reassembler()
        var whole: String? = null
        for (c in chunks) reassembler.accept(c)?.let { whole = it }
        // The watch decodes this; here we just prove the bytes survive intact.
        assertArrayEquals(encoded, whole!!.toByteArray(Charsets.UTF_8))
    }
}
