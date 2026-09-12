# Wristbridge

Android notifications on an Apple Watch, from a GrapheneOS Pixel, with no iPhone
in the loop.

## Read this first

### 1. You cannot pair an Apple Watch to an Android phone

Apple's own setup documentation requires an iPhone with the Watch app to pair
and activate an Apple Watch, and there is no supported path that does not. No
app, free or paid, changes that. Anything advertising otherwise is describing
something narrower than it sounds.

### 2. What bridging apps actually do

Since watchOS 6 an Apple Watch has its own App Store and can install
[standalone apps](https://developer.apple.com/documentation/watchos-apps/creating-independent-watchos-apps)
that run without an iPhone. Commercial bridges such as Merge are installed onto
the watch this way, according to their own setup instructions, and pair with a
companion Android app.

The watch is never paired to Android at the OS level. It is an app-to-app
bridge that sidesteps pairing entirely. The transport those apps use is not
publicly documented, so this project does not guess at it.

Building a watch app needs Swift, a Mac running Xcode, and an Apple Developer
Program membership for signing that lasts longer than seven days. That is the
real barrier, and no amount of work on the Android side removes it.

### 3. The seam this project uses

Apple documents that to receive SMS or third-party push notifications on a
cellular Apple Watch, [the paired iPhone must be powered on](https://support.apple.com/en-us/108300)
and connected, though it need not be nearby.

iCloud Mail appears to behave differently. Users report that iCloud Mail
continues to work on the watch over Wi-Fi or cellular with the iPhone
disconnected. Apple does not document this either way, and it is the assumption
the whole project rests on.

**Treat it as unproven until you have tested it yourself.** That is exactly what
the **Send to watch** button in Setup is for, and it takes about ten seconds.
If the mail reaches your wrist, everything else here works. If it does not, no
amount of configuration will help.

So: Wristbridge relays your Android notifications into your own iCloud mailbox,
and the watch pushes them to your wrist. Android-side code only. No Mac, no
Xcode, no developer account, no subscription.

## What you get

Wristbridge comes in two halves. The second is optional.

### Half one: the mail relay (no Mac, no iPhone, no developer account)

| | |
|---|---|
| Android notifications on your wrist | Yes, per app, a few seconds' latency |
| Notification text, sender, app name | Yes |
| Works with no iPhone involved | Yes, subject to the caveat above |
| Replying from the watch | Yes, via the watch's Mail app |
| Always on, app closed, screen off | Yes |
| Health data from the watch | No. HealthKit is unreachable from Android. |

### Half two: the watchOS app (needs a Mac, and an iPhone once)

| | |
|---|---|
| Heart rate, resting HR, blood oxygen | Yes |
| Steps, active energy, exercise, stand | Yes |
| Sleep stages and workouts | Yes |
| Notifications instantly, no email | While the watch app is open |
| Replying without touching Mail | While the watch app is open |
| Running in the background | No. This app does not attempt it. |

The two are complementary rather than alternatives. The watch app adds the
sensor half and makes delivery instant while you are looking at it. The mail
relay stays the always-on path, because this implementation only runs the
Bluetooth link while the watch app is in the foreground. Leave both on.

ECG is not implemented. HealthKit has exposed
[`HKElectrocardiogram`](https://developer.apple.com/videos/play/wwdc2020/10182/)
since iOS 14 and watchOS 7, so reading it is possible in principle, but a single
recording is thousands of voltage samples and does not suit a Bluetooth link
sized for short messages.

Setting up half two is [`watch/README.md`](watch/README.md). Read the
provisioning note there first: free signing lasts seven days, paid signing lasts
a year, and that decides whether this is genuinely a one-time setup.

### The one prerequisite

**Your Apple Watch must already be activated and signed into an iCloud account.**

If it is, because you used to have an iPhone or bought it set up, the mail relay
needs nothing else.

If it is factory-reset and showing a pairing screen, you need an iPhone once to
activate it. This is true of commercial bridges too.

## How it works

```
Android notification
        |  NotificationListenerService
   filter, de-duplicate, rate-limit
        |  SMTP over TLS
   smtp.mail.me.com  (your account, your credentials)
        |  Apple push
   Apple Watch: Mail notification on your wrist
```

Each notification becomes one small email, shaped so it reads like a
notification rather than mail:

- **Sender line** becomes `Signal · Alice`, app name first so it survives
  truncation on a small screen
- **Subject** becomes the message text
- **Body** carries the full text, a timestamp, and the source app

There is no Wristbridge server, no account, and no telemetry. Your credential is
encrypted with a key held in the Android Keystore, which no app can read back
out, including this one.

## Replying from your wrist

Android exposes a notification's **Reply** button to other devices as a
`RemoteInput` on a `PendingIntent`, the same mechanism a Wear OS watch uses.
Wristbridge holds onto it.

```
Reply in the watch's Mail app
        |  In-Reply-To: <token@wristbridge.local>
   iCloud IMAP, polled
        |  token identifies the original notification
   RemoteInput fired
        |
   Message sent from Signal, WhatsApp or SMS, as if typed on the phone
```

Turn it on in **Setup > Reply from your wrist**. Two caveats:

- It keeps a silent ongoing notification in your shade. The poll runs as a
  foreground service, because Android defers background work longer than a
  reply can usefully wait.
- A reply only works while the original notification still exists on the phone.
  Swipe it away and Android revokes the reply permission with it. The Activity
  tab tells you when that has happened.

## Getting the APK

### Option A: build it in the cloud, with no local tooling

1. Open the **Actions** tab, pick **Build APK**, then **Run workflow**.
2. When it finishes, download the `wristbridge-apk` artifact, about 2 MB.
3. Install it on your phone and allow installation from that source.

The workflow runs the unit tests before building, so a broken parser fails the
run rather than shipping.

### Option B: build locally

Needs a JDK and the Android SDK with platform 35. Built and tested here with
JDK 21; the Gradle and AGP versions pinned in this repo do not accept JDK 25.

```bash
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`, signed with
the debug key so a clean checkout produces something installable. Point the
release `signingConfig` at your own keystore if you want to distribute it.

If the build cannot find your SDK, set `sdk.dir` in `local.properties`.

## Setting it up

### On your Apple Watch

1. Confirm the watch is activated and signed into iCloud.
2. Connect it to Wi-Fi under **Settings > Wi-Fi** on the watch.
3. Open the **Mail** app on the watch once, so it syncs.
4. Under **Settings > Notifications > Mail**, check alerts are on, not silent.

### Generate an app-specific password

Apple requires an app-specific password for third-party mail clients when
two-factor authentication is on.

1. Sign in at [account.apple.com](https://account.apple.com).
2. Go to **Sign-In and Security > App-Specific Passwords > Generate**.
3. Copy it. Wristbridge strips the hyphens for you.

This is not your Apple ID password. That one will be rejected.

### In Wristbridge

1. **Setup** tab: enter your iCloud address and the app-specific password.
2. Tap **Test login**. If iCloud rejects it, the server's own error is shown
   verbatim so you know what to fix.
3. Tap **Send to watch**. This is the step that proves the whole approach.
4. **Apps** tab: pick the apps to forward. Start with two or three.
5. **Status** tab: grant notification access, then turn the relay on.

Also worth granting unrestricted battery from the Status tab, so Android does
not freeze the relay in the background.

## If the test mail does not reach your wrist

Mail arriving in your inbox but not on your watch is a watch-side setting:

- Mail notifications off on the watch, under **Settings > Notifications > Mail**
- Watch not on Wi-Fi, since it needs its own connection
- Notifications set to **Send to Notification Center**, which is silent by
  design; switch to alerts
- If none of those, try marking your own address as a **VIP** in Mail

If the mail never arrives at all, the problem is on the Android side, and the
**Activity** tab shows Apple's own error text.

## Keeping your inbox usable

Relaying every notification to an inbox fills it quickly. The defaults are
conservative and all of it is tunable under **Setup > Tuning**:

- **Opt in per app.** Nothing is forwarded until you choose it. The list shows
  apps with an icon by default; **Show every installed app** reaches the ones
  that notify you without appearing in your app drawer.
- **Repeat collapsing**, 20s by default, because chat apps repost on every
  message in a thread.
- **Hourly ceiling**, 60 by default, so one chatty app cannot monopolise the
  relay.
- **Daily ceiling**, 300 by default. This one protects your Apple account
  rather than your battery: Apple limits an iCloud account to
  [1,000 messages a day](https://support.apple.com/en-us/102198), and that is
  the same account your real email uses. Notifications delivered over Bluetooth
  do not count, because they never reach Apple.
- **Ongoing notifications skipped**, such as music players and navigation.
- **`FLAG_LOCAL_ONLY` respected**, so apps that ask not to be bridged are not.

A tidy option: make a second iCloud alias, point **Deliver to** at it, and add a
Mail rule keying off the `X-Wristbridge: 1` header that every relayed message
carries. Your main inbox stays clean and the watch still notifies, because the
alias is on the same account.

## What it accesses

- The app asks for an **app-specific password**, not your Apple ID password.
  Apple lets you revoke these individually at
  [account.apple.com](https://account.apple.com) without affecting anything
  else, which is the clean way to cut the app off. Setup also has a **Forget
  password** button for the copy held on the phone.
- Exactly two files open a network socket, both to `*.mail.me.com`:
  [Smtp.kt](app/src/main/java/dev/wristbridge/relay/Smtp.kt) for sending and
  [Imap.kt](app/src/main/java/dev/wristbridge/relay/Imap.kt) for reading
  replies. Searching the source for `Socket(` finds nothing else.
- Notification contents are not written to disk by this app. The Activity log
  is held in memory and cleared when the process restarts. The text does of
  course reach Apple, since sending it there is the whole mechanism.

## Risks worth knowing before you use it

**Apple has not blessed this use of iCloud.** Wristbridge talks to iCloud over
standard IMAP and SMTP with an app-specific password, which is the same thing
any mail client does. But it does so automatically and continuously, and
[Apple's iCloud terms](https://www.apple.com/legal/internet-services/icloud/us-en/terms.html)
prohibit "accessing the Service through any automated means" where that
interferes with or disrupts it, and reserve Apple's right to suspend an account
"at any time, under certain circumstances and without prior notice".

Ordinary mail-client traffic at the volumes here is a long way from the bulk
sending those clauses are aimed at, and the daily cap exists partly to keep it
that way. But the honest position is that this is not a use Apple has endorsed,
and the account at stake is the same Apple ID holding your photos, backups and
purchases. That is the real risk of running this, and it is larger than any
risk of the software misbehaving.

If that trade is not one you want to make, do not install it.

**The link between phone and watch.** Bluetooth delivery requires a bonded,
encrypted connection. Each end checks the other: the phone only accepts the
first watch that pairs, until you press "Forget this watch", and the watch
refuses a phone that does not present the token it paired with, so something
else advertising the same service cannot pose as your phone. Replies arriving
by mail are acted on only when they come from your own account. Neither of these makes the app immune to
a compromised phone or a compromised mailbox: anyone holding either can read
relayed notifications, and anyone who can send mail *from* your account can
trigger a reply.

## Project layout

```
app/src/main/java/dev/wristbridge/
├── data/
│   ├── SecureStore.kt        Keystore-backed AES-GCM for the credential
│   └── Settings.kt           All configuration, one place
├── relay/
│   ├── Smtp.kt               Dependency-free SMTP and MIME
│   ├── Imap.kt               Dependency-free IMAP, for the reply channel
│   ├── MimeText.kt           Pulls the written reply out of a mail body
│   ├── NotificationMapper.kt Notification to mail, shaped for a watch face
│   ├── RelayListenerService.kt  Filtering, de-dupe, rate limit, retry
│   ├── ReplyRegistry.kt      Holds each notification's RemoteInput action
│   ├── ReplyPollService.kt   Watches iCloud for replies
│   ├── SendQuota.kt          Keeps sending inside Apple's daily allowance
│   └── RelayLog.kt           In-memory activity log, never written to disk
├── ble/
│   ├── BleProtocol.kt        Wire format, mirrored in Protocol.swift
│   └── BleLinkService.kt     GATT server the watch app connects to
├── health/
│   └── HealthStore.kt        Samples received from the watch
└── ui/                       Compose UI

watch/                        The watchOS app; see watch/README.md
```

The mail parsing is covered by unit tests in `app/src/test/`, built from real
IMAP FETCH framing since it cannot be exercised against a live mailbox:

```bash
./gradlew :app:testDebugUnitTest
```

## Licence

MIT. See [LICENSE](LICENSE).

The built APK also contains Apache-2.0 licensed libraries from AndroidX, Jetpack
Compose and Kotlin. Their attribution is in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Where this could go further

- **Health Connect.** Forward the samples the watch sends into Android's Health
  Connect so they reach other apps on the phone. Not done yet: getting the data
  off the watch was the hard part, and this is mechanical once it flows.
- **Calendar and reminders.** Push Android events to iCloud over CalDAV so they
  appear on the watch face and in complications. Android-side only.
- **IMAP IDLE** instead of polling, to cut reply latency and retire the reply
  channel's foreground service.
- **ECG**, now that `HKElectrocardiogram` is known to be readable. The voltage
  series is too large for the current Bluetooth framing, so it would need
  chunked transfer or a summary rather than the full trace.
