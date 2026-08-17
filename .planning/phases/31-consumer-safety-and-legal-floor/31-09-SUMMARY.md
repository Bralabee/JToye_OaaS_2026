---
phase: 31-consumer-safety-and-legal-floor
plan: 09
subsystem: gdpr
tags: [gdpr, dsar, privacy, rls, multi-tenancy, scheduled-worker, system-principal]
requires:
  - "dsar_request + DsarRequest + DsarRequestRepository (V62 / 31-05)"
  - "DsarIntakeService.normaliseAddress — the digest contract (31-05)"
  - "uk.jtoye.core.security.access.SystemPrincipal (#283, Phase 28-06)"
  - "GdprService.eraseCustomerData + V42 erasure_records / _aud UPDATE policies (#84)"
  - "WebhookRetentionCleanup — the tenant-loop template (#107)"
provides:
  - "POST|GET /api/v1/public/gdpr/dsar/verify — the PENDING_VERIFICATION -> VERIFIED gate"
  - "DsarFanoutWorker — the FIRST production SystemPrincipal.asSystem caller"
  - "DsarSubjectDigest — the single shared normalisation+digest implementation"
  - "GdprService.eraseSubjectByDigest — tenant-scoped hash-keyed customer lookup"
  - "jtoye.gdpr.dsar.* config keys (fanout-interval-ms, claim-batch-size, max-process-attempts, verification-ttl-hours, verify-base-url)"
affects:
  - "31-11 (privacy notice can now describe a request path that completes, not just one that accepts)"
  - "31-13 (conformance statement cites a contact route that is actioned)"
  - "31-18 (owns the README/AGENTS/CLAUDE prose counts this plan moved)"
tech-stack:
  added: []
  patterns:
    - "tenant loop + TransactionTemplate + explicit GUC pin + per-tenant error isolation (WebhookRetentionCleanup)"
    - "single-statement FOR UPDATE SKIP LOCKED claim (media_event_outbox)"
    - "public token-verified no-auth endpoint with a JSON POST and a permanent GET companion (PublicUnsubscribeController)"
    - "declaration-over-inference background authority (SystemPrincipal, #283)"
key-files:
  created:
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarFanoutWorker.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarSubjectDigest.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarVerificationController.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarVerificationService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarVerificationMailer.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/DsarFanoutIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/DsarVerificationIntegrationTest.java
  modified:
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/GdprService.java
    - core-java/src/main/java/uk/jtoye/core/customer/CustomerRepository.java
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
decisions:
  - "The digest normalisation is SHARED CODE, not a shared rule — DsarSubjectDigest is the single implementation both sides call"
  - "The SQL-side digest was rejected on MEASUREMENT: btrim and String.trim strip different character sets"
  - "A partially-failed request is RELEASED for retry, never marked complete — completing it would silently drop a tenant's erasure"
  - "ACCESS requests are counted and logged, not executed: this plan's own T-31-09-05 forbids the only Article-15-compliant delivery"
  - "asSystem is measured DECORATIVE on today's call path; kept because #283 inverted the default and the next gated call fails closed without it"
metrics:
  duration: "~2h"
  completed: 2026-08-16
  tasks: 2
  commits: 4
---

# Phase 31 Plan 09: Cross-Tenant DSAR Fan-Out Summary

A lodged data-subject request is now executed end to end: a token proves control of the address, a
scheduled worker walks every tenant one pinned tenant at a time, and one `erasure_record` lands per
tenant that held the subject — with **no human anywhere gaining cross-tenant read**.

## What shipped

**The verification gate — closing the stub 31-05 handed forward.** 31-05 shipped the intake
deliberately inert: every row lands `PENDING_VERIFICATION` and nothing in that plan could advance
one. It rejected defaulting to `VERIFIED` because that arms an *unverified* erasure request, which
is threat T-31-05-02 itself — an irreversible action anybody on the internet can aim at anybody
else's data across every vendor, needing nothing but a guessed address.

Three new files close it. `DsarVerificationMailer` sends the readable token to **the address that
was named**, which is the entire proof: control of the mailbox is the credential.
`DsarVerificationService` advances the row in ONE conditional UPDATE (`status` must still be
`PENDING_VERIFICATION` **and** the expiry must still be in the future), so two concurrent
submissions of one token cannot both advance it and `ALREADY_VERIFIED` is read from the row's state
rather than refereed by the application. `DsarVerificationController` publishes it at
**`POST|GET /api/v1/public/gdpr/dsar/verify`** — a JSON POST as the canonical machine contract, and
a permanent GET companion because a link in an email can only be followed with a GET and a GET has
no body slot for the token (the same RFC-8058-shaped constraint `PublicUnsubscribeController`
records for one-click unsubscribe).

**`DsarFanoutWorker` — the first production `SystemPrincipal.asSystem` caller in this codebase.**
A structural clone of `WebhookRetentionCleanup`: `@Scheduled(fixedDelayString=...)`, `listTenantIds()`
native query, per-tenant `try/catch` that logs the tenant id and continues, its own
`TransactionTemplate` built in the constructor, `pinTenantGuc` doing
`SELECT set_config('app.current_tenant_id', ?, true)` inside each transaction, and
`TenantContext.clear()` in a `finally`. The differences are the outer loop over claimed requests and
the `asSystem` wrap, and both carry the comment explaining what they do and — more importantly —
what they do **not** do.

**Claiming.** One statement, the `media_event_outbox` idiom: `UPDATE ... WHERE id IN (SELECT id ...
status = 'VERIFIED' AND completed_at IS NULL AND request_type = 'ERASURE' ORDER BY received_at FOR
UPDATE SKIP LOCKED LIMIT ?) RETURNING ...`, incrementing `process_attempts` on the claim (the V60
`media_asset.process_attempts` shape — it lets a sweep tell "never attempted" from "attempted and
stalled" instead of guessing from age).

**`GdprService.eraseSubjectByDigest` — a lookup, not a second erasure routine.** The erasure itself
is `eraseCustomerData`, unchanged and unbypassed, because V42's tenant-scoped UPDATE policies on
`orders_aud`/`customers_aud` were written for exactly that routine. All the new method adds is the
step `eraseCustomerData` cannot do: it is keyed by `customerId`, and a data subject arrives as a
hash.

**Config.** `jtoye.gdpr.dsar.*` in `application.yml`, additively, in the
`webhook.delivery.retention-interval-ms` shape with inline `fixedDelayString` defaults so the worker
still runs when a key is absent — a legal-floor job must not be switched off by omission. Sweep
interval **300 000 ms (5 minutes)**: unhurried on purpose, because the statutory window is 30 days,
the queue is empty almost all the time, and each sweep walks every tenant.

## The design decision that carries the most weight

**The digest normalisation is shared CODE, not a shared rule.** The carry-forward was explicit that
the worker must reproduce `DsarIntakeService.normaliseAddress` byte-identically or "silently find
nothing and report success". A written contract is a rule two files can drift apart on, so the
implementation moved into `DsarSubjectDigest` and both sides now call it — agreement is
**structural**. `DsarIntakeService.normaliseAddress` and its private `sha256Hex` were kept as
delegating one-liners so 31-05's tests and any existing reference still compile against the same
values.

### The SQL-side digest was rejected on measurement, not taste

The obvious alternative — `encode(sha256(convert_to(lower(btrim(email)), 'UTF8')), 'hex')`, which
needs no extension and would push the match server-side — is **not the same function**, and
`DsarFanoutIntegrationTest.theSqlSideDigestIsNotEquivalentToThisOne` proves it against the real
engine rather than asserting it in prose:

| Input | Java `DsarSubjectDigest.of` | Postgres `lower(btrim(...))` |
|---|---|---|
| `Plain.Address@Example.COM` | agree (non-vacuity control) | agree |
| `\tPlain.Address@Example.COM\t` | folds to the same subject as the plain address | **differs** — `btrim` strips spaces only, `String.trim()` strips every char `<= U+0020` |

`toLowerCase(Locale.ROOT)` versus a collation-dependent `lower()` is the second divergence, and it
is why `Locale.ROOT` is written explicitly: under a Turkish default locale `"I".toLowerCase()` is
`ı` (U+0131), so an address containing a capital I would hash differently depending on an
environment variable. The cost of the Java-side match is a two-column projection per tenant; the
benefit is one authoritative definition of who a data subject is.

## Break arms — both directions, real output

Every arm ran against a **committed** tree. Every restore was verified **by content hash**
(`git hash-object`), never by `git diff --stat`. Baseline hashes: `DsarFanoutWorker.java`
`f19331b4`, `DsarSubjectDigest.java` `a7fa0f4a`, `DsarVerificationService.java` `aee2c7d4`,
`GdprService.java` `2fba670b`.

| # | Deliberate break | Clean direction | Break direction (real output) |
|---|---|---|---|
| a1 | remove `pinTenantGuc(tenantId)` | `BUILD SUCCESSFUL`, 10 tests | **10 tests, 0 failed — THE ARM DID NOT FIRE.** See below |
| a2 | remove `TenantContext.set(tenantId)` | `BUILD SUCCESSFUL`, 11 tests | 5 failed — `[tenant A held the subject] expected: 1L but was: 0L` (a ROW COUNT, not an exit code) |
| b | hoist all tenants into ONE transaction | `BUILD SUCCESSFUL`, 11 tests | 4 failed — `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only`, and `[one tenant's failure must not abort the sweep]` |
| c | remove the `asSystem` wrap (before the probe existed) | `BUILD SUCCESSFUL`, 10 tests | **1 failed — the SOURCE SCAN ONLY.** No behavioural refusal at all. See below |
| c2 | remove the `asSystem` wrap (after the probe was added) | `BUILD SUCCESSFUL`, 11 tests | 2 failed, incl. `[the per-tenant erasure must run inside SystemPrincipal.asSystem] Expecting value to be true but was false` |
| d | remove the per-tenant `try/catch` | `BUILD SUCCESSFUL`, 11 tests | 2 failed — `[one tenant's failure must not abort the sweep] Expecting code not to raise a throwable but caught ... IllegalStateException: deliberate failure` |
| e | worker hashes the address RAW (weak fixture) | `BUILD SUCCESSFUL` | **19 tests, 0 failed — THE ARM DID NOT FIRE.** See below |
| e2 | worker hashes the address RAW (fixture strengthened) | `BUILD SUCCESSFUL`, 11 tests | 1 failed — `[tenant A held the subject under a MIXED-CASE address — this is the assertion that proves the WORKER normalises, not just the intake] expected: 1L but was: 0L` |
| f | remove `finally { TenantContext.clear(); }` | `BUILD SUCCESSFUL`, 11 tests | 2 failed — `[a stale TenantContext on a returned thread is a cross-tenant read waiting to happen on an unrelated request] Expecting an empty ...` plus the structural scan |
| g | seed only ONE tenant (under-seeded fixture) | `BUILD SUCCESSFUL`, 11 tests | 1 failed — `[instrument can see tenant B] Expecting actual: 0L to be greater than: 0L` — the POSITIVE CONTROL fired, which is what distinguishes isolation from blindness |
| i | claim `PENDING_VERIFICATION` rows too (delete the gate) | `BUILD SUCCESSFUL`, 8 tests | 1 failed — `[an UNVERIFIED erasure request must not be actioned — that is T-31-05-02] expected: 0L but was: 1L` |

Closing clean arm, run **last**, after every restore and with all four baseline hashes re-verified
(`f19331b4`, `2fba670b`, `a7fa0f4a`, `aee2c7d4`):
`./gradlew :core-java:integrationTest --tests '*DsarFanout*' --tests '*DsarVerification*' --tests '*SystemPrincipalGuard*' --tests '*GdprErasure*' --tests '*RlsContractTest*' --tests '*OpenApiSnapshot*' --rerun-tasks`
→ `BUILD SUCCESSFUL in 1m 41s`; read from `build-local/test-results`: **6 classes, 39 tests, 0 failures, 0 errors**.

### Three arms that did NOT fire, and what each one actually measured

**Arm a1 — the plan's GUC criterion is unfalsifiable as written.** The plan asked to remove
`pinTenantGuc` and expected the per-tenant record count to drop to zero. It does not: **all 10 tests
still passed.** `TenantSetLocalAspect` (`TenantSetLocalAspect.java:27,43`) pins
`app.current_tenant_id` from `TenantContext` before every `@Transactional` boundary *and* before
every `Repository`/`JdbcTemplate` call, so the worker's own pin is redundant defence-in-depth while
`TenantContext.set` is present. The strictly stronger arm — remove the worker's **tenant selection**,
which is still a break at the worker's own layer and not at the global aspect — reds the erasure
count to `0L` (arm a2). Worth stating the mechanism, because it is the inverse of the usual trap:
the aspect does not merely fail to pin when `TenantContext` is empty, it actively **resets** the
GUC, so an explicit pin cannot rescue a worker that forgot to select a tenant. Both arms recorded;
`pinTenantGuc` is retained (it is what makes the class readable as tenant-scoped, and it is what
`WebhookRetentionCleanup` does).

**Arm c — `asSystem` causes no refusal today, and that is a finding the plan asked for.** With the
wrap deleted, **every behavioural test still passed**; only the source scan fired. Nothing on
`eraseCustomerData`'s path (customer, order, review, storage and `user_directory` repositories)
reaches `ShopAccessService`, so the declaration is presently **unexercised**. It is kept, and the
reason is not sentiment: #283 *inverted* the old rule under which a thread carrying no
`Authentication` was trusted by default, so an undeclared background thread is now DENIED — the
first gated call added anywhere beneath this erasure fails closed without the wrap. Rather than
report a criterion that could not fail, the claim was **raised**: `theErasureRunsInsideADeclared
SystemScope` observes `SystemPrincipal.isSystem()` from *inside* the call via a `GdprService` spy,
with a control asserting the test thread is not itself in a system scope and a `verify(...)`
asserting the observed call happened at all. Arm c2 re-ran the same break against it and it reds.

**Arm e — the fixture could not detect a divergent worker digest, and that nearly certified the
single most dangerous failure in this plan.** The first fixture lodged a mixed-case address but
seeded both customers already lower-cased, so hashing the stored value RAW produced the same digest
and the arm **passed with 19 tests green**. That fixture proved the *intake's* normalisation and
said nothing about the *worker's* — precisely the "silently matches nothing while every test stays
green" failure the carry-forward warned about. Tenant A now stores a **mixed-case** address (nothing
lower-cases `customers.email`; a vendor typing a customer in is enough), and arm e2 reds with the
erasure count at `0L`.

### The plan's `@Transactional` grep returns 2 on a CORRECT tree

The plan specified `grep -cF '@Transactional' DsarFanoutWorker.java` equal to **0**. Run literally
it returns **2** — both hits are the class javadoc explaining *why* the annotation is absent
(`DsarFanoutWorker.java:54,57`). That is a named vacuous shape in this project's standards ("a doc
rule that must name the token it forbids", and "an expected-0 that is 1 on the CORRECT tree"): the
only ways to satisfy it are to delete the warning or to misreport the count.

Replaced with a strictly stronger, permanently executable form —
`DsarFanoutIntegrationTest.theWorkerCarriesNoTransactionalAnnotationAndKeepsItsFourLoadBearing
Constructs` scans NON-COMMENT lines only, and carries two non-vacuity controls: the scan must have
read a non-empty set of code lines, and the same scan over `GdprService` (which genuinely carries
the annotation) must find it. It also asserts the four load-bearing constructs are present in
executable code, which arms c2 and f both red.

The four constructs, counted on the shipped file: `TransactionTemplate` 5, `set_config` 3,
`TenantContext.clear` 2, `asSystem` 4.

## Deviations from Plan

### Auto-added (Rule 2 — missing critical functionality)

**1. The whole verification half was built, and the plan does not list its files.**
- **Found during:** reading the carry-forward (CF-28) against `files_modified`.
- **Issue:** the plan's `files_modified` names only the worker, `GdprService`, `application.yml` and
  one test. Wire those alone and the worker is correct and permanently idle — every row sits at
  `PENDING_VERIFICATION` for ever, because nothing can advance one. The queue would fill, the
  published contact route would accept requests, and nothing would ever be actioned.
- **Fix:** `DsarVerificationMailer`, `DsarVerificationService`, `DsarVerificationController`, plus
  four lines in `DsarIntakeService` to hand the readable token to the mailer on a genuinely
  inserted row.
- **Commit:** `1e74eb2b`

**2. `DsarSubjectDigest` extracted, and `DsarIntakeService` routed through it.**
- **Issue:** the carry-forward requires byte-identical normalisation and says it must be *proven*,
  not assumed. Two implementations obeying one written rule is the shape that drifts.
- **Fix:** one implementation, both callers delegate. `normaliseAddress` and `sha256Hex` remain as
  delegating methods so nothing that referenced them changed value or signature.

**3. `SystemPrincipalGuardTest`'s intake-path scan extended by three files.**
- **Issue:** the verification endpoint is the second public request surface on the DSAR path and the
  one that decides whether a request becomes actionable — the most tempting place in the phase to
  reach for a declaration. Leaving it outside the scan would have let the guard silently stop
  covering the path it was written for.
- **Fix:** `DsarVerificationController/Service/Mailer` added to the list. The **worker is
  deliberately NOT added** — it is the background entry point that legitimately declares, and
  asserting its absence there would invert the rule.

**4. Idempotency-safe token delivery.** The mail is sent only when a row was genuinely inserted. An
`Idempotency-Key` replay returns the stored acknowledgement and sends nothing, so a retried POST
cannot quietly double the number of live credentials pointing at one request.

### Recorded scope exclusion (not a silent drop)

**5. ACCESS requests are counted, not executed — and the reason is this plan's own threat model.**
`T-31-09-05` lists "telling the subject which tenants held their data" as a threat to *mitigate*,
with the worker recording a count and never per-tenant detail. An Article 15 response cannot honour
that: UK GDPR Article 15(1)(c) obliges the controller to name the recipients, and an order history
stripped of the vendor is neither useful nor compliant. The two requirements are in direct conflict,
and resolving it means choosing a delivery channel — a one-time expiring download rather than a
mailed copy — which needs a table this plan does not own (V63 belongs to 31-10).

So the claim query filters `request_type = 'ERASURE'`, and every sweep counts outstanding VERIFIED
ACCESS rows and logs them at WARN (`event=dsar_access_requests_outstanding count=N`). The backlog is
**visible and countable** rather than rotting invisibly, which is the difference between this and the
stub class CF-28 exists to prevent. Flagged for the phase owner: **the privacy notice 31-11 writes
must not promise that ACCESS requests are actioned automatically.**

### Fixed during execution

**6. A `TransactionTemplate` + `JdbcTemplate` seed helper silently lost its tenant pin.**
- **Found during:** the first integration run — `new row violates row-level security policy for
  table "customers"`.
- **Mechanism:** under a `JpaTransactionManager` the `JdbcTemplate` may take a connection still in
  autocommit, and `set_config(..., true)` is *transaction*-local — in autocommit each statement is
  its own transaction, so the pin is reverted before the next statement runs.
- **Fix:** the test helpers go through `Session.doWork`/`doReturningWork` and run the pin and the
  statement **on the same connection** — which is the connection `TenantSetLocalAspect` and the
  worker's own `pinTenantGuc` use in production.

**7. `DsarVerificationIntegrationTest` counted every tenant's rows.**
- **Found during:** the same run — `[an UNVERIFIED erasure request must not be actioned] expected:
  0L but was: 1L`, on a test where nothing had been verified.
- **Mechanism:** that class runs as the container's bootstrap **superuser**, which bypasses even
  FORCE RLS, so an unscoped `SELECT COUNT(*) FROM erasure_records` counted a *previous test's*
  record. The GUC pin it was leaning on had no effect at all.
- **Fix:** explicit `tenant_id` predicates in that class, with the reason written into the helper.
  Proving RLS is `DsarFanoutIntegrationTest`'s job, and that class downgrades the role.

**8. A break-arm restore ate two uncommitted test improvements.** `git checkout --` on the test file
after arm g reverted it to the last commit, discarding the `asSystem` runtime probe and the
mixed-case fixture — both added *after* the GREEN commit. Detected by grepping for the two symbols
rather than by `git diff --stat` (which would have looked clean), re-applied, re-verified green, and
committed immediately (`cc48dd59`). This is the recorded "break-arm revert eats fixes" trap, and the
lesson it teaches again is: commit improvements before the next arm, not after the last one.

**9. A shared scratchpad file nearly supplied someone else's green build as this plan's evidence.**
The full suite was launched in the background writing to `scratchpad/fullsuite.log`. Some minutes
later that file contained `BUILD SUCCESSFUL in 25m 50s` and a trailing `FULL_SUITE_EXIT=0` — a
string this executor never wrote. Suspecting the instrument first: `pgrep` showed **this** run's
gradle PID still alive with three live `Gradle Test Executor` workers, and the integration results
directory still held only the two XMLs from an earlier targeted run. A concurrent session had
written to the same shared path. The real result was taken by waiting on the PID
(`until ! kill -0 <pid>`) and reading counts out of `build-local/test-results`, which is why the
figures above are class-and-test counts rather than a quoted build line. Recorded because the
failure mode is generic: **the scratchpad is shared, so a log filename is not an identity.**

## Known Stubs

| Stub | File | Why, and who resolves it |
|---|---|---|
| ACCESS requests are counted and logged, never executed | `DsarFanoutWorker.outstandingAccessRequests` | Deliberate and argued — see deviation 5. The conflict is between this plan's `T-31-09-05` and Article 15(1)(c), and the delivery channel needs a table this plan does not own. **Not silent**: every sweep logs the outstanding count at WARN. |

No other stubs. Nothing returns a hardcoded empty value, and no surface renders placeholder text.

## Threat model — dispositions

| Threat | Implemented as | Falsified by |
|---|---|---|
| T-31-09-01 `asSystem` misread as a tenancy escape | Comment at the wrap line stating what it does and does not do; the reach is the loop + the pin | arm a2 (reach is the loop), arm c (the wrap grants nothing) |
| T-31-09-02 stale `asSystem` on a pooled thread | The restoring `finally` in `SystemPrincipal` is untouched | Structurally unfalsifiable by an after-the-fact assertion (`asSystem` restores) — stated, not hidden; the in-call probe is the replacement |
| T-31-09-03 stale `TenantContext` on a returned thread | `TenantContext.clear()` in a `finally` | arm f, asserted on the EXCEPTION path |
| T-31-09-04 a repository query with no tenant predicate | `findIdAndEmailByTenantId` carries an explicit `tenant_id` predicate under FORCE RLS | arm a2 |
| T-31-09-05 telling the subject which tenants held their data | The row records a count; `complete()` writes no per-tenant detail; ACCESS is not executed | Deviation 5 |
| T-31-09-06 one tenant's failure aborting the sweep | Per-tenant `try/catch` logging the tenant id | arm d, asserted on a COMMITTED record |
| T-31-09-07 a GUC arm read as an exit code | Every GUC arm asserts a ROW COUNT | arm a2 output is `expected: 1L but was: 0L` |
| T-31-09-08 an isolation test that is blind rather than isolating | Positive control before every zero | arm g fires the control |
| T-31-05-02 an unverified erasure request | Verification gate; the claim query takes `VERIFIED` only | arm i — deleting the gate reds the refusal test at `expected: 0L but was: 1L`, and its non-vacuity control (`theFanoutActionsTheSameRequestOnceItIsVerified`) proves the worker is not simply inert |

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: new-public-endpoint | `DsarVerificationController.java` | A second anonymous public route (`/api/v1/public/gdpr/dsar/verify`). Not in the plan's register because the plan did not anticipate owning the verification half. Authorisation is the token alone; the response never varies with anything but the token; unknown/malformed/expired collapse to one answer. |
| threat_flag: outbound-email-from-anonymous-path | `DsarVerificationMailer.java` | An unauthenticated request now causes an email to a caller-supplied address. Bounded by the existing intake bucket (5/hour/client IP, in-process and not switchable off); the message is constant and reveals nothing about whether data is held. |
| threat_flag: bearer-token-in-url | `DsarVerificationController.verifyViaLink` | The emailed link carries the token in the query string, so it reaches access logs. Unavoidable — a click is a GET. Mitigated by a 168-hour expiry, single use, a config-injected base URL (never the request `Host`), and the canonical JSON POST for machine callers. |

## Cross-cutting quality contracts

| Dimension | Disposition |
|---|---|
| Security | Contracted. All eight `mitigate` dispositions implemented; six have a run break arm, one is stated unfalsifiable with a stronger replacement, one is a recorded scope exclusion. Three new threat flags raised above. |
| AI agent-readiness | Contracted. The verify endpoint is idempotent by construction (a conditional UPDATE; replay answers `already_verified` and changes nothing), errors are machine-parseable stable enums (`verified` / `already_verified` / `invalid`), it needs no credential, and the OpenAPI snapshot was regenerated from the code and matches. No MCP tool: executing a stranger's erasure is not a capability to hand an agent. |
| Falsifiable evidence | Contracted. Eleven arms run, both directions recorded, clean arm re-run last. **Three criteria were measured incapable of failing** — the plan's GUC arm, the plan's `@Transactional` grep, and this plan's own first digest fixture — and each was replaced with a stronger form rather than reported satisfied. One instrument defect caught (deviation 9: a shared log file supplying another session's green build). |
| Web performance | **N/A** — no user-facing page (31-11 owns the surface that links these routes). |
| SEO / discoverability | **N/A** — JSON API endpoints, not crawlable surfaces. |
| Accessibility | **N/A** — no UI in this plan. |
| Runtime parity | **Not applicable at this stage** — a worktree branch executed in parallel; no runtime is delivered or handed back here. `scripts/check-openapi-snapshot-fresh.sh` compares the committed contract to a service running on `localhost:9090`, which is the pre-branch build, so it FAILS by construction from here. The in-branch instrument is `OpenApiSnapshotTest`, which compares the snapshot to what the CODE emits and is green in the full suite. The phase-level rebuild-and-verify obligation stands and is unaffected. |

## Gate state handed forward

| Gate | State | Note |
|---|---|---|
| `scripts/check-no-create-extension.sh` | **rc=0** | 62 migrations scanned; this plan adds no migration |
| `scripts/check-retention-enforcement.sh` | **rc=0** | 12 rows, 6 automated, control live. This plan publishes no new retention period, so it adds no row; `verification-ttl-hours` is a consent window, not a retention period |
| `scripts/docs-freshness.sh` (tree → manifest) | **rc=0** | regenerated with `--write`; 2911 total logical invocations |
| `scripts/check-doc-metrics.sh` (prose → manifest) | **rc=1** | 5 claims in `CLAUDE.md` / `AGENTS.md` still quote `2892` / `1686` / `267`. **Plan 31-18 owns this**, and the executor protocol forbids editing prose counts from inside a worktree — the correct figure is unknowable from one branch, and three wave-1 worktrees each wrote a different wrong total |
| `scripts/check-openapi-snapshot-fresh.sh` | **rc=1** | Runtime-parity gate against the stale local stack — see the runtime-parity row above. Not an in-branch defect |
| `./gradlew :core-java:test :core-java:integrationTest` | **BUILD SUCCESSFUL, 0 failures** | full suite, `--rerun-tasks`, `6 actionable tasks: 6 executed`. Counts read from `build-local/test-results`, never from the word SUCCESSFUL: **unit 151 classes / 1136 tests** and **integration 130 classes / 596 tests**, 1 skipped, **0 failures, 0 errors**. `OpenApiSnapshotTest`, `SystemPrincipalGuardTest`, `GdprErasureIntegrationTest` and `RlsContractTest` all green |

## Commits

| Commit | Subject |
|---|---|
| `48b94108` | `test(31-09)`: failing tests for the DSAR verify gate and the cross-tenant fan-out (RED) |
| `1e74eb2b` | `feat(31-09)`: execute lodged DSARs across every tenant, with nobody holding the reach (GREEN) |
| `cc48dd59` | `test(31-09)`: strengthen two criteria that break arms proved could not fail |

## TDD Gate Compliance

Task 1 was declared `tdd="true"` and the gate sequence is present and in order: RED (`48b94108`,
recorded failing with 8 compile errors naming `DsarFanoutWorker`, `DsarVerificationMailer`,
`DsarSubjectDigest` and `GdprService.eraseSubjectByDigest`) precedes GREEN (`1e74eb2b`). No REFACTOR
commit — the implementation needed no cleanup pass, which the gate permits. `cc48dd59` is a
test-strengthening commit driven by break-arm evidence, not a fourth gate.

## Self-Check: PASSED

- All **7** created files are tracked (`git ls-files core-java/.../gdpr/` lists every one, alongside
  the pre-existing ones — so the result is about the files, not a broken listing).
- All **3** commit hashes resolve (`git log --oneline --all` matched 3 of 3).
- The five config keys exist at `application.yml:200,204,208,214,218` under `jtoye: gdpr: dsar:`.
  **Instrument note:** the first check used `grep -c 'jtoye.gdpr.dsar'` and returned **0** — a fact
  about the PATTERN, not the file, because YAML nests rather than dots. Re-checked by key name.
- `dsar/verify` appears in `docs/api/openapi-snapshot.json` (1 occurrence), and
  `OpenApiSnapshotTest` is green in the full suite, so the committed contract matches what the code
  emits.
- No pre-existing GDPR test file was modified: `git diff --name-only <base>..HEAD -- .../gdpr/`
  lists exactly the two NEW test files and nothing else.
- `STATE.md` and `ROADMAP.md` were not modified — the orchestrator owns those writes after the wave.
  The three prose metric docs (`README.md`, `CLAUDE.md`, `AGENTS.md`) were not touched either.

## Notes for the phase owner

- **31-11 (privacy notice):** the intake route now has a completing path behind it, but only for
  ERASURE. Do not write prose claiming ACCESS requests are actioned automatically — see deviation 5.
- **31-11 / 31-13:** the verification route `POST|GET /api/v1/public/gdpr/dsar/verify` is a published
  contract now; the confirmation email points at `jtoye.gdpr.dsar.verify-base-url`, which must be set
  to a real origin in any deployed environment or the link in the email points at localhost.
- **31-18:** this plan moved `java_test_methods` to 1705, `java_test_files` to 269 and
  `total_logical_invocations` to 2911 as of this worktree. Do not quote those numbers — re-run
  `scripts/docs-freshness.sh --write` on the merged tree first.
- **Deployment:** `DSAR_VERIFY_BASE_URL` and `notification.email.enabled` must both be real for the
  gate to function. With email disabled the mailer logs and no-ops, which means no token is ever
  delivered and **every request stays inert** — safe, but silent. A startup warning for that
  combination is a worthwhile follow-up and is deliberately not smuggled into this plan.
