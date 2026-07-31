# Handoff: the sign-in split shipped — and the user caught it not running

**Generated:** 2026-07-31 ~01:00 BST. Supersedes the "every open item is closed except three"
handoff, which was accurate when written. Its §0.1 (concurrent session), §3.1 (`seed-order-metric`
`FORCE=1`) and §3.2 (`RedisDown`) are still true and are carried forward below in compressed form;
its §2 and §4 are history and are not repeated.

| | |
|---|---|
| `JToye_OaaS_2026` | **2 PRs merged this session:** #381, #382. **2 issues opened:** #384, #385. HEAD deliberately **not** quoted — see the note below |
| Open PRs | **#383** `feat/allergen-consent-notice` — the concurrent session's, not mine |
| Open issues | **60** |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck (§3). **0 active alerts, 0 silences** |
| Gates | **15 of 16 rc=0.** `check-alert-metrics` is **rc=1 on `HighErrorRate`** — a real gap, now **#384**, NOT a regression |
| Runtime proof | 4/4 built services FRESH · `Implementation-Version: 2.3.0` read from inside the running `app.jar` · `ReservedSlugException` present inside that jar |
| Project version | **2.3.0** (`build.gradle.kts:15`). No `v2.3` tag |
| Test baseline | `docs/metrics.json` **1868** (was 1851) — java 1264, jest 436, schema V60 |
| Dev DB | **25 orders, 3 `metric-seed@jtoye.local`** — one is mine (23:52), two predate this session |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §4 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout — still true

`/home/sanmi/IdeaProjects/JToye_OaaS_2026` is driven by another session. Right now it holds a
worktree at `.../wt-notice` on `feat/allergen-consent-notice` (**PR #383, open**).

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

### 0.3 My own instruments were wrong four more times

The previous handoff logged seven. This session added four, and **none was caught by something
going red**:

| what I measured with | what it actually did |
|---|---|
| a `perl -0pi -e` regex to plant a break arm | **Never matched.** `grep -c BREAK-ARM` returned `0` and the suite reported *"17 passed"*. Had I read that as a pass I would have published a **fabricated** falsification. Redone by deleting the guard lines **by number**, with the plant asserted *before* the run |
| `grep` for test **method names** in a Gradle XML report | Reports record `@DisplayName`, not method names. Returned `0` for all five new tests, which had in fact run |
| `grep` for class names in a CI **job log** to see what ran | Gradle does not name passing classes. Found 21 of the **104** that actually ran; I briefly concluded `RlsContractTest` had not run. The **artifact** is the authoritative record |
| Playwright `isVisible()` as an assertion | It **samples**, it does not wait. Two false FAILs during hydration; the served HTML had the heading all along |

Plus one in the work itself: **an acceptance criterion that could not fail.** `check-alert-mute`'s
M-2 ran per-matcher *after* M-1 with a `continue` between them, and in Alertmanager's `matchers:`
list form a forbidden label is always its own entry — so M-1 rejected it first and M-2 was
**never reached**. Fixed by scanning the whole matcher set first. Both now fire.

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

---

## 2. Open items

### 2.1 `check-alert-metrics` is rc=1 — this is #384, not a regression

`HighErrorRate` matches **zero series**: `http_server_requests_seconds_count{status=~"5.."}` is a
Micrometer **request** counter, destroyed on every core-java restart. This project mandates
rebuilding after any code change, so **the alert that detects "the platform is erroring" is blind
after every rebuild.**

Identical mechanism to `NoOrdersCreated`, but with **none of the remedy**: no seed script, no
`KNOWN_DATALESS` entry (the array is empty — the exemption was *correctly* retired in #370), and a
runbook section that says nothing about the blind state.

> **Do not make this green by serving a 5xx.** That manufactures the exact signal the gate exists
> to detect. It was left red deliberately. #384 lists four options and requires whichever is chosen
> to be shown to **fail** first.

### 2.2 `H-5` reports a number that does not measure its label — #385

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

- **JToye:** `main`, clean. Another session holds `feat/allergen-consent-notice` (#383).
- **Live stack:** 17 jtoye containers. **15 healthy; `jtoye-redis-exporter` and
  `jtoye-postgres-exporter` report no health status because they define no healthcheck** (their
  images are scratch-based — see the comment in `infra/monitoring/docker-compose.monitoring.yml`).
  That is **not** unhealthy: `check-alert-liveness` L-1 asserts every scrape target is `up`, and it
  passes.
- **Alertmanager:** the mute is ACTIVE locally via `.env`
  (`ALERTMANAGER_MUTE_ALERTNAMES=NoOrdersCreated`). `.env` is gitignored, so **a fresh clone is loud
  by default** — which is intended.
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
# EXPECT 15 x rc=0, and check-alert-metrics rc=1 on HighErrorRate (issue #384).
# If check-alert-metrics fails on NoOrdersCreated instead, that is the rebuild-blindness
# case, not #384: run `bash scripts/seed-order-metric.sh` (no FORCE).

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
