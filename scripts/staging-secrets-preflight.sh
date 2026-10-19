#!/usr/bin/env bash
# staging-secrets-preflight.sh — refuse a half-configured staging deploy (Phase 29 plan 29-13,
# D-23; threats T-29-49, T-29-50, T-29-51). Run before plan 29-14's deploy and plan 29-21's
# CI deploy job.
#
# WHAT IT ASSERTS, against jtoye-stg-kv (read-only: it never writes, never assigns)
#   1. every DERIVED object is present and enabled. The list is the one the writer uses:
#        python3 k8s/scripts/keyvault-chain.py objects <staging render>   (plan 29-12)
#        bash scripts/staging-realm-import.sh --list-kv-objects            (plan 29-04)
#        bash scripts/staging-seed.sh --list-kv-objects                    (plan 29-06)
#      (scripts/lib/staging-secrets-common.sh ss_derive). Optional refs (smtp, notification,
#      stripe) are required like every other: an optional ref with no value starts a pod
#      silently unconfigured (T-29-45).
#   2. every SecretProviderClass identity holds "Key Vault Secrets User" on each object its
#      SPC names, at per-secret scope (keyvault-chain.py access).
#   3. each external object with a required prefix (Stripe: sk_test_, whsec_) holds a value
#      with that prefix. The value is read into memory, tested and discarded; only
#      "test-mode: yes (<object>)" is printed (D-28: a live key never runs in staging).
#
# OUTPUT: one line per gap, in a fixed machine-readable form, then a summary:
#   MISSING <object>                      absent or disabled
#   MISSING-ACCESS <object> <principal>   the identity lacks the role at that object's scope
#   NOT-TEST-MODE <object>                the stored value lacks its required prefix
#   test-mode: yes (<object>)
#   preflight: <n> objects, <g> grants, <k> gap(s)
#
# USAGE (JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 exported, python3 +
# PyYAML on PATH: `conda activate jtoye-ops` on this host)
#   scripts/staging-secrets-preflight.sh
#   scripts/staging-secrets-preflight.sh --selftest
#   STAGING_SECRETS_SELFTEST_FORMAT=tap scripts/staging-secrets-preflight.sh --selftest
#
# EXIT: 0 every object present + enabled + granted (+ test mode); 1 any gap, or a guard
#       refusal; 2 VOID (derivation failed, missing tooling, a vault read that failed).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SS_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=scripts/lib/staging-secrets-common.sh
. "$SCRIPT_DIR/lib/staging-secrets-common.sh"
SELF="$SCRIPT_DIR/staging-secrets-preflight.sh"

void() { echo "VOID: $*" >&2; exit 2; }

preflight() {
  local work rc=0 n e kvid cid obj pid c prefix v gaps=0 grants=0
  umask 077
  work="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-kvp.XXXXXXXX")" || void "mktemp -d failed"
  # shellcheck disable=SC2064
  trap "rm -rf '$work'" EXIT
  ss_derive "$work" || exit 2

  # shellcheck source=scripts/lib/azure-guard.sh
  . "$SCRIPT_DIR/lib/azure-guard.sh"
  rc=0; az_guard_init >/dev/null || rc=$?
  [ "$rc" -eq 0 ] || exit "$rc"

  az_read keyvault secret list --vault-name "$SS_KV_NAME" --query "[].{n:name,e:attributes.enabled}" -o tsv > "$work/present" 2>"$work/err" \
    || { cat "$work/err" >&2; void "could not list $SS_KV_NAME; a vault the preflight cannot read is not a configured vault"; }

  # 1. presence + enabled
  while IFS= read -r n; do
    e=$(awk -F'\t' -v n="$n" '$1 == n { print tolower($2); exit }' "$work/present")
    if [ "$e" != "true" ]; then echo "MISSING $n"; gaps=$((gaps + 1)); fi
  done < "$work/objects"

  # 2. per-object access for each SPC identity
  kvid=$(az_read keyvault show -n "$SS_KV_NAME" --query id -o tsv 2>"$work/err") || { cat "$work/err" >&2; void "could not read the id of $SS_KV_NAME"; }
  [ -n "$kvid" ] || void "the id of $SS_KV_NAME read back empty"
  az_read identity list --query "[].{c:clientId,p:principalId}" -o tsv > "$work/identities" 2>"$work/err" \
    || { cat "$work/err" >&2; void "could not list managed identities"; }
  while IFS=$'\t' read -r cid obj; do
    [ -n "$cid" ] || continue
    grants=$((grants + 1))
    pid=$(awk -F'\t' -v c="$cid" 'tolower($1) == tolower(c) { print $2; exit }' "$work/identities")
    if [ -z "$pid" ]; then echo "MISSING-ACCESS $obj unresolved-client-id:$cid"; gaps=$((gaps + 1)); continue; fi
    if [ ! -f "$work/scopes.$pid" ]; then
      az_read role assignment list --assignee-object-id "$pid" --all --fill-principal-name false --fill-role-definition-name false \
        --query "[?ends_with(roleDefinitionId, '$SS_ROLE_ID')].scope" -o tsv > "$work/scopes.$pid" 2>"$work/err" \
        || { cat "$work/err" >&2; void "could not list the role assignments of $pid"; }
    fi
    if ! awk -v s="$kvid/secrets/$obj" 'tolower($0) == tolower(s) { f = 1 } END { exit !f }' "$work/scopes.$pid"; then
      echo "MISSING-ACCESS $obj $pid"; gaps=$((gaps + 1))
    fi
  done < "$work/access"

  # 3. external prefixes (Stripe TEST mode), value in memory only
  while IFS= read -r n; do
    c=$(ss_classify "$n") || continue
    [ "${c%% *}" = external ] || continue
    prefix="${c#external }"
    awk -F'\t' -v n="$n" '$1 == n && tolower($2) == "true" { f = 1 } END { exit !f }' "$work/present" || continue
    rc=0; v=$(az_read keyvault secret show --vault-name "$SS_KV_NAME" -n "$n" --query value -o tsv 2>"$work/err") || rc=$?
    [ "$rc" -eq 0 ] || { cat "$work/err" >&2; void "could not read $n to check its mode"; }
    case "$v" in
      "$prefix"*) echo "test-mode: yes ($n)" ;;
      *) echo "NOT-TEST-MODE $n"; gaps=$((gaps + 1)) ;;
    esac
    v=""
  done < "$work/objects"

  echo "preflight: $(wc -l < "$work/objects") objects, $grants grants, $gaps gap(s)"
  [ "$gaps" -eq 0 ] || exit 1
  exit 0
}

# ---------------------------------------------------------------------------
# --selftest: a stub vault, filled directly (independent of the writer)
# ---------------------------------------------------------------------------
pf_run() { # TAG -> rc; output in $ST/TAG.out
  local tag="$1" rc=0
  ( PATH="$ST/bin:$PATH" SS_STUB_STATE="$ST/state" STAGING_SECRETS_RENDER_FILE="${ST_RENDER:-$ST/real.yaml}" \
      JTOYE_AZURE_SUBSCRIPTION_ID="${ST_SUB-c483d353-5f61-4587-a790-addb9ab5fb94}" \
      bash "$SELF" ) > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
pf_full() { # a fully configured stub vault: every derived object, every grant, TEST-mode Stripe values
  local n
  rm -rf "$ST/state"; mkdir -p "$ST/state/vault"; : > "$ST/state/argv.log"
  while IFS= read -r n; do printf 'stub-value-%s' "$n" > "$ST/state/vault/$n"; done < "$ST/objects"
  printf '%s' 'sk_test_stub' > "$ST/state/vault/stripe-credentials--api-key"
  printf '%s' 'whsec_stub' > "$ST/state/vault/stripe-credentials--webhook-secret"
  ss_stub_identities "$ST/state" "$ST/access"
  ss_stub_grant_all "$ST/state" "$ST/access"
}
gaplines() { command grep -E '^(MISSING|MISSING-ACCESS|NOT-TEST-MODE) ' "$ST/$1.out"; }

selftest() {
  local fmt="${STAGING_SECRETS_SELFTEST_FORMAT:-plain}" rc why fails=0 got want line pid
  local -a py
  read -r -a py <<< "${STAGING_SECRETS_PYTHON:-python3}"
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-kvp-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  command -v "${py[0]}" >/dev/null 2>&1 && "${py[@]}" -c 'import yaml' 2>/dev/null || void "python3 with PyYAML is required (conda activate jtoye-ops)"
  "${STAGING_SECRETS_KUBECTL:-kubectl}" kustomize "$SS_REPO_ROOT/k8s/staging" > "$ST/real.yaml" 2>"$ST/render.err" || { cat "$ST/render.err" >&2; void "the staging render failed"; }
  ss_stub_write "$ST/bin" || void "could not write the stub"
  mkdir -p "$ST/d"
  ( STAGING_SECRETS_RENDER_FILE="$ST/real.yaml" ss_derive "$ST/d" ) || void "derivation failed on the real render"
  cp "$ST/d/objects" "$ST/objects"; cp "$ST/d/access" "$ST/access"

  # --- full: rc 0, no gap line, exactly two test-mode lines ------------------------------
  pf_full; rc=$(pf_run full); why=""
  [ "$rc" = 0 ] || why="rc=$rc: $(tail -n 3 "$ST/full.out" | tr '\n' ' ')"
  [ -z "$why" ] && [ -n "$(gaplines full)" ] && why="gap lines on a full vault: $(gaplines full | head -n 2 | tr '\n' ' ')"
  [ -z "$why" ] && [ "$(command grep -c '^test-mode: yes ' "$ST/full.out")" != 2 ] && why="want 2 test-mode lines, got $(command grep -c '^test-mode: yes ' "$ST/full.out")"
  [ -z "$why" ] && command grep -qE 'sk_test_stub|whsec_stub' "$ST/full.out" && why="a Stripe value was printed"
  if [ -z "$why" ]; then ss_result "PASS full rc=0 gaps=0 test-mode=2"; else ss_result "FAIL full $why"; fails=$((fails + 1)); fi

  # --- two render objects missing: exactly those two MISSING lines -----------------------
  pf_full; rm -f "$ST/state/vault/redis-credentials--password" "$ST/state/vault/nextauth-secret--secret"
  rc=$(pf_run two); why=""
  got=$(gaplines two | LC_ALL=C sort); want=$(printf '%s\n' 'MISSING nextauth-secret--secret' 'MISSING redis-credentials--password')
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$got" != "$want" ] && why="gap lines were: $(printf '%s' "$got" | tr '\n' '|')"
  if [ -z "$why" ]; then ss_result "PASS two-missing rc=1 lines=2"; else ss_result "FAIL two-missing $why"; fails=$((fails + 1)); fi

  # --- an operator-script object (seed) missing: the second and third sources count ------
  pf_full; rm -f "$ST/state/vault/staging-seed--test-vendor-password" "$ST/state/vault/keycloak-realm--edge-api-client-secret"
  rc=$(pf_run seed); why=""
  got=$(gaplines seed | LC_ALL=C sort); want=$(printf '%s\n' 'MISSING keycloak-realm--edge-api-client-secret' 'MISSING staging-seed--test-vendor-password')
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$got" != "$want" ] && why="gap lines were: $(printf '%s' "$got" | tr '\n' '|')"
  if [ -z "$why" ]; then ss_result "PASS operator-script-objects rc=1 seed+realm named"; else ss_result "FAIL operator-script-objects $why"; fails=$((fails + 1)); fi

  # --- ghost: a planted secretKeyRef the vault does not hold ------------------------------
  pf_full
  ss_plant "$ST/real.yaml" "$ST/ghost.yaml" ghost-credentials key || { ss_result "FAIL ghost plant did not apply"; fails=$((fails + 1)); }
  rc=$(ST_RENDER="$ST/ghost.yaml" pf_run ghost); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && ! command grep -qx 'MISSING ghost-credentials--key' "$ST/ghost.out" && why="no 'MISSING ghost-credentials--key' line"
  [ -z "$why" ] && [ -n "$(gaplines ghost | command grep -v 'ghost-credentials--key')" ] && why="gaps other than the ghost object"
  if [ -z "$why" ]; then ss_result "PASS ghost rc=1 MISSING ghost-credentials--key"; else ss_result "FAIL ghost $why"; fails=$((fails + 1)); fi

  # --- an identity lacking its role on one object ----------------------------------------
  pf_full
  line=$(command grep -m1 'secrets/redis-credentials--password$' "$ST/state/roles")
  pid="${line%%$'\t'*}"
  command grep -vxF -- "$line" "$ST/state/roles" > "$ST/roles.tmp"; mv "$ST/roles.tmp" "$ST/state/roles"
  rc=$(pf_run access); why=""
  got=$(gaplines access)
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$got" != "MISSING-ACCESS redis-credentials--password $pid" ] && why="gap lines were: $(printf '%s' "$got" | tr '\n' '|')"
  if [ -z "$why" ]; then ss_result "PASS missing-access rc=1 MISSING-ACCESS redis-credentials--password <principal>"; else ss_result "FAIL missing-access $why"; fails=$((fails + 1)); fi

  # --- a disabled object is MISSING -------------------------------------------------------
  pf_full; : > "$ST/state/vault/postgres-credentials--host.disabled"
  rc=$(pf_run disabled); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(gaplines disabled)" != "MISSING postgres-credentials--host" ] && why="gap lines were: $(gaplines disabled | tr '\n' '|')"
  if [ -z "$why" ]; then ss_result "PASS disabled rc=1 MISSING postgres-credentials--host"; else ss_result "FAIL disabled $why"; fails=$((fails + 1)); fi

  # --- a LIVE Stripe key in the vault: NOT-TEST-MODE, value never printed ----------------
  pf_full; printf '%s' 'sk_live_stub' > "$ST/state/vault/stripe-credentials--api-key"
  rc=$(pf_run live); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(gaplines live)" != "NOT-TEST-MODE stripe-credentials--api-key" ] && why="gap lines were: $(gaplines live | tr '\n' '|')"
  [ -z "$why" ] && command grep -q 'sk_live_stub' "$ST/live.out" && why="the live value was printed"
  if [ -z "$why" ]; then ss_result "PASS not-test-mode rc=1"; else ss_result "FAIL not-test-mode $why"; fails=$((fails + 1)); fi

  # --- empty vault: every derived object MISSING -----------------------------------------
  pf_full; rm -f "$ST"/state/vault/*
  rc=$(pf_run empty); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(command grep -c '^MISSING ' "$ST/empty.out")" != "$(wc -l < "$ST/objects")" ] && why="want $(wc -l < "$ST/objects") MISSING lines, got $(command grep -c '^MISSING ' "$ST/empty.out")"
  if [ -z "$why" ]; then ss_result "PASS empty-vault rc=1 missing=$(wc -l < "$ST/objects")"; else ss_result "FAIL empty-vault $why"; fails=$((fails + 1)); fi

  # --- guard unpinned, and VOID without python -------------------------------------------
  pf_full; : > "$ST/state/argv.log"
  rc=$(ST_SUB= pf_run nosub); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ -s "$ST/state/argv.log" ] && why="the stub saw a call"
  if [ -z "$why" ]; then ss_result "PASS guard-unpinned rc=1 calls=0"; else ss_result "FAIL guard-unpinned $why"; fails=$((fails + 1)); fi
  rc=0; ( PATH="$ST/bin:$PATH" SS_STUB_STATE="$ST/state" STAGING_SECRETS_RENDER_FILE="$ST/real.yaml" STAGING_SECRETS_PYTHON="$ST/no-python" \
      JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 bash "$SELF" ) > "$ST/nopy.out" 2>&1 < /dev/null || rc=$?
  if [ "$rc" = 2 ]; then ss_result "PASS void-no-python rc=2"; else ss_result "FAIL void-no-python rc=$rc"; fails=$((fails + 1)); fi

  ss_render "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#SS_RESULTS[@]} - fails ))/${#SS_RESULTS[@]} (derived objects=$(wc -l < "$ST/objects"), grants=$(wc -l < "$ST/access"))"
  [ "$fails" -eq 0 ] || exit 1
}

case "${1-}" in
  "")         preflight ;;
  --selftest) selftest ;;
  -h|--help)  sed -n '/^# USAGE/,/^# EXIT/p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "usage: $0 [--selftest]" >&2; exit 2 ;;
esac
