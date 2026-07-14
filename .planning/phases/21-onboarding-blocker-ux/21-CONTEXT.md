# Phase 21: Onboarding Blocker UX - Context

**Gathered:** 2026-07-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Make onboarding blockers **visible**, onboarding data **correctable**, and exits **reachable** — so a vendor who hits a blocker sees what's wrong, can fix bad data in place, can withdraw, and reaches a real human. Requirements ONBD-01..05 are locked by `.planning/specs/onboarding-blocker-ux-SPEC.md`; this phase decides HOW to implement them, not WHAT.

**In scope:** vendor-facing visibility + remediation + withdraw + rejection-reason UX; a DTO-derived "in review" state; the admin gate-resolve endpoint + review-queue extension (interim resolver); an outbox notification event; the onboarding-data update endpoint. **Zero Flyway migrations.**

**Out of scope (this phase):** a J'Toye platform-operator console / cross-tenant onboarding oversight; an Ollama AI reviewer agent; a stored FHRS establishment-picker; #205 webhook *delivery* (Phase 24 delivers the event this phase emits). See Deferred.

**⚠ Spec evidence is partly STALE — verified against live code 2026-07-14 (planner must trust the code, not the spec's file:line claims):**
- Spec Problem #3 says "`OnboardingEvent` has no WITHDRAW … the state cannot be reached." **FALSE now** — `OnboardingEvent.WITHDRAW` AND the state-machine transitions from 5 states → terminal `WITHDRAWN` already exist (`VendorOnboardingStateMachineConfig.java:151-180`). Only the vendor *endpoint* + service method + UI are missing.
- Spec Problem #2 says "RESUBMIT re-runs the gates against the same data." Partly stale — `POST /onboarding/resubmit` **already resets FAILED/MANUAL_REVIEW gates to PENDING and re-runs**; what's missing is the ability to *change the underlying data* (company number / shop address) before resubmitting.
- Spec Problem #4: `rejectionReason` is **already stored** on the `VendorOnboarding` entity and exposed on `AdminOnboardingDto` — only the vendor `OnboardingDto` omits it.
- Confirmed real: the MANUAL_REVIEW black hole (`GateChainRunner` leaves it in VERIFYING; admin `/pending` lists only PENDING_APPROVAL); no stall notification; no J'Toye platform view (both dashboards are tenant-scoped).
</domain>

<spec_lock>
## Requirements (locked via the milestone SPEC)

**5 requirements (ONBD-01..05) are locked** in `.planning/specs/onboarding-blocker-ux-SPEC.md` — the authoritative source of truth for WHAT this phase delivers. Downstream agents MUST read it before planning. Requirements are not duplicated here; the decisions below record only HOW to implement them.
</spec_lock>

<decisions>
## Implementation Decisions

### Manual-review resolution model (the core decision — user, 2026-07-14)
- **D-01 — "Seams now, J'Toye console later."** There is NO J'Toye platform-operator identity today; both the vendor page and the "approvals" page are tenant-scoped (the vendor org's own `admin` role). Phase 21 therefore ships the **vendor-facing fixes + the seams**, and the real J'Toye-side reviewer becomes its own later phase:
  - Build the admin `gate-resolve` endpoint `POST /onboarding/admin/{id}/gates/{gateType}/resolve {decision: PASS|WAIVE|FAIL, reason}` so a stuck gate CAN be unstuck. **Interim resolver = the existing tenant `admin` role** (same trust boundary as the current approve/reject queue) — documented explicitly as interim.
  - Emit an **outbox notification event** when an onboarding enters MANUAL_REVIEW / stalls, so a J'Toye consumer (Phase 24 webhooks, or the future console/AI agent) can be told. Phase 21 only WRITES the event.
- **D-02 — J'Toye platform console + optional Ollama AI reviewer are DEFERRED to a dedicated phase** (see Deferred). They are a new capability (platform-operator RBAC + cross-tenant RLS bypass), which both v2.3 specs explicitly defer; folding them in would break Phase 21's zero-migration boundary.

### Manual-review visibility (ONBD-03)
- **D-03 — DTO-derive `reviewPending`**, do not migrate a new state. `reviewPending = status==VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING`, computed at the DTO layer. Vendor UI renders it as "In review" with honest, config-driven SLA copy (e.g. "a reviewer checks these within N business days") replacing "usually takes under a minute"; polling backs off once `reviewPending` is true.
- **D-04 — Admin review queue** = extend the existing tenant-scoped admin surface to also list review-pending applications (VERIFYING + MANUAL_REVIEW), each with the gate-resolve control. The `gate-resolve` handler sets the gate row's status directly (admin override) then triggers the **existing** `GateChainRunner` recompute, which advances the state machine from VERIFYING on all-passed/waived — reuse, do not reinvent the advance logic.

### Withdraw (ONBD-01)
- **D-05 — Expose withdraw from ALL 5 pre-live states** the state machine already wires (DRAFT / VERIFYING / ACTION_REQUIRED / PENDING_APPROVAL / APPROVED) — a conscious superset of the spec's locked 3, since the transitions already exist and a vendor should be able to bail any time before LIVE. Add only `POST /onboarding/withdraw` + service method + a confirm-dialog UI. Terminal; restart = a new application.

### Correctable data (ONBD-02) — zero migration
- **D-06 — Onboarding update endpoint covers `companyNumber` only** (blank/whitespace = sole trader — there is NO separate sole-trader field; nulling the company number IS becoming a sole trader, matching create semantics). Valid only in DRAFT / ACTION_REQUIRED; re-validated like create (reuse the `CreateOnboardingRequest` `@Size(32)` + `@Pattern` company-number rule); rejected outside those states with RFC 7807.
- **D-07 — "FHRS establishment override" = fix the shop's name/address, then resubmit.** The FHRS gate matches on `shop.getName()` + `shop.getAddress()` (`FhrsGate.java:95`), so the remediation is a deep-link to the **shop edit** screen (existing shop CRUD) → correct name/address → resubmit → the gate re-matches. No stored establishment-picker, no new column (keeps zero-migration). A genuinely-unmatchable case falls through to the gate-resolve seam (D-01).

### Remediation blocks (ONBD-04) — frontend-static, no backend enrichment
- **D-08 — Map `(gateType, status)` → why / what-to-do / deep-link on the frontend.** The gate `reason` string already carries the specifics: the allergen FAILED reason **names the offending products by SKU** (`AllergenCompletenessGate.java:115-122`) and the FHRS reason states the miss. No GateDto enrichment needed. Deep links: BUSINESS_VERIFIED → inline company-number edit (the D-06 update endpoint); ALLERGEN_DATA_COMPLETE → `/dashboard/products`; FOOD_HYGIENE_RATING → shop edit (D-07).

### Rejection reason + support channel (ONBD-05)
- **D-09 — Add `rejectionReason` to the vendor `OnboardingDto`** (already on the entity; one-field record change). Render it plus a **config-injected** support channel (mailto/URL, no hardcoding — GLOBAL_RULE_6) on the REJECTED and SUSPENDED terminal states, replacing the bare "Contact support for details".

### Claude's Discretion
- Exact shape of the `reviewPending` DTO field vs a computed getter; admin-queue endpoint shape (extend `/pending` vs a new `/reviews`); the outbox event's payload schema (align with V46 outbox conventions); the config keys/defaults for the SLA copy ("N business days") and support channel; whether the allergen deep link carries a products filter param; test fixture design. Follow existing onboarding package conventions.
</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Locked requirements (source of truth)
- `.planning/specs/onboarding-blocker-ux-SPEC.md` — the 5 locked requirements, boundaries, and the "derive don't migrate" resolution. **MUST read before planning.** NOTE its file:line evidence is partly stale — verify against the code refs below.

### Onboarding domain — current implementation (grounded 2026-07-14)
- `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEvent.java` — `WITHDRAW` event **already exists** (line 38).
- `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineConfig.java` §151-180 — WITHDRAW transitions from 5 states → terminal WITHDRAWN **already wired**; guards at 188-211. The SM is the sole writer of `Shop.published`.
- `core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java` §157-199 — recompute logic (MANUAL_REVIEW leaves it in VERIFYING); `@Async` re-establishes `TenantContext` (§113-117). The gate-resolve → recompute path reuses this.
- `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java` — vendor endpoints (create/submit/resubmit/go-live/me); `resubmit` already resets flagged gates.
- `core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingAdminController.java` §62-72 — admin `/pending` (PENDING_APPROVAL only), approve, reject; `@PreAuthorize("hasRole('admin')")`, tenant-scoped (no platform operator).
- `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java` — the single canonical `transition(...)` path all state changes go through.
- `core-java/src/main/java/uk/jtoye/core/onboarding/dto/OnboardingDto.java` — vendor DTO (add `rejectionReason`; add derived `reviewPending`).
- `core-java/src/main/java/uk/jtoye/core/onboarding/dto/AdminOnboardingDto.java` — already carries `rejectionReason` + `shopName`.
- `core-java/src/main/java/uk/jtoye/core/onboarding/dto/GateDto.java` — `gateType/status/mandatory/reason/checkedAt` (reason carries the specifics; evidence withheld).
- `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboarding.java` — `rejectionReason` already on entity; NO sole-trader field; NO FHRS establishment column.
- `core-java/src/main/java/uk/jtoye/core/onboarding/dto/CreateOnboardingRequest.java` §32-35 — the `@Size(32)`+`@Pattern` company-number validation to reuse in the update request.
- `core-java/src/main/java/uk/jtoye/core/onboarding/gate/FhrsGate.java` §95,107 — matches on shop name/address; MANUAL_REVIEW on no/ambiguous match (grounds D-07).
- `core-java/src/main/java/uk/jtoye/core/onboarding/gate/AllergenCompletenessGate.java` §115-122 — FAILED reason names offending SKUs (grounds D-08).

### Frontend (current)
- `frontend/app/dashboard/onboarding/page.tsx` — vendor page: `POLL_STATES` §107, "under a minute" copy §69, ACTION_REQUIRED failed-gate list §449-467, no withdraw, "Contact support" §74-75. This is the ONBD-03/04/05 surface.
- `frontend/app/dashboard/onboarding/approvals/page.tsx` — tenant-scoped admin queue UI to extend for review-pending + gate-resolve.

### Notification seam
- V46 transactional outbox — reference the existing outbox producer conventions for the MANUAL_REVIEW/stall event (delivered by Phase 24 #205 webhooks).

### Design + prior context
- `docs/architecture/VENDOR_ONBOARDING_STATE_MODEL.md` — the full onboarding state model + gate chain.
- `.planning/milestones/v2.2-phases/18-vendor-onboarding-first-slice/18-CONTEXT.md` — Phase 18 locked decisions (state-machine triad, gate registry, @Async tenant landmine, RLS/V43 conventions).
</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`GateChainRunner.runAndRecompute` / recompute** — the gate-resolve endpoint sets the gate row then triggers this existing async recompute (which fires GATES_PASSED/GATE_FAILED and advances from VERIFYING). Do not reinvent advancement.
- **`POST /onboarding/resubmit`** — already resets FAILED/MANUAL_REVIEW gates to PENDING; the ONBD-02 data-correction flow feeds into it (fix data → resubmit).
- **`CreateOnboardingRequest` company-number validation** — reuse `@Size(32)`+`@Pattern` in the update request so garbage is a clean 400.
- **Existing shop CRUD + edit screen** — the FHRS remediation deep-link target (D-07); no new surface.
- **`AdminOnboardingDto` + approvals page** — extend for the review queue; `rejectionReason` already flows there.
- **V46 outbox** — the notification-event producer.

### Established Patterns
- **State machine is the sole writer of `Shop.published`; every transition goes through `OnboardingEvent` via `VendorOnboardingService.transition(...)`** — no endpoint writes status directly (gate-resolve writes a GATE row, then recomputes → the SM advances).
- **@Async tenant landmine** — any async recompute must re-establish `TenantContext` (GateChainRunner takes `tenantId` and sets it in try/finally). The gate-resolve path must preserve this.
- **Admin surfaces are `@PreAuthorize("hasRole('admin')")` + tenant-scoped** (V43 RLS pins reads) — the gate-resolve endpoint follows the same pattern; it is NOT a cross-tenant surface.
- **RFC 7807 problem details** for validation/illegal-transition errors (400).
- **Config injection (GLOBAL_RULE_6)** — support channel + SLA copy are config-injected, never hardcoded literals.

### Integration Points
- Vendor `OnboardingDto` (+ `rejectionReason`, + derived `reviewPending`) → the vendor page.
- New `POST /onboarding/withdraw` + `POST /onboarding/admin/{id}/gates/{gateType}/resolve` + the onboarding-data update endpoint.
- Outbox event on MANUAL_REVIEW/stall → Phase 24 delivery.
- Frontend deep links: company-number inline edit, `/dashboard/products`, shop edit.
</code_context>

<specifics>
## Specific Ideas
- The vendor should never see a silent "under a minute" spinner forever — replace with an honest, backed-off "in review" state the moment a gate needs a human.
- J'Toye should be *notified* when an onboarding stalls (the outbox seam), even though the actual J'Toye-side reviewer console comes later.
- Journey-matrix requirement (from the spec): drive one blocked onboarding end-to-end in Playwright — bad company number → fix inline → resubmit → live.
</specifics>

<deferred>
## Deferred Ideas
- **J'Toye platform-operator console** — a cross-tenant onboarding oversight dashboard (see every vendor's stalled/review-pending applications, resolve gates as J'Toye, not as the tenant's own admin). Needs a platform-operator role + a deliberate audited cross-tenant RLS bypass (or per-tenant fan-out). A genuinely new capability — **propose as its own dedicated v2.3 phase** (both v2.3 specs already defer platform-operator scope). Phase 21 lays the gate-resolve endpoint + outbox event as its seams.
- **Ollama AI reviewer agent** — an AI agent (on the existing Ollama stack) that assists/auto-resolves manual-review gates (e.g. reconciling fuzzy FHRS matches) and/or drafts vendor guidance. User idea, 2026-07-14. Caveat: Ollama is currently not running (`:11434` host conflict per the image spec). Fits the AI track; pairs with the platform-console phase above.
- **Stored FHRS establishment-picker** — let the vendor pick from ambiguous multi-matches, persisting a chosen `establishment_id`. Needs a new column = a migration; deferred to preserve Phase 21's zero-migration boundary. This phase uses fix-shop-data + resubmit instead (D-07).
- **Backend-enriched GateDto remediation hints** — a structured `remediation` code/target on the gate instead of a frontend-static map. Deferred; the frontend-static mapping + existing `reason` specifics suffice (D-08).
- **Reviewer SLA tracking / escalation, multi-reviewer workflow, reapply-after-REJECTED** — explicitly deferred by the spec.

### Reviewed Todos (not folded)
None — no pending todos matched this phase.
</deferred>

---

*Phase: 21-onboarding-blocker-ux*
*Context gathered: 2026-07-14*
