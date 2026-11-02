#!/usr/bin/env bash
# Quick 261102-vwa runtime probe: the BUILT Next server (frontend/.next) against a stub core
# that records what each server-side upstream read carried.
# Usage (from the repo root): ssr-xff-probe.sh <label>   ->  evidence/probe-<label>.jsonl
# Ports 3310 (Next) and 19190 (stub) only. Never 3000/9090 (the live compose stack).
set -u
label=${1:?label}
EV=.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence
NEXT_PORT=3310
STUB_PORT=19190
LOG=$(mktemp "${TMPDIR:-/tmp}/ssr-xff-probe.XXXXXX.jsonl")

listening() { ss -ltnH "sport = :$1" 2>/dev/null | /usr/bin/grep -c LISTEN; }
for p in $NEXT_PORT $STUB_PORT; do
  n=$(listening "$p")
  if [ "$n" != "0" ]; then echo "VOID: port $p already listening" >&2; exit 2; fi
done
[ -f frontend/.next/BUILD_ID ] || { echo "VOID: no build in frontend/.next" >&2; exit 2; }

STUB_PID=""; NEXT_PID=""
cleanup() {
  rc=$?
  [ -n "$NEXT_PID" ] && kill -TERM -- "-$NEXT_PID" 2>/dev/null
  [ -n "$STUB_PID" ] && kill -TERM -- "-$STUB_PID" 2>/dev/null
  for _ in $(seq 1 50); do
    a=$(listening $NEXT_PORT); b=$(listening $STUB_PORT)
    [ "$a" = "0" ] && [ "$b" = "0" ] && break
    sleep 0.2
  done
  [ -n "$NEXT_PID" ] && kill -KILL -- "-$NEXT_PID" 2>/dev/null
  [ -n "$STUB_PID" ] && kill -KILL -- "-$STUB_PID" 2>/dev/null
  sleep 0.5
  a=$(listening $NEXT_PORT); b=$(listening $STUB_PORT)
  if [ "$a" != "0" ] || [ "$b" != "0" ]; then
    echo "CLEANUP FAILED: 3310 listening=$a 19190 listening=$b" >&2; exit 3
  fi
  echo "cleanup: ports $NEXT_PORT and $STUB_PORT free"
  exit "$rc"
}
trap cleanup EXIT

setsid node "$EV/stub-core.mjs" $STUB_PORT "$LOG" > "$EV/probe-$label-stub.out" 2>&1 &
STUB_PID=$!
(
  cd frontend && \
  CORE_API_INTERNAL_URL=http://127.0.0.1:$STUB_PORT \
  NEXT_PUBLIC_API_URL=http://127.0.0.1:$STUB_PORT \
  NEXTAUTH_URL=http://127.0.0.1:$NEXT_PORT \
  NEXTAUTH_SECRET=ci-placeholder-not-a-real-secret \
  NEXT_TELEMETRY_DISABLED=1 \
  exec setsid npx next start -p $NEXT_PORT -H 127.0.0.1
) > "$EV/probe-$label-next.out" 2>&1 &
NEXT_PID=$!

deadline=$(( $(date +%s) + 90 ))
ready=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  s=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$NEXT_PORT/shop" 2>/dev/null)
  if [ "$s" = "200" ]; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != "1" ]; then echo "VOID: /shop never answered 200 within 90s" >&2; exit 2; fi

B=http://127.0.0.1:$NEXT_PORT
COOKIE='jtoye-customer-access=probe-token'
mark() { curl -s -o /dev/null "http://127.0.0.1:$STUB_PORT/__mark/$1"; }
status_log="$EV/probe-$label-status.txt"; : > "$status_log"
req() { # case chain(or -) curl args...
  local c=$1 chain=$2; shift 2
  mark "$c"
  local s
  if [ "$chain" = "-" ]; then
    s=$(curl -s -o /dev/null -w '%{http_code}' "$@")
  else
    s=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Forwarded-For: $chain" "$@")
  fi
  echo "$c $s" | tee -a "$status_log"
}
req landing  '192.0.2.66, 198.51.100.1' "$B/"
req shop     '198.51.100.2'             "$B/shop"
req slug     '192.0.2.66,198.51.100.3'  "$B/shop/probe-kitchen"
req cart     '198.51.100.4'             "$B/shop/probe-kitchen/cart"
req sitemap  '198.51.100.5'             "$B/sitemap.xml"
req orders   '198.51.100.6'             -H "Cookie: $COOKIE" "$B/shop/orders"
req api-get  '198.51.100.7'             -H "Cookie: $COOKIE" "$B/api/customer-orders?size=5"
req api-post '198.51.100.8'             -X POST -H "Cookie: $COOKIE" -H 'Content-Type: application/json' \
                                         -H 'Idempotency-Key: probe-1' --data '{}' \
                                         "$B/api/customer-orders?shop=probe-kitchen"
req none     -                          "$B/shop"
mark end
sleep 0.5
cp "$LOG" "$EV/probe-$label.jsonl"
rm -f "$LOG"
echo "log: $EV/probe-$label.jsonl ($(wc -l < "$EV/probe-$label.jsonl") lines)"
