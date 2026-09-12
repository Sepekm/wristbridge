import SwiftUI

@main
struct WristbridgeWatchApp: App {
    @StateObject private var link = BridgeLink()
    @StateObject private var health = HealthReader()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(link)
                .environmentObject(health)
        }
    }
}
