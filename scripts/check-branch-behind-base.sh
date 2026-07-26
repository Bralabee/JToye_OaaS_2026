#!/usr/bin/env bash
# check-branch-behind-base.sh — assert this branch contains every commit already
# on the base branch.
#
# WHY THIS EXISTS (the sibling half of the 2026-07-26 stale-runtime failure)
#   Phase 26's runtime was verified green while the branch sat THREE commits
#   behind origin/main. The frontend image was therefore missing three merged UI
#   PRs — and no amount of rebuilding could have added them, because the code was
#   not in the tree being built. `scripts/check-runtime-freshness.sh` compares the
#   runtime against the tree; this compares the TREE against the base branch. Only
#   both together answer "am I looking at the current product".
#
#   This is the failure mode that makes a locally-green branch review as complete
#   while a reviewer on main sees regressions: every gate ran, every gate was
#   right, and every gate was measuring a tree that was already out of date.
#
# WHY THE BASE BRANCH IS RESOLVED AND NEVER HARDCODED
#   `main` is this repo's default today; hardcoding it would silently mis-assert
#   on any fork, rename or release branch. Resolution order, first hit wins:
#     1. --base <ref> (explicit operator intent)
#     2. $BASE_REF
#     3. $GITHUB_BASE_REF  — set by GitHub Actions on pull_request events, and the
#        only source that knows which branch THIS PR actually targets
#     4. refs/remotes/origin/HEAD — the local symref, when a clone recorded one
#     5. `git ls-remote --symref origin HEAD` — asks the remote. Read-only: unlike
#        `git remote set-head`, it writes no ref into the operator's repository.
#   No hit is exit 2 (VOID), never a pass.
#
# OFFLINE IS VOID, NOT CLEAN
#   A gate that cannot reach the remote does not know whether the branch is
#   behind. Reporting 0 there would convert "I could not check" into "you are up
#   to date" — the precise inversion this file exists to prevent. Every network,
#   ref-resolution and parse failure exits 2.
#
# Requires: bash, git. No docker, no cluster, no credentials.
#
# Exit codes: 0 = HEAD contains every commit on the base branch
#             1 = behind — the base branch has commits this branch lacks
#             2 = parse or tooling failure (the assertion is VOID, not passing)
#
# Usage:
#   scripts/check-branch-behind-base.sh                  # resolve + fetch
#   scripts/check-branch-behind-base.sh --base origin/main
#   scripts/check-branch-behind-base.sh --no-fetch        # use refs already local
#   scripts/check-branch-behind-base.sh --head <ref>      # falsify against any ref

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

REMOTE="${BASE_REMOTE:-origin}"
BASE_ARG=""
HEAD_REF="HEAD"
DO_FETCH=1

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

usage() {
    sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --base)     [ $# -ge 2 ] || parse_fail "--base needs a value";   BASE_ARG="$2"; shift 2 ;;
        --head)     [ $# -ge 2 ] || parse_fail "--head needs a value";   HEAD_REF="$2"; shift 2 ;;
        --remote)   [ $# -ge 2 ] || parse_fail "--remote needs a value"; REMOTE="$2";   shift 2 ;;
        --no-fetch) DO_FETCH=0; shift ;;
        -h|--help)  usage ;;
        *) parse_fail "unknown argument: $1 (try --help)" ;;
    esac
done

command -v git >/dev/null 2>&1 || parse_fail "git not found on PATH"
git -C "$REPO_ROOT" rev-parse --git-dir >/dev/null 2>&1 \
    || parse_fail "$REPO_ROOT is not a git work tree"

git -C "$REPO_ROOT" rev-parse --verify --quiet "$HEAD_REF" >/dev/null \
    || parse_fail "--head ref '$HEAD_REF' does not resolve in this repository"

# ---------------------------------------------------------------------------
# Resolve the base branch
# ---------------------------------------------------------------------------
BASE_SOURCE=""
BASE_BRANCH=""

if [ -n "$BASE_ARG" ]; then
    BASE_SOURCE="--base"
    BASE_BRANCH="${BASE_ARG#refs/remotes/}"
    BASE_BRANCH="${BASE_BRANCH#"$REMOTE"/}"
elif [ -n "${BASE_REF:-}" ]; then
    BASE_SOURCE="\$BASE_REF"
    BASE_BRANCH="${BASE_REF#refs/heads/}"
elif [ -n "${GITHUB_BASE_REF:-}" ]; then
    BASE_SOURCE="\$GITHUB_BASE_REF (the branch this PR targets)"
    BASE_BRANCH="${GITHUB_BASE_REF#refs/heads/}"
else
    LOCAL_SYMREF="$(git -C "$REPO_ROOT" symbolic-ref --quiet "refs/remotes/$REMOTE/HEAD" 2>/dev/null || true)"
    if [ -n "$LOCAL_SYMREF" ]; then
        BASE_SOURCE="refs/remotes/$REMOTE/HEAD"
        BASE_BRANCH="${LOCAL_SYMREF#"refs/remotes/$REMOTE/"}"
    else
        # No local symref (this clone never recorded one). Ask the remote, WITHOUT
        # writing anything into the operator's repository.
        # `awk`, not `grep -q | ...`: with `set -o pipefail` a downstream reader
        # that exits early makes the writer take SIGPIPE and promotes the whole
        # pipeline to 141, which would read as a tooling failure on a SUCCESSFUL
        # lookup. awk consumes the entire stream.
        REMOTE_SYMREF="$(
            git -C "$REPO_ROOT" ls-remote --symref "$REMOTE" HEAD 2>/dev/null \
            | awk '$1 == "ref:" { print $2; exit }'
        )" || parse_fail "git ls-remote failed against '$REMOTE' — the base branch is unknown, so this assertion is VOID (offline is not 'up to date')."
        [ -n "$REMOTE_SYMREF" ] || parse_fail \
            "'$REMOTE' advertised no symbolic HEAD, so the default branch cannot be resolved. Pass --base explicitly, or record it locally with: git remote set-head $REMOTE -a"
        BASE_SOURCE="git ls-remote --symref $REMOTE HEAD"
        BASE_BRANCH="${REMOTE_SYMREF#refs/heads/}"
    fi
fi

[ -n "$BASE_BRANCH" ] || parse_fail "resolved an EMPTY base branch name from $BASE_SOURCE — an unresolved base cannot be read as 'nothing to merge'."

BASE_TRACKING="refs/remotes/$REMOTE/$BASE_BRANCH"

echo "Branch-behind-base gate"
echo "  repo root    : $REPO_ROOT"
HEAD_NAME="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref "$HEAD_REF" 2>/dev/null || true)"
[ -n "$HEAD_NAME" ] || HEAD_NAME="detached/expression"
echo "  head         : $HEAD_REF -> $(git -C "$REPO_ROOT" rev-parse --short "$HEAD_REF") ($HEAD_NAME)"
echo "  base branch  : $REMOTE/$BASE_BRANCH  (resolved from $BASE_SOURCE)"

# ---------------------------------------------------------------------------
# Refresh the remote-tracking ref. Skipped with --no-fetch (CI checkouts already
# have the refs; a sandbox may have no network at all).
# ---------------------------------------------------------------------------
if [ "$DO_FETCH" -eq 1 ]; then
    if git -C "$REPO_ROOT" fetch --quiet --no-tags "$REMOTE" \
            "+refs/heads/$BASE_BRANCH:$BASE_TRACKING" 2>/dev/null; then
        echo "  fetch        : refreshed $BASE_TRACKING"
    else
        parse_fail "could not fetch '$BASE_BRANCH' from '$REMOTE'. Without a current base ref this gate cannot tell 'up to date' from 'three PRs behind', so it is VOID rather than passing. Re-run with network, or with --no-fetch to assert against the ref already in this clone (and say so in the evidence)."
    fi
else
    echo "  fetch        : SKIPPED (--no-fetch) — asserting against the ref already in this clone"
fi

git -C "$REPO_ROOT" rev-parse --verify --quiet "$BASE_TRACKING" >/dev/null \
    || parse_fail "$BASE_TRACKING does not exist in this clone. Nothing to compare against, so the assertion is VOID."

BASE_SHA="$(git -C "$REPO_ROOT" rev-parse --short "$BASE_TRACKING")"
echo "  base head    : $BASE_SHA"
echo

# ---------------------------------------------------------------------------
# The assertion: HEAD..base must be empty.
#
# `rev-list --count HEAD..base` counts commits reachable from base but NOT from
# HEAD — i.e. exactly what a merge would bring in. Deliberately one-directional:
# being AHEAD of the base is the normal state of a feature branch and is not a
# defect. Only being behind is.
# ---------------------------------------------------------------------------
BEHIND=""
BEHIND="$(git -C "$REPO_ROOT" rev-list --count "$HEAD_REF..$BASE_TRACKING" 2>/dev/null)" \
    || parse_fail "git rev-list failed for $HEAD_REF..$BASE_TRACKING"
[[ "$BEHIND" =~ ^[0-9]+$ ]] || parse_fail "non-numeric behind-count '$BEHIND'"

AHEAD="$(git -C "$REPO_ROOT" rev-list --count "$BASE_TRACKING..$HEAD_REF" 2>/dev/null || echo "?")"

if [ "$BEHIND" -gt 0 ]; then
    echo "MISSING COMMITS — on $REMOTE/$BASE_BRANCH but not in $HEAD_REF:"
    git -C "$REPO_ROOT" log --format='  %h  %cI  %an  %s' "$HEAD_REF..$BASE_TRACKING"
    echo
    echo "Files those commits touch that $HEAD_REF has not seen:"
    git -C "$REPO_ROOT" diff --name-only "$HEAD_REF...$BASE_TRACKING" | sed 's/^/  /'
    echo
    echo "Merge the base in, then REBUILD the runtime — a rebuild alone cannot add"
    echo "code that is not in the tree:"
    echo "  git merge $REMOTE/$BASE_BRANCH"
    echo "  docker compose -f docker-compose.full-stack.yml up -d --build core-java frontend edge-go mcp-server"
    echo "  scripts/check-runtime-freshness.sh"
    fail "$HEAD_REF is $BEHIND commit(s) behind $REMOTE/$BASE_BRANCH (and $AHEAD ahead). Any gate run on this tree is measuring an out-of-date product."
fi

echo "PASS: $HEAD_REF contains every commit on $REMOTE/$BASE_BRANCH ($BASE_SHA); it is $AHEAD commit(s) ahead and 0 behind."
