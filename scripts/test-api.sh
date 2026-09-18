#!/usr/bin/env bash
set -Eeuo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd); results="$root/build/test-results/api-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$results"
pass(){ echo "[PASS] $1"; }; fail(){ echo "[FAIL] $1" >&2; exit 1; }; require(){ command -v "$1" >/dev/null || fail "missing $1"; }
for c in go curl jq; do require "$c"; done
tmp=$(mktemp -d); port=${TOUCH_TEST_PORT:-18080}; token=test-token; log="$results/backend.log"
cleanup(){ [[ -n ${pid:-} ]] && kill "$pid" 2>/dev/null || true; rm -rf "$tmp"; }; trap cleanup EXIT
(cd "$root/backend" && go build -o "$tmp/backend" .)
TOUCH_LISTEN_ADDR=127.0.0.1:$port TOUCH_SQLITE_PATH="$tmp/touch.db" TOUCH_ENABLE_TEST_API=true TOUCH_TEST_TOKEN="$token" "$tmp/backend" >"$log" 2>&1 & pid=$!
until curl -fsS "http://127.0.0.1:$port/healthz" >/dev/null; do kill -0 "$pid" 2>/dev/null || { cat "$log"; fail "backend failed to start"; }; sleep .1; done
base=http://127.0.0.1:$port; phone=11111111-1111-4111-8111-111111111111; watch=22222222-2222-4222-8222-222222222222; msg=33333333-3333-4333-8333-333333333333
api(){
  local installation=$1 method=$2 path=$3 payload=${4-}
  if [[ -n $payload ]]; then
    curl -sS -w '\n%{http_code}' -H "X-Installation-ID: $installation" -H 'Content-Type: application/json' -X "$method" "$base$path" --data "$payload"
  else
    curl -sS -w '\n%{http_code}' -H "X-Installation-ID: $installation" -H 'Content-Type: application/json' -X "$method" "$base$path"
  fi
}
status(){ tail -n1; }; body(){ sed '$d'; }
[[ $(api '' GET /v1/touches | status) == 401 ]] || fail "authentication"; pass "authentication"
[[ $(api "$phone" PUT "/v1/installations/$phone" '{"username":"api-phone","platform":"phone","fcmToken":""}' | status) == 200 ]] || fail "phone registration"
[[ $(api "$watch" PUT "/v1/installations/$watch" '{"username":"api-watch","platform":"watch","fcmToken":""}' | status) == 200 ]] || fail "watch registration"
conflict=$(api "$watch" PUT "/v1/installations/$watch" '{"username":"api-phone","platform":"watch","fcmToken":""}'); [[ $(printf %s "$conflict" | status) == 409 && $(printf %s "$conflict" | body | jq -r '.error.code') == USERNAME_TAKEN ]] || fail "username conflict"; pass "registration and conflict"
payload='{"clientMessageId":"33333333-3333-4333-8333-333333333333","recipientUsername":"api-watch","samplePeriodMs":10,"amplitudes":[0,32,255]}'
first=$(api "$phone" POST /v1/touches "$payload"); [[ $(printf %s "$first" | status) == 202 ]] || fail "send"; touch=$(printf %s "$first" | body | jq -r .touchId)
duplicate=$(api "$phone" POST /v1/touches "$payload"); [[ $(printf %s "$duplicate" | body | jq -r .duplicate) == true ]] || fail "idempotent duplicate"
bad=$(api "$phone" POST /v1/touches "${payload/255/1}"); [[ $(printf %s "$bad" | status) == 409 && $(printf %s "$bad" | body | jq -r .error.code) == IDEMPOTENCY_CONFLICT ]] || fail "idempotency conflict"; pass "idempotency"
inbox=$(api "$watch" GET '/v1/touches?state=pending&limit=10'); [[ $(printf %s "$inbox" | body | jq -r '.touches[0].clientMessageId') == "$msg" ]] || fail "FIFO inbox"
[[ $(api "$watch" POST "/v1/touches/$touch/ack" '{"state":"persisted"}' | status) == 204 ]] || fail "persisted ack"
[[ $(api "$watch" POST "/v1/touches/$touch/ack" '{"state":"played"}' | status) == 204 ]] || fail "played ack"; pass "FIFO and monotonic acknowledgements"
echo "[PASS] API contract suite; artifacts: $results"
