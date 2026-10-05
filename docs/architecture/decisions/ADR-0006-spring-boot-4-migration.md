# ADR-0006: Move core-java to Spring Boot 4.1 — Jackson 3, explicit starters, statemachine kept

**Status:** Accepted (2026-10-05)
**Refs:** #706 (Boot 3.5 OSS end of support), #739 (springdoc 3.1.1, whose parent POM is Boot 4.1),
Phase 38 plans `38-01`..`38-19`, `.planning/phases/38-spring-boot-4-1-migration/38-CONTEXT.md` (owner
decisions D-01..D-05), `38-SPIKE.md`, `38-RESEARCH.md`, requirements BOOT4-01..BOOT4-14

**How numbers are cited.** Every figure below names the evidence file it was measured in. `ev:NAME`
means `.planning/phases/38-spring-boot-4-1-migration/evidence/NAME`. A figure without a citation is a
version read from the build file or Boot's BOM, and `ev:38-16-docs.txt` §3 lists where each was read.

## Context

core-java ran on Spring Boot 3.5.16. Boot 3.5's OSS support ended on **2026-06-30**, and 3.5 is the last
3.x line; `infra/dependency-horizons.yaml` carried a dated exemption for it under #706, expiring
2027-02-28. Boot 4.1 is OSS-supported to **2027-07-31** (commercial to 2028-07-31, per endoflife.date;
the horizons gate re-fetches that date on every run, `ev:38-16-docs.txt` §5 arm F). Dependabot's
springdoc 3.1.1 bump (#739) could not land on Boot 3.5, because springdoc 3.x is built on Boot 4.

The move is a framework change, not a version bump. Dependabot's plain 4.1.1 PR (#676) failed five CI
jobs and was closed (recorded in the #706 exemption this phase deleted: `git show 4656a7f7:infra/dependency-horizons.yaml`). Boot 4.1.1 brings Spring Framework 7.0.9, Spring Security 7.1.1, Spring Data
2026.0, Hibernate ORM 7.4, Flyway 12.4, Lettuce 7.5, Spring AMQP 4.1, JUnit Jupiter 6.0 and Jackson 3
as the default JSON line. A throwaway spike on `origin/main` @ `43ed6bbf` compiled on 4.1.1 and found
four defects that the existing tests did not catch (`38-SPIKE.md`):

- `KeycloakAdminClient` sent a Jackson-2 `ObjectNode` through a Jackson-3 `RestClient`, so tenant
  offboarding would PUT `{"array":false,…,"nodeType":"OBJECT",…}` to Keycloak with no `enabled` field.
- Spring Security 7 adds `resource_metadata="…/.well-known/oauth-protected-resource"` to every 401.
- springdoc 3.1.1 changes the generated OpenAPI document.
- 18 `application*.yml` keys are no longer bound by Boot 4 and are silently ignored.

## Decisions

The owner took D-01..D-04 on 2026-10-01, and D-05 on 2026-10-04 (refined on 2026-10-05). The
Jackson-defaults decision was taken on 2026-10-05, after 38-05 measured it. Each decision is stated
here in substance; the exact text is in `38-CONTEXT.md`.

### D-01 — Jackson 3 now (owner, 2026-10-01)

Main code moves to Jackson 3 (`tools.jackson`). The deprecated
`spring.http.converters.preferred-json-mapper=jackson2` switch is not set. This covers the classes that
injected the Jackson-2 `ObjectMapper`, `KeycloakAdminClient`, the Rabbit converter and the Redis
serializer. Jackson 2 keeps a BOM floor pin only for its transitive users (springdoc's swagger-core, the
Azure SDK, Stripe and others). The Keycloak body is asserted by content. The hazard D-01 named is a
cached value or queued message written in the Jackson-2 shape; it had to be proven readable, or flushed
as a recorded deploy step. The outcome is in "Deploy notes": nothing needs flushing or draining.

Outcome:
- The interim Jackson-2 bridge line is removed (38-12).
- No Jackson-2 auto-configuration module, classic starter or gRPC module is on the production runtime
  classpath, and no Jackson-2 `ObjectMapper` bean exists in the context.
- `JacksonLineContractTest` guards that end state. Six bracketed arms each turned it red
  (`ev:38-12-jackson-end-state.txt`).
- Spring Data registers its two Jackson-2 modules (`GeoModule`, `PageModule`) as beans. With no
  Jackson-2 mapper, nothing uses them, so they are inert (`ev:38-12-jackson-end-state.txt`).
- The test tree keeps Jackson 2 in exactly 3 files, each marked `DELIBERATE-JACKSON2`. They emulate a
  Boot-3.5 pod for the compatibility proofs (`ev:38-19-test-jackson3-sweep.txt`).
- Keycloak offboarding PUTs the real representation with `enabled:false`, asserted by content
  (`ev:38-07-jackson-request-response.txt`).
- Jackson 3 is floored at 3.1.7 by `jackson-bom.version` (Boot manages 3.1.5). Jackson 2 is floored at
  2.22.3 by `jackson-2-bom.version` (Boot manages 2.21.5). Both keys are proven load-bearing by
  near-miss arms (`ev:38-15-cve-floors.txt`).

### The Jackson-defaults decision (owner, 2026-10-05; exact words "jackson3-defaults (Recommended)")

38-05 compared Boot 4's `JsonMapper` with the 15 Jackson-2 golden fixtures that a Boot 3.5.16 pod
wrote. The result: **15 tree-equal, 11 raw-equal, 4 raw-unequal, and no JSON pointer differs
anywhere.** The four raw-unequal shapes are OrderDto, ProductDto, ShopDto and the 401 ProblemDetail. They
differ in property order only. Verdict: `VALUE-DIFF`, input side only
(`ev:38-05-jackson-wire-diff.md` §2, §7). The owner chose to keep Jackson 3's defaults: no `spring.jackson`
key is set. `Jackson3WireContractTest` (12 tests) locks the result, and four arms turned it red
(`ev:38-05-jackson-wire-diff.md` §10).

### D-02 — Explicit per-module starters, not the classic starter (owner, 2026-10-01)

The classic starters (`spring-boot-starter-classic`, `-test-classic`) pull Boot's gRPC auto-configuration
modules without gRPC itself. On a filter chain with CSRF enabled, that throws `NoClassDefFoundError`.
Instead, core-java declares each module it uses: webmvc, data-jpa, flyway, aspectj, security,
security-oauth2-resource-server, validation, actuator, restclient, data-redis, cache, amqp, websocket,
mail, webflux, webclient and zipkin, plus the test slices. Flyway liveness had to be proven.

Outcome:
- All 67 migrations apply. Removing the Flyway module gives 0 of 67 and turns `RlsContractTest` 4/7 red
  (`ev:38-03-boot4-tracer.txt`, `ev:38-04-suite-and-liveness.txt`).
- `Boot4ModuleLivenessIntegrationTest` is a permanent check, on a real context, that Flyway applied
  every migration and that the Brave tracer with its Zipkin sender, the Prometheus registry, the HTTP
  client builders and both state-machine factories exist. 38-12 added a sixth probe: the Jackson-3 mapper
  exists and no Jackson-2 mapper bean does. The arms showed the probes failing: Flyway gives 0 of 67, a
  no-op Tracer is caught, and a missing restclient fails the context (`ev:38-04-suite-and-liveness.txt`,
  `ev:38-12-jackson-end-state.txt`).
- On the end-state commit, the explicit set activates **130** auto-configurations. A classic swap of the
  same commit activates **133**. The 3 classic-only ones are intended absences: Gson, an empty Spring
  Integration metrics placeholder, and the Jackson-2 mapper (`ev:38-13-census.txt`).

### D-03 — Keep spring-statemachine 4.0.2 (owner, 2026-10-01)

core-java keeps spring-statemachine 4.0.2 and adds `org.springframework.security:spring-security-access`
for the library's `org.springframework.security.access.*` references. The risk is recorded under
"Consequences". Replacing the library is a separate, non-blocking decision, also described there.

### D-04 — 401s carry a plain `WWW-Authenticate: Bearer` (owner, 2026-10-01)

The API must not advertise a `resource_metadata` URL that it does not serve.
`ProblemDetailAuthenticationEntryPoint` keeps the framework's delegate, so the status and the
`error`/`error_description`/`error_uri` parameters stay Spring's. An RFC 7235 auth-param parser drops
only `resource_metadata`. The challenges are once again Boot 3.5's: `Bearer` for a missing token, and
`Bearer error="invalid_token", …` for a bad one (`ev:38-06-security.txt`).

### D-05 — Suppress `/.well-known/oauth-protected-resource` (owner, 2026-10-04; refined 2026-10-05)

Spring Security 7.1 always registers `OAuth2ProtectedResourceMetadataFilter`. Its default body claims
`tls_client_certificate_bound_access_tokens: true`, which is false for this API. The ruling is to
suppress the path and **not** serve corrected metadata.

At the 38-06 decision checkpoint the owner refined the answer (exact words **"anon-401-parity
(Recommended)"**). Boot 3.5.16 measured 401 for a caller with no credentials, and 404 only for an
authenticated caller (`ev:38-02-baselines.txt`). So:

| Caller | Answer on Boot 4 | Same as Boot 3.5? |
|---|---|---|
| No credentials | **401**, plain `Bearer`, the 401 problem document (same status, headers and bytes as any protected route) | yes |
| Credentials present (a bearer the resolver accepts, or an authenticated session) | **404** `errors/not-found` | yes |
| Well-formed but invalid or expired bearer | **404** | **no**: 3.5 gave `401 Bearer error="invalid_token"` (the recorded residual) |
| Anyone, if the suppression filter were removed | 200 with the false metadata (arm R) | must never happen |

The residual exists because `ProtectedResourceMetadataSuppressionFilter` answers before authentication.
It is a contract note, not a defect (`ev:38-06-security.txt`, "RESIDUAL").

### Out of scope, explicitly: RFC 9728 protected-resource metadata

This API serves no RFC 9728 metadata, correct or otherwise. Serving it would be an agent-readiness
feature, and it needs its own decision.

## Contract changes a client can observe

These are the changes the migration makes to the wire. The server's validation is unchanged, and no
endpoint was added or removed.

1. **Alphabetical key order on class-based responses.** Jackson 3 sorts properties alphabetically by
   default. OrderDto, ProductDto, ShopDto and the 401 ProblemDetail come out in a different key order;
   their values are identical. Records keep declaration order and are byte-identical
   (`ev:38-05-jackson-wire-diff.md` §2). A client that parses JSON sees no difference. A client that
   compares raw bytes does.
2. **Trailing content after a request body is a 400.** On Boot 3.5, `{"name":"a"} {"name":"b"}` was read
   as the first object, and the trailing value was silently dropped. On Boot 4 the request is answered
   `400 errors/unreadable-request` ("Malformed or unreadable request body"). This was measured on the
   real HTTP path (`ev:38-05-jackson-wire-diff.md` §4). ImageAnalysisService has its own lenient reader
   for model output, so this does not affect AI responses (`ev:38-07-jackson-request-response.txt`).
3. **`/.well-known/oauth-protected-resource`**: 401 without credentials, 404 with them, and 404 for a
   well-formed invalid or expired bearer where 3.5 gave 401 (D-05 above).
4. **Broker dates.** AMQP bodies written by Boot 4 carry ISO-8601 dates, Jackson 3's default
   (`ev:38-07-jackson-request-response.txt`, `ev:38-09-cache.txt`). Boot 3.5 wrote epoch decimals
   such as `"timestamp":1791117296.123456789` and dropped the offset (the 38-01 golden fixture
   `core-java/src/test/resources/jackson2-golden/amqp/OrderStateChangeEvent.body`). Boot-3.5 readers
   accept both forms; 38-08's reverse cases prove it for all six payloads (`ev:38-08-amqp-outbox.txt`).
5. **OpenAPI document.** The snapshot is regenerated on springdoc 3.1.1 (`ev:38-14-openapi.md`):
   - **18** request fields across **11** DTOs became `required`. Each matches a `@NotBlank`/`@NotEmpty`
     that Boot 3.5 already enforced with a 400 (`ev:38-02-request-body-constraints-boot35.tsv`).
   - `@Email` fields gain `format: email`.
   - The `HttpStatus` enum in the spec lost its deprecated constants. That is Spring Framework 7's own
     enum, not a springdoc change.
   - No server behaviour changed.

Settings Boot 4 had silently dropped are restored, with values unchanged. 38-11 renamed the **18**
unbound keys (`ev:38-11-config-keys.txt`):
- the Zipkin endpoint: `ZIPKIN_ENDPOINT` drives `management.tracing.export.zipkin.endpoint` again;
- `spring.web.error.*` error-detail settings in base, staging and prod;
- prod and staging log retention under `logging.logback.rollingpolicy.*`.

Without the renames these reverted to Boot's defaults. `scripts/check-boot-config-keys.sh` is now a CI
gate: 0 clean, 1 bad key, 2 VOID (`ev:38-11-config-keys.txt`).

## Deploy notes (operators: read before rolling out the first Boot-4 image)

- **No Redis flush.** Every cache key now starts with a `v4:` format version (`v4:{region}::tenant:…`),
  so a Boot-4 pod never reads a 3.5-era entry. Old entries expire by their TTLs (products 10 min, shops
  15 min, shopMembership 5 min), which bounds them at 15 minutes. A real-Redis test serves the database
  value beside a planted Boot-3.5 entry and keeps `jtoye.cache.errors` at 0. Without the prefix, all
  three Boot-3.5 values fail with `MismatchedInputException` and the error counter moves from 0 to 1
  (`ev:38-09-cache.txt`). The SEC-4 polymorphic-type allowlist was re-derived from the bytes Jackson 3
  writes and **narrowed** to `uk.jtoye.`, `java.util.` and `java.math.` (`ev:38-09-cache.txt`).
- **No queue drain.** In-flight AMQP messages and PENDING outbox rows written by Boot 3.5 are readable
  by Boot 4, and Boot-4 output is readable by Boot-3.5 pods. This is proven for all six payloads, both
  persisted forms and both directions: 24 cases, each direction with a negative control. A raw Boot-3.5
  message is dispatched by `__TypeId__` on a real RabbitMQ 4.3.4 broker. The trusted-package list is
  byte-identical (`ev:38-08-amqp-outbox.txt`).
- **Idempotency keys reserved before the deploy keep matching.** `IdempotencyJson` is a frozen mapper
  that reproduces the Jackson-2 bytes: all 7 golden fingerprints and all 4 stored response bodies,
  byte for byte. A key reserved on Boot 3.5 replays its original 201 on Boot 4 under the NOSUPERUSER
  role, not the 422 "same key, different body" (`ev:38-10-idempotency.txt`). Changing that format in
  future needs a dual-hash window; never edit the mapper in place.
- **DSAR acknowledgements and jsonb columns read back unchanged** (`ev:38-10-idempotency.txt`).
- **`/.well-known/oauth-protected-resource`** gives 401 without credentials and 404 with them, and every
  401 carries a plain `Bearer` challenge with no `resource_metadata` (D-04, D-05 as refined). A smoke
  check that expects 404 for an anonymous `curl` is wrong; expect 401.
- **The image gate runs after merge.** The Trivy image gate is in the post-merge `build-and-push` job.
  It was run ahead of merge with CI's Trivy 0.70.0 and flags: the branch image and origin/main's image
  both returned rc=0 on the same DB (UpdatedAt 2026-10-05 13:07 UTC). The Trivy DB moves daily, so the
  post-merge run may see a newer one (`ev:38-15-cve-floors.txt`). CVE floors held under Boot 4:
  - Tomcat 11.0.26 (Boot manages 11.0.24). The gate needs at least 11.0.25; 11.0.26 is held for
    Tomcat's advisory-only CVE-2026-76183 and CVE-2026-86350, which Trivy does not carry.
  - amqp-client 5.34.0 (Boot manages 5.30.0, which has four HIGH).
  - The two Jackson floors above.
  - No netty pin: Boot's 4.2.17.Final is clean. A 4.1.x pin would put a 4.1 netty under a 4.2-line
    reactor-netty.
- **No database migration.** Phase 38 adds no Flyway migration; the schema head stays V67
  (`git diff` of `core-java/src/main/resources/db/` across the phase is empty, `ev:38-16-docs.txt`).

## Rollback notes (returning to a Boot-3.5 image)

- **Cache.** 3.5 pods read and write their own un-prefixed keys and never see the `v4:` entries. Any
  3.5-era entry still inside its TTL is in the format 3.5 wrote, so it is read normally. No flush is
  needed in either direction.
- **Broker and outbox.** Jackson-3 messages and outbox rows written by Boot 4 are readable by a
  Jackson-2 reader built as Boot 3.5 built its beans. 38-08's reverse cases prove this for all six
  payloads (`ev:38-08-amqp-outbox.txt`).
- **Idempotency.** The frozen fingerprint is the 3.5 format, so keys reserved on Boot 4 replay on 3.5
  (`ev:38-10-idempotency.txt`).
- **Configuration and `/.well-known`.** A 3.5 image carries its own `application*.yml` inside its jar,
  and on 3.5 the path answers 401 or 404 as it always did.
- **Database.** No migration was added, so the Flyway history needs no repair. **Unverified:** whether
  Flyway 11 (Boot 3.5) accepts a history table after Flyway 12 has validated it. No pending migration
  means Flyway 12 only validates, but this was not measured; rehearse it before relying on a rollback.

## Consequences

- Boot is back inside its support window (to 2027-07-31). The horizons row is on cycle 4.1 with no
  exemption, and the doc-versions gate follows the plugin. A half-bumped build file exits 2 at H-5,
  and a stale version claim exits 1 (`ev:38-16-docs.txt` §5).
- Jackson 2 remains on the runtime classpath for transitive users only. Its floor has to be maintained
  until those libraries move to Jackson 3.
- The persisted idempotency format is frozen. Any future change needs a deliberate dual-hash window.

### Risk: spring-statemachine 4.0.2 has no Spring Framework 7 release (D-03)

The order and vendor-onboarding state machines run on a library that was not built for the framework
underneath it. What 38-04 proved:
- **28 of 28** state-machine tests and the **4** transition-driving integration classes pass on
  Framework 7.0.9 (`ev:38-04-suite-and-liveness.txt` §3).
- The library logged the guarded APPROVE and GO_LIVE transitions.
- A linkage-error scan finds 0 errors, and 2 under the access-jar arm, so the scan can fire
  (`ev:38-04-suite-and-liveness.txt`).

jdeps over the production classpath finds **16** distinct `org.springframework.security.access` classes
in **37** reference lines (23 to `spring-security-access-7.1.1.jar`, 14 to `spring-security-core`).
The spike's "24" could not be reproduced, because its command was not recorded
(`ev:38-04-suite-and-liveness.txt` §3a). `StatemachineSecurityAccessTest` loads all 16 from the
production classpath alone. Without `spring-security-access`, its 9 classes in that jar fail to load.

What it does **not** prove: that every code path inside the library is Framework-7 safe. Only the paths
the tests and the running app exercise are covered.

**The replacement is a separate, non-blocking decision, not taken here.** Each machine could become an
`EnumMap` transition table, roughly 60-80 lines per machine (the owner's estimate in `38-CONTEXT.md` D-03, not a measurement), with the existing tests as the
specification. That is worth doing if the library breaks on a Framework 7.x patch, if a CVE lands in it
with no fix, or if Boot drops `spring-security-access`. It is tracked in `.planning/codebase/CONCERNS.md`.

## Revisit triggers (any one is sufficient)

- spring-statemachine publishes a Framework-7 release, or a Framework 7.x / Boot 4.x patch breaks it.
- The Boot 4.1 horizon (2027-07-31) comes inside the horizons gate's 90-day warning window.
- A decision is taken to serve RFC 9728 metadata.
- The last transitive Jackson-2 user moves to Jackson 3, and the Jackson-2 floor can go.
- A client is found that depends on raw key order or on trailing request content.
