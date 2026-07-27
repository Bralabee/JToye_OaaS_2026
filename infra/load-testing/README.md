# Load testing

Two scripts live here. They are not alternatives — one generates load, the other **asserts**
something about it.

| | `load-test.sh` | `baseline.sh` |
|---|---|---|
| Purpose | ad-hoc load generation, human-read output | the committed, dated baseline |
| Asserts status codes? | **no** | **yes — any non-2xx fails the run** |
| Asserts DLQ depth? | n/a (HTTP only) | **yes — a DLQ that grew fails the run** |
| AMQP arm? | no | yes, parameterised |
| Emits an artifact? | no | yes, to `baselines/` |
| In CI? | no | **no, deliberately** |

`load-test.sh` is left functionally unchanged — it is working prior art and the only edit to it
is a header pointer to this file.

---

## What this baseline is FOR

**One thing, primarily: giving 27-04 the number it needs.** RabbitMQ `prefetch` and
`concurrentConsumers` have to be derived from messages/sec/consumer, and findings F-7/F-8
established that figure existed **nowhere in this repo**. Arm B produces it.

Second: turning the "Performance targets" prose in `load-test.sh` into a `budget.yaml` that a
run can actually be compared against — with every entry labelled `inherited-assumption` until a
run validates it, so an assumption is never mistaken for a measurement.

## What it is explicitly NOT

- **Not a peak-throughput measurement.** See "the rate limiter is the ceiling" below.
- **Not a soak, stress, or spike test.** Single short run, fixed volume.
- **Not a multi-tenant or cross-tenant test.** One seed user, one tenant.
- **Not a write-path test.** Arm A is read-only on purpose: writes against the shared dev stack
  would create rows that 27-01 and 27-03 subsequently measure against.
- **Not a media-transcode benchmark.** Arm B's synthetic events reference asset ids that do not
  exist, so the worker acks after a lookup miss. 27-04 layers the real transcode arm on top.
- **Not a production or staging measurement.** Local compose only.

## Why it is not in CI (D-11)

It needs a running compose stack, a real broker and real credentials, and it **publishes into
shared queues**. A CI runner has none of those, so there it could only ever be VOID — and a job
that is permanently VOID is how a check earns a `|| true`. Run it deliberately, locally.

---

## Prerequisite: `hey`

```bash
go install github.com/rakyll/hey@latest
export PATH="$(go env GOPATH)/bin:$PATH"
```

If `hey` is missing, `baseline.sh` **exits 2 (VOID)** and prints that command. It never skips the
arm and never falls back to something simpler — a load baseline that reports success while
executing nothing is worse than none, because it reads as coverage. (Baseline B-5 recorded that
`hey`, `ab`, `k6`, `wrk`, `vegeta`, `siege` and `locust` were all absent from this host, which is
why `load-test.sh` had never produced a number here.)

## Running it

```bash
# defaults: 100 requests, concurrency 5, 200 AMQP messages per queue
bash infra/load-testing/baseline.sh

# arm B against a different queue — no script edit required
QUEUES="payment.events" bash infra/load-testing/baseline.sh
```

Credentials are read from the repo `.env` (`KC_SEED_USER_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`,
`RABBITMQ_USER`, `RABBITMQ_PASSWORD`). No literal is embedded in either script, and **no
credential is written to the artifact**.

### Environment

| var | default | meaning |
|---|---|---|
| `TOTAL_REQUESTS` | `100` | arm A requests per endpoint |
| `CONCURRENT_USERS` | `5` | arm A concurrency |
| `RATE_LIMIT_PAUSE` | `65` | seconds between arm-A endpoints, to refill the shared token bucket |
| `QUEUES` | `media.process webhook.deliveries` | arm B queue list |
| `AMQP_MESSAGES` | `200` | messages published per queue |
| `DRAIN_TIMEOUT` | `60` | seconds to wait for a queue to reach 0 |
| `PAYLOAD` | media event JSON | override for a queue with a different message shape |

---

## The rate limiter is the ceiling — read this before quoting a req/s number

Measured 2026-07-27: this platform rate-limits at **100 req/min per tenant with a burst of 20**
(Bucket4j). A 500-request run returns `[200]=120 [429]=380`, and because both arm-A endpoints
share **one per-tenant bucket**, the second endpoint then returns `[429]=500` outright.

So the defaults sit deliberately **under** the bucket. This baseline answers *"what is p95 under
light concurrency"*, not *"what is peak throughput"*. Measuring peak means setting
`RATE_LIMIT_ENABLED=false` and **rebuilding** core-java — at which point you are no longer
measuring the deployed configuration. If you do that, say so in the artifact.

## Why arm A asserts status codes — the vacuous-baseline trap, caught live

This is not hypothetical. Both of these were produced by this harness on this host:

```
FAIL: arm A /api/v1/products: 500 non-2xx response(s) [401=500] — at 3395.2535 req/s
FAIL: arm A /api/v1/shops:    380 non-2xx response(s) [200=120 429=380] — at 888.7842 req/s
```

**3395 requests per second, every one of them a 401.** Without the status assertion that is a
spectacular throughput result. `GET /api/v1/shops` returns 401 unauthenticated, so an
unasserted harness pointed at it measures how fast the app can *reject* traffic.

Getting to a real 2xx took two fixes that the old script never had:

1. `load-test.sh` requests its token with `client_id=core-api` and **no client secret**.
   `core-api` is a confidential client, so Keycloak answers *"Invalid client or Invalid client
   credentials"* for every user — it could never have obtained a token.
2. Switching to `test-client` (the realm's public direct-access client) yields a token, and
   every request with it **still** 401s: core-java requires `aud: core-api`, and only the
   `core-api` client carries the audience mapper. `test-client` has `aud: null`.

A token that authenticates fine and authorizes nowhere is the subtler half of the trap.

## Why arm B asserts DLQ depth

A queue that reaches zero because every message **died** is indistinguishable from one that
reached zero because every message was **processed** — unless you watch the dead-letter queue.
Without that assertion this harness would score message destruction as throughput. It is the
exact analogue of arm A's status check.

### Shared-stack hygiene

Arm B publishes into the live dev broker that sibling plans measure, so:

- a queue whose **pre-run depth is not zero is refused**, not drained — real work in flight would
  corrupt the measurement and make cleanup unsafe;
- because of that refusal everything left at exit is ours, so an `EXIT`/`INT`/`TERM` trap may
  purge it;
- pre- and post-run depths of every touched queue are recorded in the artifact and asserted equal.
- **DLQs are never purged.** They hold real evidence: `webhook.deliveries.dlq` currently holds
  **nine** real dead vendor webhook events (finding F-2), and 27-03's proof counts exactly nine.
  Every artifact here should show `9 -> 9`. If it does not, something ate the evidence.

---

## Reading the dated artifacts

`baselines/YYYY-MM-DD-<sha>.md`, one per run, committed. Each records the exact command, the
`hey` module version, host CPU/RAM/loadavg, the **image ids** of core-java/postgres/rabbitmq (so
a number can be tied to the runtime that produced it, not just to a commit), both arms' tables,
the raw output, and a PASS/FAIL against `budget.yaml`.

Two traps worth knowing when you read or extend these:

- **`hey` 0.1.5 prints a literal double percent** in its own latency table (`95%% in 0.0056
  secs`, confirmed with `cat -A`). A `95%`-anchored pattern silently extracts nothing and the
  p95 reads `0.0` — while `grep -c 'p95'` still passes. Extract the **value** and assert it is
  non-zero; never count the label.
- A p95 of exactly `0.0` is treated as an **extraction failure**, not a fast response.

## How 27-04 extends this

27-04 adds its media arm by **parameterising**, not forking (D-B): pass its queue list and
payload through `QUEUES` and `PAYLOAD`. If you find yourself copying `baseline.sh`, the
parameterisation has failed — fix it here instead.
