#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# e2e-spec-digest.sh — a CONTENT digest of everything the Playwright skip set can
# depend on: frontend/e2e/** plus frontend/playwright.config.ts.
#
# WHY THIS EXISTS
#
#   check-e2e-skip-budget.sh must refuse a report that describes a spec set which no
#   longer exists. It used to answer that question with mtime:
#
#       find frontend/e2e frontend/playwright.config.ts -newer "$REPORT"
#
#   mtime answers "was this file WRITTEN after the report", not "is its content
#   DIFFERENT from what the report describes". `git pull`, `git checkout`, `git merge`
#   and `git stash pop` all rewrite mtime on files whose bytes never changed, so the
#   gate went VOID after EVERY merge that touched a spec — including a merge of the
#   very report-producing run. The observed cost was a ~6.5 minute suite re-run to
#   re-earn a gate over byte-identical specs, and the HANDOFF had to carry a standing
#   note telling readers to expect it.
#
#   Content is the honest question, and it is the question this repo's own proof
#   standards already insist on: "Verify a restore BY CONTENT — grep a unique token,
#   compare a hash — never by `git diff --stat`."
#
# WHAT IT HASHES
#
#   Every regular file under frontend/e2e (NOT only *.spec.ts — a helper such as
#   vendor-credentials.ts decides whether a whole spec self-skips, so it is part of
#   the skip set's input), plus frontend/playwright.config.ts, whose `projects` and
#   `grepInvert` decide which blocks are ENUMERATED at all.
#
#   That is deliberately the same file set the old mtime check walked. This change
#   swaps the QUESTION (mtime -> content), not the SCOPE; narrowing the scope would
#   be a separate decision needing its own evidence.
#
# WHY `git hash-object` AND NOT `git rev-parse HEAD:<path>`
#
#   `git hash-object` hashes the WORKING TREE bytes. The index and HEAD do not: a
#   report produced from a dirty tree would be certified against content that was
#   never run. This repo has already been bitten by exactly that distinction —
#   `git checkout` restores from the INDEX and silently discards post-staging edits.
#   Untracked files hash fine too, so a brand-new spec counts immediately.
#
# PATHS ARE PART OF THE DIGEST
#   `<relpath>\t<blobhash>` per line, so a pure RENAME changes the digest even though
#   every blob is unchanged. A renamed spec produces different test titles, which is
#   exactly the S-2/S-3 membership the gate reasons about.
#
# EXIT CODES
#   0 = digest written to stdout (64 lowercase hex chars, nothing else)
#   2 = VOID — missing tooling, no spec directory, or zero files discovered.
#       "Found nothing" must never hash to a stable value that then reads as a match.
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

void() { echo "e2e-spec-digest: VOID: $*" >&2; exit 2; }

for t in git sha256sum find sort; do
    command -v "$t" >/dev/null 2>&1 || void "$t is not on PATH — the digest cannot be computed"
done

E2E_DIR="${E2E_SPEC_DIR:-$REPO_ROOT/frontend/e2e}"
PW_CONFIG="${E2E_PW_CONFIG:-$REPO_ROOT/frontend/playwright.config.ts}"

[ -d "$E2E_DIR" ]   || void "spec directory not found: $E2E_DIR"
[ -f "$PW_CONFIG" ] || void "playwright config not found: $PW_CONFIG"

# -print0 / -d '' so a filename containing whitespace or a newline cannot split a
# record. LC_ALL=C so the order is byte-order everywhere and the digest is not
# locale-dependent — a digest that changes with $LANG would VOID on another machine
# for no content reason, reintroducing this script's own bug in a new disguise.
mapfile -d '' -t FILES < <(find "$E2E_DIR" -type f -print0 2>/dev/null | LC_ALL=C sort -z)

[ "${#FILES[@]}" -gt 0 ] \
    || void "zero files under $E2E_DIR — an empty input set would produce a constant digest that matches anything"

# Appended at a fixed final position rather than sorted in: its path lies outside
# E2E_DIR, so sorting it among them would depend on the directory's own name.
FILES+=("$PW_CONFIG")

# One process for all files. `git hash-object` prints one hash per operand, in
# operand order, so the pairing below is positional and needs no re-lookup.
mapfile -t HASHES < <(git -C "$REPO_ROOT" hash-object -- "${FILES[@]}" 2>/dev/null)

[ "${#HASHES[@]}" -eq "${#FILES[@]}" ] \
    || void "git hash-object returned ${#HASHES[@]} hash(es) for ${#FILES[@]} file(s) — refusing to emit a digest over a partial read"

{
    for i in "${!FILES[@]}"; do
        rel="${FILES[$i]#"$REPO_ROOT"/}"
        [ -n "${HASHES[$i]}" ] || void "empty hash for ${FILES[$i]}"
        printf '%s\t%s\n' "$rel" "${HASHES[$i]}"
    done
} | sha256sum | awk '{print $1}'
