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
| `JToye_OaaS_2026` | **The Wave-1 merge train ran 2026-08-04 and merged five of six: #522 (Lane C a11y), #521 (Lane D k8s), #515 (the nightly), #520 (Lane E docs/CI), #519 (Lane A core-java).** #518 (Lane B frontend) is the sixth and carries this edit. Earlier: #508/#509/#510 on 2026-08-03, and #434/#435/#436/#443/#455 plus a concurrent session's #437/#456. HEAD deliberately **not** quoted |
| Open PRs | **5, all dependabot** (#523–#527: node 24→26, postgres 15→18, awssdk bom, two minor-and-patch groups). **Zero of mine.** All six Wave-1 PRs merged; the ORDER WAS LOAD-BEARING: five of them passed `Security Scan` between 23:12 and 00:05, i.e. **before a Trivy DB roll**, so every one was stale-green. #522 carried the `fast-uri`/`ip-address` bump and had to merge first or `main` would have landed failing its own security gate. Branch protection is `strict: false`, so GitHub **would** have merged all six on those stale conclusions — what caught it was reading the check *timestamps*, not the badge colours. **Re-measure before trusting this cell** |
| Open issues | **62**, measured after the train with `--limit 300` (the default `--limit` is **30** and silently undercounts). 19 issues closed by the six PRs. ⚠ **Five of those did not auto-close**: a PR body reading `Closes #293, #506, #271, ...` only closes **#293** — GitHub's parser consumes the FIRST number in a comma list and ignores the rest. #506/#271/#298 were closed by hand afterwards; **#299 and #303 were deliberately left OPEN** because Lane D only made them *visible* as `OPEN DEFECT` allowlist entries, it did not fix them. #299 is a real production gap: the customer-storefront realm is unconfigured in EVERY k8s environment |
| Issue-count history | It moved in **both** directions across 2026-08-03 (63 → 86 → 92 → 89 → 85 → 80 → **62**) as the council backlog was filed and the trains closed issues, which is why no single figure here is safe to carry. Re-run `gh issue list --state open --limit 300 --json number --jq length` |
| Milestone | **v2.3 is OPEN and spans Phases 21–32.** Owner ruling stands — see §4. Do **not** run `/gsd-complete-milestone` |
| Live stack | Compose UP, **16** jtoye containers = 11 full-stack + 5 monitoring; **14 report healthy**. The two without health status define no healthcheck — that is **not** unhealthy. **Infra ports are now loopback-only** (#510): Postgres, Redis, RabbitMQ, MinIO, MailHog, Keycloak, Grafana, Prometheus, Alertmanager and both exporters bind `127.0.0.1`. App-tier ports (core-java 9090, frontend 3000, edge-go 8089, mcp-server 9100) stay on all interfaces as **named, reasoned exemptions** |
| Gates | **24 scripts now** (was 22 — #519/#276 adds `check-image-supply-chain.sh` and #337 adds `check-edge-core-contract.sh`; #513 had earlier added `check-e2e-baseurl-contract.sh` and `check-playwright-mobile-contract.sh`). **22 green, 0 fail, 0 VOID**, measured on `main` after #512/#513 with the runtime rebuilt. `check-e2e-skip-budget` is no longer the standing VOID it was — but understand WHAT it is: a **staleness detector**, not a one-time fix. It VOIDs whenever the stored report is older than `frontend/e2e`, which **any checkout or merge touching a spec re-triggers**, so expect it after pulling and re-run the suite (~6 min: `PLAYWRIGHT_JSON_OUTPUT_NAME=e2e-artifacts/report.json npx playwright test --reporter=json,list`). Seed first — `scripts/seed-e2e-fixtures.sh` — or the DRAFT block skips and the budget fails. ⚠ It now sits at **exactly its ceiling of 8**, so the next skip added trips it. ⚠ `check-infra-exposure` **is not wired into CI** — part of it needs a live broker, so it could only ever VOID on a runner, the same reason `check-runtime-freshness` stays out. **Nothing stops someone re-adding `0.0.0.0` in a PR**. `scripts/ci-lane-cost.sh` is deliberately NOT named `check-*` and is NOT in this count: it answers a planning question, not a correctness one |
| Merge-train lesson | **`docs/metrics.json` conflicted three ways on every lane, and NEITHER SIDE WAS EVER RIGHT.** Lane E: ours 2093 / theirs 2106 / truth **2107**. Lane A: ours 2142 / theirs 2107 / truth **2157**. Lane B: 2202. Each lane adds to a different counter (Java / Go / Jest), so "take ours" and "take theirs" are both wrong and the only correct move is `scripts/docs-freshness.sh --write` on the merged tree. The same conflict also carried README's build badge, whose two sides were the **404 repo** and the fix for it — and which side was correct **flipped** between lanes, because the fix landed mid-train |
| Test baseline | **Read `docs/metrics.json`; this cell deliberately quotes no figure.** It moved three times in one day, and nothing gates a number written *here* — `check-doc-metrics` reads only README/CLAUDE/AGENTS, so a count copied into this document rots silently. Regenerate with `scripts/docs-freshness.sh --write`; never hand-arithmetic a delta, because the gate counts literal `@Test` and a renamed or table-driven test makes arithmetic wrong |
| Runtime | **4/4 built services FRESH, 0 unverified**, re-synced 2026-08-04 after the Wave-1 train. All four were stale (`rc=1`, each named with its build-input commit); `scripts/sync-runtime.sh` rebuilt and **recreated** them, gate `rc=0` after. Both directions recorded. **Proven by content, not only by the gate:** `TenantCacheEvictor`, `PublicUnsubscribeController` and `OrderStateChangeListener` (all #519) read back from **inside** the running `app.jar` via `unzip -l`, with a `NotARealClassControl` returning **0** so the probe can demonstrably say no; and the frontend's `--primary` was read out of the **served** stylesheet (`/_next/static/chunks/*.css`) as `17.5 88.3% 40.4%` — Lane C's orange-700, matching source, where orange-600 would be `20.5 90.2% 48.2%` |
| E2E | **127 passed / 8 skipped / 0 failed of 135**, run against the re-synced stack, `check-e2e-skip-budget` **rc=0** at exactly its ceiling of 8. ⚠ **The first run of this suite reported 48 skipped / 21 undeclared and that figure was an INSTRUMENT ARTEFACT, not a finding** — the suite was launched without sourcing `.env`, so 26 vendor-authenticated specs self-skipped on "No vendor password". `set -a; . ./.env; set +a` first, and export `E2E_VENDOR_PASSWORD` from `KC_SEED_USER_PASSWORD`. A skip count is meaningless unless the credentials were present |

> ⚠ **A second session drives this same checkout.** Not a worktree — the same working tree. A `git
> checkout` here moves *their* HEAD, and `main` moved four times while this document was being written.
> **Re-measure every number below before repeating it**; §2.4's first entry is what happens when you
> don't.

> **Why no HEAD SHAs.** A document quoting its own repo's HEAD is stale the moment it merges.
> §6 pairs every fact with the command that produces it: **run them, don't read them.**

---

## 0. ⚠ READ FIRST

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
- **Toolchain: 2 DRIFT + 1 UNKNOWN**, none applied — re-measured 2026-08-03, down from 4 DRIFT.
  `conda` 26.1.1→26.5.3 and `ms-fabric-cli` 1.2.0→1.6.1. `antigravity` is UNKNOWN because it is a
  **manual** channel the probe cannot query — a recorded decision, not a gap. `docker-ce` restarts
  the daemon — stack down first.
- **`.claude/worktrees/` was not gitignored: 9 live agent worktrees, 70,498 untracked files**, each a
  full working copy holding one of the merge train's branches. A plain `git add .` in this checkout
  staged all of it. Fixed in **#482**, scoped to `.claude/worktrees/` and **not** `.claude/`, because
  the project convention reserves `.claude/skills/` for tracked project skills.
  **The habit matters more than the fix.** The same hazard fired earlier the same day at 369 lines
  through a *named-path* `git add AGENTS.md`, which merged another session's uncommitted work as
  #469 and had to be reverted by #470 — a named path proves *which file*, never *which lines*. So:
  **`git diff --staged` before every commit here**, and treat `N insertions / 0 deletions` on a file
  you only edited as content that arrived from someone else.
- **Orphaned and worth someone's attention: `feature/faster-integration-tests-parallelism`** — one
  unpushed commit, *"perf(test): parallelize integrationTest to cut ~39m runtime to ~15m"*, no PR,
  not in a worktree. The suite was **measured at 47 minutes** on #472 (02:10:20→02:57:34) and #444
  will pay the same. Deliberately not pushed — publishing another session's work is its author's call.

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
# EXPECT 24 x rc=0. A VOID (2) is not a pass. (22 -> 24: #276 added
#   check-image-supply-chain.sh and #337 added check-edge-core-contract.sh.)
# MEASURED 2026-08-04 after the Wave-1 train + a runtime sync: 24/24 rc=0.
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
# EXPECT 127 passed / 8 skipped / 0 failed of 135 (~5.3 min), measured 2026-08-04.
# 8 is EXACTLY the declared ceiling, so the next skip added trips the gate.

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
proving the sweep discriminates. Two findings — **#453 is OPEN** (F-H6, High) and **#454 is OPEN**
(F-M6) — appear in `findings.json` and the report prose but **in no group in `plan.md`**: the council
found them and never routed them. They were caught only by that sweep. If you run a council again,
diff `findings.json` against the groups in `plan.md` before trusting the adjudication.

**#428 Wave 1 (catering discovery) still costs no engineering time and runs in parallel with anything.**

**Merged code is not running code.** After any merge touching source: `bash scripts/sync-runtime.sh`,
then reseed the order metric. `docker compose start` does not rebuild.

**Squash-merge note:** the repo squash-merges, so `git branch --merged` and `git branch -d` call
merged branches unmerged. Use PR state as the authority.
