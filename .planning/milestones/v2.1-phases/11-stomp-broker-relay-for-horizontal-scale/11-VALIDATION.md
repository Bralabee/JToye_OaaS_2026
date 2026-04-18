---
phase: 11
slug: stomp-broker-relay-for-horizontal-scale
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-16
---

# Phase 11 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (backend), Playwright 1.59 (e2e) |
| **Config file** | `core-java/build.gradle.kts`, `frontend/playwright.config.ts` |
| **Quick run command** | `cd core-java && ./gradlew test --tests '*WebSocket*'` |
| **Full suite command** | `cd core-java && ./gradlew test` |
| **Estimated runtime** | ~90 seconds |

---

## Sampling Rate

- **After every task commit:** Run `cd core-java && ./gradlew test --tests '*WebSocket*'`
- **After every plan wave:** Run `cd core-java && ./gradlew test`
- **Before `/gsd-verify-work`:** Full suite must be green
- **Max feedback latency:** 90 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 11-01-01 | 01 | 1 | STMP-01 | — | Config reads stomp.broker.mode, relay mode connects to RabbitMQ | unit | `./gradlew test --tests '*WebSocketConfig*'` | ❌ W0 | ⬜ pending |
| 11-01-02 | 01 | 1 | STMP-02 | — | STOMP plugin enabled, port 61613 exposed | integration | `docker compose exec rabbitmq rabbitmq-plugins list -e` | ✅ | ⬜ pending |
| 11-02-01 | 02 | 2 | STMP-03 | — | Two-replica broadcast within 2s | smoke | `docker compose up --scale core-java=2` + curl | ❌ W0 | ⬜ pending |
| 11-02-02 | 02 | 2 | STMP-04 | — | Playwright e2e cross-replica WebSocket | e2e | `npx playwright test stomp-relay` | ❌ W0 | ⬜ pending |
| 11-03-01 | 03 | 3 | STMP-05 | — | Prometheus alert on STOMP lag > 5s | integration | `amtool check-config` + `promtool check rules` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `core-java/src/test/java/.../websocket/WebSocketConfigTest.java` — test dual-mode config
- [ ] `frontend/e2e/stomp-relay.spec.ts` — Playwright e2e for cross-replica WebSocket
- [ ] Existing test infrastructure covers JUnit 5 + Testcontainers

*Existing infrastructure covers most phase requirements. New test files needed for WebSocket config and Playwright e2e.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Two-replica broadcast timing | STMP-03 | Requires multi-container orchestration with --scale | Start 2 replicas, POST order to one, verify WebSocket on other within 2s |
| Grafana dashboard tile | STMP-05 | Visual verification of dashboard | Open Grafana, check STOMP connection count tile renders |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 90s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
