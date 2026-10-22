---
phase: 30-live-stripe-test-mode-execution
plan: 07
subsystem: staging-ops
tags: [staging, stripe, test-mode, orders, issue-958, d-01, d-02, selftest, tdd, gitleaks]
status: complete

requires:
  - phase: 30-live-stripe-test-mode-execution
    provides: "30-05: the 409 awaiting-payment gate (the answer order-transition reads); 30-06: DRAFT Cancel in the staff UI"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-18: scripts/staging-seed.sh order stage, azure-guard pin, mode-600 Stripe config, assert_test_mode, the stub selftest harness"
provides:
  - "scripts/staging-seed.sh --stage list-drafts: READ-ONLY DRAFT listing (DRAFT-CANDIDATE ... / LIST-DRAFTS count= card= at=), projection-only, no customer field"
  - "scripts/staging-seed.sh --stage vendor-order: one vendor NONE DRAFT at staging-test-kitchen (VENDOR-ORDER order= status=DRAFT payment_status=NONE), the D-01 control for 30-08"
  - "scripts/staging-seed.sh --stage order-transition --orders ORD-x --event submit|confirm|start-preparation: TRANSITION order= event= http= type= detail="
  - "scripts/staging-seed.sh --stage cancel-drafts --orders ORD-a,ORD-b: intent cancelled in Stripe TEST and read back, then the order cancelled and read back (SEED-CANCEL ...; CANCEL-DRAFTS requested= cancelled= refused=)"
  - "scripts/staging-seed.sh --stage stripe-get --path /v1/<object>/<id> [--account acct_...]: allowlisted, TEST-only, sanitised Stripe read for every later evidence step"
  - "Refusal tags [not-draft], [paid], [stripe-path], [intent-mismatch], [intent-status] (plus the existing [stripe-mode])"
affects: [30-08, 30-09, 30-10, 30-32]

actuals:
  tokens: 21424     # chars/4 over git diff fb796fcd..09beb869 (85696 chars); scripts + .gitleaksignore alone 17177
  tasks: 3
  commits: 10       # MEASURED: git rev-list --count fb796fcd..09beb869, before the SUMMARY commit

plan_head_before: fb796fcd9ba856d0cb829a5a99beb2daa5d7dd61
plan_head_after: 09beb8698531e55c7a632740c765b34cd9a02c9b

tech-stack:
  added: []
  patterns:
    - "Per-item errexit subshell in a batch loop: `set +e; ( set -e; cancel_one ... ); rc=$?; set -e` lets refuse/exit end ONE order while the loop counts it and goes on, without the -e-ignored-under-|| trap"
    - "Order fields reach stdout only through a jq projection of named keys; Stripe objects only through walk/del of secret and personal keys"
    - "A header that must not be in argv goes in a per-call mode-600 copy of the curl config (-K), and the stub reads the -K file to prove it"
    - "Stage-specific flags are validated before any guard or call; a flag a stage does not take is a usage error, never ignored"

key-files:
  created:
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-red-task1.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-red-task2.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-red-task3.json
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-task1-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-task2-arms.txt
    - .planning/phases/30-live-stripe-test-mode-execution/evidence/30-07-task3-arms.txt
  modified:
    - scripts/staging-seed.sh
    - .gitleaksignore

key-decisions:
  - "list-drafts 'only GET requests' means only GETs against the API and Stripe: the vendor password grant to the Keycloak token endpoint is a POST and cannot be otherwise. The readonly arm counts every non-GET except that one grant and wants 0"
  - "vendor-order resolves the shop id from the vendor's GET /api/v1/shops, not the public storefront: PublicShopDto carries no id. The product still comes from the public menu (first inStock)"
  - "order-transition exits 0 when the API answered the transition (2xx, 400, 409, 422) and 1 when it did not reach a verdict (000, 401, 403, 404, 5xx); detail is printed only for 4xx/5xx, because a 2xx body is an OrderDto carrying customer fields"
  - "cancel-drafts also refuses an intent whose metadata.order_number is not the named order ([intent-mismatch]) and any intent status it does not know ([intent-status]): the cancel branch is an explicit allow-list (requires_payment_method, requires_confirmation, requires_action), so an empty or new status can never fall into a cancel"
  - "stripe-get: refund and account objects carry no livemode field (measured: stripe-java 34.0.0 Refund.class and Account.class contain no 'livemode', PaymentIntent.class does). The plan's strict assertion would refuse every refund and account read its own allowlist names; those two types print a LIVEMODE n/a line, and ANY other object without the field is still refused [stripe-mode]"
  - "A duplicated or empty entry in --orders is a usage error (a second pass over an order would read CANCELLED and count as a refusal)"

patterns-established:
  - "An arm that passes at RED for the wrong reason is strengthened before the record is taken: transition-event-refused first passed on the unknown --orders flag, so it now requires the --event refusal text"

requirements-completed: []   # PAY-04 is shared with later Phase 30 plans; requirements.ready-ids: 0/1 ready

duration: 19min
completed: 2026-10-22

coverage:
  - id: D1
    description: "list-drafts lists the DRAFTs read-only with no customer field"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/staging-seed.sh --selftest#list-drafts-ok, #list-drafts-readonly, #list-drafts-no-pii (RED rc=2 unknown stage; break: whole object printed reds no-pii with 3 PII lines; break: a cancel POST reds readonly)"
        status: pass
    human_judgment: false
  - id: D2
    description: "vendor-order makes one NONE DRAFT the way the dashboard does, and refuses anything else"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/staging-seed.sh --selftest#vendor-order-ok (body is exactly {shopId, one item}, Idempotency-Key vendor-order-<stamp>), #vendor-order-not-none (paymentStatus PENDING and status PENDING both refused)"
        status: pass
    human_judgment: false
  - id: D3
    description: "order-transition calls exactly the named order and prints the problem type and detail; cancel is not an event"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/staging-seed.sh --selftest#transition-refused (one POST, 409 awaiting-payment line exact), #transition-event-refused (rc 2, 0 calls, event refusal text)"
        status: pass
    human_judgment: false
  - id: D4
    description: "cancel-drafts cancels only the named orders, intent first and read back, never a paid one, and a Stripe failure leaves the order untouched while the loop continues"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/staging-seed.sh --selftest#cancel-one, #cancel-not-draft, #cancel-paid, #cancel-live, #cancel-stripe-fails, #cancel-already-canceled, #cancel-only-listed, #cancel-empty-list (break arms: drop [paid] -> cancel-paid red; vendor cancel before the read-back -> cancel-stripe-fails + cancel-one red; ignore --orders -> cancel-only-listed red, 3 cancels)"
        status: pass
      - kind: other
        ref: "D-02 no-sweeper grep over origin/main...HEAD (k8s, core-java/src/main, infra, .github): added_schedulers=0, git rc 0; here-string control counts 1"
        status: pass
    human_judgment: false
  - id: D5
    description: "stripe-get reads an allowlisted object in TEST mode only and prints no secret or personal field; Stripe-Account only with --account and never in argv"
    requirement: PAY-04
    verification:
      - kind: other
        ref: "scripts/staging-seed.sh --selftest#stripe-get-sanitized, #stripe-get-path-refused, #stripe-get-live, #stripe-get-account-header, #stripe-get-livemode-absent (break: walk/del removed -> sanitized red; break: accept any object without livemode -> livemode-absent red)"
        status: pass
      - kind: other
        ref: "gitleaks detect --source . --log-opts origin/main..HEAD rc 0 (rc 1 with 12 findings before 2a1de3cb); control: scratch sk_test_-shaped file --no-git rc 1"
        status: pass
    human_judgment: false
  - id: D6
    description: "The tools work against live staging and live Stripe TEST"
    requirement: PAY-04
    human_judgment: true
    rationale: "Deliberately not exercised here: this plan builds the tool against stubs only (no live read or write). The first live use is plan 30-08 (order-transition, vendor-order) and plan 30-09 (cancel-drafts, behind the owner's checkpoint:decision)."
---

# Phase 30 Plan 07: Hand tools for the abandoned staging DRAFTs, and a sanitised Stripe read Summary

**`scripts/staging-seed.sh` gains five stages: a read-only DRAFT listing, a vendor NONE DRAFT for the D-01 control, a single printed transition, a cancellation of an explicit owner-confirmed list (each intent cancelled in Stripe TEST and read back before its order is touched), and an allowlisted, sanitised, TEST-only Stripe read. Each new stage keeps the 29-18 guards. The selftest grew from 20 to 41 arms; every new arm was shown RED first, and each break arm reddened its target.**

## Performance

- **Duration:** 19 min
- **Started:** 2026-10-22T20:04:57Z
- **Completed:** 2026-10-22T20:23:35Z
- **Tasks:** 3 of 3
- **Files modified:** 8 (2 code/config, 6 evidence)

## Accomplishments

- **`--stage list-drafts`** pages through `GET /api/v1/orders/status/DRAFT` as the staging vendor. It prints one `DRAFT-CANDIDATE order=… created=… payment_status=… intent=present|none total=…p` per order, then `LIST-DRAFTS count=… card=… at=<ISO>`. The fields pass through a jq projection of five named keys, so no customer name, email, phone, address, note or allergy note can reach the output. The only non-GET call is the vendor's own token grant.
- **`--stage vendor-order`** makes the D-01 control for 30-08. It posts `{shopId, items:[{productId, quantity:1}]}` with Idempotency-Key `vendor-order-<UTC stamp>`, no customer field and `submit` unset. The run fails, naming the field, unless the 201 is a DRAFT with paymentStatus NONE.
- **`--stage order-transition`** finds the named order among DRAFT, PENDING and CONFIRMED and POSTs exactly one transition. It prints `TRANSITION … http=… type=… detail=…`. `--event cancel` (or any event other than submit, confirm or start-preparation) is a usage error before any guard or call.
- **`--stage cancel-drafts --orders ORD-a,ORD-b`** (D-02) handles each named order in its own errexit subshell, in this order:
  1. Refused `[not-draft]` unless it is a DRAFT with paymentStatus PENDING or FAILED and a `pi_` reference.
  2. The intent is read with `livemode false` asserted and its `metadata.order_number` matched.
  3. Refused `[paid]` when the intent is succeeded, processing or requires_capture.
  4. The intent is cancelled with `cancellation_reason=abandoned` and Idempotency-Key `d02-cancel-<pi>`, unless it is already canceled.
  5. The intent is read back canceled. Only then is the order cancelled through the vendor API and read back CANCELLED.
  6. The order ends on `SEED-CANCEL …`.

  The run ends on `CANCEL-DRAFTS requested= cancelled= refused=` and exits 1 when anything was refused. A Stripe failure leaves its order untouched, and the next named order is still processed. No scheduled job exists anywhere.
- **`--stage stripe-get --path … [--account acct_…]`**:
  - The path is matched against an allowlist of 11 evidence objects before anything runs, otherwise `REFUSED [stripe-path]`.
  - It makes one GET with the TEST key.
  - `--account` adds `Stripe-Account` in a per-call mode-600 copy of the curl config, never in argv.
  - The object must read livemode false.
  - It is printed with client_secret, secret, billing_details, receipt_email, email, name, phone, address and shipping removed at any depth.
- **`read_stripe_key`** is extracted from `order_stage` (same guard, same `sk_test_` refusal, same config file) and shared. The USAGE block documents all five stages and the four new flags, and each stage has a `--dry-run` plan.

## Task Commits

1. **Task 1 (tracer): list-drafts, vendor-order, order-transition**
   - RED `e63d6a1e`
   - GREEN `8b104808`
   - Break-arm evidence `fb5e31d0`
   - Tracer gate (end-of-phase mode, automated-only verify): re-run on the committed tree, rc 0, so expansion followed.
2. **Task 2: cancel-drafts**
   - RED `351e84b8`
   - GREEN `44859bff`
   - Break arms and scheduler-grep control `a77e77ab`
3. **Task 3: stripe-get**
   - RED `95d59b34`
   - GREEN `5cfc96b8`
   - Fixture and fingerprint fix `2a1de3cb`
   - Evidence `09beb869`

## TDD Gate Compliance

| Task | RED commit | RED verdict (`gsd_run check tdd-red-evidence`) | GREEN |
|------|-----------|-----------------------------------------------|-------|
| 1 | e63d6a1e | RED_EVIDENCE_OK / target_test_failed: list-drafts-ok rc=2 "--stage must be vendor, order or all"; 27 tests, 20 pass, 7 fail | 8b104808 |
| 2 | 351e84b8 | RED_EVIDENCE_OK: cancel-one rc=2 (unknown stage); 35 tests, 27 pass, 8 fail | 44859bff |
| 3 | 95d59b34 | RED_EVIDENCE_OK: stripe-get-sanitized rc=2 "unknown flag --path"; 41 tests, 35 pass, 6 fail | 5cfc96b8 |

**Semantic assessment.** Every new arm ran and failed on its own first planned assertion, and every pre-existing arm stayed ok, so the harness ran without fault. There were no fixture, syntax or zero-test faults.

- `transition-event-refused` first passed at RED for the wrong reason: the unknown `--orders` flag produced its own usage error. Before the record was taken, the arm was strengthened to require the `--event` refusal text.
- `dry-run-tools` failed only on `stripe-get`, the stage Task 3 adds.

## Falsification (both directions; real output in the evidence files)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| list-drafts-no-pii | whole order object printed: FAIL, 3 PII lines | 0 PII lines over 3 candidates (the arm also refuses a vacuous 0-line pass) |
| list-drafts-readonly | a cancel POST added: FAIL, names `POST …/orders/x/cancel` | non-GET calls 0 besides the token grant |
| cancel-paid | [paid] branch dropped: FAIL "succeeded: rc=0 (want 1)" | succeeded, processing, requires_capture all refused, 0 cancels |
| cancel-stripe-fails / cancel-one | vendor cancel moved before the Stripe cancel: FAIL "the order whose intent did not cancel was cancelled anyway", "out of order: … order_cancel=5" | D001 0 vendor cancels, D002 cancelled, summary 2/1/1; order stripe_cancel < read-back < order cancel < read-back |
| cancel-only-listed | --orders ignored: FAIL "vendor cancel calls=3 (want exactly 1)" | 1 cancel, 0 calls touching unnamed orders or their intents |
| stripe-get-sanitized | walk/del removed: FAIL, client_secret and receipt_email printed | object printed (pi_sg1, ch_sg1, order number), 0 secrets, 0 PII |
| stripe-get-livemode-absent | accept any object without livemode: FAIL "intent without livemode: rc=0" | refund printed with LIVEMODE n/a; intent without livemode refused |
| D-02 no-sweeper grep | here-string `+  @Scheduled(fixedDelay = 1)` counts 1 | origin/main...HEAD counts 0 (git rc 0, 179 diff lines, so the range is non-empty) |
| gitleaks (branch range) | first run rc 1, 12 findings; control (scratch sk_test_-shaped file, --no-git) rc 1 | rc 0 after 2a1de3cb |
| Path allowlist | /v1/customers, /v1/balance_transactions, `../` traversal, `$(id)` in a query, a full URL, /v2 all refused | events, payment_intents lists, webhook_endpoints, `created[gte]` queries, balance, accounts, refunds allowed |

Every break arm was bracketed: clean run, arm, restore by `cp`, a blob-hash check of the working file against `HEAD:scripts/staging-seed.sh`, `git diff --quiet`, then a clean run again. All 7 restores matched by hash and all closing runs were 41/41 (27/27 or 35/35 at the time).

## Files Created/Modified

- `scripts/staging-seed.sh`: +859/-16.
  - Five stages, `vendor_login`, `orders_in_status`, `find_order_id`, `cancel_one` and `read_stripe_key`.
  - Optional extra-header and alternate-config support in `api` and `stripe_api`.
  - Flag validation, the USAGE block and the dry-run plans.
  - 21 new selftest arms.
  - New stub routes: order lists, detail, cancel, transitions, `POST /api/v1/orders`, Stripe cancel, per-intent fixtures, a generic Stripe GET, and the `-K` Stripe-Account read.
- `.gitleaksignore`: three commit-pinned fingerprints.
- `evidence/30-07-red-task{1,2,3}.json` and `evidence/30-07-task{1,2,3}-arms.txt`.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] stripe-get's strict livemode assertion would refuse refunds and accounts**
- **Found during:** Task 3 design.
- **Issue:** The plan says stripe-get "asserts livemode false" on objects from an allowlist that includes `refunds` and `accounts`. Stripe defines neither with a livemode field. This was measured on the project's own stripe-java 34.0.0: `Refund.class` and `Account.class` have 0 occurrences of `livemode`, while `PaymentIntent.class` and the other allowlisted classes have 4. Every refund or account read would therefore be refused.
- **Fix:** Exactly the two field-less types (refund, account) print a `LIVEMODE n/a object=…` line. The `sk_test_` key is what makes them TEST. Any other object without the field is refused.
- **Verification:** The new `stripe-get-livemode-absent` arm covers both directions, and its break arm (accept any object without livemode) reds it.
- **Commit:** 5cfc96b8.

**2. [Rule 3 - Blocking] gitleaks over the branch range was rc 1: one finding was this plan's, eleven came from plan 30-05**
- **Found during:** Task 3 acceptance.
- **Issue:**
  - 95d59b34 (this plan's RED commit) held a literal stub `client_secret` in the stripe-get fixture.
  - Commits 4bd017f0 and 48577794 (plan 30-05's RED evidence) embed verbatim JUnit XML. Its log lines carry the tests' synthetic Idempotency-Keys `key=30-05-<uuid>`, which the generic rule reads as API keys.
  - None of the commits is pushed. The plan's own criterion needs rc 0, and the PR's gitleaks job would go red on the same findings.
- **Fix:**
  - The fixture's secret is now composed at run time (the 29-18 rule).
  - Three commit-pinned fingerprints, each with a written reason, were added to `.gitleaksignore` (a file this plan lists). The evidence files themselves were not edited, because they are recorded output.
- **Verification:** rc 1 with 12 findings before, rc 0 after. The control is a run-time-composed `sk_test_` file scanned `--no-git`: rc 1. The first reading of the first scan printed `rc=0`, but that was `tail`'s status through a pipe. The scan was re-run with a report file and the rc captured on the same line.
- **Commit:** 2a1de3cb.

**3. [Rule 1 - Bug, in a test] Two of this plan's own arm expectations were wrong**
- **Found during:** Task 1 RED and Task 3 GREEN.
- **Issue:**
  - `transition-event-refused` passed at RED on an unrelated usage error.
  - `stripe-get-sanitized` expected the vendor token grant beside the Stripe GET, but stripe-get makes no grant.
- **Fix:**
  - The event arm requires the `--event` refusal text. It was amended before the RED record.
  - The sanitized arm wants exactly one Stripe GET and no write. It was fixed inside 5cfc96b8 and noted in that commit message.

**4. [Rule 3 - Blocking] `mv` is not in the selftest's tool allowlist**
- **Found during:** Task 1 GREEN (rc 127).
- **Fix:** `orders_in_status` uses `cp` and `rm` (both in the allowlist) rather than adding a tool.
- **Commit:** 8b104808.

**Also added beyond the plan's arms:**
- `stripe-get-livemode-absent` and `dry-run-tools`, which runs the plans of all five new stages: guards run, 0 az, 0 curl. The selftest now has 41 arms, against the plan's 39.
- cancel-not-draft covers a vendor NONE DRAFT and an unlisted number as well as PENDING. cancel-paid covers processing and requires_capture. cancel-empty-list covers empty and malformed lists. cancel-stripe-fails proves that the loop continues past the failure.
- The `[intent-mismatch]` and `[intent-status]` refusals (Rule 2).
- Duplicate `--orders` entries are refused.

**Total deviations:** 4 auto-fixed (2 Rule 1, 2 Rule 3), plus Rule 2 hardening noted above.
**Impact:** No scope change. Each one keeps a claim provable or a gate green.

## Interpretations recorded for the verifier

- **"Only GET requests leave the script" (list-drafts).** The password grant to the Keycloak token endpoint is necessarily a POST. The readonly arm allows exactly that one non-GET and no other. The API and Stripe see GETs only.
- **"Resolve staging-test-kitchen's id … from the public storefront read" (vendor-order).** `PublicShopDto` has no `id` field (read 2026-10-22), so the id comes from the vendor's `GET /api/v1/shops` (the vendor stage's own lookup). The product id still comes from the public menu.

## Issues Encountered

- `block-base-python` refuses the base interpreter, so edits were applied with `/usr/bin/python3` (machine tooling) and RED records were built with `jq`. No conda env was needed: the plan installs nothing.
- `ctx7` is not installed. The Stripe livemode question was answered from the project's own stripe-java jar instead (class-file grep, with PaymentIntent as the positive control).

## Known Stubs

None. The stub data lives only inside `selftest()`, and every stage calls the real API.

## Threat Flags

None beyond the plan's register.

- T-30-26: explicit `--orders` only, plus the `[not-draft]` and `[paid]` refusals before any write.
- T-30-27: projection and walk/del.
- T-30-28: the `sk_test_` refusal and `assert_test_mode` on every intent read.
- T-30-29: the intent is canceled and read back before the order cancel.
- T-30-30: the key and the Stripe-Account header stay in mode-600 config files inside WORKDIR, removed by the EXIT trap.

There is no new endpoint, auth path or schema change.

## Staging safety

No live staging or Stripe call of any kind was made: no read and no mutation. The 30-03 unpaid DRAFT ORD-1AAC0CDE-20261022-0C094A2C and the swarm DRAFTs remain for plan 30-09, which runs `list-drafts` and then `cancel-drafts`, behind the owner's checkpoint.

## Next Phase Readiness

Ready for 30-08, which runs the live #958 arms with `order-transition` and the `vendor-order` control. Nothing is pushed.

## Self-Check: PASSED

- FOUND: scripts/staging-seed.sh, .gitleaksignore, and the 6 evidence files (checked with `[ -f ]`).
- FOUND (ancestors of HEAD): e63d6a1e, 8b104808, fb5e31d0, 351e84b8, 44859bff, a77e77ab, 95d59b34, 5cfc96b8, 2a1de3cb, 09beb869.
- Plan verification re-run at 09beb869:
  - selftest rc 0, 41 PASS, 0 FAIL.
  - Task 1, 2 and 3 verify blocks rc 0.
  - Scheduler grep 0, with its control 1.
  - gitleaks branch range rc 0, with its control rc 1.
