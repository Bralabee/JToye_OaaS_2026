---
phase: 25
slug: mutating-mcp-tools
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-07-24
---

# Phase 25 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | vitest (mcp-server/) + JUnit 5 / Testcontainers (core-java) |
| **Config file** | `mcp-server/vitest.config.ts` · `core-java/build.gradle.kts` |
| **Quick run command** | `cd mcp-server && npm test` |
| **Full suite command** | `cd mcp-server && npm test && cd ../core-java && ./gradlew test integrationTest` |
| **Estimated runtime** | ~mcp vitest <30s · core-java integrationTest several min |

---

## Sampling Rate

- **After every task commit:** Run the quick run command for the touched surface (`npm test` for mcp-server; targeted `./gradlew test --tests <Class>` for core-java)
- **After every plan wave:** Run the full suite command
- **Before `/gsd:verify-work`:** Full suite must be green + live E2E (rebuild ALL containers + realm re-import) proven
- **Max feedback latency:** ~30s (mcp vitest); Java integration slower — run targeted during dev

---

## Per-Task Verification Map

> Populated by gsd-planner from the PLAN.md tasks. Each write-tool / scope-gate / realm task maps to a vitest or JUnit assertion below.

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| 25-01-01 | 01 | 1 | AI-02 | — | orders:write / customers:write gate returns 403 for no-scope token, allows write-scoped | integration (MockMvc) | `./gradlew test --tests '*ScopedWriteAccess*'` | ❌ W0 | ⬜ pending |
| 25-02-01 | 02 | 2 | AI-02 | T-25 | create_order/create_customer replay with same Idempotency-Key returns original DTO, no duplicate | unit (vitest) + integration | `cd mcp-server && npm test` | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

- [ ] `mcp-server/src/tools/create-order.test.ts` — vitest stubs for create_order (mock corePost)
- [ ] `mcp-server/src/tools/create-customer.test.ts` — vitest stubs for create_customer
- [ ] `core-java/.../ScopedWriteAccessIntegrationTest.java` — converter-through-MockMvc @PreAuthorize proof (mirror ScopedCatalogAccessIntegrationTest)
- [ ] Cross-tenant RLS proof under the write credential (HTTP-boundary; NOSUPERUSER, not superuser Testcontainers)

*Existing infrastructure (vitest + Testcontainers + JwtRolesAndScopesConverter test harness) covers the frameworks; only new test files are added.*

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Live E2E: mint integration-orders-rw token → create_customer + replay → create_order → foreign-tenant 403 | AI-02 (AC-3) | Requires rebuilt full-stack + realm re-import (Playwright not in CI; live Keycloak) | Rebuild ALL containers, `kc.sh import --override true`, mint token, POST /mcp with each tool, assert 200 + replay-identical + foreign-shopId 403 |

*Automated coverage handles the scope gate, idempotent replay (mocked), and RFC 7807 mapping; the live cross-stack proof is manual per project standard.*

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 30s (mcp) / documented for Java integration
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
