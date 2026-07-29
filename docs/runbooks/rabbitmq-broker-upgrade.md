# Runbook — RabbitMQ broker upgrade and rollback

Owner: unassigned (see ADR-0002). Written by plan 27-02, 2026-07-29, when the dev/compose broker
moved **3.12.14 → 4.3.4**.

> **This file is deliberately allowlisted from the AC-2 / AC-12 "no 3.x version strings" rules.**
> It must name `3.12` and `3.13` to document the upgrade chain at all. A rule that forbids the
> string it is required to carry would either fire on its own definition or force this runbook to be
> wrong; naming the allowlist and its reason here is the honest form.

---

## 1. Read the version off the RUNNING broker — never off the compose file

```bash
docker exec jtoye-rabbitmq rabbitmqctl version
# or, over the management API:
curl -sf -u "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
  http://localhost:15672/api/overview | jq -r .rabbitmq_version
```

`docker-compose.full-stack.yml` states an *intent*. Only the broker states a *fact*. Note also that
`scripts/check-runtime-freshness.sh` **structurally cannot cover RabbitMQ**: it discovers only
services with a `build:` stanza, and RabbitMQ is a pulled image. Broker freshness must be asserted
explicitly — no automated gate does it for you.

## 2. The upgrade chain — there is no 3.12 → 4.x hop

The supported path is **3.12 → 3.13 → 4.2 → 4.3**. It cannot be shortened:

- **Enable all stable feature flags BEFORE each hop, or the upgrade fails.** From the 4.0.1 release
  notes: a node will refuse to start if required feature flags from the previous series were not
  enabled first. Do this while still on the *old* version.
  ```bash
  docker exec jtoye-rabbitmq rabbitmqctl enable_feature_flag all
  docker exec jtoye-rabbitmq rabbitmqctl list_feature_flags
  ```
- **Precondition: no classic queue mirroring policy.** Classic mirrored queues are *removed* in 4.x;
  a surviving `ha-*` policy is silently dropped. Check by policy, because
  `check_if_cluster_has_classic_queue_mirroring_policy` **does not exist on 3.12** (it exits 64,
  a usage error, in every arm — it cannot discriminate):
  ```bash
  curl -sf -u "$RU:$RP" http://localhost:15672/api/policies \
    | jq '[.[]|select(.definition|keys[]|startswith("ha-"))]|length'   # must be 0
  ```
- **Erlang/OTP matrix:** 4.3 requires **OTP 27**. The `-management-alpine` images bundle a
  compatible OTP, so this only bites on host installs.

## 3. What the dev/compose stack actually did — FRESH INSTALL, and why you may not be able to

The compose broker took the vendor-sanctioned **fresh-install** path: snapshot the volume, destroy
it, recreate on 4.3.4, let the application re-declare the topology. That is safe *here* because all
12 durable queues, 4 DLX exchanges and every binding are re-declared at boot by Spring's
`RabbitAdmin` from `RabbitMQConfig.java` — the topology is **code**, not data.

**The messages are not code.** A fresh install destroys every queued and dead-lettered message. For
a broker holding real traffic, the fresh-install path is not available; use the 3.13 → 4.2 → 4.3
chain in §2, or drain first.

## 4. Rollback — from 4.3.4 there is only the tarball

**There is no downgrade path from 4.3.4 to 3.12.14.** 4.3 is Khepri-only and has no Mnesia reader at
all. The volume snapshot is the only way back.

### 4.1 The hostname requirement — read this before restoring anything

RabbitMQ keys its data directory by `rabbit@$(hostname)`. **A restored data directory whose node
name does not match the container's hostname is silently ignored, and the broker boots empty,
healthy and on the right version.** Every rollback assertion must therefore read a **message count**,
never a health check.

**The hostname a tarball restores under is a property of the TARBALL, not of the compose file.** It
is recorded in a sidecar written at snapshot time and read back at restore time — never typed from
memory, never derived from the tar listing (a pre-pin volume holds *ten* node directories, nine of
them orphans, so any `head -1` derivation picks an orphan nine times out of ten).

Renaming the directory in place does **not** work: Mnesia's `schema.DAT` carries the node name
internally, so a `mv` produces a directory the node still refuses to adopt.

Since 2026-07-29 the compose file pins `hostname: jtoye-rabbitmq`, so **snapshots taken from that
point need no override**. Snapshots predating it do.

### 4.2 Taking a snapshot

```bash
SNAP_NODE_HOST=$(docker inspect --format '{{.Config.Hostname}}' jtoye-rabbitmq)
SNAP_DEPTH=$(docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages --quiet \
              | awk '$1=="webhook.deliveries.dlq"{print $2}')
STAMP=$(date -u +%Y%m%dT%H%M%SZ); SNAP="rabbitmq_data-$(docker exec jtoye-rabbitmq rabbitmqctl version)-${STAMP}.tar.gz"

docker compose -f docker-compose.full-stack.yml stop rabbitmq     # a live Mnesia dir is not tar-consistent
docker run --rm -v jtoye_oaas_2026_rabbitmq_data:/from:ro -v "$PWD/.evidence":/to alpine:3.20 \
  tar -C /from -czf "/to/${SNAP}" .
printf '%s\n' "$SNAP_NODE_HOST" > ".evidence/${SNAP}.node-host"   # THE SIDECAR
printf '%s\n' "$SNAP_DEPTH"     > ".evidence/${SNAP}.depth"
docker compose -f docker-compose.full-stack.yml start rabbitmq    # `start`, NOT --force-recreate
```

`start` is correct here and `--force-recreate` is the bug: no image has changed, and a recreate mints
a new container id, orphans the live node directory and boots empty.

**Verify the snapshot by content, not by size.** A complete snapshot of this volume is only ~68 KB
compressed while the volume is ~2.3 MB on disk — Mnesia compresses roughly 17:1, so a
compressed-size floor derived from the uncompressed volume rejects a perfectly good artifact:

```bash
gzip -l ".evidence/${SNAP}" | awk 'NR==2{print $2}'              # uncompressed size; expect > 1000000
tar -tzf ".evidence/${SNAP}" > ".evidence/${SNAP}.listing"
grep -c "mnesia/rabbit@$(cat ".evidence/${SNAP}.node-host")/" ".evidence/${SNAP}.listing"   # must be >= 1
```

> **Parsing node directory names:** use `[^/]*`, never `[^/-]*`. The pinned node name
> `rabbit@jtoye-rabbitmq` **contains a hyphen**, so a `[^/-]*` character class matches nothing for it
> and the live node vanishes from its own count.

### 4.3 Restoring

Restore boots under the **snapshot's** node name, which is why the override file exists rather than
`git checkout --` on the compose file (reverting the compose file would also revert the `hostname:`
pin, leaving Docker to invent a container-id hostname — resurrecting an empty broker, which is the
exact failure this procedure exists to avoid).

```bash
SNAP=<the tarball>; SNAP_NODE_HOST=$(cat ".evidence/${SNAP}.node-host"); SNAP_DEPTH=$(cat ".evidence/${SNAP}.depth")

cat > /tmp/docker-compose.rollback.yml <<YAML
services:
  rabbitmq:
    image: rabbitmq:3.12-management-alpine
    hostname: ${SNAP_NODE_HOST}
YAML

docker volume create jtoye_oaas_2026_rabbitmq_data 2>/dev/null || true
docker run --rm -v jtoye_oaas_2026_rabbitmq_data:/to -v "$PWD/.evidence":/from:ro alpine:3.20 \
  tar -C /to -xzf "/from/${SNAP}"
docker compose -f docker-compose.full-stack.yml -f /tmp/docker-compose.rollback.yml \
  up -d --force-recreate rabbitmq
```

**Wrap any destroy→recreate window in a trap** so an abandoned or crashed session restores the
broker rather than leaving the shared stack with none:

```bash
trap resurrect_312 EXIT INT TERM     # ... volume rm + recreate run inside ...
trap - EXIT INT TERM                 # cleared only once the new broker reports healthy
```

**Rehearse the trap before relying on it.** A trap whose body has never executed is not a safety net.

### 4.4 Verifying a restore — by count, and not too early

```bash
# WAIT for the queue list to be POPULATED, not merely for ping to succeed.
for i in $(seq 1 40); do
  rows=$(docker exec jtoye-rabbitmq rabbitmqctl list_queues name --quiet 2>/dev/null | grep -c '^[a-z]')
  [ "${rows:-0}" -ge 13 ] && break; sleep 2
done
docker exec jtoye-rabbitmq rabbitmqctl list_queues name messages --quiet \
  | awk '$1=="webhook.deliveries.dlq"{print $2}'    # must equal the .depth sidecar, NOT 0
```

## 5. Two 4.x behaviours that make a CORRECT state look like a failure

Both were hit during the 27-02 upgrade; both cost a false abort.

1. **`node()` is a QUOTED Erlang atom on 4.x when the hostname contains a hyphen.** 3.12 with a
   container-id hostname returned `rabbit@53955960a605`; 4.3 with the pin returns
   `'rabbit@jtoye-rabbitmq'` **with single quotes**. A literal `[ "$n" = "rabbit@jtoye-rabbitmq" ]`
   therefore fails on a correct pin. Normalise: `n=$(printf '%s' "$n_raw" | tr -d "'")`.
2. **Health and `rabbitmq-diagnostics ping` go green before queue recovery finishes.** A depth
   assertion gated on `ping` reads *empty* — indistinguishable from the booted-empty failure in §4.1,
   and it invites destructive "correction" of a rollback that actually worked. Always poll until the
   queue list is populated.

Also note `rabbitmqctl list_queues --quiet` suppresses the banner but **not** the column header, so a
naive `wc -l` over its output is off by one.

## 6. Application-visible 4.x changes

- **Transient non-exclusive queues are refused by default.** Declaring `durable=false,
  exclusive=false` returns `INTERNAL_ERROR - Feature 'transient_nonexcl_queues' is deprecated.` All
  production queues here use `QueueBuilder.durable(...)`, and the per-JVM SSE `AnonymousQueue` is
  legal because it is **exclusive** — it is the *non-exclusive* combination that was removed.
- **Metrics renamed.** 37 series disappeared and 89 appeared between 3.12.14 and 4.3.4. All 11
  `erlang_mnesia_*` are gone (Mnesia removed); many `erlang_vm_*` were renamed to Prometheus
  conventions (`erlang_vm_atom_count` → `erlang_vm_atoms`, `..._bytes_total` → `..._bytes`,
  `erlang_vm_process_count` → `erlang_vm_processes`); and `rabbitmq_raft_*` went from 6 label-free
  series all reading `0` to 60 live ones carrying
  `{module="rabbit_khepri",ra_system="coordination"}`. **Any alert rule naming a removed series is
  now permanently silent.** Re-validate rules against a live scrape after any upgrade.
- **STOMP `/topic` destinations must be a single dot-separated segment** — unchanged by 4.3, and
  still load-bearing (issues #266 / #269). A slashed multi-segment destination is rejected with
  `'/kitchen/a/b' is not a valid topic destination`.
- `rabbitmqctl list_stomp_connections` still **rejects** the `user` info key on 4.3; the identity
  column is `auth_login`.

## 7. Staging / production — the operator action this repo cannot perform

**The staging/production broker is not deployed from this repository and its version is unknown and
unknowable from this checkout.** `k8s/base/configmap.yaml` points at
`rabbitmq.jtoye-infrastructure.svc.cluster.local`; there is no RabbitMQ manifest anywhere under
`k8s/`, only a host, a port and a `rabbitmq-credentials` secret reference. No gate reads its version
and `check-runtime-freshness.sh` cannot see it.

Someone with cluster access must:

1. Read the deployed version (`rabbitmqctl version` in the broker pod, or the management API).
2. If it is 3.12 or older, plan the §2 chain — **not** the fresh-install path in §3, which destroys
   messages.
3. Record the result against the `rabbitmq-k8s` row in `infra/dependency-horizons.yaml`, which is
   currently `pin: unknown`, `owner: UNASSIGNED`, with a dated `manual_review` that **expires
   2026-10-26** and turns the horizon gate red by itself if nobody acts.
4. Settle the in-cluster cluster-operator question in ADR-0002, still *Proposed* and unsigned since
   2026-07-12.
