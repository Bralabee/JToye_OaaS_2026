---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 02
subsystem: deploy-manifests
tags: [k8s, kustomize, staging, keycloak, redis-tls, postgres-tls, rfc8058, hpa, pdb]
requires:
  - k8s/base kustomize overlays (Phase 26)
  - infra/keycloak/realm-export.template.json
  - NotificationProperties / EmailChannel one-click machinery (Phase 22, issue #516)
provides:
  - staging overlay capable of a successful OIDC login (real client id)
  - staging scale intent that reaches HPA + PDB (6 app pods, not 11)
  - REDIS_SSL / DB_SSL_MODE config-injected transport switches
  - notification.unsubscribe.one-click-base-url wired end to end (#592)
affects:
  - plan 29-01 (must confirm the managed server's real max_connections)
  - plan 29-04 (teaches check-connection-math.sh to read db.max-connections)
  - plan 29-08 (realm parameterisation: redirectUris/webOrigins, production client id)
tech-stack:
  added: []
  patterns:
    - one file / six documents matched by GVK + name (kustomize scale patch)
    - render-time declaration keys consumed by gates, not by containers (db.port precedent)
    - fail-safe empty default + explicit per-overlay origin (RFC 8058 one-click)
key-files:
  created:
    - k8s/staging/scale-patch.yaml
  modified:
    - k8s/staging/configmap-patch.yaml
    - k8s/staging/kustomization.yaml
    - k8s/base/configmap.yaml
    - k8s/base/core-java-deployment.yaml
    - k8s/local/configmap-patch.yaml
    - core-java/src/main/resources/application.yml
    - core-java/src/test/java/uk/jtoye/core/notification/dispatch/UnsubscribeLinkRoutingTest.java
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - docs/metrics.json
    - README.md
    - CLAUDE.md
    - AGENTS.md
decisions:
  - "Staging authenticates as core-api, the realm's only confidential browser-flow client; adding a `frontend` client to the realm is explicitly NOT the fix (29-08 owns realm parameterisation)"
  - "HPA minReplicas 1 / PDB minAvailable 1 for staging; maxReplicas deliberately UNTOUCHED because check-connection-math.sh consumes it as a budget input"
  - "DB_SSL_MODE defaults to `prefer` (byte-identical to the assumed pgjdbc default) so compose is unchanged; staging asks for `require`"
  - "REDIS_SSL and REDIS_PORT always move together — Azure Cache Basic disables plaintext 6379 and serves TLS on 6380"
  - "k8s/local gets the one-click origin too, outside this plan's file list, because inheriting base would POST unsubscribes at the PRODUCTION API"
  - "db.max-connections is a render-time declaration inert today; 29-04 teaches the gate to read it"
metrics:
  duration: ~35 min
  tasks: 3
  commits: 4
  completed: 2026-08-10
---

# Phase 29 Plan 02: Staging Overlay Defects + One-Click Unsubscribe Summary

Fixed the three staging-overlay defects that every existing gate was structurally
blind to — a non-existent OIDC client, a scale intent that never reached the HPAs,
and a missing Redis TLS capability — and wired the RFC 8058 one-click unsubscribe
origin that had zero references under `k8s/`.

## What Shipped

**Blocker A — the login client.** `k8s/base/configmap.yaml` carries
`keycloak.client-id: "frontend"`, and no realm this repository ships has such a
client. Re-measured now rather than quoted from research:

```
rg -uu --count-matches --include-zero '"clientId" : "frontend"' \
   infra/keycloak/realm-export.template.json   ->  0
rg -uu --count-matches --include-zero '"clientId" : "core-api"' \
   infra/keycloak/realm-export.template.json   ->  1     <- POSITIVE CONTROL
```

The control matters: an already-0 grep is a statement about the pattern until
something proves the pattern can match. The full client list is `account`,
`account-console`, `admin-cli`, `broker`, `core-api`, `edge-api`,
`integration-catalog-ro`, `integration-orders-rw`, `realm-management`,
`security-admin-console`.

The displaced "deliberately NOT overridden" comment block was **replaced, not
deleted**. It was sound about continuity and wrong about correctness: "the same
client it always has" was the same client that had never authenticated anyone,
because staging had never completed an ingress login. 26-08 fixed this for
`k8s/local` only.

**Pitfall 6 — the scale patch.** The kustomize `replicas:` transformer reaches
only Deployments. Measured on the pre-change staging render:

| Object | Before | After |
|---|---|---|
| core-java / edge-go / frontend HPA `minReplicas` | 3 / 5 / 3 | 1 / 1 / 1 |
| core-java / edge-go / frontend PDB `minAvailable` | 2 / 3 / 2 | 1 / 1 / 1 |
| HPA `maxReplicas` | 10 / 20 / 10 | **unchanged** |

So staging scheduled **11 app pods, not the 6** `replicas: 2` implies. The PDBs
were the quieter half: `minAvailable: 3` over an edge-go Deployment of 2 replicas
is unsatisfiable, making every voluntary eviction permanently impossible.
`maxReplicas` is byte-identical pre/post (`diff` rc=0) because
`check-connection-math.sh` consumes it as an input to the Postgres budget.

**Pitfall 7 — Redis/Postgres transport.** `spring.data.redis` gained
`ssl.enabled: ${REDIS_SSL:false}`; the JDBC URL gained
`?sslMode=${DB_SSL_MODE:prefer}`. **`REDIS_PORT` was never injected before this
plan** even though `application.yml` has always read it and app-config has always
carried the key — so patching `redis.port` in an overlay reached nothing. Injecting
it is what makes staging's 6380 real.

**#592 — one-click unsubscribe.** The key had zero references under `k8s/`, so
`List-Unsubscribe` degraded to RFC 2369 with no `List-Unsubscribe-Post` in every
deployed environment. Now injected, derived per overlay from that overlay's own
`api.url`.

| Overlay | one-click origin | redis.ssl / port | db.ssl-mode | client-id |
|---|---|---|---|---|
| base | `https://api.olajay.co.uk` | false / 6379 | prefer | frontend |
| staging | `https://api-staging.olajay.co.uk` | **true / 6380** | **require** | **core-api** |
| production | `https://api.olajay.co.uk` | false / 6379 | prefer | frontend |
| local | `http://api.jtoye.local` | false / 6379 | prefer | core-api |

## The RED That Was Required (#592)

`./gradlew :core-java:test --tests '*UnsubscribeLinkRoutingTest*'` at **`1424ff51`**
(pre-wiring) — **rc=1, 4 of 8 FAILED**:

```
#592 — every DEPLOYED overlay advertises a true RFC 8058 one-click ... FAILED (:207)
#592 — each overlay's one-click origin is its OWN api origin         FAILED (:240)
Pitfall 7 — REDIS_SSL and DB_SSL_MODE default to compose values      FAILED (:294)
Pitfall 7 control — the same keys flip to TLS when supplied          FAILED (:315)

"app-config key 'notification.unsubscribe.one-click-base-url' is not supplied
 by the committed manifests" / Expecting actual not to be null
expected: "false"   (the resolved property was null)
```

After wiring: **rc=0, 8 tests / 0 failures**. Full core-java unit suite rc=0 across
149 classes.

**Why the three pre-existing tests could not see #592.** `apiOrigin()` falls back to
`api.url` when the one-click key is absent. That fallback is the test suite's own
convenience — the application has none. Every prior assertion asked "would the origin
this test computed route correctly?", never "is an origin supplied at all?". The new
assertions read the key strictly and run the **real** `EmailChannel` over the **real**
dispatch output, so they read the headers production stamps rather than a second copy
of the stamping rule. The RFC 2369 degradation is kept as a permanent control.

## Falsification — Every Arm Run, Clean State Asserted Last

| Arm | Result |
|---|---|
| Unwire `- path: scale-patch.yaml`, re-render | `minReplicas` 3/5/3 and `minAvailable` 2/3/2 **reappeared** |
| Restore, verified **by content** | `git hash-object` = `216e6ed2…`, matching pre-arm |
| Re-render after restore (clean last) | all six back to 1 |
| Scratch injected-but-unread env | `check-env-contract` **rc=1**, naming `JTOYE_SCRATCH_UNREAD_BREAK_ARM` |
| Restore, verified by content | `git hash-object` = `6f890c18…`, matching pre-arm |
| Re-run gate (clean last) | rc=0 |
| `kind: Secret` in added golden lines | 0 — and the check was shown **capable** of firing (same count over the same lines plus one synthetic `kind: Secret` → 1) |
| Instrument control for the HPA/PDB walk | the same awk found all 6 objects on the **pre**-change render with the **old** values, proving it can see and distinguish |

## Assumption A6 — Measured, With Its Unmeasured Half Named

Measured on the live compose Postgres (`jtoye-postgres`, up 16h, healthy):

```
SHOW ssl;                                        -> off
SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();  -> false
psql "...?sslmode=require"  -> rc=2
  "server does not support SSL, but SSL was required"
```

So `prefer` negotiates plaintext and **compose is byte-identical**, while the
`require` arm being *refused* proves the parameter is not a no-op — and that
`require` as a base default would have broken every compose developer.

**The half that stays UNMEASURED, stated rather than glossed:** whether pgjdbc 42.7
really defaults to `prefer` against a `require_secure_transport=on` server. No such
server exists yet (29-01 provisions it). Making the mode explicit is what *retires*
the risk — the assumption is no longer load-bearing, which was the point.

## Gate Results — Every rc Recorded

| Gate | rc |
|---|---|
| `check-no-plaintext-secrets.sh` | 0 (k8s/staging: 23 resources, 0 plaintext) |
| `check-render-invariants.sh` | 0 (INV-1..7 × 4 targets; LOC-1..6 on local) |
| `render-golden.sh` byte-compare | 0 (both goldens match, 1602 lines each) |
| `check-env-contract.sh` | 0 (core-java 66 injected / 150 read, both directions) |
| `check-connection-math.sh` | 0 (`max_connections=200`, ≥20% headroom) |
| `docs-freshness.sh` | 0 |
| `check-doc-metrics.sh` | 0 (37 claims / 3 docs) |
| `:core-java:test` (full unit) | 0 (149 classes) |

`render-golden.sh` re-run **after** all commits: rc=0, tree clean.

## Goldens — Anchored Diff, Every Removed Line Attributed

Snapshot `29-02-pre` taken **before Task 1**; `--diff-since` `resolve_exit=0`
(a 2 would be VOID, not a pass), diff non-empty (`test -s` rc=0).

All 8 removed (`<`) lines, **all in staging**:

| Removed | Became | Why |
|---|---|---|
| `minReplicas: 3 / 5 / 3` | 1 | Pitfall 6 |
| `minAvailable: 2 / 3 / 2` | 1 | Pitfall 6 |
| `keycloak.client-id: frontend` | `core-api` | Blocker A |
| `redis.port: "6379"` | `"6380"` | Pitfall 7 |

**Production is purely additive: 0 removed, 24 added.** Production behaviour is
unchanged; it gains config keys and env sources only.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 — Missing critical functionality] `k8s/local` one-click origin**
- **Found during:** Task 2
- **Issue:** `k8s/local/configmap-patch.yaml` is not in this plan's file list, but local would have inherited base's **production** API origin. Unlike the sibling keys — whose base values only produce a dead link — this one is the target a mail client POSTs `List-Unsubscribe=One-Click` to, so unsubscribing from a Mailhog rehearsal email would have mutated **live production consent**.
- **Fix:** added `notification.unsubscribe.one-click-base-url: "http://api.jtoye.local"` with the reasoning recorded in-file. The new per-overlay equality assertion catches this class permanently.
- **Commit:** `8cb4325d`

**2. [Rule 3 — Blocking issue] `REDIS_PORT` was never injected**
- **Found during:** Task 2
- **Issue:** the plan said to add `REDIS_PORT` "only if the deployment does not already inject it". It did not — so staging's `redis.port: 6380` would have reached nothing and TLS-on-6380 could not work.
- **Fix:** added the `configMapKeyRef` entry.
- **Commit:** `8cb4325d`

**3. [Rule 3 — Blocking issue] docs-metrics gates reddened by the new tests**
- **Found during:** Task 2 (baseline verified green at 2807 *before* any change)
- **Issue:** +5 `@Test` methods moved `total_logical_invocations` 2807 → 2812 and `java_test_methods` 1633 → 1638, failing both doc gates.
- **Fix:** regenerated `docs/metrics.json` **by script** (`--write`, never arithmetic), then updated the prose in README.md / CLAUDE.md / AGENTS.md since the script does not touch prose. Both gates rc=0.
- **Commit:** `8cb4325d`

### Criterion Corrected, Not Silently Substituted

Task 3's criterion predicted **exactly 7** removed golden lines (six scale values +
the client id). The actual set is **8**. The extra is `redis.port 6379 -> 6380`,
which is deliberate Pitfall 7 work named in the plan's own `<interfaces>` block —
the plan listed `redis.port` under "add to base if not already present" and did not
carry that through to the staging patch it also requires. The stronger form is
recorded above: every `<` line enumerated and attributed, which is what the criterion
existed for.

### Scope Compromise, Recorded

The two Pitfall 7 assertions live in `UnsubscribeLinkRoutingTest` because the plan's
`<files>` names only that test file. They are not about unsubscribe links, and the
class comment says so plainly — they are there because the class is already the
committed-*configuration* oracle, and the property being proven (a default that keeps
compose byte-identical while an overlay can change it without a code edit) is
identical in shape to #592's. A future plan may prefer a dedicated
`DeployedConfigContractTest`.

## Known Gaps (recorded, not dropped)

- **Production still renders `keycloak.client-id: frontend`.** Out of scope here
  (this plan is staging), and harmless today because production has no realm and no
  resolvable hostname (D-08). It is the same latent defect and must be closed by the
  realm work (29-08) or the production cutover (Phase 32) — **not** inherited as a pass.
- **`db.max-connections: "429"` for staging is UNCONFIRMED.** Sourced from
  `29-RESEARCH.md:187` citing learn.microsoft.com (B2s default 429, 414 usable), not
  from a provisioned server — 29-01's decision record did not exist when this plan ran
  (parallel wave). Re-measure with `SHOW max_connections;` before 29-04 lands.
- **`db.max-connections` is inert today.** `check-connection-math.sh` still extracts
  `max_connections` from `docker-compose.full-stack.yml` — i.e. the gate guarding the
  *cluster* budget reads its ceiling out of the *local dev stack*. 29-04 owns the fix.

## Known Stubs

None. Every key added is consumed by an injected env or is an explicitly documented
render-time declaration (`db.max-connections`, following the `db.port` precedent).

## Threat Flags

None. The plan's `<threat_model>` dispositions were all `mitigate` and all applied:
T-29-02-01 (staging uses the confidential `core-api` client), T-29-02-02 (Redis TLS
switch; enabling the cache's plaintext port rejected in writing), T-29-02-03
(signing-secret path untouched), T-29-02-04 (HPA/PDB minima with `maxReplicas`
unchanged), T-29-02-05 (`kind: Secret` = 0 in the golden diff, check shown capable of
firing), T-29-02-SC (no package installs in this plan).

## Commits

| Hash | Type | What |
|---|---|---|
| `1424ff51` | fix | Blocker A (client id) + Pitfall 6 (scale patch), break arm fired |
| `58195feb` | test | RED: 4 of 8 failing oracle for #592 + Pitfall 7 |
| `8cb4325d` | feat | GREEN: Redis TLS, Postgres sslMode, one-click origin wired |
| `a5a32883` | chore | goldens regenerated against the pre-edit snapshot |

## Self-Check: PASSED

Files verified present: `k8s/staging/scale-patch.yaml` (100 lines, 4 ×
`HorizontalPodAutoscaler`), `k8s/staging/configmap-patch.yaml` (2 ×
`keycloak.client-id`), `k8s/goldens/staging.yaml` (1 × `REDIS_SSL`),
`k8s/goldens/production.yaml`, `k8s/local/configmap-patch.yaml`,
`core-java/src/main/resources/application.yml`, `UnsubscribeLinkRoutingTest.java`.

Commits verified in history: `1424ff51`, `58195feb`, `8cb4325d`, `a5a32883`.

`must_haves` artifacts all satisfied (`scale-patch.yaml` ≥ 40 lines: 100). Working
tree clean; `render-golden.sh` rc=0 as the final assertion.

**Not updated by design (worktree mode):** `STATE.md` and `ROADMAP.md` are owned by
the orchestrator.

**Merge note for the orchestrator:** `docs/metrics.json` and the three prose docs are
the documented cross-branch conflict hotspot. If a sibling plan in this wave also adds
tests, resolve by re-running `scripts/docs-freshness.sh --write` after the merge and
then reconciling prose — never by arithmetic.
