---
phase: quick-261103-r4c
plan: 01
subsystem: core-java order + customer read paths, access decision, dashboard customers page
tags: [856, SW-TND-08, gdpr, data-minimisation, staff, rbac, openapi]
status: complete
requires: ["Option B customer access (WINDOWS #29)", "ShopAccessService ladder (VSA-02, 37-05)"]
provides:
  - "ShopAccessService.customerContactScope() + CustomerContactScope record"
  - "CustomerContactMinimiser at every STAFF-reachable OrderService exit"
  - "CustomerService email minimisation with per-page findIdsInShopSet for mixed callers"
  - "StaffCustomerFieldMinimisationIntegrationTest (HTTP-level #856 acceptance)"
  - "OrderStateChangeEventCarriesNoCustomerContactTest (broadcast-shape guard)"
affects: [OrderService, CustomerService, CustomerRepository, ShopAccessService, dashboard customers page, OpenAPI contract]
tech-stack:
  added: []
  patterns: ["decide-once-after-the-gate contact scope", "fail-closed null scope = minimised", "Page.map (never a rebuilt PageImpl) for per-row rewrites"]
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/security/access/CustomerContactScope.java
    - core-java/src/main/java/uk/jtoye/core/order/CustomerContactMinimiser.java
    - core-java/src/test/java/uk/jtoye/core/order/StaffCustomerFieldMinimisationIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderStateChangeEventCarriesNoCustomerContactTest.java
    - frontend/app/dashboard/customers/__tests__/staff-minimised-contact.test.tsx
  modified:
    - core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java
    - core-java/src/main/java/uk/jtoye/core/order/OrderService.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerService.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerController.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDto.java
    - core-java/src/main/java/uk/jtoye/core/order/dto/OrderDetailDto.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderServiceTest.java
    - core-java/src/test/java/uk/jtoye/core/customer/CustomerServiceTest.java
    - frontend/app/dashboard/customers/page.tsx
    - frontend/types/api.ts
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
decisions:
  - "#856: STAFF's view is decided per ROW's shop by ShopAccessService.customerContactScope(), the same ladder require() uses; GROUP_ADMIN/realm admin/tenant-wide SHOP_MANAGER = all shops; per-shop SHOP_MANAGER grants = managed shops; unidentifiable caller or null scope = minimised (fail closed)"
  - "#856: DELIVERY address KEPT for STAFF (STAFF performs the hand-off: all six transitions gated at STAFF, kitchen ticket prints it); only non-DELIVERY orders lose their address"
  - "#856: createOrder, updateOrder, voidOrder and createCustomer are not minimised (already SHOP_MANAGER/GROUP_ADMIN), so stored idempotency bodies and IdempotencyFingerprintGoldenTest are unchanged"
  - "#856: customerEmail is kept-and-nulled, never dropped by JsonInclude; OpenAPI change is description-only (no nullable=true, which oasdiff reports as breaking)"
metrics:
  duration: "~25 min"
  completed: 2026-11-03
  tasks: 3
  files: 21
estimate:
  tokens: 65000
actuals:
  tokens: 18636
  tasks: 3
  commits: 3
plan_head_before: c1a38cd5f2546fbb38fa6692728bcb9b2e28ca04
plan_head_after: 25e4e4e0c1c1a2507c76028297593b94d398227a
---

# Phase quick-261103-r4c Plan 01: STAFF customer field minimisation (#856) Summary

A caller whose best honoured role on an order's or customer's shop is STAFF now receives the
customer's name and phone but no email, and no address on a non-DELIVERY order. This holds on
every order read, the kitchen board, the six transitions, the allergy-note acknowledgement and the
customer reads. The rule is enforced server-side, when the response DTO is built, by
`ShopAccessService.customerContactScope()`, which decides with the same ladder as `require()`.
It was proven RED over HTTP before the fix and GREEN after, and three break arms show the check
can fail.

## Commits

| Task | Commit | Message |
|------|--------|---------|
| 1 | b74e35d1 | test(856): STAFF-signed acceptance IT for customer contact minimisation, plus broadcast guard |
| 2 | a4e3d687 | feat(856): STAFF sees customer name and phone, not email or a collection address |
| 3 | 25e4e4e0 | feat(856): dashboard renders an absent customer email; contract describes it |

No commit carries an attribution trailer. No file was deleted.

## Evidence (all under `evidence/`)

### Task 1: RED on the pre-fix tree (`t1-red.txt`, `t1-guard.txt`)
- `StaffCustomerFieldMinimisationIntegrationTest` on c1a38cd5, before any production change: gradle rc=1, **tests="5" failures="4" errors="0"**. The CONTROL test passed.
  - mixed: `[the STAFF shop's order carries no email]`. The managed-shop email WAS present, and the shop-B order was listed but still carried its email.
  - kitchen: `[STAFF's kitchen board carries no customer email]`, with `customerEmail: delivery-…` in the body.
  - customers: `[per-shop STAFF's customer list carries no email]`.
  - order reads: `[STAFF response #0 carries no customer email]`. GET /orders carried `customerEmail`.
- Guard `OrderStateChangeEventCarriesNoCustomerContactTest`: rc=0, tests="2" failures="0". Its `guard_detectsALeakyEvent` is the permanent break arm. It proves `contactComponents` returns `[customerEmail, customerPhone]` for a leaky record.

### Task 2: GREEN (`t2-green.txt`)
- One Gradle run of the acceptance IT, the eight regression ITs and OpenApiSnapshotTest: **rc=0**. Results:
  - StaffCustomerFieldMinimisation 5/0/0
  - ShopAccessEnforcement 14/0/0
  - CustomerOriginShopScope 5/0/0
  - AllergyNoteAck 14/0/0
  - OrderVoid 25/0/0
  - OrderCreateSubmit 5/0/0
  - OrderBulkCancel 8/0/0
  - IntegrityViolationDiscrimination 4/0/0
  - VanishedRowStatus 9/0/0
  - OpenApiSnapshotTest 1/0/0
  - None was VOID.
- Full `:core-java:test` (cleanTest first): **unit rc=0**, 233 XML files, failing-grep rc=1 (no failing file). Summed counts: tests=2105, failures=0, errors=0, skipped=1. IdempotencyFingerprintGoldenTest is included.

### Task 2: break arms (`t2-break.txt`), each restored by an explicit edit, never `git checkout`
1. `CustomerContactScope.fullContactOn` → `return true`: rc=1, **failures="3"**. The plan expected 4 (see Deviation 1). The customer test stayed green because the customer path never calls `fullContactOn`.
2. `CustomerService.contactMinimiser` scope → `ALL_SHOPS`: rc=1, failures="2". Both the customer test and the mixed test's customer half went red.
3. `ShopAccessService.customerContactScope()` → `ALL_SHOPS` for every caller, the "always-full decision" at the decision point: rc=1, **failures="4" errors="0"**. This matches the plan's expectation.
- Restored: `git diff --quiet -- core-java/src` rc=0, so the tree is byte-identical to a4e3d687. `rg -uu --count-matches 'BREAK ARM' core-java/src` rc=1 (no marker left). The re-run IT gave rc=0, tests="5" failures="0".

### Task 3: frontend, contract, metrics
- Jest RED (`t3-jest-red.txt`) on the pre-fix `page.tsx`: rc=1. `expect(mailIcons(minimised)).toHaveLength(0)` got length 1 (`<svg class="lucide lucide-mail h-3 w-3">`).
- Jest GREEN (`t3-jest-green.txt`) for `app/dashboard/customers`: 3 suites, 7 tests, **jest rc=0**. The control row matched one Mail icon.
- OpenAPI (`t3-openapi.txt`):
  - origin/main's snapshot against the regenerated one, with every `description` stripped: git-show rc=0, **compare rc=0** (description-only). The regenerated diff is +4 lines, all `description`.
  - Break arm: the same comparison against a copy with one `customerEmail` `"type": "string"` changed to integer gives **compare rc=1**.
- Metrics (`t3-metrics.txt`):
  - `docs-freshness.sh --write` rc=0. Changes: java 3205→3212 methods and 442→444 files; jest 3602→3603 blocks and 302→303 files; total 7188→7196.
  - `check-doc-metrics.sh` before the prose edit: **rc=1**, with 16 FAIL lines across README, CLAUDE.md and AGENTS.md.
  - After the edit: **docs-freshness rc=0, doc-metrics rc=0** (39/39 claims PASS).
- `npm run lint` rc=0 (0 errors; 26 warnings, all pre-existing). `npm run build` rc=0 (typecheck included). `git fetch` rc=0. `check-branch-behind-base.sh` **rc=0** (0 behind origin/main c1a38cd5).
- Final re-run on HEAD 25e4e4e0 (`final-rerun.txt`):
  - OpenApiSnapshotTest 1/0/0 and the acceptance IT 5/0/0, IT rc=0.
  - IdempotencyFingerprintGoldenTest 15/0/0 and the guard 2/0/0, unit rc=0.

## Deviations from Plan

1. **[Rule 1, falsifiability] The planned single break arm did not reach the customer path.** Turning `fullContactOn` to always true reddened 3 of 4 tests, not 4. A customer has no single shop (its origin shop, or any shop it ordered at), so `CustomerService` decides through `allShops()` and `managedShopIds()` plus the batch query, never through `fullContactOn`. I recorded that arm as observed, without masking it, and ran two more arms. Arm 2 hit the customer path (failures=2). Arm 3 hit the decision point, `customerContactScope()` (failures=4, as the plan expected). Together they cover every exit. No production change resulted.
2. **[Rule 3] `${out: -1500}` in the plan's Task 3 verify records nothing.** When the output is shorter than 1500 characters, bash returns an empty string for a negative offset. The first RED capture came out empty. The evidence files use `tail -c` instead.
3. **OrderCreateFulfilmentTest was not stubbed.** The plan expected it to need the `customerContactScope()` stub. It passed unchanged in the full suite, so per the plan ("do not stub other mocked-ShopAccessService tests that pass") it was left alone. The tests touched are `OrderServiceTest` and `CustomerServiceTest`, one `lenient()` stub each, beside their existing `isGroupAdmin()` stub. No assertion changed. The suite failures before stubbing were CustomerServiceTest (updateCustomer, getCustomerById, DTO mapping) and OrderServiceTest (DTO mapping).
4. **`customerContactScope()` catches `ShopAccessDeniedException` from `isGroupAdmin()` and returns NONE.** `isGroupAdmin()` throws for an unidentifiable principal, and the plan requires "never throw, fail closed". The catch implements that. In practice the read gate has already run before this call.
5. **`scripts/graph.sh affected OrderMapper/CustomerMapper` (read_first) did not finish.** It started a full `graphify update .` rebuild that was still running after more than 18 minutes. Producers were cross-checked by text instead. Running `rg` for `OrderDto|OrderDetailDto|CustomerDto` over core-java/src/main finds producers only in OrderMapper/OrderService and CustomerMapper/CustomerService. The webhook, refund and shop hits are comments. No new producer was found. This is a lower bound, as with the graph.
6. **`scripts/check-openapi-snapshot-fresh.sh` returned rc=1. This is expected and not claimed.** It compares the snapshot to the RUNNING compose core-java, which was not rebuilt. Its A-1 diff shows the four new #856 descriptions plus staleness that predates this branch: #1111's `POST /public/orders/{n}/lookup`, `OrderLookupRequest`, and the deprecated query-string lookup. So the running image (tagged 2026-11-03 11:52Z) is older than origin/main itself. Runtime parity is the ship step's gate: rebuild every image, run `check-runtime-freshness.sh`, then a live STAFF probe.

## Recorded readings (as the plan asked)

- **Delivery address:** STAFF performs the delivery hand-off. `transitionOrder` gates all six transitions at STAFF, and `kitchen-ticket.tsx` prints the DELIVERY address. So a DELIVERY order keeps its address for STAFF. Only a non-DELIVERY order (COLLECTION, or untyped) loses it. Current write paths never store an address on a COLLECTION order, so the stripping only affects legacy or mis-classified rows. It is still enforced, because the rule must not depend on the data having always been clean.
- **SSE, STOMP and the cook sheet are already compliant.** `OrderStateChangeEvent` carries no contact field, now guarded by a test with its own break arm. Clients refetch `/orders/kitchen`, which is minimised. `CookSheetDto` carries only the customer name and allergy note, which the rule allows.
- **MCP inherits the rule:** `read_orders` forwards the caller's token to the minimised endpoints. An allowlisted machine client stays equivalent to GROUP_ADMIN, by existing design.
- **Webhooks are out of scope (T-856-08).** They are a machine-to-machine export to an endpoint a Group admin configured.
- **Residual T-856-09 (accepted):** a mixed caller can `PUT /customers/{id}` on a customer visible only through a STAFF shop (pre-existing: `requireAnyRole` SHOP_MANAGER on any shop). The edit form now prefills a blank email, which zod refuses, so the UI cannot wipe the email. It passes the admission test: no gate is broken, the system loses no data, and nothing has been observed. Recorded here, not filed.

## Incremental Betterment accounting

- **Displaced deliberately, per the ruling:** STAFF's view of the customer email on orders, the board and customers; and any address on a non-DELIVERY order.
- **Preserved:** the Option B counter lookup; name, phone, items, allergens, notes and the allergy note; the DELIVERY address for the hand-off; and every SHOP_MANAGER-and-above view (proven by the CONTROL test).
- **No page loses a capability other than the email line.** The dashboard landing page, OrderDetailPanel and the kitchen ticket already guarded an absent email or address.

## Cross-cutting quality dimensions

- **Web-perf:** N/A. One conditional line; no layout or bundle change.
- **SEO:** N/A. Authenticated dashboard.
- **Agent-readiness:** MCP inherits the rule. The contract describes the null in descriptions only. No new endpoint. Idempotency is untouched (create paths exempt; golden test 15/0/0).
- **Security:** the threat register is mitigated as planned. T-856-01/02/03 by the acceptance IT; T-856-04 by the fail-closed null scope and break arm 3; T-856-05 by the guard and its break arm; T-856-07 by inheritance. T-856-06/08/09/10 are accepted as planned. No package was installed (T-856-SC).
- **Client-persisted identity:** N/A. Nothing is stored client-side.
- **Falsifiability:** every RED direction and every break arm is recorded with its real output, including the arm that came out at 3 rather than 4.

## Known Stubs

None.

## Threat Flags

None. No new endpoint, auth path or schema change.

## Self-Check: PASSED

- Created files exist: CustomerContactScope.java, CustomerContactMinimiser.java, StaffCustomerFieldMinimisationIntegrationTest.java, OrderStateChangeEventCarriesNoCustomerContactTest.java, staff-minimised-contact.test.tsx.
- Commits b74e35d1, a4e3d687 and 25e4e4e0 are ancestors of HEAD. Measured `git rev-list --count c1a38cd5..HEAD` = 3.
