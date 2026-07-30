# Phase 27 — deferred items

Things this phase found, decided not to do, and wrote down with a **named trigger** rather than a
"later". An item here is scheduled work with a condition attached; it is not a wish.

Opened by plan **27-03** (alerting / DLQ / runbook), 2026-07-29.

---

## 1. DLQ redrive — deferred with a trigger, and it is the recommendation, not a compromise

**Trigger to build it:** either (a) a `DeadLetterQueueNonEmpty` alert has fired **3+ times in one
quarter**, or (b) the manual `rabbitmqadmin`/shovel procedure in `docs/runbooks/alerts.md` has
actually been executed **twice**. Until one of those, the cost is not justified.

**Four constraints any implementation inherits.** They are recorded because each was established by
measurement during 27-03 and would otherwise be rediscovered the expensive way:

1. **The redrive target is `x-death[0].exchange` + `x-death[0]["routing-keys"][0]`** — *not* the
   message's own `exchange` field, which is the dead-letter exchange. Republishing to the latter
   sends the message straight back to the DLQ.
2. **Tenant identity is in the JSON body, not a header.** Any redrive must parse the payload to know
   whose data it is handling.
3. **A poison-loop detector is required.** The message is in the DLQ *because* a deterministic
   handler failed it three times; republishing re-enters the same handler and fails again. The loop
   is bounded only by `x-death.count`, which nothing currently reads.
4. **There is no cross-tenant operator identity on this platform.** All admin is tenant-scoped, and
   a DLQ is inherently cross-tenant (one queue, every tenant's payloads; AMQP `basic.get` is FIFO,
   so a per-tenant fetch is impossible). A redrive endpoint has no correct `@PreAuthorize` subject
   today.

**Inherited quality contract, so the follow-up cannot skip it:** if the deferred endpoint is ever
built it takes the full agent-readiness contract — an `Idempotency-Key`, RFC 7807 typed errors, a
scoped credential, and an MCP tool or a recorded reason it is out of scope. Cross-referenced to
**#209**.

## 2. `onboarding.notifications` has no DLX, and adding one breaks startup

`x-dead-letter-exchange` is a queue **argument**. Redeclaring an existing durable queue with
different arguments returns `PRECONDITION_FAILED (406)` and kills the declaring channel — so adding
a DLX to this queue breaks boot against **every** broker that already holds it, including every
developer machine.

**Migration path when it is worth doing:** new queue name → rebind → drain the old one → delete.

**Pinned as deliberate**, not as an oversight: `RabbitMQDeadLetterTopologyTest` asserts the argument
is ABSENT, with the 406 reason in the test body, so a future reader cannot quietly "fix" it.
The visibility gap it leaves is closed instead by `jtoye_amqp_retries_exhausted_total{queue}`, which
increments at the interceptor whether or not a dead-letter exchange exists downstream.

## 3. Duplicate payment-failure counters

The live scrape carries **both** `jtoye_payment_failed_total` (used by `PaymentFailureSpike`) and
`jtoye_payments_failed_total` ("Total payment failures", referenced by nothing). One of them is
dead weight and the near-identical names are a trap for whoever writes the next rule. Recorded so
`check-alert-metrics.sh` output is not misread as a false positive.

## 4. No proactive metric for webhook delivery failures

`WebhookDeliveryWorker` logs `event=webhook_delivery_failed` and auto-pauses a subscription after N
consecutive failures, and `WebhookDeliveryController` serves a paged per-tenant delivery log. So the
information exists — **on demand, per tenant, if someone goes looking.** There is no counter and
therefore no alert. A `webhook.delivery.failed{tenant}`-style metric would close it.

## 5. k8s-side alerting does not exist

`k8s/` ships **no Prometheus, no Alertmanager and no Grafana** — the whole monitoring stack lives in
`infra/monitoring/docker-compose.monitoring.yml`. Everything 27-03 built is therefore
**compose-scoped by construction**. Recorded as N/A for this phase rather than silently widened.

## 6. ABANDONMENT INSTRUCTION — if this phase stops mid-way, revert 27-00 Task 1

27-00 Task 1 fixed the core-java scrape port, which **resolved `ServiceDown{job="core-java"}` — the
only alert that was actually firing on this platform.** If the phase stops between that fix and
27-03's rules landing, the platform has *fewer* firing alerts than before and *no* new ones: a net
regression in signal produced entirely by correct changes.

**So: if Phase 27 is abandoned before 27-03 Task 2 has merged, revert 27-00 Task 1.** A firing
`ServiceDown` is worse than nothing only when something replaces it.

*(Status 2026-07-29: 27-03 Tasks 1–6 have landed on `feature/27-03-alerting-dlq-runbook`, so the
replacement signal exists — `DeadLetterQueueNonEmpty` was observed firing on the real batch. This
instruction becomes moot once that branch merges.)*

## 7. `StompBrokerLag` re-enable trigger — and the residual RED its guard can produce

**Trigger:** issue **#304** (rework `frontend/e2e/stomp-relay.spec.ts` to be ingress-capable), or any
deliberate `STOMP_BROKER_MODE=relay`. Whoever makes that spec establish a real `SUBSCRIBE` in relay
mode is the person who should uncomment the rule. The corrected expression is preserved verbatim in
`alerts.yml`, so re-enabling is an uncomment, not a re-derivation, and the expression has been
observed evaluating `> 0` against a probe queue.

**RESIDUAL, recorded rather than hidden.** The `DORMANT_RULES` wake-up guard in
`scripts/check-alert-metrics.sh` matches on a queue **name pattern**, so it also fires on any queue
*coincidentally* named `stomp-subscription*` or `amq.gen-*` — and the only remedy for that is a
**manual edit** of the `DORMANT_RULES` list. **Track that RED to closure like any other.** An entry
whose only fix is hand-editing a gate is exactly the kind someone silences instead of fixing.

## 8. postgres-exporter DSN — the cross-plan split, so nobody "adds" a key that exists

`infra/monitoring/docker-compose.monitoring.yml` builds the exporter DSN with
`sslmode=${POSTGRES_EXPORTER_SSLMODE:-require}`. The **deployed default stays `require`**; only the
local runtime overrides it to `disable` via `.env`, because the local PostgreSQL has no TLS.

**`.env.example` is 27-00's file, and the key already existed there** — the change was `require` →
`disable`, a MODIFICATION, not an addition. 27-03 asserted its value as a precondition and did not
open the file. Recorded because the original finding ("no such key") came from a **case-sensitive
`grep -n sslmode` against an UPPERCASE key**: `grep -c "sslmode"` → 0, `grep -ci` → 1. A negative
result that did not come from the thing being tested.

Any environment that enables TLS on PostgreSQL should drop the override rather than keep it.

## 9. CI-wiring handover: `check-alert-rules.sh` is wired by 27-06, and this one was dropped once

`scripts/check-alert-rules.sh` is **produced by 27-03** and **wired into CI by 27-06** (wave 4),
which wires three static gates and asserts a step count of 3.

**Recorded because the handover was already dropped once.** 27-03's draft asserted **sixteen times**
that "27-00 Task 7 wires it"; measured, `grep -c check-alert-rules 27-00-PLAN.md` → **0**, and
27-00's own AC-7.3 (`grep -c 'chmod +x'` == 2) actively forbids a third step. Left as drafted, this
phase's headline finding — **F-8, "there is no CI validation of `alerts.yml` at all"** — would have
ended the phase unclosed while every plan believed another had closed it. The script's header now
names 27-06 as its CI owner, so the next dropped handover is visible in the artifact rather than
only in a document nobody re-reads.

## 10. Three pre-existing live rules whose selectors match zero series — **ALL THREE CLOSED 2026-07-30**

> **CLOSED.** `KNOWN_DATALESS` is now **empty**, and the gate passes with `0 reasoned exemption(s)`.
> Every one of the three was removed by the gate's own STALE arm rather than by review, which is the
> whole point of writing the removal trigger *into* the entry.
>
> | rule | closed | how |
> |---|---|---|
> | `HighResponseTime` | 2026-07-29 (#343) | enabled `percentiles-histogram`; `_bucket` went 0 → 74 series |
> | `HighErrorRate` | 2026-07-30 | its own trigger fired — a 5xx was served (`/actuator/health` 503, during a core-java restart), so `status=~"5.."` matches 1 series and the exemption was removed |
> | `NoOrdersCreated` | 2026-07-30 | no exemption re-added. The remedy the header prescribes is now **committed**: `scripts/seed-order-metric.sh` |
>
> **What the `NoOrdersCreated` row below got right, and what it missed.** It correctly called this
> "worse than it looks" — an absence alert built on a counter that does not exist. What it did not
> say is *why the counter goes missing*: `http_server_requests_seconds_count` is a Micrometer
> **request** counter, created on the first matching request and **destroyed when core-java
> restarts**. It is not a database fact, so seeding an order row does not create it. Measured
> 2026-07-30: the series ran 10:00:10–11:35:10Z, vanished when core-java was rebuilt at ~11:38Z, and
> a single `GET /api/v1/shops` then moved the total series count 3 → 4.
>
> Since this project mandates rebuilding all containers after any code change, **the alert is blind
> after every rebuild until the first order** — precisely when you would most want it. Reproduced
> deliberately: `docker compose restart core-java` → series `0` → gate `rc=1`; `seed-order-metric.sh`
> → `201` → series `1` → gate `rc=0`. The rule itself is untouched and no gate was weakened.

Found by `scripts/check-alert-metrics.sh` on its first run, in rules 27-03 was explicitly forbidden
to edit ("do not touch any other rule in the file"). They were carried in that script's
`KNOWN_DATALESS` list with a reason and an owner each, subject to stale-detection — an entry whose
selectors start matching **fails the gate** so it cannot outlive its reason.

| rule | selector | why it matches nothing |
|---|---|---|
| `HighResponseTime` | `http_server_requests_seconds_bucket` | **Structural.** This deployment publishes the requests timer as `_count`/`_sum` only, with no histogram buckets, so `histogram_quantile` has nothing to read. Fixing it means enabling `management.metrics.distribution.percentiles-histogram` — an application-config change. |
| `HighErrorRate` | `http_server_requests_seconds_count{status=~"5.."}` | **Conditional.** Family and `status` label both exist; no 5xx has been served yet. Remove the exemption the first time one is. |
| `NoOrdersCreated` | `http_server_requests_seconds_count{uri=~…,method="POST",status="201"}` | **Conditional, and worse than it looks.** The rule's own logic is `increase(...) < 1`, so it can never fire while the series is absent — an absence alert built on a counter that does not exist yet, which is the opposite of what its author intended. |

## 11. `RedisDown` watches the exporter, not Redis — the `DatabaseDown` defect in a second metric

`RedisDown` alerts on `up{job="redis"} == 0`, which is the health of **redis-exporter**. The
correct signal, `redis_up`, exists in the scrape and is **referenced by no rule** — flagged
independently by 27-00's `check-alert-liveness.sh` as `L-1b`.

This is exactly the defect 27-03 corrected in `DatabaseDown` (`up{job="postgres"} == 0 or
pg_up == 0`), in a different metric. **Not fixed here** because Task 2's scope is explicit: do not
touch any other rule in the file. The one-line fix is `up{job="redis"} == 0 or redis_up == 0`, and
it should be made with the same falsification 27-03 ran for `DatabaseDown`: break the exporter's
connection to Redis and confirm the corrected rule fires where the original stayed silent.

## 12. Three runbook commands in the pre-existing `ServiceDown` section are not verbatim-runnable

Found by 27-03's T4.2 replay harness: two contain angle-bracket `<placeholder>` tokens
(`docker logs --tail 100 jtoye-<service-name>`, `docker network inspect … | grep <service>`) and one
pipes into the interactive pager `less` (and omits `-f docker-compose.full-stack.yml`, so it fails
with `no configuration file provided`). They are templates rather than commands.

The harness exempts them **structurally** (detected by shape, not hand-listed) with a reason each,
so the exemption cannot quietly grow. Worth turning into runnable form with a concrete example
service the next time that section is edited.

## 13. Consolidating the two bespoke claim gates into the claim-gate manifest — DEFERRED to a dated re-check

**Was GitHub issue #362, closed 2026-07-30 in favour of this dated entry.** Re-evaluate **on or
after 2026-09-30**, or sooner once the trust condition below is genuinely met.

`scripts/check-claims.sh` (the vendored claim-gate engine, canonical copy `~/dotfiles/gates/`) runs
the **same 43 assertions** as `scripts/check-doc-metrics.sh` (37) and
`scripts/check-project-version.sh` (6), from `scripts/gates/claims.manifest`. All three remain in
`.github/workflows/docs-freshness.yml` on purpose.

**Why not consolidated on 2026-07-30.** Equivalence is proven — engine and bespoke gates returned
identical exit codes across **9 break arms** (clean, 6 drift/M-1 cases, a `jq:` lockfile skew, a
VOID), restores verified by content. But the trust condition asked for the engine to have run green
in CI on several PRs **and to have failed at least once for a real reason**, and at the time of
deferral it had **2 green CI runs and 0 real CI failures**. It has never been observed failing *in
CI* — only locally, in 9 equivalence arms plus the engine's own 19-arm selftest. That local evidence
is substantial and arguably stronger than a run-count, which is why this is a dated deferral rather
than a refusal.

**What consolidation must do — it is not a delete.**

1. Confirm the engine has real CI history: several green runs **and** at least one genuine failure,
   so its CI behaviour is observed rather than assumed.
2. **Move, do not discard, the headers.** `check-doc-metrics.sh` and `check-project-version.sh`
   carry the measured evidence (README at `921` against a tree of `1851`; the version stuck at
   `2.1.0` across two releases) and — more valuable — the reasons for what is deliberately *not*
   checked. That is repo-resident knowledge which travels with a clone; deleting the scripts loses
   it. It belongs in `claims.manifest` or a doc the manifest cites, **before** the scripts go.
3. Remove the two CI steps and the two scripts in one change, and confirm the claim count stays
   **43**. A silent drop to 37 or 6 is the regression-by-omission this repo's doctrine names.
4. Re-run the 9 break arms against the engine alone.
5. Re-run `check-doc-citations.sh` — removing files shifts line numbers, which broke citations
   **twice on one branch** during Phase 27.

**If the engine turns out to be the wrong abstraction,** that is a legitimate outcome — record why
here rather than reverting silently, because this shape has now recurred four times in this repo.

> ⚠ **This register is not gate-enforced.** Unlike `docs/ops/terminal-states.yaml` (checked by
> `check-terminal-states.sh` for expired deferrals) and `infra/dependency-horizons.yaml`
> (`check-dependency-horizons.sh`), nothing fails when a date here passes. The 2026-09-30 date is a
> convention, not a guarantee. If this item matters enough to enforce, give it a row in
> terminal-states.yaml instead — but note that register is scoped to *failure states*, which this
> is not, so the honest options are "enforce it somewhere it fits" or "accept it is a reminder".
