#!/usr/bin/env bash
set -Eeuo pipefail
if [[ -z ${TOUCH_FIREBASE_PROJECT_ID:-} || -z ${GOOGLE_APPLICATION_CREDENTIALS:-} || ! -f ${GOOGLE_APPLICATION_CREDENTIALS:-/nonexistent} ]]; then
  echo '[SKIP] FCM credentials are absent; this optional suite was not run.' >&2
  exit 2
fi
echo '[FAIL] FCM device suite requires a reachable Firebase-backed backend test deployment; run test-device.sh for deterministic foreground delivery.' >&2
exit 1
