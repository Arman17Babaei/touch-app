#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd); results="$root/build/test-results/device-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$results"
pass(){ echo "[PASS] $1"; }; fail(){ echo "[FAIL] $1" >&2; exit 1; }; adb_cmd(){ adb -s "$1" "${@:2}"; }
phone=${PHONE_SERIAL:-}; watch=${WATCH_SERIAL:-}; port=${TOUCH_TEST_PORT:-18081}
[[ -n $phone && -n $watch ]] || fail "set PHONE_SERIAL and WATCH_SERIAL (automatic role discovery is intentionally not ambiguous)"
for serial in "$phone" "$watch"; do adb_cmd "$serial" get-state >/dev/null || fail "$serial unavailable"; done
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 "$root/gradlew" :mobile:assembleDebug :wear:assembleDebug >"$results/build.log" 2>&1
phone_apk=$(find "$root/mobile/build/outputs/apk/debug" -name '*debug*.apk' | head -1); watch_apk=$(find "$root/wear/build/outputs/apk/debug" -name '*debug*.apk' | head -1)
for item in "$phone:$phone_apk:app.touch.mobile" "$watch:$watch_apk:app.touch.wear"; do IFS=: read -r serial apk package <<<"$item"; adb_cmd "$serial" install -r "$apk" >/dev/null; adb_cmd "$serial" shell pm clear "$package" >/dev/null; adb_cmd "$serial" shell pm grant "$package" android.permission.POST_NOTIFICATIONS 2>/dev/null || true; adb_cmd "$serial" reverse "tcp:$port" "tcp:$port"; done
tmp=$(mktemp -d); cleanup(){ [[ -n ${backend_pid:-} ]] && kill "$backend_pid" 2>/dev/null || true; rm -rf "$tmp"; }; trap cleanup EXIT
(cd "$root/backend" && go build -o "$tmp/backend" .)
TOUCH_LISTEN_ADDR="127.0.0.1:$port" TOUCH_SQLITE_PATH="$tmp/touch.db" "$tmp/backend" >"$results/backend.log" 2>&1 & backend_pid=$!
until curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null; do kill -0 "$backend_pid" 2>/dev/null || { cat "$results/backend.log"; fail "backend did not start"; }; sleep .2; done
backend=http://127.0.0.1:$port; phone_user=device-phone-$RANDOM; watch_user=device-watch-$RANDOM; message=55555555-5555-4555-8555-555555555555
control(){ adb_cmd "$1" shell am broadcast --receiver-foreground -a app.touch.communication.TEST_CONTROL -n "$2/app.touch.communication.debug.TouchTestReceiver" --es command "$3" "${@:4}"; }
control "$phone" app.touch.mobile configure --es backendUrl "$backend" --es username "$phone_user" --es peerUsername "$watch_user" >"$results/phone-configure.txt"
control "$watch" app.touch.wear configure --es backendUrl "$backend" --es username "$watch_user" --es peerUsername "$phone_user" >"$results/watch-configure.txt"
control "$phone" app.touch.mobile enqueueTouch --es clientMessageId "$message" --ei samplePeriodMs 10 --es amplitudes '0,32,128,255,0' >"$results/enqueue.txt"
deadline=$((SECONDS+45)); state=''; while ((SECONDS < deadline)); do control "$phone" app.touch.mobile state >"$results/phone-state.txt"; control "$watch" app.touch.wear sync >"$results/watch-sync.txt"; control "$watch" app.touch.wear state >"$results/watch-state.txt"; state=$(cat "$results/watch-state.txt"); grep -q "$message" <<<"$state" && grep -q 'playedAtMs' <<<"$state" && break; sleep 1; done
grep -q '"status":"SENT"' "$results/phone-state.txt" || fail "phone outbox did not reach SENT"
grep -q "$message" "$results/watch-state.txt" || fail "watch did not persist client message"
grep -q 'playedAtMs' "$results/watch-state.txt" || fail "watch did not record playback"
pass "phone to watch durable delivery and playback"; echo "Artifacts: $results"
