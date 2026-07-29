# Handoff: Phase 27 CLOSED 7/7 · 5 PRs merged · one PR awaiting CI · #342 half-fixed

**Generated:** 2026-07-29 ~21:05 BST. Supersedes the "27-02 and 27-03 both complete — next is 27-06"
handoff.

| | |
|---|---|
| `origin/main` | **`15871d3`** |
| Checked out at handoff | **`fix/342-blind-alerts`** — clean, pushed as `09d70a6`, **0 behind base** |
| Open PR of mine | **#343** — CI running at handoff, merge when green |
| Phase 27 | **CLOSED 7/7.** 27-06 merged as `1545d4f` (PR #338) |
| Milestone v2.3 | build complete |
| Branch protection | **LIVE on `main`** — 13 required checks, PR required, `enforce_admins: false` |
| Live stack | Compose UP; broker RabbitMQ 4.3.4; core-java rebuilt 20:50Z; Prometheus recreated 20:56Z |
| Conda env | **none needed** — Java 21 + Gradle wrapper only |

---

## 0. ⚠ READ FIRST — two traps that will silently invalidate your verification

### 0.1 A single-file bind mount detaches on ANY inode change — including `git switch`

`infra/monitoring/prometheus/alerts.yml` is bind-mounted **as a file** into `jtoye-prometheus`.
Editing it *or switching branches* replaces the inode, and the container keeps serving the **old**
content. `SIGHUP` then logs `Completed loading of configuration file` — **for stale content.**

Measured twice today: host inode `18222075` vs container `18219239`, and again reversed after a
branch switch.

```bash
# ALWAYS check before trusting any alert-rule verification:
echo "host: $(stat -c %i infra/monitoring/prometheus/alerts.yml)"
docker exec jtoye-prometheus stat -c %i /etc/prometheus/alerts.yml
# If they differ, the container is stale. SIGHUP will NOT fix it:
docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml \
  up -d --force-recreate prometheus
```

**Prometheus is NOT in `docker-compose.full-stack.yml`.** It lives in
`infra/monitoring/docker-compose.monitoring.yml`, and that file needs `--env-file .env` or it dies
with `required variable REDIS_PASSWORD is missing a value`.

### 0.2 A freshly recreated Prometheus reports every target `health=unknown`

Not a defect — no scrape has completed yet. It makes `check-alert-liveness.sh` report
`targets=8 down=7`. Wait for scrapes before believing any L-1 result:

```bash
until [ "$(curl -sf 'http://localhost:9091/api/v1/targets?state=any' \
  | jq -r '[.data.activeTargets[]|select(.health=="unknown")]|length')" = "0" ]; do sleep 10; done
```

---

## 1. WHERE TO RESUME

### 1.1 Merge #343 (small, do this first)

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
gh pr checks 343                      # expect 14 pass / 4 skipping / 0 fail
git log --oneline $(gh pr view 343 --json headRefOid --jq .headRefOid)..origin/main | wc -l   # expect 0
gh pr merge 343 --squash --delete-branch
```

The 4 skips are always `Trivy`, `Build and Push Images`, `Deploy to Staging`, `Deploy to
Production` — that set is normal on a PR. **Any other skip is a lost gate, not a pass.**

### 1.2 Then #342 items 5 and 6 (the remaining two liveness defects)

`bash scripts/check-alert-liveness.sh` → **exit 1, 2 defects** on a settled stack. Both are in
**#342**; items 1–4 are fixed by #343.

**Item 5 — `redis_up` read by no rule (register row TS-15).** One-line fix mirroring 27-03's
`DatabaseDown` correction:

```
- alert: RedisDown
  expr: up{job="redis"} == 0 or redis_up == 0
```
Today `RedisDown` watches only `up{job="redis"}`, which is the **exporter's** health — the exact
F-3b defect 27-00 documented. `grep -c redis_up infra/monitoring/prometheus/alerts.yml` → **0**.

**Item 6 — the L-3 delivery probe is CONFIRMED flaky.** Two runs minutes apart on an unchanged
stack: `probe delivered=1` then `probe delivered=0`, with `notifications_failed_total{email}` at
`0 -> 0` both times. Alertmanager reports no failure either way, so it is the probe's own timing.
This matters more than its severity suggests: **a flaky arm makes this gate's exit code unreliable
as the phase-close signal PR #338 made mandatory.**

### 1.3 Then the 9 dependabot PRs — do NOT bulk-merge

All rebased and CI-run today. **#234 and #326 are two halves of ONE invariant** and merging either
alone breaks it:

`edge-go/Dockerfile:5-6` states the Go version must match `go.mod`'s go directive **and** the CI
`setup-go` pin — bump all three in lockstep. #234 bumps only the Dockerfile (`1.25→1.26-alpine`),
#326 bumps only `setup-go` (5→7). **#234 legitimately FAILS `Operational Contracts`**, and that is
the gate catching the invariant your own held-review comment described:

```
VOID (H-5 drift): declared pin 'golang:1.25-alpine' NOT FOUND on any non-comment line
                  of edge-go/Dockerfile (declared site edge-go/Dockerfile:7)
FAIL: H-1 golang:1.26-alpine is pinned in the declared source surface but has NO horizon row
```
A correct fix moves all three **plus** a `golang` row in `infra/dependency-horizons.yaml`.

Major-version bumps needing judgement: `eslint 9→10` (#330), `@types/node 20→26` (#328),
`jest-dom 6→7` (#329), `setup-node 4→7` (#324), `build-push-action 5→7` (#325).
`@types/node` is the risky one — `npx tsc --noEmit` is **already red at 366 pre-existing errors**
(all jest-dom matcher typings in test files `next build` never checks), so a regression there will
NOT be obvious from the count. The honest assertion is *count-unchanged*, not exit 0.

**#322 and #327 are no longer Dependabot-managed** — they carry my commits, so
`@dependabot rebase` would **discard** them. Update those branches by hand.

---

## 2. What landed today (5 PRs, all verified on `main` by content)

| PR | Merge | What |
|---|---|---|
| #336 | `3442ccb` | 27-03 failure visibility |
| #335 | `b51c82f` | 27-02 broker 3.12.14 → 4.3.4 |
| **#338** | **`1545d4f`** | **27-06 `ops-contracts` CI job — closes Phase 27 at 7/7** |
| #340 | `faefe05` | all 45 doc citations fixed + `check-doc-citations.sh` gate |
| #322 | `6063306` | AWS SDK 2.49.5, scrimage 4.6.7 |
| #341 | `15871d3` | #339 — `rabbitmq-queues` mapping, liveness gate runs again |

**`ops-contracts` now runs 4 static gates on every PR**, all required:
`check-terminal-states.sh` · `check-dependency-horizons.sh` · `check-alert-rules.sh` ·
`check-doc-citations.sh`. Runs in ~12s. Exit 2 (VOID) fails the build by design.

Branch protection was created from scratch today (`main` had **none**). `enforce_admins: false`
deliberately — an admin escape hatch, because a gate that breaks *on main* would otherwise leave
nobody able to fix it. Flip with
`gh api -X PUT repos/Bralabee/JToye_OaaS_2026/branches/main/protection/enforce_admins`.

---

## 3. Traps and defects found today (all by RUNNING things, none by reading)

1. **`gsd-sdk query state.begin-phase` corrupts `.planning/STATE.md`** — and the
   `gsd-execute-phase` workflow calls it in its own `validate_phase` step, so following the
   workflow verbatim destroys the file. It wiped ~17 KB of narrative (183727→166515 bytes),
   rewrote the counters onto a different denominator (phases 6→7, plans 48→56, percent 100→71),
   flattened `last_activity` to a bare date, and regressed `stopped_at` to a **stale Phase-26
   message** while the session was starting 27-06. Snapshot + `cmp`-verified restore. The memory
   entry `trap_gsd_state_record_session` was widened to cover the family.
   **`roadmap.update-plan-progress` remains safe** (clean two-line diff, verified again).
2. **`grep -q` under `pipefail` inverts on match** — hit while writing `check-doc-citations.sh`:
   `printf | grep -qE` gave **44 false violations** on the first run, because grep exits on match,
   printf takes SIGPIPE→141, and pipefail reports failure *because it matched*. Use bash `=~`.
3. **A weak-token fallback was a false-PASS path** — its own break arm caught it: a wrong citation
   still exited 0, because `"spring"` matches `spring-boot-starter-web`.
4. **Exit-code precedence matters** — testing "nothing verified → VOID" before "violations → 1"
   made an all-wrong document exit **2 instead of 1**, downgrading the loudest signal.
5. **A root `build.gradle.kts` (22 lines) exists** alongside `core-java/build.gradle.kts` (226).
   STACK.md used the bare path 12×, all resolving to the wrong file. Always qualify.
6. **The single-file bind-mount trap** — §0.1.
7. **`check-doc-versions.sh` only reads 3 docs and only its own claim list** — which is why Spring
   Statemachine drifted 3.2.1→4.0.2 unnoticed and JasperReports stayed documented after removal.
   Its output says so explicitly: `(not claimed in this doc: Spring StateMachine)`.

**Two alerts were structurally incapable of firing** (fixed in #343, proof in §4):
`NoOrdersCreated` watched `/orders|/api/v[0-9]+/orders` while orders are created at
`/public/shops/{slug}/orders` — and because its expression is `increase(...) < 1`, **a total outage
of order creation raised no alert**. `HighResponseTime` needed `_bucket`, which had 0 series
because no histogram was configured.

---

## 4. Evidence for #343 (already posted to the PR)

Measured on a stack carrying both #341 and #343, after scrapes settled:

```
                              before   after
L-2  selectors-matching-0-series   2  ->   0
L-2b wrong-subject                 6  ->   0
L-1  targets=8 down                0  ->   0
     TOTAL defects                 6  ->   2
```

`HighResponseTime` is proven **in the delivered runtime**: `_bucket` 0 → **74 series**, read three
ways — via Prometheus, from the running container's own `/actuator/prometheus`, and from
`BOOT-INF/classes/application.yml` **inside the running jar**.

**`NoOrdersCreated` firing is NOT proven, and #343 does not claim it.** The selector is proven
correct (matches `POST 201 /public/shops/{slug}/orders` in the series index; the old one matches
**0**), but Micrometer creates that timer **lazily on first request** and core-java restarted, so
the counter does not currently exist. **To close this gap:** place one guest order, then

```bash
curl -sfG http://localhost:9091/api/v1/query --data-urlencode \
 'query=increase(http_server_requests_seconds_count{uri=~"/api/v[0-9]+/orders|/public/shops/[^/]+/orders",method="POST",status="201"}[30m]) < 1'
# expect a NON-EMPTY result (the rule fires when orders stop)
```

I attempted this and **stopped after two failed DB-credential guesses** rather than dig —
`psql -U postgres` fails with `role "postgres" does not exist`; the real user is in `.env`
(`DB_USER`, default `jtoye_app`). The endpoint is
`POST /public/shops/{slug}/orders` (`PublicStorefrontController:99`) taking a `GuestOrderRequest`:
`customerName`, `customerEmail`, `customerPhone`, `fulfilmentType` all `@NotBlank`, plus `items`.

---

## 5. Open items

- [ ] **Merge #343.** §1.1. **Owner: next session.**
- [ ] **#342 items 5–6** — `redis_up` unread (TS-15) and the confirmed-flaky L-3 probe. §1.2.
      **Owner: unassigned.**
- [ ] **9 dependabot PRs**, #234+#326 coupled. §1.3. **Owner: maintainer (judgement needed).**
- [ ] **#337 / #115** — load-test baseline part-satisfied; edge↔core contract check and a
      dependency-down fault test outstanding. **Owner: unassigned.**
- [ ] **`check-doc-citations.sh` UNCHECKABLE path** — a claim with no backticked identifier is
      reported, not verified. Deliberate, but it is the seam where citation drift can still hide.
      **Owner: unassigned.**
- [ ] **`rabbitmq-k8s` horizon row** — `owner: UNASSIGNED`, `manual_review.expires: 2026-10-26`.
      The staging/prod broker is still undeclared. **Owner: UNASSIGNED (that is the finding).**
- [ ] **RabbitMQ 4.3 community support ends 2026-11-30** — `ops-contracts` goes amber ~2026-09-01
      and RED 2026-12-01 **with no commit in between**. Documented in the job header; do not read
      it as a broken gate. **Owner: maintainer.**
- [ ] **`.evidence/` holds the 3.12.14 tarball** — the only rollback path from 4.3.4. Untracked;
      do not delete yet. **Owner: maintainer.**

## 6. Residue

- Stack UP. `core-java` rebuilt+recreated 20:50Z (carries the histogram config); `jtoye-prometheus`
  recreated 20:56Z (re-bound mount). All 8 scrape targets healthy.
- **A fictitious `zz-fictitious-probe` scrape job was injected into the LIVE Prometheus config and
  removed again** — verified gone (`8 job_name` entries, targets back to 8). The repo template was
  never touched.
- `main` is clean; only `fix/342-blind-alerts` exists locally besides it.
- One background shell may still be polling #343's CI; it has a deadline and exits on its own.
