---
phase: 5
slug: kds-security-websocket-foundation
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 5 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + WebSocket test support |
| **Config file** | `core-java/src/test/resources/application-test.properties` |
| **Quick run command** | `cd core-java && JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew test --tests "*WebSocket*" --tests "*Tenant*Interceptor*" --no-daemon -q` |
| **Full suite command** | `cd core-java && JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew test --no-daemon` |
| **Estimated runtime** | ~45 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick command (WebSocket + interceptor tests)
- **After each plan completes:** Run full suite
- **Before phase sign-off:** Run full suite + manual WebSocket connection test

---

## Validation Architecture

### What to validate
1. WebSocket STOMP endpoint accepts connections at /ws
2. JWT validation rejects connections without valid token
3. Tenant-scoped subscriptions — tenantA cannot subscribe to tenantB's topics
4. TenantContext is set correctly in message handlers
5. TenantContext is cleaned up after handler completion (thread safety)
6. Existing SSE and all prior tests still pass (regression)

### How to validate
- Unit tests for TenantChannelInterceptor (mock JWT, verify accept/reject)
- Unit tests for JwtHandshakeInterceptor (extract token from query param)
- Integration test for cross-tenant subscription rejection
- Full test suite regression run

### Phase Requirements to Test Map

| Requirement | Test Coverage |
|-------------|---------------|
| KDS-01 (WebSocket config) | WebSocketConfigTest — endpoint registration, broker config |
| KDS-02 (Tenant isolation) | TenantChannelInterceptorTest — CONNECT reject, SUBSCRIBE reject |
| KDS-03 (TenantContext) | TenantChannelInterceptorTest — SEND sets context, afterMessageHandled clears |

---

## Wave 0 — Pre-execution Checks

- [ ] Existing test suite passes before any changes
- [ ] `spring-boot-starter-websocket` dependency resolves without conflicts
- [ ] SecurityConfig currently blocks /ws (baseline — proves the permitAll change is needed)
