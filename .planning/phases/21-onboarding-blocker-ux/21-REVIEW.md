---
phase: 21-onboarding-blocker-ux
reviewed: 2026-07-14T13:07:43Z
depth: standard
files_reviewed: 17
files_reviewed_list:
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/OnboardingDto.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/ResolveGateRequest.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/dto/UpdateOnboardingRequest.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/GateDecision.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingAdminController.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingController.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingStateChangeEvent.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingGateRepository.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
  - frontend/app/dashboard/onboarding/approvals/page.tsx
  - frontend/app/dashboard/onboarding/page.tsx
  - frontend/lib/env-validation.ts
  - frontend/types/api.ts
findings:
  critical: 0
  warning: 3
  info: 2
  total: 5
status: issues_found
---

# Phase 21: Code Review Report

**Reviewed:** 2026-07-14T13:07:43Z
**Depth:** standard
**Files Reviewed:** 17
**Status:** issues_found

## Summary

Reviewed the phase-21 onboarding-blocker-UX diff (`git diff 48d9e9b..HEAD`) across the backend gate-resolve admin surface, the outbox-routed onboarding stall event, and the two vendor/admin frontend pages. This is a well-documented, evidently already-hardened change set — comment markers like `CR-01`/`WR-02`/`WR-03` throughout `VendorOnboardingService`/`GateChainRunner`/`PaymentEventOutboxFlusher` indicate prior review passes already closed several classes of bug (after-commit gate-chain dispatch, tenant re-establishment on the async worker, outbox poison-branch ordering, auto-approve-veto rollback, TOCTOU on the allergen gate at go-live).

I verified all seven hard invariants from the phase brief directly against the diff and found **no violations**:

1. **State-machine sole writer** — `VendorOnboardingService.resolveGate` writes only the gate row (`row.setStatus/setReason/setCheckedAt` + `gateRepository.save`) and dispatches `kickGateChainAfterCommit` — never an inline `runAndRecompute` call, never a direct `status`/`published` write. Confirmed.
2. **Outbox poison ordering** — `PaymentEventOutboxFlusher.publishRow`'s `onboarding.events` branch (line 271) precedes the final `PaymentEvent.class` else (line 273-274). `OnboardingEventPublisher` is not `@Transactional` and uses the 5-arg `PaymentEventOutbox` ctor with `RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE`. Confirmed.
3. **`@Async` tenant landmine** — `GateChainRunner.runAndRecompute(UUID onboardingId, UUID tenantId)` re-establishes `TenantContext.set(tenantId)` at the top of a `try/finally`, and `publishStall(onboardingId, tenantId, ...)` is called with that same worker-scoped `tenantId` parameter (never an ambient/ThreadLocal read). Confirmed.
4. **RLS / access control** — `OnboardingAdminController` carries class-level `@PreAuthorize("hasRole('admin')")`, which covers the new `resolve` and `reviews` endpoints; `requireOnboardingById`/gate lookups run under FORCE RLS so a foreign tenant's id 404s with no existence oracle (also proven by `OnboardingGateResolveIntegrationTest`). Confirmed.
5. **Input validation** — `UpdateOnboardingRequest` reuses the exact `@Size(32)` + `@Pattern` pair from `CreateOnboardingRequest`; `ResolveGateRequest.decision` is a `@NotNull` bounded enum, and FAIL-without-reason is rejected in the service (`IllegalArgumentException` → 400). Confirmed.
6. **Config injection** — the vendor page reads the support channel and SLA copy exclusively through `resolveSupportChannel(process.env.NEXT_PUBLIC_SUPPORT_EMAIL, process.env.NEXT_PUBLIC_SUPPORT_URL)` and `process.env.NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS`; no hardcoded mailto/URL/day-count literal in `page.tsx`. Confirmed.
7. **DTO derivation** — `OnboardingDto.toDto`'s `reviewPending` predicate exactly matches the spec (`VERIFYING && anyGate==MANUAL_REVIEW && noGate==PENDING`), and `rejectionReason` is a plain nullable pass-through with null-safe rendering on both frontend pages. Confirmed.

No BLOCKER-tier findings survived scrutiny. Three WARNING-tier gaps and two INFO-tier issues were found, detailed below — the most substantive is a missing state guard on `resolveGate` that lets an admin silently strand an onboarding that has already left `VERIFYING`.

## Warnings

### WR-01: `resolveGate` has no state guard — can silently strand an onboarding outside VERIFYING

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/VendorOnboardingService.java:331-362`

**Issue:** `resolveGate` overrides a gate row unconditionally — it never checks `onboarding.getStatus() == OnboardingState.VERIFYING` before writing. `GateChainRunner.runAndRecompute` (the recompute this method dispatches) *does* guard on that same condition (`if (onboarding.getStatus() != OnboardingState.VERIFYING) { return; }`, line 163), so a resolve issued while the onboarding is already `PENDING_APPROVAL`, `APPROVED`, or `LIVE` mutates the gate row but the recompute silently no-ops. Concretely: an admin calls `resolve` with `decision=FAIL` on a mandatory gate for an onboarding already parked at `PENDING_APPROVAL` (post-recompute, all gates were previously green). The gate row flips to `FAILED` and is audited, but the onboarding's `status` never changes — it stays `PENDING_APPROVAL` indefinitely. The *next* time anyone calls `POST /approve`, the state-machine's `approveGuard` re-reads the now-`FAILED` mandatory row and vetoes with a bare `400 "Illegal transition or gate guard veto"`, with no way for the admin to discover *why* short of manually inspecting gate rows. This exact scenario is untested — `OnboardingGateResolveIntegrationTest` only exercises resolve while the onboarding is genuinely `VERIFYING`.

**Fix:** Mirror the recompute's own guard in the service method, before the gate write:
```java
public AdminOnboardingDto resolveGate(UUID onboardingId, GateType gateType,
                                      GateDecision decision, String reason) {
    UUID tenantId = CurrentTenant.require();
    VendorOnboarding onboarding = requireOnboardingById(onboardingId);

    if (onboarding.getStatus() != OnboardingState.VERIFYING) {
        throw new InvalidStateTransitionException(
                "Gates can only be resolved while the onboarding is VERIFYING "
                        + "(current: " + onboarding.getStatus() + ")");
    }
    ...
```

### WR-02: Review-pending poll backoff does not cover `PENDING_APPROVAL`, contradicting its own stated intent

**File:** `frontend/app/dashboard/onboarding/page.tsx:152-159, 246-253`

**Issue:** The new `FAST_POLL_MS`/`REVIEW_POLL_MS` split is introduced with the comment "back right off once a human is in the loop (reviewPending) — a manual review advances on a reviewer action, not a webhook, so hammering GET /me every 4s is pointless." But `reviewPending` is derived server-side as `status == VERIFYING && ...` (see `VendorOnboardingService.toDto`), so it is **always `false`** while `status === "PENDING_APPROVAL"` — which is *also* a purely human-driven state (the admin approve/reject queue), by the same rationale the comment gives. `PENDING_APPROVAL` stays in `POLL_STATES` and therefore polls at `FAST_POLL_MS` (4s) for as long as the application sits in the admin queue — potentially hours or days — exactly the class of "hammering GET /me… is pointless" load this change was meant to eliminate.

**Fix:** Back off for `PENDING_APPROVAL` too, e.g.:
```ts
const intervalMs =
  pollStatus === "PENDING_APPROVAL" || reviewPending ? REVIEW_POLL_MS : FAST_POLL_MS
```

### WR-03: New required env vars are silently unenforced in production

**File:** `frontend/lib/env-validation.ts:30-41, 96-107`

**Issue:** `NEXT_PUBLIC_SUPPORT_EMAIL`, `NEXT_PUBLIC_SUPPORT_URL`, and `NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS` were added to `requiredEnvVars` (docstring: "config-injected... so the onboarding page never hardcodes a support mailto/URL or an 'N days' literal"). But `validateEnvironment()` returns immediately when `NODE_ENV === 'production'` (line 96), *before* the `missing.length > 0` check ever runs — so in production, a completely absent value for any of these three (or any required var) produces **zero** log output, warning, or failure. The file's own header comment claims this "runs at server startup to fail fast if configuration is missing," but for production it does neither (never fails, and now also never even logs). The onboarding UI itself degrades gracefully (no crash — `support.href` falls back to a generic "contact your account manager" message, `reviewSlaDays` falls back to a genericised copy), but the misconfiguration is now completely invisible to operators.

**Fix:** Move the missing-var check ahead of the production early-return (at minimum log a `console.error`, ideally fail the health check), e.g.:
```ts
if (missing.length > 0) {
  console.error(`[ERROR] Missing required environment variables: ${missing.join(', ')}`);
}
if (process.env.NODE_ENV === 'production') return; // warnings-only below stays dev-only
```

## Info

### IN-01: `GateChainRunner` class Javadoc is stale relative to the code it documents

**File:** `core-java/src/main/java/uk/jtoye/core/onboarding/GateChainRunner.java:16-22`

**Issue:** The class-level Javadoc states "This slice ships zero gate beans, so on a real `submit()` the recompute short-circuits (no mandatory gate rows)." That was true for Phase 18-01's data-layer slice, but `FhrsGate`, `CompaniesHouseGate`, and `AllergenCompletenessGate` are all `@Component`-registered `OnboardingGate` beans (from later Phase 18 slices) and materialise/evaluate on every real `submit()`. This is exactly the file this phase added the MANUAL_REVIEW stall-notification branch to, so a maintainer extending or debugging that new branch is misled by the adjacent doc block into believing gate rows never populate in practice.

**Fix:** Update or delete the stale sentence; note that 3 concrete gate beans are now registered and `materialise`/`runAndRecompute` are live paths.

### IN-02: `BUSINESS_VERIFIED:FAILED` remediation deep-link can point at a not-yet-rendered anchor

**File:** `frontend/app/dashboard/onboarding/page.tsx` (`REMEDIATION["BUSINESS_VERIFIED:FAILED"]`, `RemediationRow`, `canEditCompanyNumber`)

**Issue:** The remediation card for a `FAILED` `BUSINESS_VERIFIED` gate links to `href="#company-number"`, but the `id="company-number"` element only renders when `canEditCompanyNumber` is true (`status === "DRAFT" || status === "ACTION_REQUIRED"`). `actionableGates` is computed from the raw gate list independent of overall `status`, so during the brief window where a gate row is already `FAILED` but the async recompute hasn't yet driven the onboarding's `status` out of `VERIFYING` into `ACTION_REQUIRED`, the "Edit company number" button is rendered pointing at an anchor that doesn't exist on the page yet — a dead in-page link until the next poll lands the status transition.

**Fix:** Low priority (self-heals on the next 4s poll); could gate the deep-link rendering on `canEditCompanyNumber` as well, falling back to plain text otherwise.

---

_Reviewed: 2026-07-14T13:07:43Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
