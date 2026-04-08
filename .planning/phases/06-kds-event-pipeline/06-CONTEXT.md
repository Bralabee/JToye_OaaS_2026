# Phase 6: KDS Event Pipeline - Context

**Gathered:** 2026-04-08
**Status:** Ready for planning

<domain>
## Phase Boundary

Add WebSocket broadcast to existing OrderStateChangeListener. When an order status changes, the RabbitMQ consumer broadcasts to the correct tenant's WebSocket topic alongside the existing SSE broadcast. Single event pipeline — no direct service-to-WebSocket broadcasting.

</domain>

<decisions>
## Implementation Decisions

### Event Payload
- **D-01:** Send `OrderStateChangeEvent` as-is via WebSocket: `orderId`, `tenantId`, `orderNumber`, `previousStatus`, `newStatus`, `timestamp`. Lean payload — KDS UI fetches full order detail via REST if needed.
- **D-02:** Use `SimpMessagingTemplate.convertAndSend()` to broadcast to `/topic/kitchen/{tenantId}/{shopId}`. The `tenantId` comes from the event; `shopId` needs to be resolved from the order.

### Wiring Pattern
- **D-03:** Add WebSocket broadcast to `OrderStateChangeListener.handleOrderStateChange()` — the existing `@RabbitListener` method. This is the single place where order events are consumed. SSE broadcast stays (D-12 from Phase 5).
- **D-04:** Inject `SimpMessagingTemplate` into `OrderStateChangeListener` via constructor injection.
- **D-05:** Resolve shopId from the order record. The `OrderStateChangeEvent` has `orderId` and `tenantId` but NOT `shopId`. The listener must look up the order to get the shopId for topic routing.

### Error Handling
- **D-06:** WebSocket broadcast failure must NOT block the existing SSE + email + metrics pipeline. Catch exceptions from `convertAndSend()` and log a warning. Fire-and-forget for WebSocket — same pattern as current SSE broadcast.

### Claude's Discretion
- Whether to add `shopId` to `OrderStateChangeEvent` (avoids extra DB lookup) or look it up in the listener
- Jackson serialization of the event payload for WebSocket (should work automatically with Spring's message converter)
- Test structure — mock `SimpMessagingTemplate` and verify `convertAndSend()` called with correct topic

</decisions>

<canonical_refs>
## Canonical References

### Event Consumer (modify this)
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` — Add WebSocket broadcast here

### Event Payload
- `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeEvent.java` — Current payload fields

### WebSocket Template (inject this)
- `core-java/src/main/java/uk/jtoye/core/websocket/` — WebSocketConfig, TenantChannelInterceptor from Phase 5

### Existing Tests
- `core-java/src/test/java/uk/jtoye/core/order/OrderStateChangeListenerTest.java` — Add WebSocket verification

### Research
- `.planning/research/PITFALLS.md` — Pitfall #4: event pipeline divergence (single source via RabbitMQ)

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- `SimpMessagingTemplate` — auto-configured by Spring WebSocket (Phase 5)
- `OrderStateChangeListener` — already processes events, sets TenantContext, sends SSE + email + metrics

### Integration Points
- `OrderStateChangeListener.handleOrderStateChange()` — add `simpMessagingTemplate.convertAndSend()` call
- `OrderRepository` or `OrderService` — may need to look up shopId from orderId

</code_context>

<specifics>
## Specific Ideas

No specific requirements — straightforward wiring of SimpMessagingTemplate into existing listener.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 06-kds-event-pipeline*
*Context gathered: 2026-04-08*
