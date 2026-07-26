---
phase: 26-local-k8s-overlay-verified-breakage-fixes
verified: 2026-07-26T01:30:00Z
status: passed
score: 8/8 must-haves verified (1 additional stretch-goal truth resolved via override)
overrides_applied: 1
overrides:
  - must_have: "A KDS client actually receives a relayed STOMP event (D-06 functional proof, 26-CONTEXT.md / 26-VALIDATION.md row INFRA-02d)"
    reason: "Deliberately pursued beyond REQUIREMENTS.md's literal INFRA-02(d) wording (\"stomp-login/passcode wiring reaches spring config\") and beyond ROADMAP.md Success Criterion 4 (\"wiring reaches the spring config ... no boot-time Access refused\") — both of which ARE met. The stronger functional row was FALSIFIED on the live cluster: k8s/LOCAL.md §11 L6 records 14 SUBSCRIBE / 14 'Invalid destination' ERROR / 0 MESSAGE frames, because a RabbitMQ /topic STOMP destination cannot contain '/' while the app subscribes to /topic/kitchen/{tenantId}/{shopId}. k8s/base/configmap.yaml:36 sets stomp.broker.mode: relay with no staging/production override, so this is a genuine, confirmed PRE-EXISTING production defect (not introduced by this phase), whose fix spans OrderStateChangeListener.java, kitchen/page.tsx and TenantChannelInterceptor's tenant-isolation parser — out of this deploy-layer phase's scope by Rule 4. Reported honestly and consistently in 26-VALIDATION.md, REQUIREMENTS.md INFRA-02(d), k8s/LOCAL.md §7 A3/§11, deferred-items.md, and STATE.md; never smoothed into a pass. Tracked as GitHub issue #266 (bug/P1, confirmed OPEN, correctly labelled) for its own scoped fix."
    accepted_by: "user (26-08 checkpoint:human-verify gate, 2026-07-25 — gate APPROVED with the falsification explicitly reported as one of two disposition observations)"
    accepted_at: "2026-07-25T00:00:00Z"
---

# Phase 26: Local-K8s Overlay + Verified Breakage Fixes Verification Report

**Phase Goal:** The imperative deploy patches from the 2026-07-14 live-deploy rehearsal are replaced by a committed, buildable `k8s/local` overlay, and the verified k8s breakage list is fixed so core boots as the NOSUPERUSER app role on a single replica.
**Verified:** 2026-07-26T01:30:00Z
**Status:** passed
**Re-verification:** No — initial verification

> **POST-PHASE ANNOTATION — added 2026-07-26 by a records reconcile. Applies to every `#266` reference in
> this report, including the frontmatter override reason. Nothing is rewritten — this report records what
> was true at `2026-07-26T01:30:00Z`, and the "confirmed OPEN" observations were correct at that instant.**
>
> Issue **#266** was **CLOSED** later the same day, at **2026-07-26T10:03:13Z**, by PR **#269** (merged to
> main as **`d964a85`**). The destination is now built in one place —
> `core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java` — as
> `/topic/{feature}.{tenantId}[.{qualifier}]`, one dot-separated segment the broker accepts;
> `TenantChannelInterceptor` parses that shape and its cross-tenant denial was **re-run, not assumed**.
>
> **This does not turn truth #9 green, and it must not be read as doing so.** The **code defect** that
> falsified L6 is **FIXED**, with unit + integration coverage and a live two-arm probe of the destination
> *shape*. The **live functional proof — row L6, *a KDS client actually receives a relayed order event
> through a real broker* — has still never been captured**, and capturing it needs a running cluster. So
> **INFRA-02(d) remains closed on credential wiring only**, exactly as the override recorded; **L6 is now
> an open evidence gap rather than a defect. A fix is not a proof.**

## Verification Method

No previous `26-VERIFICATION.md` existed (initial mode). Environment at verification time: minikube
profile `jtoye` is **Stopped** (not deleted — etcd preserved per the phase's own recorded teardown),
and all ten docker-compose services (`postgres, redis, rabbitmq, keycloak, minio, mailhog, core-java,
frontend, edge-go, mcp-server`) are **Up and healthy** — exactly the state the phase's own end-state
record (`k8s/LOCAL.md` §10 "Phase 26 end state") claims to have restored. Live cluster re-verification
was therefore correctly out of scope, per the task brief; verification instead (a) re-ran every static
gate against the current tree, (b) read the committed live-evidence block in `k8s/LOCAL.md` §11 and
cross-checked its claims against REQUIREMENTS.md / ROADMAP.md / STATE.md / deferred-items.md for
internal consistency, (c) spot-checked the underlying manifest/script/application-config files the
SUMMARYs describe, (d) confirmed GitHub issue #266 exists, is open, and is correctly labelled, and
(e) confirmed the full regression suite (unit/integration/jest) that 26-09 claims to have run left
matching zero-failure result artifacts on disk.

This is an unusually well self-audited phase — its own closing plan (26-09) already ran a full-suite
regression sweep and an evidence-block audit before declaring completion, and its own validation
contract (`26-VALIDATION.md`) already records one row as deliberately RED. The adversarial posture
below therefore focused on confirming those self-reported results are *real* (re-running gates,
reading raw XML, checking the GitHub issue), not merely re-reading the narrative.

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|-------|--------|----------|
| 1 | `kubectl kustomize k8s/local` builds and a server dry-run resolves every reference — no dangling secret/configmap/label refs (Roadmap SC1 / INFRA-01) | VERIFIED | `bash k8s/scripts/check-no-plaintext-secrets.sh` re-run live: exit 0, `[k8s/local]: build succeeded, 23 resources, 0 plaintext Secrets`. `k8s/LOCAL.md` §11 L1 records the live server dry-run: exit 0, 23 objects `(server dry run)`, 0 `denied the request` across 8 captured run logs. |
| 2 | The `k8s/local` overlay shims endpoints to `host.minikube.internal`, sets the D-09 scale triple to 1, and repoints the backup CronJob to host MinIO (Roadmap SC2 / INFRA-01) | VERIFIED | `bash k8s/scripts/check-render-invariants.sh` re-run live: exit 0 — `LOC-1 OK (8 keys shimmed by name, 8 render occurrence(s))`, `LOC-2 OK (replicas/minReplicas/minAvailable = 1 x3 each; maxReplicas [10 10 20] == base)`, `LOC-3 OK (http://host.minikube.internal:9000)`. `k8s/local/` on disk has the 6 files the SUMMARYs claim (`kustomization.yaml`, `namespace.yaml`, `configmap-patch.yaml`, `scale-patch.yaml`, `ingress-patch.yaml`, `sse-ingress-patch.yaml`). |
| 3 | `DB_PORT` is injected via `valueFrom.secretKeyRef` (no hardcoded `5432`), and secrets use `DB_USER`/`DB_PASSWORD` (`jtoye_app` NOSUPERUSER), so core boots without `DatabaseConfigurationValidator` refusing a DB superuser (Roadmap SC3 / INFRA-02a/b) | VERIFIED | `check-render-invariants.sh` INV-1 (`no 'value: \"5432\"' line`) and INV-2 (`DB_PORT present, 0 with both value+valueFrom`) both green on the current render, across all 4 kustomize targets. `k8s/QUICK_START.md:81` and `k8s/base/secrets-template.yaml.example:82,228` all read `username='jtoye_app'` (INV-5 green). `k8s/LOCAL.md` §11 L2/L2b record the live corroboration: decoded secret port 5433, validator log counts 1/1/0, DB-side `rolsuper=f`. |
| 4 | The pg-backup CronJob targets host MinIO, and the STOMP relay `stomp-login`/`passcode` wiring reaches the Spring config with no boot-time `Access refused for user 'guest'` (Roadmap SC4 / INFRA-02c/d, literal wording) | VERIFIED | `check-env-contract.sh` direction (a) green (49/49 injected names read, 0 violations). `k8s/LOCAL.md` §11 L3 (`.status.succeeded=1`, object uploaded to host MinIO) and L5 (`grep -c "Access refused for user"` = 0, plus broker-side `auth_login=jtoye`, `guest`=0). This truth is deliberately narrower than "the relay delivers messages" — see truth 9 below, which is the stronger row this phase separately (and honestly) FALSIFIED. |
| 5 | DEF-5 split-horizon issuer wiring reaches k8s, proven by a real vendor login through the Ingress landing on a dashboard (Roadmap SC / D-16) | VERIFIED (recorded live, human-confirmed) | `k8s/LOCAL.md` §11 L7: authorize redirect to the public issuer with `client_id=core-api`, landing on `http://app.jtoye.local/dashboard` in 591 ms, 10/10 API calls to `api.jtoye.local`, 0 loopback requests, real seeded data. Human gate APPROVED per 26-08 SUMMARY (`human_gate: APPROVED 2026-07-25`). |
| 6 | The dangling `auth.jtoye.co.uk → keycloak` Ingress rule (no Service anywhere in any render) and its shared TLS SAN are fixed in `k8s/base`, not merely absent locally, so staging/production stop publishing a host with no backend | VERIFIED | `grep -n "auth.jtoye.co.uk\|keycloak" k8s/base/ingress.yaml` returns only the explanatory comment block (lines 50/71/78) — no live rule or SAN entry. `render-golden.sh` confirms staging/production still match their committed goldens with the rule gone, i.e. the change was reviewed and is not silent drift. `check-render-invariants.sh` INV-6 is asserted on **every** target (not local-only), per STATE.md's recorded proof that a local-only assertion would have missed this. |
| 7 | The kube-dns NetworkPolicy selector-poisoning defect (D-17) — common labels leaking into the DNS-egress `podSelector` via `includeSelectors: true` — is fixed in base/production, not merely local, with a render-level CI assertion | VERIFIED | `k8s/base/kustomization.yaml:67` reads `includeSelectors: false` with an explicit 3-kind `fields:` list. `check-render-invariants.sh` INV-3 green across all 4 targets (`4 kube-dns selector block(s), each exactly 1 key`). |
| 8 | Recurrence-prevention CI gate (D-07/D-08) closes both the DEF-4 (injected-but-unread) and DEF-6 (unsupplied local-only default) defect classes, with a reasoned allowlist, wired into `k8s-validate` | VERIFIED | `check-env-contract.sh` re-run live: exit 0, direction (a) 49/49 read (1 reasoned exemption), direction (b) 117 placeholders / 0 violations (3 reasoned exemptions). `.github/workflows/ci-cd.yaml:191-247` wires all 5 static gates (`check-no-plaintext-secrets`, `check-connection-math`, `check-env-contract`, `check-render-invariants`, `render-golden`) into the `k8s-validate` job. |
| 9 | *(Stretch, not a roadmap/requirements literal — D-06)* A KDS client actually receives a relayed STOMP order event through the ingress-fronted relay | **FAILED — honestly reported, PASSED (override)** | `k8s/LOCAL.md` §11 L6: 14 SUBSCRIBE / 14 `Invalid destination` ERROR / **0 MESSAGE**. Root cause: a RabbitMQ `/topic` STOMP destination cannot contain `/`; the app subscribes to `/topic/kitchen/{tenantId}/{shopId}`. `k8s/base/configmap.yaml:36` sets `relay` with no staging/production override — **confirmed pre-existing production defect**. Tracked as GitHub **issue #266** (confirmed via `gh issue view 266`: OPEN, labelled `bug`+`P1`). See override entry in frontmatter. |

**Score:** 8/8 roadmap-literal must-haves VERIFIED. 1 additional self-imposed stretch-goal truth (#9) resolved via a documented, traceable override rather than silently passed or hidden.

### Required Artifacts

| Artifact | Expected | Status | Details |
|----------|----------|--------|---------|
| `k8s/local/kustomization.yaml`, `namespace.yaml`, `configmap-patch.yaml`, `scale-patch.yaml`, `ingress-patch.yaml`, `sse-ingress-patch.yaml` | Committed local overlay (6 files) | VERIFIED | All present on disk; `kubectl kustomize k8s/local` renders 23 resources (confirmed live). |
| `k8s/scripts/check-env-contract.sh` | Two-direction env contract gate (D-07/D-08), 464 lines | VERIFIED | 464 lines confirmed; re-run live, exit 0, matches SUMMARY's claimed counts exactly (49 injected / 117 placeholders). |
| `k8s/scripts/check-render-invariants.sh` | INV-1..6 + LOC-1..6 render assertions, ~1011 lines | VERIFIED | Re-run live, exit 0, all INV/LOC rows green, matches claimed counts. |
| `k8s/scripts/render-golden.sh` + `k8s/goldens/{staging,production}.yaml` | Golden-render diff harness, fail-closed | VERIFIED | Re-run live, exit 0, both goldens match at 1469 lines each. |
| `scripts/k8s-local-secrets.sh`, `scripts/k8s-local-up.sh`, `scripts/lib/k8s-local-guards.sh` | Bootstrap script pair + XOR guards (D-01..D-04, D-14) | VERIFIED | All present; `k8s/LOCAL.md` §10 records the guard actually refusing at end-of-phase teardown, matching its earlier refusal at start — a live-tested control, not a written-only one. |
| `scripts/deploy.sh` | Phantom `dev` target fixed/removed (D-14) | VERIFIED | `dev)` case now exits 1 with an explanatory message; `staging\|production` are the only accepted targets. |
| `k8s/LOCAL.md` | Rehearsal runbook + evidence template, §11 filled | VERIFIED | 1897 lines; §11 contains all 7 required live rows (L1-L7) plus 5 supplementary rows, each with Command/Expected/Actual. |
| `core-java/src/main/resources/application.yml` (STOMP fallback chain) | `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` additive chain (D-05) | VERIFIED | Present at lines 246-249, matches CONTEXT.md D-05 exactly. |
| `core-java/src/test/java/uk/jtoye/core/config/StompCredentialResolutionTest.java` | 3-case resolution test | VERIFIED | File exists; test-result XML on disk shows `tests="8" failures="0" errors="0"`, timestamped 2026-07-25 23:47 (inside the phase's execution window). |
| `.github/workflows/ci-cd.yaml` (`k8s-validate` job) | All 5 static gates wired | VERIFIED | Lines 183-247 confirm all 5 gate invocations present and in the order documented. |
| `k8s/base/ingress.yaml` | Dangling `auth.jtoye.co.uk` rule + TLS SAN removed | VERIFIED | Confirmed absent from `spec.tls`/`spec.rules`; replaced with an explanatory comment block citing the phase/plan/adjudication. |

### Key Link Verification

| From | To | Via | Status | Details |
|------|----|----|--------|---------|
| `k8s/local/kustomization.yaml` | `k8s/base` | `resources: [../base, ...]` | WIRED | `kubectl kustomize k8s/local` builds successfully and includes base resources (23 total rendered, base contributes the bulk). |
| `k8s/local/configmap-patch.yaml` | host MinIO | `s3.backup.endpoint` | WIRED | `LOC-3 OK (http://host.minikube.internal:9000)` — confirmed on live render; live CronJob run (L3) actually uploaded through this endpoint. |
| `k8s/base/core-java-deployment.yaml` `DB_PORT` | `postgres-credentials` secret | `valueFrom.secretKeyRef.key: port` | WIRED | INV-2 green; live pod env (L2b) shows `secretKeyRef` present, decoded value 5433, and the pod holds live Postgres backends on exactly that port. |
| `application.yml` STOMP credential chain | `k8s/base` `RABBITMQ_USER` env | Nested `${STOMP_CLIENT_LOGIN:${RABBITMQ_USER:guest}}` fallback | WIRED | `StompCredentialResolutionTest` 8/8 green (all 3 precedence levels); live broker identity (L5 broker-side) shows connection as `jtoye`, not `guest`. |
| `.github/workflows/ci-cd.yaml` `k8s-validate` job | 5 gate scripts | Direct `run:` invocations | WIRED | All 5 scripts invoked in sequence; independently re-run and confirmed exit 0 on the current tree. |
| `scripts/k8s-local-up.sh` | frontend image build | `--build-arg NEXT_PUBLIC_API_URL=...` | WIRED | Confirmed at lines 411-417; addresses D-18 (build-time-only `NEXT_PUBLIC_*` vs the removed dead runtime injection at `k8s/base/frontend-deployment.yaml:50`). |

### Data-Flow Trace (Level 4)

Not applicable in the conventional sense (no user-facing dynamic-data component in this phase). The
closest analogue — "does the live evidence in `k8s/LOCAL.md` §11 reflect real cluster state rather than
a static fixture" — was checked by: (a) re-running all 5 static gates independently and getting results
matching the recorded ones exactly (same counts, e.g. 49/117/8/6/1469-lines); (b) confirming the
`StompCredentialResolutionTest` result XML exists on disk with a real timestamp inside the phase's
execution window; (c) confirming `docs/metrics.json` and the `docs-freshness.sh` gate both independently
read 1698, matching the SUMMARY's claimed count; (d) confirming GitHub issue #266 is real, open, and
correctly labelled rather than an invented citation.

### Behavioral Spot-Checks

| Behavior | Command | Result | Status |
|----------|---------|--------|--------|
| All 3 quick static gates pass on the current tree | `bash k8s/scripts/check-no-plaintext-secrets.sh && bash k8s/scripts/check-connection-math.sh && bash k8s/scripts/check-env-contract.sh` | All 3 exit 0; counts match SUMMARY claims exactly | PASS |
| Render-invariants gate passes | `bash k8s/scripts/check-render-invariants.sh` | exit 0; INV-1..6 + LOC-1..6 all green | PASS |
| Golden-render diff passes | `bash k8s/scripts/render-golden.sh` | exit 0; both goldens match at 1469 lines | PASS |
| docs-freshness gate passes | `bash scripts/docs-freshness.sh` | exit 0; total 1698, matches `docs/metrics.json` | PASS |
| Ingress dangling rule genuinely removed | `grep -n "auth.jtoye.co.uk\|keycloak" k8s/base/ingress.yaml` | Only explanatory comments remain, no live `rules:`/`tls:` entry | PASS |
| Deploy-script phantom `dev` target fixed | `grep -n -A10 "dev)" scripts/deploy.sh` | Now exits 1 with explanation; `staging\|production` only accepted targets | PASS |
| Full JVM/Jest regression suite left 0-failure artifacts on disk | `grep -l 'failures="[1-9]\|errors="[1-9]' core-java/build-local/test-results/{test,integrationTest}/*.xml` | 0 matches across 104 unit-test classes and 98 integration-test classes | PASS |
| GitHub issue #266 exists, is open, correctly labelled | `gh issue view 266 --repo Bralabee/JToye_OaaS_2026 --json state,labels,title` | `state: OPEN`, labels `bug`, `P1`, title matches the STOMP relay defect | PASS |
| Environment restored to canonical compose runtime | `docker compose ps` / `minikube status -p jtoye` | 10/10 compose services Up+healthy; minikube profile `jtoye` = Stopped (not deleted) | PASS |

### Probe Execution

Not applicable — this phase's "probes" are the 5 committed bash gates under `k8s/scripts/`, already
covered under Behavioral Spot-Checks above (each independently re-run, not merely narrated).

### Requirements Coverage

| Requirement | Source Plans | Description | Status | Evidence |
|--------------|--------------|-------------|--------|----------|
| INFRA-01 | 26-01, 26-03, 26-04, 26-05, 26-06, 26-07, 26-09 | Committed `k8s/local` overlay: endpoint shims, `minReplicas=1`, backup → host MinIO, replacing imperative patches | SATISFIED | Build succeeds (re-verified live), LOC-1..6 render invariants green (re-verified live), L1/L1b/L1c/L1d live rollout+ingress-smoke+re-runnability evidence in `k8s/LOCAL.md` §11. |
| INFRA-02 | 26-01, 26-02, 26-03, 26-07, 26-08, 26-09 | (a) `DB_PORT` via `secretKeyRef`; (b) NOSUPERUSER `jtoye_app`; (c) pg-backup → host MinIO + non-empty dump falsification; (d) STOMP credential wiring reaches Spring config | SATISFIED, with (d) explicitly and consistently scoped to credential-wiring only (not relay message delivery) — see truth #9 and the frontmatter override | INV-1/INV-2/INV-5 green (re-verified live); `k8s/LOCAL.md` L2/L2b/L3/L4/L5 live evidence; the stronger D-06 functional row is FAILED, tracked as issue #266, and this scoping is stated identically and traceably in `ROADMAP.md`, `REQUIREMENTS.md`, `26-VALIDATION.md`, `k8s/LOCAL.md` §7 A3, and `STATE.md` — no document overclaims relay function. |

No orphaned requirement IDs found: `.planning/REQUIREMENTS.md` maps exactly INFRA-01 and INFRA-02 to Phase 26, and both appear in every plan's `requirements:` frontmatter field that touches them (cross-checked against all 9 `26-0N-PLAN.md` files).

### Anti-Patterns Found

None. Swept every file the 9 SUMMARYs list as modified (34 files per `git diff --stat` against the
Phase-26 merge-base) for `TBD|FIXME|XXX` (0 hits) and `TODO|HACK|PLACEHOLDER` (0 hits beyond legitimate
regex-variable names inside `check-env-contract.sh` that parse Spring `${PLACEHOLDER}` syntax — not
stub markers). No `return null`/empty-stub patterns applicable (no application code was touched beyond
the additive STOMP credential chain, which is unit-tested 8/8).

### Regression Check

- `check-connection-math.sh` — re-run live: exit 0, 133 ≤ 157 with the pool-size drift guard and HPA
  memory-metric guard both passing, unchanged from the pre-phase baseline.
- `render-golden.sh` — re-run live: exit 0, both staging and production renders byte-match their
  committed goldens (1469 lines each) despite four separate edits to `k8s/base` across four plans —
  confirming the Incremental Betterment claim that base changes cost staging/production nothing.
- Full JVM regression: 104 unit-test classes / 767 tests, 98 integration-test classes / 392 tests, all
  present on disk with **zero** `<failure>`/`<error>` elements — this is the exact suite the repo's own
  memory (`trap_scope_gate_integrationtest_regression`) warns is easy to under-run; 26-09 explicitly ran
  the whole thing rather than only its own new test, and the artifacts on disk corroborate that it did.
- `docs/metrics.json` / `docs-freshness.sh` — re-run live: exit 0, 1698, matching CLAUDE.md/AGENTS.md
  (both now read 1698, resolving the CONTEXT.md-flagged staleness).
- Environment restored: all 10 compose services Up+healthy (verified via `docker compose ps`, not
  merely narrated), minikube `jtoye` profile Stopped (not deleted), and the compose-XOR guard was
  re-tested refusing at teardown time — matching `k8s/LOCAL.md` §10's recorded proof.

### Human Verification Required

None outstanding. The live-cluster verification this phase's validation contract calls for was already
performed **during execution**, behind two explicit `checkpoint:human-verify` gates (plans 26-07 and
26-08), both recorded as `APPROVED` in their respective SUMMARY.md files with the actual command output
captured verbatim in `k8s/LOCAL.md` §11. There is no live cluster available at verification time (by
design — it was torn down per the phase's own end-state record), so there is nothing further to ask a
human to click through; a re-run of the rehearsal (if ever needed) is a single documented command
(`scripts/k8s-local-up.sh`), not a manual sequence.

### Gaps Summary

No blocking gaps. Every roadmap Success Criterion and every REQUIREMENTS.md sub-item, taken at its
literal, as-written wording, is verified against the current codebase — not merely asserted by the
SUMMARYs, but independently re-run (5 static gates), independently cross-checked (GitHub issue,
regression-test XML, file greps), and found to match exactly.

The phase's own validation work additionally pursued a stronger proof than any roadmap/requirements
text required — that the STOMP relay actually delivers a message to a live KDS WebSocket client — and
that stronger proof was **falsified**. This is reported here, as it is everywhere else in the repo's own
paper trail, as a real, pre-existing, confirmed production defect (staging and production already run
the broken `relay` mode), deliberately not fixed in this deploy-layer phase (the fix spans application
code across three files and would itself need its own threat model), and tracked as GitHub issue #266.
Handled via an explicit override rather than a silent pass, because it genuinely does not satisfy the
truth as originally aspired to (D-06) — but it also does not fail the phase's actual, literal
commitments, and the scoping is stated identically and honestly in every document that touches it. This
is the single most important thing this verification scrutinised, per the task brief, and the conclusion
is: **the closure is honest and traceable.**

---

*Verified: 2026-07-26T01:30:00Z*
*Verifier: Claude (gsd-verifier)*
