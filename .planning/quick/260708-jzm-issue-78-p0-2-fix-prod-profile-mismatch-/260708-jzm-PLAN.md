---
phase: quick-260708-jzm
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [ISSUE-78-P0-2]
files_modified:
  - core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java
  - core-java/src/main/resources/META-INF/spring.factories
  - core-java/src/test/java/uk/jtoye/core/config/ActiveProfileValidatorTest.java
  - core-java/src/main/resources/application-dev.yml
  - k8s/base/core-java-deployment.yaml
  - docs/metrics.json
  - CLAUDE.md

must_haves:
  truths:
    - "Booting with SPRING_PROFILES_ACTIVE=production fails fast, naming 'production' and the valid profile set, before any bean/DB init."
    - "Booting with SPRING_PROFILES_ACTIVE=prod activates the 'prod' profile and loads application-prod.yml."
    - "Existing profile combos ({dev,test}, {prod,test}, test, default) still boot — full fast test suite stays green."
    - "k8s base deployment sets SPRING_PROFILES_ACTIVE=prod; the string value 'production' appears nowhere in k8s."
    - "The compose 'dev' profile resolves to a real application-dev.yml with unchanged live behavior."
    - "docs-freshness gate passes with the updated Java test counts; CLAUDE.md count paragraph matches metrics.json."
  artifacts:
    - path: "core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java"
      provides: "EnvironmentPostProcessor that fails fast on unknown active profiles"
      contains: "implements EnvironmentPostProcessor"
      min_lines: 30
    - path: "core-java/src/main/resources/META-INF/spring.factories"
      provides: "Registers ActiveProfileValidator so it runs for main() and @SpringBootTest boots"
      contains: "org.springframework.boot.env.EnvironmentPostProcessor"
    - path: "core-java/src/test/java/uk/jtoye/core/config/ActiveProfileValidatorTest.java"
      provides: "Plain-JUnit coverage of the validation rule (4-6 @Test methods)"
      contains: "@Test"
    - path: "core-java/src/main/resources/application-dev.yml"
      provides: "Real document backing the compose 'dev' profile (behavior-neutral)"
    - path: "k8s/base/core-java-deployment.yaml"
      provides: "Correct SPRING_PROFILES_ACTIVE=prod value"
      contains: "value: \"prod\""
    - path: "docs/metrics.json"
      provides: "Refreshed test counts after the new test file"
  key_links:
    - from: "core-java/src/main/resources/META-INF/spring.factories"
      to: "uk.jtoye.core.config.ActiveProfileValidator"
      via: "EnvironmentPostProcessor registration key"
      pattern: "EnvironmentPostProcessor=uk\\.jtoye\\.core\\.config\\.ActiveProfileValidator"
    - from: "core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java"
      to: "startup failure message"
      via: "IllegalStateException naming offending profile + valid set"
      pattern: "KNOWN_PROFILES|Valid profiles"
    - from: "k8s/base/core-java-deployment.yaml"
      to: "prod profile"
      via: "SPRING_PROFILES_ACTIVE env value"
      pattern: "SPRING_PROFILES_ACTIVE"
---

<objective>
Issue #78 [P0-2]: `SPRING_PROFILES_ACTIVE: "production"` in the k8s base deployment never matches any `application-*.yml` (the real profile is `prod`). Consequence: the entire prod profile silently never loads, and every `@Profile("!prod")` bean — including Swagger via `OpenApiConfig` — stays active in production.

This plan:
1. Adds a startup fail-fast validator so an unknown profile can never silently boot again (runs for both `main()` and every `@SpringBootTest` context).
2. Corrects the k8s value `production` -> `prod` and refreshes the docs-freshness metrics.
3. Creates a real `application-dev.yml` so the compose `dev` profile resolves to a document.
4. Proves all three at container + live-stack level.

Purpose: Close a P0 production-security misconfiguration and make the failure mode loud instead of silent.
Output: One validator + tests + dev yml (commit 1), k8s + docs fix (commit 2), container/live proof (no commit).
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
</execution_context>

<context>
@.planning/STATE.md
@./CLAUDE.md

<interfaces>
<!-- Contracts and conventions the executor needs. Extracted from the codebase — no exploration required. -->

Known profile set (source of truth = on-disk application-*.yml + @Profile annotations):
  local, dev, test, staging, prod
  On-disk ymls: application.yml (base), application-local.yml, application-prod.yml,
  application-staging.yml, application-test.yml. NOTE: application-dev.yml does NOT
  exist yet — Task 1 creates it. `dev` is nonetheless a real annotation-backed profile:
    - TenantFilter            @Profile({"dev","local","test"})
    - DevTenantController     @Profile({"dev","local"})
    - SecurityHeadersDevProfileTest  @ActiveProfiles({"dev","test"})

Existing test profiles (all inside the known set — the validator must NOT break these):
  @ActiveProfiles("test")                 x25 files
  @ActiveProfiles({"dev","test"})         SecurityHeadersDevProfileTest  (@SpringBootTest, boots a context)
  @ActiveProfiles({"prod","test"})        SecurityHeadersProdProfileTest (@SpringBootTest, boots a context)
  (many tests boot with NO @ActiveProfiles -> empty/default, which must also pass)

Existing fail-fast precedent to mirror for style (logging banner + clear exception):
  uk.jtoye.core.config.DatabaseConfigurationValidator
  (uses @EventListener(ApplicationReadyEvent) + a nested RuntimeException subtype; NOTE
  that validator runs AFTER context ready — too late for a bad profile, hence this plan
  uses EnvironmentPostProcessor which runs at ApplicationEnvironmentPreparedEvent.)

Why EnvironmentPostProcessor (not an ApplicationListener added in main()):
  A listener added programmatically in CoreApplication.main only runs when main() runs, so
  @SpringBootTest contexts would skip it. An EnvironmentPostProcessor registered in
  META-INF/spring.factories under key
    org.springframework.boot.env.EnvironmentPostProcessor
  is auto-discovered by SpringApplication for EVERY boot — including @SpringBootTest — so the
  {dev,test} and {prod,test} context tests exercise it and act as a built-in regression net.

Gradle test task facts:
  - build dir is redirected: core-java/build.gradle.kts -> layout.buildDirectory.set(file("build-local"))
    so `./gradlew :core-java:test` writes to core-java/build-local (sanmi-owned). The root-owned
    core-java/build is legacy/unused — do NOT touch it; no docker workaround needed for unit tests.
  - `tasks.test` runs JUnit Platform and excludeTags("testcontainers"). The new plain-JUnit test
    (no @Tag) and the two @SpringBootTest profile-combo tests all run in the fast `:core-java:test`.
  - CI runs fast unit tests via `./gradlew :core-java:test --no-daemon` (job "test" in ci-cd.yaml).
  - Run gradle from the repo root; use the ./gradlew wrapper.

docs-freshness facts (docs/metrics.json is the CI-gated source of truth):
  - Current: java_test_methods=495, java_test_files=75, total_logical_invocations=694
    (694 = 495 java + 102 jest + 74 go + 23 playwright).
  - The new test FILE adds N @Test methods (N=4-6): java_test_methods +N, java_test_files 76,
    total_logical_invocations 694+N. NOTHING else moves (the validator class is not a *Controller
    and is not under src/test; application-dev.yml is not counted).
  - Regenerate with `scripts/docs-freshness.sh --write`; gate with `scripts/docs-freshness.sh` (exit 0).
  - CLAUDE.md count paragraph currently reads "692 logical invocations ... (495 Java @Test methods
    across 75 files + 100 Jest it/test blocks across 17 files + ...)". It is ALSO stale vs metrics.json
    (jest is 102/18, total 694 after PR #120). Update it to fully match the regenerated metrics.json.

Live-stack facts:
  - Image (compose project jtoye_oaas_2026): jtoye_oaas_2026-core-java:latest
  - Running container: jtoye_oaas_2026-core-java-1, host port 9090 -> 9090
  - Frontend is a SEPARATE compose file/container: jtoye-frontend on host 3100 (docker-compose.frontend-3100.yml)
  - Unauthenticated health path (used by the container HEALTHCHECK): /actuator/health -> {"status":"UP"}
  - Rebuild core-java: docker compose -f docker-compose.full-stack.yml build core-java
  - Recreate only core-java: docker compose -f docker-compose.full-stack.yml up -d --no-deps --force-recreate core-java
</interfaces>
</context>

<tasks>

<task type="auto" tdd="true">
  <name>Task 1: Fail-fast active-profile validator + plain-JUnit tests + application-dev.yml</name>
  <files>core-java/src/main/java/uk/jtoye/core/config/ActiveProfileValidator.java, core-java/src/main/resources/META-INF/spring.factories, core-java/src/test/java/uk/jtoye/core/config/ActiveProfileValidatorTest.java, core-java/src/main/resources/application-dev.yml</files>
  <behavior>
    Pure validation rule (unit-tested directly, no Spring context):
    - Empty active-profile array -> allowed (Spring "default" profile; many tests boot this way).
    - Every active profile must be an EXACT (case-sensitive) member of the known set
      {local, dev, test, staging, prod}. Case-sensitive because only exact names activate their
      application-<name>.yml and @Profile beans; "Prod"/"PRODUCTION" would silently do nothing.
    - On any unknown profile -> throw IllegalStateException whose message names the offending
      profile AND lists the full valid set (and mentions SPRING_PROFILES_ACTIVE).
    Test cases (4-6 @Test methods, one new file, call the validate method directly):
    - Test 1: single valid profile "prod" -> no exception.
    - Test 2: valid combos {"prod","test"} and {"dev","test"} -> no exception.
    - Test 3: empty array (default) -> no exception.
    - Test 4: "production" -> throws; message contains "production" AND the valid names
      (assert on "local","dev","test","staging","prod" substrings).
    - Test 5: case variant "Prod" (or "PRODUCTION") -> throws (proves case-sensitivity).
    - Test 6: mixed {"prod","bogus"} -> throws, message names "bogus".
  </behavior>
  <action>
Create ActiveProfileValidator in package uk.jtoye.core.config implementing
org.springframework.boot.env.EnvironmentPostProcessor. Declare the known set as ONE private
static final constant (a Set of the five names) with a comment stating it is the single source
of truth tied to the application-*.yml files and the @Profile annotations, and that it varies
with the codebase (not the environment), so it is an acceptable hardcoded constant. Implement
postProcessEnvironment(ConfigurableEnvironment, SpringApplication) to read
environment.getActiveProfiles() and delegate to a package-visible static method
validate(String[] activeProfiles) that holds the pure rule described in <behavior>. Keep
validate free of any Spring types so ActiveProfileValidatorTest can call it with plain arrays.
Model the exception style on DatabaseConfigurationValidator (clear, actionable message); throwing
from postProcessEnvironment at ApplicationEnvironmentPreparedEvent aborts startup with a non-zero
exit BEFORE bean/DB init — that is the intended fail-fast (do NOT swallow it). Fixes Issue #78
[P0-2] silent-boot half.

Register it in a NEW file core-java/src/main/resources/META-INF/spring.factories under the key
org.springframework.boot.env.EnvironmentPostProcessor mapped to the fully-qualified
uk.jtoye.core.config.ActiveProfileValidator. This registration is what makes it run for both
CoreApplication.main and every @SpringBootTest boot (so {dev,test}/{prod,test} context tests
exercise it).

Create ActiveProfileValidatorTest in core-java/src/test/java/uk/jtoye/core/config/ as plain JUnit
5 (org.junit.jupiter.api.Test + AssertJ, matching TenantIsolationProfileGatingTest style — NO
Spring context, NO @Tag). Implement the 4-6 cases from <behavior>, calling
ActiveProfileValidator.validate(...) directly and asserting on thrown message substrings with
AssertJ assertThatThrownBy / assertThatCode.

Create core-java/src/main/resources/application-dev.yml as a behavior-neutral document: a header
comment explaining that `dev` is the compose in-container profile whose live behavior lives mainly
in @Profile({"dev",...}) annotations (TenantFilter, DevTenantController), that all effective dev
config values come from the base application.yml env-var defaults, and that this file exists so
SPRING_PROFILES_ACTIVE=dev resolves to a real document rather than a phantom profile. Do NOT add
any key that differs from base effective values — a comment-only document is acceptable and
preferred; if a non-empty document is desired, use only a value identical to base (e.g.
logging.level.uk.jtoye: INFO which already equals the base LOG_LEVEL default).

Do NOT run scripts/docs-freshness.sh --write here — the metrics/CLAUDE.md refresh caused by the new
test file is deliberately committed in Task 2. This task's commit will momentarily leave metrics.json
stale; that is expected and resolved by Task 2 before push.
  </action>
  <verify>
    <automated>./gradlew :core-java:test --no-daemon --tests 'uk.jtoye.core.config.ActiveProfileValidatorTest' --tests 'uk.jtoye.core.security.SecurityHeadersDevProfileTest' --tests 'uk.jtoye.core.security.SecurityHeadersProdProfileTest' && ./gradlew :core-java:test --no-daemon</automated>
  </verify>
  <done>ActiveProfileValidator + spring.factories + application-dev.yml + ActiveProfileValidatorTest (4-6 @Test) exist; full `:core-java:test` is green (including the {dev,test} and {prod,test} @SpringBootTest contexts); committed as one atomic commit, e.g. `feat(config): fail-fast on unknown Spring profile + application-dev.yml (#78)`.</done>
</task>

<task type="auto">
  <name>Task 2: Correct k8s profile value + regenerate metrics + sync CLAUDE.md counts</name>
  <files>k8s/base/core-java-deployment.yaml, docs/metrics.json, CLAUDE.md</files>
  <action>
In k8s/base/core-java-deployment.yaml change the SPRING_PROFILES_ACTIVE env value from "production"
to "prod" (the pair is around lines 52-53). This is the core Issue #78 [P0-2] fix — it makes
application-prod.yml load and disables the @Profile("!prod") beans (Swagger/OpenApiConfig) in
production. There are no k8s overlays that set SPRING_PROFILES_ACTIVE (verified: only
k8s/base/core-java-deployment.yaml references it), so no other file needs editing — but re-confirm
with the grep in <verify> and fix any additional occurrence if one appears.

Regenerate the docs metrics for the new test file added in Task 1: run
`scripts/docs-freshness.sh --write`. Confirm the diff moved ONLY java_test_methods (+N, N=4-6),
java_test_files (75 -> 76), and total_logical_invocations (694 -> 694+N); every other field
(java_controllers=14, schema_version=39, go_*, jest_*, playwright_*) must be unchanged. If any
other field moved, stop and investigate before committing.

Update the count paragraph in CLAUDE.md (the "project standard is ... logical invocations passing
(...)" sentence) so every number matches the regenerated docs/metrics.json: Java methods = 495+N
across 76 files, Jest = 102 blocks across 18 files, Go = 74 across 8 files, Playwright = 23 across
5 specs, total = 694+N. (This also clears the pre-existing CLAUDE.md drift where it still read 692 /
100 jest / 17 files.)
  </action>
  <verify>
    <automated>! grep -rn 'SPRING_PROFILES_ACTIVE' k8s | grep -i 'production' && grep -n 'value: "prod"' k8s/base/core-java-deployment.yaml && scripts/docs-freshness.sh</automated>
  </verify>
  <done>k8s SPRING_PROFILES_ACTIVE is "prod" and no "production" value remains anywhere under k8s; `scripts/docs-freshness.sh` exits 0; CLAUDE.md count paragraph matches metrics.json; committed as one atomic commit, e.g. `fix(k8s): SPRING_PROFILES_ACTIVE production->prod + refresh test counts (#78)`.</done>
</task>

<task type="auto">
  <name>Task 3: Container + live-stack proof (no commit)</name>
  <files>(no source changes — verification only; capture and report all output)</files>
  <action>
Do NOT commit anything in this task. First, protect rollback: tag the current (main) image as a
backup before rebuilding, e.g. `docker tag jtoye_oaas_2026-core-java:latest jtoye_oaas_2026-core-java:pre-78`.

Rebuild the core-java image with the new validator:
`docker compose -f docker-compose.full-stack.yml build core-java`
(produces jtoye_oaas_2026-core-java:latest). Run all commands from the repo root so the compose
project name resolves to jtoye_oaas_2026 (matching the running stack).

Negative proof (fail-fast): run `docker run --rm -e SPRING_PROFILES_ACTIVE=production
jtoye_oaas_2026-core-java:latest`. Expect a NON-ZERO exit and stdout/stderr containing the
validator's error naming 'production' AND the valid set. Capture the exact matching lines and the
exit code.

Positive prod-name proof: run `docker run --rm -e SPRING_PROFILES_ACTIVE=prod
jtoye_oaas_2026-core-java:latest`. Expect the log line "The following profiles are active" showing
`prod` to appear BEFORE any environment failure. A subsequent failure (DB unreachable / missing
prod env) is expected and fine — it only needs to occur AFTER the profiles-active line. Capture the
profiles-active line and the first failure line to show ordering.

Live-stack proof (dev profile): recreate only the core-java service:
`docker compose -f docker-compose.full-stack.yml up -d --no-deps --force-recreate core-java`.
Then verify: (a) container health reaches "healthy" via
`docker inspect --format '{{.State.Health.Status}}' jtoye_oaas_2026-core-java-1`; (b) the
unauthenticated health endpoint returns UP via `curl -fsS http://localhost:9090/actuator/health`;
(c) the frontend still serves via `curl -fsI http://localhost:3100` (HTTP 200). Capture all three.

Rollback safety: if the rebuild or recreate leaves core-java unhealthy, restore main's image
(`docker tag jtoye_oaas_2026-core-java:pre-78 jtoye_oaas_2026-core-java:latest` then re-run the
force-recreate) and surface the failure with the captured logs — do NOT leave the live stack broken.
  </action>
  <verify>
    <automated>docker run --rm -e SPRING_PROFILES_ACTIVE=production jtoye_oaas_2026-core-java:latest 2>&1 | grep -iE "production|valid profile"; test ${PIPESTATUS[0]:-1} -ne 0; docker run --rm -e SPRING_PROFILES_ACTIVE=prod jtoye_oaas_2026-core-java:latest 2>&1 | grep -m1 "The following profiles are active"; curl -fsS http://localhost:9090/actuator/health; test "$(docker inspect --format '{{.State.Health.Status}}' jtoye_oaas_2026-core-java-1)" = healthy</automated>
  </verify>
  <done>Captured evidence shows: (1) `=production` exits non-zero with a message naming 'production' + the valid set; (2) `=prod` logs the profiles-active line with prod before any env failure; (3) live core-java container recreated under dev profile is `healthy`, /actuator/health is UP, and frontend :3100 still returns 200. No commit made; backup image tag exists for rollback.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| deployment config -> app profile resolution | The `SPRING_PROFILES_ACTIVE` env value (set in k8s / compose) decides which `application-*.yml` and which `@Profile` beans load. A value that matches no profile silently downgrades security posture. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation Plan |
|-----------|----------|-----------|-------------|-----------------|
| T-78-01 | Information Disclosure | OpenApiConfig (`@Profile("!prod")`) + prod actuator exposure | mitigate | Fix k8s value to `prod` (Task 2) so application-prod.yml loads and Swagger/`!prod` beans deactivate; ActiveProfileValidator (Task 1) makes any future unknown value fail-fast instead of silently exposing them. |
| T-78-02 | Elevation of Privilege | prod hardening in application-prod.yml never applied under a phantom profile | mitigate | Fail-fast validator prevents boot on any profile outside {local,dev,test,staging,prod}; container negative-proof (Task 3) confirms `production` cannot boot. |
| T-78-03 | Tampering | Case-variant / typo profile (e.g. `Prod`) silently no-ops | mitigate | Validator uses exact case-sensitive matching; unit test asserts `Prod`/`PRODUCTION` is rejected. |
| T-78-04 | Denial of Service | Fail-fast rejects a legitimate but newly-added profile | accept | Known set is a single documented constant tied to on-disk ymls; adding a profile requires updating both the yml and the constant — an intended, reviewed coupling. Low risk, dev-time only. |
| T-78-SC | Tampering | supply-chain / package installs | accept | No new npm/pip/cargo installs and no base-image changes in this plan; nothing to audit. |
</threat_model>

<verification>
- `./gradlew :core-java:test --no-daemon` green, including the {dev,test} and {prod,test} `@SpringBootTest` contexts and the new `ActiveProfileValidatorTest`.
- `scripts/docs-freshness.sh` exits 0; metrics.json moved only java_test_methods/java_test_files/total.
- No `SPRING_PROFILES_ACTIVE` value `production` anywhere under `k8s/`.
- Container: `=production` exits non-zero with a clear message; `=prod` logs profiles-active before any env failure.
- Live: core-java recreated under `dev` is `healthy`, `/actuator/health` UP, frontend :3100 returns 200.
</verification>

<success_criteria>
- Issue #78 [P0-2] closed: prod deploys load `application-prod.yml`; `@Profile("!prod")` beans (incl. Swagger) are disabled in prod.
- Unknown/misconfigured profiles fail fast at startup with a message naming the offending profile and the valid set — no more silent boots.
- Compose `dev` profile resolves to a real `application-dev.yml`; live behavior unchanged.
- Two atomic commits (Task 1, Task 2) with conventional prefixes and NO Co-Authored-By trailers; Task 3 is verification-only (no commit).
- CI-gated docs-freshness passes; CLAUDE.md counts consistent with metrics.json.
</success_criteria>

<output>
No SUMMARY required (quick mode). Report the captured Task 3 evidence (exit codes, matched log lines, health status, curl results) back to the orchestrator on completion.
</output>
