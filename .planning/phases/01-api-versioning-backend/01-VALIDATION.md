---
phase: 1
slug: api-versioning-backend
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-04-08
---

# Phase 1 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | JUnit 5 + Spring Boot Test + MockMvc |
| **Config file** | `core-java/src/test/resources/application-test.properties` |
| **Quick run command** | `cd core-java && JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew test --tests "*Controller*" --no-daemon -q` |
| **Full suite command** | `cd core-java && JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew test --no-daemon` |
| **Estimated runtime** | ~30 seconds |

---

## Sampling Rate

- **After every task commit:** Run quick command (controller tests only)
- **After each plan completes:** Run full suite
- **Before phase sign-off:** Run full suite + verify Swagger UI paths

---

## Validation Architecture

### What to validate
1. All versioned endpoints respond at `/api/v1/` prefix
2. Exempt endpoints remain at original paths (no prefix)
3. SecurityConfig allows `/api/v1/**` authenticated requests
4. Swagger UI reflects `/api/v1/` paths
5. All MockMvc tests updated and passing

### How to validate
- MockMvc tests with updated paths (primary)
- Manual curl checks for exempt endpoints
- Swagger UI path inspection

---

## Wave 0 — Pre-execution Checks

- [ ] Existing test suite passes before any changes
- [ ] Identify all MockMvc test files needing path updates
- [ ] Confirm WebConfig.java exists and is the right place for configurePathMatch()
