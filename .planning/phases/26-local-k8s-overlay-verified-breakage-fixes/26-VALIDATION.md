---
phase: 26
slug: local-k8s-overlay-verified-breakage-fixes
status: complete
nyquist_compliant: true
wave_0_complete: true
created: 2026-07-25
resolved: 2026-07-26
---

# Phase 26 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Derived from `26-RESEARCH.md` § Validation Architecture (lines 1101-1182).

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | bash gates under `k8s/scripts/` (CI job `k8s-validate`, `.github/workflows/ci-cd.yaml:191-211`) + JUnit 5/Testcontainers (`./gradlew :core-java:test`) + Playwright 1.59.1 (`frontend/playwright.config.ts`, baseURL from `PLAYWRIGHT_BASE_URL`) |
| **Config file** | none for the bash gates — paths resolve from `$BASH_SOURCE`; exit codes 0 = clean, 1 = violation, 2 = tooling failure |
| **Quick run command** | `bash k8s/scripts/check-no-plaintext-secrets.sh && bash k8s/scripts/check-connection-math.sh && bash k8s/scripts/check-env-contract.sh` (~2s, no cluster) |
| **Full suite command** | the three gates + `kubectl kustomize k8s/local` + `kubectl --context jtoye apply -k k8s/local --dry-run=server` + the live rehearsal checklist below |
| **Estimated runtime** | ~2s static; ~15-25 min live rehearsal (cluster start + image load + rollout + probes) |

**Definition of "validated" for an infra phase:** every claim is either (a) a deterministic assertion
over committed text that CI re-runs on every PR, or (b) a live observation captured as **named,
falsifiable evidence** — the command, its expected output, and the actual output recorded in the phase
evidence block. Nothing is marked complete on "it worked once".

---

## Sampling Rate

- **After every task commit:** the three bash gates (~2s, no cluster). Any `k8s/base` edit
  additionally re-runs the staging/production golden-render diff.
- **After every plan wave:** all static gates + `kubectl kustomize k8s/local` + `--dry-run=server`
  when a cluster is up + `./gradlew :core-java:test` whenever `application.yml` changed (it does, for D-05).
- **Before `/gsd:verify-work`:** full static suite green **plus** the live rehearsal evidence block —
  every "live" row below with its actual captured output.
- **Max feedback latency:** ~2s static / one rehearsal cycle for live.

---

## Per-Task Verification Map

Task IDs are assigned by the planner; rows below are the requirement-level contract each task must
inherit. `Exists?` reflects state at planning time (2026-07-25).

| Req | Behaviour | Type | Automated command | Exists? | Status |
|-----|-----------|------|-------------------|---------|--------|
| INFRA-01 | `k8s/local` builds | static | `kubectl kustomize k8s/local >/dev/null` | ✅ auto-covered (guard discovers overlays at `maxdepth 2`) | ✅ **green** — `check-no-plaintext-secrets.sh` exit 0: `[k8s/local]: build succeeded, 23 resources` |
| INFRA-01 | no `kind: Secret`, no `REPLACE_WITH` in the local build | static | `bash k8s/scripts/check-no-plaintext-secrets.sh` | ✅ exists, green | ✅ **green** — exit 0, `0 plaintext Secrets` on all 3 targets; LOC-6 additionally asserts no kustomize secret generation and no placeholder literal |
| INFRA-01 | overlay shims all four endpoints to `host.minikube.internal` | static | `kubectl kustomize k8s/local \| grep -c 'host.minikube.internal'` ≥ 4 | ❌ W0 | ✅ **green** — delivered as **LOC-1** in `check-render-invariants.sh` (26-03/26-04), exit 0; the overlay shims **eight** endpoints, not four |
| INFRA-01 | `replicas: 1` ×3, `minReplicas: 1` ×3, `minAvailable: 1` ×3, `maxReplicas` unchanged | static | rendered-scale assertion vs expected fixture | ❌ W0 | ✅ **green** — **LOC-2** (the D-09 scale triple, `maxReplicas` byte-identical to base at 10/20/10), exit 0; corroborated live in `k8s/LOCAL.md` §11 L1b |
| INFRA-01 | backup CronJob targets host MinIO | static | `kubectl kustomize k8s/local \| grep 's3.backup.endpoint: http://host.minikube.internal:9000'` | ❌ W0 | ✅ **green** — **LOC-3**, exit 0; corroborated live by the job log in §11 L3 uploading through that endpoint |
| INFRA-01 | every ref resolves, no dangling secret/configmap/label ref | **live** | pre-create ns, then `kubectl --context jtoye apply -k k8s/local --dry-run=server` | ❌ live | ✅ **green (live)** — §11 **L1**, exit 0, **verbatim**, 23 objects, 0 `denied the request` across 8 run logs. Verbatim capture earned its keep: two earlier attempts failed with the webhook *unreachable*, a state indistinguishable from a pass by exit code |
| INFRA-02a | no hardcoded `5432` in the core-java env block | static | `! grep -nE '^\s+value: "5432"' k8s/base/core-java-deployment.yaml` | ❌ W0 | ✅ **green** — **INV-1** in `check-render-invariants.sh`, asserted on the render across 4 targets, exit 0 |
| INFRA-02a | `DB_PORT` has `valueFrom` and **no** `value` (guards the both-fields trap permanently) | static | rendered EnvVar has exactly one of the two | ❌ W0 | ✅ **green** — **INV-2**, exit 0; live confirmation in §11 **L2b** (`secretKeyRef present : 1`, `"value" field present : 0`, decoded port 5433) |
| INFRA-02b | docs + template specify the NOSUPERUSER role | static | `! grep -n 'from-literal=username=jtoye$' k8s/QUICK_START.md` and same for `secrets-template.yaml.example` | ❌ W0 | ✅ **green** — **INV-5** (DEF-2 docs assertion), exit 0 |
| INFRA-02b | core boots as a non-superuser | **live** | `kubectl logs deploy/core-java \| grep -c "is NOT a superuser"` ≥ 1 **and** the DB-side truth `SELECT current_user, usesuper` under the pod's connection identity | ❌ live | ✅ **green (live, both arms)** — §11 **L2**: validator counts 1 / 1 / 0 with `Database username: jtoye_app`; DB side `rolsuper, rolbypassrls` = `f\|f`; attribution 16→0→5 connections + compose apps `exited` + backend_start/pod-start correlation |
| INFRA-02c | CronJob run exits 0 and uploads | **live** | `kubectl create job --from=cronjob/pg-backup …`; `.status.succeeded == 1` | ❌ live | ✅ **green (live)** — §11 **L3**: `condition met`, `.status.succeeded = 1`, object 214370 bytes in the bucket, unauth GET 403 with an images-object 200 control, key confirmed present *before* the 403 was interpreted |
| INFRA-02c | the dump is **NON-EMPTY** (not merely >1000 bytes) | **live, falsifiable** | download object, `pg_restore` into a scratch DB, `SELECT count(*) FROM products` > 0 (`docs/runbooks/backups.md:245-249`) | ❌ live | ✅ **green (live, two-arm)** — §11 **L4**: arm A (app role) = **products 0** while still clearing `MIN_BACKUP_BYTES` by 149× and passing `pg_restore --list`; arm B (BYPASSRLS role) = **products 47**, exact match to the live DB. Arm A is what makes arm B mean anything |
| INFRA-02d | STOMP creds reach Spring config | static | `check-env-contract.sh` direction (a): 0 injected-but-unread beyond the reasoned allowlist | ❌ W0 | ✅ **green** — `check-env-contract.sh` exit 0: 49 injected names all read, 0 violations, 1 reasoned exemption |
| INFRA-02d | no boot-time guest rejection | **live** | `kubectl logs deploy/core-java \| grep -c "Access refused for user"` == 0 | ❌ live | ✅ **green (live)** — §11 **L5**: count **0**, plus `AuthenticationFailureException\|ACCESS_REFUSED` = 0. Boundary stated honestly in the row itself: this proves no *rejection*, not that a message traversed the relay |
| INFRA-02d | a KDS client actually receives a relayed event | **live** | `RELAY_E2E=true PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/stomp-relay.spec.ts` | ⚠ spec exists, needs cookie-domain parameterisation | ❌ **RED — FALSIFIED, and PROVEN SUBSTITUTED (see the note below)**. §11 **L6**: 14 SUBSCRIBE / 14 `Invalid destination` / **0 MESSAGE**. A RabbitMQ `/topic` destination may not contain `/`; `k8s/base/configmap.yaml:36` sets `relay`, so staging + production inherit it. Confirmed production defect → **[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266)**, `k8s/LOCAL.md` §7 A3 |
| DEF-5 | a real vendor login through the ingress reaches a dashboard | **live** | `PLAYWRIGHT_BASE_URL=http://app.jtoye.local npx playwright test e2e/dashboard-mobile.spec.ts` (`.env` `KC_SEED_USER_PASSWORD`, user `admin-user`) | ⚠ spec exists (13/13 in Phase 23), needs a locally-built frontend image | ✅ **green (live, human-confirmed)** — §11 **L7**: authorize redirect to the PUBLIC issuer with `client_id=core-api`, landing on `http://app.jtoye.local/dashboard` in **591 ms**, 10/10 API calls to `api.jtoye.local`, **0** loopback app requests, real seeded data. Human independently confirmed login at 26-08's gate |
| DEF-6 | the silent-localhost-default class cannot recur | static | `check-env-contract.sh` direction (b): every unsupplied name is manifest-supplied or allowlisted **with a reason string** | ❌ W0 | ✅ **green** — exit 0: 117 placeholders, **0** local-only-default violations, 3 reasoned exemptions |
| Regression | connection math still holds | static | `bash k8s/scripts/check-connection-math.sh` | ✅ exists, green (133 ≤ 157) | ✅ **green** — exit 0, still 133 ≤ 157 with ≥20% headroom; the pool-size drift guard and the HPA-memory guard both pass |
| Regression | staging + production renders unchanged | static | golden-render diff of `kubectl kustomize k8s/staging` / `k8s/production` before vs after base edits | ❌ W0 — **the Incremental Betterment proof** | ✅ **green** — `render-golden.sh` exit 0, both overlays match their committed goldens at 1469 lines each; the harness also carries `--snapshot`/`--diff-since` and fails **closed** on a missing baseline |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

**All 19 rows resolved (2026-07-26, plan 26-09): 18 green, 1 red.** The red one is recorded as red on
purpose. A falsified row is a stronger result than an unproven one — it is the outcome D-06 was written
to obtain — and rewriting it green, or quietly downgrading it to "deferred", would make every other row
in this table worth less.

**Substituted verification — recorded, not silently ticked (threat T-26-56).** `26-CONTEXT.md` expected
the INFRA-02d relay row to be covered by `frontend/e2e/stomp-relay.spec.ts`. It was **not**. That spec
was proven structurally incapable of being the ingress-path proof, against the committed file, for four
independent reasons: (1) it authenticates with a fabricated cookie (`authjs.session-token: "e2e-stub"`
at `:61-63`, `:149-151`) while `frontend/app/dashboard/layout.tsx:19` gates server-side on `await auth()`,
so it lands on `/auth/signin`; (2) it posts orders to **edge-go** (`:29`, loopback port 8089) and the local
overlay's Ingress has only two rules — `api.jtoye.local → core-java:9090` and `app.jtoye.local →
frontend:3000` — so there is no edge-go backend to reach; (3) it waits on `networkidle` (`:76`, `:167`)
on a page holding SSE and STOMP connections open, which never settles; (4) it **skips silently** on two
separate conditions (`:46` without `RELAY_E2E`, `:80-85` without `TEST_SHOP_ID`/`TEST_PRODUCT_ID`) — and
a skipped spec reported as green is the single most likely way this row gets ticked while proving
nothing. The cookie-domain parameterisation Wave 0 called for **was** delivered (`COOKIE_DOMAIN =
new URL(BASE).hostname`, `stomp-relay.spec.ts:37`), so reason (1) is not about the domain; it is about the
token being fabricated. Reworking the spec to be ingress-capable is a recorded deferred item.

The row was instead proven two other ways, and both are stronger than the spec would have been: at the
**broker** (identity — 1 STOMP 1.2 connection as `jtoye`, `guest` = 0, with a non-vacuity control and a
predicate-can-fire fixture) and through a **real browser session with a real Keycloak login** (function —
the frame census that falsified it). The browser route is what caught the defect: the kitchen board
*does* visibly update, because each rejected SUBSCRIBE triggers a redial whose `onReconnect` refetches —
24 `/api/v1/orders` requests in 30 s with 0 MESSAGE frames. The spec, had it run, would have reported on
appearance.

---

## Wave 0 Requirements

**All five delivered (2026-07-26).** Each names the file that satisfies it, so the tick is checkable.

- [x] `k8s/scripts/check-env-contract.sh` — two-direction env contract + the localhost-default rule
      with a reasoned allowlist (covers INFRA-02a/02d + DEF-6 recurrence; decisions D-07/D-08)
      → **`k8s/scripts/check-env-contract.sh`** (464 lines, plan 26-03), wired into the `k8s-validate`
      CI job; exit 0 on the final tree with 49 injected names read, 0 violations in either direction.
- [x] Rendered-manifest assertions for `k8s/local` — endpoint-shim count, the scale triple,
      backup endpoint, and `DB_PORT` exactly-one-of `value`/`valueFrom` (same script or a sibling)
      → **`k8s/scripts/check-render-invariants.sh`** (1011 lines, plans 26-03/26-04): INV-1..INV-6
      across all 4 targets plus LOC-1..LOC-6 on `k8s/local`; exit 0. Every assertion was demonstrated
      RED against a deliberate break as well as green on the tree.
- [x] Staging/production golden-render diff harness (committed golden files, or a CI step diffing
      against the pre-change render)
      → **`k8s/scripts/render-golden.sh`** (270 lines, plan 26-01) + committed baselines
      **`k8s/goldens/staging.yaml`** and **`k8s/goldens/production.yaml`** (1469 lines each); exit 0.
      Fails **closed** on a missing baseline, so a deleted golden cannot read as "no drift".
- [x] Playwright cookie-domain parameterisation so existing specs run against `app.jtoye.local`
      instead of `localhost`
      → **`frontend/e2e/stomp-relay.spec.ts:37`** `const COOKIE_DOMAIN = new URL(BASE).hostname`,
      consumed at `:63` and `:151` (plan 26-05). Delivered as specified. It did **not** make that spec
      usable as the relay proof — see the substitution note above; the blocker is the fabricated
      session token, not the cookie domain.
- [x] `k8s/LOCAL.md` rehearsal-evidence template — a fixed home for the live rows' captured output
      → **`k8s/LOCAL.md`** §11 (plan 26-06 authored the template; 26-07 filled L1–L5, 26-08 filled
      L6–L7, 26-09 audited it): 7 live rows required, 7 filled, plus 5 supplementary rows.

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions | Done? |
|----------|-------------|------------|-------------------|-------|
| Live cluster rehearsal (rollout READY, boot-log assertions, backup restore drill, ingress auth E2E) | INFRA-01, INFRA-02, DEF-5 | No cluster in CI; `compose XOR k8s` means the rehearsal requires stopping compose app containers first. GitHub runners cannot host minikube + the host backing services. | `scripts/k8s-local-up.sh` (single idempotent entry point), then run each **live** row above and paste actual output into the `k8s/LOCAL.md` evidence block. Record the four image digests alongside results so a stale-image pass cannot masquerade as green. | [x] **DONE 2026-07-25** (plans 26-07, 26-08, each behind its own human gate). Evidence: `k8s/LOCAL.md` §11, rows **L1–L7** + L1b/L1c/L1d/L2b/L2c, 598 lines of captured output. All four image identities are in the run header and **all four were built during the run** — the on-host `:2.1.0` tags date 2026-07-13/14 and predate Phases 23/24/25, so a stale-image pass was excluded rather than assumed. `PIT-4b` records that the host image ID and the in-cluster image ID differ for the *same* build (`minikube image load` re-imports and the node recomputes), so digests say which side they came from. |
| NetworkPolicy enforcement | INFRA-01 (D-11) | minikube's default CNI does not enforce NetworkPolicies; proving enforcement needs Calico (deferred). | N/A this phase — record as explicitly **not proven** locally in `k8s/LOCAL.md`. | [x] **RECORDED AS N/A — explicitly NOT PROVEN**, per instruction. `k8s/LOCAL.md` §6 states all 6 policies are applied and **inert** under minikube's default CNI, and the §11 sign-off repeats that L1–L5 prove *nothing* about NetworkPolicy enforcement, TLS/HSTS or the six nginx security headers. Recorded as a deferred item; **the Calico prerequisite is now CLEARED** — 26-01's D-17 fix means a Calico cluster would no longer inherit the kube-dns selector blackhole (§7 PIT-6). |

---

## Anti-Anecdote Rules (from RESEARCH.md § "Making the live proofs reproducible")

1. **One idempotent entry point** — if the rehearsal cannot be re-run from a stopped cluster by a
   single command, it is not reproducible.
2. **Pin evidence to code identity** — record image digests with the results.
3. **Assert negatives with counts, not eyeballs** — a missing log line and an absent grep hit look
   identical unless you assert the count.
4. **Falsify the backup, don't confirm it** — show a `jtoye_app` dump restoring to `products=0`
   *and* the `jtoye_backup` dump restoring to `products>0`.
5. **Prove the ingress path, not localhost** — a `localhost:9090` in the evidence means compose app
   containers were up and the XOR guard was bypassed.
6. **Capture dry-run output verbatim** — a dry-run that silently skipped an admission webhook and one
   that genuinely passed share the same exit code.

---

## Validation Sign-Off

- [x] All tasks have `<automated>` verify or a Wave 0 dependency — every plan 26-01..26-09 carries an
      `<automated>` block; the two human-gated live plans (26-07, 26-08) additionally carry automated
      pre- and post-conditions around their gates.
- [x] Sampling continuity: no 3 consecutive tasks without automated verify — the three fast bash gates
      run after **every** task commit (~2s), so the maximum gap between automated feedback is one task.
- [x] Wave 0 covers all MISSING references above — all five Wave 0 items delivered and named above;
      every row previously marked `❌ W0` is now green against a committed, CI-wired assertion.
- [x] No watch-mode flags — every command in this contract is single-shot; `npx jest --ci` and
      `./gradlew --no-daemon` were used deliberately.
- [x] Feedback latency < 5s for static gates — measured: the three quick gates complete in ~2s; the
      full five-gate set plus `docs-freshness.sh` completes well inside the budget.
- [x] Live rows each have recorded command + actual output — **7 live rows required, 7 filled**, each
      with `Command` / `Expected` / `Actual` and verbatim captured output in `k8s/LOCAL.md` §11.
      Audited by plan 26-09, not asserted: placeholder sweep 0 and application-loopback-inside-fences 0
      over 598 captured-output lines, **each falsified against a deliberate break first**.
- [x] Nyquist-compliance flag set in frontmatter — done 2026-07-26; the frontmatter's nyquist,
      wave-0 and status keys now read true / true / complete. *(This line deliberately does not repeat
      those key-value strings literally. Spelling them out here makes `grep -c` over this file return
      2 rather than 1, and the assertion is meant to be about the frontmatter — the fourth instance in
      this phase of a check that a document breaks by describing itself.)*

**Approval:** **SIGNED OFF 2026-07-26 (plan 26-09).** 19 rows resolved: **18 green, 1 red.** The red row
(INFRA-02d functional relay) is a *falsification*, recorded as red and tracked as
[#266](https://github.com/Bralabee/JToye_OaaS_2026/issues/266) — not smoothed into a pass, not quietly
reclassified. INFRA-01 and INFRA-02 are marked complete in `.planning/REQUIREMENTS.md` with per-sub-item
citations, and INFRA-02(d) is explicitly scoped to credential wiring rather than relay function.
