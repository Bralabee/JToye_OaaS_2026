---
phase: 34-rendering-test-truthfulness
plan: 01
subsystem: testing
tags: [playwright, ssr, next.js, coverage, falsifiability, e2e]

requires:
  - phase: 33-locality-consumer-product
    provides: the seeded storefront (Brixton Village Grill / Mama Ade's Kitchen) the served-HTML assertions read
provides:
  - "frontend/e2e/helpers/served-html.ts — the single raw-HTML instrument for the whole suite (servedHtml/countOf/titleOf/jsonLdNodes/typesOf)"
  - "frontend/e2e/ssr-coverage.spec.ts — the committed proof that a live browser route stub satisfies the DOM instrument and cannot satisfy the raw-HTML one"
  - "the first served-HTML assertion for /shop/orders, incl. a no-order-leak negative for unauthenticated callers"
  - "both directions' real numbers (stacked vs stack-free) recorded in the two spec docblocks"
affects: [34-02 ssr-route-manifest-gate, 34-10 metrics, any phase converting a route to SSR]

tech-stack:
  added: []
  patterns:
    - "raw-HTML coverage assertion: request.get() over page.goto() for any server-rendered, data-loading route"
    - "stub-liveness positive control before any absence assertion"
    - "chrome-floor liveness control (a count that survives a page whose body rendered nothing)"
    - "in-test regex positive control against a real production value"

key-files:
  created:
    - frontend/e2e/helpers/served-html.ts
    - frontend/e2e/ssr-coverage.spec.ts
  modified:
    - frontend/e2e/storefront-ssr-seo.spec.ts

key-decisions:
  - "The instrument is a plain module, not a spec — importing a spec executes its body and would register the SEO describes twice."
  - "The helper declares no origin/host/port/env fallback, and says why: check-e2e-baseurl-contract.sh scans *.spec.ts only, so such a constant would sit outside the gate it exists to satisfy (Pitfall 7). The forbidden tokens are described rather than spelled out, because the acceptance check is a grep for those literals."
  - "The planned `<h1` liveness control does not exist on /shop/orders (measured 0). Replaced by a strictly stronger, measured chrome floor (`<h2 >= 3`) rather than silently dropped."
  - "Block 2's fail direction is a re-clientification of the page, NOT a backend outage — it passes stack-free, correctly. Reporting the stack-free arm as its falsification would have been a vacuous pass presented as evidence."
  - "docs/metrics.json deliberately NOT regenerated — plan 34-10 is its single writer. This leaves the docs-freshness gate RED on the branch until 34-10 lands (see Issues)."

patterns-established:
  - "Pattern 1 (RESEARCH): raw-HTML coverage assertion, now a shared module with a self-proving spec beside it"
  - "Every absence assertion carries a positive control proving the thing could have been present"
  - "Break arms are run AFTER the commit, restored by pathspec from the repo root, and verified by content hash — the closing clean arm is the only proof"

requirements-completed: [TRUTH-01]

duration: 62min
completed: 2026-08-28
---

# Phase 34 Plan 01: The SSR Coverage Instrument Summary

**A shared `request.get`-based raw-HTML helper, a committed spec that proves a live `context.route` stub satisfies the DOM and cannot satisfy the served bytes, and `/shop/orders`' first served-HTML assertion — with every criterion run in its fail direction first.**

## Performance

- **Duration:** ~62 min
- **Started:** 2026-08-28T21:05Z
- **Completed:** 2026-08-28T22:07Z
- **Tasks:** 3
- **Files modified:** 3 (1 modified, 2 created)

## Accomplishments

- The raw-HTML instrument exists **once**, in `frontend/e2e/helpers/served-html.ts`. `storefront-ssr-seo.spec.ts` imports it with **identical enumeration (17) and pass count (17)** before and after — the proof it was a pure move.
- `frontend/e2e/ssr-coverage.spec.ts` answers ROADMAP criterion 1 with a **live** stub: it asserts the stub is working (DOM carries `STUB_MARKER`) *before* showing `request.get` and `context.request.get` are untouched by it, and re-checks the DOM afterwards so the two instruments disagree **inside one run**.
- `/shop/orders` — the last server-rendering, data-loading public route with no served-HTML assertion — has one, including a negative that no DOM assertion in the suite could make: an unauthenticated caller must receive **no order-number-shaped content**.
- The fail direction was **executed on this tree**, not assumed, for every criterion. Three separate break arms, all restored and hash-verified.
- Two of the plan's own criteria were measured to be **broken as written** and were replaced with strictly stronger forms, both recorded in the files rather than swapped silently.

## Task Commits

1. **Task 1: Extract the raw-HTML instrument** — `ae15e1c6` (refactor)
2. **Task 2: Stub-immunity proof + /shop/orders served-wall assertion** — `24d4be5c` (test)
   - break-arm findings + a corrected figure — `d1874d08` (docs)
3. **Task 3: Execute the stack-free fail arm, record both directions** — `08de1e43` (docs)

## Files Created/Modified

- `frontend/e2e/helpers/served-html.ts` — **created.** The single definition of "what did the server actually serve". Docblock states why it exists, why it is a plain module not a spec, and what it must never declare.
- `frontend/e2e/ssr-coverage.spec.ts` — **created.** Two blocks: stub-immunity (with its own positive control) and the `/shop/orders` served wall. Docblock carries every measurement in both directions.
- `frontend/e2e/storefront-ssr-seo.spec.ts` — **modified.** Five local helper definitions replaced by an import; one dated stanza appended recording that this suite goes red on a stack-free server.

## Acceptance Criteria — PASS output and BREAK-ARM output

### Task 1

| Criterion | PASS | BREAK ARM / control |
|---|---|---|
| spec no longer defines `servedHtml` | `rg -uu -c 'async function servedHtml' …spec.ts` → *(empty)* rc=1 | **positive control:** the SAME command printed `1` rc=0 **before** the edit, and prints `1` rc=0 against `helpers/served-html.ts` after it — so the pattern can match |
| helper exports it | `rg -uu -c 'export async function servedHtml' helpers/served-html.ts` → `1` rc=0 | — |
| enumeration unchanged | `--list --project=desktop` → `Total: 17 tests in 1 file`, before **and** after | — |
| pass count unchanged | `--project=desktop` → `17 passed`, before **and** after (live Compose stack) | — |
| no base-URL literal in the helper | `grep -c 'localhost\|…_BASE_URL\|:3[0-9][0-9][0-9]'` → `0` rc=1 (both BRE and `-E` forms) | **arm:** appended `const BASE = process.env.…_BASE_URL \|\| "http://localhost:3999"` → grep → `1` rc=0, matching line 97. **`check-e2e-baseurl-contract.sh` stayed rc=0** — Pitfall 7, recorded as the finding, not as a pass. **positive control** for the grep pattern: `2` matches in `playwright.config.ts` |
| `check-e2e-baseurl-contract.sh` | rc=0, 22→23 specs scanned, 14 fallbacks, 0 divergent | — |
| `check-e2e-typecheck.sh` | rc=0, 26→27 files | — |

**Restore verification (Task 1 arm):** pre-arm `git hash-object` = `d269eb025550d5e934e13513b3b21732436529fd`; **the first restore FAILED** (`568cedf9…` — a python slice left one extra newline). Re-restored via `git checkout HEAD --`, hash back to `d269eb02…`, identical to `git rev-parse HEAD:…`, `git status` clean.

### Task 2

| Criterion | PASS | BREAK ARM |
|---|---|---|
| both blocks green, live stack | `2 passed (781ms)`; `docker ps` line recorded: `jtoye_oaas_2026-core-java-1  Up 5 hours (healthy)  0.0.0.0:9090->9090/tcp` (Pitfall 8 — **not** 9091) | — |
| `@desktop-only` excludes from mobile enumeration | `--list --project=mobile` → `Total: 0 tests in 0 files`; `--project=desktop` → `Total: 2 tests in 1 file` | **arm:** tag stripped from the describe title → mobile `--list` → `Total: 2 tests in 1 file`. Restored, hash `24970b380f97a211b25954f981dab85eab792cb4` = HEAD, status clean |
| **the criterion itself** | — | **arm:** `res.text()` → `page.content()` in Block 1 → **RED**: `Error: request fixture was intercepted by context.route … this run got 84 bytes.  expect(received).toBe(expected)  Expected: 0  Received: 1` at `ssr-coverage.spec.ts:162`. Restored, hash `24970b38…` identical, then `2 passed` |
| no base-URL literal | `grep -c 'localhost' ssr-coverage.spec.ts` → `0`; gate rc=0 | — |
| `check-e2e-typecheck.sh` | rc=0, 27 files | — |

### Task 3

| Criterion | Measured on THIS tree, 2026-08-28 |
|---|---|
| stack-free arm: 0 occurrences on both routes, liveness control holds | `:3105 /shop` → 39,438 B, **0** occurrences, `<h1`=**1**; `:3105 /shop/brixton-village-grill` → 39,299 B, **0** occurrences, `<h1`=**0** |
| stacked arm: non-zero on both | `:3000 /shop` → 54,184 B, **5**; `:3000 /shop/brixton-village-grill` → 90,951 B, **33**; `<h1`=1 on both |
| suite red stack-free / green stacked | `PLAYWRIGHT_BASE_URL=http://localhost:3105 … --project=desktop` → **rc=1**, 13 of 17 red, opening on `Error: the shop name must be an h1 in the served HTML — Expected: > 0, Received: 0`. Same command vs `:3000` → **rc=0, 17 passed**. Both rc captured on the same statement as the command. |
| port refuses after teardown | `curl` → `http_code=[000] rc=7`; `ss -ltn \| grep -c ':3105'` → `0`. **positive control:** the same listener query finds `:3000` (`LISTEN 0 4096 0.0.0.0:3000`) |
| `3105` appears ≥1 and only in comments | `rg -uu -c '3105' ssr-coverage.spec.ts` → **5**; all 5 classify as comment lines (5/5). **positive control:** the same classifier scores a known CODE line (`const STUB_MARKER`) as `0` |
| both specs green at the end (closing clean arm) | `19 passed (5.3s)` on the live stack |

### Extra break arm — Block 2's real fail direction (not in the plan; added because the planned one is vacuous for it)

Block 2 **passes** against the stack-free server, correctly: the sign-in wall needs no backend. Its real claim is that the wall is rendered *by the server*, so the arm handed the anonymous case back to the client island (`OrdersClient initial={null}` — the pre-#463 shape, where `signedOut=false` and `load=null` until an effect runs, so the server renders a spinner), then **rebuilt**:

```
armed     /shop/orders  28,365 B  "Sign in to continue"=0  <h2=3   -> RED
  Error: the wall's heading is not in the served bytes — /shop/orders is
  client-rendering it again.   Expected: > 0   Received: 0
restored  /shop/orders  30,960 B  "Sign in to continue"=2  <h2=4   -> 2 passed
```

Restore hash `a067b3523485c320fac346e82552ef8153b3706d` = HEAD, `git status` clean, `CustomerSignInPrompt` references back to 2. `.next/` was **rebuilt after the restore** — restoring source without rebuilding leaves the runtime disagreeing with the tree.

## Plan-level verification

| Command | rc | Output |
|---|---|---|
| `bash scripts/check-e2e-typecheck.sh` | 0 | `PASS: 27 e2e file(s) type-check clean.` |
| `cd frontend && npx eslint .` | **0** | `✖ 34 problems (0 errors, 34 warnings)` — identical to the 34 the research measured on the pre-change tree, so this plan added none. (rc is the verdict; the last line is the *fixable* count.) |
| `npx playwright test e2e/storefront-ssr-seo.spec.ts e2e/ssr-coverage.spec.ts --project=desktop` | 0 | `19 passed (5.3s)` |
| `bash scripts/check-e2e-baseurl-contract.sh` | 0 | `23 spec file(s), 14 local fallback(s), 0 divergent` |
| `git diff --name-only … -- frontend/package.json frontend/package-lock.json` | 0 | *(none)* — over the whole plan, so **T-34-01-SC holds: nothing was installed** |
| `git diff --name-only … -- docs/metrics.json` | 0 | *(none)* — deliberate, 34-10 is the single writer |

Files changed by the plan, in full: `frontend/e2e/helpers/served-html.ts`, `frontend/e2e/ssr-coverage.spec.ts`, `frontend/e2e/storefront-ssr-seo.spec.ts`.

## Decisions Made

- **Chrome-floor liveness control.** `<h1` was the planned control and `/shop/orders` serves 0 of them (its heading is an `<h2>`, and the route is `noindex` as a per-customer surface). Rather than drop it — which would have left the order-number negative with no liveness control at all — it was replaced by a measured floor: the not-found screen, a page with no body content, still serves **3** `<h2>` from `PublicFooter`. `<h2 >= 3` therefore survives a page that rendered nothing, which is the property `<h1` was for, and it is stronger because its source is known. The armed Block 2 run then proved the floor works in anger (wall gone, `<h2`=3 held).
- **The order-number regex is validated inside the test.** `OrderService.generateOrderNumber`'s Javadoc example is `ORD-A1B2C3D4-20260116-E5F6G7H8` and `G`/`H` are not hex digits — a positive control built from it reports 0 and reads as "clean page". The sample is a real order number read out of the running database (`ORD-00000000-20260714-DB2E43A5`) and the control is an assertion, not a comment.
- **Assertion messages print runtime lengths, not copied constants.** The docblock originally said "the stub body is 61 bytes" — 61 is the *research probe's* stub; this file's is 71. The break arm surfaced it precisely because the message printed `STUB_HTML.length`.

## Deviations from Plan

### Auto-fixed / corrected

**1. [Rule 1 — broken criterion] The planned `<h1` liveness control does not exist on `/shop/orders`**
- **Found during:** Task 2, while measuring the route before writing assertions
- **Issue:** measured `<h1`=0 on `/shop/orders`. The criterion as written is unsatisfiable; using it would have made the block permanently red, and deleting it would have made the order-number negative vacuous.
- **Fix:** replaced with a measured chrome floor (`<h2 >= 3`), justified in the spec docblock with the four-route measurement table that establishes it.
- **Verification:** the armed Block 2 run — wall removed, `<h2`=3 still served, so the 0 was demonstrably about content.
- **Committed in:** `24d4be5c`

**2. [Rule 1 — broken instrument] The order-number regex's obvious positive control is invalid**
- **Found during:** Task 2
- **Issue:** the Javadoc example is not hex-valid, so `grep -cE 'ORD-[0-9A-F]{8}-[0-9]{8}-[0-9A-F]{8}'` against it returns 0 — an instrument failure that reads exactly like a clean page.
- **Fix:** control asserted in the test against a real order number from the live DB.
- **Committed in:** `24d4be5c`

**3. [Rule 1 — stale figure] A byte count copied from a different stub**
- **Found during:** Task 2 break arm
- **Issue:** docblock said 61 bytes (the research probe's stub); this file's `STUB_HTML` is 71.
- **Fix:** corrected, provenance of both figures stated, and the assertion message left printing `STUB_HTML.length` at runtime so a failure can never quote a stale number.
- **Committed in:** `d1874d08`

**4. [Rule 2 — missing falsification] Block 2 had no executed fail direction**
- **Found during:** Task 3
- **Issue:** the plan's stack-free arm does not falsify Block 2 — it passes there, correctly, because the sign-in wall needs no backend. Recording that arm as Block 2's falsification would have been a vacuous pass reported as evidence, i.e. the exact defect this phase exists to remove.
- **Fix:** ran the real arm (re-clientify the anonymous branch, rebuild, re-serve), observed RED, restored by hash and rebuilt.
- **Committed in:** `08de1e43` (recorded in the docblock)

**5. [Rule 1 — research correction] `<h1` is NOT present in all four stack-free arms on this tree**
- **Found during:** Task 3
- **Issue:** the research note's Pitfall-2 table reports `<h1`=1 in all four rows. Measured today, the stack-free **slug** page serves `<h1`=0.
- **Fix:** recorded in the `ssr-coverage.spec.ts` docblock, with the consequence stated: on that route the liveness control is the byte count (39,299 B) alone.
- **Committed in:** `08de1e43`

---

**Total deviations:** 5 (4 × Rule 1, 1 × Rule 2). **Impact:** all are corrections to *criteria and instruments*, not to scope. No product code was changed by this plan; three e2e files, nothing else.

## Issues Encountered

1. **Teardown of the probe server silently failed.** `kill $srv` on the `npx next start` wrapper left `next-server` listening — `curl` after teardown returned **200**. Caught only by the closing assertion the plan mandates. Fixed by capturing the *listener* PID from `ss -ltnp` while the server was known-up (never with `pgrep -f`, which matches its own command line) and killing both; the port then refused with rc=7 and 0 listeners, verified after every subsequent probe cycle.
2. **A restore failed twice, both times caught only by the closing clean arm.** Once by an off-by-one newline in a python slice (hash `568cedf9…` vs `d269eb02…`), once because a script had `cd`'d into `frontend/` and used a repo-root-relative pathspec — `git checkout` printed `error: pathspec … did not match any file(s) known to git` while the surrounding script reported progress. All subsequent restores run `git checkout HEAD -- <path>` from the repo root and are verified against `git rev-parse HEAD:<path>`.
3. **`rg` is unavailable inside a `bash script.sh`.** It is an injected shell function, so a script invoking it dies rc=127 with zero output — indistinguishable from "not found". Caught because the scripts print rc. All `rg`-based evidence was re-run inline.
4. **The frontend worktree had no `node_modules`.** `npm ci` in `frontend/` (831 packages, 9s from cache). `package.json`/`package-lock.json` are byte-identical to base — verified over the whole plan.

## Known drift — deliberate, and it will show as RED until 34-10

`scripts/docs-freshness.sh` is **failing on this branch by design**:

```
docs/metrics.json   playwright_blocks 113  playwright_specs 22  total 3188
computed from tree  playwright_blocks 115  playwright_specs 23  total 3190
```

`ssr-coverage.spec.ts` adds 2 `test()` blocks in 1 new spec. Plan 34-10 is the phase's **single writer** of `docs/metrics.json` (so no two plans can disagree about the count — the Phase 31 wave-1 lesson), and it must also update the prose figures in `CLAUDE.md`, `AGENTS.md` and `README.md` that `scripts/check-doc-metrics.sh` gates. **Do not regenerate it here.** Other plans in this phase will add further blocks; the count is only correct once, at the end.

## Known Stubs

None. Nothing in this plan renders UI or wires data; the only new constants (`STUB_MARKER`, `STUB_HTML`) are deliberate test fixtures that never leave the spec, and `STUB_MARKER` is asserted **absent** from every served response.

## Threat Flags

None. No network endpoint, auth path, file access pattern or schema was added or changed. The one new security-relevant assertion is defensive: `T-34-01-02`'s mitigation is live — an unauthenticated `request.get("/shop/orders")` is asserted to receive the sign-in wall and **no order-number-shaped content**, an SSR leak that every DOM assertion in the suite is blind to. `T-34-01-03` (the probe server) was started only inside Task 3, PID captured at start, killed, and the port proven to refuse; `T-34-01-SC` holds — nothing was installed.

## Next Phase Readiness

- **34-02 (the SSR-route manifest gate)** can build directly on this: `ssr-coverage.spec.ts` contains a literal `request.get(` and `servedHtml(request, "/shop/orders")`, which is the shape its R-2 "no stale entries" rule looks for, and `/shop/orders` is now declarable with a real assertion behind it. All four server-rendering, data-loading public routes (`/`, `/shop`, `/shop/[slug]`, `/shop/orders`) now have served-HTML coverage.
- **34-10** owns `docs/metrics.json` and must run last (see the drift note above).
- **Standing caution for the rest of the phase:** every spec edit changes `specDigest`, so `check-e2e-skip-budget.sh --from-nightly` will VOID until a fresh full-suite run is taken **after** the last spec edit in the phase (research Pitfall 4).

---
*Phase: 34-rendering-test-truthfulness*
*Completed: 2026-08-28*

## Self-Check: PASSED

Run 2026-08-28, with negative controls so the check is provably able to report a miss.

- 4/4 claimed files exist; the same test reports MISSING for `frontend/e2e/helpers/does-not-exist.ts`.
- 4/4 claimed commits (`ae15e1c6`, `24d4be5c`, `d1874d08`, `08de1e43`) are reachable; the same test reports MISSING for `deadbee`.
- Both restore hashes quoted above still equal `git rev-parse HEAD:<path>` — `served-html.ts` `d269eb02…`, `app/shop/orders/page.tsx` `a067b352…` — so the break arms left nothing behind.
