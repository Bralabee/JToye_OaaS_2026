# shellcheck shell=bash
# scripts/lib/staging-secrets-common.sh — what scripts/staging-secrets.sh (the write-once
# Key Vault writer) and scripts/staging-secrets-preflight.sh (the deploy preflight) share
# (Phase 29 plan 29-13, D-23; threats T-29-47..T-29-51).
#
# SOURCED, NEVER EXECUTED. The caller owns `set` options, traps and exit codes; nothing
# here calls `exit`. The caller sets SS_REPO_ROOT before sourcing.
#
# ONE DERIVATION, NEVER A HAND LIST (D-23)
#   ss_derive DIR writes, into DIR:
#     render.yaml  `kubectl kustomize k8s/staging` (or $STAGING_SECRETS_RENDER_FILE, the
#                  selftests' planted render)
#     objects      the sorted, de-duplicated union of
#                    python3 k8s/scripts/keyvault-chain.py objects render.yaml   (plan 29-12)
#                    bash scripts/staging-realm-import.sh --list-kv-objects       (plan 29-04)
#                    bash scripts/staging-seed.sh --list-kv-objects               (plan 29-06)
#                    bash scripts/staging-mail-ui.sh --list-kv-objects            (plan 29-22: the
#                      mail-UI PLAINTEXT, which no SecretProviderClass names, so no workload
#                      identity is granted it; only its apr1 hash is mounted, D-33)
#     access       `<clientID> TAB <objectName>`, from keyvault-chain.py access: the objects
#                  each SecretProviderClass's identity may read, and nothing else
#   Any source failing, or an empty result, is VOID (return 2): "found nothing" is not
#   "nothing is needed". keyvault-chain.py prints a list only when the chain holds, so a
#   broken render cannot hand the writer a list that looks right.
#   pg-admin--password is in no source: plan 29-07's datastores script owns it, and it
#   already exists. It is never generated here.
#
# CLASSES (ss_classify NAME -> "<class> <kind>"; an unknown name returns 1 and the writer
# REFUSES, so a new secretKeyRef cannot slip through unclassified)
#   estate     a fact about the estate, not a secret (host, port, database, role names)
#   random     generated here once from openssl's CSPRNG
#   composite  built from other objects, read back from the vault (composites after inputs);
#              htpasswd-apr1:<user>:<input> is the line `<user>:<openssl passwd -apr1 -stdin>`
#              over the input's value (the password on stdin, never argv; plan 29-22, D-33)
#   external   supplied by the owner on stdin; the kind is the REQUIRED prefix (D-28:
#              a Stripe key that is not a TEST key never enters staging)
#
# TOOL OVERRIDES (selftests only): STAGING_SECRETS_KUBECTL, STAGING_SECRETS_PYTHON,
# STAGING_SECRETS_RENDER_FILE.

SS_KV_NAME="jtoye-stg-kv"
SS_KV_RG="jtoye-rg"
# Built-in role "Key Vault Secrets User" (read secret contents). Matched and assigned by
# id: the name is a display string, the id is the role.
SS_ROLE_ID="4633458b-17de-408a-b874-0445c86b69e6"
SS_ROLE_NAME="Key Vault Secrets User"
SS_IDENTITY_FILE_REL="k8s/staging/azure-identity.yaml"
SS_ROTATION_RUNBOOK="docs/runbooks/credential-rotation.md"

ss_void() { echo "VOID: $*" >&2; return 2; }

# ss_derive DIR — see header. Returns 0, or 2 (VOID) with the reason on stderr.
ss_derive() {
  local dir="$1" kubectl="${STAGING_SECRETS_KUBECTL:-kubectl}" chain rc out
  local -a py
  read -r -a py <<< "${STAGING_SECRETS_PYTHON:-python3}"
  chain="$SS_REPO_ROOT/k8s/scripts/keyvault-chain.py"
  [ -r "$chain" ] || { ss_void "keyvault-chain.py not readable at $chain"; return 2; }
  command -v "${py[0]}" >/dev/null 2>&1 || { ss_void "python3 not found ('${py[0]}'); the object list cannot be derived (activate the jtoye-ops env)"; return 2; }
  if [ -n "${STAGING_SECRETS_RENDER_FILE:-}" ]; then
    cp "$STAGING_SECRETS_RENDER_FILE" "$dir/render.yaml" 2>/dev/null || { ss_void "render file $STAGING_SECRETS_RENDER_FILE unreadable"; return 2; }
  else
    command -v "$kubectl" >/dev/null 2>&1 || { ss_void "kubectl not found ('$kubectl'); the staging render cannot be built"; return 2; }
    "$kubectl" kustomize "$SS_REPO_ROOT/k8s/staging" > "$dir/render.yaml" 2> "$dir/render.err" \
      || { cat "$dir/render.err" >&2; ss_void "kubectl kustomize k8s/staging failed"; return 2; }
  fi
  [ -s "$dir/render.yaml" ] || { ss_void "the staging render is empty"; return 2; }

  : > "$dir/objects.all"
  rc=0; out=$("${py[@]}" "$chain" objects "$dir/render.yaml" 2> "$dir/chain.err") || rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$out" ]; then
    cat "$dir/chain.err" >&2
    ss_void "keyvault-chain.py objects exited $rc with $(printf '%s' "$out" | command grep -c .) line(s); no list from a broken or unread chain"
    return 2
  fi
  printf '%s\n' "$out" >> "$dir/objects.all"
  rc=0; out=$("${py[@]}" "$chain" access "$dir/render.yaml" 2>> "$dir/chain.err") || rc=$?
  if [ "$rc" -ne 0 ] || [ -z "$out" ]; then
    cat "$dir/chain.err" >&2
    ss_void "keyvault-chain.py access exited $rc; no grant list from a broken or unread chain"
    return 2
  fi
  printf '%s\n' "$out" > "$dir/access"

  local lister
  for lister in staging-realm-import.sh staging-seed.sh staging-mail-ui.sh; do
    rc=0; out=$(bash "$SS_REPO_ROOT/scripts/$lister" --list-kv-objects 2> "$dir/lister.err") || rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$out" ]; then
      cat "$dir/lister.err" >&2
      ss_void "scripts/$lister --list-kv-objects exited $rc with no list"
      return 2
    fi
    printf '%s\n' "$out" >> "$dir/objects.all"
  done
  command grep -v '^[[:space:]]*$' "$dir/objects.all" | LC_ALL=C sort -u > "$dir/objects"
  [ -s "$dir/objects" ] || { ss_void "the derived object list is empty"; return 2; }
  # A malformed name is a derivation fault, not an object.
  if command grep -qvE '^[0-9A-Za-z-]{1,127}$' "$dir/objects"; then
    ss_void "a derived name is not a valid Key Vault object name: $(command grep -vE '^[0-9A-Za-z-]{1,127}$' "$dir/objects" | head -n 1)"
    return 2
  fi
  return 0
}

# ss_classify NAME -> prints "<class> <kind>", returns 1 when nothing matches.
# ORDERED: the first matching row wins, so the specific rows sit above *password*.
ss_classify() {
  case "$1" in
    postgres-credentials--host)                         echo "estate pg_host" ;;
    postgres-credentials--port)                         echo "estate lit:5432" ;;
    postgres-credentials--database)                     echo "estate lit:jtoye" ;;
    postgres-credentials--username)                     echo "estate lit:jtoye_runtime" ;;
    postgres-credentials--migration-username)           echo "estate lit:jtoye_app" ;;
    postgres-credentials--backup-username)              echo "estate lit:jtoye_backup" ;;
    rabbitmq-credentials--username)                     echo "estate lit:jtoye" ;;
    keycloak-credentials--admin-username)               echo "estate lit:jtoye-kc-admin" ;;
    # The monitoring role postgres-exporter connects as (plan 29-23): an estate fact, created
    # by scripts/staging-db-bootstrap.sh --stage monitor with the password object below.
    postgres-exporter-credentials--username)            echo "estate lit:jtoye_monitor" ;;
    # Mailpit accepts no auth in staging; the value exists so no ref starts unconfigured.
    smtp-credentials--username)                         echo "estate lit:staging-mailpit" ;;
    rabbitmq-credentials--stomp-login)                  echo "composite copy:rabbitmq-credentials--username" ;;
    rabbitmq-credentials--stomp-passcode)               echo "composite copy:rabbitmq-credentials--password" ;;
    rabbitmq-default-user--default-user-conf)           echo "composite rabbitmq-conf" ;;
    stripe-credentials--api-key)                        echo "external sk_test_" ;;
    stripe-credentials--webhook-secret)                 echo "external whsec_" ;;
    nextauth-secret--secret)                            echo "random b64url32" ;;
    # core-java refuses to start without a valid 64-hex DSAR key (V70).
    dsar-credentials--encryption-key)                   echo "random hex64" ;;
    client-ip-credentials--hmac-key)                    echo "random hex64" ;;
    notification-credentials--unsubscribe-signing-secret) echo "random hex64" ;;
    staging-seed--test-vendor-password)                 echo "random vendor-password" ;;
    keycloak-credentials--frontend-client-secret)       echo "random alnum32" ;;
    keycloak-realm--*-secret)                           echo "random alnum32" ;;
    # The testers' mail UI (plan 29-22, D-33): a generated password, and the apr1
    # htpasswd line Traefik's basic auth reads, derived from it (the only one mounted).
    mailpit-ui-credentials--password)                   echo "random alnum32" ;;
    mailpit-ui-credentials--auth)                       echo "composite htpasswd-apr1:staging-tester:mailpit-ui-credentials--password" ;;
    # Stated, not left to *password* below (plan 29-23; 29-12 noted it missing from the list).
    postgres-exporter-credentials--password)            echo "random alnum32" ;;
    *password*|*passcode*)                              echo "random alnum32" ;;
    *) return 1 ;;
  esac
  return 0
}

# ss_is_secret NAME -> 0 when the object's VALUE is a secret (what a leak scan looks for).
# Estate values are identifiers (`jtoye`, `5432`) that a correct evidence file contains, and
# stomp-login copies the RabbitMQ user name.
ss_is_secret() {
  local c
  c=$(ss_classify "$1") || return 0   # unknown: treat as secret (fail toward scanning)
  case "$c" in
    estate\ *) return 1 ;;
    "composite copy:"*) ss_is_secret "${c#composite copy:}"; return $? ;;
  esac
  return 0
}

# ss_identity_value KEY -> a data value of k8s/staging/azure-identity.yaml.
ss_identity_value() {
  local f="$SS_REPO_ROOT/$SS_IDENTITY_FILE_REL" v
  v=$(awk -v k="$1" '$1 == k":" { v = $2; gsub(/"/, "", v); print v; exit }' "$f" 2>/dev/null)
  [ -n "$v" ] || return 1
  printf '%s\n' "$v"
}

# ---------------------------------------------------------------------------
# Selftest support: a STATEFUL test-double CLI (an in-memory vault). Never contacts Azure.
# State lives in $SS_STUB_STATE:
#   vault/<name>             the current value (file content, as `--file` would store it)
#   vault/<name>.versions    one line per version written (the version count)
#   vault/<name>.disabled    present = the object is disabled
#   identities               `<clientId> TAB <principalId>` rows (identity list)
#   roles                    `<principalId> TAB <roleDefinitionId> TAB <scope>` rows
#   argv.log                 every argv, one space-joined line (the leak arm reads it)
# account show answers "JToye Digital Production<TAB>Enabled", as az_guard_stub does, so
# az_guard_init passes. Anything the stub does not implement exits 9 (fail closed).
# ---------------------------------------------------------------------------
ss_stub_write() {
  local bin="$1"
  mkdir -p "$bin" || return 2
  cat > "$bin/az" <<'STUB'
#!/usr/bin/env bash
# Stateful Key Vault test double written by ss_stub_write (scripts/lib/staging-secrets-common.sh).
st="${SS_STUB_STATE:?SS_STUB_STATE unset}"
printf '%s\n' "$*" >> "$st/argv.log"
line=" $* "
name="" vault="" file="" scope="" oid="" role="" query=""
args=("$@")
i=0
while [ "$i" -lt "${#args[@]}" ]; do
  a="${args[$i]}"; nx="${args[$((i + 1))]:-}"
  case "$a" in
    -n|--name) name="$nx"; i=$((i + 1)) ;;
    --vault-name) vault="$nx"; i=$((i + 1)) ;;
    --file|-f) file="$nx"; i=$((i + 1)) ;;
    --scope) scope="$nx"; i=$((i + 1)) ;;
    --assignee-object-id) oid="$nx"; i=$((i + 1)) ;;
    --role) role="$nx"; i=$((i + 1)) ;;
    --query) query="$nx"; i=$((i + 1)) ;;
  esac
  i=$((i + 1))
done
kvid="/subscriptions/c483d353-5f61-4587-a790-addb9ab5fb94/resourceGroups/jtoye-rg/providers/Microsoft.KeyVault/vaults/jtoye-stg-kv"
mkdir -p "$st/vault"
case "$line" in
  *" account show "*"{name:name,state:state}"*)
    printf '%s\t%s\n' "JToye Digital Production" "Enabled"; exit 0 ;;
  *" keyvault secret set "*)
    [ "$vault" = jtoye-stg-kv ] || { echo "stub: wrong vault '$vault'" >&2; exit 9; }
    [ -n "$name" ] && [ -n "$file" ] && [ -r "$file" ] || { echo "stub: secret set needs -n and a readable --file" >&2; exit 9; }
    cp "$file" "$st/vault/$name"
    echo "v$(( $(wc -l < "$st/vault/$name.versions" 2>/dev/null || echo 0) + 1 ))" >> "$st/vault/$name.versions"
    exit 0 ;;
  *" keyvault secret show "*)
    if [ ! -f "$st/vault/$name" ]; then
      echo "ERROR: (SecretNotFound) A secret with (name/id) $name was not found in this key vault." >&2
      exit 3
    fi
    case "$query" in
      id) printf 'https://jtoye-stg-kv.vault.azure.net/secrets/%s/%032d\n' "$name" "$(wc -l < "$st/vault/$name.versions" 2>/dev/null || echo 0)" ;;
      value) cat "$st/vault/$name"; echo ;;
      *) echo "stub: secret show --query '$query' not implemented" >&2; exit 9 ;;
    esac
    exit 0 ;;
  *" keyvault secret list "*)
    for f in "$st"/vault/*; do
      [ -e "$f" ] || continue
      n="${f##*/}"
      case "$n" in *.versions|*.disabled) continue ;; esac
      case "$query" in
        *enabled*) if [ -e "$st/vault/$n.disabled" ]; then printf '%s\tfalse\n' "$n"; else printf '%s\ttrue\n' "$n"; fi ;;
        *) printf '%s\n' "$n" ;;
      esac
    done
    exit 0 ;;
  *" keyvault show "*)
    printf '%s\n' "$kvid"; exit 0 ;;
  *" identity list "*)
    cat "$st/identities" 2>/dev/null; exit 0 ;;
  *" role assignment list "*)
    # By scope (the preflight's --names-only form, plan 29-21): the principals holding a role
    # at EXACTLY that scope, as `az role assignment list --scope S` (no --all) returns them.
    if [ -z "$oid" ] && [ -n "$scope" ]; then
      awk -F'\t' -v s="$scope" 'tolower($3) == tolower(s) { print $2 "\t" $1 }' "$st/roles" 2>/dev/null \
        | awk -F'\t' -v r="$query" '{ id = $1; sub(/.*\//, "", id); if (index(r, id)) print $2 }'
      exit 0
    fi
    [ -n "$oid" ] || { echo "stub: role assignment list without --assignee-object-id or --scope" >&2; exit 9; }
    awk -F'\t' -v p="$oid" '$1 == p { print $2 "\t" $3 }' "$st/roles" 2>/dev/null \
      | awk -F'\t' -v r="$query" '{ id = $1; sub(/.*\//, "", id); if (index(r, id)) print $2 }'
    exit 0 ;;
  *" role assignment create "*)
    [ -n "$oid" ] && [ -n "$scope" ] && [ -n "$role" ] || { echo "stub: role assignment create needs oid, role and scope" >&2; exit 9; }
    printf '%s\t/subscriptions/c483d353-5f61-4587-a790-addb9ab5fb94/providers/Microsoft.Authorization/roleDefinitions/%s\t%s\n' "$oid" "$role" "$scope" >> "$st/roles"
    exit 0 ;;
esac
echo "stub: not implemented: $*" >&2
exit 9
STUB
  chmod +x "$bin/az" || return 2
  return 0
}

# ss_stub_principal CLIENTID -> the stub's principal id for an identity.
ss_stub_principal() { printf 'p0000000-%s\n' "${1#????????-}"; }

# ss_stub_identities STATE ACCESS_FILE -> one identity row per clientID in the grant list.
ss_stub_identities() {
  local st="$1" access="$2" cid
  : > "$st/identities"
  while IFS=$'\t' read -r cid _; do
    [ -n "$cid" ] || continue
    printf '%s\t%s\n' "$cid" "$(ss_stub_principal "$cid")"
  done < <(cut -f1 "$access" | LC_ALL=C sort -u) >> "$st/identities"
}

# ss_stub_grant_all STATE ACCESS_FILE -> the role row for every grant (a fully assigned vault).
ss_stub_grant_all() {
  local st="$1" access="$2" cid obj kvid
  kvid="/subscriptions/c483d353-5f61-4587-a790-addb9ab5fb94/resourceGroups/jtoye-rg/providers/Microsoft.KeyVault/vaults/jtoye-stg-kv"
  : > "$st/roles"
  while IFS=$'\t' read -r cid obj; do
    [ -n "$cid" ] || continue
    printf '%s\t/subscriptions/c483d353-5f61-4587-a790-addb9ab5fb94/providers/Microsoft.Authorization/roleDefinitions/%s\t%s/secrets/%s\n' \
      "$(ss_stub_principal "$cid")" "$SS_ROLE_ID" "$kvid" "$obj" >> "$st/roles"
  done < "$access"
}

# ss_plant IN OUT SECRET KEY -> a copy of the render in which core-java reads SECRET/KEY and
# core-java's SPC produces it from object SECRET--KEY, so the chain still HOLDS and the new
# object reaches the derived list exactly as a real new secretKeyRef would. rc 3 = no change.
ss_plant() {
  local -a py
  read -r -a py <<< "${STAGING_SECRETS_PYTHON:-python3}"
  "${py[@]}" - "$@" <<'PYEOF'
import sys, yaml
src, dst, secret, key = sys.argv[1:5]
docs = [d for d in yaml.safe_load_all(open(src)) if isinstance(d, dict)]
before = yaml.safe_dump_all(docs, sort_keys=True)
obj = f"{secret}--{key}"
def find(kind, name):
    for d in docs:
        if d.get("kind") == kind and (d.get("metadata") or {}).get("name") == name:
            return d
    raise SystemExit(f"plant: {kind}/{name} not in the render")
spc = find("SecretProviderClass", "core-java-kv")
params = spc["spec"]["parameters"]
outer = yaml.safe_load(params["objects"])
outer["array"].append(yaml.safe_dump({"objectName": obj, "objectType": "secret"}))
params["objects"] = yaml.safe_dump(outer)
spc["spec"].setdefault("secretObjects", []).append(
    {"secretName": secret, "type": "Opaque", "data": [{"key": key, "objectName": obj}]})
dep = find("Deployment", "core-java")
for c in dep["spec"]["template"]["spec"]["containers"]:
    if c["name"] == "core-java":
        c.setdefault("env", []).append(
            {"name": "PLANTED_" + key.upper().replace("-", "_"),
             "valueFrom": {"secretKeyRef": {"name": secret, "key": key}}})
        break
else:
    raise SystemExit("plant: container core-java not found")
after = yaml.safe_dump_all(docs, sort_keys=True)
if after == before:
    sys.exit(3)
open(dst, "w").write(after)
PYEOF
}

# Selftest result collection and rendering (plain, or TAP 13 for RED evidence).
SS_RESULTS=()
ss_result() { SS_RESULTS+=("$1"); }
ss_render() {
  local fmt="$1" i=0 r verdict rest
  if [ "$fmt" = tap ]; then
    echo "TAP version 13"
    echo "1..${#SS_RESULTS[@]}"
    for r in "${SS_RESULTS[@]}"; do
      i=$((i + 1)); verdict="${r%% *}"; rest="${r#* }"
      if [ "$verdict" = PASS ]; then
        echo "ok $i - ${rest%% *}"
      else
        echo "not ok $i - ${rest%% *}"
        echo "  ---"
        echo "  message: '$(printf '%s' "${rest#* }" | tr "'\n" '" ')'"
        echo "  ..."
      fi
    done
  else
    for r in "${SS_RESULTS[@]}"; do printf '%s\n' "$r"; done
  fi
}
