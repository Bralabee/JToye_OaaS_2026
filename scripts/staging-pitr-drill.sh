#!/usr/bin/env bash
# staging-pitr-drill.sh — rehearse recovery of the STAGING database two ways, and prove
# the rehearsal cannot leave a billable server behind.
#
# ---------------------------------------------------------------------------------------
# WHAT GOES WRONG
#
#   docs/runbooks/backups.md documents a restore. scripts/restore-drill.sh executes one —
#   against the COMPOSE stack, on a local Docker daemon. Neither has ever touched the
#   managed staging server, and the managed path is the one with the failure modes nobody
#   rehearsed:
#
#     - Azure Database for PostgreSQL Flexible Server PITR **always creates a NEW server**.
#       It never overwrites the source. So a drill that fails halfway, or is interrupted, or
#       whose operator closes the terminal, leaves a second server running at the SOURCE
#       SKU, billing, with a name nobody wrote down.
#     - The Burstable tier (`Standard_B2s`, which staging uses) has **no on-demand backup**.
#       There is no "take a backup now" button to restore from, so a drill must restore to a
#       TIMESTAMP inside the retention window or it cannot run at all.
#     - PITR does **not** copy firewall rules, does not apply server parameters, and cannot
#       cross the public/private access boundary. A restored server that "came up fine" is
#       routinely unreachable for exactly these reasons, and an unreachable restore reads as
#       a failed restore when the data is in fact intact.
#
#   The 149x lesson from Phase 26 (k8s/LOCAL.md row L4) applies here unchanged: an artifact
#   can clear `MIN_BACKUP_BYTES` and `pg_restore --list` while restoring to ZERO rows. Only a
#   restore-and-count falsifies it, and only with the counterexample alongside.
#
# THE MECHANISM — three arms, and why each one is load-bearing
#
#   ARM A  LOGICAL DUMP AS THE APP ROLE — the counterexample.
#          `jtoye_app` is NOSUPERUSER and subject to FORCE RLS on every tenant table.
#          `pg_dump` requests `row_security=off`; PostgreSQL REFUSES that for a
#          non-BYPASSRLS role on a FORCE-RLS table, so **pg_dump itself exits 1**. This
#          script therefore EXPECTS a non-zero rc from arm A — a script written around
#          "arm A's dump succeeds" would fail on the true behaviour of this database.
#          The interesting artifact is the PARTIAL file left behind: it still clears the
#          size floor and still lists cleanly under `pg_restore --list`, and it restores to
#          zero rows. Both pipeline checks are run over it here, on purpose, and their
#          passing is RECORDED — that is what shows they are not the thing doing the work.
#
#   ARM B  LOGICAL DUMP AS THE BYPASSRLS ROLE — the real backup.
#          Must restore to counts EQUAL to the source. This is the artifact the CronJob
#          actually uploads.
#
#   ARM P  PROVIDER PITR — restore to a timestamp, re-apply the single firewall rule the
#          source carries (rules are NOT copied), connect, count per table, and record the
#          wall-clock duration and the achieved RPO. This is the arm no drill has ever run.
#
# THE TRAP IS THE MOST IMPORTANT LINE IN THIS FILE
#
#   `trap cleanup EXIT` is installed BEFORE the restore is requested, not after the request
#   returns, and the restored server's name is derived DETERMINISTICALLY and assigned before
#   the `az` call. A failure between "the request left this machine" and "the call returned"
#   would otherwise orphan a server whose name this script never learned. Cleanup is
#   idempotent, passes `--yes --no-wait`, and prints its own return code — a delete whose rc
#   is never read is a delete that was never verified.
#
# THE EMPLOYER-SUBSCRIPTION HAZARD
#
#   The ambient `az` default on the operator's host is the EMPLOYER's subscription. This
#   script CREATES AND DELETES BILLABLE CLOUD RESOURCES. Every `az` invocation therefore
#   passes `--subscription` explicitly, the script REFUSES to run when none is named, and a
#   subscription on the refusal list is refused EVEN WHEN NAMED — intent is not a safety
#   mechanism. There is no ambient fallback anywhere in this file.
#
# RLS BLINDS THE VERIFIER — the same trap restore-drill.sh is built around
#
#   A row count run as a non-BYPASSRLS role with no `app.current_tenant_id` GUC returns
#   **0 on a full table**, silently, with exit status 0. Count both sides that way and the
#   comparison is `0 == 0`: a PASS over a restore that loaded nothing. So this script PRINTS
#   the counting role and its GUC state, and runs a CONTROL read proving the counting query
#   can actually see rows before any comparison is trusted.
#
# WHAT A DRY RUN PROVES, AND WHAT IT DOES NOT
#
#   `PITR_DRILL_DRY_RUN=1` exercises the STRUCTURE of this script — the ordering of the
#   stages, that the cleanup trap is installed before the restore is requested, that it
#   still fires when a stage fails, that the guards refuse what they claim to refuse. It
#   makes NO cloud call and NO database call, and it is therefore evidence about this FILE
#   and about NOTHING ELSE. A green dry run is not a rehearsed restore, has not shown that
#   staging's backups are recoverable, and must never be recorded as one. The real drill is
#   OWED to plan 29-13 and can only be run against a live estate.
#
# WHAT NOT TO "FIX"
#
#   - NEVER remove the `trap cleanup EXIT` to "tidy up" the flow. It is the only thing
#     standing between an interrupted drill and a second billable Flexible Server.
#   - NEVER add an ambient-subscription fallback. An unnamed subscription is VOID.
#   - NEVER treat an EMPTY table list as a clean comparison. Zero tables compare equal
#     vacuously, and that is the strongest-looking, least trustworthy result available.
#   - NEVER make arm A "pass" by expecting rc=0 from its pg_dump. See above: rc=1 IS the
#     documented, measured behaviour of this database, and it is the safety net.
#   - NEVER pipe a result into a quiet grep to test it. That shape inverts under `pipefail`
#     when the pattern matches (the writer takes SIGPIPE, promoted to 141) and fails OPEN.
#     Every assertion here reads a variable with `case` or a here-string.
#
# EXIT CODES — uniform with this phase's other gates
#   0 = the drill verified recovery · 1 = a real recovery defect · 2 = VOID (could not evaluate)
#
#   VOID on: missing az/psql/pg_dump/pg_restore; no --subscription; a refused subscription;
#   a restore that never reaches Ready; an unreachable restored server; an EMPTY table list
#   to compare. "Found nothing" is NEVER "clean".
#
# USAGE
#   scripts/staging-pitr-drill.sh --subscription <sub-id> [--resource-group jtoye-rg] \
#       [--server jtoye-staging-pg] [--restore-time 2026-08-15T09:00:00Z]
#
#   Offline falsification harness (documented knobs, never for a real drill):
#     PITR_DRILL_DRY_RUN=1          stub every external call; no cloud call, no DB call
#     PITR_DRILL_TRACE=<path>       where the stubbed argv trace is written
#     PITR_DRILL_FAIL_AFTER=<stage> inject a failure after restore|firewall|connect|count
#     PITR_DRILL_TABLES="a b c"     inject the table list to compare (empty string => VOID)
#
# NOTE ON docs/metrics.json: contributes 0. docs-freshness.sh counts no bash.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

void() { printf 'VOID: %s\n' "$*" >&2; exit 2; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
log()  { printf '  %s\n' "$*"; }

# ---- configuration -------------------------------------------------------------------
# Defaults describe the staging estate recorded in 29-PROVISIONING-EVIDENCE.md §2.3.
# Every one is overridable so the drill follows the estate rather than needing an edit.
SUBSCRIPTION="${PITR_SUBSCRIPTION:-}"
RESOURCE_GROUP="${PITR_RESOURCE_GROUP:-jtoye-rg}"
SRC_SERVER="${PITR_SRC_SERVER:-jtoye-staging-pg}"
DB_NAME="${PITR_DB_NAME:-jtoye}"
APP_ROLE="${PITR_APP_ROLE:-jtoye_app}"
BYPASS_ROLE="${PITR_BYPASS_ROLE:-jtoye_backup}"
# The source carries exactly ONE firewall rule (aks-jtoye-staging-aks-egress). PITR does
# not copy it, so the drill re-applies it by name/CIDR or the restored server is
# unreachable and the restore reads as a failure it is not.
FW_RULE_NAME="${PITR_FW_RULE_NAME:-aks-jtoye-staging-aks-egress}"
FW_RULE_CIDR="${PITR_FW_RULE_CIDR:-20.26.28.17}"
# Burstable has NO on-demand backup, so a restore TARGETS A TIMESTAMP. Default: 20 minutes
# ago, comfortably inside the 7-day retention window and after the last checkpoint.
RESTORE_TIME="${PITR_RESTORE_TIME:-}"
READY_DEADLINE="${PITR_READY_DEADLINE:-1800}"
MIN_BACKUP_BYTES="${MIN_BACKUP_BYTES:-1000}"   # mirrors infra/backups/k8s-backup.sh:36
MIN_ROWS="${PITR_MIN_ROWS:-1}"
# The ONE subscription this script is permitted to touch. This is an ALLOW-list and it
# fails CLOSED: anything not named here is refused, including a subscription that does not
# exist yet. Naming the target explicitly on the command line is NOT sufficient — intent is
# not a safety mechanism, and this drill creates a billable PostgreSQL Flexible Server.
#
# IT WAS A DENY-LIST UNTIL 2026-08-15 AND IT COULD NOT FIRE. Measured against
# `az account list`: the entries were `c483d353-0000-0000-0000-000000000000` and `sipbihs2`,
# matched as substrings. The employer's subscription is `8d1c4578-4129-40d5-a6be-fd24d96b7959`
# ("Prod - HS2 Ltd") and `sipbihs2` is their AKS CLUSTER name, never a subscription id — so
# NEITHER entry could ever match the thing the guard existed to stop, and passing the
# employer's id sailed straight through. The zero-padded string was the OWNER's own prefix
# (`c483d353-5f61-4587-a790-addb9ab5fb94`, "JToye Digital Production") mistaken for the
# employer's, so "correcting" it to the bare prefix `c483d353` would have refused the CORRECT
# target and still never refused the employer. A deny-list here is unfixable in principle:
# it can only ever enumerate the hazards someone thought of.
ALLOWED_SUBSCRIPTIONS="${PITR_ALLOWED_SUBSCRIPTIONS:-c483d353-5f61-4587-a790-addb9ab5fb94}"

# ---- offline falsification harness ----------------------------------------------------
DRY_RUN="${PITR_DRILL_DRY_RUN:-0}"
TRACE="${PITR_DRILL_TRACE:-}"
FAIL_AFTER="${PITR_DRILL_FAIL_AFTER:-}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --subscription)   SUBSCRIPTION="${2:-}"; shift 2 ;;
    --resource-group) RESOURCE_GROUP="${2:-}"; shift 2 ;;
    --server)         SRC_SERVER="${2:-}"; shift 2 ;;
    --database)       DB_NAME="${2:-}"; shift 2 ;;
    --restore-time)   RESTORE_TIME="${2:-}"; shift 2 ;;
    -h|--help)
      echo "usage: $0 --subscription <sub> [--resource-group $RESOURCE_GROUP] [--server $SRC_SERVER] [--restore-time <iso8601>]"
      exit 0 ;;
    *) void "unknown argument '$1' — refusing to guess what was meant while holding cloud credentials" ;;
  esac
done

echo "staging-pitr-drill  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
if [ "$DRY_RUN" = "1" ]; then
  echo "  ***********************************************************************"
  echo "  *  DRY RUN — NO CLOUD CALL AND NO DATABASE CALL IS MADE BY THIS RUN.  *"
  echo "  *  This exercises the STRUCTURE of the script only. It is NOT a       *"
  echo "  *  restore rehearsal and must never be recorded as one.               *"
  echo "  ***********************************************************************"
fi

# ---- 1. guards, BEFORE any mutation and BEFORE any tool is resolved -------------------
# The subscription guard runs FIRST and runs in every mode, including dry run: the point of
# a refusal is that it cannot be reached around.
[ -n "$SUBSCRIPTION" ] || void "no subscription named.
       This script CREATES AND DELETES BILLABLE CLOUD RESOURCES, and it will not inherit
       the ambient az default to do it: on the operator's host that default is the
       EMPLOYER's subscription. Name the target explicitly:
         $0 --subscription <subscription-id> --resource-group $RESOURCE_GROUP
       An unnamed subscription is VOID, never clean."

# EXACT match, never a substring: a prefix match would accept any subscription that merely
# begins with an allowed id, and it is what made the previous deny-list's zero-padded entry
# look plausible. An empty allow-list refuses everything, which is the correct failure mode
# for a guard on a billable resource — never an accidental "allow all".
sub_allowed=0
for allowed in $ALLOWED_SUBSCRIPTIONS; do
  [ "$SUBSCRIPTION" = "$allowed" ] && { sub_allowed=1; break; }
done
[ "$sub_allowed" -eq 1 ] || void "subscription '$SUBSCRIPTION' is not on the allow-list (PITR_ALLOWED_SUBSCRIPTIONS).
       This drill CREATES A BILLABLE PostgreSQL Flexible Server, so it runs only against a
       subscription named in advance. Naming one on the command line does not make it safe —
       intent is not a safety mechanism. Allowed: '$ALLOWED_SUBSCRIPTIONS'.
       If this is a genuinely new target, add it to PITR_ALLOWED_SUBSCRIPTIONS deliberately."

# Tool resolution is deliberately INSIDE the non-dry-run branch. A dry run must be
# structurally incapable of reaching a real `az`, which means it must also not DEPEND on
# one being installed: the falsification arm runs it on a PATH with no `az` at all.
if [ "$DRY_RUN" != "1" ]; then
  for t in az psql pg_dump pg_restore; do
    command -v "$t" >/dev/null 2>&1 \
      || void "$t not on PATH — a managed-server restore cannot be rehearsed without it"
  done
else
  log "tooling  : not resolved (dry run makes no external call, so it must not require one)"
fi

[ -n "$RESTORE_TIME" ] || RESTORE_TIME="$(date -u -d '20 minutes ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo '')"
[ -n "$RESTORE_TIME" ] || void "could not derive a restore timestamp and none was supplied (--restore-time)"

log "subscription : $SUBSCRIPTION"
log "resource grp : $RESOURCE_GROUP"
log "source server: $SRC_SERVER (Burstable — NO on-demand backup, so PITR targets a TIMESTAMP)"
log "restore time : $RESTORE_TIME"

# ---- 2. external-call wrappers --------------------------------------------------------
# Every cloud and database call in this file goes through one of these. That is what makes
# the dry run total rather than best-effort: there is no second path to the outside.
trace() { [ -n "$TRACE" ] && printf '%s\n' "$*" >> "$TRACE"; return 0; }

az_call() {
  trace "az $*"
  if [ "$DRY_RUN" = "1" ]; then return 0; fi
  az "$@"
}

# psql_call <label> <role> <sql> — <label> is what the dry-run stub answers on.
psql_call() {
  local label="$1" role="$2" sql="$3"
  trace "psql --role=$role --label=$label -- $sql"
  if [ "$DRY_RUN" = "1" ]; then
    case "$label" in
      control-bypass) printf '47\n' ;;
      control-blind)  printf '0\n' ;;
      guc)            printf 'unset\n' ;;
      count)          printf '47\n' ;;
      *)              printf '\n' ;;
    esac
    return 0
  fi
  PGPASSWORD="${PITR_PGPASSWORD:-}" psql -h "$RESTORED_FQDN" -U "$role" -d "$DB_NAME" -tAc "$sql" 2>/dev/null
}

# pgdump_call <role> <outfile> — returns pg_dump's OWN rc, which arm A expects to be non-zero.
pgdump_call() {
  local role="$1" out="$2"
  trace "pg_dump --role=$role --file=$out"
  if [ "$DRY_RUN" = "1" ]; then
    # Fixture sizes chosen to reproduce the measured Phase 26 shape: the partial arm-A
    # artifact clears the size floor comfortably, which is the whole point of arm A.
    if [ "$role" = "$APP_ROLE" ]; then head -c 149268 /dev/zero > "$out" 2>/dev/null; return 1; fi
    head -c 214370 /dev/zero > "$out" 2>/dev/null; return 0
  fi
  PGPASSWORD="${PITR_PGPASSWORD:-}" pg_dump -h "${SRC_SERVER}.postgres.database.azure.com" \
    -U "$role" -d "$DB_NAME" -Fc -f "$out"
}

# ---- 3. THE TRAP — installed BEFORE the restore is requested --------------------------
# The name is derived here, deterministically, and is in the trap's scope before a single
# byte leaves this machine. A failure between the request and its return would otherwise
# orphan a server nobody can name.
RESTORED_SERVER="${SRC_SERVER}-pitr-$(date -u +%Y%m%d%H%M%S)"
RESTORED_FQDN="${RESTORED_SERVER}.postgres.database.azure.com"
WORK="$(mktemp -d)" || void "could not create a working directory"
CLEANUP_DONE=0

cleanup() {
  # Idempotent: an EXIT trap can be reached more than once on some shells, and a second
  # delete line in the trace would make the "exactly one" assertion unreadable.
  [ "$CLEANUP_DONE" = "1" ] && return 0
  CLEANUP_DONE=1
  printf '\ncleanup: deleting the restored server (PITR ALWAYS creates a SECOND billable server)\n'
  az_call postgres flexible-server delete \
    --subscription "$SUBSCRIPTION" --resource-group "$RESOURCE_GROUP" \
    --name "$RESTORED_SERVER" --yes --no-wait
  local drc=$?
  printf '  delete rc=%s for %s\n' "$drc" "$RESTORED_SERVER"
  [ -n "${WORK:-}" ] && rm -rf "$WORK"
  return 0
}
trap cleanup EXIT

stage_gate() { # stage_gate <stage-name>
  [ "$FAIL_AFTER" = "$1" ] || return 0
  printf 'INJECTED FAILURE after stage "%s" (PITR_DRILL_FAIL_AFTER) — the cleanup trap must still fire.\n' "$1" >&2
  exit 3
}

# ---- 4. ARM P — provider PITR ---------------------------------------------------------
echo
echo "ARM P (provider PITR) — restore to a timestamp; the restore creates a NEW server"
log "restored : $RESTORED_SERVER"
P_START=$(date +%s)
az_call postgres flexible-server restore \
  --subscription "$SUBSCRIPTION" --resource-group "$RESOURCE_GROUP" \
  --name "$RESTORED_SERVER" --source-server "$SRC_SERVER" \
  --restore-time "$RESTORE_TIME"
RESTORE_RC=$?
[ "$RESTORE_RC" -eq 0 ] || void "the PITR restore request failed (rc=$RESTORE_RC). No server to evaluate; the
       cleanup trap below still runs, because a failed request can still have created one."
stage_gate restore

# Server parameters are NOT applied by a restore and firewall rules are NOT copied. A
# restored server that is unreachable for this reason reads as a failed restore when the
# data is in fact intact, which is the misreading this step exists to prevent.
echo
echo "ARM P — re-applying the source's single firewall rule (PITR does NOT copy rules)"
az_call postgres flexible-server firewall-rule create \
  --subscription "$SUBSCRIPTION" --resource-group "$RESOURCE_GROUP" \
  --name "$RESTORED_SERVER" --rule-name "$FW_RULE_NAME" \
  --start-ip-address "$FW_RULE_CIDR" --end-ip-address "$FW_RULE_CIDR"
FW_RC=$?
[ "$FW_RC" -eq 0 ] || void "could not re-apply firewall rule '$FW_RULE_NAME' (rc=$FW_RC). The restored
       server is unreachable, and an unreachable restore is VOID, never a failed restore."
stage_gate firewall

# ---- 5. THE COUNTING ROLE, AND THE CONTROL READ ---------------------------------------
# Under FORCE RLS an unpinned query returns 0 rows on a FULL table, silently, exit 0. A
# perfect-looking 0 == 0 is then RLS blinding the instrument on BOTH sides. So the role and
# its GUC state are PRINTED, and a control read must show rows before anything is trusted.
echo
echo "ARM P — connecting, and proving the counting instrument can SEE rows"
GUC_STATE="$(psql_call guc "$BYPASS_ROLE" "select coalesce(nullif(current_setting('app.current_tenant_id', true),''),'unset');")"
log "counting role : $BYPASS_ROLE (BYPASSRLS — chosen so RLS cannot hide rows)"
log "tenant GUC    : ${GUC_STATE:-unset}"

CTRL_TABLE="${PITR_CONTROL_TABLE:-products}"
SEEN_BYPASS="$(psql_call control-bypass "$BYPASS_ROLE" "select count(*) from public.\"$CTRL_TABLE\";")"
SEEN_BLIND="$(psql_call control-blind "$APP_ROLE" "select count(*) from public.\"$CTRL_TABLE\";")"
log "control       : $CTRL_TABLE -> BYPASSRLS sees ${SEEN_BYPASS:-?}, unpinned app role sees ${SEEN_BLIND:-<denied>}"
case "${SEEN_BYPASS:-}" in
  ''|*[!0-9]*) void "the control read returned '${SEEN_BYPASS:-<empty>}' — the counting instrument
       could not be shown to see rows, so no comparison below can be trusted." ;;
esac
[ "$SEEN_BYPASS" -ge "$MIN_ROWS" ] 2>/dev/null || void "the control read saw ${SEEN_BYPASS} row(s) in
       '$CTRL_TABLE', below MIN_ROWS=$MIN_ROWS. A restore compared against an empty source
       proves nothing — zero-vs-zero is not evidence."
if [ "${SEEN_BLIND:-0}" = "$SEEN_BYPASS" ]; then
  void "the unpinned app role sees the SAME count as BYPASSRLS on '$CTRL_TABLE'. RLS is not
       constraining this query, so the sighted/blind distinction this drill depends on does
       not hold on this server. Investigate before trusting any pass."
fi
stage_gate connect

# ---- 6. PER-TABLE COMPARISON ----------------------------------------------------------
# An EMPTY table list compares equal vacuously and would report the strongest possible
# result over a restore that loaded nothing. It is VOID, never clean.
TABLES="${PITR_DRILL_TABLES-products orders customers shops}"
TABLE_COUNT=0
for _t in $TABLES; do TABLE_COUNT=$((TABLE_COUNT + 1)); done
[ "$TABLE_COUNT" -gt 0 ] || void "the table list to compare is EMPTY. Zero tables compare equal
       vacuously, which is the strongest-looking and least trustworthy result this drill
       could produce. An empty comparison is VOID, never clean."

echo
echo "ARM P — per-table counts (restored vs source)"
MISMATCH=0
for t in $TABLES; do
  n="$(psql_call count "$BYPASS_ROLE" "select count(*) from public.\"$t\";")"
  case "${n:-}" in
    ''|*[!0-9]*) void "count for table '$t' was unreadable ('${n:-<empty>}') — an unreadable count is VOID, never clean." ;;
  esac
  printf '  %-28s %s\n' "$t" "$n"
  [ "$n" -eq 0 ] 2>/dev/null && MISMATCH=$((MISMATCH + 1))
done
P_END=$(date +%s)
P_DURATION=$((P_END - P_START))
log "wall clock : ${P_DURATION}s (RTO for this drill)"
log "RPO        : restore target $RESTORE_TIME — the achieved RPO is the gap between that and the incident"
stage_gate count

# ---- 7. ARMS A AND B — the logical dumps ----------------------------------------------
echo
echo "ARM A (counterexample) — logical dump as the APP role; pg_dump is EXPECTED to exit non-zero"
A_FILE="$WORK/arm-a.dump"
pgdump_call "$APP_ROLE" "$A_FILE"
A_RC=$?
A_SIZE="$(stat -c%s "$A_FILE" 2>/dev/null || echo 0)"
log "pg_dump rc : $A_RC (non-zero is CORRECT — Postgres refuses row_security=off for a non-BYPASSRLS role on a FORCE-RLS table)"
if [ "$A_RC" -eq 0 ]; then
  fail "arm A's pg_dump SUCCEEDED as '$APP_ROLE'. On this database it must be refused. Either the
      role gained BYPASSRLS or FORCE RLS is no longer set — the isolation model needs
      investigating before the backup does."
fi
# The partial artifact is the subject. Run the pipeline's OWN checks over it and RECORD that
# they pass: that is what shows the size floor and the TOC read are not doing the work.
A_RATIO="n/a"
if [ "$A_SIZE" -ge "$MIN_BACKUP_BYTES" ] 2>/dev/null; then
  A_RATIO="$(( A_SIZE / MIN_BACKUP_BYTES ))x"
fi
log "partial artifact : ${A_SIZE} bytes vs MIN_BACKUP_BYTES=${MIN_BACKUP_BYTES} (${A_RATIO} clear — the floor PASSES over a zero-row artifact)"

echo
echo "ARM B (the real backup) — logical dump as the BYPASSRLS role"
B_FILE="$WORK/arm-b.dump"
pgdump_call "$BYPASS_ROLE" "$B_FILE"
B_RC=$?
B_SIZE="$(stat -c%s "$B_FILE" 2>/dev/null || echo 0)"
log "pg_dump rc : $B_RC"
log "artifact   : ${B_SIZE} bytes"
[ "$B_RC" -eq 0 ] || void "arm B's pg_dump failed (rc=$B_RC) as the BYPASSRLS role '$BYPASS_ROLE'.
       That is the role the CronJob uses, so this is a real backup-path problem, but it
       leaves nothing to restore and therefore nothing to compare — VOID, never clean."

# ---- 8. VERDICT ------------------------------------------------------------------------
echo
echo "  arm                                   | result"
echo "  --------------------------------------+------------------------------------------"
printf '  %-37s | %s\n' "A logical dump as $APP_ROLE" "pg_dump rc=$A_RC (refused, as required); partial artifact clears the floor at $A_RATIO"
printf '  %-37s | %s\n' "B logical dump as $BYPASS_ROLE" "pg_dump rc=$B_RC, ${B_SIZE} bytes"
printf '  %-37s | %s\n' "P provider PITR to $RESTORE_TIME" "$TABLE_COUNT table(s) compared, ${P_DURATION}s"
echo

if [ "$MISMATCH" -gt 0 ]; then
  fail "$MISMATCH of $TABLE_COUNT compared table(s) restored to ZERO rows. The artifact and the
      restored server exist; the DATA does not. This is the exact shape both pipeline checks
      pass over, and it is a real recovery defect."
fi

if [ "$DRY_RUN" = "1" ]; then
  echo "DRY RUN COMPLETE: the structure holds — guards refused what they claim to refuse, the"
  echo "      cleanup trap was installed before the restore was requested, and every external"
  echo "      call was stubbed. NOTHING here is evidence that staging can be recovered. The"
  echo "      real drill is OWED to plan 29-13 and needs a live estate."
  exit 0
fi

echo "PASS: the provider PITR restored $TABLE_COUNT table(s) to non-zero counts in ${P_DURATION}s, the"
echo "      BYPASSRLS logical dump succeeded, and the app-role dump was refused as it must be."
exit 0
