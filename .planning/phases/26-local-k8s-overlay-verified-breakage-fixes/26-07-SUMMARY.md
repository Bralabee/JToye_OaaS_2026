---
phase: 26-local-k8s-overlay-verified-breakage-fixes
plan: 07
subsystem: infrastructure
tags: [kubernetes, minikube, local-overlay, backups, rls, evidence, live-rehearsal]
requires: ["26-01", "26-02", "26-03", "26-04", "26-05", "26-06"]
provides:
  - "k8s/LOCAL.md §11 rehearsal evidence rows L1-L5 filled with verbatim captured output"
  - "docs/runbooks/backups.md dated in-cluster CronJob result + two-arm falsification"
  - "four recorded image identities pinning the evidence to post-Phase-25 code"
  - "three script defects fixed that made the bring-up entry point unrunnable"
affects: ["26-08", "26-09"]
tech-stack:
  added: []
  patterns:
    - "herestring instead of pipe for grep -q under set -o pipefail (SIGPIPE inverts the assertion)"
    - "admission-webhook reachability gate probing the real path, not pod readiness"
    - "existence-before-403 ordering for every non-public object assertion"
    - "falsify-then-confirm: the counterexample arm is mandatory, not optional"
key-files:
  created:
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-07-SUMMARY.md
  modified:
    - k8s/LOCAL.md
    - docs/runbooks/backups.md
    - scripts/k8s-local-up.sh
    - scripts/lib/k8s-local-guards.sh
    - scripts/k8s-local-secrets.sh
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
decisions:
  - "The plan's L2 criterion 'client_addr on the minikube bridge subnet' cannot hold on a healthy run (double NAT) — replaced with baseline + elimination + boot-time correlation"
  - "The loopback rule's own grep is unsatisfiable (the rule must name the string it forbids) — replaced with an awk form scoped to §11's fenced blocks"
  - "The runbook's in-cluster checkbox conflated 'runs in-cluster' with 'artifact in prod S3' — split rather than ticked, because ticking it would assert a prod upload that did not happen"
  - "The XOR guard's cluster-side blind spot is recorded as a deferred item, not implemented — it changes a load-bearing safety guard and did not block this rehearsal"
metrics:
  duration: ~2h10m
  completed: 2026-07-25
  tasks: 3
  commits: 7
  docs_metrics_json: untouched (26-06 owns it)
---

# Phase 26 Plan 07: Live Local-Kubernetes Rehearsal Summary

INFRA-01 and INFRA-02 moved from "the manifests are correct" to named, falsifiable live evidence — and
the act of running the phase's own tooling for the first time exposed three defects that made it
unrunnable, one of which would have let a safety guard fail open.

## What was proven

Rows **L1–L5** of `k8s/LOCAL.md` §11 are filled with verbatim output, pinned to git `db7e87c` and to
four image identities all built during the run. **L6–L7 are deliberately left unfilled** with a stated
reason — they are 26-08's.

| Proof | Result |
|---|---|
| D-04 XOR **proceed** arm | exit 0 against real state — the half 26-05 could only refuse |
| BYPASSRLS role (D-02) | `rolbypassrls, rolsuper` = `t\|f` |
| Backup bucket | exists (listing), **then** 403 vs images 200 |
| Secrets | 8 created, 2 skipped by design; `postgres-credentials/username` → `jtoye_app` |
| Bootstrap idempotence | second run exit 0, 0 conflict errors |
| L1 server dry-run | 23 objects verbatim, both Ingresses, **0** admission denials across 8 run logs |
| L1b rollout | 3/3 `successfully rolled out`, full object listing |
| L1c ingress smoke | 3× 200 through `api./app.jtoye.local`, real seeded catalogue |
| L1d flag contract | `--dry-run-only` exit 0, generation + replicaset unchanged |
| L2 DEF-2 | log counts 1 / 1 / 0, plus DB-side `f\|f` and connection attribution |
| L2b DEF-1 | `secretKeyRef`, **no** `value`, decoded port 5433 |
| L2c PIT-5 | `FileNotFoundException` count 0, `log.path` = `/tmp` |
| L5 DEF-4 | `Access refused for user` = **0** |
| L3 CronJob | `.status.succeeded` = 1, 214370 bytes, object-level 403 vs 200 |
| L4 both arms | arm A `products=0` · arm B `products=47 orders=23 customers=12 shops=5` |

Arm B cross-checks exactly against the live DB read through the BYPASSRLS role. Both scratch databases
were dropped; the database list is back to the preflight set.

**Arm A is the whole point.** Its zero-row artifact **passes both** of the pipeline's automated
verifications — 149268 bytes against a `MIN_BACKUP_BYTES` floor of 1000, and a clean `pg_restore --list`
with 393 TOC entries. Neither check separates the arms. Only the row count does.

## Human approval and the one pause

Approval was pre-granted by the human for the full itemised mutation set (a)–(f) — compose app stop,
minikube start, the BYPASSRLS role on the shared dev Postgres, the MinIO bucket, cluster objects, and
two scratch databases — each with its reversal printed. The read-only preflight matched the approved
starting state exactly (profile `Stopped`, 10 compose services up, one kubectl context `sipbihs2aks` and
not current, `jtoye_backup` absent, `jtoye-db-backups` absent), so no abort condition fired.

Exactly one pause occurred, for `/etc/hosts`, which no script may escalate to. The literal line and the
`sudo` command were printed; the human ran it and the coordinator independently verified resolution.
**The node IP was confirmed against the running cluster before the human was asked**, so the printed
line could not have been wrong.

This plan performed the phase's **first whole-script `scripts/k8s-local-secrets.sh` execution**
(relocated here from 26-05 per Adjudication J). 26-05's refuse arms were deliberately **not**
re-observed — after the approval the apps are down, and restarting them mid-rehearsal would violate the
XOR rule.

## Three defects that made the tooling unrunnable

All three were invisible to static analysis and surfaced only by execution. That is the transferable
lesson: 26-05 authored these scripts and proved everything provable without running them, which was the
right call for safety — but it left a class of defect that only a first run can find.

**1. `getent | awk` under `pipefail` killed the hosts step silently** (`5bdee21`). `getent` exits 2 for
an unresolvable name; `pipefail` propagated it and `set -e` aborted with a bare exit 2 and no output.
The branch it destroyed is the *only* branch step 6 exists to serve — the one that prints the
`/etc/hosts` line. The first run died silently at precisely the moment a human was waiting for output.

**2. `grep -q` into a pipe inverts assertions under `pipefail`** (`43fb5e1`). `grep -q` exits on first
match, the writer takes SIGPIPE and reports 141, `pipefail` promotes it — so the pipeline reports
**failure exactly when the pattern is found**. `PIPESTATUS=[141 0]` is the proof. Measured as a race: 1
pass in 8 on the ~38 KB render. The image guard aborted claiming the manifests had drifted while all
four images were present.

The serious instance was not the one that fired. `k8s-local-guards.sh:275`, the compose-XOR **APP**
loop, reads a spurious 141 as *"this app service is NOT running"* — so the guard **fails open**. It
would have reported the compose apps down while they were up and allowed a cluster to start against a
live compose stack: the two-writers hazard the guard exists to prevent. Small input, so the race was
never observed there, but the fail direction makes it the most dangerous. All instances fixed with
herestrings; the backing-service, context and profile-IP checks fail *closed* and were fixed anyway.

**3. Bootstrap used `grep` inside a container that has none** (`db7e87c`). `mc ls bootstrap | grep -q`
ran inside `minio/mc`, which ships no `grep`, `sed` or `awk`. It died `grep: command not found` *after*
mc had created the bucket, leaving the bootstrap half-applied — role and bucket created, zero Secrets —
while reporting failure for a step that had succeeded. Replaced with `mc ls "bootstrap/$MC_BUCKET"`,
which already exits 0/1 on present/absent.

**Plus a missing step, not a defect** (`db7e87c`): `minikube addons enable ingress` at step 4 is not
inert — it rolls the controller (restartCount 5→6, pod IP `.51`→`.52`) while the admission Service keeps
its ClusterIP, so the API server dials it and lands on the stale pod IP. Step 9 then failed both
Ingresses under `error when creating ".../k8s/local"`, sending an operator after a manifest bug that did
not exist. A pod-readiness wait cannot fix it (`ready=true` throughout), so new **step 4b** probes the
real webhook path with a throwaway Ingress dry-run and retries only on transport failure — an admission
*denial* is a real answer and is left for step 9, so it cannot mask a PIT-1 regression. It answered on
attempt 2 and attempt 5 on two subsequent runs: genuinely load-bearing, variable delay.

## The finding worth carrying forward

**The compose⊕k8s XOR guard cannot see a second writer already inside the cluster.**
`k8s_local_assert_compose_xor` inspects compose only and never the cluster it is about to start. It is
asymmetric: it refuses to start a cluster while compose is up, but happily starts one that already
contains its own writers.

Measured, not hypothesised. The `Stopped` profile preserved etcd from 2026-07-14, and on start restored
`jtoye-staging` with **11 running pods** on stale `:2.1.0` images (code predating Phases 23–25) plus a
`pg-backup` CronJob that fired immediately and failed. Those pods held **16 live connections to the
shared dev Postgres as `jtoye_app`**. So `minikube start` alone silently re-created the exact hazard the
human is asked to stop their compose apps to avoid — while every guard reported green.

Per the standing decision the namespace was deleted (46 objects), dropping remote connections to **0**.
That zero became the control that makes L2's connection attribution exact rather than inferential.

Recorded in `k8s/LOCAL.md` §7 A1 as a named guard gap with an interim manual mitigation, and in
`deferred-items.md` with a concrete suggested closure (a named `REFUSED [cluster-writers-present]` arm
asserting no non-zero-replica Deployment outside the expected namespace set, never auto-deleting).
**Deliberately not implemented** — it changes a load-bearing safety guard, needs its own two-arm
falsification, and did not block this rehearsal.

## Deviations from plan

### Auto-fixed (Rule 1 / Rule 3 — blocking)

**1. [Rule 3] `getent` pipefail abort** — `5bdee21`. Blocked step 6 entirely.
**2. [Rule 1 + Rule 2] `grep -q` SIGPIPE inversion** — `43fb5e1`. Blocked step 7; the guards-library
instance was a fail-open safety defect (Rule 2).
**3. [Rule 3] `grep` absent in `minio/mc`** — `db7e87c`. Blocked step 8, left a half-bootstrap.
**4. [Rule 2] missing admission-webhook readiness gate** — `db7e87c`. Blocked step 9 intermittently and
broke D-14 re-runnability.

Scope note: three of these live in files outside the plan's declared `files_modified` list
(`scripts/k8s-local-up.sh`, `scripts/lib/k8s-local-guards.sh`, `scripts/k8s-local-secrets.sh`). Each was
strictly necessary to execute the plan at all, and each is confined to the mechanism it broke.

### Unsatisfiable acceptance criteria, replaced with strictly stronger forms

**5. L2's `client_addr` on the minikube bridge subnet — cannot hold on a healthy run.** Traffic is
double-NAT'd: pod `10.244.0.x` → `host.minikube.internal` (minikube gw `192.168.49.1`) → published host
port 5433 → docker-proxy → the postgres container, which sees the **compose** bridge gateway
`172.18.0.1`. Replaced with three proofs: the 16→0→5 baseline, elimination (all four compose apps
`exited`), and correlation (5 backends sharing `application_name` and `backend_start 20:37:48` against
the pod's `startedAt 20:37:36Z`).

**6. `grep -c 'localhost:9090' k8s/LOCAL.md` == 0 — unsatisfiable.** The rule forbidding the string has
to name it, so a clean document scores **2**. Replaced with an awk form scoped to §11's fenced blocks:
**0** over 278 captured-output lines. When the worked example was itself pasted into a fence, the count
went 0→1 and the check began failing on itself — the prose-vs-grep trap of 26-01…26-05, one level more
recursive. The example is now deliberately de-fenced. The two `127.0.0.1` strings inside the fences are
the host-side MinIO bucket-privacy probes, which cannot be served through an ingress.

**7. `grep -cF "$DB_PASSWORD"` == 0 — unsatisfiable by vocabulary.** Returns **9**. This dev `.env` sets
`DB_PASSWORD` to the English word `secret` (6 chars; SHA-256 prefix `2bb80d537b1d` ==
`printf 'secret' | sha256sum`), and all 9 hits are the script's own key-**name** lines
(`nextauth-secret`, `secret-key`, `secrets created`). Replaced with high-entropy secrets checked as both
literal and base64: `DB_BACKUP_PASSWORD` 0/0, `NEXTAUTH_SECRET` 0/0, `KEYCLOAK_CLIENT_SECRET` 0/0,
`MINIO_ROOT_PASSWORD` 0/0. No value was printed to diagnose it.

**8. The runbook checkbox conflated two claims.** It read "CronJob completes **in-cluster** (exit 0) with
the artifact in the **prod** S3 bucket" — one line, two assertions. Ticking it as instructed would have
claimed a prod S3 upload that did not happen. Split: in-cluster half ticked and scoped to the local
cluster and local MinIO, prod S3 half carried as a new unticked item. The prod restore drill stays
unticked.

### Corrections to inherited documentation

**9. `WebSocketConfig` package path.** Confirmed the plan's correction: the file is at
`core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java`, **not** `.../config/` as
`26-CONTEXT.md` and `26-RESEARCH.md` state. The cited line numbers are right and were verified against
the real file — `cors.allowed-origins` at 57-58, `enableStompBrokerRelay` + `setClientLogin` /
`setSystemLogin` at 67-72, `setAllowedOrigins` at 85. `DatabaseConfigurationValidator` *is* in
`.../config/` as the plan said.

**10. The runbook's "silently captures ZERO rows" is half right.** Measured on PG15 with 36 tables
`ENABLE` RLS, all 36 `FORCE`: a plain `SELECT` as `jtoye_app` with no GUC does return 0 rows silently,
but `pg_dump` additionally requests `row_security=off`, which Postgres **refuses** for a non-BYPASSRLS
role on a FORCE-RLS table — so `pg_dump` **exits 1** with
`ERROR: query would be affected by row-level security policy for table "customers"`. It fails loudly.
This does **not** retire the BYPASSRLS role or make arm A redundant: the partial artifact still clears
both content checks while restoring to zero rows, and it is `k8s-backup.sh`'s rc check plus
`rm -f "$TMP"` that stop it reaching S3. Corrected in both documents.

**11. Restart counts explained rather than glossed.** Pods finished at 3/2/2 restarts, stable over 60s
with a clean current boot log. Cause: core-java's previous container exited 1 on
`java.net.UnknownHostException: host.minikube.internal` from Flyway at startup — pod DNS for the host
gateway had not settled. It self-heals via the restart policy and is **not** fixed by swapping the name
for an IP (§5 explains why the name is deliberate). Startup-probe `connection refused` on 9091 and the
HPA `FailedGetResourceMetric` loop are benign and documented (§6, no metrics-server). Recorded in §7 with
the rule: a stable count plus a clean boot log is success, a climbing count is a real fault.

## What this does NOT prove

Stated next to `k8s/LOCAL.md` §6 and not contradicted anywhere in the evidence: **no TLS or HSTS**
(`tls: null`, no cert-manager); **not** the six nginx security headers (the PIT-1 snippet annotation is
nulled locally, and the controller's admission posture was never weakened to make anything pass — no
`allow-snippet-annotations` change was made or proposed); **no NetworkPolicy enforcement** (minikube's
default CNI does not enforce; all 6 policies applied and sit inert). HPA scaling is likewise unproven by
design.

**INFRA-01 and INFRA-02 are deliberately NOT marked complete** (anti-false-green). Their remaining
acceptance is the DEF-5 ingress login and the functional STOMP relay proof — rows L6/L7, owned by 26-08.

## Commits

| Commit | Type | What |
|---|---|---|
| `5bdee21` | fix | hosts step no longer aborts silently under pipefail |
| `43fb5e1` | fix | `grep -q` pipe inversion, incl. the fail-open XOR guard instance |
| `db7e87c` | fix | mc-native bucket check + admission-webhook readiness gate |
| `b460053` | docs | `k8s/LOCAL.md` rows L1–L5 + bootstrap proofs + A1 guard gap |
| `6a66401` | docs | L3/L4 + dated in-cluster backup result in the runbook |
| `cfcca4c` | docs | XOR cluster-side blind spot as a deferred item |
| `880f430` | docs | host-DNS boot race + benign-warning classification |

## Gate status

All five static gates exit 0 (`check-env-contract`, `check-render-invariants`, `render-golden`,
`check-connection-math`, `check-no-plaintext-secrets`) and `docs-freshness` is green.
`docs/metrics.json` verified **untouched** — 26-06 is its single writer, and this plan adds no counted
test invocations (bash and Markdown only). The employer AKS context `sipbihs2aks` was never targeted;
`--context jtoye` was explicit on every call, and `grep -c sipbihs2aks scripts/` is 0.

## State left behind

The cluster is **up** and the compose apps are **down** — the required XOR state, deliberately not
reverted so 26-08 can run L6/L7 without a fresh bring-up. Teardown when finished is
`minikube stop -p jtoye` then
`docker compose -f docker-compose.full-stack.yml start core-java frontend edge-go mcp-server`.

Residue from the rehearsal: the `jtoye_backup` role and the `jtoye-db-backups` bucket persist by design
(both are approved, reversible, and needed by 26-08). One completed `pg-backup-rehearsal` Job and one
209 KiB dump object remain. Scratch databases were dropped; the stale `jtoye-staging` namespace was
deleted and is not coming back.

## Self-Check: PASSED

Files:
- `FOUND: k8s/LOCAL.md` (§11 rows L1–L5 filled; L6/L7 unfilled with stated reason)
- `FOUND: docs/runbooks/backups.md` (dated in-cluster section; in-cluster checkbox ticked and scoped)
- `FOUND: .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-07-SUMMARY.md`
- `FOUND: .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md` (guard gap appended)

Commits: `5bdee21`, `43fb5e1`, `db7e87c`, `b460053`, `6a66401`, `cfcca4c`, `880f430` — all verified present.

Evidence integrity: `localhost:9090` inside §11 fenced blocks = **0** over 278 lines;
`jtoye-db-backups` in `k8s/LOCAL.md` = 7; all four image identities recorded and all four built during
this run; `denied the request` = 0 across all eight captured run logs.
