---
phase: quick
plan: 261102-apf
subsystem: payments / Stripe Connect
tags: [stripe, connect, managed-risk, openapi, issue-1090, PAY-03]
status: complete
issue: 1090
requires: []
provides:
  - "Account.create under Managed Risk controller properties (no legacy type)"
  - "assertManagedRiskController helper in StripeConnectServiceTest"
  - "OpenAPI snapshot regenerated (3 description lines)"
  - "CHANGELOG top entry for the Managed Risk change"
affects: [30-30, 30-31, 30-32]
tech-stack:
  added: []
  patterns: ["Accounts v1 controller properties instead of AccountCreateParams.Type"]
key-files:
  created:
    - .planning/quick/261102-apf-fix-1090-stripe-refuses-connected-accoun/evidence/1090-red-green.txt
  modified:
    - core-java/src/main/java/uk/jtoye/core/payment/StripeConnectService.java
    - core-java/src/test/java/uk/jtoye/core/payment/StripeConnectServiceTest.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentController.java
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentService.java
    - core-java/src/main/java/uk/jtoye/core/payment/StripeProperties.java
    - core-java/src/main/java/uk/jtoye/core/payment/dto/ConnectAccountDto.java
    - core-java/src/main/java/uk/jtoye/core/tenant/StripeConnectStatus.java
    - core-java/src/main/java/uk/jtoye/core/tenant/TenantAdminController.java
    - core-java/src/main/java/uk/jtoye/core/tenant/dto/CreateTenantRequest.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/payment/StripeConnectWebhookRouteIntegrationTest.java
    - docs/api/openapi-snapshot.json
    - docs/CHANGELOG.md
decisions:
  - "Owner ruling 2026-11-02, issue #1090 option A: connected accounts are created under Managed Risk (losses.payments=stripe, fees.payer=account, requirement_collection=stripe, stripe_dashboard.type=full)"
  - "Dashboard is full: Express Dashboard with Stripe as losses collector is preview-only (2026-10-28.preview); stripe-java 34.0.0 pins GA"
  - "Method name createOrResumeExpressOnboarding kept (no churn); V48 migration comment untouched (Flyway checksum)"
metrics:
  duration: "~9 min (07:53Z to 08:02Z, 2026-11-02)"
  completed: 2026-11-02
actuals:
  tokens: 7300
  tasks: 2
  commits: 2
commits: 2
plan_head_before: 0ffad5752bf259585b2fdfa52cfbfecb2a8c399d
plan_head_after: 49e652dd4fae55046817d929a048b141bd9f3e52
---

# Quick 261102-apf: Fix #1090 (Stripe refuses connected-account create) Summary

`StripeConnectService` now creates the tenant's connected account with Accounts v1 controller properties: `losses.payments=stripe`, `fees.payer=account`, `requirement_collection=stripe` and `stripe_dashboard.type=full`. It no longer sends the legacy `type=express`, which Stripe refused while the platform was the losses collector. The new unit test was RED on the old service (22 tests, 2 failures) and GREEN on the fix (22 tests, 0 failures). The OpenAPI contract, comments and CHANGELOG now describe the account the code actually creates.

## Commits

| Task | Commit | Subject |
|------|--------|---------|
| 1 | `3717d5cc` | fix(payments): create connected accounts under Managed Risk controller properties, not the legacy type |
| 2 | `49e652dd` | docs(payments): call the Stripe account a connected account, not Express; regenerate the OpenAPI snapshot; changelog for the Managed Risk change |

Both messages were read back with `git log -1 --format=%B`. Each ends with `Refs #1090` and carries no Co-Authored-By, Claude-Session, "Generated with" or closing-keyword text. The check is `grep -E -i 'co-authored-by|claude-session|generated with|(close[sd]?|fix(e[sd])?|resolve[sd]?) #1090'`. It came back clean on both commits, and its control (a string containing `Closes #1090` and `Co-Authored-By`) hit, rc 0. `git log --oneline origin/main..HEAD -- . ':!.planning'` shows exactly these two commits.

## Evidence

`/home/sanmi/IdeaProjects/JToye_OaaS_2026/.planning/quick/261102-apf-fix-1090-stripe-refuses-connected-accoun/evidence/1090-red-green.txt` holds both directions of every gate, verbatim:

| Gate | Fail direction (HEAD 0ffad575 / pre-fix) | After |
|------|------------------------------------------|-------|
| StripeConnectServiceTest (XML, build-local, after cleanTest) | tests=22 failures=2 errors=0, gradle rc 1. Each failure is `MultipleFailuresError: Managed Risk connected-account params (issue #1090) (5 failures)`: type `but was: <EXPRESS>`, and the four controller properties each `but was: <null>` | tests=22 failures=0 errors=0, gradle rc 0 |
| PaymentServiceTest (XML) | n/a (untouched) | tests=53 failures=0 errors=0 |
| `Type.EXPRESS` in core-java/src | 2 lines, rc 0 | rc 1 |
| `Controller.Losses.Payments.STRIPE` in service | rc 1 | count 1, rc 0 |
| Phrase gate, Task 1 files | 8 lines, rc 0 | rc 1 |
| Phrase gate, Task 2 files (9) | 18 lines, rc 0 | rc 1 |
| `Express` in openapi-snapshot.json | 3 lines, rc 0 | rc 1 |
| OpenApiSnapshotTest check mode | tests=1 failures=1, rc 1, after the @Operation edits; message names `./gradlew :core-java:updateOpenApiSnapshot` | tests=1 failures=0 errors=0, rc 0 |
| Snapshot numstat | empty before regeneration | `3	3	docs/api/openapi-snapshot.json`, only the three `"description"` lines (6704, 13187, 16593) |
| Migrations untouched | — | rc 0 |
| docs/metrics.json unchanged | — | rc 0 |
| `scripts/docs-freshness.sh` | fail direction not re-executed here | rc 0 (6996 logical invocations) |
| `scripts/check-doc-metrics.sh` | fail direction not re-executed here | rc 0 (37/37 claims) |
| `scripts/check-branch-behind-base.sh` (after `git fetch origin`, rc 0) | fail direction not re-executed here | rc 0 (0 behind origin/main 0ffad575) |

## Deviations from Plan

**1. [Rule 1 - plan expectation] The PaymentServiceTest XML count is 53, not 47**
- **Found during:** the Task 1 GREEN run.
- **Issue:** the plan expected `tests="47"` in the XML. 47 is the `@Test` source count. The file also has 2 `@ParameterizedTest` methods with 3 `@ValueSource` cases each, so JUnit reports 47 + 6 = 53.
- **Fix:** none needed. The file is unchanged against origin/main (`git diff --quiet` rc 0), and 53/0 is the pre-existing figure. The figure is recorded in the evidence file. `docs/metrics.json` counts `@Test` (47), and it is unchanged.

**2. [Rule 3 - blocking] Python edits run in the project env**
- The `block-base-python` hook refused `/usr/bin/python3` for the scratchpad edit scripts and named the project env. The scripts were re-run with `conda run -n jtoye-ops python -I`, as the hook instructs. Nothing had been written by the refused call (`git diff --stat` was empty).

Otherwise the plan was executed as written.

## Ship-step obligation (not done here)

After `gh pr create`:
1. Insert ` (#<PR>)` before ` — 2026-11-02` in the new CHANGELOG heading, `### Stripe Connect: Connected Accounts Created Under Managed Risk, Not the Legacy Express Type — 2026-11-02`.
2. Push.
3. Run `bash scripts/check-changelog-cites-pr.sh --pr <PR> --title '<PR title>'` and expect rc 0. Run the control with `--pr 999999` and expect rc 1.

P-1 runs in CI on the PR and needs the heading to cite the PR's own number.

## Downstream items for 30-30..30-32 (recorded, not acted on)

- **T-apf-03, the idempotency key's 24 h window.** The key `connect-account-create-<tenantId>` is unchanged. Stripe saves no idempotent result for a request that fails validation, and the 05:29Z attempt created nothing, so the retry is expected to succeed. If 30-30's retry returns an `idempotency_error` instead, the key ages out 24 h after its first use, by 2026-11-03 05:29Z. 30-30 should read the Stripe error class before re-running.
- **T-apf-04, out-of-band dashboard refunds.** With `stripe_dashboard.type=full`, the vendor can refund or pay out from their own Stripe Dashboard, outside the platform. Whether a dashboard refund reconciles locally through the Connect endpoint's `refund.*` handling is a question for the 30-31/30-32 live loops.
- **T-apf-05, payout-interval control under Managed Risk.** The 30-29 `--stage payout` sets `interval=manual` on the connected account. Stripe docs (manage-payout-schedule) say "Platforms that manage fraud and dispute liability, or have platform controls, can adjust the payout interval." Under Managed Risk the platform does not manage that liability, so the payout stage may be refused. That risk is transferred to 30-31/30-32; no script was changed here.
- **ADR-0001 still describes Express.** Lines 5 and 156 of `docs/architecture/decisions/ADR-0001-onboarding-approval-and-stripe-money-flow.md` still say Express accounts. A decision-record addendum citing the 2026-11-02 ruling is the owner's call, not this plan's.
- **Issue #1090 stays OPEN** until 30-30 proves the create on staging. No commit or PR text may carry a closing keyword for it.

## Quality dimensions

- Agent-readiness: the OpenAPI contract was updated in lockstep, and the descriptions no longer misstate the account type.
- Security: the plan's threat model applies. T-apf-01 and T-apf-02 are mitigated by `assertManagedRiskController`, which was shown RED on the old service. No new trust boundary was introduced beyond those the threat model names.
- Falsifiability: every criterion's fail direction is recorded above, or labelled "not re-executed here".
- Web-perf, SEO and client-persisted identity: N/A, because no page, public surface or client storage was touched.
- Runtime parity: N/A for this local plan, because no runtime was handed back. The deployed proof is 30-30.

## Known Stubs

None. The changed files are a service builder call, a test helper, comments, OpenAPI descriptions, a regenerated snapshot and a CHANGELOG entry.

## Self-Check: PASSED

- FOUND: core-java/src/main/java/uk/jtoye/core/payment/StripeConnectService.java (`Controller.Losses.Payments.STRIPE` count 1)
- FOUND: core-java/src/test/java/uk/jtoye/core/payment/StripeConnectServiceTest.java (`assertManagedRiskController`)
- FOUND: docs/CHANGELOG.md top entry citing #1090
- FOUND: evidence/1090-red-green.txt
- FOUND: 3717d5cc (ancestor of HEAD)
- FOUND: 49e652dd (HEAD)
