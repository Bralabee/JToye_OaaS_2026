#!/usr/bin/env bash
# ---------------------------------------------------------------------------------
# seed-media-review-fixtures.sh — (re)establish the three media_asset fixtures that
# e2e/media-review-320.spec.ts asserts against.
#
# WHY THIS EXISTS
#
#   The spec's own header says three rows "are seeded in the dev database", but the
#   seeding was a one-off hand-typed INSERT with ABSOLUTE timestamps. It decayed, and
#   the spec has been failing since.
#
#   Measured 2026-08-01. All three fixtures were written on 2026-07-27 with
#   quarantine_expires_at = 2026-07-30 18:42:39Z. When that horizon passed, the
#   quarantine sweep (MediaAssetRepository.findReclaimableQuarantine — status <> ACTIVE
#   AND quarantine_reclaimed_at IS NULL AND quarantine_expires_at < now) did exactly its
#   job and stamped quarantine_reclaimed_at = 2026-07-30 19:16:03Z on two of them.
#
#   MediaAssetDto derives the UI bit from that column pair:
#       redrivable = quarantineExpiresAt != null && quarantineReclaimedAt == null
#   so the reclaim flipped `redrivable` to false, "Re-process" stopped rendering, and
#   the spec's anti-vacuity guard fired:
#       "VOID: no redrivable row rendered — the criterion would pass on zero"
#
#   THE SPEC WAS RIGHT AND THE FIXTURE WAS WRONG. That guard is the only reason this
#   surfaced as a failure rather than as a silent pass over an empty queue — which is
#   what it would have been without it, since "nothing overflowed at 320px" is
#   trivially true when nothing rendered.
#
#   So the fix is not to re-type the INSERT with a later date — that just re-arms the
#   same time bomb. Every instant this script writes is RELATIVE TO NOW.
#
# WHY THE PENDING FIXTURE SURVIVED FIVE DAYS (and why that is not luck)
#
#   findStalePending selects any PENDING asset older than the cutoff, and MediaPendingReaper
#   would ordinarily flip it to FAILED. It does not, because V60 made that reaper FAIL
#   CLOSED: with no media_event_outbox row for the asset, the work was never dispatched,
#   the quarantine bytes are the vendor's only copy, and the reaper skips
#   (media.reaper.undispatched_skipped). These fixtures have no outbox row, so the
#   PENDING one is stable by design rather than by accident. This script does not create
#   outbox rows, and must not.
#
# WHAT THIS SEEDS, AND WHAT EACH ONE PROVES
#
#   ac55-fixture-redrivable  FAILED,  bytes retained   -> Re-upload AND Re-process
#   ac55-fixture-vetoed      FAILED,  bytes reclaimed  -> Re-upload ONLY
#   ac55-fixture-delayed     PENDING, aged past grace  -> the "Taking longer" section
#
#   The vetoed row is deliberately reclaimed: the spec asserts Re-process appears on
#   FEWER rows than Re-upload, so a fixture set where every row is redrivable would
#   fail — correctly. Do not "fix" that by making all three retained.
#
# WHAT THIS DOES NOT DO
#
#   It seeds DATABASE state only. No object is written to MinIO, so clicking Re-process
#   on the redrivable fixture would fail at the storage layer. That is honest for this
#   spec, which asserts the control is VISIBLE and UNCLIPPED at 320px and never clicks
#   it. If a future spec clicks Re-process, this script is not sufficient — extend it,
#   do not assume it already covers you.
#
#   It does not touch the quarantine sweep, the reaper, or any gate.
#
# CONFIGURATION (GLOBAL_RULE_6 — nothing environment-varying is hardcoded)
#   PG_CONTAINER   default jtoye-postgres        postgres container name
#   POSTGRES_USER  default from .env, else jtoye
#   POSTGRES_DB    default from .env, else jtoye
#   SEED_TENANT_ID default: DISCOVERED           the tenant owning the most shops
#   RETENTION_DAYS default 30                    how far ahead the quarantine horizon sits
#   DELAY_MINUTES  default 120                   how far back the PENDING row is dated
#
# EXIT CODES — uniform with this repo's other scripts
#   0 = all three fixtures are present and in the asserted state
#   1 = the seed ran but verification did not agree
#   2 = VOID — could not evaluate (no container, no tenant, psql missing). Never treat as 0.
# ---------------------------------------------------------------------------------
set -uo pipefail

PG_CONTAINER="${PG_CONTAINER:-jtoye-postgres}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
DELAY_MINUTES="${DELAY_MINUTES:-120}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

# Read credentials from .env when present, without exporting the whole file.
env_value() {
  local key="$1"
  [ -f "$ENV_FILE" ] || return 1
  local line
  line=$(grep -E "^${key}=" "$ENV_FILE" | tail -1) || return 1
  [ -n "$line" ] || return 1
  printf '%s' "${line#*=}"
}

PGUSER="${POSTGRES_USER:-$(env_value POSTGRES_USER || echo jtoye)}"
PGDB="${POSTGRES_DB:-$(env_value POSTGRES_DB || echo jtoye)}"

echo "seed-media-review-fixtures  ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
echo "  container : $PG_CONTAINER"
echo "  database  : $PGDB (user $PGUSER)"

void() { echo "VOID: $*" >&2; exit 2; }

command -v docker >/dev/null 2>&1 || void "docker not on PATH"
docker inspect -f '{{.State.Running}}' "$PG_CONTAINER" >/dev/null 2>&1 \
  || void "container $PG_CONTAINER is not running — start the stack first"

# One helper so every query goes through the same path. -v ON_ERROR_STOP=1 is
# load-bearing: without it psql reports success after a failed statement.
psql_q() {
  docker exec -i "$PG_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -tAc "$1"
}

# --- Discover the tenant rather than hardcode it -----------------------------------
if [ -n "${SEED_TENANT_ID:-}" ]; then
  TENANT_ID="$SEED_TENANT_ID"
  echo "  tenant    : $TENANT_ID (from SEED_TENANT_ID)"
else
  TENANT_ID=$(psql_q "select tenant_id from shops group by tenant_id order by count(*) desc, tenant_id limit 1;")
  rc=$?
  [ "$rc" -eq 0 ] || void "tenant discovery query failed (rc=$rc)"
  [ -n "$TENANT_ID" ] || void "no tenant owns any shop — nothing to seed against"
  echo "  tenant    : $TENANT_ID (discovered — owns the most shops)"
fi

# --- Seed ---------------------------------------------------------------------------
# Idempotent by object_key: re-running RESTORES the asserted state rather than
# accumulating rows. The tenant GUC is pinned even though the dev role bypasses RLS,
# so this behaves identically under a non-superuser role.
#
# sha256 is a fixed per-fixture literal because (tenant_id, sha256) is UNIQUE — a random
# digest would insert a duplicate row on every run instead of updating the same one.
seed_sql=$(cat <<SQL
select set_config('app.current_tenant_id', '$TENANT_ID', false);

insert into media_asset
  (id, tenant_id, object_key, sha256, content_type, status, flagged, failure_reason,
   created_at, quarantine_expires_at, quarantine_reclaimed_at, process_attempts, version)
values
  (gen_random_uuid(), '$TENANT_ID',
   '$TENANT_ID/quarantine/ac55-fixture-redrivable.jpg',
   repeat('a', 64), 'image/jpeg', 'FAILED', false, 'Processing stalled before it finished',
   now() - interval '$DELAY_MINUTES minutes',
   now() + interval '$RETENTION_DAYS days', null, 0, 0),

  (gen_random_uuid(), '$TENANT_ID',
   '$TENANT_ID/quarantine/ac55-fixture-vetoed.jpg',
   repeat('b', 64), 'image/jpeg', 'FAILED', false, 'That file is not a supported image',
   now() - interval '$DELAY_MINUTES minutes',
   now() + interval '$RETENTION_DAYS days', now(), 0, 0),

  (gen_random_uuid(), '$TENANT_ID',
   '$TENANT_ID/quarantine/ac55-fixture-delayed.jpg',
   repeat('c', 64), 'image/jpeg', 'PENDING', false, null,
   now() - interval '$DELAY_MINUTES minutes',
   now() + interval '$RETENTION_DAYS days', null, 0, 0)
on conflict (tenant_id, sha256) do update set
  object_key              = excluded.object_key,
  status                  = excluded.status,
  failure_reason          = excluded.failure_reason,
  created_at              = excluded.created_at,
  quarantine_expires_at   = excluded.quarantine_expires_at,
  quarantine_reclaimed_at = excluded.quarantine_reclaimed_at,
  process_attempts        = excluded.process_attempts;
SQL
)

seed_out=$(printf '%s' "$seed_sql" | docker exec -i "$PG_CONTAINER" \
  psql -U "$PGUSER" -d "$PGDB" -v ON_ERROR_STOP=1 -q 2>&1); rc=$?
if [ "$rc" -ne 0 ]; then
  echo "$seed_out" >&2
  void "seed statement failed (rc=$rc)"
fi

# --- Verify, by reading back the exact predicate the DTO uses -----------------------
# NOT by counting rows: three rows in the wrong state would pass a count. This asserts
# `redrivable` as MediaAssetDto computes it — expires_at present AND reclaimed_at null.
redrivable=$(psql_q "select count(*) from media_asset
  where tenant_id = '$TENANT_ID' and object_key like '%ac55-fixture-redrivable%'
    and status = 'FAILED' and quarantine_expires_at is not null
    and quarantine_expires_at > now() and quarantine_reclaimed_at is null;")
vetoed=$(psql_q "select count(*) from media_asset
  where tenant_id = '$TENANT_ID' and object_key like '%ac55-fixture-vetoed%'
    and status = 'FAILED' and quarantine_reclaimed_at is not null;")
delayed=$(psql_q "select count(*) from media_asset
  where tenant_id = '$TENANT_ID' and object_key like '%ac55-fixture-delayed%'
    and status = 'PENDING' and created_at < now() - interval '30 minutes';")

echo "  redrivable (FAILED, bytes retained, horizon in future) : $redrivable  (expect 1)"
echo "  vetoed     (FAILED, bytes reclaimed)                   : $vetoed  (expect 1)"
echo "  delayed    (PENDING, aged past the 30-min grace)       : $delayed  (expect 1)"

if [ "$redrivable" = "1" ] && [ "$vetoed" = "1" ] && [ "$delayed" = "1" ]; then
  echo "PASS: all three fixtures present — e2e/media-review-320.spec.ts can now assert non-vacuously."
  exit 0
fi

echo "FAIL: the seed ran but the fixtures are not in the asserted state." >&2
exit 1
