---
phase: 05-kds-security-websocket-foundation
plan: 01
subsystem: core-java/websocket
tags: [websocket, stomp, security, tenant-isolation, kds]
dependency_graph:
  requires: []
  provides: [websocket-stomp-config, jwt-handshake-interceptor, tenant-channel-interceptor]
  affects: [SecurityConfig]
tech_stack:
  added: [spring-boot-starter-websocket]
  patterns: [ExecutorChannelInterceptor, HandshakeInterceptor, STOMP-tenant-isolation]
key_files:
  created:
    - core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java
    - core-java/src/main/java/uk/jtoye/core/websocket/JwtHandshakeInterceptor.java
    - core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java
    - core-java/src/test/java/uk/jtoye/core/websocket/JwtHandshakeInterceptorTest.java
    - core-java/src/test/java/uk/jtoye/core/websocket/WebSocketConfigTest.java
    - core-java/src/test/java/uk/jtoye/core/websocket/TenantChannelInterceptorTest.java
  modified:
    - core-java/build.gradle.kts
    - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
decisions:
  - Used ExecutorChannelInterceptor (not plain ChannelInterceptor) for thread-safe TenantContext cleanup via afterMessageHandled
  - JWT extracted from query param at HTTP handshake, validated at STOMP CONNECT (two-phase auth)
  - TenantId-only validation on SUBSCRIBE (shopId deferred to RLS per D-06)
  - Full TenantChannelInterceptor created in Task 1 (not stubbed) since WebSocketConfig tests require it
metrics:
  duration: 374s
  completed: 2026-04-08T12:36:40Z
  tasks: 3
  files_created: 6
  files_modified: 2
  tests_added: 23
requirements: [KDS-01, KDS-02, KDS-03]
---

# Phase 5 Plan 1: WebSocket STOMP Security Foundation Summary

WebSocket/STOMP infrastructure with JWT handshake auth, tenant-scoped subscription validation, and ExecutorChannelInterceptor-based TenantContext propagation for KDS real-time feeds.

## What Was Built

### WebSocketConfig
- `@EnableWebSocketMessageBroker` with in-memory simple broker at `/topic`
- Application destination prefix `/app`, STOMP endpoint at `/ws`
- JwtHandshakeInterceptor registered on endpoint, TenantChannelInterceptor on inbound channel
- No SockJS (per D-09), `setAllowedOriginPatterns("*")` for dev

### JwtHandshakeInterceptor
- Extracts JWT from `?token=<jwt>` query parameter on WebSocket HTTP upgrade
- Stores token in session attributes as `jwt_token` for STOMP-level validation
- Always returns `true` (rejection happens at STOMP CONNECT, not HTTP level)
- Handles null, empty, and multi-param query strings

### TenantChannelInterceptor
- Implements `ExecutorChannelInterceptor` (not plain `ChannelInterceptor`) for thread-safe cleanup
- **CONNECT**: Validates JWT via existing `JwtDecoder` bean, extracts tenantId (claim order: tenant_id, tenantId, tid), stores in session attributes, sets `JwtAuthenticationToken` as user principal
- **SUBSCRIBE**: Validates `/topic/kitchen/{tenantId}/{shopId}` destination against session tenant. Rejects cross-tenant with `MessageDeliveryException`. Non-kitchen topics pass through.
- **SEND**: Sets `TenantContext` from session attributes before handler execution
- **afterMessageHandled**: Clears `TenantContext` unconditionally (safe for all frame types, runs on handler thread)

### SecurityConfig Update
- Added `/ws/**` to `permitAll()` before `.anyRequest().authenticated()`
- WebSocket auth happens at STOMP level, HTTP upgrade must pass through

## Test Coverage

| Test Class | Tests | Coverage |
|-----------|-------|---------|
| JwtHandshakeInterceptorTest | 5 | Token extraction from query string variants |
| WebSocketConfigTest | 3 | Annotation, interface, interceptor registration |
| TenantChannelInterceptorTest | 15 | CONNECT auth, cross-tenant rejection, TenantContext lifecycle |
| **Total new tests** | **23** | |

### Key Security Tests
- Cross-tenant subscription blocked (T-01 mitigation verified)
- Missing/blank/invalid JWT rejected at CONNECT (T-02 mitigation verified)
- Invalid UUID in destination returns clear error (T-05 mitigation verified)
- TenantContext cleared after handler even on exception (T-04 mitigation verified)
- JWT claim preference order verified (tenant_id > tenantId > tid)

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] Full TenantChannelInterceptor created in Task 1 instead of stub**
- **Found during:** Task 1
- **Issue:** WebSocketConfig and its tests require TenantChannelInterceptor to compile. Creating a stub would mean rewriting in Task 2.
- **Fix:** Created full implementation in Task 1, Task 2 focused on comprehensive test suite.
- **Files modified:** TenantChannelInterceptor.java
- **Commit:** 0f37cf2

**2. [Rule 1 - Bug] StompHeaderAccessor immutable headers in tests**
- **Found during:** Task 2
- **Issue:** `accessor.setUser()` throws `IllegalStateException: Already immutable` when called on headers from a message built without `setLeaveMutable(true)`.
- **Fix:** Added `accessor.setLeaveMutable(true)` in test helper method.
- **Files modified:** TenantChannelInterceptorTest.java
- **Commit:** 0ddf346

## Threat Model Verification

| Threat | Mitigation | Test | Status |
|--------|-----------|------|--------|
| T-01: Cross-tenant data leakage | SUBSCRIBE tenant validation | shouldBlockCrossTenantSubscription | Verified |
| T-02: Unauthenticated connection | JWT validation on CONNECT | shouldRejectMissingToken, shouldRejectInvalidToken | Verified |
| T-03: JWT in URL exposure | Accepted risk (industry standard) | N/A | Accepted |
| T-04: TenantContext null in handler | preSend SEND + afterMessageHandled | shouldSetTenantContextOnSend, shouldClearTenantContextAfterMessageHandled | Verified |
| T-05: Invalid UUID in path | try-catch on UUID.fromString | shouldRejectInvalidTenantIdInDestination | Verified |

## Verification Results

- Full test suite: BUILD SUCCESSFUL (264 tests, 0 failures)
- No changes to OrderSseService.java (per D-12)
- No changes to JwtTenantFilter.java
- Clean compilation with no errors
- All 20 acceptance criteria checks pass

## Known Stubs

None. All implementations are complete and wired.
