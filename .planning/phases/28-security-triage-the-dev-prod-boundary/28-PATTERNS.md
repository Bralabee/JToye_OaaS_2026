# Phase 28: Security Triage + the Dev/Prod Boundary - Pattern Map

**Mapped:** 2026-08-10
**Files analyzed:** 24 (11 new, 13 modified)
**Analogs found:** 22 / 24 (2 partial — see "No Analog Found")

> **Read order for the planner.** Every excerpt below is a *real* in-tree pattern with a
> file:line coordinate. Where a file's best analog is **itself** (an existing method in the same
> class), that is called out as a self-analog — those are the cheapest and safest to copy, because
> the surrounding class already encodes the reasons.
>
> **Sanitization holds here too.** No literal credential value appears; env *key names* and DB
> *role names* appear only because they are already committed in `.env.example` / compose / k8s
> templates.

---

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match |
|---|---|---|---|---|
| **NEW** `docs/security/PENTEST-TRIAGE.md` | doc (security record) | static reference | `docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md` | exact |
| **NEW** `scripts/check-pentest-triage.sh` | CI gate (static) | file-I/O + transform | `scripts/check-geo-attribution.sh` | exact |
| **NEW** `docs/runbooks/credential-rotation.md` | runbook doc | procedural | `docs/runbooks/rabbitmq-broker-upgrade.md` | exact |
| **NEW** `infra/db/create-runtime-role.sql` | bootstrap SQL (operator) | DDL / batch | `infra/backups/create-backup-role.sql` | exact |
| **NEW** `core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java` | integration test | request-response | `OpenApiSnapshotTest` + `OpenApiProdProfileGatingTest` | exact |
| **NEW** `core-java/src/test/java/uk/jtoye/core/config/DatabaseConfigurationValidatorOwnershipTest.java` | integration test | catalog read / fail-fast | `OpenApiProdProfileGatingTest` (bootstrap) + `RlsContractTest` (pg_class) | role-match |
| **NEW** `core-java/src/test/java/uk/jtoye/core/security/RuntimeRoleGrantContractTest.java` | integration test | DDL + privilege probe | `RlsContractTest` + `IntegrationTestSupport` NOSUPERUSER note | role-match |
| **NEW** `core-java/src/test/java/uk/jtoye/core/order/OrderSseGrantRecheckTest.java` | unit test | event-driven | `OrderSseServiceTenantIsolationTest` | exact |
| **NEW** `core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java` | unit + integration test | request-response | `CrossTenantAuthzIntegrationTest`, `ShopAccessFailClosedIntegrationTest` | role-match |
| **NEW** `asSystem()` marker (in `ShopAccessService` or a sibling `SystemPrincipal`) | security utility | ThreadLocal declaration | `ShopAccessService.isDeclaredMachineClient` (declaration-over-inference) | partial |
| **NEW** `scripts/check-media-content-types.sh` (D-05 enumeration) | runtime gate / tool | file-I/O over S3 | `scripts/check-live-shop-coordinates.sh` + its `gate-enforcement.conf` entry | role-match |
| **MOD** `core-java/src/main/java/uk/jtoye/core/config/DatabaseConfigurationValidator.java` | config validator | boot fail-fast | **self-analog** `validateNotSuperuser()` :97-117 | exact |
| **MOD** `core-java/src/main/java/uk/jtoye/core/order/OrderSseService.java` | service | event-driven (fan-out) | `MediaProcessingWorker` :150-172 + `ShopAccessService.self()` | exact |
| **MOD** `core-java/src/main/java/uk/jtoye/core/security/access/ShopAccessService.java` | service (security) | request-response | **self-analog** `isInternalCaller()` :615-624 | exact |
| **MOD** `core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java` | integration test | schema walk | **self-analog** `everyPublicTableHasRlsAndForce()` :140-167 | exact |
| **MOD** `core-java/src/main/resources/application.yml` | config | startup wiring | **self-analog** the `spring.flyway.*` block :93-110 | exact |
| **MOD** `docker-compose.full-stack.yml` | config | container bootstrap | **self-analog** `minio` / `minio-init` :516-560 | exact |
| **MOD** `.env.example` | config | key manifest | **self-analog** `DB_USER`/`DB_PASSWORD` :98-99, secrets :200-218 | exact |
| **MOD** `k8s/base/secrets-template.yaml.example` | config (k8s) | deploy secret | **self-analog** the DB block :70-89 | exact |
| **MOD** `k8s/QUICK_START.md` | doc | procedural | same file's existing `kubectl create secret` recipe | exact |
| **MOD** `infra/keycloak/realm-export.template.json` | config (IdP) | render-from-env | its own `${...}` client-secret placeholders | exact |
| **MOD** `.github/workflows/ci-cd.yaml` | CI config | gate wiring | `ops-contracts` job, `check-no-create-extension.sh` step :686-689 | exact |
| **MOD** `scripts/gates/gate-enforcement.conf` | config (gate table) | registration | `check-live-shop-coordinates.sh` entry :35 | exact |
| **MOD** `docs/metrics.json` + prose in `CLAUDE.md`/`AGENTS.md`/`README.md` | generated manifest | bookkeeping | `scripts/docs-freshness.sh --write` (never arithmetic) | exact |

---

## Pattern Assignments

### `scripts/check-pentest-triage.sh` (CI gate, static text over a tracked doc) — D-11

**Analog:** `scripts/check-geo-attribution.sh` — the closest shape in the tree: a *small*, static,
doc-content gate that asserts a fixed list of required strings appear in one tracked file, with a
0/1/2 exit contract. (`check-no-create-extension.sh` is the secondary analog for the
**by-addition exemption table** idiom, if any finding ID ever needs one.)

**Header contract — copy this shape verbatim, including the exit-code table and the two
shell-shape warnings** (`scripts/check-geo-attribution.sh:1-30`):

```bash
#!/usr/bin/env bash
#
# Gate: <one-sentence invariant>.
#
# Exit codes:
#   0  <the invariant holds>
#   1  <the invariant is violated — named, with its line>
#   2  VOID — an input is missing, unparseable, or yielded an EMPTY result
#
# 2 is deliberate and load-bearing. "I could not find the footer" must never read as "the footer is
# fine": a gate that fails OPEN on missing input is worse than no gate, because it is trusted.
#
# Shell shapes deliberately avoided, both recorded failure modes in this repo:
#   * `cmd | grep -q X` under `set -o pipefail` INVERTS on match — grep exits at the first hit, the
#     writer takes SIGPIPE, pipefail promotes it to 141. Here-strings only.
#   * `grep -c` exits 1 on a ZERO count, i.e. on the desired state of an absence check. Counts are
#     captured with `|| true` and compared as values.

set -uo pipefail
```

**Path resolution + fail/void helpers** (`check-geo-attribution.sh:30-54`):

```bash
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

TRIAGE_MD="$REPO_ROOT/docs/security/PENTEST-TRIAGE.md"

fail() { echo "FAIL: $*" >&2; exit 1; }
void() { echo "VOID: $*" >&2; exit 2; }

[ -f "$TRIAGE_MD" ] || void "triage doc not found: $TRIAGE_MD"
[ -s "$TRIAGE_MD" ] || void "triage doc is empty: $TRIAGE_MD"
```

**Required-token loop — the exact core pattern for "all 11 finding IDs have a disposition"**
(`check-geo-attribution.sh:41` and `:70-83`). Note `grep -cF ... || true` and the explicit
empty-result VOID:

```bash
HOLDERS=("Ordnance Survey data" "Royal Mail data" "National Statistics data")
...
missing=()
for holder in "${HOLDERS[@]}"; do
    n="$(grep -cF -- "$holder" "$FOOTER" || true)"
    [ -n "$n" ] || void "attribution count for '$holder' yielded an empty result"
    if [ "$n" -lt 1 ]; then
        missing+=("$holder")
    else
        echo "  attribution        : '$holder' present"
    fi
done
if [ "${#missing[@]}" -gt 0 ]; then
    fail "the footer does not name ${#missing[@]} required rights holder(s): ${missing[*]}. ..."
fi
```

**Adaptation for D-11** (per RESEARCH Open Question 4): the ID list is a literal in the script —
`FINDING_IDS=(A1 A2 A3 B1 B2 C1 C2 C3 C4 D1 E1)` — because `SECURITY-FINDINGS.md` is git-excluded
and the gate cannot read it. Match on a **line-anchored disposition row**, not a bare ID
(a bare `A1` would match prose). Two extra assertions the geo gate's §3 justifies:

- a **denominator** assertion (count of disposition rows == 11) so a doc that lost its table
  does not pass by containing the IDs in a paragraph;
- a **status-vocabulary** assertion (each row carries one of the allowed statuses / an issue link
  / a dated acceptance), so a row present-but-empty is not "a disposition".

**Fail direction to run and record:** delete one disposition line, confirm exit 1 naming that ID;
`git hash-object` the restored file; re-run clean. Clean → break → restore → clean.

**Self-match hazard:** the script names all 11 IDs, so its scan must be scoped by **absolute path
to the triage doc only** — the same guard `check-no-create-extension.sh:34-39` documents.

---

### `.github/workflows/ci-cd.yaml` — the gate's wiring (SAME task as the script, Pitfall 7)

**Analog:** the `ops-contracts` job, `check-no-create-extension.sh` step
(`.github/workflows/ci-cd.yaml:677-689`). The job header declares its permissions explicitly
(`:635-641`), and every step carries a comment saying **why the gate is static**:

```yaml
      # Static by construction: reads .sql files, touches no database and makes no
      # network call, so it says the same thing on a hosted runner as it does locally.
      - name: Assert no migration creates a PostgreSQL extension (33-02)
        run: |
          chmod +x ./scripts/check-no-create-extension.sh
          ./scripts/check-no-create-extension.sh
```

**Copy exactly:** `chmod +x` then invoke, `name:` states the invariant, a preceding comment states
why it belongs in `ops-contracts` rather than the heavy `test` job. `check-pentest-triage.sh` is
pure text over a tracked file → **static → workflow reference, never a `gate-enforcement.conf`
entry**.

---

### `scripts/gates/gate-enforcement.conf` — only if a RUNTIME gate is added (D-05 enumeration)

**Analog:** the `check-live-shop-coordinates.sh` entry (`scripts/gates/gate-enforcement.conf:35`) —
the most recent, and the one whose reasoning matches a MinIO/`docker exec` enumeration exactly:

```
check-live-shop-coordinates.sh Reads shops.latitude out of the RUNNING dev Postgres through `docker exec`, as two different database roles, and needs DemoDataSeeder to have seeded and PostcodeCentroidImporter to have loaded 1.7M rows. A hosted runner has no container to exec into and no seeded database, so the script's own preconditions (container running, POSTGRES_USER readable, non-empty result) would exit 2 there on every run. Its whole purpose is to prove the DELIVERED RUNTIME matches the branch, which is by definition not a property of a source checkout — the Testcontainers suite (ShopCoordinateBackfillIntegrationTest) is what carries the same invariant into CI.
```

**The bar (file header :11-14):** an entry means *"a hosted runner does not have the thing this
inspects, so it could only ever exit 2 there"* — not "inconvenient in CI".
**The trap (file header :7-9, and `check-gate-enforcement.sh:152-155`):** the conf **VOIDs** on an
entry naming a script that does not exist. Land the script and its entry in the SAME task; never
pre-declare a sibling plan's script.

---

### `docs/security/PENTEST-TRIAGE.md` (tracked sanitized record) — D-11

**Analog:** `docs/security/PII-EXPOSURE-ASSESSMENT-2026-07-08.md` — the only other file in
`docs/security/`, and the established shape for a dated, sanitized, per-item security record.

**Front-matter block** (`PII-EXPOSURE-ASSESSMENT-2026-07-08.md:1-9`):

```markdown
# PII Exposure Assessment — Public-Repo Database Dumps

**Reference:** Issue #79 / remediation item **P0-3** (`docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md`)
**Assessment date:** 2026-07-08
**Regulation:** UK GDPR (Data Protection Act 2018)
**Classification:** Internal record — Art 33(5) / Art 5(2) accountability
**Status:** Repo-side remediation shipped in this PR; git-history rewrite tracked separately (orchestrator scope)

---
```

**Per-item disposition prose style** (`:36-49`) — impact and measurement, never a payload or a
literal value; measurements stated with their counts:

```markdown
- **Customers table:** the `customers` table in the tracked dump is **empty**.
- **Rows carrying email addresses:** a handful only — roughly 2 `customers_aud` rows and
  ~5 `order` rows.
- **Credentials / secrets:** none. No API keys, no password hashes.
```

**Adaptation:** one machine-greppable row per finding ID so the gate has an anchor. Suggested row
shape (settle the exact regex in the same task as the gate, and cite it in the doc so a reader can
see what the gate reads):

```markdown
| ID | Status | Disposition |
|----|--------|-------------|
| A1 | CONFIRMED | Re-verified against a stack rebuilt from HEAD, 2026-08-__ — see #548 |
| C3 | FIXED | Closed by #442 (profile allowlist); proven both directions — #549 |
| ... |
```

---

### `docs/runbooks/credential-rotation.md` — D-02

**Analog:** `docs/runbooks/rabbitmq-broker-upgrade.md` — same class of runbook (a stateful
out-of-band operator procedure whose failure mode is "the config changed and the running thing did
not").

**Opening ownership + provenance line** (`rabbitmq-broker-upgrade.md:1-4`):

```markdown
# Runbook — RabbitMQ broker upgrade and rollback

Owner: unassigned (see ADR-0002). Written by plan 27-02, 2026-07-29, when the dev/compose broker
moved **3.12.14 → 4.3.4**.
```

**§1 is always "read the fact off the RUNNING thing"** (`:13-25`) — the single most important
pattern to copy for rotation, because Pitfall 6 is exactly this shape:

```markdown
## 1. Read the version off the RUNNING broker — never off the compose file

docker exec jtoye-rabbitmq rabbitmqctl version

`docker-compose.full-stack.yml` states an *intent*. Only the broker states a *fact*.
```

**Failure-shape warnings inline, in bold** (`:66-70`):

```markdown
**A restored data directory whose node name does not match the container's hostname is silently
ignored, and the broker boots empty, healthy and on the right version.** Every rollback assertion
must therefore read a **message count**, never a health check.
```

**Adaptation:** one section per credential surface (DB role · 4 Keycloak client secrets · Grafana
admin), each ending with the #552 acceptance shape — *superseded value FAILS and current value
SUCCEEDS in the same run*. Reuse `check-infra-exposure.sh` C2/C3 verbatim for Grafana rather than
writing a probe (see Shared Patterns).

---

### `infra/db/create-runtime-role.sql` (operator bootstrap) — D-01

**Analog:** `infra/backups/create-backup-role.sql` — same role class (a superuser-only bootstrap
that Flyway provably cannot perform), same password-injection contract, same idempotency.

**Header: why this is not a migration + the usage line** (`create-backup-role.sql:1-19`):

```sql
-- create-backup-role.sql — least-privilege BYPASSRLS dump role for backups (#90 P1-8).
--
-- WHY: ...
-- The BYPASSRLS attribute can only be granted by a superuser, so this is an
-- operator bootstrap step run as the postgres superuser — NOT a Flyway migration
-- (the app migration role lacks the privilege). See docs/runbooks/backups.md.
--
-- USAGE (password injected, never hardcoded):
--   psql -U <superuser> -d jtoye \
--     -v backup_password="$(pass show jtoye/backup-role)" \
--     -f infra/backups/create-backup-role.sql
--
-- Idempotent: safe to re-run (updates the password + re-grants).

\set ON_ERROR_STOP on
```

**Idempotent create + unconditional attribute/password re-assert** (`:23-32`):

```sql
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jtoye_backup') THEN
    CREATE ROLE jtoye_backup LOGIN BYPASSRLS;
  END IF;
END
$$;

-- Ensure the attributes + password are correct even on an existing role.
ALTER ROLE jtoye_backup WITH LOGIN BYPASSRLS PASSWORD :'backup_password';
```

**Grants, each with its measured justification** (`:34-45`):

```sql
GRANT CONNECT ON DATABASE jtoye TO jtoye_backup;
GRANT USAGE ON SCHEMA public TO jtoye_backup;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO jtoye_backup;
-- pg_dump reads each sequence's last_value ... Verified live.
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO jtoye_backup;

-- Cover objects created after this runs, so future migrations stay dumpable.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO jtoye_backup;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON SEQUENCES TO jtoye_backup;
```

> **DO NOT copy the last two lines as written.** `:44-45` is the live Pitfall-1 defect: no
> `FOR ROLE`, so the defaults register against the *superuser* while Flyway creates tables as
> `jtoye_app` — measured, `jtoye_backup` cannot `SELECT postcode_centroid`. The new file must use
> `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app IN SCHEMA public GRANT … TO jtoye_runtime`, and the
> same edit should repair `jtoye_backup`'s two lines in the same task.

**Second analog — the fresh-volume path** `infra/db/init/00-create-db.sql`. Copy its
`\getenv` + `format(%L)` + `\gexec` idiom and its header's explanation of why a literal password is
a live bug (`:1-21`, `:44-45`):

```sql
\getenv app_password DB_PASSWORD

SELECT format('CREATE ROLE jtoye_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'jtoye_app')\gexec
```

Both paths are required and only one fires on any given machine (`00-create-db.sql` runs **only on
an empty data directory**); say so in the runbook.

---

### `core-java/.../config/DatabaseConfigurationValidator.java` (+ its test) — D-03

**Analog: itself.** `validateNotSuperuser()` is the exact pattern the ownership check extends —
same class, same catalog, same failure semantics.

**Registration in the check list** (`DatabaseConfigurationValidator.java:59-72`):

```java
log.info("Database username: {}", dbUsername);

// CRITICAL: Check if using superuser (bypasses RLS)
validateNotSuperuser();

// Check RLS policies exist and are enabled
validateRlsPolicies();
```

**The check body to mirror** (`:97-117`) — a single catalog query, a `SecurityConfigurationException`
whose message names the reason AND the remedy AND the files to edit, and a success log line:

```java
private void validateNotSuperuser() {
    log.info("Checking if database user is a superuser...");

    String sql = "SELECT usesuper FROM pg_user WHERE usename = CURRENT_USER";
    Boolean isSuperuser = jdbcTemplate.queryForObject(sql, Boolean.class);

    if (Boolean.TRUE.equals(isSuperuser)) {
        String error = String.format(
            "CRITICAL SECURITY ERROR: Application is using PostgreSQL superuser '%s'. " +
            "Superusers BYPASS Row-Level Security policies, making multi-tenant isolation IMPOSSIBLE. " +
            "... Solution: Change DB_USER to 'jtoye_app' in your configuration. " +
            "Files to update: docker-compose.full-stack.yml, core-java/.env, core-java/src/main/resources/application.yml",
            dbUsername
        );
        throw new SecurityConfigurationException(error);
    }

    log.info("✅ User '{}' is NOT a superuser (RLS will be enforced)", dbUsername);
}
```

**Privilege probe already in the class** (`:189-199`) — the ownership check should use the same
`has_table_privilege(CURRENT_USER, …)` style, against `pg_class.relowner`:

```java
String sql = String.format(
    "SELECT has_table_privilege(CURRENT_USER, '%s', 'SELECT')", table);
Boolean hasSelect = jdbcTemplate.queryForObject(sql, Boolean.class);
```

**Two constraints the planner must carry:**
- the class is `@Profile("!test")` (`:30`) — the new ownership test must therefore activate a
  non-`test` profile or the validator does not run at all (this is why the test's analog is
  `OpenApiProdProfileGatingTest`, which runs `@ActiveProfiles({"prod","test"})`);
- the failure message must **name the reason without naming a credential** (ASVS V7, RESEARCH
  §Security Domain). Copy the "Files to update:" tail — it is what makes the fail-fast actionable.
- **D-13 gap:** `validateRlsPolicies()` (`:122-152`) checks policy count for only five hardcoded
  tables (`TENANT_SCOPED_TABLES`, `:40-42`). The "RLS enabled but zero policies" sweep belongs on
  `RlsContractTest` (below), not here.

---

### `core-java/src/test/.../security/RlsContractTest.java` — new "≥1 policy" method (D-13)

**Analog: itself.** `everyPublicTableHasRlsAndForce()` is the schema-walk the new method mirrors.

**The walk + exemption skip + failure message that tells you what to do** (`RlsContractTest.java:140-167`):

```java
@Test
void everyPublicTableHasRlsAndForce() {
    List<Map<String, Object>> tables = jdbc.queryForList(
            "SELECT relname, relrowsecurity, relforcerowsecurity " +
                    "FROM pg_class " +
                    "WHERE relkind = 'r' " +
                    "  AND relnamespace = 'public'::regnamespace " +
                    "ORDER BY relname");

    for (Map<String, Object> row : tables) {
        String name = (String) row.get("relname");
        if (EXEMPT_TABLES.contains(name)) continue;

        assertThat(row.get("relrowsecurity"))
                .as("ENABLE ROW LEVEL SECURITY missing on public.%s — ... If %s is " +
                        "intentionally not tenant-scoped, add it to RlsContractTest.EXEMPT_TABLES " +
                        "with a written justification.", name, name)
                .isEqualTo(true);
```

**By-addition exemption with a written justification** (`:88-134`) — the V61 entry is the model for
any table the new sweep must exempt:

```java
/**
 * ... <p>If a future migration adds a public table that legitimately doesn't
 * need RLS (e.g. another infrastructure-level idempotency log), add it
 * here with a comment — DO NOT weaken the assertion below.
 */
private static final Set<String> EXEMPT_TABLES = Set.of(
        "flyway_schema_history",
        ...
        // V61 (Phase 33 / plan 33-02) public reference data: ... Note this is exempted BY ADDITION,
        // per the standing instruction above; the schema-walk assertion itself is untouched.
        "postcode_centroid"
);
```

**Testcontainers bootstrap** (`:51-83`) — this class registers its own properties rather than using
`IntegrationTestSupport`; either is acceptable, but note `spring.jpa.hibernate.ddl-auto → none` is
load-bearing (`:68-74`) so Flyway's RLS state is the sole source of truth.

**New method shape (D-13's only real gap, per DEC-1):** walk `pg_class` for
`relrowsecurity = true`, LEFT JOIN `pg_policy`, assert `count > 0` per table. **Non-vacuity control
required** — RESEARCH measured 36 tables that DO have policies, so the query is provably capable of
returning rows; assert that denominator in the same method or the zero is meaningless.

---

### `core-java/src/test/.../config/TenantHeaderAbsentDocumentTest.java` (SC-3)

**Primary analog:** `OpenApiSnapshotTest` for the *fetch*; `OpenApiProdProfileGatingTest` for the
*arms and the non-vacuity control*.

**The served-document fetch** (`OpenApiSnapshotTest.java:100-104`):

```java
@Test
void apiDocsMatchCommittedSnapshot() throws Exception {
    String raw = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
```

**The one-line bootstrap** (`OpenApiSnapshotTest.java:86-95`) — prefer this over hand-rolling the
property set:

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("jtoye_test").withUsername("test").withPassword("test");

@DynamicPropertySource
static void configureProperties(DynamicPropertyRegistry registry) {
    IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
}
```

**Why it is MockMvc and not the gradle plugin** — copy this javadoc reasoning forward, it is the
recorded rejection (`OpenApiSnapshotTest.java:39-46`):

```java
 * <p>Boots the FULL Spring context ... and fetches the real springdoc output from
 * {@code /v3/api-docs} via MockMvc — so the spec reflects exactly what production serves ...
 * This is deliberately NOT the springdoc-openapi-gradle-plugin: that boots the app
 * out-of-band (needs a live DB and port juggling in CI) ...
```

**The falsifiability discipline to copy verbatim** — `OpenApiProdProfileGatingTest` carries a
comment block naming the property that makes the test *capable of failing*
(`OpenApiProdProfileGatingTest.java:81-96`):

```java
// THE LINE THAT MAKES THIS TEST CAPABLE OF FAILING.
//
// application-prod.yml sets springdoc.api-docs.enabled=${SWAGGER_ENABLED:false},
// so by default the document does not exist in prod and every path below
// returns 404. The first version of this class asserted "not 200" without
// these two properties and PASSED IDENTICALLY with the security fix reverted —
// an assertion that was already true before the change, proving nothing.
// Caught only by running the break arm.
registry.add("springdoc.api-docs.enabled", () -> "true");
```

**The non-vacuity control method** (`OpenApiProdProfileGatingTest.java:126-148`) — the exact shape
arm 2 and arm 3 need:

```java
/**
 * Non-vacuity control. If the application answered non-200 to everything
 * anonymous — a broken context, a misrouted chain — the two assertions above
 * would pass while proving nothing.
 * ... That was measured, not assumed — the first version of this control asserted 200 and
 * failed at 404.
 */
@Test
void aGenuinelyPublicRouteIsStillAnonymousInProd() throws Exception { ... }
```

**The surface under test** (`TenantHeaderSchemeCustomizer.java:70-71`) — the `else` branch is what
SC-3 asserts:

```java
public void customise(OpenAPI openApi) {
    if (openApi == null || tenantFilterProvider.getIfAvailable() != null) {
```

**Assert on the string, never a copied literal** (`TenantHeaderSchemeCustomizer.java:146,155`
already reference `TenantFilter.TENANT_HEADER` rather than a literal — do the same in the test):

```java
assertThat(served).doesNotContain(TenantFilter.TENANT_HEADER);
assertThat(served).doesNotContain("tenant-header");
```

**Three arms, per RESEARCH Pattern 2:** (1) filter bean removed → neither string; (2) stock `test`
context → BOTH strings present (control); (3) `paths` non-empty (denominator — an empty document
satisfies arm 1 vacuously).

**Do NOT wire this as a `scripts/check-*.sh`** (RESEARCH Pattern 2 close): it needs no runtime, it
belongs in `integrationTest`, and a shell gate would owe `gate-enforcement.conf` an entry it cannot
honestly make.

---

### `core-java/.../order/OrderSseService.java` — per-emit grant re-check (D-09/D-10)

**Analog A — the emitter registry the change extends (self-analog).** `subscribe()` already
snapshots the scope; D-09 adds `userId` to the same record (`OrderSseService.java:37-43`, `:53-54`,
`:63-78`):

```java
private record ShopScope(boolean groupAdmin, Set<UUID> shopIds) {
    boolean permits(UUID shopId) {
        // A null event shopId (legacy/unknown) is only ever delivered to a
        // GROUP_ADMIN — never leaked to a scoped subscriber (deny-by-default).
        return groupAdmin || (shopId != null && shopIds.contains(shopId));
    }
}

private final ConcurrentHashMap<UUID, ConcurrentHashMap<SseEmitter, ShopScope>> emittersByTenant =
        new ConcurrentHashMap<>();
```

**The filter site to extend** (`OrderSseService.java:98-116`) — the re-check goes beside
`scope.permits(...)`, and note the existing catch-Exception rationale must survive:

```java
for (var entry : bucket.entrySet()) {
    SseEmitter emitter = entry.getKey();
    ShopScope scope = entry.getValue();
    // §3-FLAG #2: grant-set filter — skip emitters not scoped to this event's shop.
    if (!scope.permits(event.shopId())) {
        continue;
    }
    try {
        emitter.send(SseEmitter.event().name("order-state-change").data(event));
    } catch (Exception e) {
        // ... Any send failure just means THIS client is gone.
        removeEmitter(event.tenantId(), emitter);
    }
}
```

**Analog B — the tenant GUC pin on a non-request thread. THIS IS THE LOAD-BEARING COPY.**
`broadcast()` runs on `OrderSseFanoutListener`'s `@RabbitListener` thread (`OrderSseFanoutListener.java:36-42`)
with **no SecurityContext, no TenantContext, no tenant GUC**. Copy
`MediaProcessingWorker.java:154-172`:

```java
// Tenant context FIRST — ThreadLocal AND DB session GUC. The worker runs off the
// request thread, so without this pin RLS hides the PENDING row (T-24-17); the
// set_config mirrors OrderStateChangeListener's hard-pin idiom.
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

`is_local = true` makes the pin transaction-scoped so it cannot ride a recycled Hikari connection
into another tenant.

**Analog C — resolve through the proxy, not `this`** (`ShopAccessService.java:90-95`, used at
`:200`, `:342`, `:440`, `:456`):

```java
 * Lazy self-reference (WR-01): {@link #resolveMembership} is {@code @Cacheable}, but
 * ({@code this.resolveMembership(...)}) never passes through the caching interceptor — the
 * ... The internal gate methods therefore reach {@code resolveMembership}
 * through this bean proxy ({@code self().resolveMembership(...)}) so the interceptor actually
```

The cache is keyed by `tenantAwareCacheKeyGenerator` (`ShopAccessService.java:467`), which reads
`TenantContext` — so the pin MUST precede the resolve or the key is wrong:

```java
@Cacheable(value = "shopMembership", keyGenerator = "tenantAwareCacheKeyGenerator")
public Membership resolveMembership(UUID userId) {
```

Freshness comes from the existing post-commit evict (`ShopAccessService.java:513-524`) — the
`shopMembership` TTL **is** the cross-replica revocation latency; read it out of `CacheConfig` and
state the number in the plan (RESEARCH Open Question 2).

**Test analog — `OrderSseServiceTenantIsolationTest`.** Constructs the service directly, no Spring,
no Testcontainers, so it stays in the fast `test` task
(`OrderSseServiceTenantIsolationTest.java:33-52`):

```java
/**
 * <p>Constructs {@link OrderSseService} directly — no Spring context, no Testcontainers —
 * because the service has no DB or Spring dependency apart from its {@code @Service}
 * annotation. This keeps the regression suite in the fast (default) Gradle test task.</p>
 */
private final ShopAccessService shopAccessService = Mockito.mock(ShopAccessService.class);
private final OrderSseService service = new OrderSseService(shopAccessService);

@BeforeEach
void stubGroupAdmin() { Mockito.when(shopAccessService.isGroupAdmin()).thenReturn(true); }
```

**Both-arm verification idiom** (`:85-92`) — the security arm and the liveness arm read identically,
which is exactly what Pitfall 5 requires:

```java
service.broadcast(eventForA);

verify(spyA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
verify(spyB, never()).send(any(SseEmitter.SseEventBuilder.class));
```

**Emitter-swap helper to reuse as-is** (`:204-215`) — it already preserves the captured scope
object, so it survives adding `userId` to the record:

```java
private void replaceEmitterInBucket(UUID tenant, SseEmitter from, SseEmitter to) throws Exception {
    Field f = OrderSseService.class.getDeclaredField("emittersByTenant");
    f.setAccessible(true);
    Map<UUID, Map> map = (Map<UUID, Map>) f.get(service);
    Map bucket = map.get(tenant);
    Object scope = bucket.remove(from);
    bucket.put(to, scope);
}
```

> **Scope note (D-10):** RESEARCH measured exactly ONE SSE surface —
> `OrderController.java:55-59` (`@GetMapping(value="/stream", produces="text/event-stream")` →
> `sseService.subscribe()`). D-10 is satisfied by changing one class. Do not plan a search.

---

### `core-java/.../security/access/ShopAccessService.java` — `asSystem()` (#283/#284)

**Analog: itself, twice.**

**The coordinate #283 is about** (`ShopAccessService.java:615-624`) — the javadoc already states the
semantics the marker replaces:

```java
/**
 * True ONLY when there is no {@code Authentication} on the current thread — the
 * retained internal-caller bypass (scheduler, listener, internal service call, or a
 * test that set only {@link TenantContext}). An anonymous or otherwise
 * non-authenticated request principal is NOT internal and is denied; see
 * {@link #isGroupAdmin()} for why this narrow rule is fail-closed (CR-03 / D-04).
 */
private boolean isInternalCaller() {
    return SecurityContextHolder.getContext().getAuthentication() == null;
}
```

**The declaration-over-inference precedent to copy** (`ShopAccessService.java:626-637`) — the same
class already made exactly this move once, for machine clients, and its javadoc is the template for
`asSystem()`'s:

```java
/**
 * True when {@code jwt} is a declared machine/service client: its {@code sub} is
 * NOT a UUID ... AND its {@code azp}/{@code client_id} claim is present in the configured,
 * empty-by-default {@link #machineClientIds} allowlist. Trust is granted ONLY by
 * this explicit declaration — never inferred from the unparseable subject alone
 * (CR-03 / D-04). RLS still tenant-scopes an allowlisted machine caller.
 */
private boolean isDeclaredMachineClient(Jwt jwt) {
```

**The second coordinate** (`ShopAccessService.java:537-541`) — `onRequest()`'s side-effect guard;
it returns early rather than denying, so it is a different (benign) shape. Confirm the plan does not
conflate the two:

```java
private void onRequest() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
        return;
    }
```

**Test analogs:** `CrossTenantAuthzIntegrationTest` (Testcontainers + `SecurityContextHolder` +
`JwtAuthenticationToken` construction, `AopTestUtils`/`ReflectionTestUtils` for proxy unwrapping —
imports at `:1-33`) and `ShopAccessFailClosedIntegrationTest` for the deny-direction shape. The
named A1 re-verification target is
`CrossTenantAuthzIntegrationTest.createPromotion_crossTenantShop_isBlocked` (`:124`).

**Blast radius the plan must budget:** 62 no-principal test files depend on the current behaviour;
`trap_scope_gate_integrationtest_regression` records that new auth gates have silently broken
existing integrationTests. Budget the FULL `./gradlew :core-java:test :core-java:integrationTest`
(46–49 min in CI), not a targeted run.

---

### `core-java/src/main/resources/application.yml` — Flyway credential decoupling (D-01)

**Analog: itself.** The existing block already carries the rationale that the edit must not break
(`application.yml:93-110`):

```yaml
    # url/user/password are set DELIBERATELY: Spring Boot only builds Flyway a
    # dedicated, non-pooling SimpleDriverDataSource when spring.flyway.url is
    # present (FlywayAutoConfiguration#getMigrationDataSource). Without it
    # Flyway borrows from the application's Hikari pool, Hikari does not reset
    # custom GUCs on return, and the session-scoped sentinel would leak into
    # application request connections — a far worse hazard on an RLS system
    # than the bug being fixed.
    #
    # Inherited by every profile: application-staging.yml / application-prod.yml
    # deliberately do NOT restate these keys (see the note in each).
    url: ${spring.datasource.url}
    user: ${spring.datasource.username}
    password: ${spring.datasource.password}
    init-sqls:
      - "SET app.current_tenant_id = '00000000-0000-0000-0000-000000000000'"
```

**Edit shape (Pitfall 2):** keep `url:` declared, change only `user`/`password` to the
backward-compatible indirection, and **extend the comment** to say why:

```yaml
    user: ${DB_MIGRATION_USER:${spring.datasource.username}}
    password: ${DB_MIGRATION_PASSWORD:${spring.datasource.password}}
```

**Canary to read before editing:** `core-java/src/test/java/uk/jtoye/core/integration/FreshChainMigrationIntegrationTest.java`
boots the real autoconfiguration on these keys and asserts the dedicated-DataSource property.
RESEARCH assumption A3 (nested `${A:${B}}` resolution) is **unverified** — prove it with a boot test
before relying on it.

---

### `docker-compose.full-stack.yml` — `minio/mc` digest pin (#270) + runtime-role env (D-03)

**Analog: itself.** The tag indirection and required-env idiom already exist
(`docker-compose.full-stack.yml:516-560`):

```yaml
  minio:
    image: minio/minio:${MINIO_IMAGE_TAG:-latest}
    ...
    ports:
      # Loopback-only by default (#441). ...
      - "${JTOYE_BIND_HOST:-127.0.0.1}:9000:9000"   # S3 API

  # MinIO init — creates bucket with public-read policy
  minio-init:
    image: minio/mc:${MINIO_MC_IMAGE_TAG:-latest}
    container_name: jtoye-minio-init
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER:?MINIO_ROOT_USER must be set}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD must be set}
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 $$MINIO_ROOT_USER $$MINIO_ROOT_PASSWORD &&
      mc mb --ignore-existing local/jtoye-images &&
      mc anonymous set download local/jtoye-images &&
      echo 'Bucket jtoye-images ready with public-read policy'
      "
```

**Three patterns to copy:** `${VAR:?message}` for a required credential (fails loud, names the
key, never a default); `${IMAGE_TAG:-latest}` indirection so the pin lives in `.env`; `$$VAR` escaping
inside an `entrypoint:` string.

**The DB env consumer to extend** (`:272-273`):

```yaml
      DB_USER: ${DB_USER}
      DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD must be set}
```

**#270 adaptation:** pin by digest (`minio/mc@sha256:…`) via `MINIO_MC_IMAGE_TAG` so the pin is a
one-line `.env`/`.env.example` change, and have the bootstrap echo the resolved image ID.
**DEC-5, worth filing alongside:** `mc anonymous set download` grants `s3:ListBucket` as well as
`s3:GetObject` — the full 768-key inventory is enumerable with no credential. A "non-public
quarantine prefix" is therefore not achievable by naming convention.

---

### `.env.example` — new keys + rotated placeholders (D-02/D-03)

**Analog: itself.** Existing shape (`.env.example:98-99`, `:200-218`, `:308-321`):

```
DB_USER=jtoye_app
DB_PASSWORD=CHANGE_ME
...
# KEYCLOAK_CLIENT_SECRET ALSO renders the realm core-api client secret at Keycloak
KEYCLOAK_CLIENT_SECRET=CHANGE_ME
# EDGE_API_CLIENT_SECRET renders the realm edge-api client secret.
EDGE_API_CLIENT_SECRET=CHANGE_ME
INTEGRATION_CATALOG_RO_SECRET=CHANGE_ME
INTEGRATION_ORDERS_RW_SECRET=CHANGE_ME
...
GRAFANA_ADMIN_PASSWORD=CHANGE_ME
```

**Conventions:** placeholder is exactly `CHANGE_ME`; the explanatory comment goes on its **own
line ABOVE** the key. **Trap `trap_env_example_inline_comment_is_a_value`:** `VAR=  # comment`
resolves to the comment text, so an is-configured guard reads TRUE — never write a trailing comment
on a value line.

**New keys (RESEARCH assumption A2, settle the names before writing):** `DB_MIGRATION_USER`,
`DB_MIGRATION_PASSWORD`. Check whether `scripts/verify-env.sh` enumerates keys and needs the pair.

---

### `k8s/base/secrets-template.yaml.example` — the runtime/owner split (D-03)

**Analog: itself.** This is the **second** correction to the same block (Phase 26 fixed
superuser → `jtoye_app`); copy its explanatory style (`:70-89`):

```yaml
  # A PostgreSQL superuser BYPASSES every Row-Level Security policy, which makes
  # multi-tenant isolation impossible ... core-java refuses to start rather than run that way:
  # DatabaseConfigurationValidator queries pg_roles at boot and throws
  # SecurityConfigurationException ...
  #
  # In .env the two are already separate pairs, and it is the FORMER you want:
  #   DB_USER / DB_PASSWORD             -> the app role (jtoye_app) — USE THIS
  #   POSTGRES_USER / POSTGRES_PASSWORD -> the superuser — do NOT use for the app
  username: "jtoye_app"
  password: "REPLACE_WITH_SECURE_PASSWORD"  # Generate strong password
  # Least-privilege BYPASSRLS dump role for pg-backup (#90). Create it as the
  # superuser via infra/backups/create-backup-role.sql — pg_dump as the app role
  # would capture 0 rows from FORCE-RLS tenant tables.
  backup-username: "jtoye_backup"
  backup-password: "REPLACE_WITH_SECURE_PASSWORD"
```

**Copy the shape exactly for the runtime role:** a named comment block stating *why* the value is
what it is and what happens if you use the other one, then `runtime-username` /
`runtime-password` beside the existing pairs, then the same treatment in the commented
`kubectl create secret` recipe further down (`:225-228`). `k8s/QUICK_START.md` carries the same
recipe and must move with it.

---

### `infra/keycloak/realm-export.template.json` — one import, two payloads (D-02 + D-12)

**Analog: the file's own `${...}` client-secret placeholders**, rendered from `.env` (the keys are
enumerated in `.env.example:200-218`). The realm is **Postgres-backed** — dropping the volume is a
no-op; the change reaches the running realm only via `kc.sh import --override true` **plus a
restart** (`infra/keycloak/README.md`, `reference_keycloak_realm_reimport` memory).

**D-12's audit is already answerable statically** — RESEARCH enumerated every client with `jq` over
both committed exports. The only symptom is the unused **public** `test-client`; the indirect
`oidc-audience-resolve-mapper` path is currently inert (`core-api` declares zero client roles).
Record the measurement in the triage doc; fix the template on the SAME import the rotation needs.

---

## Shared Patterns

### 1. Falsifiability — every new gate/criterion shown to FAIL before it is trusted

**Sources:** `scripts/check-no-create-extension.sh:26-44` (exit-code contract + the two shell
hazards), `OpenApiProdProfileGatingTest.java:81-96` (the "line that makes this test capable of
failing"), `:126-148` (the non-vacuity control), `RESEARCH §Pattern 2` (clean → break → restore →
clean, restore verified by `git hash-object`).
**Apply to:** `check-pentest-triage.sh`, the SC-3 document test, the validator ownership check, the
D-13 policy sweep, the D-01 future-table grant test, the SSE re-check tests.

```bash
# Exit codes:
#   0  <invariant holds>   1  <violated, named>   2  VOID — input missing/unparseable/EMPTY
# 2 is deliberate and load-bearing: "I could not find it" must never read as "it is fine".
```

### 2. Non-vacuity control on every measurement

**Source:** `OpenApiProdProfileGatingTest.aGenuinelyPublicRouteIsStillAnonymousInProd` (`:141-148`);
`check-geo-attribution.sh:74` (`[ -n "$n" ] || void "... yielded an empty result"`);
`check-no-create-extension.sh:67` (`refusing to report clean over an empty scan`).
**Apply to:** every arm in the phase. Specific instances RESEARCH already measured:
- D-04 live isolation: superuser control returns 51 = 47 + 4, so the leading 0 is about RLS;
- D-13: 36 tables DO have policies, so the "zero policy-less tables" result is not vacuous;
- SC-3: `paths` non-empty, else arm 1 passes over an empty document;
- D-09: a still-granted user's emitter DOES receive — without it, "no grants for everyone" passes
  the security arm perfectly while killing the KDS.

### 3. Tenant GUC pin on any non-request thread

**Source:** `core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java:154-172`.
**Apply to:** `OrderSseService.broadcast` (D-09), and anything the `asSystem()` work touches on a
`@RabbitListener` / `@Scheduled` / `@Async` path.

```java
TenantContext.set(event.tenantId());
Session session = entityManager.unwrap(Session.class);
session.doWork(connection -> {
    try (var stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
        stmt.setString(1, event.tenantId().toString());
        stmt.execute();
    }
});
```

`TenantContext.set` is the **dominant** control (a global aspect re-establishes the GUC); the
explicit `set_config` is defence in depth — so a break arm must neutralise `TenantContext.set`,
NOT the `set_config` (`trap_tenant_pin_is_under_a_global_aspect`).

### 4. Credentials flow through the env layer, never a literal

**Sources:** `infra/db/init/00-create-db.sql:1-32` (`\getenv` + `format(%L)` + `\gexec`, and the
header explaining why a literal was a live bug); `infra/backups/create-backup-role.sql:14-17`
(`psql -v backup_password="$(...)"`); `docker-compose.full-stack.yml` `${VAR:?message}`;
`.env.example` `CHANGE_ME`.
**Apply to:** the runtime-role bootstrap SQL, compose, k8s templates, the rotation runbook.

### 5. Reuse `check-infra-exposure.sh` for the rotation acceptance arms — do not re-derive

**Source:** `scripts/check-infra-exposure.sh:275-329`. It already encodes #552's acceptance shape
(current value SUCCEEDS **and** a random value is REJECTED, in the same run) and the
Grafana-first-user-creation trap:

```bash
# C2 — the RUNNING instance actually uses the injected value.
g_ok=$(login_status "$GUSER" "$GPASS")
crc=$?
if [ "$crc" -ne 0 ] || [ -z "$g_ok" ] || [ "$g_ok" = "000" ]; then
  void "Grafana not reachable on 127.0.0.1:${GPORT} (curl rc=${crc}, status=${g_ok:-<empty>}) — cannot measure C"
  exit 2
fi
...
# C3 — instrument validity: the endpoint must be able to say no.
g_bad=$(login_status "$GUSER" "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')")
if [ "$g_bad" = "200" ]; then
  fail "C3: the running Grafana accepted a RANDOM credential (HTTP ${g_bad}) — authentication is not discriminating, so C2 proves nothing"
```

Note `crc=$?` is captured **on the line after the assignment with no intervening command** — the
`trap_exit_code_read_after_echo` shape. Assertion **D** (`:333-344`) enumerates the broker's own
user list rather than probing a default password — strictly stronger, and names no credential in a
public repo.

### 6. Test-count bookkeeping after adding any test

**Source:** `RESEARCH §Don't Hand-Roll` + `trap_docs_freshness_block_counter`.
**Apply to:** every plan that adds a Java `@Test`.

```bash
bash scripts/docs-freshness.sh --write     # regenerate docs/metrics.json — NEVER arithmetic
# then update the prose counts in CLAUDE.md, AGENTS.md, README.md
bash scripts/docs-freshness.sh && bash scripts/check-doc-metrics.sh
```

### 7. Testcontainers bootstrap for a new integration test

**Source:** `IntegrationTestSupport.registerPostgresTestProperties` (`:47-60`) — one call replaces
the whole property block. Its javadoc (`:26-34`) carries the RLS caveat the D-04 harness extends:

```java
 * <p><strong>RLS caveat:</strong> the Testcontainers bootstrap role is a
 * Postgres SUPERUSER, which bypasses even FORCE ROW LEVEL SECURITY. Tests that
 * must prove RLS <em>enforcement</em> ... additionally downgrade the role after seeding:
 * {@code jdbcTemplate.execute("ALTER ROLE \"" + postgres.getUsername() + "\" NOSUPERUSER")}
 * — see {@code ScheduledCleanupServiceIntegrationTest} and
 * {@code ShopImageCrossTenantIntegrationTest} for the pattern, and remember the
 * tenant GUC is only applied inside an active transaction
 * (TenantSetLocalAspect no-ops otherwise).
```

**D-04 extends this by one step:** the harness must additionally create a **non-owner** role and
run the isolation suite as it. 45 test files already use the NOSUPERUSER downgrade — extending
`IntegrationTestSupport` (rather than a new helper) reaches all of them.

---

## No Analog Found

| File | Role | Data Flow | Reason |
|---|---|---|---|
| `asSystem()` system-principal marker (new type or new methods on `ShopAccessService`) | security utility | ThreadLocal declaration | No explicit system-principal marker exists anywhere in the tree — the current mechanism is the *inference* at `ShopAccessService.java:623`. The closest shape is `isDeclaredMachineClient` (`:635-637`), which is declaration-over-inference but reads a JWT claim rather than a thread-local. Spring supplies `DelegatingSecurityContextRunnable`/`Executor` for *propagation*, which is different semantics (carries a **user's** identity into background work) and does not remove the bypass. Planner should design from `#283`'s stated fix shape + `isDeclaredMachineClient`'s javadoc conventions. |
| `scripts/check-media-content-types.sh` (D-05 enumeration) | runtime tool | S3 enumeration | No existing script drives the S3/MinIO API. `check-live-shop-coordinates.sh` is the structural analog (runtime-dependent, `docker exec`, conf-exempt, non-empty-result VOID) but its data source is Postgres. `mc` is **not installed on this host** — run `minio/mc` as a container (which #270 pins anyway) or drive the S3 API with `curl`. **Note the subset measures ZERO (DEC-4): the deliverable is the tool + the recorded measurement + its control, not a re-pipeline run.** |

**Also carrying no in-tree analog (deliberately deferred, spec only):** D-06/D-07/D-08's quarantine
re-pipeline. RESEARCH DEC-4 measured the input population at 0, so there is nothing to build this
phase. If a later phase executes it, the analogs are already identified: `MediaAssetService` re-drive
path (`:360-414`), `MediaProcessingEvent(tenantId, assetId)` on `media_event_outbox`
(the `outbox_flusher_dispatch_trap` does **not** apply — `MediaEventOutboxFlusher.publishRow` has no
closed-set dispatch), and the V60 `quarantine_expires_at` column pair.

---

## Metadata

**Analog search scope:** `scripts/`, `scripts/gates/`, `k8s/scripts/`, `.github/workflows/`,
`infra/db/`, `infra/backups/`, `infra/keycloak/`, `docs/security/`, `docs/runbooks/`,
`core-java/src/main/java/uk/jtoye/core/{config,security,security/access,order,media}`,
`core-java/src/test/java/uk/jtoye/core/{security,security/access,order,config,integration,testsupport}`,
`docker-compose.full-stack.yml`, `.env.example`, `k8s/base/`.

**Files read in full or in targeted ranges:** 18
(`check-no-create-extension.sh`, `check-gate-enforcement.sh`, `gate-enforcement.conf`,
`check-geo-attribution.sh`, `check-infra-exposure.sh:270-344`, `ci-cd.yaml:620-709`,
`DatabaseConfigurationValidator.java`, `RlsContractTest.java`, `OrderSseService.java`,
`OrderSseFanoutListener.java`, `OrderSseServiceTenantIsolationTest.java`,
`ShopAccessService.java:440-640`, `MediaProcessingWorker.java:120-200`,
`OpenApiSnapshotTest.java`, `OpenApiProdProfileGatingTest.java`, `create-backup-role.sql`,
`00-create-db.sql`, `PII-EXPOSURE-ASSESSMENT-2026-07-08.md:1-60`,
`rabbitmq-broker-upgrade.md:1-70`, plus targeted greps over `application.yml`,
`docker-compose.full-stack.yml`, `.env.example`, `secrets-template.yaml.example`,
`SecurityConfig.java`, `TenantHeaderSchemeCustomizer.java`, `IntegrationTestSupport.java`,
`CrossTenantAuthzIntegrationTest.java`, `OrderController.java`.)

**Pattern extraction date:** 2026-08-10
