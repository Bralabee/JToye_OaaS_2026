---
phase: 30-live-stripe-test-mode-execution
plan: 26
subsystem: payments
tags: [stripe, billing, per-location, staging-seed, bash, selftest, pay-02, d-04]

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-25 per-location subscription quantity (StripeBillingService.locationQuantity) and the after-commit BillingLocationSync on shop create/delete; the D-04 all-shops ruling in evidence/30-25-d04-decision.txt"
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-07 / 30-19 staging-seed.sh hand-tool helpers (vendor_login, read_stripe_key, stripe_api, assert_test_mode, api, fail_http) and the stubbed selftest harness"
provides:
  - "scripts/staging-seed.sh --stage billing: subscribes the staging tenant to SAAS_HYBRID_STANDARD through POST /api/v1/billing/subscription with a TEST pm made from tok_visa (an ACTIVE subscription is read back, never re-created), reads GET /v1/subscriptions/{id} and GET /v1/invoices/{latest_invoice} by id, and ends on `BILLING sub=… quantity=… locations=… invoice=… amount_paid=… status=paid livemode=false at=…`"
  - "scripts/staging-seed.sh --stage billing-sync-check: refuses [no-subscription]; under all-shops creates a probe shop, polls Stripe to n+1, deletes it, polls back to n, and ends on `BILLING-SYNC before=n after-add=n+1 after-remove=n livemode=false`; under published-shops prints `BILLING-SYNC not-runnable reason=no-second-publishable-shop`"
  - "constants BILLING_UNIT_PENNIES=3900, BILLING_PLAN_MODE=SAAS_HYBRID_STANDARD, BILLING_LOCATION_RULE=all-shops; the probe-shop delete on the EXIT trap"
  - "nine selftest arms (billing-ok, -quantity-mismatch, -amount-mismatch, -live, -replay, -no-secret, sync-ok, sync-no-subscription, sync-cleanup-on-fail); dry-run-tools covers both stages"
affects: [30-27]

actuals:
  tokens: 14756     # chars/4 over the realized diff (53321 committed diff chars) + the two uncommitted evidence files (5704 chars)
  tasks: 2
  commits: 4        # MEASURED: git rev-list --count d622dd28..HEAD before this SUMMARY's own commit
plan_head_before: d622dd2841dd78b5ed710899ea9fb03660363f1d
plan_head_after: c9505fab9e108148e48fb86625c43e983521f91d

tech-stack:
  added: []
  patterns:
    - "Evidence from Stripe objects read BY ID with the staging key: the stage never trusts the application's own record of the invoice; the billing API's latestStripeInvoiceId must equal Stripe's latest_invoice on a fresh subscribe"
    - "Unit-separator (\\u001f) jq projections read with IFS=$'\\x1f', never @tsv with a tab IFS, which collapses empty fields and shifts later columns"
    - "A resource the run creates (the probe shop) is registered for the EXIT-trap delete the moment its id is known, and a failed delete turns any status into a failure that names the resource"

key-files:
  created:
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-red-task1.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-red-task1.json"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-red-task2.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-red-task2.json"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-task1-arms.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-task2-arms.txt"
    - ".planning/phases/30-live-stripe-test-mode-execution/evidence/30-26-verify.txt"
  modified:
    - "scripts/staging-seed.sh"

key-decisions:
  - "Both billing stages sign in as the staging test vendor. BillingController calls ShopAccessService.requireGroupAdmin(), not the role list in the plan's interface note, and the vendor holds the tenant-wide GROUP_ADMIN grant the vendor stage made. No realm-admin operator is created."
  - "BILLING_LOCATION_RULE=all-shops is a stage constant taken from the owner's ruling in evidence/30-25-d04-decision.txt. Under it a location is every shop row of the tenant in GET /api/v1/shops. A shop with another tenantId, or more shops than one page of 100 holds, is a FAIL, never a guess."
  - "The 3900 is checked three ways, each a FAIL and never a recomputation: the billing DTO's monthlyAmountPennies, the Stripe price's unit_amount, and amount_paid == 3900 x quantity."

patterns-established:
  - "Billing evidence line: BILLING sub=… quantity=… locations=… invoice=… amount_paid=… status=paid livemode=false at=<ISO>, printed only after every check passes"
  - "Observable after-commit sync: create → poll the external system to n+1 → delete → poll back to n, with every read printed and a deadline on each wait"

requirements-completed: [PAY-02]

coverage:
  - id: D1
    description: "--stage billing subscribes through the product's billing API with a tok_visa TEST payment method, reads the subscription and its latest invoice from Stripe by id, and prints the BILLING line only when quantity == locations and amount_paid == 3900 x quantity"
    requirement: PAY-02
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-ok"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-quantity-mismatch"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-amount-mismatch"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-live"
        status: pass
      - kind: other
        ref: "evidence/30-26-task1-arms.txt: amount compared against 3900 regardless of quantity -> billing-amount-mismatch red (56/1), restore sha256 matches HEAD"
        status: pass
    human_judgment: false
  - id: D2
    description: "A re-run with an ACTIVE subscription in place makes no payment method and no subscribe, and prints the same BILLING line"
    requirement: PAY-02
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-replay"
        status: pass
    human_judgment: false
  - id: D3
    description: "Neither stage prints the Stripe key, a payment-method body, the customer id or a customer field"
    requirement: PAY-02
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#billing-no-secret"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#sync-ok (secret and customer markers = 0)"
        status: pass
    human_judgment: false
  - id: D4
    description: "--stage billing-sync-check refuses without an ACTIVE subscription, watches the quantity go n -> n+1 -> n around a probe shop, and deletes the probe on every exit path"
    requirement: PAY-02
    verification:
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#sync-ok"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#sync-no-subscription"
        status: pass
      - kind: unit
        ref: "scripts/staging-seed.sh --selftest#sync-cleanup-on-fail"
        status: pass
      - kind: other
        ref: "evidence/30-26-task2-arms.txt: EXIT-path delete removed -> sync-cleanup-on-fail red (59/1), restore sha256 matches HEAD"
        status: pass
    human_judgment: false
  - id: D5
    description: "The stages produce PAY-02 evidence against the real staging billing API and Stripe TEST account"
    requirement: PAY-02
    verification: []
    human_judgment: true
    rationale: "This plan proves the tool against stubs only. The live run on staging, after the 30-25/30-26 merge deploys, is plan 30-27's job. Only that run can show the real invoice by id and the real sync timing."

duration: 13min
completed: 2026-11-01
status: complete
---

# Phase 30 Plan 26: Staging Billing Evidence Stages Summary

**`staging-seed.sh` now has two new stages. `--stage billing` subscribes the staging tenant through the product's own billing API with a tok_visa TEST card, reads the subscription and its latest invoice from Stripe by id, and fails unless quantity equals the tenant's shop count and amount_paid equals 3900 × quantity. `--stage billing-sync-check` watches Stripe's quantity go n → n+1 → n around a probe shop, and the EXIT trap deletes that shop on every exit path.**

## Performance

- **Duration:** about 13 min
- **Started:** 2026-11-01T23:24:37Z
- **Completed:** 2026-11-01T23:37:30Z
- **Tasks:** 2 (Task 1 tracer, Task 2 auto; both tdd)
- **Files modified:** 1 source file (`scripts/staging-seed.sh`, +~520 lines), 7 evidence files

## Accomplishments

- **`--stage billing`** (PAY-02's falsifier: "a test-mode subscription invoice retrieved from Stripe by id"; D-04 adds "whose quantity matches"):
  1. It reads `GET /api/v1/billing/subscription`.
  2. If the subscription is ACTIVE with an id, it reads it back and goes no further (T-30-101).
  3. Otherwise it calls `POST /v1/payment_methods type=card card[token]=tok_visa` and keeps only the `pm_` id.
  4. It then calls `POST /api/v1/billing/subscription {planMode: SAAS_HYBRID_STANDARD, paymentMethodId}`.
  5. It reads `GET /v1/subscriptions/{id}`, which must be `active` with one item and `unit_amount` 3900, then `GET /v1/invoices/{latest_invoice}`. Both must read livemode false.
  6. It counts locations with `GET /api/v1/shops?size=100` under the all-shops rule.
  7. It checks the quantity, the amount and `status=paid`, then prints the BILLING line.
- **`--stage billing-sync-check`:**
  - With no ACTIVE subscription it stops with `REFUSED [no-subscription]` before any shop write.
  - Otherwise it reads the quantity n, then `POST /api/v1/shops {name: "Staging Billing Probe <UTC>"}`. The probe's id is registered for deletion as soon as it is known.
  - It polls Stripe to n+1, deletes the probe, and polls back to n. Every read is printed, and each wait has a 90 s deadline (`STAGING_SEED_BILLING_SYNC_DEADLINE_SECONDS`).
  - It ends on `BILLING-SYNC before=n after-add=n+1 after-remove=n livemode=false`.
  - Under the published-shops rule it prints `BILLING-SYNC not-runnable reason=no-second-publishable-shop` and exits 0. That is a limitation, not a pass.
- **Probe cleanup:** `cleanup()` deletes a probe that still exists, on every exit path. If that delete fails, the run fails and the message names the shop (T-30-102).
- **Secrets (T-30-103):** the Stripe key stays in the mode-600 curl config. Every Stripe and billing response is reduced with jq to named fields, then removed. Only ids, counts, amounts and statuses are printed.
- Usage text, `--dry-run` plans and stage validation cover both stages. The selftest went from 51 to 60 PASS, 0 FAIL, rc 0, in about 19 s.

## Task Commits

1. **Task 1 RED:** `b8f54976` test(30-26): add failing selftest arms for --stage billing
2. **Task 1 GREEN:** `cb7213e5` feat(30-26): --stage billing reads the subscription and invoice from Stripe by id
3. **Task 2 RED:** `8d21cad9` test(30-26): add failing selftest arms for --stage billing-sync-check (also carries the Task 1 break-arm record)
4. **Task 2 GREEN:** `c9505fab` feat(30-26): --stage billing-sync-check watches the quantity sync around a probe shop

No REFACTOR commit: nothing needed cleaning up after GREEN.

## TDD Evidence

**Task 1, RED (`b8f54976`):**
- TAP output: 6 not ok, 51 ok, rc 1, in `evidence/30-26-red-task1.txt`.
- Classifier: `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK`, reason `target_test_failed`, target `billing-ok`.
- Semantic assessment: the target ran and failed its first planned assertion (rc 2, want 0), because `--stage billing` was refused as an unknown stage. The behaviour did not exist yet. The 51 older arms still passed, so this was not a harness, fixture or discovery fault.

**Task 1, GREEN (`cb7213e5`):** 57 PASS, 0 FAIL, rc 0. The Task 1 `<verify>`, run verbatim: missing=0, fail_lines=0, rc 0.

**Task 1, break arm (`evidence/30-26-task1-arms.txt`):**
- Changed `want=$((BILLING_UNIT_PENNIES * SUB_QTY))` to `want=$BILLING_UNIT_PENNIES`.
- Result: 56/1, with exactly `FAIL billing-amount-mismatch rc=0 (want 1)`.
- Restored from the HEAD blob. sha256 `87c50939…` matches HEAD. Clean runs before and after: 57/0.

**Tracer gate:** mode `end-of-phase`, and the `<verify>` is automated only. Task 1's `<verify>` was re-run verbatim (rc 0) before expanding to Task 2.

**Task 2, RED (`8d21cad9`):**
- TAP output: 3 not ok, 57 ok, rc 1, in `evidence/30-26-red-task2.txt`.
- Classifier: `RED_EVIDENCE_OK`, target `sync-cleanup-on-fail`.
- Semantic assessment: the target failed (rc 2, want 1) because `--stage billing-sync-check` was an unknown stage. The six billing arms and the new stub routes ran green.

**Task 2, GREEN (`c9505fab`):** 60 PASS, 0 FAIL, rc 0. The Task 2 `<verify>`, run verbatim: missing=0, fail_lines=0, rc 0.

**Task 2, break arm (`evidence/30-26-task2-arms.txt`):**
- Removed the `PROBE_SHOP_ID` block from `cleanup()`.
- Result: 59/1, with exactly `FAIL sync-cleanup-on-fail the probe shop was not deleted exactly once: POST …/api/v1/shops -> 201|`.
- Restored from the HEAD blob. sha256 `b1804c77…` matches HEAD. Clean runs before and after: 60/0.

**Fail direction of each `<verify>`:** the RED runs, where Task 1 printed `MISSING PASS` for its six arms and Task 2 for its three, plus the two break arms.

**Gate order:** `git log` shows 2 `test(30-26)` and 2 `feat(30-26)` commits. The first GREEN descends from the first RED (`merge-base --is-ancestor`).

## Files Created/Modified

- `scripts/staging-seed.sh`: adds the `billing` and `billing-sync-check` stages, the helpers `read_billing_state`, `billing_dto_fields`, `read_stripe_subscription`, `count_locations`, `delete_probe_shop`, `probe_lookup` and `wait_quantity`, the probe delete in `cleanup()`, and the usage, dry-run, validation and dispatch wiring. In the selftest: nine arms, curl stub routes (billing GET/POST, `POST /v1/payment_methods`, subscriptions and invoices by id, probe shop POST/DELETE with markers), and marker reset in `_st_run`.
- `evidence/30-26-red-task{1,2}.{txt,json}`: the RED reports and classifier records.
- `evidence/30-26-task{1,2}-arms.txt`: the bracketed break arms.
- `evidence/30-26-verify.txt`: both `<verify>` blocks verbatim and the full selftest listing.

## Decisions Made

- **Caller is the staging vendor.** The plan's interface note lists roles ADMIN, TENANT_ADMIN, VENDOR and OPERATOR. The controller on main gates on `ShopAccessService.requireGroupAdmin()` instead, and the vendor holds GROUP_ADMIN, so the vendor is used. No realm-admin operator is needed. This is the "record which" the plan asked for.
- **The DTO's real field names are used:** `billingPlanMode`, `subscriptionStatus`, `stripeSubscriptionId`, `latestStripeInvoiceId`, `monthlyAmountPennies`. The plan's interface note had paraphrased them (`planMode`, `monthlyPennies`).
- **Linking the invoice to its subscription.** The invoice id comes from the subscription's own `latest_invoice`, read by id. The invoice's back-reference is read from `.subscription // .parent.subscription_details.subscription`, since newer Stripe API versions moved that field. When it is present it must name the same subscription.
- **On a fresh subscribe**, the billing API's `latestStripeInvoiceId` must equal Stripe's `latest_invoice`. On a replay, Stripe's value is authoritative, because the tenant's cached id can trail a webhook.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] The probe shop is found by name when the create gives no id**
- **Found during:** Task 2 GREEN
- **Issue:** a 201 without a parseable id, a 5xx or a dropped connection can leave a shop that the EXIT trap does not know about. T-30-102 is exactly that case.
- **Fix:** `probe_lookup` finds the single shop with the probe's unique name and registers it for the EXIT delete. A 4xx skips the lookup, because nothing was written.
- **Files modified:** scripts/staging-seed.sh
- **Committed in:** c9505fab

**2. [Rule 2 - Missing critical] The 3900 is also checked on the Stripe price**
- **Found during:** Task 1 GREEN
- **Issue:** the plan's check (amount_paid == 3900 × q) cannot tell a wrong unit price from a wrong quantity.
- **Fix:** the stage also requires Stripe's `items.data[0].unit_amount` and the DTO's `monthlyAmountPennies` to equal `BILLING_UNIT_PENNIES`. Each mismatch is its own FAIL.
- **Committed in:** cb7213e5

**3. [Rule 3 - Blocking] Field separator**
- **Found during:** Task 1 GREEN
- **Issue:** a tab IFS collapses empty fields. A NONE subscription has an empty `stripeSubscriptionId`, so the later columns would shift. The control, measured: `IFS=$'\t' read a b c <<<$'x\t\tz'` gives b=z.
- **Fix:** jq `join("\u001f")` with `IFS=$'\x1f'`, which keeps the empty field (b empty, c=z).
- **Committed in:** cb7213e5

**4. [Rule 2] dry-run-tools covers the new stages**
- **Issue:** the existing dry-run-tools arm checks every hand-tool stage's `--dry-run`, but listed only the eight older stages.
- **Fix:** the arm now lists 10 stages. This test edit landed in the GREEN commits.
- **Committed in:** cb7213e5, c9505fab

---

**Total deviations:** 4 auto-fixed (3 Rule 2, 1 Rule 3). **Impact:** all four strengthen what the plan asked for. None changes the BILLING or BILLING-SYNC contract.

## Issues Encountered

- One RED arm condition I first wrote would have matched the legitimate pm POST, so billing-ok could never have passed. I caught it on reading before the RED run and replaced it with the "Stripe writes other than the pm creation" count. The RED commit holds only the corrected form.
- **Shellcheck:** one new finding, an unused helper, removed before the GREEN commit. The six remaining findings (SC1091, SC1122, SC2016) were already in `d622dd28`, are out of scope, and CI does not run shellcheck on this script.

## Known Limits (for 30-27)

- **Stubs only.** Neither stage has run against staging. Plan 30-27 runs `--stage billing`, then `--stage billing-sync-check`, then `--stage billing` again on staging after the merge deploys.
- **The published-shops branch has no selftest arm.** The plan named none, and the ruled constant is all-shops, so the branch cannot be reached without editing the constant.
- **Proration.** Each sync-check moves the quantity n → n+1 → n with Stripe's default proration (30-25's ruling), which leaves pending proration items on the staging TEST subscription's next invoice. They roughly net to zero.
- **A later shop change makes the replay FAIL.** The replay's amount check reads the subscription's first invoice. If shops are added after subscribing (outside a sync-check, which restores n), `--stage billing` correctly FAILs `amount_paid != 3900 × quantity`, because the falsifier is that invoice matching that quantity.

## Threat Flags

None. The new calls (`POST /v1/payment_methods` on the platform account; vendor shop POST/DELETE) are inside the plan's trust boundary and covered by T-30-101 to T-30-103. No package was installed (T-30-SC).

## User Setup Required

None.

## Next Phase Readiness

Ready for 30-27: open the PR 7 integration, merge, watch Deploy to Staging, then produce the staging BILLING and BILLING-SYNC lines.

## Self-Check: PASSED

- FOUND: scripts/staging-seed.sh and all seven evidence files named above (`[ -f ]`).
- FOUND: commits b8f54976, cb7213e5, 8d21cad9, c9505fab (`git merge-base --is-ancestor … HEAD`).
- `gsd_run check evaluation-scope --plan 30-26 --commits-only` returned status resolved with 4 commits on this branch.
- Both tasks' `<verify>` blocks ran verbatim at c9505fab with rc 0 (evidence/30-26-verify.txt).

---
*Phase: 30-live-stripe-test-mode-execution*
*Completed: 2026-11-01*
