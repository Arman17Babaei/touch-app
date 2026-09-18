# Touch

Touch is a standalone Android and Wear OS haptic-and-voice messenger. Each app can record,
play, queue, send, receive, and retain touches independently; it does not use the
Wear Data Layer.

## Modules

- `core`: touch model, 10 ms recorder, optimizer, and Android vibrator adapter
- `communication`: installation/settings storage, Room inbox/outbox, HTTP client,
  WorkManager delivery, FCM handling, notifications, and playback coordination
- `mobile`: phone UI (code namespace `app.touch.mobile`)
- `wear`: standalone Wear OS UI (code namespace `app.touch.wear`)

Both applications use the install/package ID `ir.armanbabaei.touch` so Google
Play can distribute them from one listing on its mobile and Wear OS tracks.
- `backend`: Go HTTP service, SQLite store, and Firebase Admin sender

## Backend

The backend stores a touch before attempting its high-priority FCM data message.
Durable-message FCM contains only `type=touch_available` and the touch ID; the
recipient fetches the payload from the durable FIFO inbox. Live invitations use
`type=live_invite` with the ephemeral call ID and caller username. Durable
messages expire after 30 days; Live calls are never stored.

Create `backend/secrets/service-account.json` from the Firebase project that owns
both Android apps. The directory and credential files are ignored by Git.

```shell
cd backend
export TOUCH_FIREBASE_PROJECT_ID=your-firebase-project
export GOOGLE_APPLICATION_CREDENTIALS=/secrets/service-account.json
docker compose up --build
```

The service listens on port 8080 and persists SQLite in the `touch-data` volume.
Check it with `curl http://localhost:8080/healthz`. To run without FCM for local
API work, omit both Firebase environment variables. Stale usernames can be
removed manually from SQLite in v1 after confirming that their installation is
no longer active.

Allow the LAN port through the host firewall when UFW is enabled:

```shell
sudo ufw allow 8080/tcp
```

For USB/Wi-Fi ADB-only development without opening the firewall, create a tunnel
for each device and use `http://127.0.0.1:8080` as its backend URL:

```shell
adb -s DEVICE_SERIAL reverse tcp:8080 tcp:8080
```

The container supports these variables:

- `TOUCH_LISTEN_ADDR` (default `:8080`)
- `TOUCH_SQLITE_PATH` (default `/data/touch.db` in the container)
- `TOUCH_FIREBASE_PROJECT_ID`
- `GOOGLE_APPLICATION_CREDENTIALS`
- `TOUCH_LOG_LEVEL` (`debug`, `info`, `warn`, or `error`; default `info`)

The server logs every HTTP status with a truncated installation ID. Authentication
rejections identify whether the installation header was invalid or the ID was not
registered. FCM logs record send outcomes and Firebase's error, but never tokens
or message payloads. Set `TOUCH_LOG_LEVEL=debug` temporarily while diagnosing.

On Android, communication logs use the `TouchComm` tag. Enable request, FCM, and
WebSocket diagnostics at runtime without rebuilding:

```shell
adb shell setprop log.tag.TouchComm DEBUG
adb logcat -s TouchComm
```

## Firebase client configuration

Register one Android app, `ir.armanbabaei.touch`, in Firebase. Both the mobile
and standalone Wear OS builds use this shared Play/Firebase identity. Put these
values in the untracked root `local.properties` file:

```properties
TOUCH_FIREBASE_PROJECT_ID=your-firebase-project
TOUCH_FIREBASE_SENDER_ID=1234567890
TOUCH_FIREBASE_API_KEY=your-client-api-key
TOUCH_FIREBASE_APP_ID=1:1234567890:android:shared-app-id
```

These are Firebase client identifiers, not the backend service-account secret.
No `google-services.json` or service-account credential is tracked.

## Build and first run

Requirements are JDK 17, Android SDK 35, Android 8/API 26+ on phone, and Wear OS
3/API 30+ on watch.

```shell
./gradlew test assembleDebug
```

Install each debug APK, then enter on each device:

1. A backend address reachable from that device, such as
   `http://192.168.1.20:8080`.
2. A unique username for that installation.
3. The other installation's username as its peer.

Plain HTTP is permitted only by the debug manifests. Release builds require
HTTPS unless their network policy is changed deliberately.

## GitHub releases

Publishing a GitHub Release builds, signs, and attaches APK and Android App
Bundle (`.aab`) files for `mobile` and `wear`, plus a `SHA256SUMS.txt` file. The
release tag becomes the Android `versionName` (a leading `v` is removed). Mobile
uses `workflow run number * 10 + 1` as its `versionCode`; Wear uses `workflow run
number * 10 + 2`, keeping both codes unique under their shared Play listing.

Create a release keystore once and keep it backed up securely. Losing it means
future versions cannot update installations signed with that key. Add these
repository Actions secrets under **Settings > Secrets and variables > Actions**:

- `ANDROID_KEYSTORE_BASE64`: the keystore file encoded with
  `base64 -w 0 touch-release.jks`
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password
- `ANDROID_KEY_ALIAS`: the key alias
- `ANDROID_KEY_PASSWORD`: the key password

Also add the four Firebase client values from the earlier configuration section
as Actions **variables**, using the same names. The signing material is written
only to the temporary GitHub runner and is never committed or uploaded. The
workflow fails instead of publishing unsigned APKs when signing secrets are
missing.

The sender queues a non-silent recording in Room and WorkManager retries network
failures. The recipient syncs on FCM, launch, and resume; it persists and dedupes
before acknowledging. Fresh touches (up to five minutes old) auto-play FIFO when
the app is idle. All arrivals create an inbox entry and, when notification
permission is granted, a notification. The newest 100 local inbox items remain
available for replay. **Clear** removes all received touches and stops current
inbox playback on that device; already acknowledged server messages are not
downloaded again.

## Live touch

Tap **Go live** on the phone or **Live** on the watch to open the call page. The
caller sees Connecting and Ringing; the recipient receives a high-priority FCM
notification and explicitly accepts or declines. An unanswered call expires
after 60 seconds. Once connected, both touch surfaces transmit continuously in
10-sample (about 100 ms) batches, including zero-amplitude silence, until either
side hangs up. There is no 30-second Live limit and Live samples are never saved
to the inbox.

The receiver starts with a roughly 300 ms jitter buffer and plays longer chunks
instead of cancelling the vibrator for every packet. Phones without amplitude
control retain the on/off duty-cycle mapping for intensity; watches and phones
with amplitude control use amplitude waveforms.

The app no longer keeps the screen awake. Locking or leaving either app hangs up
the active call, while FCM continues to deliver durable touches and new Live
invitations. An accidental network loss enters Reconnecting for up to 30 seconds;
successful resume creates fresh stream IDs and resets sample indexes. Recorded
**Send** remains the durable and offline-capable path.

The WebSocket endpoint is `GET /v1/live`, authenticated with the same
`X-Installation-ID` header. Call control uses `call`, `accept`, `decline`,
`resume`, and `hangup`, with `ringing`, `incoming`, `connected`, `reconnecting`,
and `ended` responses. Streaming continues to use `start`, `samples`, and `end`
for compatibility with existing clients. `startIndex` is zero-based and must be
present even for the first batch.

## Voice audio and privacy

Recording a touch also records an optional mono 16 kHz AAC-LC voice attachment, capped at
30 seconds and 256 KiB. The microphone is on by default for a recording or connected Live
call when Android grants `RECORD_AUDIO`; the Mic control stops capture without disabling
haptics. Permission denial leaves the app usable as a haptic-only messenger.

Incoming voice auto-plays only on wired, USB, Bluetooth communication, BLE headset, or
hearing-aid routes. It never auto-plays on speakers, car/cast/HDMI routes, or generic
Bluetooth media devices. **Audio** / **Play audio** explicitly permits speaker output only
for the current item or call. Removing headphones stops voice immediately. Live voice is
foreground-only and ephemeral; durable voice is the optional `audio` API object.

## Tests

```shell
(cd backend && go test ./...)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test assembleDebug
```

Backend tests cover username conflicts, validation, idempotency, FIFO retrieval,
acknowledgements, expiry, persistence across mocked FCM failure, call invitation,
acceptance, hangup, and bidirectional live relay. Android unit
tests cover settings completeness, stable installation identity selection,
unsigned amplitude serialization, retry classification, and five-minute FIFO
policy. Final FCM/background/offline acceptance still requires two configured
physical devices and real Firebase credentials.
