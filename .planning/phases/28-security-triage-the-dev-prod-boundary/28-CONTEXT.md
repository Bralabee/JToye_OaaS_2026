# Phase 28: Security Triage + the Dev/Prod Boundary - Context

**Gathered:** 2026-08-10
**Status:** Ready for planning

<domain>
## Phase Boundary

This phase delivers **security triage + the dev/prod boundary**: the 11-finding Strix pentest
backlog (run `d8c0`, 2026-07-31) is dispositioned — sanitized public issue or dated written
acceptance each (SEC-02), with A1 re-verified against a stack **rebuilt from HEAD** and recorded
CONFIRMED/FALSIFIED with the measurement (SEC-01); no dev-only branch is reachable or advertised
under the `prod` profile, asserted by a CI gate shown to fail (SEC-03); and SEC-04's remaining
half — credential rotation + the runtime-role least-privilege split (#552). Folded same-class
issues: #283/#284 (the `auth == null` bypass class), #270 (unpinned `minio/mc` with root creds),
#281 (revoked user's open SSE stream), #488 (pre-#479 media objects: raw bytes, EXIF GPS,
spoofable Content-Type on a public bucket).

**Sequencing rationale (from ROADMAP):** this phase determines what is safe to deploy in
Phase 29, and it is cheap. Nothing structural depends on it.

**Carried decisions — decayed criteria, DO NOT re-plan (see `.planning/CRITERIA-DECAY-2026-08-08.md`):**
- **SC-4's loopback half is ALREADY SATISFIED.** All five named infra ports bind
  `${JTOYE_BIND_HOST:-127.0.0.1}`. The three app ports (edge-go 8089, frontend 3000, mcp-server
  9100) stay published deliberately — binding them changes local E2E reachability. Only the
  rotation + role half of SEC-04 remains.
- **SC-3 must be measured against the BUILT OpenAPI document, not source.**
  `OpenApiConfig.java:51` is the source template, stripped at document-build time when
  `TenantFilter` is absent (why pentest A2 is do-not-re-file from that coordinate). A source grep
  is a false red. The strip itself is **unverified** — proving it (both directions) is part of
  SC-3's work, per Proof Standard #2.
- **8 of 11 findings were already remediated** by other shipped work, measured with controls
  2026-08-05 (see `security-findings-untracked-pentest` memory). Still live: C3 (#549), E1
  (#551), B1 remainder (#552); C4 (#550) closed since. The 08-05 A1 measurement does NOT
  discharge SEC-01 — the criterion requires re-verification against a stack rebuilt from HEAD.

**Out of scope:** staging/prod deploys (Phase 29); the full-catalogue EXIF/WebP media sweep
(measured + dated plan only, executed later); binding application ports to loopback; sealed
secrets; the CUST-02 `MANUAL_REVIEW` adjudicator (still owner-deferred, remains on the decision
queue — surfaced this session, not settled).

**Sanitization rule (repo is PUBLIC):** issues and the tracked triage doc carry impact + fix +
acceptance only — never literal secret values, the DB→OAuth→header chain, or repro payloads.
`SECURITY-FINDINGS.md` stays git-excluded.

</domain>

<decisions>
## Implementation Decisions

### DB role & rotation depth (#552 / SEC-04 remainder)
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

### Media backfill (#488)
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

### Revoked SSE stream (#281)
- **D-09 — Per-emit grant re-check, not eviction, not acceptance.** Re-check the grant
  (cacheable lookup) before each event emit. Closes the delivery exposure completely — a revoked
  user receives no further events; the idle connection may linger to `SSE_TIMEOUT` but delivers
  nothing. Eviction-signal plumbing rejected as heavier than the residual justifies.
- **D-10 — Applied to ALL SSE emitters delivering tenant/shop-scoped data** (KDS + the
  order-updates stream), as one shared mechanism — same defect class, cheapest fixed together.

### Disposition & gate depth (#548, #551, #549, SC-3)
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

</decisions>

<canonical_refs>
## Canonical References

**Downstream agents MUST read these before planning or implementing.**

### Phase scope + requirements
- `.planning/ROADMAP.md` § "Phase 28: Security Triage + the Dev/Prod Boundary" — goal, the 4
  success criteria, AND the inline decay corrections (SC-4 satisfied, SC-3 wrong artifact).
- `.planning/CRITERIA-DECAY-2026-08-08.md` — the full SC-3/SC-4 dispositions with evidence.
- `.planning/REQUIREMENTS.md:111-114` — SEC-01..SEC-04 verbatim.
- `.planning/ISSUE-DISPOSITION.md` § "Phase 28" — the 9-issue table and why each is in scope.

### The findings themselves
- `SECURITY-FINDINGS.md` (repo root, **git-excluded via `.git/info/exclude` — never commit**) —
  the 11 findings A1-A3, B1-B2, C1-C4, D1, E1 with severities.
- `~/strix_runs/host-docker-internal-9090_d8c0/penetration_test_report.md` (chmod 600) — full
  evidence including literal values. Read locally only; nothing from it reaches the public repo.
- GitHub issues: #548 (tracking, sanitized disposition table), #549 (C3 staging spec), #551
  (E1 audience audit), #552 (B1 remainder: rotation + role), #283/#284 (bypass class), #270
  (minio/mc), #281 (SSE residual), #488 (media backfill). #550 (C4) already CLOSED.

### Code surfaces being changed or asserted
- `core-java/src/main/java/uk/jtoye/core/config/OpenApiConfig.java` +
  `TenantHeaderSchemeCustomizer` (shipped by #440, 3 unit tests) — the SC-3 surface; the
  customizer's javadoc names the C3 staging gap.
- `core-java` `DatabaseConfigurationValidator` — the boot-time check D-03 extends (existing
  superuser fail-fast is the pattern).
- `RlsContractTest` (+ `EXEMPT_TABLES`) — the schema-walk sweep D-13 measures and possibly
  extends; also the NOSUPERUSER downgrade harness D-04 builds on.
- `docker-compose.full-stack.yml` — `${JTOYE_BIND_HOST:-127.0.0.1}` bindings (SC-4 evidence),
  DB/Keycloak/Grafana env, the minio-bootstrap service (#270).
- `k8s/base/secrets-template.yaml.example`, `k8s/QUICK_START.md` — the secret recipe D-03
  updates (Phase 26 already corrected it once: superuser → `jtoye_app`).
- `infra/keycloak/realm-export.template.json` + `infra/keycloak/README.md` — the realm
  re-import procedure D-02/D-12 ride (`--override true`; volume drop is a no-op).
- Media pipeline (Phase 24/27): `MediaProcessingWorker` (normaliser), `MediaPendingReaper`,
  V60 quarantine columns — the machinery D-05..D-08 reuse; PR #479's routing of the three
  legacy endpoints is the forward-only fix #488 completes.
- SSE emitters: the KDS stream (#281's subject, `SSE_TIMEOUT` bound) and OrderController's
  order-updates SSE — D-09/D-10 surface.

### Gates + CI
- `.github/workflows/ci-cd.yaml` — where the new gates land (SC-3 built-spec gate, triage-doc
  completeness check, RLS-coverage extension if D-13 finds a gap).
- `scripts/check-gate-enforcement.sh` — default-deny: every new `scripts/check-*.sh` needs a
  workflow reference or a `gate-enforcement.conf` entry; the conf VOIDs on entries naming
  scripts that do not exist yet (Phase 33 lesson — do not pre-declare a sibling plan's script).
- Proof Standards (user-global `CLAUDE.md`) — every gate shown to FAIL before trusted; verify
  the delivered runtime, not the source.

### Memory (session-external, load-bearing)
- `security-findings-untracked-pentest` — the 8-of-11-remediated measurement record, the A2
  do-not-re-file warning, the confirmed-good controls worth protecting.
- `reference_keycloak_realm_reimport` — KC re-import mechanics.
- `trap_scope_gate_integrationtest_regression` — run the FULL suite when touching auth.
- `trap_rls_blinds_the_verification_query` / `trap_tenant_pin_is_under_a_global_aspect` —
  proving instruments can see rows before trusting 0-row results under RLS.

</canonical_refs>

<code_context>
## Existing Code Insights

### Reusable Assets
- **`RlsContractTest` harness** — already proves suites under a downgraded NOSUPERUSER role;
  D-04's non-owner run and D-13's coverage sweep both extend it rather than invent.
- **`DatabaseConfigurationValidator`** — existing boot-time fail-fast on superuser; D-03 adds
  the ownership check beside it.
- **IMG-04 vendor UI states** — PENDING/ACTIVE/FAILED→Re-upload/flagged review queue already
  rendered; D-07 reuses FAILED, no new UI.
- **V60 quarantine pattern** (`quarantine_expires_at`/`quarantine_reclaimed_at` + reaper
  sweep) — D-06's retain-on-a-horizon copies it.
- **Phase-24 normaliser pipeline** (magic-byte sniff, decode-verify, EXIF strip, WebP + 400px
  thumbnail) — D-05/D-08 run existing machinery over old objects; no new transform code.
- **`TenantHeaderSchemeCustomizer` + its 3 unit tests** — the SC-3/A2 mechanism and test
  precedent.
- **`.env` layer + `scripts/k8s-local-secrets.sh`** — the config-injection path all rotated
  values flow through (GLOBAL_RULE_6: no literals).

### Established Patterns
- **Every gate shown to FAIL before trusted** (Proof Standard #1 / the fifth quality
  dimension) — applies to the triage-doc completeness check, the built-spec gate, the validator
  extension, and D-13's sweep.
- **Verify the delivered artifact, not the source** (Proof Standard #2) — the entire reason
  SC-3 was re-stated; also governs D-04's live arm.
- **Sanitized-public discipline** — impact + fix + acceptance only; the repo is public.
- **By-addition exemptions with written justification** (`EXEMPT_TABLES`, V61 precedent) — any
  table the coverage sweep exempts follows the same shape.
- **Tenant-looped backfills under FORCE RLS** (`trap_rls_migration_backfill`) — the #488 subset
  re-pipeline must pin the tenant GUC per tenant or it silently touches zero rows.

### Integration Points
- New runtime role → compose env + k8s secret templates + Testcontainers harness + validator.
- Realm re-import event → carries D-02 rotation AND D-12 audience fixes in one import.
- `docs/security/PENTEST-TRIAGE.md` → new CI completeness check → `ci-cd.yaml` +
  `gate-enforcement` registration.
- #488 subset re-pipeline → media_event_outbox / MediaProcessingWorker path (respect the
  `outbox_flusher_dispatch_trap`: a new event type needs its dispatch branch in the SAME change).

</code_context>

<specifics>
## Specific Ideas

- The role split is explicitly framed as "the durable half" — the phase exists to decide what is
  safe to deploy in Phase 29, so deployed-config decisions (role, rotation, staging spec auth)
  land HERE, not there.
- One realm re-import event, two payloads: rotation (D-02) and audience fixes (D-12). Do not
  schedule them as separate imports.
- #488's own text: "the Content-Type subset is small and urgent; the EXIF/WebP sweep is large
  and can be gradual" — the phase honours exactly that split, with the gradual half recorded as
  a measured count + dated plan, never silently dropped.
- A revoked user's quiet connection leaks nothing — which is why per-emit re-check (D-09) is
  sufficient and eviction plumbing is rejected.

</specifics>

<deferred>
## Deferred Ideas

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

</deferred>

---

*Phase: 28-Security Triage + the Dev/Prod Boundary*
*Context gathered: 2026-08-10*
