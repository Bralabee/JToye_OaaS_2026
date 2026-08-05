#!/bin/bash
# Stop J'Toye OaaS Development Environment
#
# WHY THIS TEARS DOWN TWO RUNTIMES
#
#   This repo runs locally in two different shapes, and they are not the same stack:
#
#     1. HYBRID — what `scripts/start-dev.sh` starts: `infra/` compose (Postgres +
#        Keycloak) in containers, with the backend (`./gradlew :core-java:bootRun`)
#        and frontend (`npm run dev`) as HOST processes.
#     2. FULL STACK — `docker-compose.full-stack.yml`, compose project
#        `jtoye_oaas_2026`, all services in containers. CLAUDE.md calls this the
#        canonical local dev + E2E runtime; it is what Playwright runs against.
#
#   This script used to know only about (1). Run against (2) it killed host processes
#   that were not running, ran `docker compose down` in a project with no containers,
#   and printed "All services stopped" while all 11 containers kept running. Measured
#   2026-08-05: 11 running before, 11 after, success banner in between.
#
#   Re-pointing it at the full-stack file instead would have dropped the teardown that
#   pairs with start-dev.sh — trading one gap for another. Both are handled now, and a
#   runtime that is not present is ANNOUNCED and skipped, never treated as an error.
#
# THE BANNER IS NOW A CONSEQUENCE, NOT A PRINTF
#
#   Every teardown is followed by a check that counts surviving containers BY LABEL,
#   not by compose file. Two reasons. A file-based check cannot see containers whose
#   config path has since gone (the `monitoring` project on this machine has its
#   compose file inside a deleted worktree). And label-based counting still works when
#   the file path is wrong — which is what lets the fail arm of the test exist at all.
#
# NO `-v`, ANYWHERE
#
#   Named volumes (Postgres data, MinIO objects, Keycloak realm state) survive.
#   Stopping the stack must never be a data-destroying operation.
#
# EXIT CODES:  0 = everything that was running is stopped · 1 = something survived

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Injected, not hardcoded (GLOBAL_RULE_6): every one of these varies by checkout.
FULL_STACK_COMPOSE="${FULL_STACK_COMPOSE:-$REPO_ROOT/docker-compose.full-stack.yml}"
INFRA_DIR="${INFRA_DIR:-$REPO_ROOT/infra}"
# Compose derives the project name from the directory; lowercase is the whole of the
# normalisation for this repo's name. Overridable for worktrees, where the directory
# differs and the label would not match.
FULL_STACK_PROJECT="${FULL_STACK_PROJECT:-$(basename "$REPO_ROOT" | tr '[:upper:]' '[:lower:]')}"
INFRA_PROJECT="${INFRA_PROJECT:-$(basename "$INFRA_DIR" | tr '[:upper:]' '[:lower:]')}"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

echo "🛑 Stopping J'Toye OaaS Development Environment"
echo "================================================"

# How many containers are still running for a compose project. Label-based on purpose;
# see the header. Prints a number, always — an empty result is 0, not an error.
running_count() {
    docker ps --filter "label=com.docker.compose.project=$1" --format '{{.ID}}' 2>/dev/null | wc -l
}

if ! command -v docker >/dev/null 2>&1; then
    echo -e "${RED}✗ docker not found — cannot stop or verify container runtimes${NC}" >&2
    exit 1
fi

# ---------------------------------------------------------------- host processes (1)
# `pkill -f PATTERN` matches the FULL COMMAND LINE of every process, so it kills any
# shell that merely MENTIONS the pattern. This is not hypothetical: on 2026-08-05 the
# previous `pkill -9 -f "next-server"` SIGKILLed the very shell that was testing this
# script, because the test command contained that string in an echo. Output vanished
# and the run died with no diagnostic — indistinguishable from a hang.
#
# The bracket trick does NOT help here: `[n]ext-server` still matches the text
# "next-server" wherever it appears. So each candidate is filtered explicitly:
#   - never this process,
#   - never one of its ancestors (the invoking shell, and the terminal above it),
#   - never a process inside a container (the host PID namespace can see those, and
#     they are torn down by `compose down`, not by signals from the host).
#
# Still broad in one respect, deliberately: it matches these processes for any
# checkout on this machine. Narrowing to this repo would risk failing to stop what
# start-dev.sh started, which is the job. Recorded as a follow-up.
is_ancestor() {
    local target="$1" p=$$
    while [ "$p" -gt 1 ]; do
        [ "$p" = "$target" ] && return 0
        p=$(awk '/^PPid:/{print $2}' "/proc/$p/status" 2>/dev/null) || return 1
        [ -z "$p" ] && return 1
    done
    return 1
}

# ONE definition of "a host process we own", used by BOTH the kill and the verify.
# They were briefly separate and drifted immediately: the verifier kept matching the
# invoking shell and reported a survivor that was the check itself.
matching_host_pids() {
    local pat="$1" pid out=""
    for pid in $(pgrep -f "$pat" 2>/dev/null); do
        [ "$pid" = "$$" ] && continue
        is_ancestor "$pid" && continue
        grep -qE '(docker|containerd|kubepods|libpod)' "/proc/$pid/cgroup" 2>/dev/null && continue
        out="$out $pid"
    done
    echo "$out"
}

kill_host_procs() {
    local pat="$1" pid killed=0
    for pid in $(matching_host_pids "$pat"); do
        kill -9 "$pid" 2>/dev/null && killed=$((killed + 1))
    done
    [ "$killed" -gt 0 ] && echo "  killed $killed process(es) matching: $pat"
    return 0
}

echo -e "\n${YELLOW}Stopping host processes (hybrid mode: start-dev.sh)${NC}"
kill_host_procs "npm run dev"
kill_host_procs "node.*next.*dev"
kill_host_procs "next-server"
kill_host_procs "gradlew"
kill_host_procs "java.*CoreApplication"
sleep 2

# ------------------------------------------------------------- full-stack compose (2)
echo -e "\n${YELLOW}Stopping full stack (project: $FULL_STACK_PROJECT)${NC}"
if [ "$(running_count "$FULL_STACK_PROJECT")" -eq 0 ]; then
    echo "  not running — nothing to stop"
elif [ -r "$FULL_STACK_COMPOSE" ]; then
    (cd "$REPO_ROOT" && docker compose -f "$FULL_STACK_COMPOSE" down)
else
    echo -e "${RED}  compose file not readable: $FULL_STACK_COMPOSE${NC}" >&2
    echo -e "${RED}  containers are RUNNING and cannot be stopped from here${NC}" >&2
fi

# ------------------------------------------------------------------ infra compose (1)
echo -e "\n${YELLOW}Stopping infrastructure (project: $INFRA_PROJECT)${NC}"
if [ "$(running_count "$INFRA_PROJECT")" -eq 0 ]; then
    echo "  not running — nothing to stop"
elif [ -d "$INFRA_DIR" ]; then
    (cd "$INFRA_DIR" && docker compose down)
else
    echo -e "${RED}  infra directory not found: $INFRA_DIR${NC}" >&2
fi

# ------------------------------------------------------------------------- verify
echo -e "\n${YELLOW}Verifying${NC}"
failed=0

for proj in "$FULL_STACK_PROJECT" "$INFRA_PROJECT"; do
    n="$(running_count "$proj")"
    if [ "$n" -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} $proj: 0 containers running"
    else
        echo -e "  ${RED}✗${NC} $proj: $n container(s) STILL RUNNING" >&2
        docker ps --filter "label=com.docker.compose.project=$proj" \
                  --format '      {{.Names}} ({{.Status}})' >&2
        failed=1
    fi
done

# A HOST process, specifically. The host PID namespace can see processes running
# INSIDE containers, so a bare `pgrep -f next-server` matches the containerised
# frontend and reports it as a stray host process. Measured 2026-08-05 while proving
# this verifier could fail: with the full stack up it flagged `next-server` when no
# host process existed. Worse, an unrelated project's container would fail this
# script even after a correct teardown. So each pid is checked against its cgroup and
# skipped when it belongs to a container.
#
# Bracket self-exclusion: `pgrep -f X` reads full command lines and would otherwise
# match this script's own invocation, reporting a survivor that is the check itself.
for pat in "next-server" "gradlew" "java.*CoreApplication"; do
    survivors="$(matching_host_pids "$pat")"
    if [ -n "${survivors// /}" ]; then
        echo -e "  ${RED}✗${NC} host process still alive matching '$pat':$survivors" >&2
        failed=1
    fi
done
[ "$failed" -eq 0 ] && echo -e "  ${GREEN}✓${NC} no dev host processes alive"

echo ""
if [ "$failed" -eq 0 ]; then
    echo -e "${GREEN}✅ All services stopped (verified — volumes and data preserved)${NC}"
    exit 0
fi
echo -e "${RED}❌ Teardown INCOMPLETE — see the ✗ lines above${NC}" >&2
exit 1
