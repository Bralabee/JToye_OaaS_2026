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
# NON-VACUITY
#   Every render-level invariant also asserts that it FOUND something to check
#   (a DB_PORT EnvVar, a kube-dns selector block, a postgres-credentials recipe).
#   A gate that passes because it looked at nothing is worse than no gate, so a
#   missing subject exits 2 (the parser is blind — fix the parser) rather than 0.
#
# EXTENSION POINT
#   Plan 26-04 EXTENDS this script with the local-overlay assertions
#   (endpoint-shim count, the D-09 scale triple, the backup endpoint) rather than
#   adding another gate. Add them as INV-6.. here.
#
# Requires: kubectl (client-side `kubectl kustomize` only — no cluster access),
#           bash, awk, grep, find, sed.
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

fail() { echo "FAIL: $*" >&2; exit 1; }
parse_fail() { echo "PARSE ERROR: $*" >&2; exit 2; }

# ---------------------------------------------------------------------------
# INV-4 exclusion list.
#
# Matched on the EXACT repo-relative target path, never as a substring, so a
# future `k8s/local-staging` overlay is NOT silently excluded by `k8s/local`.
#
# k8s/local does not exist yet — plan 26-04 creates it. It is pre-seeded because
# the local overlay DELIBERATELY targets host services through
# host.minikube.internal and a host MinIO, so localhost-family literals are the
# correct content of that render and asserting against them there would be wrong.
# Every other target ships to a real cluster where such a literal is a defect.
# ---------------------------------------------------------------------------
LOCAL_ONLY_TARGETS=(
  "k8s/local"
)

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

is_local_only_target() {
    local rel="$1" excluded
    for excluded in "${LOCAL_ONLY_TARGETS[@]}"; do
        [[ "$rel" == "$excluded" ]] && return 0
    done
    return 1
}

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

    if [[ "$inv2_msg" == FAIL* || "$inv3_msg" == FAIL* || "$inv4_msg" == FAIL* ]]; then
        echo "FAIL [$rel]: INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg" >&2
    else
        echo "OK   [$rel]: INV-2 $inv2_msg | INV-3 $inv3_msg | INV-4 $inv4_msg"
    fi
done
echo

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
if (( FAILED != 0 )); then
    fail "one or more rendered-manifest invariants are broken — see above. Each invariant pins a defect that already shipped once; fix the manifest or the docs rather than relaxing the assertion."
fi

echo "PASS: INV-1..INV-5 hold across ${#TARGETS[@]} kustomize target(s)."
