# Session Handoff — Learning Site + Positioning + Progression

**Generated:** 2026-04-21
**Thread:** strategic / advisory. **Not** the Phase 17 code handoff — that one lives in [`HANDOFF.md`](../../HANDOFF.md) at the repo root and is still the authority for the next coding session.

This handoff captures a separate advisory thread: a public learning site was built, a fact-checked positioning doc was produced, and a progression plan was agreed. Use this to resume the *thinking* thread, not the *coding* thread.

---

## Current goal

Decide whether to commit to the progression plan in [`docs/planning/PROGRESSION-PLAN-2026-04-21.md`](./PROGRESSION-PLAN-2026-04-21.md) — specifically the four-wave sequence (close v2.2 → v2.3 Vendor Parity I → v2.4 Vendor Parity II → strategic branch) — and if so, fold its Wave 1 items into the active `/gsd-` workflow.

The Wave 1 work happens to be identical to what's already in flight under v2.2 (Phase 17 + manual cutovers + docs refresh), so there is no conflict between this advisory thread and the existing `HANDOFF.md` coding thread. The two converge at: *finish v2.2, then scope v2.3 from the progression plan*.

---

## Completed in this session

### 1. Built J'Toye OaaS Learning Site — an instructive, interactive companion site

**Location:** `/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site` (separate directory, **not** inside this repo, unversioned).

**Stack:** Astro 5 + Tailwind 3 + Fraunces/Instrument Sans/JetBrains Mono. Editorial chassis cloned from the J'TOYE M&A Bootcamp site pattern at `/home/sanmi/Documents/J'TOYE_DIGITAL/Mergers_And_Aquisitions/Bootcamp_Site`, accent shifted to terracotta `#C44A1C` to mark it as a companion volume (Vol. II).

**Delivered:**

- Landing page + `/modules` index + `/about` + 404.
- **Module 00 — Platform Overview** (fully written): six participants, three tiers, five-layer tenant boundary defence.
- **Module 03 — Order Lifecycle** (fully written): the seven-state machine, the six events, the ten transitions, side-effect log sourced from `OrderStateChangeListener.java`.
- Modules 01, 02, 04, 05, 06, 07, 08 (outlines with real codebase file-path anchors).
- Three inline-SVG infographics:
  - `ArchitectureTiers.astro` — 3-lane tier diagram with sync/async arrows.
  - `TenantBoundaryFlow.astro` — 4-lane swim from request to Postgres RLS.
  - `OrderStateGraph.astro` — directed graph of states with cancellation edges.
- One vanilla-JS interactive: `OrderStateMachineWalker.astro`. Mirrors `core-java/src/main/java/uk/jtoye/core/order/OrderStateMachineConfig.java` 1:1. Six event buttons, scenario presets (happy path, cancel-early, cancel-late, reset), live side-effect log reflecting `OrderStateChangeListener.java` branches, refusal messages for invalid transitions.

**Verification:** Two Playwright smoke passes (scripts at `/tmp/oaas-smoke.py` and `/tmp/oaas-smoke2.py`, screenshots at `/tmp/oaas-smoke/` and `/tmp/oaas-smoke2/`). Landing, Module 00, Module 03, and the walker cycling DRAFT → COMPLETED all pass. Terminal-state disabling verified. All three SVGs paint (non-null bounding boxes).

**To run locally:**

```bash
cd "/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site"
npm install            # already done this session, but re-run after clone
npm run dev            # http://localhost:4322
```

### 2. Fact-checked the learning site's claims against the codebase and corrected drift

Two concrete corrections applied to the published learning-site prose:

- `Flyway V33 → V34` (latest migration verified as `core-java/src/main/resources/db/migration/V34__product_optimistic_locking.sql`).
- `app.tenant_id → app.current_tenant_id` (the actual Postgres variable, verified at `core-java/src/main/java/uk/jtoye/core/security/TenantSetLocalAspect.java:61` and `core-java/src/main/resources/db/migration/V2__rls_policies.sql:1`).
- Tenant-boundary narrative expanded from 3 layers to the real 5 filters, each named with its source file (`JwtTenantFilter`, `TenantContext`, `TenantSetLocalAspect`, RLS, `TenantContextCleanupFilter`).

### 3. Produced a comprehensive state-and-positioning document

Saved as [`docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`](./PLATFORM-STATE-AND-POSITIONING-2026-04-19.md). Approximately 5,800 words across seven parts and three appendices. Key findings:

- J'Toye OaaS is in the **vendor-direct** product category (Flipdish / Slerp / Toast Online / Square Online / Olo class), **not** the aggregator category (UberEats / Just Eat / Deliveroo / DoorDash class).
- Approximately **70–80% feature parity** with vendor-direct peers.
- Approximately **15–20% feature coverage** vs aggregators, which is the wrong peer class.
- Architecture is **stronger than features** — standouts: five-layer tenant defence, formal Spring State Machine, payment outbox pattern, Hibernate Envers audit, 516+ test invocations.
- Real gaps vs vendor-direct peers: push/SMS, native mobile app, scheduled orders, loyalty, tips, multi-outlet, multi-currency, referrals.

### 4. Drafted a progression plan

Saved as [`docs/planning/PROGRESSION-PLAN-2026-04-21.md`](./PROGRESSION-PLAN-2026-04-21.md). Four-wave sequence:

- **Wave 1** (0–2 weeks): close v2.2 (Phase 17 + manual cutovers + CLAUDE.md docs refresh).
- **Wave 2** (6–10 weeks): v2.3 Vendor Parity I — push/SMS, scheduled orders, tips.
- **Wave 3** (3–4 months): v2.4 Vendor Parity II — mobile shell (Capacitor), multi-outlet/franchise, loyalty/referrals. Gated on one live vendor pilot.
- **Wave 4** (6+ months): strategic branch. Default Path A (deepen vendor-direct); optional Path C (opt-in marketplace overlay); avoid Path B (aggregator pivot) unless capitalised for £10M+.

### 5. Discovered docs-freshness drift in `CLAUDE.md`

`CLAUDE.md` still claims:

- **390 Java `@Test` methods across 48 files** — actual count is **439 methods across 60 files** (`grep -r "@Test" core-java/src/test/java | wc -l` = 439).
- **76 Jest it/test blocks across 13 files** — actual Jest/spec file count is **20** (broader scope than what's measured; individual test-block count not re-measured).
- **50 top-level Go Test funcs / 54 with t.Run across 5 files** — actual Go test file count is **6**.
- **Current schema version: V33** — actual head migration is **V34**, and Phase 17 will introduce **V35**.

Not fixed in this session — flagged in the positioning doc's Appendix C and in the progression plan's Wave 1.

---

## Remaining work (for the resuming session)

### Immediate (same session or next)

1. **Commit the two new docs.** `docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md` and `docs/planning/PROGRESSION-PLAN-2026-04-21.md` are untracked. Per the project's git policy, create a feature branch (`feature/strategic-docs-2026-04-21` or similar), commit both files + this handoff, push, open a PR. Do not commit to `main` directly.
2. **Decide on the progression plan.** Read [`PROGRESSION-PLAN-2026-04-21.md`](./PROGRESSION-PLAN-2026-04-21.md). Accept, amend, or reject the four-wave sequence. If accepted, add Wave 2 scope to `.planning/ROADMAP.md` as the v2.3 milestone after v2.2 ships.
3. **Decide on the learning site.** Options:
    - (a) Git-init at current location and publish to `learn.jtoye.digital` as-is.
    - (b) Git-init and hold private until Wave 2 ships (so it can document the updated feature surface).
    - (c) Fold into this repo under `docs/site/` rather than keeping separate.
    - (d) Delete and re-purpose the infographics components into `.planning/codebase/ARCHITECTURE.md`.
4. **Decide on the editorial accent.** Currently terracotta `#C44A1C`. Alternatives: keep as-is, revert to bootcamp-matching oxblood `#AE2E24`, or shift to a J'Toye-brand primary. One-line change in `/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site/tailwind.config.mjs` either way.

### Next (when resuming code work)

5. Switch threads to the existing [`HANDOFF.md`](../../HANDOFF.md) Phase 17 resume. That handoff is already actionable — run `/gsd-plan-phase 17` on a fresh feature branch cut from `main`.
6. After Phase 17 ships, run `/gsd-complete-milestone` for v2.2.
7. Then run `/gsd-new-milestone` for v2.3 with Wave 2 scope from the progression plan.

---

## Failed approaches (this session)

None.

Two minor dead-ends that cost a few minutes but are not load-bearing:

- **First Playwright smoke** used `expect(page).to_have_title(lambda t: ...)` — Playwright's assertion doesn't accept callables; fixed with `re.compile(r"(Field Guide|OaaS)")`.
- **First SVG visibility check** used `is_visible()` on `<title>` elements inside SVG — SVG `<title>` is a tooltip, not a visually-rendered element; fixed with `.count() > 0` plus a `bounding_box()` paint check on the parent `<svg>`.

Both captured in the smoke scripts themselves; no revision of the learning-site code was required.

---

## Key decisions and rationale

| Decision | Rationale |
|---|---|
| Learning site lives **outside** this repo, at `/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site` | User previously rejected an editorial overhaul of the **product UI** (PR #49, reverted in PR #52). The learning site being a **separate artefact** respects that boundary — product UI keeps its food-delivery palette; the learning site is an editorial companion volume. |
| Editorial accent is **terracotta `#C44A1C`**, not bootcamp oxblood `#AE2E24` | Differentiates Vol. II from Vol. I (the M&A bootcamp) while keeping the red family for editorial continuity. Easy to revert if the user prefers identical. |
| 2 modules fully authored, 7 as outlines — **not all 9 at once** | User previously had a design overhaul rejected; scoping to MVP (2 full + 7 outlines) lets the user redirect voice/depth/accent **before** I invest in the remaining six. |
| Positioning doc frames two peer classes, not one | The user's question mentioned UberEats. Answering only against UberEats would produce a misleading "you're far behind" verdict because J'Toye is not in that category. Framing *two* comparisons — vendor-direct peers (the right class) **and** aggregators (the asked-about class) — gives an honest answer without dismissing the question. |
| Progression plan defaults to **Path A (deepen vendor-direct)** | The positioning-doc Part 5 analysis of the three paths finds Path A lowest-regret absent business context (capital position, founder ambition, existing customer commitments) that I do not have. Path A is presented as the default; Path B is explicitly flagged as capital-prohibitive unless the user signals a £10M+ commitment. |
| Strategic work was **not** put on a feature branch or committed | User asked to "save to file" and "conduct a handoff", not to commit. Following CLAUDE.md git policy strictly: no commits without explicit instruction. |
| `HANDOFF.md` at repo root was **preserved**, not overwritten | It contains the load-bearing Phase 17 coding handoff from the prior session. This session's advisory thread is saved alongside under `docs/planning/SESSION-HANDOFF-2026-04-21.md` so both are discoverable and neither is destroyed. |

---

## Environment state

- **Current branch:** `main`.
- **Working tree:** clean except for two untracked docs (`docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`, `docs/planning/PROGRESSION-PLAN-2026-04-21.md`, `docs/planning/SESSION-HANDOFF-2026-04-21.md`) and one unrelated IDE file (`.idea/gradle.xml` — not my change, can be ignored).
- **Last five commits (main):**
  - `a8f61c2` chore(dev): cross-platform dev script + silence session-poll spam + handoff (#54)
  - `0d6f863` fix(dashboard): authenticate orders SSE stream + bind NextAuth to :3100 (#53)
  - `893a609` Merge pull request #52 from Bralabee/revert/design-system-overhaul
  - `f05fe51` Revert "Merge pull request #49 from Bralabee/feature/design-system-overhaul"
  - `a679453` Merge pull request #51 from Bralabee/feature/phase-17-vendor-order-detail-stripe-refund
- **OaaS dev server:** per prior HANDOFF.md, Next.js dev server is on `http://localhost:3100`; docker-compose stack is up (postgres :5433, redis :6379, keycloak :8085, rabbitmq :5672/:15672/:61613, minio :9000/:9001, core-java :9090, edge-go :8089). This session did not touch any of it. No rebuild needed.
- **Learning-site preview server:** was on `http://localhost:4322`; intentionally stopped via `pkill -f "astro preview"` at end of each smoke run.
- **Relevant file tree additions (untracked):**
  - `/home/sanmi/IdeaProjects/JToye_OaaS_2026/docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`
  - `/home/sanmi/IdeaProjects/JToye_OaaS_2026/docs/planning/PROGRESSION-PLAN-2026-04-21.md`
  - `/home/sanmi/IdeaProjects/JToye_OaaS_2026/docs/planning/SESSION-HANDOFF-2026-04-21.md`
  - `/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site/` (entire directory — unversioned, not in this repo)
- **Playwright artefacts (ephemeral, `/tmp/`):**
  - `/tmp/oaas-smoke.py`, `/tmp/oaas-smoke/` — first-pass smoke
  - `/tmp/oaas-smoke2.py`, `/tmp/oaas-smoke2/` — post-infographics smoke

---

## Resume instructions

### To resume this (strategic) thread

1. Read [`docs/planning/PLATFORM-STATE-AND-POSITIONING-2026-04-19.md`](./PLATFORM-STATE-AND-POSITIONING-2026-04-19.md) top to bottom.
2. Read [`docs/planning/PROGRESSION-PLAN-2026-04-21.md`](./PROGRESSION-PLAN-2026-04-21.md).
3. Answer the four decisions in the "Remaining work → Immediate" list above.
4. Expected outcomes:
    - (a) Two strategic docs committed to a feature branch, PR open.
    - (b) Progression plan accepted / amended / rejected, with Wave 2 scope ready to seed `/gsd-new-milestone v2.3` after v2.2 ships.
    - (c) Clear decision on learning-site disposition (separate repo, private, folded in, or deleted).

### To resume the (coding) thread instead

1. Read [`HANDOFF.md`](../../HANDOFF.md) — the Phase 17 handoff from 2026-04-19. That is the authoritative resume doc for code work.
2. Cut a fresh feature branch from `main`.
3. Run `/gsd-plan-phase 17`.
4. Expected outcome: a PLAN.md under `.planning/phases/17-*/` ready to hand to `/gsd-execute-phase`.

### To re-run the learning-site smoke

```bash
cd "/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site"
npm run build && npm run preview -- --port 4322 --host 0.0.0.0 &
sleep 3
python3 /tmp/oaas-smoke2.py    # expects "OK — infographics render, V34 correction applied, walker still green"
pkill -f "astro preview"
```

Expected outcome: the Playwright script exits 0 and prints the "OK" summary. Screenshots land in `/tmp/oaas-smoke2/`.

---

## Companion artefacts (outside this repo)

Not load-bearing for resume, but for context:

- Learning site repo root: `/home/sanmi/Documents/J'TOYE_DIGITAL/OaaS_Learning_Site`
- Key components: `src/components/OrderStateMachineWalker.astro`, `TenantBoundaryFlow.astro`, `ArchitectureTiers.astro`, `OrderStateGraph.astro`
- Reference sibling volume: `/home/sanmi/Documents/J'TOYE_DIGITAL/Mergers_And_Aquisitions/Bootcamp_Site` (the editorial chassis this one was cloned from)

---

*End of handoff. Safe to hand to any agent — Claude, Antigravity, Cursor — as a self-contained resume document.*
