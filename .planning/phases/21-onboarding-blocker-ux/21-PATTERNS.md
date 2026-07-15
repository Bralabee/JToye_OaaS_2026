# Phase 21: Onboarding Blocker UX - Pattern Map

**Mapped:** 2026-07-14
**Files analyzed:** 24 (10 backend main + 1 config + 6 backend tests + 5 frontend + 2 frontend tests). Zero Flyway migrations, zero new dependencies.
**Analogs found:** 24 / 24 (every file has an in-repo analog — this is an ~80% assembly phase)

> **How to read this map:** every analog cited below was opened and verified this session — line numbers are live as of 2026-07-14. The onboarding package is the primary source: most "new" work is a new method/field/branch beside an existing one in the SAME file, so the closest analog is frequently the file itself. Where that is the case the excerpt shows the exact sibling pattern to clone.

---

## File Classification

| Target File | New/Mod | Role | Data Flow | Closest Analog | Match |
|-------------|---------|------|-----------|----------------|-------|
| `onboarding/OnboardingController.java` | modify | controller | request-response | self (`create`/`submit`/`me`) | exact |
| `onboarding/OnboardingAdminController.java` | modify | controller | request-response | self (`pending`/`reject`) | exact |
| `onboarding/VendorOnboardingService.java` | modify | service | CRUD + state-machine | self (`resubmit`/`reject`/`transition`) | exact |
| `onboarding/dto/OnboardingDto.java` | modify | model (record) | transform | `dto/AdminOnboardingDto.java` (`rejectionReason`) | exact |
| `onboarding/dto/UpdateOnboardingRequest.java` | **new** | model (DTO) | transform | `dto/CreateOnboardingRequest.java` | exact |
| `onboarding/dto/ResolveGateRequest.java` | **new** | model (DTO) | transform | `dto/RejectOnboardingRequest.java` | exact |
| `onboarding/OnboardingEventPublisher.java` | **new** | service (producer) | event-driven | `order/OrderEventPublisher.java` | exact |
| `onboarding/OnboardingStateChangeEvent.java` | **new** | model (record) | event-driven | `order/OrderStateChangeEvent.java` | exact |
| `config/RabbitMQConfig.java` | modify | config | event-driven | self (`orderEventsExchange()`) | exact |
| `payment/PaymentEventOutboxFlusher.java` | modify | service (dispatch) | event-driven | self (`publishRow` dispatch) | exact |
| `onboarding/GateChainRunner.java` | modify | service | event-driven | self (MANUAL_REVIEW park branch) | exact |
| `onboarding/OnboardingProperties.java` | modify (only if backend-config chosen — see A1) | config | — | self | exact |
| `frontend/app/dashboard/onboarding/page.tsx` | modify | component (page) | request-response + poll | self + `approvals/page.tsx` dialogs | exact |
| `frontend/app/dashboard/onboarding/approvals/page.tsx` | modify | component (page) | request-response | self (queue + reject dialog) | exact |
| `frontend/types/api.ts` | modify | model (types) | transform | self (`OnboardingDto`/`AdminOnboardingDto`) | exact |
| `frontend/lib/env-validation.ts` | modify | config | — | self (`requiredEnvVars`) | exact |
| `frontend/.env.local.example` | modify | config | — | self / `env-validation.ts` | role-match |
| `.../VendorOnboardingStateMachineServiceTest.java` | **already passes** | test (unit) | — | self (`withdrawFromEachSource`) | exact |
| `.../OnboardingWithdrawIntegrationTest.java` (or fold into existing) | **new** | test (integration) | — | `OnboardingAdminQueueIntegrationTest.java` | exact |
| `.../OnboardingCompanyNumberUpdateIntegrationTest.java` | **new** | test (integration) | — | `OnboardingAdminQueueIntegrationTest.java` + `OnboardingCompanyNumberValidationIntegrationTest.java` | exact |
| `.../OnboardingGateResolveIntegrationTest.java` | **new** | test (integration) | — | `OnboardingAdminQueueIntegrationTest.java` | exact |
| `.../GateChainRunnerTest.java` | modify | test (unit) | — | self (recompute decision table) | exact |
| `frontend/.../onboarding/__tests__/page.test.tsx` | modify | test (Jest) | — | self (`routeGet` idiom) | exact |
| `frontend/e2e/onboarding-blocked-flow.spec.ts` | **new** | test (Playwright) | — | `frontend/e2e/vendor-refund-flow.spec.ts` | exact |

---

## Pattern Assignments

### `OnboardingController.java` (controller, request-response) — MODIFY

**Analog:** self. New `POST /onboarding/withdraw` and the company-number update endpoint clone the thin-delegation shape of `submit()`/`resubmit()`.

**Thin-delegate + OpenAPI idiom** (`OnboardingController.java:85-96`):
```java
@PostMapping("/resubmit")
@Operation(summary = "Resubmit onboarding", description = "...")
@ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Onboarding resubmitted (VERIFYING)"),
        @ApiResponse(responseCode = "400", description = "Illegal transition (not in ACTION_REQUIRED)"),
        @ApiResponse(responseCode = "404", description = "No onboarding for this tenant")
})
public ResponseEntity<OnboardingDto> resubmit() {
    return ResponseEntity.ok(vendorOnboardingService.resubmit());
}
```
- `withdraw()` → body-less POST returning `OnboardingDto` (clone `resubmit()` verbatim, swap service call).
- Update endpoint takes `@Valid @RequestBody UpdateOnboardingRequest` (clone the `@Valid @RequestBody CreateOnboardingRequest` binding at `:54-55`). RESEARCH recommends `PATCH /onboarding`; note the class has no `@PatchMapping` precedent — either add one or use `POST /onboarding/company-number` to stay consistent with the all-POST surface. **Planner's call.**
- No `@PreAuthorize` here — this controller is vendor-self-scoped; tenant resolved server-side in the service.

---

### `OnboardingAdminController.java` (controller, request-response) — MODIFY

**Analog:** self. Add `POST /{id}/gates/{gateType}/resolve` and the review-queue GET beside `reject()`/`pending()`. Class-level `@PreAuthorize` already covers them.

**Class guard + admin-scope doc** (`OnboardingAdminController.java:44-50`):
```java
@RestController
@RequestMapping("/onboarding/admin")
@PreAuthorize("hasRole('admin')")  // #83 RBAC pattern: approvals require the admin realm role
@Tag(name = "Onboarding Admin", ...)
@SecurityRequirement(name = "bearer-jwt")
@SecurityRequirement(name = "tenant-header")
public class OnboardingAdminController {
```

**Path-var + validated-body endpoint** (`OnboardingAdminController.java:97-111`):
```java
@PostMapping("/{id}/reject")
@ApiResponses(value = { /* 200 / 400 / 403 / 404 */ })
public ResponseEntity<AdminOnboardingDto> reject(
        @Parameter(description = "Onboarding id") @PathVariable UUID id,
        @Parameter(description = "Rejection reason") @Valid @RequestBody RejectOnboardingRequest req) {
    return ResponseEntity.ok(vendorOnboardingService.reject(id, req.getReason()));
}
```
- gate-resolve: add `@PathVariable GateType gateType` (Spring binds the enum from the path segment), `@Valid @RequestBody ResolveGateRequest req` → `vendorOnboardingService.resolveGate(id, gateType, req.getDecision(), req.getReason())`.
- Review queue: mirror `pending()` at `:62-72`. RESEARCH A4 / Open Q2 recommends a **new** `GET /reviews` over mutating `/pending` (Incremental Betterment — keeps the approve/reject queue contract clean). Returns `List<AdminOnboardingDto>`.
- **Interim-resolver doc requirement (D-01):** the class Javadoc at `:34-43` already documents "tenant-scoped admin, platform-wide queue is a follow-up" — extend that same note to say the gate-resolve authority is the tenant's own admin as an explicit interim.

---

### `VendorOnboardingService.java` (service, CRUD + state-machine) — MODIFY

**Analog:** self. Four new methods (`withdraw`, `updateCompanyNumber`, `resolveGate`, `listReviewPending`) + `toDto` extension. Every one reuses the canonical helpers already in this file.

**1. Withdraw — reuse the canonical `transition(...)`** (analog `resubmit()` `:132-153`, `goLive()` `:165-172`):
```java
// goLive() is the shape to clone: require → transition → toDto
public OnboardingDto goLive() {
    UUID tenantId = CurrentTenant.require();
    VendorOnboarding onboarding = requireOnboarding(tenantId);
    transition(onboarding, OnboardingEvent.GO_LIVE);
    return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
}
```
→ `withdraw()` = identical, `OnboardingEvent.WITHDRAW`. The SM already wires WITHDRAW from all 5 pre-live states (`VendorOnboardingStateMachineConfig.java:151-180`); a terminal-state source throws `InvalidStateTransitionException` → RFC 7807 400 automatically. WITHDRAW is a no-side-effect status change (falls into the `default` arm at `transition` `:290-292`).

**2. Update company number — state-guard, NO SM event** (reuse `normaliseCompanyNumber` `:344-351`):
```java
// normaliseCompanyNumber (existing): blank/whitespace -> null (= sole trader)
private static String normaliseCompanyNumber(String companyNumber) {
    if (companyNumber == null) return null;
    String normalised = companyNumber.trim().toUpperCase();
    return normalised.isEmpty() ? null : normalised;
}
```
New method guards `status ∈ {DRAFT, ACTION_REQUIRED}` else throws `InvalidStateTransitionException` (→ 400); sets normalised number; `onboardingRepository.save`; returns `toDto`. It writes a data field only — it must NOT fire any `OnboardingEvent` (D-06). Then the vendor uses existing `resubmit()`.

**3. Gate-resolve — write the GATE row, then recompute AFTER COMMIT** (the two load-bearing helpers: `requireOnboardingById` `:363-366`, `kickGateChainAfterCommit` `:311-322`):
```java
// requireOnboardingById: findById under RLS FORCE — foreign tenant is a clean 404, no oracle
private VendorOnboarding requireOnboardingById(UUID onboardingId) {
    return onboardingRepository.findById(onboardingId)
            .orElseThrow(() -> new ResourceNotFoundException("Onboarding not found: " + onboardingId));
}

// kickGateChainAfterCommit: the CR-01 landmine fix — recompute only AFTER commit
private void kickGateChainAfterCommit(UUID onboardingId, UUID tenantId) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { gateChainRunner.runAndRecompute(onboardingId, tenantId); }
        });
    } else {
        gateChainRunner.runAndRecompute(onboardingId, tenantId);
    }
}
```
`resolveGate(id, gateType, decision, reason)`: `CurrentTenant.require()` → `requireOnboardingById` → `gateRepository.findByOnboardingIdAndGateType(...)` → `row.setStatus(switch decision {PASS→PASSED; WAIVE→WAIVED; FAIL→FAILED})` + `setReason` + `setCheckedAt(OffsetDateTime.now())` → `gateRepository.save(row)` (Envers auto-writes `vendor_onboarding_gate_aud`) → `kickGateChainAfterCommit(id, tenantId)` → return `toAdminDto`. **CRITICAL (Pitfall 2/CR-01):** never call `gateChainRunner.runAndRecompute` inline; the async worker on its own connection won't see the uncommitted gate write. Never write `status`/`published` directly (anti-pattern in RESEARCH; only `transition` `:270-289` may).

**4. Review-queue list** (clone `listPendingApproval()` `:190-196`):
```java
@Transactional(readOnly = true)
public List<AdminOnboardingDto> listPendingApproval() {
    CurrentTenant.require();
    return onboardingRepository.findByStatusOrderBySubmittedAtAsc(OnboardingState.PENDING_APPROVAL).stream()
            .map(o -> toAdminDto(o, gateRepository.findByOnboardingId(o.getId())))
            .toList();
}
```
→ `listReviewPending()` selects VERIFYING onboardings whose gates include a MANUAL_REVIEW (needs a new repo finder, e.g. `findByStatusOrderBySubmittedAtAsc(VERIFYING)` then filter in-stream, or a `@Query`). Returns `AdminOnboardingDto`.

**5. `toDto` extension — derive `reviewPending` where gates are loaded** (`toDto` `:391-405`):
```java
private OnboardingDto toDto(VendorOnboarding onboarding, List<VendorOnboardingGate> gates) {
    List<GateDto> gateDtos = gates.stream()
            .map(g -> new GateDto(g.getGateType(), g.getStatus(), g.isMandatory(), g.getReason(), g.getCheckedAt()))
            .toList();
    return new OnboardingDto(onboarding.getId(), onboarding.getStatus(), onboarding.getModel(),
            onboarding.getShopId(), onboarding.getCompanyNumber(), onboarding.getSubmittedAt(),
            onboarding.getApprovedAt(), onboarding.getWentLiveAt(), gateDtos);
}
```
Add the D-03 predicate here and pass `onboarding.getRejectionReason()` + `reviewPending` into the widened record:
```java
boolean reviewPending = onboarding.getStatus() == OnboardingState.VERIFYING
        && gates.stream().anyMatch(g -> g.getStatus() == GateStatus.MANUAL_REVIEW)
        && gates.stream().noneMatch(g -> g.getStatus() == GateStatus.PENDING);
```
DTOs are hand-built records here (NOT MapStruct), so this is the correct place. `rejectionReason` already flows through `toAdminDto` at `:387`.

---

### `dto/OnboardingDto.java` (model record) — MODIFY

**Analog:** `dto/AdminOnboardingDto.java` (already carries `rejectionReason` at `:34`).

**Current vendor record** (`OnboardingDto.java:27-29`):
```java
public record OnboardingDto(UUID id, OnboardingState status, OnboardingModel model, UUID shopId,
                            String companyNumber, OffsetDateTime submittedAt, OffsetDateTime approvedAt,
                            OffsetDateTime wentLiveAt, List<GateDto> gates) {
}
```
Add `String rejectionReason` (ONBD-05) + `boolean reviewPending` (ONBD-03). Update the Javadoc `@param` block. Mirror the exact field addition in `frontend/types/api.ts` (see below) — Pitfall 6 typecheck gate.

---

### `dto/UpdateOnboardingRequest.java` (DTO) — NEW

**Analog:** `dto/CreateOnboardingRequest.java` — copy the company-number validation VERBATIM (guarantees identical accept/reject + sole-trader-blank semantics, Don't-Hand-Roll).

**Validation to copy** (`CreateOnboardingRequest.java:32-35`):
```java
@Size(max = 32, message = "companyNumber must be at most 32 characters")
@Pattern(regexp = "^\\s*([A-Za-z0-9]{2,10})?\\s*$",
        message = "companyNumber must be a valid Companies House number (2-10 letters or digits)")
private String companyNumber;
```
New DTO carries ONLY `companyNumber` (+ getter/setter). No `tenantId` (resolved server-side). Bean-validation failure → `GlobalExceptionHandler.handleValidationErrors` → 400.

---

### `dto/ResolveGateRequest.java` (DTO) — NEW

**Analog:** `dto/RejectOnboardingRequest.java` — the `@NotBlank` + `@Size` reason field is the reason-validation template.

**Reason field to mirror** (`RejectOnboardingRequest.java:15-20`):
```java
@NotBlank(message = "reason is required")
@Size(max = 500, message = "reason must be at most 500 characters")
private String reason;

public String getReason() { return reason; }
public void setReason(String reason) { this.reason = reason; }
```
Add a `GateDecision` enum field (`PASS | WAIVE | FAIL`) — `@NotNull` (bounded enum is inherently input-validated, ASVS V5). RESEARCH A5: make `reason` required for FAIL, optional for PASS/WAIVE (either keep `@NotBlank` and require it always, or drop to `@Size`-only and enforce the FAIL-needs-reason rule in the service — planner's call). Define `GateDecision` either as a nested enum or a sibling in the `onboarding` package next to `GateStatus`/`GateType`.

---

### `OnboardingEventPublisher.java` (service producer, event-driven) — NEW

**Analog:** `order/OrderEventPublisher.java` — copy the shape exactly: `@Component`, NOT `@Transactional` (joins the caller's tx), serialize to JSON, persist a `PaymentEventOutbox` row via the **5-arg constructor** with the new exchange constant.

**Producer shape to mirror** (`OrderEventPublisher.java:71-110`):
```java
public void publishStateChange(UUID orderId, UUID tenantId, String orderNumber,
                               OrderStatus previousStatus, OrderStatus newStatus) {
    OrderStateChangeEvent event = new OrderStateChangeEvent(orderId, tenantId, orderNumber,
            previousStatus, newStatus, OffsetDateTime.now());
    String routingKey = ORDER_STATE_ROUTING_PREFIX + newStatus.name().toLowerCase();
    String payloadJson;
    try {
        payloadJson = objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException e) {
        // fixed-shape record -> persist a poisoned FAILED placeholder, DO NOT propagate
        ...
        return;
    }
    PaymentEventOutbox row = new PaymentEventOutbox(
            tenantId, EVENT_TYPE, routingKey, payloadJson,
            RabbitMQConfig.ORDER_EVENTS_EXCHANGE);   // 5-arg ctor -> PaymentEventOutbox.java:109-112
    outboxRepository.save(row);
}
```
Onboarding version: `EVENT_TYPE = "ONBOARDING_STALLED"` (RESEARCH A2), routingKey e.g. `onboarding.state.manual_review`, exchange `RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE`, payload = `OnboardingStateChangeEvent`. Injects `PaymentEventOutboxRepository` + `ObjectMapper` (same ctor as the analog `:55-59`). **Called from `GateChainRunner`'s stays-VERIFYING branch** (see below), where the tenant is re-established and the async tx is open — the outbox INSERT joins that tx.

---

### `OnboardingStateChangeEvent.java` (model record, event-driven) — NEW

**Analog:** `order/OrderStateChangeEvent.java` (referenced by the flusher `:260`). A fixed-shape serializable record.

**RESEARCH-recommended shape (A2):**
```java
public record OnboardingStateChangeEvent(UUID onboardingId, UUID tenantId, UUID shopId,
                                         OnboardingState status, String reason, OffsetDateTime occurredAt) {}
```
Internal event, no external consumer yet (Phase 24 delivers) → low blast radius if the field set changes. `reason` must be human-readable (ASVS V7 — never leak raw provider text; FhrsGate already returns fixed reasons at `:99-104`).

---

### `config/RabbitMQConfig.java` (config, event-driven) — MODIFY

**Analog:** self — the `order.events` exchange declaration is the exact template.

**Exchange constant + bean to mirror** (`RabbitMQConfig.java:19` + `:34-37`):
```java
public static final String ORDER_EVENTS_EXCHANGE = "order.events";

@Bean
public TopicExchange orderEventsExchange() {
    return new TopicExchange(ORDER_EVENTS_EXCHANGE);
}
```
Add `ONBOARDING_EVENTS_EXCHANGE = "onboarding.events"` + an `onboardingEventsExchange()` `TopicExchange` bean. **No bound Queue/Binding this phase** (RESEARCH Pitfall 1 fix step 1) — a topic exchange with no binding discards cleanly; Phase 24 adds the queue + subscription. RabbitMQ topology is code-declared (auto-declares on boot), so this is the only broker change needed.

---

### `payment/PaymentEventOutboxFlusher.java` (service dispatch, event-driven) — MODIFY — **HIGH-RISK, see Pitfall 1**

**Analog:** self — the `publishRow` per-exchange dispatch must gain an `onboarding.events` arm BEFORE any onboarding row is written, or the row is deserialized as `PaymentEvent`, poisoned, and dead-lettered.

**Dispatch to extend** (`PaymentEventOutboxFlusher.java:256-266`):
```java
Object event;
if (RabbitMQConfig.ORDER_EVENTS_EXCHANGE.equals(exchange)) {
    if (row.getRoutingKey() != null
            && row.getRoutingKey().startsWith(OrderEventPublisher.ORDER_STATE_ROUTING_PREFIX)) {
        event = objectMapper.readValue(row.getPayload(), OrderStateChangeEvent.class);
    } else {
        event = objectMapper.readValue(row.getPayload(), RefundEvent.class);
    }
} else {
    event = objectMapper.readValue(row.getPayload(), PaymentEvent.class);   // onboarding payload lands HERE if not extended
}
```
Add `else if (RabbitMQConfig.ONBOARDING_EVENTS_EXCHANGE.equals(exchange)) { event = objectMapper.readValue(row.getPayload(), OnboardingStateChangeEvent.class); }` before the final `else`. Poison/backoff/dead-letter handling at `:275-299` is untouched — it already covers the new type.

---

### `GateChainRunner.java` (service, event-driven) — MODIFY

**Analog:** self — inject `OnboardingEventPublisher` and emit from the existing "stays VERIFYING because ≥1 mandatory gate is MANUAL_REVIEW" branch.

**Emission site — the recompute else branch** (`GateChainRunner.java:196-199`, inside the `@Async @Transactional runAndRecompute`, tenant already re-established at `:117`):
```java
} else if (anyFailed) {
    vendorOnboardingService.transition(onboardingId, OnboardingEvent.GATE_FAILED);
}
// else: still-PENDING gates await webhooks/resubmit -> leave in VERIFYING
//        └─► NEW: if there is ≥1 MANUAL_REVIEW mandatory row, publishStall(...)
```
The stall event fires in the terminal `else` (all-mandatory neither passed-nor-failed → at least one MANUAL_REVIEW). Because this runs on the async worker with the tenant GUC set (Pitfall 3 already handled by the existing `TenantContext.set(tenantId)`/`finally clear`), the outbox INSERT is tenant-stamped and RLS-safe. **Pitfall 4 (dup on resubmit):** RESEARCH A3 recommends at-least-once + idempotent consumer (matches existing outbox semantics) — do not add an emitted-guard unless the planner decides otherwise.

---

### `OnboardingProperties.java` (config) — MODIFY *only if backend-config channel is chosen*

**Analog:** self — `@ConfigurationProperties(prefix = "onboarding")` with nested static classes (`Fhrs`/`CompaniesHouse`) and `${ENV:default}` binding in `application.yml:268-283`.

RESEARCH A1 / Open Q1 **recommends the frontend `NEXT_PUBLIC_*` channel** for the SLA copy + support link (build-time, matches existing idiom), which means NO change here. If the team wants runtime override without a frontend rebuild, add e.g. `review.sla-business-days` + `support.channel` keys here and expose on the DTO. **Decision flagged — do not build both.**

---

### `frontend/app/dashboard/onboarding/page.tsx` (component/page) — MODIFY

**Analog:** self (static-map idiom, poll effect, action handlers) + `approvals/page.tsx` (dialog + reject-reason). This is the ONBD-03/04/05 surface. **Incremental Betterment (CLAUDE.md):** preserve the create form, submit/resubmit/go-live CTAs, gate breakdown, and milestone timeline — this is an additive rework.

**a. Remediation static map (ONBD-04)** — clone the `STATE_META`/`GATE_META` idiom (`page.tsx:54-94`):
```tsx
const STATE_SUBTITLE: Record<OnboardingState, string> = {
  VERIFYING: "Running your compliance checks. This usually takes under a minute — ...",   // :68-69 (REPLACE for reviewPending)
  SUSPENDED: "Your storefront has been suspended. Contact support for details.",          // :74 (REPLACE with config channel)
  REJECTED: "Your application was not approved. Contact support for details.",            // :75 (REPLACE: render rejectionReason + config channel)
  ...
}
```
Add a `REMEDIATION: Partial<Record<`${GateType}:${GateStatus}`, {why; what; href}>>` map. Deep links (D-08): `BUSINESS_VERIFIED:FAILED` → inline company-number edit (`#company-number` anchor, the new update endpoint); `ALLERGEN_DATA_COMPLETE:FAILED` → `/dashboard/products`; `FOOD_HYGIENE_RATING:MANUAL_REVIEW` → `/dashboard/shops`. The existing failed-gate list (`:449-467`) already reads `gate.reason` (which names SKUs / states the FHRS miss) — extend it, don't replace it.

**b. Polling back-off (ONBD-03, Pitfall 5)** — the current poll (`page.tsx:105-107,183-189`):
```tsx
const POLL_STATES: OnboardingState[] = ["VERIFYING", "PENDING_APPROVAL"]
...
useEffect(() => {
  if (!pollStatus || !POLL_STATES.includes(pollStatus)) return
  const interval = setInterval(() => { void loadOnboarding(false) }, 4000)
  return () => clearInterval(interval)
}, [pollStatus, loadOnboarding])
```
Once `onboarding.reviewPending` is true, drop VERIFYING from the fast interval (or lengthen it) and swap the copy to the config-driven "a reviewer checks these within N business days". Never rely on `networkidle` in Playwright (learning: an open interval prevents settle).

**c. Withdraw confirm dialog (ONBD-01)** — clone the go-live `<Dialog>` (`page.tsx:516-533`) and the `handleGoLive` action (`:262-288`):
```tsx
<Dialog open={goLiveOpen} onOpenChange={setGoLiveOpen}>
  <DialogContent>
    <DialogHeader><DialogTitle>Go live?</DialogTitle>
      <DialogDescription>This publishes your storefront ...</DialogDescription></DialogHeader>
    <DialogFooter>
      <Button variant="outline" onClick={() => setGoLiveOpen(false)}>Not yet</Button>
      <Button onClick={handleGoLive} disabled={goingLive}>{goingLive ? "Going live…" : "Go live"}</Button>
    </DialogFooter>
  </DialogContent>
</Dialog>
```
Withdraw variant = a `variant="destructive"` confirm; `apiClient.post("/api/v1/onboarding/withdraw", {})` → `setOnboarding(res.data)`; `useToast` on error. Show the trigger for any pre-live status.

**d. Inline company-number edit (ONBD-02)** — reuse the existing create-form `<Input>`/`<Label>` (`page.tsx:377-391`) as the edit control; on save `apiClient.post`/`patch` the update endpoint, then the vendor triggers resubmit via the existing `handleResubmit` (`:245-260`).

---

### `frontend/app/dashboard/onboarding/approvals/page.tsx` (component/page) — MODIFY

**Analog:** self — the queue-card + reject-reason dialog is the exact template for the review-pending list + gate-resolve control.

**Reject dialog with required reason** (`approvals/page.tsx:324-376`) is the shape for a gate-resolve dialog (decision select PASS/WAIVE/FAIL + reason textarea). **Load + action idiom** (`:98-116`, `:157-181`):
```tsx
const loadQueue = useCallback(async () => {
  try {
    const res = await apiClient.get("/api/v1/onboarding/admin/pending")   // add a review-queue fetch (GET /reviews)
    setApplications(res.data ?? [])
  } catch (err) { if (httpStatus(err) === 403) setForbidden(true) ... }
}, [toast])

const handleReject = async () => {
  await apiClient.post(`/api/v1/onboarding/admin/${target.id}/reject`, { reason: rejectReason.trim() })
  removeFromQueue(target.id); toast({ title: "Application rejected", ... })
}
```
gate-resolve handler → `apiClient.post(`/api/v1/onboarding/admin/${id}/gates/${gateType}/resolve`, { decision, reason })`. Reuse `gateSummary`, `GATE_META`, `GATE_STATUS_META`, the `forbidden` 403 pattern, and the destructive/confirm dialog styling. Keep the existing approve/reject queue intact (Incremental Betterment) — the review list is an addition.

---

### `frontend/types/api.ts` (model/types) — MODIFY

**Analog:** self — the `OnboardingDto`/`AdminOnboardingDto`/`RejectOnboardingRequest` interfaces (`:361-397`) are the shape templates.

**Interface to widen** (`types/api.ts:361-371`):
```ts
export interface OnboardingDto {
  id: string
  status: OnboardingState
  ...
  wentLiveAt: string | null
  gates: GateDto[]
}
```
Add `rejectionReason: string | null` + `reviewPending: boolean`. Add `UpdateOnboardingRequest { companyNumber?: string }` and `ResolveGateRequest { decision: "PASS" | "WAIVE" | "FAIL"; reason?: string }` mirroring `RejectOnboardingRequest` (`:395-397`). Keep in exact sync with the Java records or `next build` (tsc) breaks — Pitfall 6.

---

### `frontend/lib/env-validation.ts` + `frontend/.env.local.example` (config) — MODIFY *(if frontend-config channel chosen — A1 default)*

**Analog:** self — `requiredEnvVars` list (`env-validation.ts:23-31`):
```ts
const requiredEnvVars: (keyof EnvVars)[] = [
  'NEXT_PUBLIC_API_URL', 'KEYCLOAK_CLIENT_ID', ... 'NEXTAUTH_SECRET',
];
```
Register `NEXT_PUBLIC_SUPPORT_EMAIL` / `NEXT_PUBLIC_SUPPORT_URL` / `NEXT_PUBLIC_ONBOARDING_REVIEW_SLA_DAYS` in the `EnvVars` interface (`:8-21`) + list, and add them to `.env.local.example`. These are non-secret build-time values (GLOBAL_RULE_6 — no hardcoded support mailto / SLA literals). **Watch the stale `.env.local` secret trap** (MEMORY `project_motion_uplift_research`) — rebuild the frontend container after changing env.

> `frontend/lib/api-client.ts` is the axios instance (baseURL `NEXT_PUBLIC_API_URL`, 401-refresh + 5xx retry) — **used, not modified**. Every new call goes through the existing `apiClient.get/post` as the pages already do.

---

## Test Pattern Assignments

### Backend integration (Testcontainers + real Keycloak converter + RLS NOSUPERUSER)

**Analog:** `OnboardingAdminQueueIntegrationTest.java` — the canonical template for every new controller test (withdraw, update, gate-resolve, review-queue, outbox emission).

**Harness header + JWT helpers to clone** (`:66-121`):
```java
@SpringBootTest @AutoConfigureMockMvc @Testcontainers @ActiveProfiles("test") @Tag("testcontainers")
class OnboardingAdminQueueIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")...;
    @DynamicPropertySource static void configureProperties(DynamicPropertyRegistry r) {
        IntegrationTestSupport.registerPostgresTestProperties(r, postgres); }
    private static final String RLS_TEST_ROLE = "rls_admin_queue_role";
    private static RequestPostProcessor adminJwt(UUID tenant) {
        return jwt().jwt(j -> j.claim("tenant_id", tenant.toString())
                .claim("realm_access", Map.of("roles", List.of("admin"))))
                .authorities(new KeycloakRealmRoleConverter()); }
    private static RequestPostProcessor userJwt(UUID tenant) { /* roles: ["user"] */ }
    @BeforeEach void seedTenantAndShop() { /* INSERT tenants + shops via jdbc */ }
}
```
Reuse: `nonAdminGets403` for gate-resolve/review-queue authZ; the guard-veto "400 + state unchanged" pattern; the Envers `SELECT count(*) FROM vendor_onboarding_gate_aud` assertion for the gate-resolve audit; the NOSUPERUSER RLS block (tenant B invisible to tenant A) for cross-tenant gate-resolve → 404. For the **update** test also lean on the existing `OnboardingCompanyNumberValidationIntegrationTest` (garbage `@Pattern` → 400) and `OnboardingResubmitIntegrationTest` (fix-then-resubmit). For the **outbox** test assert `SELECT ... FROM payment_event_outbox WHERE exchange='onboarding.events'` tenant-stamped.

### Backend unit — GateChainRunner stall emission

**Analog:** `GateChainRunnerTest.java` — pure-Mockito, constructs the runner directly so `runAndRecompute` runs inline; `@AfterEach TenantContext.clear()`. Add the publisher as a constructor mock and `verify(publisher).publishStall(...)` in the MANUAL_REVIEW-park case; `verify(publisher, never())...` in the GATES_PASSED / GATE_FAILED cases.

### Backend unit — WITHDRAW SM proof: **already present**

`VendorOnboardingStateMachineServiceTest.withdrawFromEachSource` (`:133-146`) and the terminal-source rejection in `illegalTransitionsThrow` (`:163-165`) already prove ONBD-01's state layer — do NOT duplicate; the new work is the endpoint/service test.

### Frontend Jest

**Analog:** `onboarding/__tests__/page.test.tsx` — the `jest.mock("@/lib/api-client")` + `routeGet(url→impl)` router + `onboarding(status, overrides)` fixture (`:1-66`) is the exact idiom. Add blocks: withdraw dialog + terminal copy; inline company-number edit calls the update endpoint; in-review copy + poll back-off; per-`(gateType,status)` remediation block renders why/what/deep-link; REJECTED/SUSPENDED render reason + config support channel. Mirror for the approvals page in its `__tests__/page.test.tsx`. **Run `npm run build` (tsc) — Jest does not typecheck (Pitfall 6).**

### E2E Playwright

**Analog:** `frontend/e2e/vendor-refund-flow.spec.ts` — port-3100 base URL, `E2E_VENDOR_*` env creds, `vendorLogin` helper, and the **NOT-networkidle** rule (`:41-45`: `waitForLoadState("domcontentloaded")` + assert on concrete controls). New `onboarding-blocked-flow.spec.ts` drives the journey-matrix requirement: bad company number → fix inline → resubmit → live. Rebuild ALL containers before running (CLAUDE.md).

---

## Shared Patterns

### Canonical state transition (sole writer of `Shop.published`)
**Source:** `VendorOnboardingService.transition(...)` (`:250-297`), `kickGateChainAfterCommit` (`:311-322`)
**Apply to:** withdraw, gate-resolve (indirectly via recompute). NEVER write `status`/`published` directly; recompute only AFTER commit.

### RFC 7807 error mapping (no custom error JSON)
**Source:** `common/GlobalExceptionHandler.java` — `InvalidStateTransitionException`→400 (`:52-58`), `MethodArgumentNotValidException`→400 with field map (`:94-108`), `ResourceNotFoundException`→404 (`:44-50`)
**Apply to:** update state-guard (throw `InvalidStateTransitionException`), all `@Valid @RequestBody` DTOs, all `requireOnboardingById` 404s.

### Tenant resolution + RLS (V4/V5 security)
**Source:** `CurrentTenant.require()` (server-side, never from body); `requireOnboardingById` RLS 404 (`:363-366`); `@Async` tenant re-establishment in `GateChainRunner` (`:113-117`, finally clear `:207-208`)
**Apply to:** every new service method; the gate-resolve endpoint is tenant-scoped admin (interim resolver), NOT cross-tenant.

### Transactional outbox (never `rabbitTemplate.convertAndSend` directly)
**Source:** `OrderEventPublisher` (`:71-110`) + `PaymentEventOutbox` 5-arg ctor (`:109-112`) + flusher dispatch (`:256-266`)
**Apply to:** the stall event — new exchange bean + flusher branch MUST land together (Pitfall 1).

### Frontend static-map + defensive-fallback + dialog idiom
**Source:** `onboarding/page.tsx:54-103` (maps + `GATE_FALLBACK`), `:516-533` (dialog), `approvals/page.tsx:324-376` (reason dialog)
**Apply to:** remediation blocks, withdraw dialog, gate-resolve dialog. Unknown enum values must render neutral, never crash.

### Config injection (GLOBAL_RULE_6)
**Source:** `env-validation.ts:23-31` (frontend, A1 default) OR `OnboardingProperties` + `application.yml:268-283` (backend, if runtime override needed)
**Apply to:** support channel + SLA copy. Pick ONE channel.

---

## No Analog Found

None. Every target file maps to an existing in-repo analog (frequently the same file). This is the expected outcome for a brownfield, zero-migration, ~80%-assembly phase — the failure mode is re-implementing an invariant instead of routing through the canonical path, not a missing pattern.

## Open Decisions Surfaced to Planner (from RESEARCH, not resolvable at map time)

| # | Decision | Default recommendation |
|---|----------|------------------------|
| A1 | SLA/support config channel: frontend `NEXT_PUBLIC_*` vs backend `OnboardingProperties` | Frontend env (build-time, existing idiom) — do NOT build both |
| A4/Q2 | Review-queue endpoint: new `GET /onboarding/admin/reviews` vs extend `/pending` | New `/reviews` (keeps approve/reject contract clean) |
| A5 | `ResolveGateRequest.reason` required always vs only for FAIL | FAIL requires reason; PASS/WAIVE optional |
| A3/Pitfall4 | Stall-event de-dup on resubmit | At-least-once + idempotent consumer (matches outbox contract) |
| — | Update endpoint verb: `PATCH /onboarding` vs `POST /onboarding/company-number` | Stay consistent with the all-POST vendor surface (no `@PatchMapping` precedent in the class) |

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{onboarding,order,payment,config,common,exception}`, `core-java/src/test/java/uk/jtoye/core/onboarding`, `frontend/app/dashboard/onboarding/**`, `frontend/{types,lib,e2e}`, `core-java/src/main/resources/application.yml`.
**Files scanned:** 24 analog files opened + verified (line numbers live 2026-07-14).
**Pattern extraction date:** 2026-07-14
