---
phase: 38
phase_name: "Spring Boot 4.1 Migration"
project: "J'Toye OaaS"
generated: "2026-10-05T19:51:34Z"
counts:
  decisions: 9
  lessons: 9
  patterns: 8
  surprises: 8
missing_artifacts:
  - "38-UAT.md"
---

# Phase 38 Learnings: Spring Boot 4.1 Migration

## Decisions

### Jackson 3 now, with Boot's Jackson 3 defaults kept (no spring.jackson key)
Main code moved to `tools.jackson` (D-01) and the deprecated `preferred-json-mapper=jackson2` was not set. At the 38-05 checkpoint the owner chose "jackson3-defaults" after a measured diff, accepting two contract changes: alphabetical key order on class-based responses and a 400 on trailing tokens. `Jackson3WireContractTest` locks the result.

**Rationale:** The Jackson-2 serializers are deprecated for removal; the diff showed only key order (tree-equal) and trailing-token strictness changed, so restoring Jackson-2 defaults was not worth carrying.
**Source:** 38-CONTEXT.md, STATE.md (38-05)

---

### Explicit per-module starters instead of the classic starters
D-02 declares each module the app uses (webmvc, data-jpa, flyway, security, restclient, zipkin, etc.) rather than `spring-boot-starter-classic`. 38-13 measured it against a classic baseline: 130 auto-configurations active versus 133, with 3 classic-only entries all intended (Gson, an empty Spring Integration placeholder, Jackson 2).

**Rationale:** Classic pulls in gRPC auto-config modules without gRPC, which throws `NoClassDefFoundError` on any chain with CSRF enabled; the census proved no capability was lost.
**Source:** 38-CONTEXT.md, 38-13-SUMMARY.md

---

### Keep spring-statemachine 4.0.2 and add spring-security-access
D-03 keeps the library on Framework 7 (it has no Framework-7 release) and adds `spring-security-access` for its 24 latent `security.access` references. An EnumMap replacement stays a separate, non-blocking decision, recorded in ADR-0006 and CONCERNS.md.

**Rationale:** The spike showed 28 state-machine tests and 14 full-context classes drive real transitions on 7.0.9; replacement is optional risk reduction, not a blocker.
**Source:** 38-CONTEXT.md, 38-16-SUMMARY.md

---

### Suppress /.well-known/oauth-protected-resource, refined to "anon-401-parity"
D-05 suppresses Security 7.1's always-on metadata endpoint with a non-bean filter after CorsFilter: no credentials gives the standard 401, credentials present gives the 404 not-found document. No corrected RFC 9728 metadata is served. The residual (a well-formed invalid bearer gets 404 where 3.5 gave 401) is pinned by a test.

**Rationale:** The default body falsely claims `tls_client_certificate_bound_access_tokens: true`; measured Boot 3.5 gave 401 to anonymous callers, so the original "404 matches 3.5" premise was only half true.
**Source:** 38-CONTEXT.md, 38-06-SUMMARY.md

---

### Plain Bearer challenge on 401 (no resource_metadata)
D-04: `ProblemDetailAuthenticationEntryPoint` strips `resource_metadata` from Security 7's `WWW-Authenticate` challenge with an auth-param parser in a response wrapper, so the API never advertises a URL it does not serve.

**Rationale:** Serving RFC 9728 metadata is explicitly out of scope; advertising it without serving it is a false claim.
**Source:** 38-CONTEXT.md, STATE.md (38-06)

---

### Version the Redis cache keys instead of flushing
`CACHE_KEY_FORMAT_VERSION = "v4"` prefixes every region (`v4:{region}::`). Boot-3.5 values are unreadable by the new serializer (`MismatchedInputException`), so old keys are simply never read and expire by TTL (at most 15 min); no deploy flush step.

**Rationale:** Removes the need for an operator step; `CacheFormatIsolationIntegrationTest` plants the golden 3.5 bytes under the old key and asserts `jtoye.cache.errors` stays 0 (and goes to 1 with the prefix removed).
**Source:** 38-09-SUMMARY.md, STATE.md (38-09)

---

### Freeze the idempotency persisted format in its own mapper
`IdempotencyJson` owns `request_hash` and `response_body` using `builderWithJackson2Defaults()` minus Boot 3.5's date, unknown-property and view settings; `IdempotencyService` injects no mapper. Any future change to it needs a dual-hash window.

**Rationale:** A plain Jackson 3 mapper would have broken stored hashes for orders.create, both guest-order fingerprints and the OrderDto body; the persisted format must not follow the app-wide mapper.
**Source:** 38-10-SUMMARY.md, STATE.md (38-10)

---

### Rename the 18 silently ignored config keys and add a CI gate
All 18 keys were renamed with unchanged values, and 13 Boot-3 autoconfigure excludes were renamed to Boot-4 FQCNs. `ConfigKeyContractTest` plus `scripts/check-boot-config-keys.sh` (the last step of the CI test job) fail on an unknown or deprecated key.

**Rationale:** CLAUDE.md required a gate for the whole class, proven failing on an injected bogus key; the script runs last because its cleanTest/--rerun would replace the uploaded unit results and JaCoCo data.
**Source:** 38-CONTEXT.md, 38-11-SUMMARY.md

---

### Rebuild and take over the shared compose stack, and stay on the Boot-4 branch
The owner chose "takeover-from-worktree" and "Stay on Boot-4" at the 38-17 checkpoint; all four built services were rebuilt with `--no-deps --force-recreate` from the worktree and proven by content (jar contents, sha256 of the six application ymls).

**Rationale:** Runtime parity: read the Boot version and config out of the running artifact, not the source tree. `--force-recreate` follows the measured cached-build trap.
**Source:** 38-17-SUMMARY.md, STATE.md (38-17)

---

## Lessons

### A Jackson-2 JsonNode through the Jackson-3 RestClient sends garbage
`KeycloakAdminClient.setUserEnabled` sent a Jackson-2 `ObjectNode` through the RestClient; the wire body became `{"array":false,...,"nodeType":"OBJECT"}` with no `enabled` field, so tenant offboarding would have PUT garbage. Only `KeycloakAdminClientTest` caught it, and the fix was proven by content plus a live offboard read-back.

**Context:** Found in the feasibility spike and closed in 38-07; assert the wire body by content (`{"enabled":false}`).
**Source:** 38-CONTEXT.md, STATE.md (38-07, 38-17)

---

### Jackson 3 sorts class properties alphabetically but keeps record order, so idempotency hashes silently change
Measured with arm P (plain Jackson 3 mapper): 4 golden rows and the replay went red. Only class-based fingerprints and bodies (orders.create, both guest-order fingerprints, OrderDto) would have broken; records stayed byte-identical.

**Context:** Idempotency request hashes are persisted, so any property-order change silently breaks replay of rows stored under Boot 3.5.
**Source:** 38-10-SUMMARY.md, STATE.md (38-10)

---

### Boot 3 to 4 renamed config keys are bound by nothing and silently ignored
18 keys were no longer bound (e.g. `management.zipkin.tracing.endpoint`, `server.error.include-*`, `logging.file.max-size|max-history|total-size-cap`), with no test failing. Impact: Zipkin endpoint ignored everywhere, prod 30-day/1 GB log retention reverting to defaults, error detail changing. The 13 renamed excludes also meant the Redis exclusion in the test profile had stopped applying.

**Context:** Found by checking all 211 keys against 4.1.1 configuration metadata; an injected bogus key proved the check can fail.
**Source:** 38-CONTEXT.md, 38-11-SUMMARY.md

---

### Grepping for com.fasterxml.jackson misses Spring's Jackson-2 adapters
The narrow `com.fasterxml.jackson.{databind,core,datatype}` grep could not see five Spring-adapter users (`Jackson2JsonMessageConverter`, `MappingJackson2*`, `GenericJackson2*`), and removing the bridge would not have caught them. `JacksonLineContractTest`'s scan must also match package-qualified Spring `Jackson2*` adapters.

**Context:** Found in the 38-19 test sweep, which measured bridge-off batch by batch with sha256-verified restores.
**Source:** 38-19-SUMMARY.md, STATE.md (38-19)

---

### A structurally green gate can be vacuous on a correct tree
The plan's `applied67` awk summed both XML files, so under a Flyway-module break `FreshChainMigrationIntegrationTest` (own Flyway API) kept the sum at 1; a per-file count for `RlsContractTest` was added. Likewise the spring-security-access awk printed 0 on a correct tree because Gradle renders `spring-security-access -> 7.1.1`.

**Context:** Plan-literal checks were reported next to stronger replacements, never silently substituted.
**Source:** 38-03-SUMMARY.md

---

### A doc or grep rule can fire on its own definition
AC1's `git grep '@Component'` matched rc=0 because the filter's own Javadoc said "It is NOT a @Component"; the Javadoc was reworded rather than weakening the check.

**Context:** Also seen in 38-01, where Jackson-free classes' Javadoc named the forbidden package prefixes.
**Source:** 38-06-SUMMARY.md, 38-01-SUMMARY.md

---

### The cache-key version prefix splits evictions across Boot versions (WR-01)
Boot-4 pods evict only `v4:` keys and Boot-3.5 pods only un-prefixed ones. During the RollingUpdate (replicas 3, maxSurge 1) a revoke handled by one generation leaves the other serving a stale `Membership` grant up to 5 min (products 10 min, shops 15 min). The ADR's "No flush is needed in either direction" listed only the upside.

**Context:** Review fix options: evict both key forms in `TenantCacheEvictor` for the transition release, and/or a post-rollout operator step; record the bounded staleness in the ADR. Disposition is still open.
**Source:** 38-REVIEW.md, 38-REVIEW-DISPOSITION.md

---

### IdempotencyJson is not truly the frozen Boot-3.5 format (WR-02)
Boot 3.5 registered `ParameterNamesModule`; `builderWithJackson2Defaults()` sets `DETECT_PARAMETER_NAMES=false` and the frozen mapper never re-enables it. A constructor-only DTO would fail replay (`InvalidDefinitionException`, 500) or write different bytes. Latent: all seven fingerprints and four stored types are setter-style classes or records, so the golden test is green.

**Context:** Suggested fix is to enable `MapperFeature.DETECT_PARAMETER_NAMES` now, while it is free, and add a constructor-only golden case. Disposition is still open.
**Source:** 38-REVIEW.md, 38-REVIEW-DISPOSITION.md

---

### GSD tooling traps seen in this phase
`total_plans` was one short (155 vs 156) after 38-19 was added and corrected BY HAND; 38-03's STATE commit did not advance `completed_plans`; STATE was hand-edited, with `state.record-session` and `state.update-progress` not run. `requirements.ready-ids` withheld shared requirement IDs (e.g. BOOT4-04 across 38-05..38-19) until every sharing plan was summarised, and REQUIREMENTS.md's traceability column still read "Planned" after the checkboxes were ticked. `check-dependency-horizons.sh` VOIDed on the machine's python shim, so the first run said nothing about the row.

**Context:** Trust the shared-ID gate over per-plan claims, and record a VOIDed gate run rather than reading it as a pass.
**Source:** STATE.md, 38-03-SUMMARY.md, 38-VERIFICATION.md

---

## Patterns

### Golden fixtures captured from the old runtime before migrating
Plan 38-01 had the Boot 3.5.16 production serializers write 38 Jackson-2 golden fixtures; the permanent `GoldenFixturesIntegrityTest` imports nothing from Jackson. Later plans (wire contract, AMQP, outbox, idempotency, cache) prove compatibility against those bytes in both deploy directions.

**When to use:** Any serialization-format migration where in-flight state (messages, cache entries, stored hashes) must survive the deploy.
**Source:** 38-01-SUMMARY.md, STATE.md (38-01, 38-08, 38-09)

---

### Boot 3.5 baseline captured before the change, then diffed
38-02 measured the unchanged tree: a structural oracle (55 required request-body fields across 20 DTOs, each behind `@Valid`) and the anonymous 401 behaviour. These later drove 38-14 (the 18 newly required OpenAPI fields all join to the inventory) and the D-05 refinement.

**When to use:** Before any framework upgrade, so contract changes are judged against measured behaviour rather than assumed behaviour.
**Source:** 38-02-SUMMARY.md, STATE.md (38-02, 38-14)

---

### Bracketed break arms with sha256-verified restores
Each permanent guard was shown failing: Flyway removed gives 0 of 67 migrations; Brave module removed gives a no-op Tracer; `java.math.` or `java.util.` dropped from the allowlist goes red; config-key arms A-F give 1,1,2,2,1,1. Every restore was verified by sha256 against the HEAD blob.

**When to use:** Any new gate or contract test; "now passes" is not evidence. Commit before the arms and finish with a clean run.
**Source:** 38-04-SUMMARY.md, 38-09-SUMMARY.md, 38-11-SUMMARY.md, 38-19-SUMMARY.md

---

### Liveness tests for explicit starters
`Boot4ModuleLivenessIntegrationTest` and `StatemachineSecurityAccessTest` prove each declared module is live (Flyway, Brave tracer, restclient, spring-security-access classes), and `AutoConfigurationCensusIntegrationTest` compares explicit starters against a classic baseline. A removed module goes red instead of degrading silently.

**When to use:** When replacing aggregate starters with explicit ones, where a missing module disables capability without failing the build.
**Source:** 38-04-SUMMARY.md, 38-13-SUMMARY.md

---

### Closed deliberate-exception list guarded by a permanent contract test
`JacksonLineContractTest` enforces the end state (no Jackson-2 module on the runtime classpath, and no Jackson-2 imports in main, config or tests) with a closed allowlist of 3 DELIBERATE-JACKSON2 files (AmqpJackson2CompatibilityTest, OutboxPayloadCompatibilityTest, OpenApiSnapshotTest). It was shown red on `spring-boot-jackson2-4.1.1.jar` before the bridge line was deleted.

**When to use:** Retiring an interim compatibility bridge: remove it only after a measured sweep, then lock the end state.
**Source:** 38-12-SUMMARY.md, 38-19-SUMMARY.md

---

### Derive an allowlist from the bytes actually written
The Redis polymorphic-type allowlist was re-derived from what the new serializer emits. NON_FINAL typing writes Long, UUID, OffsetDateTime and enums bare, so `java.lang.` and `java.time.` were dropped; the id set is pinned and tied to the prefix list in both directions (`uk.jtoye.`, `java.util.`, `java.math.`).

**When to use:** Security allowlists (SEC-4) that must not drift wider than the measured need after a serializer change.
**Source:** 38-09-SUMMARY.md, STATE.md (38-09)

---

### Classify RED evidence mechanically
`gsd check tdd-red-evidence` was run on each RED record and in the fail direction: a green or doctored XML returns `INVALID_RED` (unexpected_green / no_target_test_failure), a genuine target failure `RED_EVIDENCE_OK`.

**When to use:** Test-first work, and break-arm plans where the "RED" is a state that violates a decision rather than a missing implementation.
**Source:** 38-05-SUMMARY.md, 38-07-SUMMARY.md, 38-13-SUMMARY.md

---

### Verify CVE floors by near-miss pin arms and run the image gate locally before pushing
38-15 ran Trivy with the CI image-gate flags on one cache and proved each pin with near-miss arms (`jackson3-bom.version`, `tomcat-version`, amqp pin removed, `jackson2-bom.version`, a Tomcat 11.0.24 image), then ran the gate locally before any push. On the Boot-4 branch the CI gate then read 0 in the OS and 0 in app.jar.

**When to use:** Any change to dependency pins keyed by BOM property names that can silently stop matching.
**Source:** 38-15-SUMMARY.md, STATE.md (38-15, 38-18)

---

## Surprises

### Boot 3's jackson-bom.version pin re-points the Jackson 3 BOM under Boot 4
The pin `jackson-bom.version=2.21.7` makes Boot 4 import a nonexistent `tools.jackson:jackson-bom:2.21.7`, the BOM import fails and every managed version vanishes. It had to be re-keyed to `jackson-2-bom.version`.

**Impact:** The RED compile in 38-03 had a sharper cause than "starters moved"; recorded in the pin comment as the measured reason (T-38-08 observed live).
**Source:** 38-03-SUMMARY.md, 38-CONTEXT.md

---

### Spring Security 7.1 serves /.well-known metadata whether you want it or not
The `OAuth2ProtectedResourceMetadataFilter` is always registered and cannot be disabled; its default body claims `tls_client_certificate_bound_access_tokens: true`, which is false for this API. With the suppression filter removed the path returned 200 with that false claim. D-04's premise was partly wrong.

**Impact:** Required a new filter and D-05; the first anchor, `addFilterBefore(..., OAuth2ProtectedResourceMetadataFilter.class)`, was refused at build ("does not have a registered order"), so it was placed after CorsFilter.
**Source:** 38-CONTEXT.md, 38-06-SUMMARY.md

---

### Anonymous callers got 401, not 404, on Boot 3.5
D-05 said "404 matches Boot 3.5", but 38-02 measured 401 for no credentials and 404 only for authenticated callers, so the locked 404 would have changed the anonymous answer. 38-17's probe expectation (404 for an anonymous curl) also had to change.

**Impact:** The owner refined D-05 at a decision checkpoint; `*_boot35Baseline` tests, CONTEXT, BOOT4-09 and the live-probe expectations were updated.
**Source:** 38-06-SUMMARY.md, STATE.md (38-02, 38-06)

---

### Explicit starters can drop capabilities without any failure
Without the Flyway module 0 of 67 migrations apply; without the Brave module a no-op Tracer is caught; without restclient the context fails; without spring-security-access nine classes fail to load. Classic starters add gRPC autoconfig that throws `NoClassDefFoundError` under CSRF.

**Impact:** Drove D-02's liveness tests and the classic-baseline census. The classic swap itself had to ADD the classic pair rather than replace the starters, because replacing it left tomcat, lettuce, spring-rabbit and others at 0 and did not compile.
**Source:** 38-CONTEXT.md, 38-04-SUMMARY.md, 38-13-SUMMARY.md

---

### Boot-3.5 AMQP epoch-decimal dates read to the nanosecond under Jackson 3
RESEARCH A2 was "yes": the Jackson 3 converter's default mapper reads Boot-3.5 dates carried as epoch decimals, losing the offset, to the nanosecond. In-flight messages therefore survive the deploy in both directions with no drain or flush, and a raw 3.5 PaymentEvent on a real RabbitMQ 4.3.4 broker reaches its class-level `@RabbitHandler`.

**Impact:** No deploy drain step; ADR-0006 records "ISO broker dates" as a contract change.
**Source:** 38-08-SUMMARY.md, STATE.md (38-01, 38-08)

---

### springdoc 3.1.1 changed the OpenAPI snapshot in unlisted ways
The spike listed 19 schema changes, but 38-14 found the remaining 192 leaves were mostly classifiable: the `HttpStatus` enum change is Spring Framework 7's (javap: spring-web 6.2.19 vs 7.0.9), not springdoc's; `CreateRefundRequest.amountPennies` gained `exclusiveMinimum: 0` from an existing `@Positive`, an entry the spike did not list.

**Impact:** The 18 newly required fields were accepted only after joining to the 38-02 Boot-3.5 inventory; the PR body should point reviewers to the classified table rather than the raw oasdiff list.
**Source:** 38-CONTEXT.md, 38-14-SUMMARY.md

---

### Keycloak 24 strips tenant_id on admin-API user create, making the offboard proof vacuous
`POST /users` with `attributes.tenant_id` stored no attribute and the `q=tenant_id:` search returned `[]`, so the offboard would have found no user. `partialImport` was used instead (docs/security-scopes.md section 5 records the trap).

**Impact:** The live offboard read-back (user `enabled:false`, id, username and tenant_id intact, no garbage keys, control user still enabled) became a real proof.
**Source:** 38-17-SUMMARY.md, STATE.md (38-17)

---

### The first push was refused by gitleaks on fixture false positives
Pre-push P-3 refused on 3 false positives: `golden-guest-key-0001` in the byte-guarded 38-01 fixtures, and a class name in 38-05-SUMMARY prose. The fix was an exact content allowlist, a fingerprint and an inline allow, measured on gitleaks 8.30.1 only while CI pins 8.27.2.

**Impact:** One extra commit (acee00c5); the ship checklist adds a watch on the PR's 8.27.2 job.
**Source:** 38-18-SUMMARY.md, STATE.md (38-18)
