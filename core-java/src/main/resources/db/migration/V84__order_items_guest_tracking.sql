-- V84: 37-38 gate deviation (owner decision 2026-10-12, option A) — an anonymous order-tracking read
-- sees the order's LINES, so the allergen set it reports as recorded is the V63 line snapshot.
--
-- Numbering: V82 and V83 are reserved for 37-45 (integration_credential) and 37-48 (shop fulfilment
-- flags) by the 37-01 reservation table, and are NOT renumbered. This file takes V84; when V82/V83 land
-- on a database already at V84 they apply out of order, which spring.flyway.out-of-order=true (set in
-- application.yml for every profile, repeated in -staging and -prod) admits — the V44 precedent, pinned
-- both ways by FlywayV44OutOfOrderIntegrationTest.
--
-- WHY THIS EXISTS. orders has carried orders_guest_tracking (V17) since the tracking page shipped: a
-- SELECT admitted when the row's order_number AND customer_email equal the transaction-local GUCs
-- app.tracking_order_number / app.tracking_email that PublicStorefrontService.trackOrder sets. order_items
-- never got the matching policy — only order_items_select_policy (V15, tenant_id = current_tenant_id()).
-- A tracking request carries no tenant, so under FORCE RLS it read the order row and ZERO lines, and
-- trackOrder, which derives recordedAllergens from order.getItems(), answered null ("Allergen information
-- not recorded for this order") for every guest order — on /track and the card-order route since 31.1
-- (#901), and on the cash confirmation since 37-37 moved it onto the tracking read. Measured live at the
-- 37-C gate (evidence 37-38-gate-37C.md 2.9): orders with allergen_ack_mask 320 and line masks 64/256
-- tracked as "recordedAllergens": null. CustomerRecordedAllergenSetIntegrationTest stayed green because
-- it runs as the Testcontainers SUPERUSER, which bypasses RLS; GuestTrackingOrderLinesRlsIntegrationTest
-- is the NOSUPERUSER NOBYPASSRLS proof.
--
-- SHAPE. FOR SELECT only: no INSERT, UPDATE or DELETE is widened (the write policies stay tenant-only).
-- The tenant arm mirrors V17's shape (it duplicates order_items_select_policy; permissive policies OR).
-- The tracking arm admits a line only when:
--   * both tracking GUCs are set and non-empty — checked first, so an untracked session (every vendor
--     and kitchen query) short-circuits before the subquery, the V35/V51 reviews_tenant_write pattern;
--   * the line's own order has exactly that number and email (V17's predicate, verbatim: exact text
--     equality, no lower(), no trim) and the same tenant as the line.
-- The EXISTS on orders is itself filtered by the orders policies for the current role, so it can only
-- ever admit lines of an order the caller may already read. It is mirrored on the tracking predicate
-- rather than written as a bare "EXISTS on a visible order" ON PURPOSE: orders_guest_history (V18) also
-- exposes orders by app.customer_email alone, and a bare EXISTS would hand every line of that email's
-- history to the order-history GUC too. Lines follow tracking, and only tracking.
--
-- No raw ::uuid GUC cast anywhere (V51; RlsContractTest.noPolicyUsesRawTenantGucCast): the tenant arm
-- goes through current_tenant_id(), and both tracking GUCs are compared as TEXT to TEXT columns.
-- No new table, no column, no data change, no UPDATE (so no tenant loop), no _aud change: trackOrder
-- reads order_items only, never order_items_aud. idx_order_items_order and the orders primary key serve
-- the EXISTS; idx_orders_order_number (V17) serves the tracking lookup itself.

DROP POLICY IF EXISTS order_items_guest_tracking ON order_items;
CREATE POLICY order_items_guest_tracking ON order_items
    FOR SELECT
    USING (
        tenant_id = current_tenant_id()
        OR (
            current_setting('app.tracking_order_number', true) IS NOT NULL
            AND current_setting('app.tracking_order_number', true) <> ''
            AND current_setting('app.tracking_email', true) IS NOT NULL
            AND current_setting('app.tracking_email', true) <> ''
            AND EXISTS (
                SELECT 1 FROM orders o
                WHERE o.id = order_items.order_id
                  AND o.tenant_id = order_items.tenant_id
                  AND o.order_number IS NOT NULL
                  AND o.customer_email IS NOT NULL
                  AND o.order_number = current_setting('app.tracking_order_number', true)
                  AND o.customer_email = current_setting('app.tracking_email', true)
            )
        )
    );

COMMENT ON POLICY order_items_guest_tracking ON order_items IS
    'Guest tracking (V84): SELECT the lines of an order whose order_number AND customer_email equal the '
    'transaction-local app.tracking_order_number / app.tracking_email GUCs, mirroring orders_guest_tracking (V17). '
    'SELECT only; writes stay tenant-scoped.';
