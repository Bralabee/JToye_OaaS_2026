---
phase: 06-kds-event-pipeline
plan: 01
subsystem: core-java/order
tags: [websocket, kds, event-pipeline, stomp]
dependency_graph:
  requires: [05-01]
  provides: [websocket-broadcast-pipeline]
  affects: [OrderStateChangeListener]
tech_stack:
  added: []
  patterns: [fire-and-forget-websocket, tenant-scoped-topic]
key_files:
  created: []
  modified:
    - core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java
    - core-java/src/test/java/uk/jtoye/core/order/OrderStateChangeListenerTest.java
decisions:
  - SimpMessagingTemplate injected as last constructor parameter per D-04
  - WebSocket broadcast placed before TenantContext setup, uses separate orderRepository.findById per D-05
  - Fire-and-forget pattern with try-catch around convertAndSend per D-06
metrics:
  duration: 195s
  completed: 2026-04-09T00:38:00Z
  tasks: 2/2
  files_modified: 2
---

# Phase 6 Plan 1: KDS Event Pipeline - WebSocket Broadcast Summary

WebSocket broadcast wired into OrderStateChangeListener via SimpMessagingTemplate, sending order state changes to /topic/kitchen/{tenantId}/{shopId} with fire-and-forget error isolation.

## What Was Done

### Task 1: Add WebSocket broadcast tests (TDD RED then GREEN)
**Commit:** `488fb85`

Added 3 new test methods to OrderStateChangeListenerTest:
- `broadcastsToWebSocket` - verifies convertAndSend called with correct topic path
- `skipsWebSocketWhenOrderNotFound` - verifies no WebSocket call when order missing, SSE still works
- `webSocketFailureDoesNotBlockPipeline` - verifies RuntimeException from WebSocket is caught, email pipeline continues

Updated existing test setup to inject SimpMessagingTemplate mock as constructor argument.

### Task 2: Add SimpMessagingTemplate broadcast to OrderStateChangeListener
**Commit:** `16d0f10`

Modified OrderStateChangeListener:
- Added `SimpMessagingTemplate` as final field with constructor injection
- Added WebSocket broadcast block after SSE broadcast but before TenantContext setup
- Topic path: `/topic/kitchen/{tenantId}/{shopId}` where tenantId comes from event, shopId from order lookup
- Entire broadcast wrapped in try-catch (fire-and-forget) - failure logs warning but does not affect SSE/email/metrics

## Commits

| Task | Hash | Message |
|------|------|---------|
| 1 | 488fb85 | test(06-01): add failing WebSocket broadcast tests (TDD RED) |
| 2 | 16d0f10 | feat(06-01): add WebSocket broadcast to OrderStateChangeListener |

## Verification

- All 7 OrderStateChangeListenerTest tests pass (4 existing + 3 new)
- Full core-java test suite passes with 0 failures
- `convertAndSend` present in OrderStateChangeListener.java
- `/topic/kitchen/` topic path confirmed in both source and test
- Error isolation confirmed via `WebSocket broadcast failed` log message

## Deviations from Plan

None - plan executed exactly as written.

## Known Stubs

None - all functionality is fully wired.
