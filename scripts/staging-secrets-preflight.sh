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
#   4. (full mode) every principal id committed in k8s/staging/azure-identity.yaml
#      (principal_id_<k>, paired with client_id_<k>) is that identity's LIVE principal id,
#      so the map --names-only trusts is re-checked on every operator run (plan 29-21).
#
# --names-only (plan 29-21; what the CI deploy job runs as jtoye-ci, which holds Key Vault
# Reader at vault scope and nothing that reads a value): checks 1 and 2 only, and only with
# reads that role permits. NO value is read (check 3 is skipped, so no `keyvault secret
# show`); the vault id is built from the pinned subscription instead of `keyvault show` (the
# role has Microsoft.KeyVault/vaults/*/read, not vaults/read); principal ids come from the
# committed identity file instead of `identity list` (no Microsoft.ManagedIdentity read);
# grants are listed per secret with `role assignment list --scope <vault>/secrets/<object>`
# (Microsoft.Authorization/*/read, inherited from the vault) instead of a subscription-wide
# `--all`. An SPC identity with no committed principal id is a MISSING-ACCESS gap.
#
# OUTPUT: one line per gap, in a fixed machine-readable form, then a summary:
#   MISSING <object>                      absent or disabled
#   MISSING-ACCESS <object> <principal>   the identity lacks the role at that object's scope
#   NOT-TEST-MODE <object>                the stored value lacks its required prefix
#   INVALID-VALUE <object>                an @-validated owner value (@email, @nonempty) fails
#                                         its validator (plan 29-25); the value is never printed
#   STALE-IDENTITY <key> committed <p> live <q>   (full mode) the committed map is wrong
#   test-mode: yes (<object>)
#   preflight: <n> objects, <g> grants, <k> gap(s)[ (names-only: no value read)]
#
# USAGE (JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 exported, python3 +
# PyYAML on PATH: `conda activate jtoye-ops` on this host)
#   scripts/staging-secrets-preflight.sh
#   scripts/staging-secrets-preflight.sh --names-only
#   scripts/staging-secrets-preflight.sh --selftest
#   STAGING_SECRETS_SELFTEST_FORMAT=tap scripts/staging-secrets-preflight.sh --selftest
#   (STAGING_SECRETS_IDENTITY_FILE overrides the identity file; selftests only)
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

# identity_map FILE -> `<clientId> TAB <principalId>` for every client_id_<k> that has a
# principal_id_<k> in the committed identity file (values unquoted; order of keys free).
identity_map() {
  awk '
    /^[[:space:]]+(client|principal)_id_[A-Za-z0-9_]+:[[:space:]]/ {
      k = $1; sub(/:$/, "", k); v = $2; gsub(/"/, "", v)
      if (k ~ /^client_id_/)    { sub(/^client_id_/, "", k);    c[k] = v }
      else                      { sub(/^principal_id_/, "", k); p[k] = v }
    }
    END { for (k in c) if (k in p) printf "%s\t%s\t%s\n", c[k], p[k], k }' "$1"
}

preflight() {
  local names_only="${1:-0}" idf="${STAGING_SECRETS_IDENTITY_FILE:-$SS_REPO_ROOT/$SS_IDENTITY_FILE_REL}"
  local work rc=0 n e kvid cid obj pid c prefix v k live vrc gaps=0 grants=0
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
  if [ "$names_only" -eq 1 ]; then
    # Built, not read: Key Vault Reader has no Microsoft.KeyVault/vaults/read. The subscription
    # is the one az_guard_init just asserted byte-for-byte.
    kvid="/subscriptions/${JTOYE_AZURE_SUBSCRIPTION_ID}/resourceGroups/${SS_KV_RG}/providers/Microsoft.KeyVault/vaults/${SS_KV_NAME}"
    [ -r "$idf" ] || void "identity file ${idf} is not readable; --names-only resolves principal ids from it"
    identity_map "$idf" | cut -f1,2 > "$work/identities"
    [ -s "$work/identities" ] || void "${idf} maps no client_id_<k> to a principal_id_<k>; --names-only cannot resolve any grant"
  else
    kvid=$(az_read keyvault show -n "$SS_KV_NAME" --query id -o tsv 2>"$work/err") || { cat "$work/err" >&2; void "could not read the id of $SS_KV_NAME"; }
    [ -n "$kvid" ] || void "the id of $SS_KV_NAME read back empty"
    az_read identity list --query "[].{c:clientId,p:principalId}" -o tsv > "$work/identities" 2>"$work/err" \
      || { cat "$work/err" >&2; void "could not list managed identities"; }
    # 4. the committed principal map is the live one (what --names-only trusts in CI)
    if [ -r "$idf" ]; then
      while IFS=$'\t' read -r cid pid k; do
        [ -n "$cid" ] || continue
        live=$(awk -F'\t' -v c="$cid" 'tolower($1) == tolower(c) { print $2; exit }' "$work/identities")
        if [ "$(printf '%s' "$live" | tr '[:upper:]' '[:lower:]')" != "$(printf '%s' "$pid" | tr '[:upper:]' '[:lower:]')" ]; then
          echo "STALE-IDENTITY principal_id_${k} committed ${pid} live ${live:-<no identity with client id ${cid}>}"; gaps=$((gaps + 1))
        fi
      done < <(identity_map "$idf")
    fi
  fi
  while IFS=$'\t' read -r cid obj; do
    [ -n "$cid" ] || continue
    grants=$((grants + 1))
    pid=$(awk -F'\t' -v c="$cid" 'tolower($1) == tolower(c) { print $2; exit }' "$work/identities")
    if [ -z "$pid" ]; then echo "MISSING-ACCESS $obj unresolved-client-id:$cid"; gaps=$((gaps + 1)); continue; fi
    if [ "$names_only" -eq 1 ]; then
      # Who holds the role at EXACTLY this secret's scope (no --all: that is a
      # subscription-wide read the CI identity does not have).
      if [ ! -f "$work/holders.$obj" ]; then
        az_read role assignment list --scope "$kvid/secrets/$obj" --fill-principal-name false --fill-role-definition-name false \
          --query "[?ends_with(roleDefinitionId, '$SS_ROLE_ID')].principalId" -o tsv > "$work/holders.$obj" 2>"$work/err" \
          || { cat "$work/err" >&2; void "could not list the role assignments at the scope of $obj"; }
      fi
      if ! awk -v p="$pid" 'tolower($0) == tolower(p) { f = 1 } END { exit !f }' "$work/holders.$obj"; then
        echo "MISSING-ACCESS $obj $pid"; gaps=$((gaps + 1))
      fi
      continue
    fi
    if [ ! -f "$work/scopes.$pid" ]; then
      az_read role assignment list --assignee-object-id "$pid" --all --fill-principal-name false --fill-role-definition-name false \
        --query "[?ends_with(roleDefinitionId, '$SS_ROLE_ID')].scope" -o tsv > "$work/scopes.$pid" 2>"$work/err" \
        || { cat "$work/err" >&2; void "could not list the role assignments of $pid"; }
    fi
    if ! awk -v s="$kvid/secrets/$obj" 'tolower($0) == tolower(s) { f = 1 } END { exit !f }' "$work/scopes.$pid"; then
      echo "MISSING-ACCESS $obj $pid"; gaps=$((gaps + 1))
    fi
  done < "$work/access"

  if [ "$names_only" -eq 1 ]; then
    echo "preflight: $(wc -l < "$work/objects") objects, $grants grants, $gaps gap(s) (names-only: no value read)"
    [ "$gaps" -eq 0 ] || exit 1
    exit 0
  fi

  # 3. external values: a prefix kind (Stripe TEST mode) or an @-validator kind (the owner's
  #    alert-mail values, plan 29-24), one rule shared with the writer; value in memory only
  while IFS= read -r n; do
    c=$(ss_classify "$n") || continue
    [ "${c%% *}" = external ] || continue
    prefix="${c#external }"
    awk -F'\t' -v n="$n" '$1 == n && tolower($2) == "true" { f = 1 } END { exit !f }' "$work/present" || continue
    rc=0; v=$(az_read keyvault secret show --vault-name "$SS_KV_NAME" -n "$n" --query value -o tsv 2>"$work/err") || rc=$?
    [ "$rc" -eq 0 ] || { cat "$work/err" >&2; void "could not read $n to check its mode"; }
    vrc=0; ss_external_valid "$prefix" "$v" || vrc=$?
    v=""
    case "$vrc:$prefix" in
      0:@*) ;;                                         # an owner value of a validated kind: no line
      0:*)  echo "test-mode: yes ($n)" ;;
      *:@*) echo "INVALID-VALUE $n"; gaps=$((gaps + 1)) ;;
      *)    echo "NOT-TEST-MODE $n"; gaps=$((gaps + 1)) ;;
    esac
  done < "$work/objects"

  echo "preflight: $(wc -l < "$work/objects") objects, $grants grants, $gaps gap(s)"
  [ "$gaps" -eq 0 ] || exit 1
  exit 0
}

# ---------------------------------------------------------------------------
# --selftest: a stub vault, filled directly (independent of the writer)
# ---------------------------------------------------------------------------
pf_run() { # TAG [ARGS...] -> rc; output in $ST/TAG.out
  local tag="$1" rc=0
  shift
  ( PATH="$ST/bin:$PATH" SS_STUB_STATE="$ST/state" STAGING_SECRETS_RENDER_FILE="${ST_RENDER:-$ST/real.yaml}" \
      STAGING_SECRETS_IDENTITY_FILE="${ST_IDF:-$ST/idmap.yaml}" \
      JTOYE_AZURE_SUBSCRIPTION_ID="${ST_SUB-c483d353-5f61-4587-a790-addb9ab5fb94}" \
      bash "$SELF" "$@" ) > "$ST/$tag.out" 2>&1 < /dev/null || rc=$?
  echo "$rc"
}
# pf_idmap OUT -> an identity file in the committed shape (client_id_<k> / principal_id_<k>),
# one pair per SPC clientID, holding the stub's principal ids.
pf_idmap() {
  local out="$1" cid i=0
  printf '%s\n' 'apiVersion: v1' 'kind: ConfigMap' 'metadata:' '  name: azure-identity' 'data:' > "$out"
  while IFS= read -r cid; do
    [ -n "$cid" ] || continue
    i=$((i + 1))
    printf '  client_id_id%s: "%s"\n  principal_id_id%s: "%s"\n' "$i" "$cid" "$i" "$(ss_stub_principal "$cid")" >> "$out"
  done < <(cut -f1 "$ST/access" | LC_ALL=C sort -u)
}
# no_value_reads TAG -> empty when the stub saw no value read and no read Key Vault Reader
# cannot make (vault control plane, identity list, a subscription-wide --all listing)
no_value_reads() {
  # argv.log lines are the bare argv ("keyvault secret show ..."), so each subcommand is
  # anchored at the line start; the full-mode control arm proves the pattern matches.
  command grep -nE '^(keyvault secret show|keyvault show|identity list) |(^| )--all( |$)' "$ST/state/argv.log" | head -n 3 | tr '\n' ' '
}
pf_full() { # a fully configured stub vault: every derived object, every grant, TEST-mode Stripe values
  local n
  rm -rf "$ST/state"; mkdir -p "$ST/state/vault"; : > "$ST/state/argv.log"
  while IFS= read -r n; do printf 'stub-value-%s' "$n" > "$ST/state/vault/$n"; done < "$ST/objects"
  printf '%s' 'sk_test_stub' > "$ST/state/vault/stripe-credentials--api-key"
  printf '%s' 'whsec_stub' > "$ST/state/vault/stripe-credentials--webhook-secret"
  printf '%s' 'whsec_stub' > "$ST/state/vault/stripe-credentials--connect-webhook-secret"
  # Owner-supplied values validated by a kind, not a prefix (plan 29-24 @email/@nonempty):
  # a full vault holds a well-formed one for each, as the writer would have accepted.
  while IFS= read -r n; do
    case "$(ss_classify "$n")" in
      "external @email") printf '%s' "stub.${n##*--}@example.test" > "$ST/state/vault/$n" ;;
    esac
  done < "$ST/objects"
  ss_stub_identities "$ST/state" "$ST/access"
  ss_stub_grant_all "$ST/state" "$ST/access"
}
gaplines() { command grep -E '^(MISSING|MISSING-ACCESS|NOT-TEST-MODE|INVALID-VALUE|STALE-IDENTITY) ' "$ST/$1.out"; }

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
  pf_idmap "$ST/idmap.yaml"

  # --- full: rc 0, no gap line, exactly three test-mode lines, one per Stripe object -----
  # (api-key, webhook-secret, and the Connect endpoint's connect-webhook-secret, plan 30-13)
  pf_full; rc=$(pf_run full); why=""
  [ "$rc" = 0 ] || why="rc=$rc: $(tail -n 3 "$ST/full.out" | tr '\n' ' ')"
  [ -z "$why" ] && [ -n "$(gaplines full)" ] && why="gap lines on a full vault: $(gaplines full | head -n 2 | tr '\n' ' ')"
  [ -z "$why" ] && [ "$(command grep -c '^test-mode: yes ' "$ST/full.out")" != 3 ] && why="want 3 test-mode lines, got $(command grep -c '^test-mode: yes ' "$ST/full.out")"
  [ -z "$why" ] && ! command grep -qx 'test-mode: yes (stripe-credentials--connect-webhook-secret)' "$ST/full.out" && why="the Connect webhook secret was not checked as a TEST object"
  [ -z "$why" ] && command grep -qE 'sk_test_stub|whsec_stub' "$ST/full.out" && why="a Stripe value was printed"
  if [ -z "$why" ]; then ss_result "PASS full rc=0 gaps=0 test-mode=3"; else ss_result "FAIL full $why"; fails=$((fails + 1)); fi

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

  # --- 30-13: the Connect endpoint's secret holding a non-whsec_ value: NOT-TEST-MODE -----
  pf_full; printf '%s' 'sk_live_stub' > "$ST/state/vault/stripe-credentials--connect-webhook-secret"
  rc=$(pf_run connect); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(gaplines connect)" != "NOT-TEST-MODE stripe-credentials--connect-webhook-secret" ] && why="gap lines were: $(gaplines connect | tr '\n' '|')"
  [ -z "$why" ] && command grep -q 'sk_live_stub' "$ST/connect.out" && why="the value was printed"
  if [ -z "$why" ]; then ss_result "PASS connect-not-test-mode rc=1"; else ss_result "FAIL connect-not-test-mode $why"; fails=$((fails + 1)); fi

  # --- 29-25: an owner value that fails its @-validator is INVALID-VALUE, never printed ----
  # (and a validator kind is never read as a literal prefix: the full arm above holds a
  # valid address in every @email object and must show no gap for them)
  pf_full; printf '%s' 'not-an-address' > "$ST/state/vault/alertmanager-smtp-credentials--to"
  printf '%s' '' > "$ST/state/vault/alertmanager-smtp-credentials--password"
  rc=$(pf_run invalid); why=""
  got=$(gaplines invalid | LC_ALL=C sort)
  want=$(printf '%s\n' 'INVALID-VALUE alertmanager-smtp-credentials--password' 'INVALID-VALUE alertmanager-smtp-credentials--to')
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$got" != "$want" ] && why="gap lines were: $(printf '%s' "$got" | tr '\n' '|')"
  [ -z "$why" ] && command grep -q 'not-an-address' "$ST/invalid.out" && why="the invalid value was printed"
  if [ -z "$why" ]; then ss_result "PASS invalid-external-value rc=1 INVALID-VALUE to+password"; else ss_result "FAIL invalid-external-value $why"; fails=$((fails + 1)); fi

  # --- empty vault: every derived object MISSING -----------------------------------------
  pf_full; rm -f "$ST"/state/vault/*
  rc=$(pf_run empty); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(command grep -c '^MISSING ' "$ST/empty.out")" != "$(wc -l < "$ST/objects")" ] && why="want $(wc -l < "$ST/objects") MISSING lines, got $(command grep -c '^MISSING ' "$ST/empty.out")"
  if [ -z "$why" ]; then ss_result "PASS empty-vault rc=1 missing=$(wc -l < "$ST/objects")"; else ss_result "FAIL empty-vault $why"; fails=$((fails + 1)); fi

  # --- 29-21: a committed principal id that is not the identity's live one: STALE-IDENTITY -
  # (the full, operator-run mode keeps the map --names-only trusts honest)
  pf_full
  awk -v c="$(cut -f1 "$ST/access" | LC_ALL=C sort -u | head -n 1)" '
    /^  client_id_id[0-9]+:/ { hit = (index($0, c) > 0) }
    /^  principal_id_id[0-9]+:/ && hit { sub(/"[^"]*"/, "\"p0000000-stale-0000-0000-000000000000\""); hit = 0 }
    { print }' "$ST/idmap.yaml" > "$ST/idmap-stale.yaml"
  rc=$(ST_IDF="$ST/idmap-stale.yaml" pf_run stale); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(gaplines stale | command grep -c '^STALE-IDENTITY principal_id_id[0-9]* committed p0000000-stale-')" != 1 ] && why="gap lines were: $(gaplines stale | head -n 3 | tr '\n' '|')"
  if [ -z "$why" ]; then ss_result "PASS stale-identity rc=1 STALE-IDENTITY named"; else ss_result "FAIL stale-identity $why"; fails=$((fails + 1)); fi

  # --- 29-21 --names-only: what CI runs as jtoye-ci (Key Vault Reader at vault scope) ----
  # Control first: the FULL mode makes every forbidden read, so the detector must see all
  # four kinds there; a detector that sees nothing would make the next arm vacuous.
  pf_full; : > "$ST/state/argv.log"
  rc=$(pf_run control); why=""
  [ "$rc" = 0 ] || why="rc=$rc"
  for got in '^keyvault secret show ' '^keyvault show ' '^identity list ' ' --all '; do
    [ -z "$why" ] && ! command grep -qE -- "$got" "$ST/state/argv.log" && why="the full mode made no '$got' call"
  done
  [ -z "$why" ] && [ -z "$(no_value_reads)" ] && why="the forbidden-read detector saw nothing in the full mode's argv log"
  if [ -z "$why" ]; then ss_result "PASS forbidden-read-detector-control full mode reads values (detector fires)"; else ss_result "FAIL forbidden-read-detector-control $why"; fails=$((fails + 1)); fi

  # A full vault passes with NO value read and no read that role cannot make.
  pf_full; : > "$ST/state/argv.log"
  rc=$(pf_run names-only --names-only); why=""
  [ "$rc" = 0 ] || why="rc=$rc: $(tail -n 3 "$ST/names-only.out" | tr '\n' ' ')"
  [ -z "$why" ] && [ -n "$(gaplines names-only)" ] && why="gap lines on a full vault: $(gaplines names-only | head -n 2 | tr '\n' ' ')"
  [ -z "$why" ] && [ -n "$(no_value_reads)" ] && why="the stub saw a forbidden read: $(no_value_reads)"
  [ -z "$why" ] && command grep -q '^test-mode: yes ' "$ST/names-only.out" && why="a test-mode line, so a value was read"
  [ -z "$why" ] && [ "$(command grep -c '^role assignment list --scope ' "$ST/state/argv.log")" != "$(cut -f2 "$ST/access" | LC_ALL=C sort -u | wc -l)" ] \
    && why="want one scope listing per object ($(cut -f2 "$ST/access" | LC_ALL=C sort -u | wc -l)), got $(command grep -c '^role assignment list --scope ' "$ST/state/argv.log")"
  if [ -z "$why" ]; then ss_result "PASS names-only-no-value-read rc=0 gaps=0 forbidden-reads=0"; else ss_result "FAIL names-only-no-value-read $why"; fails=$((fails + 1)); fi

  # ... and still reports a missing object and a missing grant, still without a value read
  pf_full; rm -f "$ST/state/vault/redis-credentials--password"
  line=$(command grep -m1 'secrets/nextauth-secret--secret$' "$ST/state/roles")
  pid="${line%%$'\t'*}"
  command grep -vxF -- "$line" "$ST/state/roles" > "$ST/roles.tmp"; mv "$ST/roles.tmp" "$ST/state/roles"
  : > "$ST/state/argv.log"
  rc=$(pf_run names-gaps --names-only); why=""
  got=$(gaplines names-gaps | LC_ALL=C sort)
  want=$(printf '%s\n' "MISSING redis-credentials--password" "MISSING-ACCESS nextauth-secret--secret $pid" | LC_ALL=C sort)
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$got" != "$want" ] && why="gap lines were: $(printf '%s' "$got" | tr '\n' '|')"
  [ -z "$why" ] && [ -n "$(no_value_reads)" ] && why="the stub saw a forbidden read: $(no_value_reads)"
  if [ -z "$why" ]; then ss_result "PASS names-only-reports-gaps rc=1 MISSING+MISSING-ACCESS"; else ss_result "FAIL names-only-reports-gaps $why"; fails=$((fails + 1)); fi

  # ... and an SPC identity with no committed principal id is a gap, never skipped
  pf_full
  awk 'BEGIN { done = 0 } /^  principal_id_id[0-9]+:/ && !done { done = 1; next } { print }' "$ST/idmap.yaml" > "$ST/idmap-short.yaml"
  rc=$(ST_IDF="$ST/idmap-short.yaml" pf_run names-unmapped --names-only); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && ! command grep -q '^MISSING-ACCESS .* unresolved-client-id:' "$ST/names-unmapped.out" && why="no unresolved-client-id gap"
  if [ -z "$why" ]; then ss_result "PASS names-only-unmapped rc=1 unresolved-client-id"; else ss_result "FAIL names-only-unmapped $why"; fails=$((fails + 1)); fi

  # The COMMITTED identity file maps every SPC clientID of the real render to a principal
  # id, so --names-only can resolve every grant in CI (structural; the live values are
  # held honest by the stale-identity arm above in every operator run).
  why=""
  while IFS= read -r line; do
    key=$(awk -v c="$line" -F'"' '$2 == c && $1 ~ /^  client_id_[a-z_]+: $/ { k = $1; sub(/^  client_id_/, "", k); sub(/: $/, "", k); print k; exit }' "$SS_REPO_ROOT/$SS_IDENTITY_FILE_REL")
    if [ -z "$key" ]; then why="${why}no client_id_* key for $line; "; continue; fi
    awk -v k="  principal_id_${key}:" 'index($0, k) == 1 { f = 1 } END { exit !f }' "$SS_REPO_ROOT/$SS_IDENTITY_FILE_REL" || why="${why}no principal_id_${key}; "
  done < <(cut -f1 "$ST/access" | LC_ALL=C sort -u)
  if [ -z "$why" ]; then ss_result "PASS committed-principal-map every SPC clientID mapped"; else ss_result "FAIL committed-principal-map $why"; fails=$((fails + 1)); fi

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
  "")           preflight 0 ;;
  --names-only) [ "$#" -eq 1 ] || { echo "usage: $0 [--names-only | --selftest]" >&2; exit 2; }; preflight 1 ;;
  --selftest)   selftest ;;
  -h|--help)    sed -n '/^# USAGE/,/^# EXIT/p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "usage: $0 [--names-only | --selftest]" >&2; exit 2 ;;
esac
