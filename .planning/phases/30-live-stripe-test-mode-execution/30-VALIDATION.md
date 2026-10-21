---
phase: "30"
slug: "live-stripe-test-mode-execution"
# status lifecycle: draft (seeded by plan-phase) → validated (set by validate-phase §6)
status: draft
nyquist_compliant: false
wave_0_complete: false
created: "2026-10-21"
---

# Phase 30 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution. Seeded from
> `30-RESEARCH.md` § Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (Postgres 18) via Gradle 9.7.1; Jest 30 (frontend); Playwright 1.64 (E2E, projects `mobile`/`desktop`); bash `--selftest` arms for scripts/gates |
| **Config file** | `core-java/build.gradle.kts` (build dir `build-local`); `frontend/jest.config.*`; `frontend/playwright.config.ts` |
| **Quick run command** | `./gradlew :core-java:test --tests 'uk.jtoye.core.order.OrderServiceTest'` ; `cd frontend && npx jest 'app/shop/\[slug\]/checkout'` |
| **Full suite command** | `./gradlew :core-java:test` ; `cd frontend && npx jest` ; nightly Playwright (`e2e-nightly.yml`) |
| **Estimated runtime** | quick ~60 s; full core-java ~15 min; full jest ~5 min |

Local Node is v22; the project requires 24+. A local frontend run is trusted only on Node 24 (or in CI).

---

## Sampling Rate

- **After every task commit:** the quick command for the touched module + the relevant gate selftest
- **After every plan (PR):** full `core-java` test + full jest; `k8s/scripts/check-env-contract.sh`; `scripts/check-branch-behind-base.sh`; `scripts/docs-freshness.sh` + `scripts/check-doc-metrics.sh` after `docs/metrics.json` is updated
- **Before `/gsd-verify-work`:** one green nightly with the refund test PASSED (not skipped), the skip-budget gate green on the merged report, and the staging money-loop evidence file
- **Max feedback latency:** 120 s for the quick loop

---

## Per-Task Verification Map

| Req | Behaviour | Test Type | Automated Command | File Exists | Status |
|-----|-----------|-----------|-------------------|-------------|--------|
| #957 | Card shop + no pk → visible error, no POST; clientSecret + no pk → visible error | Jest | `npx jest app/shop/\[slug\]/checkout/__tests__/card-unavailable.test.tsx` | ❌ W0 | ⬜ pending |
| #957 | Served staging chunks contain `pk_test_`, no env residue; control ≥1 | script + break arm | `bash scripts/check-frontend-stripe-key-baked.sh --url https://app-staging.olajay.co.uk` | ❌ W0 | ⬜ pending |
| #957 | 4242 card completes to confirmation + CAPTURED on staging | Playwright vs staging | `PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/staging-card-checkout.spec.ts --project=desktop` | ❌ | ⬜ pending |
| #957 | deploy-staging refuses empty / non-`pk_test_` var | CI step script + break arm | `bash scripts/check-stripe-pk-var.sh` | ❌ | ⬜ pending |
| #958 | SUBMIT/CONFIRM of an unpaid card DRAFT refused, typed; no outbox row; vendor NONE DRAFT still submits | JUnit (Testcontainers) | `./gradlew :core-java:test --tests 'uk.jtoye.core.order.OrderServiceTest'` | partial | ⬜ pending |
| #958 | Payment success keeps CONFIRMED on a link order | JUnit | `./gradlew :core-java:test --tests 'uk.jtoye.core.payment.PaymentServiceTest'` | ❌ new case | ⬜ pending |
| #958 | Kitchen toast shows the problem detail | Jest | `npx jest app/dashboard/kitchen` | ❌ | ⬜ pending |
| #108 | Stripe timeouts set from config; refund behind breaker | JUnit | `./gradlew :core-java:test --tests 'uk.jtoye.core.payment.*'` | ❌ | ⬜ pending |
| PAY-03 | Connect route verifies only with its own secret; forged/unsigned 400, 0 rows | JUnit integration | `./gradlew :core-java:test --tests 'uk.jtoye.core.payment.StripeWebhookIdempotencyIntegrationTest'` | partial | ⬜ pending |
| PAY-01 | Bare ALLOW → rc 1; no REMOVE WHEN → rc 1; real conf → rc 0 | bash selftest | `bash scripts/check-e2e-skip-budget.sh --selftest` | ❌ | ⬜ pending |
| PAY-01 | Refund spec passes in the keyed nightly phase; Stripe shows the refund | Playwright (nightly) | `npx playwright test --grep @stripe` | needs rewrite | ⬜ pending |
| PAY-03 | Loop verdict lines, every object `livemode:false`, both modes | bash selftest + live | `bash scripts/staging-seed.sh --selftest` | ❌ | ⬜ pending |
| env/KV | Contract + chain after the new secret/ARG | gates | `bash k8s/scripts/check-env-contract.sh`; `bash scripts/staging-secrets.sh --selftest` | ✅ (to update) | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*. Plan/task IDs are filled in by the planner.

### Task Map (planner, 2026-10-21)

Each row above, mapped to the plan task that builds it (RED first) and the one that proves it live. Where a plan
corrected the command in the row above, the correction is named; the plan's `<verify>` is authoritative.

| Row | Built (RED → GREEN) | Proven live / in CI | Command correction |
|-----|---------------------|---------------------|--------------------|
| #957 card shop without a key → visible error, no POST | 30-02 T1 (fail-loud states, card-unavailable notice) | 30-04 T2 (staging run) | Jest runs as `npm --prefix frontend test -- --ci --runTestsByPath <paths>` |
| #957 served chunks hold `pk_test_`, control ≥ 1 | 30-01 T1 (script + `--selftest`) | 30-03 T1 (RED vs today's staging), 30-04 T2 (GREEN) | — |
| #957 4242 completes to CAPTURED on staging | 30-03 T1 (spec, RED arm) | 30-04 T2; direct-charge variant 30-32 T1 (account-aware read from 30-19 T3) | needs `STAGING_E2E=1` and `--project=desktop`: `(cd frontend && STAGING_E2E=1 PLAYWRIGHT_BASE_URL=https://app-staging.olajay.co.uk npx playwright test e2e/staging-card-checkout.spec.ts --project=desktop --reporter=line)` |
| #957 deploy refuses an empty / non-`pk_test_` variable | 30-01 T2 (`check-stripe-pk-var.sh --selftest`, staging and production steps) | 30-03 T3 (owner sets the variable; read-back), 30-04 T1 | — |
| #958 SUBMIT/CONFIRM/PREPARING of an unpaid card DRAFT refused 409, no outbox row; vendor NONE order unchanged (D-01) | 30-05 T1 (`OrderAwaitingPaymentGateIntegrationTest`), 30-07 T1 (`--stage vendor-order`) | 30-08 T3 (live 409; SUBMIT 200 on a vendor NONE DRAFT, the same-event control; confirm 200 on a paid order) | the integration test runs under `:core-java:integrationTest` (tag `testcontainers`), not `:core-java:test` |
| #958 payment success keeps a confirmed link order's status | 30-05 T3 (`PaymentServiceTest`, WARN `payment_on_closed_order`) | 30-08 T3 | — |
| #958 kitchen and orders surfaces show the problem detail | 30-06 T1 (badge, hidden actions), 30-06 T2 (kitchen toast "Order not updated", KDS) | 30-08 T3 (the live 409 these surfaces render) | — |
| D-02 abandoned DRAFT cleanup | 30-07 T2 (`cancel-drafts`, `stripe-get` arms) | 30-09 T2–T3 (owner decision, run, Stripe read-back) | — |
| #108 Stripe timeouts from config; refund behind a breaker | 30-10 T1 (`stripe.connect-timeout-ms` …), 30-10 T2 (`stripe-refund` / `stripe-lookup` instances, `StripeBreakerFailurePredicate`, rejected-refunds and isolation arms, `CallNotPermittedException` → 503) | 30-11 T1 (old log line, RED), 30-11 T3 (configured timeouts, GREEN) | — |
| PAY-03 Connect route verifies only with its own secret | 30-12 T1 (route, verify/dispatch split), 30-13 T1 (secret wiring) | 30-15 T2 (forged/unsigned 400, 0 rows), 30-31 T2 (positive arm) | the Connect test is a new integration test, run under `:core-java:integrationTest` |
| PAY-04 `/pay` page (D-03) | 30-16 T3 (preview), 30-17 T1 (`stripeAccountId`), 30-18 T1 (`pay-client`), 30-19 T3 (staging spec) | 30-20 T3 (destination), 30-32 T1 (direct) | — |
| PAY-01 skip-budget rules | 30-21 T1 (`check-e2e-skip-budget.sh --selftest`, S-5) | 30-23 T2 (the stale ALLOW turns the gate red), 30-23 T3 (ALLOW retired, measured MAX_SKIPS, S-5 refusal shown) | — |
| PAY-01 refund spec in the keyed nightly phase | 30-22 T1 (`stripe-refund-flow.spec.ts`, `STRIPE-REFUND` line) | 30-23 T2 (branch nightly), 30-24 T2 (main nightly) | NOT `--grep @stripe` (a CLI grep replaces the projects' grepInvert); the spec is collected only with `STRIPE_E2E=1` (30-22 T1) |
| PAY-02 per-location quantity (D-04) | 30-25 T3 (`BillingLocationSync`), 30-26 T2 (`billing-sync-check`) | 30-27 T3 (`evidence/30-pay02-billing.json`, invoice by id) | — |
| PAY-03 loop verdict lines, both modes | 30-28 T1–T2 (destination), 30-29 T1–T2 (direct, payout, run-id) | 30-30 T1 (RED refusal), 30-31 T3 (destination), 30-32 T1–T2 (direct, payout) | a Refund object has no `livemode`; its charge is read instead (30-22, 30-28, 30-29) |
| env/KV contract after the new secret and build arg | 30-01 T1 (env-contract row), 30-13 T1–T2 (KV class, SPC, env) | 30-14 T3 (endpoint, KV object, access), 30-15 T2 (pod mounts the secret) | — |
| Known gaps record (D-01, D-02, refund routing, billing reconcile, phone delivery, #108 non-Stripe half, D-08) | — | 30-32 T3 (`## Known gaps (Phase 30 close)` in 30-VERIFICATION.md) | — |

---

## Wave 0 Requirements

- [ ] `frontend/app/shop/[slug]/checkout/__tests__/card-unavailable.test.tsx` (red on today's tree)
- [ ] served-chunk key checker script with its break arm
- [ ] `OrderServiceTest` SUBMIT/CONFIRM refusal arms + an outbox-row integration assertion (red today: 200)
- [ ] `check-e2e-skip-budget.sh` justification/REMOVE-WHEN check + selftest arms
- [ ] nightly Stripe phase scaffolding (CLI pin, PID-captured listener, report merge)
- [ ] `staging-seed.sh` money-loop stage stubs (selftest arms red first)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Set the staging `pk_test_` repo variable | #957 | owner holds the Stripe account | owner sets it; Claude reads back that it is non-empty and starts `pk_test_` (never prints it) |
| Create the CI sandbox key | PAY-01 | owner action (D-09) | owner creates; Claude confirms the secret name exists |
| Register the Connect webhook endpoint + complete hosted onboarding | PAY-03 | Stripe dashboard / hosted flow | owner completes; Claude reads `charges_enabled`/`payouts_enabled` from Stripe's API |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120 s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
