# Handoff: every open item is closed except three that need you, not me

**Generated:** 2026-07-30 ~18:20 BST. Supersedes the "gates are portable now · 15 PRs" handoff,
which was accurate when written and is now stale on its whole header: it reported §4 item 4 as *not
started* (both halves are done), claimed *no open PRs* while #367 was open, and described dev-DB
residue that has since been deleted.

| | |
|---|---|
| `JToye_OaaS_2026` | **8 PRs merged this session:** #367, #368, #369, #370, #371, #373, #375, #376. HEAD deliberately **not** quoted — see the note below |
| `dotfiles` | **1 PR:** #51. `master`, tree clean |
| Open PRs | The concurrent session has `docs/adr-0004-accept` in flight. **§0.1 is not history — it captured one of my commits this session. Read it before you commit anything.** |
| Open issues | **58** in JToye |
| Live stack | Compose UP, **17** jtoye containers, all healthy. **0 active alerts** |
| Gates | **15/15 rc=0** — measured from the **main checkout**. From a worktree two VOID for a reason that is not a runtime fault; §6 explains it |
| Runtime proof | `Implementation-Version: 2.3.0` read from inside the running `app.jar` · ollama `gemma3:12b 100% GPU UNTIL Forever` |
| Project version | **2.3.0** (artifact, from `build.gradle.kts`). No `v2.3` tag — milestone in development |
| Dev DB | **23 orders, 1 probe order.** The residue was cleared (§4); the one remaining is the order that cleared `NoOrdersCreated` — §3.1, deliberate |
| Conda env | **none needed** — Java 21 + Gradle wrapper, Go 1.26, Node 22. No Python *application* code (several `scripts/*.sh` do use `python3`) |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges — the
> previous handoff proved it twice within one hour. §6 pairs every fact with the command that
> produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout — and it captured one of my commits

`/home/sanmi/IdeaProjects/JToye_OaaS_2026` is driven by **another session**. The branch switched
underneath this one **twice**, and the second time did real damage before it was caught.

**What happened, because it is the sharpest possible warning.** I created
`docs/handoff-redis-and-seed-findings` off `main` and edited `HANDOFF.md`. Between the edit and the
commit, the other session switched the checkout to **their** branch `docs/adr-0004-accept`, carrying
my uncommitted change with it. My `git add && git commit` then landed **on top of their commit, on
their branch**. The push "succeeded" against the *old* position of my branch, and `gh pr create`
failed with the only visible symptom:

```
GraphQL: No commits between main and docs/handoff-redis-and-seed-findings
```

That error names a branch, not the actual problem, and nothing else went red.

**It was recoverable only because of what had *not* happened yet:** my commit was **local-only** —
`git ls-remote` showed their remote branch still at their own commit — and the two commits touched
disjoint files (`HANDOFF.md` vs the ADR). Recovery: cherry-pick mine onto a worktree cut from
`origin/main`, then `git reset --hard` their local branch back to the commit the **remote** already
had, verified by comparing to `git ls-remote` and by grepping their ADR for content, not by
`git diff --stat`. Had I force-pushed, or had both commits touched one file, this would have been a
genuine loss of someone else's work.

Keep every habit below, because the failure is silent:

- **Do all work in a `git worktree`, from the start — not just when the checkout "looks" occupied.**
  That is the only habit that would have prevented the capture above, because the switch happened
  *between* my edit and my commit. Every other PR this session was authored that way and none was
  affected.
- **Check `git branch --show-current` immediately before `git add`/`git commit`, not only at the
  start.** The window that matters is the one between editing and committing.
- Never `git switch` a checkout you do not own.
- **Before recovering anything, establish what the *remote* has** (`git ls-remote --heads origin`).
  It is the only account of their work that your local repo cannot have corrupted, and it is what
  makes a `reset --hard` on someone else's branch safe rather than destructive.
- Remove a worktree *from the main checkout directory*, never from inside it — a removed-worktree
  CWD silently produces a dirty PR with zero CI runs.
- **A concurrent merge can put your branch behind mid-flight.** It happened on #376: the merge guard
  reported `behind main : 1` after #372 landed. Rebase and re-run — and check whether the new commit
  actually touches your files before assuming a conflict. `git diff --name-only HEAD..origin/main`
  lists files that differ in *either* direction, so **your own** edited file appears there and looks
  alarming; `git show --name-only <sha>` is the question you actually meant to ask.

### 0.2 The pattern that repeated all session: my own instruments were the defect

The previous handoff's lesson was *"nearly every real defect found was a GREEN check."* This session
extended it: **the checks I wrote to verify my own work were wrong seven times**, and each failure
produced a *confident* answer rather than an obvious error.

| what I measured with | what it actually did |
|---|---|
| `printf '%s rc=%s' "$(basename "$s" .sh)" "$?"` | `$( )` runs **before** `$?` expands, and resets it — so every gate printed **`rc=0`**. It faked a green; then when a later sweep measured honestly and printed `rc=1`, I read the pair as **a regression I had caused** and started diagnosing a Prometheus change that never happened |
| `grep 'v\[0-9\]'` to verify a file restore | Matches **neither** the literal `[0-9]` **nor** `v5` — incapable of firing. It read `0` for a restore that had **succeeded**, i.e. it would have reported failure identically either way. `grep -F` returns `1` |
| `[ -f /dev/null ]` in a break arm | **False** — `/dev/null` is a character device, so the arm silently took the not-found branch and tested nothing |
| `git branch -r` to count remote branches | A local cache. Listed branches the remote deleted long ago — **6 real branches looked like 13** until pruned |
| predicting the dotfiles merge guard would VOID | It reported a real `failure` from `copilot-pull-request-reviewer`, whose body reads *"The job was not started because recent GitHub Actions payments have failed"* — the billing VOID wearing a verdict's clothing (§5 item 2) |
| **saying `seed-order-metric.sh` clears `NoOrdersCreated`** | It does not. The script asks *does the series exist*; the alert asks *was there a recent order*. Both were true at once — gate green, alert firing — so it returned `PASS` **without placing an order**. Fixed in #376, not just retracted (§3.1) |
| **repeating `deferred-items.md` §11 as an open item** | `RedisDown` had been fixed the previous day. The entry's own evidence, `grep -c redis_up alerts.yml -> 0`, returns **5**. I copied a planning file forward without running the one command it offered (§3.2) |

**The habit that caught all seven:** run the check against a deliberately broken input *first*, and
verify restores **by content**, with an instrument you have seen fire. Not one was caught by
something going red.

### 0.3 A sync that would have destroyed the fix

`sync-claude.sh` runs **live (`~/.claude`) → repo**. The dotfiles pre-push hook refused a push with
`sync-claude: drift detected`, and the reflex fix — run `sync-claude.sh` — would have **overwritten
the new `housekeeping.md` with the old live copy**. The correct move was the opposite direction:
copy repo → live, after which `--check` passes because they match.

---

## 1. What landed

### JToye (8 PRs)

| PR | what |
|---|---|
| **#370** | `NoOrdersCreated` was blind after every rebuild; `HighErrorRate`'s exemption had expired — **§3** |
| **#373** | closed §4 item 4 — the end-to-end housekeeping run found a real defect — **§2** |
| **#371** | the dev-DB order residue cleared — **§4** |
| **#368** | the last emoji-scan finding (`✕` → lucide `<X />`), and a handoff that still self-staled |
| **#369** | closed the glyph half of the old §4 item 4, with the two corrections it earned |
| **#367** | recorded the merge-guard defect; stopped quoting HEADs that self-stale *(opened by the previous session, merged by this one)* |
| **#375** | `RedisDown` needed no fix — the deferral describing it was the defect — **§3.2** |
| **#376** | `seed-order-metric.sh` could not clear a firing `NoOrdersCreated` — **§3.1** |

### dotfiles (1 PR)

**#51** — `/housekeeping` Phase 14 could never clean a squash-merged branch. See §2.

---

## 2. The housekeeping defect — a phase that cleaned nothing and reported success

Running `/housekeeping` **end-to-end** on a second repo was the last open portability item. The
read-only phases had been tested; **12–15 had not, and 14 was broken.**

`git branch --merged` is **blind to squash merges** — a squash replays the branch as a *new* commit
with no parent link, so the branch is never an ancestor of the default branch. And **Phase 12 of the
same routine runs `gh pr merge --squash --delete-branch`**: the routine manufactured exactly the case
its own cleanup cannot see. Both this repo and `jtoye-market-intel` squash-merge, so Phase 14 had
only ever cleaned branches that arrived by some other route.

Proven in a throwaway repo **with a real-merge control arm**, so the test was shown able to
distinguish the two cases:

```
content check: the squashed work IS in main   -> present
git branch --merged main                      -> lists ONLY feat-real
git branch -d feat-real                       -> Deleted
git branch -d feat-squash                     -> error: not fully merged   <-- survives
```

Then confirmed live on this session's own PR: `-d` refused `fix/phase14-squash-merge-blindness`
(#51) while its content was demonstrably already on `master`.

**`MERGED` ≠ `CLOSED`, and the distinction is load-bearing.** Filtering on "has a PR" would have
deleted `jtoye-market-intel`'s `feature/insights-report` — `CLOSED, mergedAt=null`, work never
taken. The corrected run cleaned **4** merged branches across three repos and correctly kept **2**:
that one, and #372 above.

The fix makes PR state the authority, fails closed without `gh`, keeps `-d` first with `-D` only
where the forge *proved* the merge, refuses branches with no PR (Phase 13's business — which is why
13 runs first), refuses branches checked out in another worktree, and verifies against
`git ls-remote`, not `git branch -r`.

---

## 3. The alert that could not fire, and the exemption that had expired

`check-alert-metrics` was `rc=1` on two counts. Both fixes were the ones the file already prescribed.

**`NoOrdersCreated` matched zero series.** The cause is not guessable from the red:
`http_server_requests_seconds_count` is a Micrometer **request** counter — created on the first
matching request, **destroyed when core-java restarts**. It is not a database fact, so seeding an
order row does not create it, and no read endpoint does either. Measured: the series ran
`10:00:10–11:35:10Z`, vanished when core-java was rebuilt at ~`11:38Z`, and one `GET /api/v1/shops`
then moved the total series count `3 → 4`.

Since this project mandates rebuilding all containers after any code change, **the alert that detects
"orders have stopped" was blind after every rebuild until the first order** — precisely when you
would most want it.

The remedy is now committed rather than retyped: **`scripts/seed-order-metric.sh`** places one real
guest order through the public storefront path and waits for the scrape. Slug, product id and the
shop's minimum order value are all **discovered at run time**; it picks the dearest available product
so one unit clears the minimum, and VOIDs rather than placing an absurd order. The rule is untouched
and no gate was weakened. The M-1 failure message now names both cause and remedy.

Reproduced deliberately, not simulated: `docker compose restart core-java` → series `0` → gate
`rc=1` → `seed-order-metric.sh` → `201` → series `1` → gate `rc=0`. Twelve arms, including one hit
for real on the first run (HTTP 400 — the order was below the shop's £10 minimum → VOID).

**`HighErrorRate`'s exemption carried its own removal trigger** — *"Remove this entry the first time
a 5xx is served."* One was: `/actuator/health 503`, recorded during a core-java restart. Entry
removed; **`KNOWN_DATALESS` is now empty** and the gate reports `0 reasoned exemption(s)`. That is
the third exemption retired by the STALE arm rather than by review, which is the point of writing the
trigger into the entry. `deferred-items.md` §10 is closed accordingly.

### 3.1 The gate's question and the alert's question are different — #376

`seed-order-metric.sh` **could not clear a firing `NoOrdersCreated`**, and I had said it would. Both
of these were true at the same moment, and both were correct:

| asks | reads | verdict |
|---|---|---|
| `check-alert-metrics` M-1 | *does the series **exist**?* | `series=1` → gate **GREEN** |
| `NoOrdersCreated` | `increase(...[30m]) < 1` | `increase=0` → alert **FIRING** |

So the script's early exit — *"the counter already exists, nothing to seed"* — was right for the gate
and useless for the alert: it returned `PASS` **without placing an order**. The newest order in the
DB was 2026-07-15, because §4 had deleted the probe orders.

**#376 adds `FORCE=1`**, which places an order even when the series exists and asserts the **alert's**
condition, `increase(<selector>[$ALERT_WINDOW]) >= 1` — not the gate's. Asserting series existence
there would have "passed" while the alert kept firing, which is the wrong claim the option exists to
prevent. The default path now prints the distinction and names the `FORCE=1` invocation.

Verified against live Prometheus rather than the script's own `PASS`: `NoOrdersCreated` **RESOLVED**,
`increase[30m] = 1.008`, alert expression **0 samples**.

> **This alert will fire again ~30 minutes after the last order, and that is it working, not a
> fault.** `bash scripts/seed-order-metric.sh` (no `FORCE`) is the right call when the *gate* is red;
> `FORCE=1 bash scripts/seed-order-metric.sh` when the *alert* is firing and you want quiet. Each run
> leaves one real `metric-seed@jtoye.local` order behind — that is why the dev DB shows 23/1 rather
> than the 22/0 of §4.

### 3.2 `RedisDown` needed no fix — the deferral describing it was the defect — #375

Asked to fix `RedisDown`, and it was **already fixed**: #345 (`79a3a6a2`, 2026-07-29) shipped
`expr: up{job="redis"} == 0 or redis_up == 0`, which is what `main` and the live Prometheus both
carry. What was broken was `deferred-items.md` §11, still saying *"Not fixed here"* and proposing —
as new work — the expression already in the file. Its load-bearing evidence,
`grep -c redis_up alerts.yml -> 0`, returns **5**. I had copied that entry into this handoff's
known-unfixed list without running its grep; §5 records that.

Re-verified live rather than trusting #345's comment — Redis stopped with `jtoye-redis-exporter` left
running, the exact condition the old rule was blind to:

| | during outage | after restore |
|---|---|---|
| `up{job="redis"}` | **1** — the exporter still answers | 1 |
| `redis_up` | **0** — Redis actually unreachable | 1 |
| OLD `up{job="redis"} == 0` | **0 samples** — would not have fired | 0 |
| NEW `… or redis_up == 0` | **1 sample** — fires | **0** — no steady-state page |

**The general lesson, now in that file's header: nothing gates `deferred-items.md`.** Unlike
`docs/ops/terminal-states.yaml`, no script checks it, so an entry whose *reason* stops being true
survives silently until someone reaches its expiry date. Both closures dated 2026-07-30 (§10 and
§11) were found by running an entry's own evidence, never by review. Audited the rest without
over-claiming: **§5** (no k8s alerting, 0 manifests) and **§7** (`StompBrokerLag` dormant, 0 active
rules) verified **still valid**; §8 and §9 hold measurements describing a state *at drafting* —
historical records, correct as written. The test is whether an entry asserts something is *still*
true.

---

## 4. Dev-DB cleanup

All four `*@jtoye.local` probe orders deleted — 20 rows across `orders`, `order_items`,
`order_items_aud`, `orders_aud`, `processed_order_events` — in one transaction that asserted every
count was exactly 4 and rolled back otherwise, keyed on ids captured up front rather than a `LIKE`
pattern evaluated at delete time. Backed up to CSV first. The guard was then falsified by re-running
it: `ABORT: expected 4 rows in every table, got 0`, tree unchanged.

Deleting rows does **not** affect `NoOrdersCreated` — that counter is a *request* counter, confirmed
still at 1 series with the gate green afterwards.

**Left deliberately:** 8 `@example.test` orders from the 2026-07-12/14 QA-council runs, 13
`@test.com`/`uat-tester` demo + E2E seed orders that specs may depend on, 1 null-email order, and
~40 `SyntheticDeliveryProbe-*` messages in **Mailhog** (not the DB). Orders went **26 → 22** here,
and back to **23** when §3.1 placed one to clear `NoOrdersCreated` — that one is deliberate, not
residue.

---

## 5. Open items — none of the three is actionable by an agent

1. **Gate consolidation — deferred, #362 CLOSED.**
   `.planning/phases/27-operational-maturity/deferred-items.md` **§13**, re-check **2026-09-30**.
   Both the bespoke gates and the engine stay in CI meanwhile, so nothing is unguarded.
   ⚠ `deferred-items.md` is **not** gate-enforced — the date is a convention, not a guarantee.
2. **dotfiles Actions billing — NOT fixed.** `dotfiles` is PRIVATE (paid minutes);
   `JToye_OaaS_2026` is PUBLIC (free). The job never *starts*, so its `failure` is a VOID, not a
   verdict — §0.2. Needs `github.com/settings/billing`. Mitigated by the `pre-push` hook (#47),
   which refused a push this session and was right to. **Your call, not mine.**
3. **No `v2.3` git tag.** Artifact is `2.3.0`; the milestone is in development. Cutting the tag is
   also what would push a version-numbered image (`type=semver` only fires on `v*`).
   **A release decision.**

### Known, unfixed, deliberately out of scope

- **`financial_transactions.order_id` has no foreign key to `orders`**, and 3 rows from
  2026-07-09/11 already point at orders that no longer exist (verified to reference none of the ids
  deleted in §4). Dev-DB residue as it stands; the same shape in production is a ledger-integrity gap.
- ~~**`RedisDown` watches the exporter, not Redis**~~ — **NOT an open item; it was fixed in #345 the
  day before, and I listed it here by copying `deferred-items.md` §11 without running its own
  evidence.** Full account, including the live re-verification and the reason nothing catches a
  stale deferral, is **§3.2**.
- `order_items_aud`/`orders_aud` carry 97/117 orphans — **expected**, audit mirrors of legitimately
  deleted orders.

---

## 6. Resume instructions

```bash
# 0. FIRST: is another session still using this checkout?  (§0.1)
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git branch --show-current; git status --short
#    If that is NOT 'main', do NOT switch. Work from a worktree instead:
#      git worktree add <dir> -b <branch> origin/main

# 1. Both trees clean and holding nothing unmerged. Asserted, never quoted —
#    and the default branch differs per repo (main vs master), so RESOLVE it.
for r in /home/sanmi/IdeaProjects/JToye_OaaS_2026 /home/sanmi/dotfiles; do
  git -C "$r" fetch -q origin || { echo "VOID $r: fetch failed"; continue; }
  b=$(git -C "$r" symbolic-ref --quiet --short refs/remotes/origin/HEAD) || { echo "VOID $r: no origin/HEAD"; continue; }
  echo "$r on $(git -C "$r" branch --show-current) vs $b: dirty=$(git -C "$r" status --porcelain|wc -l) ahead=$(git -C "$r" rev-list --count $b..HEAD) behind=$(git -C "$r" rev-list --count HEAD..$b)"
done
# expect dirty=0 ahead=0 behind=0 for dotfiles; JToye will differ while §0.1 holds.
# A VOID line is NOT a pass.

# 2. Every gate. Capture rc on its OWN statement — §0.2 explains why.
#    RUN THIS FROM THE MAIN CHECKOUT, NOT A WORKTREE — see the note directly below.
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-32s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# From the MAIN checkout: expect rc=0 for ALL 15 — measured, including
# check-branch-behind-base (it asserts HEAD is not BEHIND origin/main; the concurrent
# session's branch is ahead-but-not-behind, so it passes).
# From a WORKTREE, two of them VOID instead — see the note below.

# 3. The AI path, end to end — not just that the model file exists
docker exec jtoye-ollama ollama ps          # expect gemma3:12b ... 100% GPU ... Forever
docker exec jtoye-mcp-server wget -q -T60 -O- \
  --post-data='{"model":"gemma3:12b","prompt":"Reply READY","stream":false}' \
  --header='Content-Type: application/json' http://ollama:11434/api/generate   # expect "READY"

# 4. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
#    For dotfiles add: --allow-check copilot-pull-request-reviewer   (§0.2 — the job never starts)
```

**`NoOrdersCreated` — two different symptoms, two different fixes. Do not confuse them (§3.1):**

| symptom | what it means | fix |
|---|---|---|
| **`check-alert-metrics` fails** on it | the stack was rebuilt, the request counter is gone, and the alert is currently **blind** | `bash scripts/seed-order-metric.sh` |
| **the alert is firing** in Prometheus | the counter exists but no order landed in the last 30m — the alert is **working** | `FORCE=1 bash scripts/seed-order-metric.sh` |

The plain invocation **exits early and places no order** when the series already exists, so it will
not silence a firing alert — it says so itself now. Either way, do **not** re-add a
`KNOWN_DATALESS` entry; the gate's own header calls that the wrong fix. Each `FORCE=1` run leaves one
real `metric-seed@jtoye.local` order in the dev DB.

**If `check-runtime-freshness` or `check-container-config-drift` VOIDs (exit 2) — check *where* you
ran it before you touch the stack.** Both VOID from a **worktree** even on a perfectly healthy
stack, reporting every service `NOT RUNNING`. Measured this session: `rc=2` from a worktree and
`rc=0` from the main checkout, same moment, same containers.

The cause is **not** the missing `.env` (that is gitignored and absent too, but symlinking it in
does not fix this — verified). **Compose derives the project name from the directory**, and the
containers carry it as a label:

```
worktree dir 'wt-handoff'   -> compose project 'wt-handoff'      -> 0 containers
repo root                   -> compose project 'jtoye_oaas_2026' -> matches the labels
```

So from a worktree the gates query an empty project namespace. And **forcing
`COMPOSE_PROJECT_NAME` cannot fix it**, because there are *two* projects, each named after its own
directory: `jtoye-frontend` is labelled `jtoye_oaas_2026`, while `jtoye-prometheus` and
`jtoye-grafana` are labelled `monitoring` (from `infra/monitoring/`). Setting the name to
`jtoye_oaas_2026` fixed `check-runtime-freshness` and left `check-container-config-drift` VOID on the
monitoring half.

**Just run those two from the main checkout.** They fail **closed**, which is correct — but do not
go hunting for a runtime fault that does not exist.

Once you have ruled that out, a genuine VOID means the stack is down or a built service is not
running — **any** missing built service VOIDs the whole run by design. Bring it up with
`docker compose -f docker-compose.full-stack.yml --env-file .env up -d` and re-run. A VOID is never
a pass.

**Squash-merge note:** `gh pr merge --delete-branch` only deletes the *remote* branch when you are
**on** that branch locally. Merging from a worktree leaves it behind — clean it with the Phase 14
procedure (PR state, not `git branch --merged`).

---

## 7. Environment state

- **JToye:** see §0.1 — a concurrent session owns the checkout. No branches of mine remain.
- **dotfiles:** `master`, clean, only `master` locally. `sync-claude.sh --check` clean (§0.3).
- **Remotes** hold only `main`/`master`, plus `docs/adr-0004-knowledge-graph-strategy` (#372, theirs)
  and `jtoye-market-intel`'s `feature/insights-report` (#24 CLOSED — deliberately kept, §2).
- **Live stack:** 17 jtoye containers, all healthy. 4/4 built services FRESH; the running frontend
  and core-java image IDs were compared against their tags, which is what catches a `start`-only.
- **Dev DB:** 23 orders, 1 probe order — the `metric-seed@jtoye.local` order that cleared
  `NoOrdersCreated` (§3.1). Deliberate, not residue.
- **Alerts:** 0 active. `NoOrdersCreated` will re-fire ~30 min after the newest order; that is the
  alert working.
- **Test baseline:** `docs/metrics.json` — **1851** total logical invocations, enforced by two gates
  (`docs-freshness.sh` for the tree, `check-doc-metrics.sh` for the prose).
- **Toolchain:** `doctor.sh --check` reports drift on several tools; **report-only, never converge
  inside a housekeeping run.**
