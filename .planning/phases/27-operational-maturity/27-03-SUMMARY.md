# 27-03 — Failure visibility: alerting, DLQ triage and the runbook

**Status: COMPLETE.** All 9 tasks (0–8). Task 8 ran 2026-07-29 against the live 4.3.4 broker once
27-02 had replaced it. Full both-directions record: [`27-03-EVIDENCE.md`](27-03-EVIDENCE.md).

## What shipped

- The **`rabbitmq-queues` per-queue scrape job**, written into `prometheus.yml.tmpl` — the plan named
  `prometheus.yml`, a file 27-00 had already replaced with a rendered template, so editing the name
  the plan gave would have been a **silent no-op the running Prometheus ignores**.
- **Six live messaging rules**, each proven to match ≥ 1 live series, plus `StompBrokerLag` kept
  deliberately dormant with its corrected expression preserved and observed evaluating `> 0` against
  a probe queue.
- `DatabaseDown` made able to detect a database outage for the first time; the two JVM alerts now
  name the JVM they measure.
- **Two executable gates**: `scripts/check-alert-rules.sh` (static) and `scripts/check-alert-metrics.sh`
  (live series).
- 13 new runbook sections, `jtoye_amqp_retries_exhausted_total{queue}`, and `scripts/dlq-inspect.sh`.
- The nine real dead letters archived and characterised, then **handed to 27-02** for disposition
  rather than purged here.

**Headline:** `DeadLetterQueueNonEmpty` was observed **firing on the real batch** —
`firing webhook.deliveries.dlq 9` — with the value asserted equal to an independent management-API
read taken at the same moment rather than to a literal. That closed the H-1 signal-regression window
left by the `ServiceDown{job="core-java"}` that 27-00 resolved.

## Three defects found by RUNNING the checks, not reading them

| | Defect |
|---|---|
| 1 | The retry counter tagged **every** message `queue="unknown"`: Spring AMQP proxies `ContainerDelegate.invokeListener(Channel, Object)`, so `args[0]` is a Channel, never the Message. Green in every unit test, useless in production, caught only by the live end-to-end run. |
| 2 | `dlq-inspect.sh` silently ignored an unrecognised `--ackmode get` and leaked a `jq` error on bad credentials instead of VOIDing. |
| 3 | The selector stripper leaked `service`/`le` out of `by (…)` clauses, **masked by an exemption list** so the exit code looked clean. |

A fourth was corrected in Task 7: the runbook's dead-letter discriminator was wrong. It told on-call
that `MessageConversionException` is "fatal on first delivery" and that retry exhaustion "also"
increments the counter. Measured, the counter increments on **both** paths (`1 → 2` on
`queue="media.process"` from one malformed publish) because the converter runs inside
`MessagingMessageListenerAdapter`, which is wrapped by the advice chain, and `x-death[0].count` reads
`1` on both too. **Only the exception class discriminates.**

## Task 8 — the delta after 27-02 replaced the broker

| | Before — 3.12.14 | After — 4.3.4 |
|---|---|---|
| `check-alert-metrics.sh` | rc 0 — 19 live / 24 selectors / 3 dormant | rc 0 — **19 / 24 / 3, identical** |
| `check-alert-rules.sh` | rc 0 | rc 0 |
| `dlq-inspect.sh --summary` | rc **1** (9 parked) | rc **0** (0 parked) |
| `/metrics/detailed` families + prefix | `queue_coarse_metrics`, `queue_consumer_count`, `rabbitmq_detailed_` | **unchanged** |
| rules this task had to edit | — | **none** |

**The alert layer survived a major version change with no edit.** Not assumed: the endpoint contract
was re-measured before any rule was trusted, with a break arm (`family=zz_not_a_family` → 0 matching
lines from a 15-line response) proving the assertion discriminates on content.

**The intermediate RED (T8.1) was NOT captured, and that is stated rather than claimed.** The window
belonged to 27-02's execution, before this gate was in the tree, and was near-zero anyway because
core-java auto-reconnected and redeclared before the deliberate restart. A stronger, outage-free
substitute was run instead: a temporary rule whose selector cannot match → gate **rc=1** naming it
(`selector matches ZERO series — this rule can never fire`), restored to rc=0 with `cmp -s`
confirming the file is byte-identical.

**T8.3 holds on the arrival rate, not just the clock.** All four DLQs at 0, 93 minutes after the
fresh install. More decisively: depth read 9 at the archive, 9 at the snapshot and 9 at the purge —
**zero arrivals across ~3 days**, against a pre-27-05 rate of one per ~5 h. The producer is gone, not
merely quiet.

## Known state and follow-ups

- **This branch must merge `main` after 27-02 (PR #335) lands.** It still pins RabbitMQ 3.12 in
  `docker-compose.full-stack.yml` while the runtime is 4.3.4; every Task-8 gate read the live runtime,
  not the compose file, and no `compose up` was run here.
- **`.evidence/` is not gitignored on this branch** — that entry is 27-02 Task 1's, unmerged. It
  currently holds 2 RabbitMQ volume tarballs and the dead-letter export (tenant payloads). Everything
  was staged by explicit path; a blanket `git add` here would leak broker data until 27-02 merges.
- `scripts/check-alert-liveness.sh` (27-00's) remains rc=1 by design in its pre-close state.
- The metrics rename 27-02 measured (37 series removed, 89 added — `erlang_mnesia_*` gone,
  `erlang_vm_*` renamed, `rabbitmq_raft_*` now labelled) touched **no** rule in `alerts.yml`, verified
  with a grep proven able to fire. Worth re-checking if rules are added later.
- **27-06** (`ops-contracts` CI job) depends on this plan and is now unblocked.
