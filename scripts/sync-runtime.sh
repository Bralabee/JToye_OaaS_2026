#!/usr/bin/env bash
# sync-runtime.sh — rebuild exactly the services the parity gate says are stale, then
# prove it worked with the SAME gate.
#
# WHY THIS EXISTS
#
#   The remedy for runtime drift was a three-step ritual nobody performed after a
#   routine `git pull`: read which services drifted, rebuild those, re-verify. #380
#   left core-java running a four-hour-old image through two subsequent PRs because
#   the ritual was never run. `.githooks/post-merge` now DETECTS that; this is the
#   one command it points at.
#
# THE LOOP IS CLOSED BY THE SAME JUDGE
#
#   The gate decides what is stale, and the gate decides whether the rebuild worked.
#   There is no second opinion to disagree with, and no way for this script to
#   declare success over a runtime the gate would still call stale.
#
# THE PARSE IS ASSERTED, NOT TRUSTED
#
#   check-runtime-freshness.sh has no machine-readable mode, so the drifted service
#   names are read from its output. A parse that silently yields nothing would make
#   this script rebuild NOTHING and then re-run the gate, which would fail again —
#   confusing, but survivable. Worse would be parsing nothing and reporting success.
#   So: if the gate says DRIFT and the parse finds zero names, that is a VOID (exit
#   2), not "nothing to do". A truncating or mismatched filter must never manufacture
#   an absence.
#
# EXIT CODES:  0 = runtime matches the tree · 1 = rebuild ran and drift REMAINS · 2 = VOID

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GATE="$REPO_ROOT/scripts/check-runtime-freshness.sh"
COMPOSE_FILE="${COMPOSE_FILE:-$REPO_ROOT/docker-compose.full-stack.yml}"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"

void() { echo "VOID: $*" >&2; exit 2; }

[ -r "$GATE" ]         || void "gate not found at $GATE"
[ -r "$COMPOSE_FILE" ] || void "compose file not found at $COMPOSE_FILE"
command -v docker >/dev/null 2>&1 || void "docker not found"

# Compose derives its project name from the DIRECTORY, so from a worktree this would
# address an empty project namespace and "rebuild" nothing while reporting success.
if [ "$(git -C "$REPO_ROOT" rev-parse --git-common-dir)" != "$(git -C "$REPO_ROOT" rev-parse --git-dir)" ]; then
    void "running from a git worktree — compose would address an empty project namespace. Run from the main checkout."
fi

echo "sync-runtime: asking the gate what is stale"
GATE_OUT="$(cd "$REPO_ROOT" && bash "$GATE" 2>&1)"
GATE_RC=$?

if [ "$GATE_RC" -eq 0 ]; then
    echo "$GATE_OUT" | grep -E '^\s+\S+\s+(FRESH|DRIFT)\b' || true
    echo "sync-runtime: nothing to do — the runtime already matches the tree"
    exit 0
fi

if [ "$GATE_RC" -eq 2 ]; then
    printf '%s\n' "$GATE_OUT" >&2
    void "the gate could not evaluate parity (exit 2). A stopped service cannot be proven stale OR fresh. Bring the stack up first:
  docker compose -f $COMPOSE_FILE --env-file $ENV_FILE up -d"
fi

# rc=1 -> real drift. Extract the service names.
DRIFTED="$(printf '%s\n' "$GATE_OUT" | awk '$2 == "DRIFT" { print $1 }' | sort -u)"

if [ -z "$DRIFTED" ]; then
    printf '%s\n' "$GATE_OUT" >&2
    void "the gate reported drift (exit 1) but no service name could be parsed from its output. Refusing to report success over an unparsed result — the output format may have changed."
fi

echo "sync-runtime: rebuilding ->" $DRIFTED

ENV_ARGS=()
[ -r "$ENV_FILE" ] && ENV_ARGS=(--env-file "$ENV_FILE")

# up -d --build, never start/restart: neither builds, and neither replaces a container
# that is holding an older image ID.
(cd "$REPO_ROOT" && docker compose -f "$COMPOSE_FILE" "${ENV_ARGS[@]}" up -d --build $DRIFTED)
BUILD_RC=$?
[ "$BUILD_RC" -eq 0 ] || void "docker compose up -d --build exited $BUILD_RC — not re-checking parity over a failed build"

echo
echo "sync-runtime: re-asserting parity with the same gate"
VERIFY_OUT="$(cd "$REPO_ROOT" && bash "$GATE" 2>&1)"
VERIFY_RC=$?
printf '%s\n' "$VERIFY_OUT" | grep -E '^\s+\S+\s+(FRESH|DRIFT)\b' || true

case "$VERIFY_RC" in
    0) echo "sync-runtime: PASS — the running stack now matches the tree" ; exit 0 ;;
    2) printf '%s\n' "$VERIFY_OUT" >&2
       void "parity is UNVERIFIABLE after the rebuild (exit 2) — a service did not come back up. This is NOT a pass." ;;
    *) printf '%s\n' "$VERIFY_OUT" >&2
       echo "FAIL: drift REMAINS after rebuilding:" $DRIFTED >&2
       exit 1 ;;
esac
