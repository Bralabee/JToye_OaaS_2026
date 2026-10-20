#!/usr/bin/env bash
# check-dply02-dispositions.sh — DPLY-02: every issue the ROADMAP's Phase 29 section names has a
# written, evidence-linked disposition, and nothing is closed or commented that the owner has
# not approved byte for byte (Phase 29 / plan 29-27; threats T-29-98, T-29-99, T-29-104).
#
# WHY THIS EXISTS
#   DPLY-02 reads "the deploy knot closed or explicitly deferred with a reason recorded per
#   issue", and silence is the defect it names. Three things have gone wrong around exactly
#   this kind of record on this project:
#     - a hand-kept list drifts from the ROADMAP (the 2026-10-18 measurement found the section
#       already named #101, #297 and #859, and #296 was a fourth reference nobody had listed);
#     - GitHub's closing-keyword parser is LEXICAL: "does not close #100" in the body of docs
#       PR #955 closed #100 on merge (memory trap_does_not_close_still_closes). The gate refuses
#       the keyword next to any issue reference in every public text, negations included;
#     - an approval that names a table but not its bytes approves whatever the table becomes.
#       The owner approves by quoting the sha256 of a manifest that pins the table and every
#       comment file by hash, and plan 29-28 re-verifies that before it changes any state.
#   The required list is therefore DERIVED, never typed: the `#N` references between the
#   ROADMAP's "### Phase 29" heading and the next "### Phase" heading, UNIONED with #101, #296,
#   #297 and #859 (the issues ISSUE-DISPOSITION.md homes to Phase 29 or that the research found
#   the section does not name). A section from which nothing parses means the parser is blind,
#   not that nothing is owed, and that is VOID.
#
# THE TABLE
#   29-DPLY02-DISPOSITIONS.md, one row per reference, seven cells:
#     Issue | Title | State at phase start | Disposition | Evidence | Comment file | Expected state after 29-28
#   - Issue is exactly `#N` (no bold, no link): a row whose first cell is anything else is not
#     parsed, so its issue reads as MISSING rather than silently passing.
#   - State cells begin with OPEN / CLOSED (issues) or MERGED (PRs); the rest of the cell is a note.
#   - Disposition begins with one of fixed-in-phase, already-closed, deferred, superseded,
#     pr-reference. `deferred` and `superseded` carry `: <reason>` and an empty or placeholder
#     reason is a finding. fixed-in-phase and superseded must expect CLOSED; deferred expects
#     OPEN; already-closed is CLOSED on both sides; pr-reference is MERGED on both sides and has
#     no comment file (an em dash).
#   - Evidence is one or more `repo/relative/path:LINE` separated by `;`. Each path must exist and
#     each line must lie inside the file.
#   - Comment file is `evidence/29-27-comments/<name>.txt`, relative to the phase directory.
#
# THE COMMENT FILES (public text)
#   Each must contain every evidence path of its row, may cite only repo paths that exist, and
#   must pass the public-text rule: no closing keyword (close/closes/closed/fix/fixes/fixed/
#   resolve/resolves/resolved, optional colon) followed by #N, owner/repo#N or a .../issues/N
#   URL, on one line or split across two; and no AI-attribution line (Co-Authored-By, "Generated
#   with", claude.ai/code, Claude-Session:, the robot-emoji footer). The same rule is available on
#   any file as --scan-text, for the PR body and the squash message plan 29-28 composes.
#
# SHAPE RULES OBSERVED
#   `command grep` (the interactive grep is a .gitignore-honouring function, never evidence);
#   here-strings, never `cmd | grep -q` under pipefail (inverts on match via SIGPIPE 141); every
#   exit code captured on the statement that produced it; an unreadable input is VOID, never
#   "no findings"; every finding carries the file's own line number.
#
# USAGE
#   scripts/check-dply02-dispositions.sh                      static checks (also run in CI)
#   scripts/check-dply02-dispositions.sh --scan-text FILE     the public-text rule on any file
#   scripts/check-dply02-dispositions.sh --pre-close          live gh states vs the table, before 29-28
#   scripts/check-dply02-dispositions.sh --post-close         live gh states vs the expected column
#   scripts/check-dply02-dispositions.sh --write-manifest     pin the table + comment files by sha256
#   scripts/check-dply02-dispositions.sh --verify-approval [FILE]   the owner's quoted hash vs the manifest
#   scripts/check-dply02-dispositions.sh --selftest           planted-file arms for every rule above
#   scripts/check-dply02-dispositions.sh --root DIR           read DIR instead of the repository
#   DPLY02_SELFTEST_FORMAT=tap                                 TAP output for --selftest
# OUTPUT
#   FINDING <file>:<line> — <why>        a rule broken
#   ROW #N <disposition> <state>-><expected> evidence=<n> comment=<name>
#   SUMMARY derived=<n> table=<n> findings=<n>
#   Manifest-SHA256: <64 hex>            (--write-manifest) what the owner quotes
#
# EXIT CODES: 0 = clean, 1 = a finding, 2 = VOID (an input could not be read or parsed, a tool
# is missing, or gh could not answer). A VOID is never a pass.

set -uo pipefail

SELF_ABS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO_ROOT_DEFAULT="$(cd "$(dirname "$SELF_ABS")/.." && pwd)"

PHASE_REL=".planning/phases/29-deployable-staging-with-its-own-monitoring"
ROADMAP_REL=".planning/ROADMAP.md"
TABLE_REL="$PHASE_REL/29-DPLY02-DISPOSITIONS.md"
COMMENTS_SUB="evidence/29-27-comments"
MANIFEST_REL="$PHASE_REL/evidence/29-27-approval.sha256"
APPROVAL_REL="$PHASE_REL/evidence/29-27-approval.txt"
# References the section does not (or may not) name but that Phase 29 owns: #101 (PITR), #296
# (in-cluster Keycloak ingress), #297 (enforced NetworkPolicies), #859 (verified-email deploy check).
EXTRA_ISSUES=(101 296 297 859)
EM_DASH='—'

void() { echo "VOID: $*" >&2; exit 2; }

usage() {
  sed -n '/^# USAGE/,/^# OUTPUT/p' "$SELF_ABS" | sed '$d' | sed 's/^# \{0,1\}//'
}

MODE=static
ROOT=""
ARG_FILE=""
set_mode() {
  if [ "$MODE" != static ]; then echo "USAGE ERROR: more than one mode ($MODE and $1)" >&2; exit 2; fi
  MODE="$1"
}
while [ "$#" -gt 0 ]; do
  case "$1" in
    --selftest) set_mode selftest ;;
    --pre-close) set_mode pre ;;
    --post-close) set_mode post ;;
    --write-manifest) set_mode manifest ;;
    --scan-text)
      set_mode scan
      shift
      [ "$#" -gt 0 ] || { echo "USAGE ERROR: --scan-text needs a file" >&2; exit 2; }
      ARG_FILE="$1"
      ;;
    --verify-approval)
      set_mode approval
      if [ "$#" -gt 1 ] && [[ "$2" != --* ]]; then shift; ARG_FILE="$1"; fi
      ;;
    --root)
      shift
      [ "$#" -gt 0 ] || { echo "USAGE ERROR: --root needs a directory" >&2; exit 2; }
      ROOT="$1"
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument '$1'" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

# ===========================================================================
# The gate — RED STUB (plan 29-27, Task 1): it only counts the table's rows and reports success
# in every mode, which is the "a gate that only counts rows" the selftest below must catch.
# ===========================================================================

FINDINGS=0

set_paths() {
  [ -n "$ROOT" ] || ROOT="$REPO_ROOT_DEFAULT"
  [ -d "$ROOT" ] || void "no such root directory: $ROOT"
  ROOT="$(cd "$ROOT" && pwd)" || void "cannot resolve $ROOT"
  ROADMAP="$ROOT/$ROADMAP_REL"
  TABLE="$ROOT/$TABLE_REL"
  MANIFEST="$ROOT/$MANIFEST_REL"
  APPROVAL="$ROOT/$APPROVAL_REL"
}

run_static() {
  local rows
  rows=$(command grep -cE '^[|] *#[0-9]+ *[|]' "$TABLE" 2>/dev/null)
  echo "STUB rows=${rows:-0}"
  return 0
}
run_scan_text() { return 0; }
run_live() { return 0; }
run_write_manifest() { return 0; }
run_verify_approval() { return 0; }

# ===========================================================================
# --selftest: every arm builds a throwaway repository root, plants ONE condition, runs THIS
# script against it with --root, and asserts the exit code and the lines it must (and must not)
# print. The fixture is synthetic and small (a ROADMAP with three phase sections, eight table
# rows, an evidence file, an ADR file); the real tree is never touched. gh is a stub answering
# from a states file, so --pre-close and --post-close run with no network.
# ===========================================================================

ST_RESULTS=()
ST_FAILS=0
ST_TMP=""
ST_OUT=""
ST_RC=0
st_pass() { ST_RESULTS+=("PASS $1"); }
st_fail() { ST_RESULTS+=("FAIL $1 $2"); ST_FAILS=$((ST_FAILS + 1)); }
st_flat() { printf '%s' "$ST_OUT" | tr '\n' '|' | cut -c1-360; }

st_render() {
  local i=0 r verdict rest total="${#ST_RESULTS[@]}"
  if [ "${DPLY02_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..$total"
    for r in "${ST_RESULTS[@]}"; do
      i=$((i + 1))
      verdict="${r%% *}"
      rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'" '"')'"
        echo "  ..."
      fi
    done
  else
    for r in "${ST_RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
  echo "# SELFTEST: $((total - ST_FAILS))/$total"
}

# st_expect NAME WANT_RC [PATTERN...] — a PATTERN starting with ! must NOT appear in the output.
st_expect() {
  local name="$1" want="$2" p
  shift 2
  if [ "$ST_RC" -ne "$want" ]; then
    st_fail "$name" "expected rc $want, got $ST_RC: $(st_flat)"
    return
  fi
  for p in "$@"; do
    if [ "${p:0:1}" = '!' ]; then
      if command grep -qE -- "${p:1}" <<< "$ST_OUT"; then
        st_fail "$name" "output must not match /${p:1}/: $(st_flat)"
        return
      fi
    elif ! command grep -qE -- "$p" <<< "$ST_OUT"; then
      st_fail "$name" "output lacks /$p/: $(st_flat)"
      return
    fi
  done
  st_pass "$name"
}

# st_plant FILE SED-ARGS... — edits in place and fails unless the file really changed.
st_plant() {
  local f="$1" before
  shift
  before=$(cksum < "$f")
  sed -i "$@" "$f" || return 1
  [ "$(cksum < "$f")" != "$before" ]
}

ST_STUBS=""
ST_STATES=""
st_run() {  # ROOT [ARGS...]
  local root="$1"
  shift
  ST_OUT=$(PATH="$ST_STUBS:$PATH" GH_STUB_STATES="${ST_STATES:-$ST_TMP/states.default}" bash "$SELF_ABS" --root "$root" "$@" 2>&1); ST_RC=$?
}

st_tree() { cp -a "$ST_TMP/base" "$ST_TMP/$1" && printf '%s\n' "$ST_TMP/$1"; }
st_table() { printf '%s\n' "$1/$TABLE_REL"; }
st_comment() { printf '%s\n' "$1/$PHASE_REL/$COMMENTS_SUB/issue-$2.txt"; }

ST_ROWS=(
  "98|deferred: criterion 3 (log aggregation and tracing) is out of D-16 scope|OPEN|OPEN"
  "99|fixed-in-phase|OPEN|CLOSED"
  "101|fixed-in-phase|OPEN|CLOSED"
  "296|fixed-in-phase|OPEN|CLOSED"
  "297|fixed-in-phase|OPEN|CLOSED"
  "301|deferred: needs a pool in the connection budget and a public-exposure decision (Phase 32)|OPEN|OPEN"
  "859|already-closed|CLOSED|CLOSED"
  "953|pr-reference|MERGED|MERGED"
)

st_fixture() {
  local d="$1" p row n disp state expect ev cf
  p="$d/$PHASE_REL"
  mkdir -p "$p/$COMMENTS_SUB" "$d/docs" "$d/.planning" || return 1
  cat > "$d/$ROADMAP_REL" <<'EOF'
# Roadmap
### Phase 28: Something else
Mentions #555, which the Phase 29 derivation must NOT pick up.
### Phase 29: Deployable Staging
The deploy knot: #99 and #301 are named here, the ADR sign-off PR #953, and #98 once more.
| # | Maps to |
| #98 | DPLY-03 |
### Phase 30: The money path
Mentions #777, which the Phase 29 derivation must NOT pick up.
EOF
  seq 1 10 | sed 's/^/evidence line /' > "$p/evidence/ev.txt"
  seq 1 10 | sed 's/^/adr line /' > "$d/docs/adr.md"
  {
    echo '| Issue | Title | State at phase start | Disposition | Evidence | Comment file | Expected state after 29-28 |'
    echo '|---|---|---|---|---|---|---|'
    for row in "${ST_ROWS[@]}"; do
      IFS='|' read -r n disp state expect <<< "$row"
      if [ "$n" = 953 ]; then ev="docs/adr.md:2"; cf="$EM_DASH"; else ev="$PHASE_REL/evidence/ev.txt:3"; cf="$COMMENTS_SUB/issue-$n.txt"; fi
      echo "| #$n | Fixture title $n | $state | $disp | $ev | $cf | $expect |"
    done
  } > "$p/29-DPLY02-DISPOSITIONS.md"
  for row in "${ST_ROWS[@]}"; do
    n="${row%%|*}"
    [ "$n" = 953 ] && continue
    printf 'For the record, the evidence is %s/evidence/ev.txt (line 3).\nClosure of #%s is recorded here.\n' "$PHASE_REL" "$n" > "$p/$COMMENTS_SUB/issue-$n.txt"
  done
  {
    for row in "${ST_ROWS[@]}"; do
      n="${row%%|*}"; state="${row#*|}"; state="${state#*|}"; state="${state%%|*}"
      if [ "$n" = 953 ]; then echo "pr $n $state"; else echo "issue $n $state"; fi
    done
  } > "$ST_TMP/states.default"
}

st_approve() {  # ROOT — write the manifest through the gate, then an approval quoting its sha
  local t="$1" out sha
  out=$(bash "$SELF_ABS" --root "$t" --write-manifest 2>&1) || return 1
  sha=$(sed -n 's/^Manifest-SHA256: //p' <<< "$out")
  [ "${#sha}" -eq 64 ] || return 1
  printf 'approve %s\n2026-10-20T00:00:00Z\nApproved-Manifest-SHA256: %s\n' "${sha:0:12}" "$sha" > "$t/$APPROVAL_REL"
}
st_reapprove() {  # ROOT — re-quote the manifest as it now stands (so only the path-set rule can object)
  local t="$1" sha
  read -r sha _ < <(sha256sum "$t/$MANIFEST_REL")
  printf 'Approved-Manifest-SHA256: %s\n' "$sha" > "$t/$APPROVAL_REL"
}

# --- arms: the static rules --------------------------------------------------------------
arm_static() {
  local t f
  t=$(st_tree clean); st_run "$t"
  st_expect clean 0 '^SUMMARY derived=8 table=8 findings=0$' '^derived : 8 reference' '!^FINDING'

  t=$(st_tree missing-row)
  st_plant "$(st_table "$t")" '/^| #301 /d' && rm "$(st_comment "$t" 301)" || { st_fail missing-row "plant did not apply"; return; }
  st_run "$t"
  st_expect missing-row 1 '^FINDING .*MISSING row #301' '^SUMMARY derived=8 table=7 findings=1$'

  t=$(st_tree extra-row)
  sed -n '/^| #99 /p' "$(st_table "$t")" | sed 's/#99/#777/' >> "$(st_table "$t")"
  st_run "$t"
  st_expect extra-row 1 '^FINDING .*EXTRA row #777' '!MISSING row'

  t=$(st_tree evidence-missing-path)
  st_plant "$(st_table "$t")" '/^| #99 /s#evidence/ev\.txt:3#evidence/nope.txt:3#' || { st_fail evidence-missing-path "plant did not apply"; return; }
  st_run "$t"
  st_expect evidence-missing-path 1 '^FINDING .*#99 evidence path does not exist: .*nope\.txt'

  t=$(st_tree evidence-line-beyond)
  st_plant "$(st_table "$t")" '/^| #99 /s#ev\.txt:3#ev.txt:99#' || { st_fail evidence-line-beyond "plant did not apply"; return; }
  st_run "$t"
  st_expect evidence-line-beyond 1 '^FINDING .*#99 evidence line 99 is outside .*ev\.txt \(10 line'

  t=$(st_tree evidence-empty)
  st_plant "$(st_table "$t")" '/^| #99 /s#| [^|]*ev\.txt:3 |#|  |#' || { st_fail evidence-empty "plant did not apply"; return; }
  st_run "$t"
  st_expect evidence-empty 1 '^FINDING .*#99 has no evidence'

  t=$(st_tree deferral-empty-reason)
  st_plant "$(st_table "$t")" '/^| #301 /s#deferred: [^|]*|#deferred: |#' || { st_fail deferral-empty-reason "plant did not apply"; return; }
  st_run "$t"
  st_expect deferral-empty-reason 1 '^FINDING .*#301 is deferred but gives no written reason'

  t=$(st_tree deferral-bare)
  st_plant "$(st_table "$t")" '/^| #301 /s#deferred: [^|]*|#deferred |#' || { st_fail deferral-bare "plant did not apply"; return; }
  st_run "$t"
  st_expect deferral-bare 1 '^FINDING .*#301 is deferred but gives no written reason'

  t=$(st_tree deferral-placeholder)
  st_plant "$(st_table "$t")" '/^| #301 /s#deferred: [^|]*|#deferred: TBD |#' || { st_fail deferral-placeholder "plant did not apply"; return; }
  st_run "$t"
  st_expect deferral-placeholder 1 '^FINDING .*#301 is deferred but gives no written reason'

  t=$(st_tree bad-disposition)
  st_plant "$(st_table "$t")" '/^| #99 /s#| fixed-in-phase |#| done |#' || { st_fail bad-disposition "plant did not apply"; return; }
  st_run "$t"
  st_expect bad-disposition 1 "^FINDING .*#99 disposition 'done' does not begin with"

  t=$(st_tree deferred-expects-closed)
  st_plant "$(st_table "$t")" '/^| #301 /s#| OPEN |$#| CLOSED |#' || { st_fail deferred-expects-closed "plant did not apply"; return; }
  st_run "$t"
  st_expect deferred-expects-closed 1 '^FINDING .*#301 is deferred but expects .CLOSED'

  t=$(st_tree pr-with-comment)
  st_plant "$(st_table "$t")" "/^| #953 /s#| $EM_DASH |#| $COMMENTS_SUB/issue-99.txt |#" || { st_fail pr-with-comment "plant did not apply"; return; }
  st_run "$t"
  st_expect pr-with-comment 1 '^FINDING .*#953 is a pr-reference row and carries no comment file'

  t=$(st_tree orphan-comment)
  printf 'Nobody approved this. %s/evidence/ev.txt\n' "$PHASE_REL" > "$t/$PHASE_REL/$COMMENTS_SUB/issue-5000.txt"
  st_run "$t"
  st_expect orphan-comment 1 '^FINDING .*issue-5000\.txt — orphan comment file'

  t=$(st_tree comment-missing-file)
  rm "$(st_comment "$t" 98)"
  st_run "$t"
  st_expect comment-missing-file 1 '^FINDING .*issue-98\.txt — #98 comment file is missing or empty'

  t=$(st_tree malformed-row)
  st_plant "$(st_table "$t")" '/^| #99 /s#| Fixture title 99 |#|#' || { st_fail malformed-row "plant did not apply"; return; }
  st_run "$t"
  st_expect malformed-row 1 '^FINDING .*row #99 has 6 cell'
}

# --- arms: the comment files and the public-text rule ----------------------------------------
arm_comment() {
  local t
  t=$(st_tree comment-missing-evidence)
  printf 'Nothing to see here. Closure of #98 is recorded.\n' > "$(st_comment "$t" 98)"
  st_run "$t"
  st_expect comment-missing-evidence 1 '^FINDING .*issue-98\.txt — comment file does not cite its row.s evidence path'

  t=$(st_tree comment-cites-missing)
  printf 'See docs/nope.md for the rest.\n' >> "$(st_comment "$t" 99)"
  st_run "$t"
  st_expect comment-cites-missing 1 '^FINDING .*issue-99\.txt — comment file cites a path that does not exist: docs/nope\.md'

  arm_text kw-does-not-close 1 'This does not close #100.' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-closes-colon 1 'Closes: #1' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-owner-repo 1 'fixes Bralabee/JToye_OaaS_2026#99' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-issue-url 1 'resolves https://github.com/Bralabee/JToye_OaaS_2026/issues/99' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-upper-case 1 'RESOLVED: #3' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-split-lines 1 'This closes\n#1' '^FINDING .*issue-99\.txt:3 — closing keyword'
  arm_text kw-negative-control 0 'The closure of #99 is recorded.' '!^FINDING'
  arm_text kw-prefix-word-control 0 'The suffix #4 and a prefix #5 are words, not keywords.' '!^FINDING'
  arm_text attr-co-authored-by 1 'Co-Authored-By: Someone <someone@example.invalid>' '^FINDING .*issue-99\.txt:3 — AI-attribution'
  arm_text attr-generated-with 1 'Generated with a tool' '^FINDING .*issue-99\.txt:3 — AI-attribution'
  arm_text attr-claude-url 1 'https://claude.ai/code/session_x' '^FINDING .*issue-99\.txt:3 — AI-attribution'
  arm_text attr-session-line 1 'Claude-Session: x' '^FINDING .*issue-99\.txt:3 — AI-attribution'
  arm_text attr-robot-emoji 1 '\xf0\x9f\xa4\x96 footer' '^FINDING .*issue-99\.txt:3 — AI-attribution'
}

# arm_text NAME RC TEXT PATTERN... — append TEXT (printf %b) to #99's comment file, run static.
arm_text() {
  local name="$1" want="$2" text="$3" t
  shift 3
  t=$(st_tree "$name")
  printf '%b\n' "$text" >> "$(st_comment "$t" 99)"
  st_run "$t"
  st_expect "$name" "$want" "$@"
}

# --- arms: --scan-text --------------------------------------------------------------------
arm_scan_text() {
  local t f
  t=$(st_tree scan-text)
  f="$ST_TMP/scan-closes.txt";    printf 'Merging this. Closes #1\n' > "$f";                    st_run "$t" --scan-text "$f"
  st_expect scan-text-closes 1 '^FINDING .*scan-closes\.txt:1 — closing keyword'
  f="$ST_TMP/scan-negation.txt";  printf 'body\nThis does not close #100.\n' > "$f";               st_run "$t" --scan-text "$f"
  st_expect scan-text-negation 1 '^FINDING .*scan-negation\.txt:2 — closing keyword'
  f="$ST_TMP/scan-recorded.txt";  printf 'The closure of #99 is recorded.\n' > "$f";              st_run "$t" --scan-text "$f"
  st_expect scan-text-negative-control 0 '^SUMMARY scan-text .* findings=0$' '!^FINDING'
  f="$ST_TMP/scan-attr.txt";      printf 'subject\n\nCo-Authored-By: X <x@example.invalid>\n' > "$f"; st_run "$t" --scan-text "$f"
  st_expect scan-text-attribution 1 '^FINDING .*scan-attr\.txt:3 — AI-attribution'
  f="$ST_TMP/scan-empty.txt";     : > "$f";                                                       st_run "$t" --scan-text "$f"
  st_expect scan-text-empty-void 2 '^VOID: .*is empty'
  st_run "$t" --scan-text "$ST_TMP/scan-no-such-file.txt"
  st_expect scan-text-missing-void 2 '^VOID: .*cannot read'
}

# --- arms: VOID, not pass, when an input cannot be read -------------------------------------
arm_void() {
  local t
  t=$(st_tree void-no-section)
  st_plant "$t/$ROADMAP_REL" 's/^### Phase 29:/### Phase 29X /' || { st_fail void-no-section "plant did not apply"; return; }
  st_run "$t"
  st_expect void-no-section 2 "^VOID: no '### Phase 29' section"

  t=$(st_tree void-zero-refs)
  printf '# Roadmap\n### Phase 29: Deployable Staging\nNo issue numbers are named in this section at all.\n### Phase 30: Next\n' > "$t/$ROADMAP_REL"
  st_run "$t"
  st_expect void-zero-refs 2 '^VOID: zero issue references parsed'

  t=$(st_tree void-roadmap-missing)
  rm "$t/$ROADMAP_REL"
  st_run "$t"
  st_expect void-roadmap-missing 2 '^VOID: cannot read .planning/ROADMAP.md'

  t=$(st_tree void-table-missing)
  rm "$(st_table "$t")"
  st_run "$t"
  st_expect void-table-missing 2 '^VOID: cannot read the dispositions table'

  t=$(st_tree void-table-no-rows)
  printf '| Issue | Title |\n|---|---|\nprose only\n' > "$(st_table "$t")"
  st_run "$t"
  st_expect void-table-no-rows 2 '^VOID: no table rows parsed'
}

# --- arms: --pre-close / --post-close (gh is a stub) ---------------------------------------------
arm_live() {
  local t
  t=$(st_tree pre-ok)
  st_run "$t" --pre-close
  st_expect pre-close-matches 0 '^SUMMARY pre-close rows=8 findings=0$' '!^FINDING'

  t=$(st_tree pre-drift)
  ST_STATES="$ST_TMP/states.drift"; sed 's/^issue 99 OPEN$/issue 99 CLOSED/' "$ST_TMP/states.default" > "$ST_STATES"
  cmp -s "$ST_STATES" "$ST_TMP/states.default" && { st_fail pre-close-drift "plant did not apply"; ST_STATES=""; return; }
  st_run "$t" --pre-close
  st_expect pre-close-drift 1 '^FINDING .*STATE DRIFT #99 \(issue\): live CLOSED, the table recorded OPEN'

  ST_STATES="$ST_TMP/states.pr"; sed 's/^pr 953 MERGED$/pr 953 OPEN/' "$ST_TMP/states.default" > "$ST_STATES"
  cmp -s "$ST_STATES" "$ST_TMP/states.default" && { st_fail pre-close-pr-not-merged "plant did not apply"; ST_STATES=""; return; }
  st_run "$t" --pre-close
  st_expect pre-close-pr-not-merged 1 '^FINDING .*STATE DRIFT #953 \(pr\): live OPEN, the table recorded MERGED'

  ST_STATES="$ST_TMP/states.missing"; sed '/^issue 301 /d' "$ST_TMP/states.default" > "$ST_STATES"
  st_run "$t" --pre-close
  st_expect pre-close-gh-cannot-answer 2 '^VOID: gh could not report the state of issue #301'

  ST_STATES=""
  ST_OUT=$(PATH="$ST_STUBS:$PATH" GH_STUB_FAIL=1 GH_STUB_STATES="$ST_TMP/states.default" bash "$SELF_ABS" --root "$t" --pre-close 2>&1); ST_RC=$?
  st_expect pre-close-gh-fails 2 '^VOID: gh could not report'

  # post-close: the expected column. The default states are the phase-start states, so the
  # to-close rows still read OPEN and each must be named.
  st_run "$t" --post-close
  st_expect post-close-not-yet-closed 1 '^FINDING .*#99 \(issue\): live OPEN, the table expects CLOSED' '^FINDING .*#296 \(issue\)' '!#301 \(issue\): live'
  ST_STATES="$ST_TMP/states.after"
  sed -e 's/^issue 99 OPEN$/issue 99 CLOSED/' -e 's/^issue 101 OPEN$/issue 101 CLOSED/' -e 's/^issue 296 OPEN$/issue 296 CLOSED/' -e 's/^issue 297 OPEN$/issue 297 CLOSED/' "$ST_TMP/states.default" > "$ST_STATES"
  st_run "$t" --post-close
  st_expect post-close-matches 0 '^SUMMARY post-close rows=8 findings=0$' '!^FINDING'
  ST_STATES=""
}

# --- arms: the manifest and the owner's approval ----------------------------------------------------
arm_manifest() {
  local t line sha1 sha2 actual
  t=$(st_tree wm-ok)
  st_run "$t" --write-manifest
  sha1=$(sed -n 's/^Manifest-SHA256: //p' <<< "$ST_OUT")
  if [ -f "$t/$MANIFEST_REL" ]; then read -r actual _ < <(sha256sum "$t/$MANIFEST_REL"); else actual=none; fi
  if [ "$ST_RC" -ne 0 ] || [ "$sha1" != "$actual" ] || [ "${#sha1}" -ne 64 ]; then
    st_fail write-manifest "rc=$ST_RC printed=[$sha1] actual=[$actual]: $(st_flat)"
  elif [ "$(awk 'END { print NR }' "$t/$MANIFEST_REL")" -ne 8 ]; then
    st_fail write-manifest "manifest should pin the table and seven comment files, has $(awk 'END { print NR }' "$t/$MANIFEST_REL") line(s)"
  elif ! command grep -qE '^[0-9a-f]{64}  [.]planning/phases/' "$t/$MANIFEST_REL"; then
    st_fail write-manifest "manifest lines are not '<sha256>  <repo-relative path>'"
  else
    st_pass write-manifest
  fi
  st_run "$t" --write-manifest
  sha2=$(sed -n 's/^Manifest-SHA256: //p' <<< "$ST_OUT")
  [ "$sha1" = "$sha2" ] && [ -n "$sha2" ] && st_pass write-manifest-deterministic || st_fail write-manifest-deterministic "two runs printed [$sha1] and [$sha2]"

  t=$(st_tree wm-refuses)
  st_plant "$(st_table "$t")" '/^| #301 /d' && rm "$(st_comment "$t" 301)" || { st_fail write-manifest-refuses "plant did not apply"; return; }
  st_run "$t" --write-manifest
  if [ -e "$t/$MANIFEST_REL" ]; then
    st_fail write-manifest-refuses "a manifest was written for a table that fails the static checks"
  else
    st_expect write-manifest-refuses 1 '^REFUSED: the static checks'
  fi

  t=$(st_tree va-ok); st_approve "$t" || { st_fail verify-approval-ok "could not build the approval"; return; }
  st_run "$t" --verify-approval
  st_expect verify-approval-ok 0 '^SUMMARY verify-approval .* pinned=8 findings=0$' '!^FINDING'
  cp "$t/$APPROVAL_REL" "$ST_TMP/approval-elsewhere.txt"
  rm "$t/$APPROVAL_REL"
  st_run "$t" --verify-approval "$ST_TMP/approval-elsewhere.txt"
  st_expect verify-approval-explicit-file 0 '^SUMMARY verify-approval .* findings=0$'
  st_run "$t" --verify-approval
  st_expect verify-approval-absent-void 2 '^VOID: --verify-approval: the approval file .* does not exist'

  t=$(st_tree va-no-line); st_approve "$t" || { st_fail verify-approval-no-line "could not build the approval"; return; }
  printf 'approve please\n' > "$t/$APPROVAL_REL"
  st_run "$t" --verify-approval
  st_expect verify-approval-no-line-void 2 '^VOID: .*exactly one .Approved-Manifest-SHA256'
  printf 'Approved-Manifest-SHA256: abc123\n' > "$t/$APPROVAL_REL"
  st_run "$t" --verify-approval
  st_expect verify-approval-short-hash-void 2 '^VOID: .*exactly one'

  t=$(st_tree va-two-lines); st_approve "$t" || { st_fail verify-approval-two-lines "could not build the approval"; return; }
  line=$(command grep -E '^Approved-Manifest-SHA256: ' "$t/$APPROVAL_REL")
  printf '%s\n' "$line" >> "$t/$APPROVAL_REL"
  st_run "$t" --verify-approval
  st_expect verify-approval-two-lines-void 2 '^VOID: .*exactly one .*found 2'

  t=$(st_tree va-manifest-differs); st_approve "$t" || { st_fail verify-approval-manifest-differs "could not build the approval"; return; }
  printf 'Approved-Manifest-SHA256: %064d\n' 0 > "$t/$APPROVAL_REL"
  st_run "$t" --verify-approval
  st_expect verify-approval-manifest-differs 1 '^FINDING .*29-27-approval\.sha256 — the manifest.s sha256 is .* but the owner approved'

  t=$(st_tree va-comment-edit); st_approve "$t" || { st_fail verify-approval-comment-edit "could not build the approval"; return; }
  printf 'x' >> "$(st_comment "$t" 301)"
  st_run "$t" --verify-approval
  st_expect verify-approval-comment-edit 1 '^FINDING .*issue-301\.txt — changed after the manifest was written' '!the manifest.s sha256 is'

  t=$(st_tree va-table-edit); st_approve "$t" || { st_fail verify-approval-table-edit "could not build the approval"; return; }
  printf 'x' >> "$(st_table "$t")"
  st_run "$t" --verify-approval
  st_expect verify-approval-table-edit 1 '^FINDING .*29-DPLY02-DISPOSITIONS\.md — changed after the manifest was written'

  t=$(st_tree va-comment-deleted); st_approve "$t" || { st_fail verify-approval-comment-deleted "could not build the approval"; return; }
  rm "$(st_comment "$t" 98)"
  st_run "$t" --verify-approval
  st_expect verify-approval-comment-deleted 1 '^FINDING .*issue-98\.txt — pinned file cannot be read'

  t=$(st_tree va-pathset-missing); st_approve "$t" || { st_fail verify-approval-pathset-missing "could not build the approval"; return; }
  st_plant "$t/$MANIFEST_REL" '/issue-98\.txt$/d' || { st_fail verify-approval-pathset-missing "plant did not apply"; return; }
  st_reapprove "$t"
  st_run "$t" --verify-approval
  st_expect verify-approval-pathset-missing 1 '^FINDING .*path-set drift: the table names .*issue-98\.txt but the manifest does not pin it' '!changed after the manifest'

  t=$(st_tree va-pathset-extra); st_approve "$t" || { st_fail verify-approval-pathset-extra "could not build the approval"; return; }
  (cd "$t" && sha256sum -- "$ROADMAP_REL") >> "$t/$MANIFEST_REL"
  st_reapprove "$t"
  st_run "$t" --verify-approval
  st_expect verify-approval-pathset-extra 1 '^FINDING .*path-set drift: the manifest pins .*ROADMAP\.md, which the table does not name'

  t=$(st_tree va-malformed-manifest); st_approve "$t" || { st_fail verify-approval-malformed-manifest "could not build the approval"; return; }
  printf 'this is not a checksum line\n' >> "$t/$MANIFEST_REL"
  st_reapprove "$t"
  st_run "$t" --verify-approval
  st_expect verify-approval-malformed-manifest 1 '^FINDING .*sha256sum -c --strict rejected the manifest'
}

selftest() {
  ST_TMP=$(mktemp -d 2>/dev/null) || void "mktemp -d failed"
  trap 'rm -rf "$ST_TMP"' EXIT
  ST_STUBS="$ST_TMP/stubs"
  mkdir -p "$ST_STUBS" || void "could not create the gh stub directory"
  cat > "$ST_STUBS/gh" <<'EOF'
#!/usr/bin/env bash
# fixture gh: `gh issue|pr view N --json state --jq .state`, answered from $GH_STUB_STATES
[ -z "${GH_STUB_FAIL:-}" ] || exit 1
awk -v k="$1" -v n="$3" '$1 == k && $2 == n { print $3; f = 1 } END { exit f ? 0 : 1 }' "$GH_STUB_STATES"
EOF
  chmod +x "$ST_STUBS/gh"
  mkdir -p "$ST_TMP/base" || void "could not create the fixture"
  st_fixture "$ST_TMP/base" || void "could not build the fixture"
  arm_static
  arm_comment
  arm_scan_text
  arm_void
  arm_live
  arm_manifest
  st_render
  [ "$ST_FAILS" -eq 0 ] || return 1
  return 0
}

# ---------------------------------------------------------------------------
# dispatch
# ---------------------------------------------------------------------------
if [ "$MODE" = selftest ]; then
  selftest
  exit $?
fi

set_paths
case "$MODE" in
  static)   run_static ;;
  scan)     run_scan_text ;;
  pre)      run_live pre ;;
  post)     run_live post ;;
  manifest) run_write_manifest ;;
  approval) run_verify_approval ;;
esac
exit $?
