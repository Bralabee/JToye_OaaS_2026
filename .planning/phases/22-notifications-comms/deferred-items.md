# Phase 22 — Deferred Items

Out-of-scope discoveries logged during plan execution (SCOPE BOUNDARY rule). These
are NOT fixed by the discovering plan; they are reconciled at the phase gate.

## OpenAPI snapshot (`docs/api/openapi-snapshot.json`) is stale — needs one phase-gate regeneration

**Discovered during:** 22-03 (webhook subscriptions).

**What:** `OpenApiSnapshotTest` asserts byte-equality of `docs/api/openapi-snapshot.json`
against the live `/v3/api-docs`. Running `./gradlew :core-java:updateOpenApiSnapshot`
shows the committed snapshot is missing endpoints from **already-merged** plans, not just
this one:

- Phase 21: `/api/v1/onboarding/withdraw`, `/api/v1/onboarding/company-number`,
  `/api/v1/onboarding/admin/reviews`, `/api/v1/onboarding/admin/{id}/gates/{gateType}/resolve`
- 22-02: `/api/v1/public/unsubscribe` (+ `/public/unsubscribe`)
- 22-03: `/api/v1/webhooks`, `/api/v1/webhooks/{id}`, and the
  `rotate-secret` / `pause` / `resume` / `revoke` actions
- 22-05: `GET /api/v1/webhooks/{subscriptionId}/deliveries` (delivery log) and
  `POST /api/v1/webhooks/{subscriptionId}/deliveries/{deliveryId}/replay`

The snapshot is a whole-spec, byte-stable artifact — it cannot be regenerated for one
plan's endpoints in isolation. Regenerating it here would absorb three plans' worth of
unrelated API surface into the 22-03 commit and violate the plan's "stay within webhook/*
and V55" invariant. So 22-03 deliberately does NOT touch the snapshot.

**Action required (phase gate):** run `./gradlew :core-java:updateOpenApiSnapshot` once, in
the same reconciliation step as `scripts/docs-freshness.sh --write` / `docs/metrics.json`,
and commit the single regenerated snapshot. The regeneration was verified to REMOVE no
existing endpoints (additive only).

**Note:** `OpenApiSnapshotTest` (tag `testcontainers`) is therefore already red on this
branch independent of 22-03 (Phase 21 / 22-02 drift). 22-03's own verification uses the
filtered `uk.jtoye.core.webhook.*` suites, which are all green.

## `docs/metrics.json` invocation counts — phase-gate reconcile

**Discovered during:** 22-05 (webhook delivery engine).

**What:** each Comms plan adds Java `@Test`/integration methods (22-05 adds
`WebhookSignerTest` ×4, `WebhookDeliveryRlsPolicyIntegrationTest` ×2,
`WebhookDeliveryWorkerIntegrationTest` ×3, `WebhookRetentionCleanupTest` ×1). The
`docs-freshness` CI gate (`scripts/docs-freshness.sh`) enforces the invocation
totals in `docs/metrics.json`, which are a whole-repo aggregate. Per the RESEARCH
sampling plan, the count reconcile runs ONCE at the phase gate
(`scripts/docs-freshness.sh --write`), not per-plan, to avoid every plan editing the
same shared aggregate file. 22-05 stays within `webhook/*` + `V56` + `application.yml`
and its own tests; the metrics reconcile is deferred to the phase gate alongside the
OpenAPI snapshot regeneration above.
