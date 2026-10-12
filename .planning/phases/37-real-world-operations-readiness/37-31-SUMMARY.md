---
phase: 37-real-world-operations-readiness
plan: 31
subsystem: gate
tags: [runtime-parity, compose, abuse-resistance, rate-limit, x-forwarded-for, edge, order-limits, cash-caps, ip-digest, bulk-reject, unverified-contact, playwright, pr-cut, d-03, d-04, uxt-005, uxt-006, uxt-039, uxt-041, uxt-042]

requires:
  - phase: 37-23
    provides: "the sub-theme gate shape (Part 1 static gates and suites, Part 2 RED/rebuild/GREEN/live/goods/screens/branch pointer) and the PR-cut procedure"
  - phase: 37-24
    provides: "ClientIpResolver: X-Forwarded-For read only from a trusted proxy (TRUSTED_PROXY_CIDRS, empty = trust none)"
  - phase: 37-25
    provides: "the edge's per-tenant bucket and problem+json 429 with Retry-After and X-RateLimit-*"
  - phase: 37-26
    provides: "OrderLimits (422 order-limit-exceeded), PublicShopDto.maxLineQuantity, the cross-shop review refusal"
  - phase: 37-27
    provides: "V81, CashOrderCaps per email / per phone (429 order-cap-exceeded with a typed axis), contact_verified"
  - phase: 37-28
    provides: "ClientIpDigester, the per-connection axis, the digest's lifetime and sweep, CLIENT_IP_HMAC_KEY in every runtime"
  - phase: 37-29
    provides: "POST /api/v1/orders/bulk-cancel with per-id outcomes and the kept cancel reason"
  - phase: 37-30
    provides: "RejectOrdersDialog, the Orders/kitchen bulk selection, UnverifiedContactBadge, the checkout cap and limit copy, the stepper limit, R-16"
provides:
  - "D-03/D-04 reconciliation: PR 916 (37-A) MERGED by PR state; origin/main already an ancestor and origin/phase-37-a-kitchen's tree byte-equal to main's, every post-cut review hunk proven present by a reverse git apply --check with a pre-#916 index as the fail arm; no bookkeeping merge commit"
  - "Every static gate rc=0 and the full suites green on the 37-D head (core-java 1753/0 unit + 1127/0 integration after a Rule-1 fixture fix, jest 2672/229, full-program tsc, build, lint, go, vitest 61/8); metrics at V81, 5629 logical invocations"
  - "RED on the pre-rebuild runtime for all five live proofs (4th cash order per email 201, 6th per connection 201, a million units 201, a cross-shop review 201, the edge's untyped 429 starving a second tenant, 760 spoofed hops = 760 buckets and no 429), with the runtime's identity and jar content on record"
  - "Every image rebuilt and recreated; freshness PASS 4/4 (0 unverified; mcp-server's mode-B retag caught); V81 applied live; the 37-D keys and classes read out of the running jar; the OpenAPI gate green"
  - "GREEN for all five live proofs with command, rc and body: 429 order-cap-exceeded axis EMAIL cap 3 / axis IP cap 5; 422 order-limit-exceeded quantity 1000000 limit 50; 400 review-order-shop-mismatch; the edge's problem+json 429 with Retry-After for tenant A while tenant B's sync is 202; one bucket and a 429 at request 721 under 760 rotated X-Forwarded-For values"
  - "The SQL digest check: every stored digest 64 hex, no raw address, one digest per connection, none on a terminal row, none before V81, no audit mirror; the sweep's @Scheduled cron read out of the deployed class"
  - "The plan's five specs 74 passed on the rebuilt stack after the ordering specs were given per-run contacts and a cancel-by-number (e2e/order-cleanup.ts); the ten 37-D goods re-verified live"
  - "Local PR branch phase-37-d-abuse (b69bb8b4), one squash commit on origin/main, tree equal to tag cut/phase-37-d-abuse (127e8d33), upstream unset; not pushed"
affects: [gsd-ship-37-D, 37-32, 37-E, 37-42, phase-37-uat, k8s-staging-rollout, k8s-production-rollout]

actuals:
  tokens: 32156
  tasks: 3
  commits: 8
plan_head_before: 9942de0deef9cdc849a8c66044315915afe0d156
plan_head_after: 9f3cda755872abd6f5fb5aaa73d5fc06b9e8fd06

tech-stack:
  added: []
  patterns:
    - "A live proof is run on the OLD runtime first and its reading recorded as found; the instrument gets a positive control on that runtime (here: the public limiter tripping without the header) so a 0 is a reading, not a dead counter"
    - "An ordering spec owns its hygiene: per-run contact details (email AND phone) and a cancel-by-number through the vendor API, because the caps it runs under count what earlier runs left open"
    - "A public DTO without an id is resolved through the vendor's own list (shop by slug, order by number), never by guessing a field"

key-files:
  created:
    - .planning/phases/37-real-world-operations-readiness/evidence/37-31-gate-37D.md
    - .planning/phases/37-real-world-operations-readiness/evidence/37-31-screens/
    - frontend/e2e/abuse-bounds.spec.ts
    - frontend/e2e/order-cleanup.ts
  modified:
    - frontend/e2e/storefront-flows.spec.ts
    - frontend/e2e/allergen-ack-race.spec.ts
    - frontend/e2e/cash-checkout-no-stripe.spec.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - scripts/gates/object-store-residue-allowlist.conf
    - core-java/src/integrationTest/java (8 dev-profile and ownership test classes: the per-run client-ip-hmac-key fixture)

key-decisions:
  - "37-31 Task 2: proceed — 37-D may take the shared compose stack (owner, 2026-10-12)"
  - "37-31: no bookkeeping merge of origin/phase-37-a-kitchen was recorded: its tree IS origin/main's tree and main is an ancestor of HEAD, so the D-03 intent is proven by content, which is stronger than a parent link"
  - "37-31: the 19 integration failures found by the first full run since 37-23 (ClientIpDigester refusing to start under the dev profile without a key) were fixed in the test fixtures with a per-run random key — never a default key in a profile file (T-37-73)"
  - "37-31: every spec failure on the rebuilt runtime was a cap refusing the specs' own habits (fixed identities accumulated 18 / 8 / 8 open cash orders; nothing was ever cancelled) and was fixed in the specs only — per-run contacts and a cancel-by-number — never by raising a default or touching CashOrderCaps"
  - "37-31: a review needs a COMPLETED order, so two probe orders were completed (with their ledger rows) and stay; the two reviews the proofs created were deleted by SQL, counts equal before and after"
  - "37-31: the orchestrator pushes phase-37-d-abuse and opens the 37-D PR by hand in the ship shape; /gsd-ship cannot open a sub-theme PR mid-phase (verification.status missing), which supersedes the plan's /gsd-ship sentence (the 37-23 precedent)"
  - "37-31: the PR branch carries no upstream (origin/main tracking unset after the cut) so a bare push cannot target main"

patterns-established:
  - "Instrument defects are corrected and the probe re-run before any reading is taken as evidence; the first reading is recorded beside the second (the public confirmation's missing id, the grouped products map, the goods arm run under its own open orders)"

requirements-completed: [RWO-005, RWO-006, RWO-039, RWO-041, RWO-042]

coverage:
  - id: G1
    description: "D-03/D-04 reconciliation before any gate: PR 916 MERGED by PR state; origin/main an ancestor of HEAD; the PR head's tree equal to main's; every post-cut review hunk reverse-applies, with a pre-#916 index as the fail arm"
    verification:
      - kind: other
        ref: "evidence Part 1.0–1.1: gh pr view state MERGED (mergedAt 2026-10-11T20:20:41Z); merge-base --is-ancestor origin/main HEAD rc 0 (and rc 1 the other way); git apply -R --check rc 0 on HEAD, rc 1 on a 7364b215 index"
        status: pass
    human_judgment: false
  - id: G2
    description: "Every static gate green on the 37-D head and the full core-java, jest, build, tsc, lint, go and vitest suites green; the one integration cause found and fixed test-side"
    verification:
      - kind: other
        ref: "evidence 1.2–1.5 and 1.7: docs-freshness RED then --write then GREEN (5629); doc-metrics 37/37 with a reverted-badge fail arm; unit 1753/0; integration 1127/19 (RED, one cause) then 1127/0; jest 2672/229; build rc 0; tsc rc 0; go 5 ok; vitest 61/8; the tracer line rc=0 0 1 0 0 0 0 with the OpenAPI RED recorded"
        status: pass
    human_judgment: false
  - id: G3
    description: "abuse-bounds.spec.ts written with an instrument arm, listed by --list, type-checked; its two vacuous paths (the grouped products map, the id-less confirmation) found at the gate and fixed before any run"
    requirement: "RWO-041"
    verification:
      - kind: e2e
        ref: "evidence 1.3 and 2.10 items 1–2; frontend/e2e/abuse-bounds.spec.ts: --list 6 tests; run 2 on the rebuilt stack 6/6 passed"
        status: pass
    human_judgment: false
  - id: G4
    description: "Owner decision recorded before any stack action; the local .env already carried the HMAC key and verify-env PASSED; nothing appended"
    verification:
      - kind: other
        ref: "Task 2 blocking-human checkpoint answered 'proceed' (2026-10-12); evidence Part 2 preamble: verify-env.sh rc 0, 20 required variables set"
        status: pass
    human_judgment: false
  - id: G5
    description: "Runtime parity: pre-rebuild identity recorded (the 37-A gate's images with none of the 37-D keys or classes in the jar), every image rebuilt and recreated, freshness 0 unverified, image id = tag id, the 37-D keys and classes read out of the running jar, V81 the head, OpenAPI PASS"
    verification:
      - kind: other
        ref: "evidence 2.1, 2.3, 2.4: freshness rc 1 before (3 of 4 image-not-rebuilt) and rc 0 after; jar grep rc 1 → 11 key lines, classes 0 → 7; flyway 80 → 81 with the V81 log line; mode B on mcp-server caught and cleared; OpenAPI rc 1 → 0; maxLineQuantity null → 50"
        status: pass
    human_judgment: false
  - id: G6
    description: "UXT-041: the fourth open cash order per email and the sixth per connection are refused 429 order-cap-exceeded with the typed axis (RED 201/201 on the old runtime)"
    requirement: "RWO-041"
    verification:
      - kind: integration
        ref: "evidence 2.2 A/B (201 ×4, 201 ×6) and 2.6 A/B (429 axis EMAIL cap 3; 429 axis IP cap 5), each with the detail sentence and no Retry-After"
        status: pass
    human_judgment: false
  - id: G7
    description: "UXT-042: a quantity of 1,000,000 through the vendor API is 422 order-limit-exceeded naming the line; the limit (50) passes; the storefront at 51 refuses the same; the public DTO's maxLineQuantity is 50 (RED: 201 DRAFT on the old runtime)"
    requirement: "RWO-042"
    verification:
      - kind: integration
        ref: "evidence 2.2 C and 2.6 C"
        status: pass
    human_judgment: false
  - id: G8
    description: "UXT-005: a COMPLETED order at one shop cannot review another (400 review-order-shop-mismatch); the same review at its own shop is 201; the other review gates stand (RED: 201 stored on Brixton for a Peckham order)"
    requirement: "RWO-042"
    verification:
      - kind: integration
        ref: "evidence 2.2 D, 2.6 D and 2.7 (P2-REG-18 / P2-KYL-P4): already-reviewed, stranger, PENDING each 400 in words"
        status: pass
    human_judgment: false
  - id: G9
    description: "UXT-039: tenant A's flood at the edge is refused 429 problem+json rate-limited with Retry-After and X-RateLimit-* while tenant B's sync passes 202 (RED: untyped 429 for A and B alike)"
    requirement: "RWO-039"
    verification:
      - kind: integration
        ref: "evidence 2.2 E (49 × 202, 11 × untyped 429, B 429) and 2.6 E (24 × 202, 36 × 429 problem+json Retry-After 1, B 202 with its own quota headers)"
        status: pass
    human_judgment: false
  - id: G10
    description: "UXT-006: 760 rotated X-Forwarded-For values from one host end in one bucket and a 429 at request 721 (RED: 760 buckets, no 429); the instrument's positive control on both runtimes"
    requirement: "RWO-006"
    verification:
      - kind: integration
        ref: "evidence 2.2 F (+ control) and 2.6 F: redis rl:public:* key counts 760/760 → 1/0; 429 at 721 both arms on the new runtime"
        status: pass
    human_judgment: false
  - id: G11
    description: "orders.client_ip_digest holds 64-hex values and no raw address on the live database; none on a terminal row, none before V81, no audit mirror; the sweep registered in the deployed class"
    requirement: "RWO-041"
    verification:
      - kind: other
        ref: "evidence 2.8 (SQL, with the raw-IP regex's fail direction on a literal) and 2.4 (the @Scheduled cron in the running jar)"
        status: pass
    human_judgment: false
  - id: G12
    description: "The plan's five specs GREEN on the rebuilt runtime after the spec hygiene fix (run 1: 63/11 — every failure a cap; run 2: 74/0/0); bulk reject with the per-order summary, the badge on a guest order and not on a not-recorded one, the checkout cap copy; proven by content afterwards (every placed order CANCELLED, SUSPECTED_FAKE kept, 0 open cash orders under e2e identities)"
    requirement: "RWO-005"
    verification:
      - kind: e2e
        ref: "evidence 2.5; the plan's second <automated> line rc 0 (74 passed)"
        status: pass
    human_judgment: false
  - id: G13
    description: "The 37-D goods hold on the rebuilt runtime: P2-KYL-P2, P2-KYL-P3, P2-CHA-16, F3, P2-KYL-P5, P2-REG-18, P2-KYL-P4, P2-RAV-20, P2-CHA-12, P2-CHA-13"
    verification:
      - kind: integration
        ref: "evidence 2.7 (the unavailable-product arm not run live — no unavailable product on the seeded menu — and the 20-line basket bounded by the 7-product menu, both recorded)"
        status: pass
    human_judgment: false
  - id: G14
    description: "PR branch phase-37-d-abuse cut fresh from origin/main as one squash commit immediately after check-branch-behind-base passed, tree equal to cut/phase-37-d-abuse, upstream unset, not pushed; origin/main merged back (no-op)"
    verification:
      - kind: other
        ref: "evidence 2.12: behind-base PASS (297 ahead, 0 behind); trees equal (fail direction vs main rc 1); rev-list count 1 = b69bb8b4; the plan's third <automated> line rc 0; message 0 attribution lines; ls-remote 0 heads"
        status: pass
    human_judgment: false
  - id: H1
    description: "Sign off the four default reject reasons (UI-SPEC open item 1): Looks like a fake or test order / We can't make this right now / The customer asked to cancel / Something else"
    requirement: "RWO-005"
    verification: []
    human_judgment: true
    rationale: "The labels are measured (evidence 2.11 item 1, screens orders-bulk-reject-reasons-*); whether they are the right four words for a kitchen is the owner's. workflow.human_verify_mode is end-of-phase, so it is harvested into the phase UAT."
  - id: H2
    description: "Sign off the cap and limit defaults: open cash orders per email 3, per phone 3, per connection 5; line quantity 50, basket units 200, lines 50, basket total £1,000 (RESEARCH A9)"
    requirement: "RWO-041"
    verification: []
    human_judgment: true
    rationale: "Each value is read out of the running jar and refused live (2.4, 2.6); the numbers are a trade the owner makes — the per-connection 5 refused the e2e suite itself and would refuse a shared router the same way (2.10 item 5)."
  - id: H3
    description: "Screen review at 390/768/1280: the selection bar and dialog, the reasons, the summary, the badge, the checkout refusal; the cookie notice over the bar on a phone until Got it; the list's horizontal scroll at 768"
    verification: []
    human_judgment: true
    rationale: "Visual judgement is a person's; 16 JPEGs are in evidence/37-31-screens/ and were looked at (2.11)."
  - id: H4
    description: "The OPEN 1.6 finding: a signed-in customer's order reaches core through the Next proxy, so every signed-in cash order at a shop shares one connection digest while TRUSTED_PROXY_CIDRS is empty; decide before the 37-D PR merges"
    requirement: "RWO-041"
    verification: []
    human_judgment: true
    rationale: "A product decision the owner has not taken (recommended: skip the IP axis for a verified contact; alternative: declare the frontend's range trusted per environment). Not fixed in this plan by instruction; the live per-IP proof used guest orders so it is not masked."

duration: 1h 55m
completed: 2026-10-12
status: complete
---

# Phase 37 Plan 31: Sub-theme 37-D gate — abuse resistance on the delivered runtime and the PR cut Summary

**37-D is proven on the rebuilt compose runtime, with its RED on record first. The 37-A gate's images (none of the 37-D keys or classes in the jar, V80 the head) accepted a fourth cash order per email, a sixth per connection, a million units, a cross-shop review, answered an edge flood with an untyped 429 that starved a second tenant, and gave 760 spoofed X-Forwarded-For hops 760 rate-limit buckets. Every image was rebuilt and recreated (freshness 0 unverified, V81 applied live), and the same probes read 429 `order-cap-exceeded` with the typed axis, 422 `order-limit-exceeded` naming the line, 400 `review-order-shop-mismatch`, a problem+json 429 with `Retry-After` for one tenant while the other's sync passed, and one bucket with a 429 at request 721. Every stored connection digest is 64 hex and none survives a terminal order. The plan's five specs ran 74/74 once the ordering specs stopped behaving like the abuse the caps bound — fixed identities and never a cancel — which the gate found and fixed spec-side. The PR branch `phase-37-d-abuse` is cut as one squash commit (`b69bb8b4`).**

## Performance

- **Duration:** about 1h 55m over two sessions. Task 1 ran from 01:23Z (the 29-minute gradle run and its 27-minute re-run inside it) to the Part 1 commit; Task 3 ran 06:36Z to 07:12Z.
- **Started:** 2026-10-12T01:23:36Z
- **Completed:** 2026-10-12
- **Tasks:** 3 (Task 2 was the owner's decision)
- **Files modified:** 36 by this plan's own commits (20 text files, 16 screenshots), plus this summary

## Accomplishments
- **Reconciliation (D-03, D-04).** PR 916 read MERGED by PR state; origin/main was already an ancestor and the PR head's tree is main's tree byte for byte, so every post-cut review hunk was proven present by a reverse `git apply --check` (fail arm: a pre-#916 index) and no bookkeeping merge was recorded.
- **Static gates and suites (Task 1).** docs-freshness RED → `--write` → GREEN at 5629 with the prose moved; every gate rc 0 with six break arms, except the OpenAPI gate's expected live RED (8 keys absent from the stale service). The first FULL `integrationTest` since 37-23 found 19 failures from one cause — `ClientIpDigester` refusing to start under the dev profile without a key — fixed in eight test fixtures with a per-run random key, then 1127/0. Unit 1753/0, jest 2672/229, build, full-program tsc, lint, go, vitest green.
- **RED, recorded as found (2.1–2.2).** Identity, jar content and all five proofs on the old runtime, with the limiter's positive control so the rotation's "0 × 429" is a reading.
- **Rebuild and parity (2.3–2.4).** gradle/npm ci/go build executed; mcp-server's mode-B retag caught by the gate and cleared; V81 applied with its own log line; the 37-D keys, seven classes and the sweep's `@Scheduled` cron read out of the running jar; OpenAPI, strict scoping, alert-metrics green.
- **GREEN (2.5–2.7).** The five proofs with command, rc and body; the specs 74/74; the ten goods, including server-authoritative pricing with tampered prices, item isolation, a 40-unit basket with consistent totals, tracking and idempotency replay.
- **The SQL digest check (2.8)** with the raw-IP regex's fail direction on a literal.
- **Found at the gate (2.10):** two vacuous paths in the Task 1 spec, the ordering specs' fixed identities and never-cancelled orders (18 / 8 / 8 open cash orders at Mama Ade's under three addresses), the cookie notice over the selection bar on a phone, and my own probe script's id and ordering defects — each fixed and re-run before the reading was used.
- **Screens** at 390/768/1280 (16 JPEGs, looked at) and the "for the owner's eye" list for the phase UAT.
- **PR cut (2.12).** `cut/phase-37-d-abuse` = `127e8d33`; `phase-37-d-abuse` = `b69bb8b4`, one commit above origin/main, trees equal, upstream unset, not pushed; D-04 merge back a no-op.

## Task Commits

1. **Task 1: reconciliation, static gates, full suites, the spec** — `120c723f` (test: the eight dev-profile fixtures, Rule 1), `87b29328` (test: abuse-bounds.spec.ts, metrics at 5629, prose counts, residue pin), `130f81f4` (docs: Part 1 evidence)
2. **Task 2: checkpoint:decision, blocking-human.** The owner answered "proceed" (2026-10-12). No commit.
3. **Task 3: RED, rebuild, GREEN, live proofs, specs, goods, screens, the branch pointer** — `1e34500f` (test: the spec resolves the grouped menu and cancels by number), `75abfcee` (test: ordering specs use per-run contacts and cancel what they place), `127e8d33` (docs: Part 2 evidence, 16 screens), `9f3cda75` (docs: the 2.12 branch pointer, after the cut)
   - PR branch `phase-37-d-abuse` at `b69bb8b4` (a squash of `cut/phase-37-d-abuse` = `127e8d33`; local only, not pushed)

Between Task 1 and Task 3 the orchestrator committed `9ddf7e82` (HANDOFF delta); it is in the squash. `commits: 8` is `git rev-list --count 9942de0d..9f3cda75` at the time this summary was written: the 7 commits above and the orchestrator's one. This summary's own commit follows.

**Plan metadata:** this SUMMARY goes in the docs commit that follows. STATE.md, ROADMAP.md and REQUIREMENTS.md are the orchestrator's.

## Files Created/Modified
- `evidence/37-31-gate-37D.md`: Part 1 (1.0–1.7) and Part 2 (2.1–2.13)
- `evidence/37-31-screens/`: 16 JPEGs at 390, 768 and 1280
- `frontend/e2e/abuse-bounds.spec.ts`: the three blocks (bulk reject with the summary, the badge with its instrument arm, the checkout cap copy)
- `frontend/e2e/order-cleanup.ts`: `uniqueContact`, `vendorToken`, `vendorShopId`, `cancelOrdersByNumber`, `orderNumberIn`
- `frontend/e2e/storefront-flows.spec.ts`, `allergen-ack-race.spec.ts`, `cash-checkout-no-stripe.spec.ts`: per-run contacts; the confirmation's number captured and cancelled in `afterEach`
- `docs/metrics.json`, `CLAUDE.md`, `AGENTS.md`, `README.md`: 5629 logical invocations, schema head V81
- `scripts/gates/object-store-residue-allowlist.conf`: the moved S3 label line renumbered
- eight integration test classes: `jtoye.storefront.client-ip-hmac-key=${random.value}${random.value}` beside the DSAR key

## Decisions Made
See `key-decisions`. The one with consequences beyond this plan: the ordering specs now cancel what they place and never reuse an identity — any new spec that places a cash order should import `order-cleanup.ts` rather than inherit the old habit.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] Eight integration test classes could not boot under the dev profile once 37-28's digester required a key**
- **Found during:** Task 1 (the first full `integrationTest` since 37-23)
- **Issue:** `ClientIpDigester` refuses construction without a 64-hex key; the seven `@ActiveProfiles("dev")` classes and the ownership test boot without one. 19 failures, one cause.
- **Fix:** each class supplies a per-run random key in its `@TestPropertySource` / boot args, the DSAR-key precedent; never a default in a profile file.
- **Files modified:** the eight test classes. **Verification:** 1127/19 → 1127/0. **Commit:** 120c723f

**2. [Rule 3 - Blocking] The residue allowlist pointed at a moved line** — `:390` → `:395`, gate rc 1 → 0. Commit 87b29328.

**3. [Rule 1 - Bug] The Task 1 spec passed vacuously twice** — the grouped products map resolved no product (every block would have skipped), and the id-less confirmation made the cleanup a no-op. Commit 1e34500f (before the rebuild).

**4. [Rule 1 - Bug] The ordering specs were refused by the caps they run under** — fixed identities with 18 / 8 / 8 open cash orders behind them, no cancel ever, the shop id keyed on a field the public DTO does not have, and the cookie notice over the selection bar at 390 px. Spec-side only: `order-cleanup.ts`, per-run contacts, cancel-by-number, "Got it" after sign-in. 63/11 → 74/0. Commit 75abfcee.

**5. [Rule 3 - Blocking] mcp-server's container kept the old image id after a cache-hit retag (mode B)** — `up -d --no-deps --force-recreate mcp-server`; freshness rc 1 → 0. Runtime only, no commit.

### Plan wording that did not hold (recorded, not relabelled)
- **"Append CLIENT_IP_HMAC_KEY to the local .env if absent."** It was present (37-28); nothing appended; verify-env PASS recorded.
- **"20 lines / 40 units."** The seeded menu has 7 products per shop and a line is a product, so the live basket is 7 lines / 40 units with consistent totals; the 20-line arithmetic is the IT's (evidence 2.7).
- **"The PR is opened by the owner via /gsd-ship."** Superseded: the orchestrator pushes and opens the PR by hand in the ship shape (evidence 2.12).
- **"Run the SQL check … the sweep job is registered — read it out of the running log or actuator."** Spring logs the registration only at DEBUG and `scheduledtasks` is not exposed; the `@Scheduled` cron was read out of the deployed class in the running jar instead (evidence 2.4).

### Instrument defects in my own probes (fixed before any reading was used)
- the public confirmation carries no `id` (the first RED run's cancel and transition calls carried an empty one; D and F re-run);
- the goods arm ran under the script's own open orders at Peckham and read the IP cap (re-run with the slot clear).

**Total deviations:** 5 auto-fixed (3 bugs in tests/specs, 2 blocking), 4 plan-wording records, 2 instrument fixes. **Impact:** no product code changed in this plan; the 37-D tree under review is the one 37-24..37-30 built and Task 1 proved.

## Issues Encountered
- `docker compose up -d --wait` exits 1 when the two one-shot containers exit 0 (the 37-23 case); read `compose ps -a`, not the exit code.
- Mama Ade's carries 91 open cash orders from earlier QA, persona and e2e runs under fixed identities (Brixton 22, Peckham 3). Left as found — the junk 37-D's bulk reject exists for, and a vendor's call.

## Known Stubs
None introduced.

## Ledger
No WINDOWS entry added: no stub, skipped test or unrun verify. The open 1.6 finding is a product decision for the owner (H4 and the HANDOFF), not a window.

## Threat Flags
None new. T-37-78 (abuse bounds "proven" on a stale runtime) is mitigated by 2.1–2.5; T-37-SC held — nothing was installed on the host (`frontend/node_modules/next` still 16.3.6; the images ran `npm ci` from the lockfile).

## User Setup Required
None for the dev stack. Staging and production need `CLIENT_IP_HMAC_KEY` as a sealed secret before the 37-D PR deploys (37-28's runbook).

## Next Phase Readiness
- **The orchestrator** pushes `phase-37-d-abuse` (`b69bb8b4`) and opens the 37-D PR by hand in the ship shape; review fixes, merges from main and the CHANGELOG entry citing the PR number land on that branch.
- **D-04 hand-off:** origin/main (`14a2297b`) had nothing new at the cut; the merge into `phase-37-ops-readiness` was a no-op.
- **Before the PR merges, the owner decides the 1.6 finding** (signed-in orders through the Next proxy share one connection digest while `TRUSTED_PROXY_CIDRS` is empty).
- **Phase UAT:** H1 (reasons), H2 (defaults), H3 (screens), H4 (the 1.6 decision).
- **Requirements:** RWO-005, RWO-006, RWO-039, RWO-041 and RWO-042 are ready to mark.

---
*Phase: 37-real-world-operations-readiness*
*Completed: 2026-10-12*

## Self-Check: PASSED

- FOUND: evidence/37-31-gate-37D.md (contains "phase-37-d-abuse", "check-runtime-freshness" and "LastTagTime"), evidence/37-31-screens/ (16 JPEGs), frontend/e2e/abuse-bounds.spec.ts, frontend/e2e/order-cleanup.ts
- FOUND commits (ancestors of HEAD): 120c723f, 87b29328, 130f81f4, 1e34500f, 75abfcee, 127e8d33, 9f3cda75; `git rev-list --count 9942de0d..9f3cda75` = 8
- FOUND: phase-37-d-abuse b69bb8b4, one commit above origin/main, tree equal to cut/phase-37-d-abuse
