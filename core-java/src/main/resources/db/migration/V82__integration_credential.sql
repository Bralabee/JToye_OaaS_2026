-- V82 [Phase 37-45 / RWO-035, UXT-035, decisions D-22, D-23, D-24]: the tenant's register of integrator
-- credentials.
--
-- A vendor with a till, a stock system or a developer of their own had one way to give it API access:
-- share the owner's password. D-22 issues instead a Keycloak service-account client per credential
-- (37-44 builds the client: a hard-coded tenant_id claim, aud=core-api, exactly the granted #206 scopes),
-- and D-23 gives its service-account user an ordinary OPERATOR shop_staff grant, so ShopAccessService
-- limits it exactly like a person. This table is core's half: which credential belongs to which tenant,
-- which Keycloak client and service-account user it is, what it was granted, who issued, rotated and
-- revoked it and when (D-24), and when it was last used.
--
-- What is NOT here, by design:
--   * the secret. Keycloak generates and holds it; core shows it to the issuer once (create and rotate,
--     Cache-Control no-store) and never stores or logs it (T-37-108). There is no column it could go in.
--   * an _aud mirror. Who and when are columns (the V77 staff_invite precedent); a row is revoked, never
--     deleted, so the register is its own history.
--
-- Constraints:
--   * kc_client_id is generated server-side as jtoye-int-<12 base32> and is UNIQUE across the whole table,
--     because Keycloak clientIds are realm-unique: a tenant never chooses it, so it can never collide with
--     or impersonate another client (T-37-110). ck_integration_credential_client_id pins the prefix the
--     last-used recorder keys on.
--   * role is STAFF or SHOP_MANAGER only. A credential never holds GROUP_ADMIN (Q7): with it, a machine could
--     reach every requireGroupAdmin() endpoint. The service refuses it first; the CHECK makes it unstorable.
--   * scopes is a non-empty subset of the six #206 scopes (docs/security-scopes.md). Same two layers.
--   * rotated_by/rotated_at and revoked_by/revoked_at are written together or not at all.
--   * idempotency_key: the Idempotency-Key the create carried, so a same-key replay can return the
--     credential it created WITHOUT the store ever holding the response (the response carries the secret;
--     IdempotencyService.executeWithoutStoringResponse). Unique per tenant when present.
--
-- RLS: ENABLE + FORCE through the safe current_tenant_id() helper, swept by RlsContractTest with no
-- exemption, and proven under a NOSUPERUSER role by IntegrationCredentialRlsIntegrationTest (cross-tenant
-- read empty, cross-tenant UPDATE 0 rows, foreign-tenant INSERT refused 42501, each beside its own-tenant
-- control). Ships EMPTY: no UPDATE, so no tenant loop and no RLS-backfill trap.
--
-- Applied out of order on an existing database (V84 is already applied there): the project runs with
-- spring.flyway.out-of-order=true, proven on scratch Postgres 15 at the 37-C gate (37-38).

CREATE TABLE IF NOT EXISTS integration_credential (
    id                       UUID         PRIMARY KEY,
    tenant_id                UUID         NOT NULL REFERENCES tenants(id),
    name                     VARCHAR(100) NOT NULL,
    kc_client_id             VARCHAR(64)  NOT NULL,
    kc_client_uuid           UUID         NOT NULL,
    service_account_user_id  UUID         NOT NULL,
    scopes                   TEXT[]       NOT NULL,
    shop_id                  UUID         REFERENCES shops(id),
    role                     VARCHAR(16)  NOT NULL,
    idempotency_key          VARCHAR(64),
    created_by               UUID         NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    rotated_by               UUID,
    rotated_at               TIMESTAMPTZ,
    revoked_by               UUID,
    revoked_at               TIMESTAMPTZ,
    last_used_at             TIMESTAMPTZ,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_integration_credential_kc_client_id UNIQUE (kc_client_id),
    CONSTRAINT ck_integration_credential_role CHECK (role IN ('STAFF','SHOP_MANAGER')),
    CONSTRAINT ck_integration_credential_scopes CHECK (
        cardinality(scopes) >= 1
        AND scopes <@ ARRAY['catalog:read','catalog:write','orders:read','orders:write',
                            'customers:read','customers:write']::text[]),
    CONSTRAINT ck_integration_credential_name CHECK (char_length(btrim(name)) >= 1),
    CONSTRAINT ck_integration_credential_client_id CHECK (kc_client_id LIKE 'jtoye-int-%'),
    CONSTRAINT ck_integration_credential_rotated_pair CHECK ((rotated_at IS NULL) = (rotated_by IS NULL)),
    CONSTRAINT ck_integration_credential_revoked_pair CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX IF NOT EXISTS idx_integration_credential_tenant_created
    ON integration_credential (tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_integration_credential_service_account
    ON integration_credential (tenant_id, service_account_user_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_integration_credential_idempotency
    ON integration_credential (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON TABLE integration_credential IS
    'Integrator credentials (D-22..D-24): one Keycloak service-account client per row, limited by an OPERATOR shop_staff grant for its service-account user. The secret is never stored here.';
COMMENT ON COLUMN integration_credential.kc_client_id IS
    'Keycloak clientId, generated server-side (jtoye-int- + 12 base32 characters); the azp of every token the credential mints.';
COMMENT ON COLUMN integration_credential.service_account_user_id IS
    'The Keycloak service-account user: the sub of every token the credential mints and the user_id of its shop_staff grant.';
COMMENT ON COLUMN integration_credential.last_used_at IS
    'Best-effort, throttled (jtoye.credentials.last-used-interval): the last time a request authenticated with this credential, never more precise than the interval. NULL means never used.';

ALTER TABLE integration_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE integration_credential FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='integration_credential'
                 AND policyname='integration_credential_tenant_policy') THEN
    CREATE POLICY integration_credential_tenant_policy ON integration_credential
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

DO $$
BEGIN
    RAISE NOTICE 'V82 integration_credential applied: table created EMPTY under ENABLE + FORCE RLS (tenant policy via current_tenant_id()); no secret column; no row was written or changed.';
END $$;
