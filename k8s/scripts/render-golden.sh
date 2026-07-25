#!/usr/bin/env bash
# render-golden.sh — the Incremental Betterment proof harness for Phase 26.
#
# PURPOSE
#   Phase 26 makes several surgical edits to k8s/base (DB_PORT -> secretKeyRef,
#   RABBITMQ_USERNAME -> RABBITMQ_USER, the kustomize label-transformer field
#   list, the app-config split-horizon keys, the ingress host cleanup). Every one
#   of those edits changes the RENDERED staging and production output, which is
#   what actually reaches a live cluster.
#
#   "We think staging is unaffected" is not a proof. This script turns it into a
#   reviewable diff: `kubectl kustomize k8s/staging` and `kubectl kustomize
#   k8s/production` are rendered and compared byte-for-byte against committed
#   golden files under k8s/goldens/. Every base edit must therefore show ONLY its
#   intended additions in the render — never a behavioural change (a dropped
#   selector label, a changed immutable field, a lost env var).
#
#   The goldens are never hand-edited: `--write` is the arbiter (same idiom as
#   scripts/docs-freshness.sh --write). Commit the regenerated golden in the same
#   change as the base edit that caused it, so the review sees both halves.
#
# WHY k8s/goldens/ AND NOT k8s/<something>/
#   k8s/scripts/check-no-plaintext-secrets.sh discovers overlays with
#   `find k8s -maxdepth 2 -name kustomization.yaml`. k8s/goldens/ deliberately
#   contains NO kustomization.yaml, so it is not mistaken for a fourth overlay.
#
# ANCHORED (NON-VACUOUS) GOLDEN ASSERTIONS
#   A criterion of the form "this edit added no selector line" must be anchored to
#   a NAMED pre-change copy of the goldens, never to a git-history offset. The
#   form `diff <(git show HEAD~1:<f> 2>/dev/null || cat <f>) <f>` compares the
#   file TO ITSELF whenever the `git show` fails: the diff comes out empty and the
#   assertion passes VACUOUSLY. That form is forbidden phase-wide. Instead:
#
#     --snapshot <label>     copy the CURRENT goldens to k8s/goldens/.pre/<label>/
#                            BEFORE editing. Exit 1 if <label> already exists (a
#                            stale snapshot must never be silently reused);
#                            exit 2 if either golden is missing.
#     --diff-since <label>   after `--write`, print the snapshot (OLD) vs the
#                            current golden (NEW). Exit 0 when the snapshot
#                            resolved; EXIT 2 when the snapshot directory or
#                            either file inside it is missing — a missing
#                            baseline FAILS the caller's assertion instead of
#                            handing it an empty diff.
#
#   DIFF DIRECTION (load-bearing — downstream assertions grep for it):
#   `--diff-since` prints the NORMAL diff format, so
#       a line starting with '<'  exists only in the snapshot  -> REMOVED by the edit
#       a line starting with '>'  exists only in the golden    -> ADDED by the edit
#   Informational headers go to STDERR, so stdout carries the diff and NOTHING
#   else. That is what makes `test -s "$D"` a real signal: an empty stdout means
#   the edit changed no byte, which is how a snapshot taken AFTER the edit (the
#   other way to fake a pass) is caught.
#
#   Callers use the three-part shape:
#     1. `--diff-since <label> > "$D"; echo "resolve_exit=$?"`  -> must be 0
#        (a 2 means the baseline is missing and the assertion is VOID, not passed)
#     2. `grep '^>' "$D" | grep -c '<forbidden pattern>'`       -> must be 0
#     3. `test -s "$D"` / `test ! -s "$D"` per the edit's expectation
#
#   k8s/goldens/.pre/ is gitignored: snapshots are transient scaffolding for one
#   execution, not committed artifacts. `--write` NEVER writes under .pre/, which
#   is what keeps the `--write` idempotence check a clean
#   `git diff --quiet k8s/goldens`.
#
# Requires: kubectl (client-side `kubectl kustomize` only — no cluster access),
#           diff, cmp.
# Exit codes: 0 = clean / snapshot taken / snapshot resolved,
#             1 = render drifted from the golden, or snapshot label already used,
#             2 = kubectl absent, a kustomize build failed, or a baseline/golden
#                 file is missing.
#
# Usage:
#   ./k8s/scripts/render-golden.sh                     # check mode (CI, default)
#   ./k8s/scripts/render-golden.sh --write             # regenerate the goldens
#   ./k8s/scripts/render-golden.sh --snapshot <label>  # pre-change baseline
#   ./k8s/scripts/render-golden.sh --diff-since <label>
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$REPO_ROOT/k8s"
GOLDEN_DIR="$K8S_DIR/goldens"
PRE_DIR="$GOLDEN_DIR/.pre"

# The overlays whose render is contractual. k8s/base is deliberately NOT a
# target: it renders without a namespace and is only ever consumed through an
# overlay, so an overlay render is the behaviour that actually ships.
TARGETS=(staging production)

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

tool_fail() { echo "ERROR: $*" >&2; exit 2; }

require_kubectl() {
    command -v kubectl > /dev/null \
        || tool_fail "kubectl not on PATH (client-side 'kubectl kustomize' is required)."
}

# render <target> <outfile> — build one overlay or die with exit 2.
render() {
    local target="$1" out="$2"
    if ! kubectl kustomize "$K8S_DIR/$target" > "$out" 2> "$TMP/stderr"; then
        echo "ERROR: 'kubectl kustomize k8s/$target' failed:" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi
}

usage() {
    sed -n '/^# Usage:/,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# check mode (default): render vs committed golden, byte-for-byte
# ---------------------------------------------------------------------------
mode_check() {
    require_kubectl
    local drift=0 target golden current
    for target in "${TARGETS[@]}"; do
        golden="$GOLDEN_DIR/$target.yaml"
        current="$TMP/$target.yaml"
        render "$target" "$current"

        if [[ ! -f "$golden" ]]; then
            echo "FAIL [$target]: golden k8s/goldens/$target.yaml is MISSING." >&2
            echo "       Run 'k8s/scripts/render-golden.sh --write' and commit the result." >&2
            drift=1
            continue
        fi

        if cmp -s "$golden" "$current"; then
            echo "OK   [$target]: render matches k8s/goldens/$target.yaml ($(wc -l < "$golden") lines)"
        else
            echo "FAIL [$target]: render DRIFTED from k8s/goldens/$target.yaml" >&2
            diff -u \
                --label "k8s/goldens/$target.yaml (GOLDEN)" \
                --label "kubectl kustomize k8s/$target (CURRENT)" \
                "$golden" "$current" || true
            drift=1
        fi
    done

    if [[ $drift -ne 0 ]]; then
        echo >&2
        echo "The staging/production render no longer matches its committed golden." >&2
        echo "If the change is INTENDED, review the diff above, then run" >&2
        echo "  k8s/scripts/render-golden.sh --write" >&2
        echo "and commit the regenerated golden alongside the k8s/base edit that caused it." >&2
        exit 1
    fi

    echo "All overlay renders match their committed goldens."
}

# ---------------------------------------------------------------------------
# --write: the goldens are regenerated, never hand-edited
# ---------------------------------------------------------------------------
mode_write() {
    require_kubectl
    mkdir -p "$GOLDEN_DIR"
    local target golden current wrote=0
    for target in "${TARGETS[@]}"; do
        golden="$GOLDEN_DIR/$target.yaml"
        current="$TMP/$target.yaml"
        render "$target" "$current"

        if [[ -f "$golden" ]] && cmp -s "$golden" "$current"; then
            echo "UNCHANGED [$target]: k8s/goldens/$target.yaml ($(wc -l < "$golden") lines)"
        else
            cp "$current" "$golden"
            echo "WROTE     [$target]: k8s/goldens/$target.yaml ($(wc -l < "$golden") lines)"
            wrote=1
        fi
    done

    if [[ $wrote -eq 0 ]]; then
        echo "No golden changed — the render already matched."
    else
        echo "Golden(s) regenerated. Review the diff and commit them WITH the k8s/base edit."
    fi
    # NOTE: nothing above ever writes under $PRE_DIR. Snapshots are read-only
    # scaffolding for --diff-since; keeping --write out of .pre/ is what lets the
    # idempotence check stay a clean `git diff --quiet k8s/goldens`.
}

# ---------------------------------------------------------------------------
# --snapshot <label>: named PRE-CHANGE baseline for an anchored assertion
# ---------------------------------------------------------------------------
mode_snapshot() {
    local label="${1:-}"
    [[ -n "$label" ]] || tool_fail "--snapshot requires a <label> (e.g. --snapshot 26-01-task2)."
    case "$label" in
        */*|.|..) tool_fail "--snapshot label '$label' must be a plain directory name." ;;
    esac

    local dest="$PRE_DIR/$label"
    if [[ -e "$dest" ]]; then
        echo "FAIL: snapshot label '$label' already exists at k8s/goldens/.pre/$label." >&2
        echo "      A stale snapshot must never be silently reused — it would anchor the" >&2
        echo "      assertion to the wrong moment. Pick a new label, or delete that" >&2
        echo "      directory if you are certain it is spent." >&2
        exit 1
    fi

    # Verify BOTH goldens exist before creating anything, so a failed snapshot
    # never leaves a half-populated label directory blocking the retry.
    local target
    for target in "${TARGETS[@]}"; do
        [[ -f "$GOLDEN_DIR/$target.yaml" ]] \
            || tool_fail "golden k8s/goldens/$target.yaml is missing — run --write first."
    done

    mkdir -p "$dest"
    for target in "${TARGETS[@]}"; do
        cp "$GOLDEN_DIR/$target.yaml" "$dest/$target.yaml"
        echo "SNAPSHOT [$target]: k8s/goldens/$target.yaml -> k8s/goldens/.pre/$label/$target.yaml ($(wc -l < "$dest/$target.yaml") lines)"
    done
    echo "Baseline '$label' captured. Make the edit, run --write, then --diff-since $label."
}

# ---------------------------------------------------------------------------
# --diff-since <label>: snapshot (OLD) vs current golden (NEW)
#   stdout = diff only (normal format: '<' removed, '>' added)
#   stderr = headers / errors
#   exit 2 on a missing baseline — a missing baseline FAILS, never passes empty
# ---------------------------------------------------------------------------
mode_diff_since() {
    local label="${1:-}"
    [[ -n "$label" ]] || tool_fail "--diff-since requires a <label>."
    local src="$PRE_DIR/$label"

    if [[ ! -d "$src" ]]; then
        echo "ERROR: snapshot '$label' not found at k8s/goldens/.pre/$label." >&2
        echo "       An assertion anchored to a missing baseline is VOID, not passing," >&2
        echo "       so this exits 2 and prints no diff. Take the snapshot BEFORE the edit." >&2
        exit 2
    fi

    local target
    for target in "${TARGETS[@]}"; do
        [[ -f "$src/$target.yaml" ]] \
            || tool_fail "snapshot '$label' is incomplete: k8s/goldens/.pre/$label/$target.yaml is missing."
        [[ -f "$GOLDEN_DIR/$target.yaml" ]] \
            || tool_fail "golden k8s/goldens/$target.yaml is missing — run --write before --diff-since."
    done

    for target in "${TARGETS[@]}"; do
        # Header to STDERR so an unchanged render yields a genuinely EMPTY stdout.
        echo "--- diff k8s/goldens/.pre/$label/$target.yaml (OLD, '<') vs k8s/goldens/$target.yaml (NEW, '>')" >&2
        # Normal diff format on purpose: the phase's assertions grep '^>' / '^<'.
        diff "$src/$target.yaml" "$GOLDEN_DIR/$target.yaml" || true
    done
}

# ---------------------------------------------------------------------------
main() {
    case "${1:-}" in
        "")             mode_check ;;
        --write)        mode_write ;;
        --snapshot)     mode_snapshot "${2:-}" ;;
        --diff-since)   mode_diff_since "${2:-}" ;;
        -h|--help)      usage ;;
        *)              echo "ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
    esac
}

main "$@"
