---
phase: 07-kitchen-display-ui
verified: 2026-04-07T17:30:00Z
status: gaps_found
score: 6/7 must-haves verified
re_verification: false
gaps:
  - truth: "Order cards show item names — product names visible on cards"
    status: partial
    reason: "OrderItem type has no productName field; cards display truncated productId (first 8 chars of UUID) instead of human-readable product names"
    artifacts:
      - path: "frontend/app/dashboard/kitchen/page.tsx"
        issue: "Lines 410 and 451: item.productId.substring(0, 8) used as display name — UUIDs are not readable by kitchen staff"
      - path: "frontend/types/api.ts"
        issue: "OrderItem interface (line 121) lacks productName field; only has productId, quantity, unitPricePennies, totalPricePennies, createdAt"
    missing:
      - "Add productName (or name) field to OrderItem interface in frontend/types/api.ts — requires backend OrderItem DTO to include product name"
      - "Update kitchen page card rendering (lines 410, 451) to use item.productName instead of item.productId.substring(0,8)"
      - "Verify backend /api/v1/orders/{id}/detail response includes product name in items array"
human_verification:
  - test: "Confirm orders appear in real time via WebSocket"
    expected: "When an order transitions to CONFIRMED, a new card appears on the kitchen display without page refresh within 1-2 seconds"
    why_human: "Cannot run WebSocket connection or trigger order state changes programmatically in static analysis"
  - test: "Confirm audio beep plays on new order"
    expected: "An audible beep sounds when a new CONFIRMED order card appears (when not muted)"
    why_human: "Web Audio API requires browser context and user gesture; cannot verify sound output statically"
  - test: "Mute toggle persists across page loads"
    expected: "After clicking mute, refreshing the page leaves the display muted (VolumeX icon shown, kds-muted=true in localStorage)"
    why_human: "Browser localStorage state requires live browser session"
  - test: "Status bump CONFIRMED -> PREPARING -> READY -> COMPLETED"
    expected: "Clicking Start Preparing moves card to Preparing state optimistically; Mark Ready moves to Ready; Complete removes card"
    why_human: "Requires live backend API; optimistic UI rollback on error also needs real failure scenario"
---

# Phase 7: Kitchen Display UI Verification Report

**Phase Goal:** Kitchen staff see a live order feed and can manage order progression in real time
**Verified:** 2026-04-07T17:30:00Z
**Status:** gaps_found — 1 gap (item name display shows truncated UUIDs, not product names)
**Re-verification:** No — initial verification

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | Kitchen page at /dashboard/kitchen shows order cards for CONFIRMED, PREPARING, and READY orders | VERIFIED | Page exists at 484 lines; KITCHEN_STATUSES = ["CONFIRMED","PREPARING","READY"] (line 105); filters applied at line 178 |
| 2 | New orders appear in real time via WebSocket without page refresh | VERIFIED | useStomp hook subscribed to /topic/kitchen/{tenantId}/{shopId} (line 219); handleWsMessage adds/updates ordersMap on STOMP message (lines 229-255); fetchOrders passed as onReconnect (line 263) |
| 3 | Kitchen staff can bump CONFIRMED->PREPARING, PREPARING->READY, READY->COMPLETED via card buttons | VERIFIED | bumpActions map (lines 59-62) defines start-preparation, mark-ready, complete endpoints; apiClient.post called (line 298); optimistic UI with revert on error (lines 280-307) |
| 4 | Order cards change border colour based on age: green (<5m), yellow (5-15m), red (>15m) | VERIFIED | ageBorderClass() function (lines 66-71) returns border-green-500, border-yellow-500, border-red-500; applied as className on Card (line 422); setInterval at 30s (line 210) triggers re-render |
| 5 | Audio beep plays when a new order card appears on the display | VERIFIED | playBeep() uses AudioContext + OscillatorNode at 800Hz (lines 85-100); called only when !wasInKitchen && !mutedRef.current (lines 245-247) |
| 6 | Mute toggle persists across page loads via localStorage | VERIFIED | Initial state reads localStorage.getItem("kds-muted") (line 120); toggleMute() writes localStorage.setItem("kds-muted", ...) (line 270) |
| 7 | Order cards show item names for kitchen staff to know what to prepare | FAILED | OrderItem type has no productName field; cards render item.productId.substring(0,8) (lines 410, 451) — truncated UUID is unreadable by kitchen staff |

**Score: 6/7 truths verified**

Note: Truth 7 (item names) is not explicitly listed as a must_have truth in the PLAN frontmatter, but it is a critical functional requirement for the phase goal "kitchen staff can manage order progression" — staff cannot prepare orders if they cannot read what items are in them. The PLAN's D-01 acceptance criteria states "item names (from OrderDetail.items, show product names comma-separated)" and the plan specifies using "item.productId.substring(0, 8)" only as a fallback comment, not the primary intent. The OrderItem type does not include a productName field, making this unresolvable at render time.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `frontend/hooks/use-stomp.ts` | STOMP WebSocket hook for kitchen display | VERIFIED | 108 lines; exports useStomp; connected + reconnecting state; beforeConnect fetches fresh JWT via getSession(); reconnectDelay: 5000; ref-based callbacks prevent re-trigger |
| `frontend/app/dashboard/kitchen/page.tsx` | Kitchen display page, min 200 lines | VERIFIED | 484 lines; "use client"; full KDS implementation |
| `frontend/components/dashboard/sidebar.tsx` | Contains "Kitchen" nav link | VERIFIED | Kitchen entry with href="/dashboard/kitchen" and UtensilsCrossed icon at line 31 |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|-----|--------|---------|
| kitchen/page.tsx | /ws | @stomp/stompjs Client subscribing to /topic/kitchen/{tenantId}/{shopId} | VERIFIED | stompTopic = `/topic/kitchen/${tenantId}/${selectedShopId}` (line 219); passed to useStomp (line 263); useStomp creates Client and subscribes (use-stomp.ts line 65) |
| kitchen/page.tsx | /api/v1/orders | apiClient REST calls for initial load and order detail fetch | VERIFIED | apiClient.get("/api/v1/orders?shopId=...") at line 174; apiClient.get("/api/v1/orders/${o.id}/detail") at line 183 |
| kitchen/page.tsx | /api/v1/orders/{id}/start-preparation | apiClient POST for status bumping | VERIFIED | bumpActions.CONFIRMED.endpoint = "start-preparation" (line 59); apiClient.post("/api/v1/orders/${orderId}/${action.endpoint}") at line 298 |
| sidebar.tsx | /dashboard/kitchen | navigation array entry | VERIFIED | { name: "Kitchen", href: "/dashboard/kitchen", icon: UtensilsCrossed } at line 31 |

### Data-Flow Trace (Level 4)

| Artifact | Data Variable | Source | Produces Real Data | Status |
|----------|---------------|--------|--------------------|--------|
| kitchen/page.tsx | ordersMap | apiClient.get("/api/v1/orders?shopId=...") + "/api/v1/orders/{id}/detail" | Yes — live REST API calls with JWT auth via apiClient; no static/empty fallbacks | FLOWING |
| kitchen/page.tsx | shops | apiClient.get("/api/v1/shops?size=100") | Yes — live REST call; falls back to empty array [] only if no shops exist | FLOWING |
| kitchen/page.tsx | ordersMap (WebSocket path) | useStomp onMessage -> apiClient.get("/api/v1/orders/{orderId}/detail") | Yes — each WebSocket event triggers a fresh REST fetch for the full order detail | FLOWING |
| kitchen/page.tsx | item display | order.items[].productId.substring(0,8) | Partially — items are real from REST, but display value is a UUID fragment not a product name | HOLLOW_PROP (display only — data is real but rendered unreadably) |

### Behavioral Spot-Checks

Step 7b: SKIPPED — kitchen page requires a running Next.js dev server and live backend. No runnable entry points available for static spot-checks. Key API shape checks performed via type inspection instead.

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|-------------|------------|-------------|--------|----------|
| KDS-04 | 07-01-PLAN.md | Real-time order card feed on kitchen display page | VERIFIED | WebSocket via useStomp + initial REST load; ordersMap drives card grid |
| KDS-05 | 07-01-PLAN.md | Kitchen staff can bump order status (PREPARING -> READY) via WebSocket | VERIFIED | bumpActions map covers CONFIRMED->PREPARING->READY->COMPLETED; REST POST with optimistic UI |
| KDS-06 | 07-01-PLAN.md | Colour-coded order age indicators (green/yellow/red) | VERIFIED | ageBorderClass() with <5m/5-15m/>15m thresholds; updates every 30s |
| KDS-07 | 07-01-PLAN.md | Audio alert on new order arrival | VERIFIED | playBeep() with Web Audio API OscillatorNode; gated on mute state; only fires for genuinely new kitchen orders |

Note: KDS-05 description mentions "PREPARING -> READY" but the implementation covers the full chain CONFIRMED->PREPARING->READY->COMPLETED, which is correct per the plan.

### Anti-Patterns Found

| File | Line | Pattern | Severity | Impact |
|------|------|---------|----------|--------|
| kitchen/page.tsx | 410 | `item.productId.substring(0, 8)` used as display label | Warning | Kitchen staff see "3f7a1b2c" instead of "Burger", "Fries" — unreadable for real operation |
| kitchen/page.tsx | 451 | `{item.quantity}x {item.productId.substring(0,8)}` | Warning | Same issue — item details show UUID fragments not product names |

**Stub classification:** These are NOT empty stubs — real data flows from the API. However, the display value is semantically wrong (UUID != product name). The OrderItem DTO from the backend does not include a product name field, so this cannot be fixed purely in the frontend without a backend change.

No other anti-patterns detected:
- No dangerouslySetInnerHTML (XSS-safe)
- No TODO/FIXME/placeholder comments in critical paths
- No hardcoded empty arrays/null as final return values
- No console.log-only implementations

### Human Verification Required

#### 1. Real-Time WebSocket Order Feed

**Test:** With both backend and frontend running, place an order and advance it to CONFIRMED status via the orders page or API.
**Expected:** A new order card appears on /dashboard/kitchen within 1-2 seconds without any page refresh.
**Why human:** WebSocket connection and event delivery requires live running services and an actual order state change.

#### 2. Audio Alert on New Order

**Test:** Ensure the display is unmuted (Volume2 icon visible). Trigger a new CONFIRMED order.
**Expected:** An audible beep sounds as the card appears.
**Why human:** Web Audio API requires a browser context with user interaction to unlock audio. Cannot verify sound output in static analysis.

#### 3. Mute Toggle Persistence

**Test:** Click the mute button (icon changes to VolumeX). Refresh the page.
**Expected:** Page loads with VolumeX icon shown; no beep plays when a new order arrives.
**Why human:** Requires browser localStorage state inspection across page loads.

#### 4. Optimistic UI Error Rollback

**Test:** Simulate a network failure (disable API), click "Start Preparing" on a CONFIRMED card.
**Expected:** Card briefly shows "Preparing" status, then reverts to "Confirmed" and a destructive toast appears.
**Why human:** Requires intentionally failing the API call and observing UI reversion.

#### 5. Shop Selector WebSocket Reconnect

**Test:** In a multi-shop tenant, change the shop selector dropdown.
**Expected:** Connection indicator briefly shows "Reconnecting...", then returns to "Connected"; order cards update to show only the selected shop's orders.
**Why human:** Requires multi-shop tenant test data and live WebSocket observation.

### Gaps Summary

**One gap blocks full goal achievement:**

The kitchen page displays truncated UUID fragments (e.g., "3f7a1b2c") instead of human-readable product names on order cards. This is a data contract mismatch: the backend's `OrderItem` DTO (surfaced via `/api/v1/orders/{id}/detail`) only includes `productId` — there is no `productName` field — and the frontend `OrderItem` type mirrors this gap.

Kitchen staff cannot realistically prepare orders when the item list shows opaque UUID prefixes rather than "2x Chicken Burger, 1x Chips". All other KDS behaviours (real-time feed, status bumping, age colours, audio, mute, WebSocket reconnect) are fully implemented and wired correctly.

**Fix requires:**
1. Backend: Add `productName` (or `name`) to the OrderItem DTO returned by the order detail endpoint
2. Frontend `types/api.ts`: Add `productName: string` to the `OrderItem` interface
3. Frontend `kitchen/page.tsx` lines 410 and 451: Replace `item.productId.substring(0, 8)` with `item.productName`

---

_Verified: 2026-04-07T17:30:00Z_
_Verifier: Claude (gsd-verifier)_
