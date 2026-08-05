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
| `JToye_OaaS_2026` | **2026-08-05 later: TWO more PRs — #563 (`d36a1865`, closes #561) and #565 (`b0043014`) — plus #564 filed. Read §0.-10 FIRST; it closes §0.-9's open question and supersedes every "flake" reading of #561.** PRIOR SAME DAY — **shipped SIX PRs — #541, #553, #554, #555, #558, #559 — closed FOUR issues (#289, #420, #556, #557) and filed SIX (#548–#552, #556/#557). Read §0.-8 then §0.-7.** Phase 28 opened: #548–#552 filed, #289 closed. PRIOR — **2026-08-04 shipped 12 PRs and closed 1.** The six-lane Wave-1 train (#522 #521 #515 #520 #519 #518), the handoff (#528), the postgres major-parity gate + the first restore drill this repo has run (#529), and three dependabot bumps (#527 #524 #526). **#525 (postgres 15→18 backup image) was CLOSED, not merged** — see §0.-2. HEAD deliberately **not** quoted |
| Open PRs | **1 as of 2026-08-05 after EIGHT merges** — still only **#523**, which is the same PR it was this morning and is still ON HOLD for the same reason. #563 and #565 merged after the six below. PRIOR reading — **1 after six merges**; #541/#553/#554/#555/#558/#559 all merged. PRIOR reading (**2**) — #523 (dependabot node 24→26, ON HOLD: every CI job pins `node-version: 24`, so its green MCP check is evidence about a version the PR does not change, and `mcp-server/package.json` declares no `engines`) and #530 (a housekeeping doc fix). **Re-measure before trusting this cell** |
| Open issues | **65 as of 2026-08-05 after eight PRs — and it is EXACTLY the 65 it was before this session's last two merges**, having gone 65 → 64 (#561 closed by #563) → 65 (#564 filed). A second, cleaner instance of the point below: the count did not move and two real things happened. PRIOR reading — **64 after six PRs**, itself the SAME 64 it was mid-session, having moved 60 → 65 → 64 in between. That stability hides real churn: five filed (#548–#552, the pentest disposition), two more filed from the E2E re-run (#556/#557), and four closed (#289, #420, #556, #557). **A flat count is not a quiet day.** Filing findings raises it and lowers the risk; do not read the number as progress in either direction. PRIOR framing — #548–#552 added five while #289 closed one. Filing findings raises the count and lowers the risk; do not read the number as progress in either direction. PRIOR — **62**, measured after the train with `--limit 300` (the default `--limit` is **30** and silently undercounts). 19 issues closed by the six PRs. ⚠ **Five of those did not auto-close**: a PR body reading `Closes #293, #506, #271, ...` only closes **#293** — GitHub's parser consumes the FIRST number in a comma list and ignores the rest. #506/#271/#298 were closed by hand afterwards; **#299 and #303 were deliberately left OPEN** because Lane D only made them *visible* as `OPEN DEFECT` allowlist entries, it did not fix them. #299 is a real production gap: the customer-storefront realm is unconfigured in EVERY k8s environment |
| Issue-count history | It moved in **both** directions across 2026-08-03 (63 → 86 → 92 → 89 → 85 → 80 → **62**) as the council backlog was filed and the trains closed issues, which is why no single figure here is safe to carry. Re-run `gh issue list --state open --limit 300 --json number --jq length` |
| Milestone | **v2.3 is OPEN and spans Phases 21–32.** Owner ruling stands — see §4. Do **not** run `/gsd-complete-milestone` |
| Live stack | Compose UP, **16** jtoye containers = 11 full-stack + 5 monitoring; **14 report healthy**. The two without health status define no healthcheck — that is **not** unhealthy. **Infra ports are now loopback-only** (#510): Postgres, Redis, RabbitMQ, MinIO, MailHog, Keycloak, Grafana, Prometheus, Alertmanager and both exporters bind `127.0.0.1`. App-tier ports (core-java 9090, frontend 3000, edge-go 8089, mcp-server 9100) stay on all interfaces as **named, reasoned exemptions** |
| Gates | **25 `check-*.sh` as of 2026-08-05** (26 counting `docs-freshness.sh`, which is the figure H-1 asserts against the resume block in §6). #553 added `check-gate-enforcement.sh` and wired three gates that ran NOWHERE — see §0.-7; **six of the previous 24 had zero CI references, three of them not deliberately**. Sweep on `main` after #565, runtime re-synced: **26/26 rc=0**, plus 6/6 k8s. **Both standing remedies fired, exactly as this document predicts them to** — `check-alert-metrics` rc=1 after each core-java recreate (`seed-order-metric.sh` → 0), and `check-e2e-skip-budget` rc=2 VOID **once per merge**, because a merge refreshes `frontend/e2e`'s mtime and the gate is a *staleness detector*. Its content was byte-identical both times, verified with `git rev-parse HEAD:<spec>` against the tested blob — and the report was still **re-earned by re-running the suite**, because touching it is fixing the gate rather than the thing. Budget ~6.5 min each time; plan for it after any merge touching a spec. PRIOR — sweep at `7c1ef2a7`: **25/26 rc=0**, the one non-zero being `check-e2e-skip-budget` rc=2 VOID. PRIOR — **24 scripts** (was 22 — #519/#276 adds `check-image-supply-chain.sh` and #337 adds `check-edge-core-contract.sh`; #513 had earlier added `check-e2e-baseurl-contract.sh` and `check-playwright-mobile-contract.sh`). **22 green, 0 fail, 0 VOID**, measured on `main` after #512/#513 with the runtime rebuilt. `check-e2e-skip-budget` is no longer the standing VOID it was — but understand WHAT it is: a **staleness detector**, not a one-time fix. It VOIDs whenever the stored report is older than `frontend/e2e`, which **any checkout or merge touching a spec re-triggers**, so expect it after pulling and re-run the suite (~6 min: `PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test --reporter=json,list`). Seed first — `scripts/seed-e2e-fixtures.sh` — or the DRAFT block skips and the budget fails. ⚠ It now sits at **exactly its ceiling of 8**, so the next skip added trips it. ⚠ `check-infra-exposure` **is not wired into CI** — part of it needs a live broker, so it could only ever VOID on a runner, the same reason `check-runtime-freshness` stays out. **Nothing stops someone re-adding `0.0.0.0` in a PR**. `scripts/ci-lane-cost.sh` is deliberately NOT named `check-*` and is NOT in this count: it answers a planning question, not a correctness one |
| Merge-train lesson | **`docs/metrics.json` conflicted three ways on every lane, and NEITHER SIDE WAS EVER RIGHT.** Lane E: ours 2093 / theirs 2106 / truth **2107**. Lane A: ours 2142 / theirs 2107 / truth **2157**. Lane B: 2202. Each lane adds to a different counter (Java / Go / Jest), so "take ours" and "take theirs" are both wrong and the only correct move is `scripts/docs-freshness.sh --write` on the merged tree. The same conflict also carried README's build badge, whose two sides were the **404 repo** and the fix for it — and which side was correct **flipped** between lanes, because the fix landed mid-train |
| Test baseline | **Read `docs/metrics.json`; this cell deliberately quotes no figure.** It moved three times in one day, and nothing gates a number written *here* — `check-doc-metrics` reads only README/CLAUDE/AGENTS, so a count copied into this document rots silently. Regenerate with `scripts/docs-freshness.sh --write`; never hand-arithmetic a delta, because the gate counts literal `@Test` and a renamed or table-driven test makes arithmetic wrong |
| Runtime | **4/4 FRESH after #565, re-synced 2026-08-05** — `frontend` rebuilt and **recreated** after each of the two merges. **Proven by content, not by the gate**: the fix's own string (`order-detail reads`) read out of the served `.next` bundle = **2**, a constructed-absent control = **0**, a pre-existing kitchen string = **2**, so the probe discriminates both ways. Note the probe had to be *chosen* — the obvious one is present in both the fixed and broken builds, so proving a break had shipped needed a marker string planted in the toast text (2 with the break, 0 after restore). PRIOR — **4/4 FRESH at `93ad0ab0`, re-synced after #559** — `frontend` and `core-java` both rebuilt and **recreated**. Proven by content from the served build with controls **both ways**: `other shop is` **2**, `other shop are` **0**, `kds-board-shop-loading` **2**, a constructed-absent string **0**. **The zero on the OLD string is the load-bearing row** — a present-new check alone is satisfied by a build containing both. Functional re-check `kitchen-flow` 14/14. PRIOR — **4/4 FRESH at `7c1ef2a7`, re-synced after #554.** `core-java` went `[image-not-rebuilt]` the moment #554 merged (image tagged 00:51:41 vs build inputs at 11:52:31); `sync-runtime.sh` rebuilt **and recreated** it, gate rc=0 after. **Proven by content, not by the gate**: `SHOP_SCOPED_FEATURES` read from inside the running `app.jar` = **1**, negative control `NotARealFieldControl` = **0**, positive control `KITCHEN_FEATURE` = **1**, so the probe discriminates both ways. Functional path re-exercised too, not just the check that motivated the rebuild — health 200, `/shop/brixton-village-grill` 200, `/api/v1/orders` 401. PRIOR — re-synced 2026-08-04 after the Wave-1 train. All four were stale (`rc=1`, each named with its build-input commit); `scripts/sync-runtime.sh` rebuilt and **recreated** them, gate `rc=0` after. Both directions recorded. **Proven by content, not only by the gate:** `TenantCacheEvictor`, `PublicUnsubscribeController` and `OrderStateChangeListener` (all #519) read back from **inside** the running `app.jar` via `unzip -l`, with a `NotARealClassControl` returning **0** so the probe can demonstrably say no; and the frontend's `--primary` was read out of the **served** stylesheet (`/_next/static/chunks/*.css`) as `17.5 88.3% 40.4%` — Lane C's orange-700, matching source, where orange-600 would be `20.5 90.2% 48.2%` |
| E2E | **Local full suite on `main` after #565: `174 passed / 8 skipped / 0 failed of 182` (6.6 min), 0 failed.** 182 not 180 because #563 added one `test()` block × 2 projects. Skips sit at **exactly** the declared ceiling of 8, so the next one added trips the gate. ⚠ **The NIGHTLY has still not run since `d4930719`** — the authority is stale even though the local number is green; **re-dispatch it**. ⚠ **Do not verify the KDS feed area with `kitchen-flow.spec.ts:339`** — it is budget-dependent (38 × 200 in one run, 28 × 200 + 10 × 429 in another, identical request pattern), and that dependency is what made #561 read as a flake for two sessions; `:406` injects the condition instead. PRIOR — **Nightly (the authority) GREEN: `180 / 173 passed / 0 failed / 7 skipped`** on `d4930719`, twice (§0.-6). `kitchen-flow.spec.ts` is **14/14 locally** against the rebuilt stack after #558/#559 (§0.-8), but that is one spec, not the suite — **re-dispatch the nightly to get a current whole-suite number.** `check-e2e-skip-budget` is rc=0 at **exactly its ceiling of 8**, so the next skip added trips it. HISTORY, kept because it is how #556/#557 were found — **local re-run at `7c1ef2a7`: `169 passed / 3 failed / 8 skipped` in 6.7m.** The 3 failures were all `kitchen-flow.spec.ts`, **NOT #554** — that PR changed **0 frontend files** (`git show --name-only 7c1ef2a7`) and `/dashboard/kitchen` is `"use client"`, so a Java STOMP change cannot cause a DOM strict-mode violation. **Re-running the spec alone gave `13 passed / 1 failed`.** ⚠ **I read that as "2 of the 3 were flakes" and it was WRONG — `:339 [mobile]` now fails 2/2 in the full suite and passes 2/2 in isolation; see §0.-9 and #561.** What was true is that only `[desktop] :455` is deterministic in BOTH contexts — filed as **#556**: `KdsBoardShopName` renders at page.tsx **:546** (loading branch) *and* **:575** (loaded), both emitting the same `data-testid`, so React's hydration swap transiently puts two in the DOM and strict mode resolves the stale one reading *"No shop selected"*. Same mechanism as #540, the class #542 tracks. ⚠ **The full suite and the single spec disagree — measure both before believing either.** STALE 2026-08-04 LOCAL ROW FOLLOWS — **127 passed / 8 skipped / 0 failed of 135** — a LOCAL run of the spec files, NOT the nightly (which runs both projects: 180 instances, see §0.-5) —, run against the re-synced stack, `check-e2e-skip-budget` **rc=0** at exactly its ceiling of 8. ⚠ **The first run of this suite reported 48 skipped / 21 undeclared and that figure was an INSTRUMENT ARTEFACT, not a finding** — the suite was launched without sourcing `.env`, so 26 vendor-authenticated specs self-skipped on "No vendor password". `set -a; . ./.env; set +a` first, and export `E2E_VENDOR_PASSWORD` from `KC_SEED_USER_PASSWORD`. A skip count is meaningless unless the credentials were present |

> ⚠ **A second session drives this same checkout.** Not a worktree — the same working tree. A `git
> checkout` here moves *their* HEAD, and `main` moved four times while this document was being written.
> **Re-measure every number below before repeating it**; §2.4's first entry is what happens when you
> don't.

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §6 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

### 0.-10 #561 answered: a product defect, and a test that was wrong three times (2026-08-05, latest)

**Read this before §0.-9, which it closes.** **#561 is CLOSED**; **#563 is MERGED** (`d36a1865`)
and **#565 is MERGED** (`b0043014`); **#564 is OPEN** as the follow-up. `main` moved twice more —
re-measure, do not quote.

#### The mechanism, measured — and it is none of the three things it looked like

§0.-9 left three candidates and said plainly that none had been run. It is **(3), the product-side
one**, and the trigger is the board's *own* recovery request.

`fetchOrders()` on the full path issues **1 list request + one `/detail` per active ticket**,
concurrently, and the `online` handler deliberately takes that path on recovery. On the E2E
vendor's board — `Brixton Village Grill`, **18 active tickets** — that is **19 requests**, fired
twice inside ~400 ms by an offline blip. The tenant limiter is
`Bandwidth.capacity(120).refillIntervally(100, 1 min)`: **one lump per minute**, so whatever else
the tenant spent in the same 60 s window is carried state. That is exactly the "state left by the
specs that ran first" §0.-9 correctly stopped at.

`fetchKitchenOrderDetails` used `Promise.all`, so a single 429 rejected the **whole** read —
including the list request that succeeded and the eight details that succeeded. `syncFailed` went
true, `deriveFeedState` returned `status: "error"`, and the board raised *"Orders are not
refreshing"* **over data it was still holding**, with nothing retrying for up to a minute. The
trace shows **zero** further requests for the remaining ~20 s.

Two arms, identical request patterns, opposite outcomes:

| arm | `/api/v1/orders*` | statuses | lowest `X-RateLimit-Remaining` | `:339 [mobile]` |
|---|---|---|---|---|
| `kitchen-flow.spec.ts` alone | 38 (19 + 19) | **38 × 200** | **79** | PASS |
| after the 3 mobile specs before it | 38 (19 + 19) | 28 × 200, **10 × 429** (`Retry-After: 12`) | **0** | FAIL |

**Reproducing took 4 spec files and 1.5 min**, not the 6.6-min suite: the three mobile specs
preceding `kitchen-flow`, then `kitchen-flow`.

#### The fix, and the half of it that is easy to undo by accident

`Promise.allSettled`, and the read is judged on **what the board can show**: list read succeeded
and every active order has a detail, fresh or held → success. **A ticket with no detail at all
still fails the sync and still raises the banner**, and that has its own test. Without that second
half the "fix" is a mute button, and an incomplete kitchen board that stays quiet is the more
dangerous of the two failures.

#### The part worth carrying: the test was wrong THREE times, and no passing run ever caught it

Each version passed something before it was found wrong. None was caught by review.

| version | what was wrong | what caught it |
|---|---|---|
| v1 | read the **live** board, so it cost 19 + 19 requests and became an instance of the budget dependency it existed to remove; its own page load was refused and the pill read `Offline —` | the 4-file repro arm |
| v2 | armed its injected 429s during the **initial** load — it waited on the pill reading "Live", and the pill reads the **socket**, which connects before the first read returns. Two tickets then had no detail at all, so the board *correctly* refused to go quiet and the test called that a failure | the **full suite** (passed in isolation, failed on **both** projects) |
| v3 | its own fixture reads (`/api/v1/shops`, `/api/v1/staff/me`) could be refused — measured `429 Retry-After: 9` on both, leaving `selectedShopId` null, no topic, no socket, pill `Offline —` | the **post-merge** suite on `main` (173 passed / 1 failed) |

One shared cause: **a finite, minute-granular tenant budget that no test declares a claim on.**
That is #564.

#### Four things to take from it

1. **Where the full suite and a single spec disagree, the disagreement IS the finding.** §0.-9
   wrote that, and I then resolved the disagreement by trusting the isolated run anyway — twice.
   Neither result is the answer and the cheaper side is not the tiebreak.
2. **`:339` is not a regression guard for this.** It is budget-dependent: in the green re-run it
   saw **38 × 200, lowest remaining 24** — the condition did not recur, so its pass says nothing
   about 429 tolerance. `kitchen-flow.spec.ts:406` **injects** the condition instead. Verify this
   area with a trace and a request count, never with `:339`.
3. **A break arm can silently not happen.** The break was proven to have *shipped* by carrying a
   marker string into the toast text and reading it back out of the **served** `.next` bundle: 2
   with the break, **0** after the restore, with the fix's own string still at 2. A marker is
   needed because the surrounding code is present in both versions, so the obvious probe cannot
   discriminate.
4. **A fallback that never fires is unproven.** Draining the bucket with two back-to-back spec
   runs did **not** reproduce the refusal (16/16, 2.4 s), so both paths of the new retry were
   **forced**: attempt-1-refused passes in **7.5 s** instead of 2.4 s (it waited and recovered);
   all-attempts-refused fails after ~17 s with the intended message naming the URL and the budget.

#### Numbers from this session, each re-measured after the last merge

- Full E2E on `main`: **174 passed / 8 skipped / 0 failed of 182** (6.6 min), skips at exactly the
  declared ceiling of 8.
- jest **91 suites / 791 tests**; `npm run build` rc=0; lint **0 errors** (both warnings verified
  present on `origin/main` with a probe shown to discriminate).
- All **26** repo gates and all six k8s gates rc=0, after the two standing remedies fired exactly
  as documented: `check-alert-metrics` rc=1 → `seed-order-metric.sh`, and `check-e2e-skip-budget`
  rc=2 VOID **twice** — once per merge, because a merge refreshes the spec's mtime. Its content
  was byte-identical both times (`git rev-parse HEAD:…` matched the tested blob), and the report
  was still re-earned by re-running the suite rather than touched.
- `docs/metrics.json` regenerated, never hand-arithmetic: jest 743 → **747**, playwright 84 → **85**,
  total 2386 → **2391**, carried into README/CLAUDE/AGENTS (two gates read the prose, not the
  manifest — and `check-claims` caught a second copy in README after `check-doc-metrics` had
  already gone green).

### 0.-9 A correction: "flake" was the wrong call, and so was my second guess (2026-08-05)

**§0.-8 says two of the three E2E failures were flakes. That is wrong for one of them**, and the
correction is worth more than the finding.

`kitchen-flow.spec.ts:339` `[mobile]` (offline → banner → recovery) now has four data points:

| run | `:339 [mobile]` |
|---|---|
| full suite, run 1 | **failed** |
| spec alone | passed |
| spec alone, after a frontend rebuild | passed |
| full suite, run 2 (after #558/#559) | **failed** |

**2/2 failing in the full suite, 2/2 passing in isolation.** A flake does not sort itself that
cleanly by context. I labelled it from a single isolated pass, which is exactly the sample size
that cannot tell "fixed" from "not reproduced here".

**Then my second guess was also wrong, and the config falsified it before I wrote it down.** The
obvious explanation is worker contention — but `playwright.config.ts` sets `fullyParallel: false`
and `workers: 1`. The suite runs **sequentially on one worker**. So the difference between
"full suite" and "this spec alone" is not concurrency; it is **state left by the specs that ran
first**. Filed as **#561** with three candidate mechanisms and the measurement that discriminates
them — none of them yet run, and the issue says so.

**Why this one matters beyond a red test.** `:339` is deliberately the spec that lifts every stub
and uses the real stack — real SSO, real WebSocket, real STOMP topic, real feed. If the cause turns
out to be product-side, then a kitchen board that went offline **never tells the vendor it is live
again**, under exactly the conditions a real kitchen runs in: a long session with accumulated
orders. The test's own comment already says it — *"A warning that outlives its cause is how a
kitchen learns to ignore warnings."*

**The transferable lesson.** §0.-8's own lesson 1 was *"the full suite and a single spec disagree —
measure both before believing either"*. I wrote that, then in the same breath resolved the
disagreement by trusting the isolated run. Where the two disagree, **the disagreement is the
finding**; neither result is the answer, and one pass on the cheaper side is not a tiebreak.

**Suite state at `14750546`:** `171 passed / 1 failed / 8 skipped` of 180 (6.6m).
`check-e2e-skip-budget` **rc=0**, 8 skipped at exactly its ceiling of 8.

### 0.-8 Two UI defects no gate could see, both found by re-running a suite (2026-08-05)

**Read this before §0.-7.** Two more PRs merged after it: **#558** (`d16935ab`, closes #556) and
**#559** (`93ad0ab0`, closes #557). `main` HEAD is `93ad0ab0` at the time of writing — re-measure.

**The point of this section is where these came from.** Neither was found by a gate. All 26 gates
were green, CI was green, and the nightly was green, while the kitchen board told a vendor something
false. They surfaced only because the local E2E suite was re-run to clear a stale
`check-e2e-skip-budget` report — housekeeping, not a hunt.

#### #556 — the board said "No shop selected" while it was still loading

`kitchen/page.tsx` renders `KdsBoardShopName` twice: **:546** in the loading early-return and
**:575** in the loaded body. The loading one passed `shopName={null}`, so both took the same branch.

**The product half, which the issue as filed undersold.** While data was in flight the header read
**"No shop selected"**. Not vague — *false*. A vendor whose shop is loading is told there is no
shop, and a screen reader announces it as settled fact. Loading was a third state borrowing the
empty state's voice.

**The test half.** Both renders carried `data-testid="kds-board-shop"`. Next server-renders this
`"use client"` component's loading state and swaps it after hydration, so **both trees are briefly
in the DOM**; Playwright strict mode found two elements and resolved the stale one. Same mechanism
as #540, the class #542 tracks.

Fixed in #558: `loading` is explicit and carries its own testid, so the three states are
distinguishable by any consumer — a test, a screen reader, a future component.

#### #557 — the grammar defect sitting under the comment written to prevent it

`KdsAllShopsNotice`'s `shopCount === 2` branch rendered a singular noun with a plural verb:
*"orders for your other shop **are** not on this screen"*. Same class as `"1 items in basket"`
(#533) — and it sat directly beneath:

```
// "your other 1 shop" is the `"1 items"` defect in #450 item 5 wearing a
// different hat. The count is only worth printing when it is >1.
```

That earlier fix dropped the count correctly and left the verb plural. **It removed half of a
grammar defect, and its own stated reasoning should have caught the other half.** Fixed in #559 by
putting noun and verb in the SAME ternary branch — the old shape had the noun conditional and the
verb hardcoded outside it, so they could only agree by coincidence.

#### Four verification lessons, all of which cost time here

1. **The full suite and a single spec disagree, and neither is "the" answer.** The suite reported
   **3 failed**; re-running `kitchen-flow.spec.ts` alone gave **1 failed**. ⚠ **I called the other
   two "flakes" and that was WRONG for one of them — see §0.-9.** Fixing
   all three as one thing would have chased two non-causes. **Measure both before believing either.**
2. **Blame the most recent merge last, not first.** #554 had just touched the STOMP *kitchen* path
   and the failures were all in `kitchen-flow`. It is innocent: `git show --name-only 7c1ef2a7`
   changes **0 frontend files**, and `/dashboard/kitchen` is `"use client"`. Rule out by
   measurement, not by plausibility.
3. **A fix that updates one assertion and misses its twin looks complete locally.** #557's wrong
   string was encoded in **two** test files; the second
   (`marketing-kitchen-shop-scope.test.tsx:288`) was found only by searching for the string rather
   than trusting the first suite to be the only one.
4. **Guard the opposite direction or the fix is satisfiable by its mirror image.** "Fix the
   grammar" is satisfied by making *everything* singular. A 4-shop vendor must still read "your
   other 3 shops **are**", and that is now asserted.

#### Proof, and the half that is load-bearing

Runtime rebuilt **and recreated**, 4/4 FRESH at `93ad0ab0`. Read out of the served build inside the
running container, with controls **both ways**:

| string | count | meaning |
|---|---|---|
| `other shop is` | **2** | #557's fix is in the artifact |
| `other shop are` | **0** | **the defect is GONE, not merely accompanied** |
| `kds-board-shop-loading` | **2** | #558's fix is in the artifact |
| a constructed-absent string | **0** | the probe can report absence |

**The zero on the old string is the load-bearing row.** A present-new check alone is satisfied by a
build containing both. `kds-board-shop-loading` appears in **both** the SSR and client chunks, which
independently corroborates the diagnosed hydration mechanism rather than leaving it a plausible
story.

Functional re-check: `kitchen-flow.spec.ts` **14/14** against the rebuilt stack, including the
`:455` test that was reproducibly red.

### 0.-7 Phase 28 opened: two criteria closed, and a class of gate that ran nowhere (2026-08-05)

**Read this before §0.-6.** Three PRs merged after it: **#541** (`feb8ef63`), **#553** (`515652b9`),
**#554** (`7c1ef2a7`). `main` HEAD at the time of writing is `7c1ef2a7`; re-measure, do not quote.

#### Phase 28 is roughly half-done, and most of that was already true before this session

Phase 28 (Security Triage + the Dev/Prod Boundary) was "not started, not planned". Measuring it
first turned out to matter more than building it: **8 of the 11 pentest findings were already
remediated by work that shipped for other reasons.** The phase's real remaining content is small.

| criterion | state | settled by |
|---|---|---|
| SEC-01 (re-verify A1, record CONFIRMED/FALSIFIED) | **CLOSED** | root cause **FALSIFIED**, both halves — see below |
| SEC-02 (all 11 findings filed or accepted) | **CLOSED** | **#548** tracking + **#549/#550/#551/#552** |
| SEC-03 (no dev branch under prod; no advertisement) | **already done** | **#440** — see the correction below |
| SEC-04 (no `0.0.0.0` infra ports) | **already done, now ENFORCED** | #510 bound them; **#553** made the gate able to fire |
| #289 (STOMP shop-gate hard-coded) | **CLOSED** | **#554** |
| #283 / #284 (`auth == null` bypass + async SecurityContext) | **OPEN — one piece, not two** | see §0.-7 "what is left" |

#### SEC-01: A1's root cause is FALSIFIED, and the guard was proven able to fail

The criterion fails if the re-verification is skipped *or* reports "as filed" without an arm. Both
halves of the stated root cause are false on the tree:

- **Schema.** `shop_promotions` and `shop_announcements` both carry `tenant_id`, RLS **enabled and
  forced**, 2 policies each. **Non-vacuous**: the same query against `tenants` (deliberately
  RLS-free) returns `f|f|0`, so the probe can report absence.
- **Service.** Both services call `require(shopId, SHOP_MANAGER)` on create/update/delete.

**The guard was falsified, not merely observed passing.** `CrossTenantAuthzIntegrationTest` (6) and
`ShopPromotionsRlsPolicyIntegrationTest` (3) pass clean. Neutralise the ownership check in
`PromotionService.createPromotion` and the run goes to **exactly 1 failure —
`createPromotion_crossTenantShop_isBlocked()`** — and no other. Restore verified by
`git hash-object`, closing arm clean. Four arms, all recorded.

⚠ **These are `@Tag("testcontainers")` tests.** `:core-java:test --tests "*CrossTenantAuthz*"` fails
with *"No tests found for given includes"*, which reads like the test does not exist. Use
**`:core-java:integrationTest`**.

#### A correction, because it nearly became a filed defect

`OpenApiConfig.java:51` still contains the line *"Dev fallback: Use `X-Tenant-Id` header…"*, and a
source read says SEC-03 is open. **It is not.** `TenantHeaderSchemeCustomizer` strips the scheme,
the global requirement, every per-operation requirement **and that prose bullet** at document-build
time when the filter is absent, with 3 unit tests. #440 closed it properly. **Do not re-file this
from a grep.**

#### The finding that was in no issue: six gates ran nowhere

Measured while checking SEC-04: of the 24 `scripts/check-*.sh`, **six had zero references in
`.github/workflows/`**. Three are deliberate — they inspect a running stack and could only ever
VOID on a runner. **Three were not**, and each had been written *because* a defect shipped:
`check-e2e-baseurl-contract` (#505), `check-playwright-mobile-contract` (#503),
`check-no-measured-placeholders` (27-04 D-05).

This is its own failure class, one level up from the usual one. The usual trap is *a gate passes
while the thing is broken*. Here **the gate is correct and never runs**, so "the gate is green" was
never even a claim anyone made — the property simply went unasked. Same shape as #510: the loopback
fix is real, and its gate is one of the three that genuinely cannot run in CI, so nothing stopped
anyone re-adding `0.0.0.0`.

Fixed in **#553**: the three are wired into `ops-contracts`, plus
**`scripts/check-gate-enforcement.sh`** + `scripts/gates/gate-enforcement.conf` — every
`check-*.sh` must run in a workflow **or** carry a reasoned exemption. Default-deny, self-covering,
and it **failed itself on its first run**. Repo is now **25** `check-*.sh` (26 counting
`docs-freshness.sh`, which is what H-1 asserts).

**Measuring this correctly needs three things**, all of which went wrong first:
`rg -uu` (`.github/` is hidden); the rc on its **own statement** (`grep …; echo "rc=$?"` reports the
echo's, and printed a false "wired"); and a **known-wired control** — `ci_refs=0` everywhere is
equally consistent with "nothing is wired" and "the probe is broken". After the fix,
`check-infra-exposure` correctly **stays** at 0, which is what proves the measurement still
discriminates rather than having become universally 1.

#### #554 — the STOMP shop gate was default-open

`TenantChannelInterceptor` gated shops with `KITCHEN_FEATURE.equals(parts[FEATURE_WORD])`. Correct
today, and **default-open**: a second shop-scoped topic inherits the tenant wall and skips the shop
check, silently. Now reads `StompDestinations.SHOP_SCOPED_FEATURES`, and
`StompShopGateCoverageTest` derives the shop-scoped set from the **factories** by reflection and
fails when the registry falls behind. Behaviour on today's tree is **unchanged**
(`Set.of("kitchen").contains(x)` ≡ `"kitchen".equals(x)`), which is why the control is the whole
suite — **133 classes / 952 tests / 0 failures / 0 errors / 1 skipped** — not the diff.

#### Three traps this session, all in the *verification*, not the code

1. **A break arm can silently not happen, and that reads as "the guard does not fire".** A
   `perl -0pi -e` substitution failed with a syntax error, the factory was never added, the test
   passed. Only asserting the break had landed (`factory present: 1`) caught it. **A break arm needs
   its own proof that the break happened.**
2. **`git checkout` after a break arm eats uncommitted work.** The #289 registry change was
   uncommitted; `checkout` restores from the **index** and would have discarded the fix along with
   the break. The break block was removed **by editing**, then hash-verified. (This trap is already
   recorded in this repo and it still nearly fired.)
3. **`grep` and `awk` silently failed to display very long lines.** Reviewing the metrics diff, both
   showed **2 of 5** changed lines — the three long prose lines vanished. Only `Read` showed them.
   Reported as "only the badge changed" it would have been wrong. **For a diff that matters, read
   the diff.**

Also: the background-task completion notice reports the **pipeline's** exit code. A
`gradle … | tail` that BUILD FAILED was announced as *"exit code 0"*. Read the artifact, not the
notification.

#### What is left in Phase 28

**#283 and #284 are one piece, not two.** #283 (replace the retained `auth == null` bypass with an
explicit `asSystem()` marker) is explicitly deferred as oversized — **62 no-principal test files**
plus every internal call path — and #284's fix shape depends on either that marker or
`SecurityContext` propagation. #284's guard-test half wants call-graph analysis, and **there is no
ArchUnit dependency in this repo**, so it needs that added or a narrower reflection check. Budget a
session, not a tail-end.

Also open from the disposition: **#549** (staging OpenAPI), **#550** (edge `/metrics`, measured
HTTP 200 with a 401 control), **#551** (audience-mapper audit), **#552** (rotation — needs the
owner, it touches real values). And two prevention items from the report that are still unbuilt: a
CI assertion enumerating tenant-scoped tables that fails when one lacks an RLS policy, and a gate
asserting no dev-only branch is reachable under `prod`.

### 0.-6 The nightly is GREEN — the first clean run this repo has produced (2026-08-05)

**Read this before §0.-5**, which predicts this outcome rather than reporting it.

```
run 30971049317   sha d4930719   total=180  passed=173  failed=0  skipped=7
run 30967157741   sha d4930719   total=180  passed=173  failed=0  skipped=7
```

Two consecutive runs on `d4930719` (#543). §0.-5's prediction — *"expect `failed=0`"* — **held**, and
the four surviving checkout failures were indeed the `.env.example` inline comment and nothing else.

**What this does and does not establish.** It establishes that the suite has a real CI baseline for
the first time: seven earlier runs produced *zero* test results, and the eighth and ninth produced
failures. It does **not** establish the card path — with `STRIPE_API_KEY` genuinely empty, checkout
takes the COD fallback, so `failed=0` here is consistent with the online path still never having
executed. That remains the Phase 30 owner decision. **A green suite over an unreachable branch is
exactly the shape this document keeps warning about; do not read it as payment coverage.**

**The 7 skips are declared and inside the budget of 8, but unverified** — filed as **#547**, the
successor to #420 (now CLOSED). The budget sits one below its ceiling, so the next skip added trips it.

**Gate sweep at this commit:** 21/25 `scripts/check-*` rc=0 and 6/6 k8s gates rc=0. The non-zero ones
were all branch-local and are cleared by this merge — `check-branch-behind-base` (1 behind),
`check-changelog-contract` (#543's entry lives on main), `check-handoff-contract` (the two rows this
commit corrects) — plus the two standing remedies that are not regressions: `check-alert-metrics`
rc=1 (`scripts/seed-order-metric.sh`, fires on every core-java recreate) and `check-e2e-skip-budget`
rc=2 VOID (stored report older than `frontend/e2e`; re-run the suite).

**Unfiled, found while measuring the above: 6 of 24 gates have ZERO CI references.** Three are
deliberate — they need a live stack and could only ever VOID on a runner (`check-infra-exposure`
part B, `check-container-config-drift`, `check-alert-mute`). **Three are purely static and nothing
runs them:** `check-e2e-baseurl-contract` (#505), `check-playwright-mobile-contract` (#503) and
`check-no-measured-placeholders` (27-04 D-05). Each was written to stop a *specific* defect
recurring, and none can fire on a PR — the same shape as SEC-04, whose fix (#510) is green while
nothing prevents someone re-adding `0.0.0.0` tomorrow. Measure it with
`rg -uu -l "<gate>.sh" .github/workflows/` and **capture the rc on its own statement** — the
`ci_refs=0` reading is an absence claim, so it needs `-uu` (`.github/` is hidden) and a
known-wired control (`check-changelog-contract` → `docs-freshness.yml`) to prove the probe can see.

### 0.-5 The nightly finally produced a number, and it found four things no gate could (2026-08-05)

**Read this before §0.-4.** That section describes the five-lane triage train; this one describes what
happened when the train's own work was finally tested by something that had never run.

#### The headline: there is a nightly baseline now, for the first time

`e2e-nightly.yml` had run **seven** times and produced **zero** test results — every run died building
the stack, on #517. #532 fixed that, and the eighth run completed:

```
run 30955236660   sha a769b597   total=180  passed=167  failed=6  skipped=7
```

**180, not 135.** The 135 figure elsewhere in this document is a *local* run of the spec files; the
nightly runs both Playwright projects, so it executes ~180 test instances. Do not compare them. The
row in §Live-stack that reads `127 passed / 8 skipped / 0 failed of 135` is a **local** measurement
and is labelled as such — it is not the suite's CI state and never was.

#### The 6 failures decomposed into three different kinds — this is the reusable part

Two of them were ours, one was a measured flake, and **four were a pre-existing defect that only the
nightly could reach**. Conflating those would have produced the wrong fix three times over.

| failures | test | verdict |
|---|---|---|
| 4 | `storefront-flows` checkout + Mailhog | **pre-existing**, not the train (#538) — but see "The SECOND nightly" below: #539 fixed the NPE and the tests still failed, on `.env.example` (#543) |
| 2 | `public-layout` modal shape | **#537 fallout** — see the stub hazard below |
| (+1 local only) | `storefront-flows:155` menu loads | **a real flake**, measured 10/25 and 7/12 |

**The first diagnosis was wrong and it is worth knowing why.** The modal test exercises the component
#533 rewrote to a Radix Dialog, so #533 was the obvious suspect. It is innocent. The cause is #537:
the spec stubs the API with `context.route("**/public/**")`, which intercepts **browser** requests
only. Once `/shop/[slug]` became a server component, the Next server's fetch stopped passing through
the browser, so against a live backend the fixture slug `test-kitchen` gets an authoritative 404 →
`notFound()` → **no dish cards exist** → `locator.click` waits out 60s. The failure screenshot is a
"Shop not found" page. Fixed in #540 (tests only — `notFound()` on a missing slug is correct).


#### The SECOND nightly, and why "fixed" was the wrong word for the checkout half

Run `30964857894` on `1112ff15`, dispatched after #539 and #540 merged:

```
total=180  passed=169  failed=4  skipped=7      (was 167 / 6)
```

**#540 worked** — both modal failures gone. **#539 worked too, at what it targeted**: the
`Order.getId() is null` NPE occurs **0 times** in this run's stack logs, against 2 in the previous
one. But the four checkout failures survived, with an identical symptom
(`getByRole('heading', { name: 'Order confirmed!' })` never appears) and a *different* cause:

```
java.lang.RuntimeException: Payment processing unavailable. Please try again later.
	at uk.jtoye.core.storefront.PublicStorefrontService.createGuestOrder(PublicStorefrontService.java:576)
```

That is the catch block rethrowing a `StripeException` — the code now persists the order, reaches
Stripe correctly, and fails because **CI had a Stripe key that was not a real one**.

**Where CI got a key from, given the workflow never sets one.** `e2e-nightly.yml` does
`cp .env.example .env`, and `.env.example` carried:

```
STRIPE_API_KEY=               # sk_test_... from Stripe dashboard
```

**Docker Compose treats an inline comment as part of the value.** Measured with a two-service probe:
that line resolves to `STRIPE_API_KEY: '# sk_test_... from Stripe dashboard'`, while a bare `VAR=`
resolves to `""` and `CONTROL_SET=realvalue` resolves normally — so the probe discriminates.
`isConfigured()` is `apiKey != null && !apiKey.isBlank()`, so a **comment was a credential**.

Four variables had that shape, all of them "leave blank unless you have a key" flags:
`ANTHROPIC_API_KEY`, `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`,
`NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY`. Fixed in **#543**.

**The correction worth carrying: the product was right on both runs and the environment was lying
about being configured.** #538 was a genuine defect and #539 genuinely fixed it — but the thing that
*surfaced* it, and then kept the test red afterwards, was `.env.example`. A run that fails twice for
two different reasons is not the same as a fix that did not work, and reading it that way would have
sent someone back into `PublicStorefrontService` for a third time.

**Still not established, and #543 does not change it:** with the key genuinely empty, checkout returns
to the COD fallback, so the **card path remains unexercised**. Proving it needs a real Stripe test key
— the same owner decision that gates Phase 30.

**Expect on the next dispatch:** `failed=0`. **This resolved — see §0.-6.** #543 merged as `d4930719`
and the next two runs both returned `failed=0`. The contingency below was not needed, and is retained
because it is the right first move if the suite ever goes red here again: if checkout is red after a
`.env.example` fix, the cause is a third one and the `.env` resolution should be checked first — read
the value the container actually got, not the file.
#### The hazard #540 worked around but did NOT fix — this scales, and it fails silently

**A browser-level API stub cannot describe a server-rendered route.** The consequence is worse than a
red test:

> **The public-surface gate was green exactly when CI had no backend.** Stack-free, core is
> unreachable → the server fetch fails → `getJson` catches → `defer` → the client island fetches →
> the browser stub answers → pass. Give it a real backend and the same spec fails.

A **second, quieter regression** rode along: the sibling `/shop/test-kitchen` layout test was
**passing vacuously** over that same not-found page — an empty page has no fixed-ratio boxes, no
images, no overflow, and does have an `<h1>`.

**#507 has 20 more routes queued for exactly this conversion.** Each one silently removes whatever
`context.route` coverage its spec had, and the failure mode is a vacuous pass, not a failure. This is
an unfiled structural decision about how the CI browser gate should work. **File it before the
conversions start.**

#### #538 — card checkout has never worked, only been unreachable

`PublicStorefrontService.createGuestOrder` called `paymentService.createPaymentIntent(order)` at
`:512` and `orderRepository.save(order)` at `:524`, and `PaymentService:126` is
`.putMetadata("order_id", order.getId().toString())`. So the id is dereferenced **twelve lines before
it is assigned** → guaranteed NPE → HTTP 500.

It is gated behind `isConfigured()` (`apiKey != null && !isBlank()`), and **every stack has an empty
Stripe key**, so checkout has always taken the COD fallback and the online path has never executed.
That is why it survived: the same test **passes locally** (COD) and **fails in CI**.

**This sits directly under a Phase-30 blocker.** The day a Stripe test key is added to any stack, the
first card order 500s. Fixed in #539 (persist → pay, `saveAndFlush` so the V24 unique index resolves
before money is asked for; rollback on a failed intent is the *correct* behaviour, not just the free
one — keeping the DRAFT row would strand the customer's idempotency key on an unpayable order).

Two adjacent money-path defects were fixed in the same PR because they are only *possible* once the
order has an id at that moment: the PaymentIntent id is now persisted to `order.payment_reference`
(without it WR-02's idempotent-retry re-fetch at `:346-357` is guarded on `paymentReference != null`
and can **never** fire — a retried card checkout returned a null client secret), and the Stripe call
now carries an `Idempotency-Key`, which the standing agent-readiness contract requires of any mutating
endpoint.

**What #539 does NOT establish, and nobody should read into it:** the card path is correct by
construction and by test, and has still **never executed against Stripe**. The artifact that closes it
is a test-mode `pi_...` id paired with the local order row. That needs a Stripe test key — an owner
decision, and the immediate follow-up.

#### Merge-train traps, second sitting

§0.-4 lists four. Three more, all of which cost a real CI cycle today:

5. **`check-changelog-contract` C-1 wants the PR's OWN number and agents write issue numbers.** #533's
   entry cited `(#446, #272)`, so the gate went **RED on main** the instant it squash-merged and had
   to be repaired from inside #534. Every subsequent PR was fixed pre-merge. Check this before merging.
6. **`docs-freshness --write` run during an UNRESOLVED merge inflates its own counts.** An unmerged
   path appears in the index **once per stage**, so a still-conflicted spec file was counted more than
   once — it reported `playwright 92/19` where the truth was `83/18`, exactly the 9 `test(` blocks of
   the conflicted file. Resolve and `git add` every source conflict **before** regenerating. Docs-only
   conflicts are immune, which is why #534/#535 were unaffected and green.
7. **A branch name contains `/`.** `> "$SCRATCH/merge-$BR.log"` fails *before* the command runs and
   bash reports the **redirect's** status as the merge's, so the merge silently never happens and
   everything downstream operates on the unmerged tree. Guard by asserting
   `git merge-base --is-ancestor origin/main HEAD` after any "successful" merge — never by the rc.

#### State as of this commit

Ten PRs merged in sequence: **#532 #533 #534 #535 #537** (triage train), **#539 #540** (nightly
fallout). Closed: **#517 #446 #272 #454 #105 #106 #447 #538**. Filed and open: **#536**
(`/dashboard/kitchen` CLS 0.2005 against a 0.1 budget, measured, in no lane's scope).

**Do not quote these figures without re-running them** — every one moved at least twice today:

| | |
|---|---|
| runtime | 4/4 FRESH after rebuild; #539 proven in the running jar (`Idempotency` ×2 in `PaymentService.class`, was 0, negative control 0); #537 proven by served HTML (`/shop/brixton-village-grill` 91,745 B, 1 `<h1>`, 1 canonical, 1 JSON-LD, 16 `og:`; `/robots.txt` 200, was 404) |
| local specs | `public-layout` + `storefront-flows` **52 passed** against the rebuilt stack — all three previously-failing tests green |
| nightly | run `30964857894` = **180/169/4/7**. #540 fixed; #539 fixed its NPE (0 occurrences, was 2); checkout still red on `.env.example`. **#543 is now MERGED and the prediction held** — see §0.-6 |
| Keycloak | dev drift removed — `:3102`/`:3103` gone from `jtoye-dev`/`core-api`, proven by rejecting those redirect_uris while `:3000`/`:3100` still return a login page. **Realms are `jtoye-dev` and `jtoye-customers`; a probe against `jtoye` returns `Realm not found`, which reads exactly like "no drift"** |


### 0.-4 The five-lane triage train (2026-08-04, latest) — and four traps inside the MERGE itself

**What ran.** All **62** open issues were triaged read-only by five specialist agents in parallel
(frontend / core-java / security / platform / product), each required to prove an issue was still
live **against the tree** rather than trusting its title. The correction being applied: the
2026-08-03 train selected issues for *not colliding*, which is close to the inverse of *user-visible*,
and the owner then looked at the running app and still saw every problem they had reported. So this
pass rated **user-visibility** as a first-class output.

**What the triage found before a line was written** — re-verify, do not quote:

- **~9 issues were already fixed** and needed closing with evidence, not work (#104's mobile sidebar
  landed in Phase 19 and is guarded by an e2e spec; all six of #99's named CI/CD defects have
  citations on the tree).
- **#487 and #488 were filed UNMEASURED and both measure to ZERO** — 0 cross-tenant promotion rows,
  0 bucket objects with GPS, 0 outside the content-type allowlist, each with a fail-direction
  control. **#487 additionally carries an outage warning**: narrowing `shop_promotions_read` the
  obvious way would return zero promotions on every public shop page.
- **#453 (P1 `bug`, "MANUAL_REVIEW is on no surface") is probably already built** — queue, resolver
  endpoint and vendor email all shipped 2026-07-14, **19 days before the issue was filed**. The
  council saw *"No applications waiting"* because the page renders that when both queues are empty.
  Verify with a seeded row and a second-tenant arm; do not close on a code read.
- **#460 is worse than filed**: beyond `geolocation` = 0 and `deliveryRadius` = 0, **nothing writes
  `shops.latitude`/`longitude`** — not the API, the UI, the seeder, or a migration default. So even a
  "decision-neutral distance sort" is unbuildable; the real first step is a coordinate capture path.
- **The nightly E2E had run 7 times and produced a test result ZERO times**, all dying on #517.

**What shipped.** Five PRs, one per lane: **#532** (fresh-DB Flyway), **#533** (dish-modal dialog
semantics), **#534** (dashboard corrections), **#535** (kitchen print + offline UX), **#537**
(server-rendered storefront + SEO). Closed: #517, #446, #272, #454, #105, #106, #447. **#536 was
filed**, not fixed — `/dashboard/kitchen` is over the 0.1 CLS budget and belongs to no lane.

**#517's mechanism is not what its issue says, and the difference matters.** A Postgres **placeholder
GUC resets to the empty string, not to unset**: a virgin session reads `NULL`, the same session after
a committed transaction-local `set_config` reads `''`, and `''::uuid` raises 22P02 where `NULL::uuid`
is harmless. So **all six** `is_local => true` call sites leave `''` behind — V44 is merely the first
one a fresh chain reaches before V46. A fix aimed at V44 alone would have been wrong. It fires only
when V44 and V46 land on the **same physical connection**, and `out-of-order=true` applied V46 before
V44 on every long-lived database — which is why no developer machine reproduces it and every fresh
one dies.

---

**Four traps that existed only inside the merge train.** None is in any lane's diff; each cost a red
CI run or a wrong artifact.

1. **`git add` BEFORE regenerating makes every local gate green about a file you will never push.**
   On #533 the index carried `metrics.json` at 2207 while the working tree carried 2230.
   `docs-freshness` rc=0 and `check-doc-metrics` 37/37 PASS — **both true, both about the working
   tree**. CI read the commit and failed correctly. **Rule: regenerate → stage → verify with
   `git show HEAD:<file>`, never from the working tree.**

2. **`check-changelog-contract` C-1 wants the PR's OWN number, and every agent wrote issue numbers.**
   #533's entry cited `(#446, #272)`, so the gate went **RED on main** the instant it squash-merged,
   and had to be repaired from inside #534. #534/#535/#537 were fixed pre-merge. **Any new entry must
   cite its own PR number.**

3. **"Keep BOTH sides" on `docs/CHANGELOG.md` is right for two DIFFERENT entries and wrong for the
   SAME entry that was edited.** #537's merge produced A1's heading twice — the branch's stale
   `(#446, #272)` copy as an **orphaned heading with no body**, plus main's corrected `(#533 …)` copy
   with the body. Keep-both still beats take-either (which silently deletes a release note), but
   **check for duplicate `### ` headings afterwards.**

4. **`rg` does not exist inside a script.** `rg` and `grep` are shell FUNCTIONS the harness injects
   and there is **no system ripgrep behind them**, so `rg` in a `bash script.sh` dies rc=127 — which
   is indistinguishable from "no matches found". It reported `CHANGELOG conflicted but no markers
   found` on a file with three markers. Use `awk` in scripts; `grep` is safe (it falls through to
   the real binary).

**Also:** a branch name contains `/`, so `> "$SCRATCH/merge-$BR.log"` fails **before** the command
runs and bash reports the *redirect's* status as the merge's — the merge silently never happened and
counts were regenerated on an unmerged tree. Guard it by asserting `git merge-base --is-ancestor
origin/main HEAD` after any "successful" merge, not by trusting the exit code.

**Two environment facts for whoever is next.** The Keycloak `jtoye-dev` `core-api` client gained
`http://localhost:3102/*` and `http://localhost:3103/*` as redirect URIs so lanes could verify against
the shared backend; these are dev drift and are due for removal. And note the realms are
**`jtoye-dev`** and **`jtoye-customers`** — a probe against a realm named `jtoye` returns
`{"error":"Realm not found."}`, which reads exactly like "no drift found".


### 0.-3 Branch/worktree cleanup, and two hazards that were asserted rather than measured (2026-08-04, later)

**The tree is now `main` only.** 31 local branches → **1**; 10 remote → **2** (`main` + the open
dependabot #523); 16 worktrees → **1**. `.claude/worktrees/` is empty. No unpushed commits anywhere.

The 15 `wave1/*` branches were **fully absorbed** into `main` and are deleted. Do not go looking for
them. That verdict was contested and had to be settled with the right instrument:

- `git diff main...wave1/x` reported **185–1080 insertions "not in main"** and was quoted as evidence
  of a gap in the merge train. **It is the wrong instrument** — three-dot shows changes made *on the
  branch*, and stays large whenever `main` moves on. It structurally cannot answer "is this in main".
- The question that can be answered: take every line the branch **added** relative to its own
  merge-base, and look for it in `main`'s current file. Result: **13/15 fully absorbed**; `ci-276`'s
  one straggler is `docker/login-action@…v4.5.2` where `main` already has **v4.6.0** (branch behind,
  not ahead); `k8s-298-299-303`'s two are `Reviewed omission` entries for `CORE_API_INTERNAL_URL` and
  `NEXT_PUBLIC_KEYCLOAK_URL` — and `main` **supplies both as real env entries** in
  `k8s/base/frontend-deployment.yaml` + the goldens, closing #292/#293. Merging them back would be a
  regression: re-documenting as "acceptable omission" two vars that are now set.
- **The instrument was falsified before it was trusted**: against `origin/main~25` the same check
  reports **6993/7194 added lines absent (97%)**, against current `main` **0%**. It can say
  OUTSTANDING at scale, so ABSORBED is a real verdict, not a check incapable of failing.

**`git branch -d` cannot retire a squash-merged branch and says so misleadingly.** It refused every
`wave1/*` as *"not fully merged"* while their content was demonstrably in `main`. `-D` is required,
and is only safe **after** a content proof — ancestry is the wrong authority here. Contrast the 15
`worktree-agent-*` branches, which `-d` accepted because they *were* true ancestors. Two branch
families, same repo, opposite correct tool.

**A destructive step invalidates the audit that preceded it.** Deleting the 8 merged `batch/*` remote
branches stranded all 15 `wave1/*`: their commits were reachable only *through* those branches, so
they silently became local-only. The pre-deletion unpushed audit said "clean" and stayed true only
until the delete ran. **Re-run Phase 13 after any branch deletion, not just before.** They were pushed
as backup, then deleted again once absorption was proven — that round trip was correct, not waste.

**Two hazards in the previous handoff's orbit were measured and did not survive:**

1. **"A GSD update wipes `~/.claude/hooks|agents|skills`"** — this is `update.md` *prose*, not observed
   behaviour. GSD's installer last ran **2026-07-27 12:15**; custom files older than that survived it
   with original mtimes (`block-git-commit.sh` 04-15, `warn-version-stragglers.py` 06-20,
   `block-secrets.sh` 07-14, `carl-hook.py` 07-25, `skills/ui-ux-pro-max` 03-04). `gsd-user-files-backup/`
   was never refreshed on 07-27, so the backup step did not even run. Custom hooks were moved out to
   `~/.claude/guard-hooks/` on the strength of the prose, then **reverted** — `settings.json` is back to
   its pre-move hash `0d20e0fb`. Do not redo this. Agents/skills *cannot* be relocated anyway: they are
   found by directory convention, with no path registration in `settings.json`.
2. **`feature/faster-integration-tests-parallelism` was NOT orphaned work** — see §3, row corrected.

**One live-config change did land** (outside this repo): `block-main-branch.sh` now permits branch
**deletions** from `main` — narrowly, never for `main`/`master`, never alongside a commit/merge.
Shipped in dotfiles PR #64. It had a real bug on first use in anger: newline-splitting ran *before*
backslash-continuation folding, so every wrapped multi-branch delete was blocked. Fixed; harness
30 → 38 cases with 3 break arms (12/10/4 failures, each isolating one clause).

### 0.-2 The backup pipeline could not restore, and nothing in the repo could see it (2026-08-04)

Dependabot #525 bumped `infra/backups/Dockerfile` to `postgres:18-bookworm` while every server in the
tree stayed on **15.17**, and **every CI check was green**. Measured against the live server:

| | |
|---|---|
| `postgres:18` `pg_dump -Fc` against 15.17 | works — 469,421 bytes |
| `postgres:15` `pg_restore --list` on that dump | **rc=1** `unsupported version (1.16) in file header` |
| `postgres:15` on a pg15 dump (control) | rc=0 |
| `postgres:18` on a pg15 dump (direction) | rc=0 |

Tooling reads its own major and **older, never newer**. Backups keep succeeding and stop being
restorable by the server's own client — visible only during a recovery.

**Two things now close it, both in #529:**

- `scripts/check-postgres-major-parity.sh` — 25th gate, in `ops-contracts`. 8 declared sites in
  `scripts/gates/postgres-major-parity.conf`. Anchored on line prefixes, **not** a bare `postgres:`
  token, because `docker-compose.full-stack.yml:130` holds `jdbc:postgresql://postgres:5432/` (a bare
  token extracts **5432** as a major) and `infra/backups/Dockerfile:4` holds a *historical*
  `postgres:15-alpine` comment that must stay 15 forever.
- `scripts/restore-drill.sh` — **the first restore rehearsal this repo has run.** 40 tables / 8095
  rows / flyway 60/60 into a throwaway server. Deliberately not `check-*` (needs a live DB + Docker).

**`pg_restore --list` was never evidence.** It reads the archive HEADER and loads zero rows, and
`k8s-backup.sh:66` runs it *inside the image that produced the dump* — agreeing by construction twice.

**The drill is built around RLS blinding the verifier.** A count as a non-BYPASSRLS role with no
tenant GUC returns fewer rows silently, rc=0; count both sides that way and `0 == 0` passes over a
restore that loaded nothing. Defences: both sides BYPASSRLS, a `MIN_ROWS` floor, and a **control**
(`media_asset_aud`: BYPASSRLS **2224**, unpinned **741**).

WARNING — **`DB_USER` means two different things.** `.env` `DB_USER` = `jtoye_app` (**not** BYPASSRLS);
the CronJob's comes from secret key **`backup-username`** = `jtoye_backup`. Using the wrong one makes
`pg_dump` fail with *"query would be affected by row-level security policy for table customers"*,
which reads like a production fault and is not one. The drill asserts the role attribute first.

**#525 is not wrong forever — it is out of order.** That bump is REQUIRED to perform a 15 to 18
upgrade (the logical path dumps with the new tooling). PostgreSQL 15 is supported to **2027-11-11**,
18 to 2030-11-14. Bring it back with the server, the tag, the CronJob and both horizon rows moving
together, and run the drill before and after.


### 0.-1 The Wave-1 merge train (2026-08-04) — and the defects that existed ONLY in the merge

Six PRs merged in one sequence. `main` ended at `a9fb05bc`; **24/24 repo gates, 6/6 k8s gates,
127/135 E2E passing against a re-synced runtime.**

| PR | lane | closes |
|---|---|---|
| #522 | Lane C — a11y, `--primary` → orange-700 | #451 |
| #521 | Lane D — k8s, render-only | #293, #506, #271, #298 |
| #515 | the nightly E2E credential faults | refs #420 |
| #520 | Lane E — docs/CI, gate count 22 → 24 | #276, #337, #449 |
| #519 | Lane A — core-java | #278, #483, #489, #498, #501, #502 |
| #518 | Lane B — frontend | #295, #306, #490, #495, #504 |

**Two defects existed only where branches met. Neither branch's CI could see either, and no test
caught either — both were caught by a gate that only became capable of seeing them mid-train.**

1. **Two allowlist entries went stale on contact.** #298 widened the env-contract gate carrying
   reasoned entries for `CORE_API_INTERNAL_URL` and `NEXT_PUBLIC_KEYCLOAK_URL`, which no manifest
   supplied. #293/#506 — a *different branch* — then supplied exactly those two. Merged, the gate
   went `rc=1` with **zero contract violations**. Both entries' stated REASONS were falsified too:
   `CORE_API_INTERNAL_URL`'s claimed absence cost "a hairpin through the ingress, not a 502", but
   Next inlines `NEXT_PUBLIC_*` into the **server** bundle, so the fallback had already frozen to
   `http://localhost:9090`. It was a 502 path.
2. **`APP_PUBLIC_ORIGIN`** (Lane B × Lane D). Lane B added the reader; Lane D widened the gate to the
   frontend. Fixed by a **reasoned allowlist entry, not by supplying it** — it sits at the head of a
   *fallback* chain (`public-origin.ts:87`) and absence falls straight through to `NEXTAUTH_URL`,
   which the manifest already supplies from `app-config/frontend.url`. Injecting it would have made a
   second source of truth for one origin.

**`docs/metrics.json` conflicted on EVERY lane and NEITHER SIDE WAS EVER RIGHT** — Lane E: ours 2093
/ theirs 2106 / truth **2107**; Lane A: ours 2142 / theirs 2107 / truth **2157**; Lane B: **2202**.
Each lane increments a different counter (Java / Go / Jest), so "take ours" and "take theirs" are
both wrong every time. The only correct move is `scripts/docs-freshness.sh --write` on the merged
tree, then re-sync the prose. **The same conflict carried README's build badge, and which side was
correct FLIPPED mid-train** once Lane E's 404-repo fix landed on main — a blanket resolution rule
would have silently reverted it.

**Three instrument failures worth carrying:**

- **A gate sweep globbing `scripts/check-*.sh` silently omits `k8s/scripts/`** — the six gates a k8s
  change actually exercises. This was hit in Lane D, written into that changelog entry as a lesson,
  and then **repeated two lanes later on Lane B**, where CI caught a real violation that should have
  been found locally. Writing the lesson down did not prevent the repeat; the resume block now runs
  both sweeps.
- **`rg` died mid-session with `claude native binary not installed` and the `|| echo` fallback
  printed a clean result.** A search that cannot run is indistinguishable from a search that found
  nothing. Use `git diff --name-only -- <pathspec>` with a **positive control** proving the query can
  return something.
- **A skip count with no credentials is not a measurement.** The first E2E run reported 48 skipped /
  21 undeclared — an artefact of not sourcing `.env`, not a regression. See the E2E row above.

**Five issues did not auto-close**: `Closes #A, #B, #C` closes only **#A**. #506/#271/#298 were closed
by hand; **#299 and #303 remain OPEN on purpose** — Lane D only made them *visible* as `OPEN DEFECT`
allowlist entries. **#299 is a live production gap** (customer-storefront realm unconfigured in every
k8s environment).

**Still true after the train:** Lane D's k8s work is **render-verified only** — no cluster exists
(no kind, no k3d, the `minikube` profile `jtoye` has no container, and the only kubectl context is the
employer's HS2 AKS). **The CrashLoop #271 describes was never demonstrated**, and #297 (Calico) stays
out. **#517 remains the blocker for #420** and is intermittent (2 of 3), so one green fresh-DB boot
proves nothing.

### 0.0 The parallel-agent run of 2026-08-03 — and the seven findings that were WRONG AS FILED

Eight specialised agents in isolated worktrees, assembled into **three** PRs, **12 issues closed**.
The batching was the point: the `Integration Tests (Testcontainers RLS)` job is path-filtered to
`core-java/**` and measured **45 min** on #509. Five backend issues went into that one run instead of
five; #508 and #510 reported the same job at **0 min**, path-skipped. Frontend-only PRs cost ~3 min
total, so **batching is worth it for `core-java/**` and buys nothing elsewhere.**

**The single most transferable result: SEVEN filed claims were falsified while being worked.** Not one
was caught by a test passing — every one came from running the fail direction first.

| issue | what the filing said | what was true |
|---|---|---|
| **#484** | `unless="#result == null"` cannot fire for `Optional.empty()` | **Premise false.** Spring unwraps the Optional *before* evaluating `unless` (`CacheAspectSupport:600-601` → `:552` → `:897`). Its recommended fix throws `EL1004E` on every SUCCESSFUL lookup and disables the products cache — **and the issue's own acceptance criterion would have gone GREEN on that broken tree**, because a disabled cache also holds zero entries. Closed as invalid; a regression guard shipped instead |
| **#444** | replay 404s for the same reason as the log | **Half true, and the false half is the dangerous one.** Un-keyed replay was broken; **keyed replay PASSED on the unfixed tree** — and the keyed path is the one the frontend api-client uses, since it auto-retries with a key. A fix validated only there would have gone green over a live defect |
| **#444** | `TenantSetLocalAspect` "never fires" | It *does* fire, then returns early on `!isActualTransactionActive()`. `SimpleJpaRepository` opens its transaction **inside** the Spring Data proxy, after the advice returned. That is why annotating the *caller* fixes it and "make the aspect pin harder" would not |
| **#440** | unauthenticated spec survives to **production** | **False.** `OpenApiConfig` is `@Profile("!prod")`, prod sets `api-docs.enabled: false`, and anonymous reads need `looksLocal && !isDeployedProfile`. The real exposure was **staging**, which the finding never mentions |
| **#448** | 105 responses point at success DTOs | **Misread its own number.** 105 is the *total* 4xx/5xx; **96** pointed at success DTOs, 9 declared no body. Two sub-claims also false |
| **#500** | 3 bare `notFound()` sites in one controller | **12 sites across 7 controllers.** Fixing only the named one would have made #448's spec promise a body that 9 other sites never send |
| **#463** | `/shop` is a server-rendered 12 ms control | **False.** `frontend/app/shop/page.tsx:1` is `"use client"` and fetches on mount — the 12 ms was the HTML shell. So there was **no** server-rendered control in the comparison, and the owner's *"the same applies to all pages"* is **broader** than the issue recorded (#507) |

**This is now the fourth, fifth, sixth and seventh instance of the pattern §2.1 already records twice**
(SEC-01/A1's falsified root cause, F-M7/#442's falsified location). **Re-verify before implementing is
not advice here; it is the difference between a fix and a no-op over a live defect.**

### 0.0.1 Parallelism: what the previous handoff got wrong, and the two rules that made it work

§2.6 said *"do not parallelise this cluster."* **The file sets refute it.** The only genuine collision
was #467 ↔ #463, which both rewrite `frontend/app/shop/orders/page.tsx` — those went to one agent.
#459 and #458 are disjoint from those and from each other, and **all branches merged with zero
conflicts**. Check the actual file sets before declaring a cluster unparallelisable.

Two coordination rules did the real work, and both **prevent** conflicts rather than resolving them:

1. **No agent may touch `docs/metrics.json` or `docs/CHANGELOG.md`.** Regenerate once per lane at
   assembly. A per-branch edit collides on the same lines and silently deletes a sibling's.
   Corroboration worth keeping: in both lanes the agents' *independent* predictions summed to exactly
   what `docs-freshness.sh --write` produced (+15/+25/+10 → 548; +9/+9/+4/+19 → +41).
2. **Where two agents must share a file, give each an explicit region.**
   `docker-compose.full-stack.yml` was split `environment:` (#508) vs `ports:` (#510) — **merged with
   no conflict.**

### 0.0.2 Four traps this run hit in practice

- **`Closes #A, #B, #C` closes only #A.** GitHub needs the keyword before *each* reference. #508 read
  `Closes #459, #463, #467`; #459 closed and the other two silently did not. **Check issue state after
  a merge** — and note `gh issue view` lags a merge by seconds, so a stale OPEN read may just be lag.
- **A line-number citation breaks whenever anything above it moves, and the shifts COMPOUND.** mailhog
  was cited at 541; it became 552 after #508, 591 after #510 alone, **602 combined**. Re-point by
  locating the cited *subject*, never by applying an offset.
- **`rg`/`grep` do not exist inside `bash script.sh`** — they are shell functions. A citation-repointing
  helper reported success while changing **nothing** (`git status` empty), and a disclosure sweep
  returned a confident 0 from `command not found`. Use `/usr/bin/grep` inside scripts, and seed a
  control.
- **Docker's `LastTagTime` is UTC; `git log %cI` is local.** An ad-hoc staleness comparison called
  edge-go STALE on a **59-minute** gap that was purely the offset. Normalise to epoch — or just run
  `check-runtime-freshness.sh`, which already does.

**And the process miss worth repeating:** the CI `docs-freshness` job runs **seven** scripts, not the
three obvious ones. Verifying `docs-freshness` + `check-doc-metrics` + `check-doc-citations` locally
and calling it green missed `check-handoff-contract`, which then went red in CI. **Run the workflow's
real step list.**

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
| Group B → Phase 28, **SEC-02 filed; #442 now CLOSED** | **#438** F-C2 dev Postgres bind · **#439** F-C3 Grafana default creds · **#440** F-H2 spec advertises a tenant-override header · **#441** F-H10 infra port binds + mail archive · **#442** F-M7 actuator/OpenAPI/edge — **CLOSED** by PR #472, and two of its three claims were FALSIFIED (§2.1). The other four OPEN, `security` + P1/P2 labelled, **deliberately sanitised** (§2.1) |
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

**SEC-02 is COMPLETE as of 2026-08-03: all five Group B findings are CLOSED** — **#438 is CLOSED**,
**#439 is CLOSED**, **#441 is CLOSED** (all PR #510), **#440 is CLOSED** (PR #509), **#442 is CLOSED**
(PR #472). The audit is no longer one `rm` away from being lost, and it is no longer outstanding.

**#440's finding was partly FALSIFIED when it was worked** — a third instance of the pattern this
document already records twice. *"Survives to production"* is **false**: `OpenApiConfig` is
`@Profile("!prod")` and prod sets `api-docs.enabled: false`. *"Unauthenticated"* is **false** for a
deployed environment: anonymous spec reads are permitted only when `looksLocal && !isDeployedProfile`.
What was genuinely exposed is **staging** — which the finding never mentions. Re-verify before
implementing; the filed location was wrong, exactly as F-M7's was.

**#438, #439 and #441 are being closed by PR #510**, which binds every infra port to loopback behind
`${JTOYE_BIND_HOST:-127.0.0.1}` and rotates the monitoring credential live. Note its gate,
`scripts/check-infra-exposure.sh`, is **not wired into CI** — part of it needs a live broker, so it
could only ever VOID on a runner, the same reason `check-runtime-freshness` stays out. **Nothing
currently stops someone re-adding `0.0.0.0` in a PR.**

**They are deliberately sanitised, and that is a constraint on whoever works them.** This repository
is **public**, which is the same reason `SECURITY-FINDINGS.md` was git-excluded. The issues carry the
component, the problem class, the scope, the fix direction and falsifiable acceptance criteria — but
**no reproduction commands, no port/credential pairings and no role attributes**. Verified after
filing by scanning **GitHub's stored bodies**, not the local drafts, with a control token proving the
scan was not blind. The detail lives in `.qa-council/disc-20260802-121732/evidence/sec-findings.md`.
**Do not paste repro steps into these issues when working them.**

**#442 is CLOSED (PR #472) — and the reason to keep reading it is now different.** This section used
to say F-M7 was the one Group B finding reaching production, because its `permitAll` entries were not
profile-gated. **That reasoning was half wrong, and re-verifying before implementing is what caught
it.** Two of its three claims were falsified:

- *metrics unauthenticated in prod* — **FALSE**: prod binds actuator to a separate
  `management.server.port` and the k8s Service publishes only the app port. An existing test already
  proved it both directions. Implementing the filed fix would have authenticated an unreachable
  endpoint.
- *OpenAPI unauthenticated in prod* — **FALSE for the default config**: springdoc is off there
  (`SWAGGER_ENABLED:false`). Gated anyway as defence in depth, recorded as such.
- What was genuinely exposed: the **edge gateway**, and **staging** — which the finding never
  mentions, and which had no management port, `show-details: always`, *and* springdoc explicitly
  enabled.

**The transferable lesson, now twice in this file:** a council finding names a symptom and guesses a
mechanism. SEC-01/A1 had a falsified root cause; F-M7 had a falsified location. Re-verify, then fix
what is actually true — and say which parts were wrong rather than quietly fixing something else.

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
| **#458 is OPEN** (PR #508, 2026-08-03 — **partial**) | 1a, 2, 3, 4 | Nav gating shipped, desktop **and** mobile sheet, plus `/track` auto-population. **Stays open for the dispatch notification**, which is not a copy change: `OrderStatus` has no `DISPATCHED` value and `OrderStateMachineConfig` has no such edge. That gap surfaced a separate live defect — DELIVERY customers emailed *"ready for collection"* — filed as #502 |
| **#459 is CLOSED** (PR #508, 2026-08-03) | 6 | Cart payloads now carry the owning `sub` and are cleared on sign-out; anonymous carry-forward preserved. The naive fix — clearing in `clearMarker()` — satisfies the headline criterion and **breaks** anonymous carry-forward and the post-order clear, demonstrated by break arm |
| **#460 is OPEN** | 7 | **No concept of locality.** A phase, not a patch. P1 |
| **#461 is OPEN** | 8, 10 | No payment processing; pay-on-collection must become channel-issued payment links. P1 |
| **#462 is OPEN** | 11 | No second factor, no verified contact channel |
| **#463 is CLOSED** (PR #508, 2026-08-03) | 5 | `/shop/orders` is now a server component: time-to-content **2562 ms → 1001 ms**, CLS **1.0149 → 0.0052**, client fetches on load **1 → 0**, at the repo's own throttled mobile profile. Its premise was wrong — `/shop` is **also** `"use client"`, so there was no server-rendered control in the comparison; the systemic half (20 more pages) is #507 |
| **#467 is CLOSED** (PR #508, 2026-08-03) | — | The orders API 502 rendered as *"No orders found"*. Config alone could not fix it: `NEXT_PUBLIC_API_URL` is inlined at build time into the **server** bundle too, so only a non-`NEXT_PUBLIC_` variable works. k8s has the same shape and is UNMEASURED — #506 |

> ⚠ **#463 and #467 had to be closed BY HAND.** #508's body read `Closes #459, #463, #467`, and GitHub's
> auto-close requires the keyword before **each** reference — a comma-separated list after one `Closes`
> closes only the first. #459 closed; the other two silently did not. The same shape was sitting in two
> sibling PRs and was fixed there before merge. **Check issue state after a merge; do not assume the
> body did what it reads like it did.**

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

### 2.5 The eight-PR train fixed ten issues that are all INVISIBLE at this data scale

**The owner looked at the running app after the train merged and still saw the problems they had asked
about.** They were right, and the reason is a selection error worth not repeating.

The brief was "issues resolvable without colliding with each other or a concurrent session."
Non-collision selects for *small and isolated*, which is close to the inverse of *user-visible*. The
result: ten issues CLOSED, none of which change what anyone sees in normal use.

| Issue | Why it is invisible today |
|---|---|
| #302, #274, #418, #287 | CI / infrastructure only |
| #279 | forward-looking hardening — no field rendered today was ever vulnerable |
| #390 | only observable in a delete/edit race |
| #288 | needs a non-GROUP_ADMIN with ZERO shop grants; `shop_staff` has 2 rows, both GROUP_ADMIN/JIT |
| #290 | needs a `user_directory` row with NULL/empty `display_name`; all 4 rows have one |
| #282 | the cap was 200 and the tenant has 4 shops |
| #445 | forward-only; existing objects keep their raw bytes, EXIF and client-declared Content-Type |

Measured against the dev DB on 2026-08-03, not assumed. **This is also why the browser verification of
#476 had to force all three states** — two by Playwright route interception, one via the PR's own
`NEXT_PUBLIC_SHOPS_PAGE_SIZE=2` knob against the real backend. Nobody has yet seen any of the three
arise from real rows; a DB-seeded run is still owed.

**Nine follow-ups were filed** for work the agents found and correctly refused to do: 483, 484, 485,
486, 487, 488, 489, 490, 495. Two carry warnings that matter more than the fix:

- **483 says do NOT apply #287's fix to `SyncService`.** It carries the identical
  `@CacheEvict(allEntries = true)`, but that path genuinely upserts (`findByName`/`findBySku` +
  `orElseGet` at `SyncService.java:90,105`), so removing the eviction there ships stale reads. Only
  its *radius* is wrong.
- **487 is UNMEASURED and says so in its title.** Its first step is a read-only query, not a fix.

### 2.6 What the next session should actually pick up

**Superseded on 2026-08-03 — this section's advice was acted on, and one line of it was wrong.**

Of the seven the owner reported by USING the app: **#467, #463 and #459 are CLOSED** (PR #508),
**#458 is partially done** (nav gating shipped; dispatch notification deferred). **#460, #461 and
#462 remain OPEN and were deliberately not staffed** — none is an engineering task yet. #460 needs an
ADR, #461 is blocked on Stripe test-mode keys, #462 needs a product decision; two of those three are
already §4 blocking decisions. Putting agents on them would have produced code prejudging decisions
that have not been made.

**"Do not parallelise this cluster" was overbroad, and the file-level evidence refutes it.** The only
genuine collision was #467 ↔ #463, which both rewrite `frontend/app/shop/orders/page.tsx` — those two
went to a single agent. #459 (`cart-provider.tsx` + `customer-auth.ts`) and #458 (`storefront-nav.tsx`
+ `app/track/page.tsx`) are file-disjoint from those and from each other, and all three branches
merged with **zero conflicts**. Check the actual file sets before declaring a cluster unparallelisable.

**What was right, and is worth keeping:** *verify each in a real browser against the live stack rather
than trusting jsdom.* That is what turned #458's nav work into the discovery that no `DISPATCHED`
state exists (#502), and what proved #459's naive fix breaks anonymous carry-forward.

**Two coordination rules the parallel run established**, both of which prevented conflicts rather than
resolving them: agents were barred from `docs/metrics.json` and `docs/CHANGELOG.md` (regenerated once
per lane at assembly instead), and where two agents had to share a file they were each given an
explicit region — `docker-compose.full-stack.yml` was split `environment:` vs `ports:` and merged
clean.

---

## 3. Carried forward — still true, not re-measured unless noted

- **#418 is CLOSED, its mechanism is now known, and this document had it wrong.** The line that used to
  sit here said the suite does *not* race its own `@Scheduled` flusher, because `@DynamicPropertySource`
  parks both intervals at 24h. **That reasoning is refuted by PR #480**, now merged:
  `@Scheduled(fixedDelayString=…)` leaves `initialDelay` at **0**, so the first execution fires
  at context refresh *regardless of the interval*. Parking suppresses the second run onward, never the
  first — a probe with both intervals at 86400000 still found **10 live scheduled tasks**. The earlier
  supporting evidence was vacuous too: a flush pass over an empty tenant list logs nothing, so "no
  scheduled trace in the failure window" was never absence of execution. Amplified reproduction:
  300 samples → 72 failures, then 25; with the fix and the amplifier still on, 300 → **0**.
  `NoScheduledTriggersTestConfig` removes the `internalScheduledAnnotationProcessor` bean so nothing is
  ever scheduled; no sleeps, no widened timeouts, no production code touched.
- **A merge with no changelog entry reddens every *other* open PR, not the one that caused it.**
  `check-changelog-contract` ranges over **merged history** (`FLOOR..origin/main`) while reading the
  changelog from the **branch's** copy. #473 and #475 merged without entries, so from that moment every
  open PR failed the required `docs-freshness` job with `C-1 PR #473 … no entry` — a failure naming a
  PR the author had never touched. **#491 is CLOSED** and backfilled both entries. Three consequences
  worth keeping: the gate is satisfiable from inside any PR (it reads *your* changelog), so it is **not**
  the "gate forbids its own remedy" shape; a merge train must add the entry **in the merging PR**,
  because the cost lands on everyone else the instant it merges; and **two sessions diagnosed and fixed
  this independently within the hour** — the second only discovered the first when `gh pr merge`
  refused. Before writing a fix for a *shared* red gate, re-read `origin/main`.
- **The standing policy that came out of it: every feat/fix PR carries its OWN changelog entry, added
  before it merges.** The gate keys on the **squash-merge subject's trailing `(#NNN)`** —
  `PR_RE='\(#([0-9]+)\)$'`, anchored to end of line — so a branch whose subject ends `(#279)` will be
  demanded as **#478**, its merge number, not its issue number. Verified, not assumed:
  `grep -oP '\(#([0-9]+)\)$' <<< '…(#418) (#480)'` returns `(#480)`. The number exists the moment
  `gh pr create` prints it, so "you cannot write it before the PR exists" is false — only "before the
  PR is *opened*" is. Each entry in the #480/#474/#478 train was falsified by building the squash
  commit locally (`git merge --squash` + a commit carrying the predicted subject) and running the gate
  with `CHANGELOG_BASE_REF` pointed at it: clean → PASS, citation mangled → **FAIL naming that exact
  PR**, restored → PASS. Note the gate **cannot** check the citation pre-merge from the branch itself
  (its range ends at `origin/main`, where the PR is absent), so a green run on the branch proves
  nothing about it — the simulation is the only real evidence.
- **"Resolve doc conflicts by taking main's copy" is WRONG for `docs/CHANGELOG.md`, and nothing
  would have caught it.** The blanket rule is right for `AGENTS.md`/`CLAUDE.md`/`README.md`/
  `docs/metrics.json`, which are regenerated straight afterwards. It is wrong for the changelog:
  once a sibling PR merges, both entries want the same insertion point under `## [Unreleased]`, so
  taking main's copy **silently deletes your own entry**. Hit on #476's re-rebase after #479 landed.
  The gate cannot see it — its range ends at `origin/main`, where your PR is absent — so the branch
  stays green and the omission only surfaces *after* merge, reddening everyone else. Resolve that one
  file by taking main's copy and **re-inserting** your entry, then assert both citations by content
  (`grep -c '(#476)'` and `grep -c '(#479)'` each `1`) before continuing the rebase.
- **Never run a gate from the main checkout — it is usually BEHIND `origin/main`.**
  `check-changelog-contract` resolves its commit *range* from `origin/main` but reads
  `docs/CHANGELOG.md` from the **working tree**. Run from a tree two commits behind, it compared a
  current range against a stale file and reported #474 and #480 as uncited — both of which were
  present and correct. Measured 2026-08-03; the same run from an up-to-date tree was rc=0, 30 of 30.
  A gate reading a stale tree fails in whichever direction the staleness happens to point, which is
  **worse than not running it**, because the output looks authoritative. Before trusting any verdict,
  pass or fail, assert the tree's identity: `git rev-parse HEAD` vs `git rev-parse origin/main`.
- **A new E2E spec landed un-run, and the skip-budget gate caught it.** #456 added
  `frontend/e2e/marketing-dish-scroller.spec.ts`; `check-e2e-skip-budget` now returns **rc=2 VOID**
  because the stored report is older than the specs it describes. That is the gate working — a stale
  report certifying a skip set that no longer exists is a documented trap here. It is also the most
  concrete argument yet for dispatching the nightly job below: it would have run this spec.
- **#420 is CLOSED** (2026-08-05). Its CI half is satisfied: `e2e-nightly.yml` now runs and is green —
  see §0.-6. Its successor is **#547** (the 7 declared-but-unverified skips). The line this replaced
  read *"has still never run"*, which was true when written and stopped being true two days later.
  Per-PR CI still runs 2 of 126, and that half is unchanged. Corollary still live: `Integration Tests`
  passed in **6s** on #435 — path-filtered, a skip wearing a tick. The same job took **43m51s** on
  #434, which is what running it looks like.
- **The refund E2E stays skipped deliberately** — `Stripe.Refund.create` with an empty key. Needs keys,
  not a fixture.
- **`NoOrdersCreated` goes blind after any rebuild that recreates core-java.** Remedy:
  `bash scripts/seed-order-metric.sh`. Hit twice this session. Expect it every time.
- **Fixtures decay by design** — seeds write every instant relative to now. Re-run the seed before
  suspecting the product.
- **No `v2.3` git tag** — latest is `v2.2` while `build.gradle.kts` reads `2.3.0`. GTM-01.
- **`financial_transactions.order_id` has no FK to `orders`**; 3 rows point at deleted orders.
- **Toolchain: 3 DRIFT + 1 UNKNOWN**, none applied — **re-measured 2026-08-04** (was 2 DRIFT on 08-03;
  the conda target moved and copilot appeared). `conda` 26.1.1→**26.7.0**, `ms-fabric-cli` 1.2.0→1.6.1,
  `@github/copilot` 1.0.77→1.0.78. `antigravity` is UNKNOWN because it is a **manual** channel the probe
  cannot query — a recorded decision, not a gap. `docker-ce` restarts the daemon — stack down first.
  Housekeeping surfaces drift and does not converge it; apply via `update.sh --tier N` deliberately.
- **`.claude/worktrees/` was not gitignored: 9 live agent worktrees, 70,498 untracked files**, each a
  full working copy holding one of the merge train's branches. A plain `git add .` in this checkout
  staged all of it. Fixed in **#482**, scoped to `.claude/worktrees/` and **not** `.claude/`, because
  the project convention reserves `.claude/skills/` for tracked project skills.
  **As of 2026-08-04 the directory is EMPTY** — all 15 worktrees removed (see §0.-3). Each was verified
  clean (0 uncommitted, 0 untracked) and unheld by any process (`lsof +D`) *before* removal, because the
  absorption analysis that cleared their branches examined **commits only** and is blind to a dirty
  working tree. The gitignore fix stays: the hazard returns the moment an agent run recreates them.
  **The habit matters more than the fix.** The same hazard fired earlier the same day at 369 lines
  through a *named-path* `git add AGENTS.md`, which merged another session's uncommitted work as
  #469 and had to be reverted by #470 — a named path proves *which file*, never *which lines*. So:
  **`git diff --staged` before every commit here**, and treat `N insertions / 0 deletions` on a file
  you only edited as content that arrived from someone else.
- ~~**Orphaned and worth someone's attention: `feature/faster-integration-tests-parallelism`**~~
  **RESOLVED 2026-08-04 — it was not orphaned work, it was superseded work, and it is deleted.**
  Its single commit `c142b90c` is the *earlier unpushed attempt* that **#512 (`d95239dc`) explicitly
  supersedes** — #512's own message names it: *"an earlier unpushed attempt used
  `availableProcessors()/4` … INERT ON CI"*. Pushing it would have opened a PR that **conflicts** in
  `core-java/build.gradle.kts`, and whose only merge-in-its-favour reverts the divisor `/2 → /4`,
  re-breaking the speed-up on CI. Proven before deleting: `git merge-base --is-ancestor` → not an
  ancestor (so genuinely not in `main`), `git merge-tree` → CONFLICT on exactly the lines #512 rewrote,
  and `main` line 218 already reads `availableProcessors() / 2`. The 47-minute measurement stands; #512
  is the fix for it.

---

## 4. Carried forward from #430 — the blocking decisions

Phases 29–32 do not start until these land. **None are engineering tasks.**

| decision | state |
|---|---|
| **Production domain** | **SETTLED 2026-08-04, measured — the old row was wrong.** `jtoye.co.uk` **is registered** (owner-confirmed; NS `dns1/dns2.registrar-servers.com`, A `162.255.119.30`) and **resolves**: `http://jtoye.co.uk` → **200**, redirecting to `www.` on `72.251.11.125`, with an **empty `<title>`** — a registrar placeholder, not the app. **`https://` FAILS: no TLS cert.** So the blocker is no longer registration; it is **TLS + repointing DNS at real hosting**. Separately still true: **`olajay.co.uk` resolves to nothing** (`dig +short A` empty, with `google.com` answering on the same run to prove the resolver works), and `FRONTEND_PUBLIC_*` still point at it. Do not flip `DEPLOY_*_ENABLED` on "it's registered" — a 200 from a parking page is not a deployment target. |
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
- ⚠ **CORRECTED 2026-08-05 — the row below is STALE and was measured before the 2026-08-04/05 trains
  ran.** Actual now: **15 local branches**, **8 worktrees** (7 under `.claude/worktrees/` plus the
  main checkout), **16 jtoye containers**. All 7 worktree branches correspond to PRs that have since
  merged (`feat/507-447-…`, `fix/450-454-…`, `fix/checkout-payment-intent-ordering`,
  `fix/517-…`, `fix/446-…`, `feat/105-106-…`, `fix/nightly-e2e-…`) plus 7 `worktree-agent-*`
  branches, so they are retirable — but retire them **by content**, not by `git branch -d`, which
  calls a squash-merged branch "not fully merged" (§0.-3 records why). This is the recurring shape
  this document keeps hitting: the figure below was true when written and was quoted forward after
  it stopped being. **Re-run `git worktree list` and `git branch` before
  repeating either number.** ✅ **Re-measured 2026-08-05 after #563/#565 and all three still hold
  — 15 local branches, 8 worktrees, 16 containers.** Both branches this session created were
  removed by `gh pr merge --delete-branch`, locally and remotely, so they add nothing to retire.
  Recorded because "still true" is itself a measurement, and the alternative is a reader assuming
  drift that is not there. STALE ROW FOLLOWS —
  **Branches/worktrees (2026-08-04):** **1 local** (`main`), **2 remote** (`main` + dependabot #523),
  **1 worktree**. Zero unpushed commits. Everything else was retired — see §0.-3 for what was proven
  before deleting, and for why `git branch -d` refused branches whose content was already in `main`.
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
# EXPECT 26 x rc=0. A VOID (2) is not a pass. (22 -> 24: #276 added
#   check-image-supply-chain.sh and #337 added check-edge-core-contract.sh.
#   24 -> 25: check-postgres-major-parity.sh, after dependabot #525 bumped the
#   BACKUP image to postgres:18 against a 15 server with every CI check green.
#   25 -> 26: check-gate-enforcement.sh (#553), after SIX of the 24 check-*.sh
#   were measured to have ZERO references in .github/workflows/ — three of them
#   gates written to stop a specific defect recurring, and incapable of firing
#   on a PR. That gate now fails the build if a new one is added unwired.)
# MEASURED 2026-08-05 on `main` after #565, runtime re-synced: 26/26 rc=0.
#   Getting there needed BOTH standing remedies, in this order:
#     check-alert-metrics    rc=1 -> scripts/seed-order-metric.sh      (every core-java recreate)
#     check-e2e-skip-budget  rc=2 -> re-run the suite, ~6.5 min        (every merge touching a spec)
#   Neither is a regression and both print their own remedy. Budget the 6.5 min.
# EARLIER 2026-08-05 on the #553 branch: 23/26 rc=0 — the three non-zero being
#   check-alert-metrics (1), check-e2e-skip-budget (2 VOID) and
#   check-handoff-contract (1, this document's own #420 claim, corrected in #541).
# If check-runtime-freshness is 1 -> you changed source: bash scripts/sync-runtime.sh
# If check-alert-metrics    is 1 -> core-java was recreated: bash scripts/seed-order-metric.sh
#    (this fires EVERY time core-java is recreated; observed rc=1 -> seed -> rc=0 on 2026-08-04)
# If check-e2e-skip-budget  is 2 -> stored report older than frontend/e2e; re-run the suite (below)
# Both gates print their own remedy. Neither is a regression.
# ALSO RUN THE K8S GATES — `scripts/check-*.sh` does NOT glob them, and they are the
# ones a k8s change actually exercises. This omission bit twice in one session; the
# second time CI caught a real violation (APP_PUBLIC_ORIGIN) that should have been local.
for g in k8s/scripts/check-*.sh k8s/scripts/render-golden.sh; do
  bash "$g" >/dev/null 2>&1; rc=$?; printf '%-34s rc=%s\n' "$(basename "$g" .sh)" "$rc"
done
# All six of these must be rc=0. (Deliberately NOT phrased as an "EXPECT <n> x rc=0"
# claim: H-1 matches that exact shape and compares it to the count of scripts/check-*.sh,
# so writing it that way here makes a TRUE statement about the k8s gates fail the gate.)

# 1b. E2E — SOURCE .env FIRST or the count is a lie.
#     Without it, 26 vendor-authenticated specs self-skip on "No vendor password" and
#     the suite reports 48 skipped / 21 undeclared, which reads exactly like a regression.
set -a; . ./.env; set +a
export E2E_VENDOR_PASSWORD="${E2E_VENDOR_PASSWORD:-$KC_SEED_USER_PASSWORD}"
bash scripts/seed-e2e-fixtures.sh          # or the DRAFT block skips and the budget fails
( cd frontend && PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json \
    npx playwright test --reporter=json,list )
# EXPECT 174 passed / 8 skipped / 0 failed of 182 (~6.6 min), measured 2026-08-05 on
# `main` after #565. 182 = both projects; #563 added one test() block, so 180 -> 182.
# 8 is EXACTLY the declared ceiling, so the next skip added trips the gate.
# ⚠ This is the LOCAL suite. The NIGHTLY is the authority and has not run since
#   `d4930719` — re-dispatch it rather than reading this line as whole-suite health.

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

**#442 is DONE (PR #472), and its trap was real.** The acceptance criteria warned that authenticating
metrics without giving Prometheus a way in blinds the Phase 27 alerting layer. Verified: the scrape
config declares no `basic_auth` and no `authorization` for either job, so authentication was the wrong
fix outright. Closed by **port isolation** instead — the approach prod already used. Two of the
finding's three claims were falsified along the way (§2.1).

**Recommended next move: #444 (F-H4)** — the webhook delivery log is permanently empty, a shipped
Phase-22 feature that has never worked, and the finding names the cause in one line (no
`@Transactional`, so `TenantSetLocalAspect` never pins the GUC and RLS returns nothing). Needs no
decision from §4.

**Also unblocked and needing no decision: #564 is OPEN** (§0.-10). The KDS board issues one request
per active ticket on every full refresh, so an 18-ticket board costs 19 and a 40-ticket board would
cost 41 — against 100/min for the *whole* tenant. #563 made the board **tolerant** of the resulting
refusals; it did not reduce them, and the failure mode scales with how busy the kitchen is, which
is the wrong way round. A batch detail endpoint is the real fix; bounded concurrency only moves the
cliff. It also makes three separate test defects go away at their shared root — worth reading
§0.-10's table before deciding it is small.

⚠ **#444 is core-java, so its PR WILL run the full Testcontainers suite (~47 min, measured on #472).**
That is not a cost to avoid — RLS behaviour under a real Postgres is exactly what that suite buys, and
this repo has a recorded trap where auth-layer changes silently break *existing* integration tests.
Frontend-only work path-skips it in ~5s, so batching is free there and only there.

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
proving the sweep discriminates. Two findings — **#453 is OPEN** (F-H6, High) and **#454 is CLOSED** (fixed in #534)
(F-M6) — appear in `findings.json` and the report prose but **in no group in `plan.md`**: the council
found them and never routed them. They were caught only by that sweep. If you run a council again,
diff `findings.json` against the groups in `plan.md` before trusting the adjudication.

**#428 Wave 1 (catering discovery) still costs no engineering time and runs in parallel with anything.**

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric. `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
