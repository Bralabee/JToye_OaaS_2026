# Handoff: Phase 17 Plans Drafted (PR #57 DRAFT) — Execution Pending

**Generated:** 2026-04-28
**Branch:** `feature/phase-17-implementation` (PR #57 open as DRAFT — plans only, no code yet)
**Previous tip on main:** `b3aded5 Phase 16.1: Pre-prod Hardening — Wave 0 Council Audit Fixes (#56)` (merged 2026-04-28)
**Phase 17 branch tip:** plan-commit at HEAD; no production-code changes yet.

---

## Resume in a fresh context window

Phase 17 spans 4 plans across 4 waves (backend → backend infra → backend integration → frontend + E2E). Total surface area ≈ 35 source files + 1 Flyway migration + ~25 new tests + Playwright spec. **This needs a fresh context** — the prior session shipped Phase 16.1 end-to-end and drafted Phase 17 plans; executing all 4 waves on top would push beyond the 1M-context comfort zone.

### Step 1 — Verify state (<30s)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git checkout feature/phase-17-implementation
git pull
git log --oneline -3
# Expect tip: docs(17): plan Phase 17 — Vendor Order Detail + Stripe Refund Flow
```

### Step 2 — Execute the phase

```bash
/gsd-execute-phase 17
```

Plans are sequential (Wave 1 → 2 → 3 → 4). The orchestrator will dispatch one wave at a time honoring `depends_on`. Sequential execution is recommended (Java/Gradle daemon contention + Testcontainers concurrent Postgres containers — same constraint as Phase 16.1).

**Per-wave expectations:**

- **Wave 1 (17-01)** — V36 migration + Refund entity stack + state-machine extension. Pure backend. Unit tests only (no Testcontainers in this wave). ~2 commits per task = 4 commits.
- **Wave 2 (17-02)** — `payment_event_outbox.exchange` column + flusher routing + `RefundEventPublisher`. Pure backend. ~2 tasks, ~4 commits.
- **Wave 3 (17-03)** — `RefundController` + webhook `refund.*` cases (sitting AFTER Phase 16.1 dedup) + `RefundWebhookHandlingIntegrationTest` (Testcontainers). ~2 tasks, ~4 commits.
- **Wave 4 (17-04)** — Frontend detail route + `OrderDetailPanel` extraction + `RefundDialog` + Playwright E2E.
  - **MANDATORY before claiming success**: per `CLAUDE.md` E2E Utilization Testing rule + memory `feedback_e2e_click_through.md` + `feedback_image_rendering.md`:
    - `docker-compose -f docker-compose.full-stack.yml up -d --build` (rebuild ALL containers per `feedback_rebuild_containers.md`)
    - `cd frontend && npm run dev` (binds NEXTAUTH_URL=http://localhost:3100 via cross-env)
    - Playwright browser-test the full flow: vendor login → /dashboard/orders → click row → refund modal → submit → Stripe test-mode success → UI shows REFUNDED
    - Screenshot key states; verify no blank pages, no naturalWidth=0 images, no console CSP violations
  - Memory rule `feedback_design_direction.md`: refund modal MUST use existing Radix Dialog primitives + orange/emerald/slate palette. NO serif type, NO editorial layout. Plan 17-04 already locks this — do not deviate.

### Step 3 — Verify + review + merge

After all 4 waves complete:

1. Spawn `gsd-verifier` against the 10 must-haves listed in `17-CONTEXT.md` (`<must_haves>` block embedded in each PLAN.md frontmatter; consolidate during verify).
2. Spawn `gsd-code-reviewer` for a final review pass (especially the webhook handler — concurrency around `processed_stripe_events` reuse is the most likely defect site).
3. Address any blocking findings, push, wait for CI green, squash-merge PR #57.

---

## What's locked (do NOT redebate)

5 UC defaults + 3 Phase 16.1 corrections (full rationale in `17-CONTEXT.md`):

- **UC-1** stored-first server-generated UUID idempotency (Refund row inserted with status=`CREATING` BEFORE Stripe call)
- **UC-2** reuse `payment_event_outbox` table + add `exchange VARCHAR(128)` column
- **UC-3** lowercase `RefundStatus` enum (`succeeded`, `failed`, `pending`, `requires_action`, `canceled`) + uppercase sentinel `CREATING`
- **UC-4** webhook subscribes to `refund.created`/`refund.updated`/`refund.failed` exclusively; `charge.refunded` is a documented no-op
- **UC-5** defer VENDOR RBAC to v2.3 (codebase has zero RBAC today; not a Phase 17 concern)
- **CORRECTION-1** migration slot is **V36** (Phase 16.1 took V35 in PR #56) — the new file name is `V36__refunds_and_outbox_exchange.sql`
- **CORRECTION-2** refund webhook cases sit AFTER `INSERT INTO processed_stripe_events ... ON CONFLICT DO NOTHING` inside the existing PaymentService switch. NO new dedup table.
- **CORRECTION-3** every RLS policy in V36 uses `current_setting('app.current_tenant_id', true)::UUID`. Phase 16.1's `RlsContractTest` will fail the build if `refunds` lacks ENABLE+FORCE RLS or any policy references legacy `app.tenant_id`.

---

## Phase 16.1 status (completed this session)

- **PR #56 MERGED** (commit `b3aded5` on main).
- All 7 must-haves PASS per `gsd-verifier`. Code-reviewer flagged one real concurrency hole (subscribe-vs-cleanup race in OrderSseService) — fixed inline before merge with a 200-round concurrent stress test.
- 5 production-code files touched: OrderSseService, PublicStorefrontController, GlobalExceptionHandler, PaymentService, V35 migration. 6 new test classes / 20 new `@Test` methods. CI Run Tests 3m31s green.

---

## Other open work (not in scope for Phase 17)

The 2026-04-27 council audit had 8 specialist remediation pairs. Phase 16.1 closed pairs 1-3 (backend F1/F2, security F1, database F1/F2/F11). Five pairs remain unscoped:

- **DevOps F1/F3/F13** — prod actuator exposure flip, MDC tenantId, git rm backup file. Could ship as `/gsd-quick` or a small phase.
- **Frontend F1-F5** — `--primary` design token rebrand, dashboard responsive rebuild. ⚠ Memory rule: any UI refresh needs `/gsd-sketch` first per `feedback_design_direction.md`.
- **Edge-go absorb** — Wave 2+, blocks on founder decisions (see remediation plan).
- **Commercial track** — door-knock 30 vendors per the commercial critic agent (no code).

Founder decisions still pending: (1) approve edge-go absorb? (2) where does prod K8s live? (3) founder runway/day-job. Answer those three and the rest of the sequencing locks itself.

---

## Environment state

- **Repo**: `/home/sanmi/IdeaProjects/JToye_OaaS_2026`
- **Branch**: `feature/phase-17-implementation`
- **Workdir**: `.idea/gradle.xml` modified (pre-existing IDE noise; safe to leave or `git checkout`)
- **Open PRs**: #57 (DRAFT, Phase 17 plans only)
- **Dev port**: 3100 (`npm run dev` script bakes in NEXTAUTH_URL=http://localhost:3100 via cross-env)
- **docker-compose**: unchanged this session — when Wave 4 starts, rebuild ALL containers per feedback_rebuild_containers.md
- **Latest migration on main**: V35 (Phase 16.1)
- **Next migration slot**: V36 (Phase 17)

---

## Resume prompt for a fresh Claude session

```
Resuming J'Toye OaaS — Phase 17 execution.

State:
- PR #57 (Phase 17, vendor order detail + Stripe refund) is DRAFT with 4 plans committed.
- Phase 16.1 merged via PR #56 (V35 migration + processed_stripe_events idempotency + RlsContractTest).
- Branch: feature/phase-17-implementation (already checked out).
- Migration slot for Phase 17 is V36; refund webhook cases reuse Phase 16.1's processed_stripe_events guard.
- Memory rule: feedback_design_direction.md — refund modal uses existing Radix Dialog + orange/emerald/slate palette only.
- Memory rule: feedback_rebuild_containers.md — rebuild ALL Docker containers before E2E.
- Read /home/sanmi/IdeaProjects/JToye_OaaS_2026/HANDOFF.md for full context.

Run: /gsd-execute-phase 17

After all 4 waves complete, gsd-verifier + gsd-code-reviewer + push + wait CI + squash-merge PR #57.
```
