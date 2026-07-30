#!/usr/bin/env bash
# check-container-config-drift.sh — a RUNNING container must match the compose file
# that declares it.
#
# WHY THIS EXISTS — TS-16, and it had a live instance nobody had looked for
#
#   docs/ops/terminal-states.yaml TS-16 says it outright:
#
#     "check-runtime-freshness.sh compares BUILT services against the source they are
#      built from. A service running a third-party image whose COMPOSE CONFIG changed
#      is outside its scope entirely, so the running container can contradict the
#      committed file indefinitely with both parity gates green."
#
#   Its detection.alert was null and its operator_action was a manual per-service
#   `docker inspect` comparison. Nobody had run it. Run once by hand on 2026-07-29 it
#   found, immediately:
#
#     jtoye-redis-exporter  healthcheck ["CMD","wget","--spider","-q",...]  FailingStreak 1367
#     infra/monitoring/docker-compose.monitoring.yml declares NO healthcheck, and says why:
#       "the redis_exporter image is scratch-based (no shell/wget), so an exec-style
#        check can never pass and the container showed permanently unhealthy"
#
#   That comment landed 2026-07-07 in 7dcaf93. The container started 2026-07-29 and
#   still carried the removed healthcheck — `start`ed, never recreated, so a fix that
#   had existed for three weeks had never once been in effect. Nothing was broken; the
#   container simply reported `unhealthy` continuously, which is worse than useless
#   because it is a permanently-red signal everyone learns to ignore. It sat on the
#   exporter that RedisDown depends on (issue #342 item 5).
#
# WHAT IS COMPARED, AND WHY THIS SET — every one of these was MEASURED against the
# live stack before being included, because a property that false-positives makes the
# whole gate noise:
#
#   D-1  healthcheck.test   the property that found the live instance
#   D-2  restart policy     silent on drift, and decides whether an outage self-heals
#   D-3  image reference    NON-BUILT services only (see below)
#   D-4  network attachment the property that found the SECOND live instance —
#                           jtoye-ollama running, healthy, and on NO network at all
#                           (2026-07-30). D-1..D-3 all matched: every declared FIELD
#                           was correct, and the fault was in the runtime attachment.
#
#   NOT compared, with reasons, so the next person does not have to rediscover them:
#     environment  — `.Config.Env` merges the IMAGE's own ENV defaults with compose's,
#                    so a correct container shows dozens of extra vars. Comparable only
#                    with a per-image baseline, which is a bigger mechanism than this.
#     ports        — declared short syntax ("9090:9090") vs .HostConfig.PortBindings'
#                    nested map needs a normaliser per syntax form; deferred rather
#                    than shipped half-working.
#     volumes      — same shape problem, plus compose rewrites relative paths.
#
#   D-3 EXCLUDES BUILT SERVICES DELIBERATELY. For core-java/edge-go/frontend/mcp-server
#   the declared `image:` is a build output, and the running .Config.Image is the built
#   tag — measured, they disagree on a CORRECT stack (edge-go and frontend both reported
#   image:DRIFT in the first probe). Those four are check-runtime-freshness.sh's job,
#   which compares them against the SOURCE they build from. Two gates, one boundary,
#   no overlap.
#
# EXIT CODES — uniform with the other ops gates
#   0 = every running declared service matches · 1 = drift · 2 = VOID (cannot evaluate)
#
#   VOID on: missing docker/python3/PyYAML · a compose file that will not render ·
#   ZERO services discovered in a declared file · ZERO of a file's services running.
#   "Found nothing" is never "clean".
#
#   A declared service that is NOT running is REPORTED and counted, never silently
#   skipped — but it is not drift on its own, because optional and profile-gated
#   services legitimately sit stopped. If NONE of a file's services are running the
#   stack is down and the answer is VOID, not a clean bill.
#
# WHY THIS IS NOT IN CI
#   Same reason as check-runtime-freshness.sh and check-alert-liveness.sh: a CI runner
#   has no containers, so this could only ever VOID there, and a permanently-VOID job
#   trains people to add `|| true`. Run it against a live stack; its exit code belongs
#   in the phase-close record alongside the other two.
#
# USAGE
#   bash scripts/check-container-config-drift.sh
#   COMPOSE_FILES="a.yml b.yml" bash scripts/check-container-config-drift.sh
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || { echo "VOID: cannot cd to repo root" >&2; exit 2; }

void() { echo "VOID: $*" >&2; exit 2; }

# Declared surface — never inferred by globbing. docker-compose.frontend-3100.yml is
# deliberately absent: it is a one-off audit harness (see the port-3100 deep audit),
# not part of the canonical local runtime.
DEFAULT_COMPOSE_FILES="docker-compose.full-stack.yml
infra/monitoring/docker-compose.monitoring.yml"
COMPOSE_FILES="${COMPOSE_FILES:-$DEFAULT_COMPOSE_FILES}"

ENV_FILE="${ENV_FILE:-.env}"

for t in docker python3; do
  command -v "$t" >/dev/null 2>&1 || void "$t not on PATH — a check that cannot run is not a check that passed"
done
[ -r "$ENV_FILE" ] || void "$ENV_FILE not readable — compose interpolation would silently render different values"

echo "check-container-config-drift  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"

TOTAL_DRIFT=0
TOTAL_MATCH=0
TOTAL_ABSENT=0
FILES_SEEN=0

while IFS= read -r cf; do
  [ -z "$cf" ] && continue
  [ -f "$cf" ] || void "declared compose file '$cf' does not exist — fix the list rather than letting it skip"
  FILES_SEEN=$((FILES_SEEN + 1))
  echo "== $cf =="

  rendered=$(docker compose --env-file "$ENV_FILE" -f "$cf" config 2>/dev/null) \
    || void "cannot render $cf — an unrenderable compose file is VOID, never clean"

  # service -> container id, resolved through compose itself. NOT `container_name or
  # service`: core-java declares no container_name and really runs as
  # jtoye_oaas_2026-core-java-1, which a naive guess reports as NOT RUNNING.
  ids=$(docker compose --env-file "$ENV_FILE" -f "$cf" ps -aq 2>/dev/null)

  # Everything else is done in python: the values being compared are JSON arrays and
  # maps, and a delimited shell format cannot carry them. The first probe used '|' and
  # crashed on a healthcheck whose own command contains a pipe.
  out=$(python3 -c '
import sys, json, subprocess
try:
    import yaml
except ImportError:
    sys.stderr.write("PyYAML not importable\n"); sys.exit(3)

cf, env_file = sys.argv[1], sys.argv[2]
rendered = sys.stdin.read()
try:
    cfg = yaml.safe_load(rendered)
except Exception as e:
    sys.stderr.write("unparseable render: %s\n" % str(e).splitlines()[0]); sys.exit(3)

services = (cfg or {}).get("services") or {}
if not services:
    sys.stderr.write("zero services\n"); sys.exit(4)

INSPECT = ("{{if .Config.Healthcheck}}{{json .Config.Healthcheck.Test}}{{else}}null{{end}}\n"
           "{{.HostConfig.RestartPolicy.Name}}\n"
           "{{.Config.Image}}\n"
           "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}")

drift = match = absent = 0
for name in sorted(services):
    s = services[name] or {}
    cid = subprocess.run(["docker","compose","--env-file",env_file,"-f",cf,"ps","-q",name],
                         capture_output=True, text=True).stdout.strip().splitlines()
    cid = cid[0] if cid else ""
    if not cid:
        print("  %-30s NOT RUNNING (declared, no container)" % name); absent += 1; continue
    r = subprocess.run(["docker","inspect",cid,"--format",INSPECT], capture_output=True, text=True)
    if r.returncode != 0:
        print("  %-30s NOT RUNNING (container id vanished)" % name); absent += 1; continue
    # NOTE: .strip() would eat the 4th field entirely when a container is attached to NO
    # network -- the exact state D-4 exists to catch -- so strip only the trailing newline
    # and pad, rather than letting the empty field vanish into a short-parts sys.exit(3).
    parts = r.stdout.rstrip("\n").split("\n")
    if len(parts) < 3:
        sys.stderr.write("inspect returned %d fields for %s\n" % (len(parts), name)); sys.exit(3)
    while len(parts) < 4:
        parts.append("")
    run_hc, run_restart, run_image = parts[0].strip(), parts[1].strip(), parts[2].strip()
    run_nets = parts[3].split()

    problems = []

    # D-1 healthcheck.test
    #   NOT `{{json .Config.Healthcheck.Test}}` unguarded: that template ERRORS when
    #   Healthcheck is nil, and the error is indistinguishable from "container absent".
    #   The first prototype reported jtoye-postgres-exporter as NOT RUNNING while it was
    #   up 11 hours -- a container with NO healthcheck, the correct state, read as a
    #   missing container. Hence the {{if}} guard above.
    want_hc = (s.get("healthcheck") or {}).get("test")
    got_hc = json.loads(run_hc)
    if want_hc != got_hc:
        problems.append(("healthcheck", json.dumps(want_hc), json.dumps(got_hc)))

    # D-2 restart policy. compose omits it -> docker reports "no".
    want_restart = s.get("restart") or "no"
    if want_restart != run_restart:
        problems.append(("restart", want_restart, run_restart))

    # D-3 image, NON-BUILT services only -- see the header.
    if not s.get("build"):
        want_image = s.get("image")
        if want_image and want_image != run_image:
            problems.append(("image", want_image, run_image))

    # D-4 network ATTACHMENT. Added 2026-07-30 after a live instance this gate reported
    # as MATCH while the container sat on no network at all.
    #
    #   jtoye-ollama: compose declares `networks: [jtoye-network]` and .HostConfig
    #   .NetworkMode was set to it, yet .NetworkSettings.Networks was EMPTY. Docker had
    #   failed "bind host port 0.0.0.0:11434/tcp: address already in use" (a host-native
    #   `ollama serve` owns 11434), aborted networking setup, and left the container
    #   running-and-detached. Nothing detected it for weeks: `docker ps` said healthy
    #   because the healthcheck is `ollama list` run INSIDE the container, which never
    #   touches the network; D-1..D-3 all matched because every compared FIELD was
    #   correct. The failure was in the runtime attachment, not in any declared value.
    #   Consequence: core-java got `bad address 'ollama:11434'` and the model volume
    #   stayed empty (24K, no manifests) -- an AI feature silently dead behind green.
    #
    # Compose renders each declared name into the project-prefixed real network, so
    # compare by SUFFIX rather than demanding an exact string: a service declaring
    # `jtoye-network` legitimately runs on `<project>_jtoye-network`.
    want_nets = s.get("networks") or {}
    want_names = list(want_nets.keys()) if isinstance(want_nets, dict) else list(want_nets)
    if want_names:
        if not run_nets:
            problems.append(("networks", ",".join(want_names),
                             "<NONE -- container is attached to no network>"))
        else:
            for wn in want_names:
                if not any(rn == wn or rn.endswith("_" + wn) for rn in run_nets):
                    problems.append(("networks", wn, ",".join(run_nets)))

    if problems:
        drift += 1
        print("  %-30s DRIFT" % name)
        for what, want, got in problems:
            print("      %-12s declared: %s" % (what, want))
            print("      %-12s running : %s" % ("", got))
    else:
        match += 1
        print("  %-30s MATCH" % name)

print("SUMMARY %d %d %d" % (match, drift, absent))
' "$cf" "$ENV_FILE" <<< "$rendered")
  rc=$?
  case "$rc" in
    0) : ;;
    4) void "$cf rendered ZERO services — the parser broke or the file is empty" ;;
    *) void "cannot compare $cf (see message above)" ;;
  esac

  printf '%s\n' "$out" | grep -v '^SUMMARY '
  read -r m d a <<< "$(printf '%s\n' "$out" | sed -n 's/^SUMMARY //p')"
  [ -n "${m:-}" ] || void "$cf produced no summary line — refusing to count an unparsed result as clean"
  if [ "$((m + d))" -eq 0 ]; then
    void "$cf has $a declared service(s) and NONE running — a stopped stack cannot be graded, and that is VOID rather than clean"
  fi
  echo "  -- match=$m drift=$d not-running=$a"
  TOTAL_MATCH=$((TOTAL_MATCH + m)); TOTAL_DRIFT=$((TOTAL_DRIFT + d)); TOTAL_ABSENT=$((TOTAL_ABSENT + a))
done <<< "$(printf '%s\n' $COMPOSE_FILES)"

[ "$FILES_SEEN" -gt 0 ] || void "ZERO compose files discovered from the declared set"

echo
echo "  files=$FILES_SEEN  compared=$TOTAL_MATCH  drift=$TOTAL_DRIFT  not-running=$TOTAL_ABSENT"
echo "  NOTE  built services (core-java, edge-go, frontend, mcp-server) are compared on"
echo "        healthcheck and restart only. Their IMAGE is check-runtime-freshness.sh's."

if [ "$TOTAL_DRIFT" -gt 0 ]; then
  echo >&2
  echo "FAILED: $TOTAL_DRIFT running container(s) do not match the compose file that declares them." >&2
  echo "Recreate the named service(s) — \`restart\` does NOT re-read the config, and \`start\` does not either:" >&2
  echo "  docker compose --env-file $ENV_FILE -f <file> up -d --force-recreate <service>" >&2
  exit 1
fi

echo "PASS: $TOTAL_MATCH running container(s) match their compose declaration across $FILES_SEEN file(s) ($TOTAL_ABSENT declared but not running)."
