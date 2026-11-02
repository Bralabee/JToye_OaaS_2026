---
phase: quick
plan: 261102-vwa
type: execute
wave: 1
depends_on: []
files_modified:
  - frontend/lib/forwarded-for.ts
  - frontend/lib/storefront-server.ts
  - frontend/lib/customer-orders-server.ts
  - frontend/app/api/customer-orders/route.ts
  - frontend/lib/__tests__/forwarded-for.test.ts
  - frontend/lib/__tests__/storefront-server.test.ts
  - frontend/app/api/customer-orders/__tests__/route.test.ts
  - docs/metrics.json
  - CLAUDE.md
  - AGENTS.md
  - README.md
  - docs/CHANGELOG.md
  - .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/
autonomous: true
requirements: [QUICK-261102-vwa]

estimate:
  tokens: 85000
  raw_tokens: 170000
  tasks: 3
  confidence: high

must_haves:
  truths:
    - "Every server-side core read the frontend makes sends core the X-Forwarded-For value the frontend received, byte-for-byte. That covers the storefront-server loaders behind /, /shop, /shop/[slug], the basket/checkout/order step titles and /sitemap.xml; loadCustomerOrders behind /shop/orders; and GET and POST /api/customer-orders"
    - "Each read keeps exactly the headers it sent before (X-Customer-Token, Idempotency-Key, Content-Type, as applicable) plus X-Forwarded-For, and nothing else: no cookie, authorization, x-nonce, x-real-ip or forwarded"
    - "One shared module, frontend/lib/forwarded-for.ts, is the only place that reads and names the header. No caller carries its own copy, including the POST that already forwarded it"
    - "POST /api/customer-orders sends exactly what it sent before: same key, same verbatim value, only when present. It now goes through the shared helper"
    - "The frontend never computes, trims, parses or picks an address: a request without X-Forwarded-For makes reads without one, and the client-written left part of a chain passes through untouched for core's right-to-left walk to ignore"
    - "Outside a request scope (the real next/headers throws) the loaders and loadCustomerOrders behave exactly as before: no throw, no header, the same result"
    - "Every storefront read still carries cache: no-store and redirect: manual, and every customer-orders read still carries cache: no-store"
    - "No route changes rendering mode: the build route table (symbol per route) and the prerender-manifest route keys are identical before and after"
    - "core-java, every k8s overlay and the production security.trusted-proxies value are unchanged; ClientIpResolver alone decides whom to trust"
  artifacts:
    - path: "frontend/lib/forwarded-for.ts"
      provides: "forwardedForHeader(source), pure, for route handlers that hold the request; forwardedClientHeaders(), async over next/headers and never throwing, for loaders and server components"
      contains: "x-forwarded-for"
    - path: "frontend/lib/storefront-server.ts"
      provides: "getJson merges forwardedClientHeaders() beside NO_STORE"
      contains: "forwardedClientHeaders"
    - path: "frontend/lib/customer-orders-server.ts"
      provides: "loadCustomerOrders sends X-Customer-Token plus forwardedClientHeaders()"
      contains: "forwardedClientHeaders"
    - path: "frontend/app/api/customer-orders/route.ts"
      provides: "GET and POST both send forwardedForHeader(req.headers); POST's output unchanged"
      contains: "forwardedForHeader"
    - path: "frontend/lib/__tests__/forwarded-for.test.ts"
      provides: "E1: every server-side core read carries the chain (exactly 11 fetch calls across all eight callers)"
      contains: "261102-vwa"
    - path: ".planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/ssr-xff-probe.sh"
      provides: "the built Next server driven against a stub core that records what each upstream read carried"
    - path: ".planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/routes-after.txt"
      provides: "route table after the change, byte-identical to routes-before.txt"
    - path: "docs/metrics.json"
      provides: "jest_blocks raised by exactly the number of new it() blocks, jest_files by one"
      contains: "jest_blocks"
  key_links:
    - from: "frontend/lib/storefront-server.ts getJson and frontend/lib/customer-orders-server.ts loadCustomerOrders"
      to: "frontend/lib/forwarded-for.ts forwardedClientHeaders -> next/headers headers()"
      via: "awaited for each fetch; any throw or rejection becomes no header"
      pattern: "forwardedClientHeaders\\(\\)"
    - from: "frontend/app/api/customer-orders/route.ts GET and POST"
      to: "frontend/lib/forwarded-for.ts forwardedForHeader"
      via: "the request object already in hand, so the output holds when the handler is invoked outside Next's request scope"
      pattern: "forwardedForHeader\\(req\\.headers\\)"
    - from: "every server-side fetch init"
      to: "core-java ClientIpResolver.resolve"
      via: "X-Forwarded-For, read only when the socket peer is inside jtoye.security.trusted-proxies, walked right to left to the first untrusted hop"
      pattern: "X-Forwarded-For"
---

<objective>
Server-side storefront reads send core the visitor's X-Forwarded-For chain verbatim, so the public rate limiter (`rl:public:{ip}`, `rate-limiting.public.*`, 30/min + burst 10 by default) gives each visitor its own bucket. Today every SSR read is keyed on the frontend pod. The two code reviews (#1104 r1, #1105 r1) recorded the effect: the landing makes two server-side public reads per render (the listing and the featured list), so about 15 landing views a minute across ALL visitors empties the bucket, the listing gets a 429 and the kitchen row disappears. /shop and /shop/[slug] share the same bucket, and they already did before this change.

Two more server-side reads share that bucket in the same way, and the coordinator brought them into scope on 2026-11-02: `loadCustomerOrders` (`frontend/lib/customer-orders-server.ts`, behind /shop/orders) and the GET in `frontend/app/api/customer-orders/route.ts`. Both read `/public/orders/mine` without the header. The POST in the same route already forwards it, at lines 136-137. That is the precedent; its output must not change.

Purpose: one shared server-only module, `frontend/lib/forwarded-for.ts`, with two entry points and no second copy anywhere.
- `forwardedForHeader(source)`, a pure function, for callers that hold the request (the route's GET and POST pass `req.headers`).
- `forwardedClientHeaders()`, async over `next/headers`, for callers that do not (`getJson`, the single chokepoint every storefront loader goes through, and `loadCustomerOrders`).

Both forward exactly one header, X-Forwarded-For, copied byte-for-byte. Deciding whom to trust stays with core's `ClientIpResolver`: it reads XFF only when the socket peer is in `jtoye.security.trusted-proxies`, walks the chain right to left and stops at the first untrusted hop. That is why the frontend must never compute or choose an address (#782 is open on exactly that class).

Output: the shared module, its three callers and their tests (RED, then GREEN, then break arms); the POST's existing behaviour pinned by a test before it is refactored; the route table before and after; a probe of the built server against a stub core (base build vs changed build); docblock and CHANGELOG notes; and updated metrics prose.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@CLAUDE.md
@.claude/skills/proof-standards/SKILL.md
@frontend/lib/storefront-server.ts
@frontend/lib/__tests__/storefront-server.test.ts
@frontend/lib/customer-orders-server.ts
@frontend/app/api/customer-orders/route.ts
@frontend/app/api/customer-orders/__tests__/route.test.ts
@core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java

Facts measured while planning (2026-11-02, worktree at 6ce700eb). Re-measure any of them you rely on.
- Every exported loader (`loadShopList`, `loadFeaturedShops`, `loadShopDetail` = 4 reads, `loadPublicShop`, `loadAllShopSlugs`) reaches the network ONLY through `getJson`, line 102. `getOptional` calls `getJson`.
- Callers: `app/page.tsx` (landing: listing + featured), `app/shop/page.tsx`, `app/shop/[slug]/page.tsx` (page + generateMetadata, deduplicated by `react.cache`), `lib/storefront-step-title.ts`, which serves the generateMetadata of `app/shop/[slug]/{cart,checkout,orders/[orderNumber]}/layout.tsx`, and `app/sitemap.ts`. No `"use client"` file imports the module: `near-you-row.tsx` and `types/storefront.ts` only mention it in comments. A client import of `next/headers` would fail `next build`, which is the backstop.
- `app/layout.tsx:19` is `export const dynamic = "force-dynamic"` (#89, the CSP nonce), and `app/sitemap.ts` and `app/robots.ts` are force-dynamic too. So no page that calls a loader is prerendered, and the static/dynamic risk comes out at zero by construction. The before/after route table is still the evidence.
- In Next 16, `headers()` is async. Outside a request it throws "`headers` was called outside a request scope" (`next/dist/server/app-render/work-unit-async-storage.external.js:88`). Under Jest that is the REAL behaviour: `app/__tests__/landing.test.tsx:69-84` documents it. `app/shop/__tests__/shop-promotions-hidden.test.tsx` calls the real `loadShopDetail` with NO next/headers mock, so that suite exercises the degrade path for free.
- When a request arrives without X-Forwarded-For, Next's base server fills it with its own socket peer: `req.headers['x-forwarded-for'] ??= …socket.remoteAddress`, `next/dist/server/base-server.js:567` (16.4.2). The value is Next's, not ours, and it is harmless: core reads XFF only from a trusted peer. Record what the probe sees. Do not fight it.
- Staging: `CORE_API_INTERNAL_URL` = `http://core-java:9090` (ClusterIP), so core's socket peer for SSR reads is a frontend pod inside `security.trusted-proxies: "10.244.0.0/16"` (`k8s/staging/configmap-patch.yaml:207`). Traefik appends the real client as the right-most hop. The frontend NetworkPolicy admits ingress only from the ingress namespace (`k8s/base/networkpolicies/10-frontend.yaml`). Compose (`TRUSTED_PROXY_CIDRS` empty) and production (`security.trusted-proxies: ""`) trust no one, so XFF is inert there and behaviour does not change.
- `ClientIpResolver` is the only reader of X-Forwarded-For in `core-java/src/main/java`. The positive control matched its lines 75/118/186. No core change is needed. If you find one is, STOP and report; do not make it.
- Ports 3000 and 9090 are the LIVE compose stack of the main checkout. Never bind them, never stop it, never run `docker compose` from this worktree. The probe uses 3310 (Next) and 19190 (stub core).
- The worktree has no `frontend/node_modules`: run `npm ci` in `frontend/`. Do not symlink the main checkout's copy.
- Gates are green at base: `scripts/docs-freshness.sh` rc 0 (total 7071) and `scripts/check-doc-metrics.sh` rc 0 (39 claims). `docs/metrics.json` reads jest_blocks 3492, jest_files 293.
- Customer-orders reads. `loadCustomerOrders(access)` sends `{ "X-Customer-Token": accessToken }` with `cache: "no-store"` and has no unit test. Its only caller is `app/shop/orders/page.tsx:68`, a server component that has already read `cookies()` (ACCESS_COOKIE = `jtoye-customer-access`, `lib/customer-auth-cookies.ts:16`). The route's GET sends `{ "X-Customer-Token": access }`. Its POST builds a plain-object `headers` (Content-Type, then X-Customer-Token if present, then Idempotency-Key if present, then X-Forwarded-For if `req.headers.get("x-forwarded-for")` is truthy).
- `app/api/customer-orders/__tests__/route.test.ts` (`@jest-environment node`) calls GET and POST DIRECTLY with a `NextRequest`, outside Next's request scope, and reads `init.headers["X-Customer-Token"]` as a plain object. Two consequences. First, the route must read the header from `req.headers` (the pure entry point); `headers()` from next/headers would throw there and silently drop it. Second, the route's headers stay a plain object. The file has NO test of the POST's X-Forwarded-For forwarding: the only case-insensitive "forwarded" match is the comment at line 9, while `X-Customer-Token` matches 8 times as a positive control. So the POST's current output is unpinned and must be pinned before the refactor.
- A shared module, not an export from storefront-server: `storefront-server.ts:2` imports `coreBaseUrl` from `customer-orders-server.ts`, so the reverse import would create a cycle, and the route would pull in the storefront module for one function.
</context>

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1 (tracer): the shared forwarded-for module and every storefront-server read forwarding the visitor's X-Forwarded-For verbatim, proven first in Jest (RED, then GREEN) and then on the built Next server against a stub core</name>
  <files>frontend/lib/forwarded-for.ts, frontend/lib/storefront-server.ts, frontend/lib/__tests__/storefront-server.test.ts, .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/stub-core.mjs, .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/assert-probe.mjs, .planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/ssr-xff-probe.sh</files>
  <precondition>The worktree /home/sanmi/IdeaProjects/JToye_OaaS_2026-ssr-xff is on fix/ssr-forward-client-address at 6ce700eb or later, the tree is clean, and nothing is listening on 127.0.0.1:3310 or 127.0.0.1:19190 (`ss -ltn`). Stop if any of these is unmet.</precondition>
  <behavior>
    New describe block in `frontend/lib/__tests__/storefront-server.test.ts`: "forwards the visitor's X-Forwarded-For chain verbatim (quick 261102-vwa)". next/headers is mocked with a jest.fn whose implementation is reset in beforeEach to delegate to `jest.requireActual("next/headers").headers`. That makes the REAL out-of-scope behaviour the default for every test in the file, existing ones included. Use plain `it(` blocks, so `scripts/count-test-blocks.mjs` and Jest agree.
    - T1 verbatim: the request carries `x-forwarded-for: not-an-ip, 192.0.2.66, 198.51.100.7,203.0.113.9` (spacing mixed on purpose). After `loadShopList()`, `new Headers(init.headers).get("x-forwarded-for")` is exactly that string, and in the same init `cache` is "no-store" and `redirect` is "manual".
    - T2 only XFF: the request also carries cookie, authorization, x-real-ip, forwarded, x-nonce and host. The header names on the fetch init are exactly `["x-forwarded-for"]`.
    - T3 absent: the request carries only cookie and x-nonce. The fetch is still made once, and its init has no x-forwarded-for.
    - T4 outside a request scope: a positive control first. `jest.requireActual("next/headers").headers()`, called inside `Promise.resolve().then(...)`, rejects with /outside a request scope/. Then, with the default (real) implementation, `loadShopList()` resolves `state: "ok"`, the fetch was made, and it carries no x-forwarded-for. Then again with a mock that returns a rejected promise. Same result.
    Expected on the BASE source: T1 and T2 are RED (no headers on init). T3 and T4 are GREEN by nature, because base never sends a header. Their fail directions are break arms B2 and B3 in Task 3. Record that honestly; do not present it as RED. The cross-caller "every server-side read" assertion is E1, in Task 2, because it needs the customer-orders callers in the same harness.
  </behavior>
  <action>
    Implements the original scope and the shared-module half of the coordinator's amendment. Order matters: capture the baseline before ANY source edit. Save every output under `.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence/` (abbreviated EV below). Capture each rc on the same statement as its command (`out=$(cmd 2>&1); rc=$?`), never after an echo or a pipe.

    1. Baseline build. In `frontend/`, run `npm ci`, then `npm run build` with CI's env: `NEXT_PUBLIC_API_URL=http://127.0.0.1:19190`, `NEXTAUTH_URL=http://127.0.0.1:3310`, `NEXTAUTH_SECRET=ci-placeholder-not-a-real-secret`, `NEXT_TELEMETRY_DISABLED=1`. Send all output to EV/build-before.txt and record the rc. Pull the route rows out of the table: every line where a rendering symbol (○ ƒ ● ◐) precedes a `/path`. Strip the box-drawing characters and any trailing size columns to get `symbol path`, sort, and write EV/routes-before.txt. VOID (stop, exit 2) if fewer than 20 rows: an empty extraction is not "no routes". Write the sorted keys of `.routes` in `frontend/.next/prerender-manifest.json` (read with node, not jq) to EV/prerender-before.txt. VOID if that file is missing.

    2. Probe tooling (EV only; never in the app source tree). It covers EVERY case from the start, so one base run serves all three tasks.
       - `stub-core.mjs`: a node http server on 127.0.0.1:<port>. It appends one JSON line per request to a log file: method, url, `xff` (the x-forwarded-for value or null), and `names` (the sorted header names). Answers: `/__mark/<case>` gets 204 and logs a mark line; `/public/featured-shops` gets 200 `[]`; `/public/shops?…` and `/public/orders/mine…` get 200 `{"content":[],"totalElements":0,"totalPages":0,"number":0,"size":0}`; everything else gets 404 `{"title":"Not Found"}`.
       - `ssr-xff-probe.sh <label>`, run from the repo root: exit 2 if 3310 or 19190 is already listening. Start the stub, then `npx next start -p 3310 -H 127.0.0.1` from `frontend/` (CI's recipe) with `CORE_API_INTERNAL_URL=http://127.0.0.1:19190` plus the build env, each under `setsid`. A trap on EXIT kills both process groups and then asserts both ports are free. Wait for `/shop` to answer 200, with a 90 s deadline (a curl status loop, no pgrep). For each case, hit `/__mark/<case>` on the stub, then send the request and record its status. The cases (case, request, chain sent):
         - `landing`: GET `/`, `192.0.2.66, 198.51.100.1`.
         - `shop`: GET `/shop`, `198.51.100.2`.
         - `slug`: GET `/shop/probe-kitchen`, `192.0.2.66,198.51.100.3`.
         - `cart`: GET `/shop/probe-kitchen/cart`, `198.51.100.4`.
         - `sitemap`: GET `/sitemap.xml`, `198.51.100.5`.
         - `orders`: GET `/shop/orders` with cookie `jtoye-customer-access=probe-token`, `198.51.100.6`.
         - `api-get`: GET `/api/customer-orders?size=5` with that cookie, `198.51.100.7`.
         - `api-post`: POST `/api/customer-orders?shop=probe-kitchen` with that cookie, `Content-Type: application/json`, `Idempotency-Key: probe-1` and body `{}`, `198.51.100.8`.
         - `none`: GET `/shop` with no XFF header.
         Copy the log to EV/probe-<label>.jsonl.
       - `assert-probe.mjs`: segments the log by mark. It requires minimum upstream call counts per case: landing ≥2 including `/public/featured-shops`; shop ≥1; slug ≥4; cart ≥1; sitemap ≥1; orders ≥1 and api-get ≥1 on `/public/orders/mine`; api-post ≥1 POST `/public/shops/probe-kitchen/orders`. A shortfall is VOID, exit 2, because it means the probe exercised nothing. Forms:
         - `<log> before`: every upstream call has `xff` null EXCEPT api-post, which carries its chain byte-for-byte, because the POST forwarded before this change. If it does not, stop: the baseline is not what this plan measured.
         - `<log> after [--cases a,b,…]`: every upstream call of each named case (default: all) carries that case's chain exactly. The `none` case carries none of the chains the probe sent, and the value it does carry (Next's own fill) is printed.
         - `diff <before.jsonl> <after.jsonl> [--cases …]`: pairs calls by case, method and url. After names minus before names must equal exactly `["x-forwarded-for"]`, EXCEPT api-post, where the name sets and the xff value must be identical (the runtime proof that the POST's output did not change).
         Exit 1 on any violation and print the offending line.

    3. Runtime fail arm, on the BASE build: run `ssr-xff-probe.sh before`. `assert-probe.mjs EV/probe-before.jsonl before` must exit 0, and `assert-probe.mjs EV/probe-before.jsonl after` on the SAME log must exit 1. That proves the after-assertion can fail. Record both rc values in EV/probe-before-asserts.txt.

    4. RED: add the next/headers mock and T1-T4 to `frontend/lib/__tests__/storefront-server.test.ts`. Run `npx jest lib/__tests__/storefront-server.test.ts --ci` and save the output to EV/jest-red-t1.txt. Expect T1 and T2 failing, T3 and T4 passing, and every pre-existing test passing (they now run with the real next/headers, which proves the degrade path does not disturb them). Any other pattern means the test is wrong: fix the test, not the expectation.

    5. GREEN, part one.
       Create `frontend/lib/forwarded-for.ts`, SERVER ONLY by the same convention and note as `customer-orders-server.ts`. It has two exports.
       - `forwardedForHeader(source)`: source is anything with `get(name)` (a Headers, a ReadonlyHeaders, `req.headers`), or null or undefined. If `source?.get("x-forwarded-for")` is non-empty it returns `{ "X-Forwarded-For": <that value unchanged> }`; otherwise `{}`. It is synchronous and pure. No trim, split, validation or choice of hop.
       - `forwardedClientHeaders()`: async. Inside a try it returns `forwardedForHeader(await headers())`, with `headers` from next/headers. The catch returns `{}` and does not log: the address is personal data, and the out-of-scope throw is expected. No `unstable_rethrow`; the scope is never-throw, and every caller is force-dynamic.
       The module docblock explains:
       - Why it exists: the public bucket is keyed per client, and the SSR socket peer is the frontend pod (the #1104/#1105 reviews).
       - That it forwards ONE header verbatim, and trust stays with core's ClientIpResolver (trusted-proxies peer only, walked right to left; #782).
       - Why there are two entry points: route handlers hold the request, and their Jest suite invokes them outside Next's request scope, where `headers()` throws. Loaders and server components hold no request object.
       - That outside a request scope it sends no header and never throws.
       - That Next fills x-forwarded-for with its own socket peer when a request has none.
       - That production takes effect only once `security.trusted-proxies` covers the frontend pods (the SSR peer) as well as the ingress controller.
       In `frontend/lib/storefront-server.ts`, import `forwardedClientHeaders` from `@/lib/forwarded-for`. Have `getJson` build its init as NO_STORE spread plus `headers: await forwardedClientHeaders()`, so cache, redirect and the 301/404/defer logic stay exactly as they are. Add one sentence to its docblock pointing to the shared module.
       Run the file again and save the output to EV/jest-green-t1.txt: all pass.

    6. Tracer end-to-end: rebuild with the same env into EV/build-tracer.txt (rc 0). Run `ssr-xff-probe.sh tracer`. `assert-probe.mjs EV/probe-tracer.jsonl after --cases landing,shop,slug,cart,sitemap` must exit 0, and so must `assert-probe.mjs diff EV/probe-before.jsonl EV/probe-tracer.jsonl --cases landing,shop,slug,cart,sitemap,api-post`. Record the rc values in EV/probe-tracer-asserts.txt. The orders and api-get cases are expected to still carry nothing here; that is Task 2.

    7. Commit the module, storefront-server, the test and EV with the subject `fix(storefront): server-rendered reads forward the visitor's X-Forwarded-For so the public limiter buckets per visitor`. No Co-Authored-By, session-link or generated-with lines (owner ruling 2026-08-30; `block-git-commit.sh` refuses them). Pass the message through a quoted heredoc or `-F`, then read it back with `git log -1 --format=%B`.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-ssr-xff/frontend && out=$(npx jest lib/__tests__/storefront-server.test.ts --ci 2>&1); rc=$?; printf '%s\n' "$out" | tail -6; echo "jest rc=$rc"; cd .. && EV=.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence && node $EV/assert-probe.mjs $EV/probe-tracer.jsonl after --cases landing,shop,slug,cart,sitemap; a=$?; node $EV/assert-probe.mjs $EV/probe-before.jsonl after; f=$?; echo "tracer-after=$a before-as-after(must be 1)=$f"</automated>
    Pass: jest rc=0, tracer-after=0, before-as-after=1. Fail looks like: jest rc=1 naming T1/T2 (as in EV/jest-red-t1.txt), tracer-after=1 printing an upstream line with xff null, or before-as-after=0 (the assertion cannot fail, so the probe is VOID). The fail direction is executed in step 3 (runtime) and step 4 (Jest).
  </verify>
  <done>On the base build, every storefront upstream read of /, /shop, /shop/probe-kitchen, its basket title and /sitemap.xml reaches the stub core with no X-Forwarded-For; on the tracer build each carries exactly the chain the request sent, client-written left part included, and the only header added is x-forwarded-for. T1-T4 pass, with T1 and T2 shown RED first. The shared module exists. Committed.</done>
</task>

<task type="auto" tdd="true">
  <name>Task 2: loadCustomerOrders and both /api/customer-orders handlers forward through the same helper; POST's output pinned before it is refactored; one assertion over every server-side core read</name>
  <files>frontend/lib/customer-orders-server.ts, frontend/app/api/customer-orders/route.ts, frontend/app/api/customer-orders/__tests__/route.test.ts, frontend/lib/__tests__/forwarded-for.test.ts</files>
  <behavior>
    In `frontend/app/api/customer-orders/__tests__/route.test.ts` (existing node environment, existing `makeRequest`/`postRequest` helpers extended with optional headers). Use plain `it(` blocks. `CHAIN` is `not-an-ip, 192.0.2.66, 198.51.100.7,203.0.113.9`.
    - R1, GET forwards it beside the token and nothing else: the request carries the access cookie, `x-forwarded-for: CHAIN`, x-real-ip and authorization. `init.headers` equals exactly `{ "X-Customer-Token": "tok-abc", "X-Forwarded-For": CHAIN }`, and `init.cache` is "no-store". RED at Task 1's commit.
    - R2, GET with no X-Forwarded-For: `init.headers` equals exactly `{ "X-Customer-Token": "tok-abc" }`. GREEN by nature; its fail arm is B2.
    - R3, POST behaviour pinned: the request carries the cookie, `idempotency-key: k-1`, CHAIN and x-real-ip. `Object.keys(init.headers)` equals `["Content-Type", "X-Customer-Token", "Idempotency-Key", "X-Forwarded-For"]` in that order, and the values are `application/json`, `tok-abc`, `k-1` and CHAIN. Deliberately GREEN at Task 1's commit and after the refactor: it is the proof that the POST's output did not change. Its fail directions are B1, B4 and B5, which must redden it once POST goes through the helper (if they do not, POST still has its own copy).
    - R4, POST as a guest with no X-Forwarded-For: `init.headers` equals exactly `{ "Content-Type": "application/json" }`. GREEN by nature; fail arm B2.
    New file `frontend/lib/__tests__/forwarded-for.test.ts`, with `@jest-environment node`. next/headers is mocked with a jest.fn that delegates to `jest.requireActual` by default (reset in beforeEach). The file must NOT import `@/lib/forwarded-for`, so on any tree where a caller lacks the helper it fails on its assertions, not on a missing module.
    - E1, every server-side core read carries the visitor's chain verbatim and keeps its own headers. The headers mock resolves `new Headers({ "x-forwarded-for": CHAIN, cookie: "a=b", "x-nonce": "n" })`. The test calls:
      - loadShopList, loadFeaturedShops, loadShopDetail("probe-kitchen"), loadPublicShop("probe-kitchen") and loadAllShopSlugs;
      - `loadCustomerOrders("tok")`;
      - `GET(new NextRequest(…/api/customer-orders?size=5, cookie + CHAIN + x-real-ip))`;
      - `POST(new NextRequest(…?shop=probe-kitchen, POST, content-type, cookie, idempotency-key, CHAIN, x-real-ip, body "{}"))`.
      The fetch mock routes by URL to sensible bodies and sees EXACTLY 11 calls (1+1+4+1+1+1+1+1). Build one list of `{ method, path, xff, names }` per call, sorted by method and path, and compare it with `toEqual` against the expected list, so a failure names the caller:
      - xff is CHAIN for every call;
      - names are `["x-forwarded-for"]` for the 8 storefront reads;
      - names are `["x-customer-token","x-forwarded-for"]` for the two `/public/orders/mine` reads;
      - names are `["content-type","idempotency-key","x-customer-token","x-forwarded-for"]` for the POST.
      RED at Task 1's commit, and the diff must name exactly the loadCustomerOrders and GET entries.
    - E2, outside a request scope (the real next/headers): `loadCustomerOrders("tok")` resolves (never rejects), its single fetch is made with `X-Customer-Token` and `cache: "no-store"`, and it carries no x-forwarded-for. GREEN by nature; fail arm B3.
  </behavior>
  <action>
    Implements the coordinator's amendment (2026-11-02). Tests first, then the wiring, then the final build. Same EV directory and rc rules as Task 1.
    1. RED: write R1-R4 and E1-E2 as in `<behavior>`. Run `npx jest app/api/customer-orders/__tests__/route.test.ts lib/__tests__/forwarded-for.test.ts --ci` and save the output to EV/jest-red-t2.txt. Expect R1 and E1 RED, with E1's diff naming only the loadCustomerOrders and GET calls; R2, R3, R4 and E2 GREEN; every pre-existing route test GREEN. Record the pattern verbatim. If R3 is red here, the POST does not behave as measured: stop and report, because the pin is the baseline.
    2. GREEN:
       - In `frontend/lib/customer-orders-server.ts`, import `forwardedClientHeaders` from `@/lib/forwarded-for` and send headers `{ "X-Customer-Token": accessToken, ...(await forwardedClientHeaders()) }` inside the existing try. `cache: "no-store"` and the never-throw contract stay as they are.
       - In `frontend/app/api/customer-orders/route.ts`, import `forwardedForHeader` from `@/lib/forwarded-for`. GET sends `{ "X-Customer-Token": access, ...forwardedForHeader(req.headers) }`. POST replaces its own two-line read with `Object.assign(headers, forwardedForHeader(req.headers))` at the same position, so the key, the value and the key order are unchanged.
       - Update both handlers' docblocks: the GET now forwards the chain too, both go through the shared module, and the "core reads it only from a trusted-proxies peer" sentence stays.
       - No other file reads or names the header in code.
       Run both files and storefront-server.test.ts, all GREEN; save to EV/jest-green-t2.txt.
    3. Single-copy gate, the plain-text half of "no second copy". List the code lines that name x-forwarded-for (case-insensitive) in `frontend/app`, `frontend/lib`, `frontend/components`, `frontend/hooks` and `frontend/middleware.ts`, excluding tests and specs, with comment lines (`*`, `//`, `/*` after the line prefix) dropped. Use `rg -uu` and print the rc. Baseline measured at 6ce700eb while planning: exactly `route.ts:136` and `route.ts:137`, so the gate FAILS at HEAD. That is its recorded fail direction; the comment at `route.ts:111` was correctly filtered out, which is the positive control for the filter. After this step every remaining line must be in `frontend/lib/forwarded-for.ts`. Save to EV/single-copy-gate.txt. The behavioural half is B1/B4/B5 reddening R3 and E1 in Task 3. <!-- planner-discipline-allow: x-forwarded-for -->
    4. Final build and probe: rebuild into EV/build-after.txt (rc 0); extract EV/routes-after.txt and EV/prerender-after.txt exactly as in Task 1 step 1. Run `ssr-xff-probe.sh after`. Then `assert-probe.mjs EV/probe-after.jsonl after` (all cases) and `assert-probe.mjs diff EV/probe-before.jsonl EV/probe-after.jsonl` (all cases; api-post identical) must both exit 0. Record the rc values in EV/probe-after-asserts.txt.
    5. Commit with the subject `fix(storefront): customer order reads forward the visitor's X-Forwarded-For through the shared helper`, attribution-free, and read it back as in Task 1.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-ssr-xff/frontend && out=$(npx jest app/api/customer-orders/__tests__/route.test.ts lib/__tests__/forwarded-for.test.ts lib/__tests__/storefront-server.test.ts --ci 2>&1); rc=$?; printf '%s\n' "$out" | tail -6; echo "jest rc=$rc"; cd .. && EV=.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence && node $EV/assert-probe.mjs $EV/probe-after.jsonl after; a=$?; node $EV/assert-probe.mjs diff $EV/probe-before.jsonl $EV/probe-after.jsonl; d=$?; echo "after=$a diff=$d"</automated>
    Pass: jest rc=0, after=0, diff=0. Fail looks like: jest rc=1 with E1's diff naming loadCustomerOrders/GET (as in EV/jest-red-t2.txt), after=1 printing an orders or api-get upstream line with xff null, or diff=1 on api-post (the POST's output changed). The fail direction is executed in step 1 (Jest), step 3 (the gate at HEAD) and Task 1 step 3 (runtime).
  </verify>
  <done>loadCustomerOrders and both /api/customer-orders handlers send the visitor's chain byte-for-byte through the one shared module, beside their existing headers and nothing else. The POST's header keys, order and values are identical before and after (R3 green throughout; probe diff identical for api-post). E1 covers all 11 server-side core reads with one assertion. No code line outside lib/forwarded-for.ts names the header. Committed.</done>
</task>

<task type="auto">
  <name>Task 3: break arms over the shared module and every caller, route-mode parity, the full suite, metrics prose and the CHANGELOG entry</name>
  <files>frontend/lib/forwarded-for.ts, frontend/lib/storefront-server.ts, frontend/lib/customer-orders-server.ts and frontend/app/api/customer-orders/route.ts (transient break arms only, each restored by hash), docs/metrics.json, CLAUDE.md, AGENTS.md, README.md, docs/CHANGELOG.md</files>
  <action>
    1. Break arms. Bracket them clean, then arms, then clean again; Task 2's commit is the restore target. Clean first: the three suites (storefront-server, forwarded-for, route) are green. Then apply each arm as a scripted edit, run `npx jest lib/__tests__/storefront-server.test.ts lib/__tests__/forwarded-for.test.ts app/api/customer-orders/__tests__/route.test.ts --ci`, and record which named tests went red in EV/break-arms.txt. Restore the edited file with `git restore --source=HEAD -- <file>` (from HEAD, not the index), then assert by content that `git hash-object` equals `git rev-parse HEAD:<file>` for ALL FOUR source files. Expected reds:
       - B1, `forwardedForHeader` always returns no header: T1, T2, R1, R3, E1. R3 going red is the proof that the POST now uses the shared helper.
       - B2, `forwardedForHeader` invents an address (for example the literal `unknown`) when the source has none: T3, R2, R4.
       - B3, `forwardedClientHeaders` loses its try/catch: T4, E2.
       - B4, `forwardedForHeader` forwards only the left-most hop: T1, R1, R3, E1.
       - B5, `forwardedForHeader` copies every header of its source: T2, R1, R3, E1.
       - B6, getJson passes only `{ headers }`, dropping NO_STORE: T1 plus the existing "fetches every storefront call with cache: no-store" test.
       - B7, loadCustomerOrders sends only its token again: E1, with the diff naming exactly the loadCustomerOrders `/public/orders/mine` call.
       - B8, the route GET drops the helper spread: R1 and E1.
       An arm that stays green is a defect in the test. Strengthen the test, add a commit, and re-run that arm. Close with clean again: all three suites green and all four hashes match.

    2. Route-mode parity. `diff EV/routes-before.txt EV/routes-after.txt` and `diff EV/prerender-before.txt EV/prerender-after.txt` must both be rc 0. Fail arm: copy routes-after to the scratchpad, flip one row's symbol (ƒ to ○), and diff must give rc 1. Record all three rc values in EV/route-parity.txt. List every ○ row that exists and confirm none of them imports storefront-server, customer-orders-server or forwarded-for; pages are ƒ under the root layout's force-dynamic (#89), and `/api/customer-orders` is a request-reading route handler.

    3. Full frontend suite: `npx jest --ci` in `frontend/`, rc 0. Record the Tests/Suites totals in EV/jest-full.txt. landing, seller-block, account-page and shop-promotions-hidden must be green: these suites mock next/headers their own way or not at all, so they exercise the degrade path. Then run `npx eslint` on the seven touched source and test files with `--max-warnings 0`. The verdict is rc 0, not the summary line (that line counts fixables). The `next build` runs at rc 0 are the type-check.

    4. Metrics. `scripts/docs-freshness.sh` in check mode must give rc 1 first (the fail arm: the tree has gained blocks and one file). Then run it with `--write`. Next, `scripts/check-doc-metrics.sh` gives rc 1 and names the stale CLAUDE.md, AGENTS.md and README.md claims (jest_blocks, jest_files and the total of logical invocations). Update each named number to the manifest value, re-run until rc 0, and confirm `scripts/docs-freshness.sh` gives rc 0. In `docs/metrics.json`, the jest_blocks delta must equal the `it(` blocks added in Tasks 1 and 2 (planned: 4 + 4 + 2 = 10) and the jest_files delta must be 1. If they are not, that is VOID: investigate before going on. Save the rc sequence to EV/metrics.txt.

    5. CHANGELOG. Add an entry under `## [Unreleased]` in `docs/CHANGELOG.md`, heading `### Storefront: Server-Side Reads Carry the Visitor's Address to the Public Rate Limiter — 2026-11-02`, with these bullets:
       - What changed: one shared module (`lib/forwarded-for.ts`); ONLY X-Forwarded-For, forwarded verbatim; every storefront-server read, loadCustomerOrders and GET /api/customer-orders; POST /api/customer-orders unchanged in output but now through the same helper; no header and no throw outside a request.
       - Why: the #1104/#1105 reviews; two reads per landing render; one bucket per frontend pod.
       - Trust is unchanged: core's ClientIpResolver reads XFF only from a trusted-proxies peer and walks right to left, so a client-written left part is never read; no core-java or k8s change.
       - Where it takes effect: staging at once (its 10.244.0.0/16 covers the frontend pods). Compose: no change. Production: only once the owner's 37-24 `security.trusted-proxies` step declares a range that covers the frontend pods (the peer of these server-side reads) as well as the ingress controller, typically the cluster pod CIDR. The production overlay is deliberately untouched, because the pod range is not in the repo.
       - Proof: Jest RED to GREEN, eight break arms, the POST pinned before the refactor, route table and prerender manifest identical, and the built server probed against a stub core, base vs changed.
       The heading's `(#NNNN)` is added by the ship step once the PR exists (P-1/C-1, the 261102-r6u practice). Record that as the one remaining edit.

    6. Commit with the subject `docs(storefront): metrics and changelog for server-side X-Forwarded-For forwarding`, attribution-free, read back as in Task 1.

    7. SUMMARY (`261102-vwa-SUMMARY.md`) must record:
       - (a) the production precondition from bullet 5, with the overlay unchanged;
       - (b) the read-only check to run on staging after deploy: `rl:public:*` keys in the staging Redis name client addresses, not 10.244.x frontend pod addresses (kubectl context jtoye-stg-aks; nothing written);
       - (c) Next's own x-forwarded-for fill for header-less requests, with the value the `none` case observed;
       - (d) the POST's unchanged output, with R3 and the api-post probe diff as the evidence;
       - (e) that no core change was needed.
  </action>
  <verify>
    <automated>cd /home/sanmi/IdeaProjects/JToye_OaaS_2026-ssr-xff && EV=.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/evidence && diff $EV/routes-before.txt $EV/routes-after.txt; r=$?; diff $EV/prerender-before.txt $EV/prerender-after.txt; p=$?; h=0; for f in frontend/lib/forwarded-for.ts frontend/lib/storefront-server.ts frontend/lib/customer-orders-server.ts frontend/app/api/customer-orders/route.ts; do test "$(git hash-object $f)" = "$(git rev-parse HEAD:$f)" || h=1; done; bash scripts/docs-freshness.sh >/dev/null 2>&1; df=$?; bash scripts/check-doc-metrics.sh >/dev/null 2>&1; cm=$?; cd frontend && out=$(npx jest --ci 2>&1); j=$?; printf '%s\n' "$out" | tail -5; echo "routes=$r prerender=$p restored=$h docs-freshness=$df doc-metrics=$cm jest-full=$j"</automated>
    Pass: every value is 0. Fail looks like: routes=1 with a ○ to ƒ line shown (a route changed mode), restored=1 (a break arm was left in a source file), docs-freshness=1 or doc-metrics=1 (prose drift), or jest-full=1. The fail direction of each is executed in steps 1, 2 and 4 and recorded in EV.
  </verify>
  <done>Each of the eight break arms turned its named tests red (B1/B4/B5 including R3, so the POST provably uses the shared helper) and was restored by hash. Route table and prerender manifest are identical before and after, and the flipped copy diffs rc 1. The full Jest suite and eslint are rc 0. The metrics manifest and prose agree (both gates rc 0, red first). The CHANGELOG entry is written pending only its PR number. The SUMMARY records the production precondition, the staging post-deploy check and the POST evidence.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| browser → ingress → frontend | The visitor writes any X-Forwarded-For it likes. The ingress (Traefik on staging) appends the real peer as the right-most hop. |
| frontend (SSR, route handlers) → core-java /public/** | The frontend pod is the socket peer. Core believes the forwarded chain only when that peer is inside `jtoye.security.trusted-proxies`. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-vwa-01 | Spoofing | every server-side read → core public bucket (`rl:public:{ip}`) | high | mitigate | `lib/forwarded-for.ts` forwards the received chain verbatim and never parses or chooses a hop (T1, R1, R3, E1, break arm B4; the probe shows the client-written `192.0.2.66` left part reaching core untouched). Core's `ClientIpResolver` reads XFF only from a trusted-proxies peer and walks right to left to the first untrusted hop. On staging the right-most hop is Traefik's append of the real peer, so the left part is never read. No core change. |
| T-vwa-02 | Spoofing | a caller that reaches the frontend pod directly and writes the whole chain | medium | accept | Frontend ingress is admitted only from the ingress namespace (`k8s/base/networkpolicies/10-frontend.yaml`). This is the residual staging already records ("any in-cluster pod can supply X-Forwarded-For", `k8s/staging/configmap-patch.yaml`). Compose and production trust no peer, so XFF is inert there. |
| T-vwa-03 | Information disclosure | client address and other request headers sent onward | medium | mitigate | Exactly one header is added, and every caller keeps only its prior headers (T2, R1, R3, E1's per-call name sets, B5, and the probe's header-name diff). Cookies, Authorization and x-nonce never reach core through these reads. The helper logs nothing, because an IP address is personal data. |
| T-vwa-04 | Denial of service | `headers()` outside a request scope (sitemap, Jest, any non-request caller) | medium | mitigate | try/catch returns no header; the loaders and loadCustomerOrders keep their never-throw results (T4 and E2 against the REAL next/headers, and B3). Route handlers read `req.headers`, so they do not depend on request scope at all. |
| T-vwa-05 | Denial of service | an oversized client X-Forwarded-For exceeds core's header limit | low | accept | A storefront read becomes `defer` and a customer-orders read becomes its existing error state. Only the sender's own render degrades; the client island reads from the browser under its own address. |
| T-vwa-06 | Denial of service | POST /api/customer-orders refactor: losing the chain would put every cash order on the frontend pod's per-IP cap (37-28, D-27), so one cap slot would be shared platform-wide | high | mitigate | R3 pins the exact keys, order and values BEFORE the refactor and stays green after it. B1, B4 and B5 must redden R3, proving the POST goes through the helper. The probe diff requires api-post to be identical across builds. |
| T-vwa-SC | Tampering | npm installs | low | accept | No package is added. `npm ci` installs the committed lockfile. `next/headers` ships in the already-installed `next`. |
</threat_model>

<verification>
- Jest: storefront-server T1/T2, route R1 and E1 go RED before their wiring and then GREEN. R3 (the POST pin) stays GREEN throughout. Eight break arms each redden their named tests. The full suite is rc 0.
- Real path: the built Next server against a stub core.
  - Base build: no X-Forwarded-For on any read except the POST, which already forwarded.
  - Changed build: the exact chain on every read of /, /shop, /shop/[slug], the basket title, /sitemap.xml, /shop/orders, GET /api/customer-orders and POST /api/customer-orders.
  - x-forwarded-for is the only header added anywhere, and the POST is byte-identical.
  - The after-assertion is shown to fail on the base log.
- Single copy: no code line outside `frontend/lib/forwarded-for.ts` names the header. The gate failed at HEAD (route.ts:136-137) and passes after.
- Route mode: the route table and the prerender manifest are identical; the flipped copy diffs rc 1.
- Docs: docs-freshness and check-doc-metrics are red first and then rc 0. The CHANGELOG entry is present, with its PR number added at ship.
- No diff under core-java/ or k8s/: `git diff --stat origin/main -- core-java k8s` is empty. Positive control: the same command over `frontend/lib` is non-empty.
</verification>

<success_criteria>
- Every server-side core read the frontend makes (the storefront-server loaders, loadCustomerOrders, and GET and POST /api/customer-orders) carries the incoming X-Forwarded-For byte-for-byte beside its own headers and nothing else, and carries none when there is none or outside a request.
- One shared helper; the POST's output is unchanged and proven so.
- No route changes from ○ to ƒ or back; cache no-store and redirect manual are preserved.
- Proof recorded in both directions under evidence/. Metrics prose matches. The SUMMARY and CHANGELOG name the production trusted-proxies precondition (frontend pods included) and the staging post-deploy Redis-key check.
</success_criteria>

<output>
Create `.planning/quick/261102-vwa-ssr-storefront-reads-forward-the-visitor/261102-vwa-SUMMARY.md` when done
</output>
