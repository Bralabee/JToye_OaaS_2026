#!/usr/bin/env bash
# check-infra-exposure.sh — local-stack network-exposure gate (issues #438, #439, #441)
#
# WHAT IT ASSERTS
#   A. BIND ADDRESS, declared — every published port in every Compose file is
#      bound to loopback. The port set is ENUMERATED FROM COMPOSE'S OWN PARSE,
#      never from a list written here, so a service added tomorrow is covered the
#      day it is added. The only escape is an explicit, named, reasoned entry in
#      APP_TIER_EXEMPT below: default-deny, so forgetting to think about a new
#      service fails the gate rather than passing silently.
#
#   B. BIND ADDRESS, running — the same assertion against the RUNNING container's
#      actual port bindings. This is the half that matters: `docker compose start`
#      does not re-read a changed Compose file, so A can be green over a container
#      that is still published on 0.0.0.0. A alone would be a structural check
#      passing over a live exposure.
#
#   C. GRAFANA's live administrator credential is the injected one and is strong.
#      Three parts, because any one alone is satisfiable by a broken system:
#        C1 the injected value passes the same strength rules as verify-env.sh
#           — so it is not a product default and not trivially short;
#        C2 the RUNNING instance ACCEPTS that injected value — which is the part
#           that catches an edited .env over an untouched volume, since Grafana
#           applies the configured password only when it first creates the admin
#           user and silently ignores it forever after;
#        C3 the RUNNING instance REJECTS a random value — so C2's success is
#           evidence the endpoint discriminates, not that it accepts anything.
#      Together these establish that the live credential is the strong injected
#      one, which is a stronger statement than "one specific default fails" and,
#      unlike probing with a known default, puts no credential pairing in a file
#      that lives in a public repository.
#
#   D. REGRESSION GUARD on the broker. Its management API was already refusing
#      product-default credentials when the exposure was found; fixing the
#      exposure must not disturb that. Asserted by enumerating the broker's own
#      user list and requiring it to contain nothing but the injected account —
#      which is strictly stronger than probing one known default, and again names
#      no credential.
#
# EXIT CODES
#   0  PASS
#   1  FAIL — a real exposure or a live default credential
#   2  VOID — could not measure (missing tooling, unparseable compose, ZERO ports
#      discovered, or a service that must be running is not). "Found nothing" is
#      never reported as "clean".
#
# USAGE
#   scripts/check-infra-exposure.sh              # A + B + C + D
#   scripts/check-infra-exposure.sh --static     # A only (no running stack needed)
#
# NOTE ON A DELIBERATE OPT-IN
#   JTOYE_BIND_HOST=0.0.0.0 in .env republishes everything on all interfaces (for
#   example to open the storefront on a phone on the same LAN). This gate FAILS
#   while that is set, and that is the intended behaviour — the point is that the
#   exposure is now loud and deliberate instead of silent and default.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || { echo "VOID: cannot cd to repo root"; exit 2; }

STATIC_ONLY=0
[ "${1:-}" = "--static" ] && STATIC_ONLY=1

# Compose files to parse. This list names FILES, not services or ports — the
# services and ports are always read back out of Compose's parse of them.
COMPOSE_FILES=(
  "docker-compose.full-stack.yml"
  "infra/docker-compose.yml"
  "infra/monitoring/docker-compose.monitoring.yml"
)

# Services allowed to publish on a non-loopback address, each with its reason.
# Anything NOT named here must be loopback-bound. Keep this list short and
# argued; an entry added without a reason is the failure mode this gate exists
# to prevent.
#
#   core-java    — application tier. Out of scope of #438/#439/#441, which are
#                  scoped to infrastructure. It also has a live in-container
#                  consumer: the frontend's customer-orders route falls back to
#                  NEXT_PUBLIC_API_URL (http://localhost:9090) when
#                  CORE_API_INTERNAL_URL is unset. Rebinding it is a separate
#                  change that must land with that fallback fixed.
#   frontend     — application tier, out of scope.
#   edge-go      — application tier, out of scope.
#   mcp-server   — application tier, out of scope.
APP_TIER_EXEMPT=(core-java frontend edge-go mcp-server)

RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YEL=$'\033[1;33m'; NC=$'\033[0m'
pass() { printf '%s✓ PASS%s: %s\n' "$GREEN" "$NC" "$1"; }
fail() { printf '%s✗ FAIL%s: %s\n' "$RED" "$NC" "$1"; }
void() { printf '%s∅ VOID%s: %s\n' "$YEL" "$NC" "$1"; }

FAILURES=0

is_exempt() {
  local svc="$1" e
  for e in "${APP_TIER_EXEMPT[@]}"; do [ "$svc" = "$e" ] && return 0; done
  return 1
}

# A host_ip counts as loopback when it is in 127.0.0.0/8 or is the IPv6 loopback.
# An EMPTY host_ip is Docker's "all interfaces" — it is the exact shape this gate
# exists to catch, so it must never be treated as unset-and-therefore-fine.
is_loopback() {
  case "${1:-}" in
    127.*) return 0 ;;
    ::1|"[::1]") return 0 ;;
    *) return 1 ;;
  esac
}

# ---- tooling -----------------------------------------------------------------
for tool in docker jq; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    void "required tool '${tool}' not found — cannot measure"
    exit 2
  fi
done
docker compose version >/dev/null 2>&1
rc=$?
if [ "$rc" -ne 0 ]; then
  void "'docker compose' unavailable (rc=${rc}) — cannot measure"
  exit 2
fi

# ---- synthetic env so the parse works with no .env (CI-safe) -----------------
# Compose refuses to render a file with an unsatisfied ${VAR:?...}. Fill ONLY
# those, and only when genuinely unset, so a real .env always wins. The filler is
# a parse placeholder, not a credential.
fill_required_vars() {
  local f v
  for f in "${COMPOSE_FILES[@]}"; do
    [ -f "$f" ] || continue
    # shellcheck disable=SC2016
    for v in $(grep -oE '\$\{[A-Za-z_][A-Za-z0-9_]*:\?' "$f" | sed -E 's/^\$\{//; s/:\?$//' | sort -u); do
      if [ -z "${!v:-}" ]; then export "$v=placeholder"; fi
    done
  done
}
if [ -f .env ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi
fill_required_vars

echo "========================================================"
echo "A. Declared bind addresses (enumerated from compose parse)"
echo "========================================================"

TOTAL_PORTS=0
declare -a NONLOOPBACK_SERVICES=()

for f in "${COMPOSE_FILES[@]}"; do
  if [ ! -f "$f" ]; then
    void "compose file not found: ${f}"
    exit 2
  fi
  cfg=$(docker compose -f "$f" config --format json 2>&1)
  rc=$?
  if [ "$rc" -ne 0 ]; then
    void "docker compose config failed for ${f} (rc=${rc}): ${cfg}"
    exit 2
  fi

  rows=$(jq -r '
    .services
    | to_entries[]
    | . as $s
    | ($s.value.ports // [])[]
    | "\($s.key)\t\(.host_ip // "")\t\(.published // "")\t\(.target // "")"
  ' <<< "$cfg")
  jrc=$?
  if [ "$jrc" -ne 0 ]; then
    void "jq could not read ports out of ${f} (rc=${jrc})"
    exit 2
  fi

  echo "--- ${f} ---"
  while IFS=$'\t' read -r svc host_ip published target; do
    [ -z "${svc:-}" ] && continue
    TOTAL_PORTS=$((TOTAL_PORTS + 1))
    shown="${host_ip:-<none == 0.0.0.0>}"
    if is_loopback "$host_ip"; then
      printf '  %-20s %-22s %s -> %s  loopback\n' "$svc" "$shown" "$published" "$target"
    elif is_exempt "$svc"; then
      printf '  %-20s %-22s %s -> %s  %sEXEMPT (application tier)%s\n' \
        "$svc" "$shown" "$published" "$target" "$YEL" "$NC"
    else
      printf '  %-20s %-22s %s -> %s  %sALL INTERFACES%s\n' \
        "$svc" "$shown" "$published" "$target" "$RED" "$NC"
      NONLOOPBACK_SERVICES+=("${f}:${svc}:${published}")
    fi
  done <<< "$rows"
done

# "Found nothing" is never "clean".
if [ "$TOTAL_PORTS" -eq 0 ]; then
  void "ZERO published ports discovered across ${#COMPOSE_FILES[@]} compose files — the parse is not measuring anything"
  exit 2
fi

echo ""
if [ "${#NONLOOPBACK_SERVICES[@]}" -eq 0 ]; then
  pass "A: all ${TOTAL_PORTS} discovered published ports are loopback-bound or explicitly exempt"
else
  fail "A: ${#NONLOOPBACK_SERVICES[@]} non-exempt port(s) published on all interfaces:"
  printf '       %s\n' "${NONLOOPBACK_SERVICES[@]}"
  FAILURES=$((FAILURES + 1))
fi

if [ "$STATIC_ONLY" -eq 1 ]; then
  echo ""
  echo "--static: skipping the running-stack assertions (B, C, D)."
  [ "$FAILURES" -eq 0 ] && exit 0 || exit 1
fi

echo ""
echo "========================================================"
echo "B. Running containers' ACTUAL bind addresses"
echo "========================================================"
echo "(a compose edit that was only 'start'ed, never recreated, fails here)"

RUNNING_CHECKED=0
declare -a RUNTIME_BAD=()

names=$(docker ps --format '{{.Names}}' 2>&1)
rc=$?
if [ "$rc" -ne 0 ]; then
  void "docker ps failed (rc=${rc}): ${names}"
  exit 2
fi
if [ -z "$names" ]; then
  void "no containers running — the runtime half cannot be measured"
  exit 2
fi

while read -r cname; do
  [ -z "$cname" ] && continue
  svc=$(docker inspect "$cname" --format '{{index .Config.Labels "com.docker.compose.service"}}' 2>/dev/null)
  [ -z "$svc" ] && continue
  binds=$(docker inspect "$cname" --format \
    '{{range $p, $conf := .NetworkSettings.Ports}}{{range $conf}}{{$p}}|{{.HostIp}}|{{.HostPort}}{{println}}{{end}}{{end}}' 2>/dev/null)
  while IFS='|' read -r portproto hostip hostport; do
    [ -z "${hostip:-}" ] && continue
    # Docker lists v4 and v6 bindings separately; :: is the v6 all-interfaces form.
    RUNNING_CHECKED=$((RUNNING_CHECKED + 1))
    if is_loopback "$hostip"; then
      continue
    fi
    if is_exempt "$svc"; then
      continue
    fi
    RUNTIME_BAD+=("${cname} (service ${svc}) ${portproto} -> ${hostip}:${hostport}")
  done <<< "$binds"
done <<< "$names"

if [ "$RUNNING_CHECKED" -eq 0 ]; then
  void "no published port bindings found on any running container — cannot measure the runtime half"
  exit 2
fi

if [ "${#RUNTIME_BAD[@]}" -eq 0 ]; then
  pass "B: all ${RUNNING_CHECKED} running port bindings are loopback or exempt"
else
  fail "B: ${#RUNTIME_BAD[@]} running binding(s) on all interfaces:"
  printf '       %s\n' "${RUNTIME_BAD[@]}"
  FAILURES=$((FAILURES + 1))
fi

echo ""
echo "========================================================"
echo "C. Grafana's live admin credential is the injected, strong one"
echo "========================================================"

GPORT="${GRAFANA_PORT:-3001}"
GUSER="${GRAFANA_ADMIN_USER:-admin}"
GPASS="${GRAFANA_ADMIN_PASSWORD:-}"

# C1 — strength of the injected value. Same rules as verify-env.sh, restated here
# so this gate is not silently satisfied by a preflight nobody ran.
if [ -z "$GPASS" ] || [ "$GPASS" = "placeholder" ]; then
  void "GRAFANA_ADMIN_PASSWORD is not available to this run — cannot measure C"
  exit 2
fi
gp_lc=$(printf '%s' "$GPASS" | tr '[:upper:]' '[:lower:]')
weak=0
[ "${#GPASS}" -lt 8 ] && weak=1
case "$gp_lc" in change_me*) weak=1 ;; esac
# A single all-alphabetic lower-case word is the shape both measured defects had.
case "$gp_lc" in [a-z]*) [ "$gp_lc" = "$GPASS" ] && [ "${#GPASS}" -lt 12 ] && weak=1 ;; esac
if [ "$weak" -eq 1 ]; then
  fail "C1: the injected Grafana administrator credential is weak or a product default (value redacted)"
  FAILURES=$((FAILURES + 1))
else
  pass "C1: the injected Grafana administrator credential passes the strength rules"
fi

login_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST \
    -H 'Content-Type: application/json' \
    --data "$(jq -n --arg u "$1" --arg p "$2" '{user:$u,password:$p}')" \
    "http://127.0.0.1:${GPORT}/login" 2>/dev/null
}

# C2 — the RUNNING instance actually uses the injected value.
g_ok=$(login_status "$GUSER" "$GPASS")
crc=$?
if [ "$crc" -ne 0 ] || [ -z "$g_ok" ] || [ "$g_ok" = "000" ]; then
  void "Grafana not reachable on 127.0.0.1:${GPORT} (curl rc=${crc}, status=${g_ok:-<empty>}) — cannot measure C"
  exit 2
fi
if [ "$g_ok" = "200" ]; then
  pass "C2: the running Grafana accepts the injected administrator credential (HTTP ${g_ok})"
else
  fail "C2: the running Grafana does NOT accept the injected administrator credential (HTTP ${g_ok}) — the live password is something other than the configured one, so the configuration is decorative"
  FAILURES=$((FAILURES + 1))
fi

# C3 — instrument validity: the endpoint must be able to say no.
g_bad=$(login_status "$GUSER" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')")
if [ "$g_bad" = "200" ]; then
  fail "C3: the running Grafana accepted a RANDOM credential (HTTP ${g_bad}) — authentication is not discriminating, so C2 proves nothing"
  FAILURES=$((FAILURES + 1))
else
  pass "C3: the running Grafana rejects a random credential (HTTP ${g_bad}) — C2's acceptance is meaningful"
fi

echo ""
echo "========================================================"
echo "D. Broker holds no account beyond the injected one"
echo "========================================================"

RUSER="${RABBITMQ_DEFAULT_USER:-}"
RPASS="${RABBITMQ_DEFAULT_PASS:-}"
if [ -z "$RUSER" ] || [ -z "$RPASS" ] || [ "$RPASS" = "placeholder" ]; then
  void "broker credentials are not available to this run — cannot measure D"
  exit 2
fi

users_json=$(curl -s --max-time 10 -u "${RUSER}:${RPASS}" "http://127.0.0.1:15672/api/users" 2>/dev/null)
drc=$?
if [ "$drc" -ne 0 ] || [ -z "$users_json" ]; then
  void "broker management API not reachable on 127.0.0.1:15672 (curl rc=${drc}) — cannot measure D"
  exit 2
fi
user_names=$(jq -r '.[].name' <<< "$users_json" 2>/dev/null)
jrc=$?
if [ "$jrc" -ne 0 ] || [ -z "$user_names" ]; then
  void "broker user list unparseable or EMPTY — cannot measure D (rc=${jrc})"
  exit 2
fi
extra=$(grep -vxF "$RUSER" <<< "$user_names")
extra_count=$(printf '%s' "$extra" | grep -c . )
if [ "$extra_count" -ne 0 ]; then
  fail "D: broker holds ${extra_count} account(s) beyond the injected one — a product-default account may have been reintroduced:"
  printf '       %s\n' "$extra"
  FAILURES=$((FAILURES + 1))
else
  pass "D: broker holds exactly one account, the injected one (product defaults absent)"
fi

echo ""
if [ "$FAILURES" -eq 0 ]; then
  pass "check-infra-exposure: all assertions passed"
  exit 0
fi
fail "check-infra-exposure: ${FAILURES} assertion(s) failed"
exit 1
