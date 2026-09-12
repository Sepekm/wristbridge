import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var link: BridgeLink
    @EnvironmentObject private var health: HealthReader

    @State private var syncing = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    StatusRow(state: link.state)
                } header: {
                    Text("Phone")
                }

                Section {
                    Button {
                        Task { await sync() }
                    } label: {
                        HStack {
                            Text(syncing ? "Syncing…" : "Sync health now")
                            Spacer()
                            if syncing { ProgressView() }
                        }
                    }
                    .disabled(syncing || !link.state.isConnected)

                    if let lastSync = link.lastSync {
                        LabeledContent("Last sync", value: lastSync.formatted(date: .omitted, time: .shortened))
                    }
                    if link.samplesSent > 0 {
                        LabeledContent("Sent", value: "\(link.samplesSent) samples")
                    }
                    if !health.authorised {
                        Text("Health access not granted yet")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    if let error = health.lastError {
                        Text(error).font(.footnote).foregroundStyle(.red)
                    }
                } header: {
                    Text("Health")
                }

                if !link.notifications.isEmpty {
                    Section {
                        ForEach(link.notifications) { item in
                            NavigationLink {
                                NotificationDetail(item: item)
                            } label: {
                                NotificationRow(item: item)
                            }
                        }
                    } header: {
                        Text("From your phone")
                    }
                }
            }
            .navigationTitle("Wristbridge")
        }
        .task {
            await health.requestAuthorisation()
            link.start()
        }
    }

    private func sync() async {
        syncing = true
        defer { syncing = false }
        let samples = await health.collectNewSamples()
        guard !samples.isEmpty else { return }
        if link.sendHealth(samples) {
            health.markDelivered(samples)
        }
    }
}

private struct StatusRow: View {
    let state: BridgeLink.State

    var body: some View {
        HStack {
            Circle()
                .fill(colour)
                .frame(width: 8, height: 8)
            Text(label)
                .font(.footnote)
        }
    }

    private var label: String {
        switch state {
        case .idle: "Idle"
        case .bluetoothOff: "Bluetooth is off"
        case .unauthorised: "Bluetooth access denied"
        case .scanning: "Looking for your phone…"
        case .connecting: "Connecting…"
        case let .connected(phone): "Connected to \(phone)"
        case let .failed(reason): reason
        }
    }

    private var colour: Color {
        switch state {
        case .connected: .green
        case .scanning, .connecting: .orange
        default: .red
        }
    }
}

private struct NotificationRow: View {
    let item: BridgedNotification

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.app)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(item.title.isEmpty ? item.text : item.title)
                .font(.footnote)
                .lineLimit(2)
        }
    }
}

private struct NotificationDetail: View {
    let item: BridgedNotification

    @EnvironmentObject private var link: BridgeLink
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(item.app)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if !item.title.isEmpty {
                    Text(item.title).font(.headline)
                }
                Text(item.text).font(.body)

                if item.canReply {
                    // On watchOS this field opens dictation, scribble, or the
                    // keyboard, so no custom input UI is needed.
                    TextField("Reply", text: $draft)
                        .padding(.top, 8)
                    Button("Send") {
                        if link.sendReply(to: item, text: draft) {
                            dismiss()
                        } else {
                            failed = true
                        }
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    if failed {
                        Text("No link to your phone just now. The message is still here.")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                } else {
                    Text("This notification cannot be replied to.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle(item.app)
    }
}
