#!/usr/bin/env bash
# check-terminal-states.sh — the terminal-failure-state detection contract gate.
#
# WHY THIS EXISTS (Phase 27, plan 27-00 — findings F-1, F-2, F-5, F-9)
#
#   A terminal state is one where work has permanently stopped and will not resume.
#   This repo had eleven of them and a detection path for one. Measured 2026-07-27:
#
#     F-2  Four AMQP dead-letter queues, ZERO consumers, and nine real vendor
#          webhook events dead since 2026-07-15 — found by hand, 11 days later,
#          because nothing watched any DLQ depth.
#     F-1  The one messaging alert that existed (StompBrokerLag) could not fire:
#          its selector matched 0 series while the rule reported health=ok. A
#          rule that is syntactically valid and permanently inactive is worse
#          than no rule, because it reads as coverage.
#     F-5  14 live alerts vs 10 runbook sections. Four alerts fire into a page
#          that does not tell you what to do.
#
#   The common shape is not "a bug was missed". It is that nothing connected the
#   code that can stop to the alert that would say so to the runbook that says
#   what to do. This gate is those three connections, executable.
#
# THE THREE CROSS-REFERENCES
#
#   X-1 discovery -> register. Every terminal state found in the declared source
#       surface must have a row in docs/ops/terminal-states.yaml. A new one with
#       no row is the headline failure: a terminal failure state was added with
#       no detection path.
#   X-2 register -> alert. Every named detection.alert must exist in alerts.yml,
#       OR be covered by an UNEXPIRED deferred block — so a deferral cannot
#       quietly become permanent.
#
#       DELIBERATE DEVIATION FROM 27-00-PLAN.md, and why. Task 3's wording at
#       :1146 says "Every non-null detection.alert must appear as - alert: <name>
#       in alerts.yml. A null alert requires an unexpired deferred block." But
#       Task 2 at :1091 instructs the opposite shape: "detection.alert names an
#       alert 27-03 will create. Since none exist yet, every such row MUST carry
#       deferred". Those cannot both hold. Implemented literally, X-2 fails 12 of
#       16 rows on a CORRECT tree — a criterion whose satisfaction is impossible
#       given its sibling task's instruction.
#
#       Resolved in favour of Task 2, because the alternative — forcing
#       alert: null on every pending row — would DELETE the forward reference
#       naming which alert 27-03 has to build, making the register less useful
#       precisely where it is meant to be a work item. Three states are therefore
#       distinguished, and PENDING is reported separately so it can never be read
#       as coverage:
#         alert exists                      -> satisfied
#         alert named + unexpired deferral  -> PENDING (counted, not a violation)
#         alert named + no/expired deferral -> VIOLATION
#         alert null  + no deferral         -> VIOLATION
#       The anti-rot property is untouched: every PENDING row still dies on its
#       own expires date.
#   X-3 register -> runbook. Every non-null detection.alert must have a
#       "## <AlertName>" section in docs/runbooks/alerts.md, AND every live alert
#       in alerts.yml must have one (the F-5 direction).
#
#   X-3 IS EXPECTED TO FAIL ON THIS TREE. 27-03 writes the four missing sections
#   (KeycloakDown, PaymentFailureSpike, RedisDown, StompBrokerLag). Exit 1 here
#   is the correct, intended result until it lands — not a broken gate.
#
# THE DECLARED DISCOVERY SURFACE — declared, never inferred, never repo-wide
#
#   D-1 AMQP DLQs        core-java/.../config/RabbitMQConfig.java
#                        ^\s*public static final String [A-Z_]*DLQ[A-Z_]* = "..."
#   D-2 poison outboxes  core-java/src/main/resources/db/migration/*.sql
#                        poison BOOLEAN | ADD COLUMN poison
#   D-3 terminal entity  five declared files (see D3_FILES below)
#       statuses         enum constants FAILED / AUTO_PAUSED
#   D-4 scrape targets   infra/monitoring/prometheus/prometheus.yml.tmpl
#                        job_name: values
#
#   NOT COVERED BY D-3. These files hold terminal states and are NOT covered by
#   D-3. A terminal state added to any of them will not be caught here:
#     core-java/src/main/java/uk/jtoye/core/order/PaymentStatus.java
#     core-java/src/main/java/uk/jtoye/core/onboarding/GateStatus.java
#     core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEvent.java
#     core-java/src/main/java/uk/jtoye/core/tenant/TenantLifecycleService.java
#     core-java/src/main/java/uk/jtoye/core/security/TenantStatusInterceptor.java
#   This limitation is executed, not merely asserted: adding a FAILED constant to
#   PaymentStatus.java produces NO failure here, and that silence is recorded as
#   evidence in the plan SUMMARY (AC-3.3b). Extending the surface is a deliberate
#   act; un-defer it when a terminal state is added to one of these five.
#
#   SELF-EXCLUSION. This script necessarily NAMES the strings it forbids-without-
#   detection (FAILED, AUTO_PAUSED, the DLQ names). A repo-wide scan would
#   therefore match its own text and report itself — the classic "a doc rule that
#   must name the string it forbids" trap. Every discovery grep below is pinned
#   to its declared source path for that reason. Do not widen one to a repo-wide
#   scan to "catch more"; it will catch this file.
#
#   D-4 is a CLASS check, not a per-job one, and says so honestly: a new scrape
#   job does not create a new terminal state — ServiceDown's `up == 0` has no job
#   selector and covers every target generically. D-4 therefore asserts the job
#   set is non-empty (an empty one means the parser broke) and that an
#   infra_target row exists. It does not demand a row per job.
#
# EXIT CODES — uniform across this plan's gates
#   0 = clean · 1 = contract violation · 2 = VOID (could not evaluate)
#
#   VOID on: missing python3, an unparseable register, or ANY discovery rule
#   returning an EMPTY set. "Found nothing" is NEVER "clean" — an empty result
#   means the regex broke, not that no DLQs exist. Every empty-set VOID names the
#   rule that produced it.
#
# NOTE ON docs/metrics.json: this script contributes 0. docs-freshness.sh counts
# Java @Test methods, Jest/vitest blocks, Go Test funcs and Playwright tests; it
# counts no bash. The expected metrics delta for adding this file is ZERO.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

void() { echo "VOID: $*" >&2; exit 2; }

# ---------------------------------------------------------------- declared surface
CORE="core-java/src/main/java/uk/jtoye/core"

D1_SOURCE="${D1_SOURCE:-$CORE/config/RabbitMQConfig.java}"
D2_GLOB_DIR="${D2_GLOB_DIR:-core-java/src/main/resources/db/migration}"
D4_SOURCE="${D4_SOURCE:-infra/monitoring/prometheus/prometheus.yml.tmpl}"
REGISTER="${REGISTER:-docs/ops/terminal-states.yaml}"
ALERTS="${ALERTS:-infra/monitoring/prometheus/alerts.yml}"
RUNBOOK="${RUNBOOK:-docs/runbooks/alerts.md}"

# D-3's five declared files. Widening this list is a deliberate act — see the
# NOT COVERED note in the header.
D3_FILES=(
  "$CORE/media/MediaAsset.java"
  "$CORE/webhook/WebhookDelivery.java"
  "$CORE/webhook/WebhookSubscription.java"
  "$CORE/payment/PaymentEventOutbox.java"
  "$CORE/media/MediaEventOutbox.java"
)

# ---------------------------------------------------------------- tooling
command -v python3 >/dev/null 2>&1 || void "python3 not on PATH — cannot parse the YAML register"
for f in "$REGISTER" "$ALERTS" "$RUNBOOK" "$D1_SOURCE" "$D4_SOURCE"; do
  [ -r "$f" ] || void "required input not readable: $f"
done
[ -d "$D2_GLOB_DIR" ] || void "D-2 migration directory not readable: $D2_GLOB_DIR"

# ---------------------------------------------------------------- D-1 .. D-4
# Expected-0 discipline: `grep -c` prints 0 and EXITS 1, which under `set -e`
# would kill this script before it reached its own VOID handler. Every capture
# below is therefore `|| true`, and emptiness is then asserted explicitly.

D1_QUEUES=$(command grep -hoE '^[[:space:]]*public static final String [A-Z_]*DLQ[A-Z_]*[[:space:]]*=[[:space:]]*"[^"]+"' "$D1_SOURCE" 2>/dev/null \
            | command sed -E 's/.*"([^"]+)"$/\1/' | sort -u || true)
[ -n "$D1_QUEUES" ] || void "D-1 discovered ZERO DLQ constants in $D1_SOURCE — the pattern broke; this is not 'no DLQs exist'"

D2_FILES=$(command grep -lE '^[[:space:]]*(poison[[:space:]]+BOOLEAN|ADD COLUMN poison)' "$D2_GLOB_DIR"/*.sql 2>/dev/null | sort -u || true)
[ -n "$D2_FILES" ] || void "D-2 discovered ZERO poison-column migrations in $D2_GLOB_DIR — the pattern broke"

# D-3 must match ENUM CONSTANTS ONLY. A bare word-boundary grep also matches
# Javadoc prose ({@link #FAILED}, "set on FAILED"), which would invent states
# that do not exist. Two forms are matched and nothing else:
#   multi-line enum:  a line that is exactly FAILED / AUTO_PAUSED (+ , or ;)
#   inline enum:      enum X { A, B, FAILED }  on one line
D3_PAIRS=""
for f in "${D3_FILES[@]}"; do
  [ -r "$f" ] || void "D-3 declared file not readable: $f"
  multi=$(command grep -hoE '^[[:space:]]*(FAILED|AUTO_PAUSED)[[:space:]]*[,;]?[[:space:]]*$' "$f" 2>/dev/null \
          | command tr -d ' ,;' || true)
  inline=""
  if command grep -qE 'enum[[:space:]]+[A-Za-z_]+[[:space:]]*\{[^}]*\}' "$f" 2>/dev/null; then
    body=$(command grep -hoE 'enum[[:space:]]+[A-Za-z_]+[[:space:]]*\{[^}]*\}' "$f" || true)
    for c in FAILED AUTO_PAUSED; do
      command grep -qE "\b$c\b" <<<"$body" && inline="$inline$c"$'\n'
    done
  fi
  for c in $(printf '%s\n%s\n' "$multi" "$inline" | command grep -vE '^$' | sort -u || true); do
    D3_PAIRS="$D3_PAIRS$f|$c"$'\n'
  done
done
D3_PAIRS=$(printf '%s' "$D3_PAIRS" | command grep -vE '^$' | sort -u || true)
[ -n "$D3_PAIRS" ] || void "D-3 discovered ZERO terminal enum constants across ${#D3_FILES[@]} declared files — the pattern broke"

D4_JOBS=$(command grep -hoE "^[[:space:]]*-[[:space:]]*job_name:[[:space:]]*'?[A-Za-z0-9_-]+'?" "$D4_SOURCE" 2>/dev/null \
          | command sed -E "s/.*job_name:[[:space:]]*'?([A-Za-z0-9_-]+)'?.*/\1/" | sort -u || true)
[ -n "$D4_JOBS" ] || void "D-4 discovered ZERO scrape jobs in $D4_SOURCE — the pattern broke"

# ---------------------------------------------------------------- register
REG_JSON=$(python3 -c '
import sys, json
try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML not importable\n"); sys.exit(3)
try:
    d = yaml.safe_load(open(sys.argv[1]))
except Exception as e:
    sys.stderr.write("unparseable: %s\n" % str(e).splitlines()[0]); sys.exit(3)
if not isinstance(d, dict) or not isinstance(d.get("states"), list) or not d["states"]:
    sys.stderr.write("register has no states list\n"); sys.exit(3)
# default=str: YAML parses an unquoted ISO date into datetime.date, which json
# cannot serialise. Dates round-trip as ISO strings and are re-parsed below.
print(json.dumps(d["states"], default=str))
' "$REGISTER") || void "cannot parse $REGISTER (see message above)"

reg() { python3 -c '
import sys, json, datetime
rows = json.loads(sys.stdin.read())
mode = sys.argv[1]
if mode == "count":
    print(len(rows))
elif mode == "paths":
    for r in rows:
        print(r.get("locator","").rsplit(":",1)[0])
        for c in (r.get("covers") or []):
            print(c.split("#",1)[0])
elif mode == "covers":
    for r in rows:
        for c in (r.get("covers") or []):
            print(c)
elif mode == "names":
    for r in rows: print(r.get("name",""))
elif mode == "kinds":
    for r in rows: print(r.get("kind",""))
elif mode == "alerts":
    for r in rows:
        a = (r.get("detection") or {}).get("alert")
        if a: print("%s|%s|%s" % (r["id"], a, "deferred" if "deferred" in r else "-"))
elif mode == "nullalert_nodefer":
    for r in rows:
        a = (r.get("detection") or {}).get("alert")
        if a is None and "deferred" not in r: print(r["id"])
elif mode == "expired":
    today = datetime.date.today()
    for r in rows:
        d = r.get("deferred")
        if not d: continue
        e = d.get("expires")
        e = e if isinstance(e, datetime.date) else datetime.date.fromisoformat(str(e))
        if e <= today: print("%s|%s" % (r["id"], e))
elif mode == "deferred_count":
    print(sum(1 for r in rows if "deferred" in r))
' "$1" <<<"$REG_JSON"; }

REG_COUNT=$(reg count)
REG_PATHS=$(reg paths)
REG_COVERS=$(reg covers)
REG_NAMES=$(reg names)
REG_KINDS=$(reg kinds)
REG_ALERTS=$(reg alerts)
REG_DEFERRED=$(reg deferred_count)

VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

# ---------------------------------------------------------------- X-1
X1_MISSING=0
while IFS= read -r q; do
  [ -z "$q" ] && continue
  command grep -qxF "$q" <<<"$REG_NAMES" \
    || { violation "X-1 [D-1] terminal state '$q' found in $D1_SOURCE has NO register row in $REGISTER"; X1_MISSING=$((X1_MISSING+1)); }
done <<<"$D1_QUEUES"

while IFS= read -r f; do
  [ -z "$f" ] && continue
  command grep -qxF "$f" <<<"$REG_PATHS" \
    || { violation "X-1 [D-2] poison outbox migration '$f' has NO register row (no row's locator or covers names it)"; X1_MISSING=$((X1_MISSING+1)); }
done <<<"$D2_FILES"

# PAIR-level, not file-level, and DECLARED rather than inferred. File-level
# matching would miss a NEW terminal constant added to a file that already has a
# row — e.g. a FAILED added to WebhookSubscription.java, whose row is about
# AUTO_PAUSED. That is a genuinely new terminal state and must fail. Each owning
# row therefore declares the exact pair it accounts for as `covers: path#CONST`.
# Nothing is inferred from the row's prose, because prose drifts silently.
while IFS= read -r pair; do
  [ -z "$pair" ] && continue
  f=${pair%%|*}; c=${pair##*|}
  command grep -qxF "$f#$c" <<<"$REG_COVERS" \
    || { violation "X-1 [D-3] terminal enum constant '$c' in '$f' has NO register row declaring it (expected a row with covers: $f#$c)"; X1_MISSING=$((X1_MISSING+1)); }
done <<<"$D3_PAIRS"

command grep -qxF 'infra_target' <<<"$REG_KINDS" \
  || { violation "X-1 [D-4] $(command grep -c . <<<"$D4_JOBS") scrape job(s) discovered but the register has no kind: infra_target row"; X1_MISSING=$((X1_MISSING+1)); }

# ---------------------------------------------------------------- X-2
X2_MISSING=0
ALERT_NAMES=$(command grep -hoE '^[[:space:]]*-[[:space:]]*alert:[[:space:]]*[A-Za-z0-9_]+' "$ALERTS" \
              | command sed -E 's/.*alert:[[:space:]]*//' | sort -u || true)
[ -n "$ALERT_NAMES" ] || void "alert-name extraction from $ALERTS returned EMPTY — the pattern broke"

X2_PENDING=0
while IFS= read -r rowspec; do
  [ -z "$rowspec" ] && continue
  IFS='|' read -r id a defer <<<"$rowspec"
  if command grep -qxF "$a" <<<"$ALERT_NAMES"; then
    continue                                   # the alert exists — satisfied
  elif [ "$defer" = "deferred" ]; then
    X2_PENDING=$((X2_PENDING+1))               # named but not yet built, and DATED
  else
    violation "X-2 register row $id names detection.alert '$a', which does not exist in $ALERTS, and carries no deferred block"
    X2_MISSING=$((X2_MISSING+1))
  fi
done <<<"$REG_ALERTS"

while IFS= read -r id; do
  [ -z "$id" ] && continue
  violation "X-2 register row $id has detection.alert: null and NO deferred block — an absent detection path must be dated and owned"
  X2_MISSING=$((X2_MISSING+1))
done <<<"$(reg nullalert_nodefer)"

X2_EXPIRED=0
while IFS= read -r pair; do
  [ -z "$pair" ] && continue
  violation "X-2 register row ${pair%%|*} has an EXPIRED deferral (expires ${pair##*|}) — a deferral cannot become permanent"
  X2_EXPIRED=$((X2_EXPIRED+1))
done <<<"$(reg expired)"

# ---------------------------------------------------------------- X-3
# The two DiskSpace* rules are commented out with a stated reason (node-exporter
# is not deployed). An extractor that counts them demands SIX sections instead of
# four and would over-demand on 27-03's tree. The `^\s*- alert:` anchor excludes
# them because their lines begin with '#'. SKIP_DISKSPACE=0 disables that anchor
# for the AC-3.6 break arm, which proves the skip is load-bearing rather than
# incidental.
SKIP_DISKSPACE="${SKIP_DISKSPACE:-1}"
if [ "$SKIP_DISKSPACE" = "1" ]; then
  LIVE_ALERTS="$ALERT_NAMES"
else
  LIVE_ALERTS=$(command grep -hoE '^[[:space:]]*#?[[:space:]]*-[[:space:]]*alert:[[:space:]]*[A-Za-z0-9_]+' "$ALERTS" \
                | command sed -E 's/.*alert:[[:space:]]*//' | sort -u || true)
fi

RUNBOOK_SECTIONS=$(command grep -hoE '^##[[:space:]]+[A-Za-z0-9_]+' "$RUNBOOK" | command sed -E 's/^##[[:space:]]+//' | sort -u || true)
[ -n "$RUNBOOK_SECTIONS" ] || void "runbook section extraction from $RUNBOOK returned EMPTY — the pattern broke"

X3_MISSING=0
while IFS= read -r a; do
  [ -z "$a" ] && continue
  command grep -qxF "$a" <<<"$RUNBOOK_SECTIONS" \
    || { violation "X-3 live alert '$a' has no '## $a' section in $RUNBOOK"; X3_MISSING=$((X3_MISSING+1)); }
done <<<"$LIVE_ALERTS"

# ---------------------------------------------------------------- summary
D1_N=$(command grep -c . <<<"$D1_QUEUES" || true)
D2_N=$(command grep -c . <<<"$D2_FILES" || true)
D3_N=$(command grep -c . <<<"$D3_PAIRS" || true)
D4_N=$(command grep -c . <<<"$D4_JOBS" || true)
ALERT_N=$(command grep -c . <<<"$LIVE_ALERTS" || true)
RB_N=$(command grep -c . <<<"$RUNBOOK_SECTIONS" || true)

echo "terminal-states classification summary"
echo "  discovery   D-1 dlq=$D1_N  D-2 poison-migrations=$D2_N  D-3 entity-statuses=$D3_N  D-4 scrape-jobs=$D4_N"
echo "  register    rows=$REG_COUNT  with-alert=$(command grep -c . <<<"$REG_ALERTS" || true)  deferred=$REG_DEFERRED"
echo "  alerts.yml  live-alerts=$ALERT_N (DiskSpace* skip=$SKIP_DISKSPACE)   runbook sections=$RB_N"
echo "  pending     X-2 alert named but not yet built (dated deferral)=$X2_PENDING  <-- NOT coverage"
echo "  violations  X-1 missing-row=$X1_MISSING  X-2 missing-alert=$X2_MISSING expired-deferral=$X2_EXPIRED  X-3 missing-runbook=$X3_MISSING"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo "FAILED: $VIOLATIONS contract violation(s). X-3 is EXPECTED to fail until 27-03 lands the four missing runbook sections." >&2
  exit 1
fi

echo "PASS: $D1_N DLQ(s), $D2_N poison outbox migration(s), $D3_N terminal entity status(es) and $D4_N scrape job(s) all map to $REG_COUNT register rows ($REG_DEFERRED dated deferral(s), 0 expired); every named alert exists in $ALERTS and every live alert has a runbook section."
