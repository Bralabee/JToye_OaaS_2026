#!/usr/bin/env bash
# staging-kube-guard.sh — the ONE implementation of the kube-context checks every
# cluster-mutating staging script in Phase 29 runs before it touches a cluster
# (plan 29-03; threat T-29-09). Sourced by scripts/staging-bootstrap.sh (29-03),
# the Keycloak realm import --apply (29-04) and the staging seed (29-06). A fix
# here reaches all of them at once; none of them carries its own copy.
#
# SOURCED, NEVER EXECUTED. It defines one function and runs nothing at source
# time. The function RETURNS a status and never calls `exit` or `exec`, so a
# caller decides what a refusal does:
#
#     source scripts/lib/staging-kube-guard.sh
#     staging_kube_guard "$CTX" "$IDENTITY_FILE" || exit $?
#
# staging_kube_guard CONTEXT IDENTITY_FILE — the checks, in this order, each
# printing one tagged line to stderr:
#
#   1. CONTEXT empty                       USAGE ERROR: --context is required     -> 2
#   2. CONTEXT is sipbihs2aks              REFUSED [employer-context]             -> 1
#      (the employer's cluster; refused by NAME, before any read, so naming it
#      explicitly can never make it allowed)
#   3. IDENTITY_FILE unreadable, or not    REFUSED [identity-file]                -> 1
#      holding exactly ONE non-empty
#      aks_api_server_fqdn value (quotes stripped), or that value not a bare
#      host name. Fail closed: a server that cannot be checked is treated as the
#      wrong server.
#   4. kubectl not on PATH                 VOID: kubectl not on PATH              -> 2
#   5. the context's cluster server, read  REFUSED [server-url] context C points  -> 1
#      LOCALLY from the kubeconfig          at <server>, expected https://FQDN:443
#      (`kubectl config view --minify --context C`; no API call; empty when the
#      context does not exist), not byte-equal to https://<FQDN>:443. This is
#      what refuses the stale `jtoye-staging` context the deleted August cluster
#      left on this host, and any context re-pointed at another cluster.
#   6. kubelogin not on PATH               VOID: kubelogin not on PATH            -> 2
#      (the cluster is Entra-only with local accounts disabled, D-32; without
#      kubelogin every call fails to authenticate, which is "could not
#      evaluate", not "refused")
#   otherwise                                                                      -> 0
#
# The identity file is k8s/staging/azure-identity.yaml (plan 29-09), written from
# subscription-pinned read-backs; this guard makes no Azure call of its own.

if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  echo "staging-kube-guard.sh is a library: source it, then call staging_kube_guard CONTEXT IDENTITY_FILE" >&2
  exit 2
fi

staging_kube_guard() {
  local ctx="${1:-}" idf="${2:-}"
  local lines n fqdn server expected

  if [ -z "$ctx" ]; then
    echo "USAGE ERROR: --context is required (no default: an ambient context is how a command lands on the wrong cluster)" >&2
    return 2
  fi

  case "$ctx" in
    sipbihs2aks)
      echo "REFUSED [employer-context]: context '${ctx}' is the employer's cluster. Naming it explicitly does not make it allowed." >&2
      return 1
      ;;
  esac

  if [ -z "$idf" ] || [ ! -f "$idf" ] || [ ! -r "$idf" ]; then
    echo "REFUSED [identity-file]: identity file '${idf:-<none>}' is not a readable file, so the cluster this context points at cannot be checked (written by plan 29-09)" >&2
    return 1
  fi
  lines=$(command grep -E '^[[:space:]]*aks_api_server_fqdn[[:space:]]*:' "$idf" 2>/dev/null || true)
  if [ -z "$lines" ]; then n=0; else n=$(command grep -c '' <<<"$lines"); fi
  if [ "$n" -ne 1 ]; then
    echo "REFUSED [identity-file]: ${idf} holds ${n} aks_api_server_fqdn line(s), expected exactly 1" >&2
    return 1
  fi
  fqdn="${lines#*:}"
  fqdn="${fqdn#"${fqdn%%[![:space:]]*}"}"
  case "$fqdn" in
    \"*) fqdn="${fqdn#\"}"; fqdn="${fqdn%%\"*}" ;;
    \'*) fqdn="${fqdn#\'}"; fqdn="${fqdn%%\'*}" ;;
    *)   fqdn="${fqdn%%[[:space:]]#*}"; fqdn="${fqdn%"${fqdn##*[![:space:]]}"}" ;;
  esac
  if [ -z "$fqdn" ]; then
    echo "REFUSED [identity-file]: ${idf} has an empty aks_api_server_fqdn" >&2
    return 1
  fi
  if ! [[ "$fqdn" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; then
    echo "REFUSED [identity-file]: aks_api_server_fqdn '${fqdn}' is not a bare host name (no scheme, port or path)" >&2
    return 1
  fi

  if ! command -v kubectl >/dev/null 2>&1; then
    echo "VOID: kubectl not on PATH; the context's server cannot be read" >&2
    return 2
  fi
  server=$(kubectl config view --minify --context "$ctx" -o 'jsonpath={.clusters[0].cluster.server}' 2>/dev/null || true)
  expected="https://${fqdn}:443"
  if [ "$server" != "$expected" ]; then
    echo "REFUSED [server-url] context ${ctx} points at ${server:-<no such context>}, expected ${expected}" >&2
    return 1
  fi

  if ! command -v kubelogin >/dev/null 2>&1; then
    echo "VOID: kubelogin not on PATH; the cluster is Entra-only (D-32) and no call through this context could authenticate" >&2
    return 2
  fi

  return 0
}
