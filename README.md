# Wristbridge

Android notifications on an Apple Watch, from a GrapheneOS Pixel, with no iPhone
in the loop and nothing behind a paywall.

---

## Read this first: what is and isn't possible

I looked into this properly before writing any code, and the answer has three
parts. Two of them are bad news, and the third is the reason this app exists.

### 1. You cannot pair an Apple Watch to an Android phone. Ever.

This isn't a missing feature or a locked API — it's structural. Apple Watch
setup runs an activation handshake against Apple's servers that is
cryptographically bound to genuine Apple hardware: the signing keys live in the
Secure Enclave of an iPhone and are certified by Apple's CA. Replicating that
would mean forging Apple's device attestation, which is not a "trick", it's an
unsolved cryptography problem.

So: **no app, free or paid, pairs an Apple Watch with a Pixel.** Anything
advertising otherwise is describing something narrower than it sounds.

### 2. What Merge actually does — and why its paywall isn't hiding magic

Merge is legitimate, and it's cleverer than it first looks. Since watchOS 6, the
Apple Watch has its own App Store and can install **standalone apps** that run
without an iPhone. Merge ships a native watchOS app, and that app talks to their
Android app over Bluetooth LE and a cloud relay.

The watch is never "paired" to Android at the OS level. It's an app-to-app
bridge that sidesteps pairing entirely.

That's a real architecture — but it's one I can't hand you. Building the watch
half requires Swift, a Mac running Xcode, and a $99/year Apple Developer
account to get a provisioning profile that lasts longer than seven days. You're
on Windows. That's the actual wall, and no amount of cleverness on the Android
side climbs it.

### 3. The seam that is left open

While researching the above, one detail turned out to matter more than anything
else. On an Apple Watch:

- Third-party push notifications and iMessage **require the paired iPhone to be
  powered on** somewhere with an internet connection. Not nearby — but on.
- **iCloud Mail does not.** The watch maintains its own connection to iCloud
  over Wi-Fi or cellular, and mail pushes to the wrist with the iPhone off
  entirely.

iCloud Mail is the one notification channel that survives with no iPhone
anywhere in the picture. That's the seam.

**So Wristbridge relays your Android notifications into your own iCloud inbox,
and your watch pushes them to your wrist.** It's Android-side code only — no
Mac, no Xcode, no developer account, no subscription.

### What you get, honestly

| | |
|---|---|
| Android notifications on your wrist | Yes, per-app, a few seconds' latency |
| Notification text, sender, app name | Yes |
| Works with the iPhone off / nonexistent | Yes — that's the whole design |
| **Replying from the watch** | **Yes** — see below |
| Health data from watch → phone | No. That needs code running on the watch. |
| Heart rate, ECG, sleep, workouts | No. Same reason. |
| Controlling the phone from the watch | No |

Anything that reads the watch's *sensors* is out of reach, because only code
running on watchOS can touch HealthKit. Getting code onto the watch needs a Mac
within Bluetooth range, and — per Apple's own developer forums — an iPhone
plugged into that Mac before Developer Mode will even appear on the watch. That
is the honest boundary, and it is not one cleverness crosses.

Everything on the Android side of that line is fair game, which is why replies
work.

### The one prerequisite I can't work around

**Your Apple Watch must already be activated and signed into an iCloud account.**

If it is — you used to have an iPhone, or you bought it set up — you're fine,
and you never need an iPhone again.

If it's factory-reset and showing a pairing screen, it is currently an
expensive paperweight, and you need to borrow an iPhone **once** to activate it.
This is true of Merge too. Nothing changes it.

---

## How it works

```
Android notification
        ↓  NotificationListenerService
   filter · de-duplicate · rate-limit
        ↓  SMTP over TLS
   smtp.mail.me.com  (your account, your credentials)
        ↓  Apple push
   Apple Watch → Mail notification on your wrist
```

Each notification becomes one small email, shaped so it reads like a
notification rather than mail:

- **Sender line** → `Signal · Alice` (app name first, so it survives truncation)
- **Subject** → the message text
- **Body** → full text, timestamp, source app

Nothing passes through any server but Apple's. There is no Wristbridge backend,
no account, and no telemetry. Your credential is encrypted with an Android
Keystore key that cannot leave the device.

## Replying from your wrist

Android exposes a notification's **Reply** button to other devices as a
`RemoteInput` on a `PendingIntent` — it is the exact mechanism a Wear OS watch
uses to answer a message. Wristbridge holds onto it.

```
Reply on the watch's Mail app
        ↓  In-Reply-To: <token@wristbridge.local>
   iCloud IMAP, polled
        ↓  token → the original notification
   RemoteInput fired
        ↓
   Message sent from Signal / WhatsApp / SMS, as if typed on the phone
```

Turn it on in **Setup → Reply from your wrist**. Two honest caveats:

- It keeps a **silent ongoing notification** in your shade. The poll has to run
  as a foreground service, because Android defers background work far longer
  than a reply can usefully wait.
- A reply only works while the **original notification still exists** on the
  phone. Swipe it away and Android revokes the reply permission with it. The
  Activity tab tells you when that's what happened.

---

## Getting the APK

### Option A — build it in the cloud (no local tooling)

1. Push this folder to a GitHub repository.
2. Open the **Actions** tab → **Build APK** → **Run workflow**.
3. When it finishes, download the `wristbridge-apk` artifact (~2 MB).
4. Install it on your Pixel. GrapheneOS will prompt for permission to install
   from that source.

The workflow is already at `.github/workflows/build-apk.yml`. It runs the unit
tests before building, so a broken parser fails the run rather than shipping.

### Option B — build locally

Needs a JDK (17–21) and the Android SDK with platform 35.

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

It is signed with the debug key so a clean checkout builds something
installable. That is fine for sideloading onto your own phone; point the
release `signingConfig` at your own keystore if you ever want to distribute it.

> Point `local.properties` at your SDK (`sdk.dir=...`) if the build can't find
> it. JDK 22+ is too new for this Gradle/AGP pair — use 17 or 21.

---

## Setting it up

### On your Apple Watch

1. Confirm the watch is activated and signed into iCloud.
2. Connect it to Wi-Fi: **Settings → Wi-Fi** on the watch.
3. Open the **Mail** app on the watch once, so it syncs.
4. **Settings → Notifications → Mail** — make sure alerts are on, not silent.

### Generate an app-specific password

Apple requires one for any non-Apple mail client.

1. Go to [account.apple.com](https://account.apple.com) and sign in.
2. **Sign-In and Security → App-Specific Passwords → Generate**.
3. Copy it. Wristbridge strips the hyphens for you.

This is **not** your Apple ID password — that one will be rejected.

### In Wristbridge

1. **Setup** tab: enter your iCloud address and the app-specific password.
2. Tap **Test login**. If iCloud rejects it, the error from Apple's server is
   shown verbatim so you know exactly what to fix.
3. Tap **Send to watch**. Look at your wrist — this is the moment of truth.
4. **Apps** tab: pick the apps to forward. Start with two or three.
5. **Status** tab: grant notification access, then flip the relay on.

Also worth granting: unrestricted battery, from the Status tab. Without it
Android may freeze the relay in the background and delay notifications.

---

## If the test mail doesn't reach your wrist

The mail arriving in your inbox but not on your watch is the most likely
failure, and it's a watch-side setting every time:

- **Mail notifications off on the watch** — Settings → Notifications → Mail.
- **Watch not on Wi-Fi** — it needs its own connection.
- **Notifications set to "Send to Notification Center"** — silent by design;
  switch to alerts.
- **Still nothing?** In Mail, mark your own address as a **VIP**. VIP mail
  pushes far more reliably than ordinary inbox mail.

If the mail never arrives at all, the problem is on the Android side and the
**Activity** tab will say why, with Apple's own error text.

---

## Keeping your inbox usable

Relaying every notification to an inbox floods it fast. The defaults are
deliberately conservative, and all of it is tunable in **Setup → Tuning**:

- **Opt-in per app.** Nothing is forwarded until you choose it.
- **Repeat collapsing** (20s default) — chat apps repost on every message.
- **Hourly ceiling** (60 default) — a misbehaving app can't drain your battery.
- **Ongoing notifications skipped** — music players, downloads, navigation.
- **`FLAG_LOCAL_ONLY` respected** — apps that ask not to be bridged aren't.

A tidy option: make a second iCloud alias, point **Deliver to** at it, and add a
Mail rule on the Apple side keying off the `X-Wristbridge: 1` header every
relayed message carries. Your main inbox stays clean, and the watch still
notifies because the alias is on the same account.

---

## Project layout

```
app/src/main/java/dev/wristbridge/
├── data/
│   ├── SecureStore.kt        Keystore-backed AES-GCM for the credential
│   └── Settings.kt           All configuration, one place
├── relay/
│   ├── Smtp.kt               Dependency-free SMTP + MIME
│   ├── Imap.kt               Dependency-free IMAP, for the reply channel
│   ├── MimeText.kt           Pulls the written reply out of a mail body
│   ├── NotificationMapper.kt Notification → mail shaped for a watch face
│   ├── RelayListenerService.kt  Filtering, de-dupe, rate limit, retry
│   ├── ReplyRegistry.kt      Holds each notification's RemoteInput action
│   ├── ReplyPollService.kt   Watches iCloud for replies
│   └── RelayLog.kt           In-memory activity log (never written to disk)
└── ui/                       Compose UI
```

The mail parsing is covered by unit tests (`app/src/test/`) built from real
IMAP FETCH framing, since it can't be exercised against a live mailbox:

```bash
./gradlew :app:testDebugUnitTest
```

---

## Where this could go further

- **Calendar.** Push Android calendar events to iCloud over CalDAV so they
  appear on the watch face and in complications. Android-side only, so it is
  genuinely reachable — the next thing worth building.
- **Reminders.** Same channel, as CalDAV VTODOs.
- **IMAP IDLE** instead of polling, to drop reply latency to near-instant and
  retire the foreground service.
- **The full bridge.** If you ever get access to a Mac, the watchOS half —
  BLE GATT to the Android app, health data flowing back — becomes possible, and
  the Android side here is already the right shape to talk to it.
