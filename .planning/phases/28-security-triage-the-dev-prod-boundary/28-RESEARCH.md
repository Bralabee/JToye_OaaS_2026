# Phase 28: Security Triage + the Dev/Prod Boundary - Research

**Researched:** 2026-08-10
**Domain:** PostgreSQL role/privilege separation under RLS · springdoc built-artifact assertion · Spring Security context propagation · S3 object-metadata backfill · credential rotation
**Confidence:** HIGH (almost everything here was measured on this tree or this running stack, with control arms)

> **Sanitization.** This document is committed to a PUBLIC repository. Findings are referenced by
> ID (A1..E1) only. No literal credential value, no DB→OAuth→header chain, no repro payload from
> `SECURITY-FINDINGS.md` or the local Strix report appears below. Env-var *key names* and DB *role
> names* appear because they are already committed in `.env.example`, compose and k8s templates.

---

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions

**DB role & rotation depth (#552 / SEC-04 remainder)**
- **D-01 — Split roles NOW.** Create a non-owner runtime role (suggested `jtoye_runtime`):
  Flyway keeps `jtoye_app` as owner/migrator; the app connects as the new role with DML-only
  grants. Isolation stops depending on FORCE RLS being remembered on every future table.
  Rejected: "prove FORCE + accept owner" (defers the durable fix) and "defer to 29" (weakens
  the gate this phase exists to be).
- **D-02 — Rotate all six credentials + write the runbook.** The full #552 set (app DB role
  password, 4 dev-realm OAuth2 client secrets, monitoring UI admin credential), high-entropy via
  the `.env` layer, with the procedure recorded as `docs/runbooks/credential-rotation.md` so
  Phase 29's staging secrets follow the same path. Acceptance arms per #552: each superseded
  credential fails AND the current one succeeds in the same run. Known traps: Keycloak client
  secrets need a realm re-import with `--override true` (KC is Postgres-backed; volume drop is a
  no-op — `reference_keycloak_realm_reimport` memory); Grafana applies its configured admin
  password only on first user creation — reset against the running instance or recreate the
  volume, then re-verify.
- **D-03 — Split reaches every surface + the boot validator.** Compose env, k8s secret
  templates (`k8s/base/secrets-template.yaml.example`) and `k8s/QUICK_START.md` recipe, AND
  `DatabaseConfigurationValidator` extended to fail fast when the runtime role OWNS its tables
  (same pattern as its existing superuser check). A future env reverting to the owner role fails
  at boot with a named reason.
- **D-04 — Proof is permanent test + live arm.** Extend the Testcontainers RLS harness to
  provision the owner/runtime split and run the isolation suite as the NON-OWNER role
  (regression-proof), AND a one-off live-stack measurement at close-out (pinned tenant sees
  rows / other tenant sees none), both directions recorded.

**Media backfill (#488)**
- **D-05 — Measure first, remediate the urgent subset now.** Read-only enumeration of
  `jtoye-images` for objects whose stored Content-Type is outside the image allowlist (the
  stored-XSS subset — small and urgent). Only that subset is re-pipelined this phase. The full
  EXIF/WebP sweep over the catalogue gets a **measured count + dated plan**, executed gradually
  later (Phase 29+ or background job) — recorded, not silently dropped.
- **D-06 — Originals retained on a horizon.** Re-pipelined originals move to a non-public
  quarantine prefix with a declared expiry (mirror the V60 `quarantine_expires_at` pattern),
  then reap. Reversible; the public origin is clean immediately.
- **D-07 — Normaliser rejects are pulled, FAILED, and vendor-visible.** A non-decodable object
  is removed from the public origin immediately (same quarantine horizon), its asset marked
  FAILED with a reason — the existing IMG-04 vendor UI already renders FAILED → reason +
  Re-upload. Availability loses to safety; silent pulls are regression-by-omission.
- **D-08 — Successful normalisation goes to the standard derivative path.** WebP derivative +
  thumbnail exactly like a fresh upload; references (media_asset + flat dual-read columns)
  update to it. URLs change once; every image then lives under one pipeline contract.
  **Invariant: no storefront image 404s during the transition.**

**Revoked SSE stream (#281)**
- **D-09 — Per-emit grant re-check, not eviction, not acceptance.** Re-check the grant
  (cacheable lookup) before each event emit. Closes the delivery exposure completely — a revoked
  user receives no further events; the idle connection may linger to `SSE_TIMEOUT` but delivers
  nothing. Eviction-signal plumbing rejected as heavier than the residual justifies.
- **D-10 — Applied to ALL SSE emitters delivering tenant/shop-scoped data** (KDS + the
  order-updates stream), as one shared mechanism — same defect class, cheapest fixed together.

**Disposition & gate depth (#548, #551, #549, SC-3)**
- **D-11 — Dispositions live in a tracked sanitized doc:** `docs/security/PENTEST-TRIAGE.md`,
  one sanitized line per finding ID (A1..E1): status, issue link or dated acceptance. A CI check
  asserts all 11 IDs have a disposition (falsifiable — shown to fail on a removed line). #548
  closes pointing at it. Rejected: issue-comments-only (record invisible to repo-level review —
  the 2026-08-02 lesson) and the git-excluded addendum.
- **D-12 — E1 (#551): audit + fix in the same import.** Enumerate which Keycloak clients/mappers
  can mint the `core-api` audience, record in the triage doc; over-broad mappers found by the
  audit are fixed in the realm template on the SAME re-import D-02's rotation already requires.
  Anything non-trivial becomes a sanitized follow-up issue.
- **D-13 — RLS-coverage assertion: verify, then close the gap here.** FIRST measure what
  `RlsContractTest`'s schema-walk already asserts (V61 notes describe an `EXEMPT_TABLES`
  by-addition sweep — it may already cover enumerate-tenant-tables→must-have-RLS; the 08-05
  memory says it sweeps a different class — resolve the contradiction by measurement). Extend
  only if missing/partial. If already covered: record satisfied in the triage doc WITH the
  fail-direction run.
- **D-14 — #549 fixed now, config-level.** Under staging (and prod) profiles the OpenAPI
  endpoints require auth or are disabled. Proven by a profile-parameterised test showing both
  directions: dev serves the spec, staging does not. #549 closes before the environment that
  would expose it exists.

### Claude's Discretion
- **SC-3 gate mechanics** — how the built-spec assertion runs in CI (boot under `prod` profile
  and read `/v3/api-docs`, springdoc build-time generation, or equivalent). Must satisfy: gate
  asserts the BUILT artifact, and is shown to fail against a deliberately reintroduced fallback.
- **#283/#284 `asSystem()` implementation** — follow the fix shape already specified in the
  issues (explicit system-principal marker replacing the `auth == null` bypass; SecurityContext
  propagation for `@Async`/`@Scheduled`/`@RabbitListener`). Run the FULL integration suite —
  new auth gates have silently broken existing integrationTests before
  (`trap_scope_gate_integrationtest_regression` memory).
- **#270 minio/mc pin** — pin by digest, scope the bootstrap credentials; standard fix, no user
  decision needed.
- **A1 re-verification arms** — design of the CONFIRMED/FALSIFIED measurement against the
  rebuilt-from-HEAD stack; the 2026-08-05 break-arm precedent (neutralise the ownership check →
  exactly 1 named test failure) is the pattern to reuse.
- Quarantine horizon length for D-06, grant-cache TTL for D-09, exact new-role name and grant
  set for D-01.

### Deferred Ideas (OUT OF SCOPE)
- **Full-catalogue EXIF/WebP media sweep** — measured + dated plan this phase; execution later
  (Phase 29+ or a background job). The GDPR (EXIF GPS) exposure on *valid-Content-Type* legacy
  objects persists until it runs — the dated plan must say so.
- **CUST-02 `MANUAL_REVIEW` adjudicator** — still owner-deferred (carried from Phase 33's
  queue; offered this session, not settled). Constraint standing: no cross-tenant platform
  operator identity (`arch_no_platform_operator`).
- **Binding application ports (edge 8089 / frontend 3000 / mcp 9100) to loopback** — explicitly
  ruled a decision-not-a-tidy-up by the decay doc; not taken up.
- **Eviction-signal SSE plumbing (emitter registry + revocation broadcast)** — rejected for
  this phase as heavier than the residual; would matter for multi-replica fan-out later.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|------------------|
| **SEC-01** | Pentest finding A1 re-verified against a stack rebuilt from HEAD, recorded CONFIRMED or FALSIFIED with the measurement that settled it | §"A1 re-verification" — the exact break-arm recipe, the named test method (`CrossTenantAuthzIntegrationTest.createPromotion_crossTenantShop_isBlocked`, file:line verified), the runtime-parity precondition (`scripts/check-runtime-freshness.sh` + `scripts/sync-runtime.sh`), and the live RLS arm with its non-vacuity control (measured: products 0 / 47 / 4) |
| **SEC-02** | All 11 findings triaged — sanitized public issue or dated written acceptance each | §"D-11 triage doc + completeness gate" (shape, gate mechanics, fail direction); §"D-12 E1 audit" (the audit is ALREADY answerable statically — full client→audience table measured below) |
| **SEC-03** | No dev-only branch reachable under `prod`; `X-Tenant-Id` fallback no longer advertised in the unauthenticated spec; CI gate shown to fail | §"SC-3: asserting the BUILT OpenAPI document" — the committed snapshot **does** contain `tenant-header` (72 occurrences, measured) because it is generated under `test`; the gate must build the document with `TenantFilter` ABSENT. Recipe + fail direction given. **D-14/#549 is already shipped** — measured, see §"Decayed criteria" |
| **SEC-04** | Compose publishes no infrastructure port on `0.0.0.0`; local credentials rotated; closes #283/#284/#289 bypass class | Loopback half already satisfied (decay doc, re-confirmed). §"Credential rotation" enumerates all six keys and their rotation surfaces; §"D-01 runtime-role split" gives the measured grant set and the four traps; §"#283/#284" gives the two bypass coordinates and the measured blast radius |
</phase_requirements>

---

## Summary

Three of this phase's fourteen locked decisions are aimed at work that **has already shipped**, and
one is aimed at a population that **measures zero**. That is the single most important thing this
research has to say, because planning them as written would produce four vacuous criteria — the
exact defect `CRITERIA-DECAY-2026-08-08.md` was written to prevent, one phase later. Each claim
below carries a control arm.

The genuinely open, genuinely load-bearing work is smaller and sharper than the CONTEXT anticipated:
the **runtime-role split (D-01/D-03/D-04)**, the **SC-3 built-document assertion**, the **SSE
per-emit re-check (D-09/D-10)**, the **`asSystem()` marker (#283/#284)**, **credential rotation
(D-02)**, the **`minio/mc` digest pin (#270)**, and the **triage doc + its completeness gate
(D-11)**. Of these the role split carries by far the most hidden risk, and it has a trap that is
**already biting this repository today**: `ALTER DEFAULT PRIVILEGES` without `FOR ROLE` silently
grants nothing on Flyway-created tables, and `jtoye_backup` consequently cannot `SELECT` the newest
table (`postcode_centroid`) — measured live, 40 of 41 covered. Repeat that mistake for the runtime
role and the app boots fine today and dies on the first table a future migration adds.

The second-largest risk is the SSE re-check. `OrderSseService.broadcast` runs on a `@RabbitListener`
thread that carries **no `SecurityContext`, no `TenantContext` and no tenant GUC**. A per-emit grant
re-check written the obvious way queries `shop_staff` under FORCE RLS with no GUC pinned, gets zero
rows for every subscriber, and delivers nothing to anyone — a dead KDS feature that passes every
security assertion in the change. That is `trap_rls_blinds_the_verification_query` in its most
expensive form, and the plan must carry a positive-delivery control arm, not only a
revoked-user-blocked arm.

**Primary recommendation:** sequence the phase as **measure-and-dispose first** (D-13, D-14, D-05,
D-12, A1 — all four are answerable in a single wave and three of them collapse to "record the
measurement"), then **the role split** as its own wave with the four traps below encoded as
executable arms, then the code changes (SSE, `asSystem()`, `minio/mc`), then rotation last because
it invalidates every live measurement taken before it.

## Architectural Responsibility Map

| Capability | Primary Tier | Secondary Tier | Rationale |
|------------|-------------|----------------|-----------|
| Runtime vs. migrator DB role separation | Database / Storage | Core Java (config + boot validator) | Privilege separation is a database-object fact; the app's only job is to connect as the right role and refuse to boot if it does not |
| Ownership fail-fast at boot | Core Java (`DatabaseConfigurationValidator`) | — | Already the tier that owns the superuser check; ownership is the same class of assertion against the same catalog |
| RLS coverage sweep | Test harness (Testcontainers) | CI | Schema-walk over `pg_class` needs a real Postgres and the real migration chain; H2 has no RLS |
| OpenAPI document contents | Core Java (springdoc customizer) | CI gate | The document is built in-process at request time; only an in-process build can observe the filter-absent shape |
| OpenAPI endpoint authorisation | Core Java (`SecurityConfig`) | — | Profile-conditional `permitAll`; already shipped |
| Per-emit SSE grant re-check | Core Java (`OrderSseService`) | Database (shop_staff under RLS) | Emitters are per-JVM in-memory; the grant is a DB fact that must be re-read with the tenant GUC pinned |
| System-principal marker (`asSystem()`) | Core Java (`ShopAccessService` + entry points) | — | A ThreadLocal declaration, not a transport concern |
| Object-storage content-type / EXIF remediation | Object storage (MinIO) | Core Java (media pipeline) | Bytes and metadata live in S3; the transform machinery already exists in the app |
| Bucket anonymous-access policy | Object storage bootstrap (compose / `k8s-local-secrets.sh`) | — | An S3 bucket policy, not application code |
| Credential rotation | Config layer (`.env` → compose/k8s) + Keycloak realm import | Runbook | GLOBAL_RULE_6: values flow through the env layer, never a committed literal |
| Triage-doc completeness | CI gate (`scripts/check-*.sh`) | Docs | A static text assertion over a tracked file |

---

## Decayed criteria — measured 2026-08-10, with controls

> This section is the counterpart to `CRITERIA-DECAY-2026-08-08.md` for the decisions that document
> did not cover. **Read it before planning D-13, D-14, D-05 or the SC-3 fallback grep.**

### DEC-1 — D-13's contradiction is resolved: the coverage sweep ALREADY EXISTS

`RlsContractTest.everyPublicTableHasRlsAndForce()` walks **every** regular relation in the public
schema and requires BOTH `relrowsecurity` and `relforcerowsecurity`, with a by-addition
`EXEMPT_TABLES` set carrying written justifications
(`core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java:158-168`). This is exactly the
"enumerate tenant tables → must have RLS" assertion. `[VERIFIED: source read]`

**#548's own prevention list is therefore wrong** where it says *"`RlsContractTest` already sweeps
for a related defect class (raw casts), so the harness exists; the table-coverage assertion does
not."* It does. The 08-05 memory that says the sweep is "a different class" was reading the other
three test methods (`noPolicyReadsBuggyAppTenantIdGuc`, `noPolicyUsesRawTenantGucCast`,
`auditW0_05_targetTablesAreForced`).

Measured on the live DB with a control:

```
RLSCOUNT|t|t|36        -- 36 tables ENABLE + FORCE
RLSCOUNT|f|f|5         -- 5 tables neither
RLS_OFF_TABLES|flyway_schema_history,postcode_centroid,processed_stripe_events,revinfo,tenants
CTRL_HASPOLICY|36      -- control: the same query machinery DOES return rows
```

The five RLS-off tables are exactly `RlsContractTest.EXEMPT_TABLES`. `[VERIFIED: live psql]`

**The one narrow gap that IS real.** Nothing sweeps for *"RLS enabled but ZERO policies"*.
`DatabaseConfigurationValidator.validateRlsPolicies` checks policy count, but only for five
hardcoded tables (`shops, products, orders, customers, financial_transactions`). A table with
RLS forced and no policy denies **everything** — fail-closed, so it is a liveness bug not a leak —
but it is a one-method extension and it is the only honest reading of "extend only if
missing/partial". Measured today: zero such tables (control: 36 tables DO have policies, so the
query can return rows). `[VERIFIED: live psql]`

### DEC-2 — D-14 / #549 is ALREADY SHIPPED, by issue #442

`SecurityConfig` gates the doc endpoints on a **development-profile allowlist AND a
deployed-profile exclusion**, not on `!isProd`
(`core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java:190-196`):

```java
boolean looksLocal = active.stream().anyMatch(p -> p.equals("dev") || p.equals("test") || p.equals("local"));
boolean isDeployedProfile = active.contains("prod") || active.contains("staging");
if (looksLocal && !isDeployedProfile) {
    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
}
```

The both-direction profile-parameterised test D-14 asks for **also already exists**, as three
classes: `[VERIFIED: source read]`

| Test | Profile | Asserts |
|---|---|---|
| `OpenApiDevProfileGatingTest.apiDocsStayAnonymousInDev` | dev | `/v3/api-docs` → **200** |
| `StagingActuatorPortIsolationTest.apiDocsNotAnonymousInStaging` | staging | `/v3/api-docs` → **not 200** |
| `OpenApiProdProfileGatingTest` | prod | `/v3/api-docs` → **not 200** |

**Planning consequence:** D-14 is not a fix task. It is a *fail-direction proof + disposition* task:
run the staging test against a deliberately reverted `looksLocal && !isDeployedProfile` (e.g.
`!isProd`) and record that it goes red, then close #549 pointing at the triage doc. Do not plan a
config change — there is nothing to change, and a task that changes nothing will read as done while
proving nothing.

### DEC-3 — SC-3's real shape: the committed snapshot is NOT the artifact to grep

`docs/api/openapi-snapshot.json` **contains** `tenant-header` (72 occurrences) and the
`X-Tenant-Id` prose line. `[VERIFIED: grep -c, rc=0]` That is **correct, not a defect**:
`OpenApiSnapshotTest` boots under `@ActiveProfiles("test")`, and `TenantFilter` is
`@Profile({"dev","local","test"})`, so the filter IS present and the advertisement IS accurate.

So a gate that greps the committed snapshot is a guaranteed false red — the same class of mistake
the decay doc caught in SC-3's original source-grep form, one artifact further along. The gate must
build the document with the filter **absent**. See §"SC-3: asserting the BUILT OpenAPI document".

**The strip itself is still unverified end-to-end.** `TenantHeaderSchemeCustomizerTest` asserts on
the `OpenAPI` model object after the customizer runs, both directions, and its own javadoc states
why it deliberately does not assert through `/v3/api-docs`. That is a strong unit proof of the
mechanism and it is *not* a proof that the mechanism is wired into the served document under a
filter-absent profile. Closing that last link is SC-3's actual work.

### DEC-4 — #488's urgent subset measures ZERO, and so does the EXIF half

Measured against the live `jtoye-images` bucket, 2026-08-10, 768 objects enumerated:

| Measurement | Result | Control | Control result |
|---|---|---|---|
| Objects with stored Content-Type outside the allowlist | **0** | Same `curl -I` + awk extraction against a known non-image response | emitted `application/xml` — the probe **can** report a non-allowlist type |
| Legacy (non-pipeline) objects carrying `[EXIF]` or `[GPS]` tags | **0 of 37** | Same `exiftool -q -s -G` census over a copy with GPS injected | reported `[EXIF] GPSLatitudeRef`, `[Composite] GPSPosition`, … |
| Objects under a `*/quarantine/*` prefix | **0** | 768 keys listed, prefix census returned `media 731 / products 33 / shops 4` | non-empty |

Content-type census: `image/webp` 731 (all under `*/media/*` — the Phase-24 derivatives),
`image/jpeg` 35, `image/png` 2. The 37 non-webp objects are the pre-#479 legacy set
(33 `*/products/*`, 4 `*/shops/*`). `[VERIFIED: live HTTP + exiftool]`

Since #548 records that this platform *"has never been deployed outside a laptop"*, this bucket **is
the entire population**. So:

- **D-05's "remediate the urgent subset now" has an empty subset.** The deliverable is the
  enumeration tool + the recorded measurement + its control, not a re-pipeline run. A plan that
  schedules a re-pipeline task will produce a task that processes zero objects and reports success.
- **D-06/D-07/D-08 have nothing to act on this phase.** They remain the *specification* for the
  deferred sweep. Record them in the dated plan; do not build a pipeline that has no input.
- **The deferred EXIF/GPS exposure is smaller than #488 states** — zero of 37 legacy objects
  carry EXIF at all. The remaining real cost of the deferred half is the CWV/WebP one (37 objects),
  not GDPR. **Say so in the dated plan**, and say it with the control, because #488's GDPR framing
  is what makes the deferral feel dangerous.

### DEC-5 — a NEW exposure this research found, currently unfiled

`mc anonymous set download local/jtoye-images` grants **`s3:ListBucket` as well as `s3:GetObject`**.
The full 768-key object inventory is enumerable with **no credential**:

```
curl -s "http://localhost:9000/jtoye-images?list-type=2&max-keys=1000"   -> http=200, KeyCount 768
```
`[VERIFIED: live HTTP]`

This matters directly to D-06: a "non-public quarantine prefix" is **not achievable** by adding a
prefix, because the bucket-wide anonymous policy covers every key, and the keys are discoverable.
`MediaProperties`' own javadoc already records the read half of this
(*"a quarantine object is anonymously readable by key"*); the **list** half means an attacker does
not even need the key. D-06 therefore requires a prefix-scoped bucket policy
(`mc anonymous set-json` with an explicit Deny on `*/quarantine/*`, or a separate private bucket) —
not a naming convention. File it as a sanitized issue; it is in the same local-stack surface SC-4
governs and is cheap alongside #270.

---

## Standard Stack

No new runtime dependency is required by this phase. Everything below already exists in the tree.

### Core

| Library / mechanism | Version | Purpose | Why standard |
|---|---|---|---|
| PostgreSQL role + `GRANT` / `ALTER DEFAULT PRIVILEGES` | 15 | D-01 runtime/owner split | The only mechanism that separates DML from ownership; `infra/backups/create-backup-role.sql` is the in-repo precedent |
| Flyway `spring.flyway.user` / `.password` | Boot 3.5.16 autoconfig | Lets the migrator role differ from the app role | Already given a dedicated non-pooling DataSource for #517; decoupling the credentials is the intended use |
| springdoc-openapi | 2.8.6 | Builds the OpenAPI document in-process | The `OpenApiCustomizer` the strip already rides |
| Spring Security `DelegatingSecurityContextExecutor` family | 6.5 (Boot 3.5.16) | #284 context propagation, if propagation is chosen over `asSystem()` | Framework-supplied; the alternative is a hand-rolled ThreadLocal |
| Testcontainers PostgreSQL | 1.21.4 | D-04 provisions the split against a real Postgres | Every RLS proof in this repo already runs here |
| `exiftool` / `curl` | host | #488 enumeration | Present on this box (`/usr/bin/exiftool`, verified) |

### Supporting

| Mechanism | Purpose | When to use |
|---|---|---|
| `scripts/check-infra-exposure.sh` assertions C1/C2/C3 | Grafana live-credential verification | Reuse verbatim as D-02's monitoring-UI acceptance arm — it already encodes the "Grafana applies the password only on first user creation" trap |
| `scripts/check-openapi-snapshot-fresh.sh` | contract ↔ RUNNING service | Precedent for a runtime spec gate; **not** reusable for SC-3 (runs against the dev-profile stack, where the header is legitimately advertised) |
| `OpenApiSnapshotTest` MockMvc `/v3/api-docs` fetch | contract ↔ SOURCE TREE | The recipe SC-3's new test copies, minus the filter |
| `scripts/check-gate-enforcement.sh` + `scripts/gates/gate-enforcement.conf` | Gate wiring | Every new `scripts/check-*.sh` must land here or in a workflow |
| `scripts/docs-freshness.sh --write` + `scripts/check-doc-metrics.sh` | Test-count manifest | Mandatory after adding any Java `@Test` / Jest block |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
|---|---|---|
| In-process filter-absent document build (SC-3) | `springdoc-openapi-gradle-plugin` | `OpenApiSnapshotTest`'s javadoc already records why this repo rejected it: it boots the app out-of-band, needing a live DB and port juggling in CI |
| In-process build | Boot the packaged jar under `staging` in CI and curl it | Staging profile needs real Redis/Keycloak/management-port config; `StagingActuatorPortIsolationTest` shows the amount of `@DynamicPropertySource` stubbing that costs, and the answer would be one profile's shape only |
| `asSystem()` ThreadLocal marker (#283) | `DelegatingSecurityContextExecutor` propagation everywhere (#284) | Propagation carries a *user's* context into background work — wrong semantics for a scheduler, and it does not remove the `auth == null` bypass. #283's marker is the fix; propagation is at best complementary |
| Prefix-scoped bucket policy (DEC-5 / D-06) | Separate private bucket | A second bucket changes `S3_BUCKET` everywhere and every k8s/compose surface; a policy is one bootstrap line |
| New outbox event type for a #488 re-pipeline | Reuse `MediaProcessingEvent(tenantId, assetId)` | Reuse is strictly better — see §"Don't Hand-Roll", the dispatch trap does not apply to this outbox |

**Installation:** none. No package is added by this phase.

## Package Legitimacy Audit

**Not applicable — this phase installs no external packages.** Every mechanism above is already a
declared dependency of `core-java`, or a host tool already present. `slopcheck` was therefore not
run; there is nothing for it to check. If planning later introduces a dependency (none is
foreseen), the Package Legitimacy Gate applies to it at that point.

---

## Architecture Patterns

### System architecture — where each change lands

```
                        ┌──────────────────────────────────────────────────────┐
  browser / vendor UI   │  GET /api/v1/orders/stream  (SSE, fetchEventSource)  │
        │               └───────────────────────┬──────────────────────────────┘
        │                                       │ subscribe()  [request thread:
        ▼                                       │  SecurityContext + TenantContext PRESENT]
  ┌───────────────┐                             ▼
  │ OrderController│──────────────► OrderSseService.subscribe()
  └───────────────┘                    │  captures ShopScope snapshot  ── D-09 must ALSO capture userId
                                       ▼
                              emittersByTenant : Map<tenantId, Map<SseEmitter, ShopScope>>
                                       ▲
   RabbitMQ order.events ──► OrderSseFanoutListener ──► OrderSseService.broadcast(event)
   (AnonymousQueue,                    [listener thread: NO SecurityContext,
    per replica)                        NO TenantContext, NO tenant GUC]        ◄── D-09 RISK
                                       │
                                       └─► per-emit re-check MUST: pin TenantContext + set_config,
                                           resolve grant BY userId, then emit

  ┌──────────────────────── core-java boot ─────────────────────────┐
  │ Flyway  ──(spring.flyway.user)──►  jtoye_app   [OWNER/MIGRATOR] │  D-01/D-03: decouple
  │ Hikari  ──(spring.datasource.*)─►  jtoye_runtime [DML ONLY]     │  these two credentials
  │ DatabaseConfigurationValidator ──► reject if CURRENT_USER owns  │  D-03 new assertion
  │ PostcodeCentroidImporter ──► TRUNCATE + CREATE TEMP + COPY      │  D-01 grant-set input
  └──────────────────────────────────────────────────────────────────┘

  ┌───────────── OpenAPI document build (in-process, per request) ───────────┐
  │ OpenApiConfig  @Profile("!prod")  ──► OpenAPI model                      │
  │        │                                                                │
  │        ▼                                                                │
  │ TenantHeaderSchemeCustomizer.customise(openApi)                         │
  │   if ObjectProvider<TenantFilter>.getIfAvailable() != null → LEAVE ALONE │  ◄── SC-3 asserts
  │   else → remove scheme, global req, per-op reqs, prose line             │      the ELSE branch
  └──────────────────────────────────────────────────────────────────────────┘
```

### Pattern 1 — the runtime/owner role split (D-01)

**What:** `jtoye_app` remains the Flyway migrator and table owner. A new `jtoye_runtime` role holds
`SELECT/INSERT/UPDATE/DELETE` (plus the two extras below) and nothing else. The app's Hikari pool
connects as `jtoye_runtime`; Flyway connects as `jtoye_app`.

**Measured baseline (live, 2026-08-10):** `[VERIFIED: live psql]`

```
ROLES|jtoye_app|rolsuper=f|rolbypassrls=f|rolcanlogin=t
OWNER|jtoye_app|41              -- jtoye_app owns ALL 41 public tables
SEQUENCES|1  (revinfo_seq)
DB_ACL   jtoye  = {=Tc/jtoye, ...}          -- PUBLIC already has TEMPORARY + CONNECT
SCHEMA_ACL public = {=U/pg_database_owner, ...} -- PUBLIC already has USAGE
```

**Framing the planner must keep straight:** because **all 36 tenant tables are ENABLE + FORCE**, the
owner is *already* subject to RLS today. Measured:

```
as jtoye_app, no tenant GUC        : products = 0
as jtoye_app, GUC = ...0001        : products = 47
as jtoye_app, GUC = ...0002        : products = 4
as superuser (control)             : products = 51        (47 + 4 = 51)
```

So D-01 is a **durability fix, not the closure of a live hole** — precisely as D-01's own wording
says ("isolation stops depending on FORCE RLS being remembered on every future table"). Writing it
up as "the app could read across tenants" would be false, and #552's acceptance criterion
("*a query that returns rows under a pinned tenant returns none under a different one*") is
**already satisfiable on today's role**. The criterion that can actually fail is the *ownership*
one, not the *filtering* one.

**Where to create the role.** Not Flyway. `jtoye_app` has no `CREATEROLE` and the migration chain
must stay replayable. Follow the `infra/backups/create-backup-role.sql` precedent exactly: an
operator bootstrap SQL file run as the superuser, idempotent, password injected via `\getenv` /
`-v`, referenced from a runbook. For compose, `infra/db/init/00-create-db.sql` is the fresh-volume
path — **but note it runs ONLY on an empty data directory**, so an existing dev volume needs the
bootstrap file run by hand. Both paths are required; only one of them fires on any given machine.

**The grant set, derived from measured application behaviour:**

```sql
-- identity
CREATE ROLE jtoye_runtime LOGIN PASSWORD :'runtime_password';   -- NOSUPERUSER NOBYPASSRLS by default
GRANT CONNECT ON DATABASE jtoye TO jtoye_runtime;
GRANT TEMPORARY ON DATABASE jtoye TO jtoye_runtime;  -- PostcodeCentroidImporter CREATE TEMP TABLE
GRANT USAGE ON SCHEMA public TO jtoye_runtime;       -- (PUBLIC already has it; explicit is safer)

-- existing objects
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jtoye_runtime;
GRANT TRUNCATE ON postcode_centroid TO jtoye_runtime;   -- PostcodeCentroidImporter:162
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO jtoye_runtime;  -- revinfo_seq (Envers)

-- FUTURE objects — the FOR ROLE clause is the whole point (see Pitfall 1)
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jtoye_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO jtoye_runtime;
```

`TRUNCATE` is **not** part of `SELECT/INSERT/UPDATE/DELETE` and must be named. `ALL PRIVILEGES`
*does* include it, but `ALL` also re-introduces `REFERENCES`/`TRIGGER` and reads as "the same
privileges the owner has", defeating the point. `[CITED: postgresql.org/docs/15/sql-grant.html,
sql-alterdefaultprivileges.html]`

**Config decoupling (the step that is easy to miss).** `application.yml:100-102` currently reads:

```yaml
  flyway:
    url: ${spring.datasource.url}
    user: ${spring.datasource.username}
    password: ${spring.datasource.password}
```

Pointing `spring.datasource.username` at `jtoye_runtime` **also moves Flyway onto it**, and the
migrations immediately fail (no `CREATE` on schema). The fix is a new env pair with a
backward-compatible default so no existing environment breaks:

```yaml
  flyway:
    url: ${spring.datasource.url}                                  # keep — #517 needs the key present
    user: ${DB_MIGRATION_USER:${spring.datasource.username}}
    password: ${DB_MIGRATION_PASSWORD:${spring.datasource.password}}
```

`spring.flyway.url` **must stay declared** — its presence is what makes Spring Boot build Flyway a
dedicated non-pooling `SimpleDriverDataSource`, which is the #517 fix keeping the migration
sentinel GUC out of the app pool. Removing it would reintroduce a GUC leak into request
connections on an RLS system. `[VERIFIED: application.yml comment + FreshChainMigrationIntegrationTest]`

**Blast radius to check:** `FreshChainMigrationIntegrationTest` boots the real autoconfiguration on
the shipped `spring.flyway.*` keys and asserts the dedicated-DataSource property. It will see this
change. Read it before editing the keys.

### Pattern 2 — SC-3: asserting the BUILT OpenAPI document with `TenantFilter` absent

**What:** a Testcontainers integration test that boots the full context under `test` (so all the
existing infra stubbing applies), **removes the `TenantFilter` bean definition**, fetches
`/v3/api-docs` through MockMvc, and asserts the served JSON contains neither the `tenant-header`
scheme nor the `X-Tenant-Id` string — with the filter-present arm in the same class as the
non-vacuity control.

**Why bean removal rather than a profile.** `TenantHeaderSchemeCustomizer` keys off
`ObjectProvider<TenantFilter>.getIfAvailable()`. Removing the bean definition reproduces the exact
staging condition (filter absent, `OpenApiConfig` still loaded because the profile is not `prod`)
without needing staging's infrastructure. Implement with an
`ApplicationContextInitializer` registering a `BeanFactoryPostProcessor` that calls
`removeBeanDefinition("tenantFilter")`, wired via `@ContextConfiguration(initializers = …)`.

**The assertion must be on the serialized document, not the model.** The model-level assertion
already exists (`TenantHeaderSchemeCustomizerTest`); the open link is that springdoc actually
applies the customizer to what it serves. Assert on the response string:

```java
String served = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
assertThat(served).doesNotContain(TenantFilter.TENANT_HEADER);   // never a copied literal
assertThat(served).doesNotContain("tenant-header");
```

**Both directions, permanently encoded** (the `FreshChainMigrationIntegrationTest` shape):

| Arm | Context | Must hold |
|---|---|---|
| 1 | filter bean REMOVED | served document contains neither string |
| 2 (control) | stock `test` context, filter present | served document DOES contain both — proves arm 1's zero is about the strip, not about an empty/unbuildable document |
| 3 (denominator) | either | `paths` is non-empty — an empty document satisfies arm 1 vacuously |

Arm 3 is not decoration. It is the same "found nothing is never clean" rule
`check-openapi-snapshot-fresh.sh` encodes as its A-2 assertion.

**Fail direction to run and record:** neutralise `TenantHeaderSchemeCustomizer.customise` (early
`return`), confirm arm 1 goes red naming the string, restore, verify the restore **by content hash**
(`git hash-object`), re-run clean. Clean → break → restore → clean, all four recorded — the same
protocol #548 used for A1.

**Do NOT wire this as a `scripts/check-*.sh`.** It needs no runtime, it belongs in
`integrationTest`, and adding a shell gate would immediately owe
`scripts/gates/gate-enforcement.conf` an entry it cannot honestly make.

### Pattern 3 — per-emit SSE grant re-check (D-09/D-10)

**What:** `OrderSseService` stores the subscriber's `userId` alongside the `ShopScope` snapshot; on
each emit it re-resolves the grant for that user and skips the emitter when the grant no longer
permits the event's shop.

**The one hard constraint:** `broadcast()` runs on the `OrderSseFanoutListener` `@RabbitListener`
thread. That thread has **no `SecurityContext`** (so `ShopAccessService.grantedShopIds()` /
`isGroupAdmin()`, which read `SecurityContextHolder`, are unusable) and **no `TenantContext` and no
tenant GUC** (so any `shop_staff` query returns zero rows under FORCE RLS). The re-check must
therefore:

1. take the `userId` and `tenantId` from the emitter registry entry, never from the thread;
2. pin the tenant **before** the lookup, using the exact idiom `MediaProcessingWorker:154-162`
   already uses — `TenantContext.set(tenantId)` **and** `set_config('app.current_tenant_id', ?, true)`
   on the session connection, cleared in a `finally`;
3. resolve through `self().resolveMembership(userId)` so the `@Cacheable("shopMembership")`
   interceptor is actually engaged (calling `this.resolveMembership` bypasses the proxy — the WR-01
   trap already documented in `ShopAccessService`);
4. rely on the existing `evictMembershipAfterCommit(userId)` on grant/revoke for freshness. The
   cache TTL is the revocation latency; the grant-cache TTL choice D-09 leaves to discretion **is**
   that number, and it should be stated in the plan as such.

**`ShopAccessService.resolveMembership` is keyed by `tenantAwareCacheKeyGenerator`**, which reads
`TenantContext` — another reason step 2 must precede step 3, or the cache key is wrong (or throws).

**The control arm that makes this non-vacuous.** Assert BOTH:
- a revoked user's emitter receives nothing after revocation (the security arm), and
- a **still-granted** user's emitter on the same broadcast still receives the event (the liveness
  arm).

Without the second, a re-check that returns "no grants for everyone" — the exact failure mode of
forgetting the GUC pin — passes the security arm perfectly while killing the KDS.

**Scope check (D-10, "ALL SSE emitters"):** measured, there is exactly **one** SSE surface —
`OrderController` `@GetMapping(value="/stream", produces="text/event-stream")` →
`OrderSseService.subscribe()`, consumed by `frontend/hooks/use-order-events.ts` and used by both the
KDS and the order-detail page. `[VERIFIED: rg over core-java/src/main/java, 1 endpoint]` The KDS's
*other* real-time channel is STOMP/WebSocket, a different mechanism whose shop gate was #289
(CLOSED). D-10 is therefore satisfied by changing one class — say so, rather than planning a search.

### Pattern 4 — `asSystem()` replacing the `auth == null` bypass (#283/#284)

**Measured coordinates — there are exactly two:** `[VERIFIED: rg over core-java/src/main/java]`

```
ShopAccessService.java:539   if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return;   // onRequest() side-effect guard
ShopAccessService.java:623   return SecurityContextHolder.getContext().getAuthentication() == null;   // isInternalCaller()
```

`:623` is the one #283 is about. Its javadoc already states the semantics precisely: *"True ONLY
when there is no `Authentication` on the current thread — the retained internal-caller bypass."*
The fix replaces the *inference* with a *declaration*: a `ThreadLocal<Boolean>` (or a sentinel
`Authentication` with a `ROLE_SYSTEM` authority) set by an `asSystem(Runnable)` / `asSystem(Supplier)`
wrapper, with `isInternalCaller()` reading the marker and `auth == null` becoming a hard denial.

**#284's premise has partly decayed and the planner should re-measure rather than repeat it.** The
issue says *"Measured at 23-08: no gated service is currently reached from such a path."* Since then
Phase 24 shipped `MediaProcessingWorker` (`@RabbitListener`) → `MediaAssetService.placeAsset(...)`,
and `MediaAssetService` contains three `shopAccessService.require(...)` calls (`:118`, `:384`,
`:439`). Measured: `placeAsset` itself does **not** gate (`MediaAssetService:297-312`), so the claim
still holds — but it now holds by one method, in a class that gates three of its other entry points.
That is #284's "one new call away" made concrete, and it is the strongest available justification
for the guard test the issue asks for. `[VERIFIED: source read]`

**Surface size:** 11 files carry `@RabbitListener`, 9 carry `@Scheduled`, 6 carry `@Async`.
`[VERIFIED: rg -l counts]` The Spring-supplied propagation types
(`DelegatingSecurityContextExecutor`, `DelegatingSecurityContextAsyncTaskExecutor`,
`DelegatingSecurityContextRunnable`) exist and are the standard answer for *propagation*
`[CITED: docs.spring.io/spring-security/reference/6.5/features/integrations/concurrency.html]` — but
propagation carries a *user's* identity into background work, which is the wrong semantics for a
scheduler and does not remove the bypass. Prefer `asSystem()` at the entry points; reach for
propagation only where a background task genuinely continues a user's request.

**Blast radius — plan for it explicitly.** #283 records **62 no-principal test files** depending on
the current behaviour, and `trap_scope_gate_integrationtest_regression` records that new auth gates
have silently broken *existing* integrationTests before. The plan must budget a **full**
`./gradlew :core-java:test :core-java:integrationTest` run (integrationTest measures 46–49 min in
CI), not a targeted one.

### Pattern 5 — one Keycloak re-import, two payloads (D-02 + D-12)

The realm is Postgres-backed, so dropping the Keycloak volume is a **no-op**; the import needs
`kc.sh import --override true` plus a restart (`infra/keycloak/README.md`,
`reference_keycloak_realm_reimport` memory). Both the rotated client secrets (D-02) and any audience
fix (D-12) render from `.env` into `infra/keycloak/realm-export.template.json`, so they must land in
a single import event, exactly as the CONTEXT's <specifics> says.

### Anti-Patterns to Avoid

- **Grepping `docs/api/openapi-snapshot.json` for `X-Tenant-Id`.** It is there, correctly (DEC-3).
  A gate that fails on it is a permanently-red required check, which this repo records as worse than
  no check.
- **`ALTER DEFAULT PRIVILEGES` without `FOR ROLE`.** Silently inert. See Pitfall 1 — it is already
  live in this repo.
- **A per-emit grant re-check that reads `SecurityContextHolder`.** Always null on the broadcast
  thread; every emit gets skipped and the failure looks like "SSE is quiet".
- **A "no tenant table returns rows without a GUC" live assertion.** `shops` returns **3** — it
  carries a permissive `shops_public_read` SELECT policy `(published = true) OR (tenant_id =
  current_tenant_id())` for the public storefront. Measured. Pick `products`/`orders`/`customers`
  for the D-04 live arm, or account for the public policy explicitly.
- **Pre-declaring a sibling plan's script in `gate-enforcement.conf`.** The conf VOIDs on entries
  naming scripts that do not exist yet (Phase 33 lesson, recorded in the file's own header).
- **Rotating credentials before taking the live measurements.** Rotation invalidates every arm run
  against the old values; sequence it last.

---

## Don't Hand-Roll

| Problem | Don't build | Use instead | Why |
|---|---|---|---|
| Grafana live-credential verification for D-02 | A bespoke curl probe | `scripts/check-infra-exposure.sh` assertions C1/C2/C3 | Already encodes the first-user-creation trap AND a reject-a-random-value arm, so a "login worked" is evidence the endpoint discriminates |
| RabbitMQ default-credential regression check | A default-password probe | The same script's assertion D | Enumerates the broker's own user list — strictly stronger, and names no credential in a public repo |
| Building the OpenAPI document out of band | `springdoc-openapi-gradle-plugin` | `OpenApiSnapshotTest`'s MockMvc `/v3/api-docs` recipe | Its javadoc records this exact rejection and the reasons (live DB, port juggling in CI) |
| A new outbox event type for a #488 re-pipeline | New enum + dispatch branch | `MediaProcessingEvent(tenantId, assetId)` on `media_event_outbox` | **Measured:** `MediaEventOutboxFlusher.publishRow` has NO closed-set dispatch — one exchange, one payload type. The `outbox_flusher_dispatch_trap` **cannot arise here**; the CONTEXT's integration-point warning is over-cautious. `[VERIFIED: source read, MediaEventOutboxFlusher:24-39, :218-224]` |
| A backfill re-drive entry point | New service method | `MediaAssetService` re-drive path (`:360-414`) — resets to PENDING from retained quarantine bytes + enqueues an outbox row | The re-pipeline is a solved problem in this codebase |
| Tenant GUC pinning on a background thread | Bespoke `set_config` call | `MediaProcessingWorker:154-162` idiom (`TenantContext.set` + `set_config(..., true)` via `session.doWork`, cleared in `finally`) | Transaction-scoped (`is_local = true`) so it cannot ride a recycled Hikari connection into another tenant |
| A superuser bootstrap for a new DB role | Ad-hoc psql | `infra/backups/create-backup-role.sql` shape (idempotent, `\getenv`, `format(%L)`, documented as an operator step) | Handles password injection safely and is already referenced from a runbook |
| Test-count bookkeeping after adding tests | Arithmetic on `docs/metrics.json` | `scripts/docs-freshness.sh --write` then update prose in `CLAUDE.md` / `AGENTS.md` / `README.md` | Two gates fail the build on drift; `trap_docs_freshness_block_counter` records that arithmetic is wrong |

**Key insight:** every mechanism this phase needs already exists in the tree, usually with a javadoc
explaining why the obvious alternative was rejected. The failure mode here is not "we lack a tool" —
it is "we rebuilt one and lost its encoded lessons."

---

## Runtime State Inventory

This phase changes runtime state that no source edit reaches. Every category was checked.

| Category | Items found | Action required |
|---|---|---|
| **Stored data** | PostgreSQL role catalog: `jtoye_runtime` does not exist; `jtoye_app` owns 41/41 public tables; `pg_default_acl` has 2 entries, both `defaclrole = jtoye` (superuser). MinIO `jtoye-images`: 768 objects, 0 needing remediation. `[VERIFIED: live psql + live HTTP]` | **Data/bootstrap migration**, not a code edit: create the role and grants on every existing database. A fresh volume gets it from `infra/db/init/00-create-db.sql`; an **existing** dev volume does not, and this is the common case |
| **Live service config** | Keycloak realm `jtoye-dev` lives in Postgres, not in git — the committed `realm-export.template.json` is the *source*, the running realm is the *state*. Rotated client secrets and any D-12 audience change reach the running realm ONLY via `kc.sh import --override true` + restart. Grafana's admin user lives in its own volume and ignores `GF_SECURITY_ADMIN_PASSWORD` after first creation. MinIO's anonymous bucket policy is set by the `minio-init` job at bootstrap and persists in the volume | **Manual/API step per surface**, recorded in `docs/runbooks/credential-rotation.md` (D-02). Re-verify each against the RUNNING instance |
| **OS-registered state** | None. No Task Scheduler / launchd / systemd / pm2 registration references any name this phase changes. `[VERIFIED: no such registrations in repo; project is compose/k8s only]` | none |
| **Secrets / env vars** | Six rotating values: `DB_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`, `INTEGRATION_CATALOG_RO_SECRET`, `INTEGRATION_ORDERS_RW_SECRET`, `GRAFANA_ADMIN_PASSWORD`. Two NEW keys: `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` (or equivalent). `.env` is gitignored and machine-local; `.env.example` is committed and carries `CHANGE_ME` placeholders | Update `.env.example` (placeholders only), `docker-compose.full-stack.yml`, `k8s/base/secrets-template.yaml.example`, `k8s/QUICK_START.md`, `scripts/verify-env.sh` if it enumerates keys. **Trap:** `trap_env_example_inline_comment_is_a_value` — `VAR=  # comment` resolves to the comment text |
| **Build artifacts / images** | `core-java` image must be rebuilt for the role split, the SSE change and `asSystem()` to reach the running stack. `minio/mc` and `minio/minio` currently resolve `:latest` — no record of what ran | `docker compose ... up --build` (NOT `start`), then `scripts/check-runtime-freshness.sh`. #270's digest pin must record the resolved image ID in the bootstrap's own output |

**The canonical question, answered:** after every file is updated, the state still holding the old
shape is (1) the PostgreSQL role catalog on every existing volume, (2) the Keycloak realm rows in
Postgres, (3) the Grafana admin user in its volume, (4) the MinIO bucket policy, and (5) any
container image built before the change.

---

## Common Pitfalls

### Pitfall 1 — `ALTER DEFAULT PRIVILEGES` without `FOR ROLE` is inert, and it is ALREADY biting

**What goes wrong:** future-object grants silently apply to nothing, so the role works today and
breaks on the first table a future migration creates.

**Why it happens:** *"If `FOR ROLE` is omitted, the current role is assumed."*
`[CITED: postgresql.org/docs/15/sql-alterdefaultprivileges.html]` Both existing scripts run as the
**superuser**, while Flyway creates tables as **`jtoye_app`**.

**Measured live proof that this is not hypothetical:** `[VERIFIED: live psql]`

```
DEFACL | defacl_role=jtoye | public | r | {jtoye_app=arwdDxt/jtoye, jtoye_backup=r/jtoye}
NEWEST_TABLE_OWNER | postcode_centroid | jtoye_app
BACKUP_CAN_SELECT  | postcode_centroid | f          ◄── jtoye_backup CANNOT read the newest table
BACKUP_MISSING_COUNT | 1        (of 41; control: CTRL_TOTAL_TABLES = 41, 40 readable)
```

`jtoye_backup`'s default privileges are registered against the superuser, so V61's
`postcode_centroid` inherited nothing. A `pg_dump` as the backup role would fail or silently skip it.
**This is a live, unfiled defect** — file it sanitized, and fix it in the same bootstrap edit the
runtime role needs.

**How to avoid:** always `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public GRANT … TO
jtoye_runtime`.

**Warning signs / the executable arm:** after creating the role, run a migration that creates a
throwaway table as `jtoye_app` and assert
`has_table_privilege('jtoye_runtime', '<new table>', 'SELECT')` is TRUE. Run the fail direction by
omitting `FOR ROLE` first and confirming it is FALSE. A grant test that only checks *existing*
tables cannot fail for this reason.

### Pitfall 2 — moving the datasource username moves Flyway with it

**What goes wrong:** `spring.flyway.user: ${spring.datasource.username}` means pointing the app at
`jtoye_runtime` also points the migrator there. Migrations fail (`CREATE` denied on schema), and on
a fresh DB the app never boots.

**How to avoid:** the `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` indirection in Pattern 1, with
defaults falling back to the datasource values so no existing environment changes behaviour.

**Warning signs:** `FreshChainMigrationIntegrationTest` is the canary — it boots the real
autoconfiguration on the shipped `spring.flyway.*` keys. Read it before editing them, and keep
`spring.flyway.url` declared (removing it re-opens the #517 GUC-leak hazard).

### Pitfall 3 — the runtime role needs two privileges that are NOT DML

**What goes wrong:** the app boots, then `PostcodeCentroidImporter` (an `ApplicationRunner`, runs on
every startup) fails.

**Why it happens:** it does `CREATE TEMP TABLE postcode_centroid_staging … ON COMMIT DROP`
(`:141`), `COPY … FROM STDIN` into it (`:195`), then **`TRUNCATE postcode_centroid`** (`:162`).
`TRUNCATE` is a distinct privilege, not implied by `SELECT/INSERT/UPDATE/DELETE`. `TEMPORARY` is
granted to `PUBLIC` by default (measured: `datacl` contains `=Tc/jtoye`), but relying on a default
that a future hardening pass may revoke is fragile.

**Mitigating detail:** the importer is idempotent — it skips when the row count already matches the
manifest (`:127-133`). So the TRUNCATE path fires on a **fresh or short table**, i.e. first boot and
after any dataset change. That is exactly when a new deployment happens, which is the worst time to
discover it.

**How to avoid:** grant `TRUNCATE ON postcode_centroid` and `TEMPORARY ON DATABASE jtoye`
explicitly. Also grant `USAGE, SELECT, UPDATE ON ALL SEQUENCES` — `revinfo_seq` is the single
sequence and Envers writes a revision row per transaction.

**Warning signs:** an integration test that only exercises CRUD will not catch either. The D-04
harness must boot the *full* application as the runtime role, not just run queries.

### Pitfall 4 — `shops` legitimately returns rows with no tenant GUC

**What goes wrong:** the obvious D-04 live arm — *"as the runtime role with no tenant pinned, every
tenant table returns 0"* — **fails on a correct tree**.

**Measured:** `[VERIFIED: live psql]`

```
as jtoye_app, no GUC : products = 0     shops = 3
shops policies       : shops_rls_policy   (ALL) tenant_id = current_tenant_id()
                       shops_public_read  (SELECT) (published = true) OR (tenant_id = current_tenant_id())
```

PERMISSIVE policies are OR-ed, so the public-storefront read policy exposes published shops by
design. An expected-0 that is legitimately 3 is the "expected-0 that is actually 1 on a correct
tree" shape from the Proof Standards, and its "fix" would break the public storefront.

**How to avoid:** run the isolation arm on `products` / `orders` / `customers`, and record the
`shops` exception in the plan so a later reader does not "fix" it.

### Pitfall 5 — a per-emit grant re-check that silently denies everyone

**What goes wrong:** the KDS stops delivering to *every* user, and every security assertion in the
change still passes.

**Why it happens:** three compounding facts on the broadcast thread — no `SecurityContext`
(so `grantedShopIds()` throws or returns empty), no `TenantContext` (so the tenant-aware cache key
is wrong), and no tenant GUC (so `shop_staff` is empty under FORCE RLS). This is
`trap_rls_blinds_the_verification_query` and `trap_tenant_pin_is_under_a_global_aspect` firing
together.

**How to avoid:** Pattern 3's four steps, and a **positive delivery control arm** in the same test.

**Warning signs:** the revoked-user test passes on the first try with no GUC handling written. That
is the tell, not the reassurance.

### Pitfall 6 — Grafana and Keycloak both ignore a config-only rotation

**What goes wrong:** the rotation looks complete, every file is edited, and neither running service
changed its credential.

**Why:** Grafana applies `GF_SECURITY_ADMIN_PASSWORD` **only when it first creates the admin user**
(recorded in `infra/monitoring/docker-compose.monitoring.yml:22-23` and in
`check-infra-exposure.sh`'s C2 rationale). Keycloak is Postgres-backed, so dropping the volume is a
no-op; the realm needs `kc.sh import --override true` **and a restart**.

**How to avoid:** #552's own acceptance shape — each superseded credential FAILS and the current one
SUCCEEDS **in the same run**. `check-infra-exposure.sh` C2/C3 already implements exactly this for
Grafana; reuse it rather than re-deriving it.

### Pitfall 7 — a new `scripts/check-*.sh` fails `check-gate-enforcement.sh` by default

**What goes wrong:** the triage-doc completeness gate (D-11) lands and CI reds on a *different*
gate.

**Why:** `check-gate-enforcement.sh` is **default-deny** — a static gate must be referenced from
`.github/workflows/`, and a runtime-dependent one must have a reasoned
`scripts/gates/gate-enforcement.conf` entry. The conf **VOIDs** on entries naming scripts that do
not exist yet, so a plan cannot pre-declare a sibling wave's script (this forced an extra wave in
Phase 33 and the conf header records it).

**How to avoid:** land each new gate script and its `ci-cd.yaml` reference in the **same** task. The
triage gate is pure text over a tracked file — static — so it wires into a workflow, never the conf.

### Pitfall 8 — adding tests trips the doc-metrics gates

**What goes wrong:** a green test suite, a red build.

**Why:** `docs/metrics.json` is the single source of truth for 2769 logical invocations, enforced by
**two** gates (`docs-freshness.sh` source→manifest, `check-doc-metrics.sh` prose→manifest). Every
new Java `@Test`, Jest block or Playwright test moves the count.

**How to avoid:** run `scripts/docs-freshness.sh --write`, then update the prose in `CLAUDE.md`,
`AGENTS.md` and `README.md`. Never compute the delta by hand
(`trap_docs_freshness_block_counter`).

---

## Code Examples

### Tenant GUC pin on a non-request thread (the idiom to copy for D-09)

```java
// Source: core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java:154-162
TenantContext.set(event.tenantId());
Session session = entityManager.unwrap(Session.class);
session.doWork(connection -> {
    try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
        stmt.setString(1, event.tenantId().toString());
        stmt.execute();
    }
});
// ... work ...
// finally { TenantContext.clear(); }
```

`is_local = true` makes the pin transaction-scoped, so it cannot ride a recycled Hikari connection
into another tenant's thread. The class's javadoc (`:39-63`) records that `TenantContext.set` is the
*dominant* control (a global aspect re-establishes the GUC) and the explicit `set_config` is
defence-in-depth — so a break arm must neutralise `TenantContext.set`, not the `set_config`.

### The existing cross-tenant guard A1's re-verification breaks

```java
// Source: core-java/src/test/java/uk/jtoye/core/security/access/CrossTenantAuthzIntegrationTest.java:124
void createPromotion_crossTenantShop_isBlocked() { ... }
```

The 2026-08-05 precedent recorded in #548: with the ownership check neutralised in the promotion
create path, the run went to **exactly 1 failure — this method, and no other**; the file was restored
and verified **by content hash**; the suite re-ran clean. Reuse that shape. The companion suites are
`CrossTenantAuthzIntegrationTest` (6 tests), `ShopPromotionsRlsPolicyIntegrationTest` (3 tests) and
`ShopScopedListGateTest`.

### Reading the served OpenAPI document in-process (SC-3's base recipe)

```java
// Source: core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java:102
String raw = mockMvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
```

### Superuser-bootstrap role SQL, the in-repo shape

```sql
-- Source: infra/backups/create-backup-role.sql (structure; grants differ for a runtime role)
\set ON_ERROR_STOP on
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_runtime') THEN
    CREATE ROLE jtoye_runtime LOGIN;
  END IF;
END
$$;
ALTER ROLE jtoye_runtime WITH LOGIN PASSWORD :'runtime_password';
```

Password injected via `psql -v runtime_password="$(...)"`, never a literal — GLOBAL_RULE_6.

### The A1 / D-04 live isolation arm, with its control (measured 2026-08-10)

```sql
SET ROLE jtoye_app;                                     -- (or jtoye_runtime once it exists)
SELECT count(*) FROM products;                          -- no GUC   -> 0
SET app.current_tenant_id = '<tenant A>'; SELECT count(*) FROM products;   -> 47
SET app.current_tenant_id = '<tenant B>'; SELECT count(*) FROM products;   ->  4
RESET ROLE;
SELECT count(*) FROM products;                          -- superuser CONTROL -> 51  (47 + 4)
```

The superuser control is what makes the leading `0` evidence about RLS rather than about an empty
table. **`SET ROLE` from a superuser does subject the session to RLS** — verified here, since the
same session read 0 as `jtoye_app` and 51 after `RESET ROLE`.

---

## State of the Art

| Old approach | Current approach | When changed | Impact on this phase |
|---|---|---|---|
| `/v3/api-docs` `permitAll` unconditionally | Gated on `looksLocal && !isDeployedProfile` | #442 / PR #472 | **D-14 / #549 is already implemented** — see DEC-2 |
| Spec advertises `X-Tenant-Id` in every profile | `TenantHeaderSchemeCustomizer` strips it where `TenantFilter` is absent | #440 (3 unit tests) | SC-3 closes the last link: the *served* document |
| Legacy image endpoints store raw client bytes | All three routed through the Phase-24 normaliser | #445 / PR #479 | #488 is the forward-only remainder; its urgent subset measures 0 |
| `payment_event_outbox` with closed-set dispatch | Dedicated `media_event_outbox`, one exchange, one payload type | V58 / Phase 24 | The `outbox_flusher_dispatch_trap` does **not** apply to a media re-pipeline |
| Superuser named in the k8s DB secret template | NOSUPERUSER `jtoye_app` | Phase 26 / INFRA-02b | D-03 is the *second* correction to the same template — runtime vs owner |
| SSE emitters in one flat shared list | Per-tenant registry + per-shop `ShopScope` snapshot | AUDIT-W0-01 + Phase 23 | D-09 adds the *temporal* dimension the snapshot lacks |

**Deprecated / outdated in the phase inputs:**
- **#548's prevention list** claims the RLS table-coverage assertion does not exist. It does (DEC-1).
- **#549** describes staging as unauthenticated. It is not, since #442 (DEC-2).
- **#488's GDPR framing** — zero of 37 legacy objects carry EXIF or GPS on the live bucket (DEC-4).
- **#284's** "no gated service reached from an async path" — still true, but by one method since
  Phase 24 (Pattern 4).
- **The CONTEXT's outbox-dispatch-trap warning** for #488 — does not apply to `media_event_outbox`.

---

## Assumptions Log

| # | Claim | Section | Risk if wrong |
|---|---|---|---|
| A1 | `jtoye_runtime` is an acceptable role name (CONTEXT says "suggested") | Pattern 1 | Cosmetic; a rename touches compose, k8s templates, bootstrap SQL and the validator message |
| A2 | `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` are acceptable new env key names | Pattern 1 | Cosmetic, but they enter `.env.example`, compose, k8s templates and `verify-env.sh` — settle before writing |
| A3 | Spring's nested placeholder default `${A:${B}}` resolves as expected in `spring.flyway.user` | Pattern 1 | If not, the fallback must be expressed as an explicit per-profile value; **verify with a boot test before relying on it** |
| A4 | Removing the `tenantFilter` bean definition faithfully reproduces the staging document shape | Pattern 2 | If springdoc caches the document before the postprocessor runs, the test asserts nothing — the arm-2 control catches it |
| A5 | The six credentials in D-02 are exactly `DB_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, `EDGE_API_CLIENT_SECRET`, `INTEGRATION_CATALOG_RO_SECRET`, `INTEGRATION_ORDERS_RW_SECRET`, `GRAFANA_ADMIN_PASSWORD` | Credential rotation | Derived by matching #552's prose ("app DB role password, four dev-realm OAuth2 client secrets, monitoring UI admin credential") to `.env.example`; the *count* matches exactly, but #552 names no keys. **Confirm with the owner before rotating** |
| A6 | `mc anonymous set-json` with a Deny on `*/quarantine/*` is the right prefix-scoping mechanism for MinIO | DEC-5 / D-06 | Untested here; MinIO's policy dialect differs from S3 in places. Verify against MinIO docs before planning D-06's execution (which is deferred anyway) |
| A7 | The live dev bucket is the entire object population (no other environment exists) | DEC-4 | Rests on #548's statement that the platform has never been deployed outside a laptop. If a forgotten environment exists, the 0-count does not cover it |
| A8 | `postcode_centroid`'s missing `jtoye_backup` SELECT grant means a `pg_dump` would fail rather than silently skip | Pitfall 1 | Direction of failure only; either way the backup is not what it claims. Confirm by running the backup script's fail direction |

---

## Open Questions

1. **Does the runtime role need `tenants` INSERT?**
   - Known: `DevTenantService` (`:21`) inserts into `tenants`; `tenants` is deliberately RLS-free
     and role-gated. The dev-only tenant endpoint is disabled in production.
   - Unclear: whether `DevTenantController` is profile-gated tightly enough that `jtoye_runtime`
     can be denied INSERT on `tenants` outside dev.
   - Recommendation: grant DML on `tenants` like every other table for now (the admin API is the
     lifecycle writer and it runs in the same JVM); revisit only if a plan wants a tighter split.

2. **What is the grant-cache TTL for D-09, i.e. the revocation latency?**
   - Known: `resolveMembership` is `@Cacheable("shopMembership")` with `evictMembershipAfterCommit`
     on grant/revoke, so the *normal* path is immediate.
   - Unclear: the configured TTL for `shopMembership` in `CacheConfig`, which bounds staleness when
     a revoke happens on another replica.
   - Recommendation: read `CacheConfig` during planning and state the number as the revocation
     latency in the plan — D-09's promise ("a revoked user receives no further events") is only true
     up to that TTL across replicas.

3. **Is `#289`'s STOMP shop gate genuinely out of D-10's scope?**
   - Known: #289 is CLOSED (2026-08-05); STOMP is a different transport with its own
     `TenantChannelInterceptor`.
   - Unclear: whether STOMP subscriptions re-check per message or only at SUBSCRIBE — the same
     temporal defect could exist there.
   - Recommendation: measure it in the same task as D-09. If it re-checks per message, record that
     and move on; if not, it is the same defect class D-10 explicitly says to fix together.

4. **Where does the D-11 completeness gate get its list of 11 finding IDs?**
   - Known: `SECURITY-FINDINGS.md` is git-excluded, so the gate cannot read it.
   - Recommendation: the gate should assert against a literal `A1 A2 A3 B1 B2 C1 C2 C3 C4 D1 E1`
     list embedded in the script, cross-checked against `docs/security/PENTEST-TRIAGE.md`. That is
     the only source both public and complete. Fail direction: delete one line from the triage doc.

---

## Environment Availability

| Dependency | Required by | Available | Version | Fallback |
|---|---|---|---|---|
| Docker + compose stack | A1 live arm, D-04 live arm, rotation verification | ✓ | 11 jtoye containers up 4h; monitoring stack up 29h | — |
| PostgreSQL 15 (`jtoye-postgres`) | Role split measurement | ✓ | postgres:15-alpine | — |
| `psql` inside the container | Every DB measurement | ✓ | — | must use `docker exec -i` for heredocs (`trap_docker_exec_no_stdin_runs_nothing`) |
| MinIO (`jtoye-minio`) | #488 enumeration | ✓ | latest (unpinned — that is #270) | — |
| `curl` | S3 HEAD/list, gate scripts | ✓ | — | — |
| `exiftool` | EXIF census | ✓ | `/usr/bin/exiftool` | `identify` also present |
| `jq` | realm/OpenAPI inspection, gates | ✓ | — | — |
| `mc` (MinIO client) | D-06 policy work | ✗ (not on host) | — | run in a container, or use raw S3 API + curl |
| Grafana (`jtoye-grafana`) | D-02 monitoring-credential arm | ✓ | grafana/10.2.2 | — |
| Keycloak (`jtoye-keycloak`) | D-02 client-secret arms, D-12 audit | ✓ | 24.0.5 | — |
| `gh` CLI | Issue triage / closure | ✓ | authenticated | — |
| Gradle wrapper + JDK 21 | test suites | ✓ | Gradle 8.10, JDK 21 | JDK 25 is incompatible |
| Java `slopcheck` | package audit | n/a | — | no packages installed this phase |

**Missing with no fallback:** none.
**Missing with fallback:** `mc` — run `minio/mc` as a container (which #270 is pinning anyway), or
drive the S3 API directly.

---

## Validation Architecture

### Test framework

| Property | Value |
|---|---|
| Framework (Java) | JUnit 5 + Spring Boot Test + Testcontainers 1.21.4 |
| Config | `core-java/build.gradle.kts` — `tasks.test` excludes `@Tag("testcontainers")`; `tasks.register<Test>("integrationTest")` includes it |
| Quick run | `./gradlew :core-java:test --tests '<Class>'` |
| Integration run | `./gradlew :core-java:integrationTest --tests '<Class>'` |
| Full suite | `./gradlew :core-java:test :core-java:integrationTest` (integrationTest measures 46–49 min in CI) |
| Frontend | `cd frontend && npx jest <path>` ; type-check requires `npm run build` (jest does NOT type-check) |
| Shell gates | `bash scripts/check-*.sh` |
| Count manifest | `docs/metrics.json`, regenerated by `scripts/docs-freshness.sh --write` |

### Phase requirements → test map

| Req | Behaviour | Test type | Automated command | Exists? |
|---|---|---|---|---|
| SEC-01 | A1 cross-tenant write is blocked | integration | `./gradlew :core-java:integrationTest --tests '*CrossTenantAuthzIntegrationTest'` | ✅ 6 tests |
| SEC-01 | A1 RLS policy holds on promotions | integration | `./gradlew :core-java:integrationTest --tests '*ShopPromotionsRlsPolicyIntegrationTest'` | ✅ 3 tests |
| SEC-01 | Delivered runtime matches HEAD before the live arm | shell | `bash scripts/check-runtime-freshness.sh` | ✅ |
| SEC-02 | All 11 finding IDs have a disposition | shell gate | `bash scripts/check-pentest-triage.sh` *(name TBD)* | ❌ Wave 0 |
| SEC-03 | Served document omits the header when the filter is absent | integration | `./gradlew :core-java:integrationTest --tests '*TenantHeaderAbsentDocumentTest'` *(name TBD)* | ❌ Wave 0 |
| SEC-03 | Served document RETAINS the header when the filter is present (control) | integration | same class, second method | ❌ Wave 0 |
| SEC-03 | Customizer model behaviour, both directions | unit | `./gradlew :core-java:test --tests '*TenantHeaderSchemeCustomizerTest'` | ✅ 3 tests |
| SEC-03 / D-14 | `/v3/api-docs` non-200 under staging | integration | `./gradlew :core-java:integrationTest --tests '*StagingActuatorPortIsolationTest'` | ✅ |
| SEC-03 / D-14 | `/v3/api-docs` 200 under dev (control) | integration | `./gradlew :core-java:integrationTest --tests '*OpenApiDevProfileGatingTest'` | ✅ |
| SEC-03 / D-14 | `/v3/api-docs` non-200 under prod | integration | `./gradlew :core-java:integrationTest --tests '*OpenApiProdProfileGatingTest'` | ✅ |
| SEC-04 / D-13 | Every public table has ENABLE + FORCE RLS | integration | `./gradlew :core-java:integrationTest --tests '*RlsContractTest'` | ✅ |
| SEC-04 / D-13 | Every RLS table has ≥1 policy | integration | new method on `RlsContractTest` | ❌ Wave 0 |
| SEC-04 / D-03 | Boot fails when the runtime role owns its tables | integration | new `DatabaseConfigurationValidator` ownership test | ❌ Wave 0 |
| SEC-04 / D-04 | Isolation suite passes as the NON-OWNER role | integration | new provisioning in the Testcontainers RLS harness | ❌ Wave 0 |
| SEC-04 / D-01 | A table created AFTER the grants is readable by the runtime role | integration | new test asserting `has_table_privilege` on a freshly created table | ❌ Wave 0 |
| SEC-04 / D-09 | Revoked user's open emitter receives nothing | integration | new `OrderSseService` test | ❌ Wave 0 |
| SEC-04 / D-09 | Still-granted user's emitter DOES receive (liveness control) | integration | same class | ❌ Wave 0 |
| SEC-04 / #283 | `auth == null` denies instead of bypassing | unit + integration | new tests + FULL suite regression | ❌ Wave 0 |
| SEC-04 / #284 | A gated service reached from an unpropagated async path fails the build | integration | new guard test | ❌ Wave 0 |
| SEC-04 / #270 | Bootstrap fails loud on an unresolvable digest | shell / manual | run with a deliberately wrong digest | ❌ Wave 0 |
| SEC-04 / D-02 | Superseded credential fails AND current succeeds, same run | shell | `bash scripts/check-infra-exposure.sh` (Grafana C2/C3) + a rotation arm script | partial ✅ |
| all | Every new gate is wired | shell | `bash scripts/check-gate-enforcement.sh` | ✅ |
| all | Test counts match the manifest | shell | `bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh` | ✅ |

### Sampling rate

- **Per task commit:** the single affected test class (`--tests '<Class>'`) + `check-gate-enforcement.sh`.
- **Per wave merge:** `./gradlew :core-java:test` (fast) + the wave's integration classes; plus
  `docs-freshness.sh && check-doc-metrics.sh` on any wave that added a test.
- **Phase gate:** **FULL** `./gradlew :core-java:test :core-java:integrationTest` green before
  `/gsd:verify-work`. Non-negotiable for the `asSystem()` wave —
  `trap_scope_gate_integrationtest_regression` records that new auth gates have silently broken
  existing integrationTests, and #283 names 62 no-principal test files.

### Wave 0 gaps

- [ ] `scripts/check-pentest-triage.sh` + its `ci-cd.yaml` reference — covers SEC-02 (D-11). Land
      script and wiring in the SAME task (Pitfall 7).
- [ ] `docs/security/PENTEST-TRIAGE.md` — the gate's subject must exist before the gate.
- [ ] New integration test asserting the served document with `TenantFilter` absent — SEC-03.
- [ ] New `RlsContractTest` method: every RLS-enabled non-exempt table has ≥1 policy — D-13.
- [ ] Runtime-role provisioning in the Testcontainers RLS harness — D-04.
- [ ] `DatabaseConfigurationValidator` ownership assertion + its test — D-03.
- [ ] Future-table grant test (create a table as the migrator, assert the runtime role can read it)
      — Pitfall 1. **This is the highest-value new test in the phase.**
- [ ] `OrderSseService` per-emit re-check tests, both arms — D-09.
- [ ] `asSystem()` unit + guard tests — #283/#284.
- [ ] `docs/runbooks/credential-rotation.md` — D-02.

No framework install is needed; every gap uses infrastructure already present.

---

## Security Domain

### Applicable ASVS categories

| ASVS category | Applies | Standard control in this phase |
|---|---|---|
| V1 Architecture | yes | The runtime/owner role split is a trust-boundary change; the D-03 boot validator is its enforcement point |
| V2 Authentication | yes | D-02 credential rotation; D-12 audience audit (which clients may mint `core-api`) |
| V3 Session Management | yes | D-09 — revocation must reach an **already-established** session, not only new ones |
| V4 Access Control | yes | #283/#284 — replace an implicit bypass with an explicit declaration; `ShopAccessService.require` remains the gate |
| V5 Input Validation | partial | #488's stored Content-Type is the stored-XSS vector; `MediaNormalizer`'s magic-byte allowlist is the control (measured: 0 offending objects) |
| V6 Cryptography | yes | Rotation must generate high-entropy values through the env layer; never hand-roll a generator, never a committed literal |
| V7 Error Handling & Logging | yes | The validator's failure message must name the reason (owner role) without naming a credential |
| V9 Communication | no | Unchanged this phase |
| V10 Malicious Code | yes | #270 — digest-pinning `minio/mc` is supply-chain integrity; `check-image-supply-chain.sh` is the neighbouring gate |
| V12 Files & Resources | yes | The bucket's anonymous `ListBucket` (DEC-5) and the quarantine prefix's public readability |
| V13 API | yes | SC-3 — the published contract must not describe a mechanism the deployed profile lacks |
| V14 Configuration | yes | Profile-conditional doc endpoints; secret-template correctness across compose and k8s |

### Known threat patterns for this stack

| Pattern | STRIDE | Standard mitigation |
|---|---|---|
| Table owner bypasses RLS on a table that forgot FORCE | Information Disclosure | D-01 non-owner runtime role + D-13 sweep; today FORCE on all 36 tenant tables is the (fragile) wall |
| Migrator credential reused as the runtime credential | Elevation of Privilege | Decoupled `spring.flyway.user` (Pattern 1) |
| Contract advertises a dev-only tenant-override header on a deployed profile | Spoofing | `TenantHeaderSchemeCustomizer` + SC-3's served-document assertion |
| Unauthenticated read of the full API surface on staging | Information Disclosure | Already mitigated by #442's profile allowlist (DEC-2) |
| Revoked grant still delivering on an open stream | Elevation of Privilege | D-09 per-emit re-check |
| Background thread inherits no principal and takes an implicit bypass | Elevation of Privilege | #283 `asSystem()`; #284 guard test |
| Stored object with a spoofed Content-Type on a public origin | Tampering (stored XSS) | Normaliser allowlist; measured population = 0 |
| Anonymous enumeration of the whole object inventory | Information Disclosure | **Unmitigated today** (DEC-5) — file it |
| Mutable-tag container image holding root object-storage credentials | Tampering | #270 digest pin + scoped service account |
| Credential read during a pentest still live | Spoofing | D-02 rotation with both-direction arms |
| Realm client able to mint the `core-api` audience unintentionally | Spoofing | D-12 audit — measured table below |

### D-12 / E1 — the audit, already answerable from the committed exports

Measured with `jq` over both realm files. `[VERIFIED: jq over infra/keycloak/*.json]`

| Realm | Client | Confidential | Service acct | Direct `core-api` audience mapper | Intended? |
|---|---|---|---|---|---|
| jtoye-dev | `core-api` | yes | yes | **yes** | yes — it is the resource server's own client, and the frontend logs in as it (`KEYCLOAK_CLIENT_ID=core-api`) |
| jtoye-dev | `integration-catalog-ro` | yes | yes | **yes** | yes — #206 least-privilege machine client (`catalog:read`) |
| jtoye-dev | `integration-orders-rw` | yes | yes | **yes** | yes — Phase 25 machine client (`orders:write`, `customers:write`, `catalog:read`) |
| jtoye-dev | `edge-api` | yes | yes | no | correct — the edge forwards user tokens |
| jtoye-dev | `test-client` | **no (public)** | no | no | **the E1 symptom** — an unused public client in a committed export. #551 asks: remove it or write down why it stays |
| jtoye-dev | `account`, `account-console`, `admin-cli`, `broker`, `realm-management`, `security-admin-console` | mixed | no | no | Keycloak built-ins |
| jtoye-customers | `storefront-client` | — | no | no | correct — `CustomerJwtVerifier:142-144` records that storefront tokens deliberately carry no `core-api` audience and are trust-scoped by issuer instead |

**The indirect path worth naming in the triage doc:** every client carries the `roles` default client
scope, which contains an `oidc-audience-resolve-mapper`. That mapper adds the audience of any client
for which the user holds **client roles**. Measured: `core-api` declares **zero client roles**, and
no seeded user has any client-role mapping — so the resolve path cannot currently mint the `core-api`
audience for anyone. `[VERIFIED: jq over .roles.client and .users[].clientRoles]` Record this
*with the measurement*, because it is the mechanism that would silently widen the audience the day
someone adds a `core-api` client role.

**#551's second acceptance criterion needs a live token, not a config read:** *"Any client not
intended to reach the core API is shown to be rejected by it — an actual token request and an actual
401."* `test-client` is public, so a token can be requested without a secret; `AudienceValidator`
(`core-java/.../security/AudienceValidator.java:37-43`) rejects a token whose `aud` lacks the
configured expected audience. That is the arm to run and record.

---

## Project Constraints (from CLAUDE.md)

Directives the planner must honour; each is a hard constraint, not guidance.

1. **Feature branches only** (user-global) — never commit directly to `main`; PR flow.
2. **No Co-Authored-By trailers** (user-global) — overrides the GSD default commit template.
3. **Existing stack only** — Spring Boot 3.5.16, Next.js 16, Go 1.26, PostgreSQL 15; **JDK 21**
   (JDK 25 breaks Gradle 8.10).
4. **All new code requires tests**; `docs/metrics.json` is the single source of truth for the
   2769-invocation standard and **two** CI gates fail the build on drift.
5. **Multi-tenancy** — every new feature respects RLS and `TenantContext`.
6. **Rebuild ALL containers after code changes before E2E**; `docker compose start` never rebuilds.
7. **Compose is the canonical local dev/E2E runtime**; k8s is the staging/prod deploy target. Run
   Compose XOR a local minikube — never both (shared dev DB).
8. **Incremental Betterment Doctrine** — regression by omission is a defect even with a green suite.
   Directly relevant: D-09 must not silently kill KDS delivery, and D-07/D-08 must not 404 a
   storefront image.
9. **Five cross-cutting quality contracts are design-time acceptance criteria**, not audit
   afterthoughts. For this phase: **security** (a `<threat_model>` block per plan — the ASVS table
   above is its input), **falsifiable evidence + runtime parity** (every criterion shown to FAIL
   first; `check-runtime-freshness.sh` + `check-branch-behind-base.sh` before hand-back),
   **agent-readiness** (no API surface change is planned — record N/A), **web-perf** and **SEO**
   (no user-facing page change — record N/A). Never silently drop a dimension.
10. **GSD workflow enforcement** — file changes go through a GSD command.
11. **Proof Standards** (user-global): every check shown to FAIL before it is trusted; assert the
    clean state LAST as well as first; verify restores **by content** (`git hash-object`), never by
    `git diff --stat`; **commit before running break arms** (`git checkout` restores from the
    index); capture `rc` on the same statement as its command; no `cmd | grep -q X` under
    `pipefail` (use a here-string); `rg`/`grep` honour `.gitignore` — use `rg -uu` or `searchcheck`
    when a count is evidence; text search cannot answer "who calls this" (use the `idea` MCP for
    symbol questions); backticks inside double quotes execute (use `<<'EOF'` or `-F <file>` for
    commit/PR prose).

---

## Sources

### Primary (HIGH confidence)

- **This tree and this running stack**, measured 2026-08-10 — every table in this document marked
  `[VERIFIED: …]`. Live psql via `docker exec -i jtoye-postgres`; live HTTP against
  `http://localhost:9000`; `exiftool`; `jq`; `rg -uu`; `gh issue view`.
- `core-java/src/main/java/uk/jtoye/core/config/{OpenApiConfig,TenantHeaderSchemeCustomizer,DatabaseConfigurationValidator}.java`
- `core-java/src/main/java/uk/jtoye/core/security/{SecurityConfig,TenantFilter,AudienceValidator,CustomerJwtVerifier}.java`,
  `.../security/access/ShopAccessService.java`
- `core-java/src/main/java/uk/jtoye/core/order/{OrderController,OrderSseService,OrderSseFanoutListener}.java`
- `core-java/src/main/java/uk/jtoye/core/media/{MediaAssetService,MediaProcessingWorker,MediaNormalizer,MediaEventOutboxFlusher,MediaProcessingEvent,MediaProperties}.java`
- `core-java/src/main/java/uk/jtoye/core/geo/PostcodeCentroidImporter.java`
- `core-java/src/test/java/uk/jtoye/core/security/{RlsContractTest,OpenApiDevProfileGatingTest,OpenApiProdProfileGatingTest,StagingActuatorPortIsolationTest}.java`,
  `.../integration/{OpenApiSnapshotTest,FreshChainMigrationIntegrationTest}.java`,
  `.../config/TenantHeaderSchemeCustomizerTest.java`,
  `.../security/access/CrossTenantAuthzIntegrationTest.java`
- `infra/db/init/00-create-db.sql`, `infra/backups/create-backup-role.sql`,
  `infra/keycloak/realm-export.template.json`, `infra/keycloak/realm-export-customers.json`,
  `infra/monitoring/docker-compose.monitoring.yml`
- `docker-compose.full-stack.yml`, `core-java/src/main/resources/application*.yml`, `.env.example`,
  `k8s/base/secrets-template.yaml.example`
- `scripts/{check-openapi-snapshot-fresh,check-image-supply-chain,check-infra-exposure,check-gate-enforcement,docs-freshness}.sh`,
  `scripts/gates/gate-enforcement.conf`, `.github/workflows/{ci-cd,e2e-nightly}.yaml|yml`
- GitHub issues #548, #549, #551, #552, #283, #284, #270, #281, #488 (read via `gh issue view`)
- PostgreSQL 15 docs — `ALTER DEFAULT PRIVILEGES` (`FOR ROLE` semantics), `GRANT` (TRUNCATE is not
  implied by DML): https://www.postgresql.org/docs/15/sql-alterdefaultprivileges.html
- Spring Security 6.5 reference — concurrency / `DelegatingSecurityContext*`:
  https://docs.spring.io/spring-security/reference/6.5/features/integrations/concurrency.html
  (via Context7 `/websites/spring_io_spring-security_reference_6_5`)

### Secondary (MEDIUM confidence)

- `.planning/CRITERIA-DECAY-2026-08-08.md`, `.planning/ROADMAP.md` §Phase 28,
  `.planning/ISSUE-DISPOSITION.md` §Phase 28, `.planning/REQUIREMENTS.md:111-114`
- `docs/CHANGELOG.md` entries for #440/#442/#445/#479
- Project memory: `security-findings-untracked-pentest`, `reference_keycloak_realm_reimport`,
  `trap_scope_gate_integrationtest_regression`, `trap_rls_blinds_the_verification_query`,
  `trap_tenant_pin_is_under_a_global_aspect`, `trap_rls_migration_backfill`,
  `outbox_flusher_dispatch_trap`, `trap_docs_freshness_block_counter`,
  `trap_env_example_inline_comment_is_a_value`, `trap_docker_exec_no_stdin_runs_nothing`

### Tertiary (LOW confidence — flagged for validation)

- A6 (MinIO `mc anonymous set-json` prefix Deny semantics) — not tested here.
- A3 (Spring nested placeholder default in `spring.flyway.user`) — assert with a boot test.
- A5 (the exact identity of #552's six credentials) — inferred by matching prose to `.env.example`.

---

## Metadata

**Confidence breakdown:**

| Area | Level | Reason |
|---|---|---|
| Decayed criteria (DEC-1..DEC-5) | **HIGH** | Every one measured on this tree or this stack, each with an explicit control arm |
| DB role split mechanics | **HIGH** | Role/ownership/ACL state measured live; the `FOR ROLE` trap confirmed against the official docs AND observed already failing on `jtoye_backup`/`postcode_centroid` |
| SC-3 gate mechanics | **MEDIUM-HIGH** | The constraint (committed snapshot legitimately contains the header) is measured; the bean-removal recipe is reasoned from verified source and is untested (A4) |
| SSE per-emit re-check | **HIGH** | The thread-context facts are read directly from `OrderSseFanoutListener` and `OrderSseService`; the GUC-pin idiom is a verified in-repo pattern |
| #283/#284 blast radius | **HIGH** | Both bypass coordinates located; async surface counted; the `MediaProcessingWorker → placeAsset` near-miss verified by reading `placeAsset` |
| #488 scope | **HIGH** | Full 768-object census with two independent controls |
| E1 audience audit | **HIGH** | Both realm exports enumerated with `jq`; the indirect resolve path checked and found inert, with the mechanism recorded |
| Credential rotation surfaces | **MEDIUM** | Keys enumerated from `.env.example` and their consumers located; the mapping to #552's prose is inference (A5) |

**Research date:** 2026-08-10
**Valid until:** **2026-08-24** — but three figures decay faster than that and must be re-measured
before being quoted: the 768-object bucket census (any upload changes it), the 41-table / 36-FORCE
counts (any migration changes them), and the `jtoye_backup` missing-grant count (a re-run of the
backup grant script fixes it). `trap_handoff_residue_count_stale` applies: re-run the measurement
before repeating any number here to the user.
