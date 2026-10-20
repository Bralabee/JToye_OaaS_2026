---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 18
subsystem: payments
tags: [stripe, test-mode, webhook, payment-intent, seed, staging, idempotency, live, tdd, d-28, d-31]
status: complete

requires:
  - phase: 29-14
    provides: "the published staging shop staging-test-kitchen with Pelau orderable (--stage vendor), scripts/deploy.sh at the 6e95ee6c images"
  - phase: 29-13
    provides: "stripe-credentials--api-key (sk_test_) and --webhook-secret in jtoye-stg-kv; the preflight; scripts/staging-secrets.sh --leak-scan"
  - phase: 29-22
    provides: "Mailpit with STARTTLS required, where the order's guest and vendor mail lands"
  - phase: 29-06
    provides: "scripts/staging-seed.sh guards (staging_kube_guard, api-base, prod profile) and its selftest harness"
provides:
  - "scripts/staging-seed.sh --stage order: one storefront guest order paid by a Stripe TEST PaymentIntent confirmed with pm_card_visa, settled by the webhook, ending on one SEED-ORDER verdict line"
  - "scripts/staging-seed.sh --stage all = vendor, operator deleted, then order; --list-kv-objects now includes stripe-credentials--api-key"
  - "selftest 20 arms (9 new order arms), each new arm shown red by a planted defect"
  - "Staging rows: order ORD-1AAC0CDE-20261019-4D3B23A2 (PENDING / CAPTURED), one processed_stripe_events row evt_3USHS1DpS9ICNeRt19ESTYVa, one SALE ledger row"
  - "D-28 closed for Phase 29: signed webhook 200 + 1 row; unsigned and forged 400 + 0 rows, the query not blinded"
affects: [29-19, 29-23, 29-24, 29-26, 29-27, 30]

actuals:
  tokens: 16259
  tasks: 2
  commits: 4
plan_head_before: 2515c6d80a4afa0e2c9f85090622e5b2f02233ca
plan_head_after: 4ad07f09e40a9ede7e3ec263a8140757164ccbbb

tech-stack:
  added: []
  patterns:
    - "A Stripe intent id taken from the client secret's pi_ prefix in memory (the secret never printed), and on a replay found by order_number metadata"
    - "assert_test_mode on every Stripe object or list read: anything but livemode false is REFUSED [stripe-mode]"
    - "Helpers that may refuse are called directly and return through globals, never inside $(...), where a refusal would only exit the subshell"
    - "Key-shaped selftest fixtures composed at run time so no secret scanner sees a literal"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-18-stripe.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-18-t1-red-order-stage.json
  modified:
    - scripts/staging-seed.sh
    - .gitleaksignore

key-decisions:
  - "The order stage reads the Stripe key from Key Vault (the plan's design, and the object core-java itself mounts). The owner's ruling named the repo .env STRIPE_SECRET_KEY: the two were compared in memory and are byte-identical (control: a mutated copy is not), so both rulings hold. The executor's own read-backs used the .env key."
  - "No dish was published: the public menu already offers Pelau (950p, mask 0, in stock), and the stage needs only one available product."
  - "COLLECTION is chosen when the shop offers it, so no address and no delivery fee are invented; DELIVERY with the shop's fee is the fallback."
  - "--stage all deletes the vendor stage's realm-admin operator before placing the order, rather than leaving it standing until the exit trap."
  - "The RED commit's two synthetic Stripe-shaped stub answers are pinned in .gitleaksignore by commit:file:rule:line (the 38-18 precedent), because that commit cannot be rewritten in this lane. The live file composes them at run time."

patterns-established:
  - "A live money-path proof reports its event id from Stripe (GET /v1/events filtered by data.object.id), so the ledger query can name it"
  - "Webhook negative arms: HTTP code and curl rc on one statement; crc!=0 or 000 is VOID, never a refusal"

requirements-completed: [DPLY-01]

coverage:
  - id: D1
    description: "--stage order places one TEST-paid storefront guest order (Idempotency-Key, shown prices, allergen acknowledgement, pm_card_visa) that settles to paymentStatus CAPTURED, ending on the SEED-ORDER verdict line"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "out=$(... bash scripts/staging-seed.sh --context jtoye-stg-aks --stage order 2>&1); rc=$? -> rc=0, last line SEED-ORDER order=ORD-1AAC0CDE-20261019-4D3B23A2 intent=pi_3USHS1DpS9ICNeRt1nB7iIZm status=PENDING payment_status=CAPTURED livemode=false at=2026-10-19T14:22:14Z (evidence/29-18-stripe.txt)"
        status: pass
      - kind: unit
        ref: "bash scripts/staging-seed.sh --selftest -> 20/20 PASS, rc=0; RED 10/20 (RED_EVIDENCE_OK, order-first-run); 9 break arms"
        status: pass
    human_judgment: false
  - id: D2
    description: "A re-run with the same key replays the original order: same order number and intent, no second confirm, exactly one intent for the order, one order row for the key"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "run 2 of --stage order: rc=0, 'Idempotent replay' logged, SKIP already succeeded, exactly 1 TEST PaymentIntent names the order; jtoye_backup: orders_for_seed_key=1"
        status: pass
    human_judgment: false
  - id: D3
    description: "The signed payment_intent.succeeded webhook is verified and recorded (200, one processed_stripe_events row); unsigned and forged POSTs return 400 and leave no row; the same query finds the positive control"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-18-stripe.txt Task 2: pending_webhooks 0 + 'Received Stripe event' log + row processed_at 14:22:11.942Z; unsigned 400 / forged 400 / probe 400; one jtoye_backup session, one prepared statement: 1/0/0/0"
        status: pass
      - kind: other
        ref: "plan verify: curl unsigned evt_probe_unsigned -> code=400 curl_rc=0 verify_rc=0"
        status: pass
    human_judgment: false
  - id: D4
    description: "No live-mode key or live object exists: the key is sk_test_ (refused otherwise), every Stripe object read is livemode false, and no key or client secret appears in output, argv or evidence"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "preflight test-mode yes x2; Stripe read-back: 1 intent and 4 events, all livemode false; regex scan 0 (control 2); staging-secrets.sh --leak-scan 0 found (control 1 LEAK); gitleaks rc=0 (control rc=1)"
        status: pass
    human_judgment: false

duration: 15min
completed: 2026-10-19
---

# Phase 29 Plan 18: Stripe TEST seed order and webhook arms Summary

**`staging-seed.sh --stage order` places one storefront guest order on staging and pays it with a Stripe TEST PaymentIntent confirmed by `pm_card_visa`; the signed `payment_intent.succeeded` webhook settled it to CAPTURED in 2 s. Unsigned and forged webhooks are refused with 400 and leave no ledger row.**

## Performance

- **Duration:** about 15 min
- **Started:** 2026-10-19T14:14:36Z
- **Completed:** 2026-10-19T14:29:13Z
- **Tasks:** 2
- **Files modified:** 4

## Accomplishments

- **One real TEST-paid order on staging.** It is `ORD-1AAC0CDE-20261019-4D3B23A2`: 1 x Pelau at 950p, COLLECTION, allergen acknowledgement 0. Its intent is `pi_3USHS1DpS9ICNeRt1nB7iIZm` (livemode false, 950 gbp, a plain platform charge). The order is PENDING with paymentStatus CAPTURED, paid by "visa ending 4242", and has one SALE ledger row.
- **The 29-13 open item is closed.** Event `evt_3USHS1DpS9ICNeRt19ESTYVa` reached the endive-pinned endpoint, and `pending_webhooks` is 0. PaymentService deserialised the intent. The worry that `getObject()` would come back empty on an API-version mismatch did not materialise.
- **Replay is proven live.** The same Idempotency-Key returned the same order and the same intent. Nothing was confirmed again, exactly one intent names the order, and there is one order row for the key.
- **The D-28 webhook arms on the live edge:**
  - signed: 200, one row
  - unsigned: 400 (Missing Required Header), no row
  - forged (an HMAC under a random wrong secret): 400 (Invalid Stripe signature), no row
  - the plan's probe: 400, no row

  All four ids were queried in one `jtoye_backup` (BYPASSRLS) session with one prepared statement, and that statement found the positive control.

## Task Commits

1. **Task 1 RED: failing order-stage arms.** `9e13b945` (test). TAP 10/20 failing; RED_EVIDENCE_OK on `order-first-run`.
2. **Task 1 GREEN: `--stage order` and `--stage all`.** `b8a471aa` (feat). Selftest 20/20.
3. **Task 1 live: the order, the replay, the evidence, and the scanner fix.** `d8224b6f` (feat).
4. **Task 2: the webhook's three arms.** `4ad07f09` (docs).

## TDD Gate Compliance

- **RED:** `STAGING_SEED_SELFTEST_FORMAT=tap bash scripts/staging-seed.sh --selftest` exited 1.
  - Ten arms failed: `list-kv-objects` (4 names, want 5) and the nine new order arms. Each order arm failed with `rc=2 ... VOID: --stage order is added by plan 29-18`.
  - The ten pre-existing arms passed against the extended stubs, so the stub changes regressed nothing.
  - The classifier returned RED_EVIDENCE_OK (`evidence/29-18-t1-red-order-stage.json`).
  - Semantic assessment: the target arm ran and failed on its planned assertion (the stage rc) because the feature was absent. It was not a harness fault.
- **GREEN:** 20/20 after two RED-arm assertions were corrected. Neither was weakened:
  - `order-live-key` had demanded zero az calls. A key's prefix can only be seen after the key is read, so the arm now requires exactly one Key Vault read (of the Stripe object) and zero curl calls.
  - The `order-replay` fixture showed a paid order's intent as `requires_payment_method`, which a paid order never shows. The fixture now reads `succeeded`.
- **Break arms:** each was bracketed clean -> arm -> clean, and every restore was verified by blob hash against HEAD. Each target arm went red:
  - livemode check removed: `order-livemode-true`
  - client secret printed: `order-first-run`
  - stock ignored: `order-first-run`
  - a succeeded intent re-confirmed: `order-replay`
  - operator left standing: `stage-all`
  - intent count `>=1`: `order-two-intents`
  - cash order accepted: `order-cash-refused`
  - both key guards opened: `order-live-key`

  Breaking only one key guard did not red it, because the second guard (the `^sk_test_[A-Za-z0-9]+$` regex) still refused. They are two independent guards.
- **REFACTOR:** none.

## Files Created/Modified

- `scripts/staging-seed.sh`
  - `--stage order` and `--stage all`, with the order dry-run plan.
  - Helpers: `azure_guard_once`, `pub_api`, `stripe_api`, `assert_test_mode`, `intents_for`.
  - The Stripe Key Vault object is now in `--list-kv-objects`.
  - Nine selftest arms, plus stub routes for the storefront and Stripe, request-body capture and a confirm marker.
- `.gitleaksignore`: two commit-pinned fingerprints for the RED commit's synthetic fixtures.
- `evidence/29-18-stripe.txt`: the precondition, the key-source comparison, the Stripe baseline, TDD and the break arms, both live runs, the verify's fail direction, the independent read-back, the leak scan with controls, and the Task 2 arms and ledger.
- `evidence/29-18-t1-red-order-stage.json`: the RED record.

## Decisions Made

See `key-decisions` in the frontmatter. The one that matters for the owner is the key source. The script reads Key Vault, but the owner named the repo `.env`. I compared the two in memory and they are byte-identical, so the ruling holds either way. No dish was published, because Pelau was already orderable.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] gitleaks would refuse the push**
- **Found during:** the Task 1 leak scan.
- **Issue:** `stripe-access-token` matched the RED commit's two synthetic stub answers (`sk_test_`/`sk_live_`-shaped, lines 924/925 of 9e13b945). The rule allows no rewrite of that commit.
- **Fix:** the live file composes the stub values at run time, and `.gitleaksignore` pins exactly those two findings.
- **Verification:**
  - gitleaks over `2515c6d8..HEAD` passes (rc 0).
  - With the fingerprints removed, it reports 2 leaks (rc 1).
  - The same shape in another file still fires (rc 1).
- **Committed in:** d8224b6f.

**2. [Rule 1 - Bug] My scratch query helper left a relay pod running (not committed code)**
- **Found during:** Task 2.
- **Issue:** under `set -e`, the EXIT trap's `wait` on the killed port-forward returned 143 and stopped the trap before it deleted the pod. The run reported rc 143.
- **Fix:** I deleted the pod by name and added `set +e` in the trap, as `staging-db-bootstrap.sh` does with `|| true`. The session was re-run and ended rc 0 with 0 relay pods left. Both sessions returned identical rows.
- **Files modified:** none in the repo (the helper is a scratchpad script).

---

**Total deviations:** 2 auto-fixed (1 blocking, 1 bug).
**Impact on plan:** none on scope. The first keeps the push gate green; the second was tooling hygiene.

## Issues Encountered

- **shellcheck was not run.** It is not installed on this host, and no CI workflow runs it.
- **The webhook's 200 is inferred, not read from an access log.** core-java logs no access line. The evidence is Stripe's `pending_webhooks` 0 (Stripe received a 2xx) together with the controller's contract: 200 on processed, 400 on every refusal.

## Known limitations (not stubs)

- **A re-run can stop replaying if the menu changes.** The stage orders the first available dish, so the request body depends on the menu. If a vendor publishes a dish that sorts before Pelau, or reprices it, a re-run's body changes and the Idempotency-Key answers 422 (payload mismatch) instead of replaying. Re-seeding after such a change needs a new key suffix (`-v2`).
- **One minute a day refuses orders.** The shop's 00:00-23:59 schedule leaves 23:59-00:00 closed, and a run in that minute is refused before the replay check.

## User Setup Required

None.

## Next Phase Readiness

- **Ready for Phase 30 without deploy work:** PAY-03's money path (connected account, destination or direct charges, capture, payout, refund). Nothing here claims PAY-03; this intent was a plain platform charge.
- **For 29-26:** staging now holds a paid order to show testers. Its mail is in Mailpit.

## Self-Check: PASSED

- FOUND: `scripts/staging-seed.sh`, `.gitleaksignore`, `evidence/29-18-stripe.txt`, `evidence/29-18-t1-red-order-stage.json`.
- FOUND (ancestors of HEAD): 9e13b945, b8a471aa, d8224b6f, 4ad07f09.
- The verdicts were re-read from the evidence file:
  - `SEED-ORDER ... payment_status=CAPTURED livemode=false`
  - ledger 1/0/0/0
  - leak scans 0, each with its control red

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-19*
