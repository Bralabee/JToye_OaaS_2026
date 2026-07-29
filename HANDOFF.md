# Handoff: #342 closed at zero defects · #343 + #345 merged · dependabot is the next decision

**Generated:** 2026-07-29 ~22:00 BST. Supersedes the "Phase 27 CLOSED 7/7, #343 pending" handoff
(same branch, rewritten in place rather than merged stale).

| | |
|---|---|
| `origin/main` when written | **`79a3a6a`** — this file merged on top of it via PR **#344**, so `main` is one commit further on. `git log --oneline -3` beats this row. |
| Last code change | **`79a3a6a`** (#345). Nothing after it is code. |
| Phase 27 | CLOSED 7/7 |
| Milestone v2.3 | build complete |
| Issue #342 | **CLOSED.** Opened at 6 live detection defects; `check-alert-liveness.sh` now exits **0** |
| Open PRs | **#344** (this) + **9 dependabot** — none of the 9 is ready, see §3 |
| New issues filed | **#346** citations-gate coverage · **#347** TS-16 detector |
| Live stack | Compose UP. core-java 20:50Z · prometheus 21:42Z · redis-exporter 21:26Z |
| Conda env | **none needed** — Java 21 + Gradle wrapper only |

---

## 0. ⚠ READ FIRST

### 0.1 The bind-mount trap is now an executable check — do not re-add the manual step

The previous handoff opened with "ALWAYS check the inode before trusting any alert-rule
verification". It fired anyway, on the next session, within ten minutes — and silently produced an
**exit 0** by combining one branch's `alerts.yml` with another branch's running rules.

`check-alert-liveness.sh` now has **L-0**: it md5s `$ALERTS` against the file read from *inside*
the Prometheus container and **VOIDs** on mismatch. You no longer need to remember. The fix when it
VOIDs is in the message:

```bash
docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml \
  up -d --force-recreate prometheus
```

**`docker cp` cannot detect this drift.** It resolves the bind mount back to its host source path,
so both sides always agree. Measured on a drifted stack, same container, same path, same moment:
`docker cp` → `1cc20a85` (the host file), `docker exec md5sum` → `d25f3d10` (what Prometheus
actually serves). Use `docker exec`. The script says so at the call site.

**Known limit, by design:** L-0 compares *content*. A `git switch` between two commits with an
identical `alerts.yml` detaches the mount but leaves L-0 green — currently true on `main` (host
inode `18219239`, container `18219194`, md5 identical). That is safe: you can never *grade* a
mismatched runtime, and the moment content diverges L-0 catches it.

### 0.2 A freshly recreated Prometheus reports every target `health=unknown`

Unchanged from the last handoff. Not a defect — no scrape has completed. Wait before believing L-1:

```bash
until [ "$(curl -sf 'http://localhost:9091/api/v1/targets?state=any' \
  | jq -r '[.data.activeTargets[]|select(.health=="unknown")]|length')" = "0" ]; do sleep 10; done
```

Prometheus lives in `infra/monitoring/docker-compose.monitoring.yml`, **not** the full-stack file,
and that file needs `--env-file .env` or it dies on `REDIS_PASSWORD`.

### 0.3 `NoOrdersCreated` is firing on this stack, and that is correct

It fired at 21:55:12Z and will stay firing until another order is placed, because the counter is
flat rather than absent. Do not "fix" it. `check-alert-liveness.sh` is unaffected — it grades
selectors, targets and transport, not whether alerts are active.

Related: `NoOrdersCreated` was removed from `KNOWN_DATALESS` in `check-alert-metrics.sh`, so **a
stack where no order has ever been created will fail M-1 for it**. Placing an order is the fix;
re-adding the exemption is not. See §1.3 for the recipe.

---

## 1. What landed

### 1.1 Two PRs merged

| PR | Merge | What |
|---|---|---|
| #343 | `88698f3` | `NoOrdersCreated` selector + `HighResponseTime` histogram buckets |
| **#345** | **`79a3a6a`** | **#342 items 5 & 6, + L-0, + stale-exemption cleanup, + 3 wrong locators** |

`check-alert-liveness.sh` on merged `main`, against a runtime whose served `alerts.yml` is
md5-identical to it (`7368261d`):

```
  L-1   targets=8  down=0
  L-1b  exporter-jobs=2  blind=0  gauge-read-by-no-rule=0     <- was 1 (redis_up)
  L-2   rules=19  selectors-matching-0-series=0
  L-2b  wrong-subject=0
  L-3   probe delivered=1  attempts{email} 28 -> 29  failed{email} 0 -> 0
PASS  exit 0
```

All gates green on `main`: `check-terminal-states` · `check-dependency-horizons` ·
`check-alert-rules` · `check-doc-citations` · `check-alert-metrics` · `check-branch-behind-base` ·
`check-alert-liveness` (**twice in a row** — that second run *is* the item-6 fix).

### 1.2 #342 item 6 was misdiagnosed as flaky. It was deterministic.

`route.group_by` is `['alertname','service']` and the probe posted a **constant** alertname, so
every run joined the **same aggregation group** — which notifies at `group_wait` (30s) on creation
and then only every `group_interval` (**5m**). Any second run inside five minutes was never
dispatched. `probe_id` does not help; it is not in `group_by`.

| | run 1 | run 2 (within 5 min) |
|---|---|---|
| committed script | exit 0, `delivered=1` | **exit 1, `delivered=0`** |
| fixed script | exit 0, `delivered=1` | **exit 0, `delivered=1`** |

Fixed by a unique alertname per run. L-3 also now reads `alertmanager_notifications_total`, so
"never attempted" (a **dispatch** fault) can no longer masquerade as "delivery failed" — that
ambiguity is what hid this for two days.

### 1.3 #342 item 5 was latent, so it was induced, not argued

In steady state both gauges read 1 and the old and new expressions are *indistinguishable*. Stop
Redis, leave the exporter up:

|  | `up{job="redis"}` | `redis_up` | OLD expr | NEW expr |
|---|---|---|---|---|
| baseline | 1 | 1 | 0 samples | 0 samples |
| **Redis down, exporter answering** | **1** | **0** | **0 samples** | **1 sample** |
| restored | 1 | 1 | 0 samples | 0 samples |

`RedisDown` is now `up{job="redis"} == 0 or redis_up == 0`. TS-15 and TS-13 both resolved.

**To re-arm `NoOrdersCreated` on a stack (or clear an M-1 red):**

```bash
PID=$(curl -sf http://localhost:9090/public/shops/brixton-village-grill/products \
      | jq -r 'to_entries[0].value[0].id')
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://localhost:9090/public/shops/brixton-village-grill/orders \
  -H 'Content-Type: application/json' -H "Idempotency-Key: k-$(date +%s)" \
  -d "{\"customerName\":\"Probe\",\"customerEmail\":\"probe@jtoye.local\",
       \"customerPhone\":\"07700900123\",\"fulfilmentType\":\"COLLECTION\",
       \"items\":[{\"productId\":\"$PID\",\"quantity\":3}]}"     # expect 201
```

The core-java API is on **:9090** (same port as metrics). It is not on 8080/8081. The shop's
`minimumOrderPennies` is 1000 and that product is 400p, hence quantity 3.

### 1.4 `NoOrdersCreated` firing is now proven end to end

The residual #343 explicitly declined to claim. Order placed → series created → creation stopped →
`pending` 21:24:42Z → `firing` 21:55:12Z → **email delivered** 21:55:47Z, subject
`[FIRING:1] NoOrdersCreated (core-java/info)`. The firing alert carries
`uri="/public/shops/{slug}/orders"` — the series **only the corrected selector matches**. Full
evidence in the #342 comment thread.

---

## 2. Found by running, not reading

1. **`check-alert-metrics.sh` was red on `main` the moment #343 merged.** It enabled the histogram
   buckets and corrected the selector but left both `KNOWN_DATALESS` entries, so the wake-up guard
   fired on each. **That gate is deliberately not in CI** (it needs a live Prometheus), so nothing
   in the pipeline would ever have said so. Cleared in #345.
2. **TS-13's deferral had been false since 27-03 merged.** Its stated reason is
   `grep -c pg_up alerts.yml = 0`; `alerts.yml:85` reads `pg_up`. Nothing would have re-examined it
   before **2026-09-30** — a deferral is only re-read on its expiry date. That is a general hazard
   in the register, not a one-off.
3. **A live TS-16 instance** → issue **#347**. `jtoye-redis-exporter` ran a `wget` healthcheck its
   scratch image cannot satisfy, failing streak **1367**. The compose file removed that healthcheck
   on **2026-07-07**; the container started 2026-07-29 and still had it — `start`ed, never
   recreated, so a three-week-old fix had never once been in effect. Recreated; drift sweep now
   **5 MATCH / 0 DRIFT**. No repo change was needed, which is the point.
4. **Three register/runbook locators pointed at the wrong line** → issue **#346**. Fixed in #345.
   `check-doc-citations.sh` cannot see them, and **adding the register to `DEFAULT_DOCS` would be
   vacuous** — it reports `citations=0` there, because the YAML `locator:` form is not recognised.
   That needs a parser change, not a list entry.

---

## 3. WHERE TO RESUME — the 9 dependabot PRs

**Do not bulk-merge, and do not read their green as equivalent to main's.**

**All 9 are 3 commits behind `main`** (now 5, after #343/#345). Their branches predate #340, so
their `Operational Contracts` job ran **3 gates, not 4** — `check-doc-citations.sh` never ran on
any of them. Verified:

```
gates on #326's branch          gates on main
  check-alert-rules.sh            check-alert-rules.sh
  check-branch-behind-base.sh     check-branch-behind-base.sh
  check-dependency-horizons.sh    check-dependency-horizons.sh
  check-terminal-states.sh        check-doc-citations.sh      <-- absent on the branch
                                  check-terminal-states.sh
```

`Branch Not Behind Base` shows green on all of them only because it *ran before* those merges.
Every one needs a rebase, which re-runs it against the full gate set.

| PR | State | Note |
|---|---|---|
| **#234** + **#326** | #234 **fails `Operational Contracts`** | **Two halves of ONE invariant.** `edge-go/Dockerfile:5-6` requires the Go version to match `go.mod` **and** the CI `setup-go` pin. #234 bumps only the Dockerfile (`1.25→1.26-alpine`), #326 only `setup-go` (5→7). The red is the gate catching it. A correct fix moves all three **plus** adds a `golang` row to `infra/dependency-horizons.yaml`. |
| **#330** | **fails `Lint`** | eslint 9→10. `TypeError: Error while loading rule 'react/display-name': contextOrFilename.getFilename is not a function` — `eslint-plugin-react` is incompatible with eslint 10's API. Needs the plugin (and probably `eslint-config-next`) bumped in lockstep. Not a merge. |
| **#327** | **fails `docs-freshness`** | 17-package group. **Not dependabot-managed** — carries manual commits, so `@dependabot rebase` would **discard** them. Update by hand; `docs-freshness.sh --write` is the arbiter. |
| #328 | green (stale) | `@types/node` 20→26. **The risky one.** `npx tsc --noEmit` is already red at **366 pre-existing errors** (jest-dom matcher typings in test files `next build` never checks), so a regression will not be obvious from the count. The honest assertion is *count-unchanged*, not exit 0. |
| #329 | green (stale) | `@testing-library/jest-dom` 6→7 — major. |
| #324 | green (stale) | `actions/setup-node` 4→7 — major. |
| #325 | green (stale) | `docker/build-push-action` 5.4→7.3 — major. |
| #323 | green (stale) | `docker/metadata-action` 5.10→6.2 — major. |
| #243 | green (stale) | `slackapi/slack-github-action` 1.27→4.0 — major, and the oldest. |

---

## 4. Open items

- [ ] **9 dependabot PRs** — §3. **Owner: maintainer (judgement needed).**
- [ ] **#346** — teach `check-doc-citations.sh` the YAML `locator:` form, *then* add the register.
      Verify by breaking a locator; `citations=N > 0` is the minimum bar. **Owner: unassigned.**
- [ ] **#347** — a TS-16 container-config-drift detector. Working 30-line prototype described in
      the issue, including the `docker inspect` nil-healthcheck trap that makes correct services
      read as absent. **Owner: unassigned.**
- [ ] **PR #344** — this handoff. Merge when green.
- [ ] **#337 / #115** — load-test baseline part-satisfied; edge↔core contract check and a
      dependency-down fault test outstanding. **Owner: unassigned.**
- [ ] **`check-doc-citations.sh` UNCHECKABLE path** — a claim with no backticked identifier is
      reported, not verified. Deliberate, but it is where citation drift can still hide.
- [ ] **`rabbitmq-k8s` horizon row** — `owner: UNASSIGNED`, `manual_review.expires: 2026-10-26`.
      The staging/prod broker is still undeclared. **Owner: UNASSIGNED (that is the finding).**
- [ ] **RabbitMQ 4.3 community support ends 2026-11-30** — `ops-contracts` goes amber ~2026-09-01
      and RED 2026-12-01 **with no commit in between**. Do not read it as a broken gate.
- [ ] **`.evidence/` holds the 3.12.14 tarball** — the only rollback path from 4.3.4. Untracked; do
      not delete.
- [ ] **A general register hazard, from finding 2** — a `deferred` block is only re-read on its
      expiry date, so a deferral whose *reason* stops being true survives silently until then.
      TS-13 did, for weeks. Worth a gate that re-evaluates each stated reason, or at least shorter
      expiries. **Owner: unassigned, not filed.**

## 5. Residue

- Stack UP, all 8 scrape targets healthy. `NoOrdersCreated` firing by design (§0.3).
- One guest order exists on the dev DB: `ORD-00000000-20260729-63EB83BC`, customer
  `liveness-probe@jtoye.local`, placed deliberately as alert evidence.
- `jtoye-redis` was stopped and restarted during the TS-15 proof; `jtoye-redis-exporter` and
  `jtoye-prometheus` were recreated. All verified back to healthy.
- Mailhog holds several `SyntheticDeliveryProbe-*` messages and one `NoOrdersCreated` — all
  expected.
- Local branches: `main` and `docs/handoff-2026-07-29` only. `fix/342-*` branches deleted on merge.
