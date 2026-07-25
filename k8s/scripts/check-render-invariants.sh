#!/usr/bin/env bash
# check-render-invariants.sh — rendered-manifest assertions that pin the
# Phase 26 fixes so their defect classes cannot silently return.
#
# WHY A SEPARATE, RENDER-LEVEL GATE
#   Everything this script asserts was a REAL defect that a raw-file review, a
#   passing CI run and (for two of them) a live cluster rehearsal all missed. The
#   common cause: the thing that reaches the cluster is the kustomize RENDER, and
#   nothing was asserting on the render.
#   k8s/scripts/validate-networkpolicies.py parses the RAW files, so it is
#   structurally incapable of seeing INV-3 — the label transformer only injects
#   the offending labels at render time. That is why INV-3 lives here.
#
# THE INVARIANTS, THE DEFECT EACH PINS, AND THE PLAN THAT FIXED IT
#
#   INV-1  DEF-1 / INFRA-02a, SOURCE level. k8s/base/core-java-deployment.yaml
#          must not carry a hardcoded `value: "5432"` for the Postgres port.
#          A hardcoded port made every environment's DB port a manifest edit; the
#          host dev Postgres publishes 5433, so a local cluster could not work at
#          all. Fixed in plan 26-01 by routing DB_PORT through
#          postgres-credentials/port.
#
#   INV-2  PIT-2, RENDER level, per target. No rendered EnvVar may carry BOTH
#          `value:` and `valueFrom:`. `kubectl kustomize` emits that combination
#          without complaint (a strategic-merge patch that adds `valueFrom` to an
#          env item which still has `value:` merges rather than replaces), but the
#          API server rejects the apply:
#            env[i].valueFrom: Invalid value: "": may not be specified when
#            'value' is not empty
#          So the render can look fine in CI and fail at rollout. This is the
#          reason DEF-1 had to DELETE the literal in the base rather than patch
#          around it in an overlay. Pinned by plan 26-01.
#
#   INV-3  D-17, RENDER level, per target. A `matchLabels` block that selects
#          kube-dns (`k8s-app: kube-dns`) must contain NOTHING ELSE. The base
#          kustomization's label transformer used `includeSelectors: true`, which
#          injected app.kubernetes.io/managed-by, app.kubernetes.io/part-of and
#          environment into the DNS-egress podSelector of
#          networkpolicies/20-core-java.yaml. Real kube-dns pods carry none of
#          those, so the selector matched NOTHING and core-java had ZERO DNS
#          egress under an enforcing CNI — a total outage. Inert on minikube
#          (default CNI does not enforce NetworkPolicies), which is exactly why
#          it survived the live rehearsal. Fixed in plan 26-01 by replacing
#          includeSelectors with an explicit `fields:` list.
#
#          ASSERTION SHAPE IS LOAD-BEARING. This walks each `matchLabels:` block
#          by INDENTATION and inspects that block's own keys. A forward
#          `grep -A N 'k8s-app: kube-dns'` scan is UNFALSIFIABLE: kustomize sorts
#          map keys alphabetically, so the poisoned labels sort BEFORE `k8s-app`
#          and a forward scan returns 0 on the poisoned baseline too.
#
#   INV-4  DEF-6 recurrence, RENDER level, per target except local overlays. A
#          staging or production render must contain no `localhost`, `127.0.0.1`
#          or `minioadmin` literal. Thirteen placeholders used to resolve to
#          local-only defaults; plan 26-02 supplied them. This is the
#          non-regression half — check-env-contract.sh guards the config side,
#          this guards the rendered side.
#
#   INV-5  DEF-2 / INFRA-02b, DOCS level. Neither k8s/QUICK_START.md nor
#          k8s/base/secrets-template.yaml.example may name the DB SUPERUSER as
#          the postgres-credentials app username. A superuser BYPASSES EVERY RLS
#          POLICY, which is the whole multi-tenant isolation boundary, so
#          DatabaseConfigurationValidator fails core-java's boot fast when it
#          detects one. A copy-pasteable superuser recipe is therefore a latent
#          RLS bypass. Fixed in plan 26-02 (recipe, template stringData, and the
#          template's own comment-block recipe).
#
#          ASSERTION SHAPE IS LOAD-BEARING here too. The token `jtoye` appears
#          LEGITIMATELY in both files as the RabbitMQ BROKER username, so a
#          whole-file grep for it fails on a CORRECT tree. Both halves are
#          therefore BLOCK-SCOPED: each `kubectl create secret generic <name>`
#          recipe and each YAML document is attributed to its Secret name, and
#          only postgres-credentials is asserted on.
#
#   INV-6  DANGLING INGRESS BACKEND, RENDER level, EVERY target. Each Ingress
#          backend `service.name` in a render must match a `kind: Service`
#          present in that SAME render. k8s/base/ingress.yaml used to publish the
#          Keycloak hostname and route it to a Service named `keycloak` that
#          exists in NO render — the complete rendered Service set is core-java,
#          edge-go and frontend, and neither overlay adds one — so staging and
#          production each published a host for which nginx answers 503, and no
#          gate saw it. Worse, that hostname also sat in the single `jtoye-tls`
#          SAN list, so a cert-manager HTTP-01 challenge for a host this
#          controller does not serve could fail the whole certificate order and
#          stall issuance for api and app too. Fixed in plan 26-04 by REMOVING
#          the rule and the SAN in k8s/base (Keycloak is an external managed IdP,
#          so there is no Service to add), and pinned here so the class cannot
#          return through a future overlay or a re-added rule.
#          This one is deliberately NOT in the k8s/local-only section: the defect
#          it pins was a PRODUCTION defect. Proven so — with the rule restored,
#          base/staging/production all FAIL while k8s/local stays OK, because the
#          local overlay's `rules:` replacement hides it. A local-only assertion
#          would have missed the real defect entirely.
#
# THE LOCAL-OVERLAY INVARIANTS (LOC-*), Phase 26 / INFRA-01
#   These run ONLY when k8s/local/kustomization.yaml exists, so the script stays
#   valid if the overlay is ever removed. They assert the shape of the committed
#   local overlay that replaced the imperative in-cluster patches used during the
#   2026-07-14 live-deploy rehearsal.
#
#   LOC-1  Endpoint shims. Each of redis.host, rabbitmq.host,
#          stomp.broker.relay-host, s3.endpoint, s3.backup.endpoint, smtp.host,
#          keycloak.issuer.uri and keycloak.admin.base-url must resolve to a
#          host.minikube.internal value. Asserted PER KEY BY NAME, not by a total
#          count: a count alone lets a LOST shim hide behind an ADDED one, which
#          is not hypothetical — it was demonstrated (redis.host -> localhost plus
#          one extra shimmed value keeps the total at 8 and a count-only
#          assertion passes).
#
#   LOC-2  The D-09 scale triple. Exactly 3 Deployments at `replicas: 1`, 3 HPAs
#          at `minReplicas: 1`, 3 PDBs at `minAvailable: 1` — an HPA floor of 3
#          would scale a 1-replica Deployment straight back up, and a PDB
#          minAvailable of 2 over one replica makes the pod undrainable. AND the
#          local HPA maxReplicas multiset must equal the one k8s/base renders:
#          maxReplicas is an input to check-connection-math.sh's Postgres
#          connection budget, so lowering it locally would silently stop the
#          local render proving the same arithmetic. Compared AGAINST BASE rather
#          than against hardcoded numbers, so a legitimate future base change
#          carries through instead of going stale.
#
#   LOC-3  The backup repoint (INFRA-01 / INFRA-02c). s3.backup.endpoint is
#          exactly http://host.minikube.internal:9000. Base leaves it EMPTY,
#          which means "real AWS S3" — locally that aims a database dump at real
#          AWS with no credentials, and makes the #101 restore rehearsal
#          impossible to run.
#
#   LOC-4  Ingress admissibility (PIT-1 / PIT-10). No configuration-snippet, no
#          cert-manager issuer, no limit-rps/limit-connections/
#          limit-burst-multiplier and no `tls:` block in any local Ingress.
#          PIT-1 is the hard one: minikube v1.36.0 bundles ingress-nginx
#          v1.12.2, where allow-snippet-annotations defaults to FALSE and
#          annotations-risk-level to High, so its validating admission webhook
#          REJECTS the base ingress outright. The base annotation is deliberately
#          PRESERVED for staging/production — the fix belongs in the local
#          overlay, never on the cluster addon.
#
#   LOC-5  Host scoping (D-12) + no dangling Keycloak backend. Local Ingress
#          hosts are exactly api.jtoye.local and app.jtoye.local, no production
#          hostname survives into the local render, and no Ingress routes to a
#          Service named keycloak.
#
#   LOC-6  D-01 at the SOURCE level. No authored file under k8s/local/ may use
#          kustomize secret generation or carry an unsubstituted placeholder
#          literal. check-no-plaintext-secrets.sh already guards the BUILD
#          OUTPUT; this guards the input, so the intent is visible where the
#          mistake would be made.
#
# NON-VACUITY
#   Every render-level invariant also asserts that it FOUND something to check
#   (a DB_PORT EnvVar, a kube-dns selector block, a postgres-credentials recipe).
#   A gate that passes because it looked at nothing is worse than no gate, so a
#   missing subject exits 2 (the parser is blind — fix the parser) rather than 0.
#
# EXTENSION POINT
#   Plan 26-04 took this up: the local-overlay assertions live here as
#   LOC-1..LOC-6 and the all-target dangling-backend assertion as INV-6, rather
#   than as a sixth gate script. Keep extending here. An assertion that applies to
#   EVERY render belongs in the per-target loop as INV-N; one that only makes
#   sense for the local overlay belongs in the conditional LOCAL section as LOC-N.
#
#   RULE FOR ANY NEW ASSERTION (learned the hard way in this phase — six
#   acceptance criteria across plans 26-01..26-04 were unfalsifiable as written):
#   before trusting a new assertion, run it against a DELIBERATELY BROKEN input
#   and confirm it FAILS. An assertion that is already-true on the correct tree,
#   or still-true on the broken tree, proves nothing.
#
# Requires: kubectl (client-side `kubectl kustomize` only — no cluster access),
#           bash (>= 4 for mapfile and associative arrays), awk, grep, find, sed,
#           sort.
# Exit codes: 0 = all invariants hold, 1 = violation, 2 = build/parse/tooling
#             failure (including a blind assertion).
#
# Usage: ./k8s/scripts/check-render-invariants.sh
#   (run from anywhere; paths resolve relative to the repo root)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
K8S_DIR="$REPO_ROOT/k8s"

CORE_DEPLOYMENT="$K8S_DIR/base/core-java-deployment.yaml"
QUICK_START="$K8S_DIR/QUICK_START.md"
SECRETS_TEMPLATE="$K8S_DIR/base/secrets-template.yaml.example"
LOCAL_DIR="$K8S_DIR/local"
LOCAL_KUSTOMIZATION="$LOCAL_DIR/kustomization.yaml"

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# INV-4 exclusion list.
#
# Matched on the EXACT repo-relative target path, never as a substring, so a
# future `k8s/local-staging` overlay is NOT silently excluded by `k8s/local`.
#
# k8s/local exists as of plan 26-04, and the exclusion is load-bearing rather
# than defensive: that overlay DELIBERATELY carries localhost-family literals.
# Two of its values must be BROWSER-reachable rather than pod-reachable —
# `s3.public-url` (http://localhost:9000/jtoye-images: the browser is what loads
# image URLs) and `keycloak.public.issuer.uri` (http://localhost:8085/...: the
# issuer Keycloak actually stamps into `iss`) — so INV-4 would be asserting
# against the CORRECT content there. Every other target ships to a real cluster,
# where such a literal is the DEF-6 defect.
#
# The local overlay is NOT unguarded as a result: LOC-1..LOC-6 below assert its
# endpoints POSITIVELY, per key by name, which is a stronger statement than
# INV-4's "no forbidden literal" ever was.
# ---------------------------------------------------------------------------
LOCAL_ONLY_TARGETS=(
  "k8s/local"
)

# ---------------------------------------------------------------------------
# INV-6 allowlist: an Ingress backend Service name that is knowingly not in the
# render. Format: '<service-name>|<reason>'.
#
# It is EMPTY, and that is the correct state. The one entry it could have had —
# `keycloak` — was the DEFECT, not an exemption: Keycloak is an external managed
# IdP, so the right fix was removing the rule that claimed its hostname, not
# excusing a backend that resolves nowhere. An entry here means "this render
# intentionally routes to a Service created outside kustomize", which should be
# rare enough to always need an explanation.
#
# Hygiene (same rules as check-env-contract.sh's allowlists): a blank reason
# FAILS, a duplicate FAILS, and a STALE entry — one whose Service now resolves in
# every target — FAILS, so the allowlist cannot quietly become a standing excuse
# for something that is already fixed.
# ---------------------------------------------------------------------------
ALLOW_UNRESOLVED_INGRESS_BACKEND=()

# The live-verified Postgres SUPERUSER role name. `jtoye` is a superuser and
# `jtoye_app` is NOSUPERUSER (both confirmed against the running dev Postgres in
# 26-RESEARCH.md § Live Facts). Only the superuser is a defect in a recipe.
DB_SUPERUSER_ROLE="jtoye"

# Literals that must never appear in a non-local render.
FORBIDDEN_RENDER_LITERALS=(
  'localhost'
  '127\.0\.0\.1'
  'minioadmin'
)

command -v kubectl > /dev/null \
    || parse_fail "kubectl not on PATH (client-side 'kubectl kustomize' is required)."
[[ -f "$CORE_DEPLOYMENT" ]]  || parse_fail "not found: $CORE_DEPLOYMENT"
[[ -f "$QUICK_START" ]]      || parse_fail "not found: $QUICK_START"
[[ -f "$SECRETS_TEMPLATE" ]] || parse_fail "not found: $SECRETS_TEMPLATE"

# Same auto-discovery loop as check-no-plaintext-secrets.sh, so a new overlay is
# covered the moment it exists. `sort` keeps the output order deterministic.
mapfile -t TARGETS < <(find "$K8S_DIR" -maxdepth 2 -name 'kustomization.yaml' -printf '%h\n' | sort)
(( ${#TARGETS[@]} > 0 )) || parse_fail "no kustomization.yaml found under $K8S_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

FAILED=0

# ===========================================================================
# INV-1 — source level, once (not per target)
# ===========================================================================
echo "INV-1 (DEF-1 / INFRA-02a, source): no hardcoded Postgres port in k8s/base/core-java-deployment.yaml"
if grep -nE '^[[:space:]]+value: "5432"' "$CORE_DEPLOYMENT"; then
    echo "  FAIL: a hardcoded Postgres port literal is back in k8s/base/core-java-deployment.yaml." >&2
    echo "        The port is CONFIG, not a constant: the host dev Postgres publishes 5433, so a" >&2
    echo "        hardcoded 5432 makes a local cluster impossible and makes every environment's" >&2
    echo "        port a manifest edit. Route DB_PORT through postgres-credentials/port, exactly" >&2
    echo "        as k8s/base/pg-backup-cronjob.yaml already does (grep 'key: port')." >&2
    FAILED=1
else
    echo "  OK   [k8s/base/core-java-deployment.yaml]: no 'value: \"5432\"' line"
fi
echo

# ===========================================================================
# Per-target render assertions: INV-2, INV-3, INV-4
# ===========================================================================

# --- awk: emit one record per rendered EnvVar (name, line, has value, has
#     valueFrom). An EnvVar is a `- name: X` sequence item whose IMMEDIATE
#     children include `value` or `valueFrom` — restricting to immediate
#     children is what stops a container's `- name: <container>` block from
#     swallowing its own nested env items.
ENVVAR_AWK='
function endenv() {
    if (in_item && (has_value || has_valuefrom))
        printf "%s\t%d\t%d\t%d\n", name, start_line, has_value, has_valuefrom
    in_item = 0; has_value = 0; has_valuefrom = 0
}
{
    if ($0 ~ /^[[:space:]]*$/) { endenv(); next }
    ind = match($0, /[^ ]/) - 1
    if (in_item && ind <= item_ind) endenv()
    if ($0 ~ /^[[:space:]]*- name: /) {
        endenv()
        in_item = 1; item_ind = ind; start_line = NR
        name = $0
        sub(/^[[:space:]]*- name:[[:space:]]*/, "", name)
        next
    }
    if (in_item && ind == item_ind + 2) {
        key = $0
        sub(/^[[:space:]]*/, "", key)
        sub(/:.*$/, "", key)
        if (key == "value")     has_value = 1
        if (key == "valueFrom") has_valuefrom = 1
    }
}
END { endenv() }
'

# --- awk: walk every `matchLabels:` block by indentation and emit
#     "<start-line>\t<key-count>\t<is-kube-dns>\t<comma-joined keys>".
#     Block-scoped BY CONSTRUCTION: a forward grep -A scan cannot work because
#     kustomize sorts map keys alphabetically, so injected labels sort BEFORE
#     `k8s-app` and land ABOVE the anchor line.
MATCHLABELS_AWK='
function endblock() {
    if (in_block)
        printf "%d\t%d\t%d\t%s\n", start_line, nkeys, is_dns, keys
    in_block = 0; nkeys = 0; is_dns = 0; keys = ""
}
{
    if ($0 ~ /^[[:space:]]*$/) { endblock(); next }
    ind = match($0, /[^ ]/) - 1
    if (in_block && ind <= block_ind) endblock()
    if ($0 ~ /^[[:space:]]*matchLabels:[[:space:]]*$/) {
        endblock()
        in_block = 1; block_ind = ind; start_line = NR; nkeys = 0; is_dns = 0; keys = ""
        next
    }
    if (in_block) {
        key = $0; sub(/^[[:space:]]*/, "", key); sub(/:.*$/, "", key)
        val = $0; sub(/^[^:]*:[[:space:]]*/, "", val)
        nkeys++
        keys = keys (keys == "" ? "" : ",") key
        if (key == "k8s-app" && val == "kube-dns") is_dns = 1
    }
}
END { endblock() }
'

# --- awk: per-DOCUMENT walk emitting the Service inventory and every Ingress
#     backend reference of a render.
#
#     DOCUMENT-SCOPED BY CONSTRUCTION, and that is load-bearing: `kubectl
#     kustomize` emits each document's top-level keys ALPHABETICALLY, so a
#     ConfigMap's `data:` block precedes its own `kind:` line. A "track the last
#     kind seen" scan therefore attributes those lines to the PREVIOUS document
#     (a real mis-attribution hit in plan 26-02). This buffers each
#     `---`-delimited document and resolves kind + metadata.name from the buffer.
#
#     Output records:
#       SVC <TAB> <service name>
#       ING <TAB> <ingress name> <TAB> <host|(default)> <TAB> <backend service name>
INGRESS_BACKEND_AWK='
function meta_name(  i, v) {
    # The FIRST 2-space `name:` in a document is metadata.name: in a rendered
    # document only the metadata block has a key at that indent, while backend
    # service names sit far deeper.
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function flush(  i, kind, nm, host, insvc, b, l) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }

    if (kind == "Service") {
        printf "SVC\t%s\n", meta_name()
    } else if (kind == "Ingress") {
        nm = meta_name(); host = "(default)"; insvc = 0
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^[[:space:]]*- host: /) {
                host = l; sub(/^[[:space:]]*- host:[[:space:]]*/, "", host)
            }
            if (l ~ /^[[:space:]]*service:[[:space:]]*$/) { insvc = 1; continue }
            if (insvc && l ~ /^[[:space:]]*name:[[:space:]]*/) {
                b = l; sub(/^[[:space:]]*name:[[:space:]]*/, "", b)
                printf "ING\t%s\t%s\t%s\n", nm, host, b
                insvc = 0
            }
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: per-DOCUMENT walk emitting the scale-relevant top-level spec scalars.
#     Output: <kind> <TAB> <name> <TAB> <field> <TAB> <value>
SCALE_AWK='
function meta_name(  i, v) {
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^  name: /) { v = buf[i]; sub(/^  name:[[:space:]]*/, "", v); return v }
    return "(unnamed)"
}
function flush(  i, kind, nm, l, f, v) {
    if (n == 0) return
    kind = ""
    for (i = 1; i <= n; i++)
        if (buf[i] ~ /^kind: /) { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
    nm = meta_name()
    for (i = 1; i <= n; i++) {
        l = buf[i]
        if (l ~ /^  (replicas|minReplicas|maxReplicas|minAvailable): /) {
            f = l; sub(/^  /, "", f); sub(/:.*$/, "", f)
            v = l; sub(/^  [a-zA-Z]+:[[:space:]]*/, "", v)
            printf "%s\t%s\t%s\t%s\n", kind, nm, f, v
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

# --- awk: emit `<key>\t<value>` for every entry in the rendered app-config
#     ConfigMap `data:` map. Document-scoped for the same alphabetical-key reason.
CONFIGMAP_DATA_AWK='
function flush(  i, kind, nm, indata, l, k, v) {
    if (n == 0) return
    kind = ""; nm = ""
    for (i = 1; i <= n; i++) {
        if (buf[i] ~ /^kind: /)   { kind = buf[i]; sub(/^kind:[[:space:]]*/, "", kind) }
        if (buf[i] ~ /^  name: /) { if (nm == "") { nm = buf[i]; sub(/^  name:[[:space:]]*/, "", nm) } }
    }
    if (kind == "ConfigMap" && nm == "app-config") {
        indata = 0
        for (i = 1; i <= n; i++) {
            l = buf[i]
            if (l ~ /^data:[[:space:]]*$/) { indata = 1; continue }
            if (indata && l ~ /^[^ ]/)     { indata = 0; continue }
            if (indata && l ~ /^  [^ ].*:/) {
                k = l; sub(/^  /, "", k); sub(/:.*$/, "", k)
                v = l; sub(/^  [^:]*:[[:space:]]*/, "", v)
                printf "%s\t%s\n", k, v
            }
        }
    }
    n = 0; delete buf
}
/^---[[:space:]]*$/ { flush(); next }
{ buf[++n] = $0 }
END { flush() }
'

is_local_only_target() {
    local rel="$1" excluded
    for excluded in "${LOCAL_ONLY_TARGETS[@]}"; do
        [[ "$rel" == "$excluded" ]] && return 0
    done
    return 1
}

# ---------------------------------------------------------------------------
# INV-6 allowlist parse + hygiene (malformed / blank reason / duplicate). The
# STALE rule is evaluated AFTER the per-target loop, once it is known which
# entries were actually needed.
# ---------------------------------------------------------------------------
declare -A ALLOW_INGRESS_REASON=()
declare -A ALLOW_INGRESS_USED=()
# The size guard (rather than the `${arr[@]+"${arr[@]}"}` idiom) keeps this safe
# under `set -u` on an EMPTY array while still quoting each element properly —
# reasons contain spaces, so an unquoted expansion would word-split them into
# bogus entries.
if (( ${#ALLOW_UNRESOLVED_INGRESS_BACKEND[@]} > 0 )); then
    for entry in "${ALLOW_UNRESOLVED_INGRESS_BACKEND[@]}"; do
        svc="${entry%%|*}"
        reason="${entry#*|}"
        if [[ -z "$svc" || "$svc" == "$entry" ]]; then
            fail "INV-6 allowlist: entry '$entry' is malformed — the required shape is '<service-name>|<reason>'."
        fi
        if [[ -z "${reason//[[:space:]]/}" ]]; then
            fail "INV-6 allowlist: entry '$svc' has a blank reason. An unexplained exemption is indistinguishable from a forgotten defect — a backend that resolves nowhere answers 503 for a published host, which is exactly the shape INV-6 exists to catch."
        fi
        if [[ -n "${ALLOW_INGRESS_REASON[$svc]:-}" ]]; then
            fail "INV-6 allowlist: duplicate entry '$svc'."
        fi
        ALLOW_INGRESS_REASON["$svc"]="$reason"
    done
fi

for dir in "${TARGETS[@]}"; do
    rel="${dir#"$REPO_ROOT"/}"
    render="$TMP/${rel//\//_}.yaml"

    if ! kubectl kustomize "$dir" > "$render" 2> "$TMP/stderr"; then
        echo "ERROR [$rel]: 'kubectl kustomize' build failed:" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi

    # ---------------- INV-2 ----------------
    awk "$ENVVAR_AWK" "$render" > "$TMP/envvars.tsv"
    envvar_count=$(wc -l < "$TMP/envvars.tsv")
    (( envvar_count > 0 )) || parse_fail "[$rel] INV-2 found 0 EnvVar items in the render — the EnvVar shape changed and this assertion is now blind. Fix the parser, do not delete the invariant."
    if ! grep -qP '^DB_PORT\t' "$TMP/envvars.tsv"; then
        parse_fail "[$rel] INV-2 found no DB_PORT EnvVar in the render — the assertion would pass vacuously. DB_PORT must be injected (DEF-1); if it was deliberately renamed, update this gate in the same change."
    fi

    inv2_bad=0
    while IFS=$'\t' read -r name line hv hvf; do
        if (( hv == 1 && hvf == 1 )); then
            echo "  FAIL [$rel] INV-2: EnvVar '$name' (render line $line) carries BOTH 'value:' and 'valueFrom:'." >&2
            inv2_bad=1
        fi
    done < "$TMP/envvars.tsv"
    if (( inv2_bad != 0 )); then
        echo "        kubectl kustomize accepts this, but the API server REJECTS the apply:" >&2
        echo "          env[i].valueFrom: Invalid value: \"\": may not be specified when 'value' is not empty" >&2
        echo "        A strategic-merge patch MERGES into the base env item and cannot remove a" >&2
        echo "        scalar, so 'value:' must be DELETED in k8s/base — there is no overlay" >&2
        echo "        shortcut (26-RESEARCH.md PIT-2)." >&2
        FAILED=1
        inv2_msg="FAIL"
    else
        inv2_msg="OK ($envvar_count EnvVars, DB_PORT present, 0 with both value+valueFrom)"
    fi

    # ---------------- INV-3 ----------------
    awk "$MATCHLABELS_AWK" "$render" > "$TMP/matchlabels.tsv"
    dns_blocks=$(awk -F'\t' '$3 == 1' "$TMP/matchlabels.tsv" | wc -l)
    (( dns_blocks > 0 )) || parse_fail "[$rel] INV-3 found 0 matchLabels blocks selecting 'k8s-app: kube-dns' — either the DNS egress rules vanished or the parser is blind. Either way this assertion would pass vacuously; investigate, do not delete the invariant."

    inv3_bad=0
    while IFS=$'\t' read -r line nkeys is_dns keys; do
        [[ "$is_dns" == "1" ]] || continue
        if [[ "$keys" != "k8s-app" ]]; then
            echo "  FAIL [$rel] INV-3: the kube-dns podSelector at render line $line has $nkeys key(s): $keys" >&2
            inv3_bad=1
        fi
    done < "$TMP/matchlabels.tsv"
    if (( inv3_bad != 0 )); then
        echo "        A kube-dns selector must contain ONLY 'k8s-app'. Real kube-dns pods carry no" >&2
        echo "        app.kubernetes.io/* or environment label, so ANY extra key narrows the" >&2
        echo "        selector to zero pods and core-java loses ALL DNS egress under an enforcing" >&2
        echo "        CNI — a total outage (D-17). The usual cause is the base kustomization's" >&2
        echo "        label transformer reverting to 'includeSelectors: true' instead of the" >&2
        echo "        explicit 'fields:' list. This is invisible to" >&2
        echo "        k8s/scripts/validate-networkpolicies.py, which parses raw files, not the" >&2
        echo "        render — which is why the assertion lives here." >&2
        FAILED=1
        inv3_msg="FAIL"
    else
        inv3_msg="OK ($dns_blocks kube-dns selector block(s), each exactly 1 key)"
    fi

    # ---------------- INV-4 ----------------
    if is_local_only_target "$rel"; then
        inv4_msg="SKIP (LOCAL_ONLY_TARGETS: this overlay deliberately targets host services)"
    else
        inv4_bad=0
        for lit in "${FORBIDDEN_RENDER_LITERALS[@]}"; do
            if grep -nE "$lit" "$render" > "$TMP/hits" 2> /dev/null; then
                echo "  FAIL [$rel] INV-4: forbidden local-only literal '$lit' in the render:" >&2
                sed 's/^/        /' "$TMP/hits" >&2
                inv4_bad=1
            fi
        done
        if (( inv4_bad != 0 )); then
            echo "        This is the DEF-6 recurrence shape: a value that is only correct on a" >&2
            echo "        developer laptop reaching a real cluster, where it fails SILENTLY (media" >&2
            echo "        writes nowhere, email to a loopback relay, a production link pointing at" >&2
            echo "        localhost). Supply the environment's real value via app-config or a" >&2
            echo "        Secret. If this genuinely IS a local overlay, add its exact path to" >&2
            echo "        LOCAL_ONLY_TARGETS with a reason." >&2
            FAILED=1
            inv4_msg="FAIL"
        else
            inv4_msg="OK (0 localhost / 127.0.0.1 / minioadmin literals)"
        fi
    fi

    # ---------------- INV-6 ----------------
    # Every Ingress backend Service name must resolve to a Service in this SAME
    # render. Runs for EVERY target (base, local, staging, production): the
    # defect it pins was a production defect, not a local-overlay one.
    awk "$INGRESS_BACKEND_AWK" "$render" > "$TMP/ingress.tsv"
    awk -F'\t' '$1 == "SVC" { print $2 }' "$TMP/ingress.tsv" | sort -u > "$TMP/services.txt"
    awk -F'\t' '$1 == "ING"' "$TMP/ingress.tsv" > "$TMP/backends.tsv"

    svc_count=$(wc -l < "$TMP/services.txt")
    backend_count=$(wc -l < "$TMP/backends.tsv")
    (( svc_count > 0 )) || parse_fail "[$rel] INV-6 found 0 'kind: Service' documents in the render. Either the render lost every Service (a far bigger problem) or the parser is blind — in which case every backend would be reported unresolved, or, if the backend parse is equally blind, nothing would be checked at all. Fix the parser, do not delete the invariant."
    (( backend_count > 0 )) || parse_fail "[$rel] INV-6 found 0 Ingress backend references in the render. This platform ships two Ingresses (jtoye-ingress + jtoye-sse-ingress) in every target, so zero backends means the Ingress shape changed and the assertion is now vacuous. Fix the parser, do not delete the invariant."

    inv6_bad=0
    inv6_allowed=0
    while IFS=$'\t' read -r _tag ing host backend; do
        if grep -qxF "$backend" "$TMP/services.txt"; then
            continue
        fi
        if [[ -n "${ALLOW_INGRESS_REASON[$backend]:-}" ]]; then
            ALLOW_INGRESS_USED["$backend"]=1
            (( ++inv6_allowed ))
            echo "  INFO [$rel] INV-6: backend Service '$backend' (host '$host', Ingress '$ing') is ALLOWLISTED: ${ALLOW_INGRESS_REASON[$backend]}"
            continue
        fi
        echo "  FAIL [$rel] INV-6: Ingress '$ing' publishes host '$host' and routes it to Service '$backend', which does NOT exist in the $rel render." >&2
        echo "        Services present in this render: $(tr '\n' ' ' < "$TMP/services.txt")" >&2
        inv6_bad=1
    done < "$TMP/backends.tsv"

    if (( inv6_bad != 0 )); then
        echo "        nginx answers 503 for a published host with no backend — a broken endpoint that" >&2
        echo "        looks configured. It is also a TLS hazard: hosts share one certificate secret," >&2
        echo "        and a cert-manager HTTP-01 challenge for a hostname this controller does not" >&2
        echo "        actually serve can fail the WHOLE order, stalling issuance for the hosts that" >&2
        echo "        DO work. This is exactly what the Keycloak host rule did in staging and" >&2
        echo "        production until plan 26-04 removed it." >&2
        echo "        NOTE: k8s/base deliberately ships NO Keycloak workload — Keycloak is an" >&2
        echo "        EXTERNAL managed identity provider (see app-config keycloak.issuer.uri), and" >&2
        echo "        public DNS for its hostname resolves to that IdP, not to this controller. So" >&2
        echo "        the fix is to REMOVE the rule, NOT to add a Service. If a backend really is" >&2
        echo "        created outside kustomize, add it to ALLOW_UNRESOLVED_INGRESS_BACKEND WITH a" >&2
        echo "        reason." >&2
        FAILED=1
        inv6_msg="FAIL"
    else
        inv6_msg="OK ($backend_count backend ref(s) -> $svc_count Service(s))"
        if (( inv6_allowed > 0 )); then
            inv6_msg="OK ($backend_count backend ref(s) -> $svc_count Service(s), $inv6_allowed allowlisted)"
        fi
    fi

    if [[ "$inv2_msg" == FAIL* || "$inv3_msg" == FAIL* || "$inv4_msg" == FAIL* || "$inv6_msg" == FAIL* ]]; then
        echo "FAIL [$rel]: INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg" >&2
    else
        echo "OK   [$rel]: INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg | INV-6 $inv6_msg"
    fi
done
echo

# ---------------------------------------------------------------------------
# INV-6 allowlist STALE rule: an entry nobody needed is a standing excuse for
# something already fixed, so it fails rather than rotting silently.
# ---------------------------------------------------------------------------
if (( ${#ALLOW_INGRESS_REASON[@]} > 0 )); then
    for svc in "${!ALLOW_INGRESS_REASON[@]}"; do
        if [[ -z "${ALLOW_INGRESS_USED[$svc]:-}" ]]; then
            echo "FAIL: INV-6 allowlist: STALE entry '$svc' — every target's render now resolves that backend (or no Ingress references it at all), so the exemption is unnecessary. Remove the entry rather than leaving a standing excuse for a defect that is already fixed." >&2
            FAILED=1
        fi
    done
fi

# ===========================================================================
# INV-5 — docs level, block-scoped per Secret name
# ===========================================================================
echo "INV-5 (DEF-2 / INFRA-02b, docs): the DB superuser is never the postgres-credentials app username"

# --- awk: attribute every `--from-literal=username=<v>` to the
#     `kubectl create secret generic <name>` command it belongs to. A leading
#     comment marker is stripped first, so a recipe living INSIDE a comment
#     block (the template has one) is covered exactly like a live recipe.
RECIPE_AWK='
{
    line = $0
    sub(/^[[:space:]]*#[[:space:]]?/, "", line)
    if (line ~ /kubectl create secret generic[[:space:]]+[A-Za-z0-9._-]+/) {
        secret = line
        sub(/.*kubectl create secret generic[[:space:]]+/, "", secret)
        sub(/[^A-Za-z0-9._-].*$/, "", secret)
    }
    if (secret != "" && line ~ /--from-literal=username=/) {
        v = line
        sub(/.*--from-literal=username=/, "", v)
        sub(/[[:space:]\\].*$/, "", v)
        gsub(/^['"'"'"]|['"'"'"]$/, "", v)
        printf "%s\t%s\t%d\n", secret, v, NR
    }
}
'

# --- awk: attribute every `username:` value to the YAML document whose
#     metadata.name it belongs to. Documents are split on a top-level `---`.
STRINGDATA_AWK='
/^---[[:space:]]*$/ { secret = ""; next }
{
    line = $0
    if (line ~ /^[[:space:]]*#/) next
    if (line ~ /^[[:space:]]+name:[[:space:]]/ && secret == "") {
        secret = line
        sub(/^[[:space:]]+name:[[:space:]]*/, "", secret)
        gsub(/^["'"'"']|["'"'"']$/, "", secret)
    }
    if (secret != "" && line ~ /^[[:space:]]+username:[[:space:]]/) {
        v = line
        sub(/^[[:space:]]+username:[[:space:]]*/, "", v)
        sub(/[[:space:]]*#.*$/, "", v)
        gsub(/^["'"'"']|["'"'"']$/, "", v)
        printf "%s\t%s\t%d\n", secret, v, NR
    }
}
'

inv5_bad=0
inv5_checked=0

check_pg_username() {
    # check_pg_username <file-label> <tsv-file> <what>
    local label="$1" tsv="$2" what="$3" secret value line
    while IFS=$'\t' read -r secret value line; do
        [[ "$secret" == "postgres-credentials" ]] || continue
        (( ++inv5_checked ))
        if [[ "$value" == "$DB_SUPERUSER_ROLE" ]]; then
            echo "  FAIL [$label:$line] $what names the DB SUPERUSER '$value' as the postgres-credentials username." >&2
            inv5_bad=1
        else
            echo "  OK   [$label:$line] $what -> postgres-credentials username='$value'"
        fi
    done < "$tsv"
}

awk "$RECIPE_AWK"     "$QUICK_START"      > "$TMP/qs-recipe.tsv"
awk "$RECIPE_AWK"     "$SECRETS_TEMPLATE" > "$TMP/tpl-recipe.tsv"
awk "$STRINGDATA_AWK" "$SECRETS_TEMPLATE" > "$TMP/tpl-data.tsv"

check_pg_username "k8s/QUICK_START.md"                  "$TMP/qs-recipe.tsv"  "create-secret recipe"
check_pg_username "k8s/base/secrets-template.yaml.example" "$TMP/tpl-recipe.tsv" "comment-block recipe"
check_pg_username "k8s/base/secrets-template.yaml.example" "$TMP/tpl-data.tsv"   "stringData"

(( inv5_checked >= 3 )) || parse_fail "INV-5 located only $inv5_checked postgres-credentials username site(s); 3 are expected (the QUICK_START recipe, the template's comment-block recipe, and the template stringData). A missing site means the assertion is partly blind — fix the parser or the docs, do not delete the invariant."

if (( inv5_bad != 0 )); then
    echo >&2
    echo "        A Postgres SUPERUSER BYPASSES EVERY RLS POLICY, and RLS is this platform's" >&2
    echo "        entire multi-tenant isolation boundary. DatabaseConfigurationValidator fails" >&2
    echo "        core-java's boot fast when it detects a superuser precisely for that reason, so" >&2
    echo "        a copy-pasteable superuser recipe is a latent RLS bypass AND a guaranteed" >&2
    echo "        CrashLoopBackOff. Use the NOSUPERUSER app role (.env DB_USER, 'jtoye_app')." >&2
    echo "        NOTE: 'jtoye' IS correct for rabbitmq-credentials (the broker user) and" >&2
    echo "        'jtoye_backup' IS correct for backup-username (the BYPASSRLS dump role) — this" >&2
    echo "        invariant is block-scoped to postgres-credentials on purpose." >&2
    FAILED=1
fi
echo

# ===========================================================================
# LOC-1..LOC-6 — the k8s/local overlay (Phase 26 / INFRA-01)
#
# CONDITIONAL BY DESIGN: if the overlay is ever removed this section is skipped
# and the script stays valid, rather than failing on a missing directory.
# ===========================================================================
LOCAL_SECTION="LOC-1..LOC-6 SKIPPED (k8s/local/kustomization.yaml not present)"

if [[ -f "$LOCAL_KUSTOMIZATION" ]]; then
    echo "LOC-1..LOC-6 (INFRA-01, k8s/local): the committed local overlay's shape"

    LOCAL_RENDER="$TMP/loc_local.yaml"
    if ! kubectl kustomize "$LOCAL_DIR" > "$LOCAL_RENDER" 2> "$TMP/stderr"; then
        echo "ERROR [k8s/local]: 'kubectl kustomize' build failed:" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi
    BASE_RENDER="$TMP/loc_base.yaml"
    if ! kubectl kustomize "$K8S_DIR/base" > "$BASE_RENDER" 2> "$TMP/stderr"; then
        echo "ERROR [k8s/base]: 'kubectl kustomize' build failed (needed as the LOC-2 maxReplicas reference):" >&2
        cat "$TMP/stderr" >&2
        exit 2
    fi

    LOCAL_HOST_SHIM="host.minikube.internal"

    # Keys whose value MUST resolve through the minikube host gateway. Asserted
    # per key BY NAME, so a lost shim cannot hide behind an added one.
    SHIMMED_KEYS=(
      'redis.host'
      'rabbitmq.host'
      'stomp.broker.relay-host'
      's3.endpoint'
      's3.backup.endpoint'
      'smtp.host'
      'keycloak.issuer.uri'
      'keycloak.admin.base-url'
    )

    awk "$CONFIGMAP_DATA_AWK" "$LOCAL_RENDER" > "$TMP/loc_cfg.tsv"
    cfg_keys=$(wc -l < "$TMP/loc_cfg.tsv")
    (( cfg_keys > 0 )) || parse_fail "LOC-1 found no app-config data keys in the k8s/local render — the ConfigMap shape changed and every LOC-1/LOC-3 assertion would pass vacuously. Fix the parser, do not delete the invariant."

    cfg_value() {
        awk -F'\t' -v k="$1" '$1 == k { print $2; found=1 } END { if (!found) print "(ABSENT)" }' "$TMP/loc_cfg.tsv"
    }

    # ---------------- LOC-1 ----------------
    loc1_bad=0
    for key in "${SHIMMED_KEYS[@]}"; do
        val="$(cfg_value "$key")"
        if [[ "$val" != *"$LOCAL_HOST_SHIM"* ]]; then
            echo "  FAIL [k8s/local] LOC-1: app-config key '$key' is '$val' — it must resolve through '$LOCAL_HOST_SHIM'." >&2
            loc1_bad=1
        fi
    done
    shim_total=$(grep -c "$LOCAL_HOST_SHIM" "$LOCAL_RENDER" || true)
    if (( shim_total < ${#SHIMMED_KEYS[@]} )); then
        echo "  FAIL [k8s/local] LOC-1: only $shim_total '$LOCAL_HOST_SHIM' occurrence(s) in the render; at least ${#SHIMMED_KEYS[@]} are required (one per shimmed key)." >&2
        loc1_bad=1
    fi
    if (( loc1_bad != 0 )); then
        echo "        A pod cannot reach the host's docker-compose backing services on localhost —" >&2
        echo "        that is the POD's own loopback. minikube maintains the host-gateway mapping as" >&2
        echo "        '$LOCAL_HOST_SHIM' (its underlying IP varies by driver, so an IP literal is" >&2
        echo "        the DEF-1 defect class). An unshimmed endpoint fails at RUNTIME, per feature," >&2
        echo "        not at build time: a wrong s3.endpoint breaks image upload only, a wrong" >&2
        echo "        smtp.host breaks email only. DELIBERATE EXCEPTIONS, both browser-reachable and" >&2
        echo "        both correctly NOT in the list above: s3.public-url (the browser loads image" >&2
        echo "        URLs) and keycloak.public.issuer.uri (the issuer Keycloak STAMPS into 'iss')." >&2
        FAILED=1
        loc1_msg="FAIL"
    else
        loc1_msg="OK (${#SHIMMED_KEYS[@]} keys shimmed by name, $shim_total render occurrence(s))"
    fi

    # ---------------- LOC-2 ----------------
    awk "$SCALE_AWK" "$LOCAL_RENDER" > "$TMP/loc_scale.tsv"
    awk "$SCALE_AWK" "$BASE_RENDER"  > "$TMP/base_scale.tsv"

    dep_replicas=$(awk -F'\t' '$1=="Deployment" && $3=="replicas"'                 "$TMP/loc_scale.tsv" | wc -l)
    dep_ones=$(awk -F'\t'     '$1=="Deployment" && $3=="replicas" && $4=="1"'      "$TMP/loc_scale.tsv" | wc -l)
    hpa_mins=$(awk -F'\t'     '$1=="HorizontalPodAutoscaler" && $3=="minReplicas"' "$TMP/loc_scale.tsv" | wc -l)
    hpa_ones=$(awk -F'\t'     '$1=="HorizontalPodAutoscaler" && $3=="minReplicas" && $4=="1"' "$TMP/loc_scale.tsv" | wc -l)
    pdb_mins=$(awk -F'\t'     '$1=="PodDisruptionBudget" && $3=="minAvailable"'    "$TMP/loc_scale.tsv" | wc -l)
    pdb_ones=$(awk -F'\t'     '$1=="PodDisruptionBudget" && $3=="minAvailable" && $4=="1"' "$TMP/loc_scale.tsv" | wc -l)

    (( dep_replicas > 0 && hpa_mins > 0 && pdb_mins > 0 )) || parse_fail "LOC-2 found Deployment replicas=$dep_replicas, HPA minReplicas=$hpa_mins, PDB minAvailable=$pdb_mins in the k8s/local render — a zero means the parser is blind and the count assertions would pass vacuously. Fix the parser, do not delete the invariant."

    loc2_bad=0
    for spec in "Deployment replicas 3 $dep_replicas $dep_ones" \
                "HorizontalPodAutoscaler minReplicas 3 $hpa_mins $hpa_ones" \
                "PodDisruptionBudget minAvailable 3 $pdb_mins $pdb_ones"; do
        read -r kind field want total ones <<< "$spec"
        if (( total != want || ones != want )); then
            echo "  FAIL [k8s/local] LOC-2: expected $want $kind object(s) with '$field: 1'; found $total object(s), $ones of them at 1." >&2
            awk -F'\t' -v k="$kind" -v f="$field" '$1==k && $3==f { print "        " $1 "/" $2 ": " $3 ": " $4 }' "$TMP/loc_scale.tsv" >&2
            loc2_bad=1
        fi
    done

    loc_max=$(awk -F'\t' '$1=="HorizontalPodAutoscaler" && $3=="maxReplicas" { print $4 }' "$TMP/loc_scale.tsv" | sort -n | tr '\n' ' ')
    base_max=$(awk -F'\t' '$1=="HorizontalPodAutoscaler" && $3=="maxReplicas" { print $4 }' "$TMP/base_scale.tsv" | sort -n | tr '\n' ' ')
    [[ -n "${base_max// /}" ]] || parse_fail "LOC-2 found no HPA maxReplicas values in the k8s/base render, so the local-vs-base comparison has no reference and would pass vacuously. Fix the parser, do not delete the invariant."
    if [[ "$loc_max" != "$base_max" ]]; then
        echo "  FAIL [k8s/local] LOC-2: the local HPA maxReplicas multiset [$loc_max] DIVERGES from the k8s/base multiset [$base_max]." >&2
        echo "        maxReplicas is an INPUT to k8s/scripts/check-connection-math.sh: it asserts" >&2
        echo "        maxReplicas x DB_POOL_SIZE (plus Keycloak, the exporter, healthchecks and" >&2
        echo "        pg-backup) fits Postgres max_connections with >= 20% headroom. Changing it in" >&2
        echo "        the local overlay makes the local render stop proving the same arithmetic the" >&2
        echo "        gate checks, and it buys nothing: an HPA with no metrics-server never scales" >&2
        echo "        up regardless of its ceiling. Scale local with 'replicas:' + minReplicas/" >&2
        echo "        minAvailable (D-09), never by lowering the ceiling." >&2
        loc2_bad=1
    fi
    if (( loc2_bad != 0 )); then
        FAILED=1
        loc2_msg="FAIL"
    else
        loc2_msg="OK (replicas/minReplicas/minAvailable = 1 x3 each; maxReplicas [$loc_max] == base)"
    fi

    # ---------------- LOC-3 ----------------
    LOCAL_BACKUP_ENDPOINT="http://$LOCAL_HOST_SHIM:9000"
    backup_val="$(cfg_value 's3.backup.endpoint')"
    if [[ "$backup_val" != "$LOCAL_BACKUP_ENDPOINT" ]]; then
        echo "  FAIL [k8s/local] LOC-3: app-config 's3.backup.endpoint' is '$backup_val', expected exactly '$LOCAL_BACKUP_ENDPOINT'." >&2
        echo "        The base value is the EMPTY string, which the backup script reads as \"real AWS" >&2
        echo "        S3\". Locally that aims a database dump at real AWS with no credentials, and it" >&2
        echo "        makes the restore rehearsal (issue #101) impossible to run at all." >&2
        FAILED=1
        loc3_msg="FAIL"
    else
        loc3_msg="OK ($backup_val)"
    fi

    # ---------------- LOC-4 ----------------
    awk 'BEGIN{RS="\n---"} /kind: Ingress/{print}' "$LOCAL_RENDER" > "$TMP/loc_ingress.yaml"
    loc_ing_docs=$(grep -c '^kind: Ingress$' "$LOCAL_RENDER" || true)
    (( loc_ing_docs > 0 )) || parse_fail "LOC-4/LOC-5 found 0 Ingress documents in the k8s/local render. The local overlay must render both jtoye-ingress and jtoye-sse-ingress; zero means the parser is blind and every 'must not contain' assertion below would pass vacuously. Fix the parser, do not delete the invariant."

    loc4_bad=0
    for pat in 'configuration-snippet' 'cert-manager.io/cluster-issuer' \
               'nginx.ingress.kubernetes.io/limit-rps' \
               'nginx.ingress.kubernetes.io/limit-connections' \
               'nginx.ingress.kubernetes.io/limit-burst-multiplier'; do
        if grep -n "$pat" "$TMP/loc_ingress.yaml" > "$TMP/hits" 2> /dev/null; then
            echo "  FAIL [k8s/local] LOC-4: '$pat' is present in a local Ingress:" >&2
            sed 's/^/        /' "$TMP/hits" >&2
            loc4_bad=1
        fi
    done
    if grep -n '^  tls:' "$TMP/loc_ingress.yaml" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-4: a local Ingress still carries a 'tls:' block:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        loc4_bad=1
    fi
    if (( loc4_bad != 0 )); then
        echo "        PIT-1: minikube v1.36.0 bundles ingress-nginx controller v1.12.2, where" >&2
        echo "        allow-snippet-annotations defaults to FALSE and annotations-risk-level to" >&2
        echo "        High. Its validating admission webhook REJECTS a snippet annotation" >&2
        echo "        outright, so 'kubectl apply -k k8s/local' fails for BOTH Ingress objects and" >&2
        echo "        nothing deploys cleanly around it. The three rate-limit annotations are" >&2
        echo "        PIT-10: a Playwright run from one source IP can trip the per-IP connection" >&2
        echo "        cap and produce 503s that look like application faults. tls: must be absent" >&2
        echo "        because no cert-manager runs locally, so 'secretName: jtoye-tls' would never" >&2
        echo "        exist and nginx would serve its self-signed fallback." >&2
        echo "        FIX IT IN THE LOCAL OVERLAY, NOT ON THE CLUSTER. The base annotation is" >&2
        echo "        DELIBERATELY PRESERVED for staging/production (it sets six security headers)." >&2
        echo "        Setting allow-snippet-annotations: \"true\" / annotations-risk-level:" >&2
        echo "        \"Critical\" on the addon would make the apply succeed by re-enabling a" >&2
        echo "        documented Critical-risk annotation class that ingress-nginx disables by" >&2
        echo "        default — weakening the cluster to satisfy a local convenience." >&2
        FAILED=1
        loc4_msg="FAIL"
    else
        loc4_msg="OK ($loc_ing_docs Ingress doc(s): no snippet, no cert-manager, no rate limits, no tls)"
    fi

    # ---------------- LOC-5 ----------------
    LOCAL_EXPECTED_HOSTS="api.jtoye.local app.jtoye.local"
    loc_hosts=$(grep -E '^[[:space:]]*- host: ' "$TMP/loc_ingress.yaml" | sed 's/^[[:space:]]*- host:[[:space:]]*//' | sort -u | tr '\n' ' ')
    loc_hosts="${loc_hosts% }"
    loc5_bad=0
    if [[ "$loc_hosts" != "$LOCAL_EXPECTED_HOSTS" ]]; then
        echo "  FAIL [k8s/local] LOC-5: local Ingress hosts are [$loc_hosts], expected exactly [$LOCAL_EXPECTED_HOSTS] (D-12)." >&2
        loc5_bad=1
    fi
    # A production hostname surviving into the local render. Occurrences of the
    # domain as an ANNOTATION KEY NAMESPACE (`jtoye.co.uk/<name>:`) are excluded:
    # those are k8s annotation keys on a NetworkPolicy, not endpoints, and driving
    # them to zero would mean renaming an annotation for no benefit. Anything else
    # — a host, a TLS SAN, a CORS origin, a config value — is a real leak.
    if grep -E 'jtoye\.co\.uk' "$LOCAL_RENDER" | grep -vE '^[[:space:]]+jtoye\.co\.uk/' > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-5: a production hostname survives into the local render:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        loc5_bad=1
    fi
    if grep -qE '^[[:space:]]+name: keycloak$' "$TMP/loc_ingress.yaml"; then
        echo "  FAIL [k8s/local] LOC-5: a local Ingress routes to a Service named 'keycloak', which exists in no render." >&2
        loc5_bad=1
    fi
    if (( loc5_bad != 0 )); then
        echo "        Local is reached through the minikube ingress addon plus /etc/hosts entries" >&2
        echo "        for those two names. A production hostname in the local render either routes" >&2
        echo "        local traffic at production or (more usually) at nothing, and it means the" >&2
        echo "        local run is not exercising the ingress path it claims to. There is no" >&2
        echo "        keycloak host locally on purpose: Keycloak is a compose service the browser" >&2
        echo "        reaches directly, not an in-cluster workload." >&2
        FAILED=1
        loc5_msg="FAIL"
    else
        loc5_msg="OK (hosts: $loc_hosts)"
    fi

    # ---------------- LOC-6 ----------------
    # D-01 at the SOURCE level. check-no-plaintext-secrets.sh already fails on any
    # `kind: Secret` or placeholder in the BUILD OUTPUT; this asserts the input, so
    # the constraint is visible in the directory where the mistake would be made.
    loc6_bad=0
    if grep -rn 'secretGenerator' "$LOCAL_DIR" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-6: kustomize secret generation is used under k8s/local:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        echo "        D-01 forbids it: it emits a 'kind: Secret' into the build output, and" >&2
        echo "        check-no-plaintext-secrets.sh auto-discovers k8s/local at 'find -maxdepth 2'" >&2
        echo "        and fails on exactly that. Local Secrets come OUT-OF-BAND from" >&2
        echo "        scripts/k8s-local-secrets.sh, which sources the gitignored .env." >&2
        loc6_bad=1
    fi
    if grep -rn 'REPLACE_WITH' "$LOCAL_DIR" > "$TMP/hits" 2> /dev/null; then
        echo "  FAIL [k8s/local] LOC-6: an unsubstituted placeholder literal is present under k8s/local:" >&2
        sed 's/^/        /' "$TMP/hits" >&2
        echo "        Local has no CI substitution step, so a placeholder here reaches the render" >&2
        echo "        verbatim and fails check-no-plaintext-secrets.sh (which exempts only the one" >&2
        echo "        deploy-timestamp annotation staging/production stamp)." >&2
        loc6_bad=1
    fi
    if (( loc6_bad != 0 )); then
        FAILED=1
        loc6_msg="FAIL"
    else
        loc6_msg="OK (no kustomize secret generation, no placeholder literal)"
    fi

    if [[ "$loc1_msg" == FAIL* || "$loc2_msg" == FAIL* || "$loc3_msg" == FAIL* \
          || "$loc4_msg" == FAIL* || "$loc5_msg" == FAIL* || "$loc6_msg" == FAIL* ]]; then
        echo "FAIL [k8s/local]: LOC-1 $loc1_msg | LOC-2 $loc2_msg | LOC-3 $loc3_msg | LOC-4 $loc4_msg | LOC-5 $loc5_msg | LOC-6 $loc6_msg" >&2
    else
        echo "  OK   [k8s/local] LOC-1 $loc1_msg"
        echo "  OK   [k8s/local] LOC-2 $loc2_msg"
        echo "  OK   [k8s/local] LOC-3 $loc3_msg"
        echo "  OK   [k8s/local] LOC-4 $loc4_msg"
        echo "  OK   [k8s/local] LOC-5 $loc5_msg"
        echo "  OK   [k8s/local] LOC-6 $loc6_msg"
    fi
    LOCAL_SECTION="LOC-1..LOC-6 checked on k8s/local"
    echo
fi

# ===========================================================================
if (( FAILED != 0 )); then
    fail "one or more rendered-manifest invariants are broken — see above. Each invariant pins a defect that already shipped once; fix the manifest or the docs rather than relaxing the assertion."
fi

echo "PASS: INV-1..INV-6 hold across ${#TARGETS[@]} kustomize target(s); $LOCAL_SECTION."
