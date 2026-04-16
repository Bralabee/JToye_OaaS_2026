---
phase: 11-stomp-broker-relay-for-horizontal-scale
plan: 02
subsystem: testing
tags: [stomp, websocket, rabbitmq, playwright, smoke-test, cross-replica, horizontal-scaling]

# Dependency graph
requires:
  - phase: 11-stomp-broker-relay-for-horizontal-scale
    provides: Conditional STOMP broker mode (Plan 01) with relay config and RabbitMQ STOMP plugin
provides:
  - Bash smoke test for two-replica STOMP broadcast verification
  - Playwright e2e spec for cross-replica WebSocket message delivery within 2 seconds
affects: [11-03]

# Tech tracking
tech-stack:
  added: []
  patterns: [env-gated e2e tests (RELAY_E2E), parameterised test data (TEST_SHOP_ID/TEST_PRODUCT_ID), RabbitMQ management API connection inspection]

key-files:
  created:
    - scripts/smoke-test-stomp-relay.sh
    - frontend/e2e/stomp-relay.spec.ts
  modified: []

key-decisions:
  - "Smoke test checks RabbitMQ management API for STOMP connections rather than attempting raw STOMP connect"
  - "Playwright e2e gated behind RELAY_E2E=true to prevent accidental CI execution without multi-replica stack"
  - "Order state transitions walk the full state machine (PENDING -> CONFIRMED -> PREPARING) rather than jumping directly"

patterns-established:
  - "Env-gated integration tests: RELAY_E2E=true pattern for tests requiring specific infrastructure"
  - "RabbitMQ management API inspection for validating STOMP plugin and connection counts"

requirements-completed: []

# Metrics
duration: 1min
completed: 2026-04-16
status: checkpoint-pending
---

# Phase 11 Plan 02: STOMP Relay Verification Tests Summary

**Bash smoke test and Playwright e2e spec for verifying cross-replica STOMP broadcast with two core-java replicas in relay mode**

## Status: CHECKPOINT PENDING

Task 1 (auto) is complete and committed. Task 2 (checkpoint:human-verify) requires human verification of the two-replica STOMP broadcast against a live docker-compose stack. The orchestrator will present the checkpoint to the user.

### Checkpoint Verification Steps

1. Start the stack with relay mode and 2 replicas:
   ```bash
   STOMP_BROKER_MODE=relay docker compose -f docker-compose.full-stack.yml up -d --build --scale core-java=2
   ```
2. Wait for health (`docker compose ps` -- both core-java instances should be healthy)
3. Run the smoke test:
   ```bash
   ./scripts/smoke-test-stomp-relay.sh
   ```
4. Expected: All checks PASS, including STOMP connections >= 2
5. Optionally run the Playwright e2e:
   ```bash
   cd frontend && RELAY_E2E=true TEST_SHOP_ID=<uuid> TEST_PRODUCT_ID=<uuid> npx playwright test e2e/stomp-relay.spec.ts
   ```

## Performance

- **Duration:** 1 min
- **Started:** 2026-04-16T09:52:31Z
- **Completed:** 2026-04-16T09:53:58Z
- **Tasks:** 1/2 (checkpoint pending)
- **Files created:** 2

## Accomplishments
- Created `scripts/smoke-test-stomp-relay.sh` following existing smoke-test.sh pattern: validates edge health, RabbitMQ management, STOMP plugin (port 61613), relay connections >= 2 from replicas, replica count, and replica health
- Created `frontend/e2e/stomp-relay.spec.ts` gated behind `RELAY_E2E=true`: navigates to kitchen display, creates order via REST through edge-go, transitions through state machine to PREPARING, asserts WebSocket message arrives within 2 seconds
- Both tests are parameterised for multi-replica stack (STOMP_BROKER_MODE=relay, --scale core-java=2)

## Task Commits

1. **Task 1: Create smoke-test-stomp-relay.sh and Playwright e2e for cross-replica broadcast** - `d2834ba` (feat)
2. **Task 2: Verify two-replica STOMP broadcast works end-to-end** - CHECKPOINT PENDING (human verification required)

## Files Created/Modified
- `scripts/smoke-test-stomp-relay.sh` - Bash smoke test checking STOMP plugin, relay connections, replica count/health
- `frontend/e2e/stomp-relay.spec.ts` - Playwright cross-replica WebSocket e2e gated behind RELAY_E2E=true

## Decisions Made
- Smoke test queries RabbitMQ management API (`/api/connections`) to count STOMP connections from replicas rather than attempting raw STOMP protocol connections -- more reliable and provides connection metadata
- Playwright e2e walks the full order state machine (PENDING -> CONFIRMED -> PREPARING) since the kitchen event fires on PREPARING transition, matching the OrderStateChangeListener behavior
- E2e test tracks WebSocket connections via `page.on("websocket")` for observability, even though the primary assertion is DOM-based (order card appearing)

## Deviations from Plan

None - plan executed exactly as written.

## Issues Encountered
None.

## User Setup Required
None - no external service configuration required. Tests are self-contained when run against a properly configured multi-replica stack.

## Next Phase Readiness
- Checkpoint verification must be completed before marking STMP-03/STMP-04 as satisfied
- Once human confirms smoke test passes against live 2-replica stack, Plan 02 is complete
- Plan 03 (Prometheus alert + Grafana dashboard) can proceed independently of this checkpoint

---
*Phase: 11-stomp-broker-relay-for-horizontal-scale*
*Status: Checkpoint pending*
*Completed (Task 1): 2026-04-16*
