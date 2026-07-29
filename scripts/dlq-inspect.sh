#!/usr/bin/env bash
# dlq-inspect.sh — read-only operator triage for RabbitMQ dead-letter queues.
#
# WHY THIS IS A CLI AND NOT AN HTTP ENDPOINT (phase 27, plan 27-03, D-06)
#
#   A DLQ is a SINGLE queue holding EVERY tenant's event payloads, and the tenant identity lives in
#   the JSON body rather than in a header. AMQP `basic.get` is FIFO, so there is no way to fetch
#   "only my tenant's messages" — any inspection endpoint would have to pull other tenants' payloads
#   into the JVM and filter them there. This platform has NO cross-tenant operator identity (every
#   admin surface resolves WITHIN a tenant), so such an endpoint would be a cross-tenant read path
#   with no caller authorised to use it.
#
#   So the inspection path is this script: it runs on the operator's own machine, over the broker
#   management API, gated by broker credentials that are already operator-only. No new HTTP surface,
#   no new auth gate, and it works when core-java is the thing that is down.
#
#   REDRIVE IS DELIBERATELY NOT HERE. See docs/runbooks/alerts.md (## DeadLetterQueueNonEmpty) for
#   the manual procedure and the phase's deferred-items.md for the trigger that would justify
#   automating it.
#
# ITS OUTPUT IS TENANT DATA
#   `--peek` prints message payloads containing tenantId, orderId and orderNumber. Do not paste it
#   into a shared channel, an issue, or a pull request. Treat your terminal buffer accordingly.
#
# IT CANNOT CONSUME. `reject_requeue_true` is HARDCODED and is not reachable from any argument.
#   The destructive ackmode (`get`) removes the message permanently; on a DLQ the payload is the
#   only remaining copy of the event, so the safe mode is not a default here, it is the only mode.
#
# EXIT CODES — uniform across this plan's gates
#   0 = every DLQ empty · 1 = at least one DLQ non-empty · 2 = VOID (could not evaluate)
#   VOID on: missing jq/curl, an unreadable credentials file, missing credentials, or an
#   unreachable/unparseable management API. It NEVER falls back to guest:guest and never proceeds
#   anonymously — "could not check" must not share an exit code with "checked and clean".
#
#   Exit 1 on a non-empty DLQ makes this usable as a pre-release check, not only as triage.
#
# CREDENTIALS
#   Read from the environment: RABBITMQ_DEFAULT_USER / RABBITMQ_DEFAULT_PASS.
#   If either is unset, read from ${RMQ_ENV_FILE:-$REPO_ROOT/.env}. If that file is missing or
#   unreadable, VOID. A password is never accepted as an argument (shell history, and `ps` shows
#   the whole command line) and is never echoed.
#
# USAGE
#   bash scripts/dlq-inspect.sh --list              all queues, depth and consumer count
#   bash scripts/dlq-inspect.sh --summary           the DLQ depths only; exit 1 if any is non-empty
#   bash scripts/dlq-inspect.sh --peek <queue> [n]  decode up to n messages WITHOUT consuming
#
#   RABBITMQ_API=http://host:15672/api   override the management API base
#   RMQ_ENV_FILE=/path/to/.env           override the credentials file
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RABBITMQ_API="${RABBITMQ_API:-http://localhost:15672/api}"
RMQ_ENV_FILE="${RMQ_ENV_FILE:-$REPO_ROOT/.env}"
VHOST_ENC="%2F"

void() { echo "VOID: $*" >&2; exit 2; }
usage() { sed -n '/^# USAGE/,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//;$d' >&2; }

command -v jq   >/dev/null 2>&1 || void "jq not found — the management API response cannot be parsed, so nothing was checked"
command -v curl >/dev/null 2>&1 || void "curl not found — the management API cannot be reached, so nothing was checked"

# ---------------------------------------------------------------------------------
# Credentials. Every failure here is VOID, never a guest:guest fallback.
# ---------------------------------------------------------------------------------
RMQ_USER="${RABBITMQ_DEFAULT_USER:-}"
RMQ_PASS="${RABBITMQ_DEFAULT_PASS:-}"

if [ -z "$RMQ_USER" ] || [ -z "$RMQ_PASS" ]; then
  [ -r "$RMQ_ENV_FILE" ] \
    || void "credentials file not readable: $RMQ_ENV_FILE (and RABBITMQ_DEFAULT_USER/_PASS are not both set in the environment). Refusing to fall back to guest:guest."
  [ -z "$RMQ_USER" ] && RMQ_USER="$(sed -n 's/^RABBITMQ_DEFAULT_USER=//p' "$RMQ_ENV_FILE" | head -1)"
  [ -z "$RMQ_PASS" ] && RMQ_PASS="$(sed -n 's/^RABBITMQ_DEFAULT_PASS=//p' "$RMQ_ENV_FILE" | head -1)"
fi
[ -n "$RMQ_USER" ] || void "RABBITMQ_DEFAULT_USER is empty (env and $RMQ_ENV_FILE)"
[ -n "$RMQ_PASS" ] || void "RABBITMQ_DEFAULT_PASS is empty (env and $RMQ_ENV_FILE)"

# ---------------------------------------------------------------------------------
# api_get <path>  — VOIDs on transport failure or an unparseable body. An empty or
# non-JSON response must never be read as "no queues", which would report clean.
# ---------------------------------------------------------------------------------
api_get() {
  local path="$1" body
  body="$(curl -s --max-time 20 -u "$RMQ_USER:$RMQ_PASS" "$RABBITMQ_API$path" 2>/dev/null)" \
    || void "management API unreachable at $RABBITMQ_API$path"
  [ -n "$body" ] || void "EMPTY response from $RABBITMQ_API$path — the API was reached but said nothing, which is not 'no queues'"
  jq -e . >/dev/null 2>&1 <<<"$body" \
    || void "unparseable response from $RABBITMQ_API$path"
  # SHAPE CHECK, and it is load-bearing. Bad credentials return VALID JSON here —
  # {"error":"not_authorised","reason":"..."} — so a bare `jq -e .` accepts it, and the queue
  # filter then dies with "Cannot index string with string" and an exit code of 5. Measured.
  # An authentication failure must VOID, not leak a jq error and not read as "no queues".
  jq -e 'type == "array"' >/dev/null 2>&1 <<<"$body" \
    || void "$RABBITMQ_API$path did not return a queue LIST: $(jq -r '.error // .reason // "unexpected shape"' <<<"$body" 2>/dev/null). Bad credentials look exactly like this."
  printf '%s' "$body"
}

cmd_list() {
  local body; body="$(api_get "/queues/$VHOST_ENC")"
  local n; n="$(jq -r 'length' <<<"$body")"
  [ "$n" -gt 0 ] || void "the broker reports ZERO queues — that is not a clean result, it is a broker or vhost problem"
  jq -r '.[] | "\(.name)\tmsgs=\(.messages)\tconsumers=\(.consumers)"' <<<"$body" | sort
  echo "  $n queue(s)"
}

cmd_summary() {
  local body; body="$(api_get "/queues/$VHOST_ENC")"
  local dlqs; dlqs="$(jq -r '[.[] | select(.name | endswith(".dlq"))] | length' <<<"$body")"
  [ "$dlqs" -gt 0 ] \
    || void "no queue whose name ends in .dlq exists — either the topology is not declared yet or the filter is wrong. Reporting 'no dead letters' from an empty discovery would be a false all-clear."

  jq -r '.[] | select(.name | endswith(".dlq")) | "\(.name)\t\(.messages)\t\(.consumers)"' <<<"$body" \
    | sort | while IFS=$'\t' read -r name msgs consumers; do
        printf '  %-28s msgs=%-6s consumers=%s\n' "$name" "$msgs" "$consumers"
      done

  local total; total="$(jq -r '[.[] | select(.name | endswith(".dlq")) | .messages] | add' <<<"$body")"
  echo "  $dlqs dead-letter queue(s), $total message(s) parked in total"
  if [ "$total" -gt 0 ]; then
    echo "NON-EMPTY: archive before any purge — the payload is the only remaining copy of the event," >&2
    echo "           and it carries tenant data. See docs/runbooks/alerts.md ## DeadLetterQueueNonEmpty." >&2
    return 1
  fi
  echo "PASS: every dead-letter queue is empty."
  return 0
}

cmd_peek() {
  local queue="${1:-}" count="${2:-5}"
  [ -n "$queue" ] || { echo "ERROR: --peek needs a queue name" >&2; usage; exit 2; }
  case "$count" in ''|*[!0-9]*) echo "ERROR: message count must be a positive integer, got '$count'" >&2; exit 2 ;; esac
  # REJECT extra arguments rather than ignoring them. Silently swallowing `--ackmode get` would
  # tell an operator who typed it that it was accepted; it must be visibly refused instead. (The
  # destructive mode is unreachable either way, but "ignored" and "refused" look identical from
  # the exit code, and only one of them is honest.)
  shift 2 2>/dev/null || shift $#
  [ "$#" -eq 0 ] || { echo "ERROR: unexpected argument(s) after --peek <queue> [n]: $* — this script has NO ackmode option; reject_requeue_true is hardcoded and the destructive mode is deliberately unreachable" >&2; exit 2; }

  # ackmode is HARDCODED. reject_requeue_true returns each message to the queue immediately;
  # "get" would remove it permanently. There is deliberately no way to reach the destructive mode
  # from this script's arguments — see the header.
  local body
  body="$(curl -s --max-time 20 -u "$RMQ_USER:$RMQ_PASS" -H 'content-type:application/json' \
      -X POST "$RABBITMQ_API/queues/$VHOST_ENC/$queue/get" \
      -d "{\"count\":$count,\"ackmode\":\"reject_requeue_true\",\"encoding\":\"auto\",\"truncate\":50000}" 2>/dev/null)" \
    || void "management API unreachable while peeking $queue"
  [ -n "$body" ] || void "EMPTY response while peeking $queue"
  jq -e . >/dev/null 2>&1 <<<"$body" || void "unparseable response while peeking $queue: $body"

  local n; n="$(jq -r 'if type == "array" then length else 0 end' <<<"$body")"
  echo "  peeked $n message(s) from $queue (non-destructive: reject_requeue_true)"
  jq -r '.[] | {
      reason:        .properties.headers["x-first-death-reason"],
      from_exchange: .properties.headers["x-death"][0].exchange,
      from_queue:    .properties.headers["x-death"][0].queue,
      routing_key:   .properties.headers["x-death"][0]["routing-keys"][0],
      dead_letterings: .properties.headers["x-death"][0].count,
      died_at:       (.properties.headers["x-death"][0].time | todate),
      type:          .properties.headers["__TypeId__"]
    }' <<<"$body"
  echo "  REDRIVE TARGET is from_exchange + routing_key above — NOT the message's own 'exchange'"
  echo "  field, which is the dead-letter exchange. Republishing there sends it straight back."
  echo "  dead_letterings counts DEAD-LETTERINGS, not delivery attempts: the retry interceptor"
  echo "  retries in-process, so a message that failed 3 times still reads 1 here."
  return 0
}

case "${1:-}" in
  --list)    shift; cmd_list ;;
  --summary) shift; cmd_summary ;;
  --peek)    shift; cmd_peek "$@" ;;
  -h|--help) usage; exit 0 ;;
  "")        echo "ERROR: one of --list, --summary, --peek is required" >&2; usage; exit 2 ;;
  *)         echo "ERROR: unknown argument '$1'" >&2; usage; exit 2 ;;
esac
