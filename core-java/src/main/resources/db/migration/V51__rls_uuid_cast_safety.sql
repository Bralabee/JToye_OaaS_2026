-- V51: Remove the raw `current_setting('app.current_tenant_id', true)::uuid`
-- cast from every remaining RLS policy — the latent 22P02 bug class (Issue #113
-- [P3-11]).
--
-- BUG (same class V39 fixed for the three storefront SELECT policies):
--   A policy body that reads the tenant GUC via the RAW expression
--   `current_setting('app.current_tenant_id', true)::uuid` evaluates that cast
--   as a query-level constant at query init — BEFORE any row is examined, even
--   on an empty table. When a request carries no tenant context,
--   TenantSetLocalAspect leaves the GUC at its default empty string and
--   ''::uuid raises 22P02 "invalid input syntax for type uuid". Spring maps the
--   SQLException to DataIntegrityViolationException → HTTP 409/500. This is the
--   exact defect V39 removed from shop_promotions_read / shop_announcements_read
--   / reviews_tenant_read; it survived on ten other policies.
--
-- FIX:
--   Route every tenant-GUC read through the existing safe helper
--   current_tenant_id(), which returns NULL for an empty / 'default' / unset
--   GUC instead of crashing. Semantics under a VALID tenant GUC are unchanged:
--   the helper returns exactly the same UUID the raw cast produced, so tenant
--   equality (and cross-tenant filtering / write rejection) behaves identically.
--   The only behaviour that changes is for GUC values that were ALREADY a bug
--   (empty / malformed): those now fail-filtered (no rows) rather than
--   fail-errored (22P02).
--
-- SCOPE:
--   Issue #113 named 4 policies (payment_event_outbox_tenant, reviews_tenant_write,
--   refunds_tenant_policy, refunds_aud_tenant_policy). V43 (vendor onboarding),
--   V47 (processed_order_events) and V50 (idempotency_keys) landed AFTER the
--   #113 audit and reused the same raw cast, bringing the final-state total to
--   ten. All ten are migrated here — a fix scoped to only the named four would
--   leave six identical latent bugs unguarded. The permanent pg_policy sweep in
--   RlsContractTest#noPolicyUsesRawTenantGucCast enforces that no policy ever
--   reintroduces the raw ::uuid cast.
--
--   NOTE: the `tenant_id::text = current_setting('app.current_tenant_id', true)`
--   TEXT-comparison policies (orders, order_items, customers, shop_*_write) are
--   deliberately NOT touched — a text/text comparison never casts to uuid and
--   therefore never raises 22P02; they are outside this bug class.
--
-- No data change. Policy semantics preserved; only the unsafe cast is removed.

-- ============================================================
-- 0. Harden current_tenant_id(): guard the final cast too
-- ============================================================
--
-- The V1 helper already guards current_setting and returns NULL for empty /
-- 'default' / unset, but its final `RETURN v::uuid` is UNGUARDED — so a GUC set
-- to a non-UUID string (e.g. 'not-a-uuid') still raises 22P02 inside the helper.
-- V51 wraps the cast in its own exception guard: a malformed tenant GUC now
-- fail-filters (NULL → no rows) instead of fail-erroring. The application only
-- ever sets a valid UUID or empty / 'default', so this only changes behaviour
-- for values that were already a misconfiguration.
CREATE OR REPLACE FUNCTION current_tenant_id() RETURNS uuid
LANGUAGE plpgsql AS $$
DECLARE
    v text;
BEGIN
    -- Try to get the setting; if it doesn't exist or is empty, return NULL
    BEGIN
        v := current_setting('app.current_tenant_id', true);
    EXCEPTION WHEN OTHERS THEN
        RETURN NULL;
    END;

    IF v IS NULL OR v = '' OR v = 'default' THEN
        RETURN NULL;
    END IF;

    -- V51: guard the cast so a non-UUID GUC value fails filtered (NULL), not
    -- errored (22P02). Mirrors the empty-string handling above.
    BEGIN
        RETURN v::uuid;
    EXCEPTION WHEN OTHERS THEN
        RETURN NULL;
    END;
END;
$$;

-- ============================================================
-- 1. payment_event_outbox_tenant  (was V33 lines 19-22)
-- ============================================================
DROP POLICY IF EXISTS payment_event_outbox_tenant ON payment_event_outbox;
CREATE POLICY payment_event_outbox_tenant ON payment_event_outbox
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- 2. reviews_tenant_write  (was V35 lines 54-67)
--     Only the tenant branch's cast changes; the app.customer_email
--     EXISTS-on-orders ownership branch is TEXT-only and copied verbatim.
-- ============================================================
DROP POLICY IF EXISTS reviews_tenant_write ON reviews;
CREATE POLICY reviews_tenant_write ON reviews
    FOR INSERT
    WITH CHECK (
        tenant_id = current_tenant_id()
        OR (
            current_setting('app.customer_email', true) IS NOT NULL
            AND current_setting('app.customer_email', true) <> ''
            AND current_setting('app.customer_email', true) = customer_email
            AND EXISTS (SELECT 1 FROM orders o
                        WHERE o.id = order_id
                          AND o.customer_email = current_setting('app.customer_email', true)
                          AND o.tenant_id = reviews.tenant_id)
        )
    );

-- ============================================================
-- 3. refunds_tenant_policy  (was V36 lines 42-45)
-- ============================================================
DROP POLICY IF EXISTS refunds_tenant_policy ON refunds;
CREATE POLICY refunds_tenant_policy ON refunds
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- 4. refunds_aud_tenant_policy  (was V36 lines 74-77)
-- ============================================================
DROP POLICY IF EXISTS refunds_aud_tenant_policy ON refunds_aud;
CREATE POLICY refunds_aud_tenant_policy ON refunds_aud
    FOR ALL
    USING (tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());

-- ============================================================
-- 5. vendor_onboarding_tenant_policy  (was V43 lines 53-56)
-- ============================================================
DROP POLICY IF EXISTS vendor_onboarding_tenant_policy ON vendor_onboarding;
CREATE POLICY vendor_onboarding_tenant_policy ON vendor_onboarding
    FOR ALL
    USING      (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- 6. vendor_onboarding_gate_tenant_policy  (was V43 lines 96-99)
-- ============================================================
DROP POLICY IF EXISTS vendor_onboarding_gate_tenant_policy ON vendor_onboarding_gate;
CREATE POLICY vendor_onboarding_gate_tenant_policy ON vendor_onboarding_gate
    FOR ALL
    USING      (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- 7. vendor_onboarding_aud_tenant_policy  (was V43 lines 154-157)
-- ============================================================
DROP POLICY IF EXISTS vendor_onboarding_aud_tenant_policy ON vendor_onboarding_aud;
CREATE POLICY vendor_onboarding_aud_tenant_policy ON vendor_onboarding_aud
    FOR ALL
    USING      (tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());

-- ============================================================
-- 8. vendor_onboarding_gate_aud_tenant_policy  (was V43 lines 160-163)
-- ============================================================
DROP POLICY IF EXISTS vendor_onboarding_gate_aud_tenant_policy ON vendor_onboarding_gate_aud;
CREATE POLICY vendor_onboarding_gate_aud_tenant_policy ON vendor_onboarding_gate_aud
    FOR ALL
    USING      (tenant_id IS NULL OR tenant_id = current_tenant_id())
    WITH CHECK (tenant_id IS NULL OR tenant_id = current_tenant_id());

-- ============================================================
-- 9. processed_order_events_tenant  (was V47 lines 39-42)
-- ============================================================
DROP POLICY IF EXISTS processed_order_events_tenant ON processed_order_events;
CREATE POLICY processed_order_events_tenant ON processed_order_events
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());

-- ============================================================
-- 10. idempotency_keys_tenant  (was V50 lines 51-54)
-- ============================================================
DROP POLICY IF EXISTS idempotency_keys_tenant ON idempotency_keys;
CREATE POLICY idempotency_keys_tenant ON idempotency_keys
    FOR ALL
    USING (tenant_id = current_tenant_id())
    WITH CHECK (tenant_id = current_tenant_id());
