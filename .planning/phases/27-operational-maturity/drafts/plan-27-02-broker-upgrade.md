---
phase: 27-operational-maturity
plan: 02
type: execute
wave: 2
depends_on: []
files_modified:
  - docker-compose.full-stack.yml
  - core-java/src/test/java/uk/jtoye/core/order/OrderEventFanoutTopologyIntegrationTest.java
  - scripts/smoke-test-stomp-relay.sh
  - scripts/check-support-horizon.sh
  - docs/support-horizon.yaml
  - .github/workflows/docs-freshness.yml
  - k8s/LOCAL.md
  - k8s/DEPLOYMENT.md
  - docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md
  - docs/runbooks/rabbitmq-broker-upgrade.md
  - CLAUDE.md
  - AGENTS.md
  - .planning/codebase/STACK.md
  - .planning/codebase/INTEGRATIONS.md
autonomous: false
requirements: [OPS-02, OPS-05]

must_haves:
  truths:
    - "The LOCAL/dev broker runs a release that still receives community patches — proven by reading the version out of the RUNNING broker, never out of the compose file"
    - "The 3.12→4.x jump is executed as a DOCUMENTED FRESH INSTALL, not an in-place upgrade, because RabbitMQ supports no direct 3.12→4.x path and 4.3 has no Mnesia at all"
    - "The 9 real dead messages currently in webhook.deliveries.dlq are EXPORTED before the volume is destroyed and re-imported after, or explicitly adjudicated — a recreate must never silently discard tenant-bearing dead letters"
    - "Every one of the 13 declared queues is shown to survive the version change with its exact (type, durable, exclusive, auto_delete) tuple intact, read from the running broker's management API"
    - "The KDS STOMP relay is re-proven end-to-end on the new broker AND the single-segment /topic constraint (#266/#269) is shown to STILL reject a multi-segment destination — the fix stays load-bearing"
    - "No queue becomes a quorum queue and the vhost default_queue_type is NOT changed — source-verified: doing so would silently reshape every STOMP subscription queue into an illegal declaration"
    - "The Prometheus scrape stays in aggregated mode with the same series names the sibling alert-rules work-item is building on, or every deviation is named"
    - "The repo gains a CI-enforced support-horizon manifest so the NEXT pinned runtime dependency cannot silently leave support unnoticed — this is the durable half of the work-item"
    - "k8s staging/production is NOT claimed fixed: the broker is out-of-repo with no manifest, no declared version and no owner, so this plan produces a documented operator action + an ADR-0002 follow-through and nothing else"
  artifacts:
    - path: "docs/support-horizon.yaml"
      provides: "Machine-checkable declared support end date per pinned runtime dependency (OPS-05)"
      contains: "rabbitmq"
    - path: "scripts/check-support-horizon.sh"
      provides: "Fail-closed CI gate: any pinned dep past (or within the warn window of) its declared support end fails the build"
      contains: "exit 2"
    - path: "docs/runbooks/rabbitmq-broker-upgrade.md"
      provides: "The operator action for the out-of-repo staging/prod broker (D-08)"
      contains: "3.13"
  key_links:
    - from: "docs/support-horizon.yaml"
      to: "docker-compose.full-stack.yml"
      via: "the gate re-reads each declared pin from its real source file and fails on drift"
      pattern: "docker-compose\\.full-stack\\.yml"
    - from: "scripts/check-support-horizon.sh"
      to: ".github/workflows/docs-freshness.yml"
      via: "an added step in the existing docs-freshness job"
      pattern: "check-support-horizon"
---

<objective>
Move the dev/local RabbitMQ off a release that has received **no patches from any channel since
2025-06-30**, and — the durable half — install the mechanism that makes the *next* out-of-support pin
impossible to miss.

Purpose. `docker-compose.full-stack.yml:144` pins `rabbitmq:3.12-management-alpine`. The running
broker reports `3.12.14` (probed read-only 2026-07-26: `docker exec jtoye-rabbitmq rabbitmqctl
version` → `3.12.14`, container `Up 32 hours (healthy)`). Per
https://www.rabbitmq.com/release-information, the 3.12 series left **community support on
2024-02-29** and left **commercial support on 2025-06-30**; its final patch was `3.12.14` (6 May
2024). The current series is 4.3 (`4.3.4`, 23 Jul 2026). Nobody noticed for **two years and five
months**. A one-off bump without a detection mechanism guarantees a repeat, which is why this
work-item carries OPS-05 (the support-horizon gate) as a first-class task rather than a nicety.

Scope discipline. This plan owns the **compose/dev/test runtime and the repo's own claims about it**.
It does **not** own the staging/production broker, which has no manifest in this repo (see D-08) —
that surface gets a documented operator action and an ADR-0002 follow-through, not a pretend fix.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@k8s/LOCAL.md
@docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md

<interfaces>
<!-- Every fact below was verified during planning: repo facts by reading the file at the cited
     line, vendor facts against primary Broadcom/RabbitMQ sources at the cited URL, and source
     facts against the `v4.3.x` RELEASE BRANCH (never `main` — see PIT-1). Re-probe the live ones
     before executing: this checkout can be driven by a second concurrent session. -->

## A. Repo surface (file:line)

| Surface | Location | Current value |
|---|---|---|
| Compose image pin | `docker-compose.full-stack.yml:144` | `rabbitmq:3.12-management-alpine` |
| Compose data volume | `docker-compose.full-stack.yml:155` | `rabbitmq_data:/var/lib/rabbitmq` |
| Compose plugins mount | `docker-compose.full-stack.yml:156` | `./infra/rabbitmq/enabled_plugins:/etc/rabbitmq/enabled_plugins:ro` |
| Compose healthcheck | `docker-compose.full-stack.yml:158` | `rabbitmq-diagnostics ping` |
| Compose ports | `docker-compose.full-stack.yml:151-153` | 5672 AMQP / 15672 mgmt / 61613 STOMP |
| Compose credentials | `docker-compose.full-stack.yml:148-149` | `RABBITMQ_DEFAULT_USER` / `RABBITMQ_DEFAULT_PASS` |
| Enabled plugins | `infra/rabbitmq/enabled_plugins` | `[rabbitmq_management,rabbitmq_management_agent,rabbitmq_prometheus,rabbitmq_stomp].` |
| Testcontainers lib pin | `core-java/build.gradle.kts:101` | `org.testcontainers:rabbitmq:1.21.4` |
| Testcontainers image pin | `.../OrderEventFanoutTopologyIntegrationTest.java:55-56` | `DockerImageName.parse("rabbitmq:3.12-management-alpine")` |
| Broker topology | `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java` | 12 durable queues + 1 `AnonymousQueue` |
| k8s broker endpoint | `k8s/base/configmap.yaml:28-29, 37-38` | `rabbitmq.jtoye-infrastructure.svc.cluster.local` :5672 / :61613 |
| Smoke test | `scripts/smoke-test-stomp-relay.sh:59,78,94` | mgmt-API probes, `guest:guest` fallback |
| Version-specific ops recipes | `k8s/LOCAL.md:1381-1435`, `:1577`, `:1584` | `list_stomp_connections` info keys; `server:RabbitMQ/3.12.14` |
| Stale doc pins | `CLAUDE.md:83,128,136`; `AGENTS.md:82,127,135`; `.planning/codebase/STACK.md:25,118,173,183`; `.planning/codebase/INTEGRATIONS.md:63,64` | "3.12" |

**Stale line references found while verifying:** `.planning/codebase/STACK.md:183` and
`INTEGRATIONS.md:64` both cite `docker-compose.full-stack.yml:88` for the image pin. The pin is at
**line 144**. Line 88 is inside the Keycloak block. Fix the pointer while fixing the version.

**Non-Java broker clients — verified NONE.** `edge-go/go.mod` has no AMQP dependency and
`edge-go/internal` + `edge-go/cmd` contain no `amqp`/`rabbit` reference at all (CLAUDE.md's
"RabbitMQ for async messaging" claim about the edge is **stale**). No `amqplib` in any
`package.json`. The only non-Java broker client is `@stomp/stompjs ^7.3.0`
(`frontend/package.json:26`), which speaks STOMP to the **Spring relay**, not AMQP to the broker.

## B. Live broker state (probed read-only 2026-07-26 — re-probe before executing)

```
container      jtoye-rabbitmq   rabbitmq:3.12-management-alpine   Up 32 hours (healthy)
rabbitmqctl version                                               3.12.14
rabbitmq-plugins list  -> rabbitmq_prometheus 3.12.14 ENABLED
                          rabbitmq_stomp      3.12.14 ENABLED
                          rabbitmq_web_stomp  NOT enabled
webhook.deliveries.dlq                                            9 messages  ← REAL DEAD LETTERS
/metrics (15692)  rabbitmq_queue_messages_ready 9   ← AGGREGATED: no `queue` label
```

Three consequences, each load-bearing:
1. `rabbitmq_web_stomp` is **not** enabled and must not be added — the browser reaches STOMP through
   the Spring `StompBrokerRelay` over the app's own WebSocket endpoint, not through the broker's web
   port. Adding it would open an unreviewed listener (see T-27-05).
2. The 9 dead messages in `webhook.deliveries.dlq` are **tenant-bearing outbound webhook payloads**.
   D-02 destroys the data directory. Those 9 messages therefore need an explicit disposition —
   Task 2 exists solely for this and it is the reason `autonomous: false`.
3. The scrape is aggregated (`rabbitmq_queue_messages_ready 9` with **no** `queue` label). A sibling
   work-item is building alert rules on these series. See D-07 for exactly what 4.x does and does
   not change here.

## C. Vendor facts (primary sources, verified 2026-07-26)

**C1 — Support status.** https://www.rabbitmq.com/release-information

| Series | Latest patch | Community support ends | Commercial support ends |
|---|---|---|---|
| 3.12 | 3.12.14 (6 May 2024) | **2024-02-29** | **2025-06-30** |
| 3.13 | 3.13.7 (26 Aug 2024) | 2024-09-30 | 2029-12-31 |
| 4.0 | 4.0.9 | 2025-04-30 | 2026-09-30 |
| 4.1 | 4.1.8 | 2026-01-31 | 2027-04-30 |
| 4.2 | 4.2.9 (20 Jul 2026) | **2026-07-31** | 2030-06-30 |
| 4.3 | **4.3.4 (23 Jul 2026)** | **2026-11-30** | 2028-04-30 |

Only the newest minor of the newest major receives community patches. **4.3 is the only series a
non-paying user can be patched on today**, and its own community window closes 2026-11-30. That is a
treadmill, not a destination — which is precisely the argument for OPS-05 (Task 5).

**C2 — Upgrade path. THE CRITICAL CONSTRAINT.**
`rabbitmq-website/versioned_docs/version-4.0/upgrade.md:27` —
> "You can only upgrade to RabbitMQ 4.0 from RabbitMQ 3.13."

`.../upgrade.md:29-31` —
> "stable feature flags have to be enabled **before** the upgrade. The upgrade will fail if you miss
> this step."

`rabbitmq-server/release-notes/4.0.1.md` —
> "This release series only supports upgrades from `3.13.x`. This release requires **all feature
> flags** in the 3.x series (specifically `3.13.x`) to be enabled before upgrading, there is **no
> direct upgrade path from 3.12.14** (or a later patch release) straight to a `4.0.x` version."

Upgradability table (`.../upgrade.md:151-159`, and https://www.rabbitmq.com/docs/upgrade): the only
edge out of 3.12 is `3.12.x → 3.13.x`. To reach 4.3 in place the chain is
**3.12 → 3.13 → 4.2 → 4.3** — four hops, three of them cluster-wide feature-flag gates. `4.3.0`
release notes add: "only `4.2.x` clusters can upgrade to `4.3.0` in place."

**C3 — The sanctioned escape for dev.** `.../upgrade.md` § *Upgrading Development Environments*:
> "if the messages stored in RabbitMQ are not important, it may be easier to simply **delete
> everything in the data directory and start a fresh node of the new version**. Effectively, it's no
> longer an upgrade but a fresh installation… you can easily jump from any version to any other
> version without worrying about compatibility and feature flags."

This is the vendor's own instruction and it is what D-02 follows. Note the conditional — "if the
messages stored are not important" — which is exactly why the 9 DLQ messages get Task 2.

**C4 — Erlang/OTP.** https://www.rabbitmq.com/docs/which-erlang: 3.12 → OTP 25.0–26.2; 3.13 → 26.0–26.2;
4.0/4.1/4.2 → 26.2–27.x; **4.3 → 27.0–27.x** ("Erlang 26 has reached end of life"). The
`-management-alpine` image bundles a compatible OTP, so this is informational for compose, and
load-bearing only for a non-container operator install (runbook, Task 6).

**C5 — Classic mirrored queues removed in 4.0.** `release-notes/4.0.1.md`:
> "After three years of deprecation, classic queue mirroring was completely removed in this version…
> After an upgrade to 4.0, all classic queue mirroring-related parts of policies will have no effect."

**Does this repo use them? NO.** `RabbitMQConfig.java` declares exactly zero mirroring arguments:
no `x-ha-policy`, no `ha-mode`, no `ha-params`, no `ha-sync-mode`, no `queue-master-locator`, and no
`policy` is declared anywhere in the codebase. **Stated plainly as the task required.** But see
AC-6: the *grep* for those tokens is already 0 and is therefore a structurally vacuous assertion —
the real evidence is the runtime diagnostic
`rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy`
(https://www.rabbitmq.com/docs/3.13/migrate-mcq-to-qq), which must be shown to fail against a
deliberately-created `ha-mode` policy before its pass is trusted.

**C6 — Other 4.x breaking changes, each adjudicated against this repo.**

| Change | Source | Applies here? |
|---|---|---|
| `max_message_size` default 128 MiB → **16 MiB** | `4.0.1.md` | **NO.** Largest AMQP payload is `MediaProcessingEvent` = `record(UUID tenantId, UUID assetId)` (verified). Image bytes never traverse AMQP — the upload quarantines to MinIO and the event carries only ids. Multipart cap is 5 MB (`application.yml:11`). |
| Quorum queues get a default **redelivery limit of 20** | `4.0.1.md` | **NO** — no quorum queues (D-03). |
| AMQP 0-9-1 `x-death` no longer interpreted on re-publish | `4.0.1.md` | **NO** — nothing re-publishes a message carrying `x-death`; DLQ replay is via the DB outbox. |
| CQv1 removed; `classic_queue.default_version = 1` fails boot | `4.0.1.md`, `4.3.0.md` | **NO** — no `rabbitmq.conf` is mounted at all. |
| `cluster_formation.randomized_startup_delay_range.*` removed | `4.0.1.md` | **NO** — single node, no cluster config. |
| Initial AMQP 0-9-1 `frame_max` 4096 → **8192**; `amqplib < 0.10.7` cannot connect | `4.1.0.md` | **NO** — no `amqplib` in any `package.json`; the Java client uses the 131072 default. |
| **Ra/quorum Prometheus metrics renamed/removed** (`rabbitmq_raft*`, `rabbitmq_detailed_raft*`) | `4.2.0.md` | **NO for now, but tell the alerting work-item** — see D-07. |
| 4.3: Mnesia removed, **Khepri is the only metadata store** | `4.3.0.md` | **YES, and it is why D-02 is a fresh install** — a 3.12 Mnesia data directory has no 4.3 reader. |
| 4.3: **deprecated features denied by default**, incl. `transient_nonexcl_queues` | `4.3.0.md` | **Checked in depth — SAFE. See C7.** |
| 4.3: `rabbitmqadmin` v1 download endpoint removed | `4.3.0.md` | **NO** — `grep -rn rabbitmqadmin` over the repo returns 0 hits. |

**C7 — The 4.3 transient-queue denial, and why this repo survives it.** `4.3.0.md`:
> "This includes non-durable (transient) **non-exclusive** queues: attempts to declare a queue with
> such property combination will be rejected by default. Use durable queues, **transient exclusive
> queues**, or durable queues with a queue TTL instead."

The deprecated-feature is named `transient_nonexcl_queues` and its own definition
(`release-information/deprecated-features-list.md`) says it "covers queues that are both non-durable
**and** non-exclusive". Two transient queues exist in this system and **both are exclusive**:

1. Spring's `AnonymousQueue` (`RabbitMQConfig.java:125-127`) — durable=false, **exclusive=true**,
   autoDelete=true.
2. The broker-created STOMP `/topic` subscription queue. The docs page only says "an autodeleted,
   non-durable queue is created" and is **silent on exclusivity**, so this was verified at source on
   the release branch — `v4.3.x/deps/rabbitmq_stomp/src/rabbit_stomp_routing_util.erl:133-139`:
   ```erlang
   queue_declare_method(#'queue.declare'{} = Method, Type, Params) ->
       Method1 = case proplists:get_value(durable, Params, false) of
                     true  -> Method#'queue.declare'{durable     = true};
                     false -> Method#'queue.declare'{auto_delete = true,
                                                     exclusive   = true}
                 end,
   ```
   Non-durable ⇒ **exclusive = true**. Safe.

Do not take C7 on trust at execution time: AC-4 reads both queues' actual `durable`/`exclusive`/
`auto_delete` out of the running 4.3 broker.

**C8 — Quorum queue eligibility, from source not prose.**
`v4.3.x/deps/rabbit/src/rabbit_quorum_queue.erl:279-285`:
```erlang
is_compatible(_Durable = true, _Exclusive = false, _AutoDelete = false) -> true;
is_compatible(_, _, _) -> false.
```
and `declare/2` runs `check_auto_delete`, `check_exclusive`, `check_non_durable`. The authoritative
triple is therefore **durable=true ∧ exclusive=false ∧ auto_delete=false**. Corroborated by the
feature matrix at https://www.rabbitmq.com/docs/quorum-queues (Non-durable: no; Exclusivity: no;
Server-named queues: no; Global QoS: "a channel error will be returned").

**C9 — Queue type cannot be changed in place.** https://www.rabbitmq.com/docs/quorum-queues:
> "This argument must be provided by a client at queue declaration time; it cannot be set or changed
> using a policy."

Changing an existing queue's type is delete + re-declare. On a live broker that means draining or
re-routing first. Recorded because D-03 defers quorum — if a later phase adopts it, this is the cost.

**C10 — The STOMP single-segment rule is UNCHANGED in 4.x.** The #266 defect / #269 fix rests on a
RabbitMQ `/topic` destination being one segment. Verified at source on **both** `v4.2.x` and
`v4.3.x`, `deps/rabbit_common/src/rabbit_routing_parser.erl:44-57`:
```erlang
parse_endpoint0(exchange, [Name],          _) -> {ok, {exchange, {unescape(Name), undefined}}};
parse_endpoint0(exchange, [Name, Pattern], _) -> {ok, {exchange, {unescape(Name), unescape(Pattern)}}};
parse_endpoint0(Type,     [[_|_]] = [Name],_) -> {ok, {Type, unescape(Name)}};
parse_endpoint0(Type,     Rest,            _) -> {error, {invalid_destination, Type, to_url(Rest)}}.
```
Only `exchange` has a two-segment form. `topic` matches the single-`[Name]` clause; anything else is
`invalid_destination`. **The #269 fix remains load-bearing on 4.3 and must not be reverted.** AC-5
proves this against the running broker rather than resting on this read.

**C11 — Docker image + plugins.** Docker Hub tags API (`library/rabbitmq`, queried 2026-07-26):
`4.3.4-management-alpine` exists, last pushed `2026-07-24T14:53:26Z`; alpine variants are current
across 4.0–4.3 (349 `*management-alpine` tags). All four enabled plugins ship in `v4.3.x`
(`deps/rabbitmq_management`, `rabbitmq_management_agent`, `rabbitmq_prometheus`, `rabbitmq_stomp` —
all PRESENT). `docker-library/docs/rabbitmq/content.md:59`: `RABBITMQ_DEFAULT_USER` /
`RABBITMQ_DEFAULT_PASS` are still supported ("now available in RabbitMQ directly"); `:123` mounting
`/etc/rabbitmq/enabled_plugins` is still the documented mechanism. **Compose's shape needs no change
beyond the tag** (plus D-02's volume handling).

**C12 — Testcontainers.** `testcontainers-java` `1.21.4`
`modules/rabbitmq/.../RabbitMQContainer.java`: the only image coupling is
`dockerImageName.assertCompatibleWith(DockerImageName.parse("rabbitmq"))` (repo name only — any tag
passes) and `waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1))`, which 4.x still
emits. `DEFAULT_TAG` is `3.7.25-management-alpine` but is only reached via the `@Deprecated` no-arg
constructor, which this repo does not use. **No Testcontainers library bump is required** — only the
image string at `OrderEventFanoutTopologyIntegrationTest.java:56`.

## D. Pitfalls

- **PIT-1 `main` ≠ the release branch.** `rabbit_stomp_processor.erl` on `main` was rewritten for the
  queue-types API and its `?INFO_ITEMS` **adds `user`, `name`, `connected_at`** — but `v4.3.x`'s
  `deps/rabbitmq_stomp/include/rabbit_stomp.hrl:16-40` is **byte-identical to `v3.12.x`'s** and has
  none of them. Reading `main` would have produced a wrong claim about `list_stomp_connections`.
  Every source citation in this plan is pinned to `v4.3.x`. (Consequence: D-09.)
- **PIT-2 `check-runtime-freshness.sh` does not cover this.** That gate discovers only services with
  a `build:` stanza (`scripts/check-runtime-freshness.sh:226-239`). RabbitMQ is a pulled image, so
  it is invisible to the gate. Broker freshness must be asserted explicitly (AC-1).
- **PIT-3 `docker compose start` never recreates.** Editing line 144 and running
  `docker compose start rabbitmq` leaves 3.12.14 running. Required form:
  `docker compose -f docker-compose.full-stack.yml up -d --force-recreate rabbitmq` after the volume
  is removed. This is the exact shape of the `trap_stale_containers_after_phase` memory, and AC-1's
  fail direction uses it deliberately.
- **PIT-4 `kubectl kustomize` strips comments and sorts map keys.** A comment-only edit to
  `k8s/base/configmap.yaml` renders byte-identically, so no rendered-output scan can assert it. See
  D-08 and AC-8 for how that is handled without inventing a dangling ConfigMap key.
- **PIT-5 `cmd | grep -q X` under `set -o pipefail` inverts on match** (SIGPIPE → 141). Every
  assertion in this plan uses a here-string: `grep -q X <<< "$out"`.
- **PIT-6 Gradle `UP-TO-DATE` executes nothing** and the live build dir is `core-java/build-local`,
  not the stale `core-java/build`. `cleanTest` is load-bearing (AC-7).
- **PIT-7 Anonymous-queue count is replica-derived.** There is one `AnonymousQueue` per core-java
  JVM. Compose default is 1 replica; `--scale core-java=2` makes it 2. AC-4 derives the expected
  count from the running replica count — a hardcoded `1` would be an expected-value that is wrong on
  a correct tree.
- **PIT-8 `docs-freshness` greps literal `it(`/`test(`.** Nothing in this plan adds or removes a test
  block, but any prose that quotes those tokens creates a phantom. Keep them out of the new docs.
</interfaces>
</context>

<decisions>

**D-01 — Target `rabbitmq:4.3.4-management-alpine` (exact patch pin, not a floating minor).**
4.3 is the only series receiving community patches for a non-paying user (C1). Pin the *patch* so
the running version is a repo fact rather than a pull-time accident; the support-horizon gate (Task
5) is what forces the pin forward, not a floating tag that changes silently under a rebuild.
*Alternative considered — `4.2.9`:* commercial support to 2030-06-30 makes it the "LTS-shaped"
choice, but community support ended 2026-07-31 and this project holds no commercial licence, so 4.2
would be *immediately* unpatchable — the exact failure being remediated. Record `4.2.9` as the
fallback **only** if 4.3's Erlang-27/Khepri-only baseline causes an unforeseen problem; 4.2 → 4.3 is
a supported in-place hop later (C2).
*Consequence, recorded honestly:* 4.3's community window closes 2026-11-30. This is a subscription
to a treadmill. Task 5 is what makes the treadmill survivable.

**D-02 — Migrate by FRESH INSTALL (destroy `rabbitmq_data`), not by upgrade.**
Forced by C2: there is no 3.12→4.x path; in place it is 3.12→3.13→4.2→4.3, and 4.3 has no Mnesia
reader at all. C3 is the vendor's own sanctioned dev procedure. All 12 durable queues, 4 DLX
exchanges and every binding are re-declared at boot by Spring's `RabbitAdmin` from
`RabbitMQConfig.java`, so the topology is code, not data. The *messages* are not — hence D-02a.

**D-02a — The 9 `webhook.deliveries.dlq` messages are exported before the volume dies.**
They are real dead outbound webhook payloads carrying tenant data. "Recreate the queues" must not
mean "silently discard tenant-bearing dead letters". Export via the management API
(`GET /api/queues/%2F/webhook.deliveries.dlq/get` with `"ackmode":"ack_requeue_true"` — a
**non-destructive** peek) to a gitignored evidence file *before* the volume is removed, then either
re-publish them to `webhook.deliveries.dlq` on the new broker or record an explicit adjudication
that they are stale and why. **Deciding to discard is allowed; discarding without deciding is not.**
This is the single reason `autonomous: false`.

**D-03 — Queue-type strategy: ALL CLASSIC. No quorum queues this phase.**
Applying C8's triple (durable ∧ ¬exclusive ∧ ¬auto_delete) to all 13 declared queues:

| # | Queue | durable | excl | auto-del | Quorum-capable? | Reason |
|---|---|---|---|---|---|---|
| 1 | `order.state-changes` | ✓ | ✗ | ✗ | **eligible** | plain durable competing-consumer |
| 2 | `order.state-changes.dlq` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 3 | `payment.events` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 4 | `payment.events.dlq` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 5 | `order.notifications` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 6 | `onboarding.notifications` | ✓ | ✗ | ✗ | **eligible** | plain durable (no DLX by design) |
| 7 | `payment.notifications` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 8 | `refund.notifications` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 9 | `webhook.deliveries` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 10 | `webhook.deliveries.dlq` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 11 | `media.process` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 12 | `media.process.dlq` | ✓ | ✗ | ✗ | **eligible** | plain durable |
| 13 | `order.state-changes.sse.<b64>` (`AnonymousQueue`) | ✗ | ✓ | ✓ | **STRUCTURALLY IMPOSSIBLE** | fails **all three** of C8's conditions; also server-named, which quorum does not support. Its per-JVM exclusive lifecycle *is* the #92 fan-out design — a replicated durable queue would turn per-replica fan-out back into competing consumers, i.e. re-open the bug. |
| — | broker-created STOMP `/topic` sub queue (not one of the 13) | ✗ | ✓ | ✓ | **STRUCTURALLY IMPOSSIBLE** | same three failures (C7) |

So 12 of 13 *could* be quorum and exactly 1 structurally cannot. **Recommend: all-classic (defer).**
Rationale: (a) the broker is a **single node** in compose and an unknown-topology single endpoint in
k8s — a quorum queue at replication factor 1 buys zero availability and costs a Raft log per queue;
(b) quorum's default redelivery limit of 20 (C6) silently changes DLQ semantics for queues that
already have hand-tuned retry (`retryInterceptor`, `maxAttempts(3)`, `defaultRequeueRejected=false`);
(c) C9 makes it delete+recreate, so it is not free to reverse; (d) mixing types across an
exchange whose consumers assume uniform behaviour is a bigger change than this work-item's purpose.
*Revisit when* — and only when — the broker is genuinely clustered (≥3 nodes), i.e. as part of the
ADR-0002 follow-through (D-08). At that point the migration path for the 12 eligible queues is:
quiesce producers → drain → `DELETE /api/queues/%2F/<name>` → redeclare with
`.withArgument("x-queue-type","quorum")` in `RabbitMQConfig.java` → resume. Leave 13 classic forever.

**D-04 — Do NOT set the vhost `default_queue_type` to `quorum`.** Source-verified side effect:
`rabbit_stomp_routing_util.erl:148-155` (v4.3.x) reads the vhost default and, when it resolves to
quorum or stream, **overrides the STOMP subscription queue to `durable = true, exclusive = false`**
while leaving `auto_delete = true` from the earlier clause — a combination C8 rejects outright. Every
KDS SUBSCRIBE would fail. `default_queue_type` is per-vhost metadata
(`PUT /api/vhosts/{name}` `{"default_queue_type": ...}`, https://www.rabbitmq.com/docs/vhosts) and it
must stay at the classic default. AC-4's fail direction exercises exactly this and reverts.

**D-05 — Testcontainers: bump the image string only, keep `1.21.4`.** Per C12 the library has no
version coupling. Change `OrderEventFanoutTopologyIntegrationTest.java:56` to
`rabbitmq:4.3.4-management-alpine`, leave `core-java/build.gradle.kts:101` alone. Bumping the library
in the same change would confound the result: if the test then failed, the cause would be ambiguous.

**D-06 — Keep the plugin set exactly as-is; do NOT add `rabbitmq_web_stomp`.** All four ship in
`v4.3.x` (C11) and the file needs no edit. The live probe confirms `rabbitmq_web_stomp` is not
enabled and it must stay that way: the browser reaches STOMP via the Spring relay over the app's own
WebSocket endpoint, so a broker web-STOMP listener would be a new, unauthenticated-by-default,
un-network-policied ingress (T-27-05).

**D-07 — Prometheus: aggregated mode is unchanged in 4.x; say so precisely, and name the one
exception.** https://www.rabbitmq.com/docs/prometheus § *Aggregated and Per-Object Metrics*:
`/metrics` "returns aggregated metrics on this endpoint **by default**";
`prometheus.return_per_object_metrics` "default value … is `false`"; per-object lives at
`/metrics/per-object` and the `rabbitmq_detailed_*` families at `/metrics/detailed`. **4.x does not
change this default**, so `rabbitmq_queue_messages_ready 9` stays label-free after the upgrade and
the sibling alert-rules work-item is safe to build on the aggregated series.
**The one exception, and it must be relayed:** `release-notes/4.2.0.md` §*Quorum Queue Metric
Changes* —
> "Metrics emitted for Ra-based components (quorum queues, Khepri, Stream Coordinator) have changed.
> Some metrics were removed, many were added, some changed their names. Users relying on Prometheus
> metrics starting with `rabbitmq_raft` or `rabbitmq_detailed_raft` will need to update their
> dashboards and/or alerts."

Today that is inert (no quorum queues, D-03) — **but 4.3 makes Khepri the only metadata store, so
`rabbitmq_raft*` series now exist for Khepri itself where they did not on 3.12.** Any alert rule
matching `rabbitmq_raft.*` with a broad regex will start selecting Khepri series after this change.
AC-9 captures the full `/metrics` series-name set before and after and diffs it, so the alerting
work-item gets a real delta rather than a promise.

**D-08 — k8s staging/production: this plan CANNOT verify or fix it, and will not pretend to.**
Stated plainly, as required. `k8s/base/configmap.yaml:28-29,37-38` point at
`rabbitmq.jtoye-infrastructure.svc.cluster.local`. There is **no RabbitMQ manifest anywhere in
`k8s/`** — `k8s/base/` contains configmap, three deployments, two ingresses, networkpolicies,
pg-backup-cronjob and a secrets template, and nothing else. The broker lives in a different
namespace, provisioned outside this repository. Therefore:
- Its **version is unknown and unknowable from this checkout.** No file in this repo declares it, no
  gate reads it, and `scripts/check-runtime-freshness.sh` cannot see it (PIT-2).
- ADR-0002 proposed an in-cluster RabbitMQ cluster operator; it is still **"Proposed (2026-07-12) —
  needs owner sign-off"** and was never built.

What this plan does instead — nothing more, nothing less:
1. Ships `docs/runbooks/rabbitmq-broker-upgrade.md`: the operator-executable procedure to (a) read
   the live version (`rabbitmqctl version`, or `GET /api/overview` → `rabbitmq_version`), (b) if it
   is 3.12.x, follow the **real** chain 3.12 → 3.13 (enable all stable feature flags first) → 4.2 →
   4.3, with the `check_if_cluster_has_classic_queue_mirroring_policy` precondition and the
   `khepri_db`-was-experimental blue/green caveat from C2.
2. Adds a dated **Open Question** to ADR-0002 naming the unknown version, the absent owner, and the
   in-cluster-operator decision that is still unmade, and links the runbook.
3. Adds a `docs/support-horizon.yaml` entry for the k8s broker with `version: unknown` and
   `owner: UNASSIGNED`, so the gate reports it as **UNKNOWN, not clean** (Task 5 / AC-10).
It does **not** author a StatefulSet, does not guess a version, and does not add a dangling ConfigMap
key (PIT-4 / the DEF-6 defect class `k8s/local/configmap-patch.yaml:12-17` warns about).

**D-09 — `k8s/LOCAL.md`'s rabbitmqctl recipes stay as they are; re-validate, don't rewrite.**
`LOCAL.md:1402` records that `list_stomp_connections` rejects the `user` info key on 3.12 and that
`auth_login` is the identity column. Verified: `v4.3.x/deps/rabbitmq_stomp/include/rabbit_stomp.hrl`
`?INFO_ITEMS` is **byte-identical to v3.12.x** — no `user` key. **The recipe is still correct on
4.3.** Update only the version words and the `server:RabbitMQ/3.12.14` evidence line (`:1577`), and
add a dated note that **unreleased `main` adds `user`/`name`/`connected_at`**, so the recipe has a
known expiry at the next major (PIT-1). AC-11 re-runs the command and requires it to *still fail*.

**D-10 — OPS-05: a support-horizon manifest + CI gate is the durable mechanism.**
The generalised question is "what stops the next pinned runtime dependency from silently going out
of support?" Options weighed: (a) Dependabot/Renovate — tracks *newer versions*, not *support end
dates*, and would have said nothing useful about 3.12 (3.12.14 *was* the latest 3.12); (b) Trivy —
flags known CVEs, not support status, and an unpatched series simply accrues silence; (c) a calendar
reminder — not falsifiable, not in the repo, dies with the person. Chosen: **(d) a declared
support-horizon manifest checked in CI**, because it is the only option that (i) lives in the repo,
(ii) fails a build rather than sending an email, (iii) covers *every* pinned runtime uniformly, and
(iv) costs one YAML file and one bash script.
`docs/support-horizon.yaml` declares, per dependency: the pin's **source file + the exact string**,
the **declared support-end date**, the **primary source URL** that date came from, and an **owner**.
`scripts/check-support-horizon.sh` re-reads each pin from its real source file (so the manifest
cannot drift from reality), and exits **1** if any dependency is past its support-end date or within
a 90-day warn window, **2 (VOID)** if the manifest is missing/unparseable, if a declared pin string
is not found in its source file, or if the parsed dependency list is empty — "found nothing" is never
"clean". Initial coverage: `rabbitmq`, `postgres:15-alpine` (`:14`), `quay.io/keycloak/keycloak:24.0.5`
(`:81`), `redis:7-alpine` (`:126`), `mailhog/mailhog:v1.0.1` (`:476`), `eclipse-temurin:21-*`
(`core-java/Dockerfile:5,27`), `golang:1.25-alpine` (`edge-go/Dockerfile:7`), `node:20-alpine`
(`frontend/Dockerfile:5,129`), plus the k8s broker as `unknown`.
**Deliberately in scope as findings, not fixes:** the manifest will immediately expose that
**Keycloak 24.0.5 (Apr 2024) and Node 20 are also aged**, and that `minio/minio`, `minio/mc` and
`ollama/ollama` float on `:latest` (`:393`, `:415`, `:435`) — an unpinned runtime cannot have a
support horizon at all. Those are **recorded as manifest entries with an owner and a follow-up
issue**, not remediated here; fixing them in this work-item would be scope creep, but letting the
gate stay silent about them would reproduce the exact failure being fixed. The gate therefore ships
with `warn_only: true` for entries other than rabbitmq **for one milestone**, with the flip date
recorded in the manifest itself.

</decisions>

<tasks>

<task type="auto">
  <name>Task 1: Capture the falsifiable BEFORE baseline from the running 3.12 broker</name>
  <files>(no repo edits — evidence capture only)</files>
  <read_first>
    - k8s/LOCAL.md:1381-1435 (the existing broker-side evidence idiom to mirror)
    - scripts/smoke-test-stomp-relay.sh:59,94 (the mgmt-API auth pattern and the guest fallback)
  </read_first>
  <action>
Capture, into the plan's evidence block, from the **running** broker — every one of these is the
"fail direction" for a criterion below, so they must be real captured output, not assertions:

1. `docker exec jtoye-rabbitmq rabbitmqctl version` → expect `3.12.14`.
2. `docker inspect --format '{{.Image}}' jtoye-rabbitmq` and
   `docker image inspect --format '{{.Id}}' rabbitmq:3.12-management-alpine` → record both.
3. `docker exec jtoye-rabbitmq rabbitmq-plugins list -e` → record the four enabled plugins with their
   `3.12.14` version strings and the absence of `rabbitmq_web_stomp`.
4. `docker exec jtoye-rabbitmq rabbitmq-diagnostics listeners` → record the amqp/stomp/http/prometheus
   listener rows.
5. Full queue inventory: `GET /api/queues/%2F` → for each queue record
   `name, type, durable, exclusive, auto_delete, messages`. Record the **count** of
   `order.state-changes.sse.*` rows and the **running core-java replica count** side by side (PIT-7).
6. `webhook.deliveries.dlq` depth → expect **9**.
7. `curl -s http://localhost:15692/metrics` → save verbatim, and save the sorted unique **series-name
   set** (`grep -oE '^[a-z_]+' | sort -u`) as `metrics-series-before.txt`. Confirm
   `rabbitmq_queue_messages_ready` carries **no** `queue` label.
8. `docker exec jtoye-rabbitmq rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy`
   → expect exit 0.
9. **The fail-direction run for (8), which is mandatory and must happen BEFORE (8) is trusted:**
   create a throwaway mirroring policy, re-run the check, capture the non-zero exit and the message,
   then delete the policy and re-confirm exit 0:
   ```
   docker exec jtoye-rabbitmq rabbitmqctl set_policy ha-probe '^zzz-probe$' '{"ha-mode":"all"}' --apply-to queues
   docker exec jtoye-rabbitmq rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy; echo "exit=$?"   # expect NON-ZERO
   docker exec jtoye-rabbitmq rabbitmqctl clear_policy ha-probe
   docker exec jtoye-rabbitmq rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy; echo "exit=$?"   # expect 0
   ```
   Without step 9 the clean result in step 8 is an already-0 assertion and proves nothing.
10. `docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections user` → capture the
    `Info key(s) user are not supported` rejection (D-09 baseline).
11. Repo-side grep baselines, recorded as **non-zero** counts so the greps are shown capable of
    firing: `grep -rn 'rabbitmq:3\.' --include='*.yml' --include='*.java' --include='*.md' .`
    (excluding `node_modules`, `.planning/phases`, `.planning/milestones`) → record the count.
  </action>
</task>

<task type="checkpoint:human-action">
  <name>Task 2: Adjudicate the 9 dead letters in webhook.deliveries.dlq, then destroy the volume</name>
  <files>(evidence + a gitignored export file)</files>
  <action>
**This task exists because D-02 destroys data that belongs to tenants, and no agent may make that
call unattended.** Also: this checkout can be driven by a second concurrent session
(`env_concurrent_working_tree`), so stopping the shared dev broker needs a human in the loop.

1. **Non-destructive export first.** Peek all 9 with requeue so the export cannot itself lose them:
   ```
   curl -s -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
     -H 'content-type: application/json' \
     -d '{"count":9,"ackmode":"ack_requeue_true","encoding":"auto"}' \
     http://localhost:15672/api/queues/%2F/webhook.deliveries.dlq/get \
     > .evidence/webhook-dlq-export.json
   ```
   Re-read the depth afterwards and assert it is **still 9** — an export that drained the queue is a
   failed export. `.evidence/` must be gitignored: these payloads carry tenant data (T-27-03).
2. **Present to the human:** the 9 messages' routing keys, timestamps and `x-death` counts, plus the
   corresponding `webhook_delivery` DB rows if they still exist. Ask for one of:
   (a) re-publish to `webhook.deliveries.dlq` after the new broker is up; (b) discard, with the
   reason recorded in the SUMMARY; (c) abort the upgrade.
3. Only after an explicit answer:
   ```
   docker compose -f docker-compose.full-stack.yml stop rabbitmq
   docker compose -f docker-compose.full-stack.yml rm -f rabbitmq
   docker volume rm jtoye_oaas_2026_rabbitmq_data      # confirm the real name via `docker volume ls`
   ```
Do not proceed past this task on an unanswered question. If the answer is (c), stop the plan.
  </action>
</task>

<task type="auto">
  <name>Task 3: Bump the image pins and bring the 4.3 broker up as a fresh install</name>
  <files>docker-compose.full-stack.yml, core-java/src/test/java/uk/jtoye/core/order/OrderEventFanoutTopologyIntegrationTest.java</files>
  <read_first>
    - docker-compose.full-stack.yml:142-165 (the whole rabbitmq service block)
    - infra/rabbitmq/enabled_plugins (confirm no edit needed — D-06)
    - core-java/src/test/java/uk/jtoye/core/order/OrderEventFanoutTopologyIntegrationTest.java:55-56
  </read_first>
  <action>
1. `docker-compose.full-stack.yml:144` → `image: rabbitmq:4.3.4-management-alpine`. **Change nothing
   else in the block** — ports, the `enabled_plugins` bind mount, the `rabbitmq_data` volume
   declaration, `RABBITMQ_DEFAULT_USER`/`PASS` and the `rabbitmq-diagnostics ping` healthcheck are
   all still correct on 4.3 (C11). Add a comment above the pin giving the series' community-support
   end date and pointing at `docs/support-horizon.yaml`.
2. `OrderEventFanoutTopologyIntegrationTest.java:56` → `rabbitmq:4.3.4-management-alpine`. Leave
   `core-java/build.gradle.kts:101` at `1.21.4` (D-05) and say so in a comment.
3. Bring it up **with recreation, never `start`** (PIT-3):
   ```
   docker compose -f docker-compose.full-stack.yml up -d --force-recreate rabbitmq
   ```
   Wait for `healthy`, then restart the app containers so they re-declare the topology against the
   empty broker:
   ```
   docker compose -f docker-compose.full-stack.yml restart core-java
   ```
4. If Task 2's answer was (a), re-publish the exported messages to `webhook.deliveries.dlq` now and
   re-assert the depth is 9.
5. **Do not** touch `infra/rabbitmq/enabled_plugins`. **Do not** set `default_queue_type` (D-04).
  </action>
</task>

<task type="auto">
  <name>Task 4: Re-prove the runtime — topology, STOMP relay, plugins, metrics, Testcontainers</name>
  <files>(no repo edits — this is where AC-1 … AC-7, AC-9, AC-11 are executed)</files>
  <action>
Re-run every probe from Task 1 against the new broker and diff against the captured baseline. Then
run the fail directions listed in `<acceptance_criteria>` — each one must be *executed*, and both
directions' real output recorded. In particular, do not skip: AC-1's `docker compose start` inversion,
AC-4's `default_queue_type=quorum` probe (with revert), AC-5's multi-segment SUBSCRIBE, and AC-7's
second Gradle run without `cleanTest`.

Run the Testcontainers proof against the live build dir, never `core-java/build` (PIT-6):
```
./gradlew :core-java:cleanTest :core-java:test \
  --tests '*OrderEventFanoutTopologyIntegrationTest*' -PincludeIntegration
```
and read the result out of `core-java/build-local/test-results/test/TEST-uk.jtoye.core.order.OrderEventFanoutTopologyIntegrationTest.xml`.
  </action>
</task>

<task type="auto">
  <name>Task 5: OPS-05 — the support-horizon manifest and its fail-closed CI gate</name>
  <files>docs/support-horizon.yaml, scripts/check-support-horizon.sh, .github/workflows/docs-freshness.yml</files>
  <read_first>
    - scripts/check-branch-behind-base.sh (the fail-closed 0/1/2 exit convention and VOID messaging to mirror)
    - scripts/check-runtime-freshness.sh:226-239 (the "discovered ZERO … must never report clean" idiom to copy verbatim in spirit)
    - .github/workflows/docs-freshness.yml (where to add the step, and the existing job's permissions block)
  </read_first>
  <action>
**`docs/support-horizon.yaml`** — one entry per pinned runtime dependency:
```yaml
warn_window_days: 90
flip_warn_only_to_error_on: "2026-12-31"   # D-10: the date the non-rabbitmq entries stop being warn-only
dependencies:
  - id: rabbitmq
    pin_source: docker-compose.full-stack.yml
    pin_string: "rabbitmq:4.3.4-management-alpine"
    series: "4.3"
    support_ends: "2026-11-30"
    support_source: "https://www.rabbitmq.com/release-information"
    owner: <named human>
    warn_only: false
  - id: rabbitmq-k8s
    pin_source: NONE                       # D-08: out-of-repo, no manifest in k8s/
    pin_string: unknown
    series: unknown
    support_ends: unknown
    owner: UNASSIGNED
    note: "k8s/base/configmap.yaml:28 points at rabbitmq.jtoye-infrastructure.svc.cluster.local; version unverifiable from this repo. See docs/runbooks/rabbitmq-broker-upgrade.md and ADR-0002."
  # … postgres, keycloak, redis, mailhog, temurin, golang, node — each with pin_source:line,
  #   support_source URL, owner, and warn_only: true until flip_warn_only_to_error_on
  # … minio/minio, minio/mc, ollama/ollama: pin_string ":latest" -> series: unpinned
```

**`scripts/check-support-horizon.sh`** — `set -euo pipefail`, all matching via here-strings (PIT-5):
- Parse the manifest. **Exit 2 (VOID)** if it is missing, unparseable, or yields **zero** entries.
- For each entry with `pin_source != NONE`: read the source file and assert `pin_string` occurs in
  it. A declared pin that is not present in its own source file means the manifest has drifted from
  reality → **exit 2**, not exit 0. This is what stops the manifest becoming decorative.
- `support_ends: unknown` or `series: unpinned` → **report UNKNOWN and exit 1** (never 0). A
  dependency whose horizon nobody knows is a finding, not a pass.
- `support_ends` in the past → **ERROR**; within `warn_window_days` → **WARN**. Entries with
  `warn_only: true` downgrade ERROR→WARN **only until** `flip_warn_only_to_error_on`, after which the
  downgrade stops applying (so the exemption expires by itself).
- Exit 1 if any ERROR or any UNKNOWN; exit 0 only when every entry is known, present and in support.
- Print a table; never print a secret; name the offending `id` and its `pin_source:line`.

**`.github/workflows/docs-freshness.yml`** — add a `Support horizon` step invoking the script.
Declare job-level `permissions:` explicitly (the repo-fact that the default `GITHUB_TOKEN` is
restricted). Do not put the string `it(` or `test(` in any new doc (PIT-8).
  </action>
</task>

<task type="auto">
  <name>Task 6: The k8s/prod operator action, ADR-0002 follow-through, and doc reconciliation</name>
  <files>docs/runbooks/rabbitmq-broker-upgrade.md, docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md, k8s/LOCAL.md, k8s/DEPLOYMENT.md, CLAUDE.md, AGENTS.md, .planning/codebase/STACK.md, .planning/codebase/INTEGRATIONS.md</files>
  <action>
1. **`docs/runbooks/rabbitmq-broker-upgrade.md`** (new) — per D-08: how to read the live version;
   the real 3.12 → 3.13 → 4.2 → 4.3 chain with the "enable all stable feature flags **before** each
   hop, or the upgrade fails" gate quoted from `4.0.1.md`; the
   `check_if_cluster_has_classic_queue_mirroring_policy` precondition; the `khepri_db`-experimental
   blue/green caveat; the Erlang matrix from C4; and the explicit statement that the dev/compose
   broker took the *fresh-install* path (C3) which is **not** available for a broker holding real
   messages.
2. **ADR-0002** — append a dated "2026-07-26 — Open question (Phase 27)" section: the staging/prod
   broker's version is unverifiable from this repo, no manifest exists, no owner is assigned, the
   in-cluster-cluster-operator decision proposed 2026-07-12 is still unsigned. Link the runbook and
   the `rabbitmq-k8s` manifest entry. **Do not change the ADR's Status** — that needs owner sign-off,
   not an agent.
3. **`k8s/LOCAL.md`** — per D-09, update the version words at `:1388`, `:1577`, `:1584`; keep the
   `auth_login` recipe unchanged and add the dated note that unreleased `main` adds a `user` info key
   so the recipe has a known expiry. Re-record the CONNECTED-frame evidence line as
   `server:RabbitMQ/4.3.4`.
4. **`k8s/DEPLOYMENT.md`** — add the support-horizon gate beside the two existing runtime-parity
   gates, stating what each exit code means and that CI runs this one (unlike the runtime half).
5. **Version words:** `CLAUDE.md:83,128,136`; `AGENTS.md:82,127,135`;
   `.planning/codebase/STACK.md:25,118,173,183`; `.planning/codebase/INTEGRATIONS.md:63,64`. Also fix
   the **stale line pointer** `docker-compose.full-stack.yml:88` → `:144` in STACK.md:183 and
   INTEGRATIONS.md:64. In INTEGRATIONS.md, correct the stale claim that the edge gateway uses
   RabbitMQ (verified: `edge-go` has no AMQP dependency at all).
   **Do NOT rewrite `.planning/phases/**` or `.planning/milestones/**`** — those are dated historical
   records and editing them would falsify history. AC-12 excludes them explicitly.
  </action>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|---|---|
| broker version ↔ patch supply | An out-of-support broker receives no fix for any future CVE from any channel — the whole reason for this work-item |
| AMQP/STOMP authn surface | A major version change touches SASL mechanism defaults, anonymous-login handling and the STOMP login path — the credential wall between core-java and the broker |
| dead-letter contents ↔ volume destruction | 9 real messages carrying tenant webhook payloads are destroyed by D-02 unless Task 2 intervenes |
| management API (15672) + Prometheus (15692) | Both are published to the host in compose; the mgmt API exposes queue contents |
| broker listeners ↔ network | Adding a plugin adds a listener; the compose broker publishes 5672/15672/61613 on the host |
| repo pins ↔ out-of-repo staging/prod broker | This repo declares an endpoint it does not own, provision or version |

## STRIDE Threat Register (ASVS L1)

| ID | Category | Component | Disposition | Mitigation |
|---|---|---|---|---|
| T-27-01 | Elevation of Privilege | broker running an unpatched series | **mitigate — the point of the plan** | ASVS V14.2 (unsupported components). 3.12 has had no patch channel since 2025-06-30. Moving to 4.3.4 restores one; Task 5 keeps it restored. Falsified by AC-1's runtime read, not by the compose file. |
| T-27-02 | Spoofing / Elevation | AMQP + STOMP authentication after a major-version change | **mitigate** | A broker upgrade *is* an authn-surface change. `RABBITMQ_DEFAULT_USER/PASS` remain supported (C11) and the dedicated `stomp-login` path is unchanged, but this is asserted at runtime, not assumed: AC-3 requires the STOMP connection to authenticate as the dedicated login (`auth_login = jtoye`) with **zero** rows whose login is `guest`, and requires `Access refused` count = 0 in the broker log — mirroring the `k8s/LOCAL.md:1372` idiom. 4.0 introduced `anonymous_login_user`/`anonymous_login_pass` (default `guest`); AC-3's guest-row check is what would catch an accidental anonymous path. |
| T-27-03 | Information Disclosure | the 9 exported dead letters | **mitigate** | The export lands in a gitignored `.evidence/` path, is never committed, never printed to a log, and is deleted after the disposition is recorded. Payloads carry tenant webhook data. Task 2 asserts the gitignore is effective before writing. |
| T-27-04 | Denial of Service | destroying `rabbitmq_data` under a concurrent session | **mitigate** | `autonomous: false` + `checkpoint:human-action` on Task 2. A second session may own this stack (`env_concurrent_working_tree`). No queue is deleted, no volume removed, before the human answers. |
| T-27-05 | Elevation of Privilege | new broker listeners | **mitigate** | D-06 freezes the plugin set; `rabbitmq_web_stomp` stays disabled. AC-2 asserts `enabled_plugins` is byte-identical to its pre-change content and that `rabbitmq-diagnostics listeners` shows exactly the four expected listeners and no fifth. Fail direction: add a fifth plugin and confirm the assertion fires. |
| T-27-06 | Tampering | vhost `default_queue_type` | **mitigate** | D-04. Setting it to `quorum` would force every STOMP subscription queue into an illegal `durable ∧ ¬exclusive ∧ auto_delete` declaration (source-verified) and break KDS. AC-4's fail direction sets it deliberately, records the failure, and reverts — proving both the hazard and the guard. |
| T-27-07 | Information Disclosure | management API published on the host | **accept (unchanged)** | 15672/15692 were already published pre-change; this plan does not widen them. Single-user dev host. Any change belongs with the compose-exposure review, not here. |
| T-27-08 | Repudiation | "we upgraded" with no evidence of what actually runs | **mitigate** | AC-1 reads the version out of the running broker twice by independent routes and compares the running container's image ID to the tag's — the `trap_stale_containers_after_phase` class. |
| T-27-09 | Tampering | the support-horizon gate silently passing | **mitigate** | The gate exits **2 (VOID)** on a missing/unparseable/empty manifest **and** when a declared `pin_string` is absent from its own `pin_source` — so a manifest that has drifted from the tree fails rather than reports clean. AC-10 runs all three fail arms. |
| T-27-10 | Denial of Service | alert rules silently breaking on renamed metrics | **mitigate** | D-07. The before/after `/metrics` series-name diff (AC-9) is handed to the sibling alerting work-item as data. 4.2 renamed `rabbitmq_raft*`; 4.3's Khepri-only store makes those series appear where they did not exist on 3.12. |
| T-27-11 | Tampering | the out-of-repo staging/prod broker | **accept + escalate** | D-08. Out of this repo's control; no fix is invented. Recorded as an ADR-0002 open question, a runbook, and an `UNKNOWN`-status manifest entry that makes the gate exit non-zero rather than clean. |
| T-27-SC | Tampering | supply chain | **mitigate** | No package is installed. `rabbitmq:4.3.4-management-alpine` is an official Docker Library image; the exact digest is recorded at pull time and compared to the Docker Hub tag metadata (`last_updated 2026-07-24T14:53:26Z`). |

## Cross-Cutting Quality Dimensions

| Dimension | Verdict | Reason |
|---|---|---|
| **Web performance (mobile-first)** | **N/A** | No page, route, bundle, image or asset changes. The only user-visible surface touched is the KDS live feed, whose latency is dominated by the relay round-trip, not by broker version; no CWV metric is in scope. Recorded N/A rather than dropped. |
| **SEO / discoverability** | **N/A** | No public/unauthenticated surface is touched. The KDS is behind auth; the broker has no public surface. |
| **AI agent-readiness** | **N/A for the API contract, with one recorded check** | No endpoint, DTO, OpenAPI schema, error shape or Idempotency-Key contract changes. Verified specifically that the 4.0 `x-death` re-publish change (C6) does not affect the typed RFC 7807 error path or the outbox replay contract. No new MCP tool is warranted: "which broker version is running" is an operator question, not a tenant-facing capability. |
| **Security** | **APPLICABLE — see the register above** | A broker upgrade touches the authn/authz surface (T-27-02) and the whole work-item exists to close ASVS V14.2 (unsupported component). Routed through `/gsd-secure-phase`. |
| **Falsifiable evidence + runtime parity** | **APPLICABLE — the dominant dimension here** | Every criterion below carries an executed break. Runtime parity is the crux: a "broker is 4.3.4" claim read from `docker-compose.full-stack.yml` is worthless (AC-1). `scripts/check-runtime-freshness.sh` structurally cannot cover a pulled image (PIT-2), so the broker read is explicit. `scripts/check-branch-behind-base.sh` must be green before the PR. |
</threat_model>

<acceptance_criteria>

Every criterion states its **deliberate break** and the **expected fail output**. A criterion whose
break was not executed is not satisfied — record it as such rather than reporting the pass.

**AC-1 — The RUNNING broker is 4.3.4, read from the runtime by two independent routes.**
```bash
v1=$(docker exec jtoye-rabbitmq rabbitmqctl version | tr -d '\r')
v2=$(curl -sf -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
      http://localhost:15672/api/overview | jq -r .rabbitmq_version)
[ "$v1" = "4.3.4" ] && [ "$v2" = "4.3.4" ] || exit 1
# and the running container is the tag, not a survivor of `start`
[ "$(docker inspect --format '{{.Image}}' jtoye-rabbitmq)" \
= "$(docker image inspect --format '{{.Id}}' rabbitmq:4.3.4-management-alpine)" ] || exit 1
```
**Break (execute it, in this order):** after editing `docker-compose.full-stack.yml:144` but *before*
recreating, run `docker compose -f docker-compose.full-stack.yml start rabbitmq` and re-run the block.
**Expected fail output:** `v1=3.12.14`, `v2=3.12.14`, and an image-ID mismatch — the compose file says
4.3.4 while the runtime is 3.12.14. This is the whole point: the criterion reads the runtime, and
`start` is proven not to be a rebuild (PIT-3). Also record the pre-upgrade run from Task 1 as a second
fail-direction datapoint.

**AC-2 — No 3.x RabbitMQ image is pinned anywhere in the live tree, and `enabled_plugins` is untouched.**
```bash
hits=$(grep -rn 'rabbitmq:3\.' --include='*.yml' --include='*.yaml' --include='*.java' \
        --include='*.kts' --include='*.md' . \
        | grep -v node_modules | grep -v '\.planning/phases/' | grep -v '\.planning/milestones/' | wc -l)
[ "$hits" -eq 0 ] || exit 1
git diff --exit-code -- infra/rabbitmq/enabled_plugins   # D-06: must be unchanged
```
**Not-already-zero proof:** Task 1 records this same grep at a **non-zero** count on the pre-change
tree, which is what demonstrates the pattern can fire. Without that number this is a vacuous
already-0 grep.
**Break:** re-introduce `rabbitmq:3.12-management-alpine` into `docker-compose.full-stack.yml`.
**Expected fail output:** `hits=1` naming `docker-compose.full-stack.yml:144`.
(Note: `.planning/phases/**` and `.planning/milestones/**` are excluded deliberately — dated records.)

**AC-3 — All four plugins run, exactly four listeners exist, and STOMP authenticates as the dedicated login.**
```bash
out=$(docker exec jtoye-rabbitmq rabbitmq-diagnostics listeners)
for p in amqp stomp http prometheus; do grep -q "$p" <<< "$out" || exit 1; done   # here-string, PIT-5
grep -qi 'web_stomp\|web-stomp' <<< "$out" && exit 1                              # D-06
docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections conn_name auth_login protocol
# expect >=1 row, auth_login = the dedicated STOMP login, ZERO rows with auth_login = guest
docker logs jtoye-rabbitmq 2>&1 | grep -c 'Access refused'   # expect 0
```
**Break:** bind-mount an `enabled_plugins` with `rabbitmq_stomp` removed and recreate.
**Expected fail output:** `listeners` shows amqp/http/prometheus but **no stomp row**; the loop exits
1 at `stomp`; `list_stomp_connections` errors that the plugin is not enabled.
**Second break (for the guest arm):** point `STOMP_CLIENT_LOGIN` at `guest` and confirm the
zero-guest-rows assertion fires. Revert both.

**AC-4 — All 13 queues have their exact expected property tuple, read from the running broker.**
```bash
q=$(curl -sf -u "$U:$P" 'http://localhost:15672/api/queues/%2F?columns=name,type,durable,exclusive,auto_delete')
# 12 durable app queues: type=classic, durable=true, exclusive=false, auto_delete=false
# N anonymous queues matching ^order\.state-changes\.sse\. : durable=false, exclusive=true, auto_delete=true
replicas=$(docker compose -f docker-compose.full-stack.yml ps --format '{{.Name}}' | grep -c core-java)
sse=$(jq '[.[]|select(.name|startswith("order.state-changes.sse."))]|length' <<< "$q")
[ "$sse" -eq "$replicas" ] || exit 1     # PIT-7: derived, never hardcoded to 1
jq -e '[.[]|select(.name|startswith("order.state-changes.sse.")|not)
        |select(.type=="classic" and .durable and (.exclusive|not) and (.auto_delete|not))]|length == 12' <<< "$q"
```
**Break — and it doubles as the D-04 proof, so it is mandatory:**
```bash
curl -sf -u "$U:$P" -H 'content-type: application/json' \
  -X PUT -d '{"default_queue_type":"quorum"}' http://localhost:15672/api/vhosts/%2F
docker compose -f docker-compose.full-stack.yml restart core-java
```
**Expected fail output:** the `AnonymousQueue` declaration is rejected —
`PRECONDITION_FAILED - invalid property 'exclusive' for queue ... in vhost '/'` (quorum requires
`durable ∧ ¬exclusive ∧ ¬auto_delete`, `rabbit_quorum_queue.erl:279-285`) — core-java fails to start
its SSE fan-out, `sse` drops to 0, and a KDS SUBSCRIBE errors. **Revert immediately**
(`{"default_queue_type":"classic"}`), restart, and re-assert the clean state.
*Note the non-vacuous shape:* the expected classic-queue count is **12, not 0**, and the anonymous
count is `replicas`, not a constant — neither is trivially true on any tree.

**AC-5 — The KDS STOMP relay works on 4.3, AND the single-segment destination rule still rejects a multi-segment topic.**
Positive arm: drive the KDS page, capture the CONNECTED frame's `server:` header — expect
`server:RabbitMQ/4.3.4` (the 4.3 analogue of `k8s/LOCAL.md:1577`'s `server:RabbitMQ/3.12.14`) — and
confirm one real order event traverses the relay and repaints the board.
**Break (this is the load-bearing half, not an extra):** issue a SUBSCRIBE to the pre-#269 shape
`/topic/kitchen/<tenantId>/<shopId>`.
**Expected fail output:** an `ERROR` frame `Invalid destination` and the session torn down —
matching `k8s/LOCAL.md:1613`'s recorded 3.12 behaviour. Source-predicted by
`rabbit_routing_parser.erl:54-57` on `v4.3.x`, where `topic` matches only the single-`[Name]` clause.
**Why this matters:** without this arm, the positive arm alone cannot distinguish "the relay works"
from "the relay would accept anything", and the #269 fix's continued necessity would be unproven.

**AC-6 — No classic mirroring policy exists (the 4.0 removal is a genuine no-op here).**
`docker exec jtoye-rabbitmq rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy`
→ exit 0.
**Explicitly declared vacuous, and replaced:** the obvious form —
`grep -rn 'x-ha-policy\|ha-mode' core-java/src/main` — is **already 0 on the current tree**, so it is
incapable of failing and is **not** accepted as evidence. The runtime diagnostic is the criterion,
and Task 1 step 9 runs its fail direction first.
**Break:** `rabbitmqctl set_policy ha-probe '^zzz-probe$' '{"ha-mode":"all"}' --apply-to queues`.
**Expected fail output:** non-zero exit and `Cluster has classic queue mirroring policies: ha-probe`.
Clear the policy and re-confirm 0.

**AC-7 — The Testcontainers fan-out proof passes on the 4.3 image, and is proven to have actually executed.**
```bash
./gradlew :core-java:cleanTest :core-java:test \
  --tests '*OrderEventFanoutTopologyIntegrationTest*' -PincludeIntegration
xml=core-java/build-local/test-results/test/TEST-uk.jtoye.core.order.OrderEventFanoutTopologyIntegrationTest.xml
grep -q 'tests="2"' <<< "$(cat "$xml")" && grep -q 'failures="0"' <<< "$(cat "$xml")"
[ "$xml" -nt /tmp/run-start-marker ] || exit 1     # the XML was written by THIS run
```
Read from `build-local`, **never** `core-java/build` (PIT-6).
**Break:** re-run the identical command **without** `cleanTest`.
**Expected fail output:** Gradle prints `> Task :core-java:test UP-TO-DATE`, no test executes, and the
XML mtime does not advance past the marker — the `-nt` check exits 1. This proves `cleanTest` is
load-bearing and that a green Gradle line is not evidence of execution.

**AC-8 — The k8s render is provably UNCHANGED by this work-item.**
```bash
bash k8s/scripts/render-golden.sh --check     # or: diff <(kubectl kustomize k8s/staging) k8s/goldens/staging.yaml
```
must be empty for staging, production and local.
**Why this shape:** `kubectl kustomize` **strips comments and sorts map keys alphabetically**
(PIT-4), so a comment added to `k8s/base/configmap.yaml` is invisible to any rendered-output scan —
an ordered or comment-seeking assertion there would be structurally vacuous. And per D-08 no
ConfigMap **key** may be added, because a key with no `configMapKeyRef` consumer is silently ignored
(the DEF-6 class `k8s/local/configmap-patch.yaml:12-17` exists to prevent). So the honest criterion is
the negative one: **this plan does not touch the k8s render at all.**
**Break:** change `k8s/base/configmap.yaml`'s `rabbitmq.port` to `5673` and re-render.
**Expected fail output:** a non-empty diff against `k8s/goldens/staging.yaml` on the `rabbitmq.port`
line. Revert.

**AC-9 — The Prometheus scrape stays aggregated, and the series-name delta is captured for the alerting work-item.**
```bash
curl -sf http://localhost:15692/metrics | grep -oE '^[a-z_]+' | sort -u > metrics-series-after.txt
diff metrics-series-before.txt metrics-series-after.txt    # captured, adjudicated line by line
grep -E '^rabbitmq_queue_messages_ready ' <<< "$(curl -sf http://localhost:15692/metrics)"
# must match the LABEL-FREE form; a `{queue="…"}` label means the mode changed
```
Every added/removed series name must be adjudicated in the SUMMARY, with `rabbitmq_raft*` called out
explicitly (D-07: 4.2 renamed them; 4.3's Khepri-only store makes them appear where 3.12 had none).
**Break:** set `prometheus.return_per_object_metrics = true` in a mounted `rabbitmq.conf` and recreate.
**Expected fail output:** `rabbitmq_queue_messages_ready{queue="webhook.deliveries.dlq",vhost="/"} 9`
— the label-free grep finds no match and the diff explodes. Remove the conf and recreate.
*This criterion cannot be satisfied by a clean diff alone:* an empty diff is itself the finding to
report, and the break proves the check would have caught a mode change.

**AC-10 — The support-horizon gate is real: it passes on the fixed tree and fails in four distinct ways.**
`bash scripts/check-support-horizon.sh` → exit 0 on the post-change tree (with the k8s entry
reporting UNKNOWN → see the fourth break).
**Break 1 (expired):** set `rabbitmq.support_ends: "2024-02-29"` (3.12's real date).
→ **exit 1**, `ERROR rabbitmq: support ended 2024-02-29 (881 days ago) — docker-compose.full-stack.yml:144`.
**Break 2 (manifest drift — the anti-decorative arm):** leave the manifest saying
`rabbitmq:4.3.4-management-alpine` but revert `docker-compose.full-stack.yml:144` to 3.12.
→ **exit 2 (VOID)**, `declared pin "rabbitmq:4.3.4-management-alpine" NOT FOUND in docker-compose.full-stack.yml`.
**Break 3 (empty/VOID):** truncate `docs/support-horizon.yaml` to `dependencies: []`.
→ **exit 2**, `discovered ZERO dependencies — a gate that finds nothing must never report clean`.
**Break 4 (unknown ≠ clean):** confirm the `rabbitmq-k8s` entry with `support_ends: unknown` makes
the gate report **UNKNOWN and exit 1**, not 0 — then confirm the gate exits 0 only once that entry is
either resolved or explicitly `warn_only: true` with an owner and a dated flip.
All four must be **executed**, not described.

**AC-11 — `k8s/LOCAL.md`'s rabbitmqctl recipe is re-validated against 4.3, not assumed to still hold.**
```bash
docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections user 2>&1
# MUST STILL FAIL: "Error (argument validation): Info key(s) user are not supported"
docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections conn_name auth_login peer_host protocol
# MUST SUCCEED and show the dedicated login
```
**This criterion is inherently falsifiable — its expected result is a command FAILURE.** If 4.3
accepted `user`, the criterion fails and `k8s/LOCAL.md:1402` must be rewritten rather than
version-bumped. Source-predicted: `v4.3.x/deps/rabbitmq_stomp/include/rabbit_stomp.hrl` `?INFO_ITEMS`
is byte-identical to `v3.12.x` and contains no `user` key.
**Break:** run the same command against `main`-era behaviour (documented, not executed): unreleased
`main` **adds** `user`/`name`/`connected_at`, so this recipe has a known expiry at the next major.
Record that as a dated note in LOCAL.md — a criterion with a known expiry date is more honest than one
asserted as permanent.

**AC-12 — Docs state 4.3.4, historical records are untouched, and docs-freshness stays green.**
```bash
grep -rn 'RabbitMQ 3\.12\|3\.12-management-alpine' CLAUDE.md AGENTS.md k8s/ .planning/codebase/ docs/ | wc -l   # expect 0
git diff --stat -- .planning/phases .planning/milestones     # expect EMPTY — dated records are not rewritten
bash scripts/docs-freshness.sh                               # expect exit 0
bash scripts/check-branch-behind-base.sh                     # expect exit 0 before the PR
```
**Not-already-zero proof:** Task 1 records this grep at **12 hits** pre-change (`CLAUDE.md:83,128,136`;
`AGENTS.md:82,127,135`; `STACK.md:25,118,173,183`; `INTEGRATIONS.md:63,64`), so the pattern is shown
capable of firing.
**Break:** restore `RabbitMQ: 3.12-management-alpine` to `CLAUDE.md:136`.
**Expected fail output:** count `1` naming `CLAUDE.md:136`.
**Second break (for the history arm):** touch a line in `.planning/phases/26-*/26-08-SUMMARY.md`.
**Expected fail output:** `git diff --stat` is non-empty, failing the "history is not rewritten" arm.
`scripts/docs-freshness.sh` must stay green: this plan changes no `it(`/`test(` block count, and no
new doc may contain those literal tokens (PIT-8).

</acceptance_criteria>

<verification>
```bash
# --- runtime identity (the criterion that cannot be satisfied from the compose file) ---
docker exec jtoye-rabbitmq rabbitmqctl version
curl -sf -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" http://localhost:15672/api/overview | jq -r .rabbitmq_version
docker inspect --format '{{.Image}}' jtoye-rabbitmq
docker image inspect --format '{{.Id}}' rabbitmq:4.3.4-management-alpine

# --- topology, plugins, identity ---
docker exec jtoye-rabbitmq rabbitmq-diagnostics listeners
docker exec jtoye-rabbitmq rabbitmq-diagnostics check_if_cluster_has_classic_queue_mirroring_policy; echo "exit=$?"
docker exec jtoye-rabbitmq rabbitmq-diagnostics list_deprecated_features
docker exec jtoye-rabbitmq rabbitmqctl list_stomp_connections conn_name auth_login protocol
curl -sf -u "$U:$P" 'http://localhost:15672/api/queues/%2F?columns=name,type,durable,exclusive,auto_delete' | jq .

# --- metrics delta for the sibling alerting work-item ---
curl -sf http://localhost:15692/metrics | grep -oE '^[a-z_]+' | sort -u > metrics-series-after.txt
diff metrics-series-before.txt metrics-series-after.txt || true

# --- tests (cleanTest is load-bearing; read build-local, not build) ---
./gradlew :core-java:cleanTest :core-java:test --tests '*OrderEventFanoutTopologyIntegrationTest*' -PincludeIntegration
./gradlew :core-java:cleanTest :core-java:test          # full suite — no count drift
cd frontend && npx tsc --noEmit -p tsconfig.json && npm run build

# --- gates ---
bash scripts/check-support-horizon.sh;    echo "horizon exit=$?"
bash scripts/docs-freshness.sh;           echo "docs exit=$?"
bash scripts/check-branch-behind-base.sh; echo "behind-base exit=$?"
bash k8s/scripts/render-golden.sh --check
```
Every **break** listed in `<acceptance_criteria>` must be executed and both directions' real output
pasted into the SUMMARY. A criterion recorded with only a passing run is recorded as **UNFALSIFIED**,
not satisfied.
</verification>

<success_criteria>
- The running dev broker reports 4.3.4 by two independent runtime routes, and its container image ID
  matches the tag's — with the `docker compose start` inversion executed and recorded as the fail arm.
- The 9 `webhook.deliveries.dlq` messages have an explicit, human-given disposition recorded in the
  SUMMARY; none were discarded without a decision.
- All 13 queues carry their exact expected property tuple, the anonymous count derived from the live
  replica count; the `default_queue_type=quorum` hazard was exercised, observed to break the SSE
  fan-out, and reverted.
- The KDS relay is proven working on 4.3 **and** proven to still reject a multi-segment `/topic`
  destination — #269 remains load-bearing.
- No queue became a quorum queue; the per-queue eligibility table (12 eligible / 1 structurally
  impossible) is recorded with its reason, and the defer decision is justified by single-node topology.
- The classic-mirroring check was falsified with a real `ha-mode` policy before its clean result was
  accepted; the equivalent grep is recorded as vacuous, not as evidence.
- The Testcontainers fan-out test executed (XML mtime advanced) and passed on the 4.3 image; the
  no-`cleanTest` `UP-TO-DATE` run is recorded as the fail arm.
- `/metrics` remains aggregated; the before/after series-name diff is handed to the alerting
  work-item with `rabbitmq_raft*` adjudicated explicitly.
- `scripts/check-support-horizon.sh` is green on the fixed tree and was shown to fail in all four
  ways (expired, manifest drift → VOID, empty → VOID, unknown → non-zero).
- The k8s render is byte-identical to the goldens; no manifest was invented, no dangling ConfigMap key
  added, and the staging/prod broker's unverifiability is recorded as an ADR-0002 open question plus a
  runbook plus an `UNKNOWN` manifest entry — not as a fix.
- Docs state 4.3.4, the two stale `:88` line pointers and the stale edge-uses-RabbitMQ claim are
  corrected, `.planning/phases/**` and `.planning/milestones/**` are untouched, and both the
  docs-freshness and branch-behind-base gates are green before the PR.
</success_criteria>

<output>
Create `.planning/phases/27-operational-maturity/27-02-SUMMARY.md` when done.

Record: both directions' verbatim output for **every** criterion (a pass-only criterion is reported as
UNFALSIFIED, never as satisfied); the pre- and post-upgrade version reads and image IDs; the human's
disposition of the 9 dead letters and the reasoning given; the full 13-row queue property table read
from the running broker; the CONNECTED-frame `server:` header and the `Invalid destination` ERROR
frame from AC-5's break; the `default_queue_type=quorum` failure text and confirmation of the revert;
the `ha-mode` policy break output; the Gradle `UP-TO-DATE` no-op evidence; the complete
`/metrics` series-name diff with `rabbitmq_raft*` adjudicated; all four support-horizon gate failure
modes; and the list of dependencies the new manifest exposed as aged or unpinned (Keycloak 24.0.5,
Node 20, the three `:latest` images) with the follow-up issue numbers.

State plainly, in its own section: **this plan did not and could not verify the staging/production
broker's version.** Name what was produced instead (runbook, ADR-0002 open question, `UNKNOWN`
manifest entry) and what remains unowned.
</output>
