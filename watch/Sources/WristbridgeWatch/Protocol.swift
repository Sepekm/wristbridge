import Foundation
import CoreBluetooth

/// The wire format shared with the Android app.
///
/// Keep in step with `app/src/main/java/dev/wristbridge/ble/BleProtocol.kt` —
/// these are two implementations of one contract.
enum WireProtocol {

    /// Advertised by the phone. The watch scans for exactly this.
    static let serviceUUID = CBUUID(string: "7F3E9A00-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    /// Watch → phone. Health samples, replies, handshake.
    static let rxUUID = CBUUID(string: "7F3E9A01-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    /// Phone → watch, by notification.
    static let txUUID = CBUUID(string: "7F3E9A02-4C21-4B8E-9D6A-1E2F3A4B5C6D")

    static let version = 1

    /// Set on the final chunk of a message; earlier chunks carry 0.
    static let flagFinal: UInt8 = 0x01

    // MARK: - Chunking

    /// Splits an encoded message into writes that fit the negotiated MTU.
    static func chunk(_ message: Data, payloadSize: Int) -> [Data] {
        let usable = max(payloadSize - 1, 1)
        guard !message.isEmpty else { return [Data([flagFinal])] }

        var chunks: [Data] = []
        var offset = 0
        while offset < message.count {
            let end = min(offset + usable, message.count)
            let isLast = end == message.count
            var piece = Data([isLast ? flagFinal : 0])
            piece.append(message[offset..<end])
            chunks.append(piece)
            offset = end
        }
        return chunks
    }

    /// Reassembles inbound chunks into whole messages.
    final class Reassembler {
        private var buffer = Data()
        private let limit: Int

        init(limit: Int = 64 * 1024) {
            self.limit = limit
        }

        func accept(_ chunk: Data) -> Data? {
            guard let flags = chunk.first else { return nil }
            buffer.append(chunk.dropFirst())
            // A peer that never sets the final flag would otherwise grow this
            // without bound.
            if buffer.count > limit {
                buffer.removeAll()
                return nil
            }
            guard flags & flagFinal != 0 else { return nil }
            let complete = buffer
            buffer.removeAll()
            return complete
        }
    }

    // MARK: - Outbound

    static func hello(watchName: String, token: String?) -> Data {
        var body: [String: Any] = ["t": "hello", "v": version, "name": watchName]
        if let token { body["token"] = token }
        return encode(body)
    }

    static func health(samples: [HealthSample]) -> Data {
        let encoded = samples.map { sample -> [String: Any] in
            [
                "k": sample.kind,
                "v": sample.value,
                "u": sample.unit,
                "s": Int(sample.start.timeIntervalSince1970 * 1000),
                "e": Int(sample.end.timeIntervalSince1970 * 1000),
            ]
        }
        return encode(["t": "health", "samples": encoded])
    }

    static func reply(notificationID: String, text: String) -> Data {
        encode(["t": "reply", "id": notificationID, "text": text])
    }

    private static func encode(_ body: [String: Any]) -> Data {
        (try? JSONSerialization.data(withJSONObject: body)) ?? Data()
    }

    // MARK: - Inbound

    enum Inbound {
        case welcome(name: String, token: String)
        case notification(BridgedNotification)
        case ack(id: String)
        case unknown
    }

    static func decode(_ data: Data) -> Inbound {
        guard
            let object = try? JSONSerialization.jsonObject(with: data),
            let body = object as? [String: Any],
            let type = body["t"] as? String
        else { return .unknown }

        switch type {
        case "welcome":
            return .welcome(
                name: body["name"] as? String ?? "Android",
                token: body["token"] as? String ?? ""
            )

        case "notify":
            let millis = body["at"] as? Double ?? 0
            return .notification(
                BridgedNotification(
                    id: body["id"] as? String ?? UUID().uuidString,
                    app: body["app"] as? String ?? "",
                    title: body["title"] as? String ?? "",
                    text: body["text"] as? String ?? "",
                    canReply: body["canReply"] as? Bool ?? false,
                    postedAt: Date(timeIntervalSince1970: millis / 1000)
                )
            )

        case "ack":
            return .ack(id: body["id"] as? String ?? "")

        default:
            return .unknown
        }
    }
}

/// One notification pushed from the phone.
struct BridgedNotification: Identifiable, Equatable {
    let id: String
    let app: String
    let title: String
    let text: String
    let canReply: Bool
    let postedAt: Date
}

/// One health reading on its way to the phone.
struct HealthSample {
    let kind: String
    let value: Double
    let unit: String
    let start: Date
    let end: Date
}
