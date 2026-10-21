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
