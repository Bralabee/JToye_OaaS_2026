#!/usr/bin/env bash
# check-alertmanager-render.sh — the ONE Alertmanager corpus renders correctly for every
# runtime that mounts it (plan 29-24, DPLY-03 part B).
#
# WHY THIS EXISTS
#
#   infra/monitoring/alertmanager/{alertmanager.yml.tmpl,entrypoint.sh} are mounted by the
#   compose stack AND (as byte copies, k8s/copy-parity.conf) by staging Kubernetes. Plan
#   29-24 adds two OPTIONAL composed blocks to them — SMTP auth for the Zoho smarthost and
#   extra email_configs (the Mailpit sink) in receiver email-default. Two things must then
#   hold at once, and neither is visible from the template text:
#
#     1. COMPOSE IS UNCHANGED. With the compose env the render is byte-identical to what
#        compose rendered BEFORE this change. The comparison is against a committed golden
#        (compose-render.golden.yml) produced by `--write-golden` from the UNMODIFIED
#        template and entrypoint, in the commit that introduced this gate — never a file
#        against itself. A missing golden is VOID, not a pass.
#     2. STAGING RENDERS WHAT IT CLAIMS: auth on the Zoho integration only, a second
#        email_config to Mailpit with a CA file, the alertname-only mute, amtool-valid, and
#        no password string anywhere in the render.
#
# HOW
#
#   The real entrypoint runs inside the pinned prom/alertmanager image (the pin is READ from
#   infra/monitoring/docker-compose.monitoring.yml, so this gate cannot test a different
#   Alertmanager than compose runs), with the template and entrypoint mounted exactly where
#   compose mounts them, `--network none`, and ALERTMANAGER_RENDER_ONLY=1 (render, check,
#   print the path, exit before exec). An entrypoint that does not honour that switch is run
#   under `timeout`; it counts as having rendered only if amtool's own SUCCESS line is in its
#   log — that is how the golden is produced from the pre-change entrypoint. The image ships
#   a default /etc/alertmanager/alertmanager.yml, so it is deleted first: a failed render
#   must never be read as the image's own config.
#
#   Structural assertions are made on the parsed YAML (python3 + PyYAML — the runner's in
#   CI, the jtoye-ops env on a workstation), never by grepping the text.
#
# MODES
#   (none)          run every arm against infra/monitoring/alertmanager
#   --selftest      planted holes that each arm must catch (an unknown __TOKEN__ in the
#                   template, a drifted golden, the auth line removed, a widened mute
#                   validator, a password in a render) — a gate that cannot fail goes red
#   --write-golden  render the CURRENT files with the compose env into the golden; refuses
#                   (rc 2) when the golden already exists, so it cannot be used to paper over
#                   a compose change
#
#   RENDER_GATE_FORMAT=tap prints TAP (for the TDD RED-evidence classifier).
#
# EXIT: 0 pass · 1 an arm failed · 2 VOID (no docker, no image, no PyYAML, no golden)

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AM_DIR="${AM_DIR:-$REPO_ROOT/infra/monitoring/alertmanager}"
COMPOSE_FILE="$REPO_ROOT/infra/monitoring/docker-compose.monitoring.yml"
GOLDEN="${GOLDEN:-$AM_DIR/compose-render.golden.yml}"
FORMAT="${RENDER_GATE_FORMAT:-text}"
MODE="run"
case "${1:-}" in
  "") ;;
  --selftest) MODE="selftest" ;;
  --write-golden) MODE="write-golden" ;;
  *) echo "USAGE: $0 [--selftest|--write-golden]" >&2; exit 2 ;;
esac

void() { echo "VOID: $*" >&2; exit 2; }

command -v docker >/dev/null 2>&1 || void "docker not found — the render can only be proven inside the pinned image"
command -v python3 >/dev/null 2>&1 || void "python3 not found — structural assertions need PyYAML"
python3 -c 'import yaml' >/dev/null 2>&1 || void "python3 has no PyYAML (on a workstation: conda activate jtoye-ops)"
command -v sha256sum >/dev/null 2>&1 || void "sha256sum not found"
[ -f "$COMPOSE_FILE" ] || void "compose file not found at $COMPOSE_FILE"

# The pin is compose's, read — never restated here.
AM_IMAGE="$(command grep -oE 'image:[[:space:]]*prom/alertmanager:[^[:space:]]+' "$COMPOSE_FILE" | head -n 1 | sed -E 's/^image:[[:space:]]*//')"
[ -n "$AM_IMAGE" ] || void "no prom/alertmanager image pin found in $COMPOSE_FILE"
if ! docker image inspect "$AM_IMAGE" >/dev/null 2>&1; then
  docker pull -q "$AM_IMAGE" >/dev/null 2>&1 || void "cannot pull $AM_IMAGE"
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/am-render.XXXXXX")" || void "mktemp failed"
trap 'rm -rf "$WORK"' EXIT

# A per-run password nobody chose. It must never appear in a render or a log.
TESTPW="amr-pw-$(date +%s%N)-$$"
STAGING_SMARTHOST="smtp.zoho.eu:587"
STAGING_USER="alerts@olajay.co.uk"
STAGING_TO="owner-inbox@example.invalid"
STAGING_PWFILE="/mnt/secrets-store/alertmanager-smtp-credentials--password"
STAGING_SINK="mailpit.jtoye-staging.svc.cluster.local:1025"
STAGING_CA="/cluster-ca/ca.crt"

# Files the staging env names, mounted where the staging pod has them. amtool reads neither
# (measured on v0.34.1: a config naming /nonexistent paths checks SUCCESS), so their content
# is a stand-in; the entrypoint is what must refuse an unreadable one.
mkdir -p "$WORK/secrets" "$WORK/ca"
printf '%s' "$TESTPW" > "$WORK/secrets/alertmanager-smtp-credentials--password"
printf '%s\n' '-----BEGIN CERTIFICATE-----' 'c3RhbmQtaW4gY2x1c3RlciBDQSBmb3IgdGhlIHJlbmRlciBnYXRl' '-----END CERTIFICATE-----' > "$WORK/ca/ca.crt"
chmod 755 "$WORK" "$WORK/secrets" "$WORK/ca"
chmod 644 "$WORK/secrets/"* "$WORK/ca/"*

COMPOSE_ENV=(
  "ALERTMANAGER_SMTP_SMARTHOST=mailhog:1025"
  "ALERTMANAGER_SMTP_FROM=alerts@jtoye.local"
  "ALERTMANAGER_SMTP_TO=ops@jtoye.local"
  "ALERTMANAGER_SMTP_REQUIRE_TLS=false"
  "ALERTMANAGER_SLACK_WEBHOOK_URL="
  "ALERTMANAGER_SLACK_CHANNEL="
  "ALERTMANAGER_MUTE_ALERTNAMES="
  "ALERTMANAGER_SMTP_USERNAME="
  "ALERTMANAGER_SMTP_PASSWORD="
)
STAGING_ENV=(
  "ALERTMANAGER_SMTP_SMARTHOST=$STAGING_SMARTHOST"
  "ALERTMANAGER_SMTP_REQUIRE_TLS=true"
  "ALERTMANAGER_SMTP_FROM=$STAGING_USER"
  "ALERTMANAGER_SMTP_TO=$STAGING_TO"
  "ALERTMANAGER_SMTP_USERNAME=$STAGING_USER"
  "ALERTMANAGER_SMTP_PASSWORD_FILE=$STAGING_PWFILE"
  "ALERTMANAGER_SINK_SMARTHOST=$STAGING_SINK"
  "ALERTMANAGER_SINK_CA_FILE=$STAGING_CA"
  "ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated"
)

# render <outdir> <srcdir> <env...> — runs the entrypoint in the image; leaves
#   outdir/{rc,log,rendered.yml?,amtool.rc,amtool.log,tmpfiles}
render() {
  local out="$1" src="$2"; shift 2
  mkdir -p "$out"; chmod 777 "$out"
  local envargs=(-e ALERTMANAGER_RENDER_ONLY=1)
  local kv
  for kv in "$@"; do envargs+=(-e "$kv"); done
  # shellcheck disable=SC2016
  docker run --rm --network none --entrypoint sh "${envargs[@]}" \
    -v "$src/alertmanager.yml.tmpl:/etc/alertmanager/alertmanager.yml.tmpl:ro" \
    -v "$src/entrypoint.sh:/etc/alertmanager/entrypoint.sh:ro" \
    -v "$WORK/secrets:/mnt/secrets-store:ro" \
    -v "$WORK/ca:/cluster-ca:ro" \
    -v "$out:/out" \
    "$AM_IMAGE" -c '
      rm -f /etc/alertmanager/alertmanager.yml
      timeout -s TERM 8 sh /etc/alertmanager/entrypoint.sh >/out/log 2>&1
      echo $? >/out/rc
      if [ -f /etc/alertmanager/alertmanager.yml ]; then
        cp /etc/alertmanager/alertmanager.yml /out/rendered.yml
        /bin/amtool check-config /out/rendered.yml >/out/amtool.log 2>&1
        echo $? >/out/amtool.rc
      fi
      for f in /tmp/* /tmp/.[!.]*; do
        [ -f "$f" ] && echo "$(stat -c %a "$f") $(sha256sum "$f" | cut -d" " -f1) $f"
      done >/out/tmpfiles 2>/dev/null
      exit 0' >/dev/null 2>&1 || return 2
  [ -f "$out/rc" ] || return 2
  return 0
}

# A render COMPLETED when the entrypoint exited 0 (RENDER_ONLY honoured), or when its own
# `amtool check-config` printed SUCCESS for the rendered path before it exec'd alertmanager
# (an entrypoint without the switch: the pre-change one, which the golden is taken from).
# The exec'd alertmanager's own exit status is NOT the render's: under --network none it
# exits 1 at once ("no private IP address found" for the gossip mesh, measured on v0.34.1),
# and with a network it would run until the timeout (143).
completed() {
  local out="$1" rc
  rc="$(cat "$out/rc")"
  [ -f "$out/rendered.yml" ] || return 1
  [ "$rc" = 0 ] && return 0
  command grep -qF "Checking '/etc/alertmanager/alertmanager.yml'  SUCCESS" "$out/log"
}

no_secret() { # no_secret <file...> — the per-run password appears in none of them
  local f
  for f in "$@"; do
    [ -f "$f" ] || continue
    if command grep -qF -- "$TESTPW" "$f"; then echo "password string present in $(basename "$f")"; return 1; fi
  done
  return 0
}

PY_STAGING='
import sys, yaml, os
doc = yaml.safe_load(open(sys.argv[1]))
e = os.environ
errs = []
g = doc.get("global") or {}
leaked = sorted(k for k in g if k.startswith("smtp_auth_"))
if leaked: errs.append("global carries %s: every email_config would inherit it, the Mailpit sink included" % leaked)
rcv = {r.get("name"): r for r in (doc.get("receivers") or [])}
ecs = (rcv.get("email-default") or {}).get("email_configs") or []
if len(ecs) != 2: errs.append("email-default has %d email_configs, want 2" % len(ecs))
if ecs:
    p = ecs[0]
    if p.get("auth_username") != e["X_USER"]: errs.append("email_configs[0].auth_username=%r" % p.get("auth_username"))
    if p.get("auth_password_file") != e["X_PWFILE"]: errs.append("email_configs[0].auth_password_file=%r" % p.get("auth_password_file"))
    if "auth_password" in p: errs.append("email_configs[0] carries an inline auth_password")
if len(ecs) >= 2:
    s = ecs[1]
    if s.get("smarthost") != e["X_SINK"]: errs.append("email_configs[1].smarthost=%r" % s.get("smarthost"))
    if s.get("require_tls") is not True: errs.append("email_configs[1].require_tls=%r" % s.get("require_tls"))
    if ((s.get("tls_config") or {}).get("ca_file")) != e["X_CA"]: errs.append("email_configs[1].tls_config.ca_file=%r" % (s.get("tls_config") or {}).get("ca_file"))
    if s.get("to") != e["X_TO"]: errs.append("email_configs[1].to=%r (want SMTP_TO)" % s.get("to"))
    a = sorted(k for k in s if k.startswith("auth_"))
    if a: errs.append("the sink email_config carries %s" % a)
routes = (doc.get("route") or {}).get("routes") or []
mute = [r for r in routes if r.get("receiver") == "mute-null"]
if len(mute) != 1 or mute[0].get("matchers") != ["alertname=~\"^(NoOrdersCreated)$\""]:
    errs.append("mute route is %r, want exactly one alertname=~\"^(NoOrdersCreated)$\"" % mute)
if (rcv.get("mute-null") or {}).keys() - {"name"}: errs.append("mute-null has integrations")
print("; ".join(errs))
sys.exit(1 if errs else 0)
'

PY_PWFILE_PATH='
import sys, yaml
doc = yaml.safe_load(open(sys.argv[1]))
rcv = {r.get("name"): r for r in (doc.get("receivers") or [])}
ecs = (rcv.get("email-default") or {}).get("email_configs") or []
print((ecs[0].get("auth_password_file") or "") if ecs else "")
'

# ------------------------------------------------------------------ arms
# Each arm: arm_<name> <srcdir> [golden]  -> rc 0 pass / 1 fail / 2 VOID, reason on stdout.
arm_compose_identical() {
  local src="$1" golden="${2:-$GOLDEN}" o="$WORK/o-compose-$RANDOM"
  [ -f "$golden" ] || { echo "golden missing at $golden"; return 2; }
  render "$o" "$src" "${COMPOSE_ENV[@]}" || { echo "docker run failed"; return 2; }
  completed "$o" || { echo "render did not complete (rc $(cat "$o/rc")): $(tail -n 3 "$o/log" | tr '\n' ' ')"; return 1; }
  [ "$(cat "$o/amtool.rc" 2>/dev/null)" = 0 ] || { echo "amtool check-config failed: $(tr '\n' ' ' < "$o/amtool.log")"; return 1; }
  local a b; a="$(sha256sum < "$o/rendered.yml" | cut -d' ' -f1)"; b="$(sha256sum < "$golden" | cut -d' ' -f1)"
  echo "sha256 render=$a golden=$b"
  [ "$a" = "$b" ] || return 1
}

arm_compose_credentials_without_tls() {
  local src="$1" golden="${2:-$GOLDEN}" o="$WORK/o-credsnotls-$RANDOM"
  [ -f "$golden" ] || { echo "golden missing at $golden"; return 2; }
  render "$o" "$src" "${COMPOSE_ENV[@]}" "ALERTMANAGER_SMTP_USERNAME=$STAGING_USER" "ALERTMANAGER_SMTP_PASSWORD=$TESTPW" \
    || { echo "docker run failed"; return 2; }
  completed "$o" || { echo "render did not complete (rc $(cat "$o/rc")): $(tail -n 3 "$o/log" | tr '\n' ' ')"; return 1; }
  local leak; leak="$(no_secret "$o/rendered.yml" "$o/log")" || { echo "$leak"; return 1; }
  [ ! -s "$o/tmpfiles" ] || { echo "a /tmp file was written without TLS: $(cut -d' ' -f1,3 < "$o/tmpfiles" | tr '\n' ' ')"; return 1; }
  local a b; a="$(sha256sum < "$o/rendered.yml" | cut -d' ' -f1)"; b="$(sha256sum < "$golden" | cut -d' ' -f1)"
  echo "sha256 render=$a golden=$b (credentials set, REQUIRE_TLS=false)"
  [ "$a" = "$b" ] || return 1
}

arm_staging() {
  local src="$1" o="$WORK/o-staging-$RANDOM"
  render "$o" "$src" "${STAGING_ENV[@]}" || { echo "docker run failed"; return 2; }
  completed "$o" || { echo "render did not complete (rc $(cat "$o/rc")): $(tail -n 3 "$o/log" | tr '\n' ' ')"; return 1; }
  [ "$(cat "$o/amtool.rc" 2>/dev/null)" = 0 ] || { echo "amtool check-config failed: $(tr '\n' ' ' < "$o/amtool.log")"; return 1; }
  local leak; leak="$(no_secret "$o/rendered.yml" "$o/log")" || { echo "$leak"; return 1; }
  local why rc
  why="$(X_USER="$STAGING_USER" X_PWFILE="$STAGING_PWFILE" X_SINK="$STAGING_SINK" X_CA="$STAGING_CA" X_TO="$STAGING_TO" \
    python3 -c "$PY_STAGING" "$o/rendered.yml" 2>&1)"; rc=$?
  [ "$rc" -le 1 ] || { echo "python3 could not read the render: $why"; return 2; }
  [ "$rc" -eq 0 ] || { echo "$why"; return 1; }
  command grep -qF -- "$STAGING_PWFILE" "$o/rendered.yml" || { echo "the password file path is not in the render"; return 1; }
  cp "$o/rendered.yml" "$WORK/staging-render.yml"
  echo "amtool SUCCESS; 2 email_configs; auth on [0] only; sink CA set; mute alertname-only; no password string"
}

arm_password_env_to_file() {
  local src="$1" o="$WORK/o-pwenv-$RANDOM"
  render "$o" "$src" "ALERTMANAGER_SMTP_SMARTHOST=$STAGING_SMARTHOST" "ALERTMANAGER_SMTP_REQUIRE_TLS=true" \
    "ALERTMANAGER_SMTP_USERNAME=$STAGING_USER" "ALERTMANAGER_SMTP_PASSWORD=$TESTPW" || { echo "docker run failed"; return 2; }
  completed "$o" || { echo "render did not complete (rc $(cat "$o/rc")): $(tail -n 3 "$o/log" | tr '\n' ' ')"; return 1; }
  [ "$(cat "$o/amtool.rc" 2>/dev/null)" = 0 ] || { echo "amtool check-config failed"; return 1; }
  local leak; leak="$(no_secret "$o/rendered.yml" "$o/log")" || { echo "$leak"; return 1; }
  local path; path="$(python3 -c "$PY_PWFILE_PATH" "$o/rendered.yml" 2>/dev/null)"
  case "$path" in /tmp/*) ;; *) echo "email_configs[0].auth_password_file is '$path', want a /tmp file"; return 1 ;; esac
  local want; want="$(printf '%s' "$TESTPW" | sha256sum | cut -d' ' -f1)"
  local line; line="$(command grep -F " $path" "$o/tmpfiles" | head -n 1)"
  [ -n "$line" ] || { echo "$path does not exist in the container"; return 1; }
  [ "${line%% *}" = 600 ] || { echo "$path has mode ${line%% *}, want 600"; return 1; }
  [ "$(echo "$line" | cut -d' ' -f2)" = "$want" ] || { echo "$path does not hold the password"; return 1; }
  echo "password written to $path mode 600, referenced as auth_password_file, never inlined"
}

refused() { # refused <outdir> <expected-log-fragment>
  local o="$1" frag="$2" rc
  rc="$(cat "$o/rc")"
  [ "$rc" = 1 ] || { echo "entrypoint rc $rc, want 1 (refused)"; return 1; }
  [ ! -f "$o/rendered.yml" ] || { echo "a config was rendered despite the refusal"; return 1; }
  command grep -qF -- "$frag" "$o/log" || { echo "log lacks '$frag': $(tail -n 2 "$o/log" | tr '\n' ' ')"; return 1; }
  local leak; leak="$(no_secret "$o/log")" || { echo "$leak"; return 1; }
}

arm_mute_matcher_refused() {
  local src="$1" o="$WORK/o-mute-$RANDOM"
  render "$o" "$src" "${COMPOSE_ENV[@]}" "ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated,severity=info" || { echo "docker run failed"; return 2; }
  refused "$o" "not a valid alertname" && echo "FATAL on 'severity=info' (alertname-only preserved)"
}

arm_password_and_file_refused() {
  local src="$1" o="$WORK/o-both-$RANDOM"
  render "$o" "$src" "ALERTMANAGER_SMTP_SMARTHOST=$STAGING_SMARTHOST" "ALERTMANAGER_SMTP_REQUIRE_TLS=true" \
    "ALERTMANAGER_SMTP_USERNAME=$STAGING_USER" "ALERTMANAGER_SMTP_PASSWORD=$TESTPW" "ALERTMANAGER_SMTP_PASSWORD_FILE=$STAGING_PWFILE" \
    || { echo "docker run failed"; return 2; }
  refused "$o" "FATAL" && echo "FATAL on PASSWORD and PASSWORD_FILE together"
}

arm_username_without_password_refused() {
  local src="$1" o="$WORK/o-nopw-$RANDOM"
  render "$o" "$src" "ALERTMANAGER_SMTP_SMARTHOST=$STAGING_SMARTHOST" "ALERTMANAGER_SMTP_REQUIRE_TLS=true" \
    "ALERTMANAGER_SMTP_USERNAME=$STAGING_USER" || { echo "docker run failed"; return 2; }
  refused "$o" "FATAL" && echo "FATAL on a username with no password"
}

arm_unreadable_ca_refused() {
  local src="$1" o="$WORK/o-ca-$RANDOM"
  render "$o" "$src" "${STAGING_ENV[@]}" "ALERTMANAGER_SINK_CA_FILE=/cluster-ca/absent.crt" || { echo "docker run failed"; return 2; }
  refused "$o" "FATAL" && echo "FATAL on an unreadable sink CA file"
}

arm_render_only() {
  local src="$1" o="$WORK/o-ro-$RANDOM"
  render "$o" "$src" "${COMPOSE_ENV[@]}" || { echo "docker run failed"; return 2; }
  [ "$(cat "$o/rc")" = 0 ] || { echo "entrypoint rc $(cat "$o/rc"), want 0 (ALERTMANAGER_RENDER_ONLY=1 must exit before exec)"; return 1; }
  command grep -q 'RENDER_ONLY' "$o/log" || { echo "no RENDER_ONLY line in the log"; return 1; }
  echo "rendered, checked and exited 0 before exec"
}

# ------------------------------------------------------------------ reporting
N=0; FAILS=0; VOIDS=0; TAP_LINES=()
report() { # report <name> <rc> <msg> [expect-fail]
  local name="$1" rc="$2" msg="$3" ok
  N=$((N + 1))
  if [ "$rc" -eq 2 ]; then VOIDS=$((VOIDS + 1)); ok=0
  elif [ "${4:-}" = expect-fail ]; then [ "$rc" -eq 1 ] && ok=1 || ok=0
  else [ "$rc" -eq 0 ] && ok=1 || ok=0
  fi
  [ "$ok" = 1 ] || FAILS=$((FAILS + 1))
  if [ "$FORMAT" = tap ]; then
    if [ "$ok" = 1 ]; then TAP_LINES+=("ok $N - $name")
    else TAP_LINES+=("not ok $N - $name" "  ---" "  message: '$(printf '%s' "$msg" | tr "'\n" '" ')'" "  ...")
    fi
  else
    if [ "$ok" = 1 ]; then echo "  PASS  $name — $msg"
    elif [ "$rc" -eq 2 ]; then echo "  VOID  $name — $msg"
    else echo "FAIL: $name — $msg"
    fi
  fi
}
run_arm() { local name="$1"; shift; local msg rc; msg="$("$@")"; rc=$?; report "$name" "$rc" "$msg" "${EXPECT:-}"; }

finish() {
  if [ "$FORMAT" = tap ]; then
    echo "TAP version 13"; echo "1..$N"; printf '%s\n' "${TAP_LINES[@]}"
  fi
  if [ "$VOIDS" -gt 0 ]; then echo "check-alertmanager-render: VOID ($VOIDS arm(s) could not be evaluated) [$1]" >&2; exit 2; fi
  if [ "$FAILS" -gt 0 ]; then echo "check-alertmanager-render: FAIL ($FAILS of $N) [$1]" >&2; exit 1; fi
  echo "check-alertmanager-render: PASS ($N/$N) [$1] image=$AM_IMAGE"
  exit 0
}

# ------------------------------------------------------------------ modes
if [ "$MODE" = write-golden ]; then
  [ ! -e "$GOLDEN" ] || void "$GOLDEN already exists — the golden records what compose rendered BEFORE a change; delete it explicitly (a reviewable diff) if it must be re-taken"
  o="$WORK/o-golden"
  render "$o" "$AM_DIR" "${COMPOSE_ENV[@]}" || void "docker run failed"
  completed "$o" || void "the compose render did not complete: $(tail -n 3 "$o/log" | tr '\n' ' ')"
  [ "$(cat "$o/amtool.rc")" = 0 ] || void "amtool rejected the compose render"
  cp "$o/rendered.yml" "$GOLDEN"
  echo "wrote $GOLDEN sha256=$(sha256sum < "$GOLDEN" | cut -d' ' -f1) from template=$(sha256sum < "$AM_DIR/alertmanager.yml.tmpl" | cut -c1-12) entrypoint=$(sha256sum < "$AM_DIR/entrypoint.sh" | cut -c1-12)"
  exit 0
fi

if [ "$MODE" = selftest ]; then
  [ -f "$GOLDEN" ] || void "golden missing at $GOLDEN"
  plant() { local d="$WORK/plant-$1"; mkdir -p "$d"; cp "$AM_DIR/alertmanager.yml.tmpl" "$AM_DIR/entrypoint.sh" "$d/"; chmod 755 "$d"; chmod 644 "$d/"*; echo "$d"; }
  [ "$FORMAT" = tap ] || echo "check-alertmanager-render --selftest  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
  d="$(plant token)"; printf '%s\n' '__PLANTED_UNKNOWN_TOKEN__' >> "$d/alertmanager.yml.tmpl"
  EXPECT=expect-fail run_arm "selftest-unknown-token-fails-compose-arm" arm_compose_identical "$d"
  d="$(plant golden)"; cp "$GOLDEN" "$WORK/drifted-golden.yml"; printf '#\n' >> "$WORK/drifted-golden.yml"
  EXPECT=expect-fail run_arm "selftest-drifted-golden-fails-compose-arm" arm_compose_identical "$d" "$WORK/drifted-golden.yml"
  d="$(plant auth)"; sed -i '/^__SMTP_AUTH_BLOCK__$/d' "$d/alertmanager.yml.tmpl"
  EXPECT=expect-fail run_arm "selftest-auth-line-removed-fails-staging-arm" arm_staging "$d"
  d="$(plant mute)"; sed -i "s/grep -qE '\^\[A-Za-z\]\[A-Za-z0-9_\]\*\\\$'/grep -qE '.'/" "$d/entrypoint.sh"
  if cmp -s "$d/entrypoint.sh" "$AM_DIR/entrypoint.sh"; then report "selftest-widened-mute-validator-fails-mute-arm" 2 "the plant changed nothing (anchor moved?)"
  else EXPECT=expect-fail run_arm "selftest-widened-mute-validator-fails-mute-arm" arm_mute_matcher_refused "$d"; fi
  printf 'auth_password: %s\n' "$TESTPW" > "$WORK/leaky.yml"
  msg="$(no_secret "$WORK/leaky.yml")"; rc=$?; report "selftest-password-in-render-detected" "$rc" "$msg" expect-fail
  finish selftest
fi

[ "$FORMAT" = tap ] || echo "check-alertmanager-render  ($(date -u +%Y-%m-%dT%H:%M:%SZ))  image=$AM_IMAGE"
run_arm compose-render-identical            arm_compose_identical "$AM_DIR"
run_arm compose-credentials-without-tls     arm_compose_credentials_without_tls "$AM_DIR"
run_arm staging-env                         arm_staging "$AM_DIR"
run_arm password-env-to-mode-600-file       arm_password_env_to_file "$AM_DIR"
run_arm mute-matcher-refused                arm_mute_matcher_refused "$AM_DIR"
run_arm password-and-file-refused           arm_password_and_file_refused "$AM_DIR"
run_arm username-without-password-refused   arm_username_without_password_refused "$AM_DIR"
run_arm unreadable-sink-ca-refused          arm_unreadable_ca_refused "$AM_DIR"
run_arm render-only-exits-before-exec       arm_render_only "$AM_DIR"
finish run
