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
- 22-03 (this plan): `/api/v1/webhooks`, `/api/v1/webhooks/{id}`, and the
  `rotate-secret` / `pause` / `resume` / `revoke` actions

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
