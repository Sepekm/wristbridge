# Wristbridge for Apple Watch

The watchOS half of the bridge. This is what unlocks everything the Android
side cannot reach on its own — heart rate, sleep, workouts, and notifications
that arrive instantly instead of as email.

## Read this before you spend an afternoon on it

**A free Apple ID signs this for seven days.** After that the app stops
launching and has to be reinstalled from the Mac. That is Apple's limit on free
provisioning, not something the code can work around.

A **paid Apple Developer account ($99/year)** signs it for a year. That is the
difference between a genuine one-time setup and a weekly chore, and it is worth
knowing before you start rather than after.

**The watch app is not a background service.** watchOS does not let a
third-party app hold a Bluetooth connection open indefinitely in the
background. In practice:

| | |
|---|---|
| Health sync | Works — open the app, tap Sync, it uploads what's new |
| Notifications over Bluetooth | While the watch app is open |
| Replies over Bluetooth | While the watch app is open |
| Notifications when the app is closed | Falls back to the iCloud Mail relay |

So the watch app does not replace the mail relay; it adds the sensor half and
makes things instant while you're looking at it. Leave the mail relay on.

## What you need

- A Mac with Xcode 15 or later
- Your spare iPhone, **once** — see the Developer Mode step below
- The Apple Watch, activated and on the same Wi-Fi as the Mac

## Build and install

### 1. Enable Developer Mode on the watch

This is the step that needs the iPhone. On a watch that has never been used for
development, the Developer Mode toggle does not appear on its own.

1. Connect the iPhone to the Mac with a cable.
2. Open Xcode → **Window → Devices and Simulators**.
3. Trust the Mac on the iPhone when prompted, then on the watch.
4. On the watch: **Settings → Privacy & Security → Developer Mode**, turn it
   on, and restart the watch when asked.

Once Developer Mode is on, the iPhone is no longer needed.

### 2. Generate the Xcode project

```bash
brew install xcodegen
cd watch
xcodegen generate
open WristbridgeWatch.xcodeproj
```

The project is generated from [`project.yml`](project.yml) rather than checked
in, so there is no large machine-written file to review or merge.

### 3. Sign it

In Xcode, select the **WristbridgeWatch** target → **Signing & Capabilities**:

- Tick **Automatically manage signing**
- Pick your team (a personal Apple ID works, with the seven-day caveat above)
- Change the bundle identifier if `dev.wristbridge.watch` is taken — it must be
  unique across the App Store

### 4. Install onto the watch

1. Make sure the watch is on the same Wi-Fi as the Mac, and Bluetooth is on
   for both.
2. In Xcode's run destination menu, pick your Apple Watch. It can take a minute
   to appear the first time.
3. Press Run.

If the watch does not show up: keep it close to the Mac for Bluetooth but near
the Wi-Fi access point too — the watch needs a stronger signal to associate
than a phone does. Toggling Developer Mode off and on, then restarting the
watch, resolves most of the rest.

### 5. Pair it with the phone

1. On Android, open Wristbridge → **Watch** tab → turn on **Discoverable by
   your watch**. Grant the Bluetooth permission.
2. Open Wristbridge on the watch. It scans, connects, and shows
   "Connected to <your phone>".
3. Grant the health permission prompt on the watch.
4. Tap **Sync health now**. The samples appear on the Android **Watch** tab.

## How it works

```
  Apple Watch                              Android phone
  ┌──────────────┐                         ┌──────────────┐
  │ HealthKit    │──┐                      │              │
  │ (HR, sleep,  │  │  BLE central ────────▶ GATT server  │
  │  workouts)   │  │  writes health       │  peripheral  │
  └──────────────┘  │                      │              │
                    │  subscribes  ◀────────  notifies    │
                    └──  notifications      │             │
                                            └──────────────┘
```

The phone is the peripheral and the watch is the central, because watchOS
offers no `CBPeripheralManager` — a watch app can only ever be the one that
connects. Messages are UTF-8 JSON, split into chunks that fit the negotiated
MTU; see [`Protocol.swift`](Sources/WristbridgeWatch/Protocol.swift) and its
Kotlin counterpart, which must be kept in step.

## Files

```
watch/
├── project.yml                 XcodeGen spec — the project is generated
├── WristbridgeWatch.entitlements
└── Sources/WristbridgeWatch/
    ├── WristbridgeWatchApp.swift  Entry point
    ├── ContentView.swift          UI: status, sync, notifications, replies
    ├── BridgeLink.swift           BLE central, framing, reconnection
    ├── HealthReader.swift         HealthKit queries with a sync watermark
    ├── Protocol.swift             Wire format, mirrors BleProtocol.kt
    └── Info.plist
```

`.github/workflows/build-watch.yml` compiles this on a macOS runner for the
simulator, with signing disabled, so Swift errors surface in CI rather than on
the Mac.
