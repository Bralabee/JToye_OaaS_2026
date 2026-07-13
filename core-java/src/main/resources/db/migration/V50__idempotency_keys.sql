-- V50: idempotency_keys — uniform, tenant-scoped Idempotency-Key contract
-- (Issue #204 / AI-2). The generic dedup store behind the reusable
-- `@Idempotent` HTTP header contract, adopted this slice by orders.create and
-- customers.create.
--
-- It mirrors the V47 processed_order_events precedent EXACTLY: a composite
-- semantic key, ENABLE+FORCE ROW LEVEL SECURITY, the standard tenant policy,
-- and NO `_aud` Envers mirror (a dedup store is deliberately not audited —
-- same posture as V47/V35). The reserve idiom is the house
-- `INSERT ... ON CONFLICT DO NOTHING` (OrderStateChangeListener,
-- PaymentService.handleWebhookEvent): 1 row inserted => first request runs the
-- work and then stamps response_status + response_body; 0 rows => replay of the
-- stored response (or a 409 while the first request is still in-flight).
--
-- Column semantics:
--   endpoint         — the LOGICAL operation id (e.g. 'orders.create'), NOT the
--                      URL, so the key survives future API versioning.
--   idempotency_key  — the client-supplied Idempotency-Key header value (<= 64).
--   request_hash     — SHA-256 hex of the canonical request body; a replay with
--                      a DIFFERENT body under the same key is rejected 422.
--   response_status  — NULL while the FIRST request is still in-flight; a
--                      concurrent same-key request that sees NULL gets 409.
--   response_body    — the serialized response DTO (JSON). For orders.create
--                      this DTO carries customer PII (customerName / customerEmail
--                      / customerPhone), so FORCE RLS here is LOAD-BEARING, not
--                      ceremonial — cross-tenant read of this table would be a
--                      PII disclosure. Proven under the NOSUPERUSER role-downgrade
--                      by IdempotencyKeysRlsPolicyIntegrationTest.
--
-- Growth: one row per keyed mutation. Pruning is deferred to the existing
-- scheduled-cleanup housekeeping (ops note, not a launch blocker), mirroring
-- V47's posture and documented in docs/idempotency.md.
--
-- V50 is the next free slot (head is V49) and is sequential, so the
-- project-wide spring.flyway.out-of-order=true does not interact.

CREATE TABLE idempotency_keys (
    tenant_id       UUID         NOT NULL,
    endpoint        VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    request_hash    CHAR(64),
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, endpoint, idempotency_key)
);

ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY idempotency_keys_tenant ON idempotency_keys
    FOR ALL
    USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
