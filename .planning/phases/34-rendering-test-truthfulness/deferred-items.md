# Phase 34 — deferred items

Out-of-scope discoveries made while executing. Each entry says what was measured,
why it was not fixed here, and what would close it.

---

## D-34-05-01 — the same unfalsifiable overflow shape survives in two other places

**Found during:** plan 34-05, Task 1 break arm A (2026-08-28).

**What was measured.** Under `isMobile: true` Chromium emulates a phone LAYOUT
viewport: content wider than the device width makes the page zoom out to fit, and
`window.innerWidth` GROWS to match the content. So

```
expect(docScrollWidth).toBeLessThanOrEqual(window.innerWidth + 1)
```

compares a number against itself and **cannot go red**. Proven on the live Compose
stack by appending a deliberate 1200px-wide div to `/dashboard/kitchen` at a 375px
pin, before the read:

```
{"docScrollWidth":1200,"bodyScrollWidth":1200,"innerWidth":1200,
 "htmlOverflowX":"visible","bodyOverflowX":"visible","injectedWidth":1200}
```

The sweep reported `1 passed`, rc=0, over an 825px overflow on a 375px phone.

**Fixed in 34-05:** only the new eleven-route block, which now compares against
`page.viewportSize()!.width` (the configured width, which page content cannot move)
and additionally asserts the layout viewport did not widen. Same injection now
reads `expected: 376 / received: 1200`, rc=1.

**Still carrying the vacuous shape — NOT fixed here:**

1. `frontend/e2e/dashboard-mobile.spec.ts`, the single-route MOBL-01 375px block:
   `expect(geom.docScrollWidth).toBeLessThanOrEqual(geom.viewportWidth + 1)` where
   `viewportWidth` is `window.innerWidth`. Not touched because plan 34-05 says in
   terms: do not change the 390px block or any existing assertion. Its OTHER
   assertions (the 56px top-bar geometry, the escaping-element list) are genuinely
   falsifiable and were measured red on main — only this one line is affected.
2. `frontend/e2e/public-layout.spec.ts:110-113`, `horizontalOverflow()` =
   `document.documentElement.scrollWidth - window.innerWidth`, asserted
   `<= 1` at three sites (`:225`, `:248`, `:380`). The same mechanism applies
   **only under the mobile project** (`isMobile: true`); under the desktop project
   `innerWidth` is a fixed 1440 and the assertion is live. NOT verified in the fail
   direction — that is the work, and it should not be assumed either way.

**What would close it:** run the 1200px-div break arm against each site above,
record both directions, and switch the yardstick to `page.viewportSize()` wherever
the arm shows green. Small, mechanical, and outside 34-05's `files_modified`.

**Why it was not done here:** `frontend/e2e/public-layout.spec.ts` is not in this
plan's files, and the MOBL-01 line is explicitly excluded by the plan's action text.
Both are recorded in `dashboard-mobile.spec.ts`'s own docblock so the next reader of
that file cannot miss it.

---

## From plan 34-07: check-gate-enforcement.sh cannot tell a `run:` line from prose

**Found:** 2026-08-28, while running the wiring arm for the new SSR-coverage gate.

**Measured, both directions.** `scripts/check-gate-enforcement.sh` decides "is this gate
wired?" by searching `.github/workflows/*` for the script's filename, and it does **not**
strip comments. With the `ops-contracts` step for `check-ssr-coverage-contract.sh` deleted
and only a COMMENT in the `frontend-e2e` job naming the file, the meta-gate reported:

```
  gates     : 37
  exempt    : 6 declared
PASS: every gate either runs in CI or has a declared reason it cannot.
rc=0
```

With the same step deleted and no comment naming the file, it correctly reported
`FAIL: 1 gate(s) are referenced by no workflow`, rc=1.

**Why this matters beyond one comment.** The workflow files are heavily commented and
several comments name gates — `check-runtime-freshness.sh` is discussed at length in the
`ops-contracts` header, and the retention and pentest steps both explain why they must
never gain a `gate-enforcement.conf` entry. Any gate whose name appears in such a comment
is exempt from the meta-gate's detection without anyone deciding that. It is the recorded
"a gate satisfied by its own prose" class, in the one place that is supposed to catch it.

**What 34-07 did instead:** removed the script's filename from its own comment, so the only
occurrence in the workflow is the `run:` line. That fixes the instance, not the class.

**What would close it:** strip `#` comments from each workflow file before the by-name
search in `check-gate-enforcement.sh`, then re-measure every gate's wired/exempt verdict —
a gate currently counted as wired only by a comment would flip to FAIL and needs a real
step or a reasoned exemption. Run the corpus both directions before and after.

**Why it was not done here:** `scripts/check-gate-enforcement.sh` is not in plan 34-07's
`files_modified`, and the change re-classifies all 37 gates — an out-of-scope edit to a
shared meta-gate that could red the build for reasons unrelated to this plan.

---

## From plan 34-07: docs/metrics.json is behind wave 1 (PRE-EXISTING, ALREADY OWNED by 34-10)

**Found:** 2026-08-28, running `scripts/docs-freshness.sh` as a side-check during 34-07.

**Not a new finding — already decided.** `34-01-SUMMARY.md` records it as a key decision in
terms: *"docs/metrics.json deliberately NOT regenerated — plan 34-10 is its single writer.
This leaves the docs-freshness gate RED on the branch until 34-10 lands."* This entry adds
only the measured numbers and confirms the red is not 34-07's, so a reader of this file does
not re-diagnose it. **Do not fix it here or in 34-08/34-09: single-writer is the point.**

`scripts/docs-freshness.sh` exits **1** on the wave-1-complete base (8285d6f5) and on every
34-07 commit. It is NOT caused by 34-07: the only files this plan changed are
`scripts/check-ssr-coverage-contract.sh`, `scripts/gates/ssr-routes.conf` and
`.github/workflows/ci-cd.yaml`, none of which contains a test block.

| metric | docs/metrics.json (committed) | tree measures now |
|---|---:|---:|
| jest_blocks | 1230 | 1272 |
| jest_files | 120 | 124 |
| playwright_blocks | 113 | 120 |
| playwright_specs | 22 | 25 |
| total_logical_invocations | 3188 | 3237 |

The new specs and tests from plans 34-01 through 34-06 landed without the manifest being
regenerated, so BOTH docs gates are currently red: `docs-freshness.sh` (tree → manifest)
and, consequently, `check-doc-metrics.sh` (prose in CLAUDE.md / AGENTS.md / README.md →
manifest), since the prose still quotes the 3188 line verbatim.

**What would close it:** `scripts/docs-freshness.sh --write`, then update the prose
sentence in CLAUDE.md, AGENTS.md and README.md to the regenerated figures. Never compute
the new numbers arithmetically — the counter greps literal `it(` / `test(`.

**Why it was not done here:** it is not 34-07's defect, it is not in its `files_modified`,
and 34-10 is the declared single writer of that manifest. Regenerating from any other plan
would be re-broken by the next plan's specs and would take the single-writer property with
it.

---
---

# Phase 34 closeout register (plan 34-10)

Everything this phase deliberately did NOT do, each with its reason, the measurement or
quoted source behind it, and what would remove it. **An entry with no removal condition is
a wish, not a deferral.** Entries above this line were written by the plans that found them
and are not restated here.

Measured 2026-08-29 on the assembled phase branch. Where an entry repeats a claim made in
34-RESEARCH, the claim was **re-measured** rather than copied; where it rests on a research
assumption that was never executed, it says so and names the assumption.

---

## D-34-10-01 — `middleware.ts` -> `proxy.ts` is OUT OF SCOPE

**Class:** out of scope (belongs in its own issue).

**Re-measured, not copied.**

1. **Next 16.3.3 emits a build-time warning only.** The exact call site, read out of the
   installed package rather than inferred from release notes:

   ```
   frontend/node_modules/next/dist/build/index.js:730
     _log.warnOnce(`The "${MIDDLEWARE_FILENAME}" file convention is deprecated.
                    Please use "${PROXY_FILENAME}" instead.` + ` To migrate automatically, run: npx @next/codemod@…`)
   ```

   `warnOnce` — not an error, not a build failure. `frontend/package.json` resolves to next
   **16.3.3** (`node_modules/next/package.json:3`).

2. **`frontend/middleware.ts` is a NextAuth wrapper.** Line 19 is
   `export default auth((req) => {`, and the body mints the per-request CSP nonce
   (`requestHeaders.set("x-nonce", nonce)`) that every SSR page's JSON-LD depends on (#89 /
   SEC-02). Breaking it does not fail a test — it silently drops CSP nonces on every page.

3. **`next-auth@5.0.0-beta.32` contains ZERO references to the proxy convention.**
   `rg -uu -c 'proxy' frontend/node_modules/next-auth/package.json` -> **0**, with a
   positive control on the same file (`next-auth` -> **2**) proving the search reaches it;
   and a whole-package scan for the convention returned nothing, with a positive control
   (`middleware` -> 5 files, including `middleware.d.ts`) proving that search direction
   works too. An unvalidated zero is a statement about the search, not about the package.

**A finding the plan did not anticipate, and the strongest reason of the four.** Next's own
upgrade guide, shipped inside the package at
`node_modules/next/dist/docs/01-app/02-guides/upgrading/version-16.md:616`, states:

> The `edge` runtime is **NOT** supported in `proxy`. The `proxy` runtime is `nodejs`, and
> it cannot be configured.

`frontend/middleware.ts` declares **no** `export const runtime`, so it runs on the
middleware default — **edge**. The migration is therefore not a rename: it moves the CSP
nonce minter from the edge runtime to nodejs, unconfigurably. That is a runtime change to
the one file that gates every page's Content-Security-Policy, and it is not a closeout task.

**The codemod was NOT run** — research assumption **A4**, explicitly labelled LOW confidence
and "codemod was **not** run" in 34-RESEARCH:843. This entry does not claim otherwise.

**REMOVE WHEN:** an issue is opened to migrate the convention, and it establishes (a) that
the CSP nonce path behaves identically under the nodejs runtime, with the served
`Content-Security-Policy` header read back off a real response, and (b) that NextAuth
supports the `proxy` export in whatever version is then installed.

---

## D-34-10-02 — mcp-server test coverage is N/A, recorded rather than dropped

**Class:** N/A (CLAUDE.md's roster rule — never silently drop a dimension).

**Measured.** `rg -uu -c 'coverage' mcp-server/package.json` -> **0**, positive control
`vitest` -> **3** on the same file. `mcp-server/node_modules/@vitest/coverage-v8` is
**absent**. So mcp-server's 48 vitest blocks run with no coverage provider at all.

**Why it was not added.** Three independent reasons, and the third is disqualifying on its own:

1. **It is out of the issue's scope.** ROADMAP criterion 4 (`.planning/ROADMAP.md:901-903`)
   narrows #110 to exactly three coverage tiers — "JaCoCo, the generated-but-unconsumed Go
   coverage profile, and a Jest `coverageThreshold`". mcp-server is not among them.
2. **It is a genuinely new npm dependency**, and this phase's supply-chain assertion is that
   it added none: `git diff --name-only origin/main -- frontend/package.json
   frontend/package-lock.json edge-go/go.mod edge-go/go.sum mcp-server/package.json` prints
   nothing (positive control: the same pathspec form against `frontend/` returns 20+ files).
3. **`@vitest/coverage-v8` could not be legitimacy-checked.** `slopcheck` was unavailable in
   the research session — `pip install slopcheck` is blocked by this machine's
   `block-base-python.py` with no bypass, by design (34-RESEARCH:223, :741). The package is
   therefore `[ASSUMED]`, research assumption **A5**, "not verified on any registry"
   (34-RESEARCH:844). Installing an unverified package to satisfy a coverage metric is the
   exact trade this project's package-legitimacy rule exists to refuse.

**REMOVE WHEN:** `@vitest/coverage-v8` (or whichever provider is chosen) passes a real
legitimacy check on a machine where slopcheck runs, AND a decision is recorded to extend the
coverage contract to a fourth tier. Adding it must go through `checkpoint:human-verify`.

---

## D-34-10-03 — the SSR fixture server is DEFERRED

**Class:** deferred (the must shipped; this is the strictly-better version).

**What shipped instead.** 34-07's manifest gate — `scripts/gates/ssr-routes.conf` plus
`scripts/check-ssr-coverage-contract.sh` — which was the actual requirement, and 34-07's
honest statement in the `frontend-e2e` job of what its stack-free green does and does not
cover (#542's third acceptance criterion; 34-07-SUMMARY:14, :302, :327).

**What is still missing.** The per-PR stack-free job has no real SSR coverage. It cannot
have any: measured on the live stack, `/shop` serves **54,184 bytes with 5 occurrences of a
shop's name**, versus **39,438 bytes and 0** with no backend (recorded in
`scripts/gates/ssr-routes.conf`). Pointing `CORE_API_INTERNAL_URL` at a `node:http` fixture
server fed from the existing `e2e/helpers/public-surface.ts` constants would give that job
genuine server-render coverage without a stack.

**Named as an assumption, because it is one.** Research assumption **A6**, MEDIUM confidence
(34-RESEARCH:845): the env-var precedence and its runtime-lookup nature are **verified**,
but a fixture server **was not built or run** — the `:3105` arm proved only the
*unreachable-backend* case. This entry claims nothing stronger.

**Its own security constraint, already recorded** (34-RESEARCH:821, :830): such a server must
bind to `127.0.0.1`, start and stop within the Playwright run, and never be referenced from
committed non-test config — `CORE_API_INTERNAL_URL` pointed at a fixture in a non-test
environment would serve fixture data as real.

**REMOVE WHEN:** the fixture server is built, the `frontend-e2e` job sets
`CORE_API_INTERNAL_URL` at it, and a spec asserts server-rendered content from
`request.get()` in that job — with the no-fixture arm shown to fail first.

---

## D-34-10-04 — ZERO #507 route conversions, and that is a finding, not an omission

**Class:** measured decision (#507's own body: decide, do not convert reflexively).

**The measurement that makes it a finding.** `scripts/gates/ssr-routes.conf` classifies all
**38** routes: **4 SSR**, **13 STATIC**, **21 CLIENT** (counted from the file's own
directives). The three highest-impact public routes — the landing page, the shop directory
and the storefront — are **already server components with raw-HTML assertions**, so the
conversions with the most reach were already done before this phase.

Every remaining public client route depends on browser-only state. Re-verified on the tree
rather than copied — all five are `"use client"` on line 1 (`head -1`), and:

| Route | What a server cannot do | Verified |
|---|---|---|
| `shop/[slug]/cart` | basket lives in `localStorage` | `components/storefront/cart-provider.tsx:77` — `window.localStorage.getItem(cartStorageKey(slug))` |
| `shop/[slug]/orders/[orderNumber]` | proves ownership with a checkout email from `localStorage` | 3 `localStorage` references in the page |
| `shop/[slug]/checkout` | Stripe Elements mounts in the browser; card fields never touch our servers | deliberately interactive |
| `shop/auth/callback` | exchanges a single-use `?code=` that arrives only in the browser | deliberately interactive |
| `track` | pre-fills from `sessionStorage`, then polls | `app/track/page.tsx:109` — `sessionStorage.getItem("jtoye-track-email")` |

`track` is the only partially-convertible one: its signed-in half could be server-rendered
while the guest lookup form stays client.

**The per-route reasons are NOT duplicated here.** They live in
`scripts/gates/ssr-routes.conf`, which is gated by `scripts/check-ssr-coverage-contract.sh`
— a reason copied into this file would drift silently, whereas one in the conf fails the
build when it stops matching the page. This file points; the conf decides.

**REMOVE WHEN:** a route's browser-only dependency is removed (e.g. the cart moves to a
server-side session), at which point its `CLIENT` line becomes `SSR` and names its spec in
the same change — the gate fires at the moment of conversion, which is the moment the
coverage would otherwise be silently dropped.

---

## D-34-10-05 — the six E2E skips belong to #304 and #61, not to this phase

**Class:** out of scope by #547's own body; both parent phases blocked on owner action.

**Measured, from a report's own per-test annotations rather than by arithmetic** (nightly run
33142364550, 2026-08-28T04:43:48Z, 266 results — recorded in
`scripts/gates/e2e-skip-budget.conf`):

| Spec | Skips | Owner | Why |
|---|---:|---|---|
| `stomp-relay.spec.ts` | 4 (2 tests x 2 projects) | **#304** (Phase 29) | needs the stack scaled to two core-java replicas (`RELAY_E2E=1` + `--scale core-java=2`) |
| `vendor-refund-flow.spec.ts` | 2 (1 test x 2 projects) | **#61** (Phase 30) | issues a REAL partial refund; `STRIPE_API_KEY` is empty on the dev stack |
|  | **6** | | |

The refund skip is **deliberately not faked**: seeding `paymentStatus=CAPTURED` with an
invented `payment_reference` would push the test past its skip and then fail at the Stripe
call — worse than skipping, because it converts "nobody checked this" into a red herring.

**What this phase DID do about skips** (34-06, so this entry is not mistaken for inaction):
it removed the false `onboarding-blocked-flow` exemption — whose justification blamed an
absent seeded shop, a cause the same nightly measures FALSE (the desktop arm passes in
6746ms, which is only possible when that fixture IS present) — and lowered `MAX_SKIPS` from
8 to the measured 6. At 8 the gate passed on 7 and would equally have passed on 6: it could
not tell the three apart, so it could not fail for any real change in the skip set.

**REMOVE WHEN:** a scaled-stack CI job exists (#304), and Stripe test-mode keys are
provisioned for the dev/CI stack (#61). Each retirement DELETES its `ALLOW` and lowers
`MAX_SKIPS` in the same change — the gate fails on an `ALLOW` that no longer matches
anything, because a stale exemption is a lie about coverage.

---

## D-34-10-06 — #453 (who adjudicates onboarding `MANUAL_REVIEW`) is an unadjudicated PRODUCT decision

**Class:** blocked on a decision nobody has made — not an engineering task.

This is the standing consequence of the platform's deliberate architecture: there is **no
cross-tenant operator identity** (refused twice, by design). A vendor whose onboarding lands
in `MANUAL_REVIEW` is therefore waiting on a human role that does not exist in the system,
and no amount of engineering in this phase can invent one — choosing who holds that role is
a product and legal decision about liability for approving a food business.

Writing code first would be the worse failure: it would force the answer by implementation,
in exactly the area (vendor verification) where the platform's exposure is real.

**REMOVE WHEN:** the owner decides who adjudicates `MANUAL_REVIEW` — a named role, with its
authority and its audit trail — after which it becomes an ordinary engineering task.

---

## D-34-10-07 — #286 and #110 are NARROWED, not closed

**Class:** partial closure, stated precisely so neither issue is read as finished.

### #286 — narrowed by 34-05

**Closed:** the 375px half. Before this phase, 375px coverage was **ONE** route
(`/dashboard`, the MOBL-01 block); the issue names eleven. 34-05 added the eleven-route
sweep (`/dashboard`, `/shops`, `/products`, `/products/import`, `/orders`, `/orders/{id}`,
`/customers`, `/finance`, `/marketing`, `/kitchen`, `/onboarding`) at a per-describe
375x812 pin. Enumeration went **13 -> 24 tests per project (+11 each)**, both projects green.

**Also already satisfied, by a different file:** the `/dashboard/staff` click-through, in
`e2e/dashboard-interface-corrections.spec.ts` — **3** `vendorLogin` references and **0**
`context.route(` calls (real auth AND real data), the zero confirmed with `searchcheck` and
a positive control.

**What REMAINS:** the mobile Playwright project is still pinned at **390x844**, not 375.
Moving it would close #286 in one line and silently relocate every mobile spec, the
instrument contract and every mobile perf baseline — so the viewport is pinned per-describe
instead. **REMOVE WHEN:** someone decides whether the project default should move to 375,
with the perf baselines re-measured in the same change.

**Carried forward from 34-05 and still open** (see D-34-05-01 above): the same unfalsifiable
overflow shape survives in `public-layout.spec.ts` at three sites and in the MOBL-01 block.

### #110 — narrowed by 34-08 and 34-09

**Closed: all three coverage tiers ROADMAP criterion 4 names** (`.planning/ROADMAP.md:901-903`):

| Tier | Gate | Plan |
|---|---|---|
| Go | `scripts/check-go-coverage.sh` | 34-08 |
| Jest | `coverageThreshold` + its consumer | 34-08 |
| Java (JaCoCo) | `scripts/check-jacoco-coverage.sh`, floors 85/69/85/85 over the **aggregate** of both suites | 34-09 |

The issue's second criterion — "Playwright runs in CI" — was already satisfied by the
nightly job, which is what closed #420.

**A note on wording, so the two documents are not read as disagreeing.** 34-09-SUMMARY:323
says "three of the four coverage tiers"; the ROADMAP criterion names exactly **three**, all
now closed. The fourth tier in that sentence is mcp-server, which the criterion does not
name and which is recorded as N/A in **D-34-10-02** above rather than left implied.

**What REMAINS:** the JaCoCo floors are CI-calibrated with margins of only **2.03–2.78
points**, and the CI aggregate measured **0.10–0.56 points BELOW** this machine's. That is
real but thin headroom. **REMOVE WHEN:** nothing — this is a standing maintenance property,
not a deferral. If the gate goes red the answer is a test, not a smaller number.
