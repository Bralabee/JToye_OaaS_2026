-- V35: Pre-prod hardening — Wave 0 council audit fixes (AUDIT-W0-03, -04, -05).
--
-- Three concerns bundled into one atomic migration:
--   1. AUDIT-W0-03 — processed_stripe_events table for webhook idempotency.
--   2. AUDIT-W0-04 — reviews_tenant_write policy: drop V27's buggy policy,
--      recreate with canonical app.current_tenant_id GUC + EXISTS-on-orders
--      ownership proof.
--   3. AUDIT-W0-05 — FORCE ROW LEVEL SECURITY on 9 tables (reviews,
--      shop_promotions, shop_announcements + 6 _aud audit tables) so the
--      table owner / superuser cannot bypass RLS.
--
-- Forward-only. No rollback path. Looser policies are unsafe to keep around
-- as fallback per phase 16.1 design decision (16.1-CONTEXT.md <decisions>).

-- ============================================================
-- 1. AUDIT-W0-03 — processed_stripe_events idempotency table
-- ============================================================
--
-- Records every Stripe event_id we have begun processing. The application
-- inserts ON CONFLICT DO NOTHING at the top of handleWebhookEvent; if 0
-- rows returned the event is a retry, return early.
--
-- event_id is globally unique across Stripe accounts → no tenant scoping.
-- Intentionally NOT row-level-secured — this is infrastructure idempotency,
-- not tenant data, and the webhook handler runs before TenantContext is set.
-- Pruning of historical rows is deferred to a separate housekeeping phase.

CREATE TABLE processed_stripe_events (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- 2. AUDIT-W0-04 — reviews_tenant_write policy rewrite
-- ============================================================
--
-- V27's policy had two defects:
--   (a) Read app.tenant_id; the app sets app.current_tenant_id (TenantSetLocalAspect.java:61).
--       First branch was permanently false.
--   (b) OR-clause `current_setting('app.customer_email', true) = customer_email`
--       allowed anyone setting app.customer_email to insert a review on ANY
--       tenant_id and ANY order_id. Spam-review write hole.
--
-- New policy:
--   - Tenant branch reads canonical app.current_tenant_id (matches V33 pattern).
--   - Customer-email branch requires an EXISTS proof that the writer owns an
--     order matching the claimed (tenant_id, customer_email, order_id) tuple.
--   - app.customer_email NULL/empty short-circuits the EXISTS to false (desired).
--
-- The reviews_tenant_read SELECT policy from V33 is NOT touched.

DROP POLICY reviews_tenant_write ON reviews;

CREATE POLICY reviews_tenant_write ON reviews
    FOR INSERT
    WITH CHECK (
        tenant_id = current_setting('app.current_tenant_id', true)::UUID
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
-- 3. AUDIT-W0-05 — FORCE ROW LEVEL SECURITY on 9 tables
-- ============================================================
--
-- Without FORCE, table owners and BYPASSRLS roles read across tenants. Today
-- ENABLE-without-FORCE on these 9 tables means the same DB role used by some
-- Flyway migrations and Envers writes can bypass tenant isolation.
--
-- ALTER TABLE ... FORCE takes a microsecond AccessExclusiveLock on the
-- catalog only — no row-level locks, no table rewrite. Online-safe.
--
-- ENABLE was already done in V27/V28/V29 for the marketing tables and via
-- Envers auto-config for the _aud tables — this migration only adds FORCE.

ALTER TABLE reviews                    FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_promotions            FORCE ROW LEVEL SECURITY;
ALTER TABLE shop_announcements         FORCE ROW LEVEL SECURITY;
ALTER TABLE customers_aud              FORCE ROW LEVEL SECURITY;
ALTER TABLE shops_aud                  FORCE ROW LEVEL SECURITY;
ALTER TABLE products_aud               FORCE ROW LEVEL SECURITY;
ALTER TABLE financial_transactions_aud FORCE ROW LEVEL SECURITY;
ALTER TABLE orders_aud                 FORCE ROW LEVEL SECURITY;
ALTER TABLE order_items_aud            FORCE ROW LEVEL SECURITY;
