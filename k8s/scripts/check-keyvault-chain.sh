#!/usr/bin/env bash
# check-keyvault-chain.sh — the Key Vault secret chain of every target that includes
# k8s/components/azure-keyvault, checked on the RENDER (Phase 29 plan 29-12, D-23).
#
#   secretKeyRef (Secret, key) -> SecretProviderClass secretObjects -> objectName -> Key Vault
#
# WHY A GATE: in a deployed environment every Secret a pod reads is synced by the Secrets
# Store CSI driver, and only while a pod MOUNTS the SecretProviderClass that produces it.
# A reference with no producer is a pod stuck in CreateContainerConfigError (or, for an
# `optional: true` ref, a pod that starts silently unconfigured); two producers for one
# Secret overwrite each other's keys; a mounting pod whose ServiceAccount client-id is
# not the SPC's clientID fetches as the wrong identity. None of that is visible to the
# other render gates, and all of it is visible here. The rules themselves are in
# k8s/scripts/keyvault-chain.py (which also derives the Key Vault object list that plan
# 29-13's write-once writer and deploy preflight consume: `keyvault-chain.py objects`).
#
# TARGETS: every k8s/<dir>/kustomization.yaml (the same depth-2 discovery as the other
# render gates) whose non-comment lines name components/azure-keyvault. Today that is
# k8s/staging alone; production joins in Phase 32. ZERO such targets is VOID, never a
# pass, because a renamed component would otherwise switch this gate off silently.
#
# USAGE
#   bash k8s/scripts/check-keyvault-chain.sh             check every discovered target
#   bash k8s/scripts/check-keyvault-chain.sh --selftest  plant breaks, require each caught
#   KEYVAULT_CHAIN_SELFTEST_FORMAT=tap ... --selftest    the same, as TAP (RED evidence)
#
# Requires kubectl (client-side `kubectl kustomize` only, no cluster) and python3 with
# PyYAML. Locally, run it with a conda env on PATH (the host shim refuses bare python3):
#   source ~/miniconda3/etc/profile.d/conda.sh && conda activate jtoye-ops
# KEYVAULT_CHAIN_KUBECTL / KEYVAULT_CHAIN_PYTHON override the two tools (the selftest's
# VOID arms use them).
#
# EXIT: 0 every chain holds, 1 a chain is broken (or a selftest arm was not caught),
#       2 VOID (missing tooling, a failed or empty render, no target, no SPC).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$REPO_ROOT/k8s"
CHAIN_PY="$SCRIPT_DIR/keyvault-chain.py"
KUBECTL="${KEYVAULT_CHAIN_KUBECTL:-kubectl}"
read -r -a PY <<< "${KEYVAULT_CHAIN_PYTHON:-python3}"
COMPONENT_RE='components/azure-keyvault'

void() { echo "VOID: $*" >&2; exit 2; }

require_tools() {
    command -v "$KUBECTL" > /dev/null 2>&1 || void "kubectl not found ('$KUBECTL'); the render cannot be built"
    command -v "${PY[0]}" > /dev/null 2>&1 || void "python3 not found ('${PY[0]}'); the chain cannot be checked"
    [[ -r "$CHAIN_PY" ]] || void "chain library not readable: $CHAIN_PY"
}

# Targets whose kustomization names the component on a non-comment line.
discover_targets() {
    local k
    while IFS= read -r k; do
        if awk -v re="$COMPONENT_RE" '!/^[[:space:]]*#/ && $0 ~ re { f = 1 } END { exit !f }' "$k"; then
            dirname "$k"
        fi
    done < <(find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml' | LC_ALL=C sort)
}

run_gate() {
    require_tools
    local tmp targets=() t rel out rc failed=0 n=0
    tmp="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$tmp'" EXIT
    mapfile -t targets < <(discover_targets)
    (( ${#targets[@]} > 0 )) || void "no kustomize target under $K8S_DIR includes $COMPONENT_RE; the chain gate would check nothing"
    for t in "${targets[@]}"; do
        rel="${t#"$REPO_ROOT"/}"
        n=$((n + 1))
        if ! "$KUBECTL" kustomize "$t" > "$tmp/render.yaml" 2> "$tmp/err"; then
            cat "$tmp/err" >&2
            void "[$rel] kubectl kustomize failed"
        fi
        [[ -s "$tmp/render.yaml" ]] || void "[$rel] the render is empty"
        out=$("${PY[@]}" "$CHAIN_PY" check "$tmp/render.yaml" 2>&1); rc=$?
        case "$rc" in
            0) echo "OK   [$rel] $out" ;;
            1) echo "FAIL [$rel]" >&2; printf '%s\n' "$out" >&2; failed=1 ;;
            *) printf '%s\n' "$out" >&2; void "[$rel] keyvault-chain.py check exited $rc" ;;
        esac
    done
    if (( failed != 0 )); then
        echo "FAIL: the Key Vault chain is broken in at least one of $n target(s) — see above." >&2
        return 1
    fi
    echo "PASS: the Key Vault chain holds in $n target(s) (${targets[*]#"$REPO_ROOT"/})."
}

# ---------------------------------------------------------------------------
# --selftest: the gate shown FAILING before it is trusted (proof standard 1).
# Each arm plants one break into a copy of the REAL staging render and requires
# rc 1 with a message naming the break; the VOID arms require rc 2; the real render
# requires rc 0, through the library and through the whole gate.
# ---------------------------------------------------------------------------
plant() {
    # plant <arm> <in.yaml> <out.yaml>: one break, applied with PyYAML. rc 3 = the
    # plant did not change the render (so a "caught" would prove nothing).
    "${PY[@]}" - "$1" "$2" "$3" <<'PYEOF'
import sys, yaml
arm, src, dst = sys.argv[1:4]
docs = [d for d in yaml.safe_load_all(open(src)) if isinstance(d, dict)]
before = yaml.safe_dump_all(docs, sort_keys=True)

def find(kind, name):
    for d in docs:
        if d.get("kind") == kind and (d.get("metadata") or {}).get("name") == name:
            return d
    raise SystemExit(f"plant {arm}: {kind}/{name} not in the render")

def container(dep, cname):
    for c in dep["spec"]["template"]["spec"]["containers"]:
        if c["name"] == cname:
            return c
    raise SystemExit(f"plant {arm}: container {cname} not found")

if arm == "undeclared-key":
    container(find("Deployment", "core-java"), "core-java").setdefault("env", []).append(
        {"name": "PLANTED_KEY", "valueFrom": {"secretKeyRef": {"name": "dsar-credentials", "key": "not-a-key"}}})
elif arm == "two-producers":
    spc = find("SecretProviderClass", "core-java-kv")
    twin = yaml.safe_load(yaml.safe_dump(spc))
    twin["metadata"]["name"] = "planted-kv"
    twin["spec"]["secretObjects"] = [{"secretName": "postgres-credentials", "type": "Opaque",
                                      "data": [{"key": "host", "objectName": "postgres-credentials--host"}]}]
    docs.append(twin)
elif arm == "clientid-mismatch":
    find("SecretProviderClass", "frontend-kv")["spec"]["parameters"]["clientID"] = "00000000-0000-4000-8000-000000000000"
elif arm == "sync-lifetime":
    container(find("Deployment", "frontend"), "frontend").setdefault("env", []).append(
        {"name": "PLANTED_RMQ", "valueFrom": {"secretKeyRef": {"name": "rabbitmq-default-user", "key": "username"}}})
elif arm == "shared-identity":
    # frontend's SPC AND ServiceAccount both carry keycloak's client id: consistent with
    # each other (so rule (d) holds) but two workloads now share one identity.
    kc = find("ServiceAccount", "keycloak")["metadata"]["annotations"]["azure.workload.identity/client-id"]
    find("ServiceAccount", "frontend")["metadata"]["annotations"]["azure.workload.identity/client-id"] = kc
    find("SecretProviderClass", "frontend-kv")["spec"]["parameters"]["clientID"] = kc
elif arm == "object-not-mounted":
    so = find("SecretProviderClass", "redis-kv")["spec"]["secretObjects"][0]
    so["data"][0]["objectName"] = "redis-credentials--planted"
elif arm == "cert-secret-spc-producer":
    # an SPC ALSO declares the Secret cert-manager's Certificate mailpit-tls writes
    spc = find("SecretProviderClass", "core-java-kv")
    spc["spec"].setdefault("secretObjects", []).append({"secretName": "mailpit-tls", "type": "Opaque",
        "data": [{"key": "tls.key", "objectName": "mailpit-tls--tls-key"}]})
elif arm == "cert-secret-foreign-key":
    # a consumer reads a key cert-manager never writes into its Secret
    container(find("Deployment", "core-java"), "core-java").setdefault("env", []).append(
        {"name": "PLANTED_CA", "valueFrom": {"secretKeyRef": {"name": "jtoye-staging-ca", "key": "password"}}})
elif arm == "zero-spc":
    docs = [d for d in docs if d.get("kind") != "SecretProviderClass"]
else:
    raise SystemExit(f"unknown arm {arm}")
after = yaml.safe_dump_all(docs, sort_keys=True)
if after == before:
    sys.exit(3)
open(dst, "w").write(after)
PYEOF
}

selftest() {
    require_tools
    "${PY[@]}" -c 'import yaml' 2> /dev/null || void "PyYAML not importable by '${PY[*]}'; the selftest plants cannot be written"
    local fmt="${KEYVAULT_CHAIN_SELFTEST_FORMAT:-plain}" tmp arm want wantrc out rc caught line n=0 bad=0 tap=()
    tmp="$(mktemp -d)"
    # shellcheck disable=SC2064
    trap "rm -rf '$tmp'" EXIT
    "$KUBECTL" kustomize "$K8S_DIR/staging" > "$tmp/real.yaml" 2> "$tmp/err" || { cat "$tmp/err" >&2; void "the staging render failed"; }
    [[ -s "$tmp/real.yaml" ]] || void "the staging render is empty"

    local -a ARMS=(undeclared-key two-producers clientid-mismatch sync-lifetime shared-identity object-not-mounted cert-secret-spc-producer cert-secret-foreign-key zero-spc pyyaml-missing kubectl-missing)
    declare -A WANT=(
        [undeclared-key]="Deployment/core-java reads dsar-credentials/not-a-key"
        [two-producers]="Secret postgres-credentials has two producers"
        [clientid-mismatch]="Deployment/frontend mounts frontend-kv, whose clientID '00000000-0000-4000-8000-000000000000' differs"
        [sync-lifetime]="Deployment/frontend reads rabbitmq-default-user/username (env PLANTED_RMQ), produced by rabbitmq-kv, which Deployment/frontend does not mount"
        [shared-identity]="ServiceAccounts frontend, keycloak share"
        [object-not-mounted]="which is not in its parameters.objects"
        [cert-secret-spc-producer]="Secret mailpit-tls has two producers: SecretProviderClass core-java-kv and cert-manager Certificate mailpit-tls"
        [cert-secret-foreign-key]="Deployment/core-java reads jtoye-staging-ca/password (env PLANTED_CA): Secret jtoye-staging-ca is written by cert-manager Certificate jtoye-staging-ca, which holds only ca.crt, tls.crt, tls.key"
        [zero-spc]="the render holds no SecretProviderClass"
        [pyyaml-missing]="PyYAML is not importable"
        [kubectl-missing]="kubectl not found"
    )
    declare -A WANTRC=([zero-spc]=2 [pyyaml-missing]=2 [kubectl-missing]=2)
    for arm in "${ARMS[@]}"; do
        n=$((n + 1)); want="${WANT[$arm]}"; wantrc="${WANTRC[$arm]:-1}"; line=""
        case "$arm" in
            pyyaml-missing)
                # -S drops site-packages (conda's and Debian's dist-packages alike), so the
                # library's own `import yaml` fails exactly as it would without PyYAML.
                out=$("${PY[@]}" -S "$CHAIN_PY" check "$tmp/real.yaml" 2>&1); rc=$? ;;
            kubectl-missing)
                out=$(KEYVAULT_CHAIN_KUBECTL="$tmp/no-such-kubectl" bash "${BASH_SOURCE[0]}" 2>&1); rc=$? ;;
            *)
                plant "$arm" "$tmp/real.yaml" "$tmp/$arm.yaml"; rc=$?
                if (( rc != 0 )); then
                    out="plant did not apply (rc=$rc)"; rc=-1
                else
                    out=$("${PY[@]}" "$CHAIN_PY" check "$tmp/$arm.yaml" 2>&1); rc=$?
                fi ;;
        esac
        if (( rc == wantrc )) && grep -qF -- "$want" <<< "$out"; then
            caught=yes
        else
            caught=no
            line="want rc $wantrc and '$want'; got rc=$rc: $(awk 'NF && c < 2 { printf "%s ", $0; c++ }' <<< "$out")"
            bad=$((bad + 1))
        fi
        if [[ "$fmt" == tap ]]; then
            if [[ "$caught" == yes ]]; then tap+=("ok $n - $arm"); else
                tap+=("not ok $n - $arm" "  ---" "  message: '${line//\'/}'" "  ..."); fi
        else
            echo "ARM $arm caught=$caught${line:+ ($line)}"
        fi
    done
    # The clean direction: the real render through the library, then the whole gate.
    local clean_lib clean_gate
    out=$("${PY[@]}" "$CHAIN_PY" check "$tmp/real.yaml" 2>&1); clean_lib=$?
    out=$(bash "${BASH_SOURCE[0]}" 2>&1); clean_gate=$?
    for arm in clean-library clean-gate; do
        n=$((n + 1))
        if [[ "$arm" == clean-library ]]; then rc=$clean_lib; else rc=$clean_gate; fi
        (( rc == 0 )) || bad=$((bad + 1))
        if [[ "$fmt" == tap ]]; then
            if (( rc == 0 )); then tap+=("ok $n - $arm"); else
                tap+=("not ok $n - $arm" "  ---" "  message: 'expected rc 0 on the real staging render, got rc=$rc'" "  ..."); fi
        else
            echo "CLEAN $arm rc=$rc"
        fi
    done
    if [[ "$fmt" == tap ]]; then
        printf '%s\n' "TAP version 13" "1..$n" "${tap[@]}"
    else
        (( clean_gate == 0 )) || awk 'NF && c < 5 { print; c++ }' <<< "$out" >&2
        echo "SELFTEST: $((n - bad))/$n as expected"
    fi
    (( bad == 0 ))
}

case "${1:-}" in
    --selftest) selftest; exit $? ;;
    "") run_gate; exit $? ;;
    *) echo "usage: $0 [--selftest]" >&2; exit 2 ;;
esac
