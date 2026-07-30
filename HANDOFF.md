# Handoff: PR queue empty · #342 + #234 + #330 closed · every gate green on a verified runtime

**Generated:** 2026-07-30 ~06:40 BST; §0/§2/§4 updated ~09:10 BST after #355 and #357; header +§7
updated ~10:35 BST after the housekeeping pass (#359). Supersedes the
"#342 closed at zero defects" handoff
(`dce03bd`), which was accurate at `79a3a6a` and is now stale on its whole dependabot section.

| | |
|---|---|
| `origin/main` when written | **`c43d4b98`** — `ef797adc` + #355/#356/#357/#358. `git log --oneline -20` beats this row. |
| Open PRs | **#359 `chore/housekeeping-20260730`** — housekeeping, awaiting review/merge (see §7). Dependabot regenerates — see §4. |
| Issues opened here | #346 doc-gate coverage — CLOSED by #355 · #347 TS-16 detector — CLOSED by #357 |
| Issues closed here | **#342** (6 live detection defects → 0) · **#234** · **#330** · **#346** · **#347** — **none left open** |
| Working tree | clean, on **`chore/housekeeping-20260730`** (2 commits ahead of `main`, 0 behind) |
| Live stack | Compose UP, 16 jtoye containers healthy, 8/8 scrape targets up |
| Runtime parity | **`check-runtime-freshness.sh` 0 — 4/4 FRESH** (rebuilt 05:31–05:33Z) · **`check-container-config-drift.sh` 0 — 15 compared, 0 drift** (new, #357) |
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

### 0.0b …and your own guard can invert on success

`cmd | grep -q X` under `set -o pipefail` returns **141** when it MATCHES — grep exits at the
first hit, the writer takes SIGPIPE, and pipefail promotes it. So `if ! cmd | grep -q X` fires on
the *success* case. Measured on the #357 lander, whose guard checked that a hand-written commit
survived a rebase:

```
git log --oneline -3 | grep -q 'a TS-16 detector'   -> rc=141   (the string IS there)
grep -q 'a TS-16 detector' <<< "$(git log --oneline -3)"  -> rc=0
```

It declared `FATAL: the doc-sync commit did not survive the rebase` on a rebase that had
succeeded. Two things make it worth repeating rather than filing away:

- **It was LATENT.** The identical guard shipped in the #353, #355 and #356 landers and all three
  passed — because none of those branches was ever behind, so the rebase block never executed.
  #357 was the first time that path ran. A rarely-taken branch is where this hides.
- **It failed SAFE this time** (refused to merge). That was luck, not design. The same inversion
  once made a compose-XOR guard fail OPEN. Use here-strings; do not rely on which way it points.

This is already documented in `check-alert-rules.sh`'s own header, and I wrote it anyway.

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

**THREE gates are deliberately NOT in CI**, because a runner has no containers and no Prometheus,
so each could only ever VOID there — and a permanently-VOID job trains people to add `|| true`.
Run them against a live stack and put their exit codes in the phase-close record:

```
scripts/check-runtime-freshness.sh        image vs the source it builds from
scripts/check-alert-liveness.sh           targets, gauges, selectors, transport
scripts/check-container-config-drift.sh   running container vs its compose file   (new, #357)
```

---

## 2. Three gate blind spots found — each by a break arm, not by reading. ALL THREE NOW FIXED.

**1. The CI Go pin was enforced by nothing. FIXED in #352.**
`edge-go/Dockerfile` declared a lockstep invariant in a comment. Only two of its limbs had
horizon rows. With everything else at 1.26 and only the two `go-version:` pins reverted,
`check-dependency-horizons` exited **0** — CI would have compiled and tested on Go 1.25 while
production shipped 1.26, past a green board. A `go-ci-setup` row now covers both pin sites; the
same arm returns exit 2.

> The comment said "bump all three in lockstep". **There are six**: `Dockerfile:10`, `go.mod:3`,
> `ci-cd.yaml:52`, `ci-cd.yaml:667`, and **two** horizons rows. Miscounting is how #234 happened.

**2. `check-doc-versions.sh` was case-sensitive on the package label. FIXED in #355.**
`STACK.md` writes `axios` lowercase; the claim list had `Axios`, so the gate printed
`(not claimed in this doc: ... Axios)` and never checked it — while enforcing the same fact in
the two docs that capitalise it. Now `grep -oiE`.

The widening was measured on an identical tree, and the prediction was **wrong**:
`CLAUDE.md 28 → 28 · AGENTS.md 28 → 28 · STACK.md 25 → 28`. **Three** newly-visible claims, two
of them stale — `recharts 3.8.1` (actual 3.10.1) sat 45 lines below a *correct*
`Recharts 3.10.1`, so the doc contradicted itself and the gate could see only the right half.
`framer-motion 12.23.26` needed more than `-i`: the doc uses the npm package name where the
table carries the display name, so that row now accepts both forms.

**3. `check-doc-citations.sh` did not scan the register. FIXED in #355 — and the obvious fix
really was vacuous.** Adding `docs/ops/terminal-states.yaml` to `DEFAULT_DOCS` gave
`citations=0` and a PASS, because the markdown extractor wants a backticked `` `path:N` `` and
the register writes `locator: "path:N"`. It needed a YAML dialect.

**The claim is the ROW, not the `locator:` line — and two reasonable-looking heuristics were
measured and rejected first**, because each was red on a *correct* tree:

| approach | result |
|---|---|
| claim = row prose, token match | 16 violations / 17 locators |
| + CamelCase and UPPER_SNAKE tokens | 12 / 17 |
| + "token exists elsewhere in the file" | 11 / 17, matching on `NEVER` `FROM` `EVERY` |

The answer is the one this repo already uses for `eol_slug`: **declared, never inferred**. A row
may declare `subject:`; if it does the locator is checked hard against it, and if it does not
the citation is `UNCHECKABLE` — reported, never a pass. Not a loophole: 17 citations with zero
subjects makes the script's own vacuity guard exit **2**.

**It found four more wrong locators immediately** — all four DLQ rows, every one off by
**exactly 8 lines**, because one insertion near the top of `RabbitMQConfig.java` invalidated all
of them at once:

```
TS-01 order.state-changes.dlq  :27 -> :35   (:27 was ORDER_EVENTS_EXCHANGE)
TS-02 payment.events.dlq       :32 -> :40   (:32 was ..._FANOUT_QUEUE_PREFIX)
TS-03 webhook.deliveries.dlq   :62 -> :70   (:62 was ..._ROUTING_PATTERN)
TS-04 media.process.dlq        :78 -> :86   (:78 was a javadoc comment)
TS-08 webhook_delivery FAILED  :43 -> :47   (:43 was the enum header)
```

16 of 17 rows now verify. TS-16 declares no subject — it points at a comment with no identifier
to name — and is reported `UNCHECKABLE` rather than quietly counted.

**When you add a register row, declare `subject:`.** The gate will not force you, but it will
not pretend to have checked either.

> **A near-miss from that change, worth more than the fix.** Threading `subject` through as a
> 4th TSV field silently reduced the markdown path from **45 verified to 0**, and the gate
> **still exited 0**. Tab is IFS *whitespace*, so `read` collapses an empty field and the claim
> lands in the wrong variable: `printf 'A\tB\t\tD' | IFS=$'\t' read w x y z` gives `y=D`,
> `z=empty`. The separator is now `\x1f`. It was caught only by diffing the counts against
> `main`, not by the exit code — so when you change a gate, **compare its counts, never just
> its status**.

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

- [x] ~~**#346**~~ — CLOSED by #355. See §2 items 2 and 3. Two residuals were deliberately NOT
      taken and are unfiled: (a) `docs/runbooks/` is still outside `DEFAULT_DOCS` and adding it
      starts at 3 reds, all bare filenames (`WebhookDeliveryWorker.java`) rather than wrong
      facts — whether runbook prose must carry full paths is a style call, not a defect.
      Residual (b) in an earlier revision said TS-16 was the one row with no `subject:`; #357
      gave it one, so **all 17 register rows now declare a subject and verify.**
      **Owner: unassigned, low priority.**
- [x] ~~**#347**~~ — CLOSED by #357. `scripts/check-container-config-drift.sh` compares
      healthcheck, restart policy and image (non-built services only) between
      `docker compose config` and `docker inspect`. Clean run: **15 compared, 0 drift, 4
      declared-but-not-running** (one-shot init containers — reported, never silently skipped).
      Four break arms fail correctly, including re-declaring the historical `wget` healthcheck.
      **It is the THIRD live gate deliberately kept out of CI** — a runner has no containers, so
      it could only ever VOID there. Its exit code belongs in the phase-close record beside
      `check-runtime-freshness.sh` and `check-alert-liveness.sh`.
      **TS-16 keeps its `deferred:` block on purpose, and that is not an oversight:** the
      DETECTOR shipped, the ALERT is what remains deferred. `detection.alert` stays null because
      container config drift has no Prometheus series to alert on, and X-2 requires a named alert
      or a dated deferral — inventing an alert name that will never exist would be the
      fabrication the register forbids.
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

---

## 7. Housekeeping pass — PR #359 open, awaiting merge

Ran the session housekeeping routine at ~09:20–10:35 BST on a clean `main` @ `c43d4b98`.
Everything below is on branch `chore/housekeeping-20260730` (2 commits), **not yet merged**.

### The finding worth carrying forward

**A doc can name its own guardian and not have one.** `README.md` advertised
`Total: 921 logical test invocations` and, in the very next block, that those counts were
"guarded by the `docs-freshness` CI gate ... which fails the build if these numbers drift".
The tree stood at **1851**. `docs-freshness.sh` was green on every commit in between —
because it closes one half of the loop only (source tree → `docs/metrics.json`) and **never
opens a doc**. Its own failure message ends *"...then update README/PROJECT.md and commit"*:
a prose instruction, which is precisely the thing that fails. This is the same shape as §2's
two blind spots, found the same way — by asking what the gate actually reads, not what it says.

Every README sub-count was wrong (690/113 Java, 75/8 Go, 130/22 Jest, 23/5 Playwright) and the
`mcp-server` vitest tier (48 blocks) was missing from README entirely.

### What landed on the branch

1. **`scripts/check-doc-metrics.sh`** — 37 declared `(doc, metric-key, pattern)` rules over
   README/CLAUDE.md/AGENTS.md, asserted against `docs/metrics.json`. **M-1**: a rule matching
   *nothing* FAILS, so deleting the sentence cannot dodge the gate. **M-2**: every captured
   number must match. Fails **closed** at exit 2 on missing `jq`/manifest/doc, an absent or
   non-numeric manifest key, a `grep -P` error, or zero claims compared. Wired into
   `docs-freshness.yml` with `if: always()`, next to `check-doc-versions`.
   **Proven to execute in CI, not skipped**: run `30530981230` logs
   `rules: 37 across 3 doc(s) / claims: 37 extracted and compared`.
2. **CLAUDE.md + AGENTS.md said schema V59; V60 shipped in #316.** Corrected with the
   quarantine-durability rationale. The "enforced by" sentence in both now names *both* gates.
3. **CHANGELOG stopped at #314.** 47 PRs had merged since; 43 had no entry. Four dated sections
   added. The 4 left out are `docs(handoff)` artifacts (#344/#354/#356/#358) — continuity
   documents, not project changes.

### Break arms — all five run on the real tree, restores verified by content

| arm | fault | expected | got |
|---|---|---|---|
| 1 | README total `1851 → 1850` | 1 | **1** `doc says 1850, docs/metrics.json says 1851` |
| 2 | MCP claim sentence deleted | 1 | **1** `rule matched NOTHING — the claim was removed or reworded` |
| 3 | CLAUDE.md `V60 → V59` | 1 | **1** `doc says 59, docs/metrics.json says 60` |
| 4 | manifest key `mcp_test_blocks` removed | 2 | **2** VOID `key absent from docs/metrics.json` |
| 5 | `docs/metrics.json` moved away | 2 | **2** VOID `docs/metrics.json is missing` |

Restores checked with `grep -c` on unique tokens, **not** `git diff --stat` — per the
break-arm-revert trap. Pass direction re-confirmed after restore.

### Clean on everything else

- Go: `gofmt -l` empty · `go vet` · `go build` · `go mod tidy` no drift · `go test -race ./...` all pass.
- Frontend: `npm run lint` **0 errors / 28 pre-existing warnings** · `npm run build` (tsc) rc=0.
- All repo gates green, including both runtime-parity gates against the live stack
  (`4/4 FRESH`, `15 compared / 0 drift`) — my diff touches no service build path.
- Breaking-change review vs merge-base: **zero** removed lines in `*.ts|tsx|go|java|sql`
  and zero in workflows/configs.
- Unpushed-branch audit: clean (no local branch held unpushed commits).

### Two things left for a human, deliberately not actioned

1. **264 orphaned `refs/remotes/pr/*` refs.** Left by `gh pr checkout`; no fetch refspec covers
   them (`remote.origin.fetch = +refs/heads/*:refs/remotes/origin/*`), so `git fetch --prune`
   can **never** remove them. 0 map to an open PR (there are none besides #359). They mirror
   GitHub's `refs/pull/*/head`, which GitHub retains, so deleting them loses nothing:
   `git for-each-ref --format='delete %(refname)' refs/remotes/pr/ | git update-ref --stdin`
   Only 35 of 264 are ancestors of `origin/main` — the other 229 are the squash-merge
   ancestry artefact, not evidence of unmerged work.
2. **Artifact version vs release tag.** `build.gradle.kts` and `mcp-server/package.json` both
   say `2.1.0`; the latest release tag is `v2.2` and the active milestone is v2.3. README now
   states the tag and the milestone honestly rather than repeating `2.1.0`, but **bumping the
   Gradle/npm artifact version is a release decision and was not made here.**

### Toolchain drift (report only — never applied inside housekeeping)

`~/dotfiles/toolchain/doctor.sh --check` → exit 1: **6 DRIFT** (conda 26.1.1→26.5.3,
node v22.23.1→v22.23.2, npm 12.0.1→12.0.2, gemini-cli 0.52.0→0.53.0, copilot 1.0.75→1.0.76,
ms-fabric-cli 1.2.0→1.6.1), **1 UNKNOWN** (`antigravity`, policy `manual` — the probe has no
channel to query, which is its recorded state, not a gap). `~/dotfiles/sync-claude.sh --check`
clean. Converge with `update.sh --tier N` in its own session, then `doctor.sh --write-lock`.
