-- V65: the six legacy Envers _aud INSERT policies stop accepting a FOREIGN tenant_id.
--
-- WHY THIS EXISTS (QA-council 20260902-134741 SEC-6, adjudication A5). V4, V5, V9
-- and V11 created the INSERT policies on shops_aud, products_aud,
-- financial_transactions_aud, orders_aud, order_items_aud and customers_aud as
--
--     FOR INSERT WITH CHECK (true)   -- "Allow Envers to write all audit records"
--
-- Live pg_policy: all six have polcmd='a' and pg_get_expr(polwithcheck)='true'.
-- The paired *_aud_select_policy on each table is correctly tenant_id =
-- current_tenant_id(), so nothing was READABLE across tenants; the gap was
-- write-side only: a session pinned to tenant A could INSERT an audit row stamped
-- tenant B. These tables are the Article-17 erasure evidence chain (V42), and an
-- unconstrained INSERT is the shape that lets an application bug plant an
-- unattributable audit row. Measured RED before this migration:
-- AuditTableInsertPolicyIntegrationTest's armed INSERT with a foreign tenant_id
-- succeeded on all six tables under a NOSUPERUSER role with the GUC pinned.
--
-- THE FORM, AND WHY THE "OBVIOUS" ONE IS AN OUTAGE. The predicate is
--
--     WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()))
--
-- and the IS NULL arm is load-bearing. Hibernate Envers writes a DELETE revision
-- (revtype = 2) carrying only the identifier unless store_data_at_delete is on, so
-- tenant_id is NULL BY CONSTRUCTION on every such row. Measured live this run,
-- correlation exact: orders_aud 1 NULL-tenant row / 1 DELETE revision,
-- products_aud 5/5, customers_aud 1/1, and 0/0 on revtype 0 and 1. The naive
-- WITH CHECK (tenant_id = current_tenant_id()) therefore turns every product,
-- order and customer DELETE into "new row violates row-level security policy"
-- - a data-modification outage on three core entities that no test which never
-- deletes would see. V11 fixed exactly this once already, by widening to true; this
-- migration narrows to the foreign-tenant case without re-breaking deletes.
--
-- The companion N-3 fix in application.yml (org.hibernate.envers.* prefix, so
-- store_data_at_delete=true finally reaches Envers) means NEW delete revisions now
-- carry their tenant_id and pass through the second arm. The IS NULL arm stays per
-- A5: it is what keeps a DELETE auditable if that setting is ever reverted, and it
-- is the shape any audited entity without the tenant column in its revision writes.
--
-- WHY FOR INSERT ONLY, AND NOT THE REFERENCE TABLES' FOR ALL FORM. The five
-- newer _aud tables (media_asset_aud, refunds_aud, shop_staff_aud,
-- vendor_onboarding_aud, vendor_onboarding_gate_aud) use
-- FOR ALL USING (tenant_id IS NULL OR ...) WITH CHECK (tenant_id IS NULL OR ...).
-- Copying that here was REJECTED (A5): a NULL-permissive USING clause is a
-- cross-tenant READ of every delete revision (7 such rows exist today), which the
-- legacy tables' strict SELECT policies currently prevent. That latent read on the
-- five newer tables is finding N-2 and is deliberately NOT touched here. So: the
-- INSERT policy is replaced, the SELECT policies and V42's UPDATE policies on
-- orders_aud / customers_aud are left exactly as they are, and no USING clause is
-- introduced.
--
-- DDL ONLY - no rows are read or written, so the FORCE-RLS backfill trap (a bare
-- UPDATE hitting zero rows under a policy) does not apply; precedent V51:120-126.
-- Routed through the safe current_tenant_id() helper, never the raw
-- current_setting(...)::uuid cast (V51 / RlsContractTest.noPolicyUsesRawTenantGucCast).
-- Policy names are kept identical so the paired policies are untouched.
-- No extension is created (scripts/check-no-create-extension.sh).

DROP POLICY IF EXISTS shops_aud_insert_policy ON shops_aud;
CREATE POLICY shops_aud_insert_policy ON shops_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));

DROP POLICY IF EXISTS products_aud_insert_policy ON products_aud;
CREATE POLICY products_aud_insert_policy ON products_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));

DROP POLICY IF EXISTS financial_transactions_aud_insert_policy ON financial_transactions_aud;
CREATE POLICY financial_transactions_aud_insert_policy ON financial_transactions_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));

DROP POLICY IF EXISTS orders_aud_insert_policy ON orders_aud;
CREATE POLICY orders_aud_insert_policy ON orders_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));

DROP POLICY IF EXISTS order_items_aud_insert_policy ON order_items_aud;
CREATE POLICY order_items_aud_insert_policy ON order_items_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));

DROP POLICY IF EXISTS customers_aud_insert_policy ON customers_aud;
CREATE POLICY customers_aud_insert_policy ON customers_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));
