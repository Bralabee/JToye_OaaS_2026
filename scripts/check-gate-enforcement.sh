#!/usr/bin/env bash
# check-gate-enforcement.sh — every static gate must actually run in CI.
#
# WHY THIS EXISTS
#
#   Measured 2026-08-05: of the 24 scripts/check-*.sh in this repo, SIX had zero
#   references in .github/workflows/. Three of those were deliberate — they inspect a
#   running stack and could only ever exit 2 (VOID) on a hosted runner. Three were not:
#
#     check-e2e-baseurl-contract.sh       (#505)
#     check-playwright-mobile-contract.sh (#503)
#     check-no-measured-placeholders.sh   (27-04 D-05)
#
#   Each of those was written *because* a specific defect shipped, and each was
#   incapable of firing on a pull request. That is the same failure shape the repo
#   keeps re-encountering from the other direction: a gate is green, so the property
#   is assumed held, when in fact nothing ever asked. #510 bound the infra ports to
#   loopback and its gate has never run in CI either — the fix is real and completely
#   unprotected.
#
#   Writing "remember to wire new gates into CI" in a document does not survive. This
#   does.
#
# WHAT IT ASSERTS
#
#   For every scripts/check-*.sh:
#     - if the script is STATIC (invokes no runtime binary), it MUST be referenced by
#       at least one file under .github/workflows/;
#     - if it is RUNTIME-DEPENDENT, it MUST carry an explicit, reasoned entry in
#       scripts/gates/gate-enforcement.conf.
#
#   Default-deny: a new gate that is neither wired nor declared FAILS. Forgetting to
#   think about a new gate is the case this exists to catch, so it must not pass
#   silently.
#
# HOW "STATIC" IS DECIDED
#
#   By whether the script invokes a binary that needs something running: docker,
#   curl, psql, kubectl, minikube, nc, rabbitmqctl, redis-cli, wget. This is a
#   heuristic, and it is deliberately biased toward FAILING: a runtime gate
#   misclassified as static gets flagged and needs a conf entry (cheap, and the entry
#   documents the reason). A static gate misclassified as runtime would be the
#   dangerous direction, and that requires someone to have written a conf entry
#   claiming a dependency the script does not have — which the VOID below catches.
#
# SEARCH DISCIPLINE
#
#   Workflow files are enumerated with `find` and then searched BY NAME, never with a
#   recursive grep. Two reasons, both measured on this machine: `rg` does not exist
#   inside a `bash script.sh` (it is a shell function the harness injects; there is no
#   system ripgrep, so it dies rc=127, which is indistinguishable from "no matches"),
#   and the `grep` function routes to ugrep with --ignore-files, so a recursive search
#   silently honours .gitignore. `.github/` is also a hidden directory. A named-file
#   search has neither problem.
#
# Exit codes: 0 = every gate accounted for, 1 = a gate runs nowhere, 2 = VOID.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WF_DIR="$REPO_ROOT/.github/workflows"
CONF="$REPO_ROOT/scripts/gates/gate-enforcement.conf"
RUNTIME_BINARIES='docker|curl|psql|kubectl|minikube|rabbitmqctl|redis-cli|wget|nc'

void() { echo "VOID: $*" >&2; exit 2; }

command -v grep >/dev/null 2>&1 || void "grep not found"
command -v find >/dev/null 2>&1 || void "find not found"
[ -d "$WF_DIR" ] || void "no .github/workflows directory under $REPO_ROOT"
[ -f "$CONF" ]   || void "missing $CONF — the exemption table is required, not optional"

# --- collect workflow files (named, so the search cannot silently under-read) -------
WF_FILES=()
while IFS= read -r f; do WF_FILES+=("$f"); done < <(
    find "$WF_DIR" -type f \( -name '*.yml' -o -name '*.yaml' \) | sort
)
[ "${#WF_FILES[@]}" -gt 0 ] || void "no workflow files found under $WF_DIR — a scan with no inputs proves nothing"

# --- collect gates ------------------------------------------------------------------
GATES=()
while IFS= read -r f; do GATES+=("$f"); done < <(
    find "$REPO_ROOT/scripts" -maxdepth 1 -type f -name 'check-*.sh' | sort
)
[ "${#GATES[@]}" -gt 0 ] || void "no scripts/check-*.sh found — a scan with no inputs proves nothing"

# --- exemption table ----------------------------------------------------------------
# Format: <gate-basename> <reason...>. Blank lines and full-line # comments ignored.
declare -A EXEMPT_REASON=()
while IFS= read -r line; do
    case "$line" in ''|\#*) continue ;; esac
    name="${line%% *}"
    reason="${line#* }"
    [ -n "$name" ] || continue
    EXEMPT_REASON["$name"]="$reason"
done < "$CONF"

FAILED=0
UNWIRED=()
STALE_EXEMPT=()
declare -A SEEN=()

for g in "${GATES[@]}"; do
    base="$(basename "$g")"
    SEEN["$base"]=1

    # Does it invoke a runtime binary? Searched on ONE named file.
    #
    # Full-line comments and the RUNTIME_BINARIES assignment are stripped first,
    # because THIS script necessarily contains every name it looks for — the
    # self-match trap this repo has hit before, where a rule that must name the
    # token it forbids fires on its own definition. Observed here on the first run:
    # it reported itself as invoking all nine. Trailing comments are not stripped
    # (`grep -v '^ *#'` cannot see them), so a binary named in one still counts as a
    # dependency — that direction is safe, since it can only force an exemption to be
    # written, never suppress one that is needed.
    deps="$(
        grep -vE '^[[:space:]]*#' "$g" \
            | grep -vE '^[[:space:]]*RUNTIME_BINARIES=' \
            | grep -oE "\\b($RUNTIME_BINARIES)\\b" \
            | sort -u | tr '\n' ' '
    )"

    # Is it referenced by any workflow? Named files only.
    refs=0
    for wf in "${WF_FILES[@]}"; do
        if grep -qF -- "$base" "$wf" </dev/null; then refs=$((refs + 1)); fi
    done

    exempt="${EXEMPT_REASON[$base]+set}"

    if [ "$refs" -gt 0 ]; then
        # Wired. Nothing to prove, whether static or not.
        continue
    fi

    if [ -n "$exempt" ]; then
        # Declared as runtime-dependent. Sanity-check the claim: an exemption for a
        # gate with no runtime dependency at all is probably stale, and a stale
        # exemption is how a static gate goes unenforced with a paper trail saying
        # that is fine.
        if [ -z "$deps" ]; then
            STALE_EXEMPT+=("$base — declared runtime-dependent but invokes no runtime binary")
            FAILED=1
        fi
        continue
    fi

    UNWIRED+=("$base${deps:+ (invokes: ${deps% })}")
    FAILED=1
done

# --- an exemption naming a gate that does not exist is a broken table ----------------
for name in "${!EXEMPT_REASON[@]}"; do
    [ -n "${SEEN[$name]+set}" ] || void "$CONF exempts '$name', which is not a scripts/check-*.sh — a table that names nothing cannot be trusted to name the right things"
done

echo "check-gate-enforcement  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  gates     : ${#GATES[@]}"
echo "  workflows : ${#WF_FILES[@]}"
echo "  exempt    : ${#EXEMPT_REASON[@]} declared"

if [ "${#UNWIRED[@]}" -gt 0 ]; then
    echo "FAIL: ${#UNWIRED[@]} gate(s) are referenced by no workflow and carry no exemption:" >&2
    for u in "${UNWIRED[@]}"; do echo "        $u" >&2; done
    echo "      A gate that cannot fire on a pull request does not prevent anything." >&2
    echo "      Either add it to a workflow, or declare it in $CONF with the reason" >&2
    echo "      it cannot run on a hosted runner." >&2
fi

if [ "${#STALE_EXEMPT[@]}" -gt 0 ]; then
    echo "FAIL: ${#STALE_EXEMPT[@]} exemption(s) look stale:" >&2
    for s in "${STALE_EXEMPT[@]}"; do echo "        $s" >&2; done
fi

if [ "$FAILED" -eq 0 ]; then
    echo "PASS: every gate either runs in CI or has a declared reason it cannot."
fi

exit "$FAILED"
