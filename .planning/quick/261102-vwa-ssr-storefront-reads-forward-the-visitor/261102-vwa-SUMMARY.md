---
phase: quick
plan: 261102-vwa
subsystem: frontend server-side reads (storefront loaders, customer orders), public rate limiting
status: complete
tags: [x-forwarded-for, rate-limiting, ssr, storefront, customer-orders]
requires: [origin/main 6ce700eb]
provides:
  - frontend/lib/forwarded-for.ts (forwardedForHeader(source), forwardedClientHeaders())
  - every server-side core read forwards the visitor's X-Forwarded-For verbatim
affects: [/, /shop, /shop/[slug], basket/checkout/order step titles, /sitemap.xml, /shop/orders, GET+POST /api/customer-orders]
tech-stack:
  added: []
  patterns: [one shared server-only header helper with a pure entry point (route handlers) and a next/headers entry point (loaders)]
key-files:
  created:
    - frontend/lib/forwarded-for.ts
    - frontend/lib/__tests__/forwarded-for.test.ts
    - .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/ (probe, gate, arms, parity, metrics)
    - .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/deferred-items.md
  modified:
    - frontend/lib/storefront-server.ts
    - frontend/lib/customer-orders-server.ts
    - frontend/app/api/customer-orders/route.ts
    - frontend/lib/__tests__/storefront-server.test.ts
    - frontend/app/api/customer-orders/__tests__/route.test.ts
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "X-Forwarded-For is forwarded verbatim from one shared module (frontend/lib/forwarded-for.ts); the frontend never parses or chooses a hop, core's ClientIpResolver alone decides whom to trust"
  - "Route handlers use the pure forwardedForHeader(req.headers); loaders use forwardedClientHeaders() over next/headers, which returns {} and never throws outside a request"
metrics:
  duration: ~15 min wall clock (2026-11-02 23:16Z to 23:32Z)
  completed: 2026-11-02
actuals:
  tokens: 34500   # chars/4: code+docs diff 40,168 chars (~10,000) + evidence 97,816 chars (~24,500)
  tasks: 3
  commits: 4      # MEASURED before the SUMMARY commit: git rev-list --count 6ce700eb..HEAD
plan_head_before: 6ce700eb6c66dd32d96e0f484baba3148e51400c
plan_head_after: fd54e233f168c1354b223087eaa2920bea0e3f78
---

# Phase quick Plan 261102-vwa: Server-side reads forward the visitor's X-Forwarded-For Summary

Every server-side core read the frontend makes (8 storefront loader reads, `loadCustomerOrders`, GET and POST `/api/customer-orders`) now sends core the incoming X-Forwarded-For byte-for-byte through one shared module, `frontend/lib/forwarded-for.ts`, so the public limiter buckets per visitor instead of per frontend pod. POST's output is unchanged and proven so.

## Commits

| Task | Commit | Subject |
|------|--------|---------|
| plan | ea2fc557 | docs(quick-261102-vwa): plan server-side reads forwarding the visitor's X-Forwarded-For |
| 1 (tracer) | fef10a90 | fix(storefront): server-rendered reads forward the visitor's X-Forwarded-For so the public limiter buckets per visitor |
| 2 | b6e0b066 | fix(storefront): customer order reads forward the visitor's X-Forwarded-For through the shared helper |
| 3 | fd54e233 | docs(storefront): metrics and changelog for server-side X-Forwarded-For forwarding |

Each message read back with `git log -1 --format=%B`: no Co-Authored-By, session-link or generated-with lines.

## Verification, both directions (evidence under `evidence/`)

### Jest, RED then GREEN

- **T1-T4** (`jest-red-t1.txt`, base source + new tests): `Tests: 2 failed, 23 passed, 25 total`, jest rc=1. Red: T1 (`Expected: "not-an-ip, 192.0.2.66, 198.51.100.7,203.0.113.9" / Received: null`) and T2 (`["x-forwarded-for"]` expected, `[]` received). T3 and T4 green by nature on base, as the plan predicted. Their fail directions are B2 and B3. All 21 pre-existing tests green under the real next/headers.
- `jest-green-t1.txt`: `Tests: 25 passed, 25 total`, jest rc=0.
- **R1-R4, E1-E2** (`jest-red-t2.txt`, Task 1 commit + new tests): `Tests: 2 failed, 17 passed, 19 total`, jest rc=1. Red: R1 (missing `"X-Forwarded-For"` key) and E1. E1's diff named exactly the `GET /api/customer-orders` and `loadCustomerOrders` entries (`xff: null`, `x-forwarded-for` missing from names). R3, the POST pin, was **green** before the refactor, as required. R2, R4 and E2 were green by nature.
- `jest-green-t2.txt` (3 suites): `Test Suites: 3 passed`, `Tests: 44 passed, 44 total`, jest rc=0.

### Break arms (`break-arms.txt`; clean, then arms, then clean)

`clean-before: jest rc=0 passed=44 failed=0`, with no hash mismatches. Each arm ran once, then its file was restored with `git restore --source=HEAD`, and all four source files matched HEAD by hash after every restore:

| Arm | Edit | Red (expected all present) |
|-----|------|-----|
| B1 | forwardedForHeader returns `{}` | R1, R3, T1, T2, E1 (5/44) |
| B2 | invents `"unknown"` when absent | R2, R4, T3 (3/44) |
| B3 | forwardedClientHeaders loses try/catch | T4, E2, plus 12 pre-existing storefront-server blocks (every loader now defers) (14/44) |
| B4 | left-most hop only | T1, E1, R1, R3 (4/44) |
| B5 | copies every source header | T2, E1, R1, R3 (4/44) |
| B6 | getJson drops NO_STORE | the existing "fetches every storefront call with cache: no-store" block, the featured "uncached" block, T1 (3/44) |
| B7 | loadCustomerOrders token only | E1, whose diff names only `"caller": "loadCustomerOrders"` (1/44) |
| B8 | route GET drops the spread | E1, whose diff names only `"caller": "GET /api/customer-orders"`, and R1 (2/44) |

`clean-after: jest rc=0 passed=44 failed=0`, with no hash mismatches. Arms where an expected test stayed green: **0**. B1, B4 and B5 each turned R3 red, which proves the POST goes through the shared helper. Final hash check, re-run independently after the Task 3 commit: all four source files MATCH `HEAD:<file>` (`forwarded-for.ts 61650e0d…`, `storefront-server.ts 40dffcf0…`, `customer-orders-server.ts cb6d5f4e…`, `route.ts f391cecc…`), and the full Jest run was green.

### Single-copy gate (`single-copy-gate.txt`, `single-copy-gate.sh`)

- At base 6ce700eb (a `git archive` extract): **FAIL, rc=1**. It named `route.ts:136` (`req.headers.get("x-forwarded-for")`) and `route.ts:137`. One comment line was filtered (route.ts:111, the positive control for the filter).
- After Task 2: **PASS, rc=0**. The only code lines are `lib/forwarded-for.ts:53-54`, and 8 comment lines were filtered.

### Runtime probe: built Next server (`next start`, 3310) against a stub core (19190)

Statuses were identical in all three runs: landing, shop, slug, cart, sitemap, orders and api-get 200; api-post 404 (the stub's 404 passed through); none 200. Cleanup printed `ports 3310 and 19190 free` each time, and an independent `ss` check afterwards found nothing listening on either port.

- **Base build** (`probe-before.jsonl`, `probe-before-asserts.txt`): `before` gave rc=0 over 13 upstream calls. No read carried XFF except api-post, which carried `"198.51.100.8"` (the POST already forwarded it). `after` on the same log gave **rc=1, 11 violations**, which proves the after-assertion can fail.
- **Tracer build** (`probe-tracer-asserts.txt`): `after --cases landing,shop,slug,cart,sitemap` gave rc=0 (9 calls). `diff before tracer --cases landing,shop,slug,cart,sitemap,api-post` gave rc=0 (10 pairs). `after --cases orders,api-get` gave rc=1 (2 violations), as expected before Task 2.
- **Final build** (`probe-after-asserts.txt`): `after` over all cases gave rc=0 (13 calls). `diff before after` over all cases gave rc=0 (13 pairs), api-post identical in names and xff. Fail directions: `diff before before` rc=1 (12 violations), and api-post with a tampered xff rc=1.
- Every case's chain arrived exactly as sent, client-written left part included (`192.0.2.66, 198.51.100.1`, `192.0.2.66,198.51.100.3`). The only header added anywhere was `x-forwarded-for`.

### Route-mode parity (`route-parity.txt`)

- `diff routes-before routes-after` gave rc=0 (58 rows each, all `ƒ`).
- `diff prerender-before prerender-after` gave rc=0 (`/_global-error /favicon.ico`).
- Fail arm: a copy with `/shop` flipped from ƒ to ○ gave **rc=1**.
- There are no `○` rows at all, so no static route can import the three modules.
- The extractor's own VOID guard fired on its first run (perl without `-Mutf8` extracted 0 rows: `VOID: fewer than 20 route rows`, rc 2). It was fixed before any recorded extraction.

### Full suite, lint, build

- Full Jest: `Test Suites: 294 passed, 294 total`, `Tests: 3502 passed, 3502 total`, rc=0, run three times. The `--json` run lists landing (37), account-page (17), both seller-block suites (6 and 17), shop-promotions-hidden (5), storefront-server (25), route (17) and forwarded-for (2) as PASSED.
- eslint `--max-warnings 0` on the 7 touched files: rc=0. Fail direction: a probe file with an explicit `any` gave rc=1 (the file was deleted).
- `next build` rc=0 three times (`build-before.txt`, `build-tracer.txt`, `build-after.txt`). That is the type-check.

### Metrics (`metrics.txt`)

- `docs-freshness.sh` check: rc=1. After `--write`: rc=0.
- `check-doc-metrics.sh`: rc=1, with 10 FAILs naming README, CLAUDE.md and AGENTS.md for total, jest_blocks and jest_files. After the prose update: rc=0 (`PASS: all 39 prose metric claim(s)`). `docs-freshness.sh` then gave rc=0 (`total logical invocations: 7081`).
- Delta: jest_blocks 3492 to 3502 (**+10** = T1-T4, R1-R4, E1-E2, as planned); jest_files 293 to 294 (**+1**); total 7071 to 7081.

### Base and scope checks

- `git diff --stat origin/main -- core-java k8s`: empty. Positive control: the same command over `frontend/lib` shows 6 lines.
- `git rev-list --count HEAD..origin/main` after `git fetch`: 0 (not behind base).

## Recorded for ship and operations

- **(a) Production precondition.** Staging takes effect at once: `security.trusted-proxies: "10.244.0.0/16"` (`k8s/staging/configmap-patch.yaml:207`) covers the frontend pods. Compose trusts no proxy, so nothing changes there. Production (`k8s/production/configmap-patch.yaml:108`, `security.trusted-proxies: ""`) takes effect only once the owner's 37-24 step declares a range that covers the **frontend pods** (the peer of these server-side reads) as well as the ingress controller, typically the cluster pod CIDR. The production overlay is deliberately unchanged.
- **(b) Staging post-deploy check (read-only, nothing written):** with kubectl context `jtoye-stg-aks`, list the `rl:public:*` keys in the staging Redis and confirm they name client addresses, not `10.244.x` frontend pod addresses. NOT RUN here (no staging access in this task by instruction).
- **(c) Next's own fill.** For a request with no X-Forwarded-For, Next set `x-forwarded-for` to its own socket peer, and the `none` case observed **`"127.0.0.1"`** on the changed build (null on base, because base forwarded nothing). It is not one of the chains the probe sent, and it is harmless: core reads XFF only from a trusted peer.
- **(d) The POST's output is unchanged.**
  - R3 pins the keys `["Content-Type","X-Customer-Token","Idempotency-Key","X-Forwarded-For"]` in that order, and the values `application/json`, `tok-abc`, `k-1` and the chain. It was green before the refactor and after.
  - B1, B4 and B5 each turn R3 red.
  - The probe diff requires api-post to be identical, in names and xff, between the base and final builds; rc=0.
- **(e) No core change was needed.** `ClientIpResolver` is the only XFF reader in `core-java/src/main/java` (lines 75/99/186, plus a comment in `DsarIntakeRateLimiter`). No diff under core-java or k8s.
- **CHANGELOG:** the heading is `... — 2026-11-02 (#PR-PENDING)`. The PR number is the one remaining edit, made at ship.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug, probe tooling only] assert-probe `diff` had an extra rule for the `none` case**
- **Found during:** Task 2, step 4.
- **Issue:** my first `diff before after` run gave rc=1 on `none`, because I had written a "no header names may change" rule for that case. The plan's rule is after-minus-before = `["x-forwarded-for"]` for every case except api-post. After the change, `none` correctly gains Next's own fill.
- **Fix:** removed the extra branch and re-ran: rc=0. Fail directions re-shown (`diff before before` rc=1). Both runs are recorded in `probe-after-asserts.txt`. No application code was involved.

**2. [Rule 1 - Bug, test harness] E1 routed every call to its 404 fallback on the first RED run**
- **Issue:** `jest.setup.js:154` sets `NEXT_PUBLIC_API_URL=http://localhost:8080/api`, so paths carried an `/api` prefix.
- **Fix:** the test pins `CORE_API_INTERNAL_URL=http://core.test` (restored in afterAll), asserts the origin of every call, and answers a non-core origin with 599. RED was re-run and recorded after the fix.

**3. [Rule 3 - Blocking] the break-arm runner had to run in the project conda env**
- `block-base-python.py` refused `/usr/bin/python3 <script>`. Per the hook, `break-arms.py` ran via `conda run -n jtoye-ops python`.

**4. [Rule 2] the E1 single-page mock is stated in the test**, per checker warning 2: "ONE page: loadAllShopSlugs reads page 0 only".

## Deferred Issues (out of scope, logged in `deferred-items.md`)

- **`/shop` SSR sends a client-reference function as `size`.** Observed in the base probe: `GET /public/shops?page=0&size=function%28%29%7Bthrow+Error%28%22Attempted+to+call+SHOPS_PAGE_SIZE%28%29+from+the+server...`.
  - Cause: `app/shop/page.tsx:10` imports `SHOPS_PAGE_SIZE` from the `"use client"` module `shop-discovery-client.tsx:31`. In the server bundle that import is a client reference, not the number 12.
  - Likely effect: core answers 400, `getJson` defers, and the `/shop` listing is not server-rendered. That is a probable #507/#447 regression for `/shop`.
  - Not verified against a real core. It is pre-existing, and this plan did not touch it.

## NOT RUN

- The staging Redis `rl:public:*` check. It is post-deploy and read-only, and staging access was not allowed in this task.
- Playwright E2E and `check-runtime-freshness.sh`. The compose stack belongs to the main checkout and `docker compose` was not allowed.
- Probe against a real core. The stub records headers only, so core's trust walk was not exercised. That walk is unchanged (no core diff).

## Threat Flags

None. No new endpoint or trust boundary. Exactly one header is added on existing server-to-server reads, as the plan's threat model covers (T-vwa-01..06).

## Known Stubs

None.

## Self-Check: PASSED

- FOUND: `frontend/lib/forwarded-for.ts`, `frontend/lib/__tests__/forwarded-for.test.ts`, `evidence/ssr-xff-probe.sh`, `evidence/routes-after.txt`, `evidence/break-arms.txt`.
- FOUND commits (ancestors of HEAD): ea2fc557, fef10a90, b6e0b066, fd54e233.
