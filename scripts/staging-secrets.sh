#!/usr/bin/env bash
# staging-secrets.sh — the write-once Key Vault writer for the staging estate
# (Phase 29 plan 29-13 / DPLY-02, DPLY-01; decision D-23, D-28; threats T-29-47..T-29-50).
#
# WHAT IT DOES
#   Populates jtoye-stg-kv with every object the staging deploy reads, and grants each
#   workload identity "Key Vault Secrets User" on exactly the objects its own
#   SecretProviderClass names, at per-secret scope.
#
#   The object list is DERIVED, never typed (D-23): keyvault-chain.py objects over the
#   rendered staging build (plan 29-12), plus `--list-kv-objects` of
#   scripts/staging-realm-import.sh (29-04) and scripts/staging-seed.sh (29-06). See
#   scripts/lib/staging-secrets-common.sh for the derivation and the class table.
#   The phase-29-research branch's version wrote Kubernetes Secrets from an env file with a
#   23-name hand list and passed every value on argv (`--from-literal`); this one targets
#   Key Vault, derives its list and never puts a value on a command line.
#
# WRITE-ONCE, BY CONSTRUCTION (T-29-47)
#   An object that exists is NEVER written again, by any mode. Re-keying
#   dsar-credentials--encryption-key would make every open request's ciphertext unreadable,
#   and a regenerated role password would no longer match the server. Rotation is a
#   deliberate, multi-step act: docs/runbooks/credential-rotation.md, never this script.
#
# NO VALUE ON ARGV, NO VALUE PRINTED (T-29-48)
#   A value is written into a mode-600 file in a mode-700 `mktemp -d` directory (removed by
#   an EXIT trap) by shell builtins or an openssl pipeline, and handed through
#   az_mutate --no-echo to
#     az keyvault secret set ... --file FILE --encoding utf-8
#   never with the inline-value flag, which would put the value on argv. Output names
#   objects and classes, never values.
#
# USAGE (every Azure mode needs, in the CALLER's environment,
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94, and python3 + PyYAML,
#   i.e. `conda activate jtoye-ops` on this host)
#   scripts/staging-secrets.sh --list              names and classes, offline (no Azure call)
#   scripts/staging-secrets.sh --generate          write every absent estate/random/composite
#                                                  object; existing ones print "exists (write-once)"
#   scripts/staging-secrets.sh --set-external NAME read NAME's value from stdin (a hidden prompt
#                                                  on a terminal); external class only; refused when
#                                                  it exists or the prefix is wrong (sk_test_ /
#                                                  whsec_: live Stripe keys never enter staging)
#   scripts/staging-secrets.sh --assign-access     per-object role for each SPC identity; an absent
#                                                  object prints "deferred (object absent): NAME"
#   scripts/staging-secrets.sh --leak-scan FILE    rc 1 naming each object whose secret value FILE
#                                                  contains (the name, never the value)
#   scripts/staging-secrets.sh --selftest          every behaviour against a stub vault
#   STAGING_SECRETS_SELFTEST_FORMAT=tap ... --selftest   the same, as TAP (RED evidence)
#
# EXIT: 0 done, 1 refused (guard, write-once, unclassified, prefix, leak found, az error),
#       2 VOID / usage (derivation failed, missing tooling, missing file).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SS_REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=scripts/lib/staging-secrets-common.sh
. "$SCRIPT_DIR/lib/staging-secrets-common.sh"
SELF="$SCRIPT_DIR/staging-secrets.sh"

refuse() { echo "REFUSED [$1]: ${*:2}" >&2; exit 1; }
void()   { echo "VOID: $*" >&2; exit 2; }

WORK=""
setup_work() {
  umask 077
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-kvw.XXXXXXXX")" || void "mktemp -d failed"
  chmod 700 "$WORK"
  trap 'rm -rf "$WORK"' EXIT
}

derive() {
  local rc=0
  ss_derive "$WORK" || rc=$?
  [ "$rc" -eq 0 ] || exit 2
}

guard() {
  # shellcheck source=scripts/lib/azure-guard.sh
  . "$SCRIPT_DIR/lib/azure-guard.sh"
  local rc=0
  az_guard_init >/dev/null || rc=$?
  [ "$rc" -eq 0 ] || exit "$rc"
}

# exists NAME -> 0 present, 1 absent; anything else refuses (a permission error is not "absent").
exists() {
  local rc=0
  az_read keyvault secret show --vault-name "$SS_KV_NAME" -n "$1" --query id -o tsv >/dev/null 2>"$WORK/show.err" || rc=$?
  case "$rc" in
    0) return 0 ;;
    3) return 1 ;;
    *) cat "$WORK/show.err" >&2; refuse az "could not tell whether $SS_KV_NAME/$1 exists (az rc=$rc); nothing is written on an unknown state" ;;
  esac
}

# read_value NAME VAR — the stored value into VAR, never printed.
read_value() {
  local __v rc=0
  __v=$(az_read keyvault secret show --vault-name "$SS_KV_NAME" -n "$1" --query value -o tsv 2>"$WORK/read.err") || rc=$?
  [ "$rc" -eq 0 ] && [ -n "$__v" ] || { cat "$WORK/read.err" >&2; refuse az "could not read $SS_KV_NAME/$1 back (az rc=$rc)"; }
  printf -v "$2" '%s' "$__v"
}

# write_file NAME FILE — the one write path. FILE is mode 600; the value is in FILE only.
write_file() {
  local name="$1" f="$2" rc=0 id
  [ -s "$f" ] || refuse value "the value file for $name is empty"
  [ "$(stat -c '%a' "$f")" = 600 ] || refuse value "the value file for $name is not mode 600"
  az_mutate --no-echo keyvault secret set --vault-name "$SS_KV_NAME" -n "$name" --file "$f" --encoding utf-8 2>"$WORK/set.err" || rc=$?
  rm -f "$f"
  [ "$rc" -eq 0 ] || { cat "$WORK/set.err" >&2; refuse az "keyvault secret set $name failed (az rc=$rc)"; }
  id=$(az_read keyvault secret show --vault-name "$SS_KV_NAME" -n "$name" --query id -o tsv 2>/dev/null) || refuse az "$name was written but does not read back"
  echo "written: $name (version ${id##*/}; value not printed)"
}

# Generators: each writes straight into FILE (no variable, no argv).
gen_alnum() { # FILE LEN — [A-Za-z0-9], at least one upper, lower and digit
  local f="$1" n="$2" i
  for i in 1 2 3 4 5 6 7 8; do
    openssl rand -base64 96 | LC_ALL=C tr -dc 'A-Za-z0-9' | head -c "$n" > "$f"
    if [ "$(wc -c < "$f")" -eq "$n" ] && command grep -q '[A-Z]' "$f" && command grep -q '[a-z]' "$f" && command grep -q '[0-9]' "$f"; then
      return 0
    fi
  done
  refuse generate "could not generate a $n-character alphanumeric value in 8 attempts"
}
gen_value() { # KIND FILE
  local kind="$1" f="$2" sp
  case "$kind" in
    alnum32) gen_alnum "$f" 32 ;;
    hex64)   openssl rand -hex 32 | tr -d '\n' > "$f" ;;
    b64url32) openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n' > "$f" ;;
    vendor-password)
      # The staging realm policy: length(12) upperCase(1) lowerCase(1) digits(1)
      # specialChars(1) notUsername. 24 alphanumerics plus one URL-, JSON- and shell-safe
      # special character, chosen by the CSPRNG.
      gen_alnum "$f" 24
      sp=$(openssl rand 1 | od -An -tu1 | tr -d ' \n')
      case $(( sp % 3 )) in 0) printf '%s' '-' >> "$f" ;; 1) printf '%s' '_' >> "$f" ;; *) printf '%s' '.' >> "$f" ;; esac ;;
    *) refuse generate "unknown generator '$kind'" ;;
  esac
  [ -s "$f" ] || refuse generate "the $kind generator produced nothing"
}

cmd_list() {
  setup_work; derive
  local n c bad=0
  while IFS= read -r n; do
    if c=$(ss_classify "$n"); then printf '%s\t%s\n' "$n" "${c%% *}"; else printf '%s\t%s\n' "$n" "UNCLASSIFIED"; bad=$((bad + 1)); fi
  done < "$WORK/objects"
  echo "list: $(wc -l < "$WORK/objects") objects, $bad unclassified" >&2
  [ "$bad" -eq 0 ] || exit 1
}

cmd_generate() {
  setup_work; derive
  local n c cls kind f v u p written=0 kept=0 ext=0
  # Refuse BEFORE any write: an unclassified object means the table is behind the render.
  while IFS= read -r n; do
    ss_classify "$n" >/dev/null || refuse unclassified "$n matches no class in scripts/lib/staging-secrets-common.sh; classify it (estate, random, composite or external) before anything is written"
  done < "$WORK/objects"
  command -v openssl >/dev/null 2>&1 || void "openssl not found (it generates the values)"
  guard
  # Pass 1 estate + random, pass 2 composites (their inputs are read back from the vault).
  local pass
  for pass in 1 2; do
    while IFS= read -r n; do
      c=$(ss_classify "$n"); cls="${c%% *}"; kind="${c#* }"
      case "$cls" in
        external) [ "$pass" -eq 1 ] && { echo "external (owner supplies via --set-external): $n"; ext=$((ext + 1)); }; continue ;;
        composite) [ "$pass" -eq 2 ] || continue ;;
        *) [ "$pass" -eq 1 ] || continue ;;
      esac
      if exists "$n"; then echo "exists (write-once): $n"; kept=$((kept + 1)); continue; fi
      f="$WORK/value"; : > "$f"; chmod 600 "$f"
      case "$cls/$kind" in
        estate/pg_host)
          v=$(ss_identity_value pg_host) || refuse estate "pg_host is not in $SS_IDENTITY_FILE_REL"
          printf '%s' "$v" > "$f" ;;
        estate/lit:*) printf '%s' "${kind#lit:}" > "$f" ;;
        random/*) gen_value "$kind" "$f" ;;
        composite/copy:*) read_value "${kind#copy:}" v; printf '%s' "$v" > "$f"; v="" ;;
        composite/rabbitmq-conf)
          # The operator's default_user.conf (internal/resource/default_user_secret.go):
          # the same user and password core-java reads, so the broker and the app agree.
          read_value rabbitmq-credentials--username u
          read_value rabbitmq-credentials--password p
          printf 'default_user = %s\ndefault_pass = %s\n' "$u" "$p" > "$f"; u=""; p="" ;;
        *) refuse generate "no generator for $n ($c)" ;;
      esac
      write_file "$n" "$f"
      written=$((written + 1))
    done < "$WORK/objects"
  done
  echo "generate: $written written, $kept exist (write-once, untouched), $ext external left to the owner"
}

cmd_set_external() {
  local name="$1" c prefix v f
  setup_work; derive
  command grep -qxF -- "$name" "$WORK/objects" || refuse external "$name is not a derived object"
  c=$(ss_classify "$name") || refuse external "$name is unclassified"
  [ "${c%% *}" = external ] || refuse external "$name is class '${c%% *}', not external; only an owner-supplied object is set by hand (rotation: $SS_ROTATION_RUNBOOK)"
  prefix="${c#external }"
  guard
  if exists "$name"; then refuse write-once "$name already exists in $SS_KV_NAME; it is never overwritten (rotation: $SS_ROTATION_RUNBOOK)"; fi
  [ -t 0 ] && printf 'value for %s (input hidden): ' "$name" >&2
  IFS= read -rs v || true
  [ -t 0 ] && echo >&2
  v="${v%$'\r'}"
  [ -n "$v" ] || refuse external "no value on stdin for $name"
  case "$v" in
    *[[:space:]]*) v=""; refuse external "the value for $name contains whitespace" ;;
  esac
  case "$v" in
    "$prefix"*) ;;
    *) v=""; refuse external "the value for $name does not start with '$prefix' (D-28: only Stripe TEST credentials enter staging)" ;;
  esac
  f="$WORK/value"; : > "$f"; chmod 600 "$f"
  printf '%s' "$v" > "$f"; v=""
  write_file "$name" "$f"
}

cmd_assign_access() {
  setup_work; derive
  guard
  local kvid rc=0 cid obj pid scopes created=0 existing=0 deferred=0 total=0
  kvid=$(az_read keyvault show -n "$SS_KV_NAME" --query id -o tsv 2>"$WORK/err") || rc=$?
  [ "$rc" -eq 0 ] && [ -n "$kvid" ] || { cat "$WORK/err" >&2; refuse az "could not read the id of $SS_KV_NAME (az rc=$rc)"; }
  az_read identity list --query "[].{c:clientId,p:principalId}" -o tsv > "$WORK/identities" 2>"$WORK/err" \
    || { cat "$WORK/err" >&2; refuse az "could not list managed identities"; }
  az_read keyvault secret list --vault-name "$SS_KV_NAME" --query "[].name" -o tsv > "$WORK/present" 2>"$WORK/err" \
    || { cat "$WORK/err" >&2; refuse az "could not list $SS_KV_NAME"; }
  : > "$WORK/deferred"
  while IFS=$'\t' read -r cid obj; do
    [ -n "$cid" ] || continue
    total=$((total + 1))
    pid=$(awk -F'\t' -v c="$cid" 'tolower($1) == tolower(c) { print $2; exit }' "$WORK/identities")
    [ -n "$pid" ] || refuse identity "no managed identity in the subscription has client id $cid (named by the render's SecretProviderClass)"
    if ! command grep -qxF -- "$obj" "$WORK/present"; then
      command grep -qxF -- "$obj" "$WORK/deferred" || { echo "deferred (object absent): $obj"; echo "$obj" >> "$WORK/deferred"; }
      deferred=$((deferred + 1)); continue
    fi
    if [ ! -f "$WORK/scopes.$pid" ]; then
      az_read role assignment list --assignee-object-id "$pid" --all --fill-principal-name false --fill-role-definition-name false \
        --query "[?ends_with(roleDefinitionId, '$SS_ROLE_ID')].scope" -o tsv > "$WORK/scopes.$pid" 2>"$WORK/err" \
        || { cat "$WORK/err" >&2; refuse az "could not list the role assignments of $pid"; }
    fi
    scopes="$WORK/scopes.$pid"
    if awk -v s="$kvid/secrets/$obj" 'tolower($0) == tolower(s) { f = 1 } END { exit !f }' "$scopes"; then
      existing=$((existing + 1)); continue
    fi
    rc=0
    az_mutate --no-echo role assignment create --assignee-object-id "$pid" --assignee-principal-type ServicePrincipal \
      --role "$SS_ROLE_ID" --scope "$kvid/secrets/$obj" 2>"$WORK/err" || rc=$?
    [ "$rc" -eq 0 ] || { cat "$WORK/err" >&2; refuse az "role assignment create for $obj / $pid failed (az rc=$rc)"; }
    echo "$kvid/secrets/$obj" >> "$scopes"
    echo "assigned: $SS_ROLE_NAME on $obj to $pid"
    created=$((created + 1))
  done < "$WORK/access"
  echo "assign-access: $total grants, $created created, $existing existing, $deferred deferred"
}

cmd_leak_scan() {
  local file="$1" n v checked=0 skipped=0 found=0
  [ -n "$file" ] && [ -f "$file" ] && [ -r "$file" ] || void "leak-scan: file '$file' missing or unreadable"
  setup_work; derive
  guard
  az_read keyvault secret list --vault-name "$SS_KV_NAME" --query "[].name" -o tsv > "$WORK/present" 2>"$WORK/err" \
    || { cat "$WORK/err" >&2; refuse az "could not list $SS_KV_NAME"; }
  while IFS= read -r n; do
    command grep -qxF -- "$n" "$WORK/present" || continue
    if ! ss_is_secret "$n"; then skipped=$((skipped + 1)); continue; fi
    read_value "$n" v
    checked=$((checked + 1))
    # Fixed strings fed through a descriptor: the value is on no argv. Each non-empty line
    # of a multi-line value is its own pattern; an empty pattern would match everything.
    if command grep -qF -f <(printf '%s\n' "$v" | command grep -v '^$') -- "$file"; then
      echo "LEAK $n"
      found=$((found + 1))
    fi
    v=""
  done < "$WORK/objects"
  [ $((checked + skipped)) -gt 0 ] || void "leak-scan: no derived object is present in $SS_KV_NAME; nothing was checked"
  echo "leak-scan: $checked objects checked, $found found ($skipped estate identifiers not secret, skipped)"
  [ "$found" -eq 0 ] || exit 1
}

# ---------------------------------------------------------------------------
# --selftest
# ---------------------------------------------------------------------------
st_run() { # LOGTAG ARGS... -> runs this script against the stub, output in $ST/<LOGTAG>.out, rc echoed
  local tag="$1"; shift
  local rc=0
  ( PATH="$ST/bin:$PATH" SS_STUB_STATE="$ST/state" STAGING_SECRETS_RENDER_FILE="${ST_RENDER:-$ST/real.yaml}" \
      JTOYE_AZURE_SUBSCRIPTION_ID="${ST_SUB-c483d353-5f61-4587-a790-addb9ab5fb94}" \
      bash "$SELF" "$@" ) > "$ST/$tag.out" 2>&1 < "${ST_STDIN:-/dev/null}" || rc=$?
  echo "$rc"
}
st_reset() { rm -rf "$ST/state"; mkdir -p "$ST/state/vault"; : > "$ST/state/argv.log"; : > "$ST/state/roles"; ss_stub_identities "$ST/state" "$ST/access"; }
st_sets() { command grep -c 'keyvault secret set' "$ST/state/argv.log"; }

cmd_selftest() {
  local fmt="${STAGING_SECRETS_SELFTEST_FORMAT:-plain}" rc why n v fails=0 total
  local -a py
  read -r -a py <<< "${STAGING_SECRETS_PYTHON:-python3}"
  ST="$(mktemp -d "${TMPDIR:-/tmp}/jtoye-kvw-st.XXXXXXXX")" || void "mktemp -d failed"
  trap 'rm -rf "$ST"' EXIT
  command -v "${py[0]}" >/dev/null 2>&1 && "${py[@]}" -c 'import yaml' 2>/dev/null || void "python3 with PyYAML is required (conda activate jtoye-ops)"
  command -v openssl >/dev/null 2>&1 || void "openssl not found"
  "${STAGING_SECRETS_KUBECTL:-kubectl}" kustomize "$SS_REPO_ROOT/k8s/staging" > "$ST/real.yaml" 2>"$ST/render.err" || { cat "$ST/render.err" >&2; void "the staging render failed"; }
  ss_stub_write "$ST/bin" || void "could not write the stub"
  mkdir -p "$ST/d"
  ( STAGING_SECRETS_RENDER_FILE="$ST/real.yaml" ss_derive "$ST/d" ) || void "derivation failed on the real render"
  cp "$ST/d/objects" "$ST/objects"; cp "$ST/d/access" "$ST/access"
  total=$(wc -l < "$ST/objects")
  local nonext; nonext=$(while IFS= read -r n; do c=$(ss_classify "$n") && [ "${c%% *}" != external ] && echo "$n"; done < "$ST/objects" | wc -l)

  # --- write-once: a stub vault already holding the DSAR key ----------------------------
  st_reset; printf '%s' 'PRESET-DSAR-VALUE-0123456789abcdef' > "$ST/state/vault/dsar-credentials--encryption-key"; echo v1 > "$ST/state/vault/dsar-credentials--encryption-key.versions"
  rc=$(st_run gen1 --generate); why=""
  [ "$rc" = 0 ] || why="--generate rc=$rc: $(tail -n 2 "$ST/gen1.out" | tr '\n' ' ')"
  [ -z "$why" ] && ! command grep -qxF 'exists (write-once): dsar-credentials--encryption-key' "$ST/gen1.out" && why="no 'exists (write-once): dsar-credentials--encryption-key' line"
  [ -z "$why" ] && command grep -qE 'secret set .*-n dsar-credentials--encryption-key ' "$ST/state/argv.log" && why="a 'secret set' for dsar-credentials--encryption-key was issued"
  [ -z "$why" ] && [ "$(cat "$ST/state/vault/dsar-credentials--encryption-key")" != 'PRESET-DSAR-VALUE-0123456789abcdef' ] && why="the stored DSAR key changed"
  [ -z "$why" ] && [ "$(st_sets)" != "$((nonext - 1))" ] && why="expected $((nonext - 1)) writes (every non-external object but the DSAR key), stub saw $(st_sets)"
  if [ -z "$why" ]; then ss_result "PASS write-once-generate writes=$(st_sets) dsar=untouched"; else ss_result "FAIL write-once-generate $why"; fails=$((fails + 1)); fi
  printf '%s\n' 'abcdef' > "$ST/in"
  n=$(st_sets); rc=$(ST_STDIN="$ST/in" st_run setdsar --set-external dsar-credentials--encryption-key); why=""
  [ "$rc" = 1 ] || why="rc=$rc, want 1"
  [ -z "$why" ] && [ "$(st_sets)" != "$n" ] && why="a write happened"
  if [ -z "$why" ]; then ss_result "PASS write-once-set-external rc=1 $(command grep -o 'REFUSED \[[a-z-]*\]' "$ST/setdsar.out" | head -n 1)"; else ss_result "FAIL write-once-set-external $why"; fails=$((fails + 1)); fi

  # --- no value on argv: every set carries --file, and no stored secret value appears ---
  why=""; local leaks=0 nofile
  nofile=$(command grep 'keyvault secret set' "$ST/state/argv.log" | command grep -vc -- '--file ')
  [ "$nofile" = 0 ] || why="$nofile secret set line(s) without --file"
  while IFS= read -r n; do
    [ -f "$ST/state/vault/$n" ] || continue
    ss_is_secret "$n" || continue
    v=$(cat "$ST/state/vault/$n")
    if command grep -qF -f <(printf '%s\n' "$v" | command grep -v '^$') "$ST/state/argv.log"; then leaks=$((leaks + 1)); fi
  done < "$ST/objects"
  [ -z "$why" ] && [ "$leaks" != 0 ] && why="$leaks generated value(s) found in the stub's argv log"
  [ -z "$why" ] && command grep -q -- '--value' "$ST/state/argv.log" && why="--value reached the CLI"
  if [ -z "$why" ]; then ss_result "PASS argv-no-value sets=$(st_sets) all---file values-in-argv=0"; else ss_result "FAIL argv-no-value $why"; fails=$((fails + 1)); fi

  # --- value shapes: DSAR/HMAC hex64, composites equal their inputs, vendor password policy ---
  why=""
  local s=$ST/state/vault
  [[ "$(cat "$s/client-ip-credentials--hmac-key")" =~ ^[0-9a-f]{64}$ ]] || why="hmac key is not 64 hex"
  [[ "$(cat "$s/notification-credentials--unsubscribe-signing-secret")" =~ ^[0-9a-f]{64}$ ]] || why="${why}unsubscribe secret is not 64 hex; "
  [[ "$(cat "$s/postgres-credentials--password")" =~ ^[A-Za-z0-9]{32}$ ]] || why="${why}role password is not 32 alnum; "
  [[ "$(cat "$s/nextauth-secret--secret")" =~ ^[A-Za-z0-9_-]{43}$ ]] || why="${why}nextauth secret is not 32-byte base64url; "
  v=$(cat "$s/staging-seed--test-vendor-password")
  { [ "${#v}" -ge 12 ] && [[ "$v" =~ [A-Z] ]] && [[ "$v" =~ [a-z] ]] && [[ "$v" =~ [0-9] ]] && [[ "$v" =~ [^A-Za-z0-9] ]]; } || why="${why}vendor password misses the realm policy; "
  [ "$(cat "$s/postgres-credentials--host")" = "$(ss_identity_value pg_host)" ] || why="${why}host is not pg_host; "
  [ "$(cat "$s/rabbitmq-credentials--stomp-login")" = "$(cat "$s/rabbitmq-credentials--username")" ] || why="${why}stomp-login != username; "
  [ "$(cat "$s/rabbitmq-credentials--stomp-passcode")" = "$(cat "$s/rabbitmq-credentials--password")" ] || why="${why}stomp-passcode != password; "
  [ "$(cat "$s/rabbitmq-default-user--default-user-conf")" = "$(printf 'default_user = %s\ndefault_pass = %s' "$(cat "$s/rabbitmq-credentials--username")" "$(cat "$s/rabbitmq-credentials--password")")" ] || why="${why}default_user.conf does not carry the rabbitmq user and password; "
  v=""
  if [ -z "$why" ]; then ss_result "PASS value-shapes hex64 alnum32 b64url32 vendor-policy composites=inputs"; else ss_result "FAIL value-shapes $why"; fails=$((fails + 1)); fi

  # --- a second generate writes nothing --------------------------------------------------
  n=$(st_sets); rc=$(st_run gen2 --generate); why=""
  [ "$rc" = 0 ] || why="rc=$rc"
  [ -z "$why" ] && [ "$(st_sets)" != "$n" ] && why="$(( $(st_sets) - n )) write(s) on a full vault"
  [ -z "$why" ] && [ "$(command grep -c '^exists (write-once): ' "$ST/gen2.out")" != "$nonext" ] && why="want $nonext 'exists (write-once)' lines, got $(command grep -c '^exists (write-once): ' "$ST/gen2.out")"
  if [ -z "$why" ]; then ss_result "PASS second-generate-noop writes=0 exists=$nonext"; else ss_result "FAIL second-generate-noop $why"; fails=$((fails + 1)); fi

  # --- unclassified object: refused before any write -------------------------------------
  st_reset
  ss_plant "$ST/real.yaml" "$ST/planted.yaml" brand-new-credentials token || { ss_result "FAIL unclassified plant did not apply"; fails=$((fails + 1)); }
  rc=$(ST_RENDER="$ST/planted.yaml" st_run uncl --generate); why=""
  [ "$rc" = 1 ] || why="rc=$rc, want 1"
  [ -z "$why" ] && ! command grep -q 'REFUSED \[unclassified\]: brand-new-credentials--token' "$ST/uncl.out" && why="no refusal naming brand-new-credentials--token"
  [ -z "$why" ] && [ "$(st_sets)" != 0 ] && why="$(st_sets) write(s) before the refusal"
  if [ -z "$why" ]; then ss_result "PASS unclassified rc=1 writes=0"; else ss_result "FAIL unclassified $why"; fails=$((fails + 1)); fi

  # --- external: sk_live_ refused, sk_test_ written once, then refused; whsec_ likewise ---
  st_reset
  printf '%s\n' 'sk_live_x' > "$ST/in"; rc=$(ST_STDIN="$ST/in" st_run live --set-external stripe-credentials--api-key); why=""
  [ "$rc" = 1 ] || why="sk_live_ rc=$rc"
  [ -z "$why" ] && [ "$(st_sets)" != 0 ] && why="sk_live_ was written"
  [ -z "$why" ] && command grep -q 'sk_live_x' "$ST/live.out" && why="the refused value was printed"
  printf '%s\n' 'sk_test_x' > "$ST/in"; rc=$(ST_STDIN="$ST/in" st_run t1 --set-external stripe-credentials--api-key)
  [ -z "$why" ] && [ "$rc" != 0 ] && why="sk_test_ rc=$rc: $(tail -n 1 "$ST/t1.out")"
  [ -z "$why" ] && [ "$(cat "$ST/state/vault/stripe-credentials--api-key" 2>/dev/null)" != sk_test_x ] && why="sk_test_ not stored"
  rc=$(ST_STDIN="$ST/in" st_run t2 --set-external stripe-credentials--api-key)
  [ -z "$why" ] && [ "$rc" != 1 ] && why="second sk_test_ rc=$rc, want 1 (exists)"
  [ -z "$why" ] && [ "$(st_sets)" != 1 ] && why="want exactly 1 write, got $(st_sets)"
  printf '%s\n' 'sk_test_y' > "$ST/in"; rc=$(ST_STDIN="$ST/in" st_run w1 --set-external stripe-credentials--webhook-secret)
  [ -z "$why" ] && [ "$rc" != 1 ] && why="webhook secret without whsec_ rc=$rc"
  printf '%s\n' 'whsec_y' > "$ST/in"; rc=$(ST_STDIN="$ST/in" st_run w2 --set-external stripe-credentials--webhook-secret)
  [ -z "$why" ] && [ "$rc" != 0 ] && why="whsec_ rc=$rc"
  [ -z "$why" ] && command grep -qE 'sk_test_x|whsec_y' "$ST"/t1.out "$ST"/w2.out && why="a value was printed"
  if [ -z "$why" ]; then ss_result "PASS external-prefix sk_live_=refused sk_test_=written-once whsec_=required"; else ss_result "FAIL external-prefix $why"; fails=$((fails + 1)); fi

  # --- assign-access: absent objects deferred, then exactly the missing grants ------------
  st_reset
  while IFS= read -r n; do case "$n" in stripe-credentials--*) ;; *) printf 'x' > "$ST/state/vault/$n" ;; esac; done < "$ST/objects"
  rc=$(st_run aa1 --assign-access); why=""
  local grants; grants=$(wc -l < "$ST/access")
  [ "$rc" = 0 ] || why="rc=$rc: $(tail -n 2 "$ST/aa1.out" | tr '\n' ' ')"
  [ -z "$why" ] && [ "$(command grep -c '^deferred (object absent): stripe-credentials--' "$ST/aa1.out")" != 2 ] && why="want 2 stripe deferrals"
  [ -z "$why" ] && [ "$(command grep -c '^deferred' "$ST/aa1.out")" != 2 ] && why="deferrals other than the two stripe objects"
  [ -z "$why" ] && [ "$(wc -l < "$ST/state/roles")" != "$((grants - 2))" ] && why="want $((grants - 2)) assignments, got $(wc -l < "$ST/state/roles")"
  [ -z "$why" ] && command grep -q 'secrets/stripe-credentials--' "$ST/state/roles" && why="a stripe object was granted while absent"
  printf 'x' > "$ST/state/vault/stripe-credentials--api-key"; printf 'x' > "$ST/state/vault/stripe-credentials--webhook-secret"
  rc=$(st_run aa2 --assign-access)
  [ -z "$why" ] && [ "$rc" != 0 ] && why="re-run rc=$rc"
  [ -z "$why" ] && [ "$(command grep -c '^assigned: ' "$ST/aa2.out")" != 2 ] && why="re-run created $(command grep -c '^assigned: ' "$ST/aa2.out"), want 2"
  [ -z "$why" ] && command grep -q '^deferred' "$ST/aa2.out" && why="re-run still defers"
  [ -z "$why" ] && [ "$(LC_ALL=C sort -u "$ST/state/roles" | wc -l)" != "$grants" ] && why="duplicate or missing assignments: $(LC_ALL=C sort -u "$ST/state/roles" | wc -l) unique of $grants"
  rc=$(st_run aa3 --assign-access)
  [ -z "$why" ] && [ "$(command grep -c '^assigned: ' "$ST/aa3.out")" != 0 ] && why="third run created assignments"
  [ -z "$why" ] && [ "$(wc -l < "$ST/state/roles")" != "$grants" ] && why="assignment count changed on the idempotent run"
  if [ -z "$why" ]; then ss_result "PASS assign-access grants=$grants deferred=2-then-0 created=2 duplicates=0"; else ss_result "FAIL assign-access $why"; fails=$((fails + 1)); fi

  # --- leak-scan: hit names the object, never the value; clean file 0; missing/empty VOID -
  st_reset
  rc=$(st_run lgen --generate)
  v=$(cat "$ST/state/vault/redis-credentials--password")
  printf 'evidence line\nversion 0123456789abcdef0123456789abcdef\n' > "$ST/clean.txt"
  { cat "$ST/clean.txt"; printf 'pasted by mistake: %s\n' "$v"; } > "$ST/leaky.txt"
  rc=$(st_run lk1 --leak-scan "$ST/leaky.txt"); why=""
  [ "$rc" = 1 ] || why="leaky rc=$rc"
  [ -z "$why" ] && [ "$(command grep -c '^LEAK ' "$ST/lk1.out")" != 1 ] && why="want exactly one LEAK line, got $(command grep -c '^LEAK ' "$ST/lk1.out")"
  [ -z "$why" ] && ! command grep -qx 'LEAK redis-credentials--password' "$ST/lk1.out" && why="the LEAK line does not name redis-credentials--password"
  [ -z "$why" ] && command grep -qF -- "$v" "$ST/lk1.out" && why="the leaked value was printed"
  rc=$(st_run lk2 --leak-scan "$ST/clean.txt")
  [ -z "$why" ] && [ "$rc" != 0 ] && why="clean rc=$rc"
  [ -z "$why" ] && ! command grep -qE '^leak-scan: [1-9][0-9]* objects checked, 0 found' "$ST/lk2.out" && why="no 'leak-scan: N objects checked, 0 found' line"
  rc=$(st_run lk3 --leak-scan "$ST/no-such-file")
  [ -z "$why" ] && [ "$rc" != 2 ] && why="missing file rc=$rc, want 2"
  : > "$ST/empty.yaml"
  rc=$(ST_RENDER="$ST/empty.yaml" st_run lk4 --leak-scan "$ST/clean.txt")
  [ -z "$why" ] && [ "$rc" != 2 ] && why="empty derivation rc=$rc, want 2"
  v=""
  if [ -z "$why" ]; then ss_result "PASS leak-scan hit=1-named clean=0 missing=VOID empty=VOID"; else ss_result "FAIL leak-scan $why"; fails=$((fails + 1)); fi

  # --- guard: no subscription id -> refused before any vault call ------------------------
  st_reset
  rc=$(ST_SUB= st_run nosub --generate); why=""
  [ "$rc" = 1 ] || why="rc=$rc"
  [ -z "$why" ] && [ -s "$ST/state/argv.log" ] && why="the stub saw $(wc -l < "$ST/state/argv.log") call(s)"
  if [ -z "$why" ]; then ss_result "PASS guard-unpinned rc=1 calls=0"; else ss_result "FAIL guard-unpinned $why"; fails=$((fails + 1)); fi

  # --- VOID: python3 unavailable -> rc 2, no write ---------------------------------------
  st_reset
  rc=$( ( PATH="$ST/bin:$PATH" SS_STUB_STATE="$ST/state" STAGING_SECRETS_RENDER_FILE="$ST/real.yaml" STAGING_SECRETS_PYTHON="$ST/no-python" \
        JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 bash "$SELF" --generate ) > "$ST/nopy.out" 2>&1 < /dev/null; echo $? ); why=""
  [ "$rc" = 2 ] || why="rc=$rc"
  [ -z "$why" ] && [ -s "$ST/state/argv.log" ] && why="the stub was called"
  if [ -z "$why" ]; then ss_result "PASS void-no-python rc=2"; else ss_result "FAIL void-no-python $why"; fails=$((fails + 1)); fi

  ss_render "$fmt"
  [ "$fmt" = tap ] && printf "# "; echo "SELFTEST: $(( ${#SS_RESULTS[@]} - fails ))/${#SS_RESULTS[@]} (derived objects=$total, generated=$nonext, grants=$(wc -l < "$ST/access"))"
  [ "$fails" -eq 0 ] || exit 1
}

case "${1-}" in
  --list)          cmd_list ;;
  --generate)      cmd_generate ;;
  --set-external)  [ -n "${2-}" ] || void "usage: --set-external NAME"; cmd_set_external "$2" ;;
  --assign-access) cmd_assign_access ;;
  --leak-scan)     cmd_leak_scan "${2-}" ;;
  --selftest)      cmd_selftest ;;
  -h|--help)       sed -n '/^# USAGE/,/^# EXIT/p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) echo "usage: $0 --list | --generate | --set-external NAME | --assign-access | --leak-scan FILE | --selftest" >&2; exit 2 ;;
esac
