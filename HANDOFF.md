# Handoff: the audit that found the bugs is itself invisible to the repo

**Generated:** 2026-08-02 ~20:35 BST. Supersedes #430, which was accurate for the roadmap-blindness
session that produced it. Its §0.1 (two threads with no ticket) and §2.4 (the four blocking
decisions) are **still live** and are carried forward here in §4 — this document does not discard them.

> **§0.1 is the point of this document.** A full QA council ran on 2026-08-02 and found
> **3 Critical / 10 High / 9 Medium / 13 Low** across Phases 22–27. Three findings have been fixed.
> Everything else lives only in `.qa-council/disc-20260802-121732/`, which is **GITIGNORED**. It is now
> all filed (#438–#454), but the *evidence* still exists in one untracked directory. The previous
> handoff's lesson was that a roadmap review cannot see what has no ticket; this is the same lesson one
> layer down — a *repo* review cannot see what is not in the repo.
>
> **§2.4 is the newer and, for the product, larger story.** Twelve findings from the owner **using the
> running app** — filed as #457–#463. Four of them had a *different mechanism* than the symptom
> suggested, and one (#460, no concept of locality) is a missing subsystem the business model already
> assumes. **No audit in this repo found any of them.** Every process here was green while a signed-in
> customer could not tell they were signed in and no order took a payment.

| | |
|---|---|
| `JToye_OaaS_2026` | **5 PRs merged by this session: #434, #435, #436, #443, #455.** A concurrent session merged **#437** and **#456**. HEAD deliberately **not** quoted |
| Open PRs | **none** (measured, not assumed) |
| Open issues | **87** — was 63; filed **23** council issues (#438–#454) + **7** owner-reported (#457–#463). Since: #465 + #467 filed, #457 + #465 closed by PR #466 |
| Milestone | **v2.3 is OPEN and spans Phases 21–32.** Owner ruling stands — see §4. Do **not** run `/gsd-complete-milestone` |
| Live stack | Compose UP, **16** jtoye containers = 11 full-stack + 5 monitoring; **14 report healthy** |
| Gates | **18 green, 1 VOID** of 19 — `check-e2e-skip-budget` is **rc=2 and correctly so**: #456 added `frontend/e2e/marketing-dish-scroller.spec.ts` and the stored Playwright report predates it, so the gate refuses to certify a skip set that may no longer exist. **A VOID is not a pass.** Remedy: re-run the suite (§3). Not run here — it needs ~20 min against the stack the concurrent session is using |
| Test baseline | `docs/metrics.json` **1943** as of PR #466 (was 1930, and 1927 an hour before that). **Re-measure it; do not carry this number forward** — it has moved three times in a day |
| Runtime | 4/4 built services FRESH, re-asserted after the concurrent session's merges |

> ⚠ **A second session drives this same checkout.** Not a worktree — the same working tree. A `git
> checkout` here moves *their* HEAD, and `main` moved four times while this document was being written.
> **Re-measure every number below before repeating it**; §2.4's first entry is what happens when you
> don't.

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §6 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 The QA council's findings are not in the repository

`/qa-council` run **`disc-20260802-121732`** audited the six phases shipped since the 2026-07-14 run
(22 comms, 23 vendor-scoped access, 24 CoW media, 25 mutating MCP, 26 k8s overlay, 27 ops + the
RabbitMQ 3.12→4.3 replacement). It is the first full council since then.

**Where it lives:** `.qa-council/disc-20260802-121732/` — `findings.json`, `plan.md`,
`QA-COUNCIL-REPORT.md`, and per-lane evidence under `evidence/`. **`.qa-council/` is in
`.gitignore`.** One `rm` destroys it, and no clone has ever contained it.

**`.qa-council/LATEST` still points at `disc-20260714-162412`** — the July run. It will not lead you
to the August one. Read the directory listing, not the pointer.

| | |
|---|---|
| Fixed | **F-C1 + F-H1** cross-tenant write BOLA + list leak (#433 MERGED) · **F-M1** optimistic-lock 500 (#434 MERGED) |
| Group A remainder — **all FILED 2026-08-02**, clustered by root cause as the council adjudicated | **#444** F-H4 webhook delivery log (missing tenant GUC) · **#445** F-H3 raw-image endpoints bypass the Phase-24 pipeline · **#446** F-M3 hand-rolled dish modal · **#447** F-H8/F-H9 SEO · **#448** F-M5/F-L1 ProblemDetail · **#449** F-M8 17 docs-broken · **#450** the small-broken copy set · **#451** F-M4 419 axe violations · **#452** F-H5/F-H7 lifecycle dead-ends · **#453** F-H6 · **#454** F-M6 CLS |
| Group B → Phase 28, **SEC-02 issues now FILED** | **#438** F-C2 dev Postgres bind · **#439** F-C3 Grafana default creds · **#440** F-H2 spec advertises a tenant-override header · **#441** F-H10 infra port binds + mail archive · **#442** F-M7 unauth actuator/OpenAPI/edge. All five OPEN, `security` + P1/P2 labelled, **deliberately sanitised** (§2.1) |
| Group C → tracked | allergen text↔mask = #427 (still OPEN) · storefront social signup = #432 (still OPEN) · the low-severity set |

**The single most important result, worth carrying verbatim.** Phase 28's SEC-01 was written as
*"re-verify pentest A1"*. A1 is a real Critical cross-tenant write BOLA — but its **filed root cause
("missing `tenant_id` / RLS") is FALSIFIED**: both tables carry `tenant_id` with ENABLE + FORCE RLS.
The real cause was service-layer authorization in `ShopAccessService.require()`, and it also affected
`POST /products`. **Implementing the filed fix would have shipped a no-op over a live Critical.**
Re-verify, don't implement, is the whole reason that finding got closed correctly.

**The durable fix is not "look harder".** Either give the council run a tracking issue per Group, or
un-ignore a findings summary. Neither is done.

### 0.2 Instruments that lied, this session

| what I measured with | what it actually did |
|---|---|
| **HANDOFF.md §3's "one `shop_announcements`, one `shop_promotions` row"**, quoted to the owner as current | **Wrong by a day and wrong about the contents.** There were **6** rows, and **4 were created that same morning** as `SEC01-PROBE-*` / `VERIFY-PROBE-XT` attack probes — the council's `state.json` recorded them as *"DELIBERATELY RETAINED … evidence for SEC-01"*. A destructive step was approved on stale figures. Snapshotted to `evidence/sec-A1-residue-rows-preclean.txt` before deleting. **Re-run a handoff's measurement before repeating its numbers as current** |
| `BUILD SUCCESSFUL` from a `--tests`-filtered gradle run | Means nothing on its own — it is also what running **zero** tests looks like. Read `tests="N" failures="N"` out of `build-local/test-results/`. (`core-java/build/` is a **stale 2025-12-27 artifact** reporting 3 false failures — the live dir is `build-local`) |
| the changelog entry I wrote for F-M1 | **Could not satisfy its own gate.** `check-changelog-contract` keys on the merged PR's own `(#NNN)`, which does not exist until `gh pr create` prints it. The entry merged as #434 and only then went red. **Add the number after `gh pr create`, before merging** |
| `jsonPath("$.code")` in a standalone-MockMvc test | Failed `PathNotFoundException` against a handler that is **correct in production**. `new ObjectMapper()` does not register `ProblemDetailJacksonMixin`; `Jackson2ObjectMapperBuilder.json().build()` does. Same fix `RateLimitInterceptorTest` carries for #413 |
| 19 gates, read as a flat pass/fail | Two went red for reasons that are **correct behaviour**, not regressions: `check-runtime-freshness` after any core-java source change, and `check-alert-metrics` after any rebuild that recreates core-java. Both name their own remedy in their output. Expect them |

---

## 1. What landed

### 1.1 #434 — a lost optimistic-lock race is a 409, not an opaque 500 (F-M1 / INT-03)

`ObjectOptimisticLockingFailureException` matched **none** of `GlobalExceptionHandler`'s 30 handlers,
so it fell to the `Exception.class` catch-all: `500 .../errors/internal`, *"An unexpected error
occurred"*.

**Nothing was actually failing, which is the point.** 8 barrier-synchronised `confirm`s on one PENDING
order measured `{200: 1, 500: 7}` while data integrity **held** — exactly one transition applied, final
state consistent. The same duplicate and illegal transitions run **sequentially** already returned a
typed `400`. The race was the only thing separating a correct 400 from an opaque 500.

**Why it mattered operationally:** a KDS is a shared shop screen, so two staff bumping one ticket is
the normal case — and the frontend api-client **auto-retries on 5xx**. A 500 turned ordinary contention
into a retry storm against a row whose write had already succeeded.

Declared on the **`OptimisticLockingFailureException` superclass**, not Hibernate's subclass, so a
Spring-translated `StaleObjectStateException` and any future `@Version` entity are covered — one root
cause, two reported symptoms (INT-03 and the security lane's A1-del), one handler.

Detail is a **fixed string**: the provider message names the table and the `version` column, so it is
logged at WARN and never returned.

**Functional proof, same instrument as the finding, reproduced 3× on 3 distinct orders — the last on
the merged-main runtime:**

| | codes | types | final_status |
|---|---|---|---|
| before (council, 13:20) | `{200: 1, 500: 7}` | `errors/internal` | CONFIRMED |
| after | `{200: 1, 409: 7}` | `errors/concurrent-modification` | CONFIRMED |

Recorded at `.qa-council/disc-20260802-121732/evidence/fm1-optlock-409-postfix.txt`.

Falsified with opening and closing clean arms: clean 4/4 → break arm (handler de-registered) **3 of 4
fail, control arm still passing** → restore verified **by `git hash-object`** → closing clean 4/4.
Full unit suite **870 tests / 0 failures** (council baseline 866 + these 4, nothing else disturbed).

### 1.2 #435 — the `.idea` residue, and the changelog citation

Four IntelliJ database-tooling paths added to `.gitignore` (`dataSources.xml`,
`dataSources.local.xml`, `dataSources/`, `db-forest-config.xml`). The tree had been permanently
`dirty=4` since 2026-08-01, and this document's own resume block carried a footnote telling the reader
to discount it — **which trains you to discount a dirty tree at exactly the moment it is the signal.**

**Four exact paths, deliberately not a blanket `.idea/`**: the repo tracks `.idea/vcs.xml`,
`.idea/gradle.xml` and `.idea/go.imports.xml` on purpose. Control arm: `check-ignore .idea/vcs.xml`
still returns rc=1 after the change.

Also carries the `(#434)` citation fix described in §0.2.

### 1.3 The changelog gate was red on `main` before this session started

`check-changelog-contract` was **rc=1 on the first sweep of the session, before any change** — #433
merged 2026-08-02 with no entry, after #430 recorded 19/19 green on 2026-08-01. Both were true at the
time; the gate is not flaky, the world moved. Backfilled in #434; the gate now cites **22 of 22**.

---

## 2. Open items — this session's

### 2.1 The pentest backlog is now TRACKED, and A1 is ANSWERED

`SECURITY-FINDINGS.md` (untracked, git-excluded; evidence at
`~/strix_runs/host-docker-internal-9090_d8c0/`, chmod 600). Status changed this session:

- **A1** — **RESOLVED as a finding, not as filed.** Real Critical, false root cause, fixed at the
  service layer in #433. SEC-01 is answered; do not re-run it.
- **A2** (request-header tenant fallback, CVSS 8.2) — **still real in code, dev-scoped.**
  `TenantFilter` is `@Profile({"dev","local","test"})` and k8s sets `SPRING_PROFILES_ACTIVE=prod`.
  Compose runs `dev`, so it is live locally. `OpenApiConfig.java:50` still **advertises** the scheme
  unconditionally — that is council F-H2, now **#440**.
- **B1/B2/C1** — the Compose stack publishes infra ports with no bind address. Inventory deliberately
  not reproduced here; see the local evidence file. These are council F-C2 / F-H10, now **#438** and
  **#441**.

> **Disclosure note, added 2026-08-02.** The port inventory and the header name were spelled out in
> this section in #430 and carried forward unreviewed into #436. They are removed from the current
> file, but **`git log -p HANDOFF.md` still contains them** — removing text from HEAD does not remove
> it from a public repository's history, and this is recorded rather than quietly edited. Treat it as
> already-public and rotate on that basis; do not treat this edit as a containment.

**SEC-02 is DONE as of 2026-08-02: all five Group B findings now have issues** — **#438 is OPEN**,
**#439 is OPEN**, **#440 is OPEN**, **#441 is OPEN**, **#442 is OPEN**. The audit is no longer
one `rm` away from being lost.

**They are deliberately sanitised, and that is a constraint on whoever works them.** This repository
is **public**, which is the same reason `SECURITY-FINDINGS.md` was git-excluded. The issues carry the
component, the problem class, the scope, the fix direction and falsifiable acceptance criteria — but
**no reproduction commands, no port/credential pairings and no role attributes**. Verified after
filing by scanning **GitHub's stored bodies**, not the local drafts, with a control token proving the
scan was not blind. The detail lives in `.qa-council/disc-20260802-121732/evidence/sec-findings.md`.
**Do not paste repro steps into these issues when working them.**

**Read #442 before ranking by severity.** The council rated F-M7 *Medium* and the other four
Critical/High, which is right on severity and **misleading on urgency**: the other four are explicitly
dev-stack-only, while F-M7's `permitAll` entries are **not profile-gated** and therefore reach
production. It is a deploy-blocker being closed before there is a deployment to block.

### 2.2 The cross-tenant DB residue is GONE

All 6 rows deleted via `CLEAN_RESIDUE=1 bash scripts/seed-e2e-fixtures.sh`, verified 0 remaining **with
a blindness control** (3 promotions / 1 announcement still visible, so the verification query was not
simply seeing nothing). Snapshot retained at `evidence/sec-A1-residue-rows-preclean.txt`.

Previous handoffs described this as 2 rows. It was 6. See §0.2.

### 2.3 The three most load-bearing planning files are still ungated

`ROADMAP.md`, `REQUIREMENTS.md` and `STATE.md` are covered by **no gate** — unchanged from #430. A
`check-state-freshness` asserting `STATE.md`'s current phase against `ROADMAP.md`'s progress table was
recommended in `260801-ths`'s SUMMARY and is **still not built**.

### 2.4 Twelve findings the owner got by USING the app — none of which any audit found

Reported 2026-08-02 from live use, verified against the tree, filed as seven issues. Read this section
before §0.1's: the council findings are mostly correctness and infrastructure; these are the product.

| issue | items | what it is |
|---|---|---|
| **#457 is CLOSED** (PR #466, 2026-08-03) | 1b, 9 | Public header was **session-blind**. Browser-falsified and confirmed — **and it was hiding #465** (below) |
| **#458 is OPEN** | 1a, 2, 3, 4 | Signed-in nav shows `For operators` + `Track order` ungated; tracking belongs in the profile, auto-populated, with a dispatch notification |
| **#459 is OPEN** | 6 | Basket survives sign-out → a second customer on the same browser inherits it |
| **#460 is OPEN** | 7 | **No concept of locality.** A phase, not a patch. P1 |
| **#461 is OPEN** | 8, 10 | No payment processing; pay-on-collection must become channel-issued payment links. P1 |
| **#462 is OPEN** | 11 | No second factor, no verified contact channel |
| **#463 is OPEN** | 5 | *My Orders* spinner is client-side rendering, **not** a slow API |

Item 12 (README review) went as a **comment on #449**, which already owns the entry-doc surface.

**Four had a different mechanism than the symptom — this is the part worth carrying:**

- **"Going home logs me out" was BOTH — and the browser run is what separated them.** ~~Not yet
  browser-proven~~ — **proven 2026-08-03, and the diagnosis above was half right.** `PublicHeader`
  really did contain zero session references, and the session really did survive the navigation
  (control arm: returning to `/shop` restored signed-in chrome; `/shop/orders` resolved the identity
  server-side). But the same run found the session **also dies on a 300s timer regardless of
  activity** — filed as **#465**, fixed with #457 in PR #466. Both are CLOSED.

  **This is the entry to carry.** Insisting on the browser arm before writing code is what turned one
  symptom into two defects. Had the header been fixed on the strength of the filing alone, the report
  would have persisted and read as unfixed — after five minutes the header would correctly say
  "Sign in", because the customer genuinely was logged out. The refresh token had been sitting
  HttpOnly for 30 days, never redeemed; the only `grant_type: "refresh_token"` in the frontend was
  `auth.ts`, the **operator** path on a different realm.
- **The basket cannot cross shops** — `cart-provider.tsx` keys `jtoye-cart-{shopSlug}`, the payload
  carries its own slug, and the parser rejects a mismatch (`:57-61`). It crosses **identities** because
  `customerLogout()` clears four keys (`customer-auth.ts:94-98`) and not the cart. One device, public
  catalogue data, no PII, no server-side exposure. Real, bounded — and the desirable
  anonymous→signed-in carry-forward must survive the fix.
- **Payments are not missing code.** `PublicStorefrontService:508-521` falls back to COD *by design*
  when the provider is unconfigured. This is the empty-`STRIPE_API_KEY` state (§4). First action is
  keys, not code.
- **The slow page is not a slow query.** Measured: orders API **13–17 ms** warm, `/shop`
  server-rendered **12 ms**. `/shop/orders` is `"use client"` end-to-end, so the spinner covers bundle
  + hydration + fetch. "Optimise the query" would have been wasted work.

**#460 is re-ranked above the rest.** `navigator.geolocation` = **0**, `deliveryRadius` = **0**. Shop
`latitude`/`longitude` exist on the DTO (`:657-658`) and **nothing computes distance**; postcode search
is a substring match over name/description/address. Birmingham and London see the same vendor list.
Vendor visibility, delivery feasibility, distance-based fees and the local-SEO work (#447) are all
downstream of a locality model that does not exist.

**The process lesson.** Every gate was green, 23 council issues had just been filed, and none of this
surfaced. The council audits what the code *does*; the owner used what the product *is*. **Keep doing
this by hand** — no gate in this repo would have caught a single one of the twelve.

---

## 3. Carried forward — still true, not re-measured unless noted

- **#418 is still OPEN and the flake's mechanism is STILL UNKNOWN**; the issue body's diagnosis is retracted. It
  does *not* race its own `@Scheduled` flusher (`@DynamicPropertySource` parks both intervals at 24h).
  The assertion is in `PaymentEventOutboxReliabilityIntegrationTest.failedRows_resurrectAndDrain_poisonStaysDead()`
  — **line 294, not 273**, which both CI logs and the issue body still quote. #422 inverted the
  assertion order so the next occurrence is diagnostic: row `SENT` + wrong count → the mock; row still
  `PENDING` → the flush did not run. **Capture that line when it next fails.**
- **A new E2E spec landed un-run, and the skip-budget gate caught it.** #456 added
  `frontend/e2e/marketing-dish-scroller.spec.ts`; `check-e2e-skip-budget` now returns **rc=2 VOID**
  because the stored report is older than the specs it describes. That is the gate working — a stale
  report certifying a skip set that no longer exists is a documented trap here. It is also the most
  concrete argument yet for dispatching the nightly job below: it would have run this spec.
- **#420 is still OPEN — its CI half.** `e2e-nightly.yml` (all 126 specs, real stack) **has still never run**
  (`schedule` fires only on the default branch). Dispatch it once manually. Per-PR CI still runs 2 of 126.
  Corollary seen again this session: `Integration Tests` passed in **6s** on #435 — path-filtered, a
  skip wearing a tick. The same job took **43m51s** on #434, which is what running it looks like.
- **The refund E2E stays skipped deliberately** — `Stripe.Refund.create` with an empty key. Needs keys,
  not a fixture.
- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** Remedy:
  `bash scripts/seed-order-metric.sh`. Hit twice this session. Expect it every time.
- **Fixtures decay by design** — seeds write every instant relative to now. Re-run the seed before
  suspecting the product.
- **No `v2.3` git tag** — latest is `v2.2` while `build.gradle.kts` reads `2.3.0`. GTM-01.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.
- **Toolchain: 4 DRIFT + 1 UNKNOWN**, none applied. `docker-ce` restarts the daemon — stack down first.

---

## 4. Carried forward from #430 — the blocking decisions

Phases 29–32 do not start until these land. **None are engineering tasks.**

| decision | state |
|---|---|
| **Production domain** | `jtoye.co.uk` never registered; `FRONTEND_PUBLIC_*` point at `olajay.co.uk`; no A records |
| **Hosting target** | Your Azure sub is `c483d353`; the employer HS2 sub is off-limits. A live `snackpass-*` Container Apps stack already runs this product |
| **Stripe test-mode keys** | Empty on every stack. Gates Phase 30 **entirely** |
| **ADR-0002 sign-off** | Still `Proposed` — gates PITR / DPLY-04 |

Also unscheduled: **#427 is OPEN** (ADR-0004 ingredient graph, 0% built — its finding that nothing
reconciles `allergen_mask` against the ingredients text is a live product risk, scheduled as LGL-03 in
Phase 31) and **#428 is OPEN** (the catering cohort, *half the stated go-to-market*, deliberately gated
until ≥3 caterers are interviewed — **Wave 1 costs no engineering time and can start today**).

**`k8s/` still ships zero monitoring manifests** (DPLY-03, written to fail on the current tree). A
staging deploy today would be wholly unmonitored.

---

## 5. Environment state

- **Branch `main`**, 0 behind, **clean** — the `.idea` residue that made this line read `dirty=4` for
  two days is gone as of #435. A dirty tree now means *your* change.
- **Live stack:** 16 jtoye containers — 11 from `docker-compose.full-stack.yml` + 5 from
  `infra/monitoring/docker-compose.monitoring.yml`. **14 report healthy**;
  `jtoye-redis-exporter` and `jtoye-postgres-exporter` report no health status because their images
  define **no healthcheck**. That is **not** unhealthy. `keycloak-realm-render` is a one-shot init that
  exits by design and is not counted.
- **Runtime is CURRENT.** `sync-runtime.sh` was run *after* both merges and parity re-asserted:
  4/4 FRESH. The F-M1 fix was then re-proven functionally against that rebuilt stack.
- **Conda env:** none needed — no Python application code. Note the `block-base-python` hook refuses
  bare `python3` here; there is no `.conda-env` for this repo. The F-M1 race probe was therefore
  written in **Node**, not Python.
- **Stripe:** UNCONFIGURED (empty key → COD only). Email → Mailhog. S3 → MinIO. Broker → RabbitMQ 4.3.4.

---

## 6. Resume instructions

```bash
# 0. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) ahead=$(git rev-list --count $b..HEAD) behind=$(git rev-list --count HEAD..$b)"
# expect behind=0 AND dirty=0. A VOID line is NOT a pass.

# 1. Every gate. Capture rc on its OWN statement — an rc read after a pipe is the pipe's.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE (compose project name comes from the directory).
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 19 x rc=0. A VOID (2) is not a pass.
# If check-runtime-freshness is 1 -> you changed source: bash scripts/sync-runtime.sh
# If check-alert-metrics    is 1 -> core-java was recreated: bash scripts/seed-order-metric.sh
# Both gates print their own remedy. Neither is a regression.

# 2. The QA council findings — the thing no repo command can show you (§0.1).
ls .qa-council/disc-20260802-121732/                 # NOT the LATEST pointer; it still says July
sed -n '1,80p' .qa-council/disc-20260802-121732/QA-COUNCIL-REPORT.md

# 3. Re-prove F-M1 is live, rather than trusting this document.
#    Expect {"200":1,"409":7} and type errors/concurrent-modification. A 500 means the runtime is stale.
docker exec jtoye_oaas_2026-core-java-1 sh -c \
  'unzip -p /app/app.jar BOOT-INF/classes/uk/jtoye/core/common/GlobalExceptionHandler.class | strings' \
  | grep -c concurrent-modification      # expect 2; a filesystem `find` returns a misleading 0

# 4. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**#457 is DONE (PR #466) — and it paid for the method.** Settling it in a browser first, as the issue
demanded, turned one symptom into two defects and found **#465** (the session ended at 300s regardless
of activity, with a 30-day refresh token never redeemed). Both closed. Keep doing this: the ten-minute
browser arm is what stopped a correct-looking header fix from shipping over a live P1.

**Recommended next move: #442 (F-M7)** — the only Group B finding whose `permitAll` is not
profile-gated, so the only one that reaches production, and it needs no decision from §4. Its trap is
in its acceptance criteria: a fix that authenticates the metrics endpoint without giving Prometheus a
way in **silently blinds the whole Phase 27 alerting layer**, and nothing turns red when that happens.

**Also newly filed: #467** — `/api/customer-orders` 502s on the compose stack (`CORE_API_INTERNAL_URL`
unset; `localhost` in-container is the container's own loopback, and `extra_hosts` does not beat it),
and the UI renders that failure as *"No orders found for this email"*. **An error displayed as an
empty state** — invisible to the user, to a screenshot, and to any test asserting the page renders.
Found while browser-verifying #466, pre-existing.


**Then #444 (F-H4)** — the webhook delivery log is permanently empty, a shipped Phase-22 feature that
has never worked, and the finding names the cause in one line (no `@Transactional`, so
`TenantSetLocalAspect` never pins the GUC and RLS returns nothing).

**#460 and #461 need a DECISION before any code**, and both are P1. #460 (locality) is a phase, and
§4's unresolved production-domain question touches it. #461 (payments) is blocked on Stripe test-mode
keys — which is already one of §4's four blocking decisions, so it is the same blocker wearing a
different hat, not a new one.

**The whole council backlog is now filed — #438–#454, 23 issues.** A coverage sweep maps all 34
findings to filed / already-fixed / deliberately-Group-C, with **0 unaccounted** and a control token
proving the sweep discriminates. Two findings — **#453 is OPEN** (F-H6, High) and **#454 is OPEN**
(F-M6) — appear in `findings.json` and the report prose but **in no group in `plan.md`**: the council
found them and never routed them. They were caught only by that sweep. If you run a council again,
diff `findings.json` against the groups in `plan.md` before trusting the adjudication.

**#428 Wave 1 (catering discovery) still costs no engineering time and runs in parallel with anything.**

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric. `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
