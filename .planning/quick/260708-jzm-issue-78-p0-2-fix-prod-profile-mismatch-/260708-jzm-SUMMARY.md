---
phase: quick-260708-jzm
plan: 01
subsystem: core-java config / deployment
tags: [security, spring-profiles, fail-fast, k8s, docker, issue-78]
requirements: [ISSUE-78-P0-2]
requires:
  - Spring Boot 3.5.16 EnvironmentPostProcessor
provides:
  - Fail-fast startup guard rejecting unknown Spring profiles
  - Correct prod profile activation in k8s + container
affects:
  - core-java startup, k8s base deployment, core-java Docker image
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java
    - core-java/src/main/resources/META-INF/spring.factories
    - core-java/src/test/java/uk/jtoye/core/config/ActiveProfileValidatorTest.java
    - core-java/src/main/resources/application-dev.yml
  modified:
    - k8s/base/core-java-deployment.yaml
    - docs/metrics.json
    - CLAUDE.md
    - core-java/Dockerfile
decisions:
  - "EnvironmentPostProcessor (not ApplicationListener) so the guard runs for main() and every @SpringBootTest boot"
  - "Merge getActiveProfiles() with raw spring.profiles.active property to make the guard order-insensitive at env-prepared time"
  - "Remove -Dspring.profiles.active from the Docker image so the SPRING_PROFILES_ACTIVE env var flows natively (a -D would override it)"
metrics:
  duration: ~65m
  completed: 2026-07-08
  tasks: 3
  commits: 4
---

# Phase quick-260708-jzm Plan 01: Issue #78 [P0-2] Prod Profile Mismatch Fix Summary

Fail-fast Spring-profile guard (`ActiveProfileValidator`) plus the k8s `production`->`prod`
correction, closing the Issue #78 [P0-2] silent-boot misconfiguration end-to-end — including
two container-level bugs (the guard not firing in the fat jar, and a baked
`-Dspring.profiles.active=production` override) that would otherwise have negated the k8s fix.

## Tasks Completed

| Task | Name | Commit(s) | Result |
|------|------|-----------|--------|
| 1 | Fail-fast validator + plain-JUnit tests + application-dev.yml | `f76dab4`, `3984413` | Full `:core-java:test` green |
| 2 | k8s `production`->`prod` + regenerate metrics + sync CLAUDE.md | `3c10319` | k8s clean; docs-freshness exit 0 |
| 3 | Container + live-stack proof (no commit) + 2 deviation fixes | `a389739` (deviation) | All proofs green |

## Commits

- `f76dab4` feat(config): fail-fast on unknown Spring profile + application-dev.yml (#78)
- `3c10319` fix(k8s): SPRING_PROFILES_ACTIVE production->prod + refresh test counts (#78)
- `3984413` fix(config): make profile guard actually fire in the executable jar (#78) — deviation
- `a389739` fix(docker): drop baked -Dspring.profiles.active override (#78) — deviation

All four carry the conventional prefix and NO trailers (verified).

## Deviations from Plan

Task 3 (container proof) is what surfaced these — both are directly on the plan's critical path
(without them the k8s fix is silently negated and the live proof cannot pass). No architectural
change; auto-fixed under deviation Rules 1/2/3 and committed atomically.

### Auto-fixed Issues

**1. [Rule 1 - Bug] Profile guard did not fire in the executable jar**
- **Found during:** Task 3 negative proof. First `docker run -e SPRING_PROFILES_ACTIVE=production`
  exited non-zero but for the WRONG reason — it booted past the guard all the way to Flyway
  (`Connection refused`), logging `The following 1 profile is active: "production"`.
- **Root cause:** At `ApplicationEnvironmentPreparedEvent`, `Environment.getActiveProfiles()`
  returned empty, so `validate([])` passed. A decisive exploded-jar test (moving `spring.factories`
  into `BOOT-INF/classes/META-INF/`) still did not fire, isolating the cause to profile resolution,
  not file placement.
- **Fix:** Added `resolveActiveProfiles()` merging `getActiveProfiles()` with the raw
  `spring.profiles.active` property (which resolves `SPRING_PROFILES_ACTIVE` via relaxed binding),
  and ordered the processor after `ConfigDataEnvironmentPostProcessor` (still before bean/DB init).
- **Files:** `core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java`
- **Commit:** `3984413`

**2. [Rule 1/2/3 - Bug/Security/Blocking] Docker image baked a profile override**
- **Found during:** Task 3 positive proof. `docker run -e SPRING_PROFILES_ACTIVE=prod` was
  rejected for `'production'`. `docker inspect` showed
  `JAVA_OPTS=... -Dspring.profiles.active=production` baked into the image.
- **Root cause:** The Dockerfile ENV `-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-production}`
  is expanded by Docker at BUILD time (the var is empty during build) → literal `production`. A `-D`
  system property outranks the runtime `SPRING_PROFILES_ACTIVE` env var in Spring, so the container
  ran the phantom `production` profile regardless of compose (`dev`) or k8s (`prod`) — a deeper
  instance of the same P0-2 bug that would have negated Task 2's k8s fix. (The pre-change live image
  had no `-D` profile and correctly ran `dev`, confirming the flag was the regression.)
- **Fix:** Removed the `-Dspring.profiles.active` flag from `JAVA_OPTS`; Spring Boot reads
  `SPRING_PROFILES_ACTIVE` natively.
- **Files:** `core-java/Dockerfile`
- **Commit:** `a389739`

## Task 3 Evidence (verification only — no commit for this task itself)

Rollback backup tagged before rebuild: `jtoye_oaas_2026-core-java:pre-78` (`361cafed4eb6`), still
present. All proofs below are from the final image built from committed HEAD (`bf1132ab8c95`).

### Negative proof — `docker run --rm -e SPRING_PROFILES_ACTIVE=production ...:latest`
- **Exit code:** `1` (non-zero)
- **Validator message (stderr):**
  `java.lang.IllegalStateException: Unknown Spring profile 'production' is active. Valid profiles are [local, dev, test, staging, prod]. Check SPRING_PROFILES_ACTIVE — matching is exact and case-sensitive ...`
- **Aborted before bean/DB init:** 0 Hikari/Flyway/Connection lines; total output 28 lines; stack
  trace terminates in `SpringApplication.prepareEnvironment` (env-prepared phase) — no Spring banner,
  no repository scan, no Tomcat.

### Positive proof — `docker run --rm -e SPRING_PROFILES_ACTIVE=prod ...:latest`
- **Exit code:** `1` (expected: DB unreachable, which occurs AFTER profile activation)
- **Ordering:** `The following 1 profile is active: "prod"` at log line **59**; first DB failure
  (`Unable to obtain connection ... Connection to localhost:5432 refused`, during `entityManagerFactory`
  bean init) at log line **69**. Activation precedes the env failure.
- `prod` was accepted (no `Unknown Spring profile` rejection).

### Live-stack proof — recreate only core-java under compose `dev`
Command: `docker compose -f docker-compose.full-stack.yml up -d --no-deps --force-recreate core-java`
- **(a) Health:** `docker inspect --format '{{.State.Health.Status}}' jtoye_oaas_2026-core-java-1` = `healthy`
- **(b) Health endpoint:** `curl -fsS http://localhost:9090/actuator/health` = `{"status":"UP"}` (exit 0)
- **(c) Frontend:** `curl -fsI http://localhost:3100` = `HTTP/1.1 307 Temporary Redirect` (curl exit 0).
  Note: 307 is this Next.js app's normal root redirect (root -> signin/dashboard); `curl -f` succeeds
  (<400). The plan annotated "(HTTP 200)" as an expectation; the app's actual root behavior is a 307
  redirect, and the frontend container was NOT restarted (uptime `Up 46 minutes` predates the recreate).
- **Active profile:** live logs show `The following 1 profile is active: "dev"` (correct).
- **Other services untouched:** only `jtoye_oaas_2026-core-java-1` recreated (`Up ~30s`);
  `jtoye-frontend` `Up 46m`, `jtoye-keycloak`/`jtoye-postgres` `Up 2 days` — not restarted.

## Verification Results

- `./gradlew :core-java:test --no-daemon` — BUILD SUCCESSFUL (includes `ActiveProfileValidatorTest`
  6 methods + the `{dev,test}` and `{prod,test}` @SpringBootTest contexts).
- Targeted: `ActiveProfileValidatorTest`, `SecurityHeadersDevProfileTest`,
  `SecurityHeadersProdProfileTest` — all green.
- `scripts/docs-freshness.sh` — `docs-freshness OK: metrics match source (total logical invocations: 700)`.
- `metrics.json` moved ONLY `java_test_methods` 495->501, `java_test_files` 75->76, total 694->700
  (N=6 new @Test methods); every other field unchanged.
- No `SPRING_PROFILES_ACTIVE` value `production` remains anywhere under `k8s/`; base deployment now
  `value: "prod"`.
- CLAUDE.md count paragraph now reads 700 / 501 methods / 76 files / 102 jest / 18 files — matches
  metrics.json (also cleared the pre-existing 692/100/17 drift).

## Known Stubs

None.

## Self-Check: PASSED

Created files (all present):
- FOUND: core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java
- FOUND: core-java/src/main/resources/META-INF/spring.factories
- FOUND: core-java/src/test/java/uk/jtoye/core/config/ActiveProfileValidatorTest.java
- FOUND: core-java/src/main/resources/application-dev.yml

Commits (all present in git log):
- FOUND: f76dab4 (Task 1)
- FOUND: 3c10319 (Task 2)
- FOUND: 3984413 (deviation 1)
- FOUND: a389739 (deviation 2)

Docs artifacts (SUMMARY.md/STATE.md/PLAN.md), ROADMAP.md: NOT committed / NOT modified, per constraints.
No push, no PR opened.
