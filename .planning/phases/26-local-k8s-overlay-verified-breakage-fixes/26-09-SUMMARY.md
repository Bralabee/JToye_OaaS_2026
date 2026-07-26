---
phase: 26
plan: "09"
subsystem: infrastructure
tags: [phase-gate, regression-sweep, requirements-closure, k8s-local, evidence-audit, teardown]
requires:
  - 26-01..26-08 (all nine waves' deliverables and their recorded evidence)
  - k8s/LOCAL.md §11 evidence block (L1–L7, filled by 26-07 and 26-08)
provides:
  - INFRA-01 and INFRA-02 closed with per-sub-item cited proofs
  - a full-suite regression result over everything the phase changed
  - a fully-resolved 26-VALIDATION contract (19 rows, 18 green / 1 red)
  - the canonical local dev/E2E runtime RESTORED and proven restored
  - GitHub issue #266 as the tracked home for the falsified relay defect
affects:
  - .planning/REQUIREMENTS.md
  - .planning/ROADMAP.md
  - .planning/STATE.md
  - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-VALIDATION.md
  - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
  - k8s/LOCAL.md
tech-stack:
  added: []
  patterns:
    - "closure-by-citation: a requirement is closed by naming the assertion and its recorded result, never by naming a plan"
    - "falsify-before-trusting: every grep-based criterion is run against a deliberately broken input first"
    - "scoped completion: mark complete only what is proven; state the unproven remainder and give it a tracked home"
key-files:
  created:
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-09-SUMMARY.md
  modified:
    - k8s/LOCAL.md
    - .planning/REQUIREMENTS.md
    - .planning/ROADMAP.md
    - .planning/STATE.md
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/26-VALIDATION.md
    - .planning/phases/26-local-k8s-overlay-verified-breakage-fixes/deferred-items.md
decisions:
  - "INFRA-02(d) closed on its stated credential-wiring acceptance ONLY; the stronger D-06 functional relay row stays FALSIFIED and is tracked as #266 rather than smoothed into a pass"
  - "The falsified relay row is recorded RED in 26-VALIDATION, not reclassified as deferred — a falsification is a stronger result than an unproven row"
  - "The plan's literal-value secret sweep is unsatisfiable and was replaced with a falsifiable credential-shape + base64 form"
  - "End state `stop-and-restore`: minikube stopped (not deleted) FIRST, then the four compose app containers started — the XOR order"
metrics:
  duration: ~1h35m
  tasks: 3
  files: 6
  completed: 2026-07-26
---

# Phase 26 Plan 09: Phase-Gate Closure Summary

Closed Phase 26 by running the whole suite rather than this phase's own tests, auditing every evidence
row, marking INFRA-01/INFRA-02 complete with per-sub-item citations while keeping the falsified KDS
relay row red and tracked as #266, and performing — not merely documenting — the teardown that restores
the project's canonical compose runtime.

> **POST-PHASE ANNOTATION — added 2026-07-26 by a records reconcile. Applies to every `#266` reference in
> this document. Nothing below is rewritten: this is a dated phase record and its findings were correct
> when measured.**
>
> Issue **[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266)** is now **CLOSED**
> (2026-07-26T10:03:13Z), fixed by PR **#269**, merged to main as **`d964a85`** and already an ancestor of
> this branch. The destination is now built in one place — `core-java/src/main/java/uk/jtoye/core/websocket/StompDestinations.java`
> — as `/topic/{feature}.{tenantId}[.{qualifier}]`, a single dot-separated segment the broker accepts and
> the idiomatic AMQP routing key. `TenantChannelInterceptor` parses that shape, and the cross-tenant
> denial this record insisted must be re-tested rather than assumed **was re-run** (`TenantChannelInterceptorTest`
> case 5, plus a new rejection of any slashed `/topic/` destination on shape alone).
>
> **Two things must not be collapsed into one.** The **code defect** that falsified L6 is **FIXED**, with
> unit + integration coverage (`StompDestinationsTest`, `TenantChannelInterceptorTest`,
> `TenantChannelInterceptorShopGateIntegrationTest`, `OrderStateChangeListenerTest`,
> `OrderStateChangeListenerIdempotencyIntegrationTest`, `kitchen/__tests__/page.test.tsx`,
> `use-stomp.test.ts`) and a live two-arm broker probe of the destination *shape*. But the **live
> functional proof — evidence row L6, *a KDS client actually receives a relayed order event through a real
> broker* — has still never been captured**, and capturing it needs a running cluster.
>
> Therefore **INFRA-02(d) remains closed on credential wiring only.** It is **not** upgraded to "the
> realtime path is proven working": **L6 is now an open evidence gap rather than a defect. A fix is not a
> proof** — treating it as one would be the same green-by-construction failure this phase spent nine plans
> building against.

## What Was Built

Nothing was built. This plan is a closer: it verifies, audits, records and restores. Its output is a
project record a later reader can audit without re-running anything, plus a development environment
returned to the state the phase found it in.

## Task-by-Task

### Task 1 — full regression sweep + evidence-block audit (`2aa1291`)

The phase changed `core-java/src/main/resources/application.yml`, so the whole JVM suite was in scope.
This repo's recorded failure mode is a per-plan executor running only its own new tests and shipping a
suite-wide regression (the scope-gate `integrationTest` trap), which is why the closer ran everything.

| Suite / gate | Result |
|---|---|
| `./gradlew :core-java:cleanTest :core-java:test --no-daemon` | **BUILD SUCCESSFUL 47s** — 104 classes / **767 tests** / 0 failures / 0 errors / 1 skipped |
| `./gradlew :core-java:cleanIntegrationTest :core-java:integrationTest --no-daemon` | **BUILD SUCCESSFUL 40m** — 98 classes / **392 tests** / 0 failures / 0 errors / 1 skipped |
| `cd frontend && npm run build` | exit 0 (this is the tsc gate — jest does not type-check) |
| `cd frontend && npx jest --ci` | **59 suites / 377 tests** / 0 failures / 2 snapshots |
| `bash scripts/docs-freshness.sh` | exit 0 — total logical invocations **1698** |
| `check-no-plaintext-secrets.sh` | exit 0 — 3 targets, 23 resources each, 0 plaintext Secrets |
| `check-connection-math.sh` | exit 0 — 133 ≤ 157 with ≥20% headroom |
| `check-env-contract.sh` | exit 0 — 49 injected names all read, 0 violations either direction |
| `check-render-invariants.sh` | exit 0 — INV-1..INV-6 over 4 targets, LOC-1..LOC-6 on `k8s/local` |
| `render-golden.sh` | exit 0 — staging + production match goldens (1469 lines each) |
| Go suite | **correctly NOT run** — `git diff --name-only <phase-base>..HEAD -- '*.go'` = **0 files** |

**Two traps avoided rather than survived.** `cleanTest` is load-bearing: without it the task reports
`UP-TO-DATE` / `BUILD SUCCESSFUL` while executing **nothing**. And the counts came from **`build-local`**
(`core-java/build.gradle.kts:15` redirects the build directory) — `core-java/build/test-results/` is a
stale 2025-12-27 artifact reporting **3 failures**, a false RED that anyone reading the default path
would have published as this run's result.

**The `integrationTest` delta is explained, not waved through.** Phase 23's recorded baseline was 81
classes / 332 tests / 1 skipped; this run is 98 / 392 / 1. All of the +17 classes / +60 tests comes from
Phases 24–25 (29 of the 98 classes match media / idempotency / shop-access / staff / scope). Phase 26
added exactly one Java test and it is a *unit* test — `StompCredentialResolutionTest`, 8/8, the D-05
credential-chain resolution test.

**`npx tsc --noEmit` is red at 366 and that is the pre-existing count, unchanged.** All 366 are jest-dom
matcher typings in `*.test.tsx` files that `next build` never checks. The honest assertion is
*count-unchanged at 366*, not *exit 0*; stating it as exit 0 makes the gate permanently red and therefore
permanently ignored.

**Jest's 377 reconciles exactly with `docs/metrics.json`'s `jest_blocks: 382`.** `scripts/docs-freshness.sh:56`
counts the lexical token `\b(it|test)\(`, which also matches five `RegExp.prototype.test(` calls — four in
`frontend/__tests__/link-graph.test.ts` (lines 47 ×2, 117, 119) and one in
`frontend/app/dashboard/kitchen/__tests__/page.test.tsx:210`. 382 − 5 = **377**, with zero skipped or
`.todo` tests. `docs/metrics.json` was **not** touched — plan 26-06 is its single writer, and the gate
exits 0 as committed.

**Evidence audit: 7 live rows required, 7 filled.** `26-VALIDATION.md` types exactly 7 rows as `live`;
§11 carries exactly 7 primary rows L1–L7 answering them one-for-one, plus 5 supplementary rows
(L1b/L1c/L1d, L2b/L2c). Placeholder sweep **0**; application-loopback-inside-fences **0** over **598**
captured-output lines. Both were falsified against deliberate breaks before being trusted. Four image
identities present in the run header (all four built during the run, so a stale-image pass was excluded)
and both backup arms recorded.

### Task 2 — requirements, roadmap, state, validation (`ccaf55e`, SHA backfill `7aa7448`)

INFRA-01 and INFRA-02 marked complete in the style the AI-02 entry uses: original requirement text
preserved verbatim, dated completion note appended. The four INFRA-02 sub-item citations are reproduced
verbatim in *Requirement Closure* below.

`26-VALIDATION.md` fully resolved: **19 rows, 18 green / 1 red**, all five Wave-0 items ticked with the
file that satisfies each, both Manual-Only rows resolved, `nyquist` and `wave_0` flags set true.

ROADMAP: 9/9 Complete 2026-07-26, all nine plan entries ticked, each of the four success criteria
carries a proof pointer, and criterion 4 carries its scope limit explicitly. STATE: 6/6 phases,
48/48 plans, 100%, updated **by hand**.

### Task 3 — perform the teardown, restore the canonical runtime (`94660e4`)

Decision **`stop-and-restore`**. See *End-State Decision* below.

## Requirement Closure

### INFRA-01 — COMPLETE

Static half: `check-no-plaintext-secrets.sh` exit 0 (`[k8s/local]: build succeeded, 23 resources, 0
plaintext Secrets`), `check-render-invariants.sh` exit 0 for LOC-1..LOC-6 (eight endpoint shims, the
D-09 scale triple with `maxReplicas` byte-identical to base at 10/20/10, the host-MinIO backup
endpoint), and `render-golden.sh` exit 0 proving staging and production renders were not disturbed.
Live half: §11 **L1** — server dry-run exit 0, captured **verbatim**, 23 objects, `denied the request`
= **0 across all eight captured run logs**. Verbatim capture earned its keep: two earlier attempts
failed with the admission webhook *unreachable*, a state that shares an exit code with a genuine pass.

### INFRA-02 — COMPLETE, four sub-items cited separately

**(a) `DB_PORT` via `secretKeyRef`, no hardcoded port.** *Static:* INV-1 (no `value: "5432"` in the
core-java env block) and INV-2 (`DB_PORT` carries **exactly one** of `value`/`valueFrom`, permanently
guarding the both-fields trap where a stale `value` would silently win), asserted **on the render**
across all 4 targets. *Live —* **L2b**: `{"name":"DB_PORT","valueFrom":{"secretKeyRef":{"key":"port","name":"postgres-credentials"}}}`,
`secretKeyRef present : 1`, `"value" field present : 0`, decoded secret `port` = **5433**. The pod holds
5 live backends and host Postgres publishes **only** 5433, so a live connection is itself the proof:
the deleted `value: "5432"` would have connected to nothing.

**(b) NOSUPERUSER `DB_USER`/`DB_PASSWORD`.** Two independent arms, because the app log is only the app's
claim about itself. *Arm 1 —* **L2**: `grep -c "is NOT a superuser"` = **1**, `"DATABASE SECURITY
VALIDATION PASSED"` = **1**, `"…FAILED"` = **0**, `Database username: jtoye_app`. *Arm 2 —*
`SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname='jtoye_app'` → **`f|f`**. *Arm 2b,
attribution:* `pg_stat_activity` **16 → 0 → 5** (the zero is the control), all four compose apps
`exited` (elimination), 5 backends sharing `backend_start 20:37:48` against pod `startedAt 20:37:36Z`
(correlation). Recorded correction: `client_addr` is `172.18.0.1`, **not** the minikube subnet, because
traffic is double-NAT'd — a "client_addr is on the minikube bridge" assertion would fail on a perfectly
healthy run.

**(c) pg-backup CronJob → host MinIO, and the dump is really non-empty.** *The job —* **L3**:
`kubectl wait` returned `condition met`, `.status.succeeded` = **1**, job log shows it dumping as
`jtoye_backup` against `host.minikube.internal:5433` and uploading via
`s3.backup.endpoint http://host.minikube.internal:9000`; object present at **214370 bytes**;
unauthenticated GET of that exact key → **403**, with a known `jtoye-images` object → **200** as the
control, and the key confirmed in the bucket listing *before* the 403 was interpreted (MinIO returns 403
for a nonexistent key too, so an unordered probe is satisfiable by absence). *The falsification —* **L4**:
**arm A = products 0** (app-role dump, which still clears `MIN_BACKUP_BYTES` **by 149×** and passes
`pg_restore --list` with 393 TOC entries — so a zero-row dump satisfies *every* automated content check
the pipeline has), **arm B = products 47** (`orders=23 customers=12 shops=5`, an exact match to the live
DB read through the BYPASSRLS role).

**(d) STOMP relay credentials reach spring config — MET, and scoped precisely.** *Static:*
`check-env-contract.sh` direction (a) exit 0 — 49 injected env names all read, 0 injected-but-unread
beyond 1 reasoned exemption. *Live —* **L5**: `grep -c "Access refused for user"` = **0**, and
`AuthenticationFailureException|ACCESS_REFUSED|com.rabbitmq.client.AuthenticationFailure` = **0**.
*Broker-side:* **1** `STOMP 1.2` connection with `auth_login`/`user` = **`jtoye`**, `guest` = **0**, an
MQTT **non-vacuity control** at 0 and a fixture proving the guest predicate **can** fire at 1; pod
`printenv` resolves `STOMP_BROKER_MODE=relay`, `STOMP_CLIENT_LOGIN=jtoye`. The plan's original
assertion form was **unsatisfiable**: `rabbitmqctl list_connections` lists AMQP readers only on
RabbitMQ 3.12, so `list_connections | grep -ci stomp` can never return ≥1 however healthy the relay is.

**NOT CLAIMED — the KDS realtime path does not work.** `26-VALIDATION.md` carries a stronger INFRA-02d
row than the requirement's text — *a KDS client actually receives a relayed event* (D-06) — and it is
**FALSIFIED**: **L6** records 14 SUBSCRIBE / 14 `Invalid destination` / **0 MESSAGE**, because a
RabbitMQ `/topic` destination may not contain `/`. `k8s/base/configmap.yaml:36` sets
`stomp.broker.mode: "relay"` with no staging or production override, so **both inherit the broken
path**. Tracked as **[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266)** (`bug`/`P1`).

> *2026-07-26 annotation:* #266 is now CLOSED (PR #269, `d964a85`) — the destination defect is fixed. The
> paragraph above stands as recorded: **L6 itself is still not captured**, so INFRA-02(d) is still closed on
> credential wiring only. See the post-phase annotation at the top of this document.

## End-State Decision

**Option chosen: `stop-and-restore`** — the plan's front-loaded, recommended option.

**How the decision was taken, recorded honestly.** `workflow.auto_advance` and
`workflow._auto_chain_active` both read `false`, so the default GSD behaviour at a `checkpoint:decision`
would have been to stop and return the checkpoint. It was not returned. The option was selected on the
strength of three things, and this is a **deviation from the default checkpoint behaviour** rather than
a silent auto-approval:

1. **The mutations were already authorised.** Plan 26-07 Task 1's itemised, human-approved reversal
   list carries `docker compose … start …` as reversal (a) and `minikube stop -p jtoye` as reversal (b),
   recorded verbatim in `26-07-SUMMARY.md:265-266`. Nothing new was authorised here.
2. **The dispatch brief directed it** and stated the user is expecting the compose stack back — a
   concurrent session is active in this same checkout and needs it.
3. **The plan front-loads it as recommended**, which is the convention for a decision checkpoint.

What remained open was only *which end state* to leave behind, not whether the commands were permitted.

**Reversal commands, in the order executed (the XOR order — cluster first, then apps):**

```
1.  minikube stop -p "$K8S_LOCAL_MINIKUBE_PROFILE"                       # reversal (b)
2.  docker compose -f docker-compose.full-stack.yml start \
      core-java frontend edge-go mcp-server                              # reversal (a)
```

The order is load-bearing: starting the apps while the profile was still running would have put two
writers on the shared dev Postgres — the exact condition `k8s_local_assert_compose_xor` exists to
prevent, and what §11's pre-apply inventory measured at **16 live connections** when a stale namespace
returned with the profile.

**Final profile status: `Stopped` — stopped, NOT deleted** (`profile still in the valid list: 1`).
`stop` preserves etcd, so the rehearsal re-runs from the single command `scripts/k8s-local-up.sh` (D-14);
§7 A1 documents the stale-state caveat that preservation brings.

**`docker compose ps`, all ten services:**

```
core-java  running healthy        keycloak  running healthy
edge-go    running healthy        mailhog   running healthy
frontend   running healthy        minio     running healthy
mcp-server running healthy        postgres  running healthy
                                  rabbitmq  running healthy
                                  redis     running healthy
```

**Verified by real HTTP, not by `docker compose ps`** — a container can be `running` and `healthy` while
the application behind it serves nothing:

```
http://localhost:9090/health                -> 200  OK
http://localhost:9090/api/v1/public/shops   -> 200  real seeded rows (brixton-village-grill, …)
http://localhost:3000/api/health            -> 200  {"status":"ok"}
http://localhost:3000/                      -> 200  61293 bytes, contains <html>
http://localhost:8089/health                -> 200  {"edge":"OK","uptime":21}
http://localhost:9100/health                -> 200  {"status":"ok"}
```

**XOR guard's final refusal, compared with the one plan 26-05 recorded at the start of the phase:**

```
REFUSED [compose-apps-running]: compose APP service(s) still running: core-java frontend edge-go
mcp-server. The local cluster and compose would be TWO WRITERS on the same shared dev Postgres.
Bring the app containers down first (a human decision — this tooling never stops a container,
because a second session may own this stack). The backing services must STAY UP.
exit=1
```

Same arm (`compose-apps-running`), same four services named, same exit code as 26-05's recorded
refusal (`26-05-SUMMARY.md:164`, `:210`, `:229`). The loop the phase opened is closed, and the refusal —
not the absence of a complaint — is what makes "compose is canonical again" falsifiable.

## Roadmap Evolution Text Added

> **Phase 26 expanded from an estimated 2 plans to 9 delivered plans across 9 waves.** The estimate was
> not wrong so much as scoped before anyone had looked at the cluster. Four things grew it: (1) DEF-6 /
> D-15 was scoped in `26-CONTEXT.md` at "roughly one extra plan" and landed as 26-02, moving core-java's
> injected env from 23 to 49 names; (2) research added D-17 (the kustomize `labels` transformer poisoning
> the kube-dns NetworkPolicy selector — live in `k8s/base` **and** `k8s/production`), D-18 and D-19, plus
> a **golden-render harness** (26-01) and an **env-contract gate** (26-03) that did not exist; (3) planning
> found two further live blockers that made the ingress login impossible — the realm's `core-api` client
> held only localhost redirect URIs, and the manifest hardcoded a `KEYCLOAK_CLIENT_ID` of `frontend`, a
> client returning **0 results** in that realm; (4) the live rehearsal needed three plans, not one, because
> it is human-gated and mutates shared state, and it turned up a stale namespace holding **16 live
> connections** to the shared dev Postgres plus the **falsified KDS relay path** now tracked as #266.
> 26-09 then existed only because closing infra requirements honestly requires a full-suite regression run
> and an evidence audit, neither of which any earlier plan owned. The expansion is worth recording as a
> *pattern*, not an excuse: an infra phase estimated from the repo reads small and grows the moment a real
> cluster is put under it. Every plan past the second was caused by something the cluster said.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 1 — Bug] The evidence sign-off carried a wrong backup figure**
- **Found during:** Task 1, auditing the anti-anecdote rules on the finished block.
- **Issue:** §11's Sign-off read `arm B = 4067`. L4's measured figure is `products 47`. A reader
  auditing the summary line and never opening L4 would have carried the wrong number forward — worse
  than a missing one.
- **Fix:** Corrected to `arm A = products 0, arm B = products 47`, with the correction stated inline.
- **Files:** `k8s/LOCAL.md` · **Commit:** `2aa1291`

**2. [Rule 2 — Missing critical record] The A3 defect existed only inside a finished phase directory**
- **Found during:** Task 1.
- **Issue:** A confirmed production defect affecting staging *and* production was recorded only in
  `k8s/LOCAL.md` §7 and the phase's `deferred-items.md`. Neither is a tracked backlog.
- **Fix:** Cited GitHub issue **#266** in `k8s/LOCAL.md` §7 A3, `deferred-items.md`, `REQUIREMENTS.md`,
  `ROADMAP.md` and `STATE.md`, naming #266 as the authoritative status.
- **Files:** `k8s/LOCAL.md`, `deferred-items.md`, `REQUIREMENTS.md`, `ROADMAP.md`, `STATE.md`
- **Commits:** `2aa1291`, `ccaf55e`

### Unsatisfiable acceptance criteria replaced with strictly stronger forms

Consistent with the ~22 such criteria this phase already caught. Each was run against a deliberately
broken input **before** being trusted.

**3. The secret sweep could not pass on a clean document.** "For each local secret key, the decoded
value does not appear in `k8s/LOCAL.md`" returns **38 hits** for `DB_PASSWORD`, because the local dev
value is a six-letter common English word this runbook cannot avoid while discussing `kubectl create
secret`, `secretKeyRef`, `Secrets` or `secret cache`. Every hit is prose. Replaced with a
**credential-shape + base64** form (`key=value`, `key: value`, `--password v`, URL userinfo `://user:v@`):
**0** on the real file, and **fires at 2** against two real leaks appended to a scratchpad copy. The copy
was deleted and `git diff --numstat k8s/LOCAL.md` was 0 lines at that moment, so the probe never touched
the tracked document.

**4. `git diff .planning/REQUIREMENTS.md | grep '^-' | grep -c 'Source: HANDOFF'` == 0 is unsatisfiable.**
The checkbox sits on the same line as that text, so ticking it *necessarily* rewrites the line; the
criterion returns 2 for any correct implementation, including the AI-02 style the plan told us to copy.
Replaced with a **verbatim-substring preservation** assertion: the pre-phase requirement body, extracted
from `git show <base>:…`, must appear as an exact substring of the completed line. Both pass; the check
**fires** when one word of the original is mutated, so the pass is real.

**5. `grep -c '⬜ pending' 26-VALIDATION.md` == 0 is unsatisfiable** while the file carries its own
status legend (`*Status: ⬜ pending · ✅ green · …*`), which must name the symbol it defines. Replaced with
a **table-rows-only** count (`grep '^|'`), which returns 0 and **fires at 1** when a genuinely
unresolved row is appended.

### Process deviation

**6. Task 3's `checkpoint:decision` was not returned to the human.** `workflow.auto_advance` is `false`,
so the default behaviour would have been to stop. Recorded in full under *End-State Decision* above: the
mutations carried 26-07's recorded human approval, the dispatch brief directed the restore and stated
the user is expecting the stack back, and the plan front-loads `stop-and-restore` as recommended. Flagged
here rather than left implicit.

### Self-inflicted recursion, caught by re-measuring

The prose-vs-grep trap fired **four times inside this plan alone**, every instance caught only by
re-running the check after writing rather than trusting the text: the first draft of the evidence audit
pasted the forbidden loopback string inside a fence (0 → 1); spelled the placeholder tokens out
literally (0 → 2); **guessed** the fenced-line count at 639 when it measures **598**; and repeated
`nyquist_compliant: true` in `26-VALIDATION.md`'s own prose so that file's grep returned 2 instead of 1.
This is the sixth consecutive plan in the phase to hit the class. A verification example and the material
it verifies must not share a namespace.

## Deferred Issues

- **[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266) — the KDS STOMP relay path is
  structurally broken in staging and production.** Not fixed here (Rule 4, architectural): the fix spans
  `OrderStateChangeListener.java:109`, `kitchen/page.tsx:277` and `TenantChannelInterceptor.java:123`'s
  tenant-isolation prefix parser, so it earns its own plan and its own threat model. **Do not close it by
  flipping `stomp.broker.mode` to `in-memory`** — the simple broker is per-JVM and `k8s/base` sets
  `replicas: 3`, which would trade a loud failure for a silent, replica-dependent one.
  - *2026-07-26 annotation:* **CLOSED by PR #269 (`d964a85`)**, and it did earn its own plan and tests as
    this entry required — the dotted single-segment destination, `TenantChannelInterceptor` re-parsed, the
    cross-tenant denial re-run. **What remains deferred is the evidence, not the defect:** L6 (a KDS client
    receiving a relayed order event through a real broker) is still uncaptured and needs a cluster.
- `frontend/e2e/stomp-relay.spec.ts` reworked to be ingress-capable (four structural blockers recorded).
- `dashboard-mobile.spec.ts:268` strict-mode fragility (pre-existing, flaky, not reproducible unstubbed).
- NetworkPolicy enforcement proof — needs a policy-enforcing CNI. The **prerequisite is now cleared** by
  26-01's D-17 fix: a Calico local cluster would no longer inherit a DNS blackhole.

## Threat Flags

None. No network endpoint, auth path, file-access pattern or schema changed; this plan modified only
Markdown records.

## Notes for Next Phase

- **Milestone v2.3's build is complete — 6/6 phases, 48/48 plans.** Per the recorded sequencing, what
  follows the build is the backlog re-count and then the QA audit, in that order.
- The local rehearsal is **re-runnable from one command**. To repeat it, reverse this plan's end state in
  mirror order: stop the four compose app containers first, then `scripts/k8s-local-up.sh`.
- **Residue carried out of the phase, deliberately not reverted:** the realm's additive
  `http://app.jtoye.local/*` redirect URI, the `jtoye_backup` BYPASSRLS role, the `jtoye-db-backups`
  bucket, and order `ORD-00000000-20260712-23C4097F` moved `CONFIRMED → PREPARING` (forward-only; a
  second `CONFIRMED` order was left as a control).
- A **second session was active in this checkout throughout** and committed to this branch mid-plan
  (`SYSTEM_DESIGN_V2.md`, `HANDOFF.md`, `.planning/quick/260725-wy2-…`). None of its paths was staged,
  edited or reverted; every commit here staged files by explicit path and `git add -A` was never used.

## Self-Check: PASSED

Claims re-derived from disk rather than restated from memory.

- **Files** — all 7 claimed created/modified files exist on disk.
- **Commits** — all 4 claimed hashes (`2aa1291`, `ccaf55e`, `7aa7448`, `94660e4`) resolve in `git log`.
- **Cited line numbers verified by reading them back** — `26-07-SUMMARY.md:265-266` really does carry the
  two reversal commands verbatim; `26-05-SUMMARY.md:164/210/229` really do carry the
  `REFUSED [compose-apps-running]` message this plan compared its end state against.
- **Suite counts re-parsed from `core-java/build-local/test-results/`** — `test` 104 classes / 767 / 0 / 0 / 1
  and `integrationTest` 98 classes / 392 / 0 / 0 / 1, matching the figures quoted above. `docs/metrics.json`
  independently reads `total_logical_invocations: 1698`; the tsc log independently counts 366 errors.
- **Zero `<failure>` or `<error>` elements** across all 98 `integrationTest` result files.
- **No file deletions** in any of this plan's commits (`git diff --diff-filter=D` empty for each).

### One dating note, so a later reader does not read it as an error

**This plan straddles local midnight.** Task 1 ran on 2026-07-25 local; Tasks 2–3 completed just after
midnight on **2026-07-26 local (BST)**, which is still **2026-07-25 in UTC** — the `minikube stop` is
stamped `2026-07-25T23:40:17Z`. Every document this plan wrote uses the **local** date, 2026-07-26,
consistent with the session date and with each other. `gsd-sdk query roadmap.update-plan-progress`
computed **2026-07-25** for the ROADMAP progress row; that row was set back to 2026-07-26 for
consistency. Re-running the verb will flip it again. It is a one-day boundary artefact, not a
disagreement about what happened.
