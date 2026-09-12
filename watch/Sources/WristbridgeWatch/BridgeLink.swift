import Foundation
import CoreBluetooth
import Combine
import WatchKit

/// Connects the watch to the Android phone over Bluetooth LE.
///
/// watchOS only offers the central role: `CBPeripheralManager` does not exist
/// here, so the phone advertises and the watch connects. That asymmetry is why
/// the Android app is the peripheral.
@MainActor
final class BridgeLink: NSObject, ObservableObject {

    enum State: Equatable {
        case idle
        case bluetoothOff
        case unauthorised
        case scanning
        case connecting
        case connected(phone: String)
        case failed(String)

        var isConnected: Bool {
            if case .connected = self { return true }
            return false
        }
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var notifications: [BridgedNotification] = []
    @Published private(set) var samplesSent = 0
    @Published private(set) var lastSync: Date?

    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var rxCharacteristic: CBCharacteristic?
    private let reassembler = WireProtocol.Reassembler()

    /// Issued by the phone on first connection and presented on every later
    /// one, so the phone can recognise this watch.
    private var pairingToken: String? {
        get { UserDefaults.standard.string(forKey: "wristbridge.token") }
        set { UserDefaults.standard.set(newValue, forKey: "wristbridge.token") }
    }

    /// Conservative until the peripheral reports its real limit.
    private var payloadSize = 20

    func start() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func stop() {
        if let peripheral {
            central?.cancelPeripheralConnection(peripheral)
        }
        central?.stopScan()
        central = nil
        self.peripheral = nil
        rxCharacteristic = nil
        state = .idle
    }

    // MARK: - Sending

    func send(_ message: Data) {
        guard let peripheral, let rxCharacteristic else { return }
        // withResponse gives flow control; without it a burst of chunks can be
        // silently dropped when the phone's buffer fills.
        for piece in WireProtocol.chunk(message, payloadSize: payloadSize) {
            peripheral.writeValue(piece, for: rxCharacteristic, type: .withResponse)
        }
    }

    func sendHealth(_ samples: [HealthSample]) {
        guard !samples.isEmpty, state.isConnected else { return }
        // Large batches are split so no single message monopolises the link.
        for batch in samples.chunked(into: 40) {
            send(WireProtocol.health(samples: batch))
        }
        samplesSent += samples.count
        lastSync = Date()
    }

    func sendReply(to notification: BridgedNotification, text: String) {
        send(WireProtocol.reply(notificationID: notification.id, text: text))
        notifications.removeAll { $0.id == notification.id }
    }

    func clearNotifications() {
        notifications.removeAll()
    }

    // MARK: - Inbound

    private func handle(_ data: Data) {
        switch WireProtocol.decode(data) {
        case let .welcome(name, token):
            if !token.isEmpty { pairingToken = token }
            state = .connected(phone: name)

        case let .notification(item):
            notifications.removeAll { $0.id == item.id }
            notifications.insert(item, at: 0)
            if notifications.count > 50 { notifications.removeLast() }

        case let .ack(id):
            notifications.removeAll { $0.id == id }

        case .unknown:
            break
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BridgeLink: CBCentralManagerDelegate {

    nonisolated func centralManagerDidUpdateState(_ manager: CBCentralManager) {
        Task { @MainActor in
            switch manager.state {
            case .poweredOn:
                state = .scanning
                manager.scanForPeripherals(withServices: [WireProtocol.serviceUUID])
            case .poweredOff:
                state = .bluetoothOff
            case .unauthorized:
                state = .unauthorised
            case .unsupported:
                state = .failed("This watch has no Bluetooth LE")
            default:
                state = .idle
            }
        }
    }

    nonisolated func centralManager(
        _ manager: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        Task { @MainActor in
            guard self.peripheral == nil else { return }
            manager.stopScan()
            self.peripheral = peripheral
            peripheral.delegate = self
            state = .connecting
            manager.connect(peripheral)
        }
    }

    nonisolated func centralManager(
        _ manager: CBCentralManager,
        didConnect peripheral: CBPeripheral
    ) {
        Task { @MainActor in
            peripheral.discoverServices([WireProtocol.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ manager: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            state = .failed(error?.localizedDescription ?? "Could not connect")
            self.peripheral = nil
            manager.scanForPeripherals(withServices: [WireProtocol.serviceUUID])
        }
    }

    nonisolated func centralManager(
        _ manager: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        Task { @MainActor in
            self.peripheral = nil
            rxCharacteristic = nil
            state = .scanning
            // Keep looking: walking out of range and back should recover on
            // its own, without the user reopening the app.
            manager.scanForPeripherals(withServices: [WireProtocol.serviceUUID])
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BridgeLink: CBPeripheralDelegate {

    nonisolated func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        Task { @MainActor in
            guard let service = peripheral.services?.first(
                where: { $0.uuid == WireProtocol.serviceUUID }
            ) else {
                state = .failed("The phone is not offering the bridge service")
                return
            }
            peripheral.discoverCharacteristics(
                [WireProtocol.rxUUID, WireProtocol.txUUID],
                for: service
            )
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        Task { @MainActor in
            for characteristic in service.characteristics ?? [] {
                switch characteristic.uuid {
                case WireProtocol.rxUUID:
                    rxCharacteristic = characteristic
                case WireProtocol.txUUID:
                    peripheral.setNotifyValue(true, for: characteristic)
                default:
                    break
                }
            }

            payloadSize = peripheral.maximumWriteValueLength(for: .withResponse)
            send(
                WireProtocol.hello(
                    watchName: WKInterfaceDevice.current().name,
                    token: pairingToken
                )
            )
        }
    }

    nonisolated func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        Task { @MainActor in
            guard
                characteristic.uuid == WireProtocol.txUUID,
                let value = characteristic.value,
                let complete = reassembler.accept(value)
            else { return }
            handle(complete)
        }
    }
}

// MARK: - Helpers

extension Array {
    func chunked(into size: Int) -> [[Element]] {
        guard size > 0 else { return [self] }
        return stride(from: 0, to: count, by: size).map {
            Array(self[$0..<Swift.min($0 + size, count)])
        }
    }
}
