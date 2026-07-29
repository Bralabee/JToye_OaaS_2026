# 27-02 — Broker upgrade: RabbitMQ 3.12.14 → 4.3.4

**Status: COMPLETE.** All 7 tasks executed, 7 commits on `feature/27-02-broker-upgrade`
(`963611e`..`48d2e29`), branch 0 behind `origin/main`. Full both-directions record:
[`27-02-EVIDENCE.md`](27-02-EVIDENCE.md).

## What shipped

The dev/compose broker was **two years and five months past its last patch** (3.12 left community
support 2024-02-29, commercial 2025-06-30; final patch 3.12.14 on 2024-05-06). It now runs **4.3.4**,
migrated by **fresh install** — there is no in-place 3.12 → 4.x path and 4.3 has no Mnesia reader at
all. The topology is code (`RabbitAdmin` re-declares from `RabbitMQConfig`), so it came back
identical; the messages are not, which is what the checkpoint was for.

- `docker-compose.full-stack.yml` → `rabbitmq:4.3.4-management-alpine`, plus a permanent
  `hostname: jtoye-rabbitmq` pin applied **after** the fresh install (D-L).
- Both Testcontainers pins → 4.3.4, library left at 1.21.4 (D-05).
- `infra/dependency-horizons.yaml` rabbitmq row rewritten for the 4.3 horizon.
- New `docs/runbooks/rabbitmq-broker-upgrade.md`; ADR-0002 dated open question; 17 stale version
  claims reconciled across 8 files.

## Checkpoint decisions (Task 3)

1. **Discard the nine dead letters.** Defensible because **no tenant holds any webhook subscription
   at all** — `webhook_subscription` is 0 rows across all 6 tenants, and that 0 was proven *sighted*
   (the same superuser connection sees 6 tenants and 22 orders). A replay would fan out to zero
   subscribers. Payloads retained at `.evidence/webhook-dlq-export.json`.
2. **M15 gate RED → adjudicated, not overridden.** One PENDING `media_asset` — 27-01's own `ac55`
   fixture — with **no `media_event_outbox` row**, so it was never dispatched and nothing was in
   flight. Left untouched: deleting a row to green a gate is the failure this phase exists to prevent.

`SNAP_DEPTH 9 == ARCHIVED_N 9` confirmed **27-05's converter fix is holding** (zero arrivals since
2026-07-26, previously ~5/day) — the D-I precondition for asking the question at all.

## The rollback is real — proven twice

Rehearsed against the live volume (restored to depth 9 under the tarball's own node name), with a
break arm that genuinely discriminates: same tarball, same image, **wrong hostname** → queue absent.
Then it **fired for real** when the first recreate aborted, restoring 3.12.14 and all 9 messages
unattended.

## Findings that only came from RUNNING things

| # | Finding |
|---|---|
| **D1** | `check_if_cluster_has_classic_queue_mirroring_policy` **does not exist on 3.12** — all three arms exit 64, so the criterion could not discriminate. Replaced with an `ha-*` policy count: 0 → 1 → 0. |
| **D2** | AC-2's control returns **7**, not 5. `MediaListenerConcurrencyIntegrationTest.java` carries a second Testcontainers pin and is **absent from `files_modified`** (it arrived with 27-04). AC-2 demands 0, so the plan as written would have failed at its own final gate. |
| **D5** | The snapshot guard `bytes > 100000` sits on a `.tar.gz` but its `~2.3M` expectation is the **uncompressed** volume. Mnesia compresses ~17:1, so it VOIDed a **correct** snapshot. Replaced with an uncompressed-size floor plus content assertions. |
| **D6** | Two 4.x behaviours that make a **correct** state read as a failure: `node()` is a **quoted** atom when the hostname contains a hyphen (this aborted the first run), and health/`ping` go green **before queue recovery**, so a depth assertion gated on ping reads empty — indistinguishable from "booted empty". |
| **D7** | The upgrade **broke a test**: a transient **non-exclusive** queue is refused by 4.x (`transient_nonexcl_queues is deprecated`). Fixed to durable. Blast radius audited — all 10 production queues use `QueueBuilder.durable`, and the SSE `AnonymousQueue` is legal only because it is **exclusive**. |

**AC-9 was bigger than predicted: 37 series removed, 89 added.** Beyond the 11 `erlang_mnesia_*`
D-07 correctly called, many `erlang_vm_*` were **renamed** and `rabbitmq_raft_*` gained
`{module="rabbit_khepri"}` labels while going from 6 silent zeros to 60 live series. Any alert rule
naming a removed series is now permanently silent — `alerts.yml` references **0** of them, checked
with a grep proven able to fire.

## Handed onward

- **27-03's blocked Task 8**: its `/metrics/detailed` families all survive on 4.3 with 13 series each
  and the `queue=` label intact, all four DLQs included. Its rules need re-validating against the
  new series census (`.evidence/after/series-{removed,added}.txt`).
- **27-00**: the `vendor_override` rule this plan was told to hand over is **already implemented**
  (gate header line 24, `H-2b`). Recorded as a confirmation, not claimed as a catch.
- **Operator / ADR-0002**: the staging-production broker's version is still unknowable from this
  repo. `rabbitmq-k8s` row `manual_review` **expires 2026-10-26** and turns the gate red by itself.

## Gate state

`docs-freshness` 0 (1832) · `check-branch-behind-base` 0 (5 ahead, 0 behind) ·
`check-dependency-horizons` 0 · `render-golden` 0 · `check-runtime-freshness` **0, 4/4 FRESH**.

The runtime-parity gate **failed first** (`core-java DRIFT [image-not-rebuilt]` — `COPY
core-java/src` puts test sources in the build inputs), was resolved with `up -d --build`, and
re-verified by **content** from inside the running `app.jar`.

## Known follow-ups

- 4.3's community window closes **2026-11-30**: this row goes AMBER ~2026-09-01 and RED 2026-12-01
  with no commit in between. Intended, stated in the manifest header before it happens.
- The live STOMP relay was proven at the broker over a raw socket (`server:RabbitMQ/4.3.4`,
  `auth_login=jtoye`, #266/#269 two-arm). A **KDS client receiving a relayed order event** end-to-end
  is still uncaptured — the long-standing L6 evidence gap, unchanged by this plan.
