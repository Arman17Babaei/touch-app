#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
results="$root/build/test-results/local-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$results"
pass(){ echo "[PASS] $1"; }; fail(){ echo "[FAIL] $1" >&2; exit 1; }
for command in go jq yq; do command -v "$command" >/dev/null || fail "missing $command"; done
yq -e '.openapi == "3.1.0" and .paths."/v1/touches".post and .components.schemas.ErrorResponse' "$root/api/openapi.yaml" >/dev/null || fail "OpenAPI contract invalid"
pass "OpenAPI document"
(cd "$root/backend" && go test ./...) 2>&1 | tee "$results/backend.log"; pass "Go backend tests"
(cd "$root" && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :core:testDebugUnitTest :communication:testDebugUnitTest :mobile:assembleDebug :wear:assembleDebug) 2>&1 | tee "$results/android.log"; pass "Android JVM tests and debug APKs"
echo "Artifacts: $results"
