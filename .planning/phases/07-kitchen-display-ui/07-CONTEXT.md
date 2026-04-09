# Phase 7: Kitchen Display UI - Context

**Gathered:** 2026-04-09
**Status:** Ready for planning

<domain>
## Phase Boundary

Kitchen display page showing live order cards via WebSocket. Kitchen staff can bump order status (PREPARING → READY). Cards colour-coded by age. Audio alert on new orders. Uses Phase 5 WebSocket + Phase 6 event pipeline.

</domain>

<decisions>
## Implementation Decisions

### Card Layout
- **D-01:** Order cards show: order number (large, prominent), customer name, item count with names, order status badge, time elapsed since order creation. Compact card format — designed for kitchen screens where space matters.
- **D-02:** Cards arranged in a responsive grid (CSS grid). New orders appear at the top-left. Cards flow left-to-right, wrap to next row.
- **D-03:** Cards show CONFIRMED, PREPARING, and READY orders. DRAFT/PENDING/COMPLETED/CANCELLED are hidden (not relevant for kitchen).

### Status Bumping
- **D-04:** Each card has a single prominent action button:
  - CONFIRMED → "Start Preparing" button (bumps to PREPARING)
  - PREPARING → "Mark Ready" button (bumps to READY)
  - READY → "Complete" button (bumps to COMPLETED, card disappears)
- **D-05:** Status bump calls existing REST API: `POST /api/v1/orders/{id}/start-preparation`, `/mark-ready`, `/complete`. Optimistic UI — update card immediately, revert on error.

### Age Indicators
- **D-06:** Card border colour changes based on time since order creation:
  - Green: < 5 minutes
  - Yellow: 5-15 minutes
  - Red: > 15 minutes
- **D-07:** Timer updates every 30 seconds via `setInterval`. Shows "Xm ago" text.

### Audio Alerts
- **D-08:** Play a short notification sound (HTML5 `Audio` API) when a new order card appears. Use a simple built-in beep tone generated via Web Audio API (no external audio file dependency).
- **D-09:** Mute/unmute toggle button in the header. Default: unmuted. State persisted in `localStorage`.

### Shop Selector
- **D-10:** Dropdown in the page header to select which shop's orders to display. Fetches vendor's shops via `GET /api/v1/shops`. Auto-selects first shop. Changing shop disconnects WebSocket and reconnects to the new shop's topic.

### WebSocket Connection
- **D-11:** Use `@stomp/stompjs` Client directly (no wrapper library). Connect on page mount with JWT from session. Subscribe to `/topic/kitchen/{tenantId}/{shopId}`. On message: fetch full order detail via REST, add/update card.
- **D-12:** Reconnect logic: `@stomp/stompjs` has built-in reconnect delay. On reconnect, fetch full order list via REST to sync state (prevents missed events during disconnect).

### Page Location
- **D-13:** Route: `/dashboard/kitchen`. Add "Kitchen" link to sidebar navigation with a UtensilsCrossed icon.

### Claude's Discretion
- Whether to fetch all active orders on page load (recommended) or wait for WebSocket events only
- Connection status indicator in header (connected/reconnecting/disconnected)
- Full-screen mode toggle for kitchen displays
- Whether to add `@stomp/stompjs` to package.json or use a CDN

</decisions>

<canonical_refs>
## Canonical References

### WebSocket Infrastructure (Phase 5+6)
- `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java` — STOMP endpoint at /ws
- `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java` — Tenant security
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` — Broadcasts to /topic/kitchen/{tenantId}/{shopId}
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeEvent.java` — Event payload shape

### Existing Dashboard Patterns
- `frontend/app/dashboard/orders/page.tsx` — Order list with status badges, action buttons (template)
- `frontend/app/dashboard/marketing/page.tsx` — Tabbed page with status badges (Phase 4)
- `frontend/components/dashboard/sidebar.tsx` — Add Kitchen link
- `frontend/lib/api-client.ts` — Authenticated REST client

### Order API
- `GET /api/v1/orders` — List orders (for initial load)
- `GET /api/v1/orders/{id}/detail` — Full order detail
- `POST /api/v1/orders/{id}/start-preparation` — Bump CONFIRMED → PREPARING
- `POST /api/v1/orders/{id}/mark-ready` — Bump PREPARING → READY
- `POST /api/v1/orders/{id}/complete` — Bump READY → COMPLETED

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- Badge component for status display
- Card component for order cards
- Toast for error notifications
- Orders page has status bump logic (can reference pattern)

### Established Patterns
- Dashboard pages use apiClient for REST calls
- Status badges with colour coding (from marketing page)
- Sidebar navigation with Lucide icons

### Integration Points
- `frontend/components/dashboard/sidebar.tsx` — add Kitchen link
- `frontend/lib/api-client.ts` — REST calls for order detail and status bumping
- `package.json` — add `@stomp/stompjs` dependency

</code_context>

<specifics>
## Specific Ideas

No specific requirements — standard KDS implementation following industry patterns (Toast, Square, Fresh KDS research).

</specifics>

<deferred>
## Deferred Ideas

- Station routing (grill, fryer, drinks) — v2
- Course pacing — v2
- Order recall/history on KDS — v2
- Full-screen kiosk mode — nice-to-have, Claude's discretion

</deferred>

---

*Phase: 07-kitchen-display-ui*
*Context gathered: 2026-04-09*
