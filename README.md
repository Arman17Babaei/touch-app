# Touch

Touch is a standalone Android and Wear OS haptic messenger. Each app can record,
play, queue, send, receive, and retain touches independently; it does not use the
Wear Data Layer.

## Modules

- `core`: touch model, 10 ms recorder, optimizer, and Android vibrator adapter
- `communication`: installation/settings storage, Room inbox/outbox, HTTP client,
  WorkManager delivery, FCM handling, notifications, and playback coordination
- `mobile`: phone UI (`app.touch.mobile`)
- `wear`: standalone Wear OS UI (`app.touch.wear`)
- `backend`: Go HTTP service, SQLite store, and Firebase Admin sender

## Backend

The backend stores a touch before attempting its high-priority FCM data message.
FCM contains only `type=touch_available` and the touch ID; the recipient fetches
the payload from the durable FIFO inbox. Messages expire after 30 days.

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

## Firebase client configuration

Register Android apps `app.touch.mobile` and `app.touch.wear` in the same Firebase
project. Put these values in the untracked root `local.properties` file:

```properties
TOUCH_FIREBASE_PROJECT_ID=your-firebase-project
TOUCH_FIREBASE_SENDER_ID=1234567890
TOUCH_FIREBASE_API_KEY=your-client-api-key
TOUCH_MOBILE_FIREBASE_APP_ID=1:1234567890:android:mobile-app-id
TOUCH_WEAR_FIREBASE_APP_ID=1:1234567890:android:wear-app-id
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

Publishing a GitHub Release builds, signs, and attaches one APK for `mobile` and
one for `wear`, plus a `SHA256SUMS.txt` file. The release tag becomes the Android
`versionName` (a leading `v` is removed), and the workflow run number becomes
the monotonically increasing `versionCode`.

Create a release keystore once and keep it backed up securely. Losing it means
future versions cannot update installations signed with that key. Add these
repository Actions secrets under **Settings > Secrets and variables > Actions**:

- `ANDROID_KEYSTORE_BASE64`: the keystore file encoded with
  `base64 -w 0 touch-release.jks`
- `ANDROID_KEYSTORE_PASSWORD`: the keystore password
- `ANDROID_KEY_ALIAS`: the key alias
- `ANDROID_KEY_PASSWORD`: the key password

Also add the five Firebase client values from the earlier configuration section
as Actions **variables**, using the same names. The signing material is written
only to the temporary GitHub runner and is never committed or uploaded. The
workflow fails instead of publishing unsigned APKs when signing secrets are
missing.

The sender queues a non-silent recording in Room and WorkManager retries network
failures. The recipient syncs on FCM, launch, and resume; it persists and dedupes
before acknowledging. Fresh touches (up to five minutes old) auto-play FIFO when
the app is idle. All arrivals create an inbox entry and, when notification
permission is granted, a notification. The newest 100 local inbox items remain
available for replay.

## Live touch

With both apps open, tap **Go live** on the phone and **Live** on the watch. Once
both show `live`, recording on either device streams 10-sample (about 100 ms)
batches to the saved peer while the complete recording is still retained
locally. Both devices can transmit and receive at the same time. Live samples
are ephemeral: they are not written to either inbox and are not retried after a
disconnect.

The foreground screen is kept awake during a Live session. Locking the device,
leaving the app, losing Wi-Fi, or stopping the backend ends best-effort realtime
delivery; recorded **Send** remains the durable and offline-capable path.

The WebSocket endpoint is `GET /v1/live`, authenticated with the same
`X-Installation-ID` header. Client events are `start`, `samples`, and `end`;
server-only status events are `peerUnavailable` and `error`. `startIndex` is
zero-based and must be present even for the first batch.

## Tests

```shell
(cd backend && go test ./...)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew test assembleDebug
```

Backend tests cover username conflicts, validation, idempotency, FIFO retrieval,
acknowledgements, expiry, persistence across mocked FCM failure, and bidirectional
live relay. Android unit
tests cover settings completeness, stable installation identity selection,
unsigned amplitude serialization, retry classification, and five-minute FIFO
policy. Final FCM/background/offline acceptance still requires two configured
physical devices and real Firebase credentials.
