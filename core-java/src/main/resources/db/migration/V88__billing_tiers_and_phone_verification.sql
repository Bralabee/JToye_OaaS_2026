-- =============================================================================
-- V88: Recurring billing tiers (GAP-GTM-05 / PAY-02 / D-B9 / B8) and
--      verified-telephone payment links (#462 / #461 / PAY-04 / B5).
-- =============================================================================
--
-- WHY THIS MIGRATION EXISTS
-- -------------------------
-- 1. Plan-Configurable Recurring Billing & Fee Metering (GAP-GTM-05 / PAY-02 / D-B9):
--    ADR-0007 §1.4 and BUSINESS-ALIGNMENT-2026-10.md (D-B9, B8, B9) require
--    plan-configurable subscription billing and per-plan application fee basis
--    points on `tenants`:
--      - SAAS_HYBRID_STANDARD: £39/location/mo + 50 bps (0.5%)
--      - SAAS_FLAT_PREDICTABLE: £79–£149/location/mo + 0 bps (0%)
--      - BATCH_PAYG_STARTER: £0/mo + 600 bps (6.0%) for weekend batch drops
--      - WALK_SETUP_STEPDOWN (D-B9): £195/mo during setup, then steps down on
--        `walk_setup_completed_at` to 250 bps (2.5%) capped at £39/mo per
--        account (£0 in a quiet month).
--
-- 2. Telephone OTP Verification (#462 / PAY-04):
--    Adds `customers.phone_verified` and `customers.phone_verified_at` (plus
--    nullable mirrors on `customers_aud`), `orders.phone_verified` (plus
--    `orders_aud`), and the tenant-scoped `phone_verification_challenges`
--    table storing SHA-256 OTP digests and single-use verification tokens.
--
-- 3. Single-Use Expiring Payment Links (#461 / PAY-04 / B5):
--    Adds `order_payment_links` (`ENABLE + FORCE ROW LEVEL SECURITY`) with a
--    database CHECK (`phone_verified = true`) ensuring payment links can only
--    be issued to a verified telephone number, supporting UPFRONT, DEPOSIT,
--    and BALANCE payment stages.
-- =============================================================================

-- ============================================================
-- 1. tenants: recurring billing plan & Stripe subscription state
-- ============================================================
ALTER TABLE tenants
    ADD COLUMN IF NOT EXISTS billing_plan_mode VARCHAR(40),
    ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS stripe_subscription_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS subscription_current_period_end TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS subscription_cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS latest_stripe_invoice_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS walk_setup_completed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS monthly_fee_charged_pennies BIGINT NOT NULL DEFAULT 0;

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_billing_plan_mode
        CHECK (billing_plan_mode IS NULL OR billing_plan_mode IN (
            'SAAS_HYBRID_STANDARD',
            'SAAS_FLAT_PREDICTABLE',
            'BATCH_PAYG_STARTER',
            'WALK_SETUP_STEPDOWN'
        )),
    ADD CONSTRAINT ck_tenants_subscription_status
        CHECK (subscription_status IN ('NONE', 'ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED')),
    ADD CONSTRAINT ck_tenants_monthly_fee_non_negative
        CHECK (monthly_fee_charged_pennies >= 0);

CREATE INDEX IF NOT EXISTS idx_tenants_stripe_customer_id
    ON tenants (stripe_customer_id)
    WHERE stripe_customer_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_tenants_stripe_subscription_id
    ON tenants (stripe_subscription_id)
    WHERE stripe_subscription_id IS NOT NULL;

-- ============================================================
-- 2. customers + customers_aud: telephone verification (#462)
-- ============================================================
ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMPTZ;

ALTER TABLE customers_aud
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN,
    ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMPTZ;

-- ============================================================
-- 3. orders + orders_aud: verified phone snapshot (#461 / #462)
-- ============================================================
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN;

ALTER TABLE orders_aud
    ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN;

-- ============================================================
-- 4. phone_verification_challenges: tenant-scoped OTP challenges
-- ============================================================
CREATE TABLE IF NOT EXISTS phone_verification_challenges (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    customer_id UUID REFERENCES customers(id) ON DELETE SET NULL,
    phone_normalized VARCHAR(32) NOT NULL,
    otp_sha256 VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL DEFAULT 'SMS',
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_phone_verification_attempts_non_negative
        CHECK (attempts >= 0),
    CONSTRAINT ck_phone_verification_channel
        CHECK (channel IN ('SMS', 'WHATSAPP'))
);

CREATE INDEX IF NOT EXISTS idx_phone_verification_tenant_phone
    ON phone_verification_challenges (tenant_id, phone_normalized, created_at DESC);

ALTER TABLE phone_verification_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE phone_verification_challenges FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'phone_verification_challenges'
          AND policyname = 'phone_verification_challenges_tenant_isolation'
    ) THEN
        CREATE POLICY phone_verification_challenges_tenant_isolation ON phone_verification_challenges
            FOR ALL
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;
END $$;

-- ============================================================
-- 5. order_payment_links: single-use expiring bearer links (#461)
-- ============================================================
CREATE TABLE IF NOT EXISTS order_payment_links (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    token_sha256 VARCHAR(64) NOT NULL UNIQUE,
    purpose VARCHAR(16) NOT NULL,
    amount_pennies BIGINT NOT NULL,
    phone_verified BOOLEAN NOT NULL,
    stripe_payment_intent_id VARCHAR(255),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_order_payment_links_purpose
        CHECK (purpose IN ('UPFRONT', 'DEPOSIT', 'BALANCE')),
    CONSTRAINT ck_order_payment_links_amount_positive
        CHECK (amount_pennies > 0),
    CONSTRAINT ck_order_payment_links_phone_verified
        CHECK (phone_verified = true)
);

CREATE INDEX IF NOT EXISTS idx_order_payment_links_order
    ON order_payment_links (order_id, purpose);

CREATE INDEX IF NOT EXISTS idx_order_payment_links_tenant
    ON order_payment_links (tenant_id);

ALTER TABLE order_payment_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_payment_links FORCE ROW LEVEL SECURITY;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'order_payment_links'
          AND policyname = 'order_payment_links_tenant_isolation'
    ) THEN
        CREATE POLICY order_payment_links_tenant_isolation ON order_payment_links
            FOR ALL
            USING (tenant_id = current_tenant_id())
            WITH CHECK (tenant_id = current_tenant_id());
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
        WHERE tablename = 'order_payment_links'
          AND policyname = 'order_payment_links_public_select'
    ) THEN
        CREATE POLICY order_payment_links_public_select ON order_payment_links
            FOR SELECT
            USING (
                current_tenant_id() IS NULL
                AND EXISTS (
                    SELECT 1 FROM shops s
                    WHERE s.id = order_payment_links.shop_id
                      AND s.published = true
                )
            );
    END IF;
END $$;
