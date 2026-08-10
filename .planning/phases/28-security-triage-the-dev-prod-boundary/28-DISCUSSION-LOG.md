# Phase 28: Security Triage + the Dev/Prod Boundary - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-10
**Phase:** 28-security-triage-the-dev-prod-boundary
**Areas discussed:** DB role & rotation depth (#552), Media backfill (#488), Revoked SSE stream (#281), Disposition & gate depth (#548+)

---

## DB role & rotation depth (#552)

| Option | Description | Selected |
|--------|-------------|----------|
| Split roles now | Non-owner runtime role; Flyway keeps `jtoye_app` as owner/migrator; app gets DML-only grants | ✓ |
| Prove FORCE + accept owner | CI sweep asserting FORCE everywhere + dated acceptance; role split defers to 29 | |
| Defer both to Phase 29 | Rotation only this phase | |

**User's choice:** Split roles now (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| All six + runbook | Full #552 credential set + `docs/runbooks/credential-rotation.md` reusable for Phase 29 | ✓ |
| All six, no runbook | Same rotation, procedure only in SUMMARY | |
| DB + Grafana only | Defer the four Keycloak client secrets | |

**User's choice:** All six + runbook (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| All surfaces + validator | Compose + k8s templates + `DatabaseConfigurationValidator` fails fast on an owning runtime role | ✓ |
| All surfaces, no validator | Fix configs and prove with tests only | |
| Compose only this phase | k8s templates defer to Phase 29 | |

**User's choice:** All surfaces + validator (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Permanent test + live arm | Testcontainers harness runs isolation suite as non-owner role + one-off live-stack measurement at close-out | ✓ |
| Permanent test only | Skip the delivered-runtime half | |
| Live measurement only | No permanent guard | |

**User's choice:** Permanent test + live arm (recommended option).

---

## Media backfill (#488)

| Option | Description | Selected |
|--------|-------------|----------|
| Measure + urgent subset | Enumerate first; re-pipeline only bad-Content-Type objects now; EXIF/WebP sweep gets measured count + dated plan | ✓ |
| Full backfill now | Re-pipeline every pre-#479 object this phase | |
| Measure only | Read-only counts; all rewriting deferred | |

**User's choice:** Measure + urgent subset (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Retain on a horizon | Originals to non-public quarantine prefix with declared expiry (V60 pattern), then reap | ✓ |
| Rewrite in place | Originals gone immediately | |
| Keep originals indefinitely | No expiry — accumulates the raw-bytes liability elsewhere | |

**User's choice:** Retain on a horizon (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Pull + FAILED + re-upload | Remove from public origin, mark FAILED with reason; IMG-04 UI gives the vendor the fix path | ✓ |
| Flag for review queue | Suspicious object stays publicly served until reviewed | |
| Pull silently | No vendor surface — regression by omission | |

**User's choice:** Pull + FAILED + re-upload (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Standard derivative path | WebP derivative + thumbnail like a fresh upload; references update; one pipeline contract | ✓ |
| Same-key rewrite | URLs never change; legacy objects stay outside the derivative model | |
| You decide | Planner picks after dual-read mapping; no-404 invariant either way | |

**User's choice:** Standard derivative path (recommended option).

---

## Revoked SSE stream (#281)

| Option | Description | Selected |
|--------|-------------|----------|
| Per-emit re-check | Grant re-checked (cacheable) before each emit; revoked user receives nothing further | ✓ |
| Accept the 5-min bound | Dated formal acceptance, zero code | |
| Eviction signal | Emitter registry + revocation hook; heaviest | |

**User's choice:** Per-emit re-check (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| All SSE emitters | One shared mechanism wherever SSE delivers tenant/shop-scoped data (KDS + order updates) | ✓ |
| KDS only (#281 as filed) | Others get look-and-record | |

**User's choice:** All SSE emitters (recommended option).

---

## Disposition & gate depth (#548+)

| Option | Description | Selected |
|--------|-------------|----------|
| Tracked sanitized doc | `docs/security/PENTEST-TRIAGE.md`, one line per finding ID; CI asserts all 11 present | ✓ |
| Issue comments only | Record invisible to repo-level review | |
| Git-excluded addendum | Private but invisible and unprotected by history | |

**User's choice:** Tracked sanitized doc (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Audit + fix in same import | Enumerate audience mappers; trivial fixes ride the rotation's realm re-import; non-trivial → sanitized issues | ✓ |
| Audit only | All fixes become follow-ups | |
| You decide | Planner draws the line | |

**User's choice:** Audit + fix in same import (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Verify + close gap here | Measure what RlsContractTest's schema-walk asserts; extend if missing/partial; else record satisfied with fail-direction run | ✓ |
| Verify + record only | Gap becomes a later-phase issue | |

**User's choice:** Verify + close gap here (recommended option).

| Option | Description | Selected |
|--------|-------------|----------|
| Fix now, config-level | staging/prod profiles require auth for (or disable) OpenAPI endpoints; profile-parameterised both-direction test | ✓ |
| Record for Phase 29 | Becomes a staging-deploy acceptance criterion | |

**User's choice:** Fix now, config-level (recommended option).

---

## Claude's Discretion

- SC-3 built-spec gate mechanics (how CI reads the BUILT OpenAPI document).
- #283/#284 `asSystem()` implementation per the fix shape in the issues; full integration suite run.
- #270 minio/mc digest pin + scoped bootstrap credentials.
- A1 re-verification arm design (reuse the 2026-08-05 break-arm precedent).
- Quarantine horizon length, grant-cache TTL, runtime-role name and grant set.

## Deferred Ideas

- Full-catalogue EXIF/WebP media sweep (measured + dated plan this phase; execution later).
- CUST-02 `MANUAL_REVIEW` adjudicator — offered this session, not settled; stays on the owner's decision queue.
- Binding application ports (8089/3000/9100) to loopback — decision-not-a-tidy-up, not taken up.
- Eviction-signal SSE plumbing — relevant again at multi-replica fan-out.
