# Phase 21: Onboarding Blocker UX - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-07-14
**Phase:** 21-onboarding-blocker-ux
**Areas discussed:** Manual-review reviewer identity, Withdraw reachable states, (FHRS override + remediation source resolved by code grounding)

---

## Manual-review reviewer identity / resolution model

The user's freeform framing surfaced the deeper gap: *"the user doesn't know what's happening… I don't know if we get a notice about a stalled process… the only visible/implemented dashboard appears to be the Vendor's."* Verified true — there is no J'Toye platform-operator identity; both dashboards are tenant-scoped; MANUAL_REVIEW notifies nobody and appears in no queue.

| Option | Description | Selected |
|--------|-------------|----------|
| Seams now, J'Toye console later | Vendor-facing fixes + outbox stall event + gate-resolve endpoint (interim: tenant admin resolves, documented interim); J'Toye console + optional Ollama AI reviewer = own later phase | ✓ |
| Vendor-visibility only | Only the vendor-facing half + notification; ALL resolution (even the endpoint) moves to a later console phase | |
| Pull J'Toye reviewer into v2.3 now | Add platform-operator role + cross-tenant review console (+ Ollama assist) now — breaks zero-migration, needs new RBAC + cross-tenant RLS | |

**User's choice:** Seams now, J'Toye console later.
**Notes:** User floated J'Toye involvement via direct communication OR a dedicated Ollama AI agent, and wanted stall notifications. Both require a platform actor that doesn't exist — captured as the deferred J'Toye-console/AI-reviewer phase. Phase 21 lays the gate-resolve endpoint + outbox event as the seams.

---

## Withdraw reachable states

| Option | Description | Selected |
|--------|-------------|----------|
| All 5 pre-live states | DRAFT/VERIFYING/ACTION_REQUIRED/PENDING_APPROVAL/APPROVED — the state machine already wires all 5; free + more complete | ✓ |
| Just the spec's 3 | DRAFT/VERIFYING/ACTION_REQUIRED only — strict to the locked spec | |

**User's choice:** All 5 pre-live states.
**Notes:** A conscious superset of the spec's locked 3, justified because the transitions already exist and a vendor should be able to bail any time before LIVE.

---

## FHRS override mechanism + Remediation block source (resolved by code grounding)

These two were presented as gray areas but resolved by reading the gate code rather than needing a separate user turn:
- **FHRS override:** `FhrsGate.java:95` matches on `shop.getName()`+`shop.getAddress()` → the override is "fix the shop's name/address, then resubmit" (deep-link to shop edit). Zero migration; no establishment-picker/column. Residual unmatchable → gate-resolve seam.
- **Remediation source:** `AllergenCompletenessGate.java:115-122` already names offending SKUs in the FAILED `reason`, and GateDto carries `reason` → a **frontend-static** `(gateType,status)→why/what/deep-link` mapping suffices; no backend GateDto enrichment.

**Confirmed by user:** "Lock it in" — the full seams-now resolution (zero-migration ONBD-02, frontend-static remediation, DTO-derived review state).

---

## Claude's Discretion
- `reviewPending` DTO field vs computed getter; admin-queue endpoint shape (extend `/pending` vs new `/reviews`); outbox event payload schema; config keys/defaults for the SLA copy and support channel; whether the allergen deep link carries a products filter param; test fixture design.

## Deferred Ideas
- J'Toye platform-operator console (cross-tenant onboarding oversight) — new capability, own dedicated phase; propose for the v2.3 roadmap.
- Ollama AI reviewer agent for manual-review resolution (Ollama `:11434` currently down).
- Stored FHRS establishment-picker (needs a migration) — deferred to keep zero-migration.
- Backend-enriched GateDto remediation hints — deferred (frontend-static suffices).
