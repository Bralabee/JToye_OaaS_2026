---
phase: "38"
slug: "spring-boot-4-1-migration"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 2
block_on: medium
created: "2026-10-05"
---

# Phase 38 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Register authored at plan time (every one of the 19 plans carries a `<threat_model>`); verified at ASVS L2 by
> `gsd-security-auditor` against the production code each mitigation names AND the test that guards it, after the
> code-review fixes WR-01 (`c327360d`) and WR-02 (`01a67384`).
> Path roots: `main/` = `core-java/src/main/java/uk/jtoye/core/`, `test/` = `core-java/src/test/java/uk/jtoye/core/`,
> `ev/` = `.planning/phases/38-spring-boot-4-1-migration/evidence/`.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| deploy boundary (Boot 3.5 → Boot 4 pods) | bytes written by old pods are read by new ones during a rolling update | Redis cache entries, AMQP messages, outbox rows, idempotency hashes (PII in response_body) |
| client → API | untrusted JSON crosses into Jackson 3 + Bean Validation | request bodies |
| anonymous client → security filter chain | 401 challenge and `/.well-known` responses reach anyone | auth metadata |
| RabbitMQ / Redis → core-java | `__TypeId__` headers and polymorphic cache type ids name classes to instantiate | serialized objects (gadget risk) |
| core-java → Keycloak admin API | privileged user disable on tenant offboard | account lifecycle state |
| Maven Central / base image → shipped image | third-party code with known CVEs | jars (Tomcat, Jackson 2/3, Boot modules) |
| configuration → framework | keys Boot 4 no longer binds silently revert to defaults | error detail, log retention, tracing endpoint |
| published OpenAPI → integrators | contract tells clients what the server enforces | API schema |
| local branch → origin / CI | publishing triggers image builds | images |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-38-01 | Tampering | jackson2-golden fixtures | medium | mitigate | `test/boot4/GoldenFixturesIntegrityTest.java:49-89` (sha256 + length MANIFEST, Jackson-free); generator absent; capture commit `e12177e1` pinned in README | closed |
| T-38-02 | Information disclosure | fixture contents | low | accept | Synthetic data only (all emails `@example.test`) — see Accepted Risks | closed |
| T-38-03 | Tampering | request-body validation | medium | mitigate | `test/boot4/RequestBodyConstraintEnforcementTest.java:116-142` (unit task; fails by name on a missing `@Valid`) | closed |
| T-38-04 | Information disclosure | 401 challenge / metadata path | medium | mitigate | Exact `WWW-Authenticate` assertions `test/security/UnauthenticatedProblemDetailIntegrationTest.java:123,196`; baseline `ev/38-02-baselines.txt` | closed |
| T-38-05 | Elevation of privilege | Flyway/RLS bootstrap | high | mitigate | flyway starter `build.gradle.kts:244`; `test/boot4/Boot4ModuleLivenessIntegrationTest.java:93-113`; arm A 0/67 + RlsContractTest red (`ev/38-04-suite-and-liveness.txt:107-120`) | closed |
| T-38-06 | Tampering | classic starter gRPC auto-config | medium | mitigate | `test/boot4/JacksonLineContractTest.java:91-97,139-168` forbids classic/grpc jars on the production classpath; fails closed (`build.gradle.kts:406-410`) | closed |
| T-38-07 | Elevation of privilege | Tomcat 11.0.24 CVEs | high | mitigate | `build.gradle.kts:71` pins 11.0.26; dependencyInsight + Trivy arms (`ev/38-15-cve-floors.txt:197-259`) | closed |
| T-38-08 | Tampering | Jackson BOM key semantics | high | mitigate | `build.gradle.kts:222` jackson-2-bom 2.22.3, `:231` jackson-bom 3.1.7; near-miss arms red (`ev/38-15:184-242`) | closed |
| T-38-09 | Elevation of privilege | Flyway module absent → no RLS | high | mitigate | As T-38-05 (liveness test + arm A) | closed |
| T-38-10 | DoS / Tampering | state machines on Framework 7 | medium | mitigate | `test/boot4/StatemachineSecurityAccessTest.java`; arms SM-A/SM-B red; linkage scan (`ev/38-04:229-284`) | closed |
| T-38-11 | Tampering | request acceptance after Jackson 3 | medium | mitigate | `test/boot4/Jackson3WireContractTest.java:228-260` (trailing token, real-HTTP 400, null→primitive) | closed |
| T-38-12 | Repudiation / Integrity | webhook envelope + outbox shape | medium | mitigate | `test/webhook/WebhookFanoutListenerEnvelopeTest.java:105-125`; HMAC over delivered bytes `main/webhook/WebhookDeliveryWorker.java:186-197`; ADR-0006:66-74 | closed |
| T-38-13 | Information disclosure / Spoofing | OAuth2ProtectedResourceMetadataFilter default body | medium | mitigate | `main/security/ProtectedResourceMetadataSuppressionFilter.java:87-128` registered `SecurityConfig.java:283`; order asserted `UnauthenticatedProblemDetailIntegrationTest.java:328-345`; arm R; live `ev/38-17-runtime.txt:453-471` | closed |
| T-38-14 | Spoofing | resource_metadata in every 401 | medium | mitigate | `main/security/ProblemDetailAuthenticationEntryPoint.java:94,241-243` (sole challenge emitter); pass-through arm; live doctored arm `ev/38-17:519-520` | closed |
| T-38-15 | Tampering | header rewriting | low | mitigate | RFC 7235 parser `ProblemDetailAuthenticationEntryPoint.java:126-189`; table test `test/security/ProblemDetailAuthenticationEntryPointTest.java:109-161` | closed |
| T-38-16 | Information disclosure | suppression 404 security headers | low | mitigate | After HeaderWriterFilter + CorsFilter; headers asserted `UnauthenticatedProblemDetailIntegrationTest.java:203-269`; arm H | closed |
| T-38-17 | Elevation of privilege | KeycloakAdminClient.setUserEnabled | high | mitigate | `main/tenant/keycloak/KeycloakAdminClient.java:155-171`; `test/tenant/keycloak/KeycloakAdminClientTest.java:151-209`; L2 boundary closed by live read-back `ev/38-17:661-705` | closed |
| T-38-18 | Tampering | ImageAnalysisService model text | low | mitigate | Dedicated lenient reader `main/ai/ImageAnalysisService.java:121`; no polymorphic typing in main | closed |
| T-38-19 | Repudiation | webhook envelope vendors verify | medium | mitigate | As T-38-12 | closed |
| T-38-20 | Tampering / EoP | AMQP `__TypeId__` class loading | high | mitigate | `main/config/RabbitMQConfig.java:404-408,433` exact trusted list; `test/config/RabbitMQConfigMessageConverterTest.java:147-163`; arm B refuses `java.net.URI` | closed |
| T-38-21 | Denial of service | in-flight messages / PENDING outbox after deploy | high | mitigate | 6 payloads × both forms × both directions (`AmqpJackson2CompatibilityTest`, `OutboxPayloadCompatibilityTest`) + real-broker `AmqpTypeIdDispatchIntegrationTest` | closed |
| T-38-22 | Tampering | malformed outbox payload | low | mitigate | `JacksonException` catch before `Exception` in `main/payment/PaymentEventOutboxFlusher.java:312-326`, `main/media/MediaEventOutboxFlusher.java:228-241`; arm C | closed |
| T-38-23 | Elevation of privilege | polymorphic cache deserialization | high | mitigate | `main/config/CacheConfig.java:219-231,250-253` subtype-only narrowed allowlist; no unsafe typing; `test/config/CacheSerializerTypeAllowlistTest.java:341-385` | closed |
| T-38-24 | DoS / Integrity | Jackson-2-era cache entries | medium | mitigate | `v4:` prefix `CacheConfig.java:105,134`; `test/boot4/CacheFormatIsolationIntegrationTest.java:163-198`; arm P; holds after WR-01 (legacy key only deleted, never read) | closed |
| T-38-25 | Information disclosure | cross-tenant cache keys | medium | mitigate | `TenantAwareCacheKeyGenerator` unchanged; WR-01 legacy key keeps the tenant segment (`main/config/TenantCacheEvictor.java:152-190`); other-tenant entries survive byte-identical (`CacheFormatIsolationIntegrationTest.java:218-265`) | closed |
| T-38-26 | Integrity / DoS | idempotency hash drift across deploy | high | mitigate | `main/common/idempotency/IdempotencyJson.java:59-72` sole writer/reader; golden `IdempotencyFingerprintGoldenTest.java:101-129`; after WR-02 7 hashes + 4 bodies byte-identical, constructor cases `:194-210` red before the fix | closed |
| T-38-27 | Information disclosure | replayed response_body across tenants | high | mitigate | `IdempotencyLegacyHashReplayIntegrationTest.java:131,154-180` as NOSUPERUSER `rls_test_role` with tenant-B control | closed |
| T-38-28 | Tampering | jsonb columns under new Hibernate mapper | low | mitigate | `test/boot4/JsonbColumnsReadBackIntegrationTest.java:154-194` | closed |
| T-38-29 | Information disclosure | prod error detail keys | medium | mitigate | `application-prod.yml:75-79` never; `RenamedConfigKeysBindingTest.java:89-98`; `ConfigKeyContractTest.java:227`; CI gate | closed |
| T-38-30 | Repudiation | log retention / Zipkin endpoint | medium | mitigate | `application-prod.yml:103-108`, `application.yml:563`; `RenamedConfigKeysBindingTest.java:109-174` | closed |
| T-38-31 | Tampering | test autoconfigure excludes | low | mitigate | `ConfigKeyContractTest.java:236-248` | closed |
| T-38-32 | Tampering | silent return of a Jackson-2 path | medium | mitigate | `JacksonLineContractTest.java:139-304`; bridge line removed | closed |
| T-38-33 | Elevation of privilege | classic/gRPC reintroduced | medium | mitigate | As T-38-06 | closed |
| T-38-34 | DoS / Repudiation | silently absent auto-configuration | medium | mitigate | `test/boot4/AutoConfigurationCensusIntegrationTest.java:89-128,172`; `ev/38-13-census.txt` | closed |
| T-38-35 | Tampering / Repudiation | contract change hiding behaviour change | medium | mitigate | Doctored-pair arms `ev/38-14-openapi.md:145-156`; oasdiff sha256 = `ci-cd.yaml:776` | closed |
| T-38-36 | Information disclosure | introspection schemas in OpenAPI | low | accept | Pre-existing; see Accepted Risks (wording corrected per audit) | closed |
| T-38-37 | Elevation of privilege | managed versions below CVE floor | high | mitigate | dependencyInsight + arms A–E (`ev/38-15:155-259`); CI Trivy gate green (`ev/38-18-ci-and-nightly.txt:119-124`) | closed |
| T-38-38 | Tampering | renamed BOM keys | high | mitigate | Near-miss arms per key (`ev/38-15:184-242`) | closed |
| T-38-39 | Repudiation / Tampering | deploy/rollback instructions | low | mitigate | `docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md:175-259` cites 38-08/09/10/15; updated for WR-01 | closed |
| T-38-40 | Elevation of privilege | offboarded users left enabled (live) | high | mitigate | Live read-back: probe `enabled=false`, control unchanged, garbage-key arm (`ev/38-17:661-705`) | closed |
| T-38-41 | Tampering / DoS | rebuilding a shared stack | medium | mitigate | Owner decision `ev/38-17:14-15`; positive control + fail arm `:37-69`; VOID arm `:337-354` | closed |
| T-38-42 | Elevation of privilege | KC_ADMIN_ENABLED / throwaway users left | medium | mitigate | `printenv` false (arm true); 4 users re-read 404 (`ev/38-17:732-749`) | closed |
| T-38-43 | Tampering | pushing an unreviewed branch | medium | mitigate | Owner decision; behind-base PASS with arms; ff push, no force/no-verify (`ev/38-18:6-45`) | closed |
| T-38-44 | Repudiation | green badge over an empty nightly | medium | mitigate | headSha-keyed run resolution; report.json 325/319/0/6 (`ev/38-18:48-56,136-171`) | closed |
| T-38-45 | Repudiation | migrated tests loosening assertions | medium | mitigate | Verify 6: 39 files base == head with fail arm (`ev/38-19-test-jackson3-sweep.txt:462-505`) | closed |
| T-38-46 | Tampering | golden/OpenAPI regenerated to absorb change | medium | mitigate | No change under resources/docs/api in 38-19 (`ev/38-19:507-512`); review fixes touched no fixture | closed |
| T-38-47 | Elevation of privilege | hidden dependency on the bridge | low | mitigate | Bridge-off probes; tracer fails as it should (`ev/38-19:26-62,218`) | closed |
| T-38-48 | Repudiation | interim version-gate red | low | mitigate | rc 2 recorded then cleared to 0 (`ev/38-16-docs.txt:13-18,156,218`) | closed |
| T-38-49 | Tampering | half-bumped build / expired framework row | medium | mitigate | `infra/dependency-horizons.yaml:495-504`; arms C–F (`ev/38-16:176-191`) | closed |
| T-38-SC | Tampering | supply chain (19 per-plan entries) | high | mitigate | No npm/pip/go/cargo change; every new Maven coordinate official Spring/springdoc/resilience4j; oasdiff sha-verified; Trivy 0.70.0; gitleaks allowlist narrow (see flag 3) | closed |

*Status: open · closed · open — below medium threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-38-01 | T-38-02 | Golden fixtures contain only synthetic UUIDs, names and `@example.test` emails from GoldenSamples; no tenant or customer data is read or written. | plan 38-01 threat model; confirmed by audit | 2026-10-05 |
| AR-38-02 | T-38-36 | The introspection schemas (ApplicationContext, ServletContext, RedirectView) pre-date this phase. On springdoc 3.1.1 the generator EXPANDS them with JDK ClassLoader/Package/Module bean properties — JDK structure only; the schema-name set (108 keys) and top-level property names are unchanged, and no `jtoye` type appears (`ev/38-14-openapi.md:206`, class A). Expanded by the generator, not added. Their removal is a separate change. | plan 38-14 threat model; wording corrected by audit | 2026-10-05 |

*Accepted risks do not resurface in future audit runs.*

---

## Unregistered Flags (audit 2026-10-05)

Raised by the auditor outside the plan-time register. None reopens a registered threat; none is counted in `threats_open`.

1. **Asynchronous primary cache eviction (WARNING; auditor estimate low).** Spring Data Redis 4 makes `cache.evict` fire-and-forget: `main/config/TenantCacheEvictor.java:153`. The integration test polls up to 5 s for the v4 key to disappear (`CacheFormatIsolationIntegrationTest.java:250-257`). After a `shopMembership` revoke commits, there is a short window before the DEL lands, and an async DEL failure never reaches `RedisCacheErrorHandler`. Already recorded as "needs a decision" in `38-REVIEW-FIX.md:182-203`. Options: `evictIfPresent` on the primary key (the WR-01 legacy path already uses it), or `RedisCacheWriter.create(cf, c -> c.immediateWrites())`, either with a test. **Owner decision.**
2. **IN-03, the suppression 404 `instance` reflects `getRequestURI()`; maps to T-38-13/T-38-16 and does not reopen them.** The exposure is small:
   - Unauthenticated callers never see it.
   - `URI.create` rejects `<`, `>`, `"` and space.
   - Jackson escapes the rest.
   - The body is `application/problem+json` with nosniff and X-Frame-Options asserted.
   - No context path is configured.

   `GlobalExceptionHandler`'s own 404 reflects the same value, so this is a parity issue only.
3. **38-18 gitleaks allowlist (T-38-SC), narrow.** It uses three mechanisms:
   - the exact value `^golden-guest-key-0001$` with `regexTarget=secret` (`.gitleaks.toml:85-91`);
   - one fingerprint (`.gitleaksignore:48`);
   - one inline `gitleaks:allow` on `38-05-SUMMARY.md:146`.

   It was proven in both directions on gitleaks 8.30.1. CI pins 8.27.2, so the PR's gitleaks job is the first reading on that version.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-10-05 | 50 (T-38-01..49 + T-38-SC) | 50 | 0 | gsd-security-auditor (ASVS L2, block_on medium) |

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-10-05
