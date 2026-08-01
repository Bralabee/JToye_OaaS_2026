# Handoff: an unwatched E2E suite was hiding a broken customer sign-in

**Generated:** 2026-08-01 ~01:00 BST. Supersedes the node-24 handoff (#407), which was accurate when
written. Its §0.1 (concurrent session) and the instrument-failure log are carried forward; its
node-24 sections are history and are not repeated.

> **The single most useful thing in this document is §0.2:** three separate times this session, a
> check was green over a feature that was completely broken. `curl -I` showed the right header, unit
> tests asserted the right value, and the running product did not work. Every one was caught by
> exercising the real path in a browser, and none by anything going red.
>
> **The second is §0.3.** `grep` on this machine is **ugrep**, where `{` and `}` are regex
> metacharacters — so a literal pattern containing braces silently returns 0. Used to prove a
> restore had happened, it reported both "the plant is gone" and "the fix is missing" on a tree that
> was provably correct.

| | |
|---|---|
| `JToye_OaaS_2026` | **3 PRs merged this session: #408, #410, #411.** 2 issues filed (#412, #413), 1 filed-and-closed (#409). HEAD deliberately **not** quoted — see the note below |
| Open PRs | **none.** #408, #410 and #411 are all MERGED |
| Open issues | **62.** #409 is CLOSED. #404 is OPEN and remains the top standing item. #412 and #413 are OPEN and are this session's follow-ups — **land #412 before #413**, see §2.1 |
| Live stack | Compose UP, **17** jtoye containers, **15 healthy** — the other 2 define no healthcheck (§3) |
| Gates | **18 of 18 rc=0**, measured from the main checkout after the final merge |
| Runtime proof | 4/4 built services FRESH · `Implementation-Version: 2.3.0` read from inside the running `app.jar` · frontend container image ID `==` its tag, so a real rebuild not a restart · CSP on `:3000` carries `realms/jtoye-customers/` |
| Project version | **2.3.0** (`build.gradle.kts`). Latest tag is `v2.2`; no `v2.3` tag — a release decision |
| Test baseline | `docs/metrics.json` **1895** — java 1264, jest **463** / 66 files, schema V60 |

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §4 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.1 A second session shares this checkout — currently PARKED

The user confirmed mid-session that the other session is **parked**, which is why `git pull` was
run here safely. Do not assume that is still true.

**There is one unpushed local branch that is NOT mine:**
`feature/faster-integration-tests-parallelism`, one commit `c142b90c`, +8 lines in
`core-java/build.gradle.kts` ("parallelize integrationTest to cut ~39m runtime to ~15m"). It has no
PR, no remote, and no secret-looking paths. **It was deliberately left alone** — Phase 13 of
housekeeping surfaces such branches, it never pushes them.

The standing warning, unchanged: `git pull`, `checkout`, `merge`, `reset` and `branch -D` all
**write** to whatever branch is checked out in the shared checkout. Committing from a worktree
protects your commits; it does nothing about a mutating command aimed at the shared checkout. Check
`git branch --show-current` immediately before any of them, not just before `commit`.

### 0.2 Three green checks over three dead features — all in one session

This is the theme of the whole session, and the reason #409's first fix did not work.

| what was green | what was actually true |
|---|---|
| `curl -I` showed the customer realm in `connect-src`, and a unit test asserted it | **Customer sign-in was still completely broken.** A CSP source carrying a path matches EXACTLY unless it ends in `/`, so the bare realm URL never covered `…/protocol/openid-connect/token` |
| `curl -I` showed `Retry-After: 19`, and unit tests asserted the quantified copy | **The browser rendered "wait a moment" every time.** `Retry-After` is not CORS-safelisted and the API is cross-origin, so it is hidden from JS. The header branch could never execute — the tests build the header object directly and could not see that |
| `getRetryDelayMs()` docstring says it "honours the server Retry-After" | Same cause. It has **always** taken its exponential-backoff fallback. A silent fallback is indistinguishable from a working one — filed as **#412** |

**The habit:** after any fix, exercise the real path and read the value the user would see. "The
check I added now passes" is evidence about the check, not about the feature. Logging the *rendered
copy* in a browser is what caught all three.

### 0.3 My own instruments were wrong five more times

| what I measured with | what it actually did |
|---|---|
| `grep -c 'wsOrigin} ${keycloakSources.join'` to verify a break-arm restore | **Returned 0 on a provably correct tree.** `grep` here is **ugrep**: braces are metacharacters. It reported "plant gone" AND "fix missing" at once. Settled by `git hash-object` vs `git rev-parse HEAD:<path>`. Use `grep -F` for any literal containing `{`/`}` |
| `curl \| grep -c '<article'` on the shop page, to prove Add buttons were missing | **0 hits on a page that renders them.** The menu is client-rendered; curl cannot answer that question. A filter used to prove absence produced the absence |
| `grep -q 'vendor-credentials'` as an idempotence guard while editing six specs | Matched a **comment** naming the file, so two specs were silently skipped. Fixed by anchoring on the import statement itself |
| `pkill -f "next start -p 3111"` | Matched **its own shell's** command line and killed it (exit 144). The bracket form `[n]ext` is required — the same self-match trap as `pgrep`. Kill by PID from `ss -ltnp` instead |
| reading "the URL stayed on `/checkout`" as proof the order failed | **Not evidence.** The COD confirmation renders INLINE on the same route, so the URL does not change on success either. This produced a wrong issue (#409's original framing) that had to be corrected in-issue |

---

## 1. What landed

### #408 — customer sign-in was CSP-blocked, and the E2E suite that would have caught it was itself broken

Two thirds of #404's "27 of 128 failures" were never product defects.

- **37 were a vendor password that cannot authenticate.** Six specs carried `?? "password123"` — a
  literal `onboarding-blocked-flow.spec.ts:62` had already removed with the note *"it fails against
  the re-imported realm"*. A wrong password is not a missing one, so nothing skipped: each test
  timed out ~21s at `vendorLogin`, indistinguishable in the report from a broken dashboard. Now
  centralised in `frontend/e2e/vendor-credentials.ts` with an **empty** default and
  `skipWithoutVendorPassword()`.
- **Repairing the stale specs exposed a live defect.** #382 split the staff and customer Keycloak
  realms; `middleware.ts` fed only the staff URL into the CSP. Registration **succeeded** and the
  token exchange was then blocked, leaving the shopper on `/shop/auth/callback` reading
  *"Authentication failed"* holding an account they could not use.
- **And listing the realm was not enough** — see §0.2. Each realm is now emitted bare **and**
  trailing-slash. A bare *origin* already matches every path and is emitted unchanged.

Measured by test NAME, never by count (the suite is not stable to ±3): arms differing only in the
credential gave **55 → 20** failures, **18 persisting in both**.

### #410 — a rate-limited order told the shopper to do the one thing that re-trips it

Filed as #409 *"order created (201) but never confirmed"*. **That framing was wrong** and is
corrected in the issue: the POST is rejected with `429` before reaching the controller, so nothing
is persisted and there was never a duplicate-order risk.

Checkout read only `response.data.detail` (RFC 7807); the limiter answers `{"error","message"}` with
`Retry-After`. So the one actionable sentence was discarded and the shopper saw *"Failed to place
order. Please try again."* — the exact action that re-trips the limit. `lib/order-error.ts` now
handles 429 first, then `detail`, then `message`.

**The harness was generating the load.** `fullyParallel: false` only sequences tests *within a file*;
the two projects still ran on 2 workers through one Docker gateway IP. `workers` is pinned to 1.
**And that was not sufficient** — the public limit is 30/min burst 10 while one storefront page load
fires ~6 public calls, so a single sequential run produced **166** rejections. The local compose
stack now sets `RATE_LIMIT_PUBLIC_PER_MINUTE=600`, `RATE_LIMIT_PUBLIC_BURST=120`, with the limiter
**still enabled** deliberately.

### #411 — the changelog gate caught its own entry

#410's changelog heading cited the issue (`#409`) but not the PR, so `check-changelog-contract` C-1
went red on the push-to-main run **7 minutes after** the merge. Exactly the behaviour that gate
documents. The heading now carries both.

---

## 2. Open items

### 2.1 #412 and #413 — this session's follow-ups. **Land #412 first**

Both are OPEN and both change a live API response contract, which is why neither was done inline.

- **#412 — `Access-Control-Expose-Headers`.** `Retry-After`, `X-RateLimit-Limit`,
  `X-RateLimit-Remaining` and `X-RateLimit-Reset` are set by `RateLimitInterceptor` and readable by
  **no browser client**. `CorsConfig.java:25` sets `setAllowedOrigins` and never `setExposedHeaders`.
  Two client paths already depend on them and both silently degrade (§0.2).
- **#413 — the 429 body is hand-rolled JSON, not RFC 7807.** `RateLimitInterceptor.java:158` and
  `:258` write `{"error","message","tenantId"}` while `GlobalExceptionHandler.java:52-54` builds a
  proper `ProblemDetail` everywhere else.

> **⚠ The ordering is load-bearing.** While `Retry-After` stays invisible, `order-error.ts` depends
> on parsing `data.message`. Reshaping the body to RFC 7807 **before** #412 would silently drop the
> quantified wait — and **nothing would go red**: the server tests do not know about the frontend,
> and the frontend tests build their own fixtures. Sequencing notes are on both issues.

### 2.2 #404 — still the top standing item

**CI runs `e2e/public-layout.spec.ts` only — 2 of 128 tests.** The other 11 specs need a full stack
that CI does not have; that is the structural gap, not an oversight. Remaining known failures, with
mechanisms recorded rather than guessed:

- `media-review-320` ×2 — failing **correctly**, on its own anti-vacuity guard
  (*"VOID: no redrivable row rendered"*). A missing fixture, not a defect.
- `kitchen-flow` ×2 — `Received: hidden`, consistent with the streaming-buffer class #406 fixed.
  **Hypothesis, not established.**
- `webhooks-flow` ×2 — `waitForURL` times out having navigated to `/dashboard/media/review`.
  **Mechanism open.**
- `storefront-flows` is now **28 passed / 2 skipped** at 1 worker, 0 rate-limit rejections.

### 2.3 Carried forward, still true

- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** `sync-runtime.sh`
  does exactly that. Remedy: `bash scripts/seed-order-metric.sh` (no `FORCE` — the mute covers the
  firing case). Expect it every time.
- **Toolchain: 4 DRIFT + 1 UNKNOWN**, surfaced 2026-08-01, none applied (housekeeping reports, it
  does not converge). `conda` 26.1.1→26.5.3 is HELD by an upstream bug — 34 envs sit on that base,
  do not force it. `docker-ce` needs root and **restarts the daemon**, dropping all 17 containers —
  do it with the stack down. `ms-fabric-cli` 1.2.0→1.6.1 needs clone-test-promote.
  `@google/gemini-cli` 0.53.0→0.53.1 is new and trivial. `antigravity` is **UNKNOWN**, not clean:
  its `package.json` reports the VS Code base, not the product version.
- **No `v2.3` git tag** — a release decision.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.

---

## 3. Environment state

- **JToye:** `main`, clean, 0 behind. **Two local branches** — `main` and the concurrent session's
  unpushed `feature/faster-integration-tests-parallelism` (§0.1). Remote has `main` only. One
  worktree.
- **Live stack:** 17 jtoye containers. **15 healthy;** `jtoye-redis-exporter` and
  `jtoye-postgres-exporter` report no health status because their scratch-based images define no
  healthcheck. That is **not** unhealthy — `check-alert-liveness` L-1 asserts every scrape target is
  `up`, and it passes.
- **Rate limiting:** the compose stack now runs the public limiter at **600/min, burst 120**
  (default is 30/10). Still **enabled** — the 429 path is reachable by exceeding the wider budget,
  which is how the browser verification in #410 was produced.
- **Alertmanager:** the mute is ACTIVE locally via `.env` (`ALERTMANAGER_MUTE_ALERTNAMES`). `.env`
  is gitignored, so a fresh clone is loud by default — intended.
- **Git hooks:** `.githooks/post-merge` is installed and live; it fired correctly on **both** merges
  this session, naming `sync-runtime.sh` each time.
- **Node/Go:** node v22.23.2, npm 12.0.2 on the host; the containers run node 24.18.1. Go 1.26 —
  `gofmt` clean, `vet` clean, `mod tidy` no drift, `test -race -count=1` all 5 packages fresh, 0 races.
- **Conda env:** none needed — no Python application code.

---

## 4. Resume instructions

```bash
# 0. FIRST: is the other session still parked?  (§0.1)
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
# EXPECT 18 x rc=0 — ALL of them.
# If check-alert-metrics fails on NoOrdersCreated, that is the rebuild-blindness case:
# run `bash scripts/seed-order-metric.sh` (no FORCE). EXPECT it after any rebuild.

# 3. Customer sign-in, end to end, against the runtime the USER sees — §0.2 is about
#    getting this wrong. A 200 and a header prove nothing here.
curl -sI http://localhost:3000/shop | grep -i '^content-security-policy:' \
  | grep -cF 'realms/jtoye-customers/'        # expect 1 — the TRAILING SLASH form is the fix
cd frontend && KC_SEED_USER_PASSWORD=$(grep -E '^KC_SEED_USER_PASSWORD=' ../.env | cut -d= -f2-) \
  PLAYWRIGHT_BASE_URL=http://localhost:3000 \
  npx playwright test e2e/storefront-flows.spec.ts -g "Customer Auth"
#    expect 4 passed. This is the ONLY check that would have caught #408.

# 4. Runtime parity BY CONTENT (a 200 and a title are identical whether or not the code is current)
bash scripts/check-runtime-freshness.sh   # expect 4/4 FRESH, rc=0
docker exec jtoye_oaas_2026-core-java-1 sh -c 'unzip -p /app/app.jar META-INF/MANIFEST.MF | grep -i Implementation-Version'
for s in frontend mcp-server; do
  c=$(docker inspect --format '{{.Image}}' jtoye-$s); t=$(docker images -q --no-trunc jtoye_oaas_2026-$s:latest)
  [ "$c" = "$t" ] && echo "$s MATCH" || echo "$s MISMATCH — container is not running the tagged image"
done

# 5. Before merging ANY PR — never an inline gh-api-pipe-wc idiom
~/dotfiles/gates/pr-merge-guard.sh --repo Bralabee/JToye_OaaS_2026 --pr <n> --expect-head <sha>
#    0 = safe · 1 = not safe · 2 = VOID (could not evaluate — NEVER treat as 0)
```

**Running the E2E suite.** Source the stack's `.env` first (`set -a; . ./.env; set +a`) or the six
vendor specs skip with a reason. `workers` is pinned to 1 — do not raise it without also raising the
public rate limit, or the suite throttles itself (§1, #410).

**If `check-runtime-freshness` or `check-container-config-drift` VOIDs (exit 2), check *where* you
ran it before you touch the stack.** Both VOID from a **worktree** even on a healthy stack: Compose
derives the project name from the directory. **Run those two from the main checkout.** They fail
closed, which is correct.

**Merged code is not running code.** After any merge that touches source: `bash
scripts/sync-runtime.sh`, which rebuilds exactly what drifted and re-asserts with the same gate —
then reseed the order metric (§2.3).

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority. `gh pr merge --delete-branch` also cannot
delete a branch a worktree holds — remove the worktree first, from the main checkout.
