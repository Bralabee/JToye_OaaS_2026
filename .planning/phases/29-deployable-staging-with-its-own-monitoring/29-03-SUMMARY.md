---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 03
subsystem: infra
tags: [alertmanager, prometheus, kubernetes, networkpolicy, agnhost, kubectl, bash, ci-cd, gates]

# Dependency graph
requires:
  - phase: 27-monitoring-that-can-see
    provides: check-alert-liveness.sh (L-0..L-3) and its 0/1/2 exit-code doctrine
  - phase: 26-local-k8s-overlay
    provides: k8s/base/networkpolicies/*, k8s_local_assert_context context-guard pattern, the "NetworkPolicy enforcement NOT PROVEN" record this plan makes falsifiable
provides:
  - "check-alert-liveness.sh can read the alert corpus out of a Kubernetes Prometheus with no docker daemon (PROM_EXEC / PROM_RUNTIME_PROBE)"
  - "L-3b SINK CO-RESIDENCY: the searched sink must share a receiver with the human inbox, so the Gmail leg is provable via the Mailhog leg"
  - "A recorded, controlled measurement of assumption A1 (Alertmanager fans out to every email_configs entry in one receiver)"
  - "scripts/check-networkpolicy-enforcement.sh — two-arm agnhost denial proof for DPLY-05, control arm first, wired into deploy-staging"
affects: [29-06 k8s Prometheus config, 29-07 k8s Alertmanager manifests + Gmail secret, 29-09 dependency horizons, 29-14 live two-arm staging run, 29-15 deploy-staging job]

# Tech tracking
tech-stack:
  added: ["registry.k8s.io/e2e-test-images/agnhost:2.33 (probe image, pinned)"]
  patterns:
    - "Runtime-agnostic exec: a command PREFIX in an env var (PROM_EXEC) plus a matching existence probe (PROM_RUNTIME_PROBE), so one assertion serves docker and kubectl without weakening"
    - "Control arm first, and a failed control arm VOIDs the run rather than allowing the second arm to be interpreted"
    - "Test doubles at the tool boundary (a fake kubectl on PATH) instead of a test seam inside the gate, so no env var can neuter the gate in production"

key-files:
  created:
    - scripts/check-networkpolicy-enforcement.sh
  modified:
    - scripts/check-alert-liveness.sh
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "A1 measured, not reasoned: Alertmanager delivers to EVERY email_configs entry in one receiver (2/2 with a 1/0 control). The dual-sink staging receiver is therefore sound."
  - "L-3b added as an ADDITIVE assertion; L-3's own three-outcome logic is untouched byte-for-byte."
  - "A receiver with 2+ destinations and undeclared L3_SINK_TO/L3_HUMAN_TO is VOID, not a guess — a gate that picks one of several addresses could prove the leg nobody reads."
  - "PROM_EXEC defaults to the docker form, so compose behaviour is byte-identical and the k8s path is opt-in."
  - "check-networkpolicy-enforcement.sh WIRED into ci-cd.yaml deploy-staging rather than exempted in gate-enforcement.conf — that job has cluster credentials and a live namespace."
  - "Both exit 1 and exit 2 fail the deploy job: an unevaluatable security boundary is not a passing one."
  - "agnhost pulled from registry.k8s.io, not the k8s.gcr.io spelling in the AKS docs — k8s.gcr.io was frozen in 2023. Same artefact, same 2.33 pin."
  - "The control probe pod carries an always-failing readinessProbe so it never joins Service/edge-go's endpoints."

patterns-established:
  - "Assumption-first execution: a LOW/MEDIUM-confidence research assumption is measured with its control arm BEFORE the code that depends on it is written, and both numbers live in the script header rather than a chat log"
  - "A gate that depends on a config SHAPE must assert that shape (L-3b), because the shape is what makes its observation transferable"
  - "Break the wiring to prove the wiring gate sees it: check-gate-enforcement.sh PASS is only evidence after renaming the reference makes it FAIL"

requirements-completed: [DPLY-03, DPLY-05]

# Metrics
duration: 29min
completed: 2026-08-10
---

# Phase 29 Plan 03: Cluster-Capable Alert + NetworkPolicy Gates Summary

**Both live gates DPLY-03 and DPLY-05 depend on can now run against a cluster and have each been shown to produce 0, 1 and 2 — plus assumption A1 settled by a controlled measurement rather than by reasoning.**

## Performance

- **Duration:** ~29 min
- **Started:** 2026-08-10T20:44Z
- **Completed:** 2026-08-10T21:13Z
- **Tasks:** 3
- **Files modified:** 3 (1 created, 2 modified)

## Accomplishments

- **Assumption A1 is settled by measurement, with a control arm.** Alertmanager delivers to *every* `email_configs` entry in a single receiver. The dual Gmail+Mailhog receiver D-17 needs is therefore sound, and the numbers are recorded in the script header where the next reader will find them.
- **L-0 reads the corpus out of whichever runtime is present**, still byte-exactly, still VOIDing loudly. DPLY-03's literal criterion ("exit 0 against the staging target") is now reachable instead of structurally impossible.
- **L-3b makes the header's rejection of a side-channel probe executable.** A receiver with more than one destination and no declaration is VOID; a declared address that is not in that receiver is VOID; a config that mentions the probe alertname is VOID.
- **DPLY-05 has an instrument.** `check-networkpolicy-enforcement.sh` runs its control arm first, refuses the employer cluster, and has been demonstrated at all three exit codes.
- **Nothing here is trusted on a pass alone.** Every assertion in this plan was run against a deliberately broken input first.

## Task Commits

1. **Task 1: Measure A1, give L-3 an inspectable sink that does not bypass the real route** — `8172fcf1` (feat)
2. **Task 2: Runtime-agnostic L-0 exec path** — `0e6b4a6d` (feat)
3. **Task 3: New gate check-networkpolicy-enforcement.sh, control arm first** — `caa6f230` (feat)

## Files Created/Modified

- `scripts/check-networkpolicy-enforcement.sh` *(new, 349 lines)* — two-arm agnhost denial proof for DPLY-05. Control arm first; explicit kube context required; employer contexts refused even when named; `trap … EXIT` pod cleanup; 0/1/2 exit contract.
- `scripts/check-alert-liveness.sh` — `PROM_EXEC`/`PROM_RUNTIME_PROBE` runtime-agnostic L-0; new L-3b sink co-residency assertion; the A1 measurement recorded in the header; k8s-specific L-0 failure mode documented.
- `.github/workflows/ci-cd.yaml` — post-smoke-test step in `deploy-staging` running the new gate with an explicitly resolved context.

## The A1 Measurement (Task 1's deliverable)

Method: an isolated `prom/alertmanager:v0.27.0` — the same image as the live stack, the same Mailhog smarthost, on the same docker network. The shared compose stack was **not mutated**, because a second session drives that checkout.

| Arm | Config | Mailhog API URL queried | total | `a1-primary@` | `a1-secondary@` |
|---|---|---|---|---|---|
| **1 — the question** | ONE receiver, **TWO** `email_configs`, different `to:` | `http://localhost:8025/api/v2/search?kind=containing&query=a1probe-1786394841-3233795` | **2** | **1** | **1** |
| **2 — the control** | identical rig, **ONE** `email_configs` | `http://localhost:8025/api/v2/search?kind=containing&query=a1control-1786394888-3236713` | **1** | **1** | **0** |

**A1 HOLDS.** Arm 2 is what makes arm 1 mean anything: it proves the per-recipient counter *can* return 0, so the 1/1 is a real fan-out and not one message counted twice. A single count would have proved nothing about fan-out, which is exactly why the plan demanded both.

Independent corroboration arrived later from the metrics side: in the L-3b ARM D run below, a single posted alert moved `alertmanager_notifications_total{integration="email"}` by **2** on a two-entry receiver.

Because A1 holds, the documented fallback (teaching L-3 an IMAP or webhook sink) was **not** taken and no code claims dual-delivery it cannot support.

## Falsification Evidence — every arm, both directions

### Task 1 — L-3 / L-3b

| Arm | Setup | Expected | Measured |
|---|---|---|---|
| A | `MAILHOG_URL` at a closed port (8929) | 2 | **rc=2** `VOID: destination (Mailhog at http://localhost:8929) is not inspectable` |
| B | 2-destination receiver, `L3_SINK_TO`/`L3_HUMAN_TO` **unset** | 2 | **rc=2** `VOID: L-3b receiver 'email-default' has 2 email destinations` |
| C | 2 destinations, sink declared but **absent** from the receiver | 2 | **rc=2** `VOID: L-3b declared address 'not-in-this-receiver@jtoye.local' is NOT a destination` |
| D | 2 destinations, **both** declared and co-resident (the staging shape) | 0 | **rc=0**, `L-3b 2 destinations … sink 'a1-secondary@' co-resident with human inbox 'a1-primary@'` |
| Exit-1 | one destination, smarthost `192.0.2.1:1025` (TEST-NET, unroutable) | 1 | **rc=1** `L-3 Alertmanager ATTEMPTED delivery (0 -> 1) but probe … never arrived` |

`grep -c 'probe-only'` — **1 at HEAD, 1 after**. The header's rejection of a probe-only route is intact and was deliberately not re-worded, so that count stays a meaningful check.

### Task 2 — L-0

| Arm | Setup | Expected | Measured |
|---|---|---|---|
| 1 | byte drift in the file the process reads (a mutated copy inside the container's own writable layer) | fires | **rc=2**, both md5s named: host `7368261d…` vs served `dcfbabba…`, inodes printed |
| 2 | `PROM_EXEC`/`PROM_RUNTIME_PROBE` kubectl form at a non-existent context | 2 | **rc=2** `L-0 target not found — 'kubectl --context no-such-context-29-03 … get deploy/prometheus' failed` |
| 3 | same, PATH stripped of docker | no docker complaint | **rc=2** with the *target-not-found* message; `command -v docker` rc=1 proved docker invisible |
| 3b | **control:** same stripped PATH, DEFAULT (docker) `PROM_EXEC` | docker VOID fires | **rc=2** `L-0 'docker' (the first word of PROM_EXEC) is not on PATH` |
| clean | compose run, asserted **last** | 0 | **rc=0** |

Arm 1's restore was proven **by content**, not by `git diff --stat`: the mutated copy was deleted (`REMOVED`) and the real served file re-checksummed to `7368261df6b44519ddd8d12267862856`, identical to the host file.

`grep -c 'docker exec'` = **4**, every one accounted for by line number:

| Line | What it is |
|---|---|
| 257 | the recorded `docker cp` vs `docker exec` md5 measurement (historical evidence) |
| 261 | header prose explaining why exec, not cp |
| 274 | header documenting `PROM_EXEC`'s default value |
| 294 | the default value itself: `PROM_EXEC="${PROM_EXEC:-docker exec $PROM_CONTAINER}"` |

No unconditional `docker` call remains in the executable path.

### Task 3 — check-networkpolicy-enforcement.sh

Real kubectl (guards need no cluster):

| Arm | Expected | Measured |
|---|---|---|
| no `--context` | 2 | **rc=2**, message names `sipbihs2aks` and that it is EMPLOYER infrastructure |
| `--context sipbihs2aks` (named explicitly) | 2 | **rc=2** `on the refusal list … intent is not a safety mechanism` |
| `--context no-such-context-29-03` | 2 | **rc=2**, lists the known contexts |

Three-outcome arms, driven by a **test double at the kubectl boundary** (the gate has no internal seam, deliberately — an env var that could neuter it would be a fail-open):

| Arm | Expected | Measured |
|---|---|---|
| control arm TIMES OUT (broken probe) | 2 | **rc=2** `control arm TIMED OUT … the run is VOID` |
| control arm never completes (`Pending`) | 2 | **rc=2** `control arm could not be run to completion (phase='Pending')` |
| denied arm CONNECTS | **1** | **rc=1** `FAIL: the denied arm CONNECTED … stored and not enforced` |
| denied arm returns `connection refused` | 2 | **rc=2** `unrecognised result … VOID, never clean` |
| both arms as designed | **0** | **rc=0** `PASS … NetworkPolicy is enforced` |

This satisfies the plan's "cannot exit 0 without BOTH arms having run": a failing control arm VOIDs before the denied arm is even created, and a silent denied arm is a FAIL rather than a pass.

Wiring, falsified rather than assumed:

- `bash scripts/check-gate-enforcement.sh` → **rc=0**, `gates: 36, workflows: 6, exempt: 6`.
- Renaming both workflow references → **rc=1**, `check-networkpolicy-enforcement.sh (invokes: kubectl)`. The PASS is therefore evidence the checker *sees* this gate, not that it skipped an unscanned file.
- Restored from a scratch backup and proven by `git hash-object` (`36dd98d6…` before and after — `git checkout --` was **not** used, because the workflow edit was uncommitted and would have been destroyed).
- Clean state asserted last: **rc=0**.

Gate count, measured now rather than remembered: `ls scripts/check-*.sh scripts/docs-freshness.sh | wc -l` = **37**; `scripts/check-*.sh` alone (what the checker enumerates) = **36**, matching its own report.

`grep -c 'grep -q' scripts/check-networkpolicy-enforcement.sh` = **0**, and that 0 is falsifiable: the identical pattern returns **1** against a scratch file that does contain the shape. `grep -c 'TIMEOUT'` = **9**, and the denial assertion compares against that literal via `case`, not a pipe.

`bash -n` and `shellcheck -S error` (via `koalaman/shellcheck:stable` 0.11.0) both **rc=0** on both gates after all arms; the committed blob of the new gate matches the working tree (`bfdfad2a5ee0f1528925625eb5208f4298e8e1fb`).

## Decisions Made

- **Wired, not exempted.** `gate-enforcement.conf` was left untouched. `deploy-staging` already has cluster credentials, kubectl and a deployed namespace, so the bar for an exemption ("could only ever exit 2 on a hosted runner") is not met. Recorded here because the plan asked for which and why.
- **Both failure classes fail the deploy.** exit 1 (enforcement absent) and exit 2 (could not evaluate) both fail the job and therefore trigger the existing rollback step. The consequence is real and stated in the workflow comment: a probe pod that cannot be scheduled will fail a deploy. `continue-on-error` was rejected — it is how a gate becomes decoration.
- **Target/arm selection follows the manifests.** Control = `app=edge-go` → `app=core-java:9090`, which `20-core-java.yaml` ingress and `30-edge-go.yaml` egress both permit; denied = an unlabelled pod, caught by `00-default-deny.yaml`'s `podSelector: {}`. Every value is env/flag-overridable so the gate follows the manifests instead of needing an edit when they move.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical] Probe pod would have joined a live Service's endpoints**
- **Found during:** Task 3
- **Issue:** The control arm must carry `app=edge-go` for the NetworkPolicy to permit it — but `Service/edge-go` selects `app=edge-go` too (verified at `k8s/base/edge-go-deployment.yaml:121-122`). A probe pod that reports Ready is added to that Service's endpoints and receives real staging traffic it cannot serve.
- **Fix:** An always-failing `readinessProbe` (`httpGet` on port 1, where nothing listens) so the pod is never an endpoint. `httpGet` rather than `exec` because the agnhost image is minimal and an exec probe would depend on a shell being present. Documented under "WHAT NOT TO 'FIX'" so it is not removed as latency padding.
- **Verification:** Manifest inspected in the generated pod spec during the stub arms; the constraint is stated in the script header.
- **Committed in:** `caa6f230`

**2. [Rule 1 - Bug] The plan's image registry is frozen**
- **Found during:** Task 3
- **Issue:** The plan's `<interfaces>` block specifies `k8s.gcr.io/e2e-test-images/agnhost:2.33`. `k8s.gcr.io` was frozen in 2023 and only redirects; pointing a new gate at a frozen registry is a dependency that can only rot.
- **Fix:** Default `registry.k8s.io/e2e-test-images/agnhost:2.33` — same artefact, same 2.33 pin — overridable via `--image`/`NETPOL_AGNHOST_IMAGE`. The reason is recorded inline.
- **Verification:** The pin is a default string; the live pull happens in plan 29-14, which is where the plan says the live run belongs.
- **Committed in:** `caa6f230`

**3. [Rule 1 - Bug] A comment made its own acceptance criterion vacuous**
- **Found during:** Task 3
- **Issue:** The first draft's comment spelled out the forbidden pipe-into-quiet-grep shape, so `grep -c 'grep -q'` returned **1** on a correct file — the "a doc rule must name the token it forbids" trap. The AC's expected 0 would have been permanently unsatisfiable, and the check permanently meaningless.
- **Fix:** Re-worded the comment to describe the shape without spelling it, keeping the full warning (SIGPIPE → 141, fails OPEN) and adding a note saying why the literal is avoided.
- **Verification:** Count now 0, and shown falsifiable — 1 against a scratch file that contains the shape.
- **Committed in:** `caa6f230`

### Acceptance criteria corrected rather than silently substituted

**Task 2 FAIL-DIRECTION ARM 1 asked for `rc=1` on an L-0 md5 mismatch. L-0 VOIDs at `rc=2` by design**, and its own header says why: *"VOID, not FAIL. A gate whose two inputs disagree has not detected a monitoring defect; it has failed to evaluate."* Producing rc=1 there would have required weakening the gate. The arm was run and **rc=2** recorded with both md5s named — the strictly correct behaviour. Flagged rather than quietly re-interpreted.

**The same AC's method was also unsafe as written.** It said to `docker exec … sed -i` the alerts file inside the running container. That file is a **read-only bind mount resolving to the host file in the main checkout**, which a concurrent session drives — the edit would either fail or mutate shared state outside this worktree. The mutation was made on a copy inside the container's own writable layer instead, achieving the identical byte-difference with zero host risk, and the restore was proven by content.

## Issues Encountered

**An invalid fail-direction arm, caught by its own control.** Task 2's ARM 3 (docker stripped from PATH) first ran with a hand-built PATH missing `dirname`, so the script died resolving `REPO_ROOT` and VOIDed at *"alerts file not readable"* — long before reaching L-0. Read alone, that looked like a pass ("no docker complaint"). The ARM 3b control produced the **identical** message, which is precisely how the defect was visible: a control arm that cannot distinguish itself from the test arm has measured nothing. Instrument fixed, both arms re-run, and only then did ARM 3 and ARM 3b diverge as designed. This is the "suspect the instrument first" rule paying for itself inside one task.

**No unwanted mutation of the shared compose stack.** Every Alertmanager experiment ran in a throwaway container on the same network; all four rigs (`a1-probe`, `a1-control`, `l3b-rig`, `bh-rig`) were torn down and verified absent.

## Known Stubs

None. Both gates are complete instruments; what has *not* happened yet is the **live** run of `check-networkpolicy-enforcement.sh` against a real cluster, which the plan explicitly assigns to **29-14** ("This plan delivers an instrument that has been shown capable of all three exit codes").

## Threat Flags

None. No new network endpoint, auth path, file access pattern or schema was introduced. The new gate's own surface is covered by the plan's register: T-29-03-01 (explicit context + refusal list), T-29-03-02 (all three exit codes demonstrated, control arm first), T-29-03-04 (`trap … EXIT` cleanup, pinned image, default service account), T-29-03-05 (the quiet-grep-pipe shape absent by construction and asserted at 0). T-29-03-03 stands unchanged: this gate never reads the Gmail credential — it inspects Mailhog, the second sink in the same receiver.

## Cross-Cutting Quality Contracts

- **Web performance** — N/A (no user-facing page touched).
- **SEO / discoverability** — N/A (no public surface touched).
- **AI agent-readiness** — N/A (no API surface added or changed). Both gates emit machine-readable exit codes 0/1/2 with the repo's uniform meaning.
- **Security** — covered above under Threat Flags; the employer-cluster refusal is the load-bearing addition.
- **Falsifiable evidence + runtime parity** — (a) every criterion was run in its fail direction and both directions recorded, with two criteria corrected rather than silently substituted and one invalid arm caught by its control. (b) Runtime parity: this plan ships no runtime artefact — the gates were exercised against the live compose stack in this session (8 targets up, 19 rules), and `check-runtime-freshness.sh` / `check-container-config-drift.sh` are deliberately **not** run here because a worktree's directory name changes the compose project name and would VOID them; they belong to the main checkout.

## User Setup Required

None for this plan. Downstream, plan 29-07 must set `L3_SINK_TO` and `L3_HUMAN_TO` when it lands the staging Alertmanager config — a two-destination receiver with either unset is now VOID by design, so this cannot be forgotten silently.

## Next Phase Readiness

- **29-06 (k8s Prometheus)** — reproduce the job names verbatim (`EXPORTER_GAUGES`, `DIRECT_JOBS`, `SERVICE_JOB_MAP` are unchanged) and L-1b/L-2b run untouched. Invoke L-0 with:
  `PROM_EXEC="kubectl --context <ctx> -n <ns> exec deploy/prometheus --" PROM_RUNTIME_PROBE="kubectl --context <ctx> -n <ns> get deploy/prometheus"`
- **29-07 (k8s Alertmanager)** — put the Gmail relay and the in-cluster Mailhog in the **same** receiver (A1 proves the fan-out works) and declare `L3_SINK_TO`/`L3_HUMAN_TO`. Splitting them into two receivers is now a VOID, not a silent pass.
- **29-09** — add a `infra/dependency-horizons.yaml` row for `registry.k8s.io/e2e-test-images/agnhost:2.33`. Recorded in the script header as not-yet-done.
- **29-14** — run the live two-arm probe. If the denied arm connects, that is a real finding about the cluster's CNI, not a gate to relax.
- **29-15** — the `deploy-staging` step is already added; if that plan reshapes the job for Azure OIDC, keep the step and keep the explicit `--context` resolution.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-08-10*
