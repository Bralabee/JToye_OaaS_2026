-- V48: Issue #102 [P2-11] — production tenant lifecycle + Stripe Connect linkage.
--
-- Extends the `tenants` registry so a tenant can be created / suspended /
-- offboarded through the admin API (no more SQL-only lifecycle), and links a
-- Stripe Connect connected account per tenant so MARKETPLACE orders can be
-- routed as destination charges (ADR-0001 Decision 2).
--
--   1. Lifecycle:   status (ACTIVE/SUSPENDED/OFFBOARDED) + plan/tier
--                   + contact fields + suspended_at/offboarded_at/updated_at.
--   2. Connect:     stripe_account_id + stripe_connect_status
--                   (NONE/PENDING/ENABLED/DISABLED, driven by account.updated).
--
-- RLS posture — DELIBERATELY NONE: `tenants` is the cross-tenant registry
-- (V2 leaves it policy-less on purpose; see V2 lines 51-52). Access control is
-- role-gated at the API layer (@PreAuthorize hasRole('admin') on the admin
-- controller), matching how the registry is protected today. No _aud mirror:
-- Tenant is not @Audited (posture unchanged).
--
-- All new NOT NULL columns carry DEFAULTs so the V13 seed rows, the dev-only
-- DevTenantService native INSERT (id, name) and existing test seeds keep
-- working unchanged. Idempotent DO-block DDL follows the V42/V43 house style.
-- Forward-only.

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS plan   VARCHAR(16) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS contact_name  VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS contact_email VARCHAR(320);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(32);

ALTER TABLE tenants ADD COLUMN IF NOT EXISTS suspended_at  TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS offboarded_at TIMESTAMPTZ;
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS updated_at    TIMESTAMPTZ;

-- Stripe Connect linkage (ADR-0001 Decision 2). stripe_connect_status is the
-- platform's cached view of the connected account's capability state, derived
-- from the `account.updated` webhook:
--   NONE     — no connected account linked
--   PENDING  — account created, Express onboarding not complete
--   ENABLED  — charges_enabled=true → destination charges may route here
--   DISABLED — Stripe disabled the account (requirements.disabled_reason set)
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS stripe_account_id     VARCHAR(255);
ALTER TABLE tenants ADD COLUMN IF NOT EXISTS stripe_connect_status VARCHAR(16) NOT NULL DEFAULT 'NONE';

DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tenants_status') THEN
    ALTER TABLE tenants ADD CONSTRAINT chk_tenants_status
        CHECK (status IN ('ACTIVE','SUSPENDED','OFFBOARDED'));
  END IF;
  -- Plan vocabulary matches the RateLimitInterceptor tier vocabulary
  -- (STANDARD/PREMIUM/INTERNAL) so the "tier lookup returns standard for all"
  -- gap (#102 evidence) has a real column to read from.
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tenants_plan') THEN
    ALTER TABLE tenants ADD CONSTRAINT chk_tenants_plan
        CHECK (plan IN ('STANDARD','PREMIUM','INTERNAL'));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_tenants_stripe_connect_status') THEN
    ALTER TABLE tenants ADD CONSTRAINT chk_tenants_stripe_connect_status
        CHECK (stripe_connect_status IN ('NONE','PENDING','ENABLED','DISABLED'));
  END IF;
END $$;

-- One tenant per connected account: the account.updated webhook resolves the
-- tenant BY stripe_account_id, so the linkage must be unique (and indexed for
-- that lookup). Partial: unlinked tenants all carry NULL.
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenants_stripe_account
    ON tenants (stripe_account_id) WHERE stripe_account_id IS NOT NULL;

-- Cheap scan for the (cached) request-time status check and admin list filters.
CREATE INDEX IF NOT EXISTS idx_tenants_status ON tenants (status);
