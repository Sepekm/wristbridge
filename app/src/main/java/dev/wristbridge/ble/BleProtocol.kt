package dev.wristbridge.ble

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The wire format shared by the Android app and the watchOS app.
 *
 * Messages are UTF-8 JSON. BLE caps a single write at the negotiated MTU
 * (23 bytes until the peer asks for more), so every message is split into
 * chunks carrying a one-byte header, and reassembled on the far side.
 *
 * Keep this file and `watch/Sources/WristbridgeWatch/Protocol.swift` in step:
 * they are two implementations of one contract.
 */
object BleProtocol {

    /** Advertised by the phone. The watch scans for exactly this. */
    val SERVICE_UUID: UUID = UUID.fromString("7F3E9A00-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    /** Watch → phone. Health samples, replies, handshake. */
    val RX_UUID: UUID = UUID.fromString("7F3E9A01-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    /** Phone → watch, by notification. Notifications and acknowledgements. */
    val TX_UUID: UUID = UUID.fromString("7F3E9A02-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    /** Standard Client Characteristic Configuration descriptor, for subscribing. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION = 1

    /** Set on the last chunk of a message; every earlier chunk has flags 0. */
    const val FLAG_FINAL: Byte = 0x01

    // ---- Message types -----------------------------------------------------

    const val T_HELLO = "hello"
    const val T_WELCOME = "welcome"
    const val T_HEALTH = "health"
    const val T_NOTIFY = "notify"
    const val T_REPLY = "reply"
    const val T_ACK = "ack"

    // ---- Chunking ----------------------------------------------------------

    /**
     * Splits an encoded message into BLE-sized chunks. One header byte is
     * reserved, and three more are ATT overhead the caller has already
     * subtracted from [payloadSize].
     */
    fun chunk(message: ByteArray, payloadSize: Int): List<ByteArray> {
        val usable = (payloadSize - 1).coerceAtLeast(1)
        if (message.isEmpty()) return listOf(byteArrayOf(FLAG_FINAL))

        return message.toList().chunked(usable).mapIndexed { index, part ->
            val isLast = (index + 1) * usable >= message.size
            ByteArray(part.size + 1).also { out ->
                out[0] = if (isLast) FLAG_FINAL else 0
                part.forEachIndexed { i, b -> out[i + 1] = b }
            }
        }
    }

    /**
     * Reassembles chunks into whole messages. One instance per connected peer,
     * since a shared buffer would interleave two peers' fragments.
     */
    class Reassembler(private val limitBytes: Int = 64 * 1024) {
        /**
         * Bytes, deliberately, not a StringBuilder. A UTF-8 character can be up
         * to four bytes and the chunk boundary falls wherever the MTU puts it,
         * so decoding each chunk on its own turns any character unlucky enough
         * to straddle a boundary into replacement characters. Accumulate the
         * bytes and decode once, when the whole message is present.
         */
        private val buffer = java.io.ByteArrayOutputStream()

        /** Returns the complete message when the final chunk arrives, else null. */
        fun accept(chunk: ByteArray): String? {
            if (chunk.isEmpty()) return null
            if (buffer.size() + chunk.size - 1 > limitBytes) {
                // A peer that never sets the final flag would otherwise grow
                // this without bound.
                reset()
                return null
            }
            buffer.write(chunk, 1, chunk.size - 1)
            if (chunk[0].toInt() and FLAG_FINAL.toInt() != 0) {
                val complete = buffer.toString(Charsets.UTF_8.name())
                reset()
                return complete
            }
            return null
        }

        fun reset() {
            buffer.reset()
        }
    }

    // ---- Encoding ----------------------------------------------------------

    fun welcome(deviceName: String, token: String): ByteArray = JSONObject()
        .put("t", T_WELCOME)
        .put("v", PROTOCOL_VERSION)
        .put("name", deviceName)
        .put("token", token)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun notification(
        id: String,
        app: String,
        title: String,
        text: String,
        canReply: Boolean,
        postedAt: Long,
    ): ByteArray = JSONObject()
        .put("t", T_NOTIFY)
        .put("id", id)
        .put("app", app)
        .put("title", title)
        .put("text", text)
        .put("canReply", canReply)
        .put("at", postedAt)
        .toString()
        .toByteArray(Charsets.UTF_8)

    fun ack(id: String): ByteArray = JSONObject()
        .put("t", T_ACK)
        .put("id", id)
        .toString()
        .toByteArray(Charsets.UTF_8)

    // ---- Decoding ----------------------------------------------------------

    sealed interface Inbound {
        data class Hello(val watchName: String, val version: Int, val token: String?) : Inbound
        data class Health(val samples: List<HealthSample>) : Inbound
        data class Reply(val notificationId: String, val text: String) : Inbound
        data object Unknown : Inbound
    }

    data class HealthSample(
        val kind: String,
        val value: Double,
        val unit: String,
        val start: Long,
        val end: Long,
    )

    fun decode(json: String): Inbound = runCatching {
        val obj = JSONObject(json)
        when (obj.optString("t")) {
            T_HELLO -> Inbound.Hello(
                watchName = obj.optString("name", "Apple Watch"),
                version = obj.optInt("v", 1),
                token = obj.optString("token").takeIf { it.isNotBlank() },
            )

            T_HEALTH -> Inbound.Health(readSamples(obj.optJSONArray("samples")))

            T_REPLY -> Inbound.Reply(
                notificationId = obj.optString("id"),
                text = obj.optString("text"),
            )

            else -> Inbound.Unknown
        }
    }.getOrDefault(Inbound.Unknown)

    private fun readSamples(array: JSONArray?): List<HealthSample> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = item.optString("k").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = item.optLong("s", 0L)
            HealthSample(
                kind = kind,
                value = item.optDouble("v", 0.0),
                unit = item.optString("u"),
                start = start,
                // Instantaneous samples carry no end time; treat them as zero-length.
                end = item.optLong("e", start),
            )
        }
    }
}
