---
phase: 28
slug: security-triage-the-dev-prod-boundary
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-10
---

# Phase 28 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Populated by the planner from 28-RESEARCH.md § Validation Architecture.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (real Postgres + RLS); bash `check-*.sh` gates; Playwright for any E2E arm |
| **Config file** | `core-java/build.gradle.kts` (test + integrationTest source sets) |
| **Quick run command** | `cd core-java && ./gradlew test --tests '<TouchedClass>*'` |
| **Full suite command** | `cd core-java && ./gradlew test integrationTest` (FULL suite mandatory for auth-touching changes — trap_scope_gate_integrationtest_regression) |
| **Estimated runtime** | quick ~60s · full integration ~10-15 min (Testcontainers Postgres) |

---

## Sampling Rate

- **After every task commit:** Run the touched-class quick command
- **After every plan wave:** Run the full suite command
- **Before `/gsd:verify-work`:** Full suite must be green
- **Max feedback latency:** 900 seconds (integrationTest bound)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| *(populated by planner)* | | | | | | | | | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

*(populated by planner — expected: none; existing Testcontainers RLS harness, profile-gating tests, and check-*.sh gate precedents cover the phase's verification shapes. Every new gate must be shown to FAIL before it is trusted.)*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Owner confirms the identity of #552's six credentials (research Assumption A5) before rotation runs | SEC-04 | Only the owner can confirm the compromised-credential set against the private pentest report | Present the enumerated credential list; obtain explicit approval; record dated answer in the plan/SUMMARY |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 900s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
