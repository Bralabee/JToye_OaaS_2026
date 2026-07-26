---
phase: 27-messaging-layer-hardening
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - core-java/src/main/resources/db/migration/V60__media_quarantine_durability.sql
  - core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxRepository.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaQuarantineRetentionSweep.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetDto.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaController.java
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/media/MediaPendingReaperTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaQuarantineRetentionSweepTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaDurabilityIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
  - frontend/types/api.ts
  - frontend/lib/media-api.ts
  - frontend/components/ui/asset-image.tsx
  - frontend/components/ui/__tests__/asset-image.test.tsx
  - frontend/components/dashboard/media/ReviewQueue.tsx
  - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
  - docs/metrics.json
  - docs/api/openapi-snapshot.json
autonomous: true
requirements: [MSG-01]

must_haves:
  truths:
    - "The timeout path NEVER deletes quarantine bytes — MediaPendingReaper has no StorageService dependency at all"
    - "A PENDING asset whose media_event_outbox row proves the work was never dispatched (PENDING / non-poison FAILED / no row at all) is never flipped and never touched"
    - "A dispatched-but-stalled asset is re-driven (a fresh outbox row) up to a config budget before it is flipped FAILED, and the flip retains the raw bytes"
    - "When the media.process queue reports zero consumers — or its state cannot be read at all — the stall sweep suspends (fails CLOSED), so 'found nothing' is never 'safe to delete'"
    - "Quarantine-bucket growth stays bounded: a separate retention sweep reclaims expired quarantine objects on a stated, config-declared horizon (the Phase 24 good is preserved, not traded away)"
    - "The retention sweep can only ever delete an object whose key contains /quarantine/ AND whose asset is not ACTIVE — two independently-breakable guards"
    - "A FAILED asset whose raw bytes are still retained is recoverable without a re-upload, through an idempotent, shop-scoped, RFC 7807 re-drive endpoint"
    - "Every new query pins TenantContext + the app.current_tenant_id GUC inside a TransactionTemplate, and is proven tenant-scoped under the downgraded NOSUPERUSER role"
  artifacts:
    - path: "core-java/src/main/resources/db/migration/V60__media_quarantine_durability.sql"
      provides: "media_asset.process_attempts + quarantine_expires_at (+ media_asset_aud mirrors) + the outbox asset lookup index"
      contains: "media_asset_aud"
    - path: "core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java"
      provides: "Dispatch-evidence-gated, non-destructive, consumer-liveness-suspended stall sweep with a bounded re-drive"
      min_lines: 120
    - path: "core-java/src/main/java/uk/jtoye/core/media/MediaQuarantineRetentionSweep.java"
      provides: "The bounded-retention backstop that keeps quarantine-bucket growth bounded"
      min_lines: 80
  key_links:
    - from: "MediaPendingReaper"
      to: "MediaEventOutboxRepository"
      via: "latest-outbox-row-per-asset dispatch evidence"
      pattern: "findLatestDispatchStateForAssets"
    - from: "MediaPendingReaper"
      to: "AmqpAdmin.getQueueInfo"
      via: "consumer-liveness suspension (fail closed)"
      pattern: "getQueueInfo"
    - from: "MediaQuarantineRetentionSweep"
      to: "StorageService.deleteByKey"
      via: "the ONLY remaining caller that reclaims quarantine bytes on a timeout-class path"
      pattern: "deleteByKey"
    - from: "MediaController"
      to: "MediaAssetService.redriveFromQuarantine"
      via: "POST /api/v1/media/{assetId}/reprocess"
      pattern: "redriveFromQuarantine"
---

<objective>
Close a **P0 data-loss defect**: `MediaPendingReaper` permanently deletes the quarantined source
bytes of vendor uploads that were never processed, whenever the media dispatch path
(RabbitMQ / the media outbox flush) is unavailable for longer than the 15-minute reaper grace.
The transactional outbox protects the *event*; nothing protects the *object*. A transient
infrastructure failure therefore produces unrecoverable loss of user data.

The fix is **not** "turn the reaper off". Orphan cleanup is a real, working good — without it the
quarantine prefix grows without bound. The fix separates the two things the reaper currently
conflates: the cheap, reversible **state flip** and the irreversible **byte delete**; gates the
flip on durable evidence that the work was actually dispatched; suspends the sweep entirely when
the consumer side cannot be shown alive; and makes the resulting failure **recoverable from
quarantine** instead of forcing the vendor to re-upload.

Output: `V60`, a rewritten `MediaPendingReaper`, a new `MediaQuarantineRetentionSweep`, a
`POST /api/v1/media/{assetId}/reprocess` re-drive surface, the vendor-visible affordance, and 24
new Java tests + 3 new Jest tests whose *fail direction has been run and recorded*.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

---

<verified_defect>

## 1. The mechanism, confirmed against source

Every claim below was read out of the tree at `feature/phase-26-local-k8s-overlay` (`78eaa99`).

| # | Claim | Evidence |
|---|---|---|
| 1 | The reaper runs every 10 min by default | `MediaPendingReaper.java:55` — `@Scheduled(fixedDelayString = "${jtoye.media.reaper-interval-ms:600000}")` |
| 2 | Cutoff = now − 15 min | `MediaPendingReaper.java:57` — `OffsetDateTime.now().minusNanos(properties.getReaperGraceMs() * 1_000_000L)`; `MediaProperties.java:66` — `private long reaperGraceMs = 900_000;` |
| 3 | The selection is *status only*, with no dispatch evidence | `MediaAssetRepository.java:33-35` — `WHERE a.status = PENDING AND a.createdAt < :cutoff`. There is **no** join to `media_event_outbox`, no attempts column, nothing. |
| 4 | It deletes the quarantine object **and** flips FAILED | `MediaPendingReaper.java:78-82` — `storageService.deleteByKey(asset.getObjectKey()); asset.setStatus(FAILED); asset.setFailureReason("Processing timed out — please re-upload");` |
| 5 | The delete is unconditional and unrecoverable | `StorageService.deleteByKey` (`StorageService.java:288-298`) is best-effort and **swallows every exception**, so it never aborts the caller and never reports failure upward. |
| 6 | The re-delivered event is then silently discarded | `MediaProcessingWorker.java:112-116` — a redelivered event on a non-PENDING asset logs `media_process_skipped reason=not_pending` and returns. So when the broker recovers, the event arrives, finds a FAILED row, and drops. |

**Verdict: CONFIRMED, exactly as described.** The reaper was written for a crashed worker
(`MediaPendingReaper.java:18-24` says so) and has no way to distinguish that from "the worker
never ran at all".

## 2. Timing — the window is genuinely reachable, not theoretical

During a broker outage `MediaEventOutboxFlusher.publishRow` throws, increments `attempts`, and
pushes `next_attempt_at` out by `computeBackoffMillis(attempts, 5_000, 300_000)`
(`MediaEventOutboxFlusher.java:71-72, 101-113, 219-234`). Cumulative delay to `MAX_ATTEMPTS = 10`:

```
5 + 10 + 20 + 40 + 80 + 160 + 300 + 300 + 300  ≈ 1215 s ≈ 20 min
```

So at the 15-minute reap cutoff the outbox row is still `PENDING` with ~7-8 attempts — **the
event has provably not been dispatched, and the evidence for that is sitting in the database
one join away.** The reaper deletes the bytes anyway.

## 3. Two corrections to the brief

**(a) The reaper's own premise is largely false.** `RabbitMQConfig.java:389-410` sets
`factory.setAdviceChain(retryInterceptor())` (3 attempts, 1s→10s backoff) and
`factory.setDefaultRequeueRejected(false)`. A worker that *crashes mid-process* (process kill,
channel close) leaves the message **unacked**, so the broker requeues it and the work is retried —
that case is already self-healing. A worker that *throws* is retried 3× then dead-lettered to
`media.process.dlq` via `x-dead-letter-exchange` (`RabbitMQConfig.java:360-364`). So the genuine
"PENDING forever" population is much smaller than the class the reaper reaps, and the class it
does reap is dominated by *undispatched* work. This makes the defect worse, not better.

**(b) A second, independent defect in the same method.** The irreversible S3 delete happens
*inside* the `TransactionTemplate` callback (`MediaPendingReaper.java:75-84`) but is not
transactional. `MediaAsset` carries `@Version` (V59), so a concurrent worker flip makes the
commit throw `ObjectOptimisticLockingFailureException`, which propagates to
`reapOrphans`'s catch at `:63-65`. **The DB writes roll back; the object deletes do not.** The
tenant's whole batch loses its bytes while the rows still read `PENDING`. V59's optimistic lock
protects the row and does nothing for the object.

## 4. The true state space — what distinguishes the cases

`media_event_outbox` (V58) is the only durable dispatch ledger, and it is queryable from the
reaper's side: same tenant GUC, `FORCE RLS` via `current_tenant_id()`
(`V58__media_event_outbox.sql:40-52`), one row inserted in the **same transaction** as the PENDING
asset (`MediaAssetService.java:169-174`, and again on the WR-01 reprocess path at `:212-215`).
Every PENDING asset has one — including the `BulkImportService` path, which routes through the
same `acceptQuarantineAndQueue` (`BulkImportService.java:221-222`). There is **no purge** of the
outbox anywhere in the tree, so the row survives.

| Real-world case | `media_asset` | latest `media_event_outbox` row | Bytes | Reap today | Correct |
|---|---|---|---|---|---|
| A. Broker / flusher outage | PENDING | `PENDING`, attempts>0 | present | **deleted — LOSS** | leave alone |
| B. Outbox retry-exhausted | PENDING | `FAILED`, `poison=false` | present | **deleted — LOSS** | leave alone (resurrection re-leases it, `MediaEventOutboxRepository.java:47-56`) |
| C. Corrupt payload | PENDING | `FAILED`, `poison=true` | present | deleted | flip FAILED, **keep bytes** — will never be delivered |
| D. Dispatched, consumer down/absent | PENDING | `SENT` | present | deleted | re-drive, then flip FAILED **keeping bytes** |
| E. Dispatched, dead-lettered to DLQ | PENDING | `SENT` | present | deleted | same as D |
| F. Worker in flight, slow | PENDING | `SENT` | present | bytes deleted, row write may roll back | leave alone / harmless re-drive |

**A and B are fully distinguishable and are the loss cases.** D and E are *not* distinguishable
from each other or from F by the outbox row alone — that residual is what D-05 (consumer
liveness) and D-02 (never delete on the timeout path) exist to make **non-destructive** rather
than perfectly classified.

`media_asset.version` (V59) is a JPA optimistic counter only — it says nothing about dispatch
(`MediaAsset.java:102-110`). There is no other signal on the asset row.

**A FAILED asset row retains everything needed to recover** — `object_key` (still the quarantine
key), `sha256`, `content_type`, and the placement intent `product_id` / `is_primary` /
`sort_order` are all untouched by the reaper. The *only* thing missing after a timeout-reap is
the bytes. That is the whole fix in one sentence.

## 5. Incidental findings recorded (not fixed here unless stated)

- **F-1.** `jtoye.media.reaper-grace-ms` is bound but has **no key in any `application*.yml`** —
  `grep -rn "reaper-grace" --include=*.yml` returns nothing; only `reaper-interval-ms` is declared
  (`application.yml:214`). The single most safety-critical number in the pipeline is not
  operator-tunable by the documented env pattern. **Fixed by Task 3.**
- **F-2.** No index supports a lookup by `media_event_outbox.asset_id` — the only index is
  `idx_media_event_outbox_claim (status, next_attempt_at, created_at)`
  (`V58__media_event_outbox.sql:37-38`). **Fixed by Task 1.**
- **F-3.** The `jtoye-images` MinIO bucket is `mc anonymous set download`
  (`docker-compose.full-stack.yml:425-428`), so quarantine objects are anonymously readable **by
  key today**. This change lengthens the retention window and therefore that exposure. Practical
  exposure is confirmation-of-possession only (the key is `<tenant>/quarantine/<sha256-of-the-raw-bytes>`,
  so deriving it requires already holding the file). Recorded in the threat model as T-27-04 with
  a bounded default; a separate non-public quarantine bucket is **out of scope** and must be
  filed as its own issue.
- **F-4.** `media_event_outbox` has no retention prune, so it grows monotonically. Out of scope,
  but Task 2 adds a guard test because **any future purge that deletes rows for a still-PENDING
  asset silently re-opens this P0.**

</verified_defect>

---

<decisions>

### D-01 — Gate the stall sweep on durable dispatch evidence, and fail CLOSED on absence. **ACCEPTED**

Reap-eligible ⟺ the **latest** `media_event_outbox` row for the asset (by `created_at DESC`; the
WR-01 reprocess path inserts additional rows for the same `asset_id`) is `SENT`, **or** is
`FAILED AND poison = true`.

Everything else — `PENDING`, `FAILED AND poison = false`, **and no row at all** — is left
completely untouched.

*Trade-off, stated plainly:* the brief proposed treating an **absent** row as "dispatched".
That is the fail-**open** reading and this plan inverts it. An absent row is ambiguous
(pre-outbox row? future purge? manual delete?) and the repo's standing rule is that a missing or
empty discovery result is never "clean". Cost of failing closed: a genuinely orphaned asset with
no outbox row is never stall-reaped — which is exactly why **D-03's retention sweep is
unconditional** and collects it anyway on the retention horizon. The bounded-growth good survives
either way; the loss risk does not.

### D-02 — Split the reversible flip from the irreversible delete. The timeout path NEVER deletes. **ACCEPTED**

`MediaPendingReaper` loses its `StorageService` dependency entirely (constructor parameter and
field both removed). It can flip `PENDING → FAILED`; it is structurally incapable of deleting an
object. This also closes the "delete escaped the rolled-back transaction" defect from §3(b) by
construction, because there is no longer any side effect to escape.

*Alternative rejected:* "delete after commit instead of inside the tx". It fixes §3(b) and leaves
the P0 completely untouched. Strictly weaker.

### D-03 — A separate, unconditional, long-horizon retention sweep preserves bounded growth. **ACCEPTED**

New `MediaQuarantineRetentionSweep`, cloned from `WebhookRetentionCleanup` (per-tenant,
`TransactionTemplate`, GUC pinned) — the same shape `MediaPendingReaper` already clones. It
deletes a quarantine object when **all** of:

1. `status <> 'ACTIVE'` — a live derivative is never a candidate; **and**
2. `object_key` contains `/quarantine/` — the pipeline derivative convention is
   `<tenant>/media/<id>.webp` (`MediaProcessingWorker.java:151-152`) and a V53-backfilled key is
   `<tenant>/products/<pid>/<uuid>.<ext>` (`V53__media_asset.sql:189`), so neither can ever match;
   **and**
3. `quarantine_expires_at < now()`, **or** (`quarantine_expires_at IS NULL AND created_at < now() − retention`) —
   the second arm collects rows that predate V60, which get `NULL` and would otherwise be
   invisible forever.

Guards 1 and 2 are deliberately **independent and individually breakable**, so each gets its own
test with its own break. Defence-in-depth that can only be falsified by breaking both at once is
not evidence.

*This is the Incremental Betterment obligation discharged:* Phase 24's working good was "the
quarantine prefix cannot grow without bound". That good is preserved — it now happens on a
declared 72-hour policy horizon instead of as a 15-minute accident.

### D-04 — Bounded automatic re-drive before the flip. **ACCEPTED**

For a stalled asset with dispatch evidence and `process_attempts < jtoye.media.max-process-attempts`
(default 3): insert a **fresh** `media_event_outbox` PENDING row for the same asset, increment
`process_attempts`, leave the asset PENDING. At budget: flip `FAILED` with a distinct
vendor-visible reason, **bytes retained**.

`process_attempts` is a new column rather than a reuse of `media_event_outbox.attempts` (which
counts *publish* attempts, a different quantity) or `COUNT(*)` of outbox rows (which the WR-01
re-upload path also increments, so the budget would leak across a product's lifetime).

### D-05 — Consumer-liveness suspension, failing CLOSED. Subsumes the "broker-health circuit". **ACCEPTED**

Before any tenant loop, read `AmqpAdmin.getQueueInfo(RabbitMQConfig.MEDIA_EVENTS_QUEUE)`:

- `consumerCount == 0` → **suspend the whole tick** (WARN + `media.reaper.suspended` counter).
- return is `null`, the admin bean is absent, or the call throws → **suspend** (VOID, not clean).

This is the residual answer for cases D/E/F in §4, and it is also the broker-health circuit the
brief asked about — during a broker outage `getQueueInfo` cannot answer, so the sweep suspends.
One mechanism, not two. Fail-closed forever is survivable precisely because D-03's retention
sweep is *not* gated on it.

*Alternative rejected:* an Actuator `RabbitHealthIndicator` probe. It is global, coarse (broker
reachable ≠ this queue has consumers), and reports UP while `media.process` has zero consumers —
which is exactly case D.

### D-06 — Recovery is a first-class surface, not a re-upload. **ACCEPTED**

`POST /api/v1/media/{assetId}/reprocess` → `202` + `MediaAcceptDto`. Requires a retained
quarantine object (`quarantine_expires_at IS NOT NULL`); resets `status → PENDING`,
`process_attempts → 0`, `failure_reason → NULL`; inserts a fresh outbox row. Mirrors
`MediaController.keep` exactly for authorization: `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")`
plus the VSA-02 `shopAccessService.require(resolveOwningShopId(asset), SHOP_MANAGER)` gate applied
**after** the RLS `findById`, so a foreign asset is a 404 and never a 403 oracle
(`MediaAssetService.java:356-364`). Carries the uniform `Idempotency-Key` contract via
`IdempotencyService.execute("media.reprocess", ...)` and RFC 7807 typed errors — the standing
AI-agent-readiness contract for a new mutating endpoint.

### D-07 — The worker's *read-failure* path stops deleting; its *validation-veto* path keeps deleting. **ACCEPTED**

`MediaProcessingWorker.fail(...)` (`:239-245`) currently deletes on every failure. A D3 validation
veto (bomb / spoof / undecodable — `:139-147`) should keep deleting: those bytes are worthless and
possibly hostile, and that is a Phase 24 good worth keeping. But the read-failure path
(`:129-133`, "Could not read the quarantined upload") is a transient S3 condition; deleting there
converts a blip into permanent loss. Split it: read-failure leaves the object and
`quarantine_expires_at` intact (re-drivable); validation-veto deletes and NULLs the marker.

### D-08 — Retention default 72 h, not 7 days. **ACCEPTED**

72 h (259_200_000 ms) covers any realistic broker outage plus a weekend, while bounding the F-3
public-bucket exposure window. Operator-tunable via `MEDIA_QUARANTINE_RETENTION_MS`.

### D-09 — V60 is metadata-only; no backfill, so the RLS-backfill trap does not apply. **ACCEPTED**

`ADD COLUMN ... NOT NULL DEFAULT 0` and `ADD COLUMN ... TIMESTAMPTZ` (nullable) are metadata-only
in Postgres — no table rewrite, no per-row UPDATE. This is the identical argument
`V59__media_asset_version.sql:22-25` makes, and it is why the recurring `trap_rls_migration_backfill`
(V25 → V44 → V57: a bare UPDATE against a FORCE-RLS table updating zero rows as the migration
role) is genuinely inapplicable here rather than merely unmentioned. **No `UPDATE` statement may
appear in V60.**

`MediaAsset` is `@Audited` (`MediaAsset.java:40`), so **both columns must also be added to
`media_asset_aud`** or the first UPDATE of any asset row throws
`column "process_attempts" of relation "media_asset_aud" does not exist`. V59 dodged this only
because `@Version` is excluded from Envers by project convention (`V59:18-20`). This trap gets its
own test with its own break (AC-1.4).

</decisions>

---

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md

<interfaces>

**House idioms that must be copied, not re-invented:**

1. **Per-tenant scheduled sweep** — `WebhookRetentionCleanup.java:49-100` is the canonical shape and
   the one `MediaPendingReaper` already clones: `@Scheduled(fixedDelayString=...)`, `listTenantIds()`
   via `SELECT id FROM tenants` (no RLS on `tenants`), per-tenant `try { TenantContext.set(id) …
   } finally { TenantContext.clear(); }`, a `TransactionTemplate` (**never** a `@Transactional`
   private method — Spring self-invocation silently skips the proxy and the tenant comes out NULL),
   and `pinTenantGuc` via `session.doWork` +
   `SELECT set_config('app.current_tenant_id', ?, true)`. Under `FORCE RLS` an unpinned query
   returns **zero rows**, so a missing pin looks like "nothing to do".

2. **Dispatch-evidence query** — one native query, not N+1, using Postgres `DISTINCT ON`:
   ```sql
   SELECT DISTINCT ON (asset_id) asset_id, status, poison
   FROM media_event_outbox
   WHERE asset_id IN (:assetIds)
   ORDER BY asset_id, created_at DESC
   ```
   RLS still applies (the caller pins the GUC), and `created_at DESC` is load-bearing because the
   WR-01 reprocess path inserts a *second* row for the same `asset_id`
   (`MediaAssetService.java:215`).

3. **Micrometer counters** — `ObjectProvider<MeterRegistry>` + null-guarded increments, exactly as
   `MediaEventOutboxFlusher.java:70, 83-93, 218, 227`. The registry is genuinely absent in some
   test contexts.

4. **Config properties** — `MediaProperties` is registered explicitly through
   `MediaConfig.java:12-14` (`@EnableConfigurationProperties`); this project does **not** use
   `@ConfigurationPropertiesScan`. Hand-written getters/setters, no Lombok.

5. **The Phase 26 env-contract gate** (`k8s/scripts/check-env-contract.sh`) runs in CI and will see
   every new `${NAME:default}` added to `application.yml`. Direction (b) fails a placeholder that
   has **no** default, or whose default matches the local-only word list
   (`localhost`, `127.0.0.1`, `0.0.0.0`, `minioadmin`, `guest`, `mailhog`, `host.docker.internal`)
   while not being injected by any manifest. All four new keys are plain integers with defaults, so
   they pass — **but this must be asserted, not assumed** (AC-3.3).

6. **Idempotency** — `IdempotencyService.execute(endpoint, key, requestObj, responseClass, supplier)`;
   see `MediaUploadController.java:127-134` for the exact call shape and the "controller owns the
   202, the service stamps 201 internally" convention.

7. **Build reality** — the core-java build dir is redirected to `build-local`
   (`core-java/build.gradle.kts:15`). `core-java/build/` is a **stale artifact directory** and
   reading it yields a false result. `cleanTest` / `cleanIntegrationTest` are load-bearing: without
   them Gradle reports `UP-TO-DATE` / `BUILD SUCCESSFUL` while executing **nothing**. Testcontainers
   tests carry `@Tag("testcontainers")` and only run under `:core-java:integrationTest`.

</interfaces>
</context>

---

<tasks>

<task type="auto">
  <name>Task 1: V60 — durability state columns + Envers mirrors + the outbox asset index; entity/repo wiring</name>
  <files>
core-java/src/main/resources/db/migration/V60__media_quarantine_durability.sql,
core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java,
core-java/src/main/java/uk/jtoye/core/media/MediaAssetRepository.java,
core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxRepository.java
  </files>
  <read_first>
    - core-java/src/main/resources/db/migration/V59__media_asset_version.sql (the metadata-only ADD COLUMN argument to reproduce verbatim, and the "why the backfill trap does not apply" reasoning)
    - core-java/src/main/resources/db/migration/V53__media_asset.sql lines 118-154 (the media_asset_aud mirror — every business column appears there, all nullable)
    - core-java/src/main/resources/db/migration/V58__media_event_outbox.sql lines 35-52 (index + RLS policy style)
    - core-java/src/main/java/uk/jtoye/core/media/MediaAsset.java (hand-written accessors; @Audited; the @Version javadoc explaining the Envers exclusion)
  </read_first>
  <action>
Create `V60__media_quarantine_durability.sql`:

- `ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS process_attempts INT NOT NULL DEFAULT 0;`
- `ALTER TABLE media_asset ADD COLUMN IF NOT EXISTS quarantine_expires_at TIMESTAMPTZ;`
  (NULL means "no retained raw bytes" — the single source of the `redrivable` bit.)
- The **same two columns on `media_asset_aud`, both nullable** (D-09's Envers trap).
- `CREATE INDEX IF NOT EXISTS idx_media_asset_quarantine_expiry ON media_asset (quarantine_expires_at) WHERE quarantine_expires_at IS NOT NULL;`
- `CREATE INDEX IF NOT EXISTS idx_media_event_outbox_asset ON media_event_outbox (asset_id, created_at DESC);` (F-2)
- **No `UPDATE`. No `DO $$` loop. No policy change** — no new table, so RLS posture is inherited
  and `RlsContractTest` is unaffected.
- Header comment must state, in the V59 voice: why each column exists, that ADD COLUMN with a
  constant DEFAULT is metadata-only so `trap_rls_migration_backfill` genuinely does not apply,
  that NULL `quarantine_expires_at` on pre-existing rows is *correct* (backfilled ACTIVE assets
  have no quarantine object) and that pre-V60 in-flight PENDING rows are collected by the
  retention sweep's legacy `created_at` arm, and that HEAD is V59 so V60 is the next strict
  version while `spring.flyway.out-of-order=true` stays required for the V44/V53 reserved slots.

Add to `MediaAsset`: `processAttempts` (`int`, `@Column(name="process_attempts", nullable=false)`,
default `0`) and `quarantineExpiresAt` (`OffsetDateTime`, `@Column(name="quarantine_expires_at")`),
both with hand-written accessors and javadoc naming the defect they exist to close. Both are
audited (no `@NotAudited`) — that is what the aud mirror is for.

Add to `MediaAssetRepository`:
```java
@Query("SELECT a FROM MediaAsset a WHERE a.status <> uk.jtoye.core.media.MediaAsset.Status.ACTIVE "
     + "AND (a.quarantineExpiresAt < :now "
     + "  OR (a.quarantineExpiresAt IS NULL AND a.createdAt < :legacyCutoff))")
List<MediaAsset> findReclaimableQuarantine(@Param("now") OffsetDateTime now,
                                           @Param("legacyCutoff") OffsetDateTime legacyCutoff);
```
The `/quarantine/` path guard is applied in the sweep, **not** here, so the two guards stay
independently breakable (D-03).

Add to `MediaEventOutboxRepository` the `DISTINCT ON` native projection from `<interfaces>` item 2,
named `findLatestDispatchStateForAssets(Collection<UUID> assetIds)`, returning
`List<Object[]>` (`asset_id`, `status`, `poison`) with a javadoc stating that `created_at DESC` is
load-bearing because WR-01 inserts a second row per asset.
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.*' 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>

**AC-1.1 — V60 applies on a fresh DB and both columns land on pre-existing rows with the right defaults.**
- PASS: `MediaDurabilityIntegrationTest#preExistingRowsGetDefaultsWithoutBackfill` seeds a
  `media_asset` row via raw JDBC *before* asserting, then reads
  `information_schema.columns` for `media_asset` and asserts
  `process_attempts` → `is_nullable=NO`, `column_default='0'`; `quarantine_expires_at` →
  `is_nullable=YES`; and the seeded row reads `process_attempts=0`, `quarantine_expires_at IS NULL`.
- BREAK: change V60's first statement to `ADD COLUMN IF NOT EXISTS process_attempts INT NOT NULL;`
  (drop `DEFAULT 0`).
- RED (expected): Flyway migration failure at context startup —
  `org.postgresql.util.PSQLException: ERROR: column "process_attempts" of relation "media_asset" contains null values`
  on any non-empty table, surfacing as `FlywayException: Migration V60__media_quarantine_durability.sql failed`.
- *Not vacuous because:* the assertion reads `information_schema` on the live container, not the
  file. A "grep V60 for DEFAULT" check would be an already-0/already-1 string test that passes on
  a migration that never runs.

**AC-1.2 — The outbox asset index exists and is the one the dispatch query uses.**
- PASS: `SELECT indexname FROM pg_indexes WHERE tablename='media_event_outbox'` contains
  `idx_media_event_outbox_asset`; and
  `EXPLAIN SELECT DISTINCT ON (asset_id) … WHERE asset_id IN (…)` output contains `Index Scan` and
  does **not** contain `Seq Scan on media_event_outbox`.
- BREAK: comment out the `CREATE INDEX … idx_media_event_outbox_asset` line in V60.
- RED (expected): the `pg_indexes` assertion fails
  (`Expecting actual … to contain: "idx_media_event_outbox_asset"`), and the EXPLAIN assertion
  fails showing `Seq Scan on media_event_outbox`.
- *Note:* the EXPLAIN arm must run against a table seeded with enough rows that the planner would
  not prefer a seq scan anyway (≥ 500 rows); with 3 rows Postgres seq-scans a perfectly good index
  and the criterion inverts. Seed accordingly or drop the EXPLAIN arm and keep only `pg_indexes`.

**AC-1.3 — No `UPDATE` and no tenant loop in V60 (D-09).**
- PASS: `grep -cE '^[[:space:]]*(UPDATE|DO \$\$)' core-java/src/main/resources/db/migration/V60__media_quarantine_durability.sql` returns `0`.
- BREAK: append `UPDATE media_asset SET process_attempts = 0;` to V60.
- RED (expected): the grep returns `1`.
- **This criterion is a known-vacuous shape (an expected-0 grep) and is retained ONLY because its
  fail direction is run and recorded.** It is *not* the evidence that the backfill trap is
  avoided; AC-1.1 is. Record both directions' output or drop this criterion.

**AC-1.4 — The Envers mirror carries both columns (the D-09 trap).**
- PASS: `MediaDurabilityIntegrationTest#enversMirrorCarriesTheNewColumns` inserts a `media_asset`
  through the repository, **updates** it (sets `processAttempts`), flushes, then asserts a
  `media_asset_aud` row exists for that id carrying the updated `process_attempts` value.
- BREAK: delete the two `ALTER TABLE media_asset_aud ADD COLUMN` lines from V60.
- RED (expected): the update throws on flush —
  `org.postgresql.util.PSQLException: ERROR: column "process_attempts" of relation "media_asset_aud" does not exist`,
  surfacing as a `JdbcSQLIntegrityConstraintViolation`/`DataIntegrityViolationException` from
  `saveAndFlush`.
- *This is the single highest-value criterion in Task 1* — it is the exact failure V59's header
  warns about, and it is invisible to any test that only *inserts*.

**AC-1.5 — Schema version advances.**
- PASS: `scripts/docs-freshness.sh` computes `schema_version: 60` (asserted in Task 6's reconcile).
- BREAK: rename V60 to V59a. RED: Flyway rejects the non-numeric version / `docs-freshness`
  computes `59` and the committed manifest mismatches.
  </acceptance_criteria>
  <done>V60 applies fresh and out-of-order, both columns exist on `media_asset` and `media_asset_aud`, the outbox asset index exists, no UPDATE appears in the migration, and the Envers trap is proven RED by removing the aud columns.</done>
</task>

<task type="auto">
  <name>Task 2: MediaPendingReaper rewrite — dispatch-evidence gate, consumer-liveness suspension, bounded re-drive, zero delete capability</name>
  <files>
core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java,
core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java,
core-java/src/test/java/uk/jtoye/core/media/MediaPendingReaperTest.java
  </files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java (the whole file — every line changes)
    - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java lines 65-94, 119-149 (ObjectProvider<MeterRegistry> pattern, per-tenant transaction shape)
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java lines 190-228 (reprocessFailed — the outbox re-enqueue + serialize() shape the re-drive reuses)
    - core-java/src/test/java/uk/jtoye/core/media/MediaPendingReaperTest.java (the existing 2 tests; `staleOrphanReapedToFailed` asserts the delete and must be REPLACED — record that deliberately in the SUMMARY, it is not an accidental deletion)
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java lines 341-370 (MEDIA_EVENTS_QUEUE constant, DLX wiring)
  </read_first>
  <action>
Rewrite `MediaPendingReaper`:

- **Remove the `StorageService` constructor parameter and field.** The class must have no path to
  object deletion (D-02).
- Add `ObjectProvider<AmqpAdmin>` and `ObjectProvider<MeterRegistry>` constructor params, plus
  `MediaAssetService` for the re-drive enqueue.
- New tick shape:
  1. `DispatchLiveness live = probeConsumers();` → `SUSPEND` when the admin bean is absent, the
     call throws, `getQueueInfo` returns `null`, or `consumerCount == 0`. On `SUSPEND`: log
     `event=media_reaper_suspended reason=<...>`, increment `media.reaper.suspended`, **return**
     without touching a single tenant. Fail CLOSED (D-05).
  2. Per tenant, in one `TransactionTemplate` + pinned GUC: `findStalePending(cutoff)` → if empty,
     return; else `findLatestDispatchStateForAssets(ids)` → classify each asset:
     - no row / `PENDING` / (`FAILED` and `poison=false`) → **skip**, increment
       `media.reaper.undispatched_skipped`, log at DEBUG with the asset id and the reason.
     - `SENT` and `processAttempts < maxProcessAttempts` → `mediaAssetService.enqueueRedrive(asset)`
       (increments `processAttempts`, inserts a fresh outbox row **in this same transaction**),
       increment `media.reaper.redriven`.
     - `SENT` and budget exhausted, **or** `poison=true` → `setStatus(FAILED)` +
       `setFailureReason(...)`. **No object delete, no `quarantineExpiresAt` change.** Increment
       `media.reaper.stalled_failed`.
  3. Two distinct vendor-visible reasons, both re-drivable (they must differ so support can tell
     the cases apart from the UI alone):
     - budget: `"Image processing did not complete after N attempts. Your original upload is kept — press Re-process, or upload a new image."`
     - poison: `"This upload could not be queued for processing. Your original upload is kept — press Re-process, or upload a new image."`
- Keep the existing per-tenant `catch (Exception e) { log.error(...); }` continue-on-failure loop
  and the `listTenantIds()` / `pinTenantGuc` helpers verbatim.
- Class javadoc must state: what the reaper may and may not do, that the *absence* of dispatch
  evidence is treated as "not dispatched" (fail closed) and why, that byte reclamation now belongs
  exclusively to `MediaQuarantineRetentionSweep`, and that the pre-Phase-27 behaviour destroyed
  user data during a broker outage.

Add `MediaAssetService.enqueueRedrive(MediaAsset asset)`: increment `processAttempts`,
`saveAndFlush`, insert `new MediaEventOutbox(tenantId, assetId, serialize(new MediaProcessingEvent(...)))`.
Reuses the existing private `serialize` (`MediaAssetService.java:222-228`).

Add the F-4 guard test (below) so a future outbox purge cannot silently re-open this P0.
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.MediaPendingReaperTest' 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>

**AC-2.1 — The reaper is structurally incapable of deleting an object.**
- PASS: `MediaPendingReaperTest#reaperHasNoStorageDependency` asserts, by reflection,
  `Arrays.stream(MediaPendingReaper.class.getDeclaredFields()).noneMatch(f -> f.getType() == StorageService.class)`
  **and** that no declared constructor has a `StorageService` parameter.
- BREAK: re-add `private final StorageService storageService;` and the constructor parameter.
- RED (expected): `AssertionError: Expecting no field of type StorageService but found storageService`.
- *Explicitly NOT `grep -c deleteByKey MediaPendingReaper.java == 0`* — that is the "already-0
  grep" shape from CLAUDE.md and it also passes on an empty/renamed file. The reflection form
  asserts about the loaded class.

**AC-2.2 — An undispatched stall is not touched (the P0 regression test).**
- PASS: `MediaPendingReaperTest#undispatchedStallIsNeverTouched` — stub `findStalePending` to
  return one 30-minute-old PENDING asset, stub `findLatestDispatchStateForAssets` to return
  `{PENDING, poison=false}`; run; assert `asset.getStatus() == PENDING`,
  `asset.getProcessAttempts() == 0`, and `verifyNoInteractions(mediaAssetService)`.
  Sibling `#retriableFailedOutboxStallIsNeverTouched` with `{FAILED, poison=false}`.
  Sibling `#absentOutboxRowIsNeverReaped` with an empty dispatch-state result (D-01 fail-closed).
- BREAK: delete the dispatch-evidence classification and reap on status alone (i.e. restore the
  pre-Phase-27 predicate).
- RED (expected): `expected: PENDING but was: FAILED` on all three.
- **Non-vacuity is provable historically:** run these three tests against the current tree with
  `git stash` on the main-source change — they are RED before the fix. Record that output. A test
  that has never been red on the unfixed tree is not a regression test.

**AC-2.3 — A dispatched stall within budget is re-driven, not failed.**
- PASS: `#dispatchedStallWithinBudgetIsRedriven` — dispatch state `{SENT}`, `processAttempts=0`,
  budget 3 → assert `verify(mediaAssetService).enqueueRedrive(asset)` and status still `PENDING`.
- BREAK: set `maxProcessAttempts` to `0` in the test's `MediaProperties`.
- RED (expected): `Wanted but not invoked: mediaAssetService.enqueueRedrive(...)`, and the status
  assertion fails with `expected: PENDING but was: FAILED`.

**AC-2.4 — A dispatched stall over budget fails WITHOUT losing bytes.**
- PASS: `#dispatchedStallOverBudgetFailsButRetainsBytes` — `{SENT}`, `processAttempts=3`, budget 3
  → status `FAILED`, `failureReason` contains `Re-process`, and
  `assertThat(asset.getQuarantineExpiresAt()).isNotNull()` (the marker is untouched, so the bytes
  are still claimed). This test **replaces** the existing `staleOrphanReapedToFailed`.
- BREAK: add `asset.setQuarantineExpiresAt(null);` next to the FAILED flip.
- RED (expected): `Expecting actual not to be null`.

**AC-2.5 — A poisoned outbox row fails immediately, still retaining bytes.**
- PASS: `#poisonedOutboxStallFailsImmediatelyRetainingBytes` — `{FAILED, poison=true}`,
  `processAttempts=0` → status `FAILED`, `enqueueRedrive` never called, `quarantineExpiresAt`
  not null, reason differs from the budget reason.
- BREAK: classify `poison=true` alongside the other `FAILED` rows (i.e. skip it).
- RED (expected): `expected: FAILED but was: PENDING`.

**AC-2.6 — Zero consumers suspends the entire tick.**
- PASS: `#zeroConsumersSuspendsTheSweep` — `AmqpAdmin.getQueueInfo("media.process")` returns a
  `QueueInformation` with `consumerCount = 0`; assert
  `verify(mediaAssetRepository, never()).findStalePending(any())`.
- BREAK: change the guard to `consumerCount < 0`.
- RED (expected): `mediaAssetRepository.findStalePending(); Never wanted here, but invoked`.

**AC-2.7 — Unreadable queue state suspends the tick (fails CLOSED, VOID ≠ clean).**
- PASS: three sibling assertions in `#unreadableQueueStateSuspendsTheSweep` —
  (a) `getQueueInfo` returns `null`; (b) `getQueueInfo` throws `AmqpConnectException`;
  (c) the `ObjectProvider<AmqpAdmin>` yields no bean. Each asserts
  `verify(mediaAssetRepository, never()).findStalePending(any())`.
- BREAK: change the null branch to `if (info == null) return DispatchLiveness.ALIVE;`.
- RED (expected): arm (a) fails with `findStalePending(); Never wanted here, but invoked`, while
  (b) and (c) still pass — which is itself the proof the three arms are independent.
- *This is the fail-open shape CLAUDE.md names.* Each arm must be broken and recorded separately;
  breaking one and observing "the test failed" does not prove the other two can fail.

**AC-2.8 — F-4 guard: an outbox purge must not orphan a PENDING asset.**
- PASS: `#noOutboxPurgeExistsForPendingAssets` — assert, by reflection over
  `MediaEventOutboxRepository`, that no declared method is annotated `@Modifying` with a query
  containing `DELETE`; **or**, once a purge exists, that its query carries a
  `NOT EXISTS (… media_asset … status = 'PENDING' …)` predicate.
- BREAK: add `@Modifying @Query(value="DELETE FROM media_event_outbox WHERE status='SENT'", nativeQuery=true) int purgeSent();`
  to the repository.
- RED (expected): `AssertionError: media_event_outbox purge 'purgeSent' would delete dispatch evidence for PENDING assets — the Phase 27 stall gate reads it`.
- *This criterion is the one that keeps the fix alive after this plan ships.* Without it, the
  obvious future "prune the outbox" housekeeping silently restores the P0.
  </acceptance_criteria>
  <done>The reaper cannot delete an object, skips every undispatched stall, re-drives within budget, fails over budget while retaining bytes, and suspends on any unreadable/consumer-less queue state — each proven RED by its own break, and AC-2.2 additionally proven RED against the pre-fix tree.</done>
</task>

<task type="auto">
  <name>Task 3: MediaQuarantineRetentionSweep — the bounded-growth backstop — plus the four config keys and the accept/worker marker lifecycle</name>
  <files>
core-java/src/main/java/uk/jtoye/core/media/MediaQuarantineRetentionSweep.java,
core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java,
core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java,
core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java,
core-java/src/main/resources/application.yml,
core-java/src/test/java/uk/jtoye/core/media/MediaQuarantineRetentionSweepTest.java
  </files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookRetentionCleanup.java (the whole file — the sweep is a structural clone)
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java lines 149-179 and 190-220 (both places a quarantine object is PUT — both must stamp the marker)
    - core-java/src/main/java/uk/jtoye/core/media/MediaProcessingWorker.java lines 123-182 and 233-245 (the success delete at :177 and fail() at :239-245 — D-07 splits the latter)
    - core-java/src/main/resources/application.yml lines 196-221 (the jtoye.media block and its comment conventions)
    - k8s/scripts/check-env-contract.sh (the local-only word list the four new keys must not trip)
  </read_first>
  <action>
**Marker lifecycle (must be exact — the whole design hangs off it):**
- `acceptQuarantineAndQueue` (`:156` region) and `reprocessFailed` (`:198` region): immediately
  after the `storageService.putBytes(objectKey, …)` call, set
  `asset.setQuarantineExpiresAt(OffsetDateTime.now().plusNanos(quarantineRetentionMs * 1_000_000L))`.
- `MediaProcessingWorker.process` success path: set `asset.setQuarantineExpiresAt(null)`
  **alongside** `asset.setStatus(ACTIVE)` at `:157-162` — i.e. **before** the `saveAndFlush` at
  `:169`, because `placeOnActive` runs a `@Modifying(clearAutomatically = true)` repoint that
  discards a later dirty update (the existing comment at `:167-168` says exactly this).
- `MediaProcessingWorker.fail(...)`: split per D-07 into `failAndDiscard(...)` (validation veto —
  keeps the existing `deleteByKey` **and** NULLs the marker) and `failRetainingBytes(...)` (the
  read-failure path at `:129-133` — no delete, marker untouched).

**New `MediaProperties` keys** (hand-written accessors, javadoc naming the defect):
`maxProcessAttempts = 3`, `quarantineRetentionMs = 259_200_000` (72 h, D-08),
`retentionIntervalMs = 3_600_000`. Plus expose the existing `reaperGraceMs` in YAML (F-1).

**`application.yml`** under `jtoye.media` (comment block in the house voice, naming the defect):
```yaml
    reaper-grace-ms:          ${MEDIA_REAPER_GRACE_MS:900000}
    max-process-attempts:     ${MEDIA_MAX_PROCESS_ATTEMPTS:3}
    quarantine-retention-ms:  ${MEDIA_QUARANTINE_RETENTION_MS:259200000}
    retention-interval-ms:    ${MEDIA_RETENTION_INTERVAL_MS:3600000}
```

**New `MediaQuarantineRetentionSweep`** — a structural clone of `WebhookRetentionCleanup`:
`@Scheduled(fixedDelayString = "${jtoye.media.retention-interval-ms:3600000}")`, per-tenant,
own transaction, `TenantContext` + GUC pinned, continue-on-failure. Per tenant:
`findReclaimableQuarantine(now, now − retention)`, then for each row apply the **second**
independent guard `objectKey != null && objectKey.contains("/quarantine/")` before
`storageService.deleteByKey(objectKey)`, and set `quarantineExpiresAt = null`. Collect the keys and
perform the deletes **after** `transactionTemplate.execute` returns (so a rolled-back tenant batch
never leaves deleted objects behind — the §3(b) lesson, applied to the class that legitimately
does delete). Micrometer counter `media.quarantine.reclaimed`. Log
`event=media_quarantine_reclaimed tenant=… reclaimed=… olderThan=…`.

Class javadoc must state that this is the **only** remaining component that reclaims quarantine
bytes on a timeout-class path, that it is deliberately **not** gated on dispatch evidence or
consumer liveness (it is the unconditional backstop that keeps bucket growth bounded even when
every other gate is suspended), and that the two guards are independent by design.
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanTest :core-java:test --tests 'uk.jtoye.core.media.MediaQuarantineRetentionSweepTest' && bash k8s/scripts/check-env-contract.sh; echo "env-contract exit=$?"</automated>
  </verify>
  <acceptance_criteria>

**AC-3.1 — Expired quarantine bytes ARE reclaimed (the Phase 24 good, preserved).**
- PASS: `#expiredQuarantineIsReclaimedAndMarkerCleared` — a FAILED asset with
  `objectKey = "<t>/quarantine/abc.jpg"` and `quarantineExpiresAt` 1 h in the past →
  `verify(storageService).deleteByKey("<t>/quarantine/abc.jpg")` and
  `assertThat(asset.getQuarantineExpiresAt()).isNull()`.
- BREAK: comment out the `storageService.deleteByKey(key)` call in the sweep.
- RED (expected): `Wanted but not invoked: storageService.deleteByKey("<t>/quarantine/abc.jpg"); Actually, there were zero interactions with this mock.`
- *This criterion is the Incremental Betterment receipt.* If it cannot be made to fail, the plan
  has not proven it kept the good it claims to keep.

**AC-3.2 — Guard 1 (status) and Guard 2 (path) each fail independently.**
- PASS (a) `#activeAssetIsNeverReclaimed`: an ACTIVE asset with `objectKey = "<t>/media/x.webp"`
  **and** a past `quarantineExpiresAt` (deliberately inconsistent state) →
  `verify(storageService, never()).deleteByKey(anyString())`.
- BREAK (a): remove `status <> ACTIVE` from the repository predicate only.
- RED (a): `storageService.deleteByKey("<t>/media/x.webp"); Never wanted here, but invoked`.
- PASS (b) `#nonQuarantinePathIsNeverReclaimed`: a FAILED asset with a V53-backfilled key
  `"<t>/products/<pid>/<uuid>.jpg"` and a past `quarantineExpiresAt` →
  `verify(storageService, never()).deleteByKey(anyString())`.
- BREAK (b): remove the `contains("/quarantine/")` guard in the sweep only.
- RED (b): `storageService.deleteByKey("<t>/products/…"); Never wanted here, but invoked`.
- *Both breaks must be run separately and both outputs recorded.* Breaking only one and observing
  that the suite stays green is the exact defence-in-depth trap that makes a guard unfalsifiable.

**AC-3.3 — The four new config keys do not trip the Phase 26 env-contract gate.**
- PASS: `bash k8s/scripts/check-env-contract.sh` exits `0` after the `application.yml` edit, and
  its printed placeholder count increases by exactly 4.
- BREAK: temporarily change one new key's default to `${MEDIA_QUARANTINE_RETENTION_MS:localhost}`.
- RED (expected): exit `1` naming `MEDIA_QUARANTINE_RETENTION_MS` and its `localhost` default as an
  unsupplied local-only-default env (direction (b)).
- *Not vacuous because* the placeholder-count delta fails if the keys were added to a file the
  gate does not scan, and the break proves the gate actually reads the new lines.

**AC-3.4 — The marker is stamped on both quarantine PUT paths and cleared on worker success.**
- PASS: `MediaDurabilityIntegrationTest` asserts, after a real accept, that
  `quarantine_expires_at` is non-null and ≈ `created_at + 72 h` (±1 min); and after a successful
  worker run, that it is `NULL` while `status='ACTIVE'`.
- BREAK: move `setQuarantineExpiresAt(null)` in the worker to *after* the `saveAndFlush` at `:169`
  (i.e. into the `placeOnActive` shadow).
- RED (expected): `expected: null but was: 2026-07-29T…` — the `@Modifying(clearAutomatically=true)`
  in the repoint discards the dirty update. **This is the pre-existing trap the comment at
  `MediaProcessingWorker.java:167-168` documents**, and it is worth proving it still bites.

**AC-3.5 — D-07: read-failure retains bytes, validation-veto still discards them.**
- PASS: two assertions — a `StorageService.getBytes` throw leaves the object undeleted and
  `quarantineExpiresAt` non-null; a `DecompressionBombException` still calls `deleteByKey` and
  NULLs the marker.
- BREAK: point the read-failure path back at `failAndDiscard`.
- RED (expected): `storageService.deleteByKey(...); Never wanted here, but invoked` on the first
  assertion, while the second still passes — proving the two paths are genuinely distinct.
  </acceptance_criteria>
  <done>Quarantine growth is still bounded on a declared 72 h horizon; both sweep guards are individually proven RED; the four config keys pass the env-contract gate and the gate is proven capable of rejecting them; the marker lifecycle is proven on all four transition points.</done>
</task>

<task type="auto">
  <name>Task 4: The re-drive surface — MediaAssetService.redriveFromQuarantine + POST /api/v1/media/{assetId}/reprocess + the redrivable DTO bit</name>
  <files>
core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java,
core-java/src/main/java/uk/jtoye/core/media/MediaAssetDto.java,
core-java/src/main/java/uk/jtoye/core/media/MediaController.java,
core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
  </files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/media/MediaController.java (the whole file — `keep` at :69-84 is the authorization template)
    - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java lines 339-386 (dismissFlag + resolveOwningShopId — the WR-03 shop-gate-after-RLS ordering that avoids a 403 oracle)
    - core-java/src/main/java/uk/jtoye/core/media/MediaUploadController.java lines 78-140 (the Idempotency-Key + 202 + RFC 7807 conventions, and the "service stamps 201, controller owns 202" note)
    - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java (the `execute` signature)
    - core-java/src/main/java/uk/jtoye/core/exception/GlobalExceptionHandler.java (the RFC 7807 ProblemDetail mapping to extend for the new "no retained bytes" condition)
  </read_first>
  <action>
`MediaAssetService.redriveFromQuarantine(UUID assetId)`:
1. `findById` (RLS-scoped) → `ResourceNotFoundException` (404) if absent — **no cross-tenant oracle**.
2. `shopAccessService.require(resolveOwningShopId(asset), ShopRole.SHOP_MANAGER)` — after (1),
   exactly as `dismissFlag` does.
3. Reject with a typed RFC 7807 error when `asset.getQuarantineExpiresAt() == null` (bytes gone:
   the worker discarded them on a validation veto, or the retention sweep reclaimed them) or when
   `status == ACTIVE` (nothing to re-drive). Use a dedicated exception mapped to **409 Conflict**
   with a stable `type` URI and a `code` extension (`media.quarantine_not_retained` /
   `media.already_active`) — prose-only errors do not satisfy the agent-readiness contract.
4. Otherwise: `status → PENDING`, `processAttempts → 0`, `failureReason → null`, `flagged → false`,
   `saveAndFlush`, insert a fresh `MediaEventOutbox` row in the same transaction. Return
   `MediaAcceptDto`.

`MediaController`: add `POST /{assetId}/reprocess`, `@PreAuthorize("hasAuthority('SCOPE_catalog:write')")`,
`@RequestHeader("Idempotency-Key")`, wrapped in
`idempotencyService.execute("media.reprocess", idemKey, new RedriveRequest(assetId), MediaAcceptDto.class, () -> …)`,
returning `ResponseEntity.status(ACCEPTED)`. Full `@Operation`/`@ApiResponses` annotations
(202/400/403/404/409/422) — the OpenAPI contract must match the live responses.

`MediaAssetDto`: add `boolean redrivable` as the **last** record component, derived in
`MediaAssetService.toDto` as `asset.getQuarantineExpiresAt() != null`. Update the `from(...)`
factory and its javadoc. Adding a component to a record is a source-compatible change only for
callers using the canonical constructor — check `MediaAssetDtoMappingTest` and every `new
MediaAssetDto(...)` site and update them.

Also update `MediaController`'s class javadoc: "Replace is deliberately NOT an endpoint here"
(`:37-39`) is now only half true — Re-process **is** one, and Replace-with-different-bytes is
still the upload path. Leaving that comment stale would be its own small defect.
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --tests 'uk.jtoye.core.media.MediaRedriveControllerTest' 2>&1 | tail -20</automated>
  </verify>
  <acceptance_criteria>

**AC-4.1 — Happy path: 202 and the asset returns to PENDING with a fresh outbox row.**
- PASS: `#redriveReturns202AndReQueues` — a FAILED asset with a future `quarantine_expires_at` →
  HTTP 202, body `status="PENDING"`, DB `process_attempts=0`, `failure_reason IS NULL`, and
  `SELECT count(*) FROM media_event_outbox WHERE asset_id=? AND status='PENDING'` = 1.
- BREAK: remove the `mediaEventOutboxRepository.save(...)` line from `redriveFromQuarantine`.
- RED (expected): `expected: 1 but was: 0` on the outbox count. *The status assertion alone would
  still pass* — which is why the outbox count is the load-bearing half.

**AC-4.2 — Idempotent replay returns the original response, not a second enqueue.**
- PASS: `#replayWithSameKeyReturnsOriginalAndDoesNotDoubleEnqueue` — two POSTs with the same
  `Idempotency-Key` → both 202 with the same `assetId`, and the outbox count is still 1.
- BREAK: bypass `idempotencyService.execute` and call the service directly.
- RED (expected): `expected: 1 but was: 2`.

**AC-4.3 — A foreign-tenant asset is 404, never 403 (no oracle).**
- PASS: `#foreignTenantAssetIs404NotAnOracle` — seed the asset under tenant B, call as tenant A →
  404, and the response body contains no shop or product identifier.
- BREAK: move `shopAccessService.require(...)` **above** the `findById`.
- RED (expected): `expected: 404 but was: 403` — the ordering leak the WR-03 comment at
  `MediaAssetService.java:352-354` exists to prevent.

**AC-4.4 — A SHOP_MANAGER of a different shop in the same tenant is 403.**
- PASS: `#nonManagerOfOwningShopIs403`.
- BREAK: delete the `shopAccessService.require(...)` call.
- RED (expected): `expected: 403 but was: 202`.
- *Note the interaction with the recorded `trap_scope_gate_integrationtest_regression`:* a new
  `@PreAuthorize` gate has repeatedly broken **existing** integrationTests in this repo. Task 6's
  full-suite run is mandatory, not optional.

**AC-4.5 — No retained bytes → a typed RFC 7807 409, not a 500 and not a silent 202.**
- PASS: `#assetWithNoRetainedBytesIsTyped409` — `quarantine_expires_at IS NULL` → 409,
  `Content-Type: application/problem+json`, body carries a stable `type` URI and
  `code = "media.quarantine_not_retained"`.
- BREAK: delete the null check.
- RED (expected): `expected: 409 but was: 202`, and the asset is left PENDING with an enqueued
  event that the worker will fail on a missing object — i.e. the break also demonstrates the
  concrete harm.

**AC-4.6 — The OpenAPI snapshot matches the live contract.**
- PASS: `./gradlew :core-java:updateOpenApiSnapshot` then `git diff --stat docs/api/openapi-snapshot.json`
  shows the new path; `OpenApiSnapshotTest` in check mode (inside `integrationTest`) is green.
- BREAK: revert `docs/api/openapi-snapshot.json` while keeping the controller change.
- RED (expected): `OpenApiSnapshotTest` fails with a diff naming `/api/v1/media/{assetId}/reprocess`.
- *This is a genuinely falsifiable pre-existing gate*; state that it is pre-existing rather than
  claiming it as new evidence.

**AC-4.7 — `redrivable` reaches the wire.**
- PASS: `GET /api/v1/media/review-queue` returns `redrivable: true` for a reaper-failed asset and
  `redrivable: false` for a worker-vetoed one.
- BREAK: hardcode `redrivable` to `false` in `toDto`.
- RED (expected): `expected: true but was: false` on the first assertion only — proving the two
  fixtures genuinely differ rather than both being trivially false.
  </acceptance_criteria>
  <done>A FAILED-with-retained-bytes asset is recoverable in one idempotent, shop-scoped, RFC-7807-typed call; every authorization and precondition branch is proven RED by its own break; the OpenAPI snapshot matches.</done>
</task>

<task type="auto">
  <name>Task 5: Vendor affordance — surface Re-process on the FAILED card and in the review queue</name>
  <files>
frontend/types/api.ts,
frontend/lib/media-api.ts,
frontend/components/ui/asset-image.tsx,
frontend/components/ui/__tests__/asset-image.test.tsx,
frontend/components/dashboard/media/ReviewQueue.tsx,
frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx
  </files>
  <read_first>
    - frontend/components/ui/asset-image.tsx lines 1-110 (the PENDING/ACTIVE/FAILED state machine and the existing "Re-upload" control at :79-99)
    - frontend/lib/media-api.ts (the typed client; the `keepAsset` shape to mirror, and the "Replace is deliberately NOT a media endpoint" comment that now needs updating)
    - frontend/types/api.ts lines 69-78 (the MediaAsset interface)
    - frontend/components/dashboard/media/__tests__/ReviewQueue.test.tsx (existing jest conventions)
  </read_first>
  <action>
- `frontend/types/api.ts`: add `redrivable: boolean` to `MediaAsset`.
- `frontend/lib/media-api.ts`: add `reprocessAsset(assetId)` → `POST ${BASE}/${assetId}/reprocess`
  with a generated `Idempotency-Key` header (mirror however `ImageUploader` generates one — reuse,
  do not re-implement). Update the stale "Replace is deliberately NOT a media endpoint" comment.
- `asset-image.tsx` FAILED branch (`:79-99`): keep the existing **Re-upload** control unchanged
  (Incremental Betterment — it is the working good) and add **Re-process** as a *secondary* action
  rendered **only** when `redrivable`. Copy: "Re-process" with helper text "Your original upload is
  still saved." When `redrivable === false`, the card is byte-for-byte what it renders today.
- `ReviewQueue.tsx`: same secondary action per FAILED row, optimistic removal on success, error
  toast on the 409.
- Mobile-first: the two actions must not overflow at 320 px — stack, do not shrink.
  </action>
  <verify>
    <automated>cd frontend && npx jest --ci components/ui/__tests__/asset-image.test.tsx components/dashboard/media/__tests__/ReviewQueue.test.tsx 2>&1 | tail -20 && npm run build 2>&1 | tail -10</automated>
  </verify>
  <acceptance_criteria>

**AC-5.1 — Re-process appears only when the bytes are retained.**
- PASS: `asset-image.test.tsx#showsReprocessWhenRedrivable` renders
  `status="FAILED" redrivable={true}` → `screen.getByRole("button", {name: /re-process/i})` resolves;
  `#hidesReprocessWhenNotRedrivable` renders `redrivable={false}` → `queryByRole` is `null`.
- BREAK: render the button unconditionally.
- RED (expected): the second test fails —
  `expect(received).toBeNull() … Received: <button>Re-process</button>`.
- *Both directions are required.* A single "it renders" test passes on an unconditional button and
  is therefore incapable of detecting the defect it claims to guard.

**AC-5.2 — The existing Re-upload control is not displaced.**
- PASS: `#retainsReuploadAlongsideReprocess` asserts **both** buttons are present when
  `redrivable`, and that `onReupload` still fires on the Re-upload click.
- BREAK: replace the Re-upload button with Re-process.
- RED (expected): `Unable to find an accessible element with the role "button" and name /re-upload/i`.
- *This is the Incremental Betterment receipt for the UI half* — regression by omission is a defect
  even with a green suite.

**AC-5.3 — Typecheck.**
- PASS: `cd frontend && npm run build` exits `0` (the `tsc` gate — **jest does not type-check**).
- BREAK: pass `redrivable` as a string in one call site.
- RED (expected): `Type 'string' is not assignable to type 'boolean'` and a non-zero exit.
- Record `npx tsc --noEmit`'s count as **unchanged vs. the pre-change baseline** (currently 366,
  all jest-dom matcher typings in test files that `next build` never checks). Asserting exit 0
  there would make the gate permanently red and therefore permanently ignored.

**AC-5.4 — 320 px layout.**
- PASS: a Playwright/`webapp-testing` screenshot at 320 px shows both actions fully visible;
  `document.documentElement.scrollWidth <= 320`.
- BREAK: force the action row to `flex-nowrap` with fixed widths.
- RED (expected): `scrollWidth` 380-ish > 320.
- If the running stack is unavailable, mark this criterion **DEFERRED with the reason**, do not
  silently tick it.
  </acceptance_criteria>
  <done>Re-process is offered exactly when it can work, Re-upload is untouched, the tsc gate is green, and the "shown only when redrivable" claim is proven by a test that fails on an unconditional button.</done>
</task>

<task type="auto">
  <name>Task 6: Phase-gate reconcile — metrics manifest, full regression sweep, runtime parity</name>
  <files>docs/metrics.json, docs/api/openapi-snapshot.json</files>
  <read_first>
    - scripts/docs-freshness.sh (what is and is not counted: Java `@Test\b` under core-java/src/test ONLY — src/integrationTest does not exist, the tag splits the same source set; jest `\b(it|test)\(` literal-token counting)
    - docs/metrics.json (the current committed baseline)
    - .planning/phases/24-image-architecture-cow-assets-safe-upload-pipeline/24-06-PLAN.md Task 3 (the reconcile task shape)
  </read_first>
  <action>
Run `scripts/docs-freshness.sh --write`, commit the manifest, and update any README/PROJECT.md
count that references the total. Run `./gradlew :core-java:updateOpenApiSnapshot` and commit.
Then run the **full** suite — not just this plan's tests.

Planned deltas (state ACTUALS in the SUMMARY; any deviation must be explained, not absorbed):

| metric | from | planned to | source |
|---|---|---|---|
| `java_test_methods` | 1157 | 1181 (+24) | +7 MediaPendingReaperTest, +5 MediaQuarantineRetentionSweepTest, +7 MediaDurabilityIntegrationTest, +5 MediaRedriveControllerTest |
| `java_test_files` | 203 | 206 (+3) | 3 new test classes |
| `schema_version` | 59 | 60 | V60 |
| `jest_blocks` | 412 | 415 (+3) | +2 asset-image, +1 ReviewQueue |
| `total_logical_invocations` | 1736 | 1763 | +24 Java +3 Jest |
  </action>
  <verify>
    <automated>./gradlew :core-java:cleanTest :core-java:test && ./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest && (cd frontend && npm run build && npx jest --ci) && bash scripts/docs-freshness.sh && bash k8s/scripts/check-env-contract.sh && bash k8s/scripts/check-render-invariants.sh</automated>
  </verify>
  <acceptance_criteria>

**AC-6.1 — The manifest matches source and the delta is the enumerated one.**
- PASS: `scripts/docs-freshness.sh` exits 0; `git diff docs/metrics.json` shows exactly the table
  above (or a deviation explained in the SUMMARY).
- BREAK: add one `@Test` method to any file under `core-java/src/test/` without re-running `--write`.
- RED (expected): exit 1 with
  `ERROR: documentation metrics are stale (docs/metrics.json != source reality)` and the two
  blocks printed side by side.
- *Watch `trap_docs_freshness_block_counter`:* the gate greps the **literal** tokens `@Test`,
  `it(`, `test(`. A table-driven `@ParameterizedTest` contributes **0**, and the token `test(`
  appearing in prose creates a phantom. Enumerate what you added by hand and compare against the
  computed delta; do not trust the number alone.

**AC-6.2 — The full suite is green and was actually executed.**
- PASS: `:core-java:cleanTest :core-java:test` and `:core-java:cleanIntegrationTest :core-java:integrationTest`
  both `BUILD SUCCESSFUL`; record class/test/fail/err/skip counts read from
  **`core-java/build-local/test-results/`**, plus that directory's mtime, in the SUMMARY.
- BREAK: run `./gradlew :core-java:test` **without** `cleanTest` twice in a row.
- RED (expected): the second run prints `> Task :core-java:test UP-TO-DATE` and `BUILD SUCCESSFUL`
  while executing **zero** tests, and `test-results/` mtime does not advance. Record this output —
  it is the proof the `cleanTest` in the pass command is load-bearing rather than decorative.
- **Never read `core-java/build/test-results/`.** It is a stale 2025-12-27 artifact that reports
  three failures; the live directory is `build-local` (`core-java/build.gradle.kts:15`).
- Expected baseline to beat: unit ≈ 104 classes / 767 tests, integration ≈ 98 classes / 392 tests
  (Phase 26 close-out figures). A *drop* in class count is a red flag, not a pass.

**AC-6.3 — Go is asserted not-run, not assumed.**
- PASS: `git diff --name-only <phase-base>..HEAD -- '*.go' | wc -l` returns `0`, so the Go suite is
  correctly skipped.
- BREAK: `touch edge-go/internal/core/client.go` and re-run.
- RED (expected): returns `1`, requiring the Go suite to run.

**AC-6.4 — The branch is not behind its base (runtime-parity half b).**
- PASS: `bash scripts/check-branch-behind-base.sh` exits 0 — `git log HEAD..origin/<default>` is
  empty or a merge is recorded.
- BREAK: `git reset --hard HEAD~1` on a branch whose base has moved.
- RED (expected): exit 1 naming the commits the branch is missing.

**AC-6.5 — The delivered runtime matches the branch (runtime-parity half a).**
- PASS: after rebuilding, `bash scripts/check-runtime-freshness.sh` exits 0; and the value is read
  **out of the running artifact**:
  `docker exec <core-java> unzip -p /app/app.jar BOOT-INF/classes/application.yml | grep -c 'quarantine-retention-ms'` returns `1`.
- BREAK: `docker compose start core-java` **without** a rebuild after the `application.yml` change.
- RED (expected): the grep returns `0` from inside the jar (a filesystem `find` would misleadingly
  return 0 in *both* directions, which is why the read must come from inside the archive), and
  `check-runtime-freshness.sh` exits non-zero on the `.Metadata.LastTagTime` vs. newest-commit
  comparison.
- *This plan changes `application.yml`, which is exactly the file Phase 26 shipped stale.* If the
  stack is not running, this criterion is **VOID (exit 2), not clean** — record it as VOID.

**AC-6.6 — Regression criteria (pre-existing gates, not new evidence).**
- `RlsContractTest` green (V60 adds no table and no policy — this is a *regression* check and is
  **UNFALSIFIABLE by this plan**, since nothing here can make it red; recorded as such rather than
  claimed as proof).
- `MediaProcessingWorkerIntegrationTest`, `CowSafetyIntegrationTest`, `MediaDedupAttachIntegrationTest`,
  `MediaAssetOptimisticLockIntegrationTest`, `MediaUploadIdempotencyTest`, `GateStrictnessTest`,
  `MediaReviewQueueIntegrationTest`, `MediaKeepShopScopeIntegrationTest` all green — these cover the
  paths Tasks 3 and 4 edit and are the real regression surface.
  </acceptance_criteria>
  <done>The manifest matches source at the enumerated delta, both Java suites and the frontend gates are green with counts read from `build-local`, the branch is not behind base, and the running jar is proven to contain the new config key.</done>
</task>

</tasks>

---

<threat_model>

## Trust Boundaries

| Boundary | Description |
|---|---|
| vendor upload → quarantine object | Raw, unvalidated, possibly hostile bytes at rest in a bucket that is `mc anonymous set download` (F-3) |
| scheduled sweep → tenant data | Two `@Scheduled` components mutate/delete across every tenant with no request principal; only the pinned GUC keeps them tenant-scoped |
| RabbitMQ availability → user data durability | **The defect**: an infrastructure liveness property currently decides whether user data survives |
| re-drive endpoint → asset state machine | A new mutating, authenticated surface that can move an asset out of a terminal state |

## STRIDE Threat Register (ASVS L1)

| ID | Category | Component | Disposition | Mitigation |
|---|---|---|---|---|
| T-27-01 | Denial of Service (data destruction) | `MediaPendingReaper` | **mitigate** | ASVS V1.1/V7. The P0. D-01 gates the flip on durable dispatch evidence; D-02 removes the delete capability from the class entirely; D-05 suspends the tick when the consumer side cannot be shown alive. Proven by AC-2.1/2.2/2.6/2.7, each with its own break. |
| T-27-02 | Elevation of Privilege | `POST /media/{assetId}/reprocess` | **mitigate** | ASVS V4.1. `SCOPE_catalog:write` + VSA-02 `SHOP_MANAGER` on the resolved owning shop, gate applied **after** the RLS `findById` so a foreign asset is 404 not 403 (AC-4.3/4.4). Mirrors `dismissFlag`'s WR-03 fix exactly. |
| T-27-03 | Denial of Service | unbounded re-drive | **mitigate** | ASVS V11. `max-process-attempts` (default 3) bounds the automatic path; the manual path resets the budget but requires an authenticated SHOP_MANAGER and carries an `Idempotency-Key`, so a replay cannot storm the queue (AC-4.2). |
| T-27-04 | Information Disclosure | retained quarantine bytes in a public-read bucket | **accept, bounded** | ASVS V12. F-3 is pre-existing (Phase 24). This change lengthens the window from ~seconds to 72 h. Exposure is confirmation-of-possession only — the key is `<tenant>/quarantine/<sha256-of-the-raw-bytes>`, so deriving it requires already holding the exact file. Bounded by D-08's 72 h default and `MEDIA_QUARANTINE_RETENTION_MS`. **A separate non-public quarantine bucket must be filed as its own issue** — do not silently absorb it. |
| T-27-05 | Tampering | cross-tenant sweep writes | **mitigate** | ASVS V4.2. Every new query runs inside a `TransactionTemplate` with `TenantContext` + `set_config('app.current_tenant_id', …, true)` pinned, under `FORCE RLS`. Proven **under the downgraded NOSUPERUSER role** in `MediaDurabilityIntegrationTest#sweepIsTenantScopedUnderNosuperuser`, both directions (tenant A's rows are seen; tenant B's are not modified). A Testcontainers-superuser test would pass vacuously — RLS is bypassed for superusers. |
| T-27-06 | Tampering | future `media_event_outbox` purge | **mitigate** | The stall gate now *depends* on the outbox row surviving. AC-2.8 fails the build if a purge is added without a `PENDING`-asset exclusion. Without this guard, ordinary housekeeping silently restores the P0. |
| T-27-07 | Repudiation | silent stall | **mitigate** | ASVS V7.1. Four Micrometer counters (`media.reaper.suspended`, `.undispatched_skipped`, `.redriven`, `.stalled_failed`, `media.quarantine.reclaimed`) plus structured `event=` logs, so a suspended reaper is observable rather than indistinguishable from an idle one. |
| T-27-08 | Denial of Service | permanent suspension → unbounded bucket | **mitigate** | D-05 fails closed forever if `AmqpAdmin` never recovers. Survivable **only** because `MediaQuarantineRetentionSweep` is deliberately not gated on it (D-03), so growth stays bounded. Proven by AC-3.1. |
| T-27-SC | Tampering | supply chain | **n/a** | No new npm/Gradle/Go dependency. `AmqpAdmin`, Micrometer and Postgres `DISTINCT ON` are all already in the tree. |

</threat_model>

<quality_dimensions>

| Dimension | Applies | Disposition |
|---|---|---|
| **Web performance (mobile-first)** | **YES (narrow)** | Task 5 touches `asset-image.tsx`, which renders in every product grid. The change is one conditionally-rendered button — no new dependency, no new image, no bundle growth. Budget: no LCP/CLS/INP regression on `/dashboard/products` and `/dashboard/media/review` at a throttled mobile profile; AC-5.4 additionally pins the 320 px layout. Localhost-unthrottled measurement does not satisfy this. |
| **SEO / discoverability** | **N/A** | Every surface touched is authenticated vendor dashboard. No public/unauthenticated page, sitemap, or JSON-LD entity changes. |
| **AI agent-readiness** | **YES** | A new mutating endpoint. Contracted: `Idempotency-Key` (AC-4.2), RFC 7807 typed errors with stable `code` extensions rather than prose (AC-4.5), least-privilege `SCOPE_catalog:write` + `SHOP_MANAGER` (AC-4.4), OpenAPI snapshot matching the live response (AC-4.6). **MCP tool: deliberately out of scope** — re-drive is an operator-recovery action on a failed upload, not a catalog capability an agent should autonomously trigger; recorded here rather than silently omitted. |
| **Security** | **YES** | `<threat_model>` above; nine rows, one accepted-with-bound (T-27-04) and one explicitly deferred to its own issue. Routes through `/gsd-secure-phase` + `/gsd-code-review` as normal. |
| **Falsifiable evidence + runtime parity** | **YES — this is the plan's spine** | Every criterion above carries a PASS / BREAK / RED triple. Two criteria are labelled honestly rather than claimed: **AC-1.3** is a known-vacuous expected-0 grep, retained only because its fail direction is run and recorded, and it is explicitly *not* the evidence for D-09 (AC-1.1 is); **AC-6.6's `RlsContractTest` row is UNFALSIFIABLE by this plan** and is recorded as a regression check, not as proof. Runtime parity: AC-6.4 (branch vs. base) and AC-6.5 (running jar vs. tree, read from **inside** `app.jar` because a filesystem `find` returns a misleading 0) — mandatory here because this plan edits `application.yml`, the exact file Phase 26 shipped stale past four green gates. |

</quality_dimensions>

<verification>

```bash
# Java — cleanTest/cleanIntegrationTest are LOAD-BEARING (without them Gradle
# reports BUILD SUCCESSFUL while executing nothing).
./gradlew :core-java:cleanTest :core-java:test
./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest
# counts read from core-java/build-local/test-results/ — NEVER core-java/build/

# Frontend — npm run build IS the tsc gate; jest does not type-check.
cd frontend && npm run build && npx jest --ci && cd ..

# Manifests + standing gates
bash scripts/docs-freshness.sh
bash k8s/scripts/check-env-contract.sh
bash k8s/scripts/check-render-invariants.sh
bash scripts/check-branch-behind-base.sh

# Runtime parity (VOID = exit 2, not clean, when the stack is down)
bash scripts/check-runtime-freshness.sh
docker exec <core-java> unzip -p /app/app.jar BOOT-INF/classes/application.yml \
  | grep -c 'quarantine-retention-ms'   # must be 1
```

**Evidence protocol.** For every criterion, record BOTH directions' real output in the SUMMARY —
the pass and the RED produced by the stated break. A criterion recorded only passing is not
evidence. Where a break cannot produce a failure, label the criterion **UNFALSIFIABLE**, say so
explicitly, and replace it with a stronger form; never substitute silently and never report a
vacuous pass as satisfied.

**Additionally, and specific to this plan:** AC-2.2's three tests must be run against the
**pre-fix tree** (`git stash` the main-source changes) and shown RED there. This is the only
criterion in the plan whose non-vacuity can be demonstrated historically rather than by
mutation, and it is the one that proves the P0 is actually closed.

</verification>

<success_criteria>

1. `MediaPendingReaper` has no `StorageService` dependency and no path to object deletion —
   proven by reflection, not by grep.
2. A PENDING asset whose latest outbox row is `PENDING`, non-poison `FAILED`, or **absent** is left
   entirely untouched — the three tests proven RED against the pre-fix tree.
3. A dispatched stall is re-driven within budget and, at budget, flipped `FAILED` with
   `quarantine_expires_at` intact.
4. Zero consumers, a null `getQueueInfo`, a throwing `getQueueInfo`, and an absent `AmqpAdmin` each
   suspend the tick — all four arms individually proven RED.
5. Quarantine-bucket growth stays bounded via `MediaQuarantineRetentionSweep`, whose two guards
   (status, path) are individually proven RED.
6. A FAILED asset with retained bytes is recoverable through one idempotent, shop-scoped,
   RFC-7807-typed call, with the OpenAPI snapshot matching.
7. `redrivable` reaches the wire and gates a Re-process control that does **not** displace the
   existing Re-upload control.
8. V60 adds both columns to `media_asset` **and** `media_asset_aud`, contains no `UPDATE`, and the
   Envers trap is proven RED by removing the aud columns.
9. Both Java suites, the frontend `tsc` gate, jest, `docs-freshness`, and the k8s static gates are
   green, with counts read from `build-local`; the branch is not behind base; and the running jar
   is proven to carry the new config key.
10. Two criteria are recorded as honestly limited rather than claimed: AC-1.3 (known-vacuous,
    retained with its fail direction run) and AC-6.6's `RlsContractTest` row (UNFALSIFIABLE by this
    plan). T-27-04's separate-bucket remediation is filed as its own issue, not absorbed.

</success_criteria>

<output>
Create `.planning/phases/27-messaging-layer-hardening/27-01-SUMMARY.md` when done. Record:
the actual `docs/metrics.json` delta against the planned table; the pre-fix RED output for AC-2.2;
the RED output for every break in every task, including the four independent D-05 arms and the two
independent D-03 guards; the actual class/test counts from `build-local` with the directory mtime;
the `UP-TO-DATE`-with-zero-tests output that proves `cleanTest` is load-bearing; the in-jar
`application.yml` grep result; and the issue number filed for T-27-04.
</output>
