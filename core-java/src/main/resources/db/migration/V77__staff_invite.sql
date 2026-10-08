-- V77: Phase 37-07 / D-07 (RWO-004, RWO-003; closes #452 gap 2) — a Group admin invites a person by
-- email, and the invitation carries the exact grant they will receive.
--
-- WHY. Under D-06 (strict scoping ON) a self-registered user has no access at all, so an invitation is
-- the only way a new person joins a business. It must say what they get (one shop or all shops, and a
-- role), belong to one tenant, work once and expire. Accepting it (creating the Keycloak user, writing
-- the directory row and the OPERATOR grant) is 37-08; this migration holds the invitation only.
--
-- THE TOKEN IS HELD ONLY AS A DIGEST (the V62 / V75 rule). The emailed link carries a single-use
-- token of 32 random bytes; this table stores its SHA-256 hex in token_sha256 and nothing else
-- derived from it. A readable token at rest would be a bearer credential that grants access to a
-- business to whoever reads the table.
--
-- TENANT-SCOPED, UNLIKE V75. The link is {accept-base-url}/{tenantId}.{token}: the accept request is
-- anonymous, so the server pins the tenant named in the link and reads by digest UNDER RLS. A wrong
-- tenant finds nothing. So this table needs no RLS exemption and gets the ordinary V71 policy:
-- ENABLE + FORCE through the safe current_tenant_id() helper (never the raw GUC cast), swept by
-- RlsContractTest with NO entry in EXEMPT_TABLES, and proven under a NOSUPERUSER role by
-- StaffInviteRlsIntegrationTest.
--
-- THE GRANT RULES MIRROR grant(). ck_staff_invite_ga_all_shops is the
-- StaffManagementService.grant() rule "GROUP_ADMIN is tenant-wide": a shop-scoped GROUP_ADMIN row
-- would confer nothing and would corrupt the last-admin guard. That a shop_id belongs to the tenant
-- is enforced in the service (findByIdAndTenantId), because a foreign key check bypasses RLS.
--
-- STATUS IS DERIVED, NEVER STORED. OPEN / EXPIRED / ACCEPTED / CANCELLED come from accepted_at,
-- revoked_at and expires_at against the clock at read time, so no job has to move a row when time
-- passes. ck_staff_invite_accept_pair makes a half-written acceptance unstorable.
--
-- NO _aud MIRROR. Who issued it and when, who cancelled it and when, and who accepted it and when are
-- columns of the row itself. The row is never deleted by the application: a cancelled or re-sent
-- invitation is revoked (revoked_at), so its history stays readable to the tenant.
--
-- NO BACKFILL. The table ships empty; no row is written or changed by this migration.

CREATE TABLE IF NOT EXISTS staff_invite (
    id                UUID         PRIMARY KEY,
    tenant_id         UUID         NOT NULL REFERENCES tenants(id),
    email_normalised  VARCHAR(320) NOT NULL,
    shop_id           UUID         REFERENCES shops(id),
    role              VARCHAR(16)  NOT NULL
                        CHECK (role IN ('STAFF','SHOP_MANAGER','GROUP_ADMIN')),
    token_sha256      CHAR(64)     NOT NULL UNIQUE,
    expires_at        TIMESTAMPTZ  NOT NULL,
    created_by        UUID         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    accepted_at       TIMESTAMPTZ,
    accepted_user_id  UUID,
    revoked_at        TIMESTAMPTZ,
    revoked_by        UUID,
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_staff_invite_ga_all_shops
        CHECK (role <> 'GROUP_ADMIN' OR shop_id IS NULL),
    CONSTRAINT ck_staff_invite_accept_pair
        CHECK ((accepted_at IS NULL) = (accepted_user_id IS NULL))
);

-- The replay / re-issue lookup: the live invitations of one address in one tenant.
CREATE INDEX IF NOT EXISTS idx_staff_invite_tenant_email_live
    ON staff_invite (tenant_id, email_normalised)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

COMMENT ON TABLE staff_invite IS
    'D-07: an invitation to join a tenant with one grant (role on one shop, or on all shops when shop_id is NULL). Tenant-scoped under FORCE RLS; the link token is held only as SHA-256. Status is derived from accepted_at / revoked_at / expires_at, never stored.';
COMMENT ON COLUMN staff_invite.email_normalised IS
    'The invitee address, trimmed and lower-cased. Kept until the invitation is accepted, cancelled or re-sent (GDPR residual recorded in 37-07).';
COMMENT ON COLUMN staff_invite.shop_id IS
    'NULL means all shops of the tenant. Always NULL for GROUP_ADMIN (ck_staff_invite_ga_all_shops).';
COMMENT ON COLUMN staff_invite.token_sha256 IS
    'SHA-256 hex of the single-use link token (32 random bytes, base64url). The readable token is never stored.';
COMMENT ON COLUMN staff_invite.revoked_at IS
    'Set when a Group admin cancels the invitation or sends it again; the old link stops working.';

ALTER TABLE staff_invite ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_invite FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='staff_invite' AND policyname='staff_invite_tenant_policy') THEN
    CREATE POLICY staff_invite_tenant_policy ON staff_invite
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;

DO $$
BEGIN
    RAISE NOTICE 'V77 staff_invite applied: table created EMPTY under ENABLE + FORCE RLS (staff_invite_tenant_policy via current_tenant_id()); no row was written or changed.';
END $$;
