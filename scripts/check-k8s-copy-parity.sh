#!/usr/bin/env bash
# check-k8s-copy-parity.sh — every committed copy of a canonical file is byte-equal
# to it (Phase 29 plan 29-10, T-29-36). Generalises the phase-29-research branch's
# check-alert-corpus-parity.sh from one hard-coded pair to the declared list in
# k8s/copy-parity.conf.
#
# WHY THIS EXISTS
#
#   kustomize's configMapGenerator may only read files INSIDE its own kustomization
#   root, so `../../infra/...` is rejected ("security; file is not in or below the
#   current directory"), and a symlink is resolved the same way. There is no
#   supported escape. So a file that BOTH runtimes must serve — compose by bind
#   mount, k8s by ConfigMap — becomes a committed COPY, and without a gate the
#   invariant "it is the same file" is an intention. The failure is the quiet kind:
#   the two drift, both runtimes keep serving what they have, every check stays
#   green, and the only symptom is that an edit made in one place (a login-theme
#   fix, an alert rule) never reaches the other runtime. Nothing goes red.
#
# WHAT IT ASSERTS
#
#   For every `<copy> <canonical>` line of k8s/copy-parity.conf:
#       md5(copy) == md5(canonical)
#
#   BYTE-EQUALITY, deliberately (the branch gate's reasoning, kept): any semantic
#   comparison needs a normaliser, a normaliser that is slightly wrong makes a
#   required gate cry wolf, and a checksum has no such failure mode. It also catches
#   comment-only drift, and it is the only comparison that means anything for a
#   binary file (the theme's woff2 font).
#
# WHICH FILE IS CANONICAL
#
#   The RIGHT-HAND path of each line. When this gate fails, copy canonical -> copy,
#   never the other way, unless the copy is where the intended edit was made, in
#   which case copy it BACK to the canonical path first and then re-run. Either way
#   one commit ends with the two byte-equal. For a compose-mounted canonical file,
#   recreate that container afterwards (a single-file bind mount detaches on inode
#   change, so the running container keeps the old bytes).
#
#   THE COPY CARRIES NO HEADER OF ITS OWN, and that is not an oversight. A "this is
#   a copy" banner inside the copy would make the two differ by construction and this
#   gate could never pass. The statement lives in the kustomization beside each
#   generator and in k8s/copy-parity.conf.
#
# WHAT IT DOES NOT ASSERT
#
#   That the content is correct, that the generator actually lists the copy, or that
#   a running pod serves these bytes. The first is the canonical file's own gates, the
#   second is the render (the goldens), the third is the live check of the plan that
#   ships the runtime. This answers exactly one question: are the declared pairs the
#   same bytes.
#
# EXIT CODES — uniform across this repo's gates
#   0 = every declared pair is byte-identical (the count is printed)
#   1 = at least one pair has DRIFTED (pair, both md5s and the canonical side named)
#   2 = VOID: the list is missing, empty, comment-only or malformed; a path is
#       absolute, has a `..` segment, is a symlink or is listed twice; a copy or a
#       canonical file is missing, unreadable, empty or not a regular file; or md5sum
#       is not on PATH. A missing input is never clean: the natural bug here is a
#       `[ -f X ] || continue`, which would turn a deleted copy (the most complete
#       drift possible) into a pass.
#
# USAGE
#   bash scripts/check-k8s-copy-parity.sh             # the real tree
#   bash scripts/check-k8s-copy-parity.sh --selftest  # planted failure arms, then clean
#   COPY_PARITY_ROOT / COPY_PARITY_CONF override the root and the list (the selftest
#   uses them); COPY_PARITY_SELFTEST_FORMAT=tap prints TAP 13 for the RED-evidence
#   classifier.
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SELF="${BASH_SOURCE[0]}"
# No `dirname`: the no-md5sum selftest arm runs this script with a PATH holding only
# the tools the gate needs, so the script resolves itself with parameter expansion.
if [[ "$SELF" == */* ]]; then SCRIPT_DIR="${SELF%/*}"; else SCRIPT_DIR="."; fi
SCRIPT_DIR="$(cd "$SCRIPT_DIR" && pwd)" || { echo "VOID: cannot resolve the script directory" >&2; exit 2; }
DEFAULT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)" || { echo "VOID: cannot resolve the repo root" >&2; exit 2; }

# ---------------------------------------------------------------------------
# The comparison. Every problem that stops a pair being evaluated is collected and
# reported (VOID, rc 2, wins over drift); every evaluated pair is compared by md5.
# ---------------------------------------------------------------------------

# check_side <label> <path>: a path the gate can compare, or a VOID line on stderr.
check_side() {
    local label="$1" p="$2" f="$ROOT/$2"
    if [[ -L "$f" ]]; then
        echo "VOID: $label $p is a symlink — a symlinked copy compares the canonical file with itself" >&2; return 1
    fi
    [[ -e "$f" ]] || { echo "VOID: $label not found: $p — a missing input is never clean (exit 2, not 0)" >&2; return 1; }
    [[ -f "$f" ]] || { echo "VOID: $label $p is not a regular file" >&2; return 1; }
    [[ -r "$f" ]] || { echo "VOID: $label $p is not readable" >&2; return 1; }
    # -s as well as -r: an EMPTY file is readable and would md5-match another empty file.
    [[ -s "$f" ]] || { echo "VOID: $label $p is EMPTY — it would md5-match any other empty file" >&2; return 1; }
    return 0
}

# check_path <path> <conf line no>: the path rules of k8s/copy-parity.conf.
check_path() {
    local p="$1" ln="$2"
    case "$p" in
        /*) echo "VOID: $CONF:$ln: '$p' may not be absolute (paths are repo-relative)" >&2; return 1 ;;
    esac
    if [[ "/$p/" == */../* ]]; then
        echo "VOID: $CONF:$ln: '$p' may not contain '..' (a pair must stay inside the repo)" >&2; return 1
    fi
    return 0
}

run_check() {
    # Tooling first: without md5sum nothing below can be evaluated.
    command -v md5sum >/dev/null 2>&1 \
        || { echo "VOID: md5sum not on PATH — the comparison cannot be evaluated" >&2; return 2; }
    [[ -d "$ROOT" ]] || { echo "VOID: root not found: $ROOT" >&2; return 2; }
    [[ -e "$CONF" ]] || { echo "VOID: pair list not found: $CONF" >&2; return 2; }
    [[ -f "$CONF" && -r "$CONF" ]] || { echo "VOID: pair list not readable: $CONF" >&2; return 2; }

    local -a copies=() canons=() f=()
    local -A seen=()
    local raw line ln=0 voids=0
    while IFS= read -r raw || [[ -n "$raw" ]]; do
        ln=$((ln + 1))
        line="${raw%%#*}"          # `#` starts a comment; paths may not contain one
        read -r -a f <<< "$line"
        (( ${#f[@]} == 0 )) && continue
        if (( ${#f[@]} != 2 )); then
            echo "VOID: $CONF:$ln is malformed — want '<copy-path> <canonical-path>', got: $raw" >&2
            voids=$((voids + 1)); continue
        fi
        check_path "${f[0]}" "$ln" || { voids=$((voids + 1)); continue; }
        check_path "${f[1]}" "$ln" || { voids=$((voids + 1)); continue; }
        if [[ "${f[0]}" == "${f[1]}" ]]; then
            echo "VOID: $CONF:$ln names the same path on both sides ('${f[0]}') — a file compared with itself proves nothing" >&2
            voids=$((voids + 1)); continue
        fi
        if [[ -n "${seen[${f[0]}]+set}" ]]; then
            echo "VOID: $CONF:$ln: copy '${f[0]}' is listed twice (first at line ${seen[${f[0]}]}) — one copy has one canonical file" >&2
            voids=$((voids + 1)); continue
        fi
        seen["${f[0]}"]="$ln"
        copies+=("${f[0]}"); canons+=("${f[1]}")
    done < "$CONF"

    if (( ${#copies[@]} == 0 )); then
        echo "VOID: $CONF declares zero pairs — refusing to report parity over nothing" >&2
        return 2
    fi

    local i c k mc mk rc_c rc_k bc bk drift=0 ok_lines=()
    for i in "${!copies[@]}"; do
        c="${copies[$i]}"; k="${canons[$i]}"
        check_side copy "$c" || { voids=$((voids + 1)); continue; }
        check_side canonical "$k" || { voids=$((voids + 1)); continue; }
        # Captured on the SAME statement as the invocation: $? read after an echo or
        # a pipe reports THAT command's status.
        mc=$(md5sum -- "$ROOT/$c"); rc_c=$?
        mk=$(md5sum -- "$ROOT/$k"); rc_k=$?
        mc="${mc%% *}"; mk="${mk%% *}"
        if (( rc_c != 0 || rc_k != 0 )) || [[ ! "$mc" =~ ^[0-9a-f]{32}$ || ! "$mk" =~ ^[0-9a-f]{32}$ ]]; then
            echo "VOID: md5sum failed or printed no digest for pair $((i + 1)) (copy rc=$rc_c, canonical rc=$rc_k)" >&2
            voids=$((voids + 1)); continue
        fi
        bc=$(wc -c < "$ROOT/$c"); bk=$(wc -c < "$ROOT/$k")
        if [[ "$mc" == "$mk" ]]; then
            ok_lines+=("  ok   $c == $k  md5=$mc  bytes=${bc// /}")
        else
            drift=$((drift + 1))
            {
                echo "FAIL: pair $((i + 1)) has DRIFTED"
                echo "  copy     : $c  md5=$mc  bytes=${bc// /}"
                echo "  CANONICAL: $k  md5=$mk  bytes=${bk// /}"
                echo "  The right-hand path is canonical: copy it over the copy (cp $k $c),"
                echo "  unless the copy holds the intended edit; then copy it back first."
            } >&2
        fi
    done

    echo "check-k8s-copy-parity  ($(date -u +%Y-%m-%dT%H:%M:%SZ))  list: $CONF"
    (( ${#ok_lines[@]} > 0 )) && printf '%s\n' "${ok_lines[@]}"
    if (( voids > 0 )); then
        echo "VOID: $voids problem(s) above stop the parity check being evaluated (exit 2, never 0)" >&2
        return 2
    fi
    if (( drift > 0 )); then
        echo "FAIL: $drift of ${#copies[@]} pairs drifted. A copy that differs from its canonical file means one runtime serves an edit the other never received." >&2
        return 1
    fi
    echo "PASS: ${#copies[@]} pairs, all byte-equal."
    return 0
}

# ---------------------------------------------------------------------------
# --selftest: build a throwaway tree with two declared pairs (one text, one binary),
# plant one hole per arm, and require the gate to exit with the arm's rc AND name
# the hole. A plant that changes nothing is reported, never counted as caught.
# ---------------------------------------------------------------------------
selftest() {
    local fmt="${COPY_PARITY_SELFTEST_FORMAT:-plain}" st n=0 bad=0 tap=() line
    st="$(mktemp -d)" || { echo "VOID: --selftest could not create a temp dir" >&2; return 2; }
    # EXIT, not RETURN: the script exits straight after the selftest, and a RETURN
    # trap's scope across the nested helpers below is easy to get wrong.
    # shellcheck disable=SC2064
    trap "rm -rf '$st'" EXIT

    # A PATH holding every external tool the gate uses EXCEPT md5sum (the
    # no-md5sum arm). Built from the real locations, so nothing else changes.
    local fb="$st/fakebin" tool p
    mkdir -p "$fb"
    for tool in wc date mktemp rm; do
        p="$(command -v "$tool")" || { echo "VOID: --selftest needs '$tool' on PATH" >&2; return 2; }
        ln -s "$p" "$fb/$tool"
    done

    build_tree() {   # $1 = root
        mkdir -p "$1/canon" "$1/copies"
        printf 'parent=keycloak\nstyles=css/login.css css/jtoye.css\n' > "$1/canon/a.properties"
        printf '\x00\x01wOF2\xff\xfe\x10binary-font-bytes\x00' > "$1/canon/b.woff2"
        cp "$1/canon/a.properties" "$1/copies/a.properties"
        cp "$1/canon/b.woff2" "$1/copies/b.woff2"
        printf '# selftest list\n\ncopies/a.properties   canon/a.properties   # text pair\ncopies/b.woff2 canon/b.woff2\n' > "$1/list.conf"
    }

    # arm <name> <want-rc> <must-contain> <plant-shell> [env-path]
    arm() {
        local name="$1" want="$2" needle="$3" plant="$4" path="${5:-}" root out rc caught before after
        n=$((n + 1))
        root="$st/$name"; build_tree "$root"
        before="$(cd "$root" && md5sum canon/* copies/* list.conf 2>/dev/null; ls -la canon copies 2>/dev/null)"
        ( cd "$root" && eval "$plant" ) || { echo "VOID: arm $name: plant failed" >&2; bad=$((bad + 1)); return; }
        after="$(cd "$root" && md5sum canon/* copies/* list.conf 2>/dev/null; ls -la canon copies 2>/dev/null)"
        if [[ -n "$plant" && "$before" == "$after" && -z "$path" ]]; then
            line="ARM $name caught=no (plant did not apply)"; caught=no
        else
            if [[ -n "$path" ]]; then
                out="$(env PATH="$path" COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
            else
                out="$(COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
            fi
            if [[ "$rc" -eq "$want" ]] && [[ "$out" == *"$needle"* ]]; then
                caught=yes; line="ARM $name caught=yes"
            else
                caught=no; line="ARM $name caught=no (want rc $want and '$needle'; got rc=$rc: ${out//$'\n'/ })"
            fi
        fi
        [[ "$caught" == yes ]] || bad=$((bad + 1))
        if [[ "$fmt" == tap ]]; then
            if [[ "$caught" == yes ]]; then tap+=("ok $n - $name"); else
                tap+=("not ok $n - $name" "  ---" "  message: '${line//\'/}'" "  ..."); fi
        else
            echo "$line"
        fi
    }

    # One byte of the binary copy flipped: drift, rc 1, the pair named with both
    # md5s and the canonical side. printf + dd-free rewrite keeps the size equal.
    arm one-byte-edit 1 "CANONICAL: canon/b.woff2" \
        "printf '\x00\x01wOF2\xff\xfe\x10binary-font-bytez\x00' > copies/b.woff2"
    arm missing-copy 2 "copy not found: copies/a.properties" "rm copies/a.properties"
    arm missing-canonical 2 "canonical not found: canon/a.properties" "rm canon/a.properties"
    arm no-md5sum 2 "md5sum not on PATH" ":" "$fb"
    arm empty-conf 2 "zero pairs" ": > list.conf"
    arm comment-only-conf 2 "zero pairs" "printf '# nothing\n\n   # still nothing\n' > list.conf"
    arm missing-conf 2 "pair list not found" "rm list.conf"
    arm malformed-line 2 "malformed" "printf 'copies/a.properties\n' >> list.conf"
    arm self-pair 2 "same path on both sides" "printf 'copies/b.woff2 copies/b.woff2\n' > list.conf"
    arm symlink-copy 2 "is a symlink" "rm copies/a.properties && ln -s ../canon/a.properties copies/a.properties"
    arm escaping-path 2 "may not contain '..'" "printf 'copies/a.properties ../outside.txt\n' > list.conf"
    arm duplicate-copy 2 "listed twice" "printf 'copies/a.properties canon/a.properties\n' >> list.conf"

    # Clean: the unmodified tree passes and states its count.
    local root out rc
    n=$((n + 1)); root="$st/clean"; build_tree "$root"
    out="$(COPY_PARITY_ROOT="$root" COPY_PARITY_CONF="$root/list.conf" "$BASH" "$SELF" 2>&1)"; rc=$?
    if [[ "$rc" -eq 0 && "$out" == *"pairs, all byte-equal"* ]]; then
        line="CLEAN rc=0"; [[ "$fmt" == tap ]] && tap+=("ok $n - clean")
    else
        bad=$((bad + 1)); line="CLEAN rc=$rc (want 0 and 'pairs, all byte-equal'): ${out//$'\n'/ }"
        [[ "$fmt" == tap ]] && tap+=("not ok $n - clean" "  ---" "  message: '${line//\'/}'" "  ...")
    fi
    if [[ "$fmt" == tap ]]; then
        printf '%s\n' "TAP version 13" "1..$n" "${tap[@]}"
    else
        echo "$line"
        echo "selftest: $((n - bad))/$n arms as expected"
    fi
    (( bad == 0 ))
}

if [[ "${1:-}" == "--selftest" ]]; then
    selftest; exit $?
fi
[[ $# -eq 0 ]] || { echo "usage: $0 [--selftest]" >&2; exit 2; }

ROOT="${COPY_PARITY_ROOT:-$DEFAULT_ROOT}"
CONF="${COPY_PARITY_CONF:-$ROOT/k8s/copy-parity.conf}"
run_check
exit $?
