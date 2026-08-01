# Handoff: three "already fixed" claims that were each wrong in a different way

**Generated:** 2026-08-01 ~05:15 BST. Supersedes the E2E/CSP handoff (#414), which was accurate when
written. Its §0.1 (concurrent session) is carried forward; its instrument-failure log is folded into
§0.3 with this session's additions.

> **The most useful thing in this document is §0.2.** Three separate defects were closed this
> session, and in each case the *issue's own diagnosis was wrong in a way that a passing check could
> never have revealed*. One grep pattern could not match the thing it claimed was absent; one
> hypothesis named the wrong subsystem; one fixture had decayed rather than been missing. Every
> mechanism was settled by reading the failure's own output, not by reasoning about the code.

| | |
|---|---|
| `JToye_OaaS_2026` | **3 PRs merged: #415, #416, #417.** 1 issue filed (#418). HEAD deliberately **not** quoted — see the note below |
| Open PRs | **none.** #415, #416 and #417 are all MERGED |
| Open issues | **61.** #412, #413 and #404 are all CLOSED. **#418 and #420 are OPEN** — a flaky required check (§2.1) and the E2E coverage gap #404 left behind (§2.2) |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck (§3) |
| Gates | **18 of 18 rc=0**, measured from the main checkout after the final merge and rebuild |
| Runtime proof | 4/4 built services FRESH · live 429 read back as `application/problem+json;charset=UTF-8` with `retryAfterSeconds` |
| Project version | **2.3.0** (`build.gradle.kts`). Latest tag is `v2.2`; no `v2.3` tag — a release decision |
| Test baseline | `docs/metrics.json` **1914** — java 1271 / 220 files, jest **475** / 67 files, schema V60 |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §4 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout

Unchanged from #414 and still true: `git pull`, `checkout`, `merge`, `reset` and `branch -D` all
**write** to whatever branch is checked out in the shared checkout. Check `git branch --show-current`
immediately before any of them, not just before `commit`.

`feature/faster-integration-tests-parallelism` (one commit `c142b90c`, +8 lines in
`core-java/build.gradle.kts`, "parallelize integrationTest to cut ~39m runtime to ~15m") is **still
unpushed and still not mine** — deliberately left alone. Note that **#418 makes this branch more
interesting than it looks**: the flake it would help with is a contention flake.

### 0.2 Three issues, three wrong diagnoses — and none of them was detectable by a green check

| the issue said | what was actually true |
|---|---|
| #412: *"`grep -rn 'exposedHeaders\|Access-Control-Expose'` returns **nothing***", so no allowlist existed | The allowlist **existed and omitted the four headers**. `exposedHeaders` (plural) cannot match `addExposedHeader` (singular) at `CorsConfig.java:30-31`. Same defect, different mechanism — and the wrong mechanism would have produced a wrong fix |
| #404: `kitchen-flow` is *"consistent with the streaming-buffer class #406 fixed. **Hypothesis, not established**"* | **Wrong subsystem entirely.** `getByText(/Select shop\|Test Shop/i).first()` resolved to `<option value="shop-1">`. Radix `Select` renders a visually-hidden native `<select>` for a11y beside the visible trigger, and an `<option>` is hidden BY DESIGN — the assertion could never have passed, on any stack, ever |
| #404: `media-review-320` is failing on a *"missing fixture"* | The fixture was **present and had decayed**. All three rows were hand-inserted with ABSOLUTE timestamps; when `quarantine_expires_at` passed, the quarantine sweep did exactly its job and stamped `quarantine_reclaimed_at`, and `redrivable = expires_at != null && reclaimed_at == null` went false |

**The habit that settled all three:** read the failure's own output before theorising. Playwright
printed `locator resolved to <option value="shop-1">Test Shop</option>` — that one line ended the
kitchen-flow hypothesis. `curl -D -` printed `Access-Control-Expose-Headers: Authorization,
Content-Type` — that one line ended the "no allowlist" theory.

### 0.3 Instruments that lied, this session and last

| what I measured with | what it actually did |
|---|---|
| `fetch(...).catch(e => ...)` then `wave.find(r => r.status === 429)` | **Reported "never received a 429" over 1440 requests.** Every non-`Response` fell into the `.catch()` and the search skipped it, so "all failed" and "no 429" were indistinguishable. Re-instrumented to tally EVERY outcome; the re-run showed `{"429": 60}`. A filter used to prove absence must never be able to manufacture it |
| `git checkout -- <spec>` to restore after a break arm | **Ate two fixes and reported nothing.** `git checkout` restores from the **INDEX**, and the fixes were unstaged. Third occurrence in this repo. Caught only because restores are verified by `git hash-object`, never by `git diff --stat` — which is empty both when a file is restored and when it was never written. **Commit before running arms** |
| `rc=$?` after `... \| tail -15` | Reported **0 over a gate that printed 10 FAIL lines**. `$?` was `tail`'s. Capture on its own statement: `out=$(cmd); rc=$?` |
| reading a test-results XML after a build | **Read a STALE report.** The compile failed, nothing ran, and the previous run's `tests="5"` was still on disk and looked like a pass. `rm -f` the report before every arm |
| `curl -I` to answer "can the browser read this header?" | **Categorically the wrong instrument.** curl shows what was SENT; the browser decides what script may READ. On one and the same response curl showed `Retry-After: 50` while Chromium resolved `headers.get('Retry-After')` to `null` |
| a unit test asserting `body.contains("Too Many Requests")` | **Passed against BOTH the old hand-rolled body and the RFC 7807 one**, so it was never evidence about the contract at all. Replaced by parsing the JSON and asserting fields by name |

---

## 1. What landed

### #415 — the rate limiter's four headers were on the wire and readable by nobody (closes #412)

`RateLimitInterceptor` sets `Retry-After` and three `X-RateLimit-*` on every 429. A cross-origin
response hands JS only the CORS-safelisted headers unless the server names the rest, and the
allowlist carried `Authorization, Content-Type` only. Two client paths depended on them and **both
degraded silently**: `public-fetch-retry.ts` always took its exponential-backoff fallback despite a
docstring claiming otherwise, and the checkout could not quantify the wait (#409/#410).

Now config-injected via `cors.exposed-headers` (env `CORS_EXPOSED_HEADERS`). Deliberately **not**
plumbed through compose or the k8s configmap — the `application.yml` default reaches every
environment, and restating six header names in a second file is drift risk with no benefit.

### #417 — the 429 body is RFC 7807, server and client changed together (closes #413)

Both paths now emit a real `ProblemDetail` through the application's **own** `ObjectMapper` — chosen
because the defect being fixed *was* a hand-written body that merely resembled the contract, and
rebuilding it by hand would reintroduce the same bug in a nicer-looking form. `retryAfterSeconds` is
a typed number. The charset was wrong too: `getWriter()` defaults to ISO-8859-1 and the old responses
really did go out as `application/json;charset=ISO-8859-1`.

**Shipped with the frontend, and that was the whole hazard.** `order-error.ts` read `data.message`,
which this change removes. Server-only would have dropped the quantified wait back to "wait a moment"
with **nothing going red** on either side.

### #416 — the three remaining #404 E2E failures were all instrument defects (refs #404)

Six failing tests, **zero product defects**. Mechanisms in §0.2. `webhooks-flow` was broken by a
Phase 24 nav entry against a Phase 22 spec — accessible-name matching is substring, so `/view/i`
matched "Image re**view**". `scripts/seed-media-review-fixtures.sh` is new: relative timestamps,
idempotent on `(tenant_id, sha256)`, discovers the tenant, and verifies by re-reading the DTO's own
predicate rather than counting rows.

---

## 2. Open items

### 2.1 #418 — a flaky REQUIRED check that blocks merges. **Fix this first**

`PaymentEventOutboxReliabilityIntegrationTest:273` races its own `@Scheduled` flusher. It failed on
two unrelated branches at the same line with **opposite** Mockito errors — `TooManyActualInvocations`
on #415, `WantedButNotInvoked` on #417. A real defect fails in one direction; failing in both is a
race.

`flushPending()` is `@Scheduled(fixedDelayString = "${payment.outbox.flush-interval-ms:5000}")`, the
test is a plain `@SpringBootTest` with nothing disabling that schedule, and the background scheduler
shares the very `@MockBean RabbitTemplate` the assertion counts. Suggested fix and the reason **not**
to relax it to `atLeast(1)` are both on the issue. Check the `MediaEventOutboxFlusher` suites too —
they were cloned from this one.

It cost roughly two hours of merge latency this session and required a manual `gh run rerun --failed`.

### 2.2 #420 — the E2E coverage gap that #404 left behind

#404 was CLOSED once its three **failure** classes were fixed. Two things it raised were not
addressed, so they are now tracked separately as **#420** rather than disappearing with it:

- **CI runs `e2e/public-layout.spec.ts` only — 2 of 128 tests.** The other 12 specs need a full stack
  CI does not have. Structural, not an oversight — but the consequence is that 126 of 128 E2E tests
  never run on any PR, which is exactly how #404's broken sign-in went unnoticed.
- **14 tests SKIP rather than pass.** Full run on the merged tree: **114 passed, 14 skipped, 0
  failed** of 128. Playwright's summary is green and reads as "everything is verified". It is not.
  Seven distinct tests × 2 projects: desktop-only GSAP scenes enumerated under the *mobile* project
  (arguably a spec bug, not a missing fixture), multi-replica STOMP, and refundable-order /
  promotion / onboarding fixtures absent from the dev DB.

The suggested first step is on the issue: make the skip count **visible and bounded** so a new
conditional skip cannot silently join the 14 — the same shape as the anti-vacuity guard in
`media-review-320.spec.ts`, which is the only reason its own decay was ever noticed.

### 2.3 Carried forward, still true

- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** `sync-runtime.sh` does
  exactly that. Remedy: `bash scripts/seed-order-metric.sh` (no `FORCE`). Expect it every time.
- **`seed-media-review-fixtures.sh` seeds DATABASE state only** — no MinIO object — so clicking
  Re-process on the redrivable fixture still fails at the storage layer. Honest for the spec that uses
  it, which never clicks it. A future spec that does click must extend the script.
- **Toolchain: 4 DRIFT + 1 UNKNOWN**, none applied. `conda` 26.1.1→26.5.3 is HELD by an upstream bug.
  `docker-ce` restarts the daemon, dropping all 17 containers — do it with the stack down.
- **No `v2.3` git tag** — a release decision.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.

---

## 3. Environment state

- **JToye:** `main`, clean, 0 behind, rebuilt. Local branches: `main` plus the concurrent session's
  unpushed `feature/faster-integration-tests-parallelism` (§0.1). One worktree.
- **Live stack:** 17 jtoye containers, **15 healthy**; `jtoye-redis-exporter` and
  `jtoye-postgres-exporter` report no health status because their scratch images define no
  healthcheck. That is **not** unhealthy — `check-alert-liveness` L-1 asserts every scrape target is
  `up`, and it passes.
- **Rate limiting:** public limiter at **600/min, burst 120** locally (default 30/10), still
  **ENABLED** deliberately — the 429 path is how both fixes were verified.
- **Media fixtures:** seeded and current. They decay by design once `quarantine_expires_at` passes;
  re-run the script rather than editing rows.
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
#    RUN FROM THE MAIN CHECKOUT, NOT A WORKTREE.
for g in scripts/check-*.sh scripts/docs-freshness.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# EXPECT 18 x rc=0 — ALL of them.
# check-alert-metrics red on NoOrdersCreated is the rebuild-blindness case (§2.3).

# 3. The two fixes, read out of the RUNNING product — a 200 proves nothing here.
#    Trip the limiter, then read the wire. Both lines must appear.
for i in $(seq 1 900); do curl -s -o /dev/null http://localhost:9090/api/v1/public/shops & done; wait
curl -s -D - -o /dev/null -H 'Origin: http://localhost:3000' http://localhost:9090/api/v1/public/shops \
  | grep -iE '^(HTTP/|content-type|access-control-expose)'
# EXPECT: 429 · application/problem+json;charset=UTF-8 · an expose list containing Retry-After.
# `curl -I` CANNOT tell you whether a browser can READ that header — see §0.3.

# 4. Runtime parity BY CONTENT
bash scripts/check-runtime-freshness.sh   # expect 4/4 FRESH, rc=0
docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -p /app/app.jar BOOT-INF/classes/application.yml' \
  | grep -c 'exposed-headers'             # expect 1 — read from INSIDE the jar, not the filesystem

# 5. E2E — source .env FIRST or six vendor specs skip; seed or media-review VOIDs
cd frontend && set -a; . ../.env; set +a
bash ../scripts/seed-media-review-fixtures.sh
PLAYWRIGHT_BASE_URL=http://localhost:3000 npx playwright test --reporter=line
# EXPECT 114 passed / 14 skipped / 0 failed. The 14 skips are NOT passes (§2.2).

# 6. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**If the integration check goes red, read WHICH test before assuming your diff.** #418 is a live
flake in a required check; two PRs hit it this session and both passed on re-run. Confirm the failing
test is unrelated to your diff *and* that the same SHA has passed before, then
`gh run rerun <id> --failed`. Do not re-run reflexively — a real failure re-run is a real failure hidden.

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric (§2.3). `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
