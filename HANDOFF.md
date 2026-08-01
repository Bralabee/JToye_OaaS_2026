# Handoff: four wrong diagnoses, three of them mine, none catchable by a green check

**Generated:** 2026-08-01 ~11:00 BST. Supersedes #419/#421, which were accurate when written —
their §2.1 carried a diagnosis of #418 that this session **retracted**, which is exactly the
semantic rot `check-handoff-contract` says it cannot detect.

> **§0.2 is the point of this document.** Four separate defects were diagnosed this session and
> **four diagnoses were wrong** — three of them written by me, two into filed GitHub issues. Every
> one was settled by reading the failure's own output; none by anything going red. If you read one
> section, read that one, then §0.3.

| | |
|---|---|
| `JToye_OaaS_2026` | **7 PRs merged: #415, #416, #417, #419, #421, #422, #423.** 3 issues filed (#418, #420, and #404's closure). HEAD deliberately **not** quoted — see below |
| Open PRs | **none** |
| Open issues | **61.** #412, #413 and #404 are CLOSED. **#418 and #420 are OPEN** and are this session's follow-ups (§2) |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck (§3) |
| Gates | **19 of 19 rc=0.** The 19th (`check-e2e-skip-budget`) VOIDs until a fresh Playwright JSON report exists — run §4 step 5 first |
| Runtime proof | 4/4 built services FRESH · live 429 read back as `application/problem+json;charset=UTF-8` carrying `retryAfterSeconds` |
| Project version | **2.3.0** (`build.gradle.kts`). Latest tag `v2.2`; no `v2.3` tag — a release decision |
| Test baseline | `docs/metrics.json` **1917** — java 1274 / 221 files, jest 475 / 67 files, schema V60 |
| E2E | **118 passed / 8 skipped / 0 failed** of 126. The 8 are DECLARED, which is not the same as passing |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §4 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout

Unchanged and still true: `git pull`, `checkout`, `merge`, `reset` and `branch -D` all **write** to
whatever branch is checked out in the shared checkout. Check `git branch --show-current` immediately
before any of them, not just before `commit`.

`feature/faster-integration-tests-parallelism` (one commit `c142b90c`, +8 lines in
`core-java/build.gradle.kts`, "parallelize integrationTest to cut ~39m runtime to ~15m") is **still
unpushed and still not mine** — deliberately left alone. ⚠ **Think before adopting it**: the
integration job is where #418's flake lives, and that flake is contention-sensitive. More parallelism
may make it worse, not better.

### 0.2 Four diagnoses, four wrong — and the pattern is the same every time

| what was believed | what was true | how it was settled |
|---|---|---|
| **#412** (filed): *"the `Access-Control-Expose` grep returns nothing"*, so no allowlist existed | The allowlist **existed and omitted the four headers**. `exposedHeaders` (plural) cannot match `addExposedHeader` (singular) | `curl -D -` printed `Access-Control-Expose-Headers: Authorization, Content-Type` |
| **#404** (recorded): `kitchen-flow` is *"consistent with the streaming-buffer class #406 fixed"* | **Wrong subsystem.** Radix `Select` renders a visually-hidden native `<select>`; the locator resolved to an `<option>`, hidden BY DESIGN, so the assertion could never pass on any stack | Playwright's own log: `locator resolved to <option value="shop-1">Test Shop</option>` |
| **#418** (filed by me): the test *"has nothing disabling that schedule"*, so it races its own `@Scheduled` flusher | **It parks both intervals at 24h** via `@DynamicPropertySource`. My grep pattern did not contain `DynamicPropertySource` | Reading the file. Then three checks killed the theory outright (§2.1) |
| **#420** (written by me): the promo fixture *"exists and expired"* | True of `brixton-village-grill` — but `storefront-flows` opens **`mama-ades-kitchen`**, which had **zero** promotions | `SHOP_SLUG` is on line 20 of the spec |

**Two of these were retracted in public**, on #418 and #420. Neither retraction was forced by a
failing check; both came from someone re-reading the evidence.

**The habit:** read the failure's own output *before* theorising, and check the one line that says
which thing the test actually touches. Every wrong diagnosis above cost more to unwind than that
would have cost to do.

### 0.3 Instruments that lied

| what I measured with | what it actually did |
|---|---|
| a grep, twice, to prove absence | **Evidence about the PATTERN, not the code.** `exposedHeaders` cannot match `addExposedHeader`; a pattern without `DynamicPropertySource` cannot see `@DynamicPropertySource`. Both became filed issues. Prefer the ROOT token (`ExposedHeader`, `PropertySource`) and prove the pattern can hit before concluding it cannot |
| `fetch(...).catch(...)` then `find(r => r.status === 429)` | **Reported "never received a 429" over 1440 requests.** Every non-`Response` fell into the `.catch()` and the search skipped it, so "all failed" and "no 429" were indistinguishable. Re-instrumented to tally EVERY outcome: `{"429": 60}` |
| `git checkout -- <file>` to restore after a break arm | **Ate two fixes silently.** It restores from the **INDEX**, and they were unstaged. Third occurrence here. Caught only because restores are verified by `git hash-object`, never by `git diff --stat`. **Commit before running arms** |
| `rc=$?` after `… \| tail -15` | Reported **0 over a gate that printed 10 FAIL lines**. `$?` was `tail`'s. Capture on its own statement |
| reading a test-results XML after a build | **Read a STALE report** — the compile had failed, nothing ran, and the previous run's numbers looked like a pass. `rm -f` the report before every arm |
| `curl -I` to answer "can a browser read this header?" | **Categorically the wrong instrument.** On one and the same response curl showed `Retry-After: 50` while Chromium resolved `headers.get('Retry-After')` to `null` |
| `body.contains("Too Many Requests")` as a contract assertion | **Passed against BOTH the old hand-rolled body and the RFC 7807 one** — never evidence about the contract at all |
| a CI re-run to "confirm a flake" | Legitimate **only after** you have shown the failing test is unrelated to your diff AND the same SHA has passed before. A reflexive re-run hides a real failure |

---

## 1. What landed

### #415 — the rate limiter's four headers were readable by nobody (closes #412)

A cross-origin response hands JS only the CORS-safelisted headers unless the server names the rest;
the allowlist carried `Authorization, Content-Type`. Two client paths degraded **silently**:
`public-fetch-retry.ts` always took its backoff fallback despite a docstring claiming otherwise, and
the checkout could not quantify the wait. Now config-injected via `cors.exposed-headers`.

### #417 — the 429 body is RFC 7807, server and client together (closes #413)

Both paths emit a real `ProblemDetail` through the application's **own** `ObjectMapper` — chosen
because the defect *was* a hand-written body that merely resembled the contract. `retryAfterSeconds`
is a typed number. The charset was wrong too (`ISO-8859-1`). **Shipped with the frontend**, because
`order-error.ts` read `data.message`, which this removed — server-only would have dropped the
quantified wait with nothing going red.

### #416 — #404's three remaining failure classes were all instrument defects

Six failing tests, **zero product defects**: a nav link added two phases later stealing a click
(`/view/i` matches "Image re**view**"); a Radix hidden `<option>`; and a fixture that had **decayed**
because it was written with absolute dates.

### #422 — a tenant-listing blip aborted the whole scheduled pass (refs #418)

`listTenantIds()` sat **outside** the per-tenant try/catch in three scheduled workers, so a transient
failure while merely *listing* tenants aborted the entire pass — publishing nothing for **any**
tenant. 78 stack traces per failing CI run end at exactly that call. Fixed in
`PaymentEventOutboxFlusher`, `MediaEventOutboxFlusher` and `WebhookDeliveryWorker`.

### #423 — 14 E2E skips became 8, and the 8 are declared (refs #420)

The suite said "114 passed, 0 failed" while 14 skipped. Among them: *"the Issue-refund button is
hidden on a DRAFT order"* — a gating assertion on a **money path** — had never executed.

**As measured before the fix (2026-08-01 ~08:30):** all **91** orders in the dev DB were
`payment_status = NONE` and **not one** was DRAFT. Both halves of that are now deliberately stale —
`seed-e2e-fixtures.sh` adds the DRAFT order, and the count has since grown past 91 (seeding plus
`seed-order-metric.sh`). Re-measure rather than quoting these:

```bash
docker exec jtoye-postgres psql -U jtoye -d jtoye -tAc \
  "select status, payment_status, count(*) from orders group by 1,2 order by 3 desc;"
```

`payment_status` is still `NONE` for **every** row, which is why the refund test itself stays
skipped (§2.2) — that part has not changed and will not until Stripe test keys exist.

---

## 2. Open items

### 2.1 #418 — the flake's mechanism is STILL UNKNOWN. Do not trust the issue body

⚠ **The diagnosis in #418's body is wrong and is retracted in its comments.** It claims the test
races its own `@Scheduled` flusher. It does not: `@DynamicPropertySource` parks both intervals at
86400000 ms.

Three checks killed that theory:

| check | result |
|---|---|
| do scheduled `flushPending` executions land in the failing test's window? | **No** — the last is 70–82s earlier, in the *previous* class's teardown |
| does un-parking the interval to 250ms reproduce it locally? | **No** — 5/5 pass |
| could RLS scoping or cross-class DB pollution explain it? | **No** — per-class `@Container`, and Testcontainers' bootstrap role is a superuser that bypasses even FORCE RLS |

**What is known:** `PaymentEventOutboxReliabilityIntegrationTest:273` fails in **both directions** —
`TooManyActualInvocations` on #415, `WantedButNotInvoked` on #417 — on branches touching neither
payment nor outbox code, and only on heavily-contended runs where Testcontainers Postgres was
demonstrably sick (477 and 500 `Connection refused` lines).

**Next occurrence will be diagnostic.** #422 inverted the assertion order so row state is checked
**before** the invocation count:
- row `SENT`, count wrong → the publish happened; the mock/verification is the problem
- row still `PENDING` → the flush genuinely did not run

**Capture that line when it next fails** — it is the one fact that would settle this. Cost so far:
~2h of merge latency across two PRs and one manual `gh run rerun --failed`.

### 2.2 #420 — the CI coverage half is untouched

**CI runs `e2e/public-layout.spec.ts` only — 2 of 126 tests.** The other 12 specs need a full stack
CI does not have. 124 of 126 E2E tests never run on any PR, which is exactly how #404's broken
sign-in went unnoticed. Options are enumerated on the issue (nightly compose job / extend the
stack-free browser gate / accept explicitly in writing). **This is a decision, not a task.**

The skip half is done: 14 → 8, all 8 declared in `scripts/gates/e2e-skip-budget.conf` with a
justification and a REMOVE WHEN. Remaining: multi-replica STOMP (4), the real-Stripe refund (2),
demo-tenant onboarding (2).

**The refund test needs a decision from you, not a fixture.** It calls `Stripe.Refund.create` and
`STRIPE_API_KEY` is empty here. Seeding `paymentStatus=CAPTURED` with an invented
`payment_reference` would push it past its skip and then FAIL at Stripe — a green-looking fixture
over a broken path. Provision test-mode keys, or leave it declared.

### 2.3 Carried forward, still true

- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** `sync-runtime.sh` does
  exactly that. Remedy: `bash scripts/seed-order-metric.sh` (no `FORCE`). Expect it every time.
- **Fixtures decay by design.** `seed-e2e-fixtures.sh` and `seed-media-review-fixtures.sh` write
  every instant RELATIVE TO NOW for that reason. If a spec starts VOIDing on its own guard, re-run
  the seed before suspecting the product.
- **Cross-tenant residue in the dev DB**: a `shop_announcements` row and one `shop_promotions` row
  belong to tenant 2 but hang off tenant 1's shop, left from an RLS test. RLS hides them; they make
  those tables look populated when they are not. `CLEAN_RESIDUE=1 bash scripts/seed-e2e-fixtures.sh`
  removes them — **not run yet**, deliberately, since nothing depends on them either way.
- **Toolchain: 4 DRIFT + 1 UNKNOWN**, none applied. `conda` 26.1.1→26.5.3 is HELD by an upstream bug.
  `docker-ce` restarts the daemon, dropping all 17 containers — do it with the stack down.
- **No `v2.3` git tag** — a release decision.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.

---

## 3. Environment state

- **JToye:** `main`, clean, 0 behind, rebuilt. Local branches: `main` + the concurrent session's
  unpushed `feature/faster-integration-tests-parallelism` (§0.1). One worktree.
- **Live stack:** 17 jtoye containers, **15 healthy**; `jtoye-redis-exporter` and
  `jtoye-postgres-exporter` report no health status because their scratch images define no
  healthcheck. That is **not** unhealthy — `check-alert-liveness` L-1 asserts every scrape target is
  `up`, and it passes.
- **Rate limiting:** public limiter at **600/min, burst 120** locally (default 30/10), still
  **ENABLED** deliberately — the 429 path is how #412 and #413 were both verified.
- **Stripe:** `STRIPE_API_KEY` / `STRIPE_WEBHOOK_SECRET` are **empty**. This is what gates the refund
  E2E test (§2.2).
- **E2E fixtures:** seeded and current. They decay on purpose — re-run the seed, do not edit rows.
- **Conda env:** none needed — no Python application code.

---

## 4. Resume instructions

```bash
# 0. FIRST: is the other session still parked?  (§0.1)
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git branch --show-current; git status --short; git worktree list

# 1. Tree state, asserted rather than quoted. Resolve the default branch, never hardcode it.
git fetch -q origin
b=$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD) || echo "VOID: no origin/HEAD"
echo "on $(git branch --show-current) vs $b: dirty=$(git status --porcelain|wc -l) ahead=$(git rev-list --count $b..HEAD) behind=$(git rev-list --count HEAD..$b)"
# expect dirty=0 ahead=0 behind=0 on main. A VOID line is NOT a pass.

# 2. Every gate. Capture rc on its OWN statement — §0.3 explains why.
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE.  RUN STEP 5 FIRST.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 19 x rc=0 — ALL of them. check-e2e-skip-budget VOIDs (2) until step 5 has produced a
# report, the same way check-runtime-freshness VOIDs without a running stack. A VOID is not a pass.
# check-alert-metrics red on NoOrdersCreated is the rebuild-blindness case (§2.3).

# 3. The two rate-limit fixes, read out of the RUNNING product — a 200 proves nothing here.
for i in $(seq 1 900); do curl -s -o /dev/null http://localhost:9090/api/v1/public/shops & done; wait
curl -s -D - -o /dev/null -H 'Origin: http://localhost:3000' http://localhost:9090/api/v1/public/shops \
  | grep -iE '^(HTTP/|content-type|access-control-expose)'
# EXPECT: 429 · application/problem+json;charset=UTF-8 · an expose list containing Retry-After.
# `curl -I` CANNOT tell you whether a browser can READ that header — §0.3.

# 4. Runtime parity BY CONTENT
bash scripts/check-runtime-freshness.sh   # expect 4/4 FRESH, rc=0
docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -p /app/app.jar BOOT-INF/classes/application.yml' \
  | grep -c 'exposed-headers'             # expect 1 — read from INSIDE the jar, not the filesystem

# 5. E2E — source .env FIRST, then SEED, then run with the JSON reporter.
cd frontend && set -a; . ../.env; set +a
bash ../scripts/seed-e2e-fixtures.sh      # covers DRAFT order + promo, delegates to the media seed
mkdir -p e2e-artifacts
PLAYWRIGHT_BASE_URL=http://localhost:3000 npx playwright test --reporter=json > e2e-artifacts/report.json
# EXPECT 118 passed / 8 skipped / 0 failed, of 126.
bash ../scripts/check-e2e-skip-budget.sh  # expect rc=0; VOID(2) if the report is stale or absent
cd ..

# 6. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**If `Integration Tests (Testcontainers RLS)` goes red, read WHICH test before touching your diff.**
#418 is a live flake in a required check; it hit two PRs this session and both passed on re-run.
Confirm the failing test is unrelated to your diff **and** that the same SHA has passed before, then
`gh run rerun <id> --failed`. **Do not re-run reflexively** — a re-run of a real failure is a real
failure hidden. And **capture the row-state assertion** (§2.1) if it is this test.

**CI runs duplicate/stale workflows.** A force-push leaves the superseded run in flight; several
heavy runs competing is what starved Testcontainers. Cancel runs whose SHA is no longer any branch's
head — check with `git branch -a --contains <sha>` first.

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric (§2.3). `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
