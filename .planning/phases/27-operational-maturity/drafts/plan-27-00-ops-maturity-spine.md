---
phase: 27-operational-maturity-messaging-first-instance
plan: 00
type: execute
wave: 1
role: SPINE
depends_on: []
files_modified:
  - infra/monitoring/prometheus/prometheus.yml
  - infra/monitoring/prometheus/alerts.yml
  - infra/monitoring/alertmanager/alertmanager.yml.tmpl
  - infra/monitoring/alertmanager/entrypoint.sh
  - scripts/check-alert-liveness.sh
  - scripts/check-terminal-states.sh
  - scripts/check-dependency-horizons.sh
  - docs/ops/terminal-states.yaml
  - infra/dependency-horizons.yaml
  - infra/load-testing/baseline.sh
  - infra/load-testing/budget.yaml
  - infra/load-testing/README.md
  - infra/load-testing/baselines/.gitkeep
  - docs/runbooks/alerts.md
  - docs/runbooks/terminal-states.md
  - .github/workflows/ci-cd.yaml
  - k8s/DEPLOYMENT.md
  - docs/metrics.json
autonomous: true
requirements: [OPS-01, OPS-02, OPS-03]

must_haves:
  truths:
    - "Every Prometheus scrape target declared in prometheus.yml is UP, and a gate FAILS (not warns) when one is not — because an alert on a dead target is theatre, not detection"
    - "Every alert rule's metric+label selector is proven to match at least one live series; a rule that promtool accepts but that matches nothing FAILS the gate"
    - "Every terminal failure state the system can enter is enumerated in a machine-readable register with an owner, a detection signal and an operator action"
    - "A NEW terminal failure state added to the declared discovery surface without a register row FAILS CI — the register cannot silently fall behind the code"
    - "Every register row's alert exists in alerts.yml AND has a runbook section; a register row pointing at a non-existent alert or a missing runbook heading FAILS CI"
    - "Every pinned image and toolchain carries a support-horizon row whose EOL date is re-fetched from a machine-readable source at gate time; a stale cached date, a past horizon without an unexpired exemption, or a horizon inside the warn window FAILS CI"
    - "A load baseline exists at the declared design point with per-status-code assertions, covering BOTH the HTTP path and the AMQP consumer path, committed as a dated artifact"
    - "Every gate in this plan exits 2 (VOID) on missing tooling, unreachable dependency, or an empty discovery result — 'found nothing' is never 'clean'"
  artifacts:
    - path: "docs/ops/terminal-states.yaml"
      provides: "The terminal-failure-state register — the contract GAP 1 delivers"
      contains: "webhook.deliveries.dlq"
    - path: "scripts/check-terminal-states.sh"
      provides: "Static CI gate: source-discovery -> register -> alert -> runbook, three cross-references"
    - path: "scripts/check-alert-liveness.sh"
      provides: "Runtime gate: target health + selector-matches-a-live-series + end-to-end notification delivery"
    - path: "infra/dependency-horizons.yaml"
      provides: "Pinned-dependency support-horizon manifest with cached EOL + source + reasoned dated exemptions"
    - path: "scripts/check-dependency-horizons.sh"
      provides: "Fetching gate that fails on past/near horizons AND on a stale cached date"
    - path: "infra/load-testing/baseline.sh"
      provides: "Two-arm minimum honest baseline with status-code assertion"
  key_links:
    - from: "scripts/check-terminal-states.sh"
      to: "core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java"
      via: "DLQ constant discovery"
      pattern: "_DLQ\\s*="
    - from: "scripts/check-terminal-states.sh"
      to: "docs/runbooks/alerts.md"
      via: "runbook heading cross-reference"
      pattern: "^## "
    - from: ".github/workflows/ci-cd.yaml"
      to: "scripts/check-terminal-states.sh"
      via: "ops-contracts job step"
      pattern: "check-terminal-states\\.sh"
---

<objective>
Phase 27 was reframed from "Messaging Layer Hardening" to **"Operational Maturity — messaging as the
first instance"**. The four sibling plans each fix a messaging-specific instance of a gap that is not
messaging-specific. This plan is the layer that turns each of them into an instance of a policy
applied once, so the same class of defect is caught everywhere instead of being fixed four more
times.

It delivers three mechanisms, each grounded in a defect verified live on the unmodified tree:

- **GAP 1 — a detection contract for terminal failure states.** A register of every state where work
  is permanently stopped and no human is told, plus gates that fail when a new one appears without a
  detection path, when an alert selector matches nothing, when a scrape target is down, and when a
  notification never reaches its destination.
- **GAP 2 — a dependency lifecycle mechanism.** A support-horizon manifest per pinned image and
  toolchain, checked against a machine-readable EOL source at gate time so the dates cannot rot.
- **GAP 3 — the minimum honest load baseline.** Two arms (HTTP and AMQP consumer), status-code
  asserted, committed dated, against a declared budget — enough to unblock 27-04 and no more.

It does **not** fix the defects the siblings own. It fixes the two detection paths that are
structurally incapable of working (so 27-03's alerts are not theatre), and it produces the number
27-04 needs.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@docs/analysis/MESSAGING-BROKER-EVALUATION-2026-07-26.md
@docs/architecture/decisions/ADR-0003-messaging-broker-selection.md
@docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md

## Verified problem statements

Every finding below was verified by me on 2026-07-26 against the running compose stack and the tree
at `feature/phase-26-local-k8s-overlay`. **Each live probe was run with a control that proves the
probe is capable of returning a different answer** — the caller's warning about mis-reading a
"Connection refused" from the wrong container as "metric absent" is the reason every probe here is
paired.

### F-1 — The one messaging alert is structurally incapable of firing. CONFIRMED, and stronger than reported.

`infra/monitoring/prometheus/alerts.yml:243-253` defines `StompBrokerLag` as
`sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}) > 0`.

The broker's Prometheus endpoint (`jtoye-rabbitmq:15692/metrics`, `prometheus.yml:92-98`) is in
**aggregated** mode and emits exactly one series with **no `queue` label at all**:

| Probe (run inside `jtoye-prometheus`) | Result |
|---|---|
| `sum(rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*\|amq[.]gen-.*"})` | `[]` — **empty** |
| **control** `sum(rabbitmq_queue_messages_ready)` | `9` — the metric exists and carries the dead messages |
| `count(rabbitmq_queue_messages_ready{queue!=""})` | `[]` — **no series carries any `queue` label** |
| `/api/v1/series?match[]=rabbitmq_queue_messages_ready` | 1 series: `{component,instance,job,service}` — no `queue` |
| **control** `/api/v1/series?match[]=up` | 7 series — the series API works |

Live rule state confirms the rule is *healthy and permanently inactive*: `health=ok, state=inactive,
alerts=0`.

**Correction to the brief:** I could not run `promtool` — it is not installed on this host
(`command -v promtool` → MISSING). The claim "promtool passes it" is therefore *unverified by me*.
The live Prometheus rule evaluator reporting `health=ok` is the **stronger** form of the same point
and is what I rely on: the rule parses, loads and evaluates without error, and matches nothing. Do
not cite promtool in the SUMMARY unless it is actually run.

**The fix is a scrape-path change, verified before proposing it.** RabbitMQ 3.12 exposes
`/metrics/per-object`, and on this exact broker it returns the labelled series:

```
rabbitmq_queue_messages_ready{vhost="/",queue="webhook.deliveries.dlq"} 9
rabbitmq_queue_messages_ready{vhost="/",queue="media.process.dlq"} 0
... 13 queues, 39 queue-family series total
```

`/metrics/detailed?family=queue_coarse_metrics` also works (`rabbitmq_detailed_queue_messages_ready`)
but renames the metric, which would break every existing expression. **Use `/metrics/per-object`.**

**Cardinality caveat that must be handled, not ignored:** the SSE fan-out declares an
`AnonymousQueue` per replica (`order.state-changes.sse.kP5foIsLRpyX0fWkNqTBGw` is live now). Its name
is random and changes on every replica restart, so per-object scraping introduces **unbounded label
churn** over time. A `metric_relabel_configs` drop rule on `queue=~"order\\.state-changes\\.sse\\..*"`
is required in the same change, not later.

### F-2 — Four DLQs, no consumer, nine real messages dead for 11 days. CONFIRMED.

`rabbitmqctl list_queues name messages consumers` on the live broker:

| Queue | messages | consumers |
|---|---|---|
| `order.state-changes.dlq` | 0 | **0** |
| `payment.events.dlq` | 0 | **0** |
| `webhook.deliveries.dlq` | **9** | **0** |
| `media.process.dlq` | 0 | **0** |
| (9 other queues) | 0 | 1 each |

Declared at `RabbitMQConfig.java:27` (`DLQ_QUEUE`), `:32` (`PAYMENT_EVENTS_DLQ`), `:62`
(`WEBHOOK_DELIVERIES_DLQ`), `:78` (`MEDIA_EVENTS_DLQ`).

Non-destructive peek (`ackmode=reject_requeue_true`) of `webhook.deliveries.dlq` confirms the age:
payloads carry `"orderNumber":"ORD-00000000-20260715-D10B7696"` and
`"timestamp":1784115970.786537279` → **2026-07-15**. Dead 11 days, undetected. Confirmed.

### F-3 — The `core-java` Prometheus target is DOWN. CONFIRMED, with a cause the brief did not state.

`/api/v1/targets` (live, from inside `jtoye-prometheus`):

```
"scrapeUrl":"http://core-java:9091/actuator/prometheus","health":"down",
"lastError":"dial tcp 172.18.0.2:9091: connect: connection refused"
```
and `up{job="core-java"} = 0` (control: the other six targets are `up = 1`).

`prometheus.yml:36-46` scrapes `core-java:9091`. That port is the **prod-profile management port**
(`application-prod.yml:106-107`, `${MANAGEMENT_SERVER_PORT:9091}`), and the compose stack runs
`SPRING_PROFILES_ACTIVE: dev` (`docker-compose.full-stack.yml:173`), which sets no management port —
so nothing binds 9091 in this runtime. **Correction to the brief:** the brief attributes it to
`application.yml:252` (`server.port: ${SERVER_PORT:9090}`); that line is correct as far as it goes,
but the operative fact is the *profile split* — 9091 is right for k8s (`SPRING_PROFILES_ACTIVE: prod`,
`k8s/base/core-java-deployment.yaml:60-65`, `prometheus.io/port: "9091"`) and wrong for compose. One
`prometheus.yml` is being asked to serve two runtimes with different port topologies.

Proved the endpoint is otherwise healthy, from inside the app container:

| Probe | Result |
|---|---|
| `GET localhost:9090/actuator/prometheus` | **200**, 1138 lines of metrics |
| `GET localhost:9091/actuator/prometheus` | connection refused |
| **control** `GET localhost:9090/actuator` | 401 (endpoint index is secured — proves the probe reaches a live server) |

Exposure is deliberately opted in for compose at `docker-compose.full-stack.yml:209`
(`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,info,prometheus`). So the fix is one line —
scrape `core-java:9090` in the compose Prometheus — and the *durable* fix is the target-health gate,
because this class returns the moment a port moves again.

**Blast radius:** every `core-java` alert has had no data. That is `HighErrorRate`,
`HighResponseTime`, `DatabaseConnectionPoolExhausted`, `HighMemoryUsage`,
`FrequentGarbageCollection`, `NoOrdersCreated`, `TenantIsolationFailure` (the tenant-isolation
security alert) and `PaymentFailureSpike` — **8 of 14 alerts**.

### F-4 — An alert has been firing for 32 hours and reaches a mailbox nobody reads. NEW FINDING.

`/api/v1/alerts` shows `ServiceDown{job="core-java"}` **firing since 2026-07-25T13:03:41Z** — 32
hours at time of writing — and the operator learned of it from this investigation. Alertmanager
accepted it (`/api/v2/alerts`, `receivers:[{"name":"email-default"}]`) and delivered:
`alertmanager_notifications_total{integration="email"} 3`,
`alertmanager_notifications_failed_total{integration="email",...} 0` (control: the metric family
exists with 6 `alertmanager_alerts*` series).

The destination is `ops@jtoye.local` via `mailhog:1025`
(`infra/monitoring/alertmanager/entrypoint.sh` defaults; `alertmanager.yml.tmpl` receiver
`email-default`). Mailhog's log confirms `RCPT TO:<ops@jtoye.local>` on every day 2026-07-19 →
2026-07-26. **The delivery leg works and terminates in a dev sink with no human behind it.**

Also verified: `.env` sets `ALERTMANAGER_SLACK_WEBHOOK_URL` and `ALERTMANAGER_SLACK_CHANNEL`, and
`alertmanager.yml.tmpl` has **no slack receiver at all** — configured intent with no wiring.

This is the sharpest possible statement of GAP 1: it is not enough for an alert to fire. Detection is
only real when someone is told.

### F-5 — Alert/runbook drift. CONFIRMED.

`alerts.yml` defines **14** live alerts (the two `DiskSpace*` rules at `:174-210` are commented out
with a stated reason — node-exporter is not deployed). `docs/runbooks/alerts.md` has **10** `## `
sections. Undocumented: `KeycloakDown`, `RedisDown`, `PaymentFailureSpike`, `StompBrokerLag`.

### F-6 — Six pinned dependencies are past their support horizon, not one. CONFIRMED and EXPANDED.

Fetched live from `https://endoflife.date/api/<product>.json` on 2026-07-26:

| Pin | Site | Cycle | EOL | Status today |
|---|---|---|---|---|
| `rabbitmq:3.12-management-alpine` | `docker-compose.full-stack.yml:144` | 3.12 | **2024-02-21** | past, 2y5m |
| `prom/prometheus:v2.48.0` | `infra/monitoring/docker-compose.monitoring.yml:8` | 2.48 | **2023-12-28** | past, 2y7m |
| `grafana/grafana:10.2.2` | `…monitoring.yml:34` | 10.2 | **2024-07-24** | past, 2y |
| `quay.io/keycloak/keycloak:24.0.5` | `docker-compose.full-stack.yml:81` | 24.0 | **2024-06-10** | past, 2y1m |
| `node:20-alpine` | `frontend/Dockerfile:5,129`, `mcp-server/Dockerfile:7,21` | 20 | **2026-04-30** | past, 3m |
| `alpine:3.20` | `docker-compose.full-stack.yml:48` | 3.20 | **2026-04-01** | past, 4m |
| `postgres:15-alpine` | `docker-compose.full-stack.yml:14` | 15 | 2027-11-11 | OK |
| `redis:7-alpine` | `docker-compose.full-stack.yml:126` | **7.4** (resolved) | `false` | OK |
| `golang:1.25-alpine` | `edge-go/Dockerfile:7` | 1.25 | `false` | OK |
| `eclipse-temurin:21-*` | `core-java/Dockerfile:5,27` | 21 | 2029-12-31 | OK |
| `minio/minio:${MINIO_IMAGE_TAG:-latest}` | `docker-compose.full-stack.yml:393` | — | **no endoflife.date entry, and `latest` is not a pin** | unknowable |
| `minio/mc`, `ollama/ollama` | `…:415,435,460` | — | same | unknowable |

**Two design facts this table forces, both discovered by probing rather than assuming:**

1. **A floating tag cannot be looked up.** `redis:7-alpine` has no cycle "7" in the EOL data; the
   running container reports `v=7.4.8` → cycle 7.4, which is **not** EOL. The manifest must record
   the *resolved* cycle, and the gate must be able to re-resolve it from a running container or an
   image label — otherwise the check is a guess.
2. **The obvious broker upgrade target is a trap.** `rabbitmq` 4.1 EOL **2026-01-30** (already past);
   4.2 EOL **2026-07-31** — **five days from now**; 4.3 `eol: false`. A 27-02 that upgrades to 4.2
   would ship a broker that leaves support inside the same week. **This is the spine's single most
   valuable output for a sibling plan and must be handed to 27-02 explicitly.**

### F-7 — No load baseline; the existing script is HTTP-only and unreferenced. CONFIRMED, with two corrections.

`infra/load-testing/load-test.sh` exists (6928 bytes, mode 700), drives `hey`/`ab` against `/shops`,
`/products`, `/actuator/health`, and is referenced by **no workflow** (only by prose docs).
`REMEDIATION-BACKLOG-2026-07-08.md:134` (P3-13) records the gap. Design point is "tens of events/sec"
(`.planning/research/ARCHITECTURE.md:243`), unmeasured.

Corrections / additions from probing:

- **No load tool is installed**: `hey`, `ab`, `k6`, `wrk`, `vegeta` all MISSING. `go 1.26.5` IS
  present, so `go install github.com/rakyll/hey@latest` is the cheapest acquisition. A baseline plan
  that assumes the tool exists is a plan that VOIDs on first run.
- **The script has no status-code assertion.** `GET /shops` without a token returns **401**
  (verified), and `/actuator/health` returns 200 with no auth. A 401 flood or a health-check flood
  produces an excellent req/s number that means nothing. **This is the canonical vacuous-baseline
  shape and the assertion that prevents it is the whole point of the deliverable.**
- `docs/guides/TESTING.md:569` links `infra/load-testing/README.md`, which **does not exist**.

### F-8 — Consumer concurrency is unconfigured. CONFIRMED (input to 27-04, not fixed here).

`grep -rn "spring.rabbitmq.listener\|listener:" core-java/src/main/resources/application*.yml`
returns nothing (control: `grep -c "rabbitmq:" application.yml` = 1, so the file and the probe are
both real). Spring defaults apply: prefetch 250, one consumer thread per queue.

### F-9 — The register's raw material already exists in code.

Terminal-state candidates verified present:

| Kind | Locator | Signal today |
|---|---|---|
| AMQP DLQ ×4 | `RabbitMQConfig.java:27,32,62,78` | none (F-1) |
| `payment_event_outbox` FAILED + `poison=true` | `V46__outbox_reliability.sql:24`, `PaymentEventOutbox.java:28-32` | counter `payment.outbox.dead_letter` — **registered, never alerted** |
| `media_event_outbox` FAILED + `poison=true` | `V58__media_event_outbox.sql:29`, `MediaEventOutbox.java:37-41` | counter `media.outbox.dead_letter` — same |
| `media_asset` FAILED | `MediaAsset.java:44` (reaper writer at `MediaPendingReaper.java:79-81`) | none |
| `webhook_delivery` FAILED | `WebhookDelivery.java:43-48` | none |
| `webhook_subscription` AUTO_PAUSED | `WebhookSubscription.java:46`, writer `WebhookDeliveryWorker.java:229` | none — **a vendor's integration silently stops** |
| `tenants.keycloak_deprovisioned_at` NULL on an OFFBOARDED tenant | `Tenant.java:76-77` (V49) | ERROR log only |
| onboarding stalled | `OnboardingEventPublisher.java:41` `ONBOARDING_STALLED` | event published; **no operator identity exists to receive it** (see memory `arch_no_platform_operator`) |
| scrape target down | `prometheus.yml` jobs | `ServiceDown` — fires, reaches a dev sink (F-4) |

Full meter inventory (`Counter.builder`/`counter(` across `core-java/src/main`): `jtoye.orders.*`,
`jtoye.payment.failed`, `jtoye.payments.failed`, `jtoye.ratelimit.fail_open`, `jtoye.revenue.pennies`,
`media.outbox.dead_letter`, `media.outbox.resurrected`, `payment.outbox.dead_letter`,
`payment.outbox.resurrected`, `tenant.context.missing`. No `Gauge.builder` anywhere.

<interfaces>

### House style for all three gates (non-negotiable, copied from the existing set)

`scripts/check-branch-behind-base.sh` and `k8s/scripts/check-env-contract.sh` are the models:
`set -euo pipefail`; `SCRIPT_DIR`/`REPO_ROOT` from `$BASH_SOURCE`; `fail()` → exit 1,
`parse_fail()` → **exit 2**; a header comment naming the defect each assertion pins; a final
one-line `PASS:` summary.

**Exit-code contract, uniform: 0 = clean · 1 = violation · 2 = VOID.** Exit 2 on: missing `jq`/
`curl`/`docker`, unreachable Prometheus/Alertmanager/EOL API, a discovery step that returns an EMPTY
set, or an unparseable register. **"Found nothing" is never "clean"** — an empty discovery is the
exact shape that made ~22 Phase-26 criteria vacuous.

### Known vacuous shapes this plan must avoid (each has bitten this repo)

1. **Already-0 grep.** Never assert `count == 0` without first proving the pattern can match ≥1 on a
   deliberately broken input.
2. **`cmd | grep -q X` under `pipefail` inverts** — SIGPIPE→141 on match. Use here-strings:
   `grep -q X <<<"$out"`.
3. **`promtool check rules` passing a rule whose metric does not exist.** Syntax validity is not
   liveness. That is precisely F-1, and it is why `check-alert-liveness.sh` queries the live series
   API instead.
4. **Gradle `UP-TO-DATE` executing nothing.** Any Gradle verification here runs `cleanTest` first.
5. **Reading stale `core-java/build/`.** The live output dir is `build-local`.
6. **A gate that fails OPEN.** Missing tooling / empty output must exit 2.
7. **Sorted-output blindness.** `kubectl kustomize` sorts map keys; do not assert on ordering.

### Static vs runtime gate split — follow the existing precedent exactly

`ci-cd.yaml:374-378` records the rule: `check-runtime-freshness.sh` is **deliberately absent** from
CI because a runner has no running containers, so it could only ever exit 2 there, and a
permanently-VOID job invites a `|| true` that turns it into a gate measuring nothing.

Apply the same split:

| Gate | Needs a running stack? | Home |
|---|---|---|
| `check-terminal-states.sh` | no (source + YAML + Markdown) | **CI**, new `ops-contracts` job |
| `check-dependency-horizons.sh` | no (files + one HTTPS fetch) | **CI**, same job |
| `check-alert-liveness.sh` | **yes** (Prometheus + Alertmanager APIs) | **NOT CI.** Documented in `k8s/DEPLOYMENT.md` "Runtime-parity gates" next to `check-runtime-freshness.sh`; run before any phase hand-back |

### Register schema — `docs/ops/terminal-states.yaml`

```yaml
version: 1
states:
  - id: TS-01
    name: webhook.deliveries.dlq
    kind: amqp_dlq                # amqp_dlq | outbox_poison | entity_status | infra_target
    locator: "core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java:62"
    what_stops: "Vendor webhook deliveries that exhausted retries. The vendor's integration silently stops receiving events."
    owner: vendor-integrations
    detection:
      signal: 'rabbitmq_queue_messages_ready{queue="webhook.deliveries.dlq"}'
      alert: DlqNotEmpty          # must exist in alerts.yml
    runbook: "docs/runbooks/alerts.md#dlqnotempty"
    operator_action: "Inspect with rabbitmqadmin get ackmode=reject_requeue_true (NEVER ack). Decide replay vs discard. Record the decision."
```

Every field is mandatory. `detection.alert: null` is permitted **only** with a sibling
`deferred: {reason: "...", expires: YYYY-MM-DD, tracked_by: "<plan or issue>"}` — and an **expired**
deferral is a FAIL, so a deferral cannot become permanent. This is the same anti-rot rule
`check-env-contract.sh` already enforces on its allowlist.

### Discovery surface — declared, not magical (read this before calling the mechanism general)

`check-terminal-states.sh` discovers from an **explicitly declared** surface, listed in a data block
at the top of the script:

| Rule | Source | Pattern |
|---|---|---|
| D-1 DLQs | `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java` | `^\s*public static final String [A-Z_]*DLQ[A-Z_]* = "(...)"` |
| D-2 poison outboxes | `core-java/src/main/resources/db/migration/*.sql` | `^\s*poison\s+BOOLEAN` and `ADD COLUMN poison` |
| D-3 terminal entity statuses | a declared file list (`MediaAsset.java`, `WebhookDelivery.java`, `WebhookSubscription.java`, `PaymentEventOutbox.java`, `MediaEventOutbox.java`) | enum constants matching `^(FAILED\|AUTO_PAUSED)$` |
| D-4 scrape targets | `infra/monitoring/prometheus/prometheus.yml` | `job_name:` values |

**Honest limitation, to be stated in the script header and the SUMMARY:** this catches a new terminal
state added *to a file already on the surface* (a new DLQ constant, a new `FAILED` enum constant, a
new poison column, a new scrape job). It does **not** discover a terminal state invented in a brand
new file or expressed in a shape nobody anticipated. Extending the declared file list is itself a
reviewed act. **A fully general "find every way this system can silently stop" scanner is
over-engineering at this project's size** — it would be a static-analysis project, it would generate
false positives faster than one developer can triage them, and an over-firing gate gets `|| true`'d,
which is strictly worse than no gate. The strongest maintainable version is: *enumerate exhaustively
once, by hand, from the census this plan already did; then make the register unable to fall behind
the files that census came from.* Residual risk: a genuinely novel shape. Mitigation: the phase-close
checklist asks "did this phase add a state where work stops?" and the register review is a PR-template
line.

### GAP 2 — where the EOL dates come from, and how they stay honest

**Source:** `https://endoflife.date/api/<product>.json` — machine-readable, one object per cycle with
an `eol` field that is either an ISO date or `false`. Verified live for 9 of the 11 distinct products
(`minio` returns 404; `alpine` 301-redirects to `alpine-linux`).

**The three honesty mechanisms** — a hand-maintained date nobody refreshes is the same failure in a
new costume, so the manifest's date is never the authority:

1. **Re-fetch and compare.** The gate fetches the live `eol` for the recorded `eol_product` +
   `eol_cycle` and **fails if it disagrees with the cached `eol_date`**. The cached copy exists only
   so the manifest is readable and diffable offline; it can never drift silently.
2. **Offline is VOID.** Fetch failure, non-200, unknown slug, or cycle-not-found → exit **2**.
   Never a pass. A `--offline` flag is deliberately **not** provided.
3. **`--refresh` writes the cache**, mirroring `scripts/docs-freshness.sh --write`, so the correction
   path is one command and the diff is reviewable.

**Recorded discrepancy, not smoothed over:** endoflife.date gives rabbitmq 3.12 EOL `2024-02-21`;
`rabbitmq.com/release-information` (cited in `MESSAGING-BROKER-EVALUATION-2026-07-26.md` §6 #2, fetched
2026-07-26) gives `2024-02-29`. Eight days apart, both long past. The manifest therefore carries
**both**: `eol_date` (machine-checked) and `vendor_source` (the authoritative human URL). Where they
disagree the gate reports a NOTE, not a failure — failing on an 8-day cataloguing difference would
train the operator to ignore the gate.

**Products with no EOL feed** (`minio`, `minio/mc`, `ollama`) get `eol_product: null` plus a mandatory
`manual_review: {last_checked: YYYY-MM-DD, expires: YYYY-MM-DD, url: "..."}`, and an expired
`manual_review` is a FAIL. **Unpinned `:latest` tags are a distinct state**: `pin: "latest"` requires
`unpinned: true` with a reasoned dated exemption, because a floating tag is not a pin and its horizon
is unknowable by construction.

**Warn window:** `HORIZON_WARN_DAYS`, default **90**. Rationale: an image upgrade in this repo is a
rebuild-plus-full-E2E change (`CLAUDE.md`: "rebuild ALL containers"), which is a weekend of work for
one developer; 90 days is two comfortable planning cycles. Overridable via env for a targeted run.

### GAP 3 — the minimum honest baseline, and the line past which it becomes a performance project

**What 27-04 actually needs** is not a web benchmark. It needs *how long one message takes on the two
consumers whose concurrency it is about to set*. So the baseline has two arms, and only two:

- **Arm A — HTTP read path.** `hey` against `GET /shops` and `GET /products?page=0&size=20` with a
  real bearer token, at the declared design point. Records p50/p95/p99, throughput, and the **full
  status-code distribution**. **Asserts every response is 2xx and fails otherwise** — this is the
  anti-vacuity guard for F-7's 401 trap.
- **Arm B — AMQP consumer path.** Publish N synthetic events to `media.process` and
  `webhook.deliveries` via a `@Profile("local")`-guarded dev endpoint or the management API, then
  measure wall-clock drain to `messages == 0` via `rabbitmqctl`, plus per-message service time. This
  yields the single number 27-04 needs: *messages/sec/consumer*, from which prefetch and
  `concurrentConsumers` follow.

**Declared budget** lives in `infra/load-testing/budget.yaml` (the "config-declared budget" the
cross-cutting web-perf contract asks for) and seeds from the values already written in prose at
`load-test.sh:160-171`: read p95 < 200 ms, write p95 < 500 ms, error rate 0 %, > 100 req/s/instance.
These are *recorded as inherited assumptions, not as measured truth*, and the first baseline run
either confirms them or replaces them with the measured number and says so.

**Output artifact:** `infra/load-testing/baselines/YYYY-MM-DD-<short-sha>.md`, committed, recording
the exact command, tool + version, host CPU/RAM/load, container versions, the raw tool output, and
the status distribution. A number with no provenance is not a baseline.

**Explicitly NOT in scope** (this is where a capacity model metastasises): CI-run load tests (a
shared runner's numbers are noise, and a noisy gate gets disabled); soak/endurance; distributed load
generation; profiling or flame graphs; tuning anything; Gatling/k6/Locust adoption; contract tests
and fault injection (P3-13's other two halves — separate work).

</interfaces>
</context>

<decisions>

- **D-01 — GAP 1's deliverable is a register plus four cross-references, not a set of alerts.** The
  alerts are 27-03's job. This plan delivers the contract they must satisfy. Rejected alternative:
  "just add the missing alerts here" — that fixes messaging a fifth time and leaves the next terminal
  state undetected.
- **D-02 — The discovery surface is declared, not inferred.** See `<interfaces>`. Accepts a named
  residual risk in exchange for a gate that will not false-positive itself into being disabled.
- **D-03 — Alert liveness is asserted against the LIVE series API, never against `promtool`.** F-1 is
  a rule that any syntax checker accepts. The assertion is: *for each alert expr, every metric
  selector matches ≥ 1 series in the running Prometheus*.
- **D-04 — Scrape-target health is a hard FAIL, not a warning.** F-3 left 8 of 14 alerts dataless for
  an unknown period and nothing said so. A down target is a detection outage.
- **D-05 — Detection includes delivery.** F-4 proves an alert can fire, be accepted, be delivered,
  and still tell nobody. `check-alert-liveness.sh` injects a synthetic alert through the Alertmanager
  API and asserts it lands at the configured destination.
- **D-06 — The Slack receiver is added ADDITIVELY and is inert when unconfigured.** `.env` already
  carries `ALERTMANAGER_SLACK_WEBHOOK_URL`/`_CHANNEL` with no receiver behind them. Email stays
  exactly as it is (Incremental Betterment: do not trade away a working good). When the webhook var
  is empty the rendered config is byte-identical to today's — asserted, not assumed.
- **D-07 — The rabbitmq scrape moves to `/metrics/per-object` WITH a drop rule for SSE anonymous
  queues** in the same change. Verified working on this broker; the cardinality mitigation is part of
  the fix, not a follow-up.
- **D-08 — `prometheus.yml` gets an explicit per-runtime comment at the `core-java` job** stating that
  9090 is the compose/dev port and 9091 the k8s/prod management port, and that the two runtimes have
  different port topologies. The comment is not the gate; the target-health check is. The comment
  exists so the next person does not "fix" it back.
- **D-09 — EOL dates are fetched, never trusted from the file.** See `<interfaces>` GAP 2.
- **D-10 — This plan upgrades ZERO dependencies.** It records six past-horizon pins as reasoned,
  dated exemptions. `rabbitmq` is handed to 27-02 with the 4.2-is-EOL-in-five-days finding; the other
  five get exemptions expiring at dates chosen in Task 5 and become their own scoped work. Upgrading
  Keycloak, Node, Prometheus, Grafana and Alpine inside an operational-maturity spine is textbook
  scope creep and each carries independent runtime risk.
- **D-11 — The load baseline is two arms and is not wired into CI.** See `<interfaces>` GAP 3.
- **D-12 — `hey` is acquired via `go install` and its absence is VOID.** Go 1.26.5 is present. A
  baseline script that silently skips when no tool is installed is the "build reporting success while
  executing nothing" shape.
- **D-13 — No MCP tool for the register.** The cross-cutting agent-readiness contract asks for one
  per new core capability, or a recorded reason. Recorded reason: the register is operator-facing and
  cross-tenant by nature; the MCP server is tenant-scoped, and exposing cross-tenant failure counts
  through it would breach the recorded "no platform operator identity" boundary
  (memory `arch_no_platform_operator`). The machine-consumability obligation is met by making both
  registers machine-readable YAML with a documented schema.

</decisions>

<scope>

### IN SCOPE

1. Fix the two structurally-dead detection paths: the `core-java` scrape target (F-3) and the
   rabbitmq metrics path (F-1). These and only these — they are prerequisites, not the phase.
2. `docs/ops/terminal-states.yaml` — the register, populated from the F-9 census (≥ 9 rows).
3. `scripts/check-terminal-states.sh` — static CI gate, three cross-references.
4. `scripts/check-alert-liveness.sh` — runtime gate: target health, selector liveness, delivery proof.
5. Runbook completion: a section for all 14 alerts and every register row.
6. `infra/dependency-horizons.yaml` + `scripts/check-dependency-horizons.sh`.
7. `infra/load-testing/` minimum honest baseline: `baseline.sh`, `budget.yaml`, `README.md`, one
   committed dated baseline artifact.
8. CI wiring (`ops-contracts` job), `k8s/DEPLOYMENT.md` gate documentation, `docs/metrics.json`
   reconcile.

### DEFERRED, each with a named trigger

| Deferred | Trigger that un-defers it |
|---|---|
| Staging/prod alerting stack (there are **no** Prometheus/Alertmanager manifests in `k8s/` — verified; monitoring is compose-only) | The first non-local deploy expected to page a human. Tracked with ADR-0002's undeclared-broker gap. |
| A real (non-Mailhog) notification destination | Same trigger. The Slack receiver wiring lands now (D-06); pointing it at a real workspace is an operator act. |
| Upgrading the five non-broker past-EOL pins | Each exemption's `expires` date in `infra/dependency-horizons.yaml`. The gate fails on that date. |
| node-exporter + the two `DiskSpace*` rules | Already deferred with a stated reason at `alerts.yml:174-181`. Un-defers when node-exporter is deployed. Carried forward verbatim, not re-litigated. |
| DLQ replay / requeue tooling | 27-03 or later. The register's `operator_action` is a documented manual procedure for now. |
| Extending the discovery surface to `edge-go` and the frontend | Mirrors the identical, already-recorded limitation in `check-env-contract.sh`. Un-defers when a terminal state is added outside core-java. |
| Onboarding-stalled detection reaching a human | Blocked on the recorded absence of any platform-operator identity. Register row carries `deferred` with `tracked_by`. |

### SCOPE CREEP — named so it can be refused

SLOs and error budgets · on-call rotation or paging policy · Grafana dashboard rework · log
aggregation (Loki/ELK) · distributed-tracing coverage expansion · auto-remediation · a general
static-analysis scanner for "any way the system can stop" (D-02) · upgrading dependencies (D-10) ·
k6/Gatling adoption or load tests in CI (D-11) · contract tests and fault injection (the other two
thirds of P3-13) · refactoring `RabbitMQConfig.java`.

</scope>

<tasks>

<task type="auto">
  <name>Task 1: Resurrect the two dead detection paths (F-3 core-java target, F-1 rabbitmq labels)</name>
  <files>infra/monitoring/prometheus/prometheus.yml, infra/monitoring/prometheus/alerts.yml</files>
  <read_first>
    - infra/monitoring/prometheus/prometheus.yml (lines 32-46 core-java job, 91-98 rabbitmq job)
    - infra/monitoring/prometheus/alerts.yml (lines 236-253 messaging_alerts)
    - core-java/src/main/resources/application-prod.yml lines 96-108 (why 9091 exists)
    - docker-compose.full-stack.yml lines 165-215 (dev profile + the exposure opt-in at :209) and 241-243 (the 9090-9091 host publish range, which is a --scale artifact and NOT a second listener)
    - k8s/base/core-java-deployment.yaml lines 24-33, 55-65 (the prod annotations that are correct and must not be changed)
  </read_first>
  <action>
Two surgical edits to `infra/monitoring/prometheus/prometheus.yml`.

**(a) core-java job → `core-java:9090`.** Replace the comment block at lines 32-35 with one that
states the *actual* topology: prod (`SPRING_PROFILES_ACTIVE: prod`, k8s) serves actuator on the
separate management port 9091; dev/compose runs the dev profile, sets no `management.server.port`,
and opts the scrape endpoint in on the app port via
`MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` at `docker-compose.full-stack.yml:209`. State that this
Prometheus instance serves the COMPOSE runtime only (there are no monitoring manifests in `k8s/`), so
9090 is correct here and `k8s/base/core-java-deployment.yaml`'s `prometheus.io/port: "9091"` is
correct there. Warn that "fixing" this back to 9091 re-blinds 8 of 14 alerts.

**(b) rabbitmq job → `metrics_path: '/metrics/per-object'`** plus a `metric_relabel_configs` drop:

```yaml
    metric_relabel_configs:
      # The SSE fan-out declares an AnonymousQueue per replica
      # (order.state-changes.sse.<random>); the name changes on every restart, so
      # per-object scraping would grow labels without bound. Durable queues only.
      - source_labels: [queue]
        regex: 'order\.state-changes\.sse\..*'
        action: drop
```

Do NOT change `StompBrokerLag` in this task beyond what the render requires — 27-03 owns alert
content. If and only if the existing expr still matches nothing after the metrics-path change (it
will: no queue is named `stomp-subscription*` or `amq.gen-*` on this broker — verified), leave it
alone and let Task 3's gate fail RED on it, so 27-03 inherits a *proven* finding rather than a claim.

Restart Prometheus (`docker compose -f infra/monitoring/docker-compose.monitoring.yml restart
prometheus` — `/-/reload` is not enabled, per `prometheus.yml:9-13`) and capture before/after.
  </action>
  <verify>
    <automated>
docker exec jtoye-prometheus wget -qO- 'http://localhost:9090/api/v1/targets?state=any' | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health)"' | sort
docker exec jtoye-prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=count(rabbitmq_queue_messages_ready%7Bqueue!%3D%22%22%7D)' | jq -c '.data.result'
    </automated>
  </verify>
  <acceptance_criteria>
    - **AC-1.1 (RED ON THE UNMODIFIED TREE — capture this FIRST).** PASS: every job in `/api/v1/targets` reports `health == "up"`. BREAK: the tree as it stands. Expected-RED, captured 2026-07-26 before any edit: `core-java down` with `lastError: dial tcp 172.18.0.2:9091: connect: connection refused`, and `up{job="core-java"} = 0`. Record the verbatim before/after JSON.
    - **AC-1.2 (RED ON THE UNMODIFIED TREE).** PASS: `count(rabbitmq_queue_messages_ready{queue!=""})` returns a value ≥ 13. BREAK: the tree as it stands. Expected-RED, captured 2026-07-26: `[]` (empty), while the control `sum(rabbitmq_queue_messages_ready)` returns `9`. Record both arms both times.
    - **AC-1.3.** After the change, `rabbitmq_queue_messages_ready{queue="webhook.deliveries.dlq"}` returns exactly `9`. Falsify by querying `queue="does.not.exist"` and confirming `[]` — proves the label matcher is doing work rather than matching everything.
    - **AC-1.4 (cardinality guard, and it must be shown to be capable of failing).** `count(rabbitmq_queue_messages_ready{queue=~"order\\.state-changes\\.sse\\..*"})` returns `[]`. Control that proves the drop rule is what did it: temporarily comment out `metric_relabel_configs`, restart, confirm the same query returns ≥ 1, restore, confirm `[]` again. Record both.
    - **AC-1.5.** `git diff k8s/base/core-java-deployment.yaml` is empty — the k8s annotations are correct for their runtime and must not be touched.
    - **AC-1.6.** `git diff infra/monitoring/prometheus/prometheus.yml | grep -c '^-.*job_name'` returns 0 — no job was removed or renamed.
  </acceptance_criteria>
  <done>Seven of seven scrape targets UP; queue-labelled series present with the 9 dead DLQ messages visible by queue name; SSE anonymous queues dropped and the drop proven load-bearing; k8s manifests untouched.</done>
</task>

<task type="auto">
  <name>Task 2: docs/ops/terminal-states.yaml — the register</name>
  <files>docs/ops/terminal-states.yaml</files>
  <read_first>
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java (lines 27, 32, 62, 78 — the four DLQ constants; lines 86-140, 296-310, 356-370 — the DLX bindings)
    - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java (lines 260-305 — the poison/dead-letter path and the counter it increments)
    - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java (lines 200-230)
    - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java (lines 72-92 — the delete-then-FAILED path; this is 27-01's defect and the register must describe the state, not fix it)
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookDeliveryWorker.java (lines 200-240 — the AUTO_PAUSED flip)
    - core-java/src/main/java/uk/jtoye/core/webhook/WebhookSubscription.java lines 39-48, WebhookDelivery.java lines 43-48, MediaAsset.java line 44
    - core-java/src/main/java/uk/jtoye/core/tenant/Tenant.java lines 74-80 (V49 keycloak_deprovisioned_at)
    - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java line 41
    - infra/monitoring/prometheus/alerts.yml (the 14 existing alert names)
  </read_first>
  <action>
Create `docs/ops/terminal-states.yaml` using the schema in `<interfaces>`. Populate one row for every
state in the F-9 census — **minimum 9 rows**:

TS-01..04 the four DLQs · TS-05 `payment_event_outbox` poison · TS-06 `media_event_outbox` poison ·
TS-07 `media_asset` FAILED (note in `what_stops` that the reaper *deletes the object* first —
`MediaPendingReaper.java:79` — so this state is unrecoverable by retry; 27-01 owns the fix, the
register owns the description) · TS-08 `webhook_delivery` FAILED · TS-09 `webhook_subscription`
AUTO_PAUSED · TS-10 `tenants.keycloak_deprovisioned_at` NULL on an OFFBOARDED tenant · TS-11
onboarding stalled · TS-12 scrape target down (`infra_target`).

Rules while filling it in:

- `detection.alert` names an alert 27-03 will create. Since it does not exist yet, every such row
  MUST carry `deferred: {reason, expires, tracked_by: "27-03"}` — and Task 3's gate must FAIL on an
  expired `expires`. Set `expires` to a date inside this milestone so the deferral cannot outlive
  the phase.
- TS-11 (onboarding stalled) carries `owner: UNASSIGNED` with `deferred.reason` citing the recorded
  absence of a platform-operator identity. **Do not invent an owner.** A fabricated owner is worse
  than a recorded gap.
- `operator_action` must be a runnable procedure. For DLQ rows it must include the
  `ackmode=reject_requeue_true` non-destructive peek and an explicit **"NEVER ack"** — the wrong
  ackmode destroys the evidence, which on `webhook.deliveries.dlq` today means destroying nine real
  vendor events.
- `what_stops` is written from the **user's** point of view, not the system's ("the vendor's
  integration silently stops receiving events"), because that is what makes an operator act.

Sort rows by `id`. No alert content, no code changes in this task.
  </action>
  <verify>
    <automated>
jq -e '.' /dev/null >/dev/null 2>&1 || { echo "jq missing"; exit 2; }
python3 - <<'EOF' 2>/dev/null || ruby -ryaml -e 'puts YAML.load_file("docs/ops/terminal-states.yaml")["states"].length' 2>/dev/null || echo "NO-YAML-PARSER(exit 2 territory)"
EOF
grep -c '^  - id: TS-' docs/ops/terminal-states.yaml
    </automated>
  </verify>
  <acceptance_criteria>
    - **AC-2.1.** `grep -c '^  - id: TS-' docs/ops/terminal-states.yaml` returns ≥ 9. Falsify: delete one row, re-count, confirm the number drops — proves the anchor matches rows and not prose.
    - **AC-2.2.** The four live DLQ names appear verbatim: `for q in order.state-changes.dlq payment.events.dlq webhook.deliveries.dlq media.process.dlq; do grep -q "$q" docs/ops/terminal-states.yaml <<<"$(cat docs/ops/terminal-states.yaml)" || echo "MISSING $q"; done` prints nothing. **Here-string form is mandatory** — the `cmd | grep -q` shape inverts under `pipefail` via SIGPIPE→141.
    - **AC-2.3.** Every row has all mandatory keys. Assert by count equality: the number of `- id:` occurrences equals the count of each of `name:`, `kind:`, `locator:`, `what_stops:`, `owner:`, `detection:`, `runbook:`, `operator_action:`. Falsify: delete one `owner:` line and confirm the equality breaks.
    - **AC-2.4.** Every `locator:` points at a real file:line. Verify by extracting each and running `test -f` on the path plus `sed -n "<line>p"` returning non-empty. Falsify: point one locator at `does/not/exist.java:1` and confirm the check fails.
    - **AC-2.5.** The DLQ rows' `operator_action` contains `reject_requeue_true` and `NEVER ack`. Falsify: remove the phrase from one row and confirm the grep count drops.
    - **AC-2.6.** Every `deferred.expires` parses as a date and is in the future *at authoring time*: `date -d "<expires>" +%s` succeeds and exceeds `date +%s`. Falsify: set one to `2020-01-01` and confirm the comparison fails.
    - **AC-2.7.** No row invents an owner for TS-11: `grep -A12 'id: TS-11' docs/ops/terminal-states.yaml` shows `owner: UNASSIGNED` and a `deferred.reason` naming the no-platform-operator constraint.
  </acceptance_criteria>
  <done>A ≥9-row register in which every terminal state found by the F-9 census is described with a real locator, a user-visible consequence, an owner (or an honestly-recorded absence), a runnable operator action, and a dated deferral where detection does not yet exist.</done>
</task>

<task type="auto">
  <name>Task 3: scripts/check-terminal-states.sh — the static CI gate (three cross-references)</name>
  <files>scripts/check-terminal-states.sh</files>
  <read_first>
    - k8s/scripts/check-env-contract.sh (house style in full: data blocks at the top, allowlist-hygiene rules, classification summary, PASS one-liner)
    - scripts/check-branch-behind-base.sh (the exit-2 VOID discipline and the "offline is VOID, not clean" header section — reuse that reasoning verbatim in shape)
    - docs/ops/terminal-states.yaml (Task 2)
    - infra/monitoring/prometheus/alerts.yml
    - docs/runbooks/alerts.md (heading conventions: `## AlertName`)
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  </read_first>
  <action>
Create `scripts/check-terminal-states.sh` implementing the declared discovery surface from
`<interfaces>` and three cross-references:

**X-1 discovery → register.** Run D-1..D-4. Every discovered terminal state must have a register row.
A discovered state with no row is the headline failure: *"a new terminal failure state was added with
no detection path"*. Message must name the state, the file it was found in, and the rule that found
it.

**X-2 register → alert.** Every `detection.alert` that is non-null must appear as `- alert: <name>` in
`alerts.yml`. A null alert requires an unexpired `deferred` block; an **expired** `deferred.expires`
is a FAIL naming the row and the date.

**X-3 register → runbook.** Every non-null `detection.alert` must have a `## <AlertName>` heading in
`docs/runbooks/alerts.md`, and every `runbook:` anchor must resolve to a real heading. Additionally
assert the inverse for alerts.yml as a whole: **every** alert in `alerts.yml` has a runbook section —
this is F-5, currently 14 alerts vs 10 sections.

Hard requirements:

- **Exit 2 (VOID)** on: missing `jq`; no YAML parser available; a register that fails to parse; **any
  discovery rule returning an EMPTY set** (if D-1 finds zero DLQs, the regex broke — that is not
  "no DLQs exist"). Print which rule produced the empty set.
- **Every grep against captured output uses a here-string.** No `cmd | grep -q`.
- **Self-exclusion:** the script names the states it forbids-without-detection, so it will match its
  own text. Restrict every discovery grep to its declared source path — never a repo-wide scan — and
  state this in the header (the "a doc rule that must name the string it forbids" trap).
- Print a classification summary: discovered-per-rule counts, register rows, matched, deferred,
  expired, missing-alert, missing-runbook. A reviewer must see the inventory shape without reading
  the code.
- Header comment: what each cross-reference pins, the F-numbers from this plan's problem statement,
  the declared-surface limitation from `<interfaces>` with the deferred extension named, and that the
  script contributes 0 to `docs/metrics.json` (`docs-freshness.sh` counts no bash).

Do NOT touch `.github/workflows/ci-cd.yaml` — Task 7 is its single writer.
  </action>
  <verify>
    <automated>bash -n scripts/check-terminal-states.sh && bash scripts/check-terminal-states.sh; echo "exit=$?"</automated>
  </verify>
  <acceptance_criteria>
    - **AC-3.1.** `bash scripts/check-terminal-states.sh` exits 0 on the post-Task-2 tree and prints the classification summary with a non-zero count for every discovery rule.
    - **AC-3.2 (X-1 falsifiable).** BREAK: add `public static final String DEADBEEF_DLQ = "deadbeef.dlq";` to `RabbitMQConfig.java`. Expected-RED: exit 1 naming `deadbeef.dlq` and rule D-1. Restore → exit 0. Record both outputs verbatim. *This is the single criterion that proves the mechanism is real rather than aspirational.*
    - **AC-3.3 (X-1 second arm).** BREAK: add a `FAILED` constant to an enum on the declared file list that has no register row. Expected-RED: exit 1 naming it and rule D-3. Restore → exit 0.
    - **AC-3.4 (X-2 falsifiable).** BREAK: set one row's `detection.alert` to `NoSuchAlert` and remove its `deferred` block. Expected-RED: exit 1 naming the row and `NoSuchAlert`. Restore → exit 0.
    - **AC-3.5 (anti-rot falsifiable).** BREAK: set one `deferred.expires` to `2020-01-01`. Expected-RED: exit 1 naming the row as an expired deferral. Restore → exit 0.
    - **AC-3.6 (X-3, RED ON THE UNMODIFIED TREE until Task 4 lands).** PASS: every alert in `alerts.yml` has a `## ` section in `docs/runbooks/alerts.md`. Expected-RED before Task 4, and it must name exactly `KeycloakDown`, `RedisDown`, `PaymentFailureSpike`, `StompBrokerLag` — 14 alerts vs 10 sections (verified 2026-07-26). Record this RED run; it is the F-5 fail-direction evidence.
    - **AC-3.7 (VOID falsifiable, both arms).** BREAK-a: `PATH=/nonexistent bash scripts/check-terminal-states.sh` → **exit 2**, not 0 and not 1. BREAK-b: temporarily point D-1's source path at an empty temp file → **exit 2** with a message naming D-1 as the empty rule. Both restored → exit 0. *A gate that exits 0 when it found nothing is the failure this whole plan exists to prevent.*
    - **AC-3.8 (self-exclusion).** `bash scripts/check-terminal-states.sh` does not report a violation caused by its own text. Falsify: temporarily widen one discovery grep to a repo-wide scan and confirm it then reports the script itself; restore.
    - **AC-3.9.** No `| grep -q` anywhere: `grep -c '| *grep -q' scripts/check-terminal-states.sh` returns 0. Control: `grep -c 'grep -q' scripts/check-terminal-states.sh` returns ≥ 1, proving the pattern is capable of matching and the 0 above is not an already-0 grep.
  </acceptance_criteria>
  <done>Three cross-references green on the post-Task-4 tree; X-1 proven RED against both a new DLQ constant and a new FAILED enum constant; the expired-deferral rule proven RED; both VOID arms proven exit-2; the self-exclusion trap avoided and demonstrated.</done>
</task>

<task type="auto">
  <name>Task 4: scripts/check-alert-liveness.sh + runbook completion + additive Slack receiver</name>
  <files>scripts/check-alert-liveness.sh, docs/runbooks/alerts.md, docs/runbooks/terminal-states.md, infra/monitoring/alertmanager/alertmanager.yml.tmpl, infra/monitoring/alertmanager/entrypoint.sh</files>
  <read_first>
    - scripts/check-runtime-freshness.sh (the closest sibling: a runtime gate that is deliberately NOT in CI; copy its header rationale and its exit-2 discipline)
    - infra/monitoring/prometheus/alerts.yml (all 14 exprs — the selector-extraction input)
    - infra/monitoring/alertmanager/alertmanager.yml.tmpl and entrypoint.sh (the sed-render mechanism; note the explicit "no ${VAR} syntax" warning and the `amtool check-config` fail-fast)
    - docs/runbooks/alerts.md (the ServiceDown section at lines 20-83 is the template: What it means / Expected impact / First-response steps / Escalation)
    - docs/ops/terminal-states.yaml (Task 2)
  </read_first>
  <action>
**(a) `scripts/check-alert-liveness.sh`** — the runtime gate. Three assertions:

- **L-1 target health.** Every job in `/api/v1/targets` reports `health == "up"`. Any `down` → exit 1
  naming the job, its `scrapeUrl` and its `lastError`. **Zero targets discovered → exit 2.**
- **L-2 selector liveness.** For each rule in `/api/v1/rules`, extract every metric selector from
  `.query` and query `/api/v1/series?match[]=<selector>`. Empty result → exit 1 naming the alert, the
  selector, and — this is the operator-useful part — the same selector with its label matchers
  stripped, so the message says *"matches 0 series; without labels it matches N"*. That one line is
  the entire diagnosis of F-1. Parse-failure on any expr → exit 2, never a skip. Maintain a small
  reasoned exemption list for alerts whose metric legitimately has no series until an event occurs
  (`tenant_context_missing_total`, `jtoye_payment_failed_total` — counters that are only registered
  on first increment), each entry `NAME|reason`, with the same hygiene rules as
  `check-env-contract.sh`: empty reason → FAIL, stale entry (now matching) → FAIL as STALE.
- **L-3 delivery.** POST a synthetic alert (`alertname=SyntheticDeliveryProbe`, a distinct label so it
  cannot be confused with a real one) to `/api/v2/alerts`, then assert it reached the configured
  destination — in the compose runtime, poll Mailhog's `/api/v2/search?kind=containing&query=
  SyntheticDeliveryProbe` until found or timeout. Also assert `alertmanager_notifications_failed_total`
  did not increase across the probe. Unreachable Alertmanager, unreachable destination, or no
  inspectable destination → **exit 2**. Silence/expire the synthetic alert afterwards so it does not
  linger.

Header must state, in the `check-runtime-freshness.sh` idiom, why this gate is **not** in CI: a
runner has no Prometheus, so it could only ever exit 2, and a permanently-VOID job invites a
`|| true`.

**(b) Runbook completion.** Add `## KeycloakDown`, `## RedisDown`, `## PaymentFailureSpike`,
`## StompBrokerLag` to `docs/runbooks/alerts.md` in the existing four-heading shape. `StompBrokerLag`'s
section must record that this rule matched **zero series** before Task 1 and what that looked like, so
the next reader recognises the shape. Do not renumber or restructure existing sections.

**(c) `docs/runbooks/terminal-states.md`** — one section per register row: what stopped, how to see it,
what to do, what NOT to do. The DLQ sections carry the non-destructive-peek recipe and the **NEVER
ack** warning. This is the runbook half the register's `runbook:` anchors point at for rows whose
alert does not exist yet.

**(d) Additive Slack receiver (D-06).** Add `__SLACK_API_URL__`/`__SLACK_CHANNEL__` placeholders to
`alertmanager.yml.tmpl` and the matching `: "${ALERTMANAGER_SLACK_WEBHOOK_URL:=}"` /
`_CHANNEL` defaults + sed lines to `entrypoint.sh`, wiring the `.env` keys that already exist and
currently reach nothing. **The email receiver is unchanged and remains the default route.** When the
webhook var is empty the receiver block must be omitted entirely (not rendered empty), because
`amtool check-config` rejects an empty `api_url` and the entrypoint fail-fasts before exec — which
would take the whole monitoring stack down. Implement the omission with a conditional render in
`entrypoint.sh`; do **not** introduce `${VAR}` syntax into the template (the file's own header
forbids it, and Alertmanager passes it through literally).
  </action>
  <verify>
    <automated>
bash -n scripts/check-alert-liveness.sh
bash scripts/check-alert-liveness.sh; echo "exit=$?"
docker exec jtoye-alertmanager /bin/amtool check-config /etc/alertmanager/alertmanager.yml
    </automated>
  </verify>
  <acceptance_criteria>
    - **AC-4.1 (L-1, RED ON THE UNMODIFIED TREE — capture BEFORE Task 1).** PASS: all targets up. Expected-RED on the pre-Task-1 tree: exit 1 naming `core-java`, `http://core-java:9091/actuator/prometheus`, `connection refused`. Record verbatim. After Task 1: exit 0.
    - **AC-4.2 (L-2, RED ON THE UNMODIFIED TREE — capture BEFORE Task 1).** Expected-RED: exit 1 naming `StompBrokerLag`, selector `rabbitmq_queue_messages_ready{queue=~"stomp-subscription.*|amq[.]gen-.*"}`, `matches 0 series; without labels it matches 1`. Record verbatim. *This is the strongest fail-direction evidence in the phase: a rule that every syntax checker accepts and that cannot fire.*
    - **AC-4.3 (L-2 falsifiable on a passing tree).** BREAK: add a temporary alert `expr: jtoye_does_not_exist_total > 0` to `alerts.yml`, restart Prometheus. Expected-RED: exit 1 naming it and `matches 0 series; without labels it matches 0`. Remove, restart → exit 0.
    - **AC-4.4 (L-2 exemption hygiene).** BREAK-a: blank one exemption's reason → exit 1. BREAK-b: exempt an alert whose selector DOES match → exit 1 as STALE. Both restored → exit 0.
    - **AC-4.5 (L-3 delivery, falsifiable).** PASS: the synthetic alert is found at the destination within the timeout. BREAK: point `ALERTMANAGER_SMTP_SMARTHOST` at an unroutable host, restart Alertmanager, re-run → **exit 1 or 2, never 0**, and `alertmanager_notifications_failed_total{integration="email"}` increases. Restore. Record both, including the counter values before/after.
    - **AC-4.6 (VOID).** `docker stop jtoye-prometheus && bash scripts/check-alert-liveness.sh` → **exit 2**, not 0. Restart and re-confirm exit 0.
    - **AC-4.7 (runbook completeness, ties to AC-3.6).** After (b), `scripts/check-terminal-states.sh` X-3 exits 0. Falsify: delete the `## RedisDown` section → X-3 exits 1 naming it. Restore.
    - **AC-4.8 (Slack additivity — must be byte-exact, not "looks the same").** With `ALERTMANAGER_SLACK_WEBHOOK_URL` unset, the rendered `/etc/alertmanager/alertmanager.yml` is **byte-identical** to the file rendered by the pre-change entrypoint. Prove with `sha256sum` of both renders, captured before and after the edit. BREAK: set the var to a dummy URL, re-render, confirm the hash changes AND `amtool check-config` still exits 0.
    - **AC-4.9.** `git diff infra/monitoring/alertmanager/alertmanager.yml.tmpl | grep -c '^-.*email_configs'` returns 0 — the working email receiver was not traded away.
    - **AC-4.10.** `grep -c 'NEVER ack' docs/runbooks/terminal-states.md` equals the number of `kind: amqp_dlq` rows in the register (4). Falsify: remove one occurrence and confirm the equality breaks.
  </acceptance_criteria>
  <done>The runtime gate proves all targets up, every alert selector matches live series, and a synthetic alert demonstrably reaches its destination; all 14 alerts and all register rows have runbook sections; the Slack receiver is wired from existing config and proven byte-inert when unset.</done>
</task>

<task type="auto">
  <name>Task 5: infra/dependency-horizons.yaml + scripts/check-dependency-horizons.sh</name>
  <files>infra/dependency-horizons.yaml, scripts/check-dependency-horizons.sh</files>
  <read_first>
    - docker-compose.full-stack.yml (image pins at lines 14, 48, 81, 126, 144, 393, 415, 435, 460, 476)
    - infra/monitoring/docker-compose.monitoring.yml (lines 8, 34, 65, 95, 115)
    - core-java/Dockerfile lines 5, 27 · edge-go/Dockerfile line 7 · frontend/Dockerfile lines 5, 129 · mcp-server/Dockerfile lines 7, 21
    - k8s/base/*-deployment.yaml + k8s/base/pg-backup-cronjob.yaml (first-party GHCR images — these are OURS and have no upstream EOL; they need a distinct kind)
    - edge-go/go.mod line 3 · core-java/build.gradle.kts (JavaLanguageVersion 21) · gradle/wrapper/gradle-wrapper.properties (gradle 8.10.2)
    - scripts/docs-freshness.sh (the --write / check-mode duality to mirror for --refresh)
  </read_first>
  <action>
**(a) `infra/dependency-horizons.yaml`.** One row per distinct pinned artifact, using the schema in
`<interfaces>` GAP 2. Seed from the F-6 table — the fetched values are already verified, so the first
gate run should agree with the file exactly (and if it does not, that disagreement is itself the
finding).

Six rows are past horizon today. Each gets an `exemption` with a non-empty `reason`, a dated
`expires`, and a `tracked_by`:

- `rabbitmq` → `tracked_by: "27-02"`. The `reason` MUST record the finding that decides 27-02's
  target: **4.1 EOL 2026-01-30 (past), 4.2 EOL 2026-07-31 (five days from authoring), 4.3 `eol:false`
  — 4.3 is the only non-EOL series.**
- `keycloak`, `node`, `prometheus`, `grafana`, `alpine` → `tracked_by: "DEFERRED-27"` with an
  `expires` chosen deliberately and stated (D-10).

First-party GHCR images get `kind: first_party` and are exempt from EOL lookup by rule (we are
upstream), but still require a `pin` and are still checked for existence — so a new first-party image
cannot silently escape the manifest.

`minio`, `minio/mc`, `ollama` get `eol_product: null` + `manual_review` + `unpinned: true` (they
resolve `${VAR:-latest}`), with the recorded reason that a floating tag has no computable horizon.

Record `resolved_cycle` for floating tags with the evidence: `redis:7-alpine` → `7.4` because the
running container reports `v=7.4.8` (`redis-server --version`).

**(b) `scripts/check-dependency-horizons.sh`.**

- **H-1 coverage.** Extract every `image:` line from the declared manifest set and every `FROM` from
  the four Dockerfiles; each must have a manifest row. An image with no row → exit 1. **An extraction
  returning an empty set → exit 2.**
- **H-2 freshness of the cache.** For each row with a non-null `eol_product`, fetch
  `https://endoflife.date/api/<product>.json`, locate `eol_cycle`, and compare `eol` to the cached
  `eol_date`. Disagreement → exit 1 naming both values and the `--refresh` remedy.
- **H-3 horizon.** `eol` in the past, or within `HORIZON_WARN_DAYS` (default 90) → exit 1, unless an
  unexpired `exemption`/`manual_review` covers it. An **expired** exemption → exit 1 naming the row
  and the date.
- **H-4 hygiene.** Empty reason, duplicate row, or an exemption on a row that is no longer past
  horizon (STALE) → exit 1.
- **VOID conditions → exit 2:** missing `curl`/`jq`; non-200; slug 404; `eol_cycle` absent from the
  fetched cycle list; unparseable JSON; **the fetch host resolving to anything other than
  `endoflife.date`** (pin the host, follow no redirects to other hosts, treat the body as untrusted
  data — it is fetched into a gate that decides whether CI passes).
- `--refresh` rewrites `eol_date` for every row and prints a diff, mirroring
  `scripts/docs-freshness.sh --write`.
- Report the vendor-vs-catalogue discrepancy (rabbitmq `2024-02-21` vs `2024-02-29`) as a NOTE, never
  a failure.
  </action>
  <verify>
    <automated>bash -n scripts/check-dependency-horizons.sh && bash scripts/check-dependency-horizons.sh; echo "exit=$?"</automated>
  </verify>
  <acceptance_criteria>
    - **AC-5.1 (RED ON THE UNMODIFIED TREE — run this BEFORE writing any exemption).** PASS: no pin is past its horizon. Expected-RED with an empty exemption list: exit 1 naming **six** rows — `rabbitmq/3.12` (2024-02-21), `prometheus/2.48` (2023-12-28), `grafana/10.2` (2024-07-24), `keycloak/24.0` (2024-06-10), `node/20` (2026-04-30), `alpine/3.20` (2026-04-01). Record verbatim. Then add the exemptions and confirm exit 0. *The brief said one EOL dependency; the mechanism found six. That difference is the argument for the mechanism.*
    - **AC-5.2 (H-1 falsifiable).** BREAK: add `image: busybox:1.36` to `infra/monitoring/docker-compose.monitoring.yml`. Expected-RED: exit 1 naming `busybox:1.36` as having no horizon row. Restore → exit 0.
    - **AC-5.3 (H-2 falsifiable — this is the anti-rot proof).** BREAK: change `rabbitmq`'s cached `eol_date` to `2099-01-01`. Expected-RED: exit 1 reporting cached `2099-01-01` vs fetched `2024-02-21` and naming `--refresh`. Restore → exit 0. *This is the criterion that proves a hand-maintained date cannot rot.*
    - **AC-5.4 (H-3 warn-window falsifiable — and it must be shown to fire on a currently-PASSING row).** BREAK: run with `HORIZON_WARN_DAYS=100000`. Expected-RED: exit 1 naming `postgres/15` (EOL 2027-11-11) and `eclipse-temurin/21` (2029-12-31) — rows that pass at the default. Restore → exit 0. *Without this arm, "no row is inside the warn window" is an already-0 assertion.*
    - **AC-5.5 (expired exemption falsifiable).** BREAK: set `keycloak`'s `exemption.expires` to `2020-01-01` → exit 1 naming it. Restore.
    - **AC-5.6 (STALE exemption falsifiable).** BREAK: add an exemption to `postgres/15` (not past horizon) → exit 1 as STALE. Restore.
    - **AC-5.7 (VOID, three arms).** BREAK-a: `PATH=/nonexistent` → exit 2. BREAK-b: block DNS for endoflife.date (`--add-host endoflife.date:127.0.0.1` equivalent, or set a resolver override) → exit 2, **never 0**. BREAK-c: set one row's `eol_cycle` to `999` → exit 2 (cycle-not-found is VOID, not clean). All restored → exit 0.
    - **AC-5.8 (floating-tag resolution).** `redis` row records `resolved_cycle: "7.4"` and the gate passes, having looked up 7.4 (`eol: false`) and NOT "7". Falsify: set `eol_cycle: "7"` → exit 2 (cycle not found). Restore. *Proves the gate is not guessing at floating tags.*
    - **AC-5.9 (the 27-02 handoff is recorded, not remembered).** `grep -c '4\.2' infra/dependency-horizons.yaml` ≥ 1 and the rabbitmq exemption reason contains both `2026-07-31` and `4.3`. Falsify: remove the sentence and confirm the grep drops to 0.
    - **AC-5.10 (--refresh round-trip).** `--refresh` on a clean tree produces **no** diff (`git diff --quiet infra/dependency-horizons.yaml`). After AC-5.3's break, `--refresh` restores the correct value and the subsequent check exits 0.
  </acceptance_criteria>
  <done>Every pinned image and toolchain carries a horizon row; six past-horizon pins are recorded as reasoned dated exemptions rather than silently upgraded; the cache is proven unable to rot; every VOID arm exits 2; the 4.3-not-4.2 finding is written into the file 27-02 will read.</done>
</task>

<task type="auto">
  <name>Task 6: the minimum honest load baseline (two arms, status-asserted, dated artifact)</name>
  <files>infra/load-testing/baseline.sh, infra/load-testing/budget.yaml, infra/load-testing/README.md, infra/load-testing/baselines/.gitkeep</files>
  <read_first>
    - infra/load-testing/load-test.sh in full (it is the good prior art to EXTEND, not replace — Incremental Betterment; note the KC_SEED_USER_PASSWORD handling at lines 27-29 which must be preserved verbatim)
    - docs/guides/TESTING.md lines 560-570 (references a README.md that does not exist — this task creates it)
    - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java (queue names for arm B)
    - core-java/src/main/java/uk/jtoye/core/media/MediaProperties.java (the media pipeline budget knobs arm B measures against)
    - .planning/research/ARCHITECTURE.md line 243 (the "tens/sec" design point)
    - docs/analysis/REMEDIATION-BACKLOG-2026-07-08.md line 134 (P3-13)
  </read_first>
  <action>
**(a) `infra/load-testing/budget.yaml`** — the declared budget. Seed from the prose at
`load-test.sh:160-171` (read p95 < 200 ms, write p95 < 500 ms, error rate 0 %, >100 req/s/instance)
and the `tens/sec` event design point, each marked `source: inherited-assumption` with the file:line
it came from. Add `status: unvalidated` until the first baseline run either confirms or replaces it.
**Do not present an inherited assumption as a measured number.**

**(b) `infra/load-testing/baseline.sh`** — two arms, no more.

- Arm A (HTTP): `hey` against `GET /shops` and `GET /products?page=0&size=20` with a real bearer
  token obtained the same way `load-test.sh` already does. Parse `hey`'s status-code distribution and
  **exit non-zero if any response is not 2xx**. State in the header that `GET /shops` returns 401
  without a token (verified) and that an unasserted 401 flood is the canonical vacuous baseline.
- Arm B (AMQP consumer): publish N synthetic messages to `media.process` and `webhook.deliveries`,
  poll `rabbitmqctl list_queues name messages` until 0 or timeout, and report wall-clock drain and
  messages/sec/consumer. Assert the corresponding DLQ depth did **not** increase during the run —
  otherwise the "drain" was messages dying, not messages being processed. **This assertion is the arm
  B analogue of arm A's status check and is equally load-bearing.**
- **Tool acquisition is explicit and its absence is VOID:** if `hey` is missing, print the exact
  `go install github.com/rakyll/hey@latest` command and **exit 2**. Never skip, never "fall back to a
  simpler test" — that is the "reports success while executing nothing" shape.
- Emit `infra/load-testing/baselines/$(date +%F)-$(git rev-parse --short HEAD).md` containing: exact
  command, tool version, host CPU/RAM/loadavg, `docker ps` image digests for core-java + postgres +
  rabbitmq, both arms' raw output, the status distribution, and a PASS/FAIL against `budget.yaml`.

**(c) `infra/load-testing/README.md`** — resolves the dangling link at `docs/guides/TESTING.md:569`.
States: what the baseline is for (27-04's prefetch/concurrency decision), what it is explicitly NOT
(a performance-engineering programme — reproduce the "NOT in scope" list from `<interfaces>`), why it
is not in CI, the tool prerequisite, and how to read the dated artifacts.

Leave `load-test.sh` **functionally unchanged** — it is a working good. The only permitted edit is a
header pointer to `baseline.sh` and a note that it has no status assertion.
  </action>
  <verify>
    <automated>
bash -n infra/load-testing/baseline.sh
command -v hey >/dev/null || echo "hey missing -> expect exit 2"
bash infra/load-testing/baseline.sh; echo "exit=$?"
    </automated>
  </verify>
  <acceptance_criteria>
    - **AC-6.1 (VOID before tool install — verified precondition).** On this host today `hey`, `ab`, `k6`, `wrk`, `vegeta` are all MISSING (verified 2026-07-26). PASS: after `go install github.com/rakyll/hey@latest` (go 1.26.5 present, verified), the run completes. Expected-RED before install: **exit 2** with the install command printed. Record both.
    - **AC-6.2 (arm A status assertion falsifiable — the anti-vacuity proof).** BREAK: run arm A with `TOKEN=invalid`. Expected-RED: exit non-zero reporting a 401 distribution, **even though `hey` reports an excellent req/s**. Record the req/s figure alongside the failure — it is the clearest possible demonstration of why the assertion exists. Restore → exit 0.
    - **AC-6.3 (arm B DLQ assertion falsifiable).** BREAK: publish a message that the consumer will reject (a payload the listener cannot deserialize), so the queue drains to 0 by dead-lettering. Expected-RED: exit non-zero reporting the DLQ depth increase. Restore → exit 0. *Without this, "queue reached 0" would score message destruction as throughput.*
    - **AC-6.4.** A baseline artifact is committed under `infra/load-testing/baselines/` and contains a non-zero p95 for both `/shops` and `/products`, a status distribution that is 100 % 2xx, a messages/sec/consumer figure for both arm-B queues, and the image digests. Falsify: `grep -c 'p95' <artifact>` ≥ 2 and `grep -c 'msg/s' <artifact>` ≥ 2.
    - **AC-6.5 (budget honesty).** Every entry in `budget.yaml` carries either `source: inherited-assumption` with a file:line, or `source: measured` with the baseline artifact filename. `grep -c 'source:' budget.yaml` equals the number of budget entries. Falsify: delete one `source:` and confirm the equality breaks.
    - **AC-6.6.** `docs/guides/TESTING.md:569`'s link now resolves: `test -f infra/load-testing/README.md`.
    - **AC-6.7 (working good preserved).** `git diff infra/load-testing/load-test.sh | grep -vE '^[-+]#|^[-+]$' | grep -c '^[-+]'` returns 0 — only comment lines changed. Control: `git diff --stat infra/load-testing/load-test.sh` shows a non-zero change, proving the diff command is looking at a real modification.
    - **AC-6.8 (the number 27-04 needs actually exists).** The artifact contains an explicit `messages/sec/consumer` line for `media.process`. Falsify: `grep -c 'media.process.*msg/s' <artifact>` ≥ 1.
  </acceptance_criteria>
  <done>A two-arm baseline exists whose HTTP arm cannot pass on 401s and whose AMQP arm cannot pass on dead-lettering; the declared budget distinguishes inherited assumptions from measured numbers; one dated artifact is committed; 27-04 has its messages/sec/consumer figure.</done>
</task>

<task type="auto">
  <name>Task 7: CI wiring, gate documentation, metrics reconcile</name>
  <files>.github/workflows/ci-cd.yaml, k8s/DEPLOYMENT.md, docs/metrics.json</files>
  <read_first>
    - .github/workflows/ci-cd.yaml lines 321-357 (the k8s-validate job — the exact step shape to mirror) and 358-414 (branch-parity, including the header explaining why the runtime half is deliberately absent from CI) and 586-605 (the deploy-gating `needs:` comment — the new job must be considered for that list)
    - k8s/DEPLOYMENT.md "Runtime-parity gates" section (where check-alert-liveness.sh belongs)
    - scripts/docs-freshness.sh (what it counts — bash contributes 0)
    - docs/metrics.json
  </read_first>
  <action>
**(a) New CI job `ops-contracts`** (do not overload `k8s-validate` — these gates are not k8s-scoped),
mirroring `k8s-validate`'s step shape exactly (`chmod +x` then run, step name states the assertion):

- `scripts/check-terminal-states.sh`
- `scripts/check-dependency-horizons.sh`

Header comment must state: what each gate pins (with the F-numbers), that `check-alert-liveness.sh`
and `baseline.sh` are **deliberately absent** for the reason already recorded at `ci-cd.yaml:374-378`,
and that `check-dependency-horizons.sh` makes an outbound HTTPS call to `endoflife.date` — so a
network outage turns the job VOID (exit 2 → job fails) rather than silently green. Add a job-level
`permissions:` block per the recorded repo fact that the default `GITHUB_TOKEN` is restricted.

Evaluate whether `ops-contracts` belongs in the deploy jobs' `needs:` list. The comment at
`ci-cd.yaml:586-605` records the rule and the hazard: a job that can be SKIPPED blocks every deploy.
`ops-contracts` carries no job-level `if:` and therefore cannot skip — **state this explicitly in the
SUMMARY as a checked fact, not an assumption**, and add it to `needs:` only if that holds.

**(b) `k8s/DEPLOYMENT.md`** — extend the existing "Runtime-parity gates" section into an "Operational
contracts" section listing all gates old and new, which are static (CI) and which are runtime
(not CI, and why), the shared 0/1/2 exit convention, and the one copy-paste command that runs the
static set locally. Cross-reference `docs/ops/terminal-states.yaml` and
`infra/dependency-horizons.yaml` as the two registers an operator maintains, and state the two
maintenance commands (`--refresh` for horizons; add a register row for a new terminal state).

**(c) `docs/metrics.json`** — reconcile with `scripts/docs-freshness.sh --write` once, at the end.
Bash scripts and YAML registers contribute 0, so the only movement will come from any test files
added. Run check mode afterwards and record exit 0.
  </action>
  <verify>
    <automated>
bash scripts/check-terminal-states.sh && bash scripts/check-dependency-horizons.sh && echo OPS_GATES_GREEN
bash scripts/docs-freshness.sh; echo "docs-freshness exit=$?"
grep -c $'\t' .github/workflows/ci-cd.yaml
    </automated>
  </verify>
  <acceptance_criteria>
    - **AC-7.1.** The combined command prints `OPS_GATES_GREEN`.
    - **AC-7.2.** `grep -c 'check-terminal-states.sh' .github/workflows/ci-cd.yaml` ≥ 1 and the same for `check-dependency-horizons.sh`. Control: `grep -c 'check-alert-liveness.sh' .github/workflows/ci-cd.yaml` returns **0** — the runtime gate must NOT be wired into CI, and this 0 is meaningful only alongside the two ≥1s above, which prove the file and the grep both work.
    - **AC-7.3.** `awk '/^  ops-contracts:/,/^  [a-z-]+:$/' .github/workflows/ci-cd.yaml | grep -c 'chmod +x'` returns 2.
    - **AC-7.4 (no collateral damage).** `git diff .github/workflows/ci-cd.yaml | grep '^-' | grep -vE '^---' | grep -c 'check-no-plaintext-secrets\|check-connection-math\|check-env-contract\|check-render-invariants\|render-golden'` returns 0 — no existing gate step was modified or removed. Control: the same command with `grep -c 'ops-contracts'` against the `^+` side returns ≥ 1.
    - **AC-7.5.** `grep -c $'\t' .github/workflows/ci-cd.yaml` returns 0 (no tabs introduced).
    - **AC-7.6.** `bash scripts/docs-freshness.sh` exits 0 after the single `--write`.
    - **AC-7.7.** `k8s/DEPLOYMENT.md` names both registers and both maintenance commands: `grep -c 'terminal-states.yaml' k8s/DEPLOYMENT.md` ≥ 1, `grep -c 'dependency-horizons.yaml' k8s/DEPLOYMENT.md` ≥ 1, `grep -c 'check-dependency-horizons.sh --refresh' k8s/DEPLOYMENT.md` ≥ 1.
    - **AC-7.8 (branch parity, per the standing contract).** `bash scripts/check-branch-behind-base.sh` exits 0 before the PR, or a merge from the base is recorded.
    - **AC-7.9 (runtime parity, per the standing contract).** `bash scripts/check-runtime-freshness.sh` exits 0 after any rebuild this phase triggers. Note that Tasks 1 and 4 restart Prometheus/Alertmanager, which are config-mount-only containers and rebuild nothing; if any application source changed, the images must be **rebuilt**, not `start`ed.
  </acceptance_criteria>
  <done>Two static gates run in a new, non-skippable `ops-contracts` CI job with declared permissions; the runtime gate is documented and deliberately absent from CI with the reason recorded; both registers and their maintenance commands are documented; metrics reconciled; both parity gates green.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| running system → operator | The detection path. Today it is broken in three places at once: a dead scrape target, a selector matching nothing, and a destination mailbox with no human. |
| pull request → merged config | CI is the only automatic reviewer of register-vs-code drift. |
| third-party EOL API → CI gate | `check-dependency-horizons.sh` fetches remote JSON and lets it decide whether CI passes. |
| Prometheus scrape → metric label space | Per-object queue metrics widen the label surface; the SSE `AnonymousQueue` names are unbounded. |
| Alertmanager → notification destination | Alert payloads carry service, instance and description text off-host. |

## STRIDE Threat Register

| Threat ID | Category | Component | Disposition | Mitigation |
|-----------|----------|-----------|-------------|------------|
| T-27-01 | Denial of Service (detection) | Prometheus scrape targets | mitigate | ASVS V7 (logging/monitoring). L-1 makes a down target a hard FAIL. F-3 left the tenant-isolation security alert dataless; a security control nobody can observe is not a control. |
| T-27-02 | Tampering | alert rules that parse but match nothing | mitigate | ASVS V7. L-2 asserts against the live series API, not a syntax checker. F-1 is the worked example. |
| T-27-03 | Repudiation | notifications that fire and reach nobody | mitigate | ASVS V7. L-3 proves end-to-end delivery with a synthetic alert. F-4: an alert fired for 32 h into a dev sink. |
| T-27-04 | Information Disclosure | per-object queue metrics | mitigate | ASVS V8. Queue names are structural, not tenant-derived — verified: all 13 are static except the SSE `AnonymousQueue`, which the D-07 drop rule removes. No tenant identifier enters the label space. Re-assert if a future queue is ever named per tenant. |
| T-27-05 | Information Disclosure | alert annotations leaving the host | mitigate | ASVS V8. The Slack receiver reuses the existing annotation template, which carries only `summary`/`description`/`severity`/`service` — no tenant ids, no PII. Asserted by AC-4.9 (the email receiver's template is unchanged and the Slack block reuses it). |
| T-27-06 | Tampering | untrusted JSON from endoflife.date deciding CI outcomes | mitigate | ASVS V5/V12. Pin the host, refuse cross-host redirects, parse strictly with `jq`, never `eval` any field, and treat any anomaly (non-200, 404 slug, missing cycle, unparseable) as **exit 2** rather than a pass. A hostile or broken response can VOID the gate; it can never silently green it. |
| T-27-07 | Denial of Service | the EOL fetch as a CI dependency | accept | An endoflife.date outage fails `ops-contracts`. Accepted deliberately: the alternative — treating an unreachable source as clean — is the exact inversion this plan exists to prevent. `--refresh` plus the cached `eol_date` bounds the annoyance to re-running the job. |
| T-27-08 | Elevation of Privilege | synthetic-alert injection endpoint | mitigate | ASVS V4. L-3 posts to Alertmanager's API, which is bound to the compose `monitoring` network and not published beyond `localhost:9093`. The gate is runtime-only and never runs in CI, so no CI credential reaches it. The synthetic alert carries a distinct `alertname` and is expired after the probe. |
| T-27-09 | Denial of Service | the load baseline against a shared runtime | mitigate | ASVS V11. `baseline.sh` is never wired into CI (D-11), runs only against a local compose stack, and its arm-B publish volume is bounded by an explicit `N`. |
| T-27-10 | Spoofing | credentials in the baseline path | mitigate | ASVS V2. `baseline.sh` reuses `load-test.sh`'s existing `KC_SEED_USER_PASSWORD` handling verbatim; no literal is introduced. The committed baseline artifact must record image digests and host shape but **never** the token — assert `grep -c 'Bearer ' <artifact>` returns 0. |
| T-27-11 | Tampering | the register / manifest becoming a rubber stamp | mitigate | Every deferral and exemption requires a non-empty reason and a dated `expires`; an expired or stale entry FAILS. Proven RED by AC-3.5, AC-5.5 and AC-5.6. |
| T-27-12 | Tampering | the gates themselves | accept | A contributor with write access can delete a CI step. Mitigated by review only; each gate's header names the defect it pins and the F-number, so a reviewer sees the cost of removal. |
| T-27-SC | Tampering | supply chain | mitigate | One new tool: `hey` via `go install github.com/rakyll/hey@latest` — used only by a local, non-CI baseline script, never in a build or runtime image. No npm/pip/gradle dependency is added. No new container image is introduced. |

## Other Quality Contracts

- **Web performance (mobile-first): N/A** — no user-facing page, route or bundle is touched. The one
  adjacent artefact, `infra/load-testing/budget.yaml`, *creates* the config-declared budget the
  contract asks future page-touching phases to measure against, which is additive.
- **SEO / discoverability: N/A** — no public or unauthenticated surface is touched.
- **AI agent-readiness: PARTIAL, with a recorded reason.** No API surface, endpoint, error contract
  or OpenAPI document changes, so the Idempotency-Key, RFC-7807 and contract-match clauses are N/A.
  The machine-consumability clause **is** honoured: both registers are machine-readable YAML with a
  documented schema and a gate that keeps them true, rather than prose. No MCP tool is added —
  reason recorded at D-13 (the register is operator-facing and cross-tenant; the MCP server is
  tenant-scoped, and surfacing cross-tenant failure counts through it would breach the recorded
  no-platform-operator boundary).
- **Security: APPLICABLE** — register above.
- **Falsifiable evidence + runtime parity: APPLICABLE, and this plan is the reference instance.**
  Every acceptance criterion states PASS / BREAK / expected-RED. **Five criteria are RED on the
  unmodified tree and must be captured before any edit** — AC-1.1 (target down), AC-1.2 and AC-4.2
  (selector matches nothing), AC-3.6 (4 alerts with no runbook), AC-5.1 (six past-EOL pins),
  AC-6.1 (no load tool installed). Runtime parity: AC-7.8 and AC-7.9 run the standing gates.
</threat_model>

<verification>

```bash
# Static gates (CI-equivalent, ~seconds)
bash scripts/check-terminal-states.sh          # 0
bash scripts/check-dependency-horizons.sh      # 0  (network required; 2 if offline)
bash scripts/docs-freshness.sh                 # 0

# Runtime gates (require the compose stack up; NOT in CI)
bash scripts/check-alert-liveness.sh           # 0
bash scripts/check-runtime-freshness.sh        # 0
bash scripts/check-branch-behind-base.sh       # 0

# Baseline (requires `hey`; exit 2 without it, by design)
bash infra/load-testing/baseline.sh            # 0
```

**Fail-direction evidence is mandatory and is the deliverable, not a formality.** For every gate,
record BOTH directions' verbatim output in the SUMMARY. The five pre-existing REDs (AC-1.1, AC-1.2 /
AC-4.2, AC-3.6, AC-5.1, AC-6.1) must be captured **before the first edit** — once Task 1 lands they
cannot be reproduced without deliberately re-breaking the tree, and a re-broken tree is weaker
evidence than the tree as it was found.

**Do not cite `promtool`.** It is not installed on this host. If a future run installs it, note that
`promtool check rules` **passes** `alerts.yml` today — which is the point: it is the checker that
cannot catch F-1.

Known-deferred and unchanged: the `DiskSpace*` rules stay commented out with their existing reason
(`alerts.yml:174-181`); node-exporter is not deployed by this plan.
</verification>

<sequencing>

### What depends on this plan

| Sibling | Relationship to 27-00 | Why |
|---|---|---|
| **27-01 media durability** (reaper destroys objects after a 15-min broker outage, `MediaProperties.java:66` + `MediaPendingReaper.java:79`) | **PARALLEL — no blocking dependency.** One merge-gate obligation: it must add/adjust the `media_asset FAILED` register row (TS-07) in the same PR, or `check-terminal-states.sh` X-1 fails. | Its fix is self-contained in the media package. |
| **27-02 broker upgrade** | **PARALLEL, but consumes a spine finding as a hard input.** | `rabbitmq` 4.1 is EOL (2026-01-30) and **4.2 is EOL 2026-07-31** — the obvious "upgrade to 4.x" lands on a series leaving support within the week. **4.3 is the only non-EOL series.** Task 5 writes this into `infra/dependency-horizons.yaml` so it is a file 27-02 reads, not a fact someone must remember. |
| **27-03 failure visibility** | **BLOCKED on 27-00 Tasks 1 + 4.** | Alerts on a dead target are theatre. Until F-3 is fixed, 8 of 14 alerts have no data and any new core-java alert is unverifiable. Until the labelled queue metric exists (F-1), a DLQ-depth alert cannot be written at all. Until `check-alert-liveness.sh` exists, "the alert works" is unfalsifiable. 27-03 also consumes the register as its work list. |
| **27-04 throughput + guards** | **BLOCKED on 27-00 Task 6.** | Prefetch and `concurrentConsumers` are derived from messages/sec/consumer. There is no such number anywhere in the repo today (F-7/F-8). |

### Wave ordering

- **Wave 1 — 27-00 Tasks 1–2 (spine, critical path).** Fix the two dead detection paths; write the
  register. Capturing the five pre-existing REDs happens here, before any edit. Unblocks nothing yet
  but must precede everything that asserts on monitoring.
- **Wave 2 — three tracks in parallel.**
  - 27-00 Tasks 3–5 (gates + runbooks + horizons manifest).
  - **27-01** — fully parallel from Wave 1 onward.
  - **27-02** — parallel; must wait only for Task 5's rabbitmq row to exist before choosing a target
    version. In practice: start the upgrade research immediately, pin the version after Task 5.
- **Wave 3 — 27-00 Task 6 (baseline), then 27-04.** Task 6 is independent of Tasks 3–5 and can start
  in Wave 2 if capacity allows; 27-04 cannot start before it finishes.
- **Wave 4 — 27-03.** Requires Wave 1 (targets alive, labels present) and Wave 2 (the liveness gate
  and the register that is its work list).
- **Wave 5 — 27-00 Task 7 + phase close.** CI wiring lands after every gate exists so the job is
  wired once; metrics reconcile is a single `--write`.

**One ordering hazard, stated explicitly:** 27-03 will add alerts, and `check-terminal-states.sh` X-2
requires each register row's `detection.alert` to exist. Task 2 therefore ships every such row with a
`deferred` block whose `expires` sits inside this milestone. When 27-03 lands its alerts it removes
those blocks. If 27-03 slips past the expiry, the gate fails — **which is the intended behaviour**,
not a bug to work around.

</sequencing>

<success_criteria>
- All seven scrape targets report `health == "up"`, and a gate FAILS when one does not — proven RED
  against the tree as found (`core-java` down, connection refused on 9091).
- Every alert rule's selector is proven to match ≥ 1 live series — proven RED against `StompBrokerLag`
  as found (0 series matched; 1 matched without the label filter).
- A synthetic alert is demonstrably delivered end-to-end to the configured destination, and the gate
  fails when the destination is unreachable.
- `docs/ops/terminal-states.yaml` enumerates ≥ 9 terminal failure states, each with a real
  file:line locator, a user-visible consequence, an owner or an honestly-recorded absence, a runnable
  operator action, and a dated deferral where detection does not yet exist.
- Adding a new DLQ constant, or a new `FAILED`/`AUTO_PAUSED` enum constant on the declared surface,
  **fails CI** until the register is updated — proven RED with `DEADBEEF_DLQ`.
- All 14 alerts and all register rows have runbook sections (14 vs 10 as found).
- Every pinned image and toolchain carries a support-horizon row; a stale cached EOL date, a past
  horizon without an unexpired exemption, a horizon inside the 90-day window, and a stale exemption
  each fail — every arm proven RED, including the warn-window arm fired against rows that pass at the
  default.
- Six past-horizon pins are recorded as reasoned dated exemptions; **zero dependencies are upgraded
  by this plan**; the rabbitmq row records that 4.2 leaves support 2026-07-31 and 4.3 is the target.
- A two-arm load baseline exists whose HTTP arm cannot pass on 401s and whose AMQP arm cannot pass on
  dead-lettering, with one committed dated artifact carrying a messages/sec/consumer figure for
  `media.process`.
- Every gate exits **2** on missing tooling, unreachable dependency, or empty discovery — each arm
  proven.
- No working good is traded away: the email receiver, `load-test.sh`, the k8s prometheus annotations,
  the commented `DiskSpace*` rules and every pre-existing CI gate step are unchanged, each asserted
  by a diff-level criterion with a control.
</success_criteria>

<output>
Create `.planning/phases/27-operational-maturity/27-00-SUMMARY.md` when done.

Record, verbatim and in full:
1. **The five pre-existing REDs as captured before the first edit** — targets JSON, both selector
   query arms with their controls, the 4 undocumented alert names, the six past-EOL rows, the missing
   load tool. These are the phase's strongest evidence and cannot be regenerated later.
2. Both directions' output for **every** falsifiability probe in every task's acceptance criteria.
3. The final register contents (row count + the id/name/owner/deferred-expires table).
4. The final horizons manifest (row count + every exemption with its reason and expiry).
5. The classification summaries each gate prints.
6. The measured baseline numbers and how `budget.yaml` changed from inherited-assumption to measured.
7. The checked fact of whether `ops-contracts` can skip, and whether it was added to the deploy
   `needs:` list.
8. Anything I claimed in the problem statements that turned out to be wrong.
</output>
