# HANDOFF — Phase 23 (Vendor-Scoped Access) gap closure EXECUTED; 3 follow-ups remain

**Updated:** 2026-07-21
**Branch:** `feature/phase-23-vendor-scoped-access` (tree clean) — **46 commits unpushed** (remote is at the old `b1b1bfe`), no PR
**Milestone:** v2.3 Vendor Ops + AI Interleaved

---

## Current goal

Phase 23's verified authorization gaps are **closed**. Gap plans 23-08..23-15 (planned last session) plus two discovered fixes (23-16, 23-17) all executed this session. The feature is code-complete, tested green, and **deployed to the running Compose stack**. What remains is one live human check, the security gate, and pushing/PR.

## Status

| Item | State |
|---|---|
| Plans 23-01..23-07 (feature) | ✅ shipped earlier |
| Gap plans 23-08..23-15 | ✅ **executed this session**, SUMMARYs on disk |
| 23-16 (discovered: 13 legacy `@WithMockUser` tests broke on 23-08 fail-closed) | ✅ fixed test-only; full `integrationTest` **332/0** |
| 23-17 (discovered: V57 RLS-unsafe backfill blocker — code review) | ✅ fixed (V44 tenant-loop) + regression test |
| Dashboard home-overview VSA-03 gap (user-caught) | ✅ fixed + deployed — `frontend/app/dashboard/page.tsx` now scopes order-activity via `?shopId=` |
| `23-VERIFICATION.md` | ⚠ `status: human_needed` — 5/5 auto-proven; **MOBL-01 375px live Playwright** not run (`E2E_VENDOR_PASSWORD` absent) |
| `23-SECURITY.md` | ❌ still missing (`security_enforcement=true`) |
| Requirements | VSA-01 ✅ · VSA-02 ✅ · VSA-03 ✅ · VSA-04 ✅ · MOBL-01 ⚠ (Jest 375px pass; live Playwright pending) |
| docs-freshness | ✅ green at **1574**, schema **V57** |
| Running stack | ✅ **rebuilt + redeployed** — `core-java` @ V57 (staff API 401 not 404), `frontend` with switcher + home fix |

## Resume instructions (exact — do these 3, in any order)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git rev-parse --abbrev-ref HEAD                 # feature/phase-23-vendor-scoped-access
bash scripts/docs-freshness.sh                  # expect: OK ... 1574

# 1. Close MOBL-01 → flips verification human_needed → passed:
#    (needs a real Keycloak vendor login; stack is up on :3000)
export E2E_VENDOR_PASSWORD=...                  # seed user pw is JtoyeDev!2026 (admin-user)
cd frontend && npx playwright test e2e/dashboard-mobile.spec.ts   # 375px viewport spec

# 2. Security gate (load-bearing — 8 criticals were addressed):
/gsd:secure-phase 23                            # produces 23-SECURITY.md

# 3. Back up + PR the 46 unpushed commits (secret-path scan already clean):
git push -u origin feature/phase-23-vendor-scoped-access
gh pr create --base main
```

## Key decisions made this session (with rationale)

- **User ACCEPTED the D-04/D-12/D-05 revision (23-14 checkpoint).** CR-07: strict-scoping tightened nothing because JIT wrote permanent GROUP_ADMIN rows. V57 adds `grant_source` (JIT vs OPERATOR); strict-scoping ON now de-honours JIT rows while keeping operator grants, with an oldest-JIT bootstrap admin so no tenant locks out. Full accept path shipped.
- **User chose "fix now" for the 23-16 regression** — 23-08's fail-closed correctly denies non-`Jwt` principals; 7 legacy `@WithMockUser`/non-UUID-`.jwt()` test classes were migrated to UUID-subject JWT auth (test-only; the security boundary was NOT relaxed). Only the FULL `integrationTest` task exposed it — scoped `--tests` runs hid it.
- **V57 backfill blocker (23-17)** — a bare `UPDATE` in a Flyway migration against a FORCE-RLS table updates ZERO rows as the RLS-bound migration role → `SET NOT NULL` bricks non-fresh DBs. Fixed with the V44 tenant-loop `set_config` pattern + a two-tenant stepwise-Flyway regression test (RED→GREEN). This is a RECURRING trap (V25→V44→V57).
- **Runtime was stale — user caught it.** I'd reported the wave "verified" off Testcontainers without rebuilding the running stack; the `core-java` image was 6 days stale (pre-Phase-23), so the switcher never engaged. Rebuilt both images; core-java boot applied V52..V57 cleanly. Lesson: Testcontainers/unit green ≠ deployed.

## Deferred, recorded in `deferred-items.md` (not dropped)

- **WR-04** — products/marketing narrow client-side over one paginated page (wrong counts / false empties). Needs a server-side `?shopId=` filter on the products list endpoint → its own plan. This also blocks scoping the home overview's Products/Customers stat cards (order-data scopes; catalogue/CRM totals stay group-wide for now).
- **GCR-W1** BulkImportService cross-tenant `@CacheEvict(allEntries=true)` (over-eviction, non-boundary); **GCR-W2** blank shop-switcher for a zero-access user; **GCR-I1** STOMP gate hard-coded to `kitchen`; **GCR-I2** double-rendered masked email. Plus WR-03 (SSE window), IN-01 (`size=200`).
- Pre-existing emojis in `frontend/app/page.tsx` + `onboarding/page.tsx` (not this session's; flagged by housekeeping 6e).

## Environment state

- Branch `feature/phase-23-vendor-scoped-access`, tree clean. 46 commits unpushed, no PR, secret-path scan clean.
- Compose full stack UP; `core-java` @ V57 healthy on `:9090`, `frontend` on `:3000` (project `jtoye_oaas_2026`, file `docker-compose.full-stack.yml`). Rebuild recipe: `docker compose -p jtoye_oaas_2026 -f docker-compose.full-stack.yml build core-java frontend && ... up -d`.
- **Dev DB has 0 orders in every tenant** (tenant A `…0001` has 3 shops, no orders; `shop_staff` empty until first login JITs). Limits any visual scoping demo — seed orders for a vivid before/after.
- Gradle wrapper at repo **root** (`./gradlew`); `buildDir` is `build-local`; Testcontainers tests are `@Tag("testcontainers")`, run via `./gradlew :core-java:integrationTest` (the `test` task excludes them). Full integrationTest ≈ 34 min.
- Seed users (realm `jtoye-dev`, pw `JtoyeDev!2026`): `admin-user` (tenant A, realm `admin` ⇒ implicit GROUP_ADMIN), `tenant-a-user`, `tenant-b-user`, `admin-user-b`.

## Traps hit (don't repeat)

- **RLS migration-backfill trap** (V57) — see memory `trap_rls_migration_backfill`. Any migration backfilling a FORCE-RLS table MUST loop `tenants` + `set_config` the GUC; fresh-DB Testcontainers never catches it.
- **Stale containers** — the running stack can be days behind the branch; always rebuild + check image-build-time vs commit-time before claiming a feature is testable.
- `python3` is blocked in conda base (`block-base-python` hook) — use `node`/shell or a named env.
- Long analysis agents: the code-reviewer + verifier ran fine in parallel here, but keep an eye on API stream-idle timeouts on very long runs.
