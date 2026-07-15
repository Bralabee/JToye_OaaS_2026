# Phase 21: Onboarding Blocker UX - Research

**Researched:** 2026-07-14
**Domain:** Vendor onboarding lifecycle UX + Spring StateMachine + transactional outbox + multi-tenant RLS (Spring Boot 3.5.16 / Next.js 16 / PostgreSQL 15)
**Confidence:** HIGH (all core findings verified against live code with file:line; open items are the config-injection channel and outbox event-shape, both flagged)

This is a **brownfield, zero-migration** phase. There is no new library to choose — every requirement reuses assets already in the `uk.jtoye.core.onboarding` package and the shared V46 outbox. Research therefore focused on the seven genuinely-open areas in CONTEXT.md §"Claude's Discretion", each answered below with live-code evidence.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**Manual-review resolution model (D-01/D-02):**
- **D-01 — "Seams now, J'Toye console later."** No J'Toye platform-operator identity exists; both the vendor page and the "approvals" page are tenant-scoped (the vendor org's own `admin` role). Phase 21 ships the vendor-facing fixes + the seams:
  - Build the admin `gate-resolve` endpoint `POST /onboarding/admin/{id}/gates/{gateType}/resolve {decision: PASS|WAIVE|FAIL, reason}`. **Interim resolver = the existing tenant `admin` role** (same trust boundary as the current approve/reject queue) — documented explicitly as interim.
  - Emit an **outbox notification event** when an onboarding enters MANUAL_REVIEW / stalls. Phase 21 only WRITES the event.
- **D-02 — J'Toye platform console + optional Ollama AI reviewer are DEFERRED** to a dedicated later phase (new capability: platform-operator RBAC + cross-tenant RLS bypass). Folding them in would break Phase 21's zero-migration boundary.

**Manual-review visibility (ONBD-03):**
- **D-03 — DTO-derive `reviewPending`**, do not migrate a new state. `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING`, computed at the DTO layer. Vendor UI renders "In review" with honest, config-driven SLA copy ("a reviewer checks these within N business days") replacing "usually takes under a minute"; polling backs off once `reviewPending` is true.
- **D-04 — Admin review queue** = extend the existing tenant-scoped admin surface to also list review-pending applications (VERIFYING + MANUAL_REVIEW), each with the gate-resolve control. The `gate-resolve` handler sets the gate row's status directly (admin override) then triggers the **existing** `GateChainRunner` recompute, which advances the state machine from VERIFYING on all-passed/waived — reuse, do not reinvent.

**Withdraw (ONBD-01):**
- **D-05 — Expose withdraw from ALL 5 pre-live states** the SM already wires (DRAFT / VERIFYING / ACTION_REQUIRED / PENDING_APPROVAL / APPROVED) — a conscious superset of the spec's locked 3. Add only `POST /onboarding/withdraw` + service method + a confirm-dialog UI. Terminal; restart = a new application.

**Correctable data (ONBD-02) — zero migration:**
- **D-06 — Onboarding update endpoint covers `companyNumber` only** (blank/whitespace = sole trader — there is NO separate sole-trader field). Valid only in DRAFT / ACTION_REQUIRED; re-validated like create (reuse the `CreateOnboardingRequest` `@Size(32)` + `@Pattern` company-number rule); rejected outside those states with RFC 7807.
- **D-07 — "FHRS establishment override" = fix the shop's name/address, then resubmit.** The FHRS gate matches on `shop.getName()` + `shop.getAddress()`, so the remediation is a deep-link to the **shop edit** screen → correct → resubmit → gate re-matches. No stored establishment-picker, no new column. A genuinely-unmatchable case falls through to the gate-resolve seam (D-01).

**Remediation blocks (ONBD-04) — frontend-static, no backend enrichment:**
- **D-08 — Map `(gateType, status)` → why / what-to-do / deep-link on the frontend.** The gate `reason` string already carries specifics (allergen FAILED names offending SKUs; FHRS states the miss). No GateDto enrichment. Deep links: BUSINESS_VERIFIED → inline company-number edit (D-06 endpoint); ALLERGEN_DATA_COMPLETE → `/dashboard/products`; FOOD_HYGIENE_RATING → shop edit (D-07).

**Rejection reason + support channel (ONBD-05):**
- **D-09 — Add `rejectionReason` to the vendor `OnboardingDto`** (already on the entity; one-field record change). Render it plus a **config-injected** support channel (mailto/URL, no hardcoding — GLOBAL_RULE_6) on the REJECTED and SUSPENDED terminal states.

### Claude's Discretion
- Exact shape of the `reviewPending` DTO field vs a computed getter; admin-queue endpoint shape (extend `/pending` vs a new `/reviews`); the outbox event's payload schema (align with V46 outbox conventions); the config keys/defaults for the SLA copy ("N business days") and support channel; whether the allergen deep link carries a products filter param; test fixture design. Follow existing onboarding package conventions.

### Deferred Ideas (OUT OF SCOPE)
- **J'Toye platform-operator console** — cross-tenant onboarding oversight; needs a platform-operator role + audited cross-tenant RLS bypass. Its own dedicated v2.3 phase. Phase 21 lays the gate-resolve endpoint + outbox event as seams.
- **Ollama AI reviewer agent** — AI assist/auto-resolve of manual-review gates. Caveat: Ollama not running (`:11434` host conflict).
- **Stored FHRS establishment-picker** — needs a new column = a migration; deferred. This phase uses fix-shop-data + resubmit (D-07).
- **Backend-enriched GateDto remediation hints** — deferred; frontend-static map + existing `reason` specifics suffice (D-08).
- **Reviewer SLA tracking / escalation, multi-reviewer workflow, reapply-after-REJECTED** — deferred by the spec.
- **#205 webhook delivery** — Phase 24 delivers the event this phase emits.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **ONBD-01** | Vendor can withdraw an in-progress application (`WITHDRAW` event + `POST /onboarding/withdraw`, valid from the pre-live states, terminal) | SM event + transitions **already exist** (`VendorOnboardingStateMachineConfig.java:151-180`); SM proof test **already exists** (`VendorOnboardingStateMachineServiceTest.java:133-146`). Only the vendor endpoint + `VendorOnboardingService` method + confirm-dialog UI are net-new. Reuse the canonical `transition(...)` path (`VendorOnboardingService.java:239-243`). |
| **ONBD-02** | Vendor can correct onboarding data (company number), valid only DRAFT/ACTION_REQUIRED, re-validated like create, RFC 7807 outside those states | Reuse `CreateOnboardingRequest` `@Size(max=32)`+`@Pattern` (`CreateOnboardingRequest.java:32-34`) and `normaliseCompanyNumber` (`VendorOnboardingService.java:344-351`). State guard → `InvalidStateTransitionException` → RFC 7807 400 (`GlobalExceptionHandler.java:52-55`). Then vendor uses existing `resubmit` (`VendorOnboardingService.java:132-153`). |
| **ONBD-03** | Manual-review applications visible to everyone (DTO-derived `reviewPending`; admin review queue; `gate-resolve` → recompute → advance) | `GateChainRunner` leaves MANUAL_REVIEW parked in VERIFYING (`GateChainRunner.java:196-199`); recompute only advances FROM VERIFYING (line 157) and skips non-PENDING rows (line 142) so an admin-set status survives. DTO assembled in `VendorOnboardingService.toDto` (`:391-405`). |
| **ONBD-04** | Per-gate remediation blocks (why → what → deep-link) | Gate `reason` already carries specifics: allergen names SKUs (`AllergenCompletenessGate.java:115-122`), FHRS states the miss (`FhrsGate.java:107,121,128,134`). Frontend-static `(gateType,status)`→map mirrors the existing static-map idiom (`onboarding/page.tsx:54-94`). |
| **ONBD-05** | Rejection reason reaches vendor + real support channel | `rejectionReason` already on entity (`VendorOnboarding.java:73-74`) and `AdminOnboardingDto` (`:34`); add to `OnboardingDto` (`:27-30`). Support channel config-injected. Playwright blocked-journey add. |
</phase_requirements>

## Summary

Phase 21 makes onboarding blockers **visible, correctable, and exitable** without a single Flyway migration. The heavy lifting the spec feared is already done: the `WITHDRAW` event and its five source→`WITHDRAWN` transitions are wired and unit-proven; `rejectionReason` is on the entity and the admin DTO; `resubmit` already resets FAILED/MANUAL_REVIEW gates and re-runs the chain; and the async recompute (`GateChainRunner`) already advances the state machine correctly and re-establishes the tenant on its worker thread. What is missing is a thin set of **vendor endpoints, one admin endpoint, one DTO field, an outbox producer, and the frontend surfaces** — all of which have direct in-repo analogs.

The two areas that carry real design weight are (1) the **outbox notification event**, which cannot simply be written to the shared `payment_event_outbox` table because the flusher's deserialization dispatch is hard-coded to two exchanges and will **poison** any unrecognised exchange (`PaymentEventOutboxFlusher.java:256-266`); and (2) the **gate-resolve → recompute** path, which must write the gate row and then trigger recompute **after commit** to avoid the documented CR-01 async-visibility landmine (`VendorOnboardingService.java:299-322`). Both are solved below with concrete, minimal shapes.

**Primary recommendation:** Split exactly as the roadmap anticipates (21-01 backend withdraw+update, 21-02 backend visibility+gate-resolve+outbox, 21-03 frontend, 21-04 Playwright+metrics). Reuse `VendorOnboardingService`'s canonical `transition(...)` and `kickGateChainAfterCommit(...)` for every state change; never write `status` or `Shop.published` directly. Add an `onboarding.events` exchange + extend the flusher dispatch when writing the stall event. Inject the SLA copy + support channel via `NEXT_PUBLIC_*` env vars (frontend config idiom) — flagged as a decision.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Withdraw an application (ONBD-01) | Core API (state machine) | Frontend (confirm dialog) | Every lifecycle transition must go through `OnboardingEvent` → `VendorOnboardingService.transition`; the SM is the sole authority. |
| Correct company number (ONBD-02) | Core API (validation + service) | Frontend (inline edit) | Company-number format re-validation and the DRAFT/ACTION_REQUIRED state guard belong server-side; the UI only submits. |
| Manual-review visibility `reviewPending` (ONBD-03) | Core API (DTO assembly) | Frontend (render) | Derived flag must be computed where the gate rows are already loaded (`toDto`) so the UI never re-derives lifecycle logic. |
| Gate resolution → advance (ONBD-03) | Core API (service + async recompute) | — | Writing a gate row + driving the SM is a transactional, RLS-scoped, tenant-pinned operation; not a client concern. |
| Stall notification event (D-01) | Core API (transactional outbox) | Message broker (RabbitMQ) | Emitted atomically with the gate evaluation inside the async tx; delivery is Phase 24. |
| Per-gate remediation copy + deep-links (ONBD-04) | Frontend (static map) | — | D-08: the specifics already live in the gate `reason`; the "what to do next" mapping is presentation. |
| Rejection reason + support channel (ONBD-05) | Frontend (render) + Config | Core API (DTO field) | `rejectionReason` is data (DTO); the support channel/SLA copy is injected config, rendered client-side. |

## Project Constraints (from CLAUDE.md)

These are load-bearing directives the planner MUST honor (same authority as locked decisions):

- **Tech stack is fixed** — Spring Boot 3.5.16, Next.js 16.2.2 / React 19, Go 1.25, PostgreSQL 15. No new frameworks. `[CITED: CLAUDE.md]`
- **JDK 21** (JDK 25 incompatible with Gradle 8.10). `[CITED: CLAUDE.md]`
- **Multi-tenancy** — all new features respect RLS + `TenantContext`. Every new query/write runs under `app.current_tenant_id`; the admin gate-resolve endpoint is tenant-scoped, NOT cross-tenant. `[CITED: CLAUDE.md]`
- **All new code requires tests.** The project standard is **1257 logical invocations** (876 Java `@Test` + 249 Jest `it/test` + 77 Go `Test*` + 28 Playwright `test()` + 27 MCP vitest). `docs/metrics.json` is the single source of truth and the `docs-freshness` CI gate (`.github/workflows/docs-freshness.yml`) **fails the build on drift** — new tests MUST be reconciled via `scripts/docs-freshness.sh --write`. `[VERIFIED: docs/metrics.json, scripts/docs-freshness.sh]`
- **Config-injection doctrine (GLOBAL_RULE_6)** — no hardcoded literals; support channel + SLA copy are injected. `[CITED: CLAUDE.md, CONTEXT.md D-09]`
- **Rebuild ALL containers** after code changes before E2E testing. `[CITED: CLAUDE.md]`
- **Incremental Betterment** — reworking the onboarding page must preserve every existing good (create form, submit/resubmit/go-live CTAs, gate breakdown, milestone timeline). Regression by omission is a defect even with green tests. `[CITED: CLAUDE.md]`
- **GSD workflow** — do not make direct repo edits outside a GSD workflow. `[CITED: CLAUDE.md]`

## Standard Stack

No new dependencies. Everything reuses libraries already on the classpath. Versions cited from CLAUDE.md.

### Core (all already present)
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Spring Boot Web / Validation | 3.5.16 | REST controllers, `@Valid` bean-validation, RFC 7807 `ProblemDetail` | The entire onboarding surface is built on it `[CITED: CLAUDE.md]` |
| Spring StateMachine | (bundled) | Onboarding lifecycle transitions incl. `WITHDRAW` (already wired) | Sole-authority pattern for `Shop.published` `[VERIFIED: VendorOnboardingStateMachineConfig.java]` |
| Spring Data JPA + Hibernate Envers | 3.5.16 | Gate/onboarding persistence + `_aud` audit mirror | Gate-resolve write is auto-audited (`@Audited` on `VendorOnboardingGate.java:36`) `[VERIFIED]` |
| Spring AMQP + `PaymentEventOutbox` (V46) | (bundled) | Transactional outbox for the stall event | Reuse the existing table + flusher `[VERIFIED: PaymentEventOutbox.java, PaymentEventOutboxFlusher.java]` |
| `@ConfigurationProperties` (`OnboardingProperties`) | 3.5.16 | Backend config injection (`onboarding.*`) | GLOBAL_RULE_6 backend pattern `[VERIFIED: OnboardingProperties.java, application.yml:268]` |
| Next.js / React / lucide-react / framer-motion / date-fns | 16.2.2 / 19 | Vendor + admin onboarding pages | Existing page stack `[VERIFIED: onboarding/page.tsx imports]` |
| shadcn/Radix `Dialog`, `Button`, `Badge`, `Input`, `Label` | (present) | Withdraw confirm dialog, remediation blocks, inline edit | Go-live dialog is the direct idiom `[VERIFIED: onboarding/page.tsx:516-533]` |
| Jest + @testing-library/react | 29.7.0 | Frontend unit tests (mock `@/lib/api-client` + `@/hooks/use-toast`) | Existing test idiom `[VERIFIED: onboarding/__tests__/page.test.tsx:1-14]` |
| JUnit 5 + Testcontainers (Postgres 15) + Spring MockMvc | 5 / 1.21.3 | Controller/RLS integration tests under real Postgres | `OnboardingAdminQueueIntegrationTest` is the analog `[VERIFIED]` |
| Playwright | 1.59.1 | Blocked-onboarding E2E journey | `frontend/e2e/*.spec.ts` `[VERIFIED]` |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| DTO-derived `reviewPending` in `toDto` | New `IN_REVIEW` state + V5x CHECK migration | Rejected by spec: costs a migration + SM transitions for zero user-visible gain over the derived flag. `[CITED: REQUIREMENTS.md line 90]` |
| Reuse shared `payment_event_outbox` for the stall event | A dedicated `onboarding_event_outbox` table | Rejected: a new table = a migration, breaking the zero-migration boundary. Reuse + extend the flusher dispatch. |
| `NEXT_PUBLIC_*` for SLA copy/support channel | Backend `OnboardingProperties` served via DTO/endpoint | Frontend env is build-time (matches existing idiom); backend is runtime-overridable but heavier. **Decision flagged.** |

**Installation:** None. `npm install` / Gradle dependency changes are NOT expected this phase.

## Package Legitimacy Audit

**Not applicable — this phase installs zero external packages.** All work reuses libraries already declared in `core-java/build.gradle.kts`, `frontend/package.json`, and the running stack. No `npm install`, no new Gradle coordinate, no new PyPI/crate. The slopcheck/registry gate is therefore skipped by design. If any plan proposes a new dependency, that plan must run the Package Legitimacy Gate before adding it.

## Architecture Patterns

### System Architecture Diagram

```
VENDOR FLOW (ONBD-01/02/04/05)
  Browser (onboarding/page.tsx)
    │  apiClient (axios, baseURL=NEXT_PUBLIC_API_URL, 401-refresh + 5xx retry)
    ▼
  OnboardingController  /onboarding/{submit,resubmit,go-live,me}   ← existing
    + NEW  POST /onboarding/withdraw
    + NEW  PATCH /onboarding (companyNumber)
    ▼
  VendorOnboardingService  (@Transactional, CurrentTenant.require())
    ├─ transition(onboarding, event) ──► VendorOnboardingStateMachineService.sendEvent
    │     (SM validates; guard veto → InvalidStateTransitionException → RFC7807 400)
    │     side-effect switch: GO_LIVE/SUSPEND/REINSTATE flip Shop.published (SOLE writer)
    └─ writes vendor_onboarding row (RLS: app.current_tenant_id GUC)  → Envers _aud

ADMIN FLOW (ONBD-03, D-01/D-04)
  Browser (onboarding/approvals/page.tsx)  [@PreAuthorize hasRole('admin'), tenant-scoped]
    ▼
  OnboardingAdminController  /onboarding/admin/{pending,{id}/approve,{id}/reject}  ← existing
    + NEW  GET  review queue (extend /pending OR add /reviews)
    + NEW  POST /onboarding/admin/{id}/gates/{gateType}/resolve {decision,reason}
    ▼
  VendorOnboardingService.resolveGate(...)
    ├─ writes vendor_onboarding_gate row status (admin override) → Envers _aud
    └─ kickGateChainAfterCommit(onboardingId, tenantId)   ← REUSE existing (afterCommit)
          ▼ (async worker thread, tenant re-established)
       GateChainRunner.runAndRecompute(onboardingId, tenantId)  @Async @Transactional
          - re-evaluates only PENDING automatic gates (never clobbers admin-set row)
          - advances ONLY from VERIFYING:  all PASSED/WAIVED → GATES_PASSED (+auto APPROVE)
                                            any FAILED        → GATE_FAILED → ACTION_REQUIRED
                                            else (≥1 MANUAL_REVIEW) → stays VERIFYING
                                              └─► NEW: OnboardingEventPublisher.publishStall(...)

NOTIFICATION SEAM (D-01, delivered by Phase 24)
  OnboardingEventPublisher (NOT @Transactional; joins caller tx)
    ▼ writes PaymentEventOutbox row (exchange="onboarding.events", 5-arg ctor)
  PaymentEventOutboxFlusher  @Scheduled  (per-tenant tx, FOR UPDATE SKIP LOCKED)
    ▼ publishRow() dispatch  ← MUST be extended to recognise onboarding.events
  RabbitMQ TopicExchange "onboarding.events"  ← NEW bean; no bound queue yet (seam)
```

### Recommended Structure (net-new files; follow existing package layout)
```
core-java/.../onboarding/
├── OnboardingController.java            # + withdraw, + update endpoints
├── OnboardingAdminController.java       # + review-queue GET, + gate-resolve POST
├── VendorOnboardingService.java         # + withdraw(), + updateCompanyNumber(), + resolveGate(), + listReviewPending()
├── OnboardingEventPublisher.java        # NEW — mirror order/OrderEventPublisher
├── OnboardingStateChangeEvent.java      # NEW — fixed-shape record
└── dto/
    ├── OnboardingDto.java               # + reviewPending, + rejectionReason
    ├── UpdateOnboardingRequest.java     # NEW — companyNumber, reuse @Size/@Pattern
    └── ResolveGateRequest.java          # NEW — decision enum + reason
core-java/.../config/RabbitMQConfig.java # + onboarding.events TopicExchange + constant
core-java/.../payment/PaymentEventOutboxFlusher.java  # + onboarding.events deserialize branch
frontend/app/dashboard/onboarding/
├── page.tsx                             # remediation map, in-review copy+backoff, withdraw dialog, rejection+support
└── approvals/page.tsx                   # review-pending listing + gate-resolve control
frontend/types/api.ts                    # + reviewPending, + rejectionReason on OnboardingDto
frontend/e2e/onboarding-blocked-flow.spec.ts  # NEW journey spec
```

### Pattern 1: Every state change goes through the canonical `transition(...)`
**What:** Load → SM `sendEvent` → set status → stamp timestamp → run side effect → save.
**When:** All of withdraw, gate-resolve (indirectly via recompute), update (guard only — no event).
**Example:**
```java
// Source: VendorOnboardingService.java:239-297 (existing)
void transition(UUID onboardingId, OnboardingEvent event) {           // package-private, called by GateChainRunner too
    VendorOnboarding o = onboardingRepository.findById(onboardingId)
        .orElseThrow(() -> new ResourceNotFoundException("Onboarding not found: " + onboardingId));
    transition(o, event);                                             // → SM validates, throws on veto
}
// Withdraw (NEW) reuses it verbatim:
public OnboardingDto withdraw() {
    UUID tenantId = CurrentTenant.require();
    VendorOnboarding o = requireOnboarding(tenantId);
    transition(o, OnboardingEvent.WITHDRAW);                          // DRAFT/VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL/APPROVED → WITHDRAWN
    return toDto(o, gateRepository.findByOnboardingId(o.getId()));
}
```
> `WITHDRAW` from a terminal state (REJECTED/WITHDRAWN/LIVE/SUSPENDED) is not wired → `InvalidStateTransitionException` → RFC 7807 400 automatically. Verified by the existing SM test (`VendorOnboardingStateMachineServiceTest.java:163-165`).

### Pattern 2: Gate-resolve writes a GATE row, then recompute advances the SM
**What:** The admin override never writes `status`; it writes the gate row and lets `GateChainRunner` fire `GATES_PASSED`/`GATE_FAILED`.
**Why:** Preserves "the state machine is the sole writer of `Shop.published`" and reuses the auto-approve/advance logic.
**Critical:** trigger recompute **after commit**, not inline — the `@Async @Transactional` worker opens its own connection and under READ COMMITTED cannot see the uncommitted gate-row change (documented CR-01 landmine).
```java
// Source: VendorOnboardingService.java:311-322 (existing) — REUSE this exact helper
public AdminOnboardingDto resolveGate(UUID onboardingId, GateType gateType, GateDecision decision, String reason) {
    UUID tenantId = CurrentTenant.require();
    VendorOnboarding o = requireOnboardingById(onboardingId);                    // RLS 404 if foreign tenant
    VendorOnboardingGate row = gateRepository.findByOnboardingIdAndGateType(onboardingId, gateType)
        .orElseThrow(() -> new ResourceNotFoundException("Gate not found"));
    row.setStatus(switch (decision) { case PASS -> PASSED; case WAIVE -> WAIVED; case FAIL -> FAILED; });
    row.setReason(reason);
    row.setCheckedAt(OffsetDateTime.now());
    gateRepository.save(row);                                                     // Envers writes vendor_onboarding_gate_aud
    kickGateChainAfterCommit(onboardingId, tenantId);                            // afterCommit → runAndRecompute (async, tenant re-established)
    return toAdminDto(o, gateRepository.findByOnboardingId(onboardingId));
}
```
> `runAndRecompute` skips non-PENDING rows (`GateChainRunner.java:142`), so the admin-set PASSED/WAIVED/FAILED survives the re-run; it advances only from VERIFYING (`:157`), which is exactly the parked MANUAL_REVIEW state. A `FAIL` decision → `GATE_FAILED` → `ACTION_REQUIRED`, surfacing the remediation block to the vendor.

### Pattern 3: DTO-derived `reviewPending` in `toDto`
```java
// Source: VendorOnboardingService.java:391-405 (existing toDto — extend it)
private OnboardingDto toDto(VendorOnboarding o, List<VendorOnboardingGate> gates) {
    List<GateDto> gateDtos = gates.stream().map(g -> new GateDto(...)).toList();
    boolean reviewPending = o.getStatus() == OnboardingState.VERIFYING
        && gates.stream().anyMatch(g -> g.getStatus() == GateStatus.MANUAL_REVIEW)
        && gates.stream().noneMatch(g -> g.getStatus() == GateStatus.PENDING);   // D-03 exact predicate
    return new OnboardingDto(o.getId(), o.getStatus(), o.getModel(), o.getShopId(),
        o.getCompanyNumber(), o.getSubmittedAt(), o.getApprovedAt(), o.getWentLiveAt(),
        o.getRejectionReason(),        // ONBD-05 — already on the entity (VendorOnboarding.java:73-74)
        reviewPending,                 // ONBD-03
        gateDtos);
}
```
> DTOs are **hand-built records**, not MapStruct (`toDto`/`toAdminDto` at `:368-405`) — so the derivation lives here, where the gate list is already loaded. Mirror the change in `frontend/types/api.ts:361-371`.

### Pattern 4: Reuse create-time company-number validation for the update
```java
// Source: CreateOnboardingRequest.java:32-34 (copy verbatim into UpdateOnboardingRequest)
@Size(max = 32, message = "companyNumber must be at most 32 characters")
@Pattern(regexp = "^\\s*([A-Za-z0-9]{2,10})?\\s*$",
         message = "companyNumber must be a valid Companies House number (2-10 letters or digits)")
private String companyNumber;   // blank/whitespace = sole trader; service normalises blank → null
```
```java
// Update service method — state guard, NO state-machine event (it is a data edit)
public OnboardingDto updateCompanyNumber(String companyNumber) {
    UUID tenantId = CurrentTenant.require();
    VendorOnboarding o = requireOnboarding(tenantId);
    if (o.getStatus() != OnboardingState.DRAFT && o.getStatus() != OnboardingState.ACTION_REQUIRED) {
        throw new InvalidStateTransitionException(                                // → RFC 7807 400 "Invalid State Transition"
            "Company number can only be changed while in DRAFT or ACTION_REQUIRED");
    }
    o.setCompanyNumber(normaliseCompanyNumber(companyNumber));                    // VendorOnboardingService.java:344-351
    onboardingRepository.save(o);
    return toDto(o, gateRepository.findByOnboardingId(o.getId()));
}
```

### Anti-Patterns to Avoid
- **Writing `vendor_onboarding.status` (or `Shop.published`) directly** from the gate-resolve or update endpoint. Only the SM side-effect switch may touch `published` (`VendorOnboardingService.java:273-289`).
- **Calling `runAndRecompute` inline** inside the still-open service transaction — the worker won't see the gate write (CR-01, `:299-310`). Always `kickGateChainAfterCommit`.
- **Writing the stall event to `payment_event_outbox` with a new exchange but WITHOUT extending the flusher** — it poisons (see Pitfall 1).
- **Re-deriving lifecycle logic in the frontend** — `reviewPending` is a server-computed field; the UI renders it, never recomputes gate math.
- **Hardcoding the support mailto / SLA copy** — GLOBAL_RULE_6 violation.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Withdraw transition validity | A new SM config / event | `OnboardingEvent.WITHDRAW` + transitions **already wired** (`VendorOnboardingStateMachineConfig.java:151-180`) | Building it again risks diverging the terminal-state guard already tested at `VendorOnboardingStateMachineServiceTest.java:133-165`. |
| Advancing after a gate is resolved | New advance/approve logic in the resolve handler | `GateChainRunner.runAndRecompute` via `kickGateChainAfterCommit` | It already handles GATES_PASSED, model-aware auto-approve, guard-veto swallow (WR-01), and the @Async tenant landmine. |
| Re-running gates against corrected data | New re-validation loop | `POST /onboarding/resubmit` (`VendorOnboardingService.java:132-153`) | Already resets FAILED/MANUAL_REVIEW → PENDING and re-kicks the chain. The update endpoint only changes the data. |
| Company-number format check | New regex/length rule | Copy the `@Size(32)`+`@Pattern` from `CreateOnboardingRequest.java:32-34` | Guarantees identical accept/reject semantics; sole-trader blank branch already handled. |
| RFC 7807 error bodies | Custom error JSON | Throw `InvalidStateTransitionException` / rely on `@Valid` | `GlobalExceptionHandler` maps both to `ProblemDetail` 400 (`:52-55`, `:94-104`). |
| Transactional event emission | Direct `rabbitTemplate.convertAndSend` | `PaymentEventOutbox` row via an `OnboardingEventPublisher` (mirror `OrderEventPublisher.java:71-110`) | Direct publish drops events on broker outage and can announce a rolled-back transition. |
| Audit trail on the gate override | Manual audit insert | Envers — `VendorOnboardingGate` is `@Audited` (`:36`); `vendor_onboarding_gate_aud` exists (V43) | The `_aud` mirror is written automatically on `gateRepository.save`. |
| Frontend remediation copy | Backend GateDto enrichment | Frontend-static `(gateType,status)`→map (D-08) reading the existing `reason` | The FAILED reason already names offending SKUs / states the FHRS miss. |

**Key insight:** This phase is ~80% assembly of existing parts. The failure mode is *re-implementing* an invariant (advance logic, published-writer, tenant re-establishment) instead of routing through the one canonical path.

## Common Pitfalls

### Pitfall 1: Writing the stall event to the shared outbox poisons the flusher (HIGH RISK)
**What goes wrong:** You write a `PaymentEventOutbox` row with `exchange="onboarding.events"`. The scheduled flusher picks it up and its deserialization dispatch has only two arms — `order.events` and an `else` that assumes `payment.events`:
```java
// Source: PaymentEventOutboxFlusher.java:256-266
if (RabbitMQConfig.ORDER_EVENTS_EXCHANGE.equals(exchange)) { ... OrderStateChangeEvent / RefundEvent ... }
else { event = objectMapper.readValue(row.getPayload(), PaymentEvent.class); }   // ← onboarding payload lands HERE
```
An onboarding payload deserialized as `PaymentEvent` either throws `JsonProcessingException` → the row is marked **poison FAILED** and dead-lettered (`:275-284`), or (worse) succeeds leniently and publishes a malformed message. Additionally, `convertAndSend` to an undeclared `onboarding.events` exchange fails at the channel level.
**Why it happens:** The V36 per-row-exchange design deliberately kept dispatch explicit; there is no generic type registry.
**How to avoid (required tasks, all in 21-02):**
1. Declare `onboarding.events` as a `TopicExchange` bean + `ONBOARDING_EVENTS_EXCHANGE` constant in `RabbitMQConfig.java` (mirror `orderEventsExchange()` at `:34-37`). No bound queue is required this phase — a topic exchange with no binding discards cleanly; Phase 24 adds the subscription/queue.
2. Add an `else if ONBOARDING_EVENTS_EXCHANGE.equals(exchange)` branch to `publishRow` that deserializes to the new `OnboardingStateChangeEvent`.
3. Create `OnboardingEventPublisher` (mirror `OrderEventPublisher.java`, NOT `@Transactional`, joins the caller tx) + `OnboardingStateChangeEvent` record.
**Warning signs:** `payment.outbox.dead_letter` counter increments after an onboarding stalls; ERROR log "Outbox row … is unrecoverable (poisoned)".

### Pitfall 2: Inline recompute doesn't see the gate write (CR-01)
**What goes wrong:** Calling `gateChainRunner.runAndRecompute(...)` directly from `resolveGate` while the resolve transaction is still open → the async worker (its own connection, READ COMMITTED) sees the pre-update gate row → early-returns → the onboarding stays stuck.
**How to avoid:** Use the existing `kickGateChainAfterCommit(onboardingId, tenantId)` (`VendorOnboardingService.java:311-322`) which registers an `afterCommit` synchronization. Because `resolveGate` lives in the same `@Transactional` service class, it can call the private helper directly.
**Warning signs:** Gate shows PASSED in the DB but the application never leaves VERIFYING.

### Pitfall 3: @Async tenant landmine on the recompute
**What goes wrong:** The recompute worker thread does not inherit `TenantContext` (no `TaskDecorator`), so RLS writes are denied.
**How to avoid:** `runAndRecompute(onboardingId, tenantId)` already re-establishes it (`GateChainRunner.java:113-117`, try/finally clear at `:207-208`). Pass the tenant resolved via `CurrentTenant.require()` in `resolveGate`. Do NOT introduce a new async path that forgets this.

### Pitfall 4: Stall-event duplication on resubmit
**What goes wrong:** `runAndRecompute` runs on every submit/resubmit; emitting the stall event unconditionally in the "stays VERIFYING" branch fires a duplicate each cycle.
**How to avoid:** The outbox contract is already at-least-once and consumers must be idempotent — acceptable. If you want at-most-once-per-stall, only emit when the recompute *transitions into* the MANUAL_REVIEW-parked condition (e.g. guard on "no prior unsent onboarding stall row for this onboarding"). **Decision flagged** — recommend at-least-once + idempotent consumer to match the existing outbox semantics.

### Pitfall 5: Frontend polling never backs off / SSE-networkidle
**What goes wrong:** `POLL_STATES` includes `VERIFYING` and polls every 4s forever (`onboarding/page.tsx:107,185`), which is exactly the "silent spinner" the phase kills; and any long-poll/interval keeps Playwright's `networkidle` from ever settling.
**How to avoid:** Once `reviewPending` is true, drop `VERIFYING` from the fast interval (or lengthen it) and swap the copy. In Playwright, do not wait on `networkidle` — assert on visible copy/state (learning from `learnings_fleet_supervision`).

### Pitfall 6: Frontend typecheck gate
**What goes wrong:** Adding `reviewPending`/`rejectionReason` to the TS `OnboardingDto` and missing a consumer compiles under Jest (Jest does not type-check) but breaks `next build`.
**How to avoid:** Run `npm run build` (tsc) before considering the frontend done — bit the project on #87/PR #130.

### Pitfall 7: Metrics drift fails CI
**What goes wrong:** New `@Test`/`it`/Playwright `test()` blocks change the counts; `docs/metrics.json` (1257) goes stale → `docs-freshness` CI gate fails.
**How to avoid:** Run `scripts/docs-freshness.sh --write` and commit `docs/metrics.json` as the closing task (21-04).

## Code Examples

### Outbox producer to mirror (order domain → onboarding domain)
```java
// Source: order/OrderEventPublisher.java:71-110 (existing) — the exact shape to copy
public void publishStateChange(UUID orderId, UUID tenantId, String orderNumber,
                               OrderStatus previousStatus, OrderStatus newStatus) {
    OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, tenantId, orderNumber,
        previousStatus, newStatus, OffsetDateTime.now());
    String routingKey = ORDER_STATE_ROUTING_PREFIX + newStatus.name().toLowerCase();
    String payloadJson = objectMapper.writeValueAsString(event);
    PaymentEventOutbox row = new PaymentEventOutbox(tenantId, EVENT_TYPE, routingKey, payloadJson,
        RabbitMQConfig.ORDER_EVENTS_EXCHANGE);        // 5-arg ctor: custom exchange (PaymentEventOutbox.java:109-112)
    outboxRepository.save(row);                       // joins caller's @Transactional; NOT @Transactional itself
}
```
**Onboarding recommendation (concrete shape):**
- Record: `OnboardingStateChangeEvent(UUID onboardingId, UUID tenantId, UUID shopId, OnboardingState status, String reason, OffsetDateTime occurredAt)`.
- `EVENT_TYPE = "ONBOARDING_STALLED"` (or `ONBOARDING_MANUAL_REVIEW`); routingKey `onboarding.state.manual_review`.
- Emit from `GateChainRunner`'s "stays VERIFYING because ≥1 mandatory gate is MANUAL_REVIEW" branch (`GateChainRunner.java:196-199`), where the tenant is already re-established and the transaction is the async one.

### Admin controller endpoint to mirror
```java
// Source: OnboardingAdminController.java:44-111 (existing) — class-level @PreAuthorize + @RequestMapping
@RestController
@RequestMapping("/onboarding/admin")
@PreAuthorize("hasRole('admin')")   // interim resolver = tenant's own admin (D-01)
public class OnboardingAdminController {
    // NEW:
    @PostMapping("/{id}/gates/{gateType}/resolve")
    public ResponseEntity<AdminOnboardingDto> resolveGate(@PathVariable UUID id,
            @PathVariable GateType gateType, @Valid @RequestBody ResolveGateRequest req) {
        return ResponseEntity.ok(vendorOnboardingService.resolveGate(id, gateType, req.getDecision(), req.getReason()));
    }
    // NEW: review queue — extend pending() to also select VERIFYING+MANUAL_REVIEW, or add /reviews
}
```

### Integration-test analog (Testcontainers + real Keycloak converter + RLS)
```java
// Source: OnboardingAdminQueueIntegrationTest.java (existing) — the pattern for withdraw/update/gate-resolve controller tests
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test") @Tag("testcontainers")
// - adminJwt(tenant) / userJwt(tenant) via KeycloakRealmRoleConverter (:108-121)
// - guard-veto regression: 400 + state unchanged (:198-210)
// - Envers _aud assertion: SELECT count(*) FROM vendor_onboarding_gate_aud (:242-245 pattern)
// - RLS under NOSUPERUSER role: tenant B invisible to tenant A (:287-319)
```

### Frontend static-map + confirm-dialog idioms
```tsx
// Source: onboarding/page.tsx:54-94 (STATE_META/GATE_META) — add REMEDIATION map same way:
const REMEDIATION: Partial<Record<`${GateType}:${GateStatus}`, { why: string; what: string; href: string }>> = {
  "BUSINESS_VERIFIED:FAILED":        { why: "…", what: "Fix your company number", href: "#company-number" },
  "ALLERGEN_DATA_COMPLETE:FAILED":   { why: "…", what: "Complete these products", href: "/dashboard/products" },
  "FOOD_HYGIENE_RATING:MANUAL_REVIEW": { why: "…", what: "Check your shop name & address", href: "/dashboard/shops" },
}
// Withdraw dialog: clone the go-live <Dialog> at onboarding/page.tsx:516-533
// apiClient.post("/api/v1/onboarding/withdraw", {}) → setOnboarding(res.data); useToast on error.
```

## Runtime State Inventory

> This is a feature phase, not a rename/refactor — a full inventory is not required. The runtime-state items that *do* matter for correctness are captured explicitly below.

| Category | Items Found | Action Required |
|----------|-------------|------------------|
| Stored data | `vendor_onboarding` + `vendor_onboarding_gate` rows (RLS/FORCE, V43). Zero schema change; `WITHDRAWN` already in the status CHECK (`V43__vendor_onboarding.sql:29`). | Code-only; **no migration**. Verified. |
| Live service config | RabbitMQ topology is declared in code (`RabbitMQConfig.java`), not in a broker UI — so a NEW `onboarding.events` exchange is a code change that auto-declares on boot. No out-of-band broker config. | Add exchange bean; rebuild + restart core. |
| Message-broker in-flight state | The shared `payment_event_outbox` flusher will attempt to publish any onboarding row on its next tick. | The flusher dispatch MUST recognise `onboarding.events` before any onboarding row is written (Pitfall 1). |
| Secrets/env vars | New frontend config for support channel + SLA copy (recommended `NEXT_PUBLIC_*`). Stale `.env.local` secret trap noted in `project_motion_uplift_research`. | Add keys to `.env.local.example` + `env-validation.ts`; rebuild frontend. |
| Build artifacts | Docker images for core-java + frontend after code change. | **Rebuild ALL containers** before E2E (CLAUDE.md). |

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | SLA copy + support channel are best injected via `NEXT_PUBLIC_*` env vars (frontend config idiom) rather than backend `OnboardingProperties` served to the client. | Standard Stack / Config injection | If the team wants runtime override without a frontend rebuild, they must instead add backend config keys + expose on the DTO. Cheap to switch; flag in discuss/plan. |
| A2 | Recommended stall-event shape `OnboardingStateChangeEvent(onboardingId, tenantId, shopId, status, reason, occurredAt)` and event-type `ONBOARDING_STALLED`. | Outbox event | Wrong field set means a Phase 24 consumer contract change. It is an internal event with no external consumer yet, so low blast radius. |
| A3 | At-least-once stall emission (duplicate on each resubmit) is acceptable, matching the existing outbox contract. | Pitfall 4 | If duplicates are unacceptable, add a "already-emitted" guard. Decision belongs to the planner. |
| A4 | The admin review queue should be a NEW `GET /onboarding/admin/reviews` (or an extended `/pending`) returning `AdminOnboardingDto` for VERIFYING+MANUAL_REVIEW apps. | ONBD-03 | Endpoint-shape choice only; both satisfy the requirement (CONTEXT marks it discretion). |
| A5 | `GateDecision` FAIL should require a non-blank reason (mirror `RejectOnboardingRequest @NotBlank`), PASS/WAIVE reason optional. | ResolveGateRequest | If reason rules differ, adjust bean-validation. Low risk. |

**If any A# is wrong, it is a local, cheap correction — none blocks the zero-migration boundary or the sole-writer invariant.**

## Open Questions (RESOLVED — planner adopted every recommendation, 2026-07-14)

1. **Support-channel & SLA config location (frontend env vs backend property).**
   - What we know: frontend config is `NEXT_PUBLIC_*` (build-time, `env-validation.ts`); backend has `OnboardingProperties` (`onboarding.*`, runtime-overridable).
   - What's unclear: whether ops needs to change the support link/SLA without a frontend rebuild.
   - Recommendation: default to `NEXT_PUBLIC_SUPPORT_EMAIL` / `NEXT_PUBLIC_SUPPORT_URL` / `NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS`; register in `env-validation.ts` + `.env.local.example`. Revisit if runtime override is a hard requirement.
   - **RESOLVED:** plan 21-04 uses the `NEXT_PUBLIC_*` frontend env channel as recommended (A1).

2. **Review-queue endpoint: extend `/pending` vs new `/reviews`.**
   - What we know: `/pending` currently returns only `PENDING_APPROVAL` (`OnboardingAdminController.java:62-72`) and the approvals UI treats it as the approve/reject queue.
   - Recommendation: a separate `/reviews` keeps the approve/reject queue semantics clean and avoids changing the existing endpoint's contract (Incremental Betterment). Planner's call.
   - **RESOLVED:** plan 21-03 adds a new `GET /onboarding/admin/reviews` (does NOT touch `/pending`), per the recommendation (A4).

3. **Stall-event de-duplication policy** (see Pitfall 4 / A3).
   - **RESOLVED:** plan 21-02 emits at-least-once with an idempotent downstream consumer — no already-emitted guard added (A3).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| Docker + Docker Compose | Testcontainers integration tests + local E2E stack | ✓ (used every prior phase) | — | — |
| PostgreSQL 15 | RLS/onboarding persistence (compose + Testcontainers `postgres:15`) | ✓ | 15 | — |
| RabbitMQ 3.12 | Outbox flusher publish target for the new `onboarding.events` exchange | ✓ (compose) | 3.12 | Seam works without a consumer; message discarded at unbound topic exchange |
| Keycloak 24.0.5 | admin-role JWT for gate-resolve/review endpoints (real converter in tests) | ✓ | 24.0.5 | Realm re-import recipe on drift (`reference_keycloak_realm_reimport`) |
| Node 20+ / Next.js 16.2.2 | Frontend build + Jest + Playwright | ✓ | 16.2.2 | — |

**Missing dependencies with no fallback:** None.
**Note:** Ollama (`:11434`) is NOT running but is **out of scope** (deferred AI reviewer) — does not block this phase.

## Validation Architecture

> Nyquist validation is ENABLED (`workflow.nyquist_validation: true`). This maps every requirement + success criterion to a concrete test layer so a VALIDATION.md can be derived.

### Test Framework
| Property | Value |
|----------|-------|
| Frameworks | JUnit 5 + Testcontainers 1.21.3 (Postgres 15) + Spring MockMvc (backend); Jest 29.7.0 + @testing-library/react (frontend unit); Playwright 1.59.1 (E2E) |
| Config files | `core-java/build.gradle.kts` (`test` + `integrationTest` tasks, `@Tag("testcontainers")`); `frontend/jest.config.*`; `frontend/playwright.config.ts` |
| Quick run (backend unit) | `./gradlew test` (non-testcontainers) |
| Full run (backend) | `./gradlew test integrationTest` |
| Frontend unit | `npm test` (Jest) + `npm run build` (tsc typecheck gate) |
| E2E | `npx playwright test` (requires the rebuilt compose stack up) |
| Metrics gate | `scripts/docs-freshness.sh` (CI: `.github/workflows/docs-freshness.yml`) |

### Phase Requirements → Test Map
| Req | Behavior | Test Type | Command / Analog | File Exists? |
|-----|----------|-----------|------------------|-------------|
| ONBD-01 | `WITHDRAW` valid from 5 pre-live states; terminal rejects WITHDRAW | JUnit SM (unit) | `VendorOnboardingStateMachineServiceTest::withdrawFromEachSource` + `illegalTransitionsThrow` | ✅ **already present** (`:133-165`) |
| ONBD-01 | `POST /onboarding/withdraw` → WITHDRAWN; illegal source → 400 | Controller/Testcontainers | new test, analog `OnboardingAdminQueueIntegrationTest` | ❌ Wave 0 |
| ONBD-01 | Withdraw confirm dialog + terminal copy | Jest | new block in `onboarding/__tests__/page.test.tsx` | ❌ Wave 0 (file exists) |
| ONBD-02 | Update companyNumber only in DRAFT/ACTION_REQUIRED; else RFC 7807 400 | Controller/Testcontainers | new test, analog `OnboardingCompanyNumberValidationIntegrationTest` + `OnboardingResubmitIntegrationTest` | ❌ Wave 0 |
| ONBD-02 | Garbage company number → clean 400 (re-validation) | Controller | reuse `@Pattern` assertion pattern | ❌ Wave 0 |
| ONBD-02 | Inline company-number edit calls update endpoint | Jest | `onboarding/__tests__/page.test.tsx` | ❌ Wave 0 |
| ONBD-03 | `reviewPending` derived true iff VERIFYING+anyMR+noPending | JUnit (service/DTO) or Testcontainers | new test on `getMyOnboarding`/`toDto` | ❌ Wave 0 |
| ONBD-03 | gate-resolve PASS/WAIVE → recompute → GATES_PASSED → advance | Testcontainers (integration) + GateChainRunner unit | analog `GateChainRunnerTest` (unit) + admin-queue integration | ❌ Wave 0 |
| ONBD-03 | gate-resolve FAIL → GATE_FAILED → ACTION_REQUIRED | Testcontainers | new | ❌ Wave 0 |
| ONBD-03 | gate-resolve writes `vendor_onboarding_gate_aud` (Envers) | Testcontainers | analog `adminRejectsWithReason_…Envers` (`:242-245`) | ❌ Wave 0 |
| ONBD-03 | gate-resolve non-admin → 403; foreign tenant → 404 (RLS) | Testcontainers | analog `nonAdminGets403…` + RLS NOSUPERUSER (`:287-319`) | ❌ Wave 0 |
| ONBD-03 | Admin review queue lists VERIFYING+MANUAL_REVIEW | Testcontainers | analog `adminListsPending…` | ❌ Wave 0 |
| ONBD-03 | In-review copy + polling back-off | Jest | `onboarding/__tests__/page.test.tsx` | ❌ Wave 0 |
| ONBD-04 | Each `(gateType,status)` remediation block renders why+what+deep-link | Jest (per block) | new blocks | ❌ Wave 0 |
| ONBD-05 | `rejectionReason` present on vendor `OnboardingDto` | JUnit DTO serialization / Testcontainers | new | ❌ Wave 0 |
| ONBD-05 | REJECTED/SUSPENDED render reason + config support channel | Jest | new blocks | ❌ Wave 0 |
| ONBD-05 (journey) | bad company number → fix inline → resubmit → live | Playwright | `frontend/e2e/onboarding-blocked-flow.spec.ts` | ❌ Wave 0 (new spec) |
| Outbox seam (D-01) | stall emits an `onboarding.events` outbox row (tenant-stamped) | Testcontainers | new; assert `SELECT … FROM payment_event_outbox WHERE exchange='onboarding.events'` | ❌ Wave 0 |
| Outbox seam (D-01) | flusher deserializes `onboarding.events` without poisoning | JUnit (flusher unit) | analog flusher tests | ❌ Wave 0 |

### Sampling Rate
- **Per task commit:** `./gradlew test --tests "*Onboarding*"` (fast unit + SM) and/or `npm test -- onboarding`.
- **Per wave merge:** `./gradlew test integrationTest` + `npm test && npm run build`.
- **Phase gate:** full backend suite green + `npm run build` green + Playwright journey green + `scripts/docs-freshness.sh` OK, before `/gsd:verify-work`.

### Wave 0 Gaps
- [ ] Backend controller/Testcontainers tests for withdraw, update, gate-resolve, review-queue, outbox emission (analogs: `OnboardingAdminQueueIntegrationTest`, `OnboardingResubmitIntegrationTest`, `OnboardingCompanyNumberValidationIntegrationTest`, `GateChainRunnerTest`).
- [ ] Jest blocks in existing `onboarding/__tests__/page.test.tsx` + `approvals/__tests__/page.test.tsx`.
- [ ] New Playwright spec `frontend/e2e/onboarding-blocked-flow.spec.ts`.
- [ ] `scripts/docs-freshness.sh --write` + commit `docs/metrics.json` (counts move from 1257).
- Framework install: none needed — all frameworks present.

## Security Domain

> `security_enforcement` is not disabled in config → enabled. This phase touches auth, access control, input validation, and multi-tenant isolation.

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V1 Architecture | yes | State machine remains sole writer of `Shop.published`; gate-resolve writes a gate row, never `status`/`published`. |
| V2 Authentication | yes (reuse) | Keycloak OIDC JWT; `CurrentTenant.require()` resolves tenant server-side (never from request body). |
| V4 Access Control | **yes** | Admin surfaces `@PreAuthorize("hasRole('admin')")` (`OnboardingAdminController.java:46`); gate-resolve/review-queue follow the same. Vendor endpoints are tenant-self-scoped. RLS (V43 FORCE) pins every read/write; foreign tenant → 404 not 403 (no existence oracle). Interim resolver = tenant's own admin (documented, NOT cross-tenant). |
| V5 Input Validation | **yes** | Update companyNumber reuses `@Size(32)`+`@Pattern` (clean 400, and garbage never reaches Companies House API); `ResolveGateRequest.decision` is a bounded enum; `reason` `@Size`-bounded (mirror `RejectOnboardingRequest.java:15-17`). |
| V7 Error/Logging | yes | Vendor-visible gate `reason` must stay human-readable — never leak raw upstream errors (FhrsGate already logs raw at WARN, returns a fixed reason: `FhrsGate.java:98-104`). New stall event `reason` follows the same discipline. |
| V6 Cryptography | no | No new crypto; do not hand-roll. |

### Known Threat Patterns for this stack
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Cross-tenant gate resolution (resolve another tenant's gate) | Elevation of Privilege | RLS FORCE (V43) + `requireOnboardingById` returns 404 for foreign tenant (`VendorOnboardingService.java:363-366`); prove under NOSUPERUSER role (`OnboardingAdminQueueIntegrationTest.java:287-319`). |
| Async recompute writing without tenant GUC | Tampering / Info Disclosure | `runAndRecompute` re-establishes `TenantContext` from the passed `tenantId` (`GateChainRunner.java:113-117`); pass the resolved tenant from `resolveGate`. |
| Direct `published`/`status` write bypassing the SM | Tampering | Route through `transition(...)`/recompute only; verify with a guard-veto/state-unchanged test. |
| Cross-tenant event leakage via outbox | Info Disclosure | `payment_event_outbox` is RLS (V33) + the flusher iterates per-tenant with the tenant GUC (`PaymentEventOutboxFlusher.java:167-184`); stamp `tenant_id` on the onboarding row. |
| Company-number injection to Companies House lookup | Tampering | `@Pattern` bounds the value before it reaches the CH client (`CreateOnboardingRequest.java:33`). |
| Rejection/stall reason leaking provider internals to the vendor | Info Disclosure | Fixed human-readable reasons; raw provider text stays in WARN logs (`FhrsGate.java:99-103`). |

## Sources

### Primary (HIGH confidence — live code, file:line verified this session)
- `core-java/.../onboarding/VendorOnboardingStateMachineConfig.java` — WITHDRAW transitions (`:151-180`), guards (`:188-211`).
- `core-java/.../onboarding/GateChainRunner.java` — recompute + @Async tenant (`:111-210`); PENDING-skip (`:142`); advance-from-VERIFYING (`:157`); MANUAL_REVIEW park (`:196-199`).
- `core-java/.../onboarding/VendorOnboardingService.java` — canonical `transition` (`:239-297`), `kickGateChainAfterCommit` (`:311-322`), `toDto`/`toAdminDto` (`:368-405`), `normaliseCompanyNumber` (`:344-351`), `resubmit` (`:132-153`).
- `core-java/.../onboarding/OnboardingController.java` / `OnboardingAdminController.java` — vendor + admin surfaces + `@PreAuthorize` (`:46`).
- `core-java/.../onboarding/dto/{OnboardingDto,AdminOnboardingDto,GateDto,CreateOnboardingRequest,RejectOnboardingRequest}.java`.
- `core-java/.../onboarding/{VendorOnboarding,VendorOnboardingGate,GateType,GateStatus,GateResult,OnboardingEvent,OnboardingState}.java`; `OnboardingProperties.java`.
- `core-java/.../onboarding/gate/{FhrsGate,AllergenCompletenessGate,CompaniesHouseGate}.java`.
- `core-java/.../payment/{PaymentEventOutbox,PaymentEventOutboxFlusher}.java`; `order/OrderEventPublisher.java`; `config/RabbitMQConfig.java`.
- `core-java/.../common/GlobalExceptionHandler.java` (`:44-104` RFC 7807 mapping).
- `core-java/src/main/resources/db/migration/V43__vendor_onboarding.sql`; `application.yml:262-301`.
- Tests: `VendorOnboardingStateMachineServiceTest.java`, `OnboardingAdminQueueIntegrationTest.java`, `GateChainRunnerTest.java`.
- Frontend: `app/dashboard/onboarding/page.tsx`, `.../approvals/page.tsx`, `types/api.ts:324-398`, `lib/env-validation.ts`, `lib/api-client.ts`.
- Tooling: `docs/metrics.json`, `scripts/docs-freshness.sh`, `.github/workflows/docs-freshness.yml`.

### Secondary (MEDIUM — project docs / CONTEXT)
- `.planning/phases/21-onboarding-blocker-ux/21-CONTEXT.md`, `.planning/specs/onboarding-blocker-ux-SPEC.md`, `.planning/REQUIREMENTS.md`, `.planning/STATE.md`, `CLAUDE.md`, MEMORY.md entries (`learnings_fleet_supervision`, `feedback_frontend_typecheck_gate`, `project_motion_uplift_research`).

### Tertiary (LOW)
- None. No web/Context7 lookups were needed — the phase is fully internal and grounded in the codebase.

## Metadata

**Confidence breakdown:**
- Withdraw / update / gate-resolve / DTO derivation / recompute path: **HIGH** — every claim verified against live code with file:line; SM WITHDRAW proof already exists.
- Outbox stall-event shape + flusher extension: **HIGH on the coupling risk** (verified the dispatch is hard-coded), **MEDIUM on the exact event schema** (recommendation, flagged A2).
- Config-injection channel (frontend env vs backend property): **MEDIUM** — both patterns exist; recommendation flagged A1.
- Pitfalls (CR-01, @Async tenant, poison, typecheck, metrics): **HIGH** — each traced to documented in-repo code/comments or prior MEMORY learnings.

**Research date:** 2026-07-14
**Valid until:** ~2026-08-13 (stable internal codebase; re-verify if the onboarding package or the outbox flusher is refactored before planning).
