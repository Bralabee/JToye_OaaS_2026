-- V80: Phase 37-19 (RWO-022 / UXT-022, decisions D-15 and D-25) — structured opening hours and a one-tap
-- pause for every shop, migrating the existing free-text hours without changing whether any shop takes
-- orders on the day this deploys.
--
-- Why. shops.opening_hours is free text per day ("09:00 - 17:00", "closed", or anything a vendor typed). The
-- server's only gate (PublicStorefrontService.validateShopIsOpen) fails OPEN on text it cannot parse while
-- the storefront renders that same day as Closed, and there is no way to pause orders during a rush. D-15
-- replaces the text with structured windows and adds a pause; 37-20 enforces both through one policy. This
-- migration is the data half: it adds the columns and classifies every EXISTING shop, so the policy has a
-- structured schedule and a source flag to read for each one.
--
-- 1. shops.opening_schedule JSONB NULL — per day mon..sun a list of {"open": "HH:MM", "close": "HH:MM"}
--    windows; [] means closed that day. A window whose close is at or before its open is an overnight window
--    closing the next day ("18:00 - 02:00", the takeaway case WR-06 fixed on the server).
-- 2. shops.hours_source VARCHAR(24) NULL — STRUCTURED | MIGRATED_FROM_TEXT | NEEDS_REVIEW |
--    LEGACY_ALWAYS_OPEN (uk.jtoye.core.shop.HoursSource). Every value the enum will hold is listed in the
--    CHECK now, so no later migration has to rewrite the constraint (the V43 lesson). NULL means "not
--    classified" — a shop the application writes before its vendor saves a structured schedule. No DEFAULT,
--    deliberately: a default would claim a classification nobody made.
-- 3. shops.orders_paused_until TIMESTAMPTZ NULL and shops.orders_paused_indefinitely BOOLEAN NOT NULL
--    DEFAULT false — the D-15 pause (20 min, 40 min, or until reopened), written by 37-20's endpoints.
--    Adding a NOT NULL column with a constant DEFAULT fills existing rows in the catalog, with no UPDATE, so
--    the RLS trap below does not apply to it.
-- 4. The shops_aud mirrors of all four, in this SAME migration: Shop is @Audited, and a missing _aud column
--    throws at runtime on the next audited write (V30/V38). The _aud copies are unconstrained (house shape),
--    and the _aud rows are history: they are NOT backfilled, so NULL there means "not recorded".
-- 5. shops.opening_hours is KEPT, unchanged, for one release (dual-read, the V53 D-03a precedent). It also
--    holds the original text a NEEDS_REVIEW vendor is shown.
--
-- THE CLASSIFICATION (D-25). Each existing shop is classified from its opening_hours:
--   * NULL or {} -> LEGACY_ALWAYS_OPEN, opening_schedule NULL. These shops take orders at any time today
--     (the server and the client both read "no hours" as open; every demo and E2E shop is one), and D-25 rules
--     they keep doing so until their vendor saves hours. LEGACY_ALWAYS_OPEN records WHERE the hours came from;
--     it is not a statement by the vendor that the shop is open around the clock. It is written ONLY here,
--     never by the application, so a fabricated 24/7 statement stays distinguishable from a vendor's (the
--     V63/V66 rule). ck_shops_legacy_always_open_no_schedule makes "legacy, with a schedule" unstorable.
--   * Otherwise, per day mon..sun (the storefront's keys):
--       - a missing key, a JSON null, or "closed" in any case with surrounding spaces -> [] (closed);
--       - an entry that is EXACTLY one HH:MM - HH:MM window once surrounding spaces are trimmed -> that
--         window; a close of 24:00 means midnight (00:00, an overnight close);
--       - anything else is not understood: the shop becomes NEEDS_REVIEW, and the day keeps what the
--         storefront already shows for it — the FIRST window found in the text if it is a real time (so
--         "12:00 - 14:00, 18:00 - 22:00" keeps 12:00-14:00, exactly the window both parsers read today),
--         otherwise [] (both parsers already render such a day Closed). Out-of-range times (25:00), an open of
--         24:00 and a zero-length window (09:00 - 09:00, never open under either parser) are not real windows.
--       - a key that is not mon..sun, or a value that is a JSON array or object, is not understood either.
--     Every entry understood -> MIGRATED_FROM_TEXT. A jsonb that is not an object at all -> NEEDS_REVIEW with
--     every day []. The original text stays in opening_hours, so the vendor can be shown what to fix.
--   The pattern is PublicStorefrontService.HOURS_PATTERN, (dd):(dd) spaces - spaces (dd):(dd), with its
--   digit and space classes written out: Java's are ASCII-only by default, and PostgreSQL's classes are
--   locale-dependent, so [0-9] and [ \t\n\r\f\v] are what keep the two engines equal. The Java reading,
--   uk.jtoye.core.shop.OpeningSchedule.fromLegacy, is held equal to this SQL on one shared fixture list by
--   V80OpeningScheduleBackfillIntegrationTest, so the migration and 37-20's policy cannot disagree.
--
--   Whether a NEEDS_REVIEW day takes orders is 37-20's policy, not this migration's: the migration only
--   records the reading and keeps the text. (Today the server fails OPEN on such a day while the storefront
--   shows it Closed; 37-20 aligns the server with what customers already see.)
--
-- BACKFILL, AND THE FORCE-RLS TRAP. shops is ENABLE + FORCE RLS, and Flyway runs as the RLS-bound application
-- role, so during migration current_tenant_id() is NULL and the write policy hides every row: a bare UPDATE
-- shops SET hours_source = ... would match ZERO rows and report success. Because hours_source is nullable,
-- nothing would then fail: every existing shop would stay unclassified, and 37-20's policy would read "no
-- source, no schedule" as CLOSED — closing every empty-hours storefront D-25 says must stay open. That is the
-- V25 -> V44 -> V57 -> V78 -> V79 defect class. The backfill therefore walks the tenants registry (no RLS on
-- tenants) and pins the same GUC the app sets, transaction-locally, per tenant, with every statement also
-- predicated on the pinned tenant. Proven on a non-fresh two-tenant database under a NOSUPERUSER NOBYPASSRLS
-- role by V80OpeningScheduleBackfillIntegrationTest (a fresh Testcontainers database has nothing to backfill,
-- and its superuser bypasses FORCE RLS), including a bare-UPDATE break arm. Re-running is a no-op (WHERE
-- hours_source IS NULL).
--
-- No new table, no RLS policy change (shops_rls_policy and shops_public_read key on tenant_id and published;
-- the new columns add no trust boundary). No placeholder syntax anywhere in this file: Flyway substitutes
-- inside comments too.

-- ============================================================
-- 1. The columns, their constraints, and their Envers mirrors.
-- ============================================================
ALTER TABLE shops ADD COLUMN IF NOT EXISTS opening_schedule JSONB;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS hours_source VARCHAR(24);
ALTER TABLE shops ADD COLUMN IF NOT EXISTS orders_paused_until TIMESTAMPTZ;
ALTER TABLE shops ADD COLUMN IF NOT EXISTS orders_paused_indefinitely BOOLEAN NOT NULL DEFAULT false;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_shops_hours_source') THEN
        ALTER TABLE shops ADD CONSTRAINT ck_shops_hours_source
            CHECK (hours_source IN ('STRUCTURED', 'MIGRATED_FROM_TEXT', 'NEEDS_REVIEW', 'LEGACY_ALWAYS_OPEN'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_shops_legacy_always_open_no_schedule') THEN
        ALTER TABLE shops ADD CONSTRAINT ck_shops_legacy_always_open_no_schedule
            CHECK (hours_source IS DISTINCT FROM 'LEGACY_ALWAYS_OPEN' OR opening_schedule IS NULL);
    END IF;
END $$;

COMMENT ON COLUMN shops.opening_schedule IS
    'Structured opening hours (Phase 37-19, D-15): keys mon..sun, each a list of {"open":"HH:MM","close":"HH:MM"} windows in UK local time; [] = closed that day; a window whose close is at or before its open closes the next day. NULL = no structured schedule: always for LEGACY_ALWAYS_OPEN, and for a shop written by the application before its vendor saved one.';
COMMENT ON COLUMN shops.hours_source IS
    'Where opening_schedule came from: STRUCTURED (saved by the vendor), MIGRATED_FROM_TEXT (V80 understood every day of the free text), NEEDS_REVIEW (V80 did not understand at least one entry; the original text stays in opening_hours), LEGACY_ALWAYS_OPEN (no hours at all when V80 ran; open at any time until the vendor saves hours, D-25; written only by V80, never by the application, so it is never mistaken for a vendor statement). NULL = not classified; no default, deliberately.';
COMMENT ON COLUMN shops.orders_paused_until IS
    'Storefront orders are paused until this instant (D-15: 20 or 40 minutes). NULL = no timed pause.';
COMMENT ON COLUMN shops.orders_paused_indefinitely IS
    'Storefront orders are paused until the vendor reopens (D-15). Independent of orders_paused_until.';

ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS opening_schedule JSONB;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS hours_source VARCHAR(24);
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS orders_paused_until TIMESTAMPTZ;
ALTER TABLE shops_aud ADD COLUMN IF NOT EXISTS orders_paused_indefinitely BOOLEAN;

-- ============================================================
-- 2. Classify every existing shop, one tenant at a time with the tenant pinned.
-- ============================================================
DO $$
DECLARE
    -- The storefront's day keys, Monday first.
    day_keys   CONSTANT TEXT[] := ARRAY['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'];
    -- Exactly the characters Java's \s matches (space, tab, newline, carriage return, form feed, vertical tab).
    spaces     CONSTANT TEXT := E' \t\n\r\f\x0B';
    -- PublicStorefrontService.HOURS_PATTERN with its classes written out (see the header).
    hours_re   CONSTANT TEXT := '([0-9]{2}):([0-9]{2})[ \t\n\r\f\v]*-[ \t\n\r\f\v]*([0-9]{2}):([0-9]{2})';
    t          RECORD;
    s          RECORD;
    d          TEXT;
    entry      JSONB;
    txt        TEXT;
    m          TEXT[];
    oh         INT;
    om         INT;
    ch         INT;
    cm         INT;
    win        JSONB;
    review     BOOLEAN;
    src        TEXT;
    sched      JSONB;
    n_legacy   BIGINT := 0;
    n_migrated BIGINT := 0;
    n_review   BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants ORDER BY id LOOP
        -- Same GUC the app sets (TenantSetLocalAspect); transaction-local, so it vanishes when Flyway commits.
        PERFORM set_config('app.current_tenant_id', t.id::text, true);

        FOR s IN SELECT id, opening_hours FROM shops WHERE tenant_id = t.id AND hours_source IS NULL LOOP
            IF s.opening_hours IS NULL
               OR jsonb_typeof(s.opening_hours) = 'null'
               OR s.opening_hours = '{}'::jsonb THEN
                -- D-25: no hours at all. Open at any time today, and kept so until the vendor saves hours.
                src := 'LEGACY_ALWAYS_OPEN';
                sched := NULL;
            ELSIF jsonb_typeof(s.opening_hours) <> 'object' THEN
                -- Not a day map at all: nothing can be read from it.
                src := 'NEEDS_REVIEW';
                sched := '{}'::jsonb;
                FOREACH d IN ARRAY day_keys LOOP
                    sched := sched || jsonb_build_object(d, '[]'::jsonb);
                END LOOP;
            ELSE
                review := EXISTS (SELECT 1 FROM jsonb_object_keys(s.opening_hours) AS k(key)
                                   WHERE k.key <> ALL (day_keys));
                sched := '{}'::jsonb;
                FOREACH d IN ARRAY day_keys LOOP
                    entry := s.opening_hours -> d;
                    win := '[]'::jsonb;
                    IF entry IS NULL OR jsonb_typeof(entry) = 'null' THEN
                        NULL; -- a missing day is closed
                    ELSIF jsonb_typeof(entry) IN ('array', 'object') THEN
                        review := true;
                    ELSE
                        txt := s.opening_hours ->> d;
                        IF lower(btrim(txt, spaces)) = 'closed' THEN
                            NULL; -- stated closed
                        ELSE
                            m := regexp_match(txt, hours_re);
                            IF m IS NULL THEN
                                review := true;
                            ELSE
                                oh := m[1]::int; om := m[2]::int; ch := m[3]::int; cm := m[4]::int;
                                IF (oh = ch AND om = cm)
                                   OR oh > 23 OR om > 59 OR cm > 59 OR ch > 24 OR (ch = 24 AND cm <> 0) THEN
                                    review := true; -- not a real window
                                ELSE
                                    win := jsonb_build_array(jsonb_build_object(
                                            'open', m[1] || ':' || m[2],
                                            'close', CASE WHEN ch = 24 THEN '00:00' ELSE m[3] || ':' || m[4] END));
                                    IF btrim(txt, spaces) !~ ('^' || hours_re || '$') THEN
                                        review := true; -- a window plus text we do not understand
                                    END IF;
                                END IF;
                            END IF;
                        END IF;
                    END IF;
                    sched := sched || jsonb_build_object(d, win);
                END LOOP;
                src := CASE WHEN review THEN 'NEEDS_REVIEW' ELSE 'MIGRATED_FROM_TEXT' END;
            END IF;

            UPDATE shops
               SET opening_schedule = sched,
                   hours_source = src
             WHERE id = s.id
               AND tenant_id = t.id;

            CASE src
                WHEN 'LEGACY_ALWAYS_OPEN' THEN n_legacy := n_legacy + 1;
                WHEN 'MIGRATED_FROM_TEXT' THEN n_migrated := n_migrated + 1;
                ELSE n_review := n_review + 1;
            END CASE;
        END LOOP;

        -- Every shop of this tenant must now be classified; a branch that left one NULL is a bug, not a state.
        IF EXISTS (SELECT 1 FROM shops WHERE tenant_id = t.id AND hours_source IS NULL) THEN
            RAISE EXCEPTION 'V80: a shop of tenant % was left unclassified', t.id;
        END IF;
    END LOOP;

    -- Defensive reset so no later statement in this transaction inherits the last tenant's GUC (V44/V57).
    PERFORM set_config('app.current_tenant_id', '', true);

    RAISE NOTICE 'V80: classified shops: LEGACY_ALWAYS_OPEN=%, MIGRATED_FROM_TEXT=%, NEEDS_REVIEW=%',
        n_legacy, n_migrated, n_review;
END $$;
