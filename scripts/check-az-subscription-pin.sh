#!/usr/bin/env bash
# check-az-subscription-pin.sh — no Azure CLI call in scripts/ may bypass the
# subscription-pinning wrappers (Phase 29 / DPLY-01, DPLY-05; threat T-29-01).
#
# WHY THIS EXISTS
#   On this host the az CLI's ambient default subscription has been the
#   EMPLOYER's, and an empty `--subscription ""` silently resolves to it (measured
#   2026-09-28, docs/runbooks/azure-blob-provisioning.md section 1).
#   scripts/lib/azure-guard.sh makes the pin structural: az_guard_init asserts the
#   J'Toye id byte-for-byte, and az_mutate / az_read append `--subscription <id>`
#   to every call. A single raw `az …` anywhere else is the one way back to the
#   ambient default. The August provisioning script asserted this about ITSELF,
#   only when it ran (its STEP 1c). This gate asserts it about EVERY script, on
#   every pull request.
#
# WHAT IS A CALL
#   An `az` token in COMMAND POSITION: at the start of a line (after optional
#   whitespace) or after `;`, `&`, `|`, `$(` or a backtick, followed by any run of
#   the prefix tokens `if elif while until then do else ! { ( command exec env sudo
#   xargs nohup time`, `timeout N` and `NAME=value` assignments, then `az` and
#   whitespace or end of line. So `command -v az`, `echo "  az stderr:"` and
#   `(az rc=…)` inside a string are NOT calls; `$(az …)`, `if az …`, `command az …`
#   and `X=1 az …` are. A string that happens to contain `; az x` is reported as a
#   call: that direction fails closed and costs a reworded message.
#
# WHAT IS ADMITTED (every other call line is a FINDING)
#   - a full-line comment;
#   - a line inside the body of az_guard_init, az_mutate or az_read in
#     scripts/lib/azure-guard.sh. A function body runs from its `NAME() {` line at
#     column 0 to the next `}` at column 0 — the library's own style, stated in its
#     header. A raw call anywhere else in the library is a finding;
#   - this file itself (its selftest fixtures), printed as `EXCLUDED <path>`;
#   - a row of AZ_PIN_EXEMPT below. A row admits a call only when its file is the
#     row's file, the call's subcommand is the row's subcommand (only `storage`,
#     the data plane, is ever admitted), and that file assigns
#     AZURE_STORAGE_CONNECTION_STRING (connection-string authentication: no ARM
#     login, no subscription — a global --subscription would make az resolve an ARM
#     profile such a call does not need). The table lives HERE, never as a marker
#     in the scanned file, so a script cannot exempt itself. Every exempt call
#     prints `EXEMPT <path>:<line> — <reason>` and is counted; a row that matches
#     no call is a finding ("stale exemption"), so an exemption cannot outlive its
#     call or quietly widen to a new one.
#   Heredoc bodies are NOT admitted: a heredoc fed to a shell runs its lines.
#
# DISCOVERY
#   Every *.sh under ROOT/scripts/ recursively (scripts/lib and scripts/gates
#   included), via `/usr/bin/find "$ROOT/scripts/"` (the trailing slash: a
#   symlinked start point truncates find to nothing). A file is az-using when a
#   non-comment line holds a call or an az_mutate / az_read call. Zero az-using
#   files is VOID: on this repo at least the exempt listing and the guard library
#   always exist, so zero means the pattern broke, not that the tree is clean.
#   wrapper_calls counts az_mutate / az_read call lines in consuming scripts — the
#   library's own selftest calls are not counted, so the figure says how many
#   guarded call sites the scripts actually have.
#
# SHAPE RULES OBSERVED
#   `command grep` (the interactive grep is a .gitignore-honouring function, never
#   evidence); here-strings, never `cmd | grep -q` under pipefail (inverts on match
#   via SIGPIPE 141); every finding carries the FILE's own line number (lines are
#   read in order, never pre-stripped and renumbered).
#
# USAGE
#   scripts/check-az-subscription-pin.sh               scan the repo
#   scripts/check-az-subscription-pin.sh --root DIR    scan DIR/scripts/ instead
#   scripts/check-az-subscription-pin.sh --selftest    nine planted arms, one line each
#
# OUTPUT
#   EXCLUDED <path> — …    EXEMPT <path>:<line> — <reason>
#   FINDING <path>:<line> — <why>: <the line>
#   SUMMARY discovered=<n> wrapper_calls=<n> exempt=<n> findings=<n>
#
# EXIT CODES: 0 = clean, 1 = a finding (raw call or stale exemption), 2 = VOID.

set -uo pipefail

SELF_ABS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
REPO_ROOT_DEFAULT="$(cd "$(dirname "$SELF_ABS")/.." && pwd)"
GATE_REL="scripts/check-az-subscription-pin.sh"
GUARD_LIB_REL="scripts/lib/azure-guard.sh"

# path|subcommand|reason — repo-relative path; subcommand is only ever `storage`.
AZ_PIN_EXEMPT=(
  "scripts/check-media-content-types.sh|storage|Azurite data-plane listing via AZURE_STORAGE_CONNECTION_STRING (Phase 36, #488); no subscription is involved, so the pin cannot apply"
)

void() { echo "VOID: $*" >&2; exit 2; }

usage() {
  sed -n '/^# USAGE/,/^# OUTPUT/p' "$SELF_ABS" | sed '$d' | sed 's/^# \{0,1\}//'
}

MODE=scan
ROOT=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --selftest) MODE=selftest ;;
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

# RED placeholder (TDD): reports clean without looking.
scan() {
  return 0
}

# ---------------------------------------------------------------------------
# --selftest: each arm builds a temp tree from a `cp -a` of the real scripts/,
# plants one condition, runs THIS gate with --root, and asserts its rc and the
# lines it must (and must not) print.
# ---------------------------------------------------------------------------
ST_RESULTS=()
st_result() { ST_RESULTS+=("$1"); }

st_render() {
  local i=0 r verdict rest
  if [ "${AZ_PIN_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..${#ST_RESULTS[@]}"
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
}

ST_TMP=""
st_tree() {
  local t="$ST_TMP/$1"
  mkdir -p "$t" || return 2
  cp -a "$REPO_ROOT_DEFAULT/scripts" "$t/" || return 2
  printf '%s\n' "$t"
}

ST_OUT=""
ST_RC=0
st_run() {
  ST_OUT=$(bash "$SELF_ABS" --root "$1" 2>&1); ST_RC=$?
}

st_count() {
  command grep -cE -- "$1" <<<"$ST_OUT"
}

st_flat() {
  printf '%s' "$ST_OUT" | tr '\n' '|' | cut -c1-400
}

selftest() {
  local t n fails=0 ln before after
  ST_TMP=$(mktemp -d 2>/dev/null) || void "mktemp -d failed"

  # raw-call: one planted raw call, named at its own line.
  t=$(st_tree raw-call) || void "could not build the raw-call tree"
  printf '%s\n' 'az group list' > "$t/scripts/staging-x.sh"
  st_run "$t"
  if [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL raw-call expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count '^FINDING scripts/staging-x\.sh:1 ')" -ne 1 ] || [ "$(st_count '^FINDING ')" -ne 1 ]; then
    st_result "FAIL raw-call expected exactly one FINDING, at scripts/staging-x.sh:1: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS raw-call"
  fi

  # prefixed: four command-position shapes, one per file.
  t=$(st_tree prefixed) || void "could not build the prefixed tree"
  printf '%s\n' 'if az group list; then :; fi' > "$t/scripts/staging-p1.sh"
  printf '%s\n' 'command az group list' > "$t/scripts/staging-p2.sh"
  printf '%s\n' '( az group list )' > "$t/scripts/staging-p3.sh"
  printf '%s\n' 'X=1 az group list' > "$t/scripts/staging-p4.sh"
  st_run "$t"
  n=$(st_count '^FINDING ')
  if [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL prefixed expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$n" -ne 4 ] || [ "$(st_count '^FINDING scripts/staging-p[1-4]\.sh:1 ')" -ne 4 ]; then
    st_result "FAIL prefixed expected exactly four FINDING lines (staging-p1..p4 line 1), got $n: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS prefixed"
  fi

  # not-a-call: a comment and the three real prose shapes are not calls.
  t=$(st_tree not-a-call) || void "could not build the not-a-call tree"
  {
    printf '%s\n' '# az group list'
    printf '%s\n' 'command -v az >/dev/null'
    printf '%s\n' 'echo "  az stderr:"'
    printf '%s\n' 'void "listing failed (az rc=$rc)"'
  } > "$t/scripts/staging-n.sh"
  st_run "$t"
  if [ "$ST_RC" -ne 0 ]; then
    st_result "FAIL not-a-call expected rc 0, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count '^FINDING ')" -ne 0 ] || [ "$(st_count '^SUMMARY .* findings=0$')" -ne 1 ]; then
    st_result "FAIL not-a-call expected zero findings and a findings=0 SUMMARY: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS not-a-call"
  fi

  # empty-tree: nothing to discover is VOID, never clean.
  t="$ST_TMP/empty-tree"
  mkdir -p "$t/scripts" || void "could not build the empty tree"
  st_run "$t"
  if [ "$ST_RC" -ne 2 ]; then
    st_result "FAIL empty-tree expected rc 2 (VOID), got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count 'discovered zero az-using scripts')" -ne 1 ]; then
    st_result "FAIL empty-tree VOID did not say 'discovered zero az-using scripts': $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS empty-tree"
  fi

  # exempt-pass: the unmodified copy is clean with exactly the one exemption.
  t=$(st_tree exempt-pass) || void "could not build the exempt-pass tree"
  st_run "$t"
  if [ "$ST_RC" -ne 0 ]; then
    st_result "FAIL exempt-pass expected rc 0, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count '^EXEMPT scripts/check-media-content-types\.sh:[0-9]+ — ')" -ne 1 ] || [ "$(st_count '^EXEMPT ')" -ne 1 ]; then
    st_result "FAIL exempt-pass expected exactly one EXEMPT line, for check-media-content-types.sh: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count '^SUMMARY discovered=[1-9][0-9]* wrapper_calls=[0-9]+ exempt=1 findings=0$')" -ne 1 ]; then
    st_result "FAIL exempt-pass expected SUMMARY exempt=1 findings=0: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS exempt-pass"
  fi

  # unlisted: the same call in an unlisted file is a finding.
  t=$(st_tree unlisted) || void "could not build the unlisted tree"
  cp "$t/scripts/check-media-content-types.sh" "$t/scripts/media-copy.sh" || void "could not copy the exempt file"
  st_run "$t"
  if [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL unlisted expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count '^FINDING scripts/media-copy\.sh:[0-9]+ ')" -lt 1 ]; then
    st_result "FAIL unlisted expected a FINDING naming scripts/media-copy.sh: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS unlisted"
  fi

  # exempt-non-storage: a non-storage call in the exempt file is a finding.
  t=$(st_tree exempt-non-storage) || void "could not build the exempt-non-storage tree"
  printf '%s\n' 'az group list' >> "$t/scripts/check-media-content-types.sh"
  ln=$(wc -l < "$t/scripts/check-media-content-types.sh")
  ln=$((ln + 0))
  st_run "$t"
  if [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL exempt-non-storage expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count "^FINDING scripts/check-media-content-types\\.sh:${ln} ")" -ne 1 ]; then
    st_result "FAIL exempt-non-storage expected a FINDING at check-media-content-types.sh:${ln}: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS exempt-non-storage"
  fi

  # stale-exemption: the exempt call deleted, the row now matches nothing.
  t=$(st_tree stale-exemption) || void "could not build the stale-exemption tree"
  before=$(wc -l < "$t/scripts/check-media-content-types.sh")
  sed -i '/LISTING=\$(az storage blob list/d' "$t/scripts/check-media-content-types.sh"
  after=$(wc -l < "$t/scripts/check-media-content-types.sh")
  st_run "$t"
  if [ "$((before - after))" -ne 1 ]; then
    st_result "FAIL stale-exemption the plant did not delete exactly one line (before=$before after=$after)"; fails=$((fails + 1))
  elif [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL stale-exemption expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count 'stale exemption: AZ_PIN_EXEMPT row matches no call')" -ne 1 ]; then
    st_result "FAIL stale-exemption expected 'stale exemption: AZ_PIN_EXEMPT row matches no call': $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS stale-exemption"
  fi

  # guard-lib-outside-wrapper: the library is admitted only INSIDE the three wrappers.
  t=$(st_tree guard-lib-outside-wrapper) || void "could not build the guard-lib tree"
  [ -f "$t/$GUARD_LIB_REL" ] || void "the guard library $GUARD_LIB_REL is missing from the tree"
  printf '%s\n' 'az group list' >> "$t/$GUARD_LIB_REL"
  ln=$(wc -l < "$t/$GUARD_LIB_REL")
  ln=$((ln + 0))
  st_run "$t"
  if [ "$ST_RC" -ne 1 ]; then
    st_result "FAIL guard-lib-outside-wrapper expected rc 1, got $ST_RC: $(st_flat)"; fails=$((fails + 1))
  elif [ "$(st_count "^FINDING scripts/lib/azure-guard\\.sh:${ln} ")" -ne 1 ] || [ "$(st_count '^FINDING ')" -ne 1 ]; then
    st_result "FAIL guard-lib-outside-wrapper expected exactly one FINDING, at azure-guard.sh:${ln}: $(st_flat)"; fails=$((fails + 1))
  else
    st_result "PASS guard-lib-outside-wrapper"
  fi

  rm -rf "$ST_TMP"
  st_render
  [ "$fails" -eq 0 ] || return 1
  return 0
}

if [ "$MODE" = selftest ]; then
  selftest
  exit $?
fi

ROOT="${ROOT:-$REPO_ROOT_DEFAULT}"
scan "$ROOT"
exit $?
