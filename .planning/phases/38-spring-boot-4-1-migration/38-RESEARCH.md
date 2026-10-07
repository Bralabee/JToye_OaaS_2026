# Phase 38: Spring Boot 4.1 Migration - Research

**Researched:** 2026-10-04
**Domain:** JVM framework major upgrade (Spring Boot 3.5.16 -> 4.1.1: Framework 7, Security 7, Jackson 3, Hibernate 7, Flyway 12, Spring AMQP 4, Spring Data 2026.0, JUnit 6) on a multi-tenant RLS service
**Confidence:** HIGH for the version/coordinate facts and the change surface (read from the Boot 4.1.1 BOM, Maven Central and the spike); MEDIUM for Jackson-3 wire-compatibility behaviour (reasoned from documented default changes, must be measured); flagged items in the Assumptions Log.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
Copied verbatim from `38-CONTEXT.md` `## Implementation Decisions (owner, 2026-10-01)`:

- **D-01 Jackson 3 now.** Move the main code to Jackson 3 (`tools.jackson`) and do not set the
  deprecated `spring.http.converters.preferred-json-mapper=jackson2`. That covers the 14 main
  classes that inject the Jackson-2 `ObjectMapper`, `KeycloakAdminClient`, and the Rabbit
  (`Jackson2JsonMessageConverter`) and Redis (`GenericJackson2JsonRedisSerializer`) serializers,
  which are deprecated for removal. None of the main classes use Jackson-2 databind annotations.
  - Keep a Jackson-2 BOM floor pin only for transitive users (springdoc, the Azure SDK, Stripe and
    others).
  - The Keycloak wire body must be asserted BY CONTENT (`{"enabled":false}`).
  - **Hazard:** a cached value or a queued message written in the Jackson-2 shape must still be
    readable after the switch. Prove it for Redis cache entries and in-flight AMQP messages, or
    flush them as a recorded deploy step.
- **D-02 Explicit per-module starters,** not `spring-boot-starter-classic` /
  `spring-boot-starter-test-classic`. Classic pulls in Boot's gRPC autoconfig modules without gRPC
  itself, and on any filter chain with CSRF enabled that throws `NoClassDefFoundError`. Declare
  each module the app actually uses:
  - webmvc, data-jpa, flyway, aspectj, security, oauth2-resource-server, validation, actuator,
    data-redis, cache, amqp, websocket, mail, webflux, and the test slices.
  - **Flyway liveness must be proven:** migrations applied > 0, and a break arm in which removing
    the starter turns the RLS tests red.
- **D-03 Keep spring-statemachine 4.0.2,** and add `org.springframework.security:spring-security-access`
  for its 24 latent `org.springframework.security.access.*` references.
  - Record the risk: the library has no Framework-7 release, so we run it on a framework it was not
    built for.
  - Replacing it with an `EnumMap` transition table (~60–80 LOC per machine, with the existing
    tests as the spec) is a separate, non-blocking decision.
- **D-04 Keep a plain `WWW-Authenticate: Bearer` on 401.** Customise the Security 7 entry point
  (`ProblemDetailAuthenticationEntryPoint`) so the API never advertises a `resource_metadata` URL
  that it does not serve. RFC 9728 metadata is a possible agent-readiness item later, and
  explicitly out of scope here.

### Claude's Discretion
- Tomcat 11.0.25 vs 11.0.26 (take the newer one if it resolves cleanly).
- Whether the Jackson-2 floor moves to 2.22.1.
- OpenAPI: regenerate the snapshot. Each newly-`required` field must already be enforced
  server-side (a `@NotNull` / `@NotBlank` that a request without it fails with 400 today); prove
  that per field before adding a breaking-change-gate exception. Then record the exception the way
  the gate's own docs prescribe.
- Plan count and wave structure.

### Standing requirements (from CLAUDE.md, not negotiable) — copied verbatim
- **A gate for the 18-key class.** Add an executable check that fails CI on any `spring.*`,
  `management.*`, `server.*` or `logging.*` key in `application*.yml` that the Boot configuration
  metadata does not know. It must be proven FAILING on an injected bogus key and wired into CI
  (`check-gate-enforcement.sh`).
- **Falsifiable evidence.** Every acceptance criterion is observed failing before it is trusted.
- **Runtime parity.** Rebuild ALL containers. Read the Boot version and `application.yml` out of
  the running `/app/app.jar`. `check-runtime-freshness.sh` and `check-branch-behind-base.sh` must
  pass.
- **Real paths.** Exercise the real path: a live Keycloak offboard against the compose stack
  (user disabled in Keycloak, by content). The nightly E2E must be green on the branch's runtime.
- **Docs and metrics.** `docs/metrics.json` is regenerated only if test counts change. Update the
  version claims in CLAUDE.md, AGENTS.md (outside the ORGOS block; the charters went version-free
  in jtoye-orgos#35) and `.planning/codebase/STACK.md`.
- **Bracketed break arms.** Commit before the arms run, verify each restore by content, and finish
  with a clean run.

### Deferred Ideas (OUT OF SCOPE)
CONTEXT.md has no `## Deferred Ideas` section. Its `<domain>` block states **Not in scope:**
"edge-go, frontend, mcp-server (no Boot dependency); replacing spring-statemachine (D-03); serving
RFC 9728 protected-resource metadata (D-04); any behaviour change to an endpoint beyond what the
framework move forces."
</user_constraints>

<phase_requirements>
## Phase Requirements (PROPOSED — REQUIREMENTS.md has no Phase 38 IDs yet)

ROADMAP.md says "Requirements: TBD (derived at plan time from 38-CONTEXT.md)". Proposed scheme
`BOOT4-01..BOOT4-14`, one per decision/defect/gate so every plan maps to at least one. The planner
should add these to `.planning/REQUIREMENTS.md` (and the traceability table) in the first plan.

| ID | Description | Source | Research Support |
|----|-------------|--------|------------------|
| BOOT4-01 | core-java builds and runs on Spring Boot 4.1.1 (plugin in BOTH `build.gradle.kts:2` and `core-java/build.gradle.kts:2`) | #706 | Standard Stack; spike §1 |
| BOOT4-02 | Explicit per-module starters only: no `spring-boot-starter-classic`, `-test-classic`, `spring-boot-jackson2`, or `spring-boot-grpc-*` on any classpath; an autoconfiguration census shows no capability silently lost | D-02 | Pattern 1, Pitfall 1/2 |
| BOOT4-03 | Flyway liveness: migrations applied > 0 in the integration suite; break arm (starter removed) turns `RlsContractTest` red | D-02 | Pattern 1; spike §2 |
| BOOT4-04 | Main code on Jackson 3 only: zero `com.fasterxml.jackson.databind`/`.core` imports under `core-java/src/main` (annotations from `com.fasterxml.jackson.annotation` remain legal), no `preferred-json-mapper=jackson2` | D-01 | Pattern 2; inventory below |
| BOOT4-05 | `KeycloakAdminClient.setUserEnabled` sends a body whose parsed content has `enabled=false` and the user's other fields, and none of the garbage keys (`nodeType`, `array`, …); proven in a unit test AND by a live compose offboard read back from Keycloak | D-01 defect 1 | Pattern 3 |
| BOOT4-06 | AMQP converter on `JacksonJsonMessageConverter` with the SAME exact-match trusted-package list; a message written by the Jackson-2 converter is consumed by the Jackson-3 one | D-01 hazard | Pattern 4 |
| BOOT4-07 | Redis cache serializer on `GenericJacksonJsonRedisSerializer` with an explicit `PolymorphicTypeValidator` allowlist re-derived from LIVE cache bytes; Jackson-2-era entries are never read as valid (versioned key prefix or recorded flush) | D-01 hazard; SEC-4 | Pattern 5 |
| BOOT4-08 | Idempotency continuity: a request reserved under Jackson 2 and retried after deploy is not misjudged "same key, different body" (422), or the gap is a recorded, bounded deploy step | D-01 hazard (found in research) | Pitfall 4 |
| BOOT4-09 | 401s carry `WWW-Authenticate: Bearer` with no `resource_metadata`; the disposition of the Security-7 `/.well-known/oauth-protected-resource` endpoint is decided and asserted | D-04 | Pattern 6, Open Question 1 |
| BOOT4-10 | spring-statemachine 4.0.2 kept, `spring-security-access` added, risk recorded; the 28 state-machine tests + transition-driving integration classes green with break arms | D-03 | spike §4 |
| BOOT4-11 | The 18 silently-ignored keys are renamed (or deliberately deleted) AND a new unknown/removed-key gate fails CI on a bogus key; `spring.autoconfigure.exclude` values are validated too | Standing req | Pattern 7, Pitfall 3 |
| BOOT4-12 | CVE floors on the Boot-4 lines: netty pin removed, Tomcat 11.0.26, amqp-client 5.34.0 kept, Jackson 2 floor re-keyed (`jackson-2-bom.version`), Jackson 3 floor `jackson-bom.version` >= 3.1.7; a LOCAL Trivy 0.70.0 image scan of the Boot-4 jar exits 0 | defect 3 | CVE section |
| BOOT4-13 | OpenAPI snapshot regenerated; each newly `required` field proven enforced server-side (400 without it) BEFORE the snapshot is accepted; edge/MCP snapshot consumers still green | Discretion | OpenAPI section |
| BOOT4-14 | Docs, gates and runtime parity: version claims, horizons row, citations, metrics; all images rebuilt; Boot version and yml read from the running `/app/app.jar`; freshness + branch gates pass; nightly E2E green; tracing and Prometheus proven live | Standing req | Runtime/doc sections |
</phase_requirements>

## Summary

The spike (38-SPIKE.md) already proved the move is feasible on Boot 4.1.1 — but on the CLASSIC
starter route, which D-02 reverses, and with Jackson 2 still auto-configured, which D-01 reverses.
Both reversals widen the change surface beyond what the spike measured, and both convert some
silent failures into loud ones (good) while opening new silent ones (the planning risk):

1. **Explicit starters remove modules the spike had for free.** In Boot 4 each auto-configuration
   lives in its own module, and a missing module is silent. Verified from the 4.1.1 starter POMs:
   tracing needs `spring-boot-starter-zipkin` (the current `micrometer-tracing-bridge-brave` +
   `zipkin-reporter-brave` deps alone will NOT activate tracing); `RestClient.Builder` /
   `RestTemplateBuilder` need `spring-boot-starter-restclient`; the injected `WebClient.Builder`
   needs `spring-boot-starter-webclient` (`spring-boot-starter-webflux` does NOT contain
   `spring-boot-webclient`); `@WebMvcTest`/MockMvc need `spring-boot-starter-webmvc-test`. The
   remedy is a mechanical census: diff the positive-match auto-configuration report of the
   explicit-starter build against a classic-starter build of the same commit.
2. **Removing `spring-boot-jackson2` removes the Jackson-2 `ObjectMapper` bean.** 22 main files
   and 47 test files touch Jackson 2; 25 test files `@Autowired` a Jackson-2 `ObjectMapper` and will
   fail context start until migrated. Jackson 3 also changes DEFAULTS that alter bytes on the
   wire: `SORT_PROPERTIES_ALPHABETICALLY` on, `WRITE_ENUMS_USING_TO_STRING` /
   `READ_ENUMS_USING_TO_STRING` on, `FAIL_ON_NULL_FOR_PRIMITIVES` and `FAIL_ON_TRAILING_TOKENS` on,
   `DefaultTyping.EVERYTHING` removed. Research found a persisted-state hazard D-01 does not list:
   `IdempotencyService` stores `sha256(objectMapper.writeValueAsString(request))`, so a byte-level
   serialisation change makes a pre-deploy idempotency key look like "same key, different body".
3. **D-04's premise is partly wrong.** Spring Security 7.1's `OAuth2ResourceServerConfigurer`
   adds `OAuth2ProtectedResourceMetadataFilter` UNCONDITIONALLY (no disable switch), so
   `/.well-known/oauth-protected-resource` IS served, and by default it claims
   `tls_client_certificate_bound_access_tokens: true`, which this API does not do. Stripping the
   header is straightforward; what to do with the endpoint is an owner question (Open Question 1).

**Primary recommendation:** Build it in four waves — (W1) build + explicit starters + mechanical
package/test moves with a classic-vs-explicit auto-config census and the Flyway arm; (W2) Jackson 3
in main and tests, with the Keycloak/AMQP/Redis/idempotency compatibility proofs; (W3) Security 7
entry point, config-key renames + the new key gate, CVE pins + local Trivy, OpenAPI; (W4) docs,
horizons, metrics, full rebuild and runtime parity, live Keycloak offboard, nightly E2E.

## Project Constraints (from CLAUDE.md)

Project `CLAUDE.md` and the user's global `~/.claude/CLAUDE.md` directives that bind this phase:
- **Stack:** "Must use existing stack — Spring Boot 3.5.16 …" — this phase is the sanctioned exception (#706); update the claim, not the rule. JDK 25 Temurin, Gradle 9.7.1 unchanged.
- **Multi-tenancy:** all new code respects RLS and TenantContext; integration proofs touching tenant data run under the NOSUPERUSER role (a SUPERUSER bypasses FORCE RLS — see V67's history).
- **Testing:** all new code needs tests; counts live in `docs/metrics.json`, enforced by `docs-freshness.sh` + `check-doc-metrics.sh` (regenerate with `--write` when counts change; literal `it(`/`test(` counting).
- **Docker:** rebuild ALL containers after code changes before E2E; `docker compose start` never rebuilds; read config out of `/app/app.jar` (fat jar — `unzip -p`, not `find`).
- **Falsifiable evidence:** every acceptance criterion shown FAILING on a broken input and PASSING on the real tree, both outputs recorded; missing tooling / empty output = VOID (exit 2), never pass; `grep -q` under pipefail inverts — use here-strings; `rg -uu` for evidence; capture `rc` on the same line.
- **Runtime parity:** `scripts/check-runtime-freshness.sh` and `scripts/check-branch-behind-base.sh` must pass; `.Metadata.LastTagTime`, not `.Created`.
- **Break arms:** commit before arms; clean → arms → clean; verify restores by content (hash), never `git diff --stat`.
- **Agent-readiness:** OpenAPI must match live responses (`check-openapi-snapshot-fresh.sh`); RFC 7807 errors (the 401 body included) stay typed.
- **Security:** every plan carries a `<threat_model>` (ASVS; config here says level 2, block on medium).
- **Git:** feature branch (`phase-37-spring-boot-4-1` today), PR, CI; no AI-attribution trailers or footers anywhere; review rounds narrow (D3).
- **Incremental Betterment:** enumerate any displaced good (e.g. prod log retention, Zipkin endpoint, staging error detail) and preserve it.
- **GSD:** SUMMARY.md is the completion marker; `state.record-session`/`update-progress` corrupt STATE.md — hand-edit.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Framework/BOM/starter selection | API / Backend (core-java build) | — | Only core-java depends on Boot |
| JSON (de)serialisation of REST bodies | API / Backend | — | Boot-configured Jackson 3 `JsonMapper` via MVC converters |
| Event payloads on RabbitMQ | API / Backend | Message broker | Producer and consumer are both core-java; broker only stores bytes, but in-flight bytes span the deploy |
| Cached DTOs | Database / Storage (Redis) | API / Backend | Bytes written by one serializer version are read by the next |
| Idempotency fingerprints / stored responses | Database / Storage (Postgres `idempotency_keys`, FORCE RLS) | API / Backend | Hash computed in Java, persisted across the deploy |
| 401/403 challenges, RFC 9728 endpoint | API / Backend (Security filter chain) | — | Entry point + filter order |
| Keycloak deprovisioning | API / Backend | External IdP | Outbound admin REST call; truth read back from Keycloak |
| Config-key validity gate | CI (static check over the build's runtime classpath) | — | Metadata ships in the jars |
| Schema migrations / RLS | Database / Storage | API / Backend (Flyway module) | Migrations only run if the Flyway auto-config module is present |
| CVE floors | Build (dependency management) | CI image gate | Trivy scans the image, which only runs post-merge |

## Standard Stack

### Core (managed by the Boot 4.1.1 BOM unless pinned)

Read from `spring-boot-dependencies-4.1.1.pom` fetched from Maven Central this session
[VERIFIED: repo1.maven.org spring-boot-dependencies-4.1.1.pom, lines 58-215]:
`<flyway.version>12.4.0`, `<hibernate.version>7.4.5.Final`, `<jackson-2-bom.version>2.21.5`,
`<jackson-bom.version>3.1.5`, `<junit-jupiter.version>6.0.3`, `<lettuce.version>7.5.2.RELEASE`,
`<micrometer.version>1.17.1`, `<micrometer-tracing.version>1.7.1`, `<netty.version>4.2.17.Final`,
`<postgresql.version>42.7.13`, `<rabbit-amqp-client.version>5.30.0`, `<reactor-bom.version>2025.0.7`,
`<spring-amqp.version>4.1.1`, `<spring-data-bom.version>2026.0.1`, `<spring-framework.version>7.0.9`,
`<spring-security.version>7.1.1`, `<testcontainers.version>2.0.5`, `<tomcat.version>11.0.24`.

(Note: CONTEXT.md says "Spring Data 2025.1"; the 4.1.1 BOM manages **2026.0.1**.)

| Library | Version | Purpose | Why |
|---------|---------|---------|-----|
| `org.springframework.boot` Gradle plugin | 4.1.1 | Boot 4.1 line; latest 4.1.x patch on Central (4.2.0-M2 is a milestone) [VERIFIED: Maven Central metadata] | 4.1 OSS EOL 2027-07-31, extended 2028-07-31 [VERIFIED: endoflife.date API] |
| `io.spring.dependency-management` | 1.1.7 (unchanged) | BOM import + `extra[...]` overrides | Spike: works with 4.1.1 [CITED: 38-SPIKE.md §1] |
| `spring-statemachine-starter` | 4.0.2 (latest on Central) | Order + onboarding machines | D-03 [VERIFIED: Maven Central] |
| `org.springframework.security:spring-security-access` | 7.1.1 — declare WITHOUT a version: it is managed by `spring-security-bom` 7.1.1, which the Boot 4.1.1 BOM imports (BOM l.3577) | Provides the `org.springframework.security.access.*` classes statemachine-core references | D-03 [VERIFIED: spring-security-bom-7.1.1.pom lists it; Central lists 7.1.1] |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 3.1.1 | OpenAPI on Boot 4 (2.8.6 is Boot-3 only) | #739 [VERIFIED: Maven Central latest 3.1.1] |
| `io.github.resilience4j:resilience4j-spring-boot4` | 2.4.0 | Replaces `resilience4j-spring-boot3` | Only release of the boot4 artifact [VERIFIED: Maven Central] |
| `org.springframework.retry:spring-retry` | 2.0.13 (explicit — no longer BOM-managed) | `@Retryable` used by ConcurrentStockDecrement path | [CITED: 38-SPIKE.md §1]; latest on Central [VERIFIED] |

### Explicit starter set (D-02) — derived from the app's actual usage

Starter contents verified from the 4.1.1 POMs on Central this session:

| Starter | Brings | Why this app needs it |
|---------|--------|----------------------|
| `spring-boot-starter-webmvc` | `spring-boot-starter-jackson`, `-tomcat`, `spring-boot-http-converter`, `spring-boot-webmvc` | Servlet API (replaces `-web`) |
| `spring-boot-starter-data-jpa` | `-jdbc`, `spring-boot-data-jpa` | JPA + Envers |
| `spring-boot-starter-flyway` | `-jdbc`, `spring-boot-flyway` | **Without it 0 migrations run** (spike arm). Keep `flyway-database-postgresql` explicit |
| `spring-boot-starter-aspectj` | — | Replaces `-aop` |
| `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-resource-server` | `spring-boot-security-oauth2-resource-server` | JWT resource server (new name; old `-oauth2-resource-server` is the deprecated alias) |
| `spring-boot-starter-validation`, `-actuator` (brings `-micrometer-metrics`, `spring-boot-health`), `-data-redis`, `-cache`, `-amqp`, `-websocket` (brings webmvc), `-mail` | | as today |
| **`spring-boot-starter-restclient`** | `spring-boot-restclient` | `RestClient.Builder` (KeycloakAdminClient) and `RestTemplateBuilder` (SecurityConfig, CustomerJwtVerifier) beans |
| **`spring-boot-starter-webclient`** | `spring-boot-webclient`, `reactor-netty-http` | `WebClient.Builder` bean injected by FhrsClient + WebhookDeliveryClientConfig. `-webflux` does NOT include it |
| **`spring-boot-starter-zipkin`** | `spring-boot-micrometer-tracing-brave`, `spring-boot-zipkin`, `micrometer-tracing-bridge-brave` | Tracing auto-config; without it tracing is silently off. Keep `zipkin-reporter-brave` explicit |
| Test: `spring-boot-starter-test` + per-technology `-test` starters | e.g. `spring-boot-starter-webmvc-test` (brings `spring-boot-webmvc-test`, `spring-boot-resttestclient`), `spring-boot-starter-security-test`, `spring-boot-starter-data-jpa-test` | Boot 4 test slices are per module [CITED: Boot 4.0 Migration Guide] |

**webflux vs webclient (interpretation of D-02):** D-02 lists "webflux", but its rule is "declare
each module the app actually uses". The app uses only the reactive CLIENT (no
`web.reactive.function.server`), and in Boot 4 the client auto-config is `spring-boot-webclient`.
Recommend `spring-boot-starter-webclient` IN PLACE OF `-webflux`, and record it as an application of
D-02's rule; if the planner prefers the literal list, add `-webclient` alongside `-webflux`.
Either way `WebClient.Builder` must resolve, and a missing bean fails at startup (loud).

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Explicit starters | `spring-boot-starter-classic` | Rejected by D-02 (gRPC `NoClassDefFoundError` on CSRF chains) |
| Jackson 3 | `preferred-json-mapper=jackson2` | Rejected by D-01 (deprecated) |
| `spring.jackson.use-jackson2-defaults=true` | Boot 4 property that restores Jackson-2 DEFAULTS on the Jackson-3 mapper (dates, ordering, empty beans) [CITED: Boot 4.0 Migration Guide; spring.io Jackson 3 blog] | Not forbidden by D-01 (it is not the deprecated converter switch) and would neutralise the byte-shape hazards in Pitfall 4. Recommend NOT setting it by default (it is a migration aid with known side effects, e.g. spring-boot#49951 FAIL_ON_UNKNOWN_PROPERTIES) but measure first; see Open Question 2 |
| spring-retry | Framework 7 core resilience (`@Retryable` in spring-core) | Behaviour change; out of scope |

**Installation (core-java/build.gradle.kts dependencies, sketch — versions only where not managed):**
```kotlin
implementation("org.springframework.boot:spring-boot-starter-webmvc")
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.springframework.boot:spring-boot-starter-flyway")
implementation("org.springframework.boot:spring-boot-starter-aspectj")
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
implementation("org.springframework.boot:spring-boot-starter-validation")
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
implementation("org.springframework.boot:spring-boot-starter-cache")
implementation("org.springframework.boot:spring-boot-starter-amqp")
implementation("org.springframework.boot:spring-boot-starter-websocket")
implementation("org.springframework.boot:spring-boot-starter-mail")
implementation("org.springframework.boot:spring-boot-starter-restclient")
implementation("org.springframework.boot:spring-boot-starter-webclient")
implementation("org.springframework.boot:spring-boot-starter-zipkin")
implementation("org.springframework.security:spring-security-access")
implementation("org.springframework.retry:spring-retry:2.0.13")
implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
testImplementation("org.springframework.boot:spring-boot-starter-test")
testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
testImplementation("org.springframework.boot:spring-boot-starter-security-test")
testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
testImplementation("org.springframework.boot:spring-boot-configuration-metadata") // key gate
```
The exact `-test` starter list must come from the census (Pattern 1), not from this sketch.

## CVE floors (BOOT4-12)

| Pin (key) | Today | Boot 4.1.1 manages | Action | Evidence |
|-----------|-------|--------------------|--------|----------|
| `netty.version` (`core-java/build.gradle.kts:54`, `"4.1.137.Final"`) | 4.1.137.Final | 4.2.17.Final | **REMOVE.** Forcing 4.1 under reactor-netty 2025.0.x (4.2 line) is a mismatch. 4.2.18.Final exists on Central; Trivy decides whether 4.2.17 is still clean | spike §5 [CITED]; Central [VERIFIED] |
| `tomcat.version` (`:59`, `"10.1.59"`) | 10.1.59 | 11.0.24 | **Pin `11.0.26`** (released 2026-09-15). 11.0.26 fixes, among others, "Important: Bypass of security constraints for WebSocket endpoints" (CVE-2026-76183) and "Important: Regression in fix for CVE-2026-41293 can trigger request header mix-up" (CVE-2026-86350) — both on this app's serving path (`/ws/**`) | [CITED: tomcat.apache.org/security-11.html]; Central latest 11.0.26 [VERIFIED] |
| `rabbit-amqp-client.version` (`:102`, `"5.34.0"`) | 5.34.0 | 5.30.0 | **KEEP** (key name unchanged in the 4.1.1 BOM line 181) | [VERIFIED: 4.1.1 POM] |
| `jackson-bom.version` (`:153`, `"2.21.7"`) | Jackson 2 | **names Jackson 3**, 3.1.5 | **RE-KEY** to `extra["jackson-2-bom.version"]`, and ADD `extra["jackson-bom.version"] = "3.1.7"` | 4.1.1 POM lines 82-83 [VERIFIED] |
| Jackson 2 floor value | 2.21.7 | 2.21.5 | Recommend **2.22.3**: clears CVE-2026-89407/-89425/-91776/-91777 (fixed in 2.18.11/2.21.7/2.22.3) AND stops downgrading swagger-core 2.2.55's requested databind 2.22.1. 2.21.7 also clears the CVEs but downgrades springdoc's dependency | build.gradle.kts:135-137 comment [VERIFIED: read]; spike §5 [CITED] |
| Jackson 3 floor | — | 3.1.5 | **3.1.7** — smallest release clearing CVE-2026-89407 and -89425 (tools.jackson.core:jackson-core fixed 3.1.7 / 3.2.2-3) and CVE-2026-91776 (tools.jackson.core:jackson-databind fixed 3.1.7 / 3.2.3). 3.1.7 is on Central | [CITED: GitLab advisory DB via search; Keycloak issues #53444-6]; Central [VERIFIED] |

CVE-2026-91777 on the Jackson-3 line was NOT confirmed this session — the local Trivy run decides.

**The pin comments are load-bearing documentation in this repo** (each records its measured
fail-direction). Each rewritten pin needs: the new key name proven against the 4.1.1 POM, a
`dependencyInsight --configuration runtimeClasspath` before/after, and a near-miss-key arm
(e.g. `jackson3-bom.version`) showing the version reverts to the managed one.

**Local Trivy proof (the CI image gate never runs on PRs):** `aquasec/trivy:0.70.0` is already
pulled on this host; the `trivy` CLI is not installed. Build the core-java image, then
`docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.70.0 image --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 <image>`
(same flags as `ci-cd.yaml` lines ~1551-1560). Fail-direction arm: re-point `tomcat.version` to
11.0.24 (or drop the Jackson 3 pin) and show exit 1 naming the CVE; restore; exit 0. Note the
Trivy DB moves daily — record the DB timestamp with the result.

## Package Legitimacy Audit

The GSD seam supports npm/pypi/crates only (`package-legitimacy check --ecosystem maven` →
"Usage: … <npm|pypi|crates>", run this session). Maven artifacts were audited by hand: each
groupId/artifactId is named in official Spring / project documentation and resolves on Maven
Central (metadata fetched this session).

| Package | Registry | Latest on Central | Source | Verdict | Disposition |
|---------|----------|-------------------|--------|---------|-------------|
| org.springframework.boot:spring-boot-starter-* (webmvc, flyway, aspectj, security-oauth2-resource-server, restclient, webclient, zipkin, *-test) | Maven Central | 4.1.1 | Boot 4.0 Migration Guide; 4.1.1 BOM | OK (official) | Approved |
| org.springframework.boot:spring-boot-configuration-metadata | Maven Central | 4.1.1 | Boot BOM | OK | Approved (test scope) |
| org.springframework.security:spring-security-access | Maven Central | 7.1.1 (7.2.0-M2 newer milestone) | Spring Security 7 | OK | Approved |
| org.springdoc:springdoc-openapi-starter-webmvc-ui | Maven Central | 3.1.1 | springdoc (already a dep at 2.8.6) | OK | Approved |
| io.github.resilience4j:resilience4j-spring-boot4 | Maven Central | 2.4.0 (only version) | resilience4j (same group as today's boot3 artifact) | OK | Approved |
| org.springframework.retry:spring-retry | Maven Central | 2.0.13 | already on classpath | OK | Approved |
| tools.jackson:jackson-bom | Maven Central | 3.2.3 (3.1.7 chosen) | FasterXML | OK | Approved |

**Packages removed due to [SLOP]:** none. **Flagged [SUS]:** none.

## Architecture Patterns

### System Architecture Diagram

```
 HTTP client ──► Tomcat 11 ──► Security 7 filter chain ──────────────────────────────┐
                    │            ├─ OAuth2ProtectedResourceMetadataFilter (NEW, always on)
                    │            │      GET /.well-known/oauth-protected-resource ─► 200 JSON
                    │            ├─ BearerTokenAuthenticationFilter ─fail─► ProblemDetailAuthenticationEntryPoint
                    │            │                                          (strip resource_metadata ─► "Bearer")
                    │            └─ JwtTenantFilter ─► TenantContext
                    ▼
               Spring MVC 7 ──► JacksonJsonHttpMessageConverter (Jackson 3 JsonMapper)
                    │
     ┌──────────────┼──────────────────────┬────────────────────────┬──────────────────────┐
     ▼              ▼                      ▼                        ▼                      ▼
 Services ──► Hibernate 7 / Postgres   Redis cache            RabbitMQ (AMQP 4)      Outbound HTTP
  (RLS via    (Flyway 12 runs only     GenericJacksonJson-    JacksonJsonMessage-    RestClient (Keycloak PUT
   GUC)        if spring-boot-flyway   RedisSerializer +      Converter, exact       body = Jackson 3 ObjectNode)
               module present)         PTV allowlist;         trusted packages;      WebClient (FHRS, CH, AI,
  IdempotencyService                   OLD-FORMAT ENTRIES     MESSAGES IN FLIGHT     webhooks — SSRF resolver)
  sha256(serialize(req)) ─► idempotency_keys   ◄── written    ◄── written by
     (hash written by Jackson 2 pre-deploy)     by Jackson 2   Jackson 2 pre-deploy
```

### Recommended wave structure (planner's discretion; dependency-driven)

```
W1  build + explicit starters + mechanical moves (main 6 files, tests ~99 files)
    + auto-config census (explicit vs classic) + Flyway arm          [BOOT4-01..03, 10]
W2  Jackson 3 in main (22 files) + tests (47 files) + serializers
    + Keycloak unit proof + AMQP/Redis/idempotency compatibility     [BOOT4-04..08]
W3  (parallel lanes) Security entry point │ config renames + key gate │
    CVE pins + local Trivy │ OpenAPI regen + per-field proofs        [BOOT4-09, 11..13]
W4  docs/horizons/metrics/citations; full rebuild; jar read-back;
    live Keycloak offboard; tracing/Prometheus live; nightly E2E     [BOOT4-14, 05 live]
```
W1 must produce a compiling, green tree before W2 starts: Jackson migration on a non-compiling
build cannot be measured. Note the GSD SDK ignores `wave:` frontmatter (memory: trap-gsd-wave-numbering).

### Pattern 1: Auto-configuration census (makes D-02 falsifiable)
**What:** With explicit starters, a forgotten module is silent (the spike showed 0 migrations
without `spring-boot-flyway`; the same is true for tracing, Prometheus export, health groups…).
**How:** On the same commit, boot the app context twice — once with explicit starters, once with
a throwaway `spring-boot-starter-classic` swap — and dump the positive matches of
`ConditionEvaluationReport` (or `/actuator/conditions`). Every auto-configuration positive in
classic but absent in explicit must be either intended (gRPC, unused tech) or fixed. Record the
diff as evidence. Fail-direction: remove `spring-boot-starter-zipkin` → the census names
`BraveAutoConfiguration`/Zipkin auto-config as missing.
**Plus hard liveness probes** (independent of the census): migrations applied > 0 (log line
"Successfully applied 67 migrations"), `Tracer` bean present, `PrometheusMeterRegistry` bean
present, `RestClient.Builder` and `WebClient.Builder` beans present.

### Pattern 2: Jackson 3 in main code
Inventory (read this session, `core-java/src/main/java`): 22 files import `com.fasterxml.jackson`;
by package: 19 `databind.*`, 10 `core.*` (mostly `JsonProcessingException`), 10 `annotation.*`,
2 `databind.node.*` (KeycloakAdminClient, KeycloakDeprovisionService), 2 `databind.jsontype.*` +
1 `datatype.jsr310` (CacheConfig), 1 `core.type.TypeReference` (DemoImageManifest).
- `ObjectMapper` (Jackson 2) → `tools.jackson.databind.json.JsonMapper` (or `ObjectMapper`
  from `tools.jackson.databind`); inject Boot's `JsonMapper` bean.
- `JsonProcessingException` (checked) → `tools.jackson.core.JacksonException` (unchecked).
  `catch (JsonProcessingException e)` blocks become catches of an unchecked type — review each
  for behaviour (some publishers may rely on the checked catch to dead-letter or log).
- `JavaTimeModule` → built in; `WRITE_DATES_AS_TIMESTAMPS` default off in 3 (moved to `DateTimeFeature`).
- `com.fasterxml.jackson.annotation.*` (`@JsonIgnore`, `@JsonProperty`, `@JsonIgnoreProperties`,
  `@JsonTypeInfo`) **stay** — Jackson 3 still uses the 2.x annotations package
  [CITED: Jackson 3 migration guide]. So BOOT4-04's check must allow `annotation` imports.
- `ProblemDetailAuthenticationEntryPoint` writes the 401 body with the injected mapper "so it
  serialises exactly as GlobalExceptionHandler's do" via Boot's `ProblemDetailJacksonMixin`. After
  the switch, assert the 401 body STRUCTURE equals a GlobalExceptionHandler 4xx body (extension
  properties flattened, not nested under `"properties"`).

### Pattern 3: KeycloakAdminClient by content
`setUserEnabled` (KeycloakAdminClient.java:144-156) PUTs `userRep.deepCopy()` with `enabled`
changed — a FULL representation, not `{"enabled":false}` alone. So the content assertion is: parse
the captured body → `enabled == false` AND `id`/`username`/`attributes.tenant_id` preserved AND
none of `nodeType`, `array`, `bigDecimal`, `containerNode` present (the measured garbage
signature). Fail-direction arm: send a Jackson-2 `ObjectNode` through the Jackson-3 RestClient
(the current code) → test red with the garbage keys.
Live proof: compose has `KC_ADMIN_ENABLED: ${KC_ADMIN_ENABLED:-false}` (docker-compose.full-stack.yml
~l.326) and base-url `http://keycloak:8080`; the run must set `KC_ADMIN_ENABLED=true`, offboard a
THROWAWAY tenant whose Keycloak user carries its `tenant_id`, then read the user from the Keycloak
admin API and assert `enabled:false` by content; control arm: a user of another tenant stays
enabled. Then restore `KC_ADMIN_ENABLED` and clean up.

### Pattern 4: AMQP converter
`RabbitMQConfig.jsonMessageConverter()` → `new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES)`.
Spring AMQP 4's `DefaultJacksonJavaTypeMapper.isTrustedPackage` still matches by exact
`packageName.equals(trustedPackage)` with default `java.util`, `java.lang`
[VERIFIED: spring-amqp main source] — so the existing exact list and `RabbitMQConfigMessageConverterTest`
carry over. The default `JsonMapper` it builds disables FAIL_ON_UNKNOWN_PROPERTIES and
DEFAULT_VIEW_INCLUSION and calls `findAndAddModules` [VERIFIED: spring-amqp source].
**In-flight compatibility test:** serialize each `@RabbitHandler` payload type with the deprecated
`Jackson2JsonMessageConverter` (still present in AMQP 4.1, test scope), feed the `Message` (with
its `__TypeId__` header) to the new converter, assert equality. Jackson 2's AMQP mapper writes
`java.time` values as numeric timestamps (WRITE_DATES_AS_TIMESTAMPS default on) [ASSUMED]; Jackson 3
reads numeric Instants, but prove it. Fail arm: drop a payload package from the trusted list →
`MessageConversionException`.

### Pattern 5: Redis cache serializer
`CacheConfig.cacheObjectMapper()` uses `activateDefaultTyping(PTV, DefaultTyping.EVERYTHING, PROPERTY)`.
`DefaultTyping.EVERYTHING` is removed in Jackson 3 (jackson-databind #4160) [CITED], and
`GenericJacksonJsonRedisSerializer` does NOT enable default typing unless asked:
`GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(PolymorphicTypeValidator)` /
`.typePropertyName(..)` / `.customize(..)` [VERIFIED: Spring Data Redis 4.1.0 API docs]. Spring
Data Redis docs state the Jackson 3 serializer "can produce JSON output that differs from Jackson 2
output" [CITED: docs.spring.io/spring-data/redis/reference/upgrading.html].
Consequences for planning:
- The SEC-4 allowlist (`CACHE_TYPE_ID_PREFIXES`, CacheConfig.java:140-148) was derived from LIVE
  Jackson-2 EVERYTHING bytes (e.g. `["java.lang.Long", 899]`). Under the new typing scheme the set
  of type ids written changes; **re-derive from live bytes**, exactly as that comment instructs, and
  keep `CacheSerializerTypeAllowlistTest`'s round-trip and refusal arms green. NEVER use
  `enableUnsafeDefaultTyping()` (laissez-faire; reopens SEC-4).
- Old entries: `RedisCacheErrorHandler.handleCacheGetError` swallows read errors, so an
  incompatible entry is a silent miss that self-heals on write. Make it explicit: either prefix
  cache keys with a format version (`RedisCacheConfiguration.prefixCacheNameWith("v4:")` or
  `computePrefixWith`) so old entries are never read and expire by TTL (≤15 min), or record a
  flush of `shops`/`products`/`shopMembership` as a deploy step. Recommend the prefix — it needs no
  operator action and is testable. Proof: `jtoye.cache.errors` stays 0 under read-after-write on
  the rebuilt runtime.

### Pattern 6: D-04 entry point
`BearerTokenAuthenticationEntryPoint.commence` in Security 7 always does
`parameters.put("resource_metadata", getResourceMetadataParameter(request))` and has no setter to
turn it off [VERIFIED: spring-security 7.0.x source]. `BearerTokenAccessDeniedHandler` (403) does
NOT add it [VERIFIED: 7.1.x source]. Recommended: keep delegating (status + error/error_description
semantics), but pass a `HttpServletResponseWrapper` that rewrites the `WWW-Authenticate` value to
drop the `resource_metadata="…"` parameter (and its separator). Assert by content:
missing token → exactly `Bearer`; invalid token → `Bearer error="invalid_token", error_description="…"`
with no `resource_metadata`. Fail arm: remove the wrapper → test red with the parameter present.
Hand-rolling the RFC 6750 header instead is acceptable but duplicates framework logic.

### Pattern 7: Config-key gate (BOOT4-11)
Engine: a JUnit test (spike's `BootSpikePropertyAuditTest` is the prototype — read this session in
38-SPIKE.patch l.1695-1760) using `spring-boot-configuration-metadata`'s
`ConfigurationMetadataRepositoryJsonBuilder` over every
`META-INF/spring-configuration-metadata.json` + `additional-spring-configuration-metadata.json`.
Harden it beyond the spike:
1. **Read metadata from the PRODUCTION runtime classpath, not the test classpath.** The test
   classpath carries extra modules (test autoconfigure) that could declare a key the shipped jar
   does not bind. The build already hands the test JVM
   `-Djtoye.productionRuntimeClasspath=<path>` (core-java/build.gradle.kts, `tasks.test`
   jvmArgumentProviders) — reuse it and VOID (fail) when absent.
2. Fail on UNKNOWN keys and on deprecated keys at `level=error` (no longer bound). Decide whether
   `level=warning` also fails (recommend: fail, so the next major cannot repeat this).
3. Map/List ancestors (`spring.jpa.properties.*`, `logging.level.*`, `management.metrics.tags.*`)
   are legitimately absent — keep the spike's map-ancestor walk, and add a test that a bogus key
   UNDER a non-map parent still fails.
4. Scan `src/main/resources/application*.yml` AND `src/test/resources/application-test.yml`.
5. **Validate `spring.autoconfigure.exclude` values** (see Pitfall 3).
6. Prove it vacuity-safe: zero files found or zero keys checked = fail, never pass.
Wire-up: CONTEXT demands `check-gate-enforcement.sh` compliance — that gate requires every
`scripts/check-*.sh` to be invoked by a workflow or listed in `scripts/gates/gate-enforcement.conf`
[VERIFIED: read gate-enforcement.conf header]. Recommend `scripts/check-boot-config-keys.sh` that
runs the test via Gradle and parses its JUnit XML (tests > 0, failures 0; missing XML = exit 2),
invoked from the core-java unit job in `ci-cd.yaml`.

### Anti-Patterns to Avoid
- **Applying 38-SPIKE.patch.** It is the classic-starter route; use it as a map of package moves only.
- **`enableUnsafeDefaultTyping()`** for the cache — reopens the SEC-4 gadget surface.
- **Deleting the Jackson pin instead of re-keying** — `jackson-bom.version` now moves Jackson 3.
- **Leaving `extra["netty.version"]`** — forces 4.1 netty under a 4.2-line reactor-netty.
- **Trusting a green unit suite for config** — none of the 18 keys is caught by any test.
- **sed-replacing `com.fasterxml.jackson` wholesale** — annotations must stay on the 2.x package.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Knowing which config keys Boot binds | A hand-kept allow-list of keys | `spring-boot-configuration-metadata` over the shipped jars' metadata | Ships with each Boot release; carries deprecation level + replacement |
| Old→new property mapping | Manual reading of release notes | Metadata `deprecation.replacement` (and, as a one-off audit aid only, `spring-boot-properties-migrator` at runtime; remove after) | Authoritative per release |
| Polymorphic cache typing | Custom type-id scheme | `GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(BasicPolymorphicTypeValidator…)` | Gadget-safe validator built in |
| Message conversion | Custom AMQP JSON | `JacksonJsonMessageConverter(trustedPackages…)` | Keeps `__TypeId__` + trusted-package semantics |
| Which auto-configs are active | Reasoning from starter names | `ConditionEvaluationReport` / `/actuator/conditions` census | Measures, not infers |

## Runtime State Inventory

This is a framework migration with serialisation changes, so persisted state matters.

| Category | Items Found | Action Required |
|----------|-------------|-----------------|
| Stored data — Redis | `shops`, `products`, `shopMembership` cache entries written by `GenericJackson2JsonRedisSerializer` (EVERYTHING typing), TTL 10/15/5 min (CacheConfig.java:88-100) | Code: versioned key prefix (or recorded flush). Not a data migration — entries expire |
| Stored data — Postgres `idempotency_keys` | `request_hash = sha256(objectMapper.writeValueAsString(requestBody))` (IdempotencyService.java:187) and `response_body` JSON written by Jackson 2 | Code: give IdempotencyService a DEDICATED fingerprint mapper with frozen features (property order as Jackson 2 wrote it: `SORT_PROPERTIES_ALPHABETICALLY` off, dates as the Boot-3 mapper wrote them), so the persisted hash no longer depends on the app-wide mapper's defaults. Prove with a golden test: hash of fixed request objects computed on `main` (Jackson 2) committed as literals; the Jackson-3 build must reproduce them; arm: switch to Boot's default `JsonMapper` → mismatch. Do NOT route this through the existing `legacyRequestBody` path — its javadoc (IdempotencyService.java:153-158) forbids using it to replay a stored response. Stored `response_body` must still `readValue` into its DTO (test with a Jackson-2-written fixture) |
| Stored data — Postgres jsonb | `shops.opening_hours` (Map<String,String>), `products.allergen_spans` (List<record AllergenSpan(int,int)>), `vendor_onboarding_gate.evidence` (Map<String,Object>) | Read-back test over Jackson-2-era rows (jsonb normalises key order, so only value formats matter). Low risk |
| Stored data — RabbitMQ | Messages in `order.events`, payment, onboarding, media, webhook queues + DLQs, produced by `Jackson2JsonMessageConverter` | In-flight compatibility test (Pattern 4); or drain queues as a recorded deploy step |
| Live service config | Keycloak realm users (deprovisioning target) — not changed by this phase; only exercised | None |
| OS-registered state | None — verified: core-java runs as a container (compose) / Deployment (k8s); no host services reference Boot | None |
| Secrets/env vars | No env var spells a renamed key: `rg` over k8s/, infra/, docker-compose.full-stack.yml for `MANAGEMENT_ZIPKIN|SERVER_ERROR_|LOGGING_FILE_MAX|LOGGING_FILE_TOTAL|MANAGEMENT_METRICS_EXPORT|SPRING_JACKSON|SPRING_HTTP_CONVERTERS|MANAGEMENT_TRACING` → rc=1 (no match). `ZIPKIN_ENDPOINT` is a placeholder consumed by the yml key and keeps working once the key is renamed | None |
| Build artifacts | `core-java/build-local/` (live), `core-java/build/` (stale, never read); the running `jtoye_oaas_2026-core-java-1` image is Boot 3.5 | Full rebuild; read `BOOT-INF/lib/spring-boot-4.1.1.jar` presence and `application.yml` from `/app/app.jar` |

## Common Pitfalls

### Pitfall 1: a missing Boot-4 module fails silently
**What goes wrong:** Flyway, tracing, Prometheus export or health auto-config simply never runs.
**Why:** Boot 4 split auto-configuration into per-technology modules; the third-party library
alone (e.g. `flyway-core`, `micrometer-tracing-bridge-brave`) no longer triggers it.
**Avoid:** Pattern 1 census + hard liveness probes. **Warning sign:** a context that starts faster
than before; "Successfully applied" missing from integration logs.

### Pitfall 2: removing `spring-boot-jackson2` breaks 25 test contexts at once
25 test files `@Autowired` a Jackson-2 `ObjectMapper` (rg count this session). Under D-01+D-02 no
such bean exists. Migrate them to `JsonMapper` in the same wave as main; tests that use
`new ObjectMapper()` (Jackson 2 stays on the test classpath transitively) can stay but should
move for consistency with what production uses.

### Pitfall 3: `spring.autoconfigure.exclude` with a Boot-3 class name is a silent no-op
Five tests (OpenApiDevProfileGatingTest:62, SecurityHeadersProdProfileTest:97,
StagingActuatorPortIsolationTest:64, SecurityHeadersDevProfileTest:74, OpenApiProdProfileGatingTest:77)
exclude `org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration` and
`…RedisRepositoriesAutoConfiguration`. In 4.1.1 those classes do not exist: the data-redis
module's imports list `org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration`
and `…DataRedisRepositoriesAutoConfiguration` [VERIFIED: unzip of spring-boot-data-redis-4.1.1.jar
and spring-boot-autoconfigure-4.1.1.jar (0 redis entries)]. Boot's `checkExcludedClasses` only
rejects excludes that ARE on the classpath [ASSUMED: from Boot source knowledge; consistent with
the spike's green run of these tests]. So the exclusion silently stops happening. Fix: rename
to the new FQCNs and have the key gate (or a unit test) assert every exclude names a class
present in an `AutoConfiguration.imports` file.

### Pitfall 4: Jackson 3 changes bytes, and bytes are persisted
Default changes [CITED: FasterXML MIGRATING_TO_JACKSON_3.md]: `SORT_PROPERTIES_ALPHABETICALLY`
false→true; `WRITE_ENUMS_USING_TO_STRING`/`READ_ENUMS_USING_TO_STRING` false→true;
`FAIL_ON_NULL_FOR_PRIMITIVES` false→true; `FAIL_ON_TRAILING_TOKENS` false→true;
`FAIL_ON_UNKNOWN_PROPERTIES` true→false; `WRITE_DATES_AS_TIMESTAMPS` true→false.
Measured exposure in main code: **0** enums override `toString()` (intersection of the 39 enum
files with `toString()` files is empty) — so the enum flags do not change output; request DTOs with
primitives: `MediaUploadRequest(boolean, int)` is an internal fingerprint record, not JSON input;
`PublicShopDto.acceptsCardPayments`, `PublicProductDto.inStock` are response-side. The live risk is
ORDER: the idempotency hash (above), and any test asserting byte-equal JSON (memory:
trap-byte-equality-on-roundtripped-json). Decide explicitly (Open Question 2).

### Pitfall 5: D-04 strips the header but the metadata endpoint is still served
See Pattern 6 and Open Question 1. `curl -i /.well-known/oauth-protected-resource` on the rebuilt
runtime before and after is the measurement.

### Pitfall 6: Docs gates VOID, not fail, on renamed coordinates
`scripts/check-doc-versions.sh:194` resolves Resilience4j via
`g 'io.github.resilience4j:resilience4j-spring-boot3'`; after the rename that resolves to empty and
the script VOIDs (exit 2, "could not resolve the real version"). Update the coordinate and the
label regex ("Resilience4j( Spring Boot 3 Starter)?"). The Spring Boot row reads the plugin
version via `boot_version()` and matches every "Spring Boot …x.y.z" phrase, including "Spring Boot
Gradle Plugin" and "Hibernate ORM (via Spring Boot 3.5.16)". SpringDoc and statemachine rows read
coordinates that keep their names.

### Pitfall 7: line citations shift
`check-doc-citations.sh` scans CLAUDE.md, AGENTS.md, `.planning/codebase/{STACK,ARCHITECTURE,INTEGRATIONS}.md`,
`k8s/DEPLOYMENT.md`, `k8s/LOCAL.md`. Citations into `core-java/build.gradle.kts` found:
STACK.md (`:2`, `:13`, `:21-54` netty, `:104-153` Jackson, `:248`, `:466`), k8s/LOCAL.md:2058 (`:19`),
infra/dependency-horizons.yaml (`:2`, `:13` — sites). Every one must be re-pointed after the edit
(STATE.md records 5 pre-existing STACK.md citation failures — do not count those as new).

### Pitfall 8: azure-core-http-netty on netty 4.2
azure-core-http-netty 1.16.7 declares netty 4.1.137; Boot's BOM forces 4.2.17. The spike's
integration suite (which includes real-Azurite storage tests from Phase 36) passed on that mix,
so it works today — keep the Azurite integration classes in the per-wave sample.

### Pitfall 9: OpenAPI consumers outside core-java
`scripts/check-edge-core-contract.sh` and `edge-go/internal/core/contract_test.go` read
`docs/api/openapi-snapshot.json`; mcp-server's `create-order.ts`/`create-customer.ts` cite it for
required fields. After regeneration, run the edge contract gate and `go test ./internal/core/...`.

## Config keys — the 18 (verified)

Located this session (`core-java/src/main/resources`):
- `application.yml:489-490` `server.error.include-message: always`, `include-binding-errors: always`;
  `application.yml:555-557` `management.zipkin.tracing.endpoint: ${ZIPKIN_ENDPOINT:…}`.
- `application-staging.yml:71-74` four `server.error.include-*`; `:96-98` `logging.file.max-size: 10MB`,
  `max-history: 15`, `total-size-cap: 500MB`; `:129-132` `management.metrics.export.prometheus.enabled: true`.
- `application-prod.yml:68-71` four `server.error.include-*`; `:99-101` `max-size: 10MB`,
  `max-history: 30`, `total-size-cap: 1GB`.
Total 3 + 8 + 7 = 18.

**Two of these were already dead on Boot 3.5.16** — this changes how the renames are described:
from the Boot **3.5.16** metadata (unzipped from the local Gradle cache this session):
- `management.metrics.export.prometheus.enabled`: `deprecation {"level":"error","replacement":"management.prometheus.metrics.export.enabled","since":"3.0.0"}` — already unbound since 3.0 (application.yml:512 already calls it a NO-OP). Renaming it in staging sets the default (`true`); recommend DELETING it.
- `logging.file.max-size|max-history|total-size-cap`: `deprecation {"replacement":"logging.logback.rollingpolicy.*","since":"2.4.0"}` with no level (warning) — still bound on 3.5, unbound (error) on 4.1.1 per the spike. So prod's 30-day/1 GB retention IS in effect today; the rename preserves it.
- `management.zipkin.tracing.endpoint`: present, not deprecated, on 3.5.16 — renamed to `management.tracing.export.zipkin.endpoint` in 4.x [CITED: 38-SPIKE.md §3].
- `server.error.include-*` → `spring.web.error.include-*` [CITED: 38-SPIKE.md §3].
Prove each rename at RUNTIME, not only in metadata: e.g. staging-profile context →
`ErrorProperties`/`WebProperties` bean shows `include-stacktrace=on_param`; prod context → rolling
policy bean / logback appender `maxHistory=30`; Zipkin sender endpoint equals `ZIPKIN_ENDPOINT`.

## OpenAPI (BOOT4-13)

`scripts/openapi-gate.sh` compares the COMMITTED snapshot with the PR-generated spec; regenerating
the snapshot in the same PR IS the documented way to accept an intentional (even breaking) change
("regenerating the snapshot is how you mark the break as reviewed. Never edit the snapshot by
hand." — docs/api/README.md) [VERIFIED: read]. There is no separate exception list. So the
discretion item reduces to: for each of the 11 DTOs gaining `required` (spike §3), prove a request
omitting the field returns 400 today on main (the fail-direction is a request that would succeed if
the constraint were absent) BEFORE committing the regenerated snapshot. `check-openapi-snapshot-fresh.sh`
(runtime subsumption) runs in the nightly against the rebuilt service.

## Docs, horizons and metrics (BOOT4-14)

- `infra/dependency-horizons.yaml` row `spring-boot` (l.492-511): `pin` → `id("org.springframework.boot") version "4.1.1"`, `eol_cycle: "4.1"`, `eol_date: "2027-07-31"` (endoflife.date: 4.1 `eol` 2027-07-31, `extendedSupport` 2028-07-31 [VERIFIED]); DELETE the `exemption` block (#706). Re-check both `sites` line numbers. Run `scripts/check-dependency-horizons.sh` (memory: H-2 compares cached vs live catalogue).
- `scripts/check-doc-versions.sh`: change the Resilience4j row coordinate to `resilience4j-spring-boot4` and its label regex; the gate then demands every doc claim move (CLAUDE.md, AGENTS.md outside the ORGOS block, STACK.md, README, SETUP, QUICK_START, ESSENTIAL_ARCHITECTURE).
- Prose claims no gate reads but that become false: STACK.md "JUnit 5" (l.49), "Embedded Tomcat 10.1.59" (l.34), the netty and Jackson bullets (l.35-36), "Spring WebFlux (`spring-boot-starter-webflux`)" (l.39); CLAUDE.md "SpringDoc OpenAPI 2.8.6", "JUnit 5"; `core-java/Dockerfile` LABEL "Spring Boot 3 backend".
- `docs/metrics.json` (today `java_test_methods` 2005 / files 306, total 4183): the new tests change the counts → regenerate with `scripts/docs-freshness.sh --write`, then reconcile prose numbers so `check-doc-metrics.sh` passes (the worktree's CLAUDE.md and AGENTS.md both quote "4183 logical invocations" today, matching metrics.json).
- `scripts/check-project-version.sh` and the CHANGELOG contract gates may need the release note; follow existing changelog conventions (squash-merge; memory: rebase-merge voids the changelog gate).

## Code Examples

### Re-keyed BOM pins (sketch — exact keys verified in the 4.1.1 POM, lines 82-83, 159, 181, 215)
```kotlin
// netty: no pin — Boot 4.1.1 manages 4.2.17.Final (remove extra["netty.version"])
extra["tomcat.version"] = "11.0.26"
extra["rabbit-amqp-client.version"] = "5.34.0"
extra["jackson-2-bom.version"] = "2.22.3"   // Jackson 2, transitive users only
extra["jackson-bom.version"] = "3.1.7"      // Jackson 3 (tools.jackson)
```

### Redis serializer (Spring Data Redis 4.1 builder — method names from the 4.1.0 API page)
```java
// Source: docs.spring.io/spring-data-redis/docs/current/api/.../GenericJacksonJsonRedisSerializer.GenericJacksonJsonRedisSerializerBuilder.html
GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
        .enableDefaultTyping(cacheTypeValidator())   // tools.jackson PolymorphicTypeValidator, re-derived allowlist
        .build();
```

### AMQP converter
```java
// Source: spring-amqp JacksonJsonMessageConverter constructors (main branch)
@Bean
public MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(TRUSTED_PAYLOAD_PACKAGES);
}
```

### Security 7 metadata customiser (only if the owner chooses to keep the endpoint)
```java
// Source: docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html
http.oauth2ResourceServer(rs -> rs
    .protectedResourceMetadata(prm -> prm.protectedResourceMetadataCustomizer(b -> { /* correct the claims */ })));
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `@MockBean`/`@SpyBean` | `@MockitoBean`/`@MockitoSpyBean` | Boot 4.0 (removed) | 33 test files (rg this session) |
| `boot.test.autoconfigure.web.servlet.*` | `boot.webmvc.test.autoconfigure.*` | Boot 4.0 | 66 test files |
| `TestRestTemplate` auto-registered | `@AutoConfigureTestRestTemplate` + `boot.resttestclient` | Boot 4.0 | 1 test file |
| `spring-boot-starter-web`, `-aop`, `-oauth2-resource-server` | `-webmvc`, `-aspectj`, `-security-oauth2-resource-server` | Boot 4.0 | build file |
| Jackson 2 `com.fasterxml.jackson.databind` | Jackson 3 `tools.jackson.databind`; unchecked `JacksonException` | Boot 4.0 default | 22 main / 47 test files |
| Security 6 bare `Bearer` challenge | `Bearer resource_metadata="…"` + RFC 9728 endpoint | Security 7.0 | D-04 |
| JUnit 5 | JUnit Jupiter 6.0.3 | Boot 4.0 | docs claim "JUnit 5" (STACK.md:49) |

**Deprecated (still working in 4.1.1, removed later):** `Jackson2JsonMessageConverter`,
`GenericJackson2JsonRedisSerializer`, `org.springframework.boot.env.EnvironmentPostProcessor`
(ActiveProfileValidator still fires via an adapter) [CITED: 38-SPIKE.md §1].

## Assumptions Log

| # | Claim | Section | Risk if Wrong |
|---|-------|---------|---------------|
| A1 | Boot's `checkExcludedClasses` ignores excludes whose class is absent (so the 5 redis excludes are silent no-ops) | Pitfall 3 | Low — if Boot rejected them the tests would fail loudly; fix is the same rename |
| A2 | Jackson 2's AMQP mapper writes `java.time` as numeric timestamps and Jackson 3 reads them | Pattern 4 | Medium — in-flight messages could dead-letter; the compatibility test settles it |
| A3 | CVE-2026-91777 has a Jackson-3 fixed version ≤ 3.1.7 (not confirmed) | CVE floors | Medium — local Trivy would flag; then take 3.2.x |
| A4 | The Jackson-3 serialisation of at least some idempotency request bodies differs byte-wise from Jackson 2 | Runtime State | Medium — if bytes are identical, BOOT4-08 is trivially satisfied (still measure) |
| A5 | Boot 4 registers the ProblemDetail mixin on the Jackson-3 `JsonMapper` | Pattern 2 | Medium — 401 body shape would drift from other 4xx; test catches it |
| A6 | Hibernate 7.4 JSON FormatMapper reads Jackson-2-era jsonb rows for the three simple types | Runtime State | Low — types are maps/ints |
| A7 | `/.well-known/oauth-protected-resource` is reachable unauthenticated on THIS app. Supporting evidence: SecurityConfig.java has ONE `SecurityFilterChain` (l.110) with no `securityMatcher`, and the filter is inserted before `AbstractPreAuthenticatedProcessingFilter`, i.e. ahead of authorization | Pattern 6 | Medium — decides Open Question 1; measure with curl on the rebuilt runtime |

## Open Questions

1. **What happens to `/.well-known/oauth-protected-resource`?**
   - Known: Security 7.1 always registers `OAuth2ProtectedResourceMetadataFilter` (no disable);
     default body claims `bearer_methods_supported:["header"]`, `tls_client_certificate_bound_access_tokens:true`,
     `resource` derived from the request URL [VERIFIED: 7.1.x source].
   - Unclear: D-04 assumed the URL is not served. Serving RFC 9728 is out of scope, yet the
     framework now serves it, with one false claim.
   - Recommendation: measure on the rebuilt runtime, then ask the owner to choose: (a) suppress it
     (e.g. a small filter ordered before it returning 404 for that path — restores Boot-3.5 parity,
     "no behaviour change beyond what the framework forces"), or (b) keep it but correct the false
     claim via `protectedResourceMetadataCustomizer`. Until decided, plan (a) behind a checkpoint.
2. **Jackson 3 defaults vs `spring.jackson.use-jackson2-defaults`.** Measure a wire diff (parsed,
   then raw) of representative responses, events and idempotency fingerprints on Boot 3.5 vs 4.1;
   if only ordering differs and the idempotency path is handled, keep Jackson-3 defaults.
3. **Temurin.** The spike ran Ubuntu OpenJDK 25.0.4.1; `/usr/lib/jvm` has no Temurin. The image
   builds on `eclipse-temurin:25-jdk-alpine`, so the container build is the Temurin measurement;
   CI (setup-java) is the other.
4. **Testcontainers 1.21.4 vs managed 2.0.5.** Explicit versions keep 1.21.4 and the spike passed;
   moving to 2.x is NOT required and is a separate decision (doc row reads the explicit pin).

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
|------------|------------|-----------|---------|----------|
| JDK 25 | build/tests | ✓ | OpenJDK 25.0.4.1 (Ubuntu) | Temurin via the Docker build stage |
| Docker | Testcontainers, compose, Trivy | ✓ | Engine 29.8.2; compose stack UP (15 containers) | — |
| Trivy CLI | local image gate | ✗ | — | `aquasec/trivy:0.70.0` image present locally |
| oasdiff | `scripts/openapi-gate.sh` locally | ✗ | — | CI job installs v1.23.0 checksum-verified; or download the same release locally |
| Keycloak (compose) | live offboard proof | ✓ | jtoye-keycloak healthy | — |
| Gradle wrapper | build | ✓ | 9.7.1 (wrapper) | — |

**Missing with no fallback:** none. **Note:** the running `jtoye_oaas_2026-core-java-1` is Boot 3.5;
per project memory a second session may drive this checkout's stack — coordinate before rebuilding.

## Validation Architecture

### Test Framework
| Property | Value |
|----------|-------|
| Framework | JUnit Jupiter 6.0.3 (Boot 4.1.1 managed) + Spring Boot Test 4.1.1 + Testcontainers 1.21.4 (explicit) |
| Config file | `core-java/build.gradle.kts` (`tasks.test` excludes tag `testcontainers`; `integrationTest` includes it; forkEvery 4) |
| Quick run command | `./gradlew :core-java:test --tests '<Class>'` |
| Full suite command | `./gradlew :core-java:test :core-java:integrationTest --rerun` (baseline 1330 unit / 745 IT; count from `core-java/build-local/test-results/**.xml`, never from `build/`) |

### Phase Requirements → Test Map
| Req ID | Behavior | Type | Automated Command | Fail-direction arm | Exists? |
|--------|----------|------|-------------------|-------------------|---------|
| BOOT4-01 | resolves to Boot 4.1.1 | build | `./gradlew :core-java:dependencyInsight --dependency spring-boot --configuration runtimeClasspath` | plugin left at 3.5.16 in ONE of the two files → mismatch reported | ❌ W0 |
| BOOT4-02 | no classic/jackson2/grpc on classpath; census diff empty of unintended items | integration | census test + `dependencies --configuration runtimeClasspath` grep | drop `spring-boot-starter-zipkin` → census names tracing auto-config | ❌ W0 |
| BOOT4-03 | migrations applied > 0 | integration | `integrationTest --tests '*RlsContractTest' '*FreshChainMigrationIntegrationTest'` + log count | runtime-exclude `spring-boot-flyway` → 0 applied, RlsContractTest 4/7 red (delete stale XML first) | ✅ (arm new) |
| BOOT4-04 | main has no Jackson-2 databind/core imports | static | `rg -uu -n 'com\.fasterxml\.jackson\.(databind|core)' core-java/src/main` must be empty (print rc) | re-add one import → non-empty | ❌ W0 |
| BOOT4-05 | Keycloak body by content | unit + live | `test --tests '*KeycloakAdminClientTest'`; live: offboard + admin GET | current Jackson-2 ObjectNode → garbage keys present | ✅ (assertion strengthen) |
| BOOT4-06 | Jackson-2-written message consumed | unit | new `RabbitMessageCompatibilityTest` | remove a trusted package → conversion exception | ❌ W0 |
| BOOT4-07 | cache round-trip + refusal; old entries unread | unit + runtime | `test --tests '*CacheSerializerTypeAllowlistTest' '*MembershipSerializerRoundTripTest'`; runtime `jtoye.cache.errors == 0` | allowlist without `java.lang.` (or whatever the re-derivation requires) → read fails | ✅ (re-derive) |
| BOOT4-08 | pre-deploy idempotency key replays | unit + integration | golden-hash unit test (literals captured on `main` under Jackson 2) + NOSUPERUSER integration replay of a Jackson-2-era reservation → original response, not 422 | fingerprint via Boot's default `JsonMapper` → hash mismatch / 422 | ❌ W0 |
| BOOT4-09 | 401 header exactly `Bearer` / RFC 6750 error form | unit + runtime curl | `test --tests '*ProblemDetailAuthenticationEntryPointTest'`; `curl -i` live | remove wrapper → `resource_metadata` present | ✅ (assert change) |
| BOOT4-10 | statemachine transitions | unit + integration | `test --tests '*StateMachine*'` + OrderControllerIntegrationTest etc. | spike arms A/B (retarget transition; guard always passes) | ✅ |
| BOOT4-11 | unknown/removed key fails CI | unit + script | `scripts/check-boot-config-keys.sh` | inject `spring.bogus.key` and a Boot-3 key → exit 1; empty input → exit 2 | ❌ W0 |
| BOOT4-12 | CVE floors resolve; image clean | build + image | `dependencyInsight` per artifact; Trivy 0.70.0 image scan | `tomcat.version=11.0.24` → Trivy exit 1; near-miss key → managed version | ❌ |
| BOOT4-13 | snapshot fresh; newly-required fields enforced | integration + gate | `integrationTest --tests '*OpenApiSnapshotTest'`; per-field 400 requests; `openapi-gate.sh` | omit snapshot regen → gate fails | ✅ |
| BOOT4-14 | runtime parity + docs gates | script | `check-runtime-freshness.sh`, `check-branch-behind-base.sh`, `check-doc-versions.sh`, `check-doc-citations.sh`, `check-dependency-horizons.sh`, `docs-freshness.sh`, `check-doc-metrics.sh`; `unzip -l /app/app.jar | grep spring-boot-4.1.1`; `unzip -p /app/app.jar BOOT-INF/classes/application.yml` | stop one service → freshness VOID (exit 2); stale doc claim → exit 1 | ✅ |

### Sampling Rate
- **Per task commit:** the touched classes' unit tests + compile of main and test.
- **Per wave merge:** full `test` + `integrationTest --rerun` with counts read from XML and compared to the 1330/745 baseline (plus new tests).
- **Phase gate:** full suite green, all BOOT4 arms recorded both directions, rebuilt runtime gates, nightly E2E green on the branch's runtime, before `/gsd-verify-work`.

### Wave 0 Gaps
- [ ] Census harness (explicit vs classic positive matches) — BOOT4-02
- [ ] `ConfigKeyContractTest` + `scripts/check-boot-config-keys.sh` + CI wiring — BOOT4-11
- [ ] `RabbitMessageCompatibilityTest` — BOOT4-06
- [ ] Idempotency legacy-hash test — BOOT4-08
- [ ] Static Jackson-import check — BOOT4-04
- [ ] Stale-XML deletion step before every arm run (the spike's first Flyway arm was vacuous because compile failed and old XML was read)

## Security Domain

`security_enforcement: true`, ASVS level 2, block on medium (`.planning/config.json`).

### Applicable ASVS Categories
| ASVS Category | Applies | Standard Control |
|---------------|---------|-----------------|
| V2 Authentication | yes | Spring Security 7 resource server; JWT validation unchanged; entry point per D-04 |
| V3 Session Management | no change | (STATELESS change explicitly deferred in ProblemDetailAuthenticationEntryPoint javadoc) |
| V4 Access Control | yes | RLS + TenantContext unchanged; `@PreAuthorize` scope gates — run the FULL integration suite (memory: scope-gate integrationTest regression) |
| V5 Input Validation | yes | Bean Validation unchanged; springdoc now documents it (`required`) |
| V8 Data Protection | yes | Idempotency `response_body` holds PII under FORCE RLS — the compatibility test must run under the NOSUPERUSER role |
| V14 Configuration | yes | Unknown-key gate; error-detail keys (`include-stacktrace`) must keep their per-profile values (prod `never`) |
| Deserialization (V5.5) | yes | Cache PTV allowlist (SEC-4) and AMQP exact trusted packages carried to Jackson 3 |

### Known Threat Patterns
| Pattern | STRIDE | Standard Mitigation |
|---------|--------|---------------------|
| Polymorphic deserialization gadget via Redis cache type ids | Tampering/EoP | `BasicPolymorphicTypeValidator` subtype allowlist; never `enableUnsafeDefaultTyping` |
| `__TypeId__` header class loading on AMQP | Tampering | Exact-match trusted packages |
| Staging/prod error detail leak if `include-*` keys unbound | Information disclosure | Rename to `spring.web.error.*`; key gate; runtime assertion per profile |
| False RFC 9728 claim / host-header-derived `resource` | Information disclosure / Spoofing | Open Question 1 |
| Garbage PUT to Keycloak leaves offboarded users ENABLED | EoP (stolen token keeps minting) | BOOT4-05 by-content + live proof |
| Unpatched Tomcat WebSocket constraint bypass (CVE-2026-76183) | EoP | Tomcat 11.0.26 |

## Sources

### Primary (HIGH)
- Maven Central: `spring-boot-dependencies-4.1.1.pom` (managed versions, starter list); starter POMs for actuator, zipkin, webmvc, security-oauth2-resource-server, test, micrometer-metrics, data-jpa, webflux, webclient, restclient, flyway, websocket, amqp, webmvc-test, security-test; maven-metadata for jackson-bom (2 and 3), tomcat-embed-core, spring-security-access, springdoc, resilience4j-spring-boot4, spring-retry, statemachine, amqp-client, netty-bom, configuration-metadata.
- Local Gradle cache: spring-boot-3.5.16 / actuator-autoconfigure-3.5.16 metadata JSON; spring-boot-data-redis-4.1.1 and spring-boot-autoconfigure-4.1.1 jar listings.
- spring-security source: `BearerTokenAuthenticationEntryPoint` (7.0.x), `OAuth2ResourceServerConfigurer`, `OAuth2ProtectedResourceMetadataFilter`, `BearerTokenAccessDeniedHandler` (7.1.x).
- spring-amqp source (main): `DefaultJacksonJavaTypeMapper`, `JacksonJsonMessageConverter`.
- https://endoflife.date/api/spring-boot.json (4.1 EOL 2027-07-31).
- Repo files read: 38-CONTEXT.md, 38-SPIKE.md, 38-SPIKE.patch, core-java/build.gradle.kts, build.gradle.kts, CacheConfig.java, RabbitMQConfig.java, ProblemDetailAuthenticationEntryPoint.java, IdempotencyService.java, KeycloakAdminClient.java, application*.yml, Dockerfile, check-doc-versions.sh, openapi-gate.sh, docs/api/README.md, gate-enforcement.conf, dependency-horizons.yaml, docs/metrics.json.

### Secondary (MEDIUM)
- https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
- https://docs.spring.io/spring-data/redis/reference/upgrading.html ; GenericJacksonJsonRedisSerializerBuilder API (4.1.0)
- https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md ; jackson-databind #4160
- https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/protected-resource-metadata.html
- https://tomcat.apache.org/security-11.html
- https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/

### Tertiary (LOW)
- Jackson CVE fixed versions for the 3.x line via search results (GitLab advisory pages, Keycloak issues #53444-#53446) — confirm with the local Trivy run.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — versions read from the 4.1.1 BOM and Central this session.
- Architecture / starters: HIGH — starter contents read from POMs; census recommended to measure the rest.
- Jackson wire compatibility: MEDIUM — documented default changes; byte effects must be measured.
- Security 7 metadata endpoint: HIGH on mechanism (source read), MEDIUM on reachability in this chain.
- Pitfalls: HIGH for the ones measured here (redis exclude names, key metadata, doc-gate rows).

**Research date:** 2026-10-04
**Valid until:** 2026-10-18 for CVE floors (Trivy DB moves daily); 2026-11-03 for the rest.

## RESEARCH COMPLETE

Phase 38 is plannable. The spike's change map stands, but D-01 (Jackson 3) and D-02 (explicit
starters) together widen it. Three things do not show up in the spike:
- Silently missing Boot-4 modules (tracing, RestClient/WebClient builders, test slices). A
  classic-vs-explicit auto-configuration census catches them.
- Persisted-byte hazards: the Redis cache typing scheme, in-flight AMQP messages, and the
  idempotency request hash.
- Security 7 serves an RFC 9728 endpoint unconditionally, which D-04 did not account for. This is
  an owner question.

Proposed requirement IDs: BOOT4-01..14.
