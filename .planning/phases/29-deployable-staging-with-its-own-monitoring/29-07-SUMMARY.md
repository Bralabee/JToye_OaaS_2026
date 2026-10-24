---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 07
subsystem: infra
tags: [azure, az-cli, postgresql, flexible-server, blob-storage, worm, rbac, roles, rls, bash, docker]
status: complete

requires:
  - phase: 29-01
    provides: "scripts/lib/azure-guard.sh (az_guard_init, az_mutate, az_read, az_guard_stub), the naming contract, the decision-file conventions, NODE_VM_SIZE at scripts/azure-staging-provision.sh:145"
  - phase: 29-05
    provides: "k8s/scripts/connection-budget.conf k8s-staging row (max_connections 429 for Burstable B2s, reserve 15)"
  - phase: 36-azure-blob-storage-throughout
    provides: "docs/runbooks/azure-blob-provisioning.md (the Blob specification this plan executes)"
provides:
  - "scripts/azure-staging-datastores.sh: decision-gated Flexible Server jtoye-stg-pg (PostgreSQL 18, Burstable Standard_B2s, 32 GiB, 7-day backups), one firewall rule = the cluster outbound IP, azure.extensions UUID-OSSP, databases jtoye and keycloak, write-once resumable Key Vault object pg-admin--password"
  - "scripts/azure-staging-storage.sh: runbook §2.1-§6 (jtoyestgmedia, jtoyestgbackup, three containers, soft delete, UNLOCKED 30-day retention, lifecycle, container-scope RBAC) and --readback (§8, raw)"
  - "scripts/staging-db-bootstrap.sh: --stage pre|post|monitor|readback, --host, --test-container, --decisions, --selftest (five arms)"
  - "infra/db/managed-pre-bootstrap.sql: the pre-first-boot roles, ownership, default privileges and extension"
  - "infra/db/create-runtime-role.sql: now runnable by a non-superuser managed-server admin (NOSUPERUSER applied only to an actual superuser)"
  - "infra/dependency-horizons.yaml row aks-node-vm-sku (Standard_B2s, B-series v1 retirement 2028-11-15)"
affects: [29-09, 29-13, 29-14, 29-20, 29-23, 29-26]

actuals:
  tokens: 24995
  tasks: 3
  commits: 5
plan_head_before: 61bf11a90e68d576221e3bd50daecfb8119273f0
plan_head_after: f7999501074e9e00a1eb7af1860a459a2b995b56

tech-stack:
  added: []
  patterns:
    - "A secret crosses no argv: mode-600 file in a mode-700 temp dir removed by an EXIT trap; az gets it as --file / @FILE; psql gets it as a container env NAME (-e NAME) read by \\getenv"
    - "Write-once with resume: two existence probes made BEFORE any write choose fresh / resume / idempotent / refuse; the resume branch reuses the stored value and never writes a second version"
    - "Stand-in proof for a managed server: a postgres:18 container on a user network (TCP + SCRAM, never loopback trust) with a CREATEROLE non-superuser playing the admin"
    - "Runbook section numbers as executable banners (STEP runbook §n: ...), so the script and its specification read side by side"

key-files:
  created:
    - scripts/azure-staging-datastores.sh
    - scripts/azure-staging-storage.sh
    - scripts/staging-db-bootstrap.sh
    - infra/db/managed-pre-bootstrap.sql
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-07-t3-red-pre-idempotent.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-07-t3-red-no-password-in-argv.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-07-t3-red-post-nosuperuser.json
  modified:
    - infra/db/create-runtime-role.sql
    - infra/dependency-horizons.yaml
    - .planning/WINDOWS.md

key-decisions:
  - "create-runtime-role.sql applies NOSUPERUSER only when jtoye_runtime actually is a superuser: PostgreSQL 16+ refuses any ALTER ROLE naming SUPERUSER from a non-superuser, so the unconditional line made the file unrunnable as a managed-server admin (measured); superuser behaviour is unchanged"
  - "--zonal-resiliency Disabled replaces the plan's --high-availability Disabled: the pinned azure-cli 2.91.0 has no --high-availability argument (read from its _params.py)"
  - "Real-mode psql verifies the server certificate against the HOST CA bundle mounted read-only into postgres:18, which ships none; sslrootcert=system then resolves through /usr/lib/ssl/cert.pem"
  - "The admin keeps its self-granted membership in jtoye_app and keycloak (a re-run's ALTER statements need it)"
  - "Pre-stage role attributes are given at CREATE ROLE and ASSERTED afterwards, never re-applied by ALTER ROLE, for the same SUPERUSER-attribute reason"

requirements-completed: [DPLY-01, DPLY-04]

duration: 20min
completed: 2026-10-18
---

# Phase 29 Plan 07: Datastore Layer Scripts Summary

**Guarded, decision-gated scripts for the managed PostgreSQL 18 server and the two Blob accounts, plus a role bootstrap proven against a postgres:18 stand-in admin. The admin password reaches az only as a file reference. The stand-in exposed one more defect: `create-runtime-role.sql` could not run as a non-superuser admin, and it is now fixed at the source.**

## Performance

- **Duration:** ~20 min (2026-10-18T23:12:03Z to 23:32Z)
- **Tasks:** 3 of 3
- **Files:** 4 created, 3 modified, 3 evidence records

## Accomplishments

- `scripts/azure-staging-datastores.sh` creates `jtoye-stg-pg`: PostgreSQL 18, Burstable `Standard_B2s` (consistent with the 429 / 15 budget row), 32 GiB, 7-day backups, `--public-access <outbound IP>`. It reads back exactly one firewall rule, sets the `azure.extensions` allowlist (`UUID-OSSP`, cross-checked against the migrations) and creates the `jtoye` and `keycloak` databases. The admin password lives only in a mode-600 file, and the run is resumable after a failed create.
- `scripts/azure-staging-storage.sh` implements runbook §2.1, §2.2, §3, §4, §5 (read only) and §6 in order, with `--readback` for §8. No line of it can lock the retention policy.
- `scripts/staging-db-bootstrap.sh` and `infra/db/managed-pre-bootstrap.sql` encode Blocker E's order: `pre` before the first boot, `post` after the first migration. All five behaviour arms PASS against a real postgres:18 stand-in.
- `aks-node-vm-sku` horizon row, with the retirement date checked on Microsoft Learn.

## Task Commits

1. **Task 1: Managed Postgres script + horizon row**: `5cdf761e` (feat), plus `f7999501` (fix: the create line spells `--version 18` as a literal, the plan's artifact contract)
2. **Task 2: Blob estate script**: `e99145aa` (feat)
3. **Task 3 (TDD): DB role bootstrap**: RED `a6b2a60b` (test), GREEN `4d86fef2` (feat)

## Evidence (both directions recorded)

### Task 1

| Run | Result |
|---|---|
| No decision file (verify 1) | `rc=1 refused_lines=1 stub_calls=0` |
| Branch (a), fresh estate (verify 2) | `rc=0 create_lines=1 missing_flags=0 pw_by_file=1 pw_literal=0 kv_set_by_file=1 kv_value_args=0 password_like_runs=0 calls=9 pinned=9` |
| Branch (b), resume (verify 3) | `rc=0 kv_set_lines=0 create_by_file=1 secret_leaks=0`, and stdout shows `STEP 3b: RESUME` |
| Branch (c), both exist | `rc=0 kv_set_lines=0 create_lines=0 param_set=1 db_create=2` (`STEP 3c`) |
| Branch (d), server without secret | `rc=1 refused=1 kv_set_lines=0 create_lines=0 calls=3`: `REFUSED [pg-admin-password]: server jtoye-stg-pg exists but Key Vault jtoye-stg-kv holds no pg-admin--password ... az postgres flexible-server update ... --admin-password @FILE ... This script never does it.` |
| Pin + horizons (verify 4) | `pin_gate_rc=0 pin_summary_ok=1 horizons_rc=0 missing_row_zero=1 pin_not_at_site_zero=1 site_unresolvable_zero=1 sku_rows=1` |

Branch (a) argv, the create line verbatim (path randomised per run):
```
postgres flexible-server create -g jtoye-rg -n jtoye-stg-pg -l uksouth --version 18 --tier Burstable --sku-name Standard_B2s --storage-size 32 --backup-retention 7 --zonal-resiliency Disabled --admin-user jtoyepgadmin --admin-password @/tmp/jtoye-pgadmin.tMW9upfZ/pg-admin-password --public-access stub-id --yes --output none --subscription c483d353-5f61-4587-a790-addb9ab5fb94
keyvault secret set --vault-name jtoye-stg-kv -n pg-admin--password --file /tmp/jtoye-pgadmin.tMW9upfZ/pg-admin-password --output none --subscription c483d353-5f61-4587-a790-addb9ab5fb94
```
(`stub-id` is the stub's canned answer to the outbound-IP read. Live, the script asserts the value is IPv4.) The temp dir is gone after each run (`ls -d /tmp/jtoye-pgadmin.*`: no such file).

**Break arms** (scratch copies; the real tree was untouched):

| Break | Measured |
|---|---|
| Key Vault write by `--value "$(cat file)"` | `kv_set_by_file=0 kv_value_args=1 password_like_runs=2` |
| `--admin-password "$(cat file)"` | `pw_by_file=0 pw_literal=1 password_like_runs=2` |
| Unmodified copy (control) | `pw_by_file=1 pw_literal=0 kv_set_by_file=1 kv_value_args=0 password_like_runs=0` |
| Resume branch prints the file | `secret_leaks=1` |
| Resume branch writes the secret again | `kv_set_lines=1` |
| Horizon site `:144` instead of `:145` | `NOTE aks-node-vm-sku: pin not at ...:144; found at line(s) 145`, `pin-not-at-site=1` |
| Horizon pin `Standard_B2ms` | `site-unresolvable=1`, rc 2 (VOID) |

**The `@FILE` expansion, cited.** azure-cli 2.91.0, `/opt/az/lib/python3.14/site-packages/azure/cli/core/commands/__init__.py`, `_expand_file_prefixed_files` (line 77). It strips a trailing newline (`content.rstrip(os.linesep)`, line 84). On `OSError` it **falls back to the literal argument** (lines 97-98, "Failed to load '%s', assume not a file"). So the script asserts the file is present, non-empty and mode 600 immediately before the create. The live proof that the server holds the vault's value is plan 29-09's first `--stage pre` login (WINDOWS #47).

**B-series retirement date.** It was checked on 2026-10-19 at https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/lifecycle/retirements-and-capacity-restrictions (the old `.../sizes/retirement/retired-sizes-list` URL redirects there). Row `B-series (V1)`: Announced, 10/15/25, retirement **11/15/28**. The row records `vendor_eol: 2028-11-15` plus a dated `manual_review`, because endoflife.date has no slug for this, so the gate prints it as UNKNOWN.

### Task 2

| Run | Result |
|---|---|
| No decision file (verify 1) | `rc=1 refused_lines=1 stub_calls=0` |
| Dry-run, `APPROVED: option-a` (verify 2) | `rc=0 missing_steps=0 account_creates=2 container_creates=3 unlocked_notice=1 calls=18 pinned=18` |
| Lock structurally absent (verify 3) | `lock_calls=0 runbook_control=1` (the runbook positive control matches the human §4.4 command once) |
| `--readback --dry-run` | `rc=0 calls=16 pinned=16 mutations=0`, every §8 label printed, `readback reads_failed=0` |
| Banners in the source (`rstep <n>` calls) | §2.1 1, §2.2 1, §3 1, §4 1, §5 1, §6 1, §8 1 |

**Break arms:**

| Break | Measured |
|---|---|
| Planted `az_mutate storage container immutability-policy lock …` line | `lock_calls=1` |
| §6 banner removed | `missing_steps=1` |
| UNLOCKED notice removed | `unlocked_notice=0` |
| Backup container create skipped | `container_creates=2` |

### Task 3 (TDD)

**RED** (`a6b2a60b`, skeleton SQL without the self-membership grants, runner passing passwords as `psql -v`). `BOOTSTRAP_SELFTEST_FORMAT=tap bash scripts/staging-db-bootstrap.sh --selftest`, rc=1:
```
not ok 1 - pre-idempotent           pre rc=3/3: psql:/sql/db/managed-pre-bootstrap.sql:61: ERROR:  must be able to SET ROLE "jtoye_app"
not ok 2 - owners-can-create        pre stage did not complete, so neither owner exists to create anything
not ok 3 - post-before-migration-fails  rc=3 for the wrong reason: psql:/sql/db/create-runtime-role.sql:61: ERROR:  permission denied to alter role
not ok 4 - post-readback            pre stage did not complete, so jtoye_app cannot run the simulated migration
not ok 5 - no-password-in-argv      3 distinct password(s) visible in docker inspect Path/Args/Cmd of 3 psql container(s)
```
- **Classifier:** `gsd_run check tdd-red-evidence` returned `RED_EVIDENCE_OK` (`target_test_failed`, TAP, 5 tests, 5 failing) for pre-idempotent, no-password-in-argv and post-before-migration-fails. Records: `evidence/29-07-t3-red-{pre-idempotent,no-password-in-argv,post-nosuperuser}.json`.
- **Semantic assessment:** pre-idempotent failed on the planned SET ROLE refusal, at the ownership hand-over. no-password-in-argv inspected a non-zero number of containers and found the `-v` passwords. post-before-migration-fails ran and failed its reason assertion for a reason the plan did not anticipate. That reason is the deviation below; it is not a harness fault.

**Intermediate run** (green SQL and runner, original `create-runtime-role.sql`): pre-idempotent, owners-can-create and no-password-in-argv PASS. post-before-migration-fails and post-readback FAIL on `create-runtime-role.sql:61: ERROR: permission denied to alter role`. This isolates the defect to that one line.

**GREEN** (`4d86fef2`). The plan's verify line:
```
PASS pre-idempotent rc=0/0 diff_lines=0 control_diff_lines=1 restored_diff_lines=0 runtime_defaults=2
PASS owners-can-create keycloak@keycloak rc=0 jtoye_app@jtoye rc=0
PASS post-before-migration-fails rc=3 reason=relation-postcode_centroid-missing
PASS post-readback rc=0 jtoye_app=f jtoye_backup=t jtoye_runtime=f runtime_and_backup_read=ok
PASS no-password-in-argv psql_containers=5 password_hits=0
rc=0
missing=0 fail_lines=0
```
- **Idempotency AC.** The diff of the two `pre` captures prints nothing. The captures cover the role list, memberships, database and schema owners and ACLs, extensions, and `\ddp` for jtoye plus the keycloak schema. Control: a planted `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app ... GRANT SELECT ON TABLES TO keycloak` makes the diff `> keycloak=r/jtoye_app` (1 line). Revoking it restores the capture (0 lines): clean, then the arm, then clean again.
- **Readback output** (stand-in, raw): `jtoye_app|f|f|t`, `jtoye_backup|f|t|t`, `jtoye_runtime|f|f|t`, `keycloak|f|f|t`. Database owners: `jtoye|fake_admin`, `keycloak|keycloak`. Schema public owners: `jtoye|public|jtoye_app` and `keycloak|public|keycloak`. `\ddp`: `jtoye_app|public|table|jtoye_runtime=arwd/jtoye_app` plus `jtoye_backup=r/jtoye_app`, and the sequence equivalents. `max_connections 100` (the stand-in's default, not Azure's).
- **The admin's memberships**, recorded as the plan asks: `jtoye_app` and `keycloak`, each granted by fake_admin to itself with `admin_option=f inherit_option=t set_option=t`. These sit beside the implicit CREATEROLE grants (`admin_option=t inherit_option=f set_option=f`).
- **`\getenv` AC:** `command grep -c '\\getenv' infra/db/managed-pre-bootstrap.sql` = 4 (3 statements, plus 1 header comment that names the mechanism).
- **VOID direction:** with docker removed from PATH, `--selftest` gives `rc=2 VOID: docker not on PATH: the stand-in cannot run, which is not a pass`. The selftest leaves 0 containers, 0 labelled psql containers and 0 networks behind (counted after each run).
- **Pin gate (verify 2):** `rc=0 summary_ok=1`, `SUMMARY discovered=8 wrapper_calls=42 exempt=1 findings=0`.
- **Real-mode refusals:** with `JTOYE_AZURE_SUBSCRIPTION_ID` unset, the run gives `REFUSED [subscription]` rc 1 and `az_calls=0`. With a stub answering "not found" for a secret, it gave `REFUSED [secret]: could not read Key Vault object jtoye-stg-kv/pg-admin--password (az rc=3)` (this was measured before the live-mode stub refusal was added). Real mode now refuses the az_guard_stub with a VOID.
- **`--stage monitor`**, run by hand on a stand-in (it is not a selftest arm). Without ADMIN OPTION on pg_monitor, the admin fails at `CREATE ROLE jtoye_monitor ... IN ROLE pg_monitor`, rc 3. With `GRANT pg_monitor TO fake_admin WITH ADMIN OPTION`, rc 0, the re-run is rc 0, and the membership reads `pg_monitor|jtoye_monitor`. With the password variable unset, `REFUSED [env]` rc 1.
- **CA bundle proof** (inside postgres:18, `openssl s_client -verify_return_error` to learn.microsoft.com:443). Without the mount: `Verify return code: 20 (unable to get local issuer certificate)`. With the host bundle mounted read-only at `/etc/ssl/certs/ca-certificates.crt`: `Verify return code: 0 (ok)`. The host bundle carries `DigiCert Global Root G2` and `Microsoft RSA Root Certificate Authority 2017`.

## Decisions Made

See `key-decisions` in the frontmatter. The first two are deviations, recorded below.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] `infra/db/create-runtime-role.sql` could not run as a managed-server admin**
- **Found during:** Task 3 RED (arm post-before-migration-fails failed for the wrong reason)
- **Issue:** Line 61, `ALTER ROLE jtoye_runtime WITH LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD …`, is refused for any non-superuser on PostgreSQL 16+ ("Only roles with the SUPERUSER attribute may change the SUPERUSER attribute"). This was measured on postgres:18 twice: once with a one-off probe, and once by the selftest. The plan's "re-run create-runtime-role.sql unchanged as the server admin" could therefore never reach the file's grants on Azure, whose admin is not a superuser.
- **Fix:** `NOSUPERUSER` is now applied only when `pg_roles.rolsuper` says the role is a superuser (`\gset` + `\if`), and `LOGIN NOBYPASSRLS PASSWORD` is applied unconditionally. Only a superuser can have made the role a superuser, so the correction a superuser gets is unchanged. Measured as superuser: run 1 rc 0; then, with the role made `SUPERUSER BYPASSRLS`, run 2 rc 0 and the role reads `f|f` again. A non-superuser facing a superuser runtime role fails loudly (`permission denied to alter role`). The Java contract tests that execute this exact file still pass: `RuntimeRoleGrantContractTest` (5) and `PostcodeTruncateGrantMigrationTest` (3) ran with `--rerun-tasks`, 8 tests, 0 failures, 0 skipped, with reports timestamped 2026-10-18T23:29Z. The post stage still runs the file unchanged in the sense the plan meant: one source, run through `\ir`, never a fork.
- **Files modified:** infra/db/create-runtime-role.sql
- **Commit:** 4d86fef2

**2. [Rule 1 - Bug] `--high-availability Disabled` is not an argument of the pinned CLI**
- **Found during:** Task 1 (checking flags against azure-cli 2.91.0)
- **Issue:** `az postgres flexible-server create` in 2.91.0 registers `--zonal-resiliency` (default Disabled; `_params.py` lines 254-257 and 432) and has no `--high-availability` argument. The plan's spelling would fail the live create.
- **Fix:** `--zonal-resiliency Disabled`, with a comment. None of the six flags the verify checks is affected.
- **Commit:** 5cdf761e

**3. [Rule 3 - Blocking] The horizons verify required `pin-not-at-site=0`, but HEAD read 2 before this plan touched the file**
- **Issue:** The `go-ci-setup` sites `.github/workflows/ci-cd.yaml:939` and `:1336` had drifted to 998 and 1412 after earlier ci-cd.yaml edits.
- **Fix:** Refreshed the two line numbers by hand in the file this task edits. `pin-not-at-site=0` after.
- **Commit:** 5cdf761e

**4. [Rule 2 - Missing critical] Additions the plan did not name**
- The datastores script validates PG_ADMIN_LOGIN against Azure's rules (reserved names, `pg_` prefix) before anything is written. The generated password is retried until it contains upper, lower and digit (Azure's three-of-four rule). The pinned CLI's literal fallback is guarded (file present and mode 600 before the create). An empty firewall list on an existing server gets its one rule created, and any other rule set refuses.
- The bootstrap runs in real mode with `sslmode=verify-full sslrootcert=system` and mounts the host CA bundle, because postgres:18 ships no bundle (`/usr/lib/ssl/cert.pem` dangles). It also refuses the az test double in real mode.
- The selftest harness got two fixes during RED. Its EXIT trap referenced function locals that no longer existed (rc unchanged, but containers were left behind). The `\ddp` and readback parsers were tightened so they count only the rows they mean.

**Total deviations:** 4 (2 bugs, 1 blocking, 1 group of correctness additions). **Impact:** no scope creep. The create-runtime-role.sql fix is the one change outside `files_modified`. It is required for the plan's own must-have, and it is covered by the existing Java contract tests.

## TDD Gate Compliance

Task 3 (`tdd="true"`):
- RED `a6b2a60b` (`test(29-07)`) precedes GREEN `4d86fef2` (`feat(29-07)`).
- `RED_EVIDENCE_OK` for pre-idempotent, no-password-in-argv and post-before-migration-fails, with the semantic assessment above.
- No REFACTOR commit.

Tasks 1 and 2 are `type="auto"`. Their checks were shown failing by break arms.

## Issues Encountered

- **Caveat for A18 (required by the plan).** fake_admin is not azure_pg_admin. It is a CREATEROLE CREATEDB BYPASSRLS non-superuser that owns schema public directly, whereas on Azure the admin is a member of the role that owns it. A18 therefore stays an assumption until plan 29-14's live readback. The same holds for: the admin pre-creating uuid-ossp; the admin creating a BYPASSRLS jtoye_backup (D-10); and `--stage monitor` needing ADMIN OPTION on pg_monitor (plan 29-23). Recorded as WINDOWS #46.
- The post stage, run before the migration, partially applies `create-runtime-role.sql` (everything above the TRUNCATE grant) before it fails. That is harmless, because the file is idempotent and the later run completes it.
- `ALTER ROLE … PASSWORD` sends the password in the SQL text, as every existing role file already does. It is not on any argv, but a server with `log_statement=ddl|all` would log it. Azure's default is `none`.

## Known Stubs

None. `stub-id` and `STUB-SECRET-` are the dry-run test double's answers, not product content.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: credential-env | scripts/staging-db-bootstrap.sh | Role passwords reach psql through `docker run -e NAME`. They are absent from argv (proven), but present in the short-lived `--rm` container's `Config.Env`, which `docker inspect` shows to members of the docker group while it runs |
| threat_flag: role-attribute-correction | infra/db/create-runtime-role.sql | The NOSUPERUSER correction now runs only when the role is a superuser. Behaviour is unchanged for a superuser runner. A non-superuser runner facing a superuser jtoye_runtime fails loudly instead of being silently unable to correct it |

## User Setup Required

None in this plan. Plan 29-09 runs the two Azure scripts behind its checkpoint. Plans 29-14 and 29-23 run the bootstrap against the live server.

## Next Phase Readiness

- Plan 29-09 runs `azure-staging-datastores.sh`, then `azure-staging-storage.sh` and `azure-staging-storage.sh --readback`, all against its decision file (keys PG_ADMIN_LOGIN and PG_PUBLIC_ACCESS=public). Each refuses without that file.
- Plan 29-14 runs `staging-db-bootstrap.sh --stage pre`, deploys, then runs `--stage post` and `--stage readback`. `readback` prints `SHOW max_connections`, which feeds the k8s-staging budget row. Its scratch-database arm needs a helper this script does not have yet (create/drop a database as the admin). Add it there.
- Plan 29-26's runbook page should record T-29-25: uuid-ossp is created by the admin, outside Flyway.
- The Burstable `Standard_B2s` PostgreSQL compute is a separate managed SKU from the B-series v1 VMs being retired, so the `aks-node-vm-sku` row does not cover it. Whether Flexible Server B-series compute follows the same retirement was not checked.

## Self-Check: PASSED

- FOUND: scripts/azure-staging-datastores.sh, scripts/azure-staging-storage.sh, scripts/staging-db-bootstrap.sh, infra/db/managed-pre-bootstrap.sql, infra/db/create-runtime-role.sql, infra/dependency-horizons.yaml, and the three evidence records
- FOUND commits: 5cdf761e, e99145aa, a6b2a60b, 4d86fef2, f7999501 (each an ancestor of HEAD)
- Artifact `contains` checks: `--version 18` 1, `jtoyestgbackup` 2, `postgres:18` 5, `ALTER DEFAULT PRIVILEGES FOR ROLE jtoye_app` 2, key link `create-backup-role.sql` 2
