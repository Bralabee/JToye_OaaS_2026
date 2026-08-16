---
phase: 31-consumer-safety-and-legal-floor
plan: 05
subsystem: gdpr
tags: [gdpr, dsar, privacy, rls, idempotency, rate-limiting, public-api]
requires:
  - "uk.jtoye.core.exception.IdempotencyConflictException / IdempotencyPayloadMismatchException (V50 / #204)"
  - "uk.jtoye.core.security.ClientIpResolver (#88)"
  - "uk.jtoye.core.security.access.SystemPrincipal (#283, Phase 28-06)"
  - "RlsContractTest.EXEMPT_TABLES by-addition precedent (V61 / postcode_centroid)"
provides:
  - "POST /api/v1/public/gdpr/dsar — anonymous, cross-tenant DSAR intake returning an opaque 202"
  - "dsar_request table (V62) — platform-level intake queue, digest-keyed, not tenant-scoped"
  - "DsarRequest entity + DsarRequestRepository (oldest-first claim query for 31-09)"
  - "DsarIntakeService.normaliseAddress — the digest normalisation contract 31-09 must reproduce"
  - "https://jtoye.uk/errors/dsar-rate-limited — typed 429 with retryAfterSeconds"
affects:
  - "31-09 (fan-out worker consumes dsar_request; must match the digest contract)"
  - "31-11 (privacy notice links the published intake route)"
  - "31-13 (conformance statement cites a real contact route)"
  - "31-18 (owns the README/AGENTS/CLAUDE prose that quotes schema_version and test counts)"
tech-stack:
  added: []
  patterns:
    - "reserve-first INSERT ... ON CONFLICT DO NOTHING (IdempotencyService / OrderStateChangeListener)"
    - "by-addition RlsContractTest.EXEMPT_TABLES exemption with a written justification (V61)"
    - "digest-only subject identifier (V42 erasure_records rule)"
    - "public unauthenticated mutating endpoint (PublicStorefrontController)"
key-files:
  created:
    - core-java/src/main/resources/db/migration/V62__dsar_request.sql
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarRequest.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarRequestRepository.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeController.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeRateLimiter.java
    - core-java/src/main/java/uk/jtoye/core/gdpr/dto/DsarIntakeRequest.java
    - core-java/src/main/java/uk/jtoye/core/exception/DsarRateLimitExceededException.java
    - core-java/src/test/java/uk/jtoye/core/gdpr/DsarIntakeIntegrationTest.java
  modified:
    - core-java/src/test/java/uk/jtoye/core/security/RlsContractTest.java
    - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java
    - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
    - core-java/src/main/java/uk/jtoye/core/security/ClientIpResolver.java
    - docs/api/openapi-snapshot.json
    - docs/metrics.json
decisions:
  - "Idempotency lives on dsar_request, not the shared store — IdempotencyService cannot serve a tenant-less caller (measured)"
  - "The unique constraint is on idempotency_key ALONE, strictly stronger than the planned composite"
  - "The DSAR bucket is in-process, not behind the shared Redis proxy manager, so it cannot be switched off or fail open"
  - "dsar_request is exempt from RLS by addition, and the exemption's premise is now itself asserted"
metrics:
  duration: "~2h"
  completed: 2026-08-16
  tasks: 2
  commits: 5
---

# Phase 31 Plan 05: Data-Subject-Request Intake Summary

A published single point of contact for UK-GDPR requests now has something real behind it: an
anonymous, cross-tenant `POST /api/v1/public/gdpr/dsar` that answers an opaque, byte-identical 202
whether or not any tenant holds the address, stores the subject only as a SHA-256 digest, is
idempotent under a repeated `Idempotency-Key`, is bounded by its own per-client bucket, and never
declares system authority on the request thread.

## What shipped

**V62 `dsar_request`** — a platform-level intake queue. Columns for the subject digest, request
type, a claim/complete lifecycle, a verification token digest and expiry, idempotency bookkeeping,
attempt counting and timestamps. A partial unique index on `idempotency_key`, and a partial index
on outstanding rows for the worker's oldest-first claim. No `_aud` mirror: `erasure_records` is
already the Article-17 proof row, and mirroring an operational queue would create a second
long-lived store keyed by a data subject.

**The RLS decision, argued in three places.** The table is deliberately not tenant-scoped: an
anonymous subject lodges before any tenant is known, and the request exists to be actioned across
all of them. The argument that matters is not "there is no tenant to scope by" but "scoping it
would be worse" — with no `tenant_id` there is no predicate to write, so a FORCE'd policy would
return zero rows to the very worker that must read them, and the DSAR path would be silently dead
while the intake kept returning 202 and every test stayed green. That reasoning is written into
the migration, into `EXEMPT_TABLES` **by addition**, and into the entity javadoc. The schema-walk
assertion is untouched.

**The public intake.** `uk.jtoye.core.gdpr` receives `WebConfig`'s invisible version prefix, so
`@RequestMapping("/public/gdpr")` is served at **`/api/v1/public/gdpr/dsar`** — anonymous via the
existing `/api/v1/public/**` allowance, and already inside the platform limiter's tenant-less
public tier. Request body is `{ "email": string, "requestType": "ACCESS" | "ERASURE" }`; the
response is a constant `{ "status": "received", "detail": "...", "acknowledgementWindowDays": 30 }`.
**31-11 and 31-13 can link this route.**

**`GdprController` is untouched.** Verified two ways: `git diff --name-only` against the plan base
is empty for that file, and the same instrument reports six changed files in the same package — so
the empty result is about the file, not about a broken diff.

## The measurement that decided the idempotency design

The plan asked for this to be measured rather than assumed, and the measurement is decisive.
`IdempotencyService.execute` opens at line 111 with:

```java
UUID tenantId = TenantContext.get()
        .orElseThrow(() -> new MissingTenantContextException(...));
```

and its store `idempotency_keys` (V50) is keyed `(tenant_id, endpoint, idempotency_key)` under
`FORCE ROW LEVEL SECURITY`. An anonymous caller has no tenant, so the shared service cannot serve
this endpoint at all — the request would become a **500** through
`GlobalExceptionHandler.handleMissingTenantContext` before reaching any storage. Weakening it to
accept a null tenant would put a FORCE-RLS store into the state where its own policy predicate
cannot match, which is the same silently-dead-table failure the RLS decision above rejects.

So the constraint lives on `dsar_request`, using the same reserve-first
`INSERT ... ON CONFLICT DO NOTHING` idiom, and re-using the shared contract's typed outcomes
(`IdempotencyConflictException` → 409, `IdempotencyPayloadMismatchException` → 422) so a client
sees one uniform contract regardless of which store backs it. The shared service is unmodified and
no second general-purpose store was created.

### Deviation from the planned constraint shape, and why it is strictly stronger

The plan specified a unique constraint over `(subject_email_sha256, idempotency_key)`. **Shipped as
a unique index on `idempotency_key` alone**, because the composite cannot satisfy the plan's own
behaviour list: under it, the same key carrying a *different address* produces a different subject
digest, does not collide, and inserts a second row — which is precisely the "silent second row"
`<behavior>` forbids. In the shared store the key is scoped by a caller dimension (`tenant_id`);
this endpoint has no caller dimension at all, so the key must be unique for the endpoint outright.
Recorded here rather than silently substituted, and proven by
`theSameKeyWithADifferentPayloadIsRejectedRatherThanQueueingASecondRow`, which posts two different
addresses under one key and asserts 422 plus a row count of exactly 1.

## The rate limit, and why it is not the shared limiter

**5 requests per client IP per hour**, injected from `jtoye.gdpr.dsar.rate-limit.requests-per-hour`.
Reasoning: a genuine data subject lodges one request; a whole household behind one NAT address does
not lodge five in an hour. The platform default is 100/min and the public storefront tier is
30/min — sized for browsing, which is a budget rather than a bound for a destructive action.

The bucket is **in-process, not behind the shared Redis proxy manager**, and that is a deliberate
trade recorded rather than hidden. The shared limiter is switched off wholesale by
`rate-limiting.enabled=false` and **fails OPEN with an alarm on any Redis error** — both correct for
a throughput control and wrong for a guard on an unverified erasure request. A protection that
disappears when a config flag flips or a cache blinks is the fail-open shape this project keeps
paying for. The cost is stated plainly in the class javadoc: the bound is **per instance**, so with
N replicas the global ceiling is N × 5/hour. The distributed public tier still sits on top. A
distributed DSAR bucket is a worthwhile follow-up, not a precondition.

The IP map is bounded (`max-tracked-clients`, default 10 000). Above the cap it first evicts
fully-refilled buckets — safe, because a full bucket is indistinguishable from a new one — and then
routes further clients to one shared overflow bucket. That is fail-closed on purpose: clearing the
map is exactly the reset an address-spraying attacker would be trying to cause.

Refusal is a typed RFC 7807 429 at `https://jtoye.uk/errors/dsar-rate-limited` with
`code: "DSAR_RATE_LIMITED"`, a numeric `retryAfterSeconds`, and a `Retry-After` header.
Deliberately distinct from the platform limiter's `.../errors/rate-limited` so a machine client can
tell "I am going too fast" from "this destructive endpoint is protected".

## Break arms — both directions, real output

Every arm was run against a **committed** tree, restored by `git checkout -- <file>`, and each
restore verified **by content hash** (`git hash-object`), never by `git diff --stat`. The closing
clean arm was run last.

Baseline hashes: `RlsContractTest.java` `5ec09c18`, `V62__dsar_request.sql` `daa04487`,
`DsarIntakeService.java` `b4370fd1`, `DsarIntakeController.java` `500dd26a`.

| # | Deliberate break | Clean direction | Break direction (real output) |
|---|---|---|---|
| A | Remove `dsar_request` from `EXEMPT_TABLES` | `BUILD SUCCESSFUL`, 6 tests | `everyPublicTableHasRlsAndForce() FAILED`, 6 completed / 1 failed — message: *"ENABLE ROW LEVEL SECURITY missing on public.dsar_request … If dsar_request is intentionally not tenant-scoped…"* |
| B | Add `tenant_id` to V62 | `BUILD SUCCESSFUL`, 7 tests | `dsarRequestHasNoTenantDimension() FAILED`, 7 completed / 1 failed — **and `everyPublicTableHasRlsAndForce` stayed GREEN**, which is the point (see below) |
| C1 | 404 when no tenant holds the address | `BUILD SUCCESSFUL`, 14 tests | 14 completed / **9 failed**, incl. `theResponseIsByteIdentical…` — *"Status expected:<202> but was:<404>"* |
| C2 | 202 both ways, but the body reveals the match | `BUILD SUCCESSFUL`, 14 tests | 14 completed / **exactly 1 failed** — only `theResponseIsByteIdentical…`; leaked body `{"status":"received-matched",…}` |
| D1 | Persist the readable address in `last_error` | `BUILD SUCCESSFUL` | `onlyTheDigestOfTheAddressIsPersisted() FAILED` — *"column dsar_request.last_error holds the readable address"* |
| D2 | Drop the lower-casing half of the normalisation | `BUILD SUCCESSFUL` | `onlyTheDigestOfTheAddressIsPersisted() FAILED` at the digest assertion |
| E1 | Declare system authority **inside the service body** | `BUILD SUCCESSFUL`, 21 tests | `theDsarIntakePathNeverDeclaresSystemAuthority() FAILED` — and the runtime probe stayed green (documented blind spot) |
| E2 | Declare system authority **at the controller** | `BUILD SUCCESSFUL`, 21 tests | **2 failed**: the source arm *and* `theIntakeRequestThreadNeverDeclaresSystemAuthority()` |
| F | Remove the IP bucket | `BUILD SUCCESSFUL` | 2 failed: `exceedingThePerIpLimitReturns429…` and `aDifferentClientIpHasItsOwnBucket()` |

Closing clean arm, run **last**, after every restore:
`./gradlew :core-java:integrationTest --tests '*DsarIntake*' --tests '*SystemPrincipalGuard*' --tests '*RlsContractTest*' --rerun-tasks` → `BUILD SUCCESSFUL in 52s`.

### Arm C2 is the one that justifies the whole test design

Under C2 the endpoint still returned **202 in both cases**, so every `status().isAccepted()`
assertion in the class passed — 13 of 14 tests green. Only the byte-for-byte body comparison fired.
A status-only acceptance criterion would have reported this endpoint clean while it answered
"which of your vendors holds this person's address" to anyone with a browser. That is exactly why
the criterion compares raw bytes rather than status codes.

### Arm B: the plan's own criterion could not fail, and was replaced

The plan asked to add a `tenant_id` column and "confirm the justification is now false". Run as
written, that arm **passes**: `EXEMPT_TABLES` is keyed by table name, so the schema walk skips
`dsar_request` before it looks at a single column. The sweep stayed green with a tenant-dimensioned
table sitting inside an exemption whose written reason had just become a lie.

Rather than report the vacuous pass as satisfied, the criterion was replaced with a strictly
stronger executable form: `RlsContractTest.dsarRequestHasNoTenantDimension` walks `pg_attribute`
and asserts the table carries no `tenant_id`, behind a non-vacuity control that asserts the walk
saw the table's columns at all first. Re-running the same arm now reds that test and names the
columns it found. Both directions are recorded above.

### Arm E: the runtime probe's blind spot, measured not assumed

`SystemPrincipal.asSystem` **restores** the prior value in a `finally`, so after the call the
thread is byte-identical whether or not it declared. Any assertion taken before or after the intake
is therefore incapable of failing. Two complementary arms were built, and arm E1 measured the
limit of each: a declaration placed **inside the service body** reds only the source scan, while
one placed **at the controller** reds both. The javadoc on both tests states this rather than
implying uniform coverage.

## Deviations from Plan

### Auto-fixed / auto-added (Rules 1–2)

**1. [Rule 2 — missing critical functionality] The DTO now trims before validation**
- **Found during:** Task 2, GREEN phase — `onlyTheDigestOfTheAddressIsPersisted` failed with
  `Status expected:<202> but was:<400>` on the input `"  MiXeD.Case@Example.COM  "`.
- **Issue:** Jakarta `@Email` does not trim. People reach a legal page by pasting an address out of
  an email client, and a pasted value routinely carries a leading or trailing space. A consumer
  trying to exercise a statutory right was told their own address was invalid, with a deliberately
  generic message giving them nothing to fix.
- **Fix:** a compact canonical constructor on `DsarIntakeRequest` trims `email`. Jackson binds
  through it, so validation, the digest and the persisted row all see the trimmed value.
  Lower-casing deliberately stays with the digest in `DsarIntakeService`, which owns the whole
  normalisation contract.
- **Files:** `core-java/src/main/java/uk/jtoye/core/gdpr/dto/DsarIntakeRequest.java`
- **Commit:** `e1783625`

**2. [Rule 2] Files added beyond `files_modified`, all additive**
- `DsarIntakeRateLimiter.java` and `exception/DsarRateLimitExceededException.java` — the plan's
  action item and threat T-31-05-02 both require a dedicated IP bucket, which needs a component and
  a typed error; neither could live in the declared files without either polluting the shared
  `RateLimitInterceptor` or returning an untyped error.
- `GlobalExceptionHandler.java` — one additive `@ExceptionHandler` so the 429 is a typed RFC 7807
  document like every other error surface in the codebase. No existing handler touched.
- `ClientIpResolver.java` — widened from package-private to public (two keywords, one javadoc
  paragraph) so the second tenant-less limiter keys on the *same* resolution instead of
  reimplementing X-Forwarded-For handling. Two implementations that can disagree about what a
  client is would be worse than the visibility change.
- `docs/api/openapi-snapshot.json`, `docs/metrics.json` — regenerated, never hand-edited.

No other wave-1 plan declares any of these files, so the conflict risk was checked before editing.

### Deliberate substitutions (recorded, not silent)

**3. Unique constraint on `idempotency_key` alone, not the planned composite.** See above — the
composite cannot satisfy the plan's own `<behavior>` list.

**4. `RlsContractTest` arm (b) replaced with a stronger executable form.** See Arm B above.

**5. The `EXEMPT_TABLES` diff shows one modified line.** The acceptance criterion asked for
additions only. The single modified line is `"postcode_centroid"` gaining a trailing comma, which
is mechanically unavoidable when adding an element to a `Set.of(...)`. No line of any sweep
assertion is modified; the diff is `29 insertions(+), 1 deletion(-)`.

## Known Stubs

**Verification delivery and the verify endpoint are NOT in this plan, and the queue is inert
without them.** Stated plainly because it is the kind of gap this phase exists to stop shipping
silently.

| Stub | File | Why, and who resolves it |
|---|---|---|
| Rows are created `PENDING_VERIFICATION` and nothing can move them to `VERIFIED` | `DsarIntakeService.lodge` | A verification token is generated and its **digest + expiry are persisted**, so the check has something to compare against. What is missing is (a) emailing the readable token and (b) a verify endpoint. Both need the notification package and both belong to the execution half. **Plan 31-09** owns making a row actionable. |

The alternative — defaulting to `VERIFIED` — was rejected outright: it would make an *unverified*
erasure request executable, which is precisely the weapon threat T-31-05-02 describes. An inert-but-
safe queue is the correct state to hand to 31-09; an armed one is not.

## Notes for plan 31-09

- **Route and contract:** `POST /api/v1/public/gdpr/dsar`, body
  `{ "email": string, "requestType": "ACCESS" | "ERASURE" }`, optional `Idempotency-Key` header.
- **The digest contract is load-bearing and both sides must agree.**
  `DsarIntakeService.normaliseAddress` is `email.trim().toLowerCase(Locale.ROOT)`, then SHA-256 hex
  over UTF-8. The worker must reproduce this exactly over customer rows or the fan-out silently
  matches nothing while every test stays green. Note `GdprService.sha256Hex` hashes the address
  **as-is** with no normalisation — do not assume the two are interchangeable.
- **The readable address is not available to the worker.** It is never stored. Matching is
  digest-to-digest. PostgreSQL 11+ has a built-in `sha256(bytea)` in `pg_catalog`, so
  `encode(sha256(convert_to(lower(trim(email)), 'UTF8')), 'hex')` computes the comparison
  server-side **with no extension** — which matters, because `scripts/check-no-create-extension.sh`
  forbids one and the Flyway role could not create it anyway.
- **Claim query:** `DsarRequestRepository.findByStatusAndCompletedAtIsNullOrderByReceivedAtAsc`,
  backed by the partial index `idx_dsar_request_outstanding` (`WHERE completed_at IS NULL`).
- **`process_attempts`, `claimed_at`, `last_error`** exist for the worker's bookkeeping and are
  currently only ever written by it.
- **`dsar_request` has no RLS.** The worker's cross-tenant reach must come from iterating tenants
  and pinning `app.current_tenant_id`, exactly as `WebhookRetentionCleanup` does — never from a
  query that ignores the wall.

## Cross-cutting quality contracts

| Dimension | Disposition |
|---|---|
| Security | Contracted. All eight `mitigate` dispositions implemented; the enumeration-oracle, digest-only, no-system-authority and rate-limit mitigations each have a run break arm. |
| AI agent-readiness | Contracted. Idempotency-Key contract honoured and advertised in OpenAPI via `@Idempotent`; all errors are RFC 7807 with stable types and machine-parseable `code` / `retryAfterSeconds`; the regenerated snapshot matches live responses (`OpenApiSnapshotTest` green in the full suite). No MCP tool: an anonymous consumer-privacy intake is not an agent-operable capability, and exposing one would give an agent a destructive action against arbitrary third parties. |
| Falsifiable evidence | Contracted. Nine break arms run, both directions recorded, one criterion measured unfalsifiable and replaced with a stronger form. |
| Web performance | **N/A** — no user-facing page in this plan (31-11 owns the surface that calls this route). |
| SEO / discoverability | **N/A** — a JSON API endpoint, not a crawlable surface. |
| Accessibility | **N/A** — no UI in this plan. |
| Runtime parity | **Not applicable at this stage** — this is a worktree branch executed in parallel; no runtime is delivered or handed back here. The phase-level rebuild-and-verify obligation stands and is unaffected by this plan. |

## Threat Flags

None. Every security-relevant surface this plan introduces — the public endpoint, the new table,
the anonymous write path — is already in the plan's `<threat_model>` register (T-31-05-01 through
-08). No new trust boundary was created beyond those.

## Gate state handed forward

| Gate | State | Note |
|---|---|---|
| `scripts/check-no-create-extension.sh` | **rc=0** | 62 migration files scanned (was 61 — the instrument sees V62) |
| `scripts/docs-freshness.sh` (tree → manifest) | **rc=0** | regenerated with `--write` |
| `scripts/check-doc-metrics.sh` (prose → manifest) | **rc=1** | 13 claims in README / CLAUDE / AGENTS still quote `2807`, `1633`, `264`, `V61`. **Plan 31-18 owns this.** Deliberate: every wave-1 plan moves these counters, so prose written now is stale before the wave merges. The correct resolution on a merged tree is to re-run `scripts/docs-freshness.sh --write` there, then update the prose once. |
| `./gradlew :core-java:test :core-java:integrationTest` | **BUILD SUCCESSFUL in 18m 37s** | full suite, `--rerun-tasks`, including `OpenApiSnapshotTest` — a new public path has broken existing integration tests in this repo before, so the full run was not skipped |

## Commits

| Commit | Subject |
|---|---|
| `382c2b36` | `feat(31-05)`: V62 dsar_request intake queue with an explicit RLS decision |
| `468405d4` | `test(31-05)`: make the dsar_request exemption justification falsifiable |
| `6cc38d21` | `test(31-05)`: failing test for the public DSAR intake (RED) |
| `e1783625` | `feat(31-05)`: public DSAR intake — opaque, rate-limited, idempotent (GREEN) |
| `ae6c5836` | `chore(31-05)`: regenerate docs/metrics.json for V62 and the new tests |

## TDD Gate Compliance

Task 2 was declared `tdd="true"` and the gate sequence is present and in order: RED (`6cc38d21`,
recorded failing with *"package uk.jtoye.core.gdpr.dto does not exist"*) precedes GREEN
(`e1783625`). No REFACTOR commit — the implementation needed no cleanup pass, which the gate permits.
