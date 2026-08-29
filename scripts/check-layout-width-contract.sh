#!/usr/bin/env bash
#
# Gate: the phase-35 horizontal layout contract is enforced, not merely written down.
#
# WHY THIS EXISTS
#
#   The 1400px cap this phase deleted was the stock shadcn `container` scaffold block,
#   shipped verbatim and never chosen. It survived for the life of the product because
#   nothing was watching. CLAUDE.md is unambiguous about which of a document and a script
#   survives: "when a recurring failure is found, the fix is a script that fails loudly —
#   not a firmer instruction." Plan 35-11 writes the document a person reads. This is the
#   thing that stops the contract eroding.
#
#   Its manifest is docs/architecture/layout-tiers.tsv. The gate reads the manifest rather
#   than hard-coding a surface list, so the declared surface set and the enforcement
#   cannot disagree.
#
# WHAT IT ASSERTS  (each assertion names the defect it catches, because a gate whose
# purpose is not written down is the next thing someone deletes)
#
#   G-1 SCATTERED LITERALS. Each tier class literal appears exactly once in the frontend,
#       in the file the manifest names `vocabulary`. Catches a developer typing the class
#       directly onto an element — which works today and breaks silently the moment a tier
#       value changes. Also asserts the index tier has no cap class anywhere: the index
#       tier deliberately maps to the empty string, so a `max-w-index` utility appearing
#       would mean someone gave the fluid tier a width.
#
#   G-2 THE RETIRED SCAFFOLD CLASS. The shadcn container class appears in no class context
#       in shipped app/ or components/ source. Catches the 1400px default being
#       reintroduced. Matched as a whitespace-delimited TOKEN inside a string literal in a
#       class context, never as a bare substring.
#
#       MEASURED, and the measurement corrects PATTERNS.md. A naive case-sensitive
#       substring search over comment-stripped app/ + components/ returns 371 lines across
#       55 files against 0 real ones. PATTERNS attributes those to DialogContent,
#       CardContent and TabsContent — that attribution is WRONG and was checked rather
#       than inherited: none of those three identifiers contains the string "container"
#       ("Content" is not "container"). What they actually are is Testing Library's
#       `container` local: 189 bare occurrences plus 70 `container.querySelector`, 30
#       `container.querySelectorAll`, 27 `container.firstElementChild` and so on, every
#       one of them in a test file. In shipped non-test source the case-sensitive count is
#       ZERO. Case-INSENSITIVELY it is 10 — `ResponsiveContainer` x8 (recharts) and
#       `staggerContainer` x2 (a framer-motion variant) — which is what a sloppier form of
#       this check would red on. Both are identifiers rather than string literals, so the
#       token-inside-a-string-literal rule is immune to them by construction, and the G-2
#       control arm proves it on a real ResponsiveContainer rather than on a fabricated
#       example.
#
#   G-3 THE CONFIG, BOTH HALVES, INDEPENDENTLY. tailwind.config.ts declares no `container`
#       key inside `theme`, AND sets `container: false` inside `corePlugins`. Deleting the
#       theme block alone is not enough — measured during plan 35-01: the plugin then falls
#       back to the DEFAULT screens and emits five media queries, one per breakpoint, which
#       is strictly worse than the single 1400px query the tree had before. A gate that
#       checks one half is half a gate, so the two halves are separate assertions with
#       separate fail arms.
#
#   G-4 WIDTH-FAMILY PARITY. Every member of a declared `parity:` family carries the same
#       band token, and no other. Catches the exact defect plan 35-07 fixed on the shop
#       detail route, where the skeleton was 384px wider than the content and the page
#       narrowed on hydration. THIS ASSERTION IS OWED: 35-07 ran that break arm and
#       recorded that it produced no red anywhere on the tree, because this gate did not
#       exist yet. It is a FAMILY, not a pair — /shop/[slug] has three members and a check
#       hard-coded to compare two would leave the third free to drift while reporting the
#       route as covered.
#
#   G-5 NO WIDTH LITERAL IN THE BUILD CONFIG. tailwind.config.ts imports the widths by
#       relative specifier and carries no raw pixel band value of its own. Catches the
#       config and the constants module drifting — the same failure lib/cart-identity.ts's
#       docblock records for a different string.
#
#   G-6 MANIFEST COMPLETENESS, BOTH DIRECTIONS. Every file the manifest assigns a tier
#       declares that tier, every file the manifest marks N/A declares none, AND every
#       shipped file that declares a tier appears in the manifest with that tier. Catches,
#       in the first direction, a tier silently dropped by a refactor; in the second, a
#       surface tiered by someone who did not update the contract. One direction alone is
#       half a check.
#
#   G-7 THE INDEX TIER IS UNCAPPED, AT THE DECLARING ELEMENT. Every element declaring the
#       index tier carries no band token and no tier class in its own opening tag.
#       Catches someone "tidying" a fluid resource index by giving it a cap — the one tier
#       whose contract is an ABSENCE, and therefore the one no width measurement can
#       distinguish from a bug.
#
#       SCOPED TO THE ELEMENT, NEVER THE FILE, and that is load-bearing. Measured: all
#       five original index pages legitimately carry max-w-2xl on a modal DialogContent
#       (orders:782,958 · products:641 · customers:419 · shops:430). Those are Radix
#       portals rendered outside the page container, so the page tier cannot reach them.
#       A file-scoped version of this check would red on correct code.
#
# EXIT CODES
#   0  every claim holds
#   1  at least one claim is violated — named, with its file
#   2  VOID — missing tooling, an unreadable/empty/malformed manifest, a manifest row
#      naming a file that does not exist, a scan that discovered no files, or an element
#      whose opening tag could not be delimited
#
#   2 is load-bearing. "Found nothing" is never "clean": this repo has rediscovered that
#   failure mode repeatedly, most recently in a deny-list guard that could not fire at all.
#
# FOUR TRAPS THIS SCRIPT HAS TO DODGE ITSELF
#
#   1. A GATE THAT FIRES ON ITS OWN DEFINITION. This script names every token it forbids,
#      and so does docs/architecture/layout-tiers.tsv and plan 35-11's contract document.
#      All three live OUTSIDE the scanned set — every scan below is rooted at frontend/ and
#      restricted to .ts/.tsx — so no amount of prose here can satisfy or trip a check.
#
#   2. A COMMENT SATISFYING A GREP. Every file is comment-stripped BEFORE it is counted.
#      A bare occurrence count over an unfiltered file is forbidden here, and the reason is
#      measured rather than theoretical: on the clean tree the raw counts are
#      max-w-shell=2, max-w-detail=1, max-w-marketing=2 — the extras being
#      lib/__tests__/layout-widths-css.test.ts:80 and app/__tests__/landing.test.tsx:248,
#      both comments, plus two more in e2e/. In shipped, comment-stripped source it is
#      genuinely 1/1/1. Without stripping this gate would red on two pre-existing lines it
#      did not cause.
#
#   3. AN EXIT STATUS READ LATE. A status captured after an intervening command reports the
#      wrong command's result, and in a gate that means a violation reported as clean.
#      Every status here is captured on the same statement as the command that produced it.
#
#   4. `cmd | grep -q X` UNDER pipefail INVERTS ON MATCH — grep exits at the first hit, the
#      writer takes SIGPIPE, pipefail promotes it to 141, and the guard fails OPEN on the
#      case it exists to catch. Here-strings and file arguments only.
#
# SEARCH DISCIPLINE, and it is not optional in this repository
#
#   Files are enumerated with `find` and then searched BY NAME. Two reasons, both measured
#   on this machine: `rg` does not exist inside a `bash script.sh` (it is a shell function
#   the harness injects, so it dies rc=127, which is indistinguishable from "no matches"),
#   and the `grep` function routes to a tool that honours ignore files, so a recursive
#   search silently skips content. scripts/check-gate-enforcement.sh documents the same
#   reasoning in its own header. Start points carry a trailing slash because a symlinked
#   start point truncates find to nothing, silently, at rc=0.
#
# WHICH ASSERTIONS ARE GENUINELY BLOCKING
#
#   All seven. This gate is STATIC by construction — it reads .ts/.tsx, one config and one
#   TSV out of the checkout, touches no database, starts no browser and makes no network
#   call — so it says the same thing on a hosted runner as it does locally, and it runs on
#   every pull request in the Operational Contracts job. That distinction matters on this
#   phase: the full-suite Playwright lane is DARK (#683), so the phase's browser-measured
#   width assertions are "covered by a spec that no current tree executes". These are not.

set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

# Overridable so the VOID direction is testable against an empty manifest without inventing
# a second code path.
MANIFEST="${LAYOUT_TIERS_MANIFEST:-$REPO_ROOT/docs/architecture/layout-tiers.tsv}"
FRONTEND="$REPO_ROOT/frontend"
TW_CONFIG="$FRONTEND/tailwind.config.ts"

void() { echo "VOID: $*" >&2; exit 2; }

VIOLATIONS=0
violation() { echo "  VIOLATION: $*" >&2; VIOLATIONS=$((VIOLATIONS + 1)); }

CLAIMS=0
claim() { CLAIMS=$((CLAIMS + 1)); }

# --- tooling ------------------------------------------------------------------------------
for tool in grep find awk sed sort mktemp; do
    command -v "$tool" >/dev/null 2>&1 || void "$tool not found — a gate that cannot run must not report clean"
done
[ -d "$FRONTEND" ]   || void "no frontend/ directory under $REPO_ROOT"
[ -f "$TW_CONFIG" ]  || void "tailwind config not found: $TW_CONFIG"
[ -f "$MANIFEST" ]   || void "manifest not found: $MANIFEST — the surface set is required, not optional"
[ -r "$MANIFEST" ]   || void "manifest not readable: $MANIFEST"

STRIP_DIR="$(mktemp -d)" || void "could not create a scratch directory for comment-stripped copies"
trap 'rm -rf -- "$STRIP_DIR"' EXIT

# --- the comment stripper -----------------------------------------------------------------
#
# Removes /* ... */ block comments (including the JSX {/* ... */} form, which is the same
# thing) and // line comments, preserving line numbering so a violation can still be
# reported at its real line. `//` is left alone when the preceding character is `:`, so a
# URL inside a string survives.
STRIP_AWK='
{
    line = $0; out = ""; i = 1; n = length(line)
    while (i <= n) {
        if (incomment) {
            e = index(substr(line, i), "*/")
            if (e == 0) { i = n + 1 } else { incomment = 0; i = i + e + 1 }
            continue
        }
        two = substr(line, i, 2)
        if (two == "/*") { incomment = 1; i += 2; continue }
        if (two == "//") {
            prev = (i > 1) ? substr(line, i - 1, 1) : ""
            if (prev != ":") { break }
        }
        out = out substr(line, i, 1)
        i++
    }
    print out
}'

# stripped <repo-relative-path> -> echoes the path of a cached comment-stripped copy
stripped() {
    local rel="$1" out
    out="$STRIP_DIR/${rel//\//__}"
    if [ ! -f "$out" ]; then
        awk "$STRIP_AWK" "$REPO_ROOT/$rel" > "$out" || void "could not comment-strip $rel"
    fi
    printf '%s' "$out"
}

# --- file enumeration ---------------------------------------------------------------------
#
# ALL_SRC     : every .ts/.tsx under app/ components/ lib/ e2e/, TESTS INCLUDED. G-1's scope.
# SHIPPED_SRC : the same minus __tests__/ directories and *.test.* / *.spec.* files.
#               G-2, G-6's discovery direction and G-7's scope.
ALL_SRC=()
while IFS= read -r f; do ALL_SRC+=("${f#"$REPO_ROOT"/}"); done < <(
    find "$FRONTEND/app/" "$FRONTEND/components/" "$FRONTEND/lib/" "$FRONTEND/e2e/" \
        -type f \( -name '*.ts' -o -name '*.tsx' \) | sort
)
[ "${#ALL_SRC[@]}" -gt 0 ] || void "no .ts/.tsx files found under frontend/{app,components,lib,e2e} — a scan with no inputs proves nothing"

SHIPPED_SRC=()
while IFS= read -r f; do SHIPPED_SRC+=("${f#"$REPO_ROOT"/}"); done < <(
    find "$FRONTEND/app/" "$FRONTEND/components/" \
        -type f \( -name '*.ts' -o -name '*.tsx' \) \
        -not -path '*/__tests__/*' -not -name '*.test.ts' -not -name '*.test.tsx' \
        -not -name '*.spec.ts' -not -name '*.spec.tsx' | sort
)
[ "${#SHIPPED_SRC[@]}" -gt 0 ] || void "no shipped .ts/.tsx files found under frontend/{app,components} — a scan with no inputs proves nothing"

# --- the manifest ---------------------------------------------------------------------------
#
# EXACTLY TWO non-empty tab-separated fields per data row. A row that does not split that way
# is a VOID and not a skip: consecutive tabs COLLAPSE under a tab field separator, so an empty
# field shifts every later column and the gate would then be reasoning about the wrong data
# while reporting success.
declare -a M_PATH=() M_CLAIM=()
ROWNO=0
while IFS= read -r raw || [ -n "$raw" ]; do
    ROWNO=$((ROWNO + 1))
    case "$raw" in ''|'#'*) continue ;; esac
    [ -n "${raw//[[:space:]]/}" ] || continue

    # Field split done by awk on a literal tab, then validated. Reading with IFS=$'\t' in the
    # shell would silently collapse a run of tabs and hide exactly the defect being guarded.
    nf="$(awk -F'\t' '{print NF}' <<< "$raw")"
    [ "$nf" = "2" ] || void "manifest row ${ROWNO} does not split into exactly 2 tab-separated fields (got ${nf}): ${raw}"
    p="$(awk -F'\t' '{print $1}' <<< "$raw")"
    c="$(awk -F'\t' '{print $2}' <<< "$raw")"
    [ -n "$p" ] || void "manifest row ${ROWNO} has an empty path field"
    [ -n "$c" ] || void "manifest row ${ROWNO} has an empty claim field"
    [ -f "$REPO_ROOT/$p" ] || void "manifest row ${ROWNO} names a file that does not exist: $p"

    M_PATH+=("$p")
    M_CLAIM+=("$c")
done < "$MANIFEST"

ROWS="${#M_PATH[@]}"
[ "$ROWS" -gt 0 ] || void "manifest contains no data rows — refusing to report clean over an empty manifest"

# The vocabulary row tells G-1 which file is ALLOWED to hold the tier class literals, so
# moving the vocabulary is a manifest edit rather than a gate edit.
VOCAB=""
for i in "${!M_PATH[@]}"; do
    if [ "${M_CLAIM[$i]}" = "vocabulary" ]; then
        [ -z "$VOCAB" ] || void "manifest declares more than one 'vocabulary' file (${VOCAB} and ${M_PATH[$i]}) — the single-home property is what G-1 asserts, so it cannot have two homes"
        VOCAB="${M_PATH[$i]}"
    fi
done
[ -n "$VOCAB" ] || void "manifest declares no 'vocabulary' file — G-1 has no home to check against"

# Reject any claim the grammar does not define, rather than skipping it. A typo'd claim that
# silently does nothing is a row that reads as enforced and is not.
for i in "${!M_CLAIM[@]}"; do
    case "${M_CLAIM[$i]}" in
        shell|index|detail|marketing|'N/A'|vocabulary) : ;;
        parity:*:*) : ;;
        *) void "manifest row for ${M_PATH[$i]} carries an unknown claim '${M_CLAIM[$i]}' — the grammar is documented at the top of the manifest" ;;
    esac
done

echo "Layout width contract gate"
echo "  manifest   : ${MANIFEST#"$REPO_ROOT"/}  (${ROWS} rows)"
echo "  vocabulary : ${VOCAB}"

# ============================================================================================
# G-1  SCATTERED LITERALS
# ============================================================================================
#
# The three capped tiers are `shell`, `detail` and `marketing`; the index tier deliberately
# maps to the empty string and therefore has no utility to find. Each capped tier's literal
# must occur EXACTLY ONCE across every .ts/.tsx under app/ components/ lib/ e2e/ — tests
# included, deliberately.
#
# WHY TESTS ARE IN SCOPE HERE, and this is a decision rather than an oversight. A test that
# restates the literal is the same drift hazard as a component that does: it would keep
# passing after the vocabulary changed. Plan 35-02 already built to this rule — its suite
# asserts the DERIVATION (`max-w-` + the tier key) instead of restating the strings, and its
# summary records that had it restated them, this gate would have been counting its own test
# file. Measured on the clean tree, comment-stripped: 1 / 1 / 1. The escape hatch for any
# future test that needs the string is to import WIDTH_TIER_CLASS, which is the same escape
# hatch application code has.
#
# CONTRAST WITH G-2 BELOW, which excludes tests for a measured reason of its own.
G1_TIERS=(shell detail marketing)
for tier in "${G1_TIERS[@]}"; do
    claim
    token="max-w-${tier}"
    hits=()
    for rel in "${ALL_SRC[@]}"; do
        # -o so occurrences are counted rather than lines, with an explicit right-hand
        # boundary so `max-w-shell` cannot be satisfied by a longer token.
        n="$(grep -oE "${token}([^A-Za-z0-9_-]|$)" "$(stripped "$rel")" | wc -l)"
        [ "$n" -gt 0 ] && hits+=("${rel}:${n}")
    done
    total=0
    for h in "${hits[@]:-}"; do [ -n "$h" ] && total=$((total + ${h##*:})); done
    if [ "$total" -ne 1 ] || [ "${#hits[@]}" -ne 1 ] || [ "${hits[0]%%:*}" != "$VOCAB" ]; then
        violation "G-1: '${token}' must appear exactly once, in ${VOCAB}. Found ${total} occurrence(s) in ${#hits[@]} file(s): ${hits[*]:-none}"
    fi
done

# The index tier has no width, on purpose. A `max-w-index` utility appearing anywhere means
# somebody gave the fluid tier a cap, which silently narrows exactly the surfaces this phase
# exists to widen.
claim
idx_hits=()
for rel in "${ALL_SRC[@]}"; do
    n="$(grep -oE "max-w-index([^A-Za-z0-9_-]|$)" "$(stripped "$rel")" | wc -l)"
    [ "$n" -gt 0 ] && idx_hits+=("${rel}:${n}")
done
[ "${#idx_hits[@]}" -eq 0 ] || violation "G-1: the index tier declares no width by design, but a 'max-w-index' literal exists: ${idx_hits[*]}"

# ============================================================================================
# G-2  THE RETIRED SCAFFOLD CLASS
# ============================================================================================
#
# A violation is the whitespace-delimited token `container` (or a variant-prefixed form such
# as `sm:container`) inside a string literal, on a line that is in class context.
#
# CLASS CONTEXT rather than bare substring, and the difference is measured: on this tree a
# naive substring search returns 371 comment-stripped lines across 55 files, because
# DialogContent, CardContent, TabsContent and Testing-Library `container` locals all contain
# the word. Against exactly 0 real ones.
#
# TESTS ARE EXCLUDED FROM THIS ONE, and unlike G-1 that is forced rather than chosen.
# components/dashboard/__tests__/dashboard-shell.test.tsx:188 asserts
# `classList.contains("container")` is false — it must NAME the token to assert its absence,
# so a gate that scanned it would fire on its own guard. Two further hits measured in test
# files are English prose inside `it(...)` titles ("the container bind address"), which is
# the other reason: a test title is not a class list.
CONTAINER_HITS=()
for rel in "${SHIPPED_SRC[@]}"; do
    sf="$(stripped "$rel")"
    # Look back up to 6 lines for a class context opener, so a multi-line
    # `className={cn(` argument list is still covered.
    while IFS= read -r hit; do
        [ -n "$hit" ] || continue
        CONTAINER_HITS+=("${rel}:${hit}")
    done < <(
        awk '
            { ctx[NR % 7] = $0 }
            {
                inctx = 0
                for (k = 0; k < 7; k++) {
                    j = (NR - k) % 7
                    if (j < 0) j += 7
                    if (NR - k < 1) break
                    if (ctx[j] ~ /className|class=|cn\(/) { inctx = 1; break }
                }
                if (!inctx) next
                line = $0
                # Pull out every quoted / backticked string on the line and token-split it.
                rest = line
                while (match(rest, /"[^"]*"|'"'"'[^'"'"']*'"'"'|`[^`]*`/)) {
                    s = substr(rest, RSTART + 1, RLENGTH - 2)
                    rest = substr(rest, RSTART + RLENGTH)
                    n = split(s, toks, /[ \t]+/)
                    for (t = 1; t <= n; t++) {
                        if (toks[t] ~ /^([A-Za-z0-9_-]+:)*container$/) { print NR ": " line; next }
                    }
                }
            }
        ' "$sf"
    )
done
claim
[ "${#CONTAINER_HITS[@]}" -eq 0 ] || {
    for h in "${CONTAINER_HITS[@]}"; do echo "    $h" >&2; done
    violation "G-2: the retired shadcn 'container' class is applied in ${#CONTAINER_HITS[@]} place(s). It caps at 1400px by default and the core plugin is switched off, so the class now generates NOTHING — the element renders uncapped. Use WIDTH_TIER_CLASS from ${VOCAB}."
}

# ============================================================================================
# G-3  THE CONFIG, BOTH HALVES, INDEPENDENTLY
# ============================================================================================
#
# block_of prints the body of a top-level object key by brace depth, so a nested `container`
# inside `theme` is found wherever it sits rather than only on a line the author formatted
# a particular way.
TW_STRIPPED="$(stripped frontend/tailwind.config.ts)"

block_of() { # <stripped-file> <key>
    awk -v key="$2" '
        BEGIN { started = 0; depth = 0 }
        !started {
            if ($0 ~ ("^[[:space:]]*" key "[[:space:]]*:")) {
                started = 1
                n = gsub(/\{/, "{"); m = gsub(/\}/, "}")
                depth = n - m
                print
                if (n > 0 && depth <= 0) exit
            }
            next
        }
        {
            n = gsub(/\{/, "{"); m = gsub(/\}/, "}")
            depth += n - m
            print
            if (depth <= 0) exit
        }
    ' "$1"
}

THEME_BLOCK="$(block_of "$TW_STRIPPED" theme)"
[ -n "$THEME_BLOCK" ] || void "G-3: no 'theme' block found in tailwind.config.ts — the config is not the shape this gate knows how to read, so it cannot report on it"

# HALF A — the theme block carries no container key.
claim
theme_container="$(grep -nE '^[[:space:]]*container[[:space:]]*:' <<< "$THEME_BLOCK")"
[ -z "$theme_container" ] || violation "G-3a: tailwind.config.ts reinstates a 'container' key inside theme (line number is relative to the theme block, not the file): ${theme_container//$'\n'/ | }. The plugin's selector is a hardcoded '.container' and it forces each cap to EQUAL the breakpoint that activates it, so it cannot express this contract at all."

CORE_BLOCK="$(block_of "$TW_STRIPPED" corePlugins)"

# HALF B — the core plugin is disabled. Independent of half A, and it has to be: deleting the
# theme block on its own makes the plugin emit its DEFAULT five media queries, which is
# strictly worse than the single 1400px query the tree had before this phase.
claim
if [ -z "$CORE_BLOCK" ]; then
    violation "G-3b: tailwind.config.ts has no 'corePlugins' block, so the container core plugin is ON. With no theme.container to constrain it, the plugin falls back to the default screens and emits FIVE media queries — one per breakpoint, each capping at its own breakpoint value. That is worse than the state this phase replaced, and it happens silently because the class keeps working and simply caps somewhere else."
else
    core_off="$(grep -cE '^[[:space:]]*container[[:space:]]*:[[:space:]]*false' <<< "$CORE_BLOCK")"
    [ "$core_off" -ge 1 ] || violation "G-3b: tailwind.config.ts does not set 'container: false' in corePlugins. See G-3a's note — the plugin left on emits five default media queries."
fi

# ============================================================================================
# G-5  NO WIDTH LITERAL IN THE BUILD CONFIG
# ============================================================================================
#
# Run before G-4 only because it shares the stripped config above. The config must IMPORT the
# widths; a number typed here could drift from the module the app and the Playwright contract
# spec both read, which is the failure the single declaration exists to prevent.
claim
grep -qE 'from[[:space:]]+"\./lib/layout-widths"' "$TW_STRIPPED" \
    || violation "G-5: tailwind.config.ts no longer imports ./lib/layout-widths by relative specifier. The '@/' alias cannot be used here — jiti does not read tsconfig paths, measured both directions in plan 35-01."

claim
grep -q 'LAYOUT_WIDTHS' "$TW_STRIPPED" \
    || violation "G-5: tailwind.config.ts no longer spreads LAYOUT_WIDTHS into theme.extend.maxWidth."

claim
px_literals="$(grep -nE '[0-9]{3,4}px' "$TW_STRIPPED")"
[ -z "$px_literals" ] || violation "G-5: tailwind.config.ts carries a raw pixel band literal: ${px_literals//$'\n'/ | }. Band widths come from lib/layout-widths.ts and are never restated here. (Three- and four-digit px only — the radius calc's single-digit values are not band widths.)"

# ============================================================================================
# G-4  WIDTH-FAMILY PARITY
# ============================================================================================
#
# Band tokens are the stock numeric max-width scale plus the three tier utilities. `max-w-full`
# and arbitrary values such as `max-w-[68ch]` are deliberately NOT band tokens: the first is a
# fill, the second a typographic measure, and both legitimately coexist with a band on the same
# route. Measured: app/shop/[slug]/loading.tsx carries max-w-full beside its band, so a blanket
# "no other max-w" rule would red on correct code.
BAND_TOKEN_RE='max-w-(xs|sm|md|lg|xl|2xl|3xl|4xl|5xl|6xl|7xl|shell|detail|marketing)([^A-Za-z0-9_-]|$)'

band_tokens_of() { # <repo-relative path> -> sorted unique band tokens, one per line
    grep -oE "$BAND_TOKEN_RE" "$(stripped "$1")" \
        | sed -E 's/[^A-Za-z0-9_-]+$//' | sort -u
}

FAMILIES=()
for i in "${!M_CLAIM[@]}"; do
    case "${M_CLAIM[$i]}" in
        parity:*) fam="${M_CLAIM[$i]#parity:}"; fam="${fam%%:*}"
                  case " ${FAMILIES[*]:-} " in *" $fam "*) : ;; *) FAMILIES+=("$fam") ;; esac ;;
    esac
done

if [ "${#FAMILIES[@]}" -eq 0 ]; then
    void "G-4: the manifest declares no parity family. This assertion is OWED to plan 35-07, whose skeleton-parity break arm produced no red anywhere on the tree; a manifest with no family would silently retire that debt instead of discharging it."
fi

for fam in "${FAMILIES[@]}"; do
    members=()
    expected=""
    for i in "${!M_CLAIM[@]}"; do
        case "${M_CLAIM[$i]}" in
            "parity:${fam}:"*)
                tok="${M_CLAIM[$i]##*:}"
                if [ -z "$expected" ]; then expected="$tok"
                elif [ "$expected" != "$tok" ]; then
                    void "G-4: family '${fam}' declares two different band tokens in the manifest ('${expected}' and '${tok}') — the manifest disagrees with itself, so the family cannot be evaluated"
                fi
                members+=("${M_PATH[$i]}")
                ;;
        esac
    done
    [ "${#members[@]}" -ge 2 ] || void "G-4: parity family '${fam}' has ${#members[@]} member(s). A family of one asserts nothing and reads as covering a route."

    for m in "${members[@]}"; do
        claim
        actual="$(band_tokens_of "$m" | tr '\n' ' ')"
        actual="${actual% }"
        if [ "$actual" != "$expected" ]; then
            violation "G-4: width family '${fam}' — ${m} carries band token(s) [${actual:-none}] but the family is declared at [${expected}]. Every member of this family renders the same route in sequence (skeleton, content, not-found), so a disagreement is a visible width jump on hydration. Family members: ${members[*]}"
        fi
    done
done

# ============================================================================================
# G-6  MANIFEST COMPLETENESS, BOTH DIRECTIONS
# ============================================================================================
TIER_NAMES='shell|index|detail|marketing'

declared_tiers_of() { # <repo-relative path> -> sorted unique tiers declared in that file
    grep -oE "data-width-tier=\"(${TIER_NAMES})\"" "$(stripped "$1")" \
        | sed -E 's/^data-width-tier="//; s/"$//' | sort -u
}

# --- Direction 1: every manifest claim is true of the file it names -------------------------
for i in "${!M_PATH[@]}"; do
    rel="${M_PATH[$i]}"; cl="${M_CLAIM[$i]}"
    case "$cl" in
        shell|index|detail|marketing)
            claim
            found="$(declared_tiers_of "$rel" | tr '\n' ' ')"
            case " $found " in
                *" $cl "*) : ;;
                *) violation "G-6a: the manifest assigns ${rel} the '${cl}' tier, but the file declares [${found:-none}]. A tier dropped by a refactor is invisible at runtime — the index tier adds no class, and the capped tiers simply stop capping." ;;
            esac
            ;;
        'N/A')
            claim
            found="$(declared_tiers_of "$rel" | tr '\n' ' ')"
            found="${found% }"
            [ -z "$found" ] || violation "G-6a: the manifest records ${rel} as deliberately untiered (N/A), but it now declares [${found}]. Either the decision changed and the manifest must say so, or the declaration is an accident."
            ;;
        vocabulary|parity:*) : ;;  # asserted by G-1 and G-4 respectively
    esac
done

# --- Direction 2: every shipped file that declares a tier is in the manifest -----------------
#
# One direction alone is half a check: direction 1 catches a tier silently dropped, direction 2
# catches a surface tiered by someone who did not update the contract. Tests are excluded from
# the discovery scope because they legitimately contain selector strings of the exact form
# `[data-width-tier="marketing"]`, which is a query and not a declaration.
DISCOVERED=0
for rel in "${SHIPPED_SRC[@]}"; do
    tiers="$(declared_tiers_of "$rel")"
    [ -n "$tiers" ] || continue
    DISCOVERED=$((DISCOVERED + 1))
    while IFS= read -r t; do
        [ -n "$t" ] || continue
        claim
        listed=0
        for i in "${!M_PATH[@]}"; do
            if [ "${M_PATH[$i]}" = "$rel" ] && [ "${M_CLAIM[$i]}" = "$t" ]; then listed=1; break; fi
        done
        [ "$listed" -eq 1 ] || violation "G-6b: ${rel} declares the '${t}' tier but the manifest does not list it. Add a row to ${MANIFEST#"$REPO_ROOT"/} — the contract is what the manifest says, and a surface tiered outside it is a surface nobody agreed to."
    done <<< "$tiers"
done
[ "$DISCOVERED" -gt 0 ] || void "G-6b: discovered ZERO tier declarations across ${#SHIPPED_SRC[@]} shipped files. Either the attribute was renamed or the scan is broken; both are unverifiable, and a scan that found nothing must not report clean."

# ============================================================================================
# G-7  THE INDEX TIER IS UNCAPPED, AT THE DECLARING ELEMENT
# ============================================================================================
#
# The opening tag is delimited by scanning forward from the declaration to the first `>` at or
# after it, capped at 30 lines. If the tag cannot be delimited the run VOIDs rather than
# assuming it is clean.
TAG_WINDOW=30
for rel in "${SHIPPED_SRC[@]}"; do
    sf="$(stripped "$rel")"
    while IFS= read -r ln; do
        [ -n "$ln" ] || continue
        claim
        tag="$(
            awk -v start="$ln" -v max="$TAG_WINDOW" '
                NR < start { next }
                NR == start {
                    p = index($0, "data-width-tier")
                    rest = substr($0, p)
                    buf = $0
                    if (index(rest, ">") > 0) { print buf; found = 1; exit }
                    next
                }
                {
                    buf = buf " " $0
                    if (index($0, ">") > 0) { print buf; found = 1; exit }
                }
                NR >= start + max { exit }
                END { if (!found) exit 3 }
            ' "$sf"
        )"
        tagrc=$?
        [ "$tagrc" -eq 0 ] && [ -n "$tag" ] || void "G-7: could not delimit the opening tag for the index declaration at ${rel}:${ln} within ${TAG_WINDOW} lines. An element this gate cannot read is unverified, not clean."

        caps="$(grep -oE "$BAND_TOKEN_RE|WIDTH_TIER_CLASS\.[a-z]+" <<< "$tag" | sed -E 's/[^A-Za-z0-9_.-]+$//' | sort -u | tr '\n' ' ')"
        caps="${caps% }"
        [ -z "$caps" ] || violation "G-7: ${rel}:${ln} declares the index tier but its own opening tag carries [${caps}]. Index means 'fluid to the shell cap' — a cap here silently narrows exactly the data-dense surface the tier exists to widen. (This check is scoped to the declaring ELEMENT: these pages legitimately cap their modal DialogContent, and a file-scoped check would red on correct code.)"
    done < <(grep -nE 'data-width-tier="index"' "$sf" | sed -E 's/:.*$//')
done

# ============================================================================================
# Report
# ============================================================================================
echo "  scanned    : ${#ALL_SRC[@]} source file(s) for tier literals, ${#SHIPPED_SRC[@]} shipped file(s) for declarations and class context"
echo "  discovered : ${DISCOVERED} shipped file(s) declaring a tier"
echo "  families   : ${#FAMILIES[@]} parity family/families"
echo "  checked    : ${CLAIMS} claim(s) across 7 assertions (G-1..G-7)"

if [ "$VIOLATIONS" -gt 0 ]; then
    echo "FAIL: ${VIOLATIONS} layout-contract violation(s). The four-tier contract is declared in frontend/lib/layout-widths.ts and applied through ${VOCAB}; the surface set is ${MANIFEST#"$REPO_ROOT"/}." >&2
    exit 1
fi

echo "PASS: the horizontal layout contract holds across ${CLAIMS} checked claim(s)."
