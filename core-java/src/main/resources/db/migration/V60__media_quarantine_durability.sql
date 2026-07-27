-- V60: Phase 27 / plan 27-01 — quarantine durability columns for media_asset.
--
-- WHY THIS EXISTS. MediaPendingReaper currently selects on status ALONE
-- (MediaAssetRepository.findStalePending: status = PENDING AND created_at < now() - 15 min)
-- and then permanently deletes the quarantined source bytes. During a RabbitMQ / media-outbox
-- outage the event has provably NOT been dispatched — MediaEventOutboxFlusher backs off
-- 5+10+20+40+80+160+300+300+300s (~20 min) to MAX_ATTEMPTS, so at the 15-minute reap cutoff the
-- outbox row is still PENDING with ~7-8 attempts. The reaper deletes the vendor's upload anyway.
-- The transactional outbox protects the EVENT; nothing protected the OBJECT. These three columns
-- are what let the sweep tell "never dispatched" from "dispatched and stalled", and what let the
-- bytes be retained on a declared horizon instead of destroyed as a 15-minute accident.
--
-- process_attempts        — counts HUMAN-INITIATED re-drives only (MediaAssetService
--                           .redriveFromQuarantine), bounded by jtoye.media.max-process-attempts so
--                           a vendor cannot loop a permanently-broken asset through the pipeline.
--                           It is NOT a publish-attempt counter (media_event_outbox.attempts is
--                           that) and NOT COUNT(*) of outbox rows (the WR-01 re-upload path also
--                           inserts one).
-- quarantine_expires_at   — when the retained raw bytes become reclaimable. NULL means "no retained
--                           raw bytes were ever claimed" — which is CORRECT for every pre-existing
--                           row: V53-backfilled ACTIVE assets have no quarantine object at all.
-- quarantine_reclaimed_at — THE SENTINEL. NON-NULL means "the quarantine object for this asset is
--                           gone". Stamped by the retention sweep on a CONFIRMED delete
--                           (StorageService.deleteByKeyChecked returning true), by the worker on
--                           success, and by the worker's validation-veto discard. It is the single
--                           termination condition of the retention sweep, and the negation of the
--                           `redrivable` DTO bit.
--
-- WHY THE SENTINEL IS A NEW COLUMN AND NOT "set quarantine_expires_at = NULL". The retention
-- sweep's legacy arm selects rows where quarantine_expires_at IS ALREADY NULL (they predate this
-- migration). Nulling that column as a termination marker would therefore be a no-op for exactly
-- the rows the legacy arm selects: the same rows would be re-selected on every hourly tick forever,
-- deleteByKey would be re-called on already-deleted objects forever, and because deleteByKey
-- swallows every exception (StorageService: catch Exception -> log.warn) nothing would ever have
-- complained. quarantine_reclaimed_at is a column no selection predicate can already satisfy, so
-- stamping it genuinely terminates the row.
--
-- Pre-V60 in-flight PENDING rows (quarantine_expires_at IS NULL, status <> ACTIVE) are collected
-- EXACTLY ONCE by the retention sweep's legacy created_at arm and then sentinel-stamped.
--
-- ADD COLUMN with a constant DEFAULT is a metadata-only change in Postgres 11+ (no table rewrite,
-- no per-row UPDATE; the default is recorded in pg_attribute.attmissingval with atthasmissing =
-- true). This is the identical argument V59 makes, and it is why the recurring
-- trap_rls_migration_backfill (V25 -> V44 -> V57: a bare UPDATE against a FORCE-RLS table sees ZERO
-- rows under the migration role, so a following SET NOT NULL bricks a non-fresh DB) genuinely does
-- NOT apply here rather than merely going unmentioned. There is deliberately NO UPDATE and NO DO $$
-- loop in this file.
--
-- media_asset is @Audited (Envers), so all three columns MUST also land on media_asset_aud or the
-- first UPDATE of any asset row throws: column "process_attempts" of relation "media_asset_aud"
-- does not exist. V59 dodged this only because @Version is legitimately excluded from Envers by
-- project convention. Mirror columns are ALL nullable, per the V53 media_asset_aud shape.
--
-- No new table, so the RLS posture is inherited unchanged (media_asset and media_asset_aud are both
-- already ENABLE + FORCE RLS through the safe current_tenant_id() helper) and RlsContractTest is
-- unaffected. No policy is created or altered here.
--
-- HEAD is V59; V60 is the next strict version (fresh DBs apply it in order, deployed DBs append
-- it). spring.flyway.out-of-order stays required for the V44/V53 reserved slots.

-- ============================================================
-- 1. media_asset — the three durability columns (metadata-only)
-- ============================================================
ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS process_attempts        INT NOT NULL DEFAULT 0;
ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS quarantine_expires_at   TIMESTAMPTZ;
ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS quarantine_reclaimed_at TIMESTAMPTZ;

-- ============================================================
-- 2. media_asset_aud — Envers mirror. ALL NULLABLE (V53 mirror convention):
--     an _aud row records a revision, not a live constraint.
-- ============================================================
ALTER TABLE media_asset_aud ADD COLUMN IF NOT EXISTS process_attempts        INT;
ALTER TABLE media_asset_aud ADD COLUMN IF NOT EXISTS quarantine_expires_at   TIMESTAMPTZ;
ALTER TABLE media_asset_aud ADD COLUMN IF NOT EXISTS quarantine_reclaimed_at TIMESTAMPTZ;

-- ============================================================
-- 3. Indexes
-- ============================================================

-- Backs MediaQuarantineRetentionSweep.findReclaimableQuarantine. Partial on the sentinel because a
-- reclaimed row is never a candidate again, so it does not belong in the index at all.
CREATE INDEX IF NOT EXISTS idx_media_asset_quarantine_sweep
    ON media_asset (quarantine_expires_at)
    WHERE quarantine_reclaimed_at IS NULL;

-- F-2: no index supported a lookup by media_event_outbox.asset_id — the only index was
-- idx_media_event_outbox_claim (status, next_attempt_at, created_at), which cannot serve the
-- reaper's dispatch-evidence probe. created_at DESC is part of the index because the probe is a
-- DISTINCT ON (asset_id) ... ORDER BY asset_id, created_at DESC: the WR-01 reprocess path and the
-- D-06 re-drive both insert a SECOND row for the same asset_id, so "latest" is load-bearing.
CREATE INDEX IF NOT EXISTS idx_media_event_outbox_asset
    ON media_event_outbox (asset_id, created_at DESC);
