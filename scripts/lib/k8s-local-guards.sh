#!/usr/bin/env bash
# k8s-local-guards.sh — shared guards for the local-Kubernetes bring-up path
# (Phase 26 / INFRA-01).
#
# WHAT THIS IS
#   A SOURCE-ONLY library. It defines guards; it runs none of them at source
#   time. Both scripts/k8s-local-secrets.sh and scripts/k8s-local-up.sh source
#   it and call the guards explicitly, BEFORE any mutating step.
#
# WHY SOURCE-ONLY MATTERS
#   Every guard must be falsifiable in isolation without a cluster and without
#   mutating anything:
#     bash -c 'source scripts/lib/k8s-local-guards.sh
#              k8s_local_load_env
#              k8s_local_assert_compose_xor'
#   If sourcing invoked docker/kubectl/minikube, that probe would be measuring
#   the sourcing, not the guard. So: definitions and constants only below —
#   no top-level docker, kubectl or minikube call.
#
# EXIT-CODE CONVENTION (the sibling k8s/scripts/check-*.sh convention)
#   0 = clean / may proceed
#   1 = violation — a guard refuses
#   2 = parse or tooling failure (the assertion is VOID, not passing)
#   The guards RETURN these codes; callers run with `set -euo pipefail` so a
#   refusal aborts them at the refusing guard.
#
# NO ENVIRONMENT-VARYING LITERALS
#   Every host and port comes from the gitignored .env via the K8S_LOCAL_* keys
#   (GLOBAL_RULE_6 / ARCHITECTURE_RULE_8). A published-port shift in
#   docker-compose.full-stack.yml is then a one-file .env fix, not a script edit.
#
# SECURITY
#   Mirrors scripts/verify-env.sh: this library prints variable NAMES only,
#   never a value.

# Include-once: `readonly` would abort a re-source under `set -e`.
if [ -n "${K8S_LOCAL_GUARDS_LOADED:-}" ]; then
  return 0 2>/dev/null || true
fi
K8S_LOCAL_GUARDS_LOADED=1

K8S_LOCAL_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K8S_LOCAL_REPO_ROOT="$(cd "$K8S_LOCAL_LIB_DIR/../.." && pwd)"
readonly K8S_LOCAL_LIB_DIR K8S_LOCAL_REPO_ROOT

# The compose file that defines the local backing services. Single source of
# truth for both the XOR guard's service inventory and the container names the
# bootstrap reaches into.
readonly K8S_LOCAL_COMPOSE_FILE="docker-compose.full-stack.yml"

# The kustomization whose `namespace:` line is the single source of truth for the
# local namespace (never duplicated into .env).
readonly K8S_LOCAL_KUSTOMIZATION="k8s/local/kustomization.yaml"

# compose APP services — these WRITE the shared dev Postgres, so the cluster and
# compose can never run them at the same time (D-04). `core-java` has no
# container_name in the compose file, hence services are matched by SERVICE name
# rather than container name.
readonly K8S_LOCAL_APP_SERVICES="core-java frontend edge-go mcp-server"

# compose BACKING services — the cluster CONSUMES these over the pod host, so
# they must be UP for the overlay to work at all (D-04, other half).
readonly K8S_LOCAL_BACKING_SERVICES="postgres redis rabbitmq keycloak minio mailhog"

# Namespaces allowed to run workloads on the local profile WITHOUT being treated
# as a second writer on the shared dev Postgres. Kubernetes' own control-plane
# namespaces plus the ingress addon's, which carry no application code. The
# EXPECTED LOCAL NAMESPACE IS DELIBERATELY ABSENT from this list: it is read at
# call time from k8s_local_namespace(), so the local namespace keeps exactly one
# source of truth (k8s/local/kustomization.yaml) and cannot drift into a second.
readonly K8S_LOCAL_SYSTEM_NAMESPACES="kube-system kube-public kube-node-lease ingress-nginx default"

# A pod in any of these phases has stopped for good and can hold no database
# connection, so the cluster inventory excludes them SERVER-SIDE. Everything else
# — Running, Pending, Unknown — counts as live: a pod still pulling its image in a
# stale namespace is the same hazard a few seconds early, and a guard that only
# looked at Running would wave it through.
readonly K8S_LOCAL_TERMINAL_PHASE_SELECTOR="status.phase!=Succeeded,status.phase!=Failed"

# The K8S_LOCAL_* contract every caller depends on. Asserted set + non-empty by
# k8s_local_load_env, by NAME.
K8S_LOCAL_REQUIRED_KEYS=(
  K8S_LOCAL_POD_HOST
  K8S_LOCAL_DB_PORT
  K8S_LOCAL_KC_PORT
  K8S_LOCAL_REDIS_PORT
  K8S_LOCAL_AMQP_PORT
  K8S_LOCAL_STOMP_PORT
  K8S_LOCAL_MINIO_PORT
  K8S_LOCAL_SMTP_PORT
  K8S_LOCAL_KUBE_CONTEXT
  K8S_LOCAL_MINIKUBE_PROFILE
  K8S_LOCAL_MINIKUBE_CPUS
  K8S_LOCAL_MINIKUBE_MEMORY
  K8S_LOCAL_BACKUP_BUCKET
)
readonly K8S_LOCAL_REQUIRED_KEYS

# ---------------------------------------------------------------------------
# Reporting helpers (names only, never values)
# ---------------------------------------------------------------------------
k8s_local_refuse() {
  # k8s_local_refuse <arm> <message...>
  local arm="$1"; shift
  echo "REFUSED [$arm]: $*" >&2
  return 1
}

k8s_local_tooling_fail() {
  echo "TOOLING ERROR: $*" >&2
  return 2
}

k8s_local_ok() { echo "OK: $*"; }

# ---------------------------------------------------------------------------
# k8s_local_load_env [env-file]
#   Sources the env file with `set -a` (the scripts/verify-env.sh idiom) and then
#   asserts the K8S_LOCAL_* contract is complete. Fails by NAME.
# ---------------------------------------------------------------------------
k8s_local_load_env() {
  local env_file="${1:-$K8S_LOCAL_REPO_ROOT/.env}"

  if [ ! -f "$env_file" ]; then
    k8s_local_tooling_fail "env file not found: $env_file (copy .env.example and fill it in)"
    return 2
  fi

  set -a
  # shellcheck disable=SC1090
  . "$env_file"
  set +a

  local missing=0 key
  for key in "${K8S_LOCAL_REQUIRED_KEYS[@]}"; do
    if [ -z "${!key:-}" ]; then
      echo "MISSING: ${key} is unset or empty in ${env_file}" >&2
      missing=$((missing + 1))
    fi
  done
  if [ "$missing" -gt 0 ]; then
    k8s_local_refuse "env-contract" \
      "${missing} required K8S_LOCAL_* key(s) missing — see the names above and .env.example"
    return 1
  fi

  k8s_local_ok "env loaded from ${env_file}; all ${#K8S_LOCAL_REQUIRED_KEYS[@]} K8S_LOCAL_* keys present"
  return 0
}

# ---------------------------------------------------------------------------
# k8s_local_profile_ip
#   Echoes the local minikube profile's node IP.
#   `minikube ip -p <profile>` is authoritative but EXITS 83 while the profile is
#   Stopped, so fall back to the profile registry JSON, which reports the node IP
#   even then. Emits nothing (and fails) when neither resolves — the callers
#   then fail CLOSED rather than proceed on an unresolvable expectation.
# ---------------------------------------------------------------------------
k8s_local_profile_ip() {
  local profile="${K8S_LOCAL_MINIKUBE_PROFILE:-}"
  [ -n "$profile" ] || return 2

  local ip=""
  ip="$(minikube ip -p "$profile" 2>/dev/null || true)"
  if ! grep -qE '^[0-9]+(\.[0-9]+){3}$' <<<"$ip"; then
    ip="$(minikube profile list -o json 2>/dev/null \
          | jq -r --arg p "$profile" \
              '.valid[]? | select(.Name == $p) | .Config.Nodes[0].IP // empty' \
          2>/dev/null || true)"
  fi
  grep -qE '^[0-9]+(\.[0-9]+){3}$' <<<"$ip" || return 1
  printf '%s\n' "$ip"
  return 0
}

# ---------------------------------------------------------------------------
# k8s_local_assert_context [expected-context]
#   Refuses unless the resolved kubectl context IS the configured local minikube
#   one. Four refusal arms, each named in the message so a falsification probe
#   can record which fired:
#     unresolvable-profile-ip | wrong-name | context-absent | server-host-mismatch
#
#   THIS GUARD IS LOAD-BEARING, NOT DECORATIVE. On this host the ONLY kubeconfig
#   context is employer infrastructure. `kubectl config use-context` is never
#   called anywhere in this tooling, and every cluster call goes through
#   k8s_local_kubectl, which passes --context explicitly.
# ---------------------------------------------------------------------------
k8s_local_assert_context() {
  local want="${K8S_LOCAL_KUBE_CONTEXT:-}"
  local profile="${K8S_LOCAL_MINIKUBE_PROFILE:-}"
  local employer_warning
  employer_warning="the other kubectl context(s) on this host are EMPLOYER infrastructure and must NEVER be targeted"

  if [ -z "$want" ] || [ -z "$profile" ]; then
    k8s_local_tooling_fail "K8S_LOCAL_KUBE_CONTEXT / K8S_LOCAL_MINIKUBE_PROFILE unset — call k8s_local_load_env first"
    return 2
  fi

  # A caller may not override the configured local context. Any other value is a
  # mis-invocation, and on this host the alternative is employer infrastructure.
  local asked="${1:-$want}"
  if [ "$asked" != "$want" ]; then
    k8s_local_refuse "wrong-name" \
      "caller asked for context '${asked}' but the configured local context is '${want}'; ${employer_warning}"
    return 1
  fi

  # Fail CLOSED when the expectation itself cannot be resolved.
  local node_ip
  if ! node_ip="$(k8s_local_profile_ip)"; then
    k8s_local_refuse "unresolvable-profile-ip" \
      "could not resolve a node IP for minikube profile '${profile}' (tried 'minikube ip -p' then the profile registry JSON); refusing rather than proceeding on an unresolvable expectation; ${employer_warning}"
    return 1
  fi

  local ctx_names
  ctx_names="$(kubectl config get-contexts -o name 2>/dev/null || true)"
  if ! grep -Fxq "$want" <<<"$ctx_names"; then
    k8s_local_refuse "context-absent" \
      "kubectl context '${want}' does not exist in kubeconfig — the minikube profile '${profile}' creates it on start, so start the profile first (scripts/k8s-local-up.sh does this in order); ${employer_warning}"
    return 1
  fi

  local cluster server server_host
  cluster="$(kubectl config view -o "jsonpath={.contexts[?(@.name=='${want}')].context.cluster}" 2>/dev/null || true)"
  if [ -z "$cluster" ]; then
    k8s_local_tooling_fail "context '${want}' exists but names no cluster — kubeconfig is malformed"
    return 2
  fi
  server="$(kubectl config view -o "jsonpath={.clusters[?(@.name=='${cluster}')].cluster.server}" 2>/dev/null || true)"
  server_host="$(printf '%s' "$server" | sed -E 's#^[a-zA-Z]+://##; s#[:/].*$##')"
  if [ -z "$server_host" ]; then
    k8s_local_tooling_fail "cluster '${cluster}' has no resolvable server host — kubeconfig is malformed"
    return 2
  fi

  if [ "$server_host" != "$node_ip" ]; then
    k8s_local_refuse "server-host-mismatch" \
      "context '${want}' points at API server host '${server_host}', but minikube profile '${profile}' is at '${node_ip}'; ${employer_warning}"
    return 1
  fi

  k8s_local_ok "kubectl context '${want}' resolves to the local minikube profile '${profile}' at ${node_ip}"
  return 0
}

# ---------------------------------------------------------------------------
# k8s_local_compose_state
#   Echoes `<service> <state>` per compose service, ALL states included
#   (`--all`), so a stopped service is visible rather than merely absent. This is
#   the one place the format is defined, which is what makes the guard
#   falsifiable against a fixture that prints the same format.
# ---------------------------------------------------------------------------
k8s_local_compose_state() {
  (
    cd "$K8S_LOCAL_REPO_ROOT" || exit 2
    docker compose -f "$K8S_LOCAL_COMPOSE_FILE" ps --all --format '{{.Service}} {{.State}}' 2>/dev/null
  )
}

# ---------------------------------------------------------------------------
# k8s_local_assert_compose_xor
#   D-04, both halves:
#     * APP containers DOWN — the cluster and compose would otherwise be two
#       writers on the same shared dev Postgres.
#     * BACKING services UP — the overlay shims every endpoint at the pod host,
#       so the cluster consumes the compose backing services.
#
#   READ-ONLY BY CONSTRUCTION. This function never stops, starts or removes a
#   container: this checkout can be driven by a second concurrent session, so
#   shutting the developer's stack down could destroy work that is not ours.
#   Bringing the app containers down is the HUMAN's decision.
#
#   THIS IS ONLY HALF OF D-04. It inspects compose and nothing else, so it cannot
#   see a second writer that is already inside the cluster. The cluster side is
#   k8s_local_assert_cluster_xor, at the bottom of this file, and it must run after
#   the profile start and before any apply. Do not read a green here as "there is
#   one writer".
# ---------------------------------------------------------------------------
k8s_local_assert_compose_xor() {
  if ! command -v docker >/dev/null 2>&1; then
    k8s_local_tooling_fail "docker not found on PATH"
    return 2
  fi

  local state
  if ! state="$(k8s_local_compose_state)"; then
    k8s_local_tooling_fail "could not read compose state from ${K8S_LOCAL_COMPOSE_FILE}"
    return 2
  fi
  if [ -z "$state" ]; then
    k8s_local_tooling_fail "compose reported no services at all for ${K8S_LOCAL_COMPOSE_FILE} — the assertion would pass by finding nothing, which is VOID, not clean"
    return 2
  fi

  # HERESTRINGS, not pipes — and here the fail DIRECTION is what makes it urgent.
  # `grep -q` exits on first match, the pipe's writer takes SIGPIPE and reports
  # 141, and the caller's `set -o pipefail` promotes 141 to the pipeline status.
  # In the APP loop below a spurious 141 reads as "this app service is NOT
  # running", so the guard would FAIL OPEN: it would conclude the compose apps
  # are down while they are still up, and let a cluster start against a live
  # compose stack — the two-writers-on-one-dev-Postgres hazard this guard exists
  # to prevent. (The backing loop fails closed by comparison, and this input is
  # small enough that the race was never observed here; the 38 KB render in
  # scripts/k8s-local-up.sh is where the class actually bit, in plan 26-07.)
  local svc running_apps="" down_backing=""
  for svc in $K8S_LOCAL_APP_SERVICES; do
    if grep -qE "^${svc} running$" <<<"$state"; then
      running_apps="${running_apps} ${svc}"
    fi
  done
  for svc in $K8S_LOCAL_BACKING_SERVICES; do
    if ! grep -qE "^${svc} running$" <<<"$state"; then
      down_backing="${down_backing} ${svc}"
    fi
  done

  if [ -n "$running_apps" ]; then
    k8s_local_refuse "compose-apps-running" \
      "compose APP service(s) still running:${running_apps}. The local cluster and compose would be TWO WRITERS on the same shared dev Postgres. Bring the app containers down first (a human decision — this tooling never stops a container, because a second session may own this stack). The backing services must STAY UP."
    return 1
  fi

  if [ -n "$down_backing" ]; then
    k8s_local_refuse "compose-backing-down" \
      "compose BACKING service(s) not running:${down_backing}. The local overlay shims every endpoint to the pod host '${K8S_LOCAL_POD_HOST:-<unset>}', so the cluster CONSUMES these — a pod would come up and fail to connect. Start the backing services (apps stay down)."
    return 1
  fi

  k8s_local_ok "compose XOR k8s satisfied — all app services down, all backing services up"
  return 0
}

# ---------------------------------------------------------------------------
# k8s_local_namespace
#   Echoes the namespace parsed from the local kustomization, so the namespace
#   has exactly ONE source of truth and never drifts into .env or a script.
# ---------------------------------------------------------------------------
k8s_local_namespace() {
  local file="$K8S_LOCAL_REPO_ROOT/$K8S_LOCAL_KUSTOMIZATION"
  local ns
  ns="$(awk '/^namespace:[[:space:]]*[^[:space:]]/{print $2; exit}' "$file" 2>/dev/null || true)"
  if [ -z "$ns" ]; then
    k8s_local_tooling_fail "could not parse a 'namespace:' line from ${K8S_LOCAL_KUSTOMIZATION}"
    return 2
  fi
  printf '%s\n' "$ns"
  return 0
}

# ---------------------------------------------------------------------------
# k8s_local_kubectl
#   The ONLY way this tooling talks to a cluster: --context is always explicit.
#   `kubectl config use-context` is never called, so the ambient current-context
#   (unset on this host) can never decide where an apply lands.
# ---------------------------------------------------------------------------
k8s_local_kubectl() {
  if [ -z "${K8S_LOCAL_KUBE_CONTEXT:-}" ]; then
    k8s_local_tooling_fail "K8S_LOCAL_KUBE_CONTEXT unset — call k8s_local_load_env first"
    return 2
  fi
  kubectl --context "$K8S_LOCAL_KUBE_CONTEXT" "$@"
}

# ---------------------------------------------------------------------------
# k8s_local_cluster_pod_inventory
#   Echoes `<namespace> <phase> <pod> <image>[ <image>...]` per NON-TERMINATED
#   pod, cluster-wide. As with k8s_local_compose_state, this is the ONE place the
#   format is defined, which is what makes the guard above falsifiable by
#   redefining this function over a fixture that prints the same shape.
#
#   stderr is deliberately NOT swallowed: when the API server is unreachable,
#   kubectl's own message is the most useful diagnostic there is, and the caller
#   adds the interpretation rather than replacing the evidence.
# ---------------------------------------------------------------------------
k8s_local_cluster_pod_inventory() {
  k8s_local_kubectl get pods --all-namespaces \
    --field-selector "$K8S_LOCAL_TERMINAL_PHASE_SELECTOR" \
    -o go-template='{{range .items}}{{.metadata.namespace}} {{.status.phase}} {{.metadata.name}}{{range .spec.containers}} {{.image}}{{end}}{{"\n"}}{{end}}'
}

# ---------------------------------------------------------------------------
# k8s_local_assert_cluster_xor
#   The CLUSTER-SIDE half of D-04, and the reason it exists is measured, not
#   theoretical. k8s_local_assert_compose_xor inspects COMPOSE ONLY. It never
#   looks at the cluster it is about to start, so it is asymmetric: it refuses to
#   start a cluster while compose is up, but it will happily start one that
#   ALREADY CONTAINS ITS OWN WRITERS.
#
#   Measured live on 2026-07-25 (plan 26-07): a Stopped minikube profile preserves
#   etcd, and starting this profile restored an 11-day-old `jtoye-staging`
#   namespace with 11 running pods on stale `:2.1.0` images — code predating
#   Phases 23, 24 and 25 — holding 16 live connections to the shared dev Postgres
#   as `jtoye_app`. So `minikube start` ALONE re-created the exact
#   two-writers-on-one-database hazard the human is asked to stop their compose
#   app containers to avoid, while every guard reported green. Deleting the
#   namespace dropped those connections 16 -> 0.
#
#   WHY PODS AND NOT DEPLOYMENT REPLICAS. The original suggested closure named
#   non-zero-replica Deployments. Pods are strictly better: the same stale
#   namespace also carried a `pg-backup` CronJob that fired on start, and a Job-,
#   CronJob-, StatefulSet-, DaemonSet- or bare-Pod-shaped writer holds a database
#   connection exactly as well as a Deployment's does. Counting pods catches every
#   shape, including ones nobody has thought of yet.
#
#   READ-ONLY BY CONSTRUCTION, for the same reason as the compose arms: deleting a
#   namespace is a HUMAN decision. This function never deletes, scales or patches
#   anything — it names what it found and refuses.
#
#   POINT-IN-TIME, and honest about it: this is a snapshot taken between the
#   profile start and the apply. It cannot stop a workload created after it runs.
#   Its job is the stale-etcd class, which is present the instant the profile
#   comes up.
# ---------------------------------------------------------------------------
k8s_local_assert_cluster_xor() {
  if ! command -v kubectl >/dev/null 2>&1; then
    k8s_local_tooling_fail "kubectl not found on PATH"
    return 2
  fi

  # The expected namespace comes from the kustomization, never from a literal.
  local ns_expected
  if ! ns_expected="$(k8s_local_namespace)"; then
    k8s_local_tooling_fail "could not resolve the expected local namespace from ${K8S_LOCAL_KUSTOMIZATION} — refusing to inventory a cluster without knowing which namespace is ours"
    return 2
  fi

  local inventory
  if ! inventory="$(k8s_local_cluster_pod_inventory)"; then
    k8s_local_tooling_fail "could not read the cluster pod inventory through context '${K8S_LOCAL_KUBE_CONTEXT:-<unset>}' (kubectl's own error is above) — the API server may be unreachable or the minikube profile not started. The assertion is VOID, not passing."
    return 2
  fi

  # FAIL CLOSED ON EMPTINESS. Wave 5 had to fix exactly this class in the compose
  # guard, where empty `docker compose ps` output read as "clean". A reachable
  # cluster ALWAYS has running kube-system pods, so an empty inventory means the
  # query did not measure what it claims to measure.
  if [ -z "$inventory" ]; then
    k8s_local_tooling_fail "the cluster reported NO non-terminated pods at all — not even kube-system, which is impossible on a reachable cluster. The assertion would pass by finding nothing, which is VOID, not clean."
    return 2
  fi

  # FAIL CLOSED ON AN UNPARSEABLE RESPONSE. Every line must carry at least
  # namespace, phase, pod name and one image. A response we cannot parse must not
  # be read as an absence of offenders.
  local malformed total
  malformed="$(awk 'NF < 4 {c++} END {print c + 0}' <<<"$inventory")"
  total="$(awk 'END {print NR + 0}' <<<"$inventory")"
  if [ "$malformed" != "0" ]; then
    k8s_local_tooling_fail "${malformed} of ${total} cluster-inventory line(s) do not match the expected '<namespace> <phase> <pod> <image>...' shape — the response is unparseable, so the assertion is VOID, not clean."
    return 2
  fi

  # One line per OFFENDING namespace: `<ns> <pod-count> <phases> <images>`, both
  # lists comma-separated and de-duplicated so the refusal can name them.
  local offenders
  if ! offenders="$(awk -v expected="$ns_expected" -v allowed="$K8S_LOCAL_SYSTEM_NAMESPACES" '
      function add(list, item) {
        if (index("," list ",", "," item ",") > 0) return list
        return (list == "" ? item : list "," item)
      }
      BEGIN {
        n = split(allowed, a, " ")
        for (i = 1; i <= n; i++) if (a[i] != "") skip[a[i]] = 1
        skip[expected] = 1
      }
      skip[$1] { next }
      {
        if (!($1 in count)) order[++seen] = $1
        count[$1]++
        phases[$1] = add(phases[$1], $2)
        for (i = 4; i <= NF; i++) images[$1] = add(images[$1], $i)
      }
      END {
        for (i = 1; i <= seen; i++) {
          ns = order[i]
          printf "%s %d %s %s\n", ns, count[ns], phases[ns], images[ns]
        }
      }
    ' <<<"$inventory")"; then
    k8s_local_tooling_fail "could not aggregate the cluster pod inventory (${total} line(s)) — the assertion is VOID, not clean."
    return 2
  fi

  if [ -n "$offenders" ]; then
    local ns pod_count phase_list image_list detail=""
    while read -r ns pod_count phase_list image_list; do
      [ -n "$ns" ] || continue
      detail="${detail}
    - namespace '${ns}': ${pod_count} live pod(s) [${phase_list}] running image(s) ${image_list}"
    done <<<"$offenders"

    k8s_local_refuse "cluster-writers-present" \
      "the cluster ALREADY contains workloads outside the expected local namespace '${ns_expected}':${detail}

  These pods can write the same shared dev Postgres as the overlay about to be applied, so proceeding re-creates the TWO-WRITERS-ON-ONE-DATABASE hazard that stopping the compose app containers exists to prevent — and stale images mean stale code against a current schema (a false green). Measured on 2026-07-25: a restored 11-day-old namespace held 16 live 'jtoye_app' connections while every other guard reported green.

  A Stopped minikube profile PRESERVES etcd, so a namespace from an earlier rehearsal comes back the moment the profile starts. Inspect, then delete it yourself — this tooling never deletes a namespace, for the same reason it never stops a compose container:

    kubectl --context ${K8S_LOCAL_KUBE_CONTEXT:-<unset>} -n <namespace> get all
    kubectl --context ${K8S_LOCAL_KUBE_CONTEXT:-<unset>} delete namespace <namespace>

  Namespaces exempt by design: '${ns_expected}' (ours) and ${K8S_LOCAL_SYSTEM_NAMESPACES// /, }."
    return 1
  fi

  k8s_local_ok "cluster XOR satisfied — ${total} live pod(s) inventoried, all inside '${ns_expected}' or the system set (${K8S_LOCAL_SYSTEM_NAMESPACES// /, })"
  return 0
}
