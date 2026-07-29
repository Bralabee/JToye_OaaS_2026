# 27-06 — CI gate wiring — SUMMARY

**Status: COMPLETE (3 tasks + Task 0).** Branch `feature/27-06-ops-contracts`, branched from
`origin/main` @ `3442ccb`. Commits `173cd43`, `4e3d54b`, `f6bb30d`, plus this one.

This plan wrote **no gate logic**. Every script it wires already existed and was already proven red
in its own fail direction by the plan that shipped it. What it added is enforcement, one inventory
document, and an honest disposition of issue #115.

---

## 1. Task 0 — preconditions, recorded as facts not hopes

Full capture: [`baselines/B-8.txt`](baselines/B-8.txt) (152 lines).

**(a) The three scripts exist and are executable** — all three `exists=yes exec=yes`.

**(b) 27-03 has merged, recorded by SHA:**

```
3442ccb feat(27-03): failure visibility — live-proven alert rules, DLQ triage and the messaging runbook (#336)
MERGED_AT: 2026-07-29T15:32:13+01:00
```

**AC-0.2 control (mandatory):** the same command against a path that was never committed
(`scripts/check-does-not-exist.sh`) returns **empty, 0 lines** — so it distinguishes "merged" from
"not merged" rather than always printing something.

**(c) All three gates GREEN before any edit**, from a clean tree (`porcelain_lines=0`):

| Gate | Exit | Headline |
|---|---|---|
| `check-terminal-states.sh` | **0** | `X-1 missing-row=0  X-2 missing-alert=0 expired-deferral=0  X-3 missing-runbook=0` |
| `check-dependency-horizons.sh` | **0** | `H-1 coverage missing-row=0 · H-5 drift=0 · H-2/H-3 violations=0 · H-6 UNKNOWN rows=8` |
| `check-alert-rules.sh` | **0** | `19 live rule(s) valid, labelled, annotated and documented; 3 dormant` |

`check-terminal-states.sh` X-3 is **0 because 27-03 landed the four missing runbook sections**; it
was 1 for 27-00. That is the whole reason this plan had to run last, and it was checked rather than
assumed.

**(d)** `baselines/B-8.txt` written in 27-00's established shape — the eighth baseline file.

---

## 2. Falsifiability — both directions, every probe

### Task 0

| Criterion | PASS | BREAK |
|---|---|---|
| AC-0.1 preconditions can fail | loop exits 0 | `mv scripts/check-alert-rules.sh` away → **exit 2**, `VOID: … its owning plan (27-03) has not merged`. Restore verified by **sha256** (`ff12a640…`), identical before and after. `git checkout` deliberately not used — the recorded break-arm-revert trap restores from the index and eats later edits. |
| AC-0.2 merge is a SHA | `3442ccb` returned | never-committed path → **empty** |
| AC-0.3 baseline shape | all 7 fields present | truncate → **rc=2 (VOID)**; strip `--- OUTPUT ---` → **rc=1** naming the field. Restore checksum-identical. |
| AC-0.4 a red gate STOPs | not triggered — all three green | rule is executable: the Task 0 verify block's own exit code is the gate |

### Task 1

| Criterion | PASS | BREAK / CONTROL |
|---|---|---|
| AC-1.1 green at wiring, two DATED runs | `OPS_GATES_GREEN` at **14:36:17Z** (pre-edit) and **14:44:27Z** (post-edit) | delete a `## <AlertName>` heading → **rc=1**, no `OPS_GATES_GREEN`, and the gate **names it**: `FAIL: S-4 live rule 'DeadLetterQueueNonEmpty' has no '## DeadLetterQueueNonEmpty' heading`. Restore checksum-identical. |
| AC-1.3 three steps | ops-contracts chmod = **3** | **CONTROL k8s-validate = 5** — the extractor is proven to work, so `== 3` is not satisfied by an extractor that returns 0 for everything |
| AC-1.4 no collateral damage | **0** invocations lost set-wise; set grew **8 → 11** | **CONTROL** deleting the `check-env-contract.sh` step in a scratch copy **does** print `./k8s/scripts/check-env-contract.sh` |
| AC-1.5 workflow validity | `yaml-ok`; ops-contracts parses as 4 steps / `{"contents":"read"}` / job-`if` **ABSENT** | **CONTROL** tab in real mapping indentation → `tab characters must not be used in indentation (547:1)` |
| AC-1.6a cannot skip | job-level `if:` = **0** | **CONTROL branch-parity = 1** — the pattern can match |
| AC-1.6b needs↔comment biconditional | **0 == 0**, holds | FIRES in **both** directions: add to `needs:` only → 1≠0; name in comment only → 0≠1 |
| AC-1.7 declared permissions | `permissions:` = 1 | asserted; removal breaks it |
| AC-1.8 VOID fails the build | — | **live arm 1:** unreachable `endoflife.date` → **exit 2** (`an unreachable source is never 'clean'`). **live arm 2:** docker absent from a real bash+coreutils PATH → **exit 2**, *not* 127 (sanity-controlled: the scratch shell runs). **static arm:** 0 × `continue-on-error` / `\|\| true` / `if: always`. |
| AC-1.9 UNKNOWN row stays green honestly | see §4 | see §4 |

### Task 2

| Criterion | PASS | BREAK / CONTROL |
|---|---|---|
| AC-2.1 inventory complete | all six ≥1 | remove the `--refresh` sentence → **0**, loop exits 1 |
| AC-2.2 literal SUMMARY form | `check-alert-liveness.sh: exit=` present | reword to prose → **0** |
| AC-2.3 transport ≠ recipient | present | delete → **0** |
| AC-2.4 documented command runs | **extracted from the document by awk, not retyped** → exit **0**, `ALL_STATIC_GATES_GREEN`, all 8 gates green | typo one path → **exit 127**, no GREEN printed |
| AC-2.5 nothing displaced | **0** lost; set grew **9 → 13** | **CONTROL** deleting a pre-existing mention **does** print it |

### Task 3

| Criterion | PASS | BREAK / CONTROL |
|---|---|---|
| AC-3.1 #115 comment machine-checked | artifact-path matcher = **1**, state **OPEN** | **CONTROL** matcher on a never-posted path → **0** |
| AC-3.2 follow-up links both ways | #337→#115 **YES**, #115→#337 **YES** | `gh issue view 99999` → **exit 1**, not empty success. **CONTROL** bogus `#99999` not matched. |
| AC-3.3 branch parity | **0** | `--head 78eaa99` → **exit 1**, `is 35 commit(s) behind origin/main` |
| AC-3.4 runtime parity | **0**, 4/4 FRESH | see §7 — the plan's stated arm was **invalid**; a corrected arm was used |
| AC-3.5 metrics baseline | docs-freshness **0** with no `--write`, `metrics.json` unchanged, delta **ZERO** | `origin/main` = **1851 / 1259**; break against `78eaa99` = **1736 / 1157** — differs, so it can fail |

---

## 3. The `ops-contracts` job, and AC-1.2's six counts

The job as merged is at `.github/workflows/ci-cd.yaml`, **Job 1f**, placed after `mcp-server-tests`
so the existing `1 … 1e` numbering stays monotonic and no existing job moved. Three steps, each
named for the assertion rather than the file; `permissions: contents: read`; no job-level `if:`;
no softening constructs.

**AC-1.2 as literally written is UNSATISFIABLE, and was replaced — not silently satisfied.**

Task 1(c) **requires** the header to state that `check-alert-liveness.sh` and
`check-alert-metrics.sh` are deliberately absent and why. AC-1.2 **requires** `grep -c <name> == 0`.
Naming them in order to explain their absence makes the count non-zero — measured **2 / 1 / 1**.
Both requirements cannot hold. This is the recursive self-break trap this project has recorded
firing four times in a single plan: *a verification example and the material it verifies must not
share a namespace*.

**Restated form (strictly stronger):** count **invocations**, not mentions — comments stripped,
matching `./scripts/<name>`. That measures the property that actually matters (the gate is not
*run* in CI) rather than a property nobody cares about (the gate is never *mentioned*).

| Script | Mentions (plan's form) | **Invocations (restated)** |
|---|---|---|
| `check-terminal-states.sh` | 3 | **2** (chmod + call) |
| `check-dependency-horizons.sh` | 4 | **2** |
| `check-alert-rules.sh` | 4 | **2** |
| `check-alert-liveness.sh` | 2 | **0** |
| `check-alert-metrics.sh` | 1 | **0** |
| `infra/load-testing/baseline.sh` | 1 | **0** |

**It fires:** injecting a real step invocation of `check-alert-liveness.sh` into a scratch copy takes
its count **0 → 1**. The comment-stripper does not over-strip (wired gates still count 2).

---

## 4. AC-1.9 — the UNKNOWN horizon row, all three arms

This is the criterion that keeps the job honest without a `|| true`.

**PASS (exit 0), the printed line asserted rather than only the exit code:**

```
UNKNOWN rabbitmq-k8s: The staging/production broker is not deployed from this repo. … owner=UNASSIGNED review-expires=2026-10-26
  H-6 UNKNOWN    rows=8  (printed above; each passes only while its review is unexpired)
```

**BREAK-a** — delete the row's `manual_review` → **exit 1**, two findings:
```
FAIL: H-4 rabbitmq-k8s: owner is UNASSIGNED with no manual_review — an unowned row must at least be a dated one
FAIL: H-6 rabbitmq-k8s: horizon is UNKNOWN and no manual_review claims it — an unknown nobody has agreed to re-check is a silence, not a state
```

**BREAK-b** — expire it to `2020-01-01` → **exit 1**:
```
FAIL: H-6 rabbitmq-k8s: manual_review LAPSED on 2020-01-01 (2401 days ago) — an expired review is not a review
```

Restore checksum-identical; gate green again at exit 0.

---

## 5. The `needs:` decision (D-06)

**`ops-contracts` was NOT added to `build-and-push`'s `needs:` list.**

The checked fact that made it *eligible*: it carries no job-level `if:` (asserted = 0, with
`branch-parity` = 1 as the control proving the pattern matches) and the workflow has no `paths:`
filter, so it cannot skip.

**Why it was still not added.** This gate reddens **on a date, with no commit** — amber ~2026-09-01,
red 2026-12-01, from RabbitMQ 4.3's vendor horizon 2026-11-30 against the 90-day warn window
(arithmetic verified: `date -d "2026-11-30 - 90 days"` = 2026-09-01). It also depends on two
outbound services. Putting it in the deploy path before it has been observed through a single
horizon transition converts a correctness gate into a deploy outage — and the recorded hazard in
`ci-cd.yaml` itself is about `needs:` entries blocking deploys. It still fails **every PR**, which
is what OPS-02's "fails the build before it lapses" actually requires.

**The enumerating comment was therefore NOT edited** — it says "None of the four added jobs can
skip", which remains true. The biconditional holds at **0 == 0** and fires in both break directions.

---

## 6. `k8s/DEPLOYMENT.md` — the command as executed

Extracted from the document with `awk` over the fenced block (**copied, not retyped** — a documented
command nobody has run is a guess) and executed verbatim:

```
ALL_STATIC_GATES_GREEN
AC-2.4_EXIT=0
```

All 8 static gates green in one run: 4 kustomize builds / connection budget 155 ≤ 157 / env contract
0 violations both directions / INV-1..INV-6 + LOC-1..LOC-6 / both goldens byte-identical at 1547
lines / terminal states / horizons / 19 alert rules.

The new **"Operational contracts"** section is **additive**: the pre-existing "K8s static gates" and
"Runtime-parity gates" sections were left intact (AC-2.5: 0 mentions lost, set 9 → 13).

---

## 7. Issue #115 disposition

| | Before | After |
|---|---|---|
| State | OPEN | **OPEN** (deliberately) |
| Comments | 0 | 1 |
| Artifact-path matcher | 0 | **1** |

Comment: <https://github.com/Bralabee/JToye_OaaS_2026/issues/115#issuecomment-5119466154> — cites the
committed artifact `infra/load-testing/baselines/2026-07-27-7c4a617.md` with its measured numbers
(`/api/v1/shops` p95 8.8 ms / 962.3 req/s / `200=100`; `media.process` 80.97 msg/s/consumer).

Follow-up: **#337** — `[P3-13 remainder] Edge↔core contract check + dependency-down fault test in CI`,
labels `P3,ci,remediation` **matching #115 exactly**. Links verified in both directions.

**A defect of mine, corrected and recorded:** the first `gh issue create` used `--json`/`--jq` flags
it rejected, so a `||` fallback fired and created #337 with a literal `placeholder` body (12 chars).
Caught by AC-3.2's link assertion returning **NO**, not by inspection. Fixed with `gh issue edit
--body-file` (1934 chars) and re-verified. Only one issue was created — checked, no duplicate.

---

## 8. Required liveness field — and it VOIDs

```
check-alert-liveness.sh: exit=2 at 2026-07-29T14:54:53Z
```

```
VOID: L-1b job 'rabbitmq-queues' has no upstream-gauge mapping and is not on DIRECT_JOBS
      — add a row rather than letting a new exporter skip the check
```

**This is a real cross-plan finding, and the SUMMARY requirement is what surfaced it.** Task 2 made
"every phase SUMMARY records this gate's exit code" a durable requirement; the very first SUMMARY
bound by it discovers the gate is VOID. That is the requirement working exactly as designed — a gate
whose only enforcement is a human remembering is not a gate.

**Cause.** 27-00's `DIRECT_JOBS=("prometheus" "core-java" "edge-go" "keycloak" "rabbitmq")` predates
the `rabbitmq-queues` scrape job that **27-03** added (`3442ccb`). The gate refuses to skip an
unknown exporter, which is correct behaviour — it VOIDs rather than silently passing.

**Not fixed here, by design (D-08).** `scripts/check-alert-liveness.sh` is in
`files_NOT_modified_by_design`. A gate that must change to be usable is a **finding about the gate**,
reported rather than absorbed. Owner: **27-00** (the script and its data block) with **27-03** as the
change that outdated it.

**Filed as [#339](https://github.com/Bralabee/JToye_OaaS_2026/issues/339)** (`bug`/`ci`/`P2`).

**The fix is a `DIRECT_JOBS` entry, not an `EXPORTER_GAUGES` row** — verified rather than assumed:
`rabbitmq` and `rabbitmq-queues` target the **identical** endpoint `jtoye-rabbitmq:15692`, differing
only by `metrics_path` (`/metrics` vs `/metrics/detailed`). It is the broker's own plugin on a second
path, not a sidecar exporter fronting a separate upstream, so it has no meaningful self-reported
upstream-health gauge — that concept exists for `postgres`→`pg_up` and `redis`→`redis_up`, where an
exporter can answer while the thing behind it is dead.

The issue's acceptance carries the **fail direction as a requirement**: adding a fictitious ninth
scrape job must still VOID at exit 2. A "fix" that made the gate green by teaching it to ignore
unknown jobs would be strictly worse than the current red.

---

## 9. Gates still non-zero at phase close

| Gate | Exit | Owner | Note |
|---|---|---|---|
| `scripts/check-alert-liveness.sh` | **2 (VOID)** | 27-00 script / 27-03 introduced the job | §8. Not in CI, so it blocks nothing automatically — which is precisely why it needs an owner. |

All three **wired** static gates are **0**. Branch parity **0**. Runtime parity **0** (after the
rebuild in §10). docs-freshness **0**.

---

## 10. Runtime parity — a real drift, found and fixed

AC-3.4 **failed on first run**: `1 of 4 running built service(s) do not match the source tree`.

```
core-java  DRIFT [image-not-rebuilt]  image tagged 2026-07-29 11:42:19 UTC
                                      / newest build-input commit 3442ccb (2026-07-29 14:32:13 UTC)
```

Not caused by this plan — the **27-03 merge** modified `core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java`
(113 lines: the retry-exhaustion counter) at 14:32, after the running image was built at 11:42. This
is the standing "restoring an environment is a code-changing event" rule firing correctly.

Remedy applied — the one the gate prints: `docker compose up -d --build core-java` (rebuild **and**
recreate; `start`/`restart` fix neither half). Re-run: **4/4 FRESH, exit 0**.

**Proved by CONTENT, not by the gate's say-so.** Read from *inside* the running jar:

```
jtoye.amqp.retries_exhausted      ← 27-03's metric
findMessage                        ← 27-03's fix for the queue="unknown" defect
normaliseQueueTag                  ← 27-03's cardinality guard
```
Control: a bogus string returns **0**, so the probe discriminates.

*My first content probe searched for `jtoye_amqp_retries_exhausted` (the Prometheus form) and
returned 0. The probe was wrong, not the jar — Micrometer's Java-side name is dotted. Recorded
because "the first probe was wrong, not the check" is a recurring shape in this repo.*

**AC-3.4's BREAK ARM AS WRITTEN IS INVALID — finding F-4.** The plan says `docker stop
jtoye-prometheus` → exit 2. **Measured: exit 0.** Prometheus has no `build:` stanza, and the gate
covers only built services (`core-java`, `edge-go`, `frontend`, `mcp-server`). The arm is incapable
of firing, so it would have "verified" AC-3.4 while proving nothing.

**Corrected arm** — stop a genuinely built service (`mcp-server`) → **exit 2**:
```
PARSE ERROR: 1 of 4 built service(s) could not be verified … A service that is not running
cannot be proven fresh, so this assertion is VOID, not passing.
```
Restored; PASS arm re-confirmed at exit 0.

---

## 11. The three cross-plan reconciliations 27-00's D-Q creates for 27-02

Restated here rather than silently absorbed, per the plan's `<output>` item 10:

1. **27-02's AC-10 steady state.** 27-02 states this gate's steady state is **exit 1** with one
   UNKNOWN row. Under 27-00's schema (D-Q) it is **exit 0 with the UNKNOWN row printed**. Measured:
   exit **0**. 27-02's criterion must be restated, not the gate changed.
2. **27-02's Break 2 expected exit** is **2, not 1** — VOID outranks FINDING. Confirmed by this
   plan's AC-1.8 arm a, where an unreachable source exits 2 while contract violations exit 1, and by
   the horizon gate's own message: *"Exit 2 takes precedence over the N contract violation(s) also
   reported."*
3. **27-02's Break 3 `eol_source` field.** The `rabbitmq` row carries `eol_date: "false"` with
   `eol_source: vendor` and `vendor_eol: "2026-11-30"` — the vendor override is load-bearing, and
   verified live this run: `endoflife.date` returns `eol = False` for cycle 4.3, so without the
   override the horizon would silently not exist.

---

## 12. Things claimed that turned out to be wrong

The plan predicted there would be more than the two it already knew about. There were **five**.

| # | Claim | Measured |
|---|---|---|
| **F-1** | AC-1.9: horizon summary reports `unknown=1` | **`H-6 UNKNOWN rows=8`**. False on a *correct* tree; making it true would mean deleting seven legitimate rows. Replaced with a row-scoped form asserting the `rabbitmq-k8s` line + unexpired review. |
| **F-2** | AC-3.5: `origin/main` metrics are 1759 / 1176 | **1851 / 1259** (+92). Sibling plans 27-01/27-04/27-05 and the 27-02/27-03 merges moved them after authoring. Asserted at execution time and recorded, as the plan's own note instructs. |
| **F-3** | Line references (`k8s-validate` 296-357, `needs:` comment 596-605) | Shifted: `k8s-validate` **321-378**; and *this plan's own commit* moved the `needs:` comment to **683-692**. The plan's `sed -n '596,606p'` window now holds Trivy SARIF lines — it **bit my own AC-1.6b check**, which reported a vacuous `comment=0` until re-anchored on content. |
| **F-4** | AC-3.4 BREAK: `docker stop jtoye-prometheus` → exit 2 | **exit 0.** Prometheus is not a built service. The arm cannot fire. Replaced with stopping `mcp-server` → exit 2. |
| **F-5** | AC-1.2: three `grep -c … == 0` | Unsatisfiable alongside Task 1(c)'s mandatory header. Measured **2 / 1 / 1**. Replaced with an invocation count. §3. |

Two of my **own** probes were also vacuous and were fixed rather than accepted:

- the AC-1.5 tab control injected a tab at **line 2, which is blank** — a tab on a blank line is
  legal YAML, so it was **accepted** and proved nothing. Re-targeted into real mapping indentation,
  where it rejects at `(547:1)`.
- the first jar content probe searched the Prometheus-form metric name (§10).

---

## 13. Residue and follow-ups

- [ ] **Make `ops-contracts` a required status check** — branch protection is a repo-settings act,
      not a file edit, so it cannot be done in this PR. Until it is, the job runs and reports but
      cannot block a merge. **Owner: maintainer.**
- [ ] **`check-alert-liveness.sh` VOIDs at exit 2** — one missing `DIRECT_JOBS` entry for the
      `rabbitmq-queues` job. Filed as **#339** (`bug`/`ci`/`P2`) so it is scheduled work rather than
      a note inside a finished phase directory. **Owner: 27-00** (script), surfaced by 27-03's new
      scrape job. §8.
- [ ] **#337** — edge↔core contract check + dependency-down fault test in CI. **Owner: unassigned.**
- [ ] **#115 stays OPEN** until #337 lands. **Owner: unassigned.**
- [ ] **Horizon dates**: this job goes amber ~**2026-09-01** and red **2026-12-01** with no commit in
      between. Documented in the job header so it is not misread as a broken gate. **Owner: maintainer.**
- [ ] **`rabbitmq-k8s` review expires 2026-10-26** — the staging/production broker is still
      unowned and undeclared. **Owner: UNASSIGNED** (that is the finding, not an oversight).
- [x] **core-java rebuilt and recreated** — runtime parity restored, 4/4 FRESH, proven by content.
      **Owner: 27-06 (done this plan).**

**No gate script was edited.** All five in `files_NOT_modified_by_design` are untouched.
