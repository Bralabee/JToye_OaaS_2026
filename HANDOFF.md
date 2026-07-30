# Handoff: PR queue empty · #342 + #234 + #330 closed · every gate green on a verified runtime

**Generated:** 2026-07-30 ~06:40 BST. Supersedes the "#342 closed at zero defects" handoff
(`dce03bd`), which was accurate at `79a3a6a` and is now stale on its whole dependabot section.

| | |
|---|---|
| `origin/main` when written | **`ef797adc`** — 15 commits on from `15871d3`. `git log --oneline -20` beats this row. |
| Open PRs | **none, as of 05:35Z.** Dependabot regenerates — see §4. |
| Open issues opened here | **#346** doc-gate coverage · **#347** TS-16 detector |
| Issues closed here | **#342** (6 live detection defects → 0) · **#234** · **#330** |
| Working tree | clean, on `main`, no local branches besides `main` |
| Live stack | Compose UP, 16 jtoye containers healthy, 8/8 scrape targets up |
| Runtime parity | **`check-runtime-freshness.sh` exit 0 — 4/4 FRESH**, rebuilt 05:31–05:33Z |
| Conda env | **none needed** — Java 21 + Gradle wrapper, Go 1.26.5, Node 22 |

---

## 0. ⚠ READ FIRST — the one thing that cost the most time

**A PR's green attests to whatever base it last ran against, and GitHub never re-runs a check
when the base moves.** This is not a nuance; it produced four separate false signals tonight:

- `mergeStateStatus: CLEAN` on #328 while it was a commit behind. Its `Branch Not Behind Base`
  had run at `22:24:34Z`; #348 merged at `22:49:00Z`.
- All ten dependabot PRs reporting a green `Operational Contracts` that had run **3 gates, not
  4** — their branches predated #340, so `check-doc-citations.sh` was not in their workflow at
  all. Verified by diffing the invoked gate set on #326's branch against `main`.
- A `gh pr checks` read showing #243 green while those results predated a force-push by
  minutes; the real run on the new head had only just started.
- My own monitor printing `ALL REBASED` on an **empty** result, because the `case` catch-all
  matched the empty string when transient `gh` calls failed.

The executable form of this is the **skip-if-behind guard** in the merge queue, and it fired
twice for real (#243, #324) — the only reason neither merged on a stale signal.

**If you take one habit from this session:** check `check-runs` for the **specific head SHA**,
not `gh pr checks`, and re-verify `behind == 0` immediately *before* merging, not once at the
start.

### 0.1 The bind-mount trap is executable now — L-0

`check-alert-liveness.sh` md5s `$ALERTS` against the file read from **inside** the Prometheus
container and **VOIDs** on mismatch. The previous handoff warned about this in prose and it
fired anyway within ten minutes. Fix when it VOIDs:

```bash
docker compose --env-file .env -f infra/monitoring/docker-compose.monitoring.yml \
  up -d --force-recreate prometheus
```

**`docker cp` cannot see this drift** — it resolves the bind mount back to its host source path,
so both sides always agree. Measured on a drifted stack, same container, same moment: `docker cp`
→ `1cc20a85` (the host file), `docker exec md5sum` → `d25f3d10` (what Prometheus serves). Use
`docker exec`.

L-0 compares **content**, so a `git switch` between two commits with an identical `alerts.yml`
leaves it green while the mount is detached. That is safe — you can never *grade* a mismatched
runtime — but it means an inode difference alone is not a failure.

### 0.2 `NoOrdersCreated` fires on a quiet dev stack, by design

Proven end-to-end 2026-07-29: order placed → series created → creation stopped → `pending`
21:24:42Z → `firing` 21:55:12Z → **email delivered** 21:55:47Z, subject
`[FIRING:1] NoOrdersCreated (core-java/info)`. It stays firing until another order is placed,
because the counter is flat rather than absent. Do not "fix" it. To clear it, §3.2.

---

## 1. What landed — 15 commits

```
ef797adc  minor-and-patch: AWS SDK 2.49.6 + stripe-java 33.2.0   (#353)
5d79249a  eslint 9.39.4 -> 9.39.5                                 (#351)
39597f6b  actions/setup-node 4 -> 7                               (#324)
155c8ee7  slackapi/slack-github-action 1.27.1 -> 4.0.0            (#243)
9227b345  docker/build-push-action 5.4.0 -> 7.3.0                 (#325)
37d4ea11  docker/metadata-action 5.10.0 -> 6.2.0                  (#323)
b4a4e236  actions/setup-go 5 -> 7                                 (#326)
3514246c  Go 1.25 -> 1.26 across all SIX pin sites  (#352, closed #234)
e00f1788  ignore eslint majors — blocked upstream  (#349, closed #330)
a77829a4  @testing-library/jest-dom 6 -> 7                        (#329)
5e889542  @types/node 20 -> 26                                    (#328)
f081c442  minor-and-patch, 18 frontend updates                    (#348)
dce03bde  previous handoff                                        (#344)
79a3a6a2  #342 items 5 & 6, L-0, stale exemptions, 3 locators     (#345)
88698f3f  the two alerts that could never fire                    (#343)
```

### 1.1 Verification, on the delivered runtime

Every static gate exits 0 on `main`: `check-terminal-states` · `check-dependency-horizons` ·
`check-alert-rules` · `check-doc-citations` · `check-doc-versions` · `docs-freshness` ·
`check-branch-behind-base` · `check-alert-metrics` · `check-alert-liveness`.

`check-runtime-freshness.sh` **exit 0, 4/4 FRESH** — and proven by content, not by tag:

```
edge-go     go version -m /edge                        -> go1.26.5
frontend    node_modules/next/package.json             -> 16.2.12
core-java   unzip -l /app/app.jar | grep stripe        -> stripe-java-33.2.0.jar
```

`check-alert-liveness.sh` exit 0: 8/8 targets, `gauge-read-by-no-rule=0`,
`selectors-matching-0-series=0`, `wrong-subject=0`, probe delivered.

---

## 2. Three gate blind spots found — each by a break arm, not by reading

**1. The CI Go pin was enforced by nothing. FIXED in #352.**
`edge-go/Dockerfile` declared a lockstep invariant in a comment. Only two of its limbs had
horizon rows. With everything else at 1.26 and only the two `go-version:` pins reverted,
`check-dependency-horizons` exited **0** — CI would have compiled and tested on Go 1.25 while
production shipped 1.26, past a green board. A `go-ci-setup` row now covers both pin sites; the
same arm returns exit 2.

> The comment said "bump all three in lockstep". **There are six**: `Dockerfile:10`, `go.mod:3`,
> `ci-cd.yaml:52`, `ci-cd.yaml:667`, and **two** horizons rows. Miscounting is how #234 happened.

**2. `check-doc-versions.sh` is case-sensitive on the package label. NOT FIXED — on #346.**
`STACK.md` writes `axios` lowercase; the claim list has `Axios`. The gate prints
`(not claimed in this doc: ... Axios)` and never checks it. That claim was genuinely stale.
Break arm: plant `axios 9.9.9` and the gate still reports `drift=0`, `PASS`, exit 0.

**3. `check-doc-citations.sh` does not scan the register or the runbooks. NOT FIXED — #346.**
`DEFAULT_DOCS` is `CLAUDE.md`, `AGENTS.md`, three `.planning/codebase/*.md`, `k8s/DEPLOYMENT.md`.
**Adding the register would be vacuous** — it reports `citations=0` there because the YAML
`locator: "path:line"` form is not recognised. Needs a parser change, not a list entry.
Three wrong locators were found and fixed in #345 that nothing would have caught.

---

## 3. Recipes you will want

### 3.1 Reading a C-3 citation failure correctly

```
FAIL: C-3 STACK.md:100 cites core-java/build.gradle.kts:87, but that line says
      nothing the claim names
```

This happened **three times** (#348, #352, #353) and every time the obvious reading was wrong.
C-3 asserts the cited line *supports the claim*. A stale **version** in the claim breaks it
exactly as a moved line would. Check the version first — acting on "the line moved" puts a
wrong citation into a correct doc.

### 3.2 Place a guest order (clears `NoOrdersCreated`, re-arms M-1)

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

The core-java API is on **:9090** (shared with metrics). Not 8080/8081. `minimumOrderPennies`
is 1000 and that product is 400p, hence quantity 3. `NoOrdersCreated` was removed from
`KNOWN_DATALESS`, so a stack where no order has ever been placed fails M-1 until you run this.

### 3.3 The frontend typecheck baseline is **378**, not 366

The previous handoff said 366; measured tonight it is **378** (377 × `TS2339` jest-dom matcher
typings + 1 × `TS2503`), all in test files that `next build` never checks. `npx tsc --noEmit`
is red by default. The honest assertion for a dependency bump is **error set unchanged**, and
a matching *count* is the weak form — compare the sets:

```bash
diff <(sort baseline.txt) <(sort candidate.txt)   # must be empty incl. line:col
```

Verified this way for #328 (`@types/node` 20→26) and #329 (jest-dom 6→7): both **378 → 378,
byte-identical including line:col**. The harness was shown able to fail first — a planted
`TS2322` moved it to 379 and was named in the diff.

### 3.4 Never `@dependabot rebase` a PR carrying a hand-written commit

It discards it. Happened to #327 at `16:36Z` (rebase requested `16:32Z`), losing the doc fix and
leaving it red all day until it was closed and reborn as #348. #348 and #353 both carry
hand-written doc commits for exactly this reason. If such a PR falls behind: rebase **locally**
and force-push, and assert the commit survived before pushing.

---

## 4. Open items

- [ ] **#346** — teach `check-doc-citations.sh` the YAML `locator:` form, *then* add the
      register; verify by breaking a locator (`citations=N > 0` is the minimum bar). Separately,
      `check-doc-versions.sh` case-sensitivity — prefer making an unclaimed label a first-class
      failure over just lowering the match. **Owner: unassigned.**
- [ ] **#347** — a TS-16 container-config-drift detector. A working 30-line prototype is in the
      issue, including the `docker inspect` nil-healthcheck trap that makes correct services read
      as absent. **Owner: unassigned.**
- [ ] **Dependabot will refill the queue.** It regenerates continuously and *regroups*: #350 was
      closed and reborn as #353 mid-session, changing from 1 update to 2. "No open PRs" is true
      **as of 05:35Z**, not a steady state. A frontend or Gradle group bump will usually also
      break `check-doc-versions` — expect to add a doc-sync commit on top, per §3.4.
- [ ] **eslint 10** stays blocked until `npm view eslint-plugin-react peerDependencies.eslint`
      contains `^10`. Today: `^3 || … || ^8 || ^9.7`. Then delete the ignore block in
      `.github/dependabot.yml`. **Owner: maintainer.**
- [ ] **#337 / #115** — load-test baseline part-satisfied; edge↔core contract check and a
      dependency-down fault test outstanding. **Owner: unassigned.**
- [ ] **`rabbitmq-k8s` horizon row** — `owner: UNASSIGNED`, `manual_review.expires: 2026-10-26`.
      The staging/prod broker is still undeclared. **Owner: UNASSIGNED (that is the finding).**
- [ ] **RabbitMQ 4.3 community support ends 2026-11-30** — `ops-contracts` goes amber ~2026-09-01
      and RED 2026-12-01 **with no commit in between**. Not a broken gate.
- [ ] **`.evidence/` holds the 3.12.14 tarball** — the only rollback path from 4.3.4. Untracked.
- [ ] **A register hazard, unfiled.** A `deferred:` block is only re-read on its `expires` date,
      so a deferral whose stated *reason* becomes false survives silently. TS-13's did, for weeks:
      its reason was `grep -c pg_up alerts.yml = 0` while `alerts.yml:85` reads `pg_up`. Nothing
      would have looked before 2026-09-30. Worth a gate that re-evaluates reasons, or shorter
      expiries. **Owner: unassigned.**

## 5. Corrections to the previous handoff

Recorded because each cost time before being caught:

- **"#234 and #326 are two halves of ONE invariant."** They are not. #326 only bumps the
  *action* version (`setup-go@v5→v7`); the invariant is about the Go *language* version. They
  were always independently mergeable.
- **"#327 carries my commits — `@dependabot rebase` would discard them."** Already discarded
  when written. All ten PRs had exactly one dependabot commit; the rebase ran at `16:36:19Z`,
  hours before the handoff. The *lesson* was right, the *state* was stale.
- **"`npx tsc --noEmit` is red at 366."** It is **378**. See §3.3.

## 6. Residue

- Stack UP, 16 jtoye containers, 8/8 targets. `core-java`/`edge-go`/`frontend` rebuilt and
  recreated 05:31–05:33Z; `mcp-server` correctly untouched (nothing it builds from changed).
- `jtoye-redis-exporter` was recreated to drop a phantom `wget` healthcheck its scratch image
  cannot satisfy (failing streak 1367; the compose file removed it 2026-07-07 and the container
  had never been recreated). Monitoring healthcheck-drift sweep: 5 MATCH / 0 DRIFT.
- Two synthetic orders exist on the dev DB from alert evidence, customer
  `liveness-probe@jtoye.local` — e.g. `ORD-00000000-20260729-63EB83BC`.
- Mailhog holds several `SyntheticDeliveryProbe-*` messages and a `NoOrdersCreated` — expected.
- `jtoye-redis` was stopped/started during the TS-15 induced-outage proof; verified healthy.
