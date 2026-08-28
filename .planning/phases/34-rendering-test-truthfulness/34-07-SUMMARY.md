---
phase: 34-rendering-test-truthfulness
plan: 07
subsystem: testing
tags: [ci, gates, ssr, next.js, coverage, default-deny, falsifiability]

requires:
  - phase: 34-rendering-test-truthfulness
    provides: "34-01's frontend/e2e/helpers/served-html.ts and ssr-coverage.spec.ts — the raw-HTML calls this gate's R-2b requires an SSR entry to name"
provides:
  - "scripts/check-ssr-coverage-contract.sh — default-deny SSR-route manifest gate, R-1/R-2a-d/R-3, VOID on unknown"
  - "scripts/gates/ssr-routes.conf — every one of the 38 app/**/page.tsx declared SSR, STATIC or CLIENT, each non-SSR entry with a verified reason (#507's last acceptance criterion)"
  - "the gate wired into ci-cd.yaml's ops-contracts job, with its four-arm falsification record beside it"
  - "an honest statement in the frontend-e2e job of what its stack-free green does and does not cover (#542's third acceptance criterion)"
affects: [34-08, 34-09, 34-10, any future plan converting a route to SSR]

tech-stack:
  added: []
  patterns:
    - "default-deny manifest gate: declare every unit by name, so a NEW one fails rather than a count moving"
    - "classify by DIRECTIVE POSITION, never substring — the strict parser is measurably 4 apart from git grep, in the direction that makes the work look undone"
    - "a required literal must sit INSIDE the call it certifies, not merely somewhere in the file"
    - "comment-stripping before every content match, proven in both directions on the same tokens"

key-files:
  created:
    - scripts/check-ssr-coverage-contract.sh
    - scripts/gates/ssr-routes.conf
  modified:
    - .github/workflows/ci-cd.yaml
    - .planning/phases/34-rendering-test-truthfulness/deferred-items.md

key-decisions:
  - "R-2b requires the route literal INSIDE a servedHtml( / request.get( call on the same line, not merely present in the spec. Measured load-bearing: swapping /shop/orders' servedHtml for page.goto left the literal in the file twice and the gate still went red. A file-contains check would have passed."
  - "Page paths are app-relative and spec paths repo-relative, per the plan's grammar. Both are existence-checked, so a mix-up fails loudly (R-1b / R-2b) rather than silently."
  - "The conf declares 13 STATIC entries that the plan did not enumerate, because 4 SSR + 21 CLIENT leaves 13 server components that fetch nothing. R-2c asserts that claim rather than trusting it."
  - "The zero-discovery arm is exercised through an SSR_APP_DIR env override rather than a tree edit. The override can only ever produce a VOID or a failure (an empty root VOIDs; a smaller root makes every declaration stale), never a false pass — so it is not a bypass."
  - "--classify is a diagnostic that runs no assertions and says so on every invocation; it exists because the acceptance criteria require the classifier's verdicts to be recorded, and a separate throwaway script would not have been the gate's own classifier."
  - "The gate is WIRED, not exempted. It reads only files in the checkout, so gate-enforcement.conf's bar ('a hosted runner does not have the thing this inspects') is false for it, and check-gate-enforcement.sh would reject such an entry as stale anyway."
  - "check-gate-enforcement.sh's inability to distinguish a run: line from a comment was fixed IN THIS PLAN'S OWN FILE only (removing the script's filename from the frontend-e2e comment). The general fix re-classifies all 37 gates and is logged to deferred-items."

patterns-established:
  - "Every VOID path exercised before the artifact it guards exists — the conf-absent arm was run before Task 2 wrote the conf, which is the only moment that path is naturally reachable"
  - "A break arm's restore is verified by git hash-object, and the closing clean arm is run after all arms"
  - "When a break arm reveals a defect the plan did not anticipate, fix it and re-run the same arm — the wiring arm was run twice, and only the second run could fail"

requirements-completed: [TRUTH-01]

metrics:
  duration: ~22 min (first commit 23:01:53, last 23:11:27, plus measurement before and after)
  completed: 2026-08-28
  tasks: 3
  commits: 6
  files-created: 2
  files-modified: 2
---

# Phase 34 Plan 07: SSR-Coverage Contract Gate Summary

A default-deny manifest gate that fires at the moment a route becomes server-rendered
without a raw-HTML assertion — all 38 app routes declared, each of the 34 non-SSR ones with
a reason verified against its file, and every assertion shown to fail before being trusted.

## What Was Built

**`scripts/check-ssr-coverage-contract.sh`** (401 lines). Structural clone of
`check-e2e-skip-budget.sh`: same header order, `set -uo pipefail` (accumulating, so one run
reports every undeclared route), `void()` / `fail()`, `--help` served from its own header,
unknown argument is VOID. Assertions:

| id | asserts |
|----|---------|
| R-1 | default-deny both directions: every discovered `page.tsx` declared exactly once, every declaration names a page that exists |
| R-2a | class agreement between the declaration and the strict classifier |
| R-2b | an SSR entry's spec exists and carries the declared literal **inside** a `servedHtml(` / `request.get(` call on the same line |
| R-2c | a STATIC page imports no storefront loader and calls no `fetch(` — measured after stripping comments |
| R-2d | CLIENT/STATIC reasons are non-empty, non-placeholder, and at least ten characters |
| R-3 | classifier self-test in both directions, run FIRST; a wrong verdict or a missing fixture is VOID |

**`scripts/gates/ssr-routes.conf`** (160 lines). 4 SSR, 13 STATIC, 21 CLIENT.

**`.github/workflows/ci-cd.yaml`**. One new `ops-contracts` step with its falsification
record above it, plus a measured statement in `frontend-e2e` of what that job's green covers.
No existing `run:` line changed; the diff deletes nothing (`67 insertions(+)` on the wiring
commit, all comments except the four lines of the new step).

## Measured Starting State (re-measured, not carried over)

Discovery: `/usr/bin/find frontend/app/ -type f -name page.tsx` → **38** files.
Strict classifier: **21 CLIENT / 17 SERVER**. Of the 17, exactly **4** load data
server-side. This matches the plan's stated figures.

### PITFALL-1 ARM — strict classifier vs `git grep`, side by side

```
git grep -l '"use client"' -- 'frontend/app/**/page.tsx' | wc -l   ->  25
strict classifier CLIENT count                                     ->  21
```

The four prose-only matches, each a page that already converted and documented the fact:
`shop/page.tsx`, `shop/[slug]/page.tsx`, `shop/orders/page.tsx`, `unsubscribe/page.tsx`.
RESEARCH measured 25 vs 21; this run reproduces both numbers exactly.

**A second, independent defect in the same search, found while checking the first.** The
pathspec `'frontend/app/**/page.tsx'` never matches `frontend/app/page.tsx` — git applies no
zero-directory `**` semantics to a pathspec without `:(glob)`. Positive control:

```
git grep -l loadShopList -- 'frontend/app/**/page.tsx'   ->  frontend/app/shop/page.tsx
git grep -l loadShopList -- frontend/app                 ->  frontend/app/__tests__/landing.test.tsx
                                                             frontend/app/page.tsx        <- invisible above
                                                             frontend/app/shop/page.tsx
```

`frontend/app/page.tsx:17` imports the same loader and the narrower search cannot see it.
The 25 is unaffected only because the landing page's docblock deliberately avoids spelling
the directive out. One file's wording away from being wrong in a second way. Both defects
are recorded in the conf header so the next person to reach for `git grep` reads them first.

## Every Arm, Both Directions

Bracketed clean → arms → clean. Each tree-mutating arm was run **after** the commit it
targets, restored by pathspec, and verified by `git hash-object`.

### Task 1 — the gate

| arm | how it was broken | result |
|-----|-------------------|--------|
| conf absent (run BEFORE Task 2 wrote the conf) | nothing — the natural pre-Task-2 state | `VOID: config not found: …/ssr-routes.conf` **rc=2** |
| zero discovery | `SSR_APP_DIR` pointed at an empty directory | `VOID: discovered ZERO page.tsx … 'found nothing' is never 'clean'` **rc=2** |
| R-3 wrong verdict | prepended the client directive to `app/shop/page.tsx` | `VOID: R-3 self-test: classifier called the known SERVER page … 'CLIENT'` **rc=2** |
| R-3 fixture missing | moved `app/track/page.tsx` out of the tree | `VOID: R-3 self-test fixture missing: …/track/page.tsx` **rc=2** |
| unknown directive | a conf containing `MAX_SKIPS 3` | `VOID: unknown directive 'MAX_SKIPS' … refusing to guess` **rc=2** |
| unknown argument | `--no-such-flag` | `VOID: unknown argument: --no-such-flag (try --help)` **rc=2** |

Restores verified: `app/shop/page.tsx` → `5449ecd5e41ad809be8d62ac8d741cee0a810756`,
`app/track/page.tsx` → `8e9d6c92038efc31619145d87dd01214f3cd3576`, both matching their
pre-arm hashes, and `git status --short` empty afterwards.

**CLASSIFIER ARM.** The gate's own classifier, via `--classify`:

```
classify : SERVER  frontend/app/shop/page.tsx
classify : CLIENT  frontend/app/track/page.tsx
```

Then against copies in a scratch directory with a `/* licence header */` block plus a blank
line prepended: **SERVER** and **CLIENT** unchanged. The parser is not `head -3` in disguise.

**`bash -n`** rc=0. **shellcheck is NOT installed on this machine** (`command -v shellcheck`
rc=1) — recorded as unavailable rather than implied to have passed.

**Assertion ids in failure messages:** `rg -uu -c 'R-1|R-2|R-3'` → 32, and each of R-1,
R-2a, R-2b, R-2c, R-2d, R-3 appears in at least one `fail`/`void` string (enumerated at
`:220-225`, `:312`, `:318`, `:330-333`, `:340`, `:353`, `:359`, `:364`, `:375-387`).

### Task 2 — the manifest

Clean: **rc=0**, `discovered: 38`, `declared: 4 SSR, 13 STATIC, 21 CLIENT (38 total)`.
4 + 13 + 21 = 38 = discovered.

| arm | how it was broken | result |
|-----|-------------------|--------|
| R-1 undeclared page | deleted the `CLIENT dashboard/staff/page.tsx` line | `FAIL: R-1 undeclared page: dashboard/staff/page.tsx` **rc=1** |
| R-1 stale declaration | added `CLIENT dashboard/does-not-exist/page.tsx …` | `FAIL: R-1 stale declaration at …:161 … names no page` **rc=1** |
| R-2b raw-HTML deleted | `/shop/orders`: `servedHtml(request, "/shop/orders")` → `page.goto("/shop/orders").then(r => r.text())` | `FAIL: R-2b … has no raw-HTML call … carrying the literal "/shop/orders"` **rc=1** |
| R-2a class mismatch | changed `CLIENT track/page.tsx` to an SSR entry | `FAIL: R-2a class mismatch … declared SSR but IS a client component` **rc=1** (plus the consequent R-2b) |
| R-2d empty reason | blanked `dashboard/finance/page.tsx`'s reason | `FAIL: R-2d CLIENT entry … carries no reason` **rc=1** |
| R-2c real load in a STATIC page | added `const data = await fetch("/api/v1/public/policy")` to `legal/cookies/page.tsx` | `FAIL: R-2c … calls fetch( in code (comments stripped)` **rc=1** |
| R-2c the SAME tokens in a comment | `// a docblock that MENTIONS fetch("…") and the storefront-server loader` | **rc=0 PASS** — the strip is load-bearing and the gate is not satisfiable by prose (T-34-07-03) |

**The R-2b arm is the one that justifies the design.** The spec still named `/shop/orders`
twice — in the test title at `:242` and in the replacement `page.goto` line itself — and the
gate still went red, because the literal must sit inside the raw-HTML call. A
"the spec mentions the route" check would have passed on a spec that had just lost its only
server-render assertion, which is #542 exactly.

Restores verified: conf → `784a0401211215d9a150444653bdfec7ae3ffd82` after each of the four
conf arms; `frontend/e2e/ssr-coverage.spec.ts` → `ae00254b5bdc61c179c17116a0a6b657329d6632`;
`frontend/app/legal/cookies/page.tsx` → `81f6f4dd249b82061fbdeeba807a32f0828fcc6b`.
**Closing clean arm: rc=0, `git status --short` 0 lines.**

`rg -uu -n 'Total =' scripts/gates/ssr-routes.conf` prints nothing (rc=1) — no hand-computed
total, which is the defect its analog shipped.

### Task 3 — the wiring

| arm | how it was broken | result |
|-----|-------------------|--------|
| wiring (1st run) | the step did not yet exist | `FAIL: 1 gate(s) are referenced by no workflow … check-ssr-coverage-contract.sh` **rc=1** |
| wiring (2nd run, after the fix below) | deleted the four step lines from the committed file | same FAIL, **rc=1** |
| clean | — | `gates: 37, workflows: 6, exempt: 6` → PASS **rc=0** |

Before: 36 gates / 6 exempt. After: 37 gates / 6 exempt.
`rg -uu -c 'check-ssr-coverage-contract' scripts/gates/gate-enforcement.conf` → rc=1, absent:
the gate is wired, not exempted.

**YAML.** Parsed with **PyYAML 6.0.3 under `conda run -n jtoye-ops`** — the machine's
base-python guard blocks an undeclared interpreter and this repo declares no `.conda-env`,
so the env was named explicitly rather than guessed. rc=0 on the real file; **fail direction
run**: a copy with an unterminated flow sequence appended gives
`yaml.parser.ParserError: while parsing a flow sequence`, rc=1. Beyond the parse, the new
step was confirmed present in the **parsed structure** (`jobs.ops-contracts.steps`, 18 steps,
run block `chmod +x ./scripts/check-ssr-coverage-contract.sh\n./scripts/…`), so the check is
about the file's meaning and not only its syntax.

**The `frontend-e2e` job's `run:` lines are unchanged**, read out of the parsed YAML:
`echo "Frontend E2E skipped…"`, `npm ci`, `npx playwright install --with-deps chromium`,
`npm run build`, `npx next start -p 3000 &`,
`npx playwright test e2e/public-layout.spec.ts e2e/public-a11y.spec.ts`.
Diff stat on the wiring commit: `1 file changed, 67 insertions(+)`, zero deletions; the only
non-comment additions are the four lines of the new step.

## Reasons Verified Against Their Files

The six public CLIENT routes, with the line that supports the stated reason:

| route | reason | evidence |
|-------|--------|----------|
| `shop/[slug]/cart` | the basket lives in localStorage | `components/storefront/cart-provider.tsx:77` `window.localStorage.getItem(cartStorageKey(slug))`, consumed by the page at `:6`/`:15` via `useCart` |
| `shop/[slug]/checkout` | Stripe Elements mounts in the browser | `:7-8` imports `loadStripe` / `Elements, PaymentElement, useStripe, useElements`; `:143-144` calls the hooks; `:228` "Your card details never touch our servers" |
| `shop/[slug]/orders/[orderNumber]` | proves ownership with an email read from localStorage | `:70` `localStorage.getItem(\`jtoye-checkout-email-${slug}\`)` |
| `shop/auth/callback` | exchanges a single-use `?code=` from the URL | `:35` `searchParams.get("code")`; the docblock at `:38-41` records why a second exchange fails |
| `track` | pre-fills from sessionStorage and polls after load | `:109` `sessionStorage.getItem("jtoye-track-email")` |
| `auth/signin` | starts the operator OIDC flow in the browser | `:104` `signIn("keycloak", { callbackUrl: "/dashboard" })` |

The 15 dashboard CLIENT reasons share one verified fact: every one of those pages reaches
core through the `lib/api-client` family (confirmed by name for all 15), and that client's
bearer token comes from a **browser** `getSession()` — `frontend/lib/api-client.ts:31-33`.
Their reasons therefore also carry the conversion constraint (forward the caller's token;
RLS is the enforcement point, ASVS V3/V4), which is threat **T-34-07-04**'s mitigation:
the next converter meets the constraint in the manifest before writing code.

The 13 STATIC reasons are asserted by the gate itself (R-2c), not merely written: no page in
the tree calls `fetch(` except `track/page.tsx:145` (a CLIENT route), and only
`shop/page.tsx` and `shop/[slug]/page.tsx` import the storefront loader.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] My own comment made `check-gate-enforcement.sh` unable to detect this gate's unwiring**

- **Found during:** Task 3's wiring arm.
- **Issue:** `check-gate-enforcement.sh` answers "is this gate wired?" by searching workflow
  files for the script's filename, and it does **not** strip comments. The `frontend-e2e`
  comment I had just written named `scripts/check-ssr-coverage-contract.sh`. With the
  `ops-contracts` step **deleted**, the meta-gate still printed
  `PASS: every gate either runs in CI or has a declared reason it cannot.` at **rc=0**. The
  arm that was supposed to prove the wiring mattered could not fail.
- **Fix:** the `frontend-e2e` comment now names the gate by description and by its manifest
  (`scripts/gates/ssr-routes.conf`); the only occurrence of the script's filename in the
  workflow is the `run:` line. The comment states the measurement so the constraint survives
  the next edit. Re-run of the same arm: **rc=1**, naming the script.
- **Files modified:** `.github/workflows/ci-cd.yaml`
- **Commit:** `364ec206`

**2. [Rule 3 - Blocking] The prompt's base SHA did not exist**

- **Issue:** the worktree instructions named base `8285d6f5f0d0c0b7f2a08e2a35317308ac82449e`;
  `git cat-file` rejected it. The short prefix resolves to
  `8285d6f5e63e6168d514aee18bd8be2ec160087c` ("docs(phase-34): wave 1 complete"), and the
  worktree was sitting on main at `896c8828` with `frontend/e2e/helpers/served-html.ts`
  absent — the 34-01 output this plan depends on.
- **Fix:** confirmed `896c8828` is an ancestor of `8285d6f5` with nothing on main outside it
  (so the reset could only fast-forward), then reset to the resolved SHA and verified
  `served-html.ts` present, as the prompt instructed.

**3. [Rule 3 - Blocking] `check-e2e-typecheck.sh` VOIDed on a worktree with no node_modules**

- **Issue:** the plan's verification block requires it; it exits **2** ("frontend/node_modules
  is absent"), which is not a pass.
- **Fix:** ran `npm ci` in `frontend/` (rc=0), then the gate: **rc=0**,
  `PASS: 29 e2e file(s) type-check clean`. No dependency file changed —
  `git diff --name-only -- frontend/package.json frontend/package-lock.json edge-go/go.mod
  core-java/build.gradle.kts` prints nothing (T-34-07-SC).

### Not Fixed — Out of Scope, Logged

Both are in `deferred-items.md`:

1. **`check-gate-enforcement.sh` cannot tell a `run:` line from prose** — the general form of
   deviation 1. Several existing workflow comments name gates, so any of those gates may be
   counted as wired by prose alone. The fix (strip comments before the by-name search)
   re-classifies all 37 gates, is another gate's behaviour, and is outside this plan's
   `files_modified`.
2. **`docs/metrics.json` is behind wave 1** — `docs-freshness.sh` exits 1 on the base commit
   and on every 34-07 commit; measured jest 1230→1272, playwright 113/22→120/25, total
   3188→3237. **Not 34-07's**: the three files this plan changed contain no test block, and
   `34-01-SUMMARY.md` already ruled that **34-10 is the manifest's single writer** and that
   this gate stays red until it lands. The entry records the numbers so the next reader does
   not re-diagnose a decided question.

## Threat Model Outcomes

| id | disposition | evidence |
|----|-------------|----------|
| T-34-07-01 classifier silently stopped working | mitigated | R-3 runs first; VOID measured on a wrong verdict **and** on a missing fixture; classifier proven against a licence-header variant |
| T-34-07-02 a gate that passes over nothing | mitigated | zero discovery VOIDs, exercised via `SSR_APP_DIR` at an empty directory |
| T-34-07-03 a gate satisfied by its own prose | mitigated | the identical tokens fail in code (rc=1) and pass in a comment (rc=0); the strict directive rule is measured 4 apart from a substring search |
| T-34-07-04 an SSR conversion using a service identity | mitigated | all 15 dashboard CLIENT reasons carry the forward-the-caller's-token constraint, sourced from `api-client.ts:31-33` |
| T-34-07-05 the stack-free job read as SSR coverage | mitigated | the `frontend-e2e` job now states, with the measured byte and occurrence table, what it covers and what it does not |
| T-34-07-06 a required job made permanently VOID | mitigated | the gate is static — no docker, curl, psql or network — so it cannot VOID for environmental reasons on a hosted runner; every VOID path names a real defect |
| T-34-07-SC package installs | mitigated | no dependency file touched; the gate uses bash, find, awk and sed only, and introduces no `jq` requirement |

## Verification

| check | result |
|-------|--------|
| `bash scripts/check-ssr-coverage-contract.sh` | **rc=0** — 38 discovered, 4 SSR / 13 STATIC / 21 CLIENT |
| every break arm | rc=1 (five contract arms) / rc=2 (six VOID arms), each naming its assertion id |
| `bash scripts/check-gate-enforcement.sh` | **rc=0** — 37 gates, 6 exempt |
| `bash scripts/check-e2e-typecheck.sh` | **rc=0** — 29 files clean |
| workflow parses | rc=0 (PyYAML 6.0.3), fail direction rc=1 on a broken copy |
| dependency files | unchanged (0 lines) |
| `bash scripts/docs-freshness.sh` | rc=1 — **pre-existing**, owned by 34-10 (see Deviations) |

## Success Criteria

- [x] A default-deny manifest gate runs in CI and fails on an undeclared page, a stale
      declaration, a class mismatch, an empty reason and a deleted raw-HTML assertion —
      **each shown to fail**, with real output recorded above.
- [x] Every `page.tsx` is classified, and every client route carries a reason verified
      against the file (#507's last acceptance criterion).
- [x] The classifier is proven in both directions in the same run (R-3, first), and a
      zero-discovery result VOIDs.
- [x] The per-PR stack-free E2E job states, with measured numbers, which surfaces its green
      covers and which it does not (#542's third acceptance criterion).

## Known Stubs

None. The gate, the manifest and the CI wiring are all live; nothing is placeholdered.

## Self-Check: PASSED

Every artifact this summary names exists on disk (`scripts/check-ssr-coverage-contract.sh`
401 lines executable 755, `scripts/gates/ssr-routes.conf` 160 lines,
`.github/workflows/ci-cd.yaml`, this file, `deferred-items.md`), and all six cited commits
resolve in `git log --all` (`3a17a01d`, `a91a8c79`, `c7001f9b`, `364ec206`, `1fddcefe`,
`08b98f37`).

Every file:line citation was re-read rather than remembered:
`cart-provider.tsx:77` `window.localStorage.getItem(cartStorageKey(slug))` ·
`checkout/page.tsx:228` "Your card details never touch our servers" ·
`orders/[orderNumber]/page.tsx:70` ``localStorage.getItem(`jtoye-checkout-email-${slug}`)`` ·
`auth/callback/page.tsx:35` `searchParams.get("code")` ·
`track/page.tsx:109` `sessionStorage.getItem("jtoye-track-email")` ·
`auth/signin/page.tsx:104` `signIn("keycloak", …)` ·
`api-client.ts:31-33` `getSession()` → `Authorization: Bearer` ·
`ssr-coverage.spec.ts:242` the test title that still names `/shop/orders` after its
raw-HTML call was removed, which is why the R-2b arm is the load-bearing one.
`rg -uu -c '^CLIENT dashboard/' scripts/gates/ssr-routes.conf` → 15, matching the count
claimed above.
