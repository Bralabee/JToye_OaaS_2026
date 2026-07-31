# Handoff: node 24 is merged and RUNNING — the E2E hold was noise all along

**Generated:** 2026-07-31 ~01:00 BST, **updated ~21:15** with the tab-bar finding (§5.14) on top of
the node-24 resolution (§5.11–§5.13).
Supersedes the "every open item is closed except three" handoff, which was accurate when written.
Its §0.1 (concurrent session), §3.1 (`seed-order-metric` `FORCE=1`) and §3.2 (`RedisDown`) are
still true and are carried forward below in compressed form; its §2 and §4 are history and are not
repeated.

> **The single most useful thing in this document is §5.11's lesson:** the E2E baseline is not
> stable to ±1 between identical runs, so a ±3 count delta can never be read as a regression.
> **Compare by test NAME.** That is what turned a "held pending investigation" PR into a merge.
>
> **The second most useful is §5.14's.** Three times today a conclusion was drawn from a *symptom*
> while its *mechanism* was still open. **One was published wrong** — #404's "duplicate nav is an
> a11y defect"; there is no duplicate nav (§5.14). The other two were caught only because a *second*
> instrument disagreed with the first: a break arm that appeared to prove an assertion could not fail
> (the arm was broken, §5.14), and a diff filter that appeared to prove #402 never touched the docs
> (`--stat` said otherwise, §0.3). **Before asserting a consequence, establish the mechanism — or
> mark the consequence unestablished too, not just the mechanism.**

| | |
|---|---|
| `JToye_OaaS_2026` | **Node-24 session (§5.11–§5.14): 6 PRs merged** — #402, #400, #399, #405, #403, #406, plus this one. **1 issue opened:** #404. HEAD deliberately **not** quoted — see the note below |
| Open PRs | **none once this merges.** **#402 is MERGED** (node 24, hold lifted). **#399 is MERGED**, **#400 is MERGED** (dependabot). **#405 is MERGED** (the doc straggler #402 left). **#406 is MERGED** (the tab-bar locator, §5.14). **#398 is CLOSED** — it proposed node 25, already EOL. ⚠ §5.10 is the standing warning that a "none" in this row is exactly what `check-handoff-contract` **cannot** catch — re-read, do not trust its silence |
| Open issues | **60**; **#404 is OPEN** — the E2E baseline. ⚠ **#404's section (a) is WRONG and is corrected in-issue** (banner + comment) and in §5.14: there is **no** duplicate nav and **no** a11y defect. Its live count is **23**, not 27 — 5 of the 27 were the locator artefact #406 fixed. **#385** and **#384** remain **CLOSED** |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck (§3). Alerts routed to `mute-null` — the mute working, §1.1 |
| Gates | **18 of 18 rc=0**, re-measured after the node-24 rebuild. The 17th is `check-changelog-contract` (#393), the 18th `check-handoff-contract` (#395) — which is what asserts this very row |
| Runtime proof | 4/4 built services FRESH · **`node --version` = `v24.18.1` read from inside BOTH the running `jtoye-frontend` and `jtoye-mcp-server`** (alpine 3.24.1) · core-java still Java 21 / `Implementation-Version: 2.3.0` from inside `app.jar` · container image IDs `==` their tags, so this is a real rebuild and not a restart |
| Project version | **2.3.0** (`build.gradle.kts:15`). No `v2.3` tag |
| Test baseline | `docs/metrics.json` **1872** — java 1264, jest **440** / 65 files, schema V60 |
| Dev DB | **37 orders** in db `jtoye` (not `jtoye_dev`), **0** `metric-seed%` *customers*. **The delta is no longer unexplained — see §5.12: the E2E suite writes REAL orders**, one per storefront checkout, tagged `email-<epoch_ms>@test.com`. `metric-seed` orders are **guest** orders, so the email is on the order and there is no `customers` row — which is why that count reads 0 while three such orders exist |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §4 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout — still true

`/home/sanmi/IdeaProjects/JToye_OaaS_2026` is driven by another session. It previously held a
worktree at `.../wt-notice` on `feat/allergen-consent-notice`. **That worktree is gone** — #383 was
merged and both it and `.../wt-384` were removed after their branches proved merged (§5). The
warning itself stands: assume a second session shares this checkout.

The previous handoff records the sharp version of this warning: a branch switch between an edit and
a commit **captured one of my commits onto their branch**, and the only visible symptom was
`gh pr create` saying *"No commits between main and <branch>"*. Worth reading in `git log` if you
have not seen it.

The habits, unchanged:

- **Work in a `git worktree` from the start** — the only habit that prevents the capture, because
  the window is *between* editing and committing.
- **Check `git branch --show-current` immediately before `git add`/`git commit`,** not only at the
  start. Every commit this session was guarded with an explicit
  `[ "$(git branch --show-current)" = "<expected>" ] || exit 1`.
- Never `git switch` a checkout you do not own.
- Establish what the **remote** has (`git ls-remote --heads origin`) before recovering anything.
- Remove a worktree **from the main checkout**, never from inside it.
- **A concurrent merge can put your branch behind mid-flight.** #380 landed while both my branches
  were in review. Rebase and re-run.

> **⚠ It happened again on 2026-07-31 ~20:34, and worktrees did NOT prevent it — because the command
> was a `pull`, not a commit.** A second session created
> `feature/faster-integration-tests-container-reuse` at 20:31 and checked it out in the shared
> checkout. Three minutes later I ran `git pull --ff-only origin main` **in that checkout**, and it
> fast-forwarded **their** branch from `2b5339f8` to `6f159fd0`. The reflog is explicit:
>
> ```
> feature/faster-integration-tests-container-reuse@{0}: pull --ff-only origin main: Fast-forward
> feature/faster-integration-tests-container-reuse@{1}: branch: Created from HEAD
> ```
>
> **Damage, established rather than assumed:** none to commits — it was a fast-forward and their
> branch carried no commits of its own. Their four uncommitted test-file edits also survived it
> (observed `dirty=4` *after* the pull; the incoming commits touched `HANDOFF.md`,
> `docs/CHANGELOG.md` and `check-handoff-contract.sh`, none of their files). Only the branch pointer
> moved. It was deliberately **not** reset backwards — a backwards move on a branch you do not own
> risks more than it repairs.
>
> **The habit this adds, because the existing one was not sufficient:** committing from a worktree
> protects *your* commits, and it did. It does nothing about **mutating git commands aimed at the
> shared checkout**. `git pull` writes: it moves whatever branch happens to be checked out there.
> So — **never run `pull`, `checkout`, `merge`, `reset` or `branch -D` in a checkout you do not own,
> and check `git branch --show-current` immediately before any of them, not just before `commit`.**
> Do refreshes inside your own worktree (`git fetch` is safe; `git merge origin/main` there is not
> shared state).

### 0.2 The defect the USER caught: I proved a fix on a server nobody was looking at

**This is the most important entry in this document.**

I verified the sign-in split end-to-end in a real browser — customer flow reaching
`jtoye-customers`, vendor flow reaching `jtoye-dev`, twelve assertions, all green — and reported it
as working. The user then said:

> *"i don't see the changes. both still bring me to same place even after a hard refresh."*

They were right. The proof ran against a **temporary `next start` on :3105** built from the branch,
which I then shut down. Their browser was pointed at **:3000**, the compose stack, serving `main`.
The branch was not merged. Everything I said was true of a runtime that no longer existed.

CLAUDE.md's rule (b) — *"a phase is not done until the DELIVERED RUNTIME matches the branch"* —
exists for exactly this, and I still walked into it, because **the harness was honest and the
inference was not**. A green run tells you what it was pointed at, not what the user sees.

**The habit that fixes it:** state the base URL and its provenance in the same breath as the
result. Not "ALL PASS" but "ALL PASS *against :3105, a temporary build of the branch; the stack on
:3000 still runs main*". Better: point the harness at the URL the user actually uses, and say so.

### 0.3 My own instruments were wrong six more times

The previous handoff logged seven. The sign-in-split session added four and the node-24 session two
more, and **not one was caught by something going red**. Rows are labelled by session where it
matters. The two newest are both *absence* bugs — a search that stopped matching and reported the
silence as a clean result:

| what I measured with | what it actually did |
|---|---|
| a `perl -0pi -e` regex to plant a break arm | **Never matched.** `grep -c BREAK-ARM` returned `0` and the suite reported *"17 passed"*. Had I read that as a pass I would have published a **fabricated** falsification. Redone by deleting the guard lines **by number**, with the plant asserted *before* the run |
| `grep` for test **method names** in a Gradle XML report | Reports record `@DisplayName`, not method names. Returned `0` for all five new tests, which had in fact run |
| `grep` for class names in a CI **job log** to see what ran | Gradle does not name passing classes. Found 21 of the **104** that actually ran; I briefly concluded `RlsContractTest` had not run. The **artifact** is the authoritative record |
| Playwright `isVisible()` as an assertion | It **samples**, it does not wait. Two false FAILs during hydration; the served HTML had the heading all along |
| *(node-24 session, §5.12)* `git show <sha> -- CLAUDE.md \| grep -E '^[+-][^+-]'` | Returned **empty**, and I nearly reported that #402 never touched the docs. The diff lines are **markdown bullets**, so they read `-- Node.js: 20+` — second char `-`, which the filter excludes by construction. `--stat` disagreed, which is the only reason it surfaced. **A filter used to prove absence produced the absence** |
| *(node-24 session, §5.11)* a **fixed-depth** jq path, `.suites[].suites[]?.specs[]?` | Cannot see a deeper nesting level, so it under-reports failures — and under-reporting reads as *improvement*. Replaced with a recursive walk and **validated against `stats`** (128 rows vs 128; 27 FAIL vs `unexpected` 27) before being trusted |

Plus **two in the work itself**, both of the same shape — a check whose failure mode is silence:

- **An acceptance criterion that could not fail.** `check-alert-mute`'s M-2 ran per-matcher *after*
  M-1 with a `continue` between them, and in Alertmanager's `matchers:` list form a forbidden label
  is always its own entry — so M-1 rejected it first and M-2 was **never reached**. Fixed by
  scanning the whole matcher set first. Both now fire.
- **A hook that was silent on the only case it exists for.** `.githooks/post-merge`'s first draft
  carried `trap 'exit 0' ERR` as a "never break a pull" safety net. Bash runs an ERR trap on any
  non-zero command, and the parity gate **returns 1 exactly when there is drift** — so the hook
  exited before printing anything. Every other path looked perfect; caught only by stubbing the
  gate to exit 1 and observing **no output**. Removed; non-blocking is now achieved by every path
  ending in `exit 0`, which git ignores anyway.

That second one is uncomfortable and worth sitting with: it is the same defect the hook exists to
prevent, committed while building the hook.

---

## 1. What landed

### #381 — environment-scoped Alertmanager mute (`ALERTMANAGER_MUTE_ALERTNAMES`)

`NoOrdersCreated` fires ~30 min after the last order, so a quiet local stack pages forever — and
the only prior remedy, `FORCE=1 scripts/seed-order-metric.sh`, buys silence by **writing a real
order row into the dev DB every run** (hence 25/3, §3).

It withholds the **notification only**. The rule still evaluates and still shows as firing in
Prometheus and the Alertmanager UI. `alerts.yml` is byte-unchanged.

Two constraints, both **measured** rather than assumed:

1. **The child routes share one YAML key.** `entrypoint.sh` rendered `__SLACK_ROUTE_BLOCK__` as the
   whole `routes:` mapping key, so a second block emitting it is a duplicate-key error `amtool`
   rejects. Now composed into `__CHILD_ROUTES_BLOCK__`, emitted once, in route order: mute first
   (no fall-through), Slack second (`continue: true`).
2. **The matcher keys on `alertname` and nothing else.** `check-alert-liveness.sh:435` posts its
   L-3 probe with `severity="info", service="platform"`; a severity-keyed mute swallows it and L-3
   then blames *"an active silence, an inhibit rule"* — reading as a transport fault rather than as
   this config. A mute value that is not a bare alertname is **FATAL at container start**.

New gate **`scripts/check-alert-mute.sh`** (M-1…M-6; `MODE=static` for CI). **The M-6 arm is the
point of it:** on a misordered route, **M-1 through M-4 all passed** over a mute that consumed
nothing — only the functional assertion caught it.

The unconfigured render is **byte-identical** to the pre-change baseline captured from the running
container (1629 bytes, `sha256 f9b5b39f…`), and the Slack-only render is byte-identical to the same
arm rendered from the pre-change files. The existing feature is provably unregressed.

### #382 — customer and vendor sign-in were the same page

`public-header.tsx` "Sign in" and `public-footer.tsx` "Vendor sign in" both resolved to
`/auth/signin`, which authenticates against the **`jtoye-dev` staff realm**. Customers exist only in
**`jtoye-customers`**. The **backend split was already correct** (`CustomerJwtVerifier`, separate
`CUSTOMER_KC_ISSUER_URI`) — only the frontend leaked. Customer login had **no page at all**: a bare
`window.location` redirect from a button, so an expired session or a `/shop/orders` deep link had
nowhere to land.

Ships `/shop/signin`, repoints the header, retitles `/auth/signin`, adds reciprocal persona
cross-links, and turns both bare redirects into `Link`s carrying `?next=` — narrowed by
`safeReturnTo` at **both** ends against open redirect (absolute, protocol-relative `//host`,
backslash variants, `javascript:`/`data:`).

**Prerequisite shipped first:** a reserved-slug guard. `/shop/signin` is a **static** segment under
`/shop/[slug]` and Next.js resolves static first, so a vendor-supplied slug would strand a shop
permanently. Reachable today — `CreateShopRequest.slug` is user-supplied and honoured verbatim when
non-blank — and **already live for `auth` and `orders` since Phase 18**. RFC 7807 422; the list is
config (`jtoye.shop.reserved-slugs`), not a constant, because it tracks the frontend route table.

**This change broke an existing verifier in a way that would not have failed loudly.**
`e2e/customer-realm-split.verify.mjs` located the storefront control with `getByRole("button")`,
now a `Link` — and `.first()` on a missing role **times out**, which reads as a slow stack. Repaired
to the two-step flow and deliberately **not** relaxed to a button-or-link matcher, which would keep
passing through exactly this class of change. Checked and found safe: the six vendor E2E specs
locate the SSO control by *"Sign in with Keycloak"*, unchanged — otherwise their
`test.skip(true, "No sign-in method found")` fallback would have gone green while testing nothing.

### #386 — the handoff this document supersedes

Recorded §0.2 (the runtime-parity miss the user caught) and the four wrong instruments.

### #387 — the parity gate now runs itself

**The gate was correct and nobody invoked it.** #380 merged, changing `core-java` sources; `git
pull` on the machine hosting the stack said nothing, and the container kept serving an image built
four hours earlier — through two further PRs — until someone ran it by hand.

**`.githooks/post-merge` is now INSTALLED AND LIVE on this checkout.** It runs
`check-runtime-freshness.sh` after every merge and names the fix. Advisory (every path exits 0),
silent unless the stack is **UP and DRIFTED**, reports VOID as *"no opinion, NOT a pass"*, and skips
inside a worktree where the gate cannot answer.

`scripts/sync-runtime.sh` is the fix it names: asks the gate what drifted, rebuilds exactly those
with `up -d --build`, then **re-asserts with the same gate**, so it cannot report success over a
runtime the gate would still call stale. The service names are parsed from the gate's output, so
the parse is asserted — *drift reported but zero names parsed* is a VOID, never "nothing to do".

**Why it is not a CI job, checked rather than assumed:** 0 self-hosted runners, every job
`ubuntu-latest`, and `DEPLOY_ENABLED` unset so the deploy jobs never run — a GitHub-hosted runner
has **no runtime to inspect**, local or deployed, so the gate could only ever exit 2 there. What CI
*can* assert, and now does as the fifth Operational Contracts step, is `install-hooks.sh --check`:
that the hook is executable **in the git index**. A hook committed `100644` is skipped silently by
both git and the dispatcher's `[[ -x ]]`, and that symptom is identical to a clean run.

**How hooks reach this repo — and why `install-hooks.sh` does NOT set `core.hooksPath`.** This
machine runs a global hook set (`core.hooksPath = ~/.git-hooks`) whose members are **dispatchers**:
git runs only one hooks directory, so each delegates to a repo-local `.githooks/<name>` when the
repo commits an executable one. **A repo opts in by committing the file and nothing else.** A
per-repo `core.hooksPath` *replaces* the global directory and would disable the sibling
`prepare-commit-msg` (it strips the authorship trailers this project's git policy forbids) and
`pre-push`. The first draft of the installer did exactly that.

> **A config change was made to this shared checkout.** The repo-level `core.hooksPath`, which
> pointed at a directory holding **zero** hooks and thereby disabled all three dispatchers here, was
> **removed**. `git config --local` writes to the shared `.git/config`, so this applies to the
> concurrent session's worktree too: their commits now pass through `prepare-commit-msg` as well.
> Nothing was displaced (the shadowed directory was empty, and the installer refuses if it is not).

---

## 2. Open items

### 2.1 `HighErrorRate` — #384. **CLOSED by #389.** The analysis below is kept as the reasoning

> **Status update (housekeeping, 2026-07-31).** #384 is **CLOSED**; #389 fixed it by teaching
> `check-alert-metrics` to distinguish a **self-healing** empty selector from a **blind** one.
> `HighErrorRate` is now a *declared* self-healing rule — an empty 5xx counter is the correct
> resting state of a healthy platform, not a violation. The gate reports
> `self-heal : 0 rule(s) empty-but-self-healing (#384), 1 declared`.
> Everything below is the analysis that motivated the fix; it is no longer an open item.

**Read this before concluding #384 is fixed.** At the time of writing `check-alert-metrics` is
rc=0 — because three genuine 500s happened to be served after the rebuild:

```
count(http_server_requests_seconds_count{status=~"5.."})  ->  3
  500 /api/v1/announcements/{id}   500 /api/v1/promotions/{id} x2
```

Those are stale (`rate[5m]=0`, `increase[30m]=0`) and `HighErrorRate` is `inactive` — no live
error problem. But the series exists **only until the next core-java restart**, so the gate is
green by accident and will go red again on the next rebuild. Nothing was fixed.

`http_server_requests_seconds_count{status=~"5.."}` is a Micrometer **request** counter, destroyed
on every core-java restart. This project mandates rebuilding after any code change, so **the alert
that detects "the platform is erroring" is blind after every rebuild.**

Identical mechanism to `NoOrdersCreated`, but with **none of the remedy**: no seed script, no
`KNOWN_DATALESS` entry (the array is empty — the exemption was *correctly* retired in #370), and a
runbook section that says nothing about the blind state.

> **Do not make this green by serving a 5xx.** That manufactures the exact signal the gate exists
> to detect. It was left red deliberately. #384 lists four options and requires whichever is chosen
> to be shown to **fail** first.

### 2.2 `H-5` reports a number that does not measure its label — #385. **CLOSED by #396**

> **Status update (2026-07-31).** **#385 is now CLOSED.** #396 prints each accumulator under the
> label that describes it — `pin-not-at-site` (NOTE class, advisory) and `site-unresolvable` (VOID
> class) — and fixed the reason the drift persisted: `--refresh`, the remedy the NOTE advertises,
> carried `len(want_sites[cur]) == 1` and only matched the inline list form, so it silently skipped
> every multi-site and block-form row. That was **exactly** the three stale rows. Both forms now
> rewrite at any length, and the three real citations were corrected. Kept **advisory** (the issue's
> option 1): `rc` behaviour is unchanged. The analysis below is retained as the reasoning.

`check-dependency-horizons.sh` prints `H-5 drift  pin-not-at-site=0` **in the same run** in which it
printed `NOTE minio: pin not at docker-compose.full-stack.yml:9999; found at line(s) 407`.

Two accumulators: `DRIFT` (pin found *nowhere* → fails) and `LINE_DRIFT` (pin at a *different* line
→ NOTE only). The summary prints `pin-not-at-site=$n_drift`, so the label names what `LINE_DRIFT`
measures while the number reports `DRIFT`.

Not filed as "make it fail" — failing on every line shift would be noisy, and this session's own
compose comment shifted two rows. **The defect is the label/number contradiction.** The consequence
is real: four rows were stale on clean `main`, corrected only as a side effect of an unrelated PR.

### 2.3 An external data source flipped both PRs red mid-review

`Operational Contracts` went red on both open PRs with no code change:
`endoflife.date` gave Redis 7.4 a concrete EOL (`2029-12-01`) where it previously published `false`.
Established as **environmental before touching anything** — the same gate failed on a clean `main`
checkout, rc=1. Fixed with `scripts/check-dependency-horizons.sh --refresh`. Same class as the
Trivy daily-DB time-bomb: expect it again, and diagnose against clean `main` before assuming your
branch caused it.

> **Recurred on #383, with a different remedy — read this before running `--refresh`.** The refresh
> above landed in `main` as part of #381 (`6f4e5fd5`). #383 had been cut earlier and was **six
> commits behind base**, so it still carried the stale `eol_date: "false"` and failed the identical
> gate a day later. Running `--refresh` on the branch would have been a *second, competing* edit to
> the same manifest row; the correct fix was `git merge origin/main`.
> **The tell:** a sibling PR (#389) passed the same gate the same morning. When that is true the
> gate is not flaky and `main` is not broken — **your branch is behind base**. Check
> `git log <branch>..origin/main` and diff the manifest row before editing anything.
> Note `Branch Not Behind Base` is no protection: it passed on #383 when its CI ran, and `main`
> moved six commits underneath it afterwards. It only ever means "was not behind *at that time*".

### 2.4 Carried forward, still true

- **`NoOrdersCreated` has two symptoms with two different fixes.** Gate red → `bash
  scripts/seed-order-metric.sh`. Alert firing → `FORCE=1 bash scripts/seed-order-metric.sh`. The
  gate asks *does the series exist*; the alert asks *was there a recent order*. Both can be true at
  once. **Locally the mute now covers the firing case** — prefer it over `FORCE=1`, which writes a
  real order row each run. Never re-add a `KNOWN_DATALESS` entry; that gate's header calls it the
  wrong fix.
- **`RedisDown` needs no fix** — fixed in #345. The `deferred-items.md` §11 entry describing it was
  the defect. **Nothing gates `deferred-items.md`**, so an entry whose reason stops being true
  survives until its expiry date. Re-run an entry's own evidence before repeating it.
- **Gate consolidation** deferred, `deferred-items.md` §13, re-check 2026-09-30.
- **dotfiles Actions billing** — not fixed; the job never *starts*, so its `failure` is a VOID.
- **No `v2.3` git tag.** A release decision.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.

---

## 3. Environment state

- **JToye:** `main`, clean. **One branch, one worktree** — every merged feature branch was deleted
  on 2026-07-31 (§5). No other session holds a worktree in this checkout.
- **Live stack:** 17 jtoye containers. **15 healthy; `jtoye-redis-exporter` and
  `jtoye-postgres-exporter` report no health status because they define no healthcheck** (their
  images are scratch-based — see the comment in `infra/monitoring/docker-compose.monitoring.yml`).
  That is **not** unhealthy: `check-alert-liveness` L-1 asserts every scrape target is `up`, and it
  passes.
- **Alertmanager:** the mute is ACTIVE locally via `.env`
  (`ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated`). `.env` is gitignored, so **a fresh clone is loud
  by default** — which is intended. **Observed working under real conditions, not a synthetic
  probe:** `NoOrdersCreated` is currently **firing** in Prometheus (2 instances) and Alertmanager
  routes both to `receivers=mute-null` — visible in both UIs, no email. That is the contract.
- **Git hooks:** `.githooks/post-merge` is **installed and live** on this checkout, reached through
  the global dispatcher at `~/.git-hooks` (there is no `core.hooksPath` to set — §1, #387). The
  repo-level override that previously shadowed it has been removed, which also re-enabled
  `prepare-commit-msg` and `pre-push` here **for every worktree of this repo**, including the
  concurrent session's.
- **Dev DB:** 25 orders, 3 `metric-seed@jtoye.local` (17:47 and 19:17 predate this session; 23:52 is
  mine, from clearing the gate after the rebuild).
- **Conda env:** none needed — Java 21 + Gradle wrapper, Go 1.26, Node 22. No Python *application*
  code (some `scripts/*.sh` call `python3`; the base-python hook does not intercept those).
- **Test baseline:** `docs/metrics.json` **1868**, enforced by two gates — `docs-freshness.sh`
  (tree → manifest) and `check-doc-metrics.sh` (prose → manifest). Both must move together; the last
  stale prose claim this session was found by the gate, not by eye, after a bulk `sed` looked
  complete.

---

## 4. Resume instructions

```bash
# 0. FIRST: is another session using this checkout?  (§0.1)
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git branch --show-current; git status --short; git worktree list
#    If that is NOT 'main', do NOT switch. Work from a worktree:
#      git worktree add <dir> -b <branch> origin/main

# 1. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) ahead=$(git rev-list --count $b..HEAD) behind=$(git rev-list --count HEAD..$b)"
# expect dirty=0 ahead=0 behind=0 on main. A VOID line is NOT a pass.

# 2. Every gate. Capture rc on its OWN statement — §0.3 explains why.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE (see the note below).
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 18 x rc=0 — ALL of them. Updated 2026-07-31: #393 added the 17th
# (check-changelog-contract) and #395 the 18th (check-handoff-contract, which
# asserts this very number). #384 is closed by #389, so
# check-alert-metrics no longer stays red on HighErrorRate (it is now a declared
# self-healing rule). The old expectation of "15 x rc=0 + 1 red" is superseded.
# If check-alert-metrics fails on NoOrdersCreated, that is the rebuild-blindness case:
# run `bash scripts/seed-order-metric.sh` (no FORCE). Rebuilding core-java destroys the
# Micrometer request counter, so EXPECT this after any rebuild — including a rebuild of
# another service that recreates core-java as a dependency.

# 3. The sign-in split, against the runtime the USER sees — §0.2 is about getting this wrong.
curl -s http://localhost:3000/auth/signin | grep -o 'Vendor sign in'          # expect a hit
curl -s http://localhost:3000/ | grep -o 'href="/shop/signin"'                # expect a hit
curl -s http://localhost:3000/shop/signin | grep -o 'Sign in to order'        # expect a hit
#    NOTE: every page embeds Next's "404: This page could not be found" notFound
#    boilerplate in its RSC payload. It is present on / and /track too — NOT a 404.

# 4. The mute, functionally — not just that the config contains it
bash scripts/check-alert-mute.sh          # expect PASS; M-6 proves muted->mute-null, control->email
docker logs jtoye-alertmanager 2>&1 | grep 'mute ACTIVE'

# 5. Runtime parity BY CONTENT (a 200 and a title are identical whether or not the code is current)
bash scripts/check-runtime-freshness.sh   # expect 4/4 FRESH, rc=0
docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -p /app/app.jar META-INF/MANIFEST.MF | grep -i Implementation-Version'
#    NODE 24 (merged in #402) — assert it in the RUNTIME, not in the Dockerfile:
docker exec jtoye-frontend   node --version    # expect v24.18.1  (NOT v20.20.2)
docker exec jtoye-mcp-server node --version    # expect v24.18.1
#    A rebuild that was only `start`ed keeps the OLD image id. Prove they match:
for s in frontend mcp-server; do
  c=$(docker inspect --format '{{.Image}}' jtoye-$s); t=$(docker images -q --no-trunc jtoye_oaas_2026-$s:latest)
  [ "$c" = "$t" ] && echo "$s MATCH" || echo "$s MISMATCH — container is not running the tagged image"
done

# 5b. Is the post-merge hook actually WIRED? A hook that never runs is indistinguishable
#     from a quiet one, so prove it rather than trusting the file's presence.
bash scripts/install-hooks.sh --check      # expect PASS (H-1/H-2; H-3 needs the dispatcher)
JTOYE_HOOK_VERBOSE=1 bash ~/.git-hooks/post-merge
#     expect: [post-merge] runtime parity: all built services match the tree
#     SILENCE HERE MEANS IT IS NOT RUNNING — not that everything is fine.
#     (A real `git merge` on main is blocked by the protected-branch guard, which is
#      why this invokes the dispatcher directly; git's own invocation of the
#      dispatcher was proven separately with a real merge in a worktree.)
#     If it ever reports drift:  bash scripts/sync-runtime.sh

# 6. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**If `check-runtime-freshness` or `check-container-config-drift` VOIDs (exit 2), check *where* you
ran it before you touch the stack.** Both VOID from a **worktree** even on a healthy stack: Compose
derives the project name from the directory, so a worktree queries an empty project namespace.
`COMPOSE_PROJECT_NAME` cannot fix it — there are two projects (`jtoye_oaas_2026` and `monitoring`).
**Run those two from the main checkout.** They fail closed, which is correct; do not go hunting for
a runtime fault that does not exist.

**Merged code is not running code.** `docker compose start`/`restart` neither builds nor replaces a
container holding an older image ID. After any merge that touches source:

```bash
docker compose -f docker-compose.full-stack.yml --env-file .env up -d --build <service>...
bash scripts/check-runtime-freshness.sh    # then PROVE it by content, per §0.2
```

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority and `-D` only where the forge proved the
merge. `gh pr merge --delete-branch` also cannot delete a branch a worktree holds — remove the
worktree first, from the main checkout.

---

## 5. Housekeeping session, 2026-07-31 ~08:40–11:25 BST

Merged the two open PRs, cleared every merged branch, restored the runtime and the monitoring the
merges disturbed, then closed the two documentation gaps the run exposed. **Five PRs merged, no
feature work.** The repo ends at `main` alone — one branch, one remote ref, one worktree.

### 5.1 What merged

- **#389** `fix/alert-metrics-self-healing` → `227e4e32`. Closes **#384**: `check-alert-metrics` now
  separates a *self-healing* empty selector from a *blind* one, so a healthy platform's empty 5xx
  counter is no longer a violation (§2.1).
- **#383** `feat/allergen-consent-notice` → `29e6132f`. Its `Operational Contracts` failure was
  **not** its own: the branch was six commits behind base and carried a stale `redis` horizon.
  Fixed by merging `main`, not by `--refresh` — the reasoning is in §2.3, which is the part of this
  document most likely to be needed again.

The merge collided on the test counts. Resolved by **regenerating, never arithmetic**: take `main`'s
text, `bash scripts/docs-freshness.sh --write`, then let `check-doc-metrics.sh` name each prose line
to edit. That produced **1872** = `main`'s 436 Jest blocks / 64 files + the 4 blocks in #383's new
test file. Guessing would have been wrong in both directions.

Then three documentation PRs, each closing a gap the previous one exposed:

- **#391** `1cde1f83` — this document. Five of its claims went false the moment #383 and #389
  merged, and **nothing gates this file**, so they would have survived until someone reread it.
- **#392** `97a985f1` — the changelog backfill (§5.5).
- **#393** `4674e3d1` — the changelog gate (§5.6), because #392 fixed the *instance* and nothing
  stopped the *recurrence*.
- **#394** `2e7156c1` — this document again, because §5.5 still called the changelog "the one thing
  left undone" after #392 and #393 had done and gated it. **Twice stale in one session.**
- **#395** `66c123bf` — the handoff gate (§5.6b), the answer to #394 happening at all.
- **#396** `ef81e1fa` — H-5's label/number contradiction, closing **#385** (§5.8).

**Eight PRs, and only the first two were feature work.** Every one after that existed because the
previous step exposed something nothing was watching. Worth reading as a chain rather than a list:
merging #383 made the runtime stale → the #387 hook caught it → rebuilding blinded an alert →
housekeeping found the handoff stale (#391) → which surfaced the changelog 24 PRs behind (#392) →
which had no gate (#393) → which made the handoff stale again (#394) → which had no gate (#395).

### 5.2 Branch and worktree cleanup — seven branches, all verified before deletion

`main` is now the **only** branch and the **only** worktree, locally and on the remote.

Ancestry is the wrong authority here (see the squash-merge note in §4). Two checks per branch,
**both shown able to return NO before five yeses were believed**:

1. remote tip `==` the PR's `headRefOid` → nothing was pushed after the merge;
2. a commit in `main` whose subject ends `(#NNN)` → the squash landed.

A first attempt — "does `main` still differ from this branch on the files it touched" — produced
**false negatives** and must not be reused: three merged branches flagged unsafe purely because a
*later* commit had touched the same files (`HANDOFF.md`, rewritten by #388, is the clearest case).
It measured "has anything changed since", not "did this land".

### 5.3 Runtime and monitoring restored — both were disturbed BY the merges

- **Frontend was stale the moment #383 merged.** The post-merge hook from #387 caught it
  (`[image-not-rebuilt]`), which is the first time that hook has paid for itself. Rebuilt and
  **recreated**; image id `fcee04bd` → `b09b1f2f`, and the Art. 9 copy read back out of the running
  container in both the SSR chunk and the client bundle. `check-runtime-freshness` 4/4 FRESH.
- **`NoOrdersCreated` went blind as a side effect.** Rebuilding the frontend recreated `core-java`
  as a dependency, which destroys the Micrometer request counter. `check-alert-metrics` correctly
  went rc=1 on `NoOrdersCreated`; `bash scripts/seed-order-metric.sh` restored it (series 0 → 1,
  `HTTP 201 ORD-00000000-20260731-DD8DA495`). **Expect this after any rebuild, including one that
  only names another service.** §4's gate expectation was updated accordingly.
- **Browser-verified**, not just bundle-verified: real Keycloak sign-in, the notice visible in the
  same dialog as the 14 allergen checkboxes and above them, 9/9 checks — with a break arm returning
  rc=1 and a closing clean arm returning rc=0.

### 5.4 State at hand-off

- **17 of 17 gates rc=0**, measured 11:25 BST. Better than this document previously claimed
  possible (`15 + 1 red`); the 17th is `check-changelog-contract`, added by #393.
- Go: `gofmt` clean, `vet` clean, `mod tidy` no drift, `test -race -count=1` all five packages
  fresh (not `(cached)`), 0 data races. Frontend: `npm run lint` 0 errors / 28 warnings (baseline),
  `npm run build` rc=0 with TypeScript clean.
- Runtime: `check-runtime-freshness` rc=0, 4/4 FRESH. Stack UP, 17 jtoye containers, 15 healthy
  (the other two define no healthcheck — §3). Dev DB `jtoye`: 28 orders.
- **No open issue from this session remains.** **#384 is CLOSED** (by #389) and **#385 is CLOSED**
  (by #396). 59 open issues repo-wide.
- **3 open PRs, all Dependabot, none mine** — opened 15:24–15:28 while this document was being
  written (see §5.10). **#398 is a MAJOR bump (node 20-alpine → 25-alpine in `/frontend`)** and
  wants real evaluation, not a rubber stamp.
- **Toolchain: 4 of 7 applied, 3 + 1 UNKNOWN outstanding** (§5.9). Applied and verified on a clean
  login shell: node v22.23.1→**v22.23.2**, npm 12.0.1→**12.0.2**, gemini-cli 0.52→**0.53**,
  copilot 1.0.75→**1.0.77**. Held or handed over: conda (documented upstream block),
  docker-ce (root, and the daemon restart drops this stack), ms-fabric-cli (per-env promotion),
  and `antigravity` **UNKNOWN** — a manual channel with no probe, unanswerable rather than clean.

### 5.5 The changelog backfill — #392

**Superseded the "one thing left undone" that stood here.** `docs/CHANGELOG.md` had no entry since
#363 (`ccb15e23`) and **nothing read it**: the four doc gates open CLAUDE.md, AGENTS.md, README.md,
the `.planning/codebase` docs, `k8s/DEPLOYMENT.md` and `terminal-states.yaml` — and not it. So it
drifted **24 PRs deep** with every one of those gates green throughout.

Nine feat/fix PRs were missing (#368, #370, #376, #380, #381, #382, #383, #387, #389), written as
**six** entries — grouped where the work is genuinely one thread, matching the file's existing style
(`(#342, #346, #347)`). The three alert PRs are the clearest case for grouping: #370 found
`NoOrdersCreated` blind after every rebuild, #376 found that #370's own seed script *could not clear
the alert* (it asked "does the series exist" while the rule asks `increase[30m] < 1`), and #389
found #384's premise wrong — `HighErrorRate` fires on a high *ratio*, so an empty numerator is
correct silence. Each fix revealed the previous one had measured the wrong thing; split apart it
reads as three unrelated tweaks.

Every entry was written **from the merged commit's own body**, carrying across the measured numbers
and break arms rather than re-deriving them.

**The verification limit, stated because it is easy to miss:** the four doc gates were rc=0, but
none of them reads `docs/CHANGELOG.md`, so those greens proved only that nothing *else* broke. The
entries' claims were checked against the tree instead — 16 assertions. The one that appeared to fail
was the *check*, not the entry: `customerAllergenMask` still grep-matched in `GuestOrderRequest.java`
because the only hit is the **tombstone comment documenting its removal** — a rule firing on its own
definition. Excluding comments: 0 fields, 0 accessors, 0 in the OpenAPI snapshot, with a control on
the live `idempotencyKey` returning 3 to prove the filter could still find something.

### 5.6 The changelog gate — #393. It shipped with the bug it exists to catch, twice

#392 fixed the instance; nothing stopped the recurrence. `scripts/check-changelog-contract.sh` now
runs in `docs-freshness.yml`:

- **C-1** every feat/fix commit merged after `FLOOR` is cited in an entry **heading** by `(#NNN)`
- **C-2** `EXEMPT` rows retire themselves — a stale one FAILS (`KNOWN_DATALESS`'s mechanism)
- **C-3 / C-4** self-tests that the matcher and the citation lookup can each both fire *and* decline

It reads **merged history** (`origin/HEAD`, resolved not hardcoded), never `HEAD`: branch-local
commits carry no PR number, so ending at `HEAD` would VOID on nearly every feature PR and train
people to ignore it. The consequence is deliberate — **a PR that forgets its entry goes red on the
push-to-main run immediately after it merges.**

**Both defects in this gate were found by running the fail direction, not by anything going red:**

1. **It passed while checking nothing.** The subject regex is anchored `^`, but the scan runs over
   `git log --format='%h%x09%s'` lines beginning with the SHA and a TAB. First run:
   `26 in range, 0 feat/fix` → **PASS**. C-3 passed alongside it because it tested a **bare
   subject** — an input shape the gate never sees. Caught only because the printed count looked
   wrong. Fixed by re-anchoring past the SHA field *and* rebuilding every C-3 sample in the real
   TAB-bearing shape.
2. **An incidental mention satisfied it.** The first break arm — delete #380 from its heading —
   **passed**, because an unrelated entry's prose says "#380 merged, changing core-java sources".
   Citations now count only in entry headings; the arm then failed correctly, with a control
   confirming a whole-file search would still have passed.

Falsified across **nine arms** with opening *and* closing clean arms, every restore verified by
content: lost citation → 1, stale exemption → 1, four VOID conditions → 2, reintroduced regex bug
→ 2 (C-3 catches it *before* scanning), honoured exemption → 0.

**`FLOOR` is `ccb15e23`, not the root commit,** and the config records why: older entries cite the
ISSUE, not the PR — the top entry cites #362 while the commit that merged it was #363, which appears
nowhere (`grep -c '(#363)'` → 0, `'(#362)'` → 1). Extending backwards would report historically
CORRECT entries as drift, and a gate that cries wolf gets `|| true` appended to it.

**Proven end-to-end after merge**, which is the only test that mattered: the gate discovered its own
squash commit, 9 → **10 feat/fix, 10 cited, rc=0**. It was not red on arrival because #393 carries
its own entry, verified *forward* against the exact subject line the squash would produce before the
PR number existed.

**Two limits worth keeping visible.** It checks **citation, not quality** — a heading with the right
number satisfies it, and it cannot tell a real write-up from a stub. And everything before `FLOOR`
remains ungated.

### 5.7 Left undone

- **Toolchain: the 3 that need your hands** (§5.9). `conda` is HELD by an upstream bug — do not
  escalate to `--force-reinstall`, 34 envs sit on that base. `docker-ce` needs root **and restarts
  the daemon**, which drops all 17 containers, so do it with the stack down:
  `sudo apt install docker-ce docker-ce-cli containerd.io`. `ms-fabric-cli` 1.2.0→1.6.1 needs
  clone-the-env → run a real sigantry/fabric command → promote only on green. `antigravity` stays
  UNKNOWN until someone reads the product version by hand.
- ~~#402 HELD on an unattributed E2E delta~~ — **DONE. #402 is MERGED and RUNNING** (§5.11). The
  delta was flake; the hold is lifted and node 24 is proven inside the containers.
- ~~The 26-failure Playwright baseline~~ — **filed as #404**, which is **OPEN** and is still the top
  standing item. Live count **23**, not 26 or 27: 5 of them were the streaming-buffer locator
  artefact and are fixed by **#406** (§5.14). ⚠ **#404's own section (a) is wrong** — no duplicate
  nav, no a11y defect — corrected in-issue and in §5.14. **What stands is the real finding: CI runs
  `e2e/public-layout.spec.ts` only — 2 of 128 tests.** The remaining 23 are unexamined; 17 of them
  are `storefront-flows.spec.ts`, and nobody has looked at why.
- ~~#399 and #400 untriaged~~ — **both MERGED** (§5.12), #400 verified SHA-against-tag with its only
  breaking change (node24 action runtime) shown satisfied.
- ~~The `metric-seed` delta is unexplained~~ — **explained** (§5.12): the E2E suite writes real
  orders. Still true that you should re-measure rather than carry a number forward.
- **Toolchain: still the 3 that need your hands** (§5.9) — unchanged this session.
- **`.planning/codebase/STACK.md:22` says `Go 1.22 runtime`** while `STACK.md:189` and CLAUDE.md say
  **Go 1.26**. Found while fixing the node stragglers (#405), deliberately left — same defect class,
  different dependency, out of scope for a node bump. Nothing gates it.

### 5.8 #385 closed — and the fix that made the fix worth having (#396)

`H-5` printed `pin-not-at-site=$n_drift`, but `$n_drift` counts DRIFT (pin on **no** line) while
the label names what LINE_DRIFT measures (pin exists, wrong line). **No break arm was needed**: on
clean `main` @ `66c123bf` the gate printed **three NOTEs naming site drift immediately above
`pin-not-at-site=0`**. Each accumulator now prints under its own label.

**Fixing the label alone would have been worse than useless, and this is the part to remember.**
The NOTE says "run `--refresh`"; `--refresh` reported `0 field(s) rewritten`. Its rewrite branch
carried `and len(want_sites[cur]) == 1` and only matched the **inline** list form, so it silently
skipped every multi-site row and every block-form list — and the manifest holds 2 block rows plus
1 inline row with 2 sites, **exactly the three that were stale**. An honest `pin-not-at-site=3`
that nobody could clear is how an advisory earns an `|| true`. Both forms now rewrite at any
length; `ollama` 435→449 and 460→510, `go-ci-setup` 667→686 were corrected (`ci-cd.yaml:52` was
already right and left alone).

**A note on the issue's own acceptance criteria.** It asked to "confirm the clean tree still
reports 0". It does — but only because the three genuinely-stale rows were fixed first. Taken
literally against a dirty tree, that criterion pushes toward making the number *say* 0 by some
other means, re-hiding exactly what the fix exposes. The planted-line arm carries the weight:
**0 only means something once it is a state the number can leave.**

Four arms, opening and closing clean arm, restores verified by content:

| arm | `pin-not-at-site` | `site-unresolvable` | rc |
|---|---|---|---|
| clean (opening) | 0 | 0 | 0 |
| plant a **line** drift | **1** | 0 | 0 |
| plant an **unresolvable** pin | 0 | **1** | 2 |
| plant **both** | **1** | **1** | 2 |
| clean (closing) | 0 | 0 | 0 |

### 5.9 Toolchain converged — 4 of 7, and two traps found doing it

Applied via `~/dotfiles/toolchain/update.sh --tool <t> --apply`, each verified on a **clean login
shell** rather than the calling one. Recorded in `toolchain.lock` and merged as **dotfiles #52**
(`5aba46e`). Drift **7 → 3**, plus 1 UNKNOWN.

| tool | before → after |
|---|---|
| node | v22.23.1 → **v22.23.2** |
| npm | 12.0.1 → **12.0.2** |
| `@google/gemini-cli` | 0.52.0 → **0.53.0** |
| `@github/copilot` | 1.0.75 → **1.0.77** |

All 9 npm globals survived the node move, including `@anthropic-ai/claude-code` and the GSD SDK.

**TRAP 1 — order matters, and the tier listing invites the wrong one.** npm was upgraded to 12.0.2,
then the node step **silently reverted it to 10.9.8**: `nvm install --reinstall-packages-from`
installs the npm *bundled* with the new node. Nothing errored; only re-running `doctor.sh` caught
it. **Upgrade npm AFTER node.** Re-applied and re-verified at 12.0.2 on a login shell.

**TRAP 2 — the received GSD-hook rule is wrong, and the truth is quieter.** The rule says "any node
upgrade silently kills every GSD hook". It does not: **nvm keeps old versions**, so all 6 hooks
pinned to `.../v22.23.1/bin/node` kept working — on a **stale interpreter**, which nothing reports
and which `doctor.sh` does not look at. The outage arrives only on `nvm uninstall`, a pruning
cleanup, or a **fresh machine**, where the dotfiles backup installs 6 hooks pointing at a node that
was never there. Re-pinned **surgically** (backup → `jq` validate → sed → `jq` validate → prove the
new binary *executes* → `sync-claude`), **not** by re-running the GSD installer, which wipes the
local `execute-phase.md`/`execute-plan.md` customizations. The memory note was corrected.

**Not applied, each for a reason, not an oversight:** `conda` is HELD since 2026-07-26 by an
upstream self-update bug (34 envs sit on that base — do not force it); `docker-ce` needs root and
restarts the daemon; `ms-fabric-cli` needs a per-env clone-test-promote; `antigravity` is a manual
tarball whose `package.json` version is the VS Code base, not the product.

**On the CI signal for dotfiles #52:** "0 failing checks" was nearly vacuous — only GitGuardian can
run, because `verify.yml` is **dormant by design** (private repo, Actions billing failing since
2026-07-26). The real guard is the local `.githooks/pre-push` selftest, run explicitly: `gates/
selftest.sh` (engine can fail), `sync-claude.sh --check` (live matches repo), `gates/install.sh
--check-all`. rc=0.

### 5.10 Three Dependabot PRs opened mid-session — and the gate that will NOT catch this

Opened 15:24–15:28 on 2026-07-31, all `app/dependabot`, none from a human session:

- **#398 — `node` 20-alpine → 25-alpine in `/frontend`. A MAJOR bump.** Two LTS generations, under
  Next.js 16. Do not merge on green CI alone; the frontend image is what serves the vendor
  dashboard, and §0.2 of this document is entirely about proving a runtime rather than assuming it.
- **#399** — `github/codeql-action` 4 → 4.37.3 (minor-and-patch group).
- **#400** — `docker/login-action` 3.7.0 → 4.5.2. Also a major.

**This is a worked example of the handoff gate's stated limit.** When these opened, this document
said "Open PRs: **none**" and `check-handoff-contract` stayed **green**: H-3 saw 0 commits behind
(nothing had merged), and H-2 only checks claims written as `#NNN … CLOSED/OPEN` in CAPS — "none"
is not one. The gate never claimed to catch semantic rot; this is what that looks like in practice.
**Re-read the table, do not trust its silence.**

### 5.11 node 20 → 24 (#402) — RESOLVED: the +3 was flake, and the hold is lifted

**#398 (node 25) is CLOSED.** Node 25 is an odd-numbered *Current* line, `lts=false`, EOL
**2026-06-01** — dead before that PR opened. It would have swapped one EOL runtime for another and
lost LTS status. It also moved only 2 of the 4 sites, leaving `mcp-server` on 20, which one manifest
pin cannot describe.

**#402 replaced it with node 24 LTS** (EOL 2028-04-30) in one coherent change: 4 Dockerfile `FROM`
lines, 4 `node-version` steps in `ci-cd.yaml`, the horizon row, and the "Node.js 20+" claim in
CLAUDE.md / AGENTS.md / README.md. The `DEFERRED-27` exemption is **removed, not updated** — node 24
is not past horizon, and the gate FAILS an exemption on a non-breaching row.

#### The blocker is cleared — by name, not by count

The hold was a "+3 E2E regression" (26 on node 20 → 29 on node 24). **It does not reproduce, and it
was never a regression.** Both arms were re-run against the same live stack on `:3000`, swapping only
the frontend image:

| arm | failed | passed | skipped | duration |
|---|---|---|---|---|
| node 20 — `b09b1f2f`, control | **27** | 87 | 14 | 254s |
| node 24 — `34dd4dca` | **27** | 87 | 14 | 254s |

Identical. And the *sets* churn symmetrically — 3 fail only on 24, **3 fail only on 20**, 24 on both.
The second number is the falsifier, and it is the criterion this section originally specified: a
node-24 regression cannot make a test fail on **node 20** and pass on node 24.

Confirmatory re-runs, 3x per stack. The rule was *consistent fail on 24 + consistent pass on 20*;
neither conjunct holds. `Products … 390px (desktop)` failed in the full node-24 arm and then passed
**3/3** on re-run.

| candidate | node 24 | node 20 |
|---|---|---|
| `Customers (/dashboard/customers) … 390px` — mobile | F F P | F P P |
| `Order detail (/dashboard/orders/order-1) … 390px` — mobile | F P P | F P P |
| `Products (/dashboard/products) … 390px` — desktop | **P P P** | P P P |

**Mechanism:** failures land on the **first** repeat and pass afterwards, on *both* runtimes — a
cold-start effect. That is also why the failing set churns while the total holds at ~27: whichever
test hits a cold path first absorbs the failure.

**The control moved too, and this is the durable lesson.** The earlier session measured 26 failures
on the same unchanged tree and the same node-20 image; the re-run measured 27. The baseline is not
stable to ±1, so a ±3 count delta was never separable from noise. **Compare by test name. Never by
count.** Had only the node-24 arm been run, 29 would have read as a node-24 catastrophe.

Evidence recorded on the PR itself (comment on #402), so it survives this document.

#### Two method fixes worth carrying

- The extractor in the original resume block, `.suites[].suites[]?.specs[]?`, is **fixed-depth** and
  can silently miss a nesting level — returning fewer failures than exist and reading as improvement.
  Replaced with a recursive walk, and **validated against `stats` before being trusted**: 128 rows
  extracted vs `expected+unexpected+skipped` = 128, and 27 FAIL rows vs `unexpected` = 27.
- `test-results/` was confirmed to contain only the current run's artifacts (21 dirs, 0 older than
  the run start) before anything was inferred from it. Playwright clears the directory at start, but
  that is worth asserting rather than assuming — reading a stale artifact directory is a known trap.

#### The 27-failure baseline is now **issue #404**

A standing problem no gate reports: CI's `Frontend E2E (public surfaces)` job runs
`npx playwright test e2e/public-layout.spec.ts` — **one spec file of thirteen, two tests of 128.**
The other 126 are unwatched. #404 records the two failure classes: a deterministic duplicate
`mobile-tab-bar` nav landmark (5 tests, and an a11y defect in its own right — two `<nav>` elements
sharing `aria-label="Primary"`, mechanism not yet established since source has exactly one render
site), and the cold-start class above.

### 5.12 The merge session — 5 PRs, and two defects found by looking rather than by a gate

Everything in §5.11 was evidence; this is what shipped on it. **All four outstanding PRs merged, in
this order:** #402 (node 24), #400, #399 (dependabot), #405 (the doc straggler #402 left behind).

#### #400 was the one that deserved real scrutiny, and survived it

`docker/login-action` 3.7.0 → 4.5.2 is a **major**, SHA-pinned, and it is **not** a no-op: the
`build-and-push` job runs on every push to main (`if: github.event_name == 'push' || … 'release'`)
and pushes images to GHCR. Cleared on four checks, none of them "CI is green":

1. the PR's SHA `371161bb…` **verifies against tag `v4.5.2`** via the git-ref API (supply chain);
2. all **7 inputs are unchanged** between the pinned v3 and v4.5.2 — the three used here
   (`registry`, `username`, `password`) among them;
3. the **only** breaking change is `runs.using: node20 → node24`, needing Actions Runner ≥ 2.327.1;
4. this repo has **0 self-hosted runners** and every `runs-on:` is `ubuntu-latest`, so (3) is
   satisfied by construction.

#399 (`codeql-action` `@v4` → `@v4.37.3`) is a tightening from a floating major to an exact pin —
low risk, but note it trades auto-patching for a PR per patch.

#### A merge policy call worth knowing about

Each merge puts the others behind base, and this pipeline takes **~45 min** per cycle
(`Integration Tests (Testcontainers RLS)` alone is ~41 of it). Strictly re-validating all four would
have cost ~2.5 h of waiting. The base-freshness rule exists because *"a branch behind its base ships
a runtime missing already-merged work"* — and **none of #399/#400/#405/#403 has a runtime**.

So base re-validation was kept for the runtime-affecting PR and skipped for the CI/docs-only ones,
after checking the thing the rule actually protects: **line-level disjointness**. #399 touches
`ci-cd.yaml` ~625 and ~832, #400 line ~742, #402 lines 55/226/466/668 — no overlap anywhere.

**And then it was verified rather than assumed.** After the behind-base merges, `origin/main` was
read back: `node-version: '24'` ×4 and ×0 of `'20'`, login-action v4.5.2 sha ×1 and the old v3 sha
×0, `upload-sarif@v4.37.3` ×2, STACK.md `Node.js 24+` ×2, colon-form `20+` ×0. Six assertions, all
as expected. That read-back is the part that makes the shortcut defensible.

#### #405 — #402 updated a generated file and not its source

`CLAUDE.md` and `AGENTS.md` carry `<!-- GSD:stack-start source:codebase/STACK.md -->`. #402 changed
`Node.js 20+` → `24+` **inside that generated block** while leaving
`.planning/codebase/STACK.md` at `20+`. **The next `map-codebase` regeneration would have silently
reverted the node-24 documentation**, with all 18 gates green throughout — `check-doc-versions` does
not assert this claim.

It also missed a third instance because it is **spelled differently**: `- Node.js: 20+`, the *colon*
form, in the container-versions list at `CLAUDE.md:139` / `AGENTS.md:138`. That exact string is in
neither STACK.md nor anywhere else. A grep for the string you edited does not find the one you didn't.

> **My own search manufactured a false negative while finding this.** `git show <sha> -- CLAUDE.md |
> grep -E '^[+-][^+-]'` returned **empty**, and I nearly concluded #402 had not touched the docs at
> all. The diff lines are markdown bullets, so they read `-- Node.js: 20+` — second character `-`,
> which that filter excludes by construction. Same family as the `head`-truncation trap in
> CLAUDE.md: **a filter used to prove absence produced the absence.** `--stat` disagreed with it,
> which is what exposed it.

#### The dev-DB "unexplained delta" is explained

**The E2E suite writes real orders into the dev DB** — one per storefront checkout test, with
customer email `email-<epoch_ms>@test.com`. Decoding those timestamps places them inside the arms:
`1785518634469` → 18:23:54, within the node-24 run. 8 orders landed in a single hour spanning the
two arms. 28 → 37 is E2E runs plus one `seed-order-metric` order, not a mystery.

It also reconciles the confusing part of the old row: `metric-seed%` **customers** reads 0 while
three `metric-seed@jtoye.local` **orders** exist, because those are **guest** orders — the email
lives on the order and no `customers` row is created.

#### Runtime, proven

`sync-runtime.sh` rebuilt `frontend` + `mcp-server` (the two the post-merge hook named) and
re-asserted with the same gate: 4/4 FRESH. Then proven by content, which is the part that matters:
`node --version` inside **both** running containers is **`v24.18.1`** on alpine 3.24.1, core-java is
untouched at Java 21 / `2.3.0`, and each container's image ID equals its tag's — so it is a rebuild,
not a restart.

Rebuilding recreated `core-java` as a dependency and blinded the Micrometer counter exactly as §4
predicts; `bash scripts/seed-order-metric.sh` (no `FORCE`) restored it, series 0 → 1. **18 of 18
gates rc=0** afterwards.

### 5.13 `check-handoff-contract` H-3 deadlocked itself — and it did so at the worst moment

**Found by this document failing to merge.** `docs-freshness` is a **required** check, and it went
red on `main` at `2b5339f8` and on #403 simultaneously. Both for the same line.

H-3 computed `LAST_TOUCH` from **`BASE_REF`**:

```sh
LAST_TOUCH=$(git log -1 --format=%H "$BASE_REF" -- "$DOC")   # <- the defect
```

That asks *"how far has the base moved since **the base** last touched HANDOFF.md"* — **a question
no pull request can change**, because the PR's commit is not on the base until it merges. So the
moment `main` exceeded `MAX_PRS_BEHIND` (3), every PR went red, **including the handoff update that
was the only thing capable of clearing it.** The more overdue the handoff, the more unmergeable its
own fix became.

This is a nastier shape than a gate that cries wolf: it is a gate whose remedy it forbids. The four
node-24 merges (#402, #400, #399, #405) pushed main to 4-behind and tripped it.

**The fix is to ask the question that was meant** — *is the copy I am looking at stale?*

```sh
LAST_TOUCH=$(git log -1 --format=%H HEAD -- "$DOC")
```

A change that updates the handoff gets credit for it; one that does not, does not. **On-main
semantics are unchanged by construction**: on a push `HEAD == BASE_REF`, so it resolves to the same
commit and a genuinely stale main still fails.

Falsified in four directions rather than observed passing once — the middle two are the ones that
prove the fix did not simply remove the gate's teeth:

| arm | expected | result |
|---|---|---|
| branch updates the doc, up to date | pass | **rc=0**, 0 behind |
| **stale main, doc not updated** | **fail** | **rc=1**, 4 behind — teeth intact |
| **branch updates the doc but is BEHIND base** | **fail** | **rc=1**, 4 behind — behind-base signal intact |
| closing clean arm | pass | rc=0 |

Arm 3 is worth keeping in mind: updating the handoff must never excuse being behind base, and it
does not — the base's newer commits are simply not reachable from `LAST_TOUCH`. Its failure message
also improved, now naming the branch's own touching commit rather than an unrelated one on main.

**The general lesson, which is the same one §5.11 taught in a different costume:** a check can be
correct about the world and still ask a question whose answer nobody can act on. "Is it true?" and
"can anything make it true?" are different questions, and only the second one tells you whether the
gate is usable.

### 5.14 The "duplicate mobile-tab-bar" was React's streaming buffer — and #404's a11y claim was wrong

**#404 said the dashboard renders two `<nav>` landmarks sharing `aria-label="Primary"` and called it
an accessibility defect. That was wrong, and I filed it.** It is corrected in the issue (banner +
comment) and fixed in **#406**, which is **MERGED**.

**What is actually happening.** While React is still streaming, a suspended segment's HTML is parked
in a staging buffer appended to `<body>` as `<div hidden id="S:0">` — and that buffer holds a
**complete duplicate of the dashboard shell**. A raw `getByTestId` matches the live copy *and* the
staged one, so Playwright strict mode fails with *"resolved to 2 elements"*.

| observation | value |
|---|---|
| strict-mode failures, whole file in one run | **8** |
| the same tests run in isolation (`-g`) | **0** — the stream completes first under light load |
| at every failure | **`byTestId=2` but `byRole=1`** |
| the second element's parent chain | `body > div#S:0 > div.flex.h-screen > nav` |

`byRole=1` is the measurement that disproves the a11y claim: the staged copy sits inside `[hidden]`,
so assistive tech never sees it. **Exactly one Primary landmark is exposed, which is correct.**

**Why the wrong conclusion was reached, because the mistake is reusable.** I read the strict-mode
error text — two identical `<nav aria-label="Primary">` — and inferred an accessibility consequence
from the *symptom*, while in the same breath recording the *mechanism* as "not established". The
consequence depended on the mechanism. **One `getByRole` call, which was one line away, would have
said 1 immediately.** Declining to guess the mechanism was right; asserting its consequence anyway
was not.

It also explains why it looked deterministic: it is **load-dependent**, so it appears in a full run
and vanishes under a targeted one — the opposite of the timing class §5.11 contrasted it with.

**The fix, and why it is not `.first()`.** Query the tab bar by ROLE, and route every other
shell-scoped query through a `live()` root (`body > div:not([hidden])`) that excludes the staging
buffers. `.first()` would silence strict mode while letting the assertion bind to the hidden staged
copy — the class of change that keeps passing through a real regression.

**Fixing only the tab bar moved the failure rather than removing it:** violations went **8 → 7**, and
all 7 survivors were `getByText('OaaS Platform')`, the sidebar subtitle, because the buffer
duplicates the *whole* shell. That intermediate measurement is why the fix is general.

`e2e/unsubscribe-flow.spec.ts:57` is deliberately left on `getByTestId(...).toHaveCount(0)`: that is
an **absence** assertion, and a role query would be strictly *weaker* (it would pass if dashboard
chrome leaked onto the public page but were hidden).

**Result:** `dashboard-mobile.spec.ts` **5 → 0** failures; whole suite **27 → 23**. Treat 23 as
indicative, not a clean baseline — that run executed while a concurrent Testcontainers workload was
on the machine, and this suite is demonstrably load-sensitive.

**A break arm that did not break.** ARM2's first attempt reported "assertion did not fail", which
read as *the assertion cannot fail*. It was the **arm** that was broken: the mutation set
`display` on the `<span>` while an ancestor stayed `display:none`, so nothing actually became
visible. Redone as ARM2b, verifying the element measured **208×16** before trusting the result — and
then the assertion failed correctly. **A break arm must be shown to have actually broken something**,
or its "pass" means nothing.

| arm | result |
|---|---|
| clean, before | 8 failed / 8 strict-mode violations |
| clean, after | **26 passed, 0 violations — run twice** |
| ARM1 remove the nav's `aria-label` | assertion **FAILS** as required |
| ARM2 first attempt | did **NOT** fail — the arm was broken, not the assertion |
| ARM2b force it genuinely visible (208×16) | assertion **FAILS** as required |
| closing clean arm | 26 passed, 0 violations |
