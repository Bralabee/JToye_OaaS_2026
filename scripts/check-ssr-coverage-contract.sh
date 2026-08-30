#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# check-ssr-coverage-contract.sh — every app route is CLASSIFIED, and every
# server-rendered one names a spec that reads the SERVED BYTES.
#
# WHY THIS EXISTS
#
#   A route-interception stub (`context.route`) answers a browser navigation and
#   cannot answer `request.get`. So the moment a route is converted from a client
#   component to a server component, a spec whose only coverage is a browser stub
#   goes on passing while it now proves nothing about the server render — measured
#   on the pre-#507 tree, where /shop/brixton-village-grill served 34,419 bytes
#   with 1 spinner, 0 <h1> and 0 occurrences of the shop's own name while every
#   DOM assertion stayed green (#542, #507).
#
#   Nothing failed at the moment of conversion, and nothing ever would have. This
#   gate is that failure: a page that becomes server-rendered without anyone
#   declaring it, or a declared server route whose raw-HTML assertion is deleted,
#   goes red in the same change that caused it.
#
# WHY A COUNT ALONE IS NOT ENOUGH
#
#   "N routes still to convert" is the shape #507 used, and it is vacuous in the
#   wrong direction: `git grep -l '"use client"'` matches the string in PROSE, so
#   the counter RISES when a conversion is well documented. Measured on this tree
#   today: 25 page.tsx files match that search, 21 actually carry the directive.
#   The four extra are pages that already converted and wrote about it. So this
#   gate does not count anything. It declares every route by name, and a route
#   nobody declared fails whether or not any number moved.
#
# AND WHY STALE ENTRIES ALSO FAIL
#
#   A declaration naming a page that no longer exists is a claim about coverage
#   that is no longer true, and a CLIENT entry is a standing statement that this
#   route needs no server-render coverage. Both are retired by this gate going
#   red rather than by someone remembering to look — the same contract as
#   check-e2e-skip-budget's S-3 and check-changelog-contract's C-2.
#
# WHAT IT ENFORCES
#   R-1   default-deny, BOTH directions: every discovered frontend/app/**/page.tsx
#         is declared EXACTLY once, and every declared page exists.
#   R-2a  class agreement: a CLIENT entry's page must carry the client directive as
#         its first statement; an SSR or STATIC entry's page must not.
#   R-2b  SSR entries: the named spec must exist and must contain the declared
#         literal INSIDE a raw-HTML call (`servedHtml(` or `request.get(`) on the
#         same line. A deleted raw-HTML assertion goes red even though the spec
#         still mentions the route in a title.
#   R-2c  STATIC entries: the page must load no data server-side — no import of
#         @/lib/storefront-server and no `fetch(` — measured AFTER stripping
#         comments, so a page's own docblock cannot satisfy or break the check.
#   R-2d  CLIENT and STATIC entries carry a non-empty, non-placeholder reason.
#   R-3   SELF-TEST of the classifier, in BOTH directions, run BEFORE the real
#         checks: a known server page must classify SERVER and a known client page
#         must classify CLIENT, so "all declared" cannot be reached by a classifier
#         that silently stopped working. A missing fixture is VOID, not a skip.
#
# HOW A PAGE IS CLASSIFIED
#
#   By DIRECTIVE POSITION, not by substring. Blank lines, `//` line comments and
#   leading `/* */` blocks are skipped, and the first remaining statement must be
#   exactly the client directive. `git grep -l`, and `head -3`, are both wrong:
#   grep is wrong today (by 4, above) and head is wrong on the first file that
#   grows a licence header.
#
# WHAT IT DOES NOT CHECK — stated rather than implied:
#
#   This is a STATIC gate. It reads text. It cannot prove that any route actually
#   server-rendered anything, and it does not run a browser, a build or a server.
#   The raw-HTML assertions in frontend/e2e/storefront-ssr-seo.spec.ts and
#   frontend/e2e/ssr-coverage.spec.ts are what prove that, against a running
#   frontend, in the nightly suite. Neither replaces the other: this one runs in
#   seconds on a runner with no stack and catches the omission at PR time, and a
#   structural check can pass while the function is still broken.
#
#   R-2c's "loads no data server-side" is exactly two tokens — the storefront
#   loader module and `fetch(`. A page that reached a datastore by some third
#   route would pass it. It is a tripwire for the common shape, not a proof.
#
#   A PASS says every route is declared and every declaration is true. It does NOT
#   say every route is covered: a CLIENT entry is a decision to have no
#   server-render coverage, recorded so it can be argued with.
#
# INPUT
#   scripts/gates/ssr-routes.conf   (override: SSR_ROUTES_CONF=<file>)
#   frontend/app/                   (override: SSR_APP_DIR=<dir>, used by the
#                                    zero-discovery arm; an override can only ever
#                                    produce a VOID or a failure, never a pass)
#
# EXIT CODES
#   0 = every page is declared and every declaration is true
#   1 = an undeclared page, a stale declaration, a class mismatch, a missing
#       raw-HTML assertion, a server-side load in a STATIC page, an empty reason
#   2 = VOID — conf missing, unknown or malformed directive, duplicate
#       declaration, ZERO pages discovered, or a self-test fixture missing.
#       "Found nothing" is never "clean".
#
# USAGE
#   scripts/check-ssr-coverage-contract.sh
#   scripts/check-ssr-coverage-contract.sh --classify <file>   # diagnostic only
#   SSR_ROUTES_CONF=<file> scripts/check-ssr-coverage-contract.sh
# ---------------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONF="${SSR_ROUTES_CONF:-$REPO_ROOT/scripts/gates/ssr-routes.conf}"
# TRAILING SLASH IS LOAD-BEARING. A symlinked start point truncates the walk to
# nothing, silently, with rc=0 and empty stderr — and many paths on the machine
# this repo is developed on are symlinks. The slash forces the walk through it.
APP_DIR="${SSR_APP_DIR:-$REPO_ROOT/frontend/app/}"
case "$APP_DIR" in */) ;; *) APP_DIR="$APP_DIR/" ;; esac
CLASSIFY_ONLY=""

echo "check-ssr-coverage-contract  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

void() { echo "VOID: $*" >&2; exit 2; }
fail_count=0
fail() { echo "FAIL: $*" >&2; fail_count=$((fail_count + 1)); }

while [ $# -gt 0 ]; do
    case "$1" in
        --classify) shift; [ $# -gt 0 ] || void "--classify needs a file argument"; CLASSIFY_ONLY="$1"; shift ;;
        -h|--help) sed -n '2,/^set -uo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; exit 0 ;;
        *) void "unknown argument: $1 (try --help)" ;;
    esac
done

command -v awk >/dev/null 2>&1 || void "awk is not installed — the classifier cannot run"
# `find` is a shim on some developer machines (it routes to bfs, which rejects
# GNU-only operands silently). Prefer the real binary when it is present.
FIND_BIN="find"
[ -x /usr/bin/find ] && FIND_BIN="/usr/bin/find"

# --- the classifier ------------------------------------------------------------------
# Finds the first STATEMENT — skipping blank lines, `//` line comments and leading
# `/* */` blocks — and reports CLIENT only when that statement is exactly the client
# directive. The two quote characters are built with sprintf rather than written out:
# this file is itself scanned by nothing today, but a gate whose source contains the
# token it looks for is one careless grep away from being satisfied by its own text.
CLASSIFY_AWK='
BEGIN { DQ = sprintf("%c", 34); SQ = sprintf("%c", 39); inblock = 0; done = 0 }
done { next }
{
    line = $0
    while (1) {
        if (inblock) {
            idx = index(line, "*/")
            if (idx == 0) { line = ""; break }
            line = substr(line, idx + 2); inblock = 0; continue
        }
        idx = index(line, "/*")
        if (idx > 0) {
            before = substr(line, 1, idx - 1)
            rest = substr(line, idx + 2)
            gsub(/^[ \t]+|[ \t]+$/, "", before)
            if (before != "") { line = before; break }
            line = rest; inblock = 1; continue
        }
        break
    }
    sub(/\/\/.*/, "", line)
    gsub(/^[ \t]+|[ \t]+$/, "", line)
    if (line == "") next
    if (line == DQ "use client" DQ || line == SQ "use client" SQ \
        || line == DQ "use client" DQ ";" || line == SQ "use client" SQ ";") {
        print "CLIENT"
    } else {
        print "SERVER"
    }
    done = 1
    exit
}
END { if (!done) print "SERVER" }
'

# Strips comments so a content match reads CODE and not a page docblock. `://` is
# protected first, or a line-comment strip would eat everything after a URL scheme.
STRIP_AWK='
BEGIN { inblock = 0 }
{
    line = $0; out = ""
    while (1) {
        if (inblock) {
            idx = index(line, "*/")
            if (idx == 0) { line = ""; break }
            line = substr(line, idx + 2); inblock = 0; continue
        }
        idx = index(line, "/*")
        if (idx > 0) {
            out = out substr(line, 1, idx - 1)
            line = substr(line, idx + 2); inblock = 1; continue
        }
        break
    }
    out = out line
    gsub(/:\/\//, ":\001\001", out)
    sub(/\/\/.*/, "", out)
    print out
}
'

classify() { awk "$CLASSIFY_AWK" "$1" 2>/dev/null; }
strip_comments() { awk "$STRIP_AWK" "$1" 2>/dev/null; }

if [ -n "$CLASSIFY_ONLY" ]; then
    [ -f "$CLASSIFY_ONLY" ] || void "--classify: no such file: $CLASSIFY_ONLY"
    echo "  classify  : $(classify "$CLASSIFY_ONLY")  $CLASSIFY_ONLY"
    echo "DIAGNOSTIC ONLY — no assertion was run. Re-run with no arguments to check the contract."
    exit 0
fi

[ -f "$CONF" ] || void "config not found: $CONF — the manifest is the gate; without it nothing is declared"

# --- R-3  self-test of the classifier, BOTH directions, BEFORE anything else ---------
# A classifier that silently stopped working would report every page SERVER (or every
# page CLIENT) and R-2a would then pass over everything, which is the classic vacuous
# assertion. The fixtures are named rather than discovered so a rename VOIDs instead of
# quietly removing the self-test.
SELFTEST_SERVER="$REPO_ROOT/frontend/app/shop/page.tsx"
SELFTEST_CLIENT="$REPO_ROOT/frontend/app/track/page.tsx"
[ -f "$SELFTEST_SERVER" ] || void "R-3 self-test fixture missing: $SELFTEST_SERVER — the classifier cannot be proven, so nothing it says is evidence"
[ -f "$SELFTEST_CLIENT" ] || void "R-3 self-test fixture missing: $SELFTEST_CLIENT — the classifier cannot be proven, so nothing it says is evidence"
st_server="$(classify "$SELFTEST_SERVER")"
st_client="$(classify "$SELFTEST_CLIENT")"
[ "$st_server" = "SERVER" ] || void "R-3 self-test: classifier called the known SERVER page $SELFTEST_SERVER '$st_server'"
[ "$st_client" = "CLIENT" ] || void "R-3 self-test: classifier called the known CLIENT page $SELFTEST_CLIENT '$st_client'"

# --- discovery -----------------------------------------------------------------------
# Enumerated with find and read BY NAME. Not `rg` (it does not exist inside a
# `bash script.sh` — it is a shell function the harness injects, so it dies rc=127,
# which is indistinguishable from "no matches"), not a recursive `grep` (routes to
# ugrep with --ignore-files, silently honouring .gitignore), and not `git grep -l`
# (measured wrong by 4, see the header).
mapfile -t PAGE_ABS < <("$FIND_BIN" "$APP_DIR" -type f -name 'page.tsx' 2>/dev/null | sort)
[ "${#PAGE_ABS[@]}" -gt 0 ] \
    || void "discovered ZERO page.tsx under $APP_DIR — 'found nothing' is never 'clean'; a walk with no inputs cannot prove anything"

PAGES=()
for abs in "${PAGE_ABS[@]}"; do
    PAGES+=("${abs#"$APP_DIR"}")
done

# --- parse the manifest --------------------------------------------------------------
DECL_PATH=(); DECL_KIND=(); DECL_SPEC=(); DECL_LITERAL=(); DECL_REASON=(); DECL_LINE=()
lineno=0
while IFS= read -r raw || [ -n "$raw" ]; do
    lineno=$((lineno + 1))
    line="${raw%%$'\r'}"
    trimmed="$(printf '%s' "$line" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
    case "$trimmed" in ''|'#'*) continue ;; esac

    directive="${trimmed%% *}"
    case "$directive" in
        SSR)
            read -r _d _page _spec _literal _extra <<< "$trimmed"
            [ -n "${_page:-}" ] && [ -n "${_spec:-}" ] && [ -n "${_literal:-}" ] \
                || void "malformed SSR directive at $CONF:$lineno — expected: SSR <page> <spec> <literal>"
            [ -z "${_extra:-}" ] \
                || void "SSR directive at $CONF:$lineno has trailing fields ('$_extra') — refusing to guess which is the literal"
            DECL_PATH+=("$_page"); DECL_KIND+=("SSR"); DECL_SPEC+=("$_spec")
            DECL_LITERAL+=("$_literal"); DECL_REASON+=(""); DECL_LINE+=("$lineno")
            ;;
        STATIC|CLIENT)
            read -r _d _page _reason <<< "$trimmed"
            [ -n "${_page:-}" ] \
                || void "malformed $directive directive at $CONF:$lineno — expected: $directive <page> <reason...>"
            DECL_PATH+=("$_page"); DECL_KIND+=("$directive"); DECL_SPEC+=("")
            DECL_LITERAL+=(""); DECL_REASON+=("${_reason:-}"); DECL_LINE+=("$lineno")
            ;;
        *)
            void "unknown directive '$directive' at $CONF:$lineno — refusing to guess"
            ;;
    esac
done < "$CONF"

[ "${#DECL_PATH[@]}" -gt 0 ] || void "$CONF declares nothing — a manifest with no entries cannot enforce R-1"

# Duplicates are VOID, not a failure: with a path declared twice the manifest has two
# answers for one route and the gate cannot say which is being asserted.
for ((i = 0; i < ${#DECL_PATH[@]}; i++)); do
    for ((j = i + 1; j < ${#DECL_PATH[@]}; j++)); do
        [ "${DECL_PATH[i]}" = "${DECL_PATH[j]}" ] \
            && void "duplicate declaration of '${DECL_PATH[i]}' ($CONF:${DECL_LINE[i]} and $CONF:${DECL_LINE[j]}) — one route, two answers"
    done
done

n_ssr=0; n_static=0; n_client=0
for kind in "${DECL_KIND[@]}"; do
    case "$kind" in
        SSR) n_ssr=$((n_ssr + 1)) ;;
        STATIC) n_static=$((n_static + 1)) ;;
        CLIENT) n_client=$((n_client + 1)) ;;
    esac
done

echo "  app dir   : $APP_DIR"
echo "  config    : $CONF"
echo "  discovered: ${#PAGES[@]} page.tsx"
echo "  declared  : $n_ssr SSR, $n_static STATIC, $n_client CLIENT (${#DECL_PATH[@]} total)"
echo "  R-3 self  : classifier says SERVER for ${SELFTEST_SERVER#"$REPO_ROOT/"} and CLIENT for ${SELFTEST_CLIENT#"$REPO_ROOT/"}"

declared_index() {
    local want="$1" k
    for ((k = 0; k < ${#DECL_PATH[@]}; k++)); do
        [ "${DECL_PATH[k]}" = "$want" ] && { printf '%s' "$k"; return 0; }
    done
    return 1
}

# --- R-1a  every discovered page is declared -----------------------------------------
for p in "${PAGES[@]}"; do
    declared_index "$p" >/dev/null \
        || fail "R-1 undeclared page: $p — a route nobody declared is the moment #542 says the failure must fire. Add an SSR, STATIC or CLIENT line to $CONF."
done

# --- R-1b  every declaration names a page that exists --------------------------------
for ((i = 0; i < ${#DECL_PATH[@]}; i++)); do
    [ -f "$APP_DIR${DECL_PATH[i]}" ] \
        || fail "R-1 stale declaration at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' names no page under $APP_DIR — a declaration that outlived its route is a lie about coverage; delete it."
done

# --- R-2  per-entry assertions --------------------------------------------------------
for ((i = 0; i < ${#DECL_PATH[@]}; i++)); do
    page="$APP_DIR${DECL_PATH[i]}"
    kind="${DECL_KIND[i]}"
    [ -f "$page" ] || continue   # already reported by R-1b

    # R-2a  class agreement
    verdict="$(classify "$page")"
    if [ "$kind" = "CLIENT" ] && [ "$verdict" != "CLIENT" ]; then
        fail "R-2a class mismatch at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' is declared CLIENT but carries no client directive as its first statement (classifier: $verdict)"
    fi
    if [ "$kind" != "CLIENT" ] && [ "$verdict" = "CLIENT" ]; then
        fail "R-2a class mismatch at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' is declared $kind but IS a client component — a client component cannot serve its content in the raw HTML"
    fi

    case "$kind" in
        SSR)
            spec="$REPO_ROOT/${DECL_SPEC[i]}"
            if [ ! -f "$spec" ]; then
                fail "R-2b SSR entry at $CONF:${DECL_LINE[i]}: spec '${DECL_SPEC[i]}' does not exist — a server route whose coverage names a missing file has no coverage"
                continue
            fi
            lit="${DECL_LITERAL[i]}"
            hit=0
            while IFS= read -r sline; do
                case "$sline" in
                    *"servedHtml("*|*"request.get("*)
                        case "$sline" in *"$lit"*) hit=1; break ;; esac
                        ;;
                esac
            done < "$spec"
            [ "$hit" -eq 1 ] \
                || fail "R-2b SSR entry at $CONF:${DECL_LINE[i]}: '${DECL_SPEC[i]}' has no raw-HTML call (servedHtml( or request.get() carrying the literal $lit — the served bytes for '${DECL_PATH[i]}' are asserted by nothing. A browser stub satisfies a DOM assertion and cannot satisfy this one; that is the whole point (#542)."
            ;;
        STATIC)
            code="$(strip_comments "$page")"
            case "$code" in
                *"storefront-server"*)
                    fail "R-2c STATIC entry at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' imports the storefront server loader, so it DOES load data server-side — declare it SSR and name the spec that reads its served bytes"
                    ;;
            esac
            case "$code" in
                *"fetch("*)
                    fail "R-2c STATIC entry at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' calls fetch( in code (comments stripped), so it DOES load data — declare it SSR and name the spec that reads its served bytes"
                    ;;
            esac
            ;;
    esac

    # R-2d  a reason that is a reason
    if [ "$kind" = "CLIENT" ] || [ "$kind" = "STATIC" ]; then
        reason="${DECL_REASON[i]}"
        trimmed_reason="$(printf '%s' "$reason" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')"
        if [ -z "$trimmed_reason" ]; then
            fail "R-2d $kind entry at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' carries no reason — an undeclared-coverage decision with no stated reason cannot be argued with"
        else
            placeholder=0
            case "$(printf '%s' "$trimmed_reason" | tr '[:upper:]' '[:lower:]')" in
                todo*|tbd*|n/a|na|-|?|reason|fixme*|later*|"see above"*)
                    placeholder=1
                    fail "R-2d $kind entry at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' reason '$trimmed_reason' is a placeholder, not a fact about the route"
                    ;;
            esac
            # Anything under ten characters is a label, not a reason. Written as a
            # length test rather than a run of '?' globs so it says what it means.
            if [ "$placeholder" -eq 0 ] && [ "${#trimmed_reason}" -lt 10 ]; then
                fail "R-2d $kind entry at $CONF:${DECL_LINE[i]}: '${DECL_PATH[i]}' reason '$trimmed_reason' is too short to be a reason"
            fi
        fi
    fi
done

if [ "$fail_count" -eq 0 ]; then
    echo "PASS: all ${#PAGES[@]} app route(s) are declared, and every declaration is true."
    echo "      NOTE: a declared CLIENT route is a DECISION to have no server-render"
    echo "      coverage, not coverage. This gate reads text; only the raw-HTML specs"
    echo "      running against a live frontend prove a route server-rendered anything."
    exit 0
fi
echo "FAILED: $fail_count SSR-coverage contract violation(s) (see above)." >&2
exit 1
