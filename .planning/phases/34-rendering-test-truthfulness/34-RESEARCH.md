# Phase 34: Rendering + Test Truthfulness - Research

**Researched:** 2026-08-28
**Domain:** Next.js 16 App Router rendering strategy; Playwright coverage semantics; multi-language coverage gating
**Confidence:** HIGH (almost every claim below was measured on this tree and this running stack today, in both directions)

---

## Summary

The roadmap's Phase 34 section carries measurements dated **2026-08-07**. Every one of them was
re-measured for this research and **four are now materially wrong**. The phase's own instruction —
*"scope from measurement, not from #463"* — turns out to apply to the roadmap section itself.

The three biggest corrections:

1. **#507's two flagship conversions are already done.** `/shop` and `/shop/[slug]` are server
   components today and serve their content in the raw HTML (measured live: 5 and 33 occurrences of
   a shop name in the served bytes, against a validated negative control of 0). The roadmap
   repeats #507's premise correction — *"`/shop` is client-rendered too"* — as though it were still
   true. It describes the tree of 2026-08-04.
2. **#547's diagnosis of the one unowned skip is wrong, and the correction makes it ~30 minutes of
   work instead of a fixture-engineering task.** #547 and `e2e-skip-budget.conf` both say the
   `onboarding-blocked-flow` mobile skip needs a `DemoDataSeeder` shop. The nightly report's own
   annotation says otherwise: `skip=single-tenant onboarding journey pinned to the desktop project
   (UNIQUE(tenant_id) — no cross-worker race)`. It is a *"not applicable here"* skip, which
   `playwright.config.ts` already has a documented mechanism for (`@desktop-only` + `grepInvert`).
3. **#202 is 4 sites in the issue and 10 suppressions on the tree**, and the fourth site has
   *moved* — the `storefront-nav.tsx` one was extracted into `hooks/use-customer-session.ts` by
   #457, where it now serves two headers. The four #202-lineage sites are still identifiable, by the
   marker string `refactor tracked in issue #99 follow-up`, which exactly four suppressions carry.

The good news is that **this repo has already built every pattern this phase needs**, in two
places, and the work is mostly generalisation rather than invention:
`lib/storefront-server.ts` holds the three-valued SSR loader (`ok | notfound | defer`) and the
server-seed → client-island idiom; `e2e/storefront-ssr-seo.spec.ts` holds the raw-HTML coverage
assertion that a browser route stub is structurally incapable of satisfying.

**Primary recommendation:** Adopt `request.get()` raw-HTML assertions as the *definition* of
coverage for a server-rendered route, back it with a default-deny SSR-route manifest gate in
`scripts/gates/`, and treat #507 conversions as discretionary follow-on rather than the phase's
load-bearing deliverable.

---

## User Constraints

**No CONTEXT.md exists for this phase** (`gsd-sdk query init.phase-op 34` → `has_context: false`),
so there are no locked user decisions. The binding constraint set is the ROADMAP's five success
criteria plus `CLAUDE.md`. Reproduced here so the planner does not have to re-derive them.

### Locked (ROADMAP.md, Phase 34 — verbatim intent)

| # | Criterion | Req |
|---|-----------|-----|
| 1 | A route-interception stub is never the coverage story for a server-rendered route. The phase must produce **a pattern** that does not silently drop coverage, **shown to fail against a stubbed SSR route**. Scope from measurement, not from #463. | TRUTH-01 |
| 2 | The four mount-time `setState`-in-effect hydration sites are gone, with the ESLint gate **shown to fail against a reintroduced one**. | TRUTH-01 |
| 3 | #286 is **narrowed**, not closed whole. `/dashboard/staff` already runs live. What remains is the viewport and the 9 route stubs. | TRUTH-02 |
| 4 | #110 is **narrowed to coverage**: JaCoCo, the unconsumed Go profile, a Jest `coverageThreshold`. Its "Playwright runs in CI" half is met by the nightly. | TRUTH-02 |
| 5 | #547 closes **by closing its children**. Only the `onboarding-blocked-flow` mobile skip is this phase's to home. | TRUTH-02 |

### Claude's discretion

- Which (if any) of the remaining 21 mount-fetch routes to convert, and in what order.
- The exact shape of the SSR-coverage guard (#542 offers four options, none prejudged).
- Coverage threshold values — provided they are derived from a measured baseline and declared as
  no-regression guardrails rather than invented targets.

### Out of scope (record N/A, do not absorb)

- The 6 skips owned by **#304** (Phase 29, BLOCKED on owner) and **#61** (Phase 30, BLOCKED on
  owner). #547 says so in its own body.
- The **`middleware.ts` → `proxy.ts`** migration — see *Open Question 1*, which recommends
  out-of-scope with evidence.
- **#453** (who adjudicates onboarding MANUAL_REVIEW) — unadjudicated product decision.

---

## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| TRUTH-01 | A route-interception stub is never the coverage story for a server-rendered route, and the four mount-time `setState`-in-effect hydration sites are gone with the ESLint gate shown to fail against a reintroduced one. Closes #542, #507, #202. | *Pattern 1* (raw-HTML coverage assertion, with its fail direction executed below), *Pattern 2* (SSR-route manifest gate), *Pattern 4* (`useSyncExternalStore` / derive-during-render), and the measured rule-shape table showing exactly which code shapes the ESLint rule does and does not flag. |
| TRUTH-02 | Coverage is measured and gated per language, and the remaining E2E skips are owned. #286 narrowed, #110 narrowed to coverage, #547 closes via its children. | Measured baselines for all three coverage tiers (JaCoCo 62.12% line, Go 66.8%, Jest 65.1% line); the corrected root cause of the one unowned skip; the measured 375px/390px viewport state. |

---

## Project Constraints (from CLAUDE.md)

The planner must verify compliance with each of these; they are as binding as a locked decision.

| Directive | Consequence for this phase |
|-----------|----------------------------|
| **`docs/metrics.json` is the single source of truth for test counts**, enforced by *two* gates (`scripts/docs-freshness.sh` tree→manifest, `scripts/check-doc-metrics.sh` prose→manifest). | Any added/removed test block requires `docs-freshness.sh --write` **and** a prose update in README.md / AGENTS.md / CLAUDE.md. Never compute the new number arithmetically (recorded trap: the counter greps literal `it(` / `test(`). Current: `playwright_blocks 113`, `playwright_specs 22`, `total_logical_invocations 3188`. Both gates measured **rc=0** today. |
| **Incremental Betterment Doctrine** — never trade away a working good. | Do **not** change `playwright.config.ts`'s mobile project from 390×844 to 375. That viewport is documented, is what `mobile-instrument-contract.spec.ts` asserts, and is the baseline of every mobile perf measurement. Extend the 375 coverage additively instead (see *Criterion 3*). |
| **Falsifiable evidence (a)** — every acceptance criterion must be *shown to fail* before it is trusted. | Each criterion in this document ships with its fail-direction command. Three of them were executed during this research and their real output is recorded. |
| **Runtime parity (b)** — a phase is not done until the delivered runtime matches the branch. | Any SSR conversion needs a **frontend rebuild** before E2E. `scripts/check-runtime-freshness.sh` + `scripts/check-branch-behind-base.sh`. |
| **All new code requires tests.** | Any new gate script needs its own falsification run recorded; any new spec is type-checked by `npx tsc --noEmit` **and** `scripts/check-e2e-typecheck.sh`. |
| **Compose is the canonical local runtime; k8s is the deploy target. XOR at local runtime.** | Measurements here were taken against the running Compose stack. Minikube must stay stopped. |
| **Multi-tenancy: RLS + TenantContext.** | Any dashboard SSR conversion must carry the **caller's** bearer token to core so RLS applies. See *Security Domain*. |
| **GSD workflow enforcement** — no direct edits outside a GSD command. | Execution goes through `/gsd:execute-phase`. |
| **No emoji as decorative code content.** | Applies to any new gate output. |

**Project skills:** `.claude/skills/proof-standards/SKILL.md` is loaded for the executor, verifier,
code-reviewer and planner (`config.json` `agent_skills`). Its rules are assumed throughout: show the
fail direction; `rg`/`grep` honour `.gitignore` so use `rg -uu`; capture `rc` on the same line;
`cmd | grep -q X` inverts under `pipefail`; bracket destructive work clean → arms → clean.

---

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Fetching storefront/dashboard data for first paint | **Frontend Server (SSR)** | Browser (island refetch) | An effect only ever runs in a browser, so the server can only render the loading branch. `lib/storefront-server.ts` already owns this. |
| Deciding *"this shop does not exist"* vs *"we could not get an answer"* | **Frontend Server (SSR)** | Browser (owns retry budget on `defer`) | The three-valued `StorefrontLoad<T>` exists precisely so a non-answer is never presented as authoritative (F-RATE / #88). |
| Tenant/row authorisation of any fetched data | **API / Backend (Postgres RLS)** | — | The frontend never filters by tenant. An SSR fetch must forward the caller's token so RLS applies; it must never use a service account. |
| Access-token refresh | **Browser** (`lib/api-client.ts` single-flight interceptor) | — | There is **no** refresh path on the SSR fetch. An SSR loader meeting an expired token must `defer`, not error. |
| CSP nonce minting | **Frontend Server (middleware)** | — | `middleware.ts` → `x-nonce` request header → read by server pages emitting inline JSON-LD. |
| Coverage of a **client-rendered** route in E2E | **Browser** (`context.route` stub) | — | Legitimate and cheap; this is what the per-PR stack-free gate is for. |
| Coverage of a **server-rendered** route in E2E | **HTTP (raw response)** — `request.get()` | Browser (hydration behaviour only) | **Measured today: `context.route` does not intercept `APIRequestContext`.** A browser stub is structurally incapable of covering the SSR path. |
| Per-language coverage measurement | **Build tool** (Gradle/JaCoCo, `go tool cover`, Jest) | CI (threshold gate) | Coverage is a build-time artefact; the gate is the CI consumer of it. |

---

## Re-measurement Ledger — what the roadmap says vs. what is true today

Every row was measured on **2026-08-28** against `main @ efcc3ee9` and the running Compose stack.
This table is the single most important input to planning: four rows changed.

| Roadmap / issue claim (2026-08-05 → 08-07) | Measured today | Status |
|---|---|---|
| `/shop` is client-rendered; #463's premise is wrong | `app/shop/page.tsx` is a **server component**; served HTML for `/shop` carries the shop name **5×**, `/shop/brixton-village-grill` **33×** (negative control `zzzNotARealShopName` = 0) | **STALE — already fixed** |
| #507: 25 client pages queued for conversion | `git grep -l '"use client"' -- 'frontend/app/**/page.tsx'` still returns **25**, but **4 of those are comment-only matches** in pages that were already converted and documented the fact. Real count: **21** of 38 `page.tsx` | **INSTRUMENT DEFECT** (see Pitfall 1) |
| 6 specs rely on route interception | **7** files: `dashboard-mobile`, `kitchen-flow`, `public-layout`, `unsubscribe-flow`, `webhooks-flow`, `webhooks-webperf`, **+ `helpers/public-surface.ts`** (new since) — **38** `.route(` calls total | **CHANGED (+1 file)** |
| `dashboard-mobile` carries 9 route stubs | **9** — confirmed (`e2e/dashboard-mobile.spec.ts` lines 267, 275, 280, 284, 288, 294, 300, 301, 433) | **CONFIRMED** |
| `dashboard-mobile` runs at 390×844, not 375 | Confirmed for the **11-route sweep** (`:309`). But the spec **already has a dedicated 375×812 describe block** at `:425` ("MOBL-01 + switcher regression", 1 test) | **PARTIALLY STALE** |
| `dashboard-interface-corrections.spec.ts`: 3 `vendorLogin` refs, 0 route stubs | **3** refs (`:49` def, `:108`, `:201`), **0** `.route(` | **CONFIRMED** |
| Nightly: 182 total / 175 passed / 7 skipped (run 31138225934) | Latest nightly **33142364550** (2026-08-28 04:37, sha `75005617`): **266 total / 253 passed / 6 failed / 7 skipped** | **STALE — total is 266, not 182** |
| The 6 nightly failures | `storefront-flows` × 2 projects (checkout, Mailhog, shop card) — fixed by **#670 / `7ac23442`**, which merged **after** that nightly ran. No nightly has yet run on the fixed tree | **STALE — already fixed, unproven** |
| #547: only 1 of 7 skips is unowned | Confirmed: stomp-relay 2 tests × 2 = 4 (#304), vendor-refund 1 × 2 = 2 (#61), onboarding-blocked-flow **mobile only** = 1 | **CONFIRMED** |
| #547 + conf: the onboarding skip needs a `DemoDataSeeder` shop | The report's own annotation: `skip=single-tenant onboarding journey pinned to the desktop project (UNIQUE(tenant_id) — no cross-worker race)`. It fires at `onboarding-blocked-flow.spec.ts:116`, **before** the fixture check at `:171`. The desktop project **passed** | **WRONG CAUSE — see Criterion 5** |
| #202: four `setState`-in-effect sites | **10** suppressions on the tree; exactly **4** carry the `#99 follow-up` marker, and one of those **moved** from `storefront-nav.tsx` into `hooks/use-customer-session.ts` (#457) | **CHANGED** |
| #202: the ESLint gate needs "resurrecting" | `react-hooks/set-state-in-effect` is **already at severity 2 (error)** — `npx eslint --print-config app/page.tsx`. `npm run lint` runs it in CI job `lint`. Fail direction executed: rc=1 | **ALREADY LIVE** |
| #110: no JaCoCo | Confirmed absent from `core-java/build.gradle.kts` (positive control: `plugins` matched, rc=0) | **CONFIRMED** |
| #110: Go coverage profile generated but unconsumed | Confirmed — `ci-cd.yaml:78` `go test -v -coverprofile=coverage.out ./...`, zero `go tool cover` / upload / threshold anywhere | **CONFIRMED** |
| #110: no Jest `coverageThreshold` | Confirmed — `jest.config.js` has `collectCoverageFrom` only | **CONFIRMED** |
| Skip budget: 7 against a ceiling of 8 | Confirmed. `MAX_SKIPS 8`, 3 `ALLOW` entries. The conf's own arithmetic comment ("Total = 8, 4 distinct tests × 2 projects") is **wrong** — the onboarding one is mobile-only | **CONFIRMED, conf comment stale** |
| Compose maps core-java as range 9090-9091 (#671) | The **running** container has `0.0.0.0:9090->9090/tcp`. Docker picked 9090 this time; browser-side calls work | **OK today, still a latent hazard** |

**Measured coverage baselines (new — none of these existed in the roadmap):**

| Tier | Metric | Today | How |
|---|---|---|---|
| Java — `core-java:test` only (testcontainers **excluded**) | INSTRUCTION / BRANCH / LINE / METHOD | **62.57% / 51.09% / 62.12% / 65.01%** | JaCoCo 0.8.12 via a throwaway `--init-script`; read from `build-local/reports/jacoco/test/jacocoTestReport.csv` |
| Java — `test` **+ `integrationTest`** aggregated | INSTRUCTION / BRANCH / LINE / METHOD | **88.07% / 71.95% / 87.55% / 87.53%** | Same, with `executionData` over both `.exec` files. `integrationTest` measured **607 tests, 0 failures, 1 skipped, 132 classes** |
| Go (`edge-go`) | statements | **66.8%** total (cmd/edge 49.8, docs 0.0, auth 88.6, core 80.0, middleware 79.8, whatsapp 92.6) | `go test -coverprofile` + `go tool cover -func` |
| Jest (frontend) | Stmts / Branch / Funcs / Lines | **63.76% / 57.06% / 60.71% / 65.10%** | `npx jest --coverage --coverageReporters=text-summary` (120 suites / 1230 tests — exactly matches `docs/metrics.json`) |
| mcp-server (vitest 4) | — | **no coverage provider installed** (`@vitest/coverage-v8` absent) | `mcp-server/package.json` |

---

## Standard Stack

Everything this phase needs is **already installed**. The strong recommendation is to add **zero
new packages**.

### Core (already present, verified in this session)

| Tool | Version | Purpose | Why it is the right one |
|------|---------|---------|--------------------------|
| `@playwright/test` | **1.62.1** `[VERIFIED: npx playwright --version]` | E2E, and the `request` fixture that is the whole coverage fix | `APIRequestContext` is measurably not intercepted by `context.route` — that immunity is the property the phase needs |
| `next` | **16.3.3** installed, `^16.3.2` declared `[VERIFIED: require('next/package.json')]` | App Router SSR | Bumped today by #679/#681 |
| `eslint` + `eslint-config-next` | 9 / **16.3.2** `[VERIFIED: package.json + --print-config]` | `react-hooks/set-state-in-effect` at severity **2** | The "resurrected gate" already exists and already fails; only the 4 suppressions remain |
| **JaCoCo** | **0.8.12** `[VERIFIED: resolved + executed locally under Gradle 8.10.2 / JDK 21]` | Java coverage | Gradle **core** plugin (`plugins { jacoco }`) — no third-party plugin, no version to choose, no supply-chain surface |
| `go tool cover` | Go 1.26 stdlib `[VERIFIED: go tool cover -func ran]` | Go coverage | The profile is already generated; only a consumer is missing |
| Jest | 29.7.0 `[VERIFIED: package.json]` | Frontend coverage | `coverageThreshold` is built-in config, no package |
| `node:http` | Node 24 stdlib | An optional SSR fixture server | Avoids adding `msw` for ~40 lines of handler |

### Supporting (already present)

| Module | Purpose | When to use |
|--------|---------|-------------|
| `lib/storefront-server.ts` | The three-valued SSR loader + `react.cache` per-request memoisation | Every new SSR conversion — extend it, do not write a second loader |
| `e2e/helpers/public-surface.ts` | `SHOP` / `PRODUCTS` fixtures, `stubPublicApi`, `resolveStorefrontPath`, `openStorefront` | The single fixture source. An SSR fixture server should be fed from **these same constants**, not a copy |
| `e2e/storefront-ssr-seo.spec.ts` | `servedHtml()` + `countOf()` — the raw-HTML assertion | The exemplar to generalise for Pattern 1 |
| `scripts/gates/e2e-skip-budget.conf` + `check-e2e-skip-budget.sh` | Default-deny + stale-entry-fails + VOID-on-unknown | The idiom to copy for a new SSR-route manifest gate |
| `scripts/check-gate-enforcement.sh` | Every `check-*.sh` must run in CI or be declared | **Any new gate must satisfy this or the build goes red** |

### Alternatives considered

| Instead of | Could use | Tradeoff |
|------------|-----------|----------|
| `node:http` fixture server | `msw` + `setupServer` in a Next instrumentation hook | Adds a dependency and requires `instrumentation.ts` wiring into the production server. Higher blast radius for identical benefit. **Rejected.** |
| Gradle core `jacoco` | `com.github.kt3k.coveralls`, `jacoco-report-aggregation` | Third-party supply-chain surface for a report this phase can read from CSV in five lines of Python. **Rejected.** |
| `@vitest/coverage-v8` for mcp-server | — | A genuinely new npm dependency, and #110/criterion 4 names only JaCoCo/Go/Jest. **Recommend explicit N/A with a reason** rather than silent omission (CLAUDE.md's roster rule). |
| Converting all 21 mount-fetch routes | Converting 0 and shipping only the pattern + guard | The criteria demand a *pattern*, not the conversions. See *Open Question 2*. |

**Installation:** none required.

```bash
# Java coverage — a CORE Gradle plugin, no dependency block
# core-java/build.gradle.kts:  plugins { ... jacoco }

# Go coverage — stdlib, already generating the profile
go tool cover -func=coverage.out

# Jest — config only
# frontend/jest.config.js:  coverageThreshold: { global: { ... } }
```

---

## Package Legitimacy Audit

**No external packages are recommended by this research.** Every tool named above is either already
in `package.json` / `go.mod`, or is a core plugin of a build tool already in use.

| Package | Registry | Age | Downloads | Source Repo | slopcheck | Disposition |
|---------|----------|-----|-----------|-------------|-----------|-------------|
| *(none)* | — | — | — | — | — | — |

**slopcheck was NOT available in this session.** `pip install slopcheck` was **blocked** by this
machine's `block-base-python.py` hook (no `.conda-env` is declared for this repo, and the hook has
no bypass by design). Per the machine's own rule that a blocked command is the answer, no reroute was
attempted.

**Consequence for the planner:** if the plan introduces **any** new package — the most likely
candidate is `@vitest/coverage-v8` for mcp-server — it must be tagged `[ASSUMED]` and gated behind a
`checkpoint:human-verify` task before install. The zero-new-package path above avoids the gate
entirely and is the recommendation.

---

## Architecture Patterns

### System architecture — how a request reaches content, and where each test instrument can see

```
                        ┌──────────────────────────────────────────────────────────┐
                        │                    BROWSER                               │
  user / crawler ──────►│  1. HTML parse   2. hydrate   3. island effect ──fetch──┐│
                        └───────────────▲──────────────────────────────────────┬──┘│
                                        │                                      │   │
   Playwright page.goto ────────────────┘                    context.route ────┘   │
   (SEES BOTH PATHS)                                         (SEES ONLY THIS ONE) ─┘
                                        │
                                        │ HTTP
                                        ▼
        ┌───────────────────────────────────────────────────────────────────────┐
        │              NEXT SERVER  (:3000, output: standalone)                 │
        │                                                                        │
        │  middleware.ts ──► x-nonce ──► app/layout.tsx (dynamic="force-dynamic")│
        │                                     │                                  │
        │                    ┌────────────────┴─────────────────┐                │
        │                    ▼                                  ▼                │
        │        SERVER page.tsx                       CLIENT page.tsx           │
        │        loadShopList / loadShopDetail         (renders shell only;      │
        │          │                                    fetch happens in the     │
        │          │  fetch(CORE_API_INTERNAL_URL)      browser, step 3 above)   │
        │          ▼                                                             │
        │     ┌────────────────────┐                                             │
        │     │  ok  │ notfound │ defer                                          │
        │     └───┬──────┬─────────┬──┘                                          │
        │         │      │         └────► hand to the island (browser fetches)   │
        │         │      └──────────────► notFound() → 404                       │
        │         └─────────────────────► seed the island, suppress mount fetch  │
        └────────────────────────────────┬──────────────────────────────────────┘
                                         │
   Playwright request.get() ─────────────┘   ◄── NOT intercepted by context.route
   (SEES ONLY THE SERVER PATH — this is the coverage instrument)
                                         │
                                         ▼
                        ┌────────────────────────────────┐
                        │  CORE API :9090 (Postgres RLS) │
                        └────────────────────────────────┘
```

The two arrows on the left are the whole phase. `page.goto` sees the union of both render paths and
therefore cannot tell them apart; `request.get` sees only the server path and therefore can.

### Pattern 1 — The raw-HTML coverage assertion (the answer to criterion 1)

**What:** assert server-rendered content against the *bytes the server returned*, using Playwright's
`request` fixture (or `page.request`), never against the DOM.

**Why it cannot silently drop coverage — MEASURED, both directions, 2026-08-28:**

```
Playwright 1.62.1, live stack. context.route("**/shop", fulfill 61-byte stub) registered, then:
  A  request fixture   .get("/shop")   stubbed? false   bytes 54190   real content? true
  B  page.goto("/shop")                stubbed? true    bytes 61        <-- POSITIVE CONTROL: the stub IS live
  C  context.request   .get("/shop")   stubbed? false   bytes 54190   real content? true
```

B is the control that proves the stub was registered and working. A and C bypass it entirely.

**And the vacuous pass it replaces — REPRODUCED IN ONE RUN, 2026-08-28.** Against a stack-free Next
server (`CORE_API_INTERNAL_URL=http://127.0.0.1:59999`, reproducing the CI condition) with only the
existing `stubPublicApi` browser stub:

```
DOM   : article cards = 3, h1 = "Test Kitchen"          <-- every DOM assertion holds
RAW   : bytes = 39245, "Test Kitchen" occurrences = 0   <-- the server rendered nothing
```

That is #542's complaint, demonstrated, with the fix visible in the same output.

**When to use:** any route whose `page.tsx` is not `"use client"` *and* which loads data
server-side.

**Example (the in-repo exemplar to generalise — `e2e/storefront-ssr-seo.spec.ts:33`):**

```typescript
// Source: frontend/e2e/storefront-ssr-seo.spec.ts (in-repo, shipped)
/** The raw response body — no browser, no hydration, no waiting. */
async function servedHtml(request: APIRequestContext, path: string): Promise<string> {
  const res = await request.get(path)
  expect(res.status(), `${path} should serve 200`).toBe(200)
  return res.text()
}

// The assertion carries its own pre-fix number, so it is falsifiable by construction.
expect(
  countOf(html, "Brixton Village Grill"),
  "the shop's name appeared 0 times in the served HTML before #507"
).toBeGreaterThan(0)
```

**Two properties worth copying verbatim:** the assertion message *states the pre-fix measurement*,
and the block is tagged `@desktop-only` because served bytes do not vary with viewport — so it is
excluded from the mobile project's *enumeration* rather than skipped at runtime.

### Pattern 2 — The SSR-route manifest gate (#542 option 4, made falsifiable)

**What:** `scripts/gates/ssr-routes.conf` + `scripts/check-ssr-coverage-contract.sh`, built on the
exact idiom of `check-e2e-skip-budget.sh`.

**Assertions:**

- **R-1 (default-deny):** every `frontend/app/**/page.tsx` that is *not* a client component and
  that loads data server-side must appear in the manifest. A **newly converted route that nobody
  declared fails**, which is the moment #542 says the failure must fire.
- **R-2 (no stale entries):** every manifest entry must name a spec file that exists and that
  contains a `request.get(` for that route. A retired route or a deleted assertion goes red —
  same contract as the skip budget's S-3 and `check-changelog-contract`'s C-2.
- **R-3 (self-test, both directions):** the classifier must be shown to classify a known server
  page as SERVER and a known client page as CLIENT in the same run, so "all declared" cannot be
  reached by a classifier that silently stopped working.
- **VOID (exit 2)** on missing `jq`, an unparseable directive, or a **zero-length** discovery
  result. "Found nothing" is never "clean".

**Classifier — use the strict form, not `head -3`, and not `git grep`.** Strip leading comments and
blank lines, then require the first statement to be the `"use client"` directive. Validated today:
across all 58 `page.tsx` + `layout.tsx` files the strict parser and `head -3` **agree 0/58
mismatches**, and no file has the directive preceded by comments — so `head -3` is correct *today*
and would break on the first file with a licence header. `git grep -l '"use client"'` is already
wrong (see Pitfall 1).

### Pattern 3 — Server seed → client island (the conversion idiom, already shipped)

**What:** the server page loads via `lib/storefront-server.ts`, passes `initial` to a `"use client"`
island, and the island seeds its state from it and one-shot-suppresses the mount fetch.

```typescript
// Source: frontend/app/shop/shop-discovery-client.tsx:210-311 (in-repo, shipped)
// Server-seeded content is not "loading": swapping real HTML for a skeleton on
// hydration is exactly the layout shift this change exists to remove.
const [loading, setLoading] = useState(initial === null)

// The server already answered for (page 0, initialQuery). One-shot, so the
// mount effect does not immediately refetch what is already on screen — but
// any later page or query change still fetches normally.
const serverSeeded = useRef(initial !== null)

useEffect(() => {
  if (serverSeeded.current) {
    serverSeeded.current = false
    return
  }
  fetchShops()
}, [fetchShops])
```

**Three non-obvious requirements the shipped code documents:**

1. `loading` must start **false** when seeded, or hydration swaps real HTML for a skeleton — the
   exact CLS the conversion exists to remove.
2. Anything the server *derived* must travel with the data. `/shop` passes
   `initialInterpretation` (parsed from the `X-Search-Interpretation` response header) because the
   suppressed mount fetch would otherwise never correct a stale heading.
3. The three-valued return is load-bearing: `defer` (429/5xx/DNS/timeout) must not be collapsed
   into `notfound`, or a transient outage renders as an authoritative "shop not found".

**Authenticated variant** (`app/shop/orders/page.tsx`): read the HttpOnly cookies with
`await cookies()`, render the sign-in wall **from the server**, and rely on the root layout's
`dynamic = "force-dynamic"` + `cookies()`-is-dynamic so no per-customer render can be cached.

### Pattern 4 — Removing a `setState`-in-effect without suppressing it

**Measured rule behaviour, 2026-08-28, with both controls firing** (`npx eslint --stdin`):

| Shape | `react-hooks/set-state-in-effect` | Verdict |
|---|---|---|
| A. synchronous `setState` in the effect body | **1 error, rc=1** | POSITIVE CONTROL — the defect shape |
| B. `setState` passed to `.then()` | 0, rc=0 | a promise continuation is not flagged |
| C. `setState` inside a **called** local async fn | **1 error, rc=1** | **the rule traces into the call graph** |
| D. `useSyncExternalStore` (with `getServerSnapshot`) | 0, rc=0 | sanctioned fix |
| E. derive during render | 0, rc=0 | sanctioned fix |
| F. clean file | 0, rc=0 | NEGATIVE CONTROL |

Row C is why `hooks/use-customer-session.ts:35` is flagged even though its `setProfile` lives inside
`checkSession`'s async body — and it rules out any "move it into a helper" non-fix.

**Site-by-site recommendation:**

| Site | Reads | Recommended fix | Risk |
|---|---|---|---|
| `components/dashboard/sidebar.tsx:63` | `localStorage["theme"]` + `matchMedia` | `useSyncExternalStore` with `getServerSnapshot: () => false`. Keep the `classList.toggle` side effect in an effect — only the `setDark` must move. | LOW |
| `components/dashboard/mobile-tab-bar.tsx:64` | `document.documentElement.classList.contains("dark")` | **Same shared hook.** These two components currently share theme state *through a DOM class*, with an implicit ordering dependency on the sidebar's effect. Extracting one `useTheme()` removes both suppressions **and** the coupling — the #457 precedent (extract, do not copy). | LOW-MED |
| `app/shop/auth/callback/page.tsx:18` | `searchParams.get("code")` | Derive during render: `const code = searchParams.get("code")`; return the error branch directly. No state at all for the missing-code case. The async `handleCallback(...).then(...)` branch stays and is **not** flagged (row B). | LOW |
| `hooks/use-customer-session.ts:35` | `await getCustomerSession()` (server truth) | `useSyncExternalStore` over a small session-store module, whose `subscribe` keeps the existing focus / visibilitychange / storage listeners and the 5×1s post-OAuth poll. | **HIGH** — two consumers (`StorefrontNav`, `PublicHeader`), and #465's single-flight refresh + rotation contract sits underneath. Give it its own task and its own falsification. |

`useSyncExternalStore` **must** be given a `getServerSnapshot`, or it throws during SSR — and with
`dynamic = "force-dynamic"` app-wide, every one of these renders on the server on every request.

### Anti-patterns to avoid

- **Counting `"use client"` with `git grep -l`.** It matches prose. Four already-converted pages
  are in today's count of 25 *because they document their own conversion*. The better the fix is
  documented, the higher the "unfixed" counter reads.
- **Asserting SSR content through the DOM.** `page.goto` + `expect(locator)` passes identically on
  a client-rendered tree, because the client fetch fills the DOM and Playwright waits. Measured on
  the pre-#507 tree: ~2.5s to the heading, and the assertion still green.
- **Changing the mobile project viewport to satisfy #286.** It would silently move every mobile
  spec, the `mobile-instrument-contract` assertions and every mobile perf baseline. Additive
  extension only.
- **A runtime `test.skip(project !== "x")`.** `playwright.config.ts` says it directly: a skip must
  mean *"nobody checked this"*; it cannot also mean *"not applicable here"* and stay useful. Use
  `@desktop-only` / `@mobile-only` + `grepInvert`.
- **Raising a threshold until the tree is green.** `perf-budgets.ts` states the repo's position:
  a budget nobody can defend gets `|| true` appended the first time it goes red.
- **Aggregating JaCoCo across the `test` and `integrationTest` CI jobs in this phase.** They are
  different jobs and the integration job is path-filtered and reports SUCCESS while skipping — a
  gate that depends on it would be wrong on exactly the runs that skip. See Pitfall 5.

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---------|-------------|-------------|-----|
| Stubbing the API for the SSR path | A bespoke mock inside `next.config.mjs`, or a second fixture set | Point `CORE_API_INTERNAL_URL` at a `node:http` fixture server fed from `e2e/helpers/public-surface.ts`'s existing `SHOP`/`PRODUCTS` constants | `CORE_API_INTERNAL_URL` is a **runtime** lookup (`NEXT_PUBLIC_*` is inlined into the server bundle at build time — measured, documented in `docs/CHANGELOG.md:1821`). No rebuild needed. Two fixture sets is how "did this storefront load" got two definitions once already. |
| Deciding whether a page is a client component | `git grep`/`rg` on `"use client"` | A strict directive parser (strip leading comments, require the first statement) | Validated 0/58 mismatch against `head -3`; `git grep` is measurably wrong by 4 |
| A skip/exemption registry | A fresh design | Clone `check-e2e-skip-budget.sh` + its `.conf` | It already has default-deny, stale-entry-fails, a both-directions matcher self-test, and VOID-on-unparseable |
| Java coverage | A third-party Gradle plugin | Core `plugins { jacoco }` (0.8.12 under Gradle 8.10.2) | Ran successfully on this tree today; zero supply-chain surface |
| Reading a JaCoCo report | Parsing HTML | `reports { csv.required.set(true) }` and sum the CSV columns | Five lines; XML also available for CI annotation |
| Go coverage threshold | A coverage service | `go tool cover -func=coverage.out \| tail -1` and compare | The profile is already being produced |
| Frontend coverage threshold | A script | Jest's built-in `coverageThreshold` | It exits non-zero itself |
| Theme state shared between sidebar and tab bar | Reading each other's DOM classes | One `useTheme()` on `useSyncExternalStore` | Removes two suppressions and an implicit mount-ordering dependency |

**Key insight:** every custom solution this phase might reach for already exists in this repo,
paid for by a recorded incident. The phase's value is generalisation and wiring, not invention.

---

## Common Pitfalls

### Pitfall 1 — `git grep -l '"use client"'` counts prose, and gets *worse* as the work gets done

**What goes wrong:** #542's headline "25 conversions queued" is inflated by 4.
**Why:** `git grep -l` matches the string anywhere, including the explanatory docblocks that
`app/shop/page.tsx`, `app/shop/[slug]/page.tsx`, `app/shop/orders/page.tsx` and
`app/unsubscribe/page.tsx` each carry *about their own conversion*.
**How to avoid:** classify by directive position. Any acceptance criterion phrased as "the
`"use client"` count drops to N" is vacuous in the wrong direction — the counter can *rise* when a
conversion is well documented.
**Warning signs:** a "remaining work" counter that does not fall when work lands.

### Pitfall 2 — The stack-free CI gate covers the *deferred* path, never the SSR path

**What goes wrong:** the per-PR `frontend-e2e` job sets only `NEXT_PUBLIC_API_URL`, so the SSR fetch
falls through to an unreachable `localhost:9090` → `getJson` catches → `defer` → the island fetches
→ the browser stub answers → green.
**Measured today** (stack-free `:3105`, `CORE_API_INTERNAL_URL` unreachable):

| Server | Route | bytes | shop-name occurrences | `<h1` |
|---|---|---:|---:|---:|
| `:3000` stacked | `/shop` | 54,184 | **5** | 1 |
| `:3000` stacked | `/shop/brixton-village-grill` | 90,951 | **33** | 1 |
| `:3105` stack-free | `/shop` | 39,438 | **0** | 1 |
| `:3105` stack-free | `/shop/brixton-village-grill` | 39,299 | **0** | 1 |

The `<h1` control is present in all four, so the 0s are about the **content**, not a dead server.
Note the stack-free slug page returns **200, not 404** — `defer`, not `notfound` — which is exactly
what lets the browser stub fill it.
**How to avoid:** either give the stack-free job an SSR fixture server, or record explicitly which
public surfaces its green does and does not cover (#542's own third acceptance criterion).

### Pitfall 3 — A skip that means "not applicable here" reads as unverified surface forever

**What goes wrong:** `onboarding-blocked-flow.spec.ts:116` is
`test.skip(testInfo.project.name !== "desktop", ...)`. It has been counted as one of the 7
unverified-surface skips, and both #547 and the conf attribute it to a missing fixture.
**Why:** the fixture check is at `:171`, **after** the project pin at `:116`, so it never runs on
mobile — and on desktop the test **passes**, proving the fixture is present.
**How to avoid:** `@desktop-only` in the title + the existing `grepInvert`, which stops the mobile
project *enumerating* it. Then delete the `ALLOW` and lower `MAX_SKIPS`.
**Warning signs:** a skip whose annotation contains the words "pinned", "not applicable",
"desktop only" or "covered elsewhere".

### Pitfall 4 — The nightly report and the skip-budget gate are currently out of sync with `main`

**What goes wrong:** `scripts/check-e2e-skip-budget.sh --from-nightly` will **VOID** today.
**Measured:** nightly `33142364550` stamped `specDigest=eab59e77…`; the tree computes
`2f2a7752…`. They differ because #670 edited a spec after that nightly ran.
**Consequence:** the phase cannot use a downloaded nightly report as evidence. It needs a fresh
nightly (or a local full-suite run) **after** its own spec edits — and **every** spec edit this
phase makes, including adding an `@desktop-only` tag, changes the digest again.
**How to avoid:** sequence the full-suite run as the last step, not the first.

### Pitfall 5 — A JaCoCo threshold from `test` alone understates real coverage by 25 points

**What goes wrong:** `test` **excludes** the `testcontainers` tag by default
(`build.gradle.kts:183-186`) and `integrationTest` runs **only** that tag. Both use
`sourceSets["test"]`. A threshold calibrated on `test` alone is therefore calibrated on roughly
two-thirds of the suite.

**Measured today — this is the whole reason the pitfall matters:**

| Counter | `test` only | `test` + `integrationTest` | Delta |
|---|---:|---:|---:|
| INSTRUCTION | 62.57% | **88.07%** | +25.50 |
| BRANCH | 51.09% | **71.95%** | +20.86 |
| LINE | 62.12% | **87.55%** | +25.43 |
| METHOD | 65.01% | **87.53%** | +22.52 |

`integrationTest` ran **607 tests, 0 failures, 1 skipped** across 132 classes on this tree, and
carries **+25.4 points of line coverage**. A "60% line" gate on `test` alone would be satisfied with
~2 points of headroom against a codebase that is actually at 87.55% — it could never catch a real
regression, and it would publish a number that is wrong by a quarter of the codebase.

**And the two halves run in different CI jobs:** `ci-cd.yaml:63` (`test`) and `:232`
(`integrationTest`), the second of which is **path-filtered and reports SUCCESS while skipping**.

**How to avoid — pick one, explicitly, and say which in the gate's header:**

1. **Aggregate (recommended).** Upload `build-local/jacoco/test.exec` as an artifact from job 1,
   download it in the integration job, and run a `JacocoReport` whose `executionData` covers both.
   Treat a **skipped** integration job as **VOID (exit 2)**, never as a pass — "could not measure"
   is not "measured and fine". Threshold from the 87.55% baseline.
2. **Unit floor only.** Gate `jacocoTestReport` in job 1 at the 62.12% baseline, and state in the
   script's own header that it is a *unit-coverage floor and not total coverage*, with the measured
   aggregate quoted so nobody mistakes one for the other.

Option 1 is the honest number; option 2 is the cheap one. Do not ship option 2 while *describing*
it as the project's coverage.

### Pitfall 6 — `eslint .` has no `--max-warnings`, so 34 warnings are invisible

**Measured today:** `npx eslint .` → **rc=0, `✖ 34 problems (0 errors, 34 warnings)`**.
`react-hooks/exhaustive-deps` is at severity **1**. So the gate catches `set-state-in-effect` (2)
and nothing at warn level. Recorded so nobody assumes a broader guarantee than exists — and so the
recorded trap *"eslint's last line is the FIXABLE count, not the verdict"* is not repeated.

### Pitfall 7 — An e2e *helper* escapes the base-URL contract gate

`scripts/check-e2e-baseurl-contract.sh` scans `*.spec.ts` only — `e2e/helpers/public-surface.ts`
says so in its own docblock, which is why it navigates with relative paths. A fixture server whose
port or origin is declared in a helper would sit **outside** that gate. Declare such constants in a
place the gate can see, or extend the gate's scan.
**Measured today:** the gate passes — 22 specs scanned, 14 local fallbacks, 0 divergent.

### Pitfall 8 — `docker compose` maps core-java as a port **range**

`docker-compose.full-stack.yml` publishes `9090-9091:9090` while the frontend bundle bakes
`localhost:9090` (#671). If Docker picks 9091, browser-side calls die silently and every live
measurement is wrong. **Measured today:** the running container has `0.0.0.0:9090->9090/tcp`, so
today's measurements are valid. Re-check `docker ps` before trusting any future live-stack number.

---

## Code Examples

### Criterion 1 — the falsifiable coverage assertion (fail direction executed)

```typescript
// PASS direction (live stack): 33 occurrences.
// FAIL direction (stack-free :3105): 0 occurrences — executed 2026-08-28.
const html = await servedHtml(request, "/shop/brixton-village-grill")
expect(
  countOf(html, "Brixton Village Grill"),
  "served HTML carried 0 occurrences of the shop name on the stack-free server"
).toBeGreaterThan(0)
```

```bash
# The fail arm, reproducible without touching the tree:
cd frontend && CORE_API_INTERNAL_URL=http://127.0.0.1:59999 \
  NEXT_PUBLIC_API_URL=http://localhost:9090 NEXTAUTH_URL=http://localhost:3105 \
  NEXTAUTH_SECRET=probe npx next start -p 3105 &
body=$(curl -s http://localhost:3105/shop); rc=$?
n=$(grep -o -F "Brixton Village Grill" <<< "$body" | wc -l)   # here-string: no pipefail inversion
echo "rc=$rc bytes=${#body} occurrences=$n"                   # measured: rc=0 bytes=39438 occurrences=0
```

### Criterion 2 — the ESLint gate, both arms (both executed)

```bash
cd frontend
# BREAK ARM — measured rc=1, "react-hooks/set-state-in-effect"
printf '%s\n' '"use client"' 'import { useEffect, useState } from "react"' \
  'export function A(){ const [v,setV]=useState(0); useEffect(()=>{ setV(1) },[]); return <p>{v}</p> }' \
  | npx eslint --stdin --stdin-filename components/__probe__.tsx; echo "rc=$?"

# CLEAN ARM — measured rc=0, 0 errors / 34 warnings
out=$(npx eslint .); rc=$?; echo "rc=$rc"
```

`--stdin` is deliberate: it needs no file written to the tree, so there is no restore to verify.

### Criterion 5 — homing the one unowned skip

```typescript
// frontend/e2e/onboarding-blocked-flow.spec.ts — title gains the tag; the runtime
// project pin at :116 is then deleted, because grepInvert stops the mobile project
// ENUMERATING the block at all. A skip must mean "nobody checked this".
test("bad company number -> fix inline -> re-run checks -> honest in-review @desktop-only", ...)
```

```
# scripts/gates/e2e-skip-budget.conf
- ALLOW onboarding-blocked-flow.spec.ts     <-- delete (S-3 fails on a stale ALLOW)
- MAX_SKIPS 8
+ MAX_SKIPS 6                               <-- 4 stomp-relay + 2 vendor-refund
```

Fail direction: keep `MAX_SKIPS 8` and the gate still passes (7 ≤ 8) — so **lowering the ceiling is
what makes the criterion falsifiable**. Verify by re-adding the ALLOW after the tag lands and
confirming S-3 goes red on the now-unmatched entry.

### Criterion 4 — the three coverage consumers

```kotlin
// core-java/build.gradle.kts
plugins { /* … */ jacoco }

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports { xml.required.set(true); csv.required.set(true); html.required.set(true) }
}
tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    // Baselines MEASURED 2026-08-28 (Pitfall 5 — pick one and say which):
    //   `test` only .................. INSTR 62.57  BRANCH 51.09  LINE 62.12  METHOD 65.01
    //   `test` + `integrationTest` ... INSTR 88.07  BRANCH 71.95  LINE 87.55  METHOD 87.53
    // The numbers below are the AGGREGATE floor, so `executionData` must cover both .exec
    // files and a SKIPPED integration job must VOID (exit 2), never pass.
    // No-regression guardrails set just below the measurement — not targets.
    violationRules {
        rule { limit { counter = "LINE";   value = "COVEREDRATIO"; minimum = "0.85".toBigDecimal() } }
        rule { limit { counter = "BRANCH"; value = "COVEREDRATIO"; minimum = "0.69".toBigDecimal() } }
    }
}
```

```bash
# edge-go — consume the profile that is already generated. Measured today: 66.8%.
total=$(go tool cover -func=coverage.out | awk '/^total:/ {gsub(/%/,"",$3); print $3}'); rc=$?
[ "$rc" -eq 0 ] || { echo "VOID: go tool cover failed"; exit 2; }
[ -n "$total" ] || { echo "VOID: no total line — empty or unparseable profile"; exit 2; }
awk -v t="$total" -v min=65 'BEGIN { exit !(t+0 >= min) }' \
  || { echo "FAIL: edge-go coverage ${total}% < ${min}%"; exit 1; }
```

```javascript
// frontend/jest.config.js — baseline MEASURED 2026-08-28:
//   Stmts 63.76%  Branch 57.06%  Funcs 60.71%  Lines 65.10%
coverageThreshold: {
  global: { statements: 61, branches: 54, functions: 58, lines: 62 },
},
```

Every threshold above sits **below** its measurement, deliberately. Each must be falsified by
raising it above the measured value once and observing red.

---

## Runtime State Inventory

Not a rename phase, but this phase invalidates several pieces of state that live **outside** the
source tree and will not update themselves. Included because a plan that misses these ships a VOID
gate.

| Category | Items found | Action required |
|----------|-------------|-----------------|
| Stored data | **None** — verified: no migration, no schema change, no seeded row is touched by any criterion. | none |
| Live service config | **The nightly E2E report artifact.** `check-e2e-skip-budget.sh --from-nightly` compares `config.metadata.specDigest`; tree `2f2a7752…` vs nightly `eab59e77…` — **already mismatched today** because #670 landed after the last nightly. Every spec edit this phase makes invalidates it again. | Re-run the nightly (or a local full suite) **after** all spec edits, not before. |
| OS-registered state | **None** — verified: no Task Scheduler / systemd / pm2 registration references any artefact of this phase. | none |
| Secrets / env vars | `CORE_API_INTERNAL_URL` gains a **new consumer** if a fixture server is adopted (CI job `frontend-e2e` does not currently set it). `E2E_VENDOR_PASSWORD` / `KC_SEED_USER_PASSWORD` are already wired in the nightly. | Add `CORE_API_INTERNAL_URL` to the `frontend-e2e` job's `next start` env if Pattern 2's fixture server is adopted. |
| Build artifacts | **The Docker frontend image.** Any SSR conversion requires a rebuild before E2E — `docker compose start` does not rebuild. `core-java/build/` is stale; the live output dir is **`build-local`**. A JaCoCo report read from `build/` would be a stale-artifact read. | Rebuild + `scripts/check-runtime-freshness.sh`; read JaCoCo from `core-java/build-local/reports/jacoco/`. |
| Gate registry | `scripts/check-gate-enforcement.sh` is **default-deny**: a new `check-*.sh` that is neither referenced under `.github/workflows/` nor declared in `scripts/gates/gate-enforcement.conf` **fails**. Measured today: 36 gates, 6 exempt, rc=0. | Wire any new gate into CI in the same PR. |
| Doc metrics | `docs/metrics.json` — `playwright_blocks 113`, `playwright_specs 22`, `total 3188`. Two gates enforce it; both rc=0 today. Adding an `@desktop-only` **tag** does not change the static block count; adding a **spec file or test block** does. | `scripts/docs-freshness.sh --write`, then update the prose in README.md / AGENTS.md / CLAUDE.md. |

---

## State of the Art

| Old approach | Current approach | When changed | Impact on this phase |
|---|---|---|---|
| `"use client"` page + `useEffect` fetch | Server page + `lib/storefront-server.ts` + seeded island | #463 / #537 / #507, 2026-08-04 → 08-09 | `/shop`, `/shop/[slug]`, `/shop/orders` are done. The pattern exists; copy it. |
| `next lint` (removed in Next 16) | `eslint .` on a flat config, in CI job `lint` | #99 / PR #201 | The "resurrected gate" is already live at error severity. |
| Coverage asserted through the DOM | `request.get()` raw-HTML assertions | `storefront-ssr-seo.spec.ts`, 2026-08-04 | The exemplar for criterion 1. |
| Skip-budget freshness by **mtime** (`find -newer`) | Content digest (`specDigest` from `scripts/e2e-spec-digest.sh`) | `playwright.config.ts:5-23` | A digest mismatch is VOID, never a pass. Plan around it. |
| Runtime `test.skip(project !== …)` | `@desktop-only` / `@mobile-only` + `grepInvert` | #420 / #503 | The mechanism criterion 5 needs already exists. |
| `next build` type-checks the pages/app graph | `next build` type-checks the whole `tsconfig.build.json` program; a bare `tsc --noEmit` covers tests/e2e | next 16.3.x, #679/#681, **today** | Any new spec/test file this phase adds is type-checked by the bare `tsc` step and by `check-e2e-typecheck.sh`. |
| `middleware.ts` | `proxy.ts` (rename + function rename; codemod `@next/codemod@canary middleware-to-proxy`) | next 16.3.x — **warning only, no behaviour change** | Recommended **out of scope** — see Open Question 1. |

**Deprecated / outdated:**

- `git grep -l '"use client"'` as a conversion counter — measurably wrong by 4 today.
- The `e2e-skip-budget.conf` comment "Total = 8 (4 distinct tests × 2 projects)" — actual is 7.
- The conf's onboarding `ALLOW` justification ("needs a shop for the demo tenant") — the measured
  cause is the desktop project pin.
- The `--format compact` ESLint formatter — removed from core ESLint (encountered during this
  research; it exits rc=2, which is easily misread as a lint failure).

---

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker Compose stack (frontend, core-java, postgres, keycloak, minio, rabbitmq, redis, mailhog) | Live E2E + SSR measurement | Yes | all `healthy`, up 3-8h | — |
| core-java published on **9090** | Browser-side calls from the baked bundle (#671) | Yes | `0.0.0.0:9090->9090/tcp` | force-recreate the container |
| Node.js / npm | frontend build, Jest, Playwright | Yes | next 16.3.3, playwright 1.62.1 | — |
| Playwright chromium browser | E2E | Yes | probes ran | `npx playwright install chromium` |
| Go toolchain | Go coverage | Yes | `go test -coverprofile` + `go tool cover` ran | — |
| Gradle + JDK 21 | JaCoCo | Yes | Gradle 8.10.2, JaCoCo 0.8.12 resolved and executed | — |
| `jq` | skip-budget + report parsing | Yes | gate ran | — |
| `gh` CLI | nightly artifact download | Yes | `gh run download` succeeded | — |
| **`slopcheck`** | package legitimacy audit | **No** | — | **No fallback**: `pip install` is blocked by `block-base-python.py` (no `.conda-env` declared). Mitigated by recommending **zero new packages**. |
| **`ctx7` / Context7 MCP** | library docs | **No** | — | Used the **installed** `next@16.3.3` package's own bundled docs (`node_modules/next/dist/docs/…`), which is version-pinned and strictly better than a web search. |

**Missing with no fallback:** `slopcheck` — see the Package Legitimacy Audit for the consequence.
**Missing with fallback:** Context7 — replaced by version-pinned in-package documentation.

---

## Validation Architecture

### Test framework

| Property | Value |
|----------|-------|
| Frameworks | Jest 29.7.0 (frontend unit), Playwright 1.62.1 (E2E), JUnit 5 + Testcontainers (Java), `go test` (Go), vitest 4 (mcp-server) |
| Config files | `frontend/jest.config.js`, `frontend/playwright.config.ts`, `core-java/build.gradle.kts`, `mcp-server/vitest.config.ts` |
| Quick run (frontend unit) | `cd frontend && npx jest <path> --ci --watchAll=false` |
| Quick run (lint gate) | `cd frontend && npx eslint .` — **~measured rc=0** |
| Quick run (single spec) | `cd frontend && npx playwright test e2e/<spec>.spec.ts --project=desktop` |
| Full suite (frontend) | `cd frontend && npm test -- --ci --watchAll=false` — 120 suites / 1230 tests / **12.3s** |
| Full suite (E2E) | `cd frontend && npx playwright test --reporter=json > e2e-artifacts/report.json` (needs the stack + `E2E_VENDOR_PASSWORD`) |
| Full suite (Java unit) | `./gradlew :core-java:test --no-daemon` |
| Gate sweep | `bash scripts/check-*.sh` — 36 gates, 6 declared runtime-exempt |

### Phase requirements → test map

| Req | Behaviour | Test type | Automated command | Exists? |
|---|---|---|---|---|
| TRUTH-01 | A stubbed SSR route cannot satisfy the coverage assertion | e2e | `npx playwright test e2e/storefront-ssr-seo.spec.ts` + the `:3105` stack-free fail arm | ✅ spec exists; ❌ **fail arm not automated** — Wave 0 |
| TRUTH-01 | A newly server-rendered route with only a browser stub fails CI | gate | `bash scripts/check-ssr-coverage-contract.sh` | ❌ Wave 0 |
| TRUTH-01 | `react-hooks/set-state-in-effect` fires on a reintroduced site | lint | `printf … \| npx eslint --stdin --stdin-filename components/__probe__.tsx` (rc must be 1) | ✅ **measured rc=1 today** |
| TRUTH-01 | The 4 `#99 follow-up` suppressions are gone | grep | `rg -uu -c 'refactor tracked in issue #99 follow-up' frontend/` → must be **0**, and the pre-change value **4** must be recorded | ✅ instrument validated (4 today) |
| TRUTH-01 | Theme toggling, sidebar state, storefront session pill and the OAuth callback error path still behave | e2e | `npx playwright test e2e/dashboard-mobile.spec.ts e2e/public-layout.spec.ts` + a landing-destination pass with a stale cookie | ⚠️ partial — the session-pill and callback-error paths need new blocks (Wave 0) |
| TRUTH-02 | Java unit coverage ≥ the declared floor | build | `./gradlew :core-java:jacocoTestCoverageVerification` | ❌ Wave 0 |
| TRUTH-02 | Go coverage ≥ the declared floor | build | `go tool cover -func=coverage.out \| awk '/^total:/…'` | ❌ Wave 0 |
| TRUTH-02 | Frontend coverage ≥ the declared floor | unit | `npm test -- --coverage --ci` | ❌ Wave 0 (config only) |
| TRUTH-02 | The 11-route dashboard sweep has no horizontal overflow at 375px | e2e | `npx playwright test e2e/dashboard-mobile.spec.ts` | ⚠️ 375px block exists for **1** route; extend the 11-route loop |
| TRUTH-02 | Skips = 6, every one declared, no stale ALLOW | gate | `bash scripts/check-e2e-skip-budget.sh` (needs a **fresh** report — see Pitfall 4) | ✅ gate exists; report currently VOID |

### Sampling rate

- **Per task commit:** `npx eslint .` + `npx tsc --noEmit` + the touched Jest file.
- **Per wave merge:** `npm test -- --ci` (12s), `./gradlew :core-java:test`, `go test ./...`,
  the full `scripts/check-*.sh` sweep.
- **Phase gate:** a full Playwright suite against a **rebuilt** stack, its digest matching the
  tree, `check-e2e-skip-budget.sh` rc=0, `check-runtime-freshness.sh` rc=0,
  `check-branch-behind-base.sh` rc=0, then `/gsd:verify-work`.

### Wave 0 gaps

- [ ] `scripts/check-ssr-coverage-contract.sh` + `scripts/gates/ssr-routes.conf` — TRUTH-01, plus
      its wiring into `.github/workflows/ci-cd.yaml` (else `check-gate-enforcement.sh` fails).
- [ ] An automated **fail arm** for the SSR coverage assertion (a stack-free `next start` on a
      spare port, or a second Playwright project with `CORE_API_INTERNAL_URL` unreachable).
- [ ] `core-java/build.gradle.kts` — `jacoco` plugin + `jacocoTestCoverageVerification` rules, plus
      the `.exec` artifact hand-off between CI jobs 1 and the integration job if the aggregate
      figure is gated (Pitfall 5).
- [ ] A Go coverage consumer step in `ci-cd.yaml` (fail-closed on empty/unparseable → exit 2).
- [ ] `frontend/jest.config.js` — `coverageThreshold`, and `--coverage` on the CI Jest step.
- [ ] Playwright blocks for the storefront session pill and the OAuth callback error path
      (#202's own acceptance list, currently uncovered).
- [ ] A fresh full-suite E2E run **after** all spec edits, to re-earn the skip-budget gate.

---

## Security Domain

`security_enforcement: true`, `security_asvs_level: 2`.

### Applicable ASVS categories

| ASVS category | Applies | Standard control |
|---------------|---------|------------------|
| V2 Authentication | no | No auth mechanism changes. `vendorLogin` in specs already uses committed dev-realm creds via `E2E_VENDOR_PASSWORD` / `KC_SEED_USER_PASSWORD`. |
| V3 Session Management | **yes** | An SSR loader has **no** token-refresh path — `lib/api-client.ts`'s single-flight interceptor is browser-only, and `auth.ts:96` can return `accessToken: undefined, error: "RefreshTokenError"`. Any authenticated SSR conversion must treat a missing/expired token as **`defer`**, never as an error and never as an empty result. |
| V4 Access Control | **yes** | An SSR fetch must forward **the caller's** bearer token (dashboard) or HttpOnly customer cookies (storefront) so Postgres RLS applies. A service account on the SSR path would bypass the tenant wall for every visitor. Precedent: `app/shop/orders/page.tsx`. |
| V5 Input Validation | **yes** | Server pages read `searchParams`. `?q=` can legally arrive repeated — `app/shop/page.tsx:67` takes the first rather than joining. Copy that handling into any new SSR page. |
| V6 Cryptography | no | Nothing cryptographic is introduced. |
| V7 Error Handling & Logging | **yes** | `getJson` never throws — a thrown error in a server component renders the route's error boundary and loses the page chrome. Preserve the never-throw contract. |
| V8 Data Protection | **yes** | Per-user SSR must never be cached into a shared render. Mitigated app-wide today: `app/layout.tsx:18` sets `dynamic = "force-dynamic"`, and `cookies()` is itself dynamic. **Any conversion that adds `revalidate` or removes `force-dynamic` reopens this.** |
| V14 Configuration | **yes** | A test-only SSR fixture server must be loopback-bound and started only by the test harness. `CORE_API_INTERNAL_URL` pointed at a fixture in a non-test environment would serve fixture data as real. |

### Known threat patterns for this stack

| Pattern | STRIDE | Standard mitigation |
|---------|--------|---------------------|
| SSR renders another tenant's/user's data into a cached page | Information Disclosure | `dynamic = "force-dynamic"` (already app-wide) + `cache: "no-store"` on every authenticated fetch + `react.cache` (per-request, cannot cross visitors) |
| SSR fetch uses a privileged/service identity instead of the caller's | Elevation of Privilege | Forward the caller's token; RLS is the enforcement point, the frontend never filters |
| Inline JSON-LD emitted without the CSP nonce → blocked, or the nonce omitted → CSP weakened | Tampering | Read `x-nonce` from `headers()`; **measured today:** `/shop` and `/shop/[slug]` each serve exactly **1** `ld+json` script tag and it **is** nonce'd. `e2e/csp-no-violations.spec.ts` covers `/`, `/for-operators`, `/shop/[slug]`, `/dashboard` — **`/shop` is not covered**; add it if that page's JSON-LD changes |
| A fixture server reachable outside the test harness | Spoofing | Bind to `127.0.0.1`, start/stop within the Playwright run, never reference it from committed non-test config |
| A coverage threshold lowered to go green | — (process) | Thresholds live in version control with the measured baseline in a comment; a lowering is visible in review, and the falsification (raise above the measurement → red) is recorded |
| Test credentials leaking into the repo | Information Disclosure | Already handled: `e2e/vendor-credentials.ts` has no committed default (the old `?? "password123"` was removed); gitleaks + `pii-guard` run in CI |

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|-------|---------|---------------|
| A1 | Adding `@desktop-only` to `onboarding-blocked-flow`'s title makes the mobile project stop enumerating it, taking skips 7 → 6 | Criterion 5 | LOW — the mechanism is documented in `playwright.config.ts:81` and already used by `storefront-ssr-seo.spec.ts`, but this exact test was **not** run with the tag applied |
| A2 | A no-regression threshold set just below today's measurement will not flake in CI | Criterion 4 | MED — CI coverage can differ from local (different JDK/Node minor, different test selection). **The plan must measure the baseline in CI once before fixing the number.** |
| A3 | The remaining 6 nightly failures are fully resolved by #670 and the next nightly will be green | Re-measurement ledger | MED — no nightly has yet run on the fixed tree. Do not build a criterion on "the nightly is green" without running one |
| A4 | The `middleware.ts` → `proxy.ts` codemod cannot be applied cleanly to a NextAuth `export default auth((req)=>…)` wrapper | Open Question 1 | LOW — inferred from the codemod's documented behaviour (renames the `middleware` function) plus the measured fact that `next-auth@5.0.0-beta.32` contains **zero** references to the proxy convention. The codemod was **not** run |
| A5 | `@vitest/coverage-v8` is the right provider if mcp-server coverage is ever added | Alternatives | LOW-MED — **not verified on any registry** and slopcheck was unavailable. Tagged `[ASSUMED]`; gate behind `checkpoint:human-verify` if adopted |
| A6 | A `node:http` fixture server pointed at by `CORE_API_INTERNAL_URL` will satisfy `lib/storefront-server.ts`'s expectations | Pattern 2 | MED — the env var precedence and its runtime-lookup nature are **verified**; a fixture server was **not** built or run. The `:3105` arm proved only the *unreachable* case |
| A7 | *(retired — measured, not assumed)* The aggregate is 87.55% line, +25.4 points over `test` only | Pitfall 5 | n/a — this row is kept so the retirement is visible rather than silently dropped |

---

## Open Questions

1. **Is the `middleware.ts` → `proxy.ts` migration in scope?**
   - **What we know:** next 16.3.3 emits `The "middleware" file convention is deprecated. Please use
     "proxy" instead.` It is a **`warnOnce` at build time only** — no behaviour change
     (`node_modules/next/dist/build/index.js:730`). The migration is a file + function rename via
     `npx @next/codemod@canary middleware-to-proxy .`. `frontend/middleware.ts` is
     `export default auth((req) => …)` — a NextAuth wrapper, not a named `middleware` export — and
     `next-auth@5.0.0-beta.32` contains **zero** references to `proxy`. The middleware also mints
     the CSP nonce that every SSR page's JSON-LD depends on.
   - **What's unclear:** whether the codemod handles a default-exported wrapper, and whether
     NextAuth v5 beta supports the `proxy` convention at all.
   - **Recommendation:** **OUT OF SCOPE, recorded as such.** It is a warning, not a break; it shares
     no criterion with this phase; and it touches the exact seam (`x-nonce`) that the SSR work
     depends on. File it as its own issue. Record the decision either way, per the phase's own rule.

2. **How many of #507's 21 remaining mount-fetch routes should this phase convert?**
   - **What we know:** criterion 1 asks for a **pattern**, not the conversions. The three
     highest-impact public routes are already done. #507's own ordering puts `/track` and
     `/shop/[slug]/orders/[orderNumber]` next, and explicitly says `checkout` and `auth/callback`
     are *"deliberately interactive and may be correct as client components — decide, do not convert
     reflexively"*. The 14 dashboard routes are authenticated, not SEO-relevant, and carry the V3/V4
     risks above.
   - **What's unclear:** whether the owner reads "pages stop fetching on mount" in the phase goal as
     a mandate for conversions.
   - **Recommendation:** ship the pattern + guard as the load-bearing deliverable; convert **zero to
     two** public routes as a demonstration of the pattern under the guard; list every route left as
     a client component **with a reason** (#507's own last acceptance criterion). Surface this to
     the owner in `/gsd:discuss-phase`.

3. **Should the fixture server (Pattern 2, #542 option 2) be built, or is the manifest gate
   (option 4) sufficient?**
   - **What we know:** the manifest gate is cheap, static, and fires at the moment of conversion —
     which is what #542's sequencing section asks for. The fixture server additionally makes the
     per-PR stack-free job cover the SSR path for real, closing Pitfall 2. Both are compatible.
   - **Recommendation:** the manifest gate is the **must**; the fixture server is a **should**,
     sequenced after it, and cleanly droppable if the phase runs long.

4. **RESOLVED during this research — JaCoCo including the Testcontainers suite is 87.55% line.**
   - **Measured:** `test` only 62.57 instr / 51.09 branch / 62.12 line / 65.01 method;
     `test` + `integrationTest` aggregated **88.07 / 71.95 / 87.55 / 87.53**. The integration
     suite ran 607 tests, 0 failures, 1 skipped, 132 classes, on this tree.
   - **What this changes:** Pitfall 5 is no longer a caveat, it is a first-order design
     constraint — a unit-only gate would be **25 points** below the real figure and incapable of
     catching a regression. The remaining decision is aggregate-across-jobs (honest, needs
     artifact passing + a VOID on the skipped integration job) vs unit-floor-with-a-disclaimer.
   - **Recommendation:** aggregate. Surface the choice in `/gsd:discuss-phase` since it decides
     whether the coverage gate is meaningful or decorative.

5. **Does #286 close, or narrow again?**
   - **What we know:** its `/dashboard/staff` half is satisfied; a 375px block already exists for
     one route; the 9 stubs are #542's complaint, not a separate one.
   - **Recommendation:** narrow #286 to *"the 11-route dashboard sweep asserts no horizontal
     overflow at 375px as well as usability at 390px"*, and let #542 carry the stubs. Do not close
     it whole (ISSUE-DISPOSITION.md says so explicitly).

---

## Sources

### Primary (HIGH confidence — measured on this tree / this stack, 2026-08-28)

- Live Compose stack (`docker ps`; core-java on `0.0.0.0:9090`) — SSR content probes for `/shop`,
  `/shop/[slug]`, `/track`, `/`, `/legal/*`, `/dashboard`, with a validated negative control.
- A stack-free `next start -p 3105` with `CORE_API_INTERNAL_URL=http://127.0.0.1:59999` — the fail
  arm of the SSR coverage assertion (0 occurrences vs 5/33 stacked; `<h1` control present in all).
- Playwright 1.62.1 route-scope probe — `context.route` vs `request` fixture vs `context.request`,
  with the stub's liveness proved by `page.goto` returning the 61-byte body.
- Playwright vacuous-pass reproduction — DOM 3 cards / raw HTML 0 occurrences, one run.
- `npx eslint --print-config` (17 `react-hooks` rules, `set-state-in-effect` = 2) and six
  `--stdin` rule-shape probes with both controls firing.
- `npx eslint .` on the real tree — rc=0, 0 errors / 34 warnings.
- Gradle 8.10.2 + JaCoCo 0.8.12 via `--init-script`, twice: `test` alone, then `test` +
  `integrationTest` aggregated over both `.exec` files. Reports read from
  `core-java/build-local/reports/jacoco/test/jacocoTestReport.csv`; the integration run's own
  JUnit XML (132 files) parsed for 607 tests / 0 failures / 1 skipped.
- `go test -coverprofile` + `go tool cover -func` in `edge-go`.
- `npx jest --coverage` — 120 suites / 1230 tests, cross-checked against `docs/metrics.json`.
- `gh run download 33142364550` — the nightly `report.json`, parsed with `jq` for counts,
  per-test skip annotations and `config.metadata.specDigest`.
- `scripts/e2e-spec-digest.sh`, `check-e2e-skip-budget.sh`, `check-doc-metrics.sh`,
  `docs-freshness.sh`, `check-gate-enforcement.sh`, `check-e2e-baseurl-contract.sh`,
  `check-playwright-mobile-contract.sh` — all executed.
- A strict `"use client"` directive parser vs `head -3` across all 58 `page.tsx`/`layout.tsx`
  (0 mismatches) and vs `git grep -l` (4 false positives, each named).
- `node_modules/next/dist/docs/01-app/03-api-reference/03-file-conventions/proxy.md` — the
  **installed** next 16.3.3's own bundled documentation for the proxy migration.
- In-repo source of record: `lib/storefront-server.ts`, `app/shop/page.tsx`,
  `app/shop/orders/page.tsx`, `app/shop/shop-discovery-client.tsx`, `e2e/storefront-ssr-seo.spec.ts`,
  `e2e/helpers/public-surface.ts`, `e2e/dashboard-mobile.spec.ts`,
  `e2e/onboarding-blocked-flow.spec.ts`, `playwright.config.ts`, `eslint.config.mjs`,
  `jest.config.js`, `core-java/build.gradle.kts`, `.github/workflows/ci-cd.yaml`,
  `.github/workflows/e2e-nightly.yml`, `scripts/gates/e2e-skip-budget.conf`.

### Secondary (MEDIUM confidence)

- GitHub issues #542, #507, #202, #286, #547, #110 via `gh issue view` — authoritative for intent,
  **not** for current numbers (four of their measurements are stale; each correction is in the
  ledger).
- `.planning/ISSUE-DISPOSITION.md`, `.planning/ROADMAP.md`, `.planning/STATE.md`,
  `docs/CHANGELOG.md` (the `CORE_API_INTERNAL_URL` build-time-inlining entry at :1821 and the
  stack-free `:3105` verification at :1574).

### Tertiary (LOW confidence — flagged, not relied upon)

- The behaviour of `@next/codemod middleware-to-proxy` on a NextAuth default-export wrapper —
  **not executed** (A4).
- `@vitest/coverage-v8` as the mcp-server provider — **not verified on any registry**, slopcheck
  unavailable (A5).

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|------|-------|--------|
| Re-measurement ledger | **HIGH** | Every row executed today; instruments carry positive and negative controls |
| Coverage baselines (Java unit + aggregate / Go / Jest) | **HIGH** | All measured by running the tools; the Java aggregate came from a completed 607-test Testcontainers run (0 failures) |
| SSR coverage pattern | **HIGH** | Fail direction and vacuous pass both reproduced in one session |
| ESLint gate + rule shapes | **HIGH** | Six probes, both controls fired |
| Skip-budget diagnosis | **HIGH** | Read from the nightly report's own annotation, not from prose |
| SSR fixture-server design | **MEDIUM** | Env-var seam verified; the server itself not built (A6) |
| `proxy.ts` migration | **MEDIUM** | Docs and next-auth surface verified; codemod not run (A4) |
| Architecture patterns | **HIGH** | All are shipped in-repo, quoted from source |

**Research date:** 2026-08-28
**Valid until:** 2026-09-11 (14 days). Shorter than the usual 30: `next` moved a minor version
**today**, the nightly has not yet run on the fixed tree, and the coverage baselines drift with
every merged test.
