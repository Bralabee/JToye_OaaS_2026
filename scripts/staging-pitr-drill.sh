#!/usr/bin/env bash
# staging-pitr-drill.sh — recover the STAGING database two ways and prove it by row count
# (Phase 29 plan 29-20; DPLY-04, #101; decision D-10; threats T-29-71..T-29-74).
#
# WHAT IT PROVES
#   ARM P  Provider point-in-time restore of jtoye-stg-pg into a NEW server
#          jtoye-stg-pg-drill-<RUN_ID>, at a time T0 chosen strictly between two marker
#          products written through the vendor API. The restored server must carry the
#          before-marker, not the after-marker, and per-table counts (products, orders,
#          customers, shops) equal to the source's counts at T0.
#   ARM A  The counterexample (D-10 as corrected on main by #954). On the RESTORED server a
#          scratch role jtoye_drill_norls (LOGIN, NOBYPASSRLS, SELECT only) dumps it:
#          a plain pg_dump ERRORS on the first RLS table; with --enable-row-security it
#          writes a PLAUSIBLE PARTIAL dump that PASSES the pipeline's own size floor and
#          `pg_restore --list` (the lines are read out of the backup image's script and
#          run unchanged), and that restores into scratch database drill_a to counts that
#          do NOT equal the source. The row count catches what the pipeline's checks pass.
#   ARM B  The same dump of the RESTORED server as jtoye_backup (BYPASSRLS; PITR carries
#          the role) passes the same checks and restores into drill_b to EQUAL counts.
#   ZERO   A zero-byte artefact is refused by the same size-floor line (the floor is not
#          vacuous).
#
# THE TWO SERVERS, NEVER INTERCHANGEABLE
#   SOURCE   = jtoye-stg-pg (live). Its only drill-time writes are the two marker products,
#              made through the vendor API as any vendor edit would be. Everything else
#              against it is a read by jtoye_backup.
#   RESTORED = jtoye-stg-pg-drill-<RUN_ID>. Every CREATE ROLE, CREATE DATABASE, DROP and
#              pg_restore goes through target_guard, an ALLOW-list that admits only a
#              jtoye-stg-pg-drill-<digits> host and refuses (rc 1, before any connection)
#              the SOURCE name or FQDN, an empty host, or anything else.
#
# THE TRAP
#   `trap cleanup EXIT` is installed near the top of this file, before any pod exists and
#   long before the restore is requested. RESTORE_REQUESTED is set immediately BEFORE the
#   request leaves this machine, so an interrupt between "sent" and "returned" still deletes
#   the server. cleanup deletes the drill pod by name and reads its absence back, deletes
#   RESTORED (retrying while Azure reports it busy) and reads back, in ONE list query whose
#   positive control is the live SOURCE, that RESTORED is gone. Only then does it print the
#   deletion line. A SIGKILL cannot be trapped: the pod carries activeDeadlineSeconds, and the
#   server name is printed before the request so a human can delete it.
#
# RLS BLINDS THE VERIFIER
#   Every count is taken as jtoye_backup (BYPASSRLS), and the counting session first prints
#   its own role, its rolbypassrls and the tenant GUC. A zero source count is VOID. Counts are
#   one statement that also prints current_database(), and that database must be the one the
#   step meant to read. Count sets are compared by TABLE NAME (jq), never by output position.
#
# NETWORK (A12)
#   PITR does not copy firewall rules. The drill reads SOURCE's single rule (the cluster
#   outbound IP, cross-checked against k8s/staging/azure-identity.yaml) and adds that one
#   rule to RESTORED only. Every psql/pg_dump/pg_restore runs inside one drill pod in
#   jtoye-staging labelled app.kubernetes.io/name=pg-backup (its NetworkPolicy admits public
#   5432), from the backup image itself (PostgreSQL 18 client; the host's 16 cannot dump an
#   18 server). Secrets reach the pod only on `kubectl exec -i` stdin, read into environment
#   variables there; never argv, never a pod spec, never a Kubernetes Secret.
#
# WHAT IS NOT DONE HERE
#   The WORM retention lock on jtoye-db-backups (a one-way human step, runbook §4.4). The
#   arm A dump is never uploaded. Nothing is restored onto SOURCE, and nothing is written to
#   the live backups/ prefix.
#
# USAGE
#   JTOYE_AZURE_SUBSCRIPTION_ID=c483d353-5f61-4587-a790-addb9ab5fb94 \
#     scripts/staging-pitr-drill.sh --context jtoye-stg-aks [--dry-run]
#   scripts/staging-pitr-drill.sh --selftest
#
#     --context NAME  the kube context (required; sipbihs2aks is refused by name, and the
#                     context's API server must be the one azure-identity.yaml records).
#     --dry-run       refused (rc 2) unless `command -v az` resolves into a directory that
#                     holds the az_guard_stub marker .az-guard-stub. Runs the guards, two
#                     pinned reads against the stub, and prints the ordered plan. No kubectl
#                     API call, no curl, no database call.
#     --selftest      offline arms, one line each (PASS <arm> | FAIL <arm> <why>);
#                     PITR_DRILL_SELFTEST_FORMAT=tap renders TAP 13.
#
#   Knobs (defaults are the plan's): PITR_DRILL_MARKER_GAP_SECONDS (60),
#   PITR_DRILL_WAL_WAIT_SECONDS (300, after the after-marker, before the restore request).
#
# EXIT CODES
#   0 every arm held and RESTORED is confirmed deleted · 1 a refusal or a recovery defect ·
#   2 VOID (could not evaluate: missing tool, unreachable server, empty table list, a quiet
#   window that moved). "Found nothing" is never "clean".

set -uo pipefail

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
SCRIPT_DIR="$(dirname "$SELF")"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RG="jtoye-rg"
SOURCE="jtoye-stg-pg"
PG_SUFFIX=".postgres.database.azure.com"
SOURCE_FQDN="${SOURCE}${PG_SUFFIX}"
DB="jtoye"
KV="jtoye-stg-kv"
NS="jtoye-staging"
BACKUP_ROLE="jtoye_backup"
DRILL_ROLE="jtoye_drill_norls"
OWNER_ROLE="jtoye_app"
IMAGE="ghcr.io/bralabee/jtoye-pg-backup:18-blob"
PIPELINE_REL="infra/backups/k8s-backup.sh"
PIPELINE_IN_IMAGE="/usr/local/bin/backup"
API="https://api-staging.olajay.co.uk"
TOKEN_ENDPOINT="https://auth-staging.olajay.co.uk/realms/jtoye-staging/protocol/openid-connect/token"
VENDOR_USERNAME="vendor.staging@olajay.co.uk"
SHOP_SLUG="staging-test-kitchen"
IDENTITY_FILE="$REPO_ROOT/k8s/staging/azure-identity.yaml"
DECISIONS_FILE="$REPO_ROOT/.planning/phases/29-deployable-staging-with-its-own-monitoring/29-ESTATE-DECISIONS.md"
FW_RULE="drill-aks-outbound"
COUNT_TABLES="products orders customers shops"
COUNT_SQL="SELECT current_database(), (SELECT count(*) FROM products) AS products, (SELECT count(*) FROM orders) AS orders, (SELECT count(*) FROM customers) AS customers, (SELECT count(*) FROM shops) AS shops"
DUMP_FLAGS="--no-owner --no-acl --format=custom --compress=6"   # infra/backups/k8s-backup.sh pg_dump flags
MARKER_GAP="${PITR_DRILL_MARKER_GAP_SECONDS:-60}"
WAL_WAIT="${PITR_DRILL_WAL_WAIT_SECONDS:-300}"
POD_DEADLINE_SECONDS=7200

RUN_ID="$(date -u +%Y%m%d%H%M%S)"
RESTORED="${SOURCE}-drill-${RUN_ID}"
RESTORED_FQDN="${RESTORED}${PG_SUFFIX}"
POD="jtoye-pitr-drill-${RUN_ID}"
MARKER_BEFORE="pitr-marker-before-${RUN_ID}"
MARKER_AFTER="pitr-marker-after-${RUN_ID}"

CONTEXT=""
MODE="run"
WORK=""
POD_CREATED=0
RESTORE_REQUESTED=0
CLEANUP_RAN=0

say()  { printf '[%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
void() { echo "VOID: $*" >&2; exit 2; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# cleanup — the EXIT trap. Installed on the next line after its definition,
# before any pod exists and before the restore can be requested.
# ---------------------------------------------------------------------------
cleanup() {
  local rc=$? attempt names deleted=0 drc
  set +e
  if [ "$CLEANUP_RAN" = 1 ]; then exit "$rc"; fi
  CLEANUP_RAN=1
  if [ "$POD_CREATED" = 1 ]; then
    say "cleanup: deleting drill pod ${NS}/${POD} by name"
    kubectl --context "$CONTEXT" -n "$NS" delete pod "$POD" --ignore-not-found --wait=true --timeout=180s >/dev/null 2>&1
    if kubectl --context "$CONTEXT" -n "$NS" get pod "$POD" >/dev/null 2>&1; then
      echo "DRILL POD STILL PRESENT: ${NS}/${POD} — delete it by that name" >&2
      [ "$rc" -ne 0 ] || rc=1
    else
      say "drill pod ${NS}/${POD} gone (read back: NotFound)"
    fi
  fi
  if [ "$RESTORE_REQUESTED" = 1 ]; then
    say "cleanup: deleting RESTORED ${RESTORED} (PITR always creates a second, billable server)"
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
      az_mutate --no-echo postgres flexible-server delete -g "$RG" --name "$RESTORED" --yes 2>"$WORK/delete.err"
      drc=$?
      say "  delete attempt ${attempt}: rc=${drc}$( [ "$drc" -eq 0 ] || printf ' (%s)' "$(head -c 200 "$WORK/delete.err" | tr '\n' ' ')")"
      names="$(az_read postgres flexible-server list -g "$RG" --query "[?starts_with(name,'${SOURCE}')].name" -o tsv 2>/dev/null | LC_ALL=C sort | tr '\n' ' ')"
      say "  SERVERS after delete attempt ${attempt}: ${names:-<none>}"
      case " $names " in
        *" ${SOURCE} "*)
          case " $names " in
            *" ${RESTORED} "*) ;;
            *) deleted=1 ;;
          esac ;;
        *) echo "  the list did not show the live ${SOURCE} (the positive control): this read cannot judge absence" >&2 ;;
      esac
      [ "$deleted" = 1 ] && break
      sleep 60
    done
    if [ "$deleted" = 1 ]; then
      say "restored server deleted: ${RESTORED} absent from the list that shows ${SOURCE}"
    else
      echo "RESTORED SERVER NOT CONFIRMED DELETED: ${RESTORED} may still be billing. Delete it by name: postgres flexible-server delete -g ${RG} --name ${RESTORED} --yes (pinned to the J'Toye subscription)" >&2
      [ "$rc" -ne 0 ] || rc=1
    fi
  fi
  if [ -n "${WORK:-}" ] && [ -d "$WORK" ]; then rm -rf "$WORK"; fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------
# helpers the selftest exercises
# ---------------------------------------------------------------------------

# target_guard ACTION HOST — the SOURCE-write refusal. An allow-list: only a
# jtoye-stg-pg-drill-<digits> server is admitted. Returns 1 (refused) before any
# connection is attempted; callers run it first and stop on non-zero.
target_guard() {
  local action="$1" host="${2-}" h
  h="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"
  h="${h%.}"
  if [ -z "$h" ]; then
    echo "REFUSED [source-write]: ${action} names no target host; refusing rather than letting libpq pick a default" >&2
    return 1
  fi
  case "$h" in
    "$SOURCE"|"$SOURCE_FQDN")
      echo "REFUSED [source-write]: ${action} targets SOURCE (${SOURCE_FQDN}). Roles, databases, drops and restores go to RESTORED only; SOURCE's only drill writes are the two vendor-API markers" >&2
      return 1 ;;
  esac
  case "$h" in
    "${SOURCE}-drill-"[0-9]*"$PG_SUFFIX")
      case "${h#"${SOURCE}-drill-"}" in
        *[!0-9]*"$PG_SUFFIX") ;;
        *) return 0 ;;
      esac ;;
  esac
  echo "REFUSED [source-write]: ${action} targets '${host}', which is not a ${SOURCE}-drill-<RUN_ID>${PG_SUFFIX} server" >&2
  return 1
}

# pipeline_snippet SCRIPT DUMP — the pipeline's own floor and listing lines, read
# out of SCRIPT (k8s-backup.sh) and run UNCHANGED against DUMP. Each line must occur
# exactly once, or the snippet is VOID (rc 2): a changed pipeline is re-read, never
# assumed. Only fail() differs: the pipeline's also deletes the file, this one only
# reports.
pipeline_snippet() {
  local f="$1" dump="$2" pat n line
  local -a pats=(
    '^MIN_BACKUP_BYTES="\$\{MIN_BACKUP_BYTES:-[0-9]+\}"$'
    '^SIZE="\$\(stat -c%s "\$TMP"'
    '^\[ "\$SIZE" -ge "\$MIN_BACKUP_BYTES" \] \|\| fail '
    '^pg_restore --list "\$TMP" .*\|\| fail '
  )
  local -a lines=()
  [ -r "$f" ] || { echo "VOID: pipeline script $f unreadable" >&2; return 2; }
  for pat in "${pats[@]}"; do
    n="$(command grep -cE -- "$pat" "$f")"
    if [ "$n" != 1 ]; then
      echo "VOID: the pipeline line /${pat}/ occurs ${n} times in ${f} (want exactly 1)" >&2
      return 2
    fi
    line="$(command grep -E -- "$pat" "$f")"
    lines+=("$line")
  done
  printf '%s\n' 'set -euo pipefail'
  printf '%s\n' 'fail() { echo "PIPELINE REFUSED: $*"; exit 1; }'
  printf 'TMP=%q\n' "$dump"
  printf '%s\n' "${lines[@]}"
  printf '%s\n' 'echo "PIPELINE PASS: ${SIZE} bytes, floor ${MIN_BACKUP_BYTES} bytes"'
}

# counts_json — psql -A output (header line, value line; tab-separated) on stdin ->
# one JSON object keyed by column name.
counts_json() {
  jq -Rn '[inputs | select(length > 0) | split("\t")] as $r
    | if ($r | length) != 2 then error("expected one header line and one value line, got \($r | length)")
      else [$r[0], $r[1]] | transpose | map({(.[0]): .[1]}) | add end'
}

# compare_counts LABEL_A JSON_A LABEL_B JSON_B — by TABLE NAME. Prints one line per
# table; returns 0 all equal, 1 any differ, 2 VOID (no tables, a missing table or a
# non-numeric count on either side).
compare_counts() {
  local la="$1" a="$2" lb="$3" b="$4" t va vb n=0 differ=0
  for t in $COUNT_TABLES; do
    n=$((n + 1))
    va="$(jq -r --arg k "$t" '.[$k] // empty' <<<"$a" 2>/dev/null)"
    vb="$(jq -r --arg k "$t" '.[$k] // empty' <<<"$b" 2>/dev/null)"
    case "$va" in ''|*[!0-9]*) echo "VOID: ${la} has no numeric count for table ${t} ('${va}')" >&2; return 2 ;; esac
    case "$vb" in ''|*[!0-9]*) echo "VOID: ${lb} has no numeric count for table ${t} ('${vb}')" >&2; return 2 ;; esac
    if [ "$va" = "$vb" ]; then
      printf '  %-10s %s=%s %s=%s equal\n' "$t" "$la" "$va" "$lb" "$vb"
    else
      printf '  %-10s %s=%s %s=%s DIFFERENT\n' "$t" "$la" "$va" "$lb" "$vb"
      differ=1
    fi
  done
  [ "$n" -gt 0 ] || { echo "VOID: the table list is empty; zero tables compare equal vacuously" >&2; return 2; }
  return "$differ"
}

# trap_before_restore FILE — the plan's ordering check: the first non-comment line
# naming the trap precedes the first non-comment line naming the restore.
trap_before_restore() {
  # The two patterns are assembled from pieces so that THIS line does not itself
  # match them: the restore the check finds is the real request, not this helper.
  local tp="trap cleanup" rp="flexible-server"
  awk -v tp="$tp EXIT" -v rp="$rp restore" '!/^[[:space:]]*#/ && index($0, tp) && !t {t=NR} !/^[[:space:]]*#/ && index($0, rp) && !r {r=NR} END {print "trap=" t " restore=" r; exit (t && r && t < r) ? 0 : 1}' "$1"
}

# ---------------------------------------------------------------------------
# selftest (offline: no az, no kubectl API, no database)
# ---------------------------------------------------------------------------
ST_RESULTS=()
st() { ST_RESULTS+=("$1"); }

selftest() {
  local tmp rc out err why bin calls f h
  tmp="$(mktemp -d)" || void "mktemp -d failed"
  bin="$tmp/bin"; mkdir -p "$bin"
  # A kubectl that records every call: the refusal must happen before any.
  printf '%s\n' '#!/usr/bin/env bash' 'printf "%s\n" "$*" >> "$(dirname "$0")/kubectl.log"' 'exit 0' > "$bin/kubectl"
  chmod +x "$bin/kubectl"; : > "$bin/kubectl.log"

  # 1. source-write-refused
  why=""
  for h in "$SOURCE_FQDN" "$SOURCE" "JTOYE-STG-PG.postgres.database.azure.com" "${SOURCE_FQDN}." ""; do
    for f in "CREATE ROLE" "CREATE DATABASE" "DROP DATABASE" "pg_restore"; do
      err="$tmp/sw.err"; : > "$err"
      rc=0; ( PATH="$bin:$PATH"; target_guard "$f" "$h" ) 2>"$err" || rc=$?
      [ "$rc" = 1 ] || why="${why}${f}@'${h}' rc=${rc}; "
      command grep -qF 'REFUSED [source-write]' "$err" || why="${why}${f}@'${h}' no REFUSED line; "
    done
  done
  calls="$(command grep -c . "$bin/kubectl.log" || true)"
  [ "$calls" = 0 ] || why="${why}kubectl called ${calls} time(s) before the refusal; "
  if [ -z "$why" ]; then st "PASS source-write-refused rc=1 x20 kubectl_calls=0"; else st "FAIL source-write-refused ${why}"; fi

  # 2. restored-admitted (and near-misses refused)
  why=""
  rc=0; target_guard "CREATE ROLE" "$RESTORED_FQDN" 2>/dev/null || rc=$?
  [ "$rc" = 0 ] || why="${why}${RESTORED_FQDN} rc=${rc}; "
  for h in "${SOURCE}-drill-x${PG_SUFFIX}" "${SOURCE}-drill-1a${PG_SUFFIX}" "other-pg${PG_SUFFIX}" "${SOURCE}-drill-1.example.com"; do
    rc=0; target_guard "CREATE ROLE" "$h" 2>/dev/null || rc=$?
    [ "$rc" = 1 ] || why="${why}${h} rc=${rc} (want 1); "
  done
  if [ -z "$why" ]; then st "PASS restored-admitted rc=0 near_misses_refused=4"; else st "FAIL restored-admitted ${why}"; fi

  # 3. trap-before-restore (this file, and a planted copy with the order reversed)
  out="$(trap_before_restore "$SELF")"; rc=$?
  printf '%s\n' 'x=1' "az_mutate postgres flexible-server $(printf restore) -g g --name n" "trap cleanup $(printf EXIT)" > "$tmp/reversed.sh"
  local rrc=0; trap_before_restore "$tmp/reversed.sh" >/dev/null || rrc=$?
  if [ "$rc" = 0 ] && [ "$rrc" = 1 ]; then st "PASS trap-before-restore ${out} planted_reversed_rc=${rrc}"
  else st "FAIL trap-before-restore self rc=${rc} (${out}) planted_reversed_rc=${rrc} (want 0 and 1)"; fi

  # 4. zero-byte-refused (+ a junk file passes the floor and is refused by the listing;
  #    + a pipeline with the floor line removed is VOID, never a pass)
  why=""
  : > "$tmp/zero.dump"
  pipeline_snippet "$REPO_ROOT/$PIPELINE_REL" "$tmp/zero.dump" > "$tmp/zero.sh" 2>"$tmp/snip.err"; rc=$?
  if [ "$rc" != 0 ]; then why="snippet rc=${rc}: $(head -c 200 "$tmp/snip.err"); "
  else
    out="$(bash "$tmp/zero.sh" 2>&1)"; rc=$?
    [ "$rc" = 1 ] || why="${why}zero-byte rc=${rc} (want 1); "
    case "$out" in *"below size floor"*) ;; *) why="${why}zero-byte refusal is not the size floor: ${out}; " ;; esac
  fi
  head -c 5000 /dev/urandom > "$tmp/junk.dump"
  pipeline_snippet "$REPO_ROOT/$PIPELINE_REL" "$tmp/junk.dump" > "$tmp/junk.sh" 2>/dev/null
  if command -v pg_restore >/dev/null 2>&1; then
    out="$(bash "$tmp/junk.sh" 2>&1)"; rc=$?
    [ "$rc" = 1 ] || why="${why}junk rc=${rc} (want 1); "
    case "$out" in *"not a readable pg_restore archive"*) ;; *) why="${why}junk refusal is not the listing: ${out}; " ;; esac
  else
    why="${why}VOID pg_restore not on PATH for the junk arm; "
  fi
  command grep -vE '^\[ "\$SIZE" -ge' "$REPO_ROOT/$PIPELINE_REL" > "$tmp/no-floor.sh"
  rc=0; pipeline_snippet "$tmp/no-floor.sh" "$tmp/zero.dump" >/dev/null 2>&1 || rc=$?
  [ "$rc" = 2 ] || why="${why}floorless pipeline rc=${rc} (want 2 VOID); "
  if [ -z "$why" ]; then st "PASS zero-byte-refused zero_rc=1 junk_rc=1 floorless_rc=2"; else st "FAIL zero-byte-refused ${why}"; fi

  # 5. compare-by-name (order-independent; differ; missing table; empty list)
  why=""
  local a b
  a="$(printf 'current_database\tproducts\torders\tcustomers\tshops\njtoye\t7\t1\t0\t1\n' | counts_json)"
  b="$(printf 'shops\tcustomers\torders\tproducts\tcurrent_database\n1\t0\t1\t7\tdrill_b\n' | counts_json)"
  rc=0; compare_counts src "$a" restored "$b" >/dev/null 2>&1 || rc=$?
  [ "$rc" = 0 ] || why="${why}reordered columns rc=${rc} (want 0); "
  b="$(printf 'current_database\tproducts\torders\tcustomers\tshops\ndrill_a\t5\t0\t0\t1\n' | counts_json)"
  rc=0; compare_counts src "$a" restored "$b" >/dev/null 2>&1 || rc=$?
  [ "$rc" = 1 ] || why="${why}different counts rc=${rc} (want 1); "
  b="$(printf 'current_database\tproducts\torders\tshops\ndrill_a\t7\t1\t1\n' | counts_json)"
  rc=0; compare_counts src "$a" restored "$b" >/dev/null 2>&1 || rc=$?
  [ "$rc" = 2 ] || why="${why}missing table rc=${rc} (want 2); "
  rc=0; ( COUNT_TABLES=""; compare_counts src "$a" restored "$a" ) >/dev/null 2>&1 || rc=$?
  [ "$rc" = 2 ] || why="${why}empty table list rc=${rc} (want 2); "
  if [ -z "$why" ]; then st "PASS compare-by-name reordered=0 differ=1 missing=2 empty=2"; else st "FAIL compare-by-name ${why}"; fi

  rm -rf "$tmp"
  local i=0 r fails=0
  if [ "${PITR_DRILL_SELFTEST_FORMAT:-plain}" = tap ]; then
    echo "TAP version 13"; echo "1..${#ST_RESULTS[@]}"
    for r in "${ST_RESULTS[@]}"; do
      i=$((i + 1))
      case "$r" in
        PASS\ *) r="${r#PASS }"; echo "ok $i - ${r%% *}" ;;
        *) fails=$((fails + 1)); r="${r#FAIL }"; echo "not ok $i - ${r%% *}"; echo "  ---"; echo "  message: '$(printf '%s' "${r#* }" | tr "'" '"')'"; echo "  ..." ;;
      esac
    done
  else
    for r in "${ST_RESULTS[@]}"; do
      printf '%s\n' "$r"
      case "$r" in PASS\ *) ;; *) fails=$((fails + 1)) ;; esac
    done
  fi
  [ "$fails" -eq 0 ]
}

# ---------------------------------------------------------------------------
# argument parsing
# ---------------------------------------------------------------------------
while [ "$#" -gt 0 ]; do
  case "$1" in
    --context) shift; [ "$#" -gt 0 ] && [ -n "$1" ] || { echo "USAGE ERROR: --context needs a kube context name" >&2; exit 2; }; CONTEXT="$1" ;;
    --dry-run) MODE="dry-run" ;;
    --selftest) MODE="selftest" ;;
    -h|--help) sed -n '/^# USAGE/,/^# EXIT CODES/p' "$SELF" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "USAGE ERROR: unknown argument '$1'" >&2; exit 2 ;;
  esac
  shift
done

if [ "$MODE" = selftest ]; then
  selftest; exit $?
fi

# ---------------------------------------------------------------------------
# guards (both modes)
# ---------------------------------------------------------------------------
[ -n "$CONTEXT" ] || { echo "USAGE ERROR: --context is required (no default)" >&2; exit 2; }
case "$CONTEXT" in
  sipbihs2aks) echo "REFUSED [employer-context]: sipbihs2aks is the employer's cluster" >&2; exit 1 ;;
esac
for t in jq kubectl; do command -v "$t" >/dev/null 2>&1 || void "$t not on PATH"; done
if [ "$MODE" = dry-run ]; then
  azp="$(command -v az 2>/dev/null || true)"
  if [ -z "$azp" ] || [ ! -f "$(dirname "$azp")/.az-guard-stub" ]; then
    echo "REFUSED [dry-run]: --dry-run needs az on PATH to be the az_guard_stub (a directory holding .az-guard-stub); got '${azp:-<none>}'" >&2
    exit 2
  fi
else
  command -v curl >/dev/null 2>&1 || void "curl not on PATH"
fi
# shellcheck source=scripts/lib/staging-kube-guard.sh
. "$SCRIPT_DIR/lib/staging-kube-guard.sh" || void "could not source scripts/lib/staging-kube-guard.sh"
grc=0; staging_kube_guard "$CONTEXT" "$IDENTITY_FILE" || grc=$?
[ "$grc" -eq 0 ] || exit "$grc"
# shellcheck source=scripts/lib/azure-guard.sh
. "$SCRIPT_DIR/lib/azure-guard.sh" || void "could not source scripts/lib/azure-guard.sh"
grc=0; az_guard_init || grc=$?
[ "$grc" -eq 0 ] || exit "$grc"

WORK="$(umask 077 && mktemp -d)" || void "mktemp -d failed"
chmod 700 "$WORK"

ADMIN_LOGIN="$(sed -nE 's/^PG_ADMIN_LOGIN=([a-z0-9_]+)$/\1/p' "$DECISIONS_FILE" | head -1)"
[ -n "$ADMIN_LOGIN" ] || void "PG_ADMIN_LOGIN not found in $DECISIONS_FILE"
OUTBOUND_IP="$(sed -nE 's/^[[:space:]]*outbound_ip_address:[[:space:]]*"?([0-9.]+)"?[[:space:]]*$/\1/p' "$IDENTITY_FILE" | head -1)"
[ -n "$OUTBOUND_IP" ] || void "outbound_ip_address not found in $IDENTITY_FILE"

say "staging-pitr-drill mode=${MODE} run_id=${RUN_ID} context=${CONTEXT}"
say "SOURCE=${SOURCE_FQDN} (live; writes: the two vendor-API markers only)"
say "RESTORED=${RESTORED_FQDN} (temporary; deleted by the EXIT trap)"

# Source firewall: exactly one rule, start = end = the cluster outbound IP.
fw="$(az_read postgres flexible-server firewall-rule list -g "$RG" --server-name "$SOURCE" --query "[].[name,startIpAddress,endIpAddress]" -o tsv)" \
  || void "could not read ${SOURCE}'s firewall rules"
if [ "$MODE" = dry-run ]; then
  say "firewall (stub): ${fw}"
  cat <<PLAN

DRY RUN — ordered plan (no kubectl API call, no curl, no database call was made)
  01 EXIT trap (cleanup)                     installed before anything is created (deletes pod ${POD} and RESTORED, reads both back)
  02 KV reads (values never printed)         ${KV}: postgres-credentials--backup-password, pg-admin--password, staging-seed--test-vendor-password, keycloak-credentials--frontend-client-secret
  03 drill pod                               ${NS}/${POD} image ${IMAGE}, label app.kubernetes.io/name=pg-backup, activeDeadlineSeconds ${POD_DEADLINE_SECONDS}
  04 instrument control (SOURCE, read)       ${BACKUP_ROLE} on ${SOURCE_FQDN}: role, rolbypassrls, tenant GUC; counts; products > 0 or VOID
  05 before-marker (SOURCE, vendor API)      POST ${API}/api/v1/products sku=${MARKER_BEFORE} available=false; read its created_at (SOURCE, read)
  06 sleep ${MARKER_GAP}s
  07 T0 + source counts (SOURCE, read)       counts before T0 and right after T0 must be equal (quiet window), else VOID
  08 sleep ${MARKER_GAP}s
  09 after-marker (SOURCE, vendor API)       POST ${API}/api/v1/products sku=${MARKER_AFTER} available=false; before.created_at < T0 < after.created_at (SOURCE, read)
  10 sleep ${WAL_WAIT}s                       WAL past T0 archived before the request
  11 PITR request -> RESTORED               az_mutate (pinned): restore ${SOURCE} at T0 into NEW server ${RESTORED}
  12 SERVERS during run                      az_read flexible-server list (both names expected)
  13 firewall (RESTORED only)                rule ${FW_RULE} = ${OUTBOUND_IP} (the cluster outbound IP; SOURCE's rule is not touched)
  14 counts + markers (RESTORED, read)       ${BACKUP_ROLE}: counts == source T0 counts by table name; before present, after absent
  15 scratch role (RESTORED, admin)          CREATE ROLE ${DRILL_ROLE} LOGIN NOBYPASSRLS + SELECT; read back on RESTORED
  16 arm A plain dump (RESTORED)             pg_dump as ${DRILL_ROLE}: expect rc != 0 with a row-level security error
  17 arm A row-security dump (RESTORED)      pg_dump --enable-row-security as ${DRILL_ROLE}
  18 pipeline checks (pod)                   size floor + pg_restore --list, lines read from ${PIPELINE_IN_IMAGE}: expect PASS
  19 restore into drill_a (RESTORED)         CREATE DATABASE drill_a; pg_restore; counts != source
  20 arm B dump (RESTORED)                   pg_dump as ${BACKUP_ROLE}; pipeline checks PASS
  21 restore into drill_b (RESTORED)         CREATE DATABASE drill_b; pg_restore; counts == source
  22 zero-byte arm (pod)                     the size-floor line against an empty file: expect refused
  23 compare                                 every count set joined by table name (jq)
  24 cleanup (trap)                          delete pod by name + read back; delete RESTORED + read back with SOURCE as control
SOURCE is never a target of 15, 16, 17, 19, 20 or 21: target_guard refuses it before any connection.
PLAN
  say "DRY RUN COMPLETE: evidence about this file only, never a rehearsed restore"
  exit 0
fi

# ===========================================================================
# REAL RUN
# ===========================================================================
nfw="$(command grep -c . <<<"$fw" || true)"
[ "$nfw" = 1 ] || void "${SOURCE} has ${nfw} firewall rules (want exactly 1, the cluster outbound IP): ${fw}"
fw_start="$(cut -f2 <<<"$fw")"; fw_end="$(cut -f3 <<<"$fw")"
[ "$fw_start" = "$fw_end" ] && [ "$fw_start" = "$OUTBOUND_IP" ] \
  || void "${SOURCE}'s rule is ${fw_start}-${fw_end}, not the recorded outbound IP ${OUTBOUND_IP}"
say "SOURCE firewall: 1 rule $(cut -f1 <<<"$fw") ${fw_start} (= azure-identity.yaml outbound_ip_address)"

src_state="$(az_read postgres flexible-server show -g "$RG" --name "$SOURCE" --query "[state,version,fullyQualifiedDomainName]" -o tsv | tr '\t\n' '  ')" \
  || void "could not read ${SOURCE}"
say "SOURCE server: ${src_state}"

# --- secrets: mode-600 files, never printed, never argv ---------------------
kv_to_file() {
  local obj="$1" out="$2" v rc=0
  v="$(az_read keyvault secret show --vault-name "$KV" -n "$obj" --query value -o tsv)" || rc=$?
  [ "$rc" -eq 0 ] && [ -n "$v" ] || void "could not read Key Vault object ${obj} (rc=${rc})"
  (umask 077 && printf '%s' "$v" > "$out")
}
kv_to_file postgres-credentials--backup-password "$WORK/pw-backup"
kv_to_file pg-admin--password "$WORK/pw-admin"
kv_to_file staging-seed--test-vendor-password "$WORK/vendor-password"
kv_to_file keycloak-credentials--frontend-client-secret "$WORK/client-secret"
(umask 077 && printf '%s' "$VENDOR_USERNAME" > "$WORK/vendor-username")
(umask 077 && head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 40 > "$WORK/pw-drill")
[ "$(wc -c < "$WORK/pw-drill")" -ge 32 ] || void "could not generate the scratch role password"
say "secrets: 4 Key Vault objects read into mode-600 files + 1 generated (values never printed)"

# --- the drill pod ----------------------------------------------------------
jq -n --arg name "$POD" --arg ns "$NS" --arg image "$IMAGE" --arg run "$RUN_ID" --argjson deadline "$POD_DEADLINE_SECONDS" '{
  apiVersion: "v1", kind: "Pod",
  metadata: {name: $name, namespace: $ns, labels: {"app.kubernetes.io/name": "pg-backup", "app.kubernetes.io/component": "pitr-drill", "jtoye.uk/pitr-drill-run": $run}},
  spec: {
    automountServiceAccountToken: false, restartPolicy: "Never", activeDeadlineSeconds: $deadline,
    securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000, fsGroup: 1000, seccompProfile: {type: "RuntimeDefault"}},
    containers: [{name: "drill", image: $image, command: ["sleep", "7200"],
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}},
      resources: {requests: {cpu: "100m", memory: "256Mi"}, limits: {cpu: "500m", memory: "512Mi"}}}]}}' > "$WORK/pod.json"
POD_CREATED=1
kubectl --context "$CONTEXT" -n "$NS" create -f "$WORK/pod.json" >/dev/null || void "could not create the drill pod ${NS}/${POD}"
kubectl --context "$CONTEXT" -n "$NS" wait --for=condition=Ready "pod/${POD}" --timeout=300s >/dev/null \
  || void "drill pod ${NS}/${POD} not Ready within 300s"
say "drill pod ${NS}/${POD} Ready ($(kubectl --context "$CONTEXT" -n "$NS" exec "$POD" -- pg_dump --version 2>/dev/null))"

# pod_exec "VAR=file ..." CMD ARGS... — each secret file becomes one stdin line read
# into VAR inside the pod (exported), then CMD runs with the rest of this function's stdin.
pod_exec() {
  local spec="$1" pair names=""
  shift
  for pair in $spec; do names="$names ${pair%%=*}"; done
  {
    for pair in $spec; do printf '%s\n' "$(cat "${pair#*=}")"; done
    cat
  } | kubectl --context "$CONTEXT" -n "$NS" exec -i "$POD" -- bash -c 'for v in $1; do IFS= read -r "$v"; export "$v"; done; shift; exec "$@"' _ "$names" "$@"
}
conninfo() { printf 'host=%s dbname=%s user=%s sslmode=verify-full sslrootcert=system connect_timeout=15 application_name=pitr-drill-%s' "$1" "$2" "$3" "$RUN_ID"; }
pw_for() {
  case "$1" in
    "$BACKUP_ROLE") echo "$WORK/pw-backup" ;;
    "$DRILL_ROLE") echo "$WORK/pw-drill" ;;
    "$ADMIN_LOGIN") echo "$WORK/pw-admin" ;;
    *) void "no password file for role $1" ;;
  esac
}
# psql_at HOST DB ROLE [psql args] — SQL on stdin; tab-separated, header on, no footer.
psql_at() {
  local host="$1" db="$2" role="$3"
  shift 3
  pod_exec "PGPASSWORD=$(pw_for "$role")" psql -X -q -v ON_ERROR_STOP=1 -A -F $'\t' -P footer=off "$@" "$(conninfo "$host" "$db" "$role")" -f -
}

# count_set LABEL HOST DB — identity line, then the one count statement; JSON to
# $WORK/counts-LABEL.json. The database read must be DB.
count_set() {
  local label="$1" host="$2" db="$3" out rc=0 who
  who="$(psql_at "$host" "$db" "$BACKUP_ROLE" -t <<<"SELECT current_user, (SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user), coalesce(nullif(current_setting('app.current_tenant_id', true), ''), 'unset');" 2>"$WORK/err")" || rc=$?
  [ "$rc" -eq 0 ] || { echo "count ${label}: identity query failed rc=${rc}: $(head -c 300 "$WORK/err")" >&2; return 2; }
  say "INSTRUMENT ${label}: host=${host} user=$(cut -f1 <<<"$who") bypassrls=$(cut -f2 <<<"$who") tenant_guc=$(cut -f3 <<<"$who")"
  case "$who" in "${BACKUP_ROLE}"$'\t'"t"$'\t'*) ;; *) echo "count ${label}: the counting session is not ${BACKUP_ROLE} with BYPASSRLS ('${who}'); RLS could blind it" >&2; return 2 ;; esac
  out="$(psql_at "$host" "$db" "$BACKUP_ROLE" <<<"${COUNT_SQL};" 2>"$WORK/err")" || rc=$?
  [ "$rc" -eq 0 ] || { echo "count ${label}: count failed rc=${rc}: $(head -c 300 "$WORK/err")" >&2; return 2; }
  counts_json <<<"$out" > "$WORK/counts-${label}.json" || { echo "count ${label}: unparseable output" >&2; return 2; }
  say "COUNTS ${label}: $(jq -c . "$WORK/counts-${label}.json")"
  [ "$(jq -r .current_database "$WORK/counts-${label}.json")" = "$db" ] \
    || { echo "count ${label}: read database '$(jq -r .current_database "$WORK/counts-${label}.json")', not ${db}" >&2; return 2; }
}

# --- instrument control on SOURCE (read) -------------------------------------
ok=0
for attempt in 1 2 3; do
  if count_set source-control "$SOURCE_FQDN" "$DB"; then ok=1; break; fi
  sleep 10
done
[ "$ok" = 1 ] || void "SOURCE could not be counted from the drill pod"
[ "$(jq -r .products "$WORK/counts-source-control.json")" -gt 0 ] 2>/dev/null \
  || void "SOURCE products count is 0: a restore compared against an empty source proves nothing"
schemas="$(psql_at "$SOURCE_FQDN" "$DB" "$BACKUP_ROLE" -t <<<"SELECT string_agg(DISTINCT schemaname, ',' ORDER BY schemaname) FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema');")" \
  || void "could not list SOURCE schemas"
say "SOURCE user schemas: ${schemas}"

# --- vendor token and shop ---------------------------------------------------
tok_code="$(curl -sS -X POST -o "$WORK/token.json" -w '%{http_code}' \
  --data-urlencode "grant_type=password" --data-urlencode "client_id=core-api" \
  --data-urlencode "client_secret@$WORK/client-secret" --data-urlencode "scope=openid" \
  --data-urlencode "username@$WORK/vendor-username" --data-urlencode "password@$WORK/vendor-password" \
  "$TOKEN_ENDPOINT")" || tok_code="000"
[ "$tok_code" = 200 ] || { rm -f "$WORK/token.json"; void "vendor token request returned HTTP ${tok_code}"; }
[ -n "$(jq -r '.access_token // empty' "$WORK/token.json")" ] || { rm -f "$WORK/token.json"; void "the vendor token response carried no access_token"; }
(umask 077 && jq -r '"header = \"Authorization: Bearer " + .access_token + "\""' "$WORK/token.json" > "$WORK/vendor-auth.curlrc")
rm -f "$WORK/token.json"
api_get() { curl -sS -K "$WORK/vendor-auth.curlrc" -H 'Accept: application/json' -o "$2" -w '%{http_code}' "${API}$1" || echo 000; }
code="$(api_get "/api/v1/shops?size=100" "$WORK/shops.json")"
[ "$code" = 200 ] || void "GET /api/v1/shops returned HTTP ${code}"
SHOP_ID="$(jq -r --arg s "$SHOP_SLUG" '[.content[]? | select(.slug == $s)][0].id // empty' "$WORK/shops.json")"
[ -n "$SHOP_ID" ] || void "the vendor sees no shop ${SHOP_SLUG}"
say "vendor token: HTTP 200; shop ${SHOP_SLUG} = ${SHOP_ID}"

# create_marker SKU — POST through the vendor API. A transport error or 502/503/504
# (a concurrent rollout) is retried; a 409 on retry means the first attempt landed.
create_marker() {
  local sku="$1" attempt code
  jq -n --arg sku "$sku" --arg shop "$SHOP_ID" '{sku: $sku, title: $sku,
    ingredientsText: "PITR drill marker (plan 29-20). Not food; never offered for sale.",
    allergenMask: 0, pricePennies: 100, available: false, shopId: $shop}' > "$WORK/marker.json"
  for attempt in 1 2 3 4 5 6 7 8 9; do
    code="$(curl -sS -K "$WORK/vendor-auth.curlrc" -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' \
      --data-binary "@$WORK/marker.json" -o "$WORK/marker-resp.json" -w '%{http_code}' "${API}/api/v1/products" || echo 000)"
    case "$code" in
      201) say "marker ${sku}: HTTP 201 id=$(jq -r '.id // empty' "$WORK/marker-resp.json")"; return 0 ;;
      409) [ "$attempt" -gt 1 ] || { echo "marker ${sku}: 409 on the first attempt (a SKU this run did not write)" >&2; return 1; }
           say "marker ${sku}: HTTP 409 on retry ${attempt} — the earlier attempt landed"; return 0 ;;
      000|502|503|504) say "marker ${sku}: HTTP ${code} (attempt ${attempt}; a rollout in progress?) — retrying in 20s"; sleep 20 ;;
      *) echo "marker ${sku}: HTTP ${code}: $(jq -r '[.type,.title,.detail]|map(select(.!=null))|join(" | ")' "$WORK/marker-resp.json" 2>/dev/null | head -c 300)" >&2; return 1 ;;
    esac
  done
  echo "marker ${sku}: no 201 after 9 attempts" >&2; return 1
}
# marker_ts SKU — the committed row's created_at on SOURCE (read), ISO UTC microseconds.
marker_ts() {
  psql_at "$SOURCE_FQDN" "$DB" "$BACKUP_ROLE" -t -v "sku=$1" <<<"SELECT to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM products WHERE sku = :'sku';"
}

# --- before-marker -----------------------------------------------------------
say "STEP before-marker (SOURCE, vendor API): ${MARKER_BEFORE}"
create_marker "$MARKER_BEFORE" || fail "the before-marker could not be written"
BEFORE_TS=""
for attempt in 1 2 3 4 5 6; do
  BEFORE_TS="$(marker_ts "$MARKER_BEFORE")" && [ -n "$BEFORE_TS" ] && break
  sleep 5
done
[ -n "$BEFORE_TS" ] || fail "the before-marker row is not visible on SOURCE"
say "before-marker committed: created_at=${BEFORE_TS}"
say "sleep ${MARKER_GAP}s"; sleep "$MARKER_GAP"

# --- T0 inside a quiet window --------------------------------------------------
count_set source-pre-t0 "$SOURCE_FQDN" "$DB" || void "SOURCE count before T0 failed"
t0_epoch=$(( $(date +%s) + 2 ))
while [ "$(date +%s)" -le "$t0_epoch" ]; do sleep 0.2; done
T0="$(date -u -d "@${t0_epoch}" +%Y-%m-%dT%H:%M:%S+00:00)"
say "T0=${T0}"
count_set source-t0 "$SOURCE_FQDN" "$DB" || void "SOURCE count after T0 failed"
say "quiet-window check (counts before T0 vs right after T0):"
qrc=0; compare_counts pre "$(cat "$WORK/counts-source-pre-t0.json")" post "$(cat "$WORK/counts-source-t0.json")" || qrc=$?
[ "$qrc" = 0 ] || void "tenant rows moved across T0 (rc=${qrc}); the source counts at T0 are not pinned. Re-run."
say "sleep ${MARKER_GAP}s"; sleep "$MARKER_GAP"

# --- after-marker --------------------------------------------------------------
say "STEP after-marker (SOURCE, vendor API): ${MARKER_AFTER}"
create_marker "$MARKER_AFTER" || fail "the after-marker could not be written"
AFTER_TS=""
for attempt in 1 2 3 4 5 6; do
  AFTER_TS="$(marker_ts "$MARKER_AFTER")" && [ -n "$AFTER_TS" ] && break
  sleep 5
done
[ -n "$AFTER_TS" ] || fail "the after-marker row is not visible on SOURCE"
say "after-marker committed: created_at=${AFTER_TS}"
between="$(psql_at "$SOURCE_FQDN" "$DB" "$BACKUP_ROLE" -t -v "b=$MARKER_BEFORE" -v "a=$MARKER_AFTER" -v "t0=$T0" \
  <<<"SELECT (SELECT created_at FROM products WHERE sku = :'b') < :'t0'::timestamptz AND :'t0'::timestamptz < (SELECT created_at FROM products WHERE sku = :'a');")" \
  || void "could not compare the markers with T0"
[ "$between" = t ] || fail "T0 is not strictly between the markers (before=${BEFORE_TS} T0=${T0} after=${AFTER_TS})"
say "restore point strictly between markers: before=${BEFORE_TS} < T0=${T0} < after=${AFTER_TS}"
mk="$(psql_at "$SOURCE_FQDN" "$DB" "$BACKUP_ROLE" -v "b=$MARKER_BEFORE" -v "a=$MARKER_AFTER" \
  <<<"SELECT current_database(), (SELECT count(*) FROM products WHERE sku = :'b') AS before_marker, (SELECT count(*) FROM products WHERE sku = :'a') AS after_marker;" | counts_json)" \
  || void "could not read the markers on SOURCE"
say "MARKERS on SOURCE (fail direction of the marker query: both must be 1): $(jq -c . <<<"$mk")"
[ "$(jq -r .before_marker <<<"$mk")/$(jq -r .after_marker <<<"$mk")" = 1/1 ] || void "the marker query does not see both markers on SOURCE"

say "sleep ${WAL_WAIT}s (WAL past T0 archived before the restore request)"; sleep "$WAL_WAIT"

# --- ARM P: the restore ------------------------------------------------------
say "STEP restore: ${SOURCE} @ ${T0} -> NEW server ${RESTORED} (cleanup trap already installed)"
p_start=$(date +%s)
RESTORE_REQUESTED=1
rrc=0; az_mutate --no-echo postgres flexible-server restore -g "$RG" --name "$RESTORED" --source-server "$SOURCE" --restore-time "$T0" --yes 2>"$WORK/restore.err" || rrc=$?
p_end=$(date +%s)
say "restore request rc=${rrc} after $((p_end - p_start))s"
[ "$rrc" -eq 0 ] || void "the PITR restore failed: $(head -c 500 "$WORK/restore.err" | tr '\n' ' ')"
names="$(az_read postgres flexible-server list -g "$RG" --query "[?starts_with(name,'${SOURCE}')].name" -o tsv | LC_ALL=C sort | tr '\n' ' ')"
say "SERVERS during run: ${names}"
say "RESTORED server: $(az_read postgres flexible-server show -g "$RG" --name "$RESTORED" --query "[state,version,sku.name,fullyQualifiedDomainName]" -o tsv | tr '\t\n' '  ')"
say "RESTORED firewall rules as restored (A12: PITR does not copy them): [$(az_read postgres flexible-server firewall-rule list -g "$RG" --server-name "$RESTORED" --query "[].[name,startIpAddress]" -o tsv | tr '\t\n' ' ;')]"
say "RESTORED azure.extensions: $(az_read postgres flexible-server parameter show -g "$RG" --server-name "$RESTORED" --name azure.extensions --query value -o tsv)"
say "STEP firewall (RESTORED only): ${FW_RULE} = ${OUTBOUND_IP}"
az_mutate --no-echo postgres flexible-server firewall-rule create -g "$RG" --server-name "$RESTORED" --name "$FW_RULE" \
  --start-ip-address "$OUTBOUND_IP" --end-ip-address "$OUTBOUND_IP" || void "could not add the firewall rule to ${RESTORED}"
say "RESTORED firewall rules now: [$(az_read postgres flexible-server firewall-rule list -g "$RG" --server-name "$RESTORED" --query "[].[name,startIpAddress,endIpAddress]" -o tsv | tr '\t\n' ' ;')]"
say "SOURCE firewall rules (unchanged): [$(az_read postgres flexible-server firewall-rule list -g "$RG" --server-name "$SOURCE" --query "[].[name,startIpAddress,endIpAddress]" -o tsv | tr '\t\n' ' ;')]"

ok=0
for attempt in 1 2 3 4 5 6 7 8 9 10; do
  if count_set restored-P "$RESTORED_FQDN" "$DB"; then ok=1; break; fi
  say "RESTORED not reachable yet (attempt ${attempt}); retrying in 20s"; sleep 20
done
[ "$ok" = 1 ] || void "RESTORED ${RESTORED_FQDN} unreachable from the drill pod; an unreachable restore is VOID, never a failed restore"
P_OK=0; A_PIPE_OK=0; A_DIFF_OK=0; B_OK=0; BEFORE_OK=0; AFTER_OK=0; PLAIN_OK=0; ZERO_OK=0; ROLE_OK=0
say "COMPARE arm P (source @T0 vs RESTORED jtoye):"
crc=0; compare_counts source "$(cat "$WORK/counts-source-t0.json")" restored "$(cat "$WORK/counts-restored-P.json")" || crc=$?
case "$crc" in
  0) say "arm P counts equal"; P_OK=1 ;;
  1) say "arm P counts MISMATCH" ;;
  *) void "arm P comparison could not be evaluated" ;;
esac
rmk="$(psql_at "$RESTORED_FQDN" "$DB" "$BACKUP_ROLE" -v "b=$MARKER_BEFORE" -v "a=$MARKER_AFTER" \
  <<<"SELECT current_database(), (SELECT count(*) FROM products WHERE sku = :'b') AS before_marker, (SELECT count(*) FROM products WHERE sku = :'a') AS after_marker;" | counts_json)" \
  || void "could not read the markers on RESTORED"
say "MARKERS on RESTORED: $(jq -c . <<<"$rmk")"
if [ "$(jq -r .before_marker <<<"$rmk")" = 1 ]; then say "before-marker present"; BEFORE_OK=1; else say "before-marker MISSING on RESTORED"; fi
if [ "$(jq -r .after_marker <<<"$rmk")" = 0 ]; then say "after-marker absent"; AFTER_OK=1; else say "after-marker PRESENT on RESTORED (the restore point was not honoured)"; fi
say "arm P wall clock: restore request to Ready $((p_end - p_start))s; RPO here = T0 by construction"

# --- helpers for the arms (every one passes target_guard first) --------------
admin_sql() { # HOST DB — SQL on stdin, as the server admin
  target_guard "admin SQL (CREATE ROLE/CREATE DATABASE/GRANT)" "$1" || exit 1
  pod_exec "PGPASSWORD=$WORK/pw-admin DRILL_PW=$WORK/pw-drill" psql -X -q -v ON_ERROR_STOP=1 -A -t "$(conninfo "$1" "$2" "$ADMIN_LOGIN")" -f -
}
make_scratch_db() { # HOST NAME
  local host="$1" name="$2" erc=0
  target_guard "CREATE DATABASE ${name}" "$host" || exit 1
  admin_sql "$host" postgres <<<"CREATE DATABASE ${name};" || fail "CREATE DATABASE ${name} on RESTORED failed"
  admin_sql "$host" "$name" <<<'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";' 2>"$WORK/ext.err" || erc=$?
  if [ "$erc" -ne 0 ]; then
    say "  ${name}: uuid-ossp not creatable on RESTORED ($(head -c 160 "$WORK/ext.err" | tr '\n' ' ')); scratch-only shim public.uuid_generate_v4() := gen_random_uuid()"
    admin_sql "$host" "$name" <<<'CREATE FUNCTION public.uuid_generate_v4() RETURNS uuid LANGUAGE sql VOLATILE AS $f$ SELECT gen_random_uuid() $f$;' \
      || fail "could not create the uuid shim in ${name}"
  else
    say "  ${name}: uuid-ossp created"
  fi
}
restore_into() { # HOST DB DUMP
  local host="$1" db="$2" dump="$3" rc=0 nerr
  target_guard "pg_restore into ${db}" "$host" || exit 1
  pod_exec "PGPASSWORD=$WORK/pw-admin" pg_restore --no-owner --no-acl -d "$(conninfo "$host" "$db" "$ADMIN_LOGIN")" "$dump" </dev/null 2>"$WORK/restore-${db}.err" || rc=$?
  nerr="$(command grep -c '^pg_restore: error' "$WORK/restore-${db}.err" || true)"
  say "  pg_restore into ${db}: rc=${rc} errors=${nerr}$( [ "$nerr" = 0 ] || printf ' first: %s' "$(command grep -m3 '^pg_restore: error' "$WORK/restore-${db}.err" | cut -c1-160 | tr '\n' '|')")"
  admin_sql "$host" "$db" <<<"GRANT CONNECT ON DATABASE ${db} TO ${BACKUP_ROLE}; GRANT USAGE ON SCHEMA public TO ${BACKUP_ROLE}; GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${BACKUP_ROLE};" \
    || fail "could not grant ${BACKUP_ROLE} read on ${db}"
}
dump_restored() { # ROLE OUT [extra pg_dump args] — pg_dump of RESTORED's jtoye; prints rc
  local role="$1" out="$2" rc=0
  shift 2
  # shellcheck disable=SC2086
  pod_exec "PGPASSWORD=$(pw_for "$role")" pg_dump -d "$(conninfo "$RESTORED_FQDN" "$DB" "$role")" $DUMP_FLAGS "$@" -f "$out" </dev/null 2>"$WORK/dump.err" || rc=$?
  return "$rc"
}
pipeline_checks() { # DUMP — the image's own lines, unchanged, inside the pod
  pipeline_snippet "$WORK/backup-in-image.sh" "$1" > "$WORK/snippet.sh" || void "the image's pipeline lines could not be read"
  kubectl --context "$CONTEXT" -n "$NS" exec -i "$POD" -- bash -s < "$WORK/snippet.sh"
}

# --- pipeline lines: from the IMAGE (what runs nightly), compared with the repo --
kubectl --context "$CONTEXT" -n "$NS" exec "$POD" -- cat "$PIPELINE_IN_IMAGE" > "$WORK/backup-in-image.sh" || void "could not read ${PIPELINE_IN_IMAGE} from the image"
pipeline_snippet "$WORK/backup-in-image.sh" /x > "$WORK/snip-image" || void "image pipeline lines unreadable"
pipeline_snippet "$REPO_ROOT/$PIPELINE_REL" /x > "$WORK/snip-repo" || void "repo pipeline lines unreadable"
if cmp -s "$WORK/snip-image" "$WORK/snip-repo"; then say "pipeline check lines: image ${PIPELINE_IN_IMAGE} == repo ${PIPELINE_REL}"
else say "pipeline check lines: image DIFFERS from repo; the image's (what runs nightly) are used"; fi
say "pipeline lines used: $(sed -n '4,7p' "$WORK/snip-image" | tr '\n' '|')"

# --- ARM A ---------------------------------------------------------------------
say "STEP scratch role on RESTORED: ${DRILL_ROLE}"
target_guard "CREATE ROLE ${DRILL_ROLE}" "$RESTORED_FQDN" || exit 1
admin_sql "$RESTORED_FQDN" "$DB" <<SQL || fail "could not create ${DRILL_ROLE} on RESTORED"
\\getenv drill_pw DRILL_PW
CREATE ROLE ${DRILL_ROLE} LOGIN NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'drill_pw';
GRANT CONNECT ON DATABASE ${DB} TO ${DRILL_ROLE};
SET ROLE ${OWNER_ROLE};
GRANT USAGE ON SCHEMA public TO ${DRILL_ROLE};
GRANT SELECT ON ALL TABLES IN SCHEMA public TO ${DRILL_ROLE};
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO ${DRILL_ROLE};
RESET ROLE;
SQL
role_rb="$(admin_sql "$RESTORED_FQDN" "$DB" <<<"SELECT count(*) || ' (rolbypassrls=' || coalesce(bool_or(rolbypassrls)::text, 'n/a') || ', db=' || current_database() || ')' FROM pg_roles WHERE rolname = '${DRILL_ROLE}';")" \
  || fail "could not read ${DRILL_ROLE} back on RESTORED"
say "ROLE ${DRILL_ROLE} on RESTORED: ${role_rb}"
case "$role_rb" in "1 (rolbypassrls=false,"*) ROLE_OK=1 ;; *) fail "${DRILL_ROLE} on RESTORED reads '${role_rb}', not 1 NOBYPASSRLS role" ;; esac

say "STEP arm A plain pg_dump (RESTORED, as ${DRILL_ROLE}; expected to FAIL)"
arc=0; dump_restored "$DRILL_ROLE" /tmp/arm-a-plain.dump || arc=$?
say "  arm A plain pg_dump rc=${arc}: $(head -c 400 "$WORK/dump.err" | tr '\n' ' ')"
if [ "$arc" -ne 0 ] && command grep -q 'row-level security' "$WORK/dump.err"; then PLAIN_OK=1; say "arm A plain dump refused by row-level security (as D-10 states)"
elif [ "$arc" -eq 0 ]; then fail "arm A plain pg_dump SUCCEEDED as ${DRILL_ROLE}: RLS is not constraining a NOBYPASSRLS role"
else void "arm A plain pg_dump failed (rc=${arc}) for a reason other than row-level security"; fi

say "STEP arm A pg_dump --enable-row-security (RESTORED, as ${DRILL_ROLE})"
arc=0; dump_restored "$DRILL_ROLE" /tmp/arm-a.dump --enable-row-security || arc=$?
[ "$arc" -eq 0 ] || void "arm A --enable-row-security dump failed rc=${arc}: $(head -c 300 "$WORK/dump.err")"
out="$(pipeline_checks /tmp/arm-a.dump 2>&1)"; prc=$?
say "  pipeline checks on the arm A dump: rc=${prc} ${out}"
a_size="$(sed -nE 's/^PIPELINE PASS: ([0-9]+) bytes.*/\1/p' <<<"$out")"
if [ "$prc" -eq 0 ] && [ -n "$a_size" ]; then
  say "arm A pipeline checks PASS: ${a_size} bytes vs floor 1000 = $(( a_size / 1000 ))x, pg_restore --list readable"; A_PIPE_OK=1
else say "arm A pipeline checks REFUSED the partial dump"; fi
say "STEP restore arm A into drill_a (RESTORED)"
make_scratch_db "$RESTORED_FQDN" drill_a
restore_into "$RESTORED_FQDN" drill_a /tmp/arm-a.dump
count_set drill_a "$RESTORED_FQDN" drill_a || void "drill_a could not be counted"
say "COMPARE arm A (source @T0 vs drill_a):"
crc=0; compare_counts source "$(cat "$WORK/counts-source-t0.json")" drill_a "$(cat "$WORK/counts-drill_a.json")" || crc=$?
case "$crc" in
  1) say "arm A counts differ (the row count catches what the size floor and the listing passed)"; A_DIFF_OK=1 ;;
  0) say "arm A counts EQUAL the source: the counterexample did not materialise" ;;
  *) void "arm A comparison could not be evaluated" ;;
esac

# --- ARM B ---------------------------------------------------------------------
say "STEP arm B pg_dump (RESTORED, as ${BACKUP_ROLE})"
brc=0; dump_restored "$BACKUP_ROLE" /tmp/arm-b.dump || brc=$?
[ "$brc" -eq 0 ] || void "arm B pg_dump failed rc=${brc}: $(head -c 300 "$WORK/dump.err")"
out="$(pipeline_checks /tmp/arm-b.dump 2>&1)"; prc=$?
say "  pipeline checks on the arm B dump: rc=${prc} ${out}"
[ "$prc" -eq 0 ] || fail "the pipeline refused the BYPASSRLS dump"
say "STEP restore arm B into drill_b (RESTORED)"
make_scratch_db "$RESTORED_FQDN" drill_b
restore_into "$RESTORED_FQDN" drill_b /tmp/arm-b.dump
count_set drill_b "$RESTORED_FQDN" drill_b || void "drill_b could not be counted"
say "COMPARE arm B (source @T0 vs drill_b):"
crc=0; compare_counts source "$(cat "$WORK/counts-source-t0.json")" drill_b "$(cat "$WORK/counts-drill_b.json")" || crc=$?
case "$crc" in
  0) say "arm B counts equal"; B_OK=1 ;;
  1) say "arm B counts MISMATCH" ;;
  *) void "arm B comparison could not be evaluated" ;;
esac

# --- ZERO-BYTE -----------------------------------------------------------------
kubectl --context "$CONTEXT" -n "$NS" exec "$POD" -- bash -c ': > /tmp/zero.dump' || void "could not create the zero-byte file"
out="$(pipeline_checks /tmp/zero.dump 2>&1)"; zrc=$?
say "  pipeline checks on a zero-byte file: rc=${zrc} ${out}"
if [ "$zrc" -eq 1 ] && command grep -q 'below size floor' <<<"$out"; then say "zero-byte artefact refused by the size floor"; ZERO_OK=1
else say "zero-byte artefact NOT refused by the size floor"; fi

# --- verdict -------------------------------------------------------------------
say "VERDICT P=${P_OK} before=${BEFORE_OK} after=${AFTER_OK} role=${ROLE_OK} A_plain=${PLAIN_OK} A_pipeline=${A_PIPE_OK} A_differ=${A_DIFF_OK} B=${B_OK} zero=${ZERO_OK}"
if [ "$P_OK$BEFORE_OK$AFTER_OK$ROLE_OK$PLAIN_OK$A_PIPE_OK$A_DIFF_OK$B_OK$ZERO_OK" = 111111111 ]; then
  say "DRILL PASS (the restored server is deleted by the trap next; its read-back decides the exit status)"
  exit 0
fi
say "DRILL FAIL: at least one arm did not hold"
exit 1
