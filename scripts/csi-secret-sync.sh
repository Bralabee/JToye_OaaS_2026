#!/usr/bin/env bash
# csi-secret-sync.sh — a Key Vault object added to a SecretProviderClass reaches the pod's
# environment on the deploy that adds it, and the deploy fails when it does not (issue #1054).
#
# ---------------------------------------------------------------------------------------
# WHAT WENT WRONG (observed 2026-10-31, #1046's deploy, every gate green)
#
#   1. ORDER. `kubectl apply -k` renders every Deployment before every SecretProviderClass,
#      so the rollout's new pod mounted the OLD SecretProviderClass (21 objects) before the
#      new one (22) was applied. The new object never reached the mount.
#   2. NO RESYNC. The AKS addon runs with enableSecretRotation=false. The driver creates a
#      synced Secret on the first mount and never updates it, so even a LATER pod that
#      mounted all 22 objects left `stripe-credentials` with its two old keys.
#   3. SILENCE. The env reference is `optional: true`, so the pod was Ready with the
#      variable unset. Digest parity, rollout status and the edge probe all passed.
#
# WHAT THIS DOES
#
#   --prepare   Before the overlay apply. Applies ONLY the overlay's SecretProviderClasses,
#               so every pod the apply creates mounts the new ones. Then, for each synced
#               Secret a SecretProviderClass names that lacks a key it now declares, deletes
#               it (only if the driver made it: its managed label or a workload owner; a
#               hand-made Secret is refused, not deleted): the next pod mount recreates it. The Deployments and StatefulSets
#               that mount that SecretProviderClass are written to --state for --restart.
#   --restart   After the overlay apply. Restarts the workloads --state names (a template the
#               apply did not change would otherwise keep its old pods) and waits for each.
#   --check     After rollout: the gate. Exit 1 when a synced Secret lacks a declared key, a
#               running consumer has no synced Secret, or a running consumer pod's mount holds
#               a different object count from its SecretProviderClass. Exit 2 (VOID) when
#               nothing could be read: "found nothing" is never "clean".
#   --selftest  The comparison on fixtures, both directions, no cluster.
#
#   Values are never read: only Secret KEY names, object counts and workload names.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

MODE=""
KUBE_CONTEXT=""
NAMESPACE="jtoye-staging"
OVERLAY="k8s/staging"
STATE=""
FIXTURE=""
ROLLOUT_TIMEOUT="10m"
MANAGED_LABEL="secrets-store.csi.k8s.io/managed=true"

needval() { [ "$#" -ge 2 ] || void "$1 needs a value, and none was given (it was the last argument)"; }

while [ "$#" -gt 0 ]; do
  case "$1" in
    --prepare|--restart|--check|--selftest)
      [ -z "$MODE" ] || void "one mode only (got $MODE and $1)"; MODE="${1#--}"; shift ;;
    --context)   needval "$@"; KUBE_CONTEXT="$2"; shift 2 ;;
    --namespace) needval "$@"; NAMESPACE="$2"; shift 2 ;;
    --overlay)   needval "$@"; OVERLAY="$2"; shift 2 ;;
    --state)     needval "$@"; STATE="$2"; shift 2 ;;
    --fixture)   needval "$@"; FIXTURE="$2"; shift 2 ;;
    --rollout-timeout) needval "$@"; ROLLOUT_TIMEOUT="$2"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --prepare|--restart|--check --context <ctx> [--namespace ns] [--overlay dir] [--state file]"
      echo "       $0 --check --fixture <dir>    (spc.json secrets.json pods.json podstatus.json)"
      echo "       $0 --selftest"
      exit 0 ;;
    *) void "unknown argument '$1' — refusing to guess what was meant while holding cluster credentials" ;;
  esac
done
[ -n "$MODE" ] || void "a mode is required: --prepare, --restart, --check or --selftest"
command -v jq >/dev/null 2>&1 || void "jq is not installed"

# ---- the comparison: four kubectl List documents in, one finding per line out ----------
# Each line: STALE-SECRET <secret> <spc> missing=<keys> | MISSING-SECRET <secret> <spc> pods=<n>
#          | STALE-MOUNT <pod> <spc> mounted=<n> declared=<m> | NO-POD-STATUS <pod> <spc>
# shellcheck disable=SC2016  # $vars below are jq's, not the shell's
FINDINGS_JQ='
  def objcount: ((.spec.parameters.objects // "") | [scan("objectName:\\s*\\S+")] | length);
  def consumers($spc): [ $pods[0].items[]
      | select(.metadata.deletionTimestamp == null and .status.phase == "Running")
      | select([.spec.volumes[]?.csi.volumeAttributes.secretProviderClass?] | index($spc)) ];
  ($secrets[0].items | map({(.metadata.name): ((.data // {}) | keys)}) | add // {}) as $have
  | ($status[0].items | map({((.status.podName // "") + "/" + (.status.secretProviderClassName // "")):
        ((.status.objects // []) | length)}) | add // {}) as $mounted
  | .items[] as $s | $s.metadata.name as $spc
  | ( ($s.spec.secretObjects // [])[] as $so
      | ($so.data // [] | map(.key)) as $want
      | if $have[$so.secretName] == null then
          (consumers($spc) | length) as $n
          | if $n > 0 then "MISSING-SECRET \($so.secretName) \($spc) pods=\($n)" else empty end
        else
          ($want - $have[$so.secretName]) as $miss
          | if ($miss | length) > 0 then "STALE-SECRET \($so.secretName) \($spc) missing=\($miss | join(","))" else empty end
        end ),
    ( consumers($spc)[] | .metadata.name as $p
      | $mounted[$p + "/" + $spc] as $m
      | if $m == null then "NO-POD-STATUS \($p) \($spc)"
        elif $m != ($s | objcount) then "STALE-MOUNT \($p) \($spc) mounted=\($m) declared=\($s | objcount)"
        else empty end )
'

# compare DIR -> prints findings; returns 0 none, 1 some, 2 unreadable or empty
compare() {
  local d="$1" f
  for f in spc secrets pods podstatus; do
    jq -e '.items | type == "array"' "$d/$f.json" >/dev/null 2>&1 || { echo "VOID: $f.json is missing or not a List" >&2; return 2; }
  done
  [ "$(jq '.items | length' "$d/spc.json")" -gt 0 ] || { echo "VOID: no SecretProviderClass read" >&2; return 2; }
  local out rc=0
  out="$(jq -r --slurpfile secrets "$d/secrets.json" --slurpfile pods "$d/pods.json" \
            --slurpfile status "$d/podstatus.json" "$FINDINGS_JQ" "$d/spc.json")" || { echo "VOID: the comparison did not run" >&2; return 2; }
  [ -z "$out" ] || { printf '%s\n' "$out"; rc=1; }
  return "$rc"
}

# ---- --selftest ----------------------------------------------------------------------
if [ "$MODE" = selftest ]; then
  T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
  spc() { # name secretName "k1 k2" objectCount
    local objs="" i keys
    for i in $(seq 1 "$4"); do objs="${objs}- |\n  objectName: obj-$i\n"; done
    keys="$(printf '%s\n' $3 | jq -R '{key: ., objectName: ("obj-" + .)}' | jq -s .)"
    jq -n --arg n "$1" --arg s "$2" --arg o "$(printf '%b' "$objs")" --argjson d "$keys" \
      '{metadata:{name:$n}, spec:{parameters:{objects:$o}, secretObjects:[{secretName:$s, data:$d}]}}'
  }
  pod() { jq -n --arg n "$1" --arg s "$2" --arg ph "${3:-Running}" \
      '{metadata:{name:$n}, status:{phase:$ph}, spec:{volumes:[{name:"kv", csi:{volumeAttributes:{secretProviderClass:$s}}}]}}'; }
  build() { # dir spcs secrets pods statuses  (each a JSON array)
    mkdir -p "$1"
    jq -n --argjson i "$2" '{items:$i}' > "$1/spc.json"
    jq -n --argjson i "$3" '{items:$i}' > "$1/secrets.json"
    jq -n --argjson i "$4" '{items:$i}' > "$1/pods.json"
    jq -n --argjson i "$5" '{items:$i}' > "$1/podstatus.json"
  }
  S3="[$(spc core-java-kv stripe-credentials "api-key webhook-secret connect-webhook-secret" 22)]"
  SEC3='[{"metadata":{"name":"stripe-credentials"},"data":{"api-key":"x","webhook-secret":"x","connect-webhook-secret":"x"}}]'
  SEC2='[{"metadata":{"name":"stripe-credentials"},"data":{"api-key":"x","webhook-secret":"x"}}]'
  P="[$(pod core-java-a core-java-kv)]"
  ST22='[{"status":{"podName":"core-java-a","secretProviderClassName":"core-java-kv","objects":'"$(jq -n '[range(22)|{id:"o"}]')"'}}]'
  ST21='[{"status":{"podName":"core-java-a","secretProviderClassName":"core-java-kv","objects":'"$(jq -n '[range(21)|{id:"o"}]')"'}}]'
  PEND="[$(pod core-java-b core-java-kv Pending)]"

  pass=0; bad=0
  arm() { # label expected_rc expected_pattern dir
    local out rc
    out="$(compare "$4" 2>&1)"; rc=$?
    if [ "$rc" -eq "$2" ] && { [ -z "$3" ] || grep -qE "$3" <<<"$out"; }; then
      log "ok   $1 -> rc $rc ${out:+($(head -1 <<<"$out"))}"; pass=$((pass+1))
    else
      log "BAD  $1 -> rc $rc, wanted $2 matching '$3'; output: $out"; bad=$((bad+1))
    fi
  }
  build "$T/green"        "$S3" "$SEC3" "$P" "$ST22";  arm "green: 3 keys, mount 22 of 22"                 0 ""                 "$T/green"
  build "$T/2026-10-31"   "$S3" "$SEC2" "$P" "$ST21";  arm "the observed shape: 2 keys, mount 21 of 22"    1 "STALE-SECRET stripe-credentials core-java-kv missing=connect-webhook-secret" "$T/2026-10-31"
  build "$T/stale-secret" "$S3" "$SEC2" "$P" "$ST22";  arm "remount alone (#1053's rollout): 2 keys, 22"   1 "^STALE-SECRET .*missing=connect-webhook-secret$" "$T/stale-secret"
  build "$T/stale-mount"  "$S3" "$SEC3" "$P" "$ST21";  arm "Secret complete, pod mounted the old SPC"      1 "^STALE-MOUNT core-java-a core-java-kv mounted=21 declared=22$" "$T/stale-mount"
  build "$T/no-secret"    "$S3" '[]'    "$P" "$ST22";  arm "consumer running, synced Secret absent"        1 "^MISSING-SECRET stripe-credentials core-java-kv pods=1$" "$T/no-secret"
  build "$T/no-status"    "$S3" "$SEC3" "$P" '[]';     arm "consumer running with no pod status"           1 "^NO-POD-STATUS core-java-a core-java-kv$" "$T/no-status"
  build "$T/pending"      "$S3" '[]'    "$PEND" '[]';  arm "control: only a Pending pod, no Secret yet"    0 ""                 "$T/pending"
  build "$T/no-spc"       '[]'  "$SEC3" "$P" "$ST22";  arm "no SecretProviderClass read"                   2 "VOID"             "$T/no-spc"
  build "$T/garbled"      "$S3" "$SEC3" "$P" "$ST22"; echo 'not json' > "$T/garbled/pods.json"
                                                       arm "an unreadable pod list"                        2 "VOID"             "$T/garbled"
  echo
  [ "$bad" -eq 0 ] || fail "selftest: $bad arm(s) did not behave ($pass passed)"
  echo "PASS: selftest, $pass arms (green, the observed 2026-10-31 shape, five single-defect reds, two VOIDs)"
  exit 0
fi

# ---- fixture mode (offline --check) -------------------------------------------------------
if [ "$MODE" = check ] && [ -n "$FIXTURE" ]; then
  out="$(compare "$FIXTURE")"; rc=$?
  [ -z "$out" ] || printf '%s\n' "$out"
  exit "$rc"
fi

# ---- cluster modes -------------------------------------------------------------------
[ -n "$KUBE_CONTEXT" ] || void "--context is required"
command -v kubectl >/dev/null 2>&1 || void "kubectl is not installed"
[ "$NAMESPACE" = jtoye-staging ] || void "namespace '$NAMESPACE' has no identity file to check the context's server
     against (only k8s/staging/azure-identity.yaml exists): fail closed."
# shellcheck source=scripts/lib/staging-kube-guard.sh
. "$REPO_ROOT/scripts/lib/staging-kube-guard.sh" || void "could not source scripts/lib/staging-kube-guard.sh"
grc=0
staging_kube_guard "$KUBE_CONTEXT" "$REPO_ROOT/k8s/staging/azure-identity.yaml" || grc=$?
[ "$grc" -eq 0 ] || void "the staging context guard refused '$KUBE_CONTEXT' (rc $grc, line above); nothing was done"
K=(kubectl --context "$KUBE_CONTEXT" -n "$NAMESPACE")

read_live() { # DIR
  "${K[@]}" get secretproviderclass -o json > "$1/spc.json" || return 2
  # Every Secret, because the driver does not always label what it syncs (staging's
  # rabbitmq-default-user carries only its secretObjects labels and an ownerReference to the
  # consumer's ReplicaSet). VALUES ARE DROPPED IN THE PIPE: only key names, labels and owners
  # ever reach the disk.
  "${K[@]}" get secret -o json \
    | jq '{items: [.items[] | {metadata: {name: .metadata.name, labels: (.metadata.labels // {}),
             ownerReferences: (.metadata.ownerReferences // [])}, data: ((.data // {}) | map_values(""))}]}' \
    > "$1/secrets.json" || return 2
  "${K[@]}" get pods -o json > "$1/pods.json" || return 2
  "${K[@]}" get secretproviderclasspodstatus -o json > "$1/podstatus.json" || return 2
}

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT

case "$MODE" in
  prepare)
    [ -n "$STATE" ] || void "--prepare needs --state <file> for --restart to read"
    [ -d "$OVERLAY" ] || void "overlay '$OVERLAY' is not a directory"
    if command -v kustomize >/dev/null 2>&1; then kustomize build "$OVERLAY" > "$W/render.yaml" || void "kustomize build $OVERLAY failed"
    else "${K[@]}" kustomize "$OVERLAY" > "$W/render.yaml" || void "kubectl kustomize $OVERLAY failed"; fi
    # The render is a stream of documents split by `---` lines, each with a top-level `kind:`.
    awk 'BEGIN{RS="\n---\n"; ORS="\n---\n"} /(^|\n)kind: SecretProviderClass\n/' "$W/render.yaml" > "$W/spc.yaml"
    n="$(grep -c '^kind: SecretProviderClass$' "$W/spc.yaml")"
    want="$(grep -c '^kind: SecretProviderClass$' "$W/render.yaml")"
    [ "$n" -gt 0 ] && [ "$n" -eq "$want" ] || void "extracted $n SecretProviderClass document(s) of $want rendered"
    # A split that merged documents would carry a Deployment into this early apply.
    [ "$(grep -c '^kind: ' "$W/spc.yaml")" -eq "$n" ] || void "the extracted SecretProviderClass stream holds another kind: the document split is wrong"
    echo "prepare: applying the overlay's $n SecretProviderClass(es) before anything that mounts them"
    "${K[@]}" apply -f "$W/spc.yaml" || fail "applying the SecretProviderClasses failed"
    read_live "$W" || void "could not read SecretProviderClasses, synced Secrets, pods or pod statuses"
    : > "$STATE"
    while read -r kind secret spc rest; do
      [ "$kind" = STALE-SECRET ] || continue
      # Only a Secret the driver made: its managed label, or owned by a workload (the driver sets
      # the consumer's owner). A hand-made Secret of the same name has neither and is never deleted.
      jq -e --arg n "$secret" --arg l "${MANAGED_LABEL%%=*}" '.items[] | select(.metadata.name == $n)
          | (.metadata.labels[$l] == "true") or ((.metadata.ownerReferences | length) > 0)' "$W/secrets.json" >/dev/null \
        || fail "synced Secret $secret lacks a key $spc declares ($rest), but it carries neither the driver's label nor an owner: not deleting a Secret this script cannot prove the driver made"
      echo "prepare: synced Secret $secret lacks a key $spc declares ($rest); deleting it so the next mount recreates it complete"
      "${K[@]}" delete secret "$secret" --wait=true || fail "could not delete stale synced Secret $secret"
      "${K[@]}" get deploy,statefulset -o json \
        | jq -r --arg s "$spc" '.items[] | select([.spec.template.spec.volumes[]?.csi.volumeAttributes.secretProviderClass?] | index($s))
                                 | "\(.kind | ascii_downcase)/\(.metadata.name)"' >> "$STATE" \
        || fail "could not list the workloads that mount $spc"
    done < <(compare "$W" 2>/dev/null)
    sort -u -o "$STATE" "$STATE"
    echo "prepare: $(grep -c . "$STATE") workload(s) to restart after the apply"
    ;;
  restart)
    [ -n "$STATE" ] && [ -f "$STATE" ] || void "--restart needs the --state file --prepare wrote"
    while read -r w; do
      [ -n "$w" ] || continue
      echo "restart: $w (its synced Secret was recreated)"
      "${K[@]}" rollout restart "$w" || fail "rollout restart $w failed"
    done < "$STATE"
    while read -r w; do
      [ -n "$w" ] || continue
      "${K[@]}" rollout status "$w" --timeout="$ROLLOUT_TIMEOUT" || fail "$w did not roll out"
    done < "$STATE"
    ;;
  check)
    read_live "$W" || void "could not read SecretProviderClasses, synced Secrets, pods or pod statuses"
    out="$(compare "$W")"; rc=$?
    log "namespace : $NAMESPACE"
    log "spcs      : $(jq '.items | length' "$W/spc.json"), synced Secrets: $(jq '.items | length' "$W/secrets.json")"
    if [ "$rc" -eq 0 ]; then
      echo "PASS: every synced Secret holds every key its SecretProviderClass declares, and every running consumer pod mounted the full object set."
    elif [ "$rc" -eq 1 ]; then
      printf '%s\n' "$out"
      fail "a Key Vault object did not reach a pod (issue #1054). A STALE-SECRET is repaired by deleting that driver-managed Secret and restarting its consumers; a STALE-MOUNT by restarting the pod."
    fi
    exit "$rc"
    ;;
esac
