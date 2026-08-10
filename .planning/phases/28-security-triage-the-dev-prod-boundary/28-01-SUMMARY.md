---
phase: 28-security-triage-the-dev-prod-boundary
plan: 01
subsystem: testing
tags: [rls, postgres, pg_policy, testcontainers, falsifiability, runtime-parity, pentest, bola]

requires:
  - phase: 16.1-pre-prod-hardening
    provides: "RlsContractTest's pg_class schema-walk and the by-addition EXEMPT_TABLES convention this plan mirrors"
  - phase: 23-vendor-scoped-access
    provides: "ShopAccessService.require(shopId, ShopRole) — the ownership gate A1's break arm neutralises"
provides:
  - "A FALSIFIED verdict on pentest finding A1's stated root cause, measured against a stack rebuilt from HEAD"
  - "RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy — the catalog sweep for RLS-on/zero-policy tables, with a >= 30 denominator"
  - "A recorded four-arm falsification protocol for the A1 guard (clean -> break -> restore -> clean), reusable by 28-07"
  - "The measured live RLS isolation numbers (products 0/47/4, superuser control 51) that 28-07's role split must reproduce as the non-owner role"
  - "The measured shop_promotions storefront-carve-out exception — a second instance of the shops Pitfall-4 shape"
affects: [28-07, 28-11]

tech-stack:
  added: []
  patterns:
    - "A policy-count sweep carries its own denominator, so an empty walk fails instead of passing vacuously"
    - "A fail-closed liveness defect is worth a gate precisely because it is invisible: a dead table and an empty table read identically"
    - "Runtime parity is re-asserted AFTER the restore, not only before the break"

key-files:
  created:
    - .planning/phases/28-security-triage-the-dev-prod-boundary/28-01-SUMMARY.md
  modified:
    - core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java

key-decisions:
  - "A1's stated root cause is FALSIFIED, not 'as filed' — shop_promotions and shop_announcements both carry tenant_id, both have RLS ENABLED+FORCED, and each has two policies"
  - "The live isolation arm runs on products, never on shops OR shop_promotions — both carry a permissive published-shop carve-out that legitimately returns rows with no tenant GUC"
  - "The D-13 gap is closed on RlsContractTest, not on DatabaseConfigurationValidator, per RESEARCH DEC-1"
  - "The >= 30 denominator floor sits below the measured 36 so ordinary schema churn does not red the build, while an empty scan still does"
  - "docs/metrics.json and all prose counts left untouched — 28-11 owns the phase manifest"

patterns-established:
  - "Denominator-in-the-same-method: the non-vacuity control is an assertion inside the sweep, not a sibling test that can be deleted independently"
  - "Break-arm targeting by argument, not by call text: PromotionService has five require() calls and only one takes request.getShopId()"

requirements-completed: [SEC-01, SEC-04]

duration: 55min
completed: 2026-08-10
---

# Phase 28 Plan 01: A1 Re-verification + the RLS Zero-Policy Sweep — Summary

**Pentest finding A1's stated root cause is FALSIFIED against a stack rebuilt from HEAD — the tables it names have both a `tenant_id` column and RLS policies — while the service-layer gate that actually blocks the attack is proven capable of failing; and D-13's one real gap is closed by a `pg_policy` catalog sweep that reds on a policy-less RLS table.**

## Performance

- **Duration:** ~55 min
- **Started:** 2026-08-10T01:55Z
- **Completed:** 2026-08-10T02:20Z (measurement work), SUMMARY 02:25Z
- **Tasks:** 2 of 2
- **Files modified:** 1 (`RlsContractTest.java`); `PromotionService.java` touched by the break arm only and restored to a byte-identical state

## Task Commits

1. **Task 1: Re-verify A1 against a stack rebuilt from HEAD** — no commit, by design. The plan scopes this task to a break arm that is restored (net-zero source change); its deliverable is the recorded verdict below, which lands in this SUMMARY's commit. A commit was deliberately not manufactured for a task that legitimately changes nothing.
2. **Task 2: Sweep for RLS-enabled tables with zero policies (D-13)** — `3555bc27` (test)

---

## Task 1 — A1 verdict: **FALSIFIED**

### The verdict

**A1's stated root cause — that `shop_promotions` / `shop_announcements` lack a `tenant_id` column or an RLS policy — is FALSIFIED.**

The measurement that settled it, read off the live database of the stack rebuilt from HEAD (`docker exec -i jtoye-postgres psql -U jtoye -d jtoye`):

```
COLUMN|shop_announcements|tenant_id_present=1
COLUMN|shop_promotions|tenant_id_present=1
RLSFLAGS|shop_announcements|enabled=true|forced=true
RLSFLAGS|shop_promotions|enabled=true|forced=true
POLICY|shop_announcements|shop_announcements_read|cmd=r
POLICY|shop_announcements|shop_announcements_write|cmd=*
POLICY|shop_promotions|shop_promotions_read|cmd=r
POLICY|shop_promotions|shop_promotions_write|cmd=*
```

Both columns exist, RLS is both ENABLED and FORCED on both tables, and each table carries two policies. Every clause of the stated root cause is false on this tree.

**What is true, and is not the same claim:** the cross-tenant write the finding describes was real, and what blocks it is the *service-layer* ownership gate (`ShopAccessService.require(shopId, SHOP_MANAGER)`), not RLS. The break arm below proves that gate is load-bearing and capable of failing. RLS alone does **not** confine these tables (see the `shop_promotions` exception), which is exactly why the fix lives where it does.

### Instrument note — an rc that lied

The first policy query returned `rc=0` while printing `ERROR: operator is not unique: text || "char"`. `psql` does not fail the process on a statement error by default, so the exit code was evidence about `docker exec`, not about the query. Re-run with `-v ON_ERROR_STOP=1`, a deliberate second error returned `rc=3`, confirming the flag actually changes the failure signal. All quoted results above come from `ON_ERROR_STOP=1` runs.

### Runtime parity (established BEFORE the measurement)

`scripts/check-runtime-freshness.sh`, run from the main checkout (the compose project name derives from the directory):

| Arm | Result |
|---|---|
| Baseline, before any rebuild | **rc=1** — `core-java` and `frontend` DRIFT `[image-not-rebuilt]`, images tagged 2026-08-09 20:42–20:43 UTC vs newest build-input commit `8d53a6fc` (2026-08-09 21:34:53 UTC) |
| After `up -d --build core-java frontend` | **rc=1** — both DRIFT `[container-not-recreated]`: `core-java` executing `sha256:16e23b92ed39` while the tag moved to `sha256:96f16dc12695`; `frontend` executing `sha256:992de2d66dba` while the tag moved to `sha256:f8c3a234260c` |
| After `up -d --build --force-recreate` | **rc=0** — `PASS: 4 running built service(s) match the source tree (0 unverified)` |
| **FAIL DIRECTION:** `docker compose stop core-java` | **rc=2 (VOID)** — `core-java — container 'jtoye_oaas_2026-core-java-1' is 'exited', not 'running' (nothing to verify)` … `1 of 4 built service(s) could not be verified` |
| After restart, **clean again** | **rc=0** — `PASS: 4 running built service(s) match the source tree (0 unverified)` |

The middle row is the Phase-33 trap the plan warned about, reproduced exactly: `up -d --build` rebuilt both images and left both containers on the old image IDs. Had the gate only checked timestamps, the measurement would have run against a stale runtime with a green light.

### The four-part A1 arm

| Arm | Command | Result |
|---|---|---|
| **CLEAN** | `./gradlew :core-java:integrationTest --tests '*CrossTenantAuthzIntegrationTest' --tests '*ShopPromotionsRlsPolicyIntegrationTest'` | **BUILD SUCCESSFUL**, `tests="6" failures="0"` + `tests="3" failures="0"` = **9/9 green** (timestamps 02:06:30 / 02:06:35 — freshly executed, not `UP-TO-DATE`) |
| **BREAK** | `PromotionService.java:90` `shopAccessService.require(request.getShopId(), ShopRole.SHOP_MANAGER)` commented out | **BUILD FAILED**, `9 tests completed, 1 failed`, failure = `createPromotion_crossTenantShop_isBlocked()` — `AssertionError: Expecting code to raise a throwable` at `CrossTenantAuthzIntegrationTest.java:132` |
| **RESTORE** | `git checkout -- core-java/.../PromotionService.java` | restored `136440b58a8287ae34c0e196c3973351c1d91f63` == `git rev-parse HEAD:<path>` `136440b58a8287ae34c0e196c3973351c1d91f63` |
| **CLEAN AGAIN** | same as CLEAN | **BUILD SUCCESSFUL**, `tests="6" failures="0"` + `tests="3" failures="0"` = **9/9 green** (timestamps 02:09:13 / 02:09:33) |

**Exactly one failure, and it is the named method.** The break was targeted by *argument* (`request.getShopId()`), the only one of the file's five `shopAccessService.require(` call sites in the create path — lines 72, 83, 90, 113, 134 were all enumerated first, precisely because a first-match text replace would have hit the wrong one.

The three sibling deny-direction tests (`createProduct_crossTenantShop_isBlocked`, `createAnnouncement_crossTenantShop_isBlocked`) stayed green under the break, confirming the arm is no broader than the claim: it neutralises the promotion create path and nothing else.

### Live RLS isolation arm, with its non-vacuity control

Run as the application role on the running database, with `SET ROLE jtoye_app` (`rolsuper=false`, `rolbypassrls=false`):

```
ROLE_NOW|jtoye_app
A1_products_noGUC|0                     <- no tenant GUC pinned
A2_products_tenantA|47                  <- SET app.current_tenant_id = ...0001
A3_products_tenantB|4                   <- SET app.current_tenant_id = ...0002
A4_products_SUPERUSER_CONTROL|51        <- RESET ROLE
```

**51 = 47 + 4.** The superuser control is what makes the leading `0` evidence about RLS rather than about an empty table — recorded explicitly so it cannot be assumed. Confirms `SET ROLE` from a superuser does subject the session to RLS: the same session read 0 as `jtoye_app` and 51 after `RESET ROLE`.

### The `shops` exception — and a second instance of it

`shops` carries a permissive `shops_public_read` SELECT policy `(published = true) OR (tenant_id = current_tenant_id())`. Measured in the same session: **`A1_shops_noGUC|3`**. This is correct, not a defect — permissive policies are OR-ed, and "fixing" it would break the public storefront. Do not treat a non-zero `shops` count with no GUC as a finding.

**New this plan: `shop_promotions` carries the same shape**, which is worth recording because the plan only warned about `shops`:

```
POLICYEXPR|shop_promotions|shop_promotions_read|((tenant_id = current_tenant_id()) OR (EXISTS (
   SELECT 1 FROM shops WHERE ((shops.id = shop_promotions.shop_id) AND (shops.published = true)))))
B1_shop_promotions_noGUC|3
B2_shop_promotions_tenantA|3
B3_shop_promotions_tenantB|3
B4_shop_promotions_SUPERUSER_CONTROL|3
```

All four numbers are 3 — so on this dataset the `shop_promotions` live arm **cannot discriminate** and is reported as such rather than as a pass. The reason is measured, not guessed: `PROMO|00000000-...-0001|shop_published=true|n=3` — every promotion in the database belongs to a published shop, so the storefront carve-out legitimately returns all three regardless of GUC. The valid isolation arm is `products`. Both directions on `shop_promotions` are covered instead by the Testcontainers suite, which seeds what the live data lacks: `tenantAOnlySeesOwnPromotions` and `publishedShopPromotionsAreReadableAcrossTenants`.

This is also the mechanical reason the F-H1 fix exists at the service layer (`findByTenantId` rather than `findAll`): RLS alone does not confine the authenticated list on this table.

---

## Task 2 — the zero-policy sweep

`RlsContractTest.everyRlsEnabledTableHasAtLeastOnePolicy()` walks `pg_class` for `relkind='r'` in `'public'::regnamespace` where `relrowsecurity = true`, LEFT JOINs `pg_policy` on `polrelid = oid`, groups by `relname`, skips `EXEMPT_TABLES`, and asserts `policy_count >= 1` — then asserts the denominator `>= 30` in the same method.

### Before / after counts

| Measurement | Before | After |
|---|---|---|
| `RlsContractTest` tests executed | **5** (`tests="5" failures="0"`, 02:11:38) | **6** (`tests="6" failures="0"`, 02:13:34) |
| `@Test` annotations in the file | 5 | 6 |
| `rg -uu --count-matches pg_policy RlsContractTest.java` | **2** | **3** |

**Correction to the plan's acceptance criterion.** It states the `pg_policy` count "is >= 1 where it was **0** before". It was **2**, not 0 — `noPolicyReadsBuggyAppTenantIdGuc` and `noPolicyUsesRawTenantGucCast` both already query `pg_policy`. The criterion as written would have passed vacuously (`2 >= 1` was already true before the change). The falsifiable form is the *delta*, recorded above: 2 → 3, alongside the test-count delta 5 → 6.

### Both fail directions, run and recorded

**Arm 1 — a deliberately policy-less RLS table.** A throwaway table was created inside the test's own Testcontainers database (`CREATE TABLE zz_break_arm_no_policy (id uuid PRIMARY KEY)` + `ALTER TABLE ... ENABLE ROW LEVEL SECURITY`, dropped in a `finally` so only this method observes it):

```
RlsContractTest > everyRlsEnabledTableHasAtLeastOnePolicy() FAILED
6 tests completed, 1 failed
AssertionError: [public.zz_break_arm_no_policy has ENABLE ROW LEVEL SECURITY but ZERO policies — ...]
Expecting actual: 0L  to be greater than or equal to: 1L
```

Exactly one failure, the new method, naming the table.

**Arm 2 — the denominator.** The walk's namespace filter was changed to `'pg_catalog'::regnamespace`, which matches nothing with `relrowsecurity = true`:

```
RlsContractTest > everyRlsEnabledTableHasAtLeastOnePolicy() FAILED
6 tests completed, 1 failed
NON-VACUITY DENOMINATOR: this sweep observed only 0 non-exempt table(s) ...
```

The method failed on the **denominator**, not on "zero policy-less tables" — which is the whole point. Without that assertion an empty walk is indistinguishable from a clean schema.

**Restore, verified by content:** `git hash-object core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` = `742f03a532aab990daf74f10addb3a46a26450d4` = `git rev-parse HEAD:<path>`. `rg -uu --count-matches 'zz_break_arm_no_policy'` = **0** (arm-1 residue gone). **CLEAN AGAIN:** `tests="6" failures="0"` at 02:18:10, `git status --short` empty.

Arm 2 was run *after* the commit deliberately, so `git checkout --` restored from a committed state rather than the index — the `trap_break_arm_revert_eats_fixes` shape.

### Live schema numbers behind the `>= 30` floor

36 public tables have RLS enabled; all 36 carry at least one policy; 0 policy-less tables (RESEARCH DEC-1, re-confirmed by the sweep passing with a denominator of 36 non-exempt tables). The floor is 30 so ordinary churn does not red the build while an empty scan still does.

---

## Deviations from Plan

### Auto-fixed / corrected

**1. [Rule 3 — Blocking] `up -d --build` did not recreate the containers**
- **Found during:** Task 1 runtime-parity setup
- **Issue:** After a successful rebuild both containers were still executing the previous image IDs; the gate reported `[container-not-recreated]`.
- **Fix:** re-ran with `--force-recreate`, exactly as the plan pre-authorised. Not a code change.
- **Commit:** n/a (runtime operation)

**2. [Rule 1 — Instrument bug] A `psql` statement error read as `rc=0`**
- **Found during:** Task 1 A1 root-cause query
- **Issue:** `docker exec -i ... psql` exits 0 on a statement-level `ERROR`, so the exit code certified the container call rather than the SQL.
- **Fix:** all evidence queries re-run with `-v ON_ERROR_STOP=1`; the flag was itself falsified (a deliberate second error produced `rc=3`).
- **Commit:** n/a (measurement method)

**3. [Rule 1 — Acceptance-criterion defect] The `pg_policy` criterion was already true before the change**
- **Found during:** Task 2 before-state measurement
- **Issue:** the plan asserted the count "was 0 before"; it was 2. As written the criterion is incapable of failing.
- **Fix:** replaced with the falsifiable delta (2 → 3 `pg_policy` lines, 5 → 6 tests), both directions recorded. The plan text is not edited — the correction is recorded here per the "never silently substitute" rule.
- **Commit:** `3555bc27` (the code is unaffected; only the claim about it changed)

### Deliberate non-deviation

**Task 1 produced no commit.** Its only file touch is a break arm that is restored. A commit was not manufactured to satisfy the per-task convention.

---

## Designed reds a later reader must not treat as regressions

**`scripts/docs-freshness.sh` is RED (rc=1) — and it is the tree half, not the prose half.**

Measured, not assumed:

| Gate | rc | Meaning |
|---|---|---|
| `scripts/docs-freshness.sh` (source tree → `docs/metrics.json`) | **1** | tree now computes `java_test_methods: 1596` / `total_logical_invocations: 2770`; the committed manifest says `1595` / `2769` |
| `scripts/check-doc-metrics.sh` (prose → `docs/metrics.json`) | **0** | PASS — 37 prose claims across 3 docs all still match the manifest, because neither was edited |

**The plan's wording ("a red `check-doc-metrics` … is DESIGNED") names the wrong gate.** `check-doc-metrics.sh` is green and will stay green until the manifest is regenerated; it is `docs-freshness.sh` that reds. Both are in `.github/workflows/docs-freshness.yml`. Plan **28-11** owns the manifest for the whole phase and closes this with `scripts/docs-freshness.sh --write` plus the prose updates (never arithmetic — `trap_docs_freshness_block_counter`). `docs/metrics.json` was **not** modified by this plan (`git diff --name-only HEAD~1 HEAD` lists only `RlsContractTest.java`).

**`check-runtime-freshness.sh` now reports `core-java` DRIFT (rc=1) again**, because commit `3555bc27` touches `core-java/src`, which is a build input:

```
core-java  DRIFT [image-not-rebuilt]  image tagged 2026-08-10 02:04:04 UTC / newest build-input commit 3555bc27 (2026-08-10 02:16:37 UTC)
edge-go    FRESH ·  frontend  FRESH ·  mcp-server  FRESH
```

This is expected and correct. The gate's rc=0 requirement applies **before the A1 measurement**, and it was satisfied then. Rebuilding now would re-drift the moment plans 28-02/03/04 land their own commits; the phase close-out rebuild is the right place to reconcile it.

---

## Threat Model Outcomes

| Threat ID | Disposition | Evidence |
|---|---|---|
| T-28-01 | mitigated, **proven capable of failing** | break arm reds exactly `createPromotion_crossTenantShop_isBlocked`; restore verified by hash |
| T-28-02 | mitigated | `everyRlsEnabledTableHasAtLeastOnePolicy` + `>= 30` denominator, both fail directions run |
| T-28-03 | mitigated | superuser control 51 = 47 + 4, recorded explicitly |
| T-28-04 | mitigated | findings referenced as **A1** only; no literal value, no chain, no payload in any committed file; `SECURITY-FINDINGS.md` untouched and still git-excluded |

Cross-cutting dimensions: web-perf **N/A**, SEO **N/A**, agent-readiness **N/A** (no user-facing page, public surface or API surface changed). Falsifiable evidence: every criterion carries a run fail direction above, and the clean state is asserted last as well as first on both tasks.

## Threat Flags

None. No new network endpoint, auth path, file-access pattern or trust-boundary schema change; the only source change is a test method that reads system catalogs.

## Known Stubs

None.

## Notes for Later Plans

- **28-07 (role split)** declared a dependency on this wave so the role catalogue would not move under this measurement. The numbers it must reproduce as the *non-owner* role are `products` = 0 / 47 / 4 with a superuser control of 51. Note `jtoye_app` today is `rolsuper=false, rolbypassrls=false` but is still the table **owner** — which is precisely why FORCE RLS is load-bearing and why D-01 exists.
- **28-07** should also reuse the four-arm shape recorded here verbatim, including asserting runtime parity *after* the restore.
- **28-11** closes the `docs-freshness.sh` red with `--write` (+1 Java `@Test`: 1595 → 1596, total 2769 → 2770).
- Any later plan running a live isolation arm: use `products`. Both `shops` **and** `shop_promotions` carry published-shop carve-outs and will legitimately return rows with no tenant GUC.

## Self-Check: PASSED

- `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` — FOUND, contains `everyRlsEnabledTableHasAtLeastOnePolicy`, hash `742f03a532aab990daf74f10addb3a46a26450d4` matches `HEAD:<path>`
- `.planning/phases/28-security-triage-the-dev-prod-boundary/28-01-SUMMARY.md` — FOUND (this file)
- commit `3555bc27` — FOUND in `git log`
- `core-java/src/main/java/uk/jtoye/core/shop/PromotionService.java` — byte-identical to HEAD (`136440b58a8287ae34c0e196c3973351c1d91f63`), not in any commit from this plan
- `docs/metrics.json`, `.planning/STATE.md`, `.planning/ROADMAP.md` — not modified by this plan
