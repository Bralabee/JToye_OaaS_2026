# Handoff: 27-03 is 8/9 (Task 8 blocked) — next is 27-02

**Generated:** 2026-07-29 ~10:15 BST. Supersedes the "27-04 merged, 27-03 next" handoff.

| | |
|---|---|
| `origin/main` | **`9da0761`** — CI green |
| Working branch | **`feature/27-03-alerting-dlq-runbook`** — 10 commits, clean, **0 behind**, pushed, **no PR yet** (deliberate, see §2) |
| Phase 27 | 27-00 ✅ 27-01 ✅ **27-05 ✅ 27-04 ✅** (both MERGED) · 27-03 **8/9** · **27-02 next** · 27-06 after |
| Stack | Compose UP, all services running; all 4 built services FRESH; 13 queues; the real nine dead letters untouched at `msgs=9` |

---

## 0. ⚠ READ THIS FIRST — 27-04 IS DONE. DO NOT RE-EXECUTE IT.

**27-04 and 27-05 are COMPLETE and MERGED to `main`.** 27-04 landed as PR **#331** → `9858370`;
27-05 landed as PR **#310**. Verified by content on `origin/main`, not by ancestry (the repo
squash-merges, so their original SHAs are not ancestors).

**The GSD SDK will tell you otherwise, and it is wrong.** `gsd-sdk query init.execute-phase 27`
reports `incomplete_plans: [27-02, 27-03, 27-04, 27-05, 27-06]` because it uses **`SUMMARY.md`
presence** as the completion marker — and 27-04/27-05 shipped with `EVIDENCE.md` instead. Running
`/gsd-execute-phase 27` with no wave filter would **re-execute work already on main**.

Two ways to stay safe, use either:
- scope with the wave filter — `/gsd-execute-phase 27 --wave 4` (27-02 and 27-06 are the only
  wave-4 plans; 27-03 is wave 3, 27-04 wave 2, 27-05 wave 1); or
- write the two missing `SUMMARY.md` files from their `EVIDENCE.md` files first, which closes the
  hole permanently. **Still open — nobody has done this.**

---

## 1. WHERE TO RESUME — 27-02

`.planning/phases/27-operational-maturity/27-02-PLAN.md` (wave 4, 171KB).

It is the only thing standing between 27-03 and completion, **and** it owns disposing of the nine
dead letters that 27-03 archived and handed over.

```bash
cd /home/sanmi/IdeaProjects/JToye_OaaS_2026
git fetch origin && git switch -c feature/27-02-<name> origin/main
bash scripts/check-branch-behind-base.sh          # expect rc=0
```

**Verify 27-02's own preconditions against the tree before starting** — do not trust a plan's
`files_modified`. 27-03's plan named `infra/monitoring/prometheus/prometheus.yml`, which 27-00 had
already replaced with `prometheus.yml.tmpl` + `entrypoint.sh`; editing the name as written would
have created a file the running Prometheus ignores — a silent no-op behind green gates.

27-02 is `autonomous: false` with a `checkpoint:human-action` on Task 2 (it replaces the broker), so
expect it to stop for approval the same way 27-03 did.

---

## 2. 27-03 state — 8 of 9 tasks, and why there is no SUMMARY.md

Tasks 0–7 are done, committed and evidenced. **Task 8 re-runs the live alert gate AFTER 27-02
replaces the broker** and records the delta. It is an exit criterion of the plan, not a follow-up.

No `SUMMARY.md` is written and **no PR is open**, deliberately: both would assert a completion that
has not happened. Full record: `.planning/phases/27-operational-maturity/27-03-EVIDENCE.md` (735
lines, every criterion in both directions).

Delivered: a `rabbitmq-queues` scrape job, six live alert rules + one deliberately dormant, two
executable alert gates (`scripts/check-alert-rules.sh`, `scripts/check-alert-metrics.sh`), 13 runbook
sections, `scripts/dlq-inspect.sh`, a retry-exhaustion counter, and the F-9 listener-factory
signature fix layered on 27-04's.

**Open the PR only after Task 8 runs.** Until then the branch is pushed and safe.

---

## 3. Three defects found this session that no test caught

1. **AC-10's terminal assertion was blind (27-04).** It counted PENDING rows on an *untransacted*
   connection with no tenant GUC; under the `NOSUPERUSER` downgrade RLS filtered every row, so the
   count was structurally 0 and the test survived three break arms. Probe: `expected: 12 but was: 0`
   with all 12 rows provably PENDING. **Prove an instrument can SEE the rows before trusting its
   silence.**
2. **The retry counter tagged every message `queue="unknown"` (27-03 T5).** Spring AMQP proxies
   `invokeListener(Channel, Object)`, so `args[0]` is a Channel, never the Message — but the test
   fixture built `new Object[]{message}`, a shape production never produces. Green throughout while
   the metric was useless. Only the live run could falsify it.
3. **The runbook's dead-letter discriminator was wrong (27-03 T7).** It told on-call that
   `MessageConversionException` is "fatal on first delivery" and that retry exhaustion "also"
   increments `jtoye_amqp_retries_exhausted_total`. Measured: the counter increments on **both**
   paths (`1 -> 2` on `queue="media.process"` from one malformed publish), because the converter runs
   inside `MessagingMessageListenerAdapter`, which is *wrapped by the advice chain*. `x-death[0].count`
   reads `1` on both too. **Only the exception class discriminates.** Runbook corrected.

---

## 4. Traps confirmed this session

- **An `INT`/`TERM` trap that does not `exit` RESUMES the script.** The 27-03 drill harness ran its
  cleanup on SIGINT and then *carried on*, leaking a stray process. Use
  `trap cleanup EXIT; trap 'cleanup; exit 130' INT TERM`. Found only because the break arm was run
  **before** the drills rather than after.
- **A `docker logs --since Nm` window can close before you read it.** The first read of the
  conversion exception returned nothing because the grep ran 5.5 min after a 3-min window. An empty
  grep was one step from being recorded as "no exception occurred" — which would have *confirmed* a
  wrong claim rather than refuting it.
- **RLS blinds a verification query** — see §3.1. Recorded as a memory.
- **The tenant pin sits under a global aspect**, and the aspect *overwrites* a correct explicit pin.
  `TenantContext.set` is the dominant control, not the explicit `set_config`.
- **The Testcontainers-startup flake reads as a code failure.** `main` went red at `1500f22` on
  *Integration Tests*; a re-run of the identical SHA went green. Re-run before diagnosing.

Standing: `grep` is a shell function → `command grep` in scripts; `cmd | grep -q X` under `pipefail`
INVERTS on match; capture `rc=$?` on its own line; `cleanTest`/`cleanIntegrationTest` are load-bearing
(without them the task reports UP-TO-DATE while executing NOTHING); counts come from
`core-java/build-local/`, never `core-java/build/`; the repo squash-merges so verify merges **by
content**; stage by explicit path, never `git add -A`; `git stash -u` is unsafe here.

---

## 5. Baselines and gates

| suite | last green |
|---|---|
| `:core-java:cleanTest test` | **119 classes / 851 tests / 0 fail / 0 err / 1 skip** (+3/+19 vs the 116/832 baseline = exactly the 3 new `RabbitMQ*Test` classes, 7+9+3) |
| `:core-java:cleanIntegrationTest integrationTest` | 104 classes / 416 tests / 0 fail (~42 min local, ~48 min CI) — **not re-run since 27-03's Java change; run it before the 27-03 PR** |

All green at handoff: `docs-freshness`, `check-doc-versions`, `check-branch-behind-base`,
`check-alert-rules`, `check-alert-metrics`, `check-runtime-freshness` (4/4 services FRESH).
Expected non-zero: 27-00's `check-alert-liveness` (rc=1, its designed pre-close state) and
`dlq-inspect --summary` (rc=1 — the nine parked messages, which 27-02 owns).

**New gate as of today:** `scripts/check-doc-versions.sh` compares documented dependency versions in
`CLAUDE.md`, `AGENTS.md` and `.planning/codebase/STACK.md` against `build.gradle.kts` /
`package.json` / `go.mod`, and runs in the `docs-freshness` workflow. If you bump a dependency,
update those three docs in the same commit. `.planning/PROJECT.md` is deliberately not gated (its
line ~113 is dated history).

---

## 6. Carried forward

- [ ] **27-02** (wave 4) → unblocks 27-03 Task 8 → then 27-06. **Before executing it:**
      `27-02-PLAN.md:141` cites `infra/monitoring/prometheus/prometheus.yml:92-98` in its fact
      table. That file does **not** exist (27-00 replaced it with `prometheus.yml.tmpl` +
      `entrypoint.sh`), so the path and line numbers are both stale. It is a *citation*, not a
      `files_modified` entry, so it will not cause a phantom edit — but do not trust that row.
      The live codebase doc carrying the same error is fixed in PR **#334**.
- [ ] **Local branch cleanup is pending a decision.** Three merged branches survive locally because
      the repo squash-merges, so `git branch -d` refuses them ("not fully merged") even though all
      three are verified merged by content *and* PR state: `feature/27-04-consumer-concurrency`
      (#331), `chore/docs-version-drift` (#332), `chore/phase27-summaries` (#333). Removing them
      needs `git branch -D`, which discards — left for the user rather than done automatically.
- [ ] **Toolchain drift surfaced, not applied** (`~/dotfiles/toolchain/doctor.sh --check`, exit 1):
      conda `26.1.1 → 26.5.3`, `@google/gemini-cli` `0.52.0 → 0.53.0`, `ms-fabric-cli` `1.2.0 →
      1.6.1`. One UNKNOWN row — `antigravity`, policy `manual`, which has no probeable channel by
      design. Toolchain changes get their own session (`update.sh --tier N`, dry run first), never a
      housekeeping run.
- [ ] **Write `27-04-SUMMARY.md` and `27-05-SUMMARY.md`** from their EVIDENCE files — closes the
      re-execution hazard in §0 permanently.
- [ ] **`.planning/STATE.md`** was stale for the whole phase (it still read "Phase 26 CLOSED") and was
      hand-edited by 27-03. Note `state.record-session` CORRUPTS it; hand-edit,
      `roadmap.update-plan-progress` is safe.
- [ ] Run the full `integrationTest` before opening the 27-03 PR.
- [ ] **AKS deployment** — decided, scoped, NOT started. Blocking: Keycloak hosting decision; no
      `jtoye-infrastructure` manifests; 25 secret keys; no DNS A records on `olajay.co.uk`.
- [ ] Dependabot PRs — triage, do not bulk-merge. `main` already carries three **major** bumps
      (spring-statemachine 4.0.2, stripe-java 33.1.1, awssdk bom 2.49.2).
- [ ] **Evidence row L6** — a KDS client receiving a relayed order event through a real broker has
      still never been captured. #266 fixed but unproven.
- [ ] #274 gitleaks allowlists inert; #276 matrix `fail-fast: false`.
- [ ] Wire jest-dom into `tsconfig.json` so the type-error count becomes a real gate.

## 7. Residue

- Compose stack UP, all services running/healthy, all 4 built images FRESH.
- 13 queues, no drill queues survive, the nine real dead letters untouched at `msgs=9`, archived
  off-repo at `…/scratchpad/27-03/webhook-dlq-archive-2026-07-29.json`.
- No stray drill processes. No CPU pins set this session.
