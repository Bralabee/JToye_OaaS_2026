---
phase: quick
plan: 261026-9qm
subsystem: frontend-dashboard, frontend-e2e, core-java-image, docs
tags: [ci, scheduled-reds, sse, void-dialog, e2e, session-cache, trivy, curl, cve]
status: complete
requires: []
provides:
  - order detail page background refresh (SSE and refund re-reads keep the panel and dialogs mounted)
  - kitchen lapse spec SESSION_BURST_WAIT_MS with a jest cross-check against SESSION_BURST_TTL_MS
  - core-java runtime image upgrades curl/libcurl (CVE-2026-92392)
affects:
  - E2E Nightly 'G-37-6 item 4' and kitchen-session-lapse 'lapse...' / 'INSTRUMENT...'
  - Base Image Freshness and the ci-cd.yaml Trivy image gate on the next main push
tech-stack:
  added: []
  patterns:
    - "ref-keyed background refresh: a read for the id already on screen never sets loading or error"
    - "latest-callback ref (toastRef), copied from hooks/use-order-events.ts onEventRef"
    - "jest reads an E2E spec as text to tie a wait to a product constant"
key-files:
  created:
    - frontend/app/dashboard/orders/[id]/__tests__/background-refresh.test.tsx
    - .planning/quick/261026-9qm-fix-three-scheduled-ci-reds-core-java-cu/evidence/order-detail-sse-tdd.txt
    - .planning/quick/261026-9qm-fix-three-scheduled-ci-reds-core-java-cu/evidence/kitchen-burst-wait.txt
    - .planning/quick/261026-9qm-fix-three-scheduled-ci-reds-core-java-cu/evidence/core-java-curl.txt
  modified:
    - frontend/app/dashboard/orders/[id]/page.tsx
    - frontend/lib/api-client.ts
    - frontend/lib/__tests__/api-client-session-lapse.test.ts
    - frontend/e2e/kitchen-session-lapse.spec.ts
    - core-java/Dockerfile
    - docs/metrics.json
    - CLAUDE.md
    - AGENTS.md
    - README.md
    - docs/CHANGELOG.md
decisions:
  - "Order page: background vs foreground is keyed on the id of the order on screen (shownOrderIdRef), so a navigation to another id still gets a fresh load with a spinner"
  - "A failed background refresh keeps the order and raises one destructive toast (403 access ended, 404 no longer available, otherwise could not refresh; description 'Showing what was last loaded.')"
  - "The kitchen lapse wait lives inside dropAppSession, not at each call site; SESSION_BURST_TTL_MS stays 2_000 (#963 cache intended)"
  - "curl/libcurl are NAMED in the existing apk upgrade list (never a blanket upgrade)"
metrics:
  duration: "~35 min"
  completed: 2026-10-26
actuals:
  tokens: 9000
  tasks: 3
  commits: 5
plan_head_before: 5fa3a955bec27a81fa8e65da1d1eb47bf4dc26fd
plan_head_after: 7c8d443e7a1cc8437cb5838c82e60ff9bdf8a34b
---

# Quick 261026-9qm: Three Scheduled-CI Reds Fixed Summary

The order detail page now refreshes the order in place when a live update arrives, so an open Void dialog and the typed reason survive. The kitchen lapse E2E waits 2.5 s, longer than the api-client's 2 s session cache. The core-java runtime image now upgrades curl and libcurl to 8.23.0-r0 (CVE-2026-92392).


## Commits

| Task | Commit | Subject |
|------|--------|---------|
| 1 (red) | e34d8292 | test(dashboard): an order-page live update must not unmount an open dialog (red) |
| 1 (fix) | 6e7dad8b | fix(dashboard): a live update refreshes the order page in place, keeping an open dialog |
| 2 | d5af11ca | test(e2e): the kitchen lapse spec waits out the api-client session burst cache |
| 3 (fix) | fe8782b5 | fix(security): upgrade curl and libcurl in the core-java runtime image (CVE-2026-92392) |
| 3 (docs) | 7c8d443e | docs: counts and changelog for the three scheduled-CI fixes |

I read every message back with `git log -1 --format=%B`. None has an attribution trailer or footer.

## Evidence (in `evidence/`)

### order-detail-sse-tdd.txt (Task 1)

- **RED on the pre-fix page.tsx, jest rc=1.**
  - A failed: the spinner rendered and the page was replaced.
  - B failed: the error page "Failed to load order. Please try again." replaced the order.
  - C passed.
- **GREEN, four suites, rc=0.** The suites are background-refresh, detail-tier, void-order-dialog and OrderDetailPanel: 4 suites, 76 tests. jest --json shows all of A, B and C passed. The output has no act() warnings.
- **detail-tier.test.tsx is unmodified.** `git diff --exit-code origin/main` on it gives rc=0.
- **Break arm B1**, run after commit 6e7dad8b. With the predicate changed to `shownOrderIdRef.current !== null`, only Test C goes RED (no spinner on a different route id), rc=1. I restored with `git checkout`; `git diff --exit-code` gives rc=0, and all four suites are green again (76/76).
- **tsc and eslint:** tsc rc=0, eslint rc=0.
- **Full frontend jest after all commits:** 274/274 suites and 3217/3217 tests, rc=0.

### kitchen-burst-wait.txt (Task 2)

- **Three api-client suites green, rc=0:** 26 tests. The new block passed.
- **`playwright test --list`, rc=0:** "Total: 10 tests in 1 file".
- **tsc and eslint:** both rc=0.
- **`SESSION_BURST_TTL_MS` is still 2_000.** The api-client diff against origin/main is only the `__testing` addition.
- **Break arms**, run after commit d5af11ca. Each one was restored with `git checkout`, `git diff --exit-code` gave rc=0, and the suite was green again (6/6):
  - A: wait set to 1_500 → RED (Expected > 2000, Received 1500), rc=1.
  - B: the waitForTimeout line removed → RED (toContain), rc=1.
  - C: TTL set to 3_000 → RED (Expected > 3000, Received 2500), rc=1.
  - D (extra): constant renamed → RED (`declared` is null), rc=1.

### core-java-curl.txt (Task 3)

- **(1) Static list check:** origin/main gives "MISSING curl", rc=1. The tree gives ALL_NAMED, rc=0.
- **(2) The real image (RED).**
  - `docker pull ghcr.io/bralabee/jtoye-core-java:latest` succeeded, rc=0, anonymous. It has curl-8.22.0-r0 and libcurl-8.22.0-r0 on Alpine 3.24.3.
  - The local jtoye_oaas_2026-core-java:latest also has 8.22.0-r0.
  - eclipse-temurin:25-jre-alpine has no curl at all.
- **(3) GREEN.**
  - The anchored awk extract into an empty scratch dir is 47 lines: the planned 38 plus the 9-line 2026-10-26 entry. It ends with the RUN line and its `&& apk upgrade ... curl libcurl` continuation.
  - `docker build --pull --no-cache` succeeded, rc=0.
  - `apk info -v` holds exactly curl-8.23.0-r0 (rc=0) and libcurl-8.23.0-r0 (rc=0).
- **(4) Old-list/new-list pair, built on top of the published image.**
  - The old list leaves 8.22.0-r0 and the new list lands 8.23.0-r0.
  - A fresh, cache-busted `apk add` would also install 8.23.0-r0. Naming the packages in the upgrade list keeps the fixed version explicit and also invalidates the cached layer.
- **(5) Trivy, done.** aquasec/trivy:0.70.0 with the CI gate's flags.
  - Published image: rc=1, HIGH x2 (curl and libcurl, CVE-2026-92392, fixed in 8.23.0-r0). app.jar is clean.
  - Runtime-stage proof image: rc=0. No other fixable HIGH or CRITICAL package, so nothing else needed naming.
- **Task 3 verify block:** every value 0, verify_rc=0.
  - Fail controls: the CVE grep over origin/main's CHANGELOG gives rc=1, and over origin/main's Dockerfile gives rc=1.
- **Docs gates.**
  - docs-freshness: rc=1 before `--write`, rc=0 after.
  - check-doc-metrics: rc=1 before the prose edits (10 FAIL lines across README.md, CLAUDE.md and AGENTS.md), rc=0 after (37/37).
  - check-branch-behind-base: rc=0, 0 behind.
  - check-changelog-contract: rc=0.
- **(6) Cleanup.** I removed the jtoye-curl-proof:* tags after verify passed and did not touch jtoye_oaas_2026-core-java:latest. The pulled ghcr image and aquasec/trivy:0.70.0 stay in the local store.

## Measured counts

- jest_blocks went from 3213 to 3217 and jest_files from 273 to 274.
- total_logical_invocations went from 6637 to 6641.

## Optional runs

- **Trivy: RUN.** Results are in core-java-curl.txt, step (5).
- **Live Playwright kitchen-session-lapse against a rebuilt frontend: NOT RUN.** It would mean rebuilding the frontend image on the shared running compose stack, which also retags dependency images, and it needs vendor credentials. The fix is not claimed proven live; the next E2E Nightly is the live check.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] A late response for a route id the page has left is dropped**
- **Found during:** Task 1 (GREEN)
- **Issue:** With refreshes now in place, a slow background read for order A could finish after navigation to order B had loaded, and setOrder would paint A over B. The same race already existed for foreground reads.
- **Fix:**
  - `routeIdRef` is updated in an effect on `[orderId]`.
  - Success and failure handlers return early when `routeIdRef.current !== orderId`.
  - A foreground load also clears `shownOrderIdRef` to null while nothing is on screen. Without that, navigating from A to B (B fails) and back to A would be treated as a background refresh, and B's error page would stay up.
- **Files modified:** frontend/app/dashboard/orders/[id]/page.tsx
- **Commit:** 6e7dad8b

**2. [Extra proof] Break arm D (rename)** was added to Task 2. The plan says the cross-check fails closed on a rename, and arms A to C did not exercise that. It went RED (rc=1) and was restored.

**3. Structure.** The Task 2 jest block sits in its own `describe` inside api-client-session-lapse.test.ts. It is still exactly one new `it(`.

## Cross-cutting quality contracts

- Web performance: the order page no longer flashes the spinner on each live update or refund refresh. No dependency was added.
- SEO, agent-readiness and client-persisted identity: N/A (authenticated dashboard, no API change, no client storage touched).
- Security: T-9qm-01 (mitigated, proven by apk info and Trivy) and T-9qm-03 (mitigated, Test A plus break arm B1). T-9qm-02 and T-9qm-04 are accepted as planned.
- Falsifiable evidence: every check was shown failing first, as listed above.
- Runtime parity: no runtime was handed back or rebuilt. The local core-java and frontend images are untouched.

## Handback for the orchestrator

- Open the PR with a `fix(...)` title, for example "fix: three scheduled-CI reds (curl CVE-2026-92392, order page keeps an open dialog through a live update, kitchen lapse spec waits out the session cache)".
- Then append `(#NNNN)` to the new CHANGELOG heading in a follow-up commit (check-changelog-cites-pr.sh P-1).
- Nothing has been pushed. The untracked `.planning/ui-reviews/` is not this plan's.

## Self-Check: PASSED

- All five commits are on HEAD: FOUND e34d8292, 6e7dad8b, d5af11ca, fe8782b5, 7c8d443e.
- All three evidence files exist, and so does background-refresh.test.tsx.
