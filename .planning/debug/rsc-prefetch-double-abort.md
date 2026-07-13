---
status: awaiting_human_verify
trigger: "User report 2026-07-13: landing page hit or miss; several clicks on the home page just hang on shop"
created: 2026-07-13T13:30:00Z
updated: 2026-07-13T14:40:00Z
---

## Symptoms

DATA_START
- **Expected:** Next.js App Router prefetches route RSC payloads once per visible Link; clicking navigates instantly from the prefetch cache.
- **Actual (measured, Playwright headless chromium, 4/4 cold loads, 2026-07-13):** EVERY page load fires RSC prefetches for all header/footer routes (`/shop`, `/track`, `/for-operators`, `/auth/signin`, `/`) **twice with two distinct `_rsc` tokens** (e.g. `?_rsc=5CB68i4pnAekjehf` and `?_rsc=tXyZJ52UQVBCVsD3`) and **ALL abort** with `net::ERR_ABORTED`. Zero successful prefetches. Consequence: every click pays an on-demand RSC fetch at click time — under real-device latency this presents exactly as "click hangs on shop" / "landing hit or miss". Clean-lab clicks still complete (~3s to content), so the defect is masked on fast localhost.
- **Errors:** `net::ERR_ABORTED` on all `?_rsc=` GETs; no console hydration errors captured in headless run (but open issue #202 "hydration" exists — suspected related).
- **Timeline:** unknown start; frontend is Next.js 16.2.2 App Router; Phase 19 overhaul (PR #181) rebuilt the header/nav; #202 hydration filed as open P2.
- **Reproduction:** `/tmp/claude-1000/-home-sanmi-IdeaProjects-JToye-OaaS-2026/5581c3e3-1926-4520-90ab-11439487cfb7/scratchpad/repro-ux.js` (TEST A evidence section shows the aborted prefetch pairs), run from `frontend/` with `NODE_PATH=./node_modules node <script>`. Stack must be up (frontend :3000, prod build in container).
- **Leads to investigate:** (1) double-render/remount of the nav (two Link trees → two prefetch generations, first aborted — but here BOTH abort); (2) middleware interfering with `_rsc` requests (NextAuth middleware / CSP nonce — memory: "Next.js nonce-CSP needs force-dynamic"); (3) router-state key instability (#202 hydration mismatch → router cache key churn); (4) `next.config.mjs` standalone output + headers config; (5) Next 16 prefetch=auto behavior with dynamic pages — aborts may be EXPECTED for dynamic routes (rule out: verify against a stock Next 16 app or docs before declaring defect).
- **IMPORTANT epistemic note:** prefetch aborts CAN be benign (viewport-exit cancellation, dedupe-supersede, dynamic-route bailout). The investigation MUST first establish whether these aborts are pathological (e.g., by comparing click-time latency with prefetch disabled vs enabled, checking for double-mounted nav, and checking Next.js 16 expected behavior) before changing code. If benign, the user-perceived hang has another cause (e.g., shop page data fetch blocking first paint on device networks) — then instrument /shop's data path instead.
- **Constraints:** branch `feature/ux-mobile-nav-rsc-fixes` (off main; PR #213 separate). Frontend TS changes require `npm run build` (tsc). Dev-mode repro (`npm run dev`) differs from prod container behavior — confirm findings against the containerized prod build before claiming fixed; rebuild frontend container before any live-E2E claim. Verification must run the mandatory matrix: desktop + 390px mobile × fresh + stale cookies × real click-through with content-appearance timing. SSE pages break `networkidle` waits — use element-based waits.
DATA_END

## Current Focus

hypothesis: The _rsc aborts are BENIGN (proven — see Evidence 14:20). The real perceived-hang mechanism: ZERO loading.tsx boundaries anywhere + app-wide force-dynamic ⇒ every client-side navigation waits for the full RSC server round-trip with NO visual feedback (router does not commit until payload arrives). On localhost that window is ~10ms; on real-device network latency it is seconds of frozen UI ⇒ "several clicks just hang on shop".
test: CDP Network.emulateNetworkConditions (≈4G: 300ms RTT, ~1.5Mbps) against the CURRENT prod container; measure click→first-visual-change, click→URL change, click→shop H1/data. Prediction if hypothesis true: click→first-visual-change ≈ click→shop-H1 (both ≥ RTT+render, >1s) — i.e. zero feedback until full payload lands.
expecting: CONFIRMED — throttled probe measured firstVisualFeedback == urlCommit == shopH1 (542ms @SLOW4G, 1490ms @POOR; data 2286ms @POOR): zero feedback until full payload arrival.
next_action: fix implemented + verified (see Resolution); awaiting human verification that the real-device "clicks hang on shop" experience is resolved; note "landing hit or miss" was never reproduced in lab — ask user to re-test landing too.

reasoning_checkpoint:
  hypothesis: "The universal double-token _rsc aborts are benign Next 16 two-wave prefetch stream-termination (tree wave + full wave, both 200 + payload bytes before deliberate cancel). The user-perceived hang is caused by ZERO loading.tsx boundaries + app-wide force-dynamic: click navigations render no feedback until the full RSC round-trip completes."
  confirming_evidence:
    - "CDP lifecycle: all 9 'aborted' prefetches received status=200 + text/x-component + 183-1447 bytes BEFORE canceled=true; two _rsc tokens map 1:1 to two distinct prefetch header sets (next-router-segment-prefetch=/_tree vs next-router-prefetch+state-tree)"
    - "Throttled probe on current build: click->firstVisualFeedback (MutationObserver) == urlCommit == shopH1 — 542ms @SLOW4G / 1490ms @POOR, data 2286ms @POOR; find over app tree: zero loading.tsx files; DOM: 1 header/1 nav (no double mount)"
  falsification_test: "After adding loading.tsx boundaries and rebuilding the container, re-run the identical throttled probe: if click->firstVisualFeedback does NOT drop to near-instant (<150ms even @POOR), the mechanism claim is wrong and the fix is reverted."
  fix_rationale: "loading.tsx creates Suspense boundaries letting the router commit navigation immediately and paint a skeleton from the prefetched shell while the dynamic payload streams — removes the zero-feedback window (root cause), instead of touching the benign aborts (symptom). Minimal: three static server-component files, no behavior change to data flow, middleware, or nav."
  blind_spots: "(a) 'landing hit or miss' never reproduced in lab (4/4 cold loads OK, TTFB 23-64ms; throttled landing streams progressively) — plausibly environmental or the already-fixed mobile-nav gap; noted for user verification. (b) Assumes Next 16 prefetch includes the loading shell for force-dynamic routes — verified empirically post-rebuild by the falsification test. (c) Dashboard flows out of scope."

## Evidence

- timestamp: 2026-07-13T14:05:00Z
  checked: knowledge base (.planning/debug/knowledge-base.md)
  found: only entry is mobile-nav-operators-hidden; no keyword overlap with _rsc/prefetch/abort
  implication: no known-pattern candidate; proceed with fresh hypotheses

- timestamp: 2026-07-13T14:06:00Z
  checked: frontend/middleware.ts + frontend/next.config.mjs + frontend/app/layout.tsx
  found: (a) middleware matcher does NOT exclude _rsc requests — every prefetch runs NextAuth auth() + fresh CSP nonce; (b) root layout sets `export const dynamic = "force-dynamic"` app-wide (issue #89 CSP nonce requirement); (c) CSP set on request+response headers per canonical Next recipe
  implication: ALL routes are dynamic. In Next App Router, prefetch of a dynamic route is PARTIAL (up to nearest loading.js) and the client deliberately aborts the stream once it has the partial payload — devtools/Playwright report net::ERR_ABORTED even though the prefetch cache was populated. Strong benign-candidate for the universal aborts. Two distinct _rsc tokens still needs explanation (the token is a hash of the prefetch request headers — two tokens = two different header sets, e.g. Next-Router-Prefetch wave vs full-tree wave).

- timestamp: 2026-07-13T14:12:00Z
  checked: frontend/app tree for loading.js boundaries; app/shop/page.tsx + app/shop/layout.tsx; app/page.tsx; components/public/*; frontend/auth.ts; jtoye-frontend container env
  found: (a) ZERO loading.tsx files in the entire app; (b) /shop page is "use client" with its own client-side skeleton + /public/shops fetch; (c) home is a server component in PublicShell (server comp, single header); (d) middleware auth() jwt callback fires a blocking Keycloak refresh fetch on EVERY matched request when accessToken expired, and on failure returns error-marked token WITHOUT persisting a cookie update — refresh retried per request; (e) container has KEYCLOAK_ISSUER_INTERNAL=http://keycloak:8080 (fast path); NODE_ENV=production; canonical frontend = jtoye-frontend on :3000
  implication: no loading boundary + force-dynamic ⇒ click-time navigations render NOTHING until full RSC round-trip completes. Stale-cookie refresh retry is fast intra-Docker but is a per-request Keycloak round-trip in prod topology.

- timestamp: 2026-07-13T14:25:00Z
  checked: throttled proof-of-mechanism probe (scratchpad/throttled-hang-proof.js, CDP Network.emulateNetworkConditions) on PRE-fix build — MutationObserver first-visual-feedback vs URL-commit vs content timings
  found: click / -> /shop with NO loading boundary — SLOW4G (150ms RTT): firstVisualFeedback=542ms == urlCommit=535ms == shopH1=551ms; POOR (400ms RTT): firstVisualFeedback=1490ms, data=2286ms. Landing initial loads stream progressively (h1 at 208/534ms) — only CLIENT-SIDE navigations freeze.
  implication: mechanism proven — feedback is gated on full RSC payload arrival; 0.5-1.5s+ frozen UI after tap under realistic latency = the reported "clicks hang on shop". Landing "hit or miss" NOT reproduced (4/4 cold loads OK in both repro rounds).

- timestamp: 2026-07-13T14:35:00Z
  checked: POST-fix verification — identical throttled probe + full matrix (desktop+390px × fresh+stale JWE session cookie × click-through / -> /shop -> card -> [slug] -> back) + loading-state screenshots + jest
  found: (a) falsification test PASSED — firstVisualFeedback dropped 542->43ms (SLOW4G), 1490->40ms (POOR), 390px 17ms; urlCommit 29-40ms; content streams behind skeleton. (b) Matrix all 4 cells green: /->shop h1 57-830ms, card->detail 72-109ms, back->shop 11-19ms, zero console/pageerror/hydration across all flows. (c) Screenshots: /shop skeleton renders under intact StorefrontNav; [slug] skeleton committed at /shop/brixton-village-grill under 800ms-RTT throttle; root branded loader at /track. (d) npm run build green (all routes ƒ dynamic); jest 33/33 suites, 229 tests pass. (e) NOTE: instant commit requires the link's tree prefetch to have landed — a just-mounted link under heavy throttle still pays one tree round-trip before the skeleton (observed, expected).
  implication: fix verified end-to-end in the containerized prod build.

- timestamp: 2026-07-13T14:20:00Z
  checked: CDP Network-domain lifecycle forensics of every _rsc request (scratchpad/rsc-forensics.js) against live prod container :3000 — cold / load, fresh+stale cookie, desktop+390px, click-through timings
  found: ALL 9 "aborted" prefetches actually received status=200 + mime=text/x-component + payload bytes BEFORE client-side cancel (wave 1 `next-router-segment-prefetch=/_tree` token 5CB68i4pnAekjehf, 183-207 bytes; wave 2 `next-router-prefetch=1` + state-tree token tXyZJ52UQVBCVsD3, 1349-1447 bytes). Two tokens = two header sets hashed into _rsc (Next 16 segment-cache tree wave + full prefetch wave). DOM: 1 header, 1 nav (no double-mount). Click / → /shop: fresh desktop 86ms to data, 390px 68ms, STALE cookie 77ms; stale-cookie TTFB on / = 23-64ms (internal Keycloak refresh fails fast). Click-time nav issues fresh /shop?_rsc fetch (5647 bytes, 6-13ms) — expected for dynamic route.
  implication: aborts are Next.js 16 expected behavior for dynamic routes (deliberate stream termination after useful payload; ERR_ABORTED in devtools is cosmetic). NOT the defect. Clean-lab click latency is tiny; perceived hang must come from the zero-feedback full-round-trip navigation under real network latency (no loading.tsx anywhere + force-dynamic).

## Eliminated

- hypothesis: Nav/header double-mounts creating two prefetch generations, both pathologically killed, leaving router cache empty
  evidence: DOM shows exactly 1 header / 1 nav; both prefetch waves DELIVERED payload bytes (192-1447B) with status 200 before deliberate client cancel; two _rsc tokens explained by two distinct header sets (tree-prefetch wave vs full-prefetch wave), not remounts
  timestamp: 2026-07-13T14:20:00Z

- hypothesis: Middleware (NextAuth auth() / CSP nonce) interferes with _rsc requests causing aborts/redirect churn
  evidence: all _rsc responses were 200 text/x-component with correct flight payload; no redirects, no set-cookie churn observed; stale-cookie navigation adds no measurable latency intra-Docker (TTFB 23-64ms, click→data 77ms)
  timestamp: 2026-07-13T14:20:00Z

## Resolution

root_cause: The reported `_rsc` double-token universal aborts are BENIGN Next.js 16 prefetch behavior (two prefetch waves — segment-cache `/_tree` wave + full prefetch wave — each deliberately terminating its stream after receiving the payload; devtools reports the termination as net::ERR_ABORTED). The actual perceived-hang defect: the app has ZERO loading.tsx boundaries while the root layout forces `dynamic = "force-dynamic"` app-wide (CSP nonce, issue #89), so every client-side navigation waits for the FULL RSC server round-trip before ANY visual change — measured 542ms (SLOW4G) / 1490ms (POOR, 400ms RTT) of frozen UI after click, presenting exactly as "clicks on the home page just hang on shop" on real-device networks.
fix: Added three static server-component loading boundaries so the App Router commits navigations instantly and paints a skeleton from the prefetched shell while the dynamic RSC payload streams — frontend/app/loading.tsx (root branded loader: covers /, /track, /for-operators, /auth/signin, dashboard entry), frontend/app/shop/loading.tsx (browse-grid skeleton mirroring the page's own client-side skeleton, StorefrontNav header stays via shop layout), frontend/app/shop/[slug]/loading.tsx (detail-shaped skeleton for shop-card taps). The benign _rsc aborts were deliberately left untouched. No changes to middleware, force-dynamic, data flow, or nav.
verification: Identical throttled probe pre/post fix (falsification test): click->firstVisualFeedback 542ms->43ms @SLOW4G, 1490ms->40ms @POOR(400ms RTT), 17ms @390px; urlCommit 29-40ms. Full matrix (desktop+390px × fresh+stale minted JWE session cookie) all green with zero console/hydration errors; click-through / -> /shop -> shop card -> back verified with content-appearance timing; loading states visually confirmed via mid-navigation screenshots under 600-800ms RTT throttle; npm run build (tsc) green; jest 33 suites/229 tests green; verified against rebuilt containerized prod build (jtoye-frontend :3000). Element-based waits used throughout (no networkidle).
files_changed: [frontend/app/loading.tsx, frontend/app/shop/loading.tsx, "frontend/app/shop/[slug]/loading.tsx"]
