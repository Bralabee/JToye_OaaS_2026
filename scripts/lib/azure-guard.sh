# shellcheck shell=bash
# scripts/lib/azure-guard.sh — the subscription pin as a property of the code
# (Phase 29 / DPLY-01, DPLY-05; decisions D-01, D-32; threat T-29-01).
#
# SOURCED, NEVER EXECUTED. `set -euo pipefail` stays the caller's. No function
# here calls `exit` or `exec`: the caller owns its shell.
#
# WHY THIS EXISTS
#   On this host the az CLI's ambient default subscription has been the
#   EMPLOYER's. An empty `--subscription ""` does not fail: it silently resolves
#   to that default (measured 2026-09-28, docs/runbooks/azure-blob-provisioning.md
#   section 1). So "remember to pass --subscription" is a habit, and a habit is
#   not a guard. Here every Azure CLI call goes through az_mutate or az_read, and
#   both append `--subscription <the J'Toye id>` as the LAST two argv entries
#   after az_guard_init has asserted the id byte-for-byte and read the
#   subscription's name and state back. scripts/check-az-subscription-pin.sh fails
#   CI on any raw az call outside these functions.
#
# CONTRACT (every later Phase 29 plan relies on exactly this)
#   export JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94
#   . scripts/lib/azure-guard.sh
#   az_guard_init >/dev/null          # call it DIRECTLY: it sets AZ_GUARD_OK=1 in
#                                     # THIS shell. A lone `x=$(az_guard_init)`
#                                     # runs in a subshell and drops the flag.
#   az_mutate [--no-echo] <az args…>  # --no-echo adds `--output none` ahead of the
#                                     # pin (create output can carry credentials)
#   v=$(az_read <az args…>)           # stdout passed through unchanged; nothing
#                                     # logged, so a secret read lands only where
#                                     # the caller puts it
#   One substitution that sources, inits AND reads together is fine:
#   $(export JTOYE_AZURE_SUBSCRIPTION_ID=…; . scripts/lib/azure-guard.sh && az_guard_init >/dev/null && az_read …)
#
#   Sourcing this file RESETS AZ_GUARD_OK to 0, and both wrappers re-check the id
#   on every call, so a flag inherited from a parent environment can never stand
#   in for an init that did not run in this shell against this id.
#
# STYLE RULE THE STATIC GATE DEPENDS ON
#   A function runs from its `NAME() {` line at column 0 to the next `}` at
#   column 0. check-az-subscription-pin.sh admits a raw CLI call in this file ONLY
#   inside az_guard_init, az_mutate and az_read, located by that rule. Keep every
#   function in that shape, and never put a `}` at column 0 inside a body.
#
# TEST DOUBLE
#   az_guard_stub DIR writes DIR/az (records argv, answers from a table, never
#   contacts Azure) plus the marker DIR/.az-guard-stub that --dry-run scripts
#   require before they will run. Every dry-run in this phase uses this one stub.
#
# EXIT / RETURN CODES: 0 = done, 1 = refused or verification failed,
#                      2 = tooling (az missing, temp dir unavailable).

JTOYE_SUBSCRIPTION_EXPECTED="c483d353-5f61-4587-a790-addb9ab5fb94"
JTOYE_SUBSCRIPTION_NAME_EXPECTED="JToye Digital Production"

# Reset on every source: see CONTRACT above.
AZ_GUARD_OK=0

_az_guard_refuse() {
  echo "REFUSED [subscription]: $*" >&2
  return 1
}

# The id check shared by init and both wrappers. Byte-for-byte: a trailing
# space, a newline or a case change is a different id, and an empty value is the
# one that silently resolves to the ambient (employer) default.
_az_guard_id_ok() {
  if [ -z "${JTOYE_AZURE_SUBSCRIPTION_ID+set}" ]; then
    _az_guard_refuse "JTOYE_AZURE_SUBSCRIPTION_ID is unset; refusing to let the CLI resolve its ambient default, which on this host is EMPLOYER infrastructure"
    return 1
  fi
  if [ -z "$JTOYE_AZURE_SUBSCRIPTION_ID" ]; then
    _az_guard_refuse "JTOYE_AZURE_SUBSCRIPTION_ID is empty; an empty --subscription silently resolves to the ambient default (measured 2026-09-28)"
    return 1
  fi
  if [ "$JTOYE_AZURE_SUBSCRIPTION_ID" != "$JTOYE_SUBSCRIPTION_EXPECTED" ]; then
    _az_guard_refuse "JTOYE_AZURE_SUBSCRIPTION_ID is not the J'Toye subscription (${JTOYE_SUBSCRIPTION_NAME_EXPECTED}); a wrong-subscription mutation is not recoverable by re-running with the right one"
    return 1
  fi
  return 0
}

# Both wrappers refuse unless init ran in THIS shell AND the id is still right.
_az_guard_ready() {
  _az_guard_id_ok || return 1
  if [ "${AZ_GUARD_OK:-0}" != "1" ]; then
    _az_guard_refuse "az_guard_init has not passed in this shell; call it directly (not inside \$( )) before any wrapped call"
    return 1
  fi
  return 0
}

# RED placeholder (TDD): always passes, makes no read-back.
az_guard_init() {
  AZ_GUARD_OK=1
  export AZ_GUARD_OK
  return 0
}

az_mutate() {
  local no_echo=0 rc=0
  if [ "${1-}" = "--no-echo" ]; then
    no_echo=1
    shift
  fi
  _az_guard_ready || return 1
  if [ "$no_echo" -eq 1 ]; then
    command az "$@" --output none --subscription "$JTOYE_AZURE_SUBSCRIPTION_ID" || rc=$?
  else
    command az "$@" --subscription "$JTOYE_AZURE_SUBSCRIPTION_ID" || rc=$?
  fi
  return "$rc"
}

# RED placeholder (TDD): replaces the caller's shell.
az_read() {
  [ "${AZ_GUARD_OK:-0}" = "1" ] || return 1
  exec az "$@" --subscription "$JTOYE_AZURE_SUBSCRIPTION_ID"
}

# az_guard_stub DIR — write the test-double CLI into DIR.
#   DIR/az            records its argv (one space-joined line) to $AZ_STUB_LOG
#                     (default DIR/argv.log), echoes it to stderr as `DRY-RUN az …`
#   DIR/.az-guard-stub  the marker a --dry-run requires on PATH's az
#   Answers: $AZ_STUB_ANSWERS, when set, is a file of tab-separated rows
#     <extended regex matched against the argv line> TAB <exit code> TAB <stdout>
#   first match wins; exit 3 is how a row says "not found", as az does. With no
#   file or no matching row, `account show --query {name:name,state:state}` answers
#   "$AZ_STUB_ACCOUNT_NAME<TAB>$AZ_STUB_ACCOUNT_STATE" (defaults: the expected
#   name, Enabled) so az_guard_init passes, a call carrying `--output none` exits
#   0 silently (as the CLI does), and everything else exits 0 printing the canned
#   identifier `stub-id`.
az_guard_stub() {
  local dir="${1-}"
  if [ -z "$dir" ]; then
    echo "USAGE ERROR: az_guard_stub DIR" >&2
    return 2
  fi
  mkdir -p "$dir" || return 2
  {
    printf '%s\n' '#!/usr/bin/env bash'
    printf '%s\n' '# Written by az_guard_stub (scripts/lib/azure-guard.sh). Records argv; never contacts Azure.'
    printf 'AZ_STUB_DEFAULT_NAME=%q\n' "$JTOYE_SUBSCRIPTION_NAME_EXPECTED"
    cat <<'STUB'
line="$*"
log="${AZ_STUB_LOG:-$(dirname "$0")/argv.log}"
printf '%s\n' "$line" >> "$log"
printf 'DRY-RUN az %s\n' "$line" >&2
if [ -n "${AZ_STUB_ANSWERS:-}" ]; then
  if [ ! -f "$AZ_STUB_ANSWERS" ]; then
    echo "az stub: AZ_STUB_ANSWERS names a missing file: $AZ_STUB_ANSWERS" >&2
    exit 2
  fi
  while IFS= read -r row || [ -n "$row" ]; do
    [ -n "$row" ] || continue
    case "$row" in '#'*) continue ;; esac
    # Split by parameter expansion, never `IFS=$'\t' read`: tab IFS collapses
    # empty fields and shifts the later columns.
    re="${row%%$'\t'*}"
    rest="${row#*$'\t'}"
    if [ "$rest" = "$row" ]; then
      echo "az stub: answers row has no tab: $row" >&2
      exit 2
    fi
    code="${rest%%$'\t'*}"
    if [ "$code" = "$rest" ]; then out=""; else out="${rest#*$'\t'}"; fi
    case "$code" in
      ''|*[!0-9]*) echo "az stub: answers row exit code is not a number: $row" >&2; exit 2 ;;
    esac
    if [[ "$line" =~ $re ]]; then
      [ -z "$out" ] || printf '%s\n' "$out"
      exit "$code"
    fi
  done < "$AZ_STUB_ANSWERS"
fi
case " $line " in
  *" account show "*"{name:name,state:state}"*)
    printf '%s\t%s\n' "${AZ_STUB_ACCOUNT_NAME-$AZ_STUB_DEFAULT_NAME}" "${AZ_STUB_ACCOUNT_STATE-Enabled}"
    exit 0
    ;;
esac
# Like the real CLI, `--output none` prints nothing.
case " $line " in
  *" --output none "*) exit 0 ;;
esac
printf '%s\n' "stub-id"
exit 0
STUB
  } > "$dir/az" || return 2
  chmod +x "$dir/az" || return 2
  : > "$dir/.az-guard-stub" || return 2
  return 0
}

# Count the lines a stub log holds (0 for a missing file).
_az_guard_lines() {
  local n=0
  if [ -f "$1" ]; then n=$(wc -l < "$1"); fi
  echo $((n + 0))
}

# One refusal case in its own subshell: prints "<init rc> <mutate rc> <read rc>".
#   _az_guard_refusal_case BIN LOG ERR MODE [VALUE] [INHERITED_FLAG]
#   MODE unset | set. INHERITED_FLAG=1 starts the subshell with AZ_GUARD_OK=1, as
#   if a parent environment had exported it.
_az_guard_refusal_case() {
  local bin="$1" log="$2" err="$3" mode="$4" val="${5-}" inherited="${6:-0}"
  (
    PATH="$bin:$PATH"
    AZ_STUB_LOG="$log"
    export AZ_STUB_LOG
    unset AZ_STUB_ANSWERS AZ_STUB_ACCOUNT_NAME AZ_STUB_ACCOUNT_STATE
    if [ "$mode" = unset ]; then
      unset JTOYE_AZURE_SUBSCRIPTION_ID
    else
      JTOYE_AZURE_SUBSCRIPTION_ID="$val"
      export JTOYE_AZURE_SUBSCRIPTION_ID
    fi
    irc=skipped
    if [ "$inherited" = 1 ]; then
      AZ_GUARD_OK=1
      export AZ_GUARD_OK
    else
      AZ_GUARD_OK=0
      irc=0
      az_guard_init >/dev/null 2>>"$err" || irc=$?
    fi
    mrc=0
    az_mutate group list >/dev/null 2>>"$err" || mrc=$?
    rrc=0
    az_read group list >/dev/null 2>>"$err" || rrc=$?
    printf '%s %s %s\n' "$irc" "$mrc" "$rrc"
  )
}

# Collect one arm result ("PASS <arm> …" or "FAIL <arm> …") for the renderer.
_az_guard_result() {
  _AZ_GUARD_RESULTS+=("$1")
}

# Print the collected results: one plain line per arm, or TAP 13 when
# AZ_GUARD_SELFTEST_FORMAT=tap (the same verdicts in a format the GSD RED-evidence
# classifier reads; the plain form is the contract CI and the plan verify use).
_az_guard_render() {
  local i=0 r verdict rest
  if [ "${AZ_GUARD_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"
    echo "1..${#_AZ_GUARD_RESULTS[@]}"
    for r in "${_AZ_GUARD_RESULTS[@]}"; do
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
    for r in "${_AZ_GUARD_RESULTS[@]}"; do
      printf '%s\n' "$r"
    done
  fi
}

# az_guard_selftest — eight named arms, one line each:
#   PASS <arm> argv_count=<n>   or   FAIL <arm> <reason>
# argv_count is the number of lines the stub logged during the arm. Returns 0 only
# when every arm passes.
az_guard_selftest() {
  local tmp bin pin fails=0 arm log err res n total pinned first last v
  _AZ_GUARD_RESULTS=()
  tmp=$(mktemp -d 2>/dev/null) || { echo "VOID: mktemp -d failed" >&2; return 2; }
  bin="$tmp/bin"
  az_guard_stub "$bin" || { echo "VOID: could not write the stub" >&2; rm -rf "$tmp"; return 2; }
  pin="--subscription $JTOYE_SUBSCRIPTION_EXPECTED"

  # --- unset / empty: refused, and the stub saw nothing ---------------------
  for arm in unset empty; do
    log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
    if [ "$arm" = unset ]; then
      res=$(_az_guard_refusal_case "$bin" "$log" "$err" unset)
    else
      res=$(_az_guard_refusal_case "$bin" "$log" "$err" set "")
    fi
    n=$(_az_guard_lines "$log")
    if [ "$res" != "1 1 1" ]; then
      _az_guard_result "FAIL $arm expected init/mutate/read rc '1 1 1', got '${res:-<no output>}'"; fails=$((fails + 1))
    elif [ "$n" -ne 0 ]; then
      _az_guard_result "FAIL $arm refused but the stub recorded $n call(s) first"; fails=$((fails + 1))
    elif ! command grep -qF 'REFUSED [subscription]' "$err"; then
      _az_guard_result "FAIL $arm no 'REFUSED [subscription]' on stderr"; fails=$((fails + 1))
    else
      _az_guard_result "PASS $arm argv_count=$n"
    fi
  done

  # --- wrong-id: every near-miss refused, including an inherited flag -------
  arm=wrong-id; log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
  local bad reason=""
  local -a bads=(
    "00000000-0000-0000-0000-000000000000"
    "$JTOYE_SUBSCRIPTION_EXPECTED "
    " $JTOYE_SUBSCRIPTION_EXPECTED"
    "$(printf '%s' "$JTOYE_SUBSCRIPTION_EXPECTED" | tr 'a-f' 'A-F')"
    "${JTOYE_SUBSCRIPTION_EXPECTED%?}"
    "$JTOYE_SUBSCRIPTION_NAME_EXPECTED"
  )
  for bad in "${bads[@]}"; do
    res=$(_az_guard_refusal_case "$bin" "$log" "$err" set "$bad")
    [ "$res" = "1 1 1" ] || reason="${reason}value '$bad' gave '${res:-<no output>}'; "
  done
  res=$(_az_guard_refusal_case "$bin" "$log" "$err" set "${bads[0]}" 1)
  [ "$res" = "skipped 1 1" ] || reason="${reason}an inherited AZ_GUARD_OK=1 with a wrong id gave '${res:-<no output>}'; "
  n=$(_az_guard_lines "$log")
  if [ -n "$reason" ]; then
    _az_guard_result "FAIL $arm $reason"; fails=$((fails + 1))
  elif [ "$n" -ne 0 ]; then
    _az_guard_result "FAIL $arm refused but the stub recorded $n call(s) first"; fails=$((fails + 1))
  else
    _az_guard_result "PASS $arm argv_count=$n"
  fi

  # --- wrong-name / disabled: the read-back refuses ---------------------------
  for arm in wrong-name disabled; do
    log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
    res=$(
      PATH="$bin:$PATH"; AZ_STUB_LOG="$log"; export AZ_STUB_LOG; unset AZ_STUB_ANSWERS
      JTOYE_AZURE_SUBSCRIPTION_ID="$JTOYE_SUBSCRIPTION_EXPECTED"; export JTOYE_AZURE_SUBSCRIPTION_ID
      if [ "$arm" = wrong-name ]; then
        AZ_STUB_ACCOUNT_NAME="Prod - Some Employer Ltd"; export AZ_STUB_ACCOUNT_NAME
      else
        AZ_STUB_ACCOUNT_STATE="Disabled"; export AZ_STUB_ACCOUNT_STATE
      fi
      AZ_GUARD_OK=0
      irc=0; az_guard_init >/dev/null 2>>"$err" || irc=$?
      mrc=0; az_mutate group list >/dev/null 2>>"$err" || mrc=$?
      printf '%s %s\n' "$irc" "$mrc"
    )
    n=$(_az_guard_lines "$log")
    if [ "$res" != "1 1" ]; then
      _az_guard_result "FAIL $arm expected init/mutate rc '1 1', got '${res:-<no output>}'"; fails=$((fails + 1))
    elif [ "$n" -ne 1 ]; then
      _az_guard_result "FAIL $arm expected exactly the one account-show read, stub recorded $n"; fails=$((fails + 1))
    elif ! command grep -qF 'REFUSED [subscription]' "$err"; then
      _az_guard_result "FAIL $arm no 'REFUSED [subscription]' on stderr"; fails=$((fails + 1))
    else
      _az_guard_result "PASS $arm argv_count=$n"
    fi
  done

  # --- pinned-argv: EVERY logged line, init's included, ends with the pin ---
  arm=pinned-argv; log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
  res=$(
    PATH="$bin:$PATH"; AZ_STUB_LOG="$log"; export AZ_STUB_LOG
    unset AZ_STUB_ANSWERS AZ_STUB_ACCOUNT_NAME AZ_STUB_ACCOUNT_STATE
    JTOYE_AZURE_SUBSCRIPTION_ID="$JTOYE_SUBSCRIPTION_EXPECTED"; export JTOYE_AZURE_SUBSCRIPTION_ID
    AZ_GUARD_OK=0
    irc=0; az_guard_init >/dev/null 2>>"$err" || irc=$?
    mrc=0; az_mutate group create -n azg-selftest -l uksouth >/dev/null 2>>"$err" || mrc=$?
    rrc=0; v=$(az_read group show -n azg-selftest --query id -o tsv 2>>"$err") || rrc=$?
    printf '%s %s %s %s\n' "$irc" "$mrc" "$rrc" "$v"
  )
  total=$(_az_guard_lines "$log")
  pinned=0
  while IFS= read -r v; do
    case "$v" in *" $pin") pinned=$((pinned + 1)) ;; esac
  done < "$log"
  first=$(head -n 1 "$log" 2>/dev/null)
  if [ "$res" != "0 0 0 stub-id" ]; then
    _az_guard_result "FAIL $arm expected init/mutate/read rc '0 0 0' and read output 'stub-id', got '${res:-<no output>}'"; fails=$((fails + 1))
  elif [ "$total" -eq 0 ]; then
    _az_guard_result "FAIL $arm the stub recorded nothing"; fails=$((fails + 1))
  elif [ "$pinned" -ne "$total" ]; then
    _az_guard_result "FAIL $arm only $pinned of $total logged lines end with '$pin': $(tr '\n' '|' < "$log")"; fails=$((fails + 1))
  elif [ "$first" != "account show --query {name:name,state:state} -o tsv $pin" ]; then
    _az_guard_result "FAIL $arm first logged line is not az_guard_init's pinned account-show read: '$first'"; fails=$((fails + 1))
  else
    _az_guard_result "PASS $arm argv_count=$total"
  fi

  # --- no-echo: --output none lands AHEAD of the pin --------------------------
  arm=no-echo; log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
  res=$(
    PATH="$bin:$PATH"; AZ_STUB_LOG="$log"; export AZ_STUB_LOG
    unset AZ_STUB_ANSWERS AZ_STUB_ACCOUNT_NAME AZ_STUB_ACCOUNT_STATE
    JTOYE_AZURE_SUBSCRIPTION_ID="$JTOYE_SUBSCRIPTION_EXPECTED"; export JTOYE_AZURE_SUBSCRIPTION_ID
    AZ_GUARD_OK=0
    irc=0; az_guard_init >/dev/null 2>>"$err" || irc=$?
    mrc=0; az_mutate --no-echo aks create -g jtoye-rg -n azg-selftest 2>>"$err" || mrc=$?
    printf '%s %s\n' "$irc" "$mrc"
  )
  n=$(_az_guard_lines "$log")
  last=$(tail -n 1 "$log" 2>/dev/null)
  if [ "$res" != "0 0" ]; then
    _az_guard_result "FAIL $arm expected init/mutate rc '0 0', got '${res:-<no output>}'"; fails=$((fails + 1))
  elif [ "$last" != "aks create -g jtoye-rg -n azg-selftest --output none $pin" ]; then
    _az_guard_result "FAIL $arm logged '$last'"; fails=$((fails + 1))
  elif command grep -qF -- '--no-echo' "$log"; then
    _az_guard_result "FAIL $arm --no-echo reached the CLI"; fails=$((fails + 1))
  else
    _az_guard_result "PASS $arm argv_count=$n"
  fi

  # --- returns-to-caller: a failing call returns its status AND the caller's
  #     next line still runs (an exec-based wrapper never prints the sentinel) --
  arm=returns-to-caller; log="$tmp/$arm.log"; err="$tmp/$arm.err"; : > "$log"; : > "$err"
  printf '%s\t%s\t%s\n' '^group show( |$)' 3 '' '^group delete( |$)' 3 '' > "$tmp/$arm.answers"
  res=$(
    PATH="$bin:$PATH"; AZ_STUB_LOG="$log"; export AZ_STUB_LOG
    AZ_STUB_ANSWERS="$tmp/$arm.answers"; export AZ_STUB_ANSWERS
    unset AZ_STUB_ACCOUNT_NAME AZ_STUB_ACCOUNT_STATE
    JTOYE_AZURE_SUBSCRIPTION_ID="$JTOYE_SUBSCRIPTION_EXPECTED"; export JTOYE_AZURE_SUBSCRIPTION_ID
    AZ_GUARD_OK=0
    az_guard_init >/dev/null 2>>"$err" || { echo "init-refused"; exit 0; }
    set -e
    rrc=0; az_read group show -n azg-missing >/dev/null 2>>"$err" || rrc=$?
    echo "SENTINEL read rc=$rrc"
    mrc=0; az_mutate group delete -n azg-missing 2>>"$err" || mrc=$?
    echo "SENTINEL mutate rc=$mrc"
  )
  n=$(_az_guard_lines "$log")
  if [ "$res" != "SENTINEL read rc=3"$'\n'"SENTINEL mutate rc=3" ]; then
    _az_guard_result "FAIL $arm expected both sentinels with rc=3, got '$(printf '%s' "${res:-<no output>}" | tr '\n' '|')'"; fails=$((fails + 1))
  else
    _az_guard_result "PASS $arm argv_count=$n"
  fi

  rm -rf "$tmp"
  _az_guard_render
  [ "$fails" -eq 0 ] || return 1
  return 0
}
