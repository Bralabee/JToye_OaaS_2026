# Phase 38: Spring Boot 4.1 Migration - Context

**Gathered:** 2026-10-01
**Status:** Ready for planning

<domain>
## Phase Boundary

core-java moves from Spring Boot 3.5.16 to the Boot 4.1.x line (issue #706). Boot 3.5's OSS support
ended 2026-06-30 and 3.5 is the last 3.x line; 4.1 is OSS-supported to 2027-07-31. The move brings
Spring Framework 7, Spring Security 7, Hibernate 7, Flyway 12, Lettuce 7, Spring Data 2025.1,
Spring AMQP 4, JUnit 6 and Jackson 3 as the default JSON line. It also unblocks dependabot #739
(springdoc 3.1.1, whose parent POM is Boot 4.1 and which carries 8 security advisories).

**In scope:** the build, main code and tests of `core-java/`; its `application*.yml`; the CVE
floor pins in `core-java/build.gradle.kts`; the OpenAPI snapshot and its gates; the docs and
version claims gated by `check-doc-versions.sh` / `check-doc-citations.sh`; the
`infra/dependency-horizons.yaml` spring-boot row (its #706 exemption is removed).

**Not in scope:** edge-go, frontend, mcp-server (no Boot dependency); replacing
spring-statemachine (D-03); serving RFC 9728 protected-resource metadata (D-04); any behaviour
change to an endpoint beyond what the framework move forces.

</domain>

<evidence>
## Feasibility spike (2026-10-01) — `38-SPIKE.md`, diff in `38-SPIKE.patch`

A throwaway worktree on `origin/main` @ `43ed6bbf` reached a compiling Boot 4.1.1 build. It ran on
Ubuntu OpenJDK 25.0.4.1, because no Temurin JDK is installed on the host; re-measure on Temurin
in CI.

- **Unit:** 1330 tests, 2 failures. **Integration (Testcontainers):** 745 tests, 1 failure. Counts
  come from the JUnit XML with `--rerun`. Baseline on main: 1330/0 and 745/0.
- **Flyway runs:** "Successfully applied 67 migrations" in 143 classes. With the Flyway module
  removed, 0 migrations run and `RlsContractTest` goes 4/7 red, so this check can fail.
- **spring-statemachine 4.0.2 works on Framework 7.0.9.** 28 state-machine tests and 14
  full-context classes drive real transitions. Each break arm turned its test red.
- The patch follows the CLASSIC-starter route. D-02 below reverses that, so treat the patch as a
  map of the package moves and API removals, not as something to apply.

**Remaining failures found by the spike, each a planning input:**
1. `KeycloakAdminClient.setUserEnabled` sends a Jackson-2 `ObjectNode` through the RestClient,
   which now uses Jackson 3. The wire body comes out as `{"array":false,…,"nodeType":"OBJECT",…}`
   with no `enabled` field, so tenant offboarding would PUT garbage to Keycloak. Only
   `KeycloakAdminClientTest` caught it.
2. Spring Security 7 sends `WWW-Authenticate: Bearer resource_metadata="…/.well-known/oauth-protected-resource"`
   on every 401.
3. springdoc 3.1.1 changes 19 OpenAPI schemas:
   - 11 request DTOs gain `required`.
   - 5 fields gain `format: email`.
   - The `HttpStatus` enum loses its deprecated constants.
   - One field becomes nullable.

   These describe validation the server already enforces, but the CI breaking-change gate will
   probably still flag them.

**Silent breakage that no test catches:** 18 `application*.yml` keys are no longer bound by Boot 4
and are silently ignored. The spike checked all 211 keys against the 4.1.1 configuration metadata,
and an injected bogus key was flagged, so the check can fail. The keys:
- `management.zipkin.tracing.endpoint` → `management.tracing.export.zipkin.endpoint`. Without the
  rename, `ZIPKIN_ENDPOINT` is ignored everywhere.
- `server.error.include-*` → `spring.web.error.include-*` (base, staging, prod).
- `logging.file.max-size|max-history|total-size-cap` → `logging.logback.rollingpolicy.*` (prod,
  staging). Without the rename, prod's 30-day / 1 GB retention reverts to the defaults.
- `management.metrics.export.prometheus.enabled` → `management.prometheus.metrics.export.enabled`
  (staging).

**CVE floor pins (dropping each pin was measured):**
- netty: Boot 4.1.1 manages 4.2.17, which is patched for the same CVEs. The 4.1.137 pin MUST go,
  or it forces a 4.1.x netty under a 4.2-line reactor-netty.
- Tomcat: Boot 4.1.1 manages 11.0.24, below our floor. Pin ≥ 11.0.25; 11.0.26 fixes 14 more CVEs.
- amqp-client: keep the 5.34.0 pin; without it the managed version is 5.30.0.
- Jackson 2 BOM: `jackson-bom.version` names the Jackson 3 BOM under Boot 4. Re-key it to
  `jackson-2-bom.version` and re-check the floor. springdoc 3.1.1 wants databind 2.22.1.

</evidence>

<decisions>
## Implementation Decisions (owner, 2026-10-01)

- **D-01 — Jackson 3 now.** Move the main code to Jackson 3 (`tools.jackson`) and do not set the
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
- **D-02 — Explicit per-module starters,** not `spring-boot-starter-classic` /
  `spring-boot-starter-test-classic`. Classic pulls in Boot's gRPC autoconfig modules without gRPC
  itself, and on any filter chain with CSRF enabled that throws `NoClassDefFoundError`. Declare
  each module the app actually uses:
  - webmvc, data-jpa, flyway, aspectj, security, oauth2-resource-server, validation, actuator,
    data-redis, cache, amqp, websocket, mail, webflux, and the test slices.
  - **Flyway liveness must be proven:** migrations applied > 0, and a break arm in which removing
    the starter turns the RLS tests red.
- **D-03 — Keep spring-statemachine 4.0.2,** and add `org.springframework.security:spring-security-access`
  for its 24 latent `org.springframework.security.access.*` references.
  - Record the risk: the library has no Framework-7 release, so we run it on a framework it was not
    built for.
  - Replacing it with an `EnumMap` transition table (~60–80 LOC per machine, with the existing
    tests as the spec) is a separate, non-blocking decision.
- **D-04 — Keep a plain `WWW-Authenticate: Bearer` on 401.** Customise the Security 7 entry point
  (`ProblemDetailAuthenticationEntryPoint`) so the API never advertises a `resource_metadata` URL
  that it does not serve. RFC 9728 metadata is a possible agent-readiness item later, and
  explicitly out of scope here.
- **D-05 — Suppress `/.well-known/oauth-protected-resource`** (owner, 2026-10-04, after research).
  `38-RESEARCH.md` Open Question 1 found that D-04's premise was partly wrong:
  - Spring Security 7.1 always registers `OAuth2ProtectedResourceMetadataFilter` (it cannot be
    disabled) and serves that path.
  - Its default body claims `tls_client_certificate_bound_access_tokens: true`, which is false for
    this API.

  Ruling: suppress the path with a small filter ordered before the framework's, returning 404 for
  that path, which matches Boot 3.5 behaviour today. Do NOT serve corrected metadata. RFC 9728
  stays out of scope, as in D-04.
  - Prove it on the rebuilt runtime: `curl -i` on the path returns 404 and the 401 header carries
    no `resource_metadata`.
  - Fail-direction arm: remove the suppressing filter, and the path returns 200 with the false
    claim.

## Claude's Discretion
- Tomcat 11.0.25 vs 11.0.26 (take the newer one if it resolves cleanly).
- Whether the Jackson-2 floor moves to 2.22.1.
- OpenAPI: regenerate the snapshot. Each newly-`required` field must already be enforced
  server-side (a `@NotNull` / `@NotBlank` that a request without it fails with 400 today); prove
  that per field before adding a breaking-change-gate exception. Then record the exception the way
  the gate's own docs prescribe.
- Plan count and wave structure.

## Standing requirements (from CLAUDE.md, not negotiable)
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

</decisions>
