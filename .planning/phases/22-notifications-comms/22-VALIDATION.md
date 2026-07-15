---
phase: 22
slug: notifications-comms
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-14
---

# Phase 22 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Testcontainers (Java) · Jest (frontend) · Playwright (E2E) |
| **Config file** | `core-java/build.gradle.kts` (test/integrationTest) · `frontend/jest.config.js` · `frontend/playwright.config.ts` |
| **Quick run command** | `cd core-java && ./gradlew test --tests "*Notification*" "*Webhook*" "*Email*"` |
| **Full suite command** | `cd core-java && ./gradlew test integrationTest` · `cd frontend && npm test && npm run build` |
| **Estimated runtime** | ~120 seconds (unit) / several minutes (integrationTest w/ Testcontainers) |

---

## Sampling Rate

- **After every task commit:** Run the quick run command scoped to the touched area
- **After every plan wave:** Run the full suite command
- **Before `/gsd:verify-work`:** Full suite must be green; `docs/metrics.json` reconciled + docs-freshness gate green
- **Max feedback latency:** 120 seconds (unit sampling)

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| _populated by planner/executor_ | — | — | COMMS-01..07 | — | — | — | — | — | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

*Validation Architecture is authored in `22-RESEARCH.md` (§ Validation Architecture) — the planner maps each requirement (RLS isolation under NOSUPERUSER, Mailhog email-landing assertion, HMAC verification, head-of-line-block isolation, retention prune, WhatsApp no-op, 375px UI) into this table during planning.*

---

## Wave 0 Requirements

- [ ] Existing infrastructure covers all phase requirements (JUnit 5 + Testcontainers + Mailhog in `docker-compose.full-stack.yml`; Jest + Playwright in frontend). No new framework install expected — confirm during planning.

*If a new test seam is needed (e.g. a test HMAC receiver, Mailhog assertion helper), the planner declares it as a Wave 0 dependency.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| _populated by planner_ | — | — | — |

*Prefer automated (Mailhog assertions make email-landing testable; Testcontainers makes RLS testable). If none remain: "All phase behaviors have automated verification."*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 120s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
