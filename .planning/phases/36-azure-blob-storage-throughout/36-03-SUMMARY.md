---
phase: 36-azure-blob-storage-throughout
plan: 03
subsystem: frontend-security
tags: [csp, img-src, next-config, remotePatterns, ssrf, azurite, jest]

requires:
  - phase: 36-01
    provides: "public image URL shape http://localhost:10000/devstoreaccount1/jtoye-images/<key> (storage.blob.public-url)"
  - phase: 36-02
    provides: "Azurite on loopback 10000 in every local runtime"
provides:
  - "CSP img-src admits http://localhost:10000 in place of the retired dev origin; no other token added"
  - "next.config.mjs images.remotePatterns = exactly one exact origin/path (http, localhost, 10000, /devstoreaccount1/jtoye-images/**)"
  - "five jest assertions that fail on the retired origin (any host, port 9000), on any img-src wildcard other than https://*.stripe.com, on any blob.core.windows.net source, and on any remotePatterns change or wildcard hostname"
  - "image fixtures and comments in the Azurite URL shape"
affects: [36-12, 36-13, 36-16, 36-17, 36-18]

actuals:
  tokens: 2784
  tasks: 2
  commits: 4
plan_head_before: 3445ac69b8c16144fbdf1d89a0f65e6af8d7314c

tech-stack:
  added: []
  patterns:
    - "Assert a retired origin absent by PORT on any host, not by one exact string: strictly stronger, and keeps the forbidden literal out of the tree so residue greps need no test allowlist entry"
    - "remotePatterns is pinned by deep equality in a test, so any added host (wildcard or not) is a deliberate test edit"

key-files:
  created: []
  modified:
    - frontend/lib/security-headers.ts
    - frontend/next.config.mjs
    - frontend/__tests__/csp-headers.test.ts
    - frontend/__tests__/__snapshots__/header-snapshot.test.ts.snap
    - frontend/components/ui/__tests__/asset-image.test.tsx
    - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
    - frontend/components/ui/asset-image.tsx
    - frontend/e2e/public-layout.spec.ts

key-decisions:
  - "The retired-origin test asserts no img-src source on port 9000 on any host, instead of not.toContain of the exact retired string. The exact form missed 127.0.0.1:9000 (measured), and naming the literal made Task 2's frontend-wide residue grep unsatisfiable (and would have needed a 36-16 allowlist entry)"
  - "No *.blob.core.windows.net source in CSP: the staging/production raw Blob origin (D-06) is already covered by the existing https: source"
  - "remotePatterns carries no production host: no shipped code imports next/image (verified), so the list governs only the /_next/image optimizer's server-side fetch surface"

patterns-established:
  - "Retired-origin absence asserted by port, any host"

requirements-completed: [BLOB-08]

coverage:
  - id: D1
    description: "CSP img-src admits the Azurite dev origin and nothing broader"
    requirement: "BLOB-08"
    verification:
      - kind: unit
        ref: "frontend/__tests__/csp-headers.test.ts#img-src admits the Azurite dev origin and nothing broader (Phase 36) (3 tests); four break arms red, closing clean 22/22"
        status: pass
      - kind: unit
        ref: "frontend/__tests__/header-snapshot.test.ts; token-level diff 3445ac69..HEAD = exactly -http://localhost:9000 +http://localhost:10000"
        status: pass
    human_judgment: false
  - id: D2
    description: "next.config.mjs remotePatterns holds one exact Azurite origin/path, no wildcard hostname (T-36-10)"
    requirement: "BLOB-08"
    verification:
      - kind: unit
        ref: "frontend/__tests__/csp-headers.test.ts#next.config.mjs images.remotePatterns (Phase 36, T-36-10) (2 tests); arm 3 reds both"
        status: pass
    human_judgment: false
  - id: D3
    description: "Image fixtures and comments use the Azurite URL shape; the typecheck gate builds"
    requirement: "BLOB-08"
    verification:
      - kind: unit
        ref: "npx jest components/ui/__tests__/asset-image.test.tsx components/dashboard/media/__tests__/ReviewQueue.test.tsx (21/21); arm stripping /devstoreaccount1 in AssetImage reds 2"
        status: pass
      - kind: other
        ref: "cd frontend && npm run build (rc=0; planted type error -> rc=1); npx tsc --noEmit (rc=0; planted error -> rc=2)"
        status: pass
    human_judgment: false

duration: 6min
completed: 2026-09-28
status: complete
---

# Phase 36 Plan 03: Frontend Image Origins on Azurite Summary

**The CSP `img-src` dev origin moved from the retired `http://localhost:9000` to Azurite's `http://localhost:10000`, and `remotePatterns` now holds one exact entry for `/devstoreaccount1/jtoye-images/**`. Five jest tests turn red if the retired port comes back on any host, if any wildcard other than `https://*.stripe.com` or any `blob.core.windows.net` source appears, or if `remotePatterns` changes or gains a wildcard hostname.**

## Performance

- **Duration:** about 6 min
- **Started:** 2026-09-28T22:13:42Z
- **Completed:** 2026-09-28T22:19:36Z
- **Tasks:** 2
- **Files modified:** 8 (73 insertions, 20 deletions)

## Accomplishments

- `security-headers.ts`: one img-src token changed (`http://localhost:9000` became `http://localhost:10000`). The `upgradeInsecure` comment now names Azurite as the http image source that an unconditional upgrade would break. Nothing else was added. The D-06 raw Blob origin is covered by the existing `https:` source.
- `next.config.mjs`: `remotePatterns` is exactly `[{protocol:'http', hostname:'localhost', port:'10000', pathname:'/devstoreaccount1/jtoye-images/**'}]`. The commented `*.amazonaws.com` wildcard example is deleted. A new one-line comment says staging/production render through a plain `<img>` and that any future entry must be an exact hostname.
- `csp-headers.test.ts`: five new tests.
  - Azurite origin present.
  - Retired origin absent: no source on port 9000, on any host.
  - Only wildcard is `https://*.stripe.com`, and no Blob host.
  - `remotePatterns` deep-equals the one entry.
  - No `remotePatterns` hostname contains `*`.
- The header snapshot was regenerated with its documented command (`npm test -- -u header-snapshot`). Its diff is exactly one token.
- Fixtures in `asset-image.test.tsx` (l.12, l.40) and `ReviewQueue.test.tsx` (l.88-89) now use `http://localhost:10000/devstoreaccount1/jtoye-images/...`, with the same tenant/media/id segments. The `asset-image.tsx` and `public-layout.spec.ts` comments now name Azurite. No code changed.

## Task Commits

1. **Task 1 RED:** `d720daae` test(36-03): add failing tests pinning image origins to Azurite and nothing broader
2. **Task 1 GREEN:** `3f4e0a75` feat(36-03): admit the Azurite image origin in CSP img-src and remotePatterns, nothing broader
3. **Task 1 test strengthening (deviation 1):** `359f65e6` test(36-03): assert the retired image origin absent by port, on any host
4. **Task 2:** `60d1075c` test(36-03): move image fixtures and comments to the Azurite URL shape

**Plan metadata:** recorded in the SUMMARY commit that follows.

## Evidence (both directions per criterion)

### Task 1: RED
`npx jest __tests__/csp-headers.test.ts` gave rc=1: **20 run, 17 passed, 3 failed**. The three failures were the target tests, each failing on its assertion:
- "admits the Azurite dev origin" failed with `Expected value: "http://localhost:10000"` / `Received array: ["'self'", "data:", "blob:", "https://*.stripe.com", "https:", "http://localhost:9000"]`.
- The retired-origin test failed.
- The `remotePatterns` exact test failed (`- Expected - 2 / + Received + 2`).

The two wildcard guards passed on this tree by construction. They are proven by the arms below.

The jest `--json` result was projected to TAP line for line, with nothing filtered, and classified with `gsd_run check tdd-red-evidence`. Verdict: **RED_EVIDENCE_OK** (reason `target_test_failed`, tests=20, fail=3).

### Task 1: GREEN, break arms, snapshot
Verify command: `npx jest __tests__/csp-headers.test.ts __tests__/header-snapshot.test.ts`.

The bracket ran against the committed state: clean, then the arms, then clean again. Each restore was checked by `git hash-object` against `HEAD:` blob.

| Run | Input | Result |
|-----|-------|--------|
| clean (open) | HEAD | rc=0, 22/22 |
| arm 1 | img-src += `https://*.blob.core.windows.net` | rc=1, 2 failed: "keeps https://*.stripe.com as its ONLY wildcard source, and names no Blob host" + the CSP snapshot |
| arm 1b | img-src += `https://jtoyemedia.blob.core.windows.net` (non-wildcard) | rc=1, 1 failed: the same test, on its **Blob-host** expectation (arm 1 stopped at the wildcard expectation, so this arm proves the second expectation separately) |
| arm 2 | img-src += the retired origin | rc=1, 2 failed: the retired-origin test + the snapshot |
| arm 3 | remotePatterns hostname -> `*.blob.core.windows.net` | rc=1, 2 failed: "holds exactly the Azurite public-container origin/path" + "has no entry whose hostname contains a wildcard" |
| clean (close) | restored, both files == HEAD blob | rc=0, 22/22 |

After deviation 1, the port-based test was re-proved:
- `http://localhost:9000`: red.
- `http://127.0.0.1:9000`: red (the exact-string form had stayed green here).
- `https://localhost:9000/`: red.
- `http://localhost:19000`: **green**. This is the negative control, and it shows no false positive.
- Closing run: 20/20, and the file matches HEAD.

Snapshot, token-level: `git diff --word-diff=porcelain --word-diff-regex='[^ ;]+' 3445ac69..HEAD -- ...header-snapshot.test.ts.snap` prints exactly `-http://localhost:9000` / `+http://localhost:10000`, and numstat is `1 1`. The regenerated snapshot is therefore evidence of exactly the intended change and nothing else.

Acceptance greps:
- `git grep -n 'localhost:9000' -- frontend/lib frontend/next.config.mjs`: **rc=1** on HEAD. Fail direction: the same grep at base `3445ac69` returned rc=0 with the two security-headers.ts hits (l.37, l.94).
- `git grep -n 'blob.core.windows.net' -- frontend/lib frontend/next.config.mjs`: **rc=1** on HEAD. **On its own this criterion is vacuous**: it is also rc=1 at base, because nothing there ever named the host. Fail direction shown instead during arm 1b: the same grep against the working tree returned rc=0 with the planted l.94 line. Positive control: `git grep -c 'localhost:10000'` over the same paths gives rc=0 (2 hits).

### Task 2
- Baseline before the edits: the two suites ran 21/21 on the base fixtures.
- After: `npm run build` rc=0 ("Finished TypeScript"). Then the two suites ran rc=0, **21/21**.
- Build fail direction: a planted `const __tsArm: number = "not a number"` in `asset-image.tsx` gave build **rc=1** (`asset-image.tsx(206,7): error TS2322`). Restored by sha256.
- `tsconfig.build.json` excludes tests, so the bare `npx tsc --noEmit` (the CI step that type-checks tests) was also run: rc=0. Its fail direction: a planted error in `csp-headers.test.ts` gave **rc=2** with 1 `error TS2322`. Restored and matches the HEAD blob.
- Fixture arm: rewriting AssetImage's `src` to drop `/devstoreaccount1` reds exactly the 2 src-pinning tests. **ReviewQueue asserts nothing about the URL** (its tests check the review actions). Its green run shows only that the fixture is still valid data, not anything about the URL shape.
- `git grep -n -i -E 'localhost:9000|minio' -- frontend`: **rc=1** on HEAD. Fail direction: the same grep at base `3445ac69` returned rc=0 with 10 hits across 6 files. A wider search that includes untracked files and the fresh `.next` build (`rg -uu -i --glob '!**/node_modules/**' 'localhost:9000|minio' frontend`) also gave **rc=1**. The same command sees 11 files for `localhost:10000` (positive control).
- it/test block counts, from `git show 3445ac69:<file>` to a scratch file (rc=0) compared with the committed file: asset-image **11 -> 11**, ReviewQueue **10 -> 10**. The awk counter's 11+10 matches jest's own 21, so the counter is not blind.

### Plan-level
- Full frontend jest: rc=0, **172 suites, 1878 tests, 1878 passed, 0 failed, 0 pending**.
- eslint on all 7 changed source/test files: rc=0, no problems. The fail direction was not run for eslint, which is not a plan criterion, so this is **unverified** as a check.
- Playwright: `e2e/public-layout.spec.ts` changed only in a comment. **No Playwright spec was run.** The stack is down, and the delivered-runtime and browser proofs belong to 36-12/36-13/36-18.

## Decisions Made

See `key-decisions` in the frontmatter. The load-bearing one is deviation 1.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] The plan's two retired-origin criteria contradicted each other; the test was changed to a strictly stronger form**
- **Found during:** Task 2, acceptance criteria.
- **Issue:** Task 1's behaviour, as written ("img-src tokens do not include `http://localhost:9000`"), needs a test that names the retired origin literally. Task 2's criterion requires `git grep -i -E 'localhost:9000|minio' -- frontend` to print only rc=1. Both cannot hold. This is the known vacuous shape "a rule that must name the token it forbids". 36-16's repo-wide residue gate (R-1 includes `localhost:9000`) would also have needed an allowlist entry for this test. The exact-string form was also weak: it stayed green with `http://127.0.0.1:9000` appended (measured).
- **Fix:** the test now asserts `imgSrcTokens().filter(t => /:9000(?:\/|$)/.test(t))` equals `[]`. The comment explains why.
- **Files modified:** `frontend/__tests__/csp-headers.test.ts`
- **Verification:** red on `localhost:9000`, `127.0.0.1:9000` and `https://localhost:9000/`. Green on the `localhost:19000` negative control and on the real tree (see Evidence).
- **Committed in:** `359f65e6`

**2. [Rule 3 - Blocking, evidence tooling] RED evidence needed TAP**
- **Found during:** Task 1 RED.
- **Issue:** `gsd_run check tdd-red-evidence` parses TAP, and jest does not emit it.
- **Fix:** the real `jest --json` result was projected to TAP with a scratch converter (`/tmp/claude-36-03/jest2tap.cjs`). It maps every assertion 1:1, with no filtering and nothing invented. Nothing in the repo changed.
- **Committed in:** n/a (scratch only).

---

**Total deviations:** 2 (1 Rule 1, 1 Rule 3 tooling).
**Impact on plan:** Deviation 1 makes the retired-origin guard strictly stronger and lets both residue criteria (this plan's and 36-16's) hold without an allowlist entry. No scope creep.

## TDD Gate Compliance

- Task 1: RED `d720daae` (valid, RED_EVIDENCE_OK), then GREEN `3f4e0a75`, then test strengthening `359f65e6`. The sequence is compliant.
- Task 2: `tdd="true"` in the plan, but it changes only fixtures and comments. Neither component filters hosts (verified: SafeImage renders `<img>`, and no code imports next/image), so there is no behaviour to drive RED. A RED run would have been an "unexpected GREEN" by construction. It was committed as one `test(36-03)` commit. Falsifiability is shown by the AssetImage `src` arm instead. **No RED commit exists for Task 2, deliberately.**

## Counts Moved (do not regenerate here; 36-17 owns docs/metrics.json)

- Jest `it/test` blocks: **1873 -> 1878** (+5, all in `__tests__/csp-headers.test.ts`). Jest files unchanged at 172.
- `scripts/docs-freshness.sh` is rc=1 (metrics file `jest_blocks: 1873`, tree `1878`). This is part of the inherited docs-freshness red that 36-17 owns.

## Issues Encountered

- My first break-arm filter hid the names of the failing tests. The arms were re-run with `--json` to name each failure.
- The first untracked-files residue search used `--glob '!node_modules/**'`, which does not match nested `frontend/node_modules`. It returned zod's `mini/o…` strings. Re-run with `'!**/node_modules/**'`, it returned rc=1, and a positive control was included.

## Threat Flags

None. T-36-10 and T-36-11 are mitigated as planned, each with a recorded break arm. No new surface.

## Known Stubs

None.

## User Setup Required

None.

## Next Phase Readiness

- The browser now accepts Azurite image URLs under the nonce CSP. The runtime proof (images actually rendering from `:10000`) belongs to 36-12/36-13.
- 36-16's residue gate needs **no** frontend allowlist entry for `localhost:9000` (measured: rc=1 over `frontend/`).

---
*Phase: 36-azure-blob-storage-throughout*
*Completed: 2026-09-28*

## Self-Check: PASSED

- Files present: all 8 in `key-files.modified` (checked with `[ -f ]` before this commit).
- Commits present: d720daae, 3f4e0a75, 359f65e6, 60d1075c (in `git log`). `git rev-list --count 3445ac69..HEAD` = 4 at SUMMARY time.
- Final re-run of both verify commands: Task 1 22/22, Task 2 build rc=0 + 21/21. Full suite 1878/1878.
