---
phase: 22-notifications-comms
plan: 06
subsystem: frontend
tags: [webhooks, dashboard, nextjs, react19, tailwind, mobile-first, a11y, tdd, comms-06, idempotency]

# Dependency graph
requires:
  - phase: 22-notifications-comms (22-03)
    provides: webhook subscription CRUD + rotate/pause/resume/revoke at /api/v1/webhooks; plaintext signing_secret returned once (WithSecret)
  - phase: 22-notifications-comms (22-05)
    provides: delivery-log list (GET .../deliveries?status=&eventType=&page=) + tagged Idempotency-Key-safe replay (POST .../{deliveryId}/replay)
  - phase: prior milestones
    provides: apiClient (Bearer + X-Tenant-Id), RefundDialog (Dialog+Zod+makeIdempotencyKey), orders/page.tsx (statusConfig/filter/table→cards), dashboard shell + sidebar navigation array
provides:
  - lib/webhooks-api.ts (typed webhook client + secure makeIdempotencyKey + event-type family metadata + RFC 7807 detail extractor)
  - /dashboard/webhooks subscriptions list (Surface A) — create/pause/resume/revoke + once-only secret reveal + rotate
  - /dashboard/webhooks/[id] endpoint detail + delivery-log browser (Surface B) — filter + auto-pause alert + Idempotency-Key-safe replay
  - status-badge taxonomy (subscription + delivery states: tinted bg + lucide icon + text label)
  - 3 reusable dialogs (WebhookCreateDialog / SecretRevealDialog / ConfirmActionDialog)
  - Webhooks sidebar nav entry
affects: [22-07 (Playwright E2E + throttled-mobile CWV smoke on these routes)]

# Tech tracking
tech-stack:
  added: []  # zero new deps — reused vendored shadcn primitives + lucide + react-hook-form + zod
  patterns:
    - "Responsive dual-render: Table at sm+ (scrolls inside its own overflow-x container) + card-stacking below sm (375px no-body-overflow contract)"
    - "Once-only secret dialog: no backdrop/Esc/X dismiss (guarded onOpenChange + [&>button]:hidden), only the explicit confirm button"
    - "Every status badge = tinted bg + lucide icon + text label (never colour alone) via a statusConfig map (orders/page.tsx shape)"
    - "Replay carries a fresh secure Idempotency-Key (makeIdempotencyKey reused from RefundDialog)"
    - "Config-injected retention-days copy via NEXT_PUBLIC_WEBHOOK_RETENTION_DAYS (GLOBAL_RULE_6)"

key-files:
  created:
    - frontend/lib/webhooks-api.ts
    - frontend/components/dashboard/webhooks/status-badge.tsx
    - frontend/components/dashboard/webhooks/WebhookCreateDialog.tsx
    - frontend/components/dashboard/webhooks/SecretRevealDialog.tsx
    - frontend/components/dashboard/webhooks/ConfirmActionDialog.tsx
    - frontend/app/dashboard/webhooks/page.tsx
    - frontend/app/dashboard/webhooks/[id]/page.tsx
    - frontend/app/dashboard/webhooks/__tests__/webhooks-page.test.tsx
    - frontend/app/dashboard/webhooks/__tests__/delivery-log.test.tsx
  modified:
    - frontend/components/dashboard/sidebar.tsx

key-decisions:
  - "Webhook API methods live in lib/webhooks-api.ts wrapping the default apiClient (per the task action) — api-client.ts itself was NOT modified"
  - "Grouped event-type checkboxes = one checkbox per backend WebhookEventType family (Orders/Refunds/Onboarding/Payments); the enum exposes exactly one type per family, so there are no sub-events to expand"
  - "List 'Updated' column uses subscription.updatedAt — the subscription DTO carries no last-delivery timestamp; the actual delivery log lives on the detail page"
  - "Secret dialog blocks ALL implicit dismissals (backdrop + Esc + X) so the once-only secret can't be lost by accident"

requirements-completed: [COMMS-06]

# Metrics
duration: ~12min
completed: 2026-07-15
---

# Phase 22 Plan 06: Vendor Webhook Management + Delivery-Log UI Summary

**The self-serve dashboard for the machine channel (COMMS-06): a mobile-first `/dashboard/webhooks` subscriptions list (create / pause / resume / revoke + once-only focus-trapped secret reveal + rotate) and a `/dashboard/webhooks/[id]` endpoint detail + filterable delivery-log browser with an AUTO-PAUSED amber alert and an Idempotency-Key-safe manual replay — cards below sm, Table at sm+, every status an icon+label badge, zero new deps.**

## Performance

- **Duration:** ~12 min
- **Started:** 2026-07-15T04:21:40Z
- **Completed:** 2026-07-15T04:34:01Z
- **Tasks:** 3
- **Files:** 10 (9 created, 1 modified)

## What Was Built

**Task 1 — webhooks-api client + status-badge taxonomy + sidebar nav (`8f5d0b7`, feat) + list page (`1b1e55b`, feat)**
- `lib/webhooks-api.ts`: typed `list/get/create/rotateSecret/pause/resume/revoke/listDeliveries/replay` wrapping the authed `apiClient`; the `WebhookSubscriptionWithSecret` once-only type; `EVENT_TYPE_META`/`EVENT_TYPE_ORDER` family metadata; a secure `makeIdempotencyKey` (verbatim from RefundDialog) and an RFC 7807 `extractErrorDetail`.
- `status-badge.tsx`: `subscriptionStatusConfig` (Active/emerald·CheckCircle2, Paused/slate·Pause, Auto-paused/amber·AlertTriangle, Revoked/red·Ban) + `deliveryStatusConfig` (Pending/slate·Clock, Delivered/emerald·CheckCircle2, Retrying/amber·RefreshCcw, Failed/red·XCircle) + a `ReplayTag` outline — every entry has an `icon:` and `label:` (never colour alone).
- `sidebar.tsx`: additive `{ Webhooks → /dashboard/webhooks }` entry (lucide `Webhook`), falls into the mobile "More" sheet.
- `/dashboard/webhooks` (Surface A): header `flex-col sm:flex-row` + `text-2xl sm:text-3xl` H1 + full-width orange **Add endpoint** CTA on mobile; Table at sm+ (URL `font-mono` truncate, events, status badge, updated, actions) inside its own `overflow-x-auto`; card-stacking below sm (`break-all` URL, `min-h-11` buttons); empty state + CTA; orange spinner; destructive load-error toast; wires create→secret-reveal, pause/resume, rotate→reveal, revoke; revoked rows drop actions; View links keep the `[id]` route non-orphan.

**Task 2 — create / secret-reveal / confirm dialogs (`0541a71`, feat)**
- `WebhookCreateDialog`: react-hook-form + Zod `z.string().url().startsWith("https://", …)` (non-HTTPS field error verbatim) + grouped event-type checkboxes (`.min(1)`, `min-h-11` rows); on success hands the create response up so the caller reveals the secret.
- `SecretRevealDialog`: shown ONCE, focus-trapped, **cannot** be dismissed by backdrop/Esc/X (guarded `onOpenChange` + `[&>button]:hidden` + `onInteractOutside`/`onEscapeKeyDown` preventDefault) — only "I've saved it"; readOnly `font-mono` Input `aria-label="Signing secret"`; `role="alert"` warning; Copy button + aria-live toast.
- `ConfirmActionDialog`: reusable focus-trapped confirm for Rotate (orange), Revoke (destructive red), Replay; async pending state; `aria-describedby` via `DialogDescription`.

**Task 3 — endpoint detail + delivery-log (Surface B) + Jest tests (RED `08bf76c` → GREEN `2cced30`)**
- `/dashboard/webhooks/[id]`: back link; summary card (URL, status badge, event-type chips, created date, Rotate/Pause·Resume/Revoke); AUTO_PAUSED amber alert (`role="status"`) with consecutive-failure count + last-error line (`Last error: … · HTTP … · relative time`) + **Resume delivery**; delivery-log with Event type + Status Selects (stack `flex-col` on mobile / `sm:w-[180px]` inline) driving `GET .../deliveries?status=&eventType=&page=`; Table at sm+ (event type, status badge + `font-mono` HTTP code, attempts, Replay tag, when, Replay) + card-stacking below sm; `<Pagination>`; empty + filtered-empty (Clear filters) states; Replay → confirm → `POST .../replay` with a fresh `Idempotency-Key` → "Replay queued…" toast.
- Jest (7/7 green): list renders URLs + icon/label badges + Add endpoint CTA + responsive card/table containers; delivery-log renders a row, `status=FAILED` narrows the rows (server-side re-fetch), 375px card/table split with no element wider than the viewport, and Replay issues a POST whose config headers include `Idempotency-Key`.

## Verification

- `cd frontend && npm test -- webhooks` — **7/7 green** (render, status-filter narrowing, 375px no-overflow, replay Idempotency-Key).
- `cd frontend && npm run build` — **type-clean** (tsc + Next compile: `✓ Compiled successfully`), both `/dashboard/webhooks` and `/dashboard/webhooks/[id]` route chunks registered; **no new dependency** added to `package.json` (bundle-growth guardrail).
- Full frontend suite — **37 suites / 262 tests / 2 snapshots, 0 failures** (link-graph orphan guard confirms both new routes are non-orphan; palette-discipline + dashboard-shell nav unaffected).

## Threat Model Coverage

| Threat ID | Mitigation | Where |
|-----------|-----------|-------|
| T-22-06-01 Secret disclosure/re-fetch | secret only in `SecretRevealDialog` from the create/rotate response, cleared from state on close; GET DTO carries no secret | list + detail pages, SecretRevealDialog |
| T-22-06-02 XSS via rendered URL/error | React auto-escaping; identifiers rendered as text in `font-mono`; no `dangerouslySetInnerHTML` | all surfaces |
| T-22-06-03 Duplicate delivery on replay | fresh secure `makeIdempotencyKey()` header on every replay POST; backend replay is a tagged new attempt reusing the original envelope id | webhooks-api.replay, detail page |
| T-22-06-04 Horizontal overflow / bundle bloat at 375px | card-stacking below sm + Jest 375px no-overflow assertion; paginated log (no unbounded DOM); zero new deps | detail + list pages, delivery-log.test |
| T-22-06-SC new UI primitive | none added — reused vendored shadcn primitives + native checkboxes only | — |

## Deviations from Plan

### Auto-fixed / interpretation decisions

**1. [Rule 3 - Sequencing] page.tsx committed after the dialogs it imports**
- **Found during:** Task 1.
- **Issue:** the plan bundles `page.tsx` into Task 1, but the list page imports the Task-2 dialogs, so a Task-1 `page.tsx` commit would not type-compile.
- **Fix:** split the work into build-clean commits — api+badge+nav (`8f5d0b7`), dialogs (`0541a71`), then the list page (`1b1e55b`). Each commit passes `npm run build`; no scope change.

**2. [Rule 3 - Interpretation] api-client.ts left untouched; methods live in webhooks-api.ts**
- **Issue:** the plan's `files_modified` lists `lib/api-client.ts`, but the Task-1 action says the methods "wrap the default apiClient".
- **Fix:** the typed methods live in `lib/webhooks-api.ts` importing the default `apiClient`; `api-client.ts` (the hardened axios instance) is unchanged — the correct, contained placement.

**3. [Rule 3 - Interpretation] event-type "families" = one checkbox per backend enum value**
- **Issue:** UI-SPEC describes grouped checkboxes "expandable to individual event types", but the backend `WebhookEventType` enum exposes exactly one type per family (ORDER_STATE_CHANGED / ORDER_REFUNDED / ONBOARDING_STATE_CHANGED / PAYMENT_EVENT).
- **Fix:** rendered one labelled checkbox per family (≥1 required) — there are no sub-events to expand. Matches the live contract.

**4. [Rule 2 - Data gap] list "Updated" column instead of "Last delivery"**
- **Issue:** UI-SPEC Surface A lists a "Last delivery" column, but `WebhookSubscriptionDto` carries no last-delivery timestamp (only created/updated).
- **Fix:** the list shows `updatedAt` under an honest "Updated" header rather than fabricating a last-delivery time; the real per-delivery timeline is the detail page's delivery log.

**5. [Rule 1 - Test infra] stabilised the useToast test mock**
- **Issue:** the detail page's `useCallback([id, toast])` spun into an infinite re-fetch under a mock that returned a fresh `toast` each render (the RED suite hung on the spinner). The real `useToast` returns the module-level `toast` (stable) — so production is unaffected.
- **Fix:** the test mock now returns a stable module-level `toast`, mirroring the real hook.

**Total deviations:** 5 (all sequencing/interpretation/test-infra within the plan's declared frontend files; no behavioural scope creep — all must-have truths delivered).

## Known Stubs

None. No placeholder/empty-data stubs — the list and log render live API data with real empty/error/filtered-empty states.

## Deferred (phase-gate reconcile — see deferred-items.md)

- **`docs/metrics.json` counts:** 22-06 adds 2 frontend test files (7 `it` blocks). Per the phase's established pattern (22-03/22-05), the `docs-freshness` aggregate is reconciled ONCE at the phase gate, not per-plan.
- **Throttled-mobile CWV smoke (LCP/CLS/INP)** on `/dashboard/webhooks` + `/dashboard/webhooks/[id]`: measured in 22-07's Playwright E2E pass (this plan asserts only the build-time bundle-growth guardrail — no new heavy dep, paginated log).

## User Setup Required

None for dev. Optional: `NEXT_PUBLIC_WEBHOOK_RETENTION_DAYS` (default `30`) can be set to mirror the backend `webhook.delivery.retention-days` in the revoke copy.

## Next Phase Readiness

- **22-07 (E2E):** the two new routes are live and non-orphan; Playwright can drive create→secret-reveal→list→pause/resume/revoke/rotate and the delivery-log filter/replay, plus the throttled-mobile CWV smoke at 375px.

## Self-Check: PASSED

- All 9 created files + 1 modified file verified present on disk.
- All 5 task commits present in git history: `8f5d0b7`, `0541a71`, `1b1e55b`, `08bf76c`, `2cced30`.
- `npm test -- webhooks` 7/7 green; `npm run build` type-clean; full suite 262/262 green.

## TDD Gate Compliance

Task 3 followed RED → GREEN: `test(22-06)` (`08bf76c`, delivery-log suite failing — `[id]/page.tsx` absent) precedes `feat(22-06)` (`2cced30`, detail page implemented, 7/7 green). Tasks 1–2 (api client, static badge taxonomy, dialog scaffolding) are `type="auto"` non-TDD per the plan; the list-page render test is exempt static-markup coverage.

---
*Phase: 22-notifications-comms*
*Completed: 2026-07-15*
