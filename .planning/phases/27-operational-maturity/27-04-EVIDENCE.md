# 27-04 — AC-10 evidence: tenant isolation under concurrency (T-27-01, #284)

**Status: SATISFIED, falsified in both directions.** Recorded 2026-07-28.

AC-10 is 27-04's load-bearing security proof, and it is the criterion the plan itself flagged as a
rewrite of an earlier unfalsifiable form. This file records what was actually measured, including
**two claims in the plan and one in the codebase that the measurement refutes**.

Test:
`core-java/src/test/java/uk/jtoye/core/media/MediaTenantIsolationUnderConcurrencyIntegrationTest.java`

---

## 1. The first written form of this test was itself unfalsifiable

The test was written, went green, and **three successive break arms were run without turning it
red**. It was therefore not evidence of anything, and was recorded as such rather than reported as a
pass (commit `f3f7440`).

The hypothesis recorded at that point was that `ALTER ROLE … NOSUPERUSER` does not reach Hikari's
already-established sessions, so the workers kept superuser attributes, bypassed FORCE RLS, and
assertion (a) passed vacuously.

**That hypothesis is REFUTED.** RLS *is* genuinely enforced on those pooled connections — and its
enforcement was itself the defect.

`MediaProcessingWorker.onMediaEvent` takes an early return when RLS hides the row
(`event=media_process_skipped reason=asset_not_visible`, a WARN and a `return`, **no throw**). So an
isolation failure surfaces only as a row left `PENDING`, and the only processing-sensitive assertion
in the test was the terminal `stillPending` count. That count ran on an **untransacted** connection
with no tenant GUC pinned. Under the downgraded role `current_tenant_id()` is NULL, the
`media_asset_tenant_policy` filters every row, and the count is structurally 0.

Nothing else in the test could move: the worker never rewrites `tenant_id`, so the ownership loop
held either way, and the no-throw early return kept the `failures` list empty.

### Measured, with a probe placed immediately after the role downgrade

At that instant all 12 seeded assets are provably `PENDING` and no worker has run, so the query must
read 12:

```
[VACUITY PROBE: all 12 seeded assets are PENDING and no worker has run]
expected: 12
 but was: 0
```

**Fix:** the read-back now goes through the tenant-pinned path (`visibleAssetsAsTenant`) and carries
`status` as well as `id`. The probe is kept as a **permanent non-vacuity guard on the instrument
itself** — before any worker runs, the read-back must SEE `PER_TENANT` PENDING rows per tenant, so a
later "nothing is PENDING" reading cannot be blindness. Commit `8c2a253`.

---

## 2. The arm matrix — all four arms run on the real tree

| arm | `TenantContext` | explicit `set_config` | result |
|---|---|---|---|
| pass | correct | present | **GREEN** |
| 1 — *the break the plan prescribes* | correct | **DELETED** | **GREEN** |
| 2 | **wrong (random UUID)** | present | **RED** |
| 3 | **wrong (random UUID)** | **DELETED** | **RED** |

Both RED arms fail on the isolation assertion itself, not on a harness accident:

```
java.lang.AssertionError: [these assets of tenant 8a809caf-1a82-4794-8e38-a37dfeaa84d4 are still
PENDING — their workers could not SEE them, i.e. ran under the wrong tenant. That is the isolation
failure this test exists to catch]
Expecting empty but was: [dbc15a34-…, 51cb6418-…, 2dd97d77-…, ae725177-…, 5ec6aae1-…, d42c5762-…]
    at …MediaTenantIsolationUnderConcurrencyIntegrationTest…(…:295)
```

All six of that tenant's assets, i.e. every event that consumer handled.

Restores after each break arm were verified **by token**, not by `git diff --stat`
(`break_tokens=0`, `TenantContext.set(event.tenantId())` present at line 146, `dirty=0`), because a
`git checkout` restore after staging has silently eaten later edits in this repo before.

---

## 3. What the matrix refutes

### 3a. "Two independent tenant pins" — REFUTED

The prior handoff recorded, as a genuine finding worth keeping, that the worker has two independent
pins (the aspect via `TenantContext`, and the explicit `set_config` via `event.tenantId()`) and that
a single-point regression is therefore already tolerated — "a stronger property than 27-04's threat
model assumed".

It is not. `TenantSetLocalAspect.setTenantBeforeDbOps` re-pins the GUC from `TenantContext` before
**every** repository call, so the aspect is the **last writer** before the claim query and it
overwrites whatever the worker pinned explicitly. The pins are **ordered, not redundant**:

- the explicit `set_config` is *redundant* while `TenantContext` is correct → **arm 1 GREEN**;
- the explicit `set_config` is *powerless* when `TenantContext` is wrong → **arm 2 RED**.

`TenantContext.set(event.tenantId())` — the first line of the listener — is the **single dominant
control**. This matches the standing `trap_tenant_pin_is_under_a_global_aspect` note.

### 3b. The plan's prescribed break arm is vacuous — REFUTED

AC-10 specifies: *"Break: **omit the pin entirely** on one of the two interleaved consumers (delete
its `session.doWork(...)` block)"*, and T-27-01 repeats it (*"which is exactly why its removal must
be the break"*). Measured: **arm 1 is GREEN**. The correct break arm is a wrong `TenantContext`.

Per the falsifiable-evidence contract this is recorded rather than silently substituted, and the
replacement is strictly stronger: it breaks the control that actually carries the risk.

### 3c. The plan's expected-RED prediction was partly wrong

AC-10 predicts *"Assertion (b) fails independently on the reused connection. Record which fired."*
Recording it: **assertion (b) did NOT fire in either RED arm.** It passed throughout. Assertion (b)
checks that the GUC is empty at transaction start — a property of `is_local = true` scoping, which
is unaffected by *which* tenant a worker pins. Only assertion (a)'s status half fired.

Assertion (b) is retained: it is the direct check on transaction-scoping and it would fire on an
`is_local = false` regression that leaves a value on a recycled connection. But it is **not** what
makes this test capable of failing on a broken pin, contrary to the plan's claim that it is.

### 3d. `MediaProcessingWorker`'s own javadoc asserted 3b and is corrected

The class javadoc stated *"That is why the pin's removal — not a change to its `is_local` flag — is
the break arm of the two-tenant isolation test."* Corrected in commit `0a0b306` to record the
measured ordering and name `TenantContext.set` as the dominant control.

---

## 4. Fail-closed properties

- Empty probe → VOID, not clean: `assertProbeNonVacuous` requires exactly `PER_TENANT * 2` readings.
- The read-back instrument is proven sighted before it is trusted (§1).
- The worker's no-throw `asset_not_visible` path is explicitly accounted for, so an isolation
  failure cannot be absorbed as a silent skip.

## 5. Commits

| commit | content |
|---|---|
| `9f5cfef`, `7b470f2`, `a1c7ad8` | the test as first written (green, **not** evidence) |
| `f3f7440` | recorded that it was not falsified — do not mark AC-10 satisfied |
| `8c2a253` | the harness fix: read the terminal assertion through the tenant-pinned path |
| `0a0b306` | the falsified arm matrix, in both the test and the worker javadoc |
