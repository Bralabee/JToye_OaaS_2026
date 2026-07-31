#!/usr/bin/env bash
# install-hooks.sh — make this repo's tracked hooks actually run, and prove they can.
#
# HOW HOOKS REACH THIS REPO — read this before changing anything here
#
#   This machine installs a GLOBAL hook set (`core.hooksPath = ~/.git-hooks`) whose
#   members are DISPATCHERS. Git only ever runs one hooks directory, so each global
#   hook keeps that single slot generic and delegates to a repo-local
#   `.githooks/<name>` when the repository provides an executable one:
#
#       ~/.git-hooks/post-merge        -> exec <repo>/.githooks/post-merge
#       ~/.git-hooks/pre-push          -> exec <repo>/.githooks/pre-push   (BLOCKING)
#       ~/.git-hooks/prepare-commit-msg -> strips Co-Authored-By trailers
#
#   **A repo therefore opts in by committing an executable `.githooks/<hook>`, and
#   NOTHING ELSE.** There is no path to configure.
#
# WHY THIS SCRIPT DOES NOT SET core.hooksPath — it would BREAK things
#
#   The obvious "installer" move is `git config core.hooksPath .githooks`. That is
#   wrong here, and the dispatcher's own header says so: a per-repo `core.hooksPath`
#   REPLACES the global directory, which would disable `prepare-commit-msg` and
#   `pre-push` for this repo. The first of those strips `Co-Authored-By` trailers,
#   which this project's git policy forbids — so "installing" hooks that way would
#   silently start letting forbidden trailers through.
#
#   Measured on this checkout before writing this: a repo-level
#   `core.hooksPath = <repo>/.git/hooks` was set, pointing at a directory containing
#   ZERO non-sample hooks. Its effect was to disable all three global dispatchers
#   here. So the fix is to REMOVE that override, not to add another one.
#
# THE FAILURE `--check` GUARDS — a hook without the executable bit is SILENTLY IGNORED
#
#   Both git and the dispatcher (`[[ -x "$local_hook" ]]`) skip a non-executable hook
#   without a warning, and the symptom is indistinguishable from "the hook ran and had
#   nothing to say" — which is exactly what `.githooks/post-merge` looks like on a
#   clean pull, by design. A silent detector is worse than none, because its presence
#   in the tree reads as coverage.
#
#   The bit is asserted against git's OWN INDEX (`ls-files --stage`, mode 100755), not
#   the filesystem: a local `chmod +x` that was never committed is lost on the next
#   clone, so a filesystem check would pass here and fail for everyone else.
#
# USAGE
#   scripts/install-hooks.sh            enable (idempotent; removes a shadowing override)
#   scripts/install-hooks.sh --check    verify only, no writes. THIS RUNS IN CI.
#
# EXIT CODES:  0 = ok · 1 = violation · 2 = VOID (could not evaluate)

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOKS_DIR_REL=".githooks"
GLOBAL_DISPATCH_DIR="${JTOYE_GLOBAL_HOOKS_DIR:-$HOME/.git-hooks}"

MODE="install"
case "${1:-}" in
    --check) MODE="check" ;;
    "")      ;;
    *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

VIOLATIONS=0
violation() { echo "FAIL: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }
void()      { echo "VOID: $*" >&2; exit 2; }

command -v git >/dev/null 2>&1 || void "git not found"
[ -d "$REPO_ROOT/$HOOKS_DIR_REL" ] || void "no $HOOKS_DIR_REL/ directory at $REPO_ROOT"

# Discovery must never find nothing and call it clean.
TRACKED="$(git -C "$REPO_ROOT" ls-files --stage -- "$HOOKS_DIR_REL" 2>/dev/null || true)"
[ -n "$TRACKED" ] || void "no tracked files under $HOOKS_DIR_REL/ — 'found nothing' is not 'nothing to check'"

HOOK_COUNT=0

# ---- H-1 executable in the index · H-2 valid bash  (both CI-safe) -----------------
while IFS= read -r line; do
    [ -n "$line" ] || continue
    mode="${line%% *}"
    path="${line#*$'\t'}"
    case "$(basename "$path")" in README*|*.md) continue ;; esac
    HOOK_COUNT=$((HOOK_COUNT + 1))

    if [ "$mode" != "100755" ]; then
        violation "H-1 $path is committed with mode $mode, not 100755. Git AND the global dispatcher (\`[[ -x ]]\`) will SILENTLY skip it, which is indistinguishable from the hook running with nothing to report. Fix: git update-index --chmod=+x $path"
    fi
    if ! bash -n "$REPO_ROOT/$path" 2>/dev/null; then
        violation "H-2 $path is not syntactically valid bash — an advisory hook dies on line 1 and takes its own failure with it"
    fi
done <<< "$TRACKED"

[ "$HOOK_COUNT" -gt 0 ] || void "no hook files found under $HOOKS_DIR_REL/ after filtering docs"

# ---- H-3 the repo-level override must not shadow the dispatcher (LOCAL only) ------
# Skipped when there is no global dispatcher — a CI runner has none, and asserting
# against an absent mechanism would be a permanently-red job over nothing.
REPO_HOOKS_PATH="$(git -C "$REPO_ROOT" config --local --get core.hooksPath 2>/dev/null || true)"
DISPATCH_PRESENT=0
[ -x "$GLOBAL_DISPATCH_DIR/post-merge" ] && DISPATCH_PRESENT=1

if [ "$MODE" = "check" ]; then
    if [ "$DISPATCH_PRESENT" -eq 1 ] && [ -n "$REPO_HOOKS_PATH" ]; then
        violation "H-3 a repo-level core.hooksPath ('$REPO_HOOKS_PATH') shadows the global dispatcher at $GLOBAL_DISPATCH_DIR, so $HOOKS_DIR_REL/ hooks never run here. Fix: scripts/install-hooks.sh"
    elif [ "$DISPATCH_PRESENT" -eq 0 ]; then
        echo "  H-3      skipped — no global dispatcher at $GLOBAL_DISPATCH_DIR (expected on a CI runner)"
    fi
    echo "  H-1/H-2  $HOOK_COUNT tracked hook(s): executable in the index, valid bash"
    [ "$VIOLATIONS" -gt 0 ] && { echo "install-hooks --check: $VIOLATIONS violation(s)" >&2; exit 1; }
    echo "install-hooks --check: PASS"
    exit 0
fi

# ---- enable ----------------------------------------------------------------------
[ "$VIOLATIONS" -eq 0 ] || { echo "install-hooks: refusing to enable $VIOLATIONS broken hook(s)" >&2; exit 1; }

if [ "$DISPATCH_PRESENT" -eq 0 ]; then
    void "no global dispatcher at $GLOBAL_DISPATCH_DIR/post-merge. This repo's hooks are reached THROUGH it; without it there is nothing to enable, and setting core.hooksPath here would disable the sibling global hooks (see the header)."
fi

if [ -n "$REPO_HOOKS_PATH" ]; then
    ACTIVE_THERE="$(ls -1 "$REPO_HOOKS_PATH" 2>/dev/null | grep -vc '\.sample$' || true)"
    echo "  removing repo-level core.hooksPath = $REPO_HOOKS_PATH ($ACTIVE_THERE active hook(s) there)"
    echo "  it shadows the global dispatcher, so $HOOKS_DIR_REL/ never runs."
    if [ "${ACTIVE_THERE:-0}" -gt 0 ]; then
        echo "  WARNING: that directory holds ${ACTIVE_THERE} hook(s) that will stop running." >&2
        echo "  Move them into $HOOKS_DIR_REL/ first if they are still wanted, then re-run." >&2
        exit 1
    fi
    git -C "$REPO_ROOT" config --local --unset core.hooksPath
    STILL="$(git -C "$REPO_ROOT" config --local --get core.hooksPath 2>/dev/null || true)"
    [ -z "$STILL" ] || void "core.hooksPath still reads '$STILL' after --unset"
    echo "  removed. This also re-enables the sibling global hooks here (prepare-commit-msg, pre-push)."
else
    echo "  no repo-level core.hooksPath override — the dispatcher already reaches $HOOKS_DIR_REL/"
fi

echo
echo "install-hooks: $HOOK_COUNT hook(s) live via $GLOBAL_DISPATCH_DIR:"
printf '%s\n' "$TRACKED" | sed 's/.*\t//' | while IFS= read -r p; do
    case "$(basename "$p")" in README*|*.md) continue ;; esac
    printf '  %s\n' "$p"
done
echo
echo "PROVE it fires — a hook that never runs looks exactly like a quiet one:"
echo "  JTOYE_HOOK_VERBOSE=1 git merge --no-ff --no-edit HEAD"
echo "  expect a [post-merge] line. No output means it is NOT running."
