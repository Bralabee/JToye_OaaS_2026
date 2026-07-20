# HANDOFF — Phase 23 (Vendor-Scoped Access) code-complete, gap closure PLANNED but NOT executed

**Created:** 2026-07-20
**Branch:** `feature/phase-23-vendor-scoped-access` (HEAD `077afd0`) — **NO UPSTREAM, 41 commits unpushed**
**Milestone:** v2.3 Vendor Ops + AI Interleaved

---

## Current goal

Close Phase 23's verified authorization gaps. Plans 23-01..23-07 shipped the feature; verification
found it **not shippable**. Gap plans 23-08..23-15 exist and are checker-approved but **none have run**.

## Status

| Item | State |
|---|---|
| Plans 23-01..23-07 | ✅ Executed, committed, SUMMARYs on disk |
| `23-VERIFICATION.md` | ❌ `status: gaps_found` (3/5) |
| `23-REVIEW.md` | 8 Critical / 12 Warning / 3 Info |
| Gap plans 23-08..23-15 | ✅ Written, committed, plan-checker **PASS** — **NOT executed** |
| `23-SECURITY.md` | ❌ Missing (`security_enforcement=true`) |
| Requirements | VSA-01 ✅ · VSA-02 ❌ · VSA-03 ✅ · VSA-04 ❌ · MOBL-01 ✅ |
| docs-freshness | ✅ green at **1511** |

## Resume instructions (exact)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git rev-parse --abbrev-ref HEAD        # expect feature/phase-23-vendor-scoped-access
bash scripts/docs-freshness.sh          # expect: OK ... 1511

/gsd:execute-phase 23 --gaps-only       # runs 23-08..23-15 in 5 waves
```
Expected: waves 1→5; **23-14 halts on a blocking decision checkpoint** (see below). Then
`/gsd:secure-phase 23` (produces the missing SECURITY.md), then re-verify.

## The 3 confirmed bypasses being fixed (verified against source, not assumed)

1. **CR-01 — `@Cacheable` short-circuits the gate.** `ShopService.java:92` / `ProductService.java:108`
   wrap methods whose body calls `require()` (`:100` / `:118`). Spring's cache interceptor runs
   *before* the body → cache hit = no gate. Key `tenant:{tenantId}:{method}:{params}` has **no user
   component**. Fixed by 23-10.
2. **CR-02 — STOMP KDS unscoped.** `TenantChannelInterceptor.validateSubscription` validates only
   `parts[3]` (tenantId) of `/topic/kitchen/{tid}/{shopId}`; zero refs to `ShopAccessService`.
   23-03 gated SSE but STOMP is the real transport. Fixed by 23-11.
3. **CR-03 — fail-OPEN system principal.** `ShopAccessService.isSystemPrincipal()` (`:298-309`)
   returns true on absent auth *or* non-UUID `sub`; `isGroupAdmin():144` short-circuits on it;
   `StaffController` has no `@PreAuthorize`. Contradicts locked **D-04**. Fixed by 23-08.

Plus CR-04 NPE→500, CR-05 role downgrades silently no-op, CR-06 last-GA check-then-act race,
**CR-07 strict-scoping tightens nothing** (design defect), CR-08 GA detection mismatch.

## Key decisions + rationale

- **Executed sequentially on the main tree, NOT in git worktrees.** `frontend/node_modules` (822 MB)
  is gitignored and absent from worktrees, so `npm run build`/jest would break there. Dependency
  chain was near-linear anyway. Keep this for gap execution.
- **Every gap plan carries a falsifiability gate** — the executor must demonstrate each new test RED
  against pre-fix code (`git stash push -- core-java/src/main/java`) and paste output into the SUMMARY.
  This exists because the first pass shipped green tests that *structurally could not fail*.
- **23-10 supplies its own `CacheManager`/key-generator beans.** `CacheConfig` is `@Profile("!test")`
  and the enforcement IT is `@ActiveProfiles("test")` → caching is OFF in tests, so the CR-01 bug class
  is invisible by construction. A fix "verified" under the plain test profile proves nothing.
- **CR-03 scoped narrower than the review proposed, deliberately.** 62 test files call gated services
  with `TenantContext` but no `SecurityContext`; the review's `asSystem()` ThreadLocal flip would
  cascade through all of them. 23-08 keeps only the `auth == null` branch and records the retained
  branch as accepted threat T-23-08-06.
- **False-complete markers were reverted** (commit `9c5ae72`): the last plan's executor had ticked
  ROADMAP Phase 23 and REQUIREMENTS VSA-02/VSA-04 as Complete. `phase.complete` was never run.

## ⏸ Blocking checkpoint in 23-14 (needs a human answer)

CR-07: enabling `strict-scoping` tightens nothing because D-04's JIT already wrote **permanent**
GROUP_ADMIN rows. 23-14 is `autonomous: false`, adds **V57** (`grant_source`) to distinguish
JIT-provisioned rows from explicit grants, and revises locked **D-04 / D-12 / D-05**. Three options:
accept / accept-no-bootstrap / reject. `reject` stops the plan and leaves VSA-02 open rather than
overclaiming.

## Carve-outs that need a NORMAL-footprint session

Low-footprint mode was in force after a desktop-session crash (no docker builds / Testcontainers /
Playwright / gradle). These are deferred and **not** claimed as done:

- `./gradlew :core-java:updateOpenApiSnapshot` + commit — **CI-BLOCKING**: `docs/api/openapi-snapshot.json`
  lacks the 3 `/api/v1/staff` endpoints; `OpenApiSnapshotTest` runs check-mode in `integrationTest`.
- Full `./gradlew test integrationTest`, `npx playwright test`.
- Live-browser pass over `/dashboard/staff` with real GROUP_ADMIN vs non-GA sessions.

## Deferred, recorded (not dropped)

- **WR-04** — Products/marketing narrow client-side over one paginated server page: wrong counts,
  false empty states, unreachable rows. User-visible, **not** a security bypass (set is already
  grant-scoped server-side). Needs new `?shopId=` API surface + 2 screen reworks → its own plan.
- WR-03, IN-01 and three others — listed in 23-15 task 3 with reasons.

## Environment state

- Branch `feature/phase-23-vendor-scoped-access`, tree **clean**, HEAD `077afd0`.
- **41 commits unpushed, no upstream, no PR** — secret-path scan clean, no hardcoded secrets.
- Docker Compose full stack **up** (10 services healthy), frontend `:3000`. Frontend image was
  rebuilt with the 23-05 switcher; **core-java container is stale** (pre-23-01, no V52 migration).
- Gradle wrapper at repo **root** (`./gradlew`); `buildDir` is `build-local`; Testcontainers tests are
  `@Tag("testcontainers")` and run via `./gradlew :core-java:integrationTest` (the `test` task excludes them).
- Last test results: unit suite green (38s); access-package integration 20 tests / 0 failures;
  frontend 352/352 jest + tsc clean; docs-freshness OK at 1511.

## Failed approaches / traps hit

- **Two long agents in parallel → API stream-idle timeouts.** The code-reviewer and verifier both
  stalled mid-write. Recovered via `SendMessage` resume (context intact) — do that rather than
  re-running the analysis. Run long analysis agents **one at a time**.
- **Bash tool default timeout is 2 min** — Testcontainers runs get SIGTERM'd. Pass an explicit
  `timeout` (used 540000 ms).
- `python3` is blocked in conda base by the `block-base-python` hook — use shell tooling or a named env.
