---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 02
subsystem: infra
tags: [kubernetes, networkpolicy, kustomize, cilium, traefik, cert-manager, render-gate, bash, awk]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-01: Cilium chosen as the network-policy engine at cluster creation, so the manifests must stay networking.k8s.io/v1 (this plan pins the dialect in INV-7)"
  - phase: 26-local-k8s-overlay
    provides: "the #271 replacements mechanism (db.port -> spec.egress.1.ports.0.port), RENDER_PATHS_AWK, the goldens and the k8s/local overlay"
provides:
  - "Base NetworkPolicies for the enforcing topology: core-java admits the controller namespace on 9090 and app=prometheus on 9091 (Blocker F); Postgres egress at index 1 is ipBlock 0.0.0.0/0 except RFC1918 on db.port (Blocker D); every platform peer is a same-namespace podSelector"
  - "NetworkPolicy acme-http01-solver-allow (k8s/base/networkpolicies/60-acme-http01-solver.yaml): solver pods accept 8089 from the controller namespace only, no egress"
  - "app-config ingress.controller-namespace (traefik in base/staging/production, ingress-nginx in k8s/local) and its kustomize replacement in k8s/base and k8s/local"
  - "INV-7 rewritten: NETPOL_RULES_EXPECTED (exact tuple multiset per policy per target), NETPOL_IPBLOCK_EXPECTED (one declared shape, 443 + db.port), dialect check, stale-row hygiene; --selftest-inv7 (five planted arms) wired into ci-cd.yaml"
  - "The pod-label contract (README + validate-networkpolicies.py) consumed by plans 29-10, 29-12, 29-22, 29-23, 29-24"
affects: [29-03, 29-10, 29-12, 29-14, 29-17, 29-22, 29-23, 29-24]

actuals:
  tokens: 31420
  tasks: 3
  commits: 4
plan_head_before: 7c687487af3189ebb1001ae2bfe8797f1f41e70b
plan_head_after: 9df744f8c4292297100cdd82a1c0a1f7c36234b6

tech-stack:
  added: []
  patterns:
    - "A per-target fact a policy needs (Postgres port, controller namespace) is ONE app-config key copied in by a kustomize replacement; an overlay that patches the key repeats the replacement"
    - "INV-7 asserts the whole allow-list as a sorted tuple multiset (sel, types, in|eg:<peer>:<port>) read from full key paths, never a forward scan"
    - "A gate selftest copies k8s/, plants one hole per arm, runs the COPY's own gate, and requires rc 1 plus the message naming the hole; a plant that changes nothing is caught=no"

key-files:
  created:
    - k8s/base/networkpolicies/60-acme-http01-solver.yaml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-02-t3-red-dropped-9091.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-02-t3-red-undeclared-policy.json
  modified:
    - k8s/base/networkpolicies/10-frontend.yaml
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/30-edge-go.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/networkpolicies/50-observability.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/base/kustomization.yaml
    - k8s/base/configmap.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/local/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/scripts/validate-networkpolicies.py
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - .github/workflows/ci-cd.yaml

key-decisions:
  - "INV-7 tuples include sel:<podSelector> and types:<policyTypes> as well as the plan's dir:peer:port, so a widened selector (default-deny narrowed to one app, an allow policy opened to every pod) fails too; non-TCP ports are written <n>/<PROTO> so DNS UDP 53 and TCP 53 stay distinct"
  - "An ipBlock's except-list is NOT part of its peer token; NETPOL_IPBLOCK_EXPECTED alone guards it, so the undeclared-ipblock arm proves that second arm is independently load-bearing"
  - "Missing declared policy in a target is FAIL rc 1 (a dropped default-deny is a violation, not a parse problem); an ambiguous row match is PARSE ERROR rc 2; a row or ipBlock shape no target uses fails as stale"
  - "Goldens are regenerated in each commit that changes the render (Task 1 policies, Task 2 configmap key), so every commit's golden gate is self-consistent"

patterns-established:
  - "Config-driven NetworkPolicy peers: any per-environment value a policy names comes from app-config through replacements, with indices commented as load-bearing"
  - "Planted-hole selftest for a render gate, runnable in CI with no cluster"

requirements-completed: [DPLY-05, DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Base allow-lists rewritten for the enforcing topology: Blocker F (controller ns -> core-java 9090, prometheus -> 9091), Blocker D (Postgres ipBlock on db.port at egress.1), in-namespace platform peers, no jtoye-infrastructure in any NetworkPolicy, ACME solver admitted"
    requirement: "DPLY-05"
    verification:
      - kind: other
        ref: "kubectl kustomize k8s/staging | core-java-allow block: block_lines=105 port_9091=1 (main: block_lines=74 port_9091=0, rc 1)"
        status: pass
      - kind: other
        ref: "NetworkPolicy-only extraction of the staging render: np_docs=7 infra=0 (main: np_docs=6 infra=5, rc 1)"
        status: pass
      - kind: other
        ref: "RENDER_PATHS_AWK over staging: core-java-allow and pg-backup-allow spec.egress.1 = one port 5432, cidr 0.0.0.0/0, except 10/8,172.16/12,192.168/16"
        status: pass
    human_judgment: false
  - id: D2
    description: "Controller namespace is one app-config value per target: staging/production render traefik x4, local ingress-nginx x4"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "Task 2 verify line: staging/production np_docs=7 traefik=4 ingress-nginx=0, local traefik=0 ingress-nginx=4, bad_targets=0 (main: bad_targets=3, rc 1)"
        status: pass
      - kind: other
        ref: "break arm: local replacement entry deleted -> local traefik=4 ingress-nginx=0; restored, sha 9e1d7ff1accb195b == committed, ingress-nginx=4"
        status: pass
    human_judgment: false
  - id: D3
    description: "INV-7 asserts the exact rule set per policy per target, refuses undeclared policies, undeclared ipBlock shapes and foreign policy kinds, and cannot pass blind"
    requirement: "DPLY-05"
    verification:
      - kind: unit
        ref: "bash k8s/scripts/check-render-invariants.sh --selftest-inv7 (5/5 caught=yes, CLEAN rc=0, rc 0; RED on the old INV-7: 0/5, CLEAN rc=2)"
        status: pass
      - kind: other
        ref: "quick static set (check-render-invariants, render-golden, check-env-contract, check-no-plaintext-secrets, check-connection-math): rc=0 fail_or_parse_lines=0 output_lines=83"
        status: pass
    human_judgment: false
  - id: D4
    description: "The #271 mechanism extended, not reopened: db.port still drives egress.1 in every target (local 5433); a retargeted replacement fails INV-7"
    requirement: "DPLY-02"
    verification:
      - kind: other
        ref: "extra arm db-replacement-retarget (egress.1 -> egress.2): rc 1, undeclared eg:pod=app=redis:5432, missing eg:pod=app=redis:6379"
        status: pass
    human_judgment: false
  - id: D5
    description: "Policies are correct under ENFORCEMENT on the live cluster (denied connection captured)"
    requirement: "DPLY-05"
    verification: []
    human_judgment: true
    rationale: "No cluster exists yet; this plan is the static half. The live two-arm denial capture (controller ns -> core-java:9090 and prometheus -> core-java:9091 included) is plan 29-17."

duration: 16min
completed: 2026-10-18
status: complete
---

# Phase 29 Plan 02: NetworkPolicies for an Enforcing Cluster Summary

**The base NetworkPolicies now admit the real login path (Traefik namespace to core-java 9090, Prometheus to 9091), reach the managed Postgres as a public peer on db.port, name the controller namespace from one app-config key, admit the cert-manager ACME solver, and INV-7 asserts the whole allow-list exactly, shown catching five planted holes.**

## Performance

- **Duration:** 16 min
- **Started:** 2026-10-18T21:18:36Z
- **Completed:** 2026-10-18T21:34:38Z
- **Tasks:** 3
- **Files modified:** 18 (15 code/config/docs + goldens, 2 evidence records, plus this SUMMARY)

## Accomplishments

- **Blocker F fixed in the render.** `core-java-allow` admits the controller namespace on 9090 (browsers call the API host and the SSE stream through it) and `app=prometheus` pods on 9091. The `monitoring` and `jtoye-infrastructure` namespace peers are gone: neither namespace exists, and the new scrape rule opens the management port to one labelled workload instead of the application port to two namespaces.
- **Blocker D fixed in the render.** The Postgres rule stays at `spec.egress.1` with exactly one port replaced from `db.port`, now to `ipBlock 0.0.0.0/0 except 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16` (the managed server's public endpoint, T-29-05 accepted). Redis, RabbitMQ (5672, 61613), Mailpit (1025), Alertmanager (9093) and Keycloak (8080) are same-namespace podSelectors.
- **D-29(c).** `ingress.controller-namespace` is `traefik` in base (staging and production inherit it) and `ingress-nginx` in `k8s/local`. A replacement writes it into the four controller namespaceSelectors, and `k8s/local` repeats it because it patches the key. The good this displaced (a local render that names the controller it actually runs) is kept.
- **ACME solver.** `acme-http01-solver-allow` admits TCP 8089 from the controller namespace only, with no egress. Without it, default-deny would fail every HTTP-01 order.
- **INV-7 rewritten.** `NETPOL_RULES_EXPECTED` holds the exact tuple multiset per policy per target. `NETPOL_IPBLOCK_EXPECTED` allows one shape, on 443 and `db.port`. Every `*NetworkPolicy` document must be `networking.k8s.io/v1` kind NetworkPolicy. Zero policies extracted is a PARSE ERROR. `--selftest-inv7` is wired into the CI `k8s-validate` job.
- **Pod-label contract** written into the README and into `validate-networkpolicies.py`.

## Task Commits

1. **Task 1: Rewrite the base allow-lists and add the ACME solver policy**: `5596c625` (feat)
2. **Task 2: Config-driven ingress-controller namespace**: `a2af7c41` (feat)
3. **Task 3: INV-7 rewrite (TDD)**
   - RED: `ccfc2932` (test). Selftest harness plus RED evidence.
   - GREEN: `9df744f8` (feat). New INV-7 plus the CI wiring.

**Plan metadata:** the docs commit that carries this SUMMARY.

## Evidence (both directions recorded)

**Task 1 verify 1** (`kubectl kustomize k8s/staging`): rc=0 after the change.

**Task 1 verify 2** (core-java-allow `port: 9091` count):
- Before, on main: `block_lines=74 port_9091=0`, rc 1.
- After: `block_lines=105 port_9091=1`, rc 0.

**Task 1 AC (`jtoye-infrastructure` in NetworkPolicy documents only):**
- Before, on main: `rc=0 np_docs=6 infra=5`, rc 1.
- After: `rc=0 np_docs=7 infra=0`, rc 0.

**Task 1 AC (egress.1 read with the INV-7 path walker):** for both `core-java-allow` and `pg-backup-allow` in staging, `spec::egress::[1]` carries exactly `ports::[0]::port 5432` and `to::[0]::ipBlock::cidr 0.0.0.0/0`, except `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`. Local renders `port: 5433`.

**Task 2 verify:**
- Before, on main: every target printed `np_docs=6 traefik=0 ingress-nginx=2`, `bad_targets=3`, rc 1.
- After: staging and production print `np_docs=7 traefik=4 ingress-nginx=0`, local prints `np_docs=7 traefik=0 ingress-nginx=4`, `bad_targets=0`, rc 0.

**Task 2 break arm** (committed first at `a2af7c41`, then clean, arm, clean):

| Step | sha | local render |
|------|-----|--------------|
| CLEAN-BEFORE | `9e1d7ff1accb195b` | traefik=0 ingress-nginx=4 |
| ARM: local replacement entry deleted | `dd088159078a2a47` | traefik=4 ingress-nginx=0 |
| CLEAN-AFTER | `9e1d7ff1accb195b` | traefik=0 ingress-nginx=4 |

The CLEAN-AFTER sha equals the committed file's sha, so the restore was verified by content.

**Task 2, base replacement load-bearing** (temp copy, base value planted as `zzz-planted-ns`): staging and production render `zzz=4 traefik=0`, local renders `ingress-nginx=4`.

**Task 3 RED** (`ccfc2932`, the old INV-7 on the rewritten tree):
- All five arms `caught=no`, and `CLEAN rc=2`. Every run hit `PARSE ERROR ... found no 'jtoye-infrastructure' egress ports`.
- The classifier returned `RED_EVIDENCE_OK` for both targets, `dropped-9091` and `undeclared-policy` (TAP, records in `evidence/29-02-t3-red-*.json`).
- **Semantic assessment:** each target arm executed: the plant was applied (checksum changed), the copy's gate ran, and it gave rc 2 instead of rc 1 with the named hole. The old INV-7 could only see egress toward one namespace, so it is blind on the new topology.
- **Supplementary RED on the main tree (`7c687487`)**, where the old INV-7 still works:
  - The clean tree gives rc 0.
  - Planting undeclared-policy, foreign-kind or undeclared-ipblock each still gives **rc 0 (missed)**.
  - Only an extra port on the infrastructure-namespace rule is caught (rc 1).
  - `core-java-allow` has 0 rules on 9091, so there was nothing to drop.
  - Together: the old gate misses the 9091 and undeclared-policy arms, which the acceptance criterion requires.

**Task 3 GREEN** (`9df744f8`): `ARM extra-port|dropped-9091|undeclared-policy|undeclared-ipblock|foreign-kind caught=yes`, `CLEAN rc=0`, rc 0. The verify line reports `missing=0 caught_no=0 clean_ok=1`.

**Extra break arms beyond the selftest** (temp copies, recorded once):

| Arm | rc | What failed |
|-----|----|-------------|
| quoted port `"6379"` | 1 | non-numeric port token, plus an undeclared tuple |
| db.port replacement retargeted to egress.2 | 1 | undeclared `eg:pod=app=redis:5432`, missing `eg:pod=app=redis:6379` |
| local controller-namespace replacement removed | 1 | INV-7 FAIL on k8s/local: `in:ns=traefik:*` vs `in:ns=ingress-nginx:*` |
| base `ingress.controller-namespace` key removed | 2 | kustomize itself fails, because the replacement source is missing |
| default-deny podSelector narrowed to `app=frontend` | 1 | `sel:app=frontend` undeclared, `sel:*` missing |
| a `ghost-allow@*` row for a non-existent policy | 1 | declared policy absent |
| port 22 added to a public 443 rule | 1 | undeclared tuple, plus ipBlock on undeclared port 22 |

**Quick static set** (check-render-invariants, render-golden, check-env-contract, check-no-plaintext-secrets, check-connection-math):
- At Task 1, before the rewrite, it was red: the old INV-7 gave rc 2, and render-golden gave rc 1 until `--write`.
- On the committed tree: `rc=0 fail_or_parse_lines=0 output_lines=83`.

**`validate-networkpolicies.py`** (conda env `aims-api`):
- After Task 1's new selectors and before the contract labels were added: rc 1 with 10 unknown pod-label references.
- After: `PASS: 7 files`, rc 0.
- A planted `app=mailpitx` gives rc 1.

**`check-gate-enforcement.sh`:** rc 0.

**Goldens:** `git show --stat 5596c625` lists `k8s/goldens/staging.yaml` and `k8s/goldens/production.yaml` beside the policy files. `a2af7c41` carries the one-line golden change for the new configmap key. Both were regenerated by `render-golden.sh --write`, never edited by hand.

## Files Created/Modified

- `k8s/base/networkpolicies/10-frontend.yaml`: controller ingress on 3000 (replaced); egress to DNS, core-java 9090, keycloak 8080 and public 443.
- `k8s/base/networkpolicies/20-core-java.yaml`: ingress from frontend/edge-go 9090, the controller ns 9090 and prometheus 9091; egress in the order DNS, Postgres ipBlock on db.port, redis, rabbitmq, mailpit, alertmanager, keycloak, 443.
- `k8s/base/networkpolicies/30-edge-go.yaml`: controller ns 8080 and prometheus 8080; egress to DNS, core-java, keycloak and 443.
- `k8s/base/networkpolicies/40-datastores.yaml`: pg-backup DNS, Postgres ipBlock on db.port and 443. The dead infrastructure-namespace mirror notes are replaced by the #271 other-side note.
- `k8s/base/networkpolicies/50-observability.yaml`: header comment only (the scrape is 9091, in-namespace Prometheus). The rendered placeholder is unchanged.
- `k8s/base/networkpolicies/60-acme-http01-solver.yaml`: new.
- `k8s/base/networkpolicies/README.md`: the flow matrix matches the render row for row; adds the pod-label contract, Traefik, the local ingress-nginx replacement, and an accurate CI section. The rollback section is kept.
- `k8s/base/kustomization.yaml`: the 60 resource, plus the controller-namespace replacement with its index comment.
- `k8s/base/configmap.yaml`: `ingress.controller-namespace: "traefik"`.
- `k8s/local/configmap-patch.yaml` and `k8s/local/kustomization.yaml`: `ingress-nginx` plus the repeated replacement. The header comments now describe the derived values.
- `k8s/scripts/check-render-invariants.sh`: the INV-7 header, tables, tuple awk, body, stale checks and `--selftest-inv7`.
- `k8s/scripts/validate-networkpolicies.py`: the contract labels in `KNOWN_EXTERNAL_LABELS`.
- `.github/workflows/ci-cd.yaml`: the `--selftest-inv7` step in `k8s-validate`.
- `k8s/goldens/{staging,production}.yaml`: regenerated.

## Decisions Made

See the `key-decisions` frontmatter. In short:
- **Tuples carry the selector and policyTypes.** This is stronger than dir:peer:port alone.
- **The except list is guarded by NETPOL_IPBLOCK_EXPECTED alone.**
- **Severities:**
  - A declared policy missing from a target: FAIL rc 1.
  - An ambiguous row match: PARSE ERROR rc 2.
  - An unused row or shape: fails as stale.
- **Goldens are regenerated in every commit that changes the render.**

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] The 60 resource entry was added in Task 1, not Task 2**
- **Found during:** Task 1.
- **Issue:** Task 1's acceptance criterion requires `np_docs=7`, which needs the new policy in `resources`. The plan placed that edit in Task 2.
- **Fix:** Added `networkpolicies/60-acme-http01-solver.yaml` to `k8s/base/kustomization.yaml` in the Task 1 commit.
- **Committed in:** `5596c625`.

**2. [Rule 3 - Blocking] Goldens regenerated in the Task 1 and Task 2 commits, not only in Task 3**
- **Found during:** Task 1.
- **Issue:** The policy change alters the render, so leaving the goldens for Task 3 would give two commits with a red golden gate. The plan also requires the goldens in "the commit that changes the policies".
- **Fix:** Ran `render-golden.sh --write` in each render-changing commit. The Task 3 commit changes no render.
- **Committed in:** `5596c625`, `a2af7c41`.

**3. [Rule 3 - Blocking] validate-networkpolicies.py taught the pod-label contract**
- **Found during:** Task 1.
- **Issue:** The repo's validator (not CI-wired) failed rc 1 on the ten new contract selectors, because those workloads come from later plans or from operators.
- **Fix:** Added them to `KNOWN_EXTERNAL_LABELS` with the contract comment. A planted unknown label still fails.
- **Committed in:** `5596c625`.

**4. [Rule 2 - Missing critical] The selftest is wired into CI**
- **Found during:** Task 3.
- **Issue:** A falsifiability arm that runs nowhere proves nothing after the commit that adds it.
- **Fix:** Added a `--selftest-inv7` step to the `ci-cd.yaml` `k8s-validate` job. `check-gate-enforcement.sh` still gives rc 0.
- **Committed in:** `9df744f8`.

**5. [Rule 2 - Missing critical] INV-7 tuples include `sel:` and `types:`, and non-TCP ports carry `/<PROTO>`**
- **Found during:** Task 3.
- **Issue:** The plan's `dir:peer:port` grammar cannot see a widened policy podSelector, and it collapses UDP 53 and TCP 53 into one tuple.
- **Fix:** Both added, a strict strengthening. The default-deny-narrowed extra arm shows the selector check failing.
- **Committed in:** `9df744f8`.

**6. [Doc accuracy] Comment-only corrections in files already touched or adjacent**
- **Issue:** The `50-observability.yaml` header and the `k8s/local/kustomization.yaml` header named the dead `jtoye-infrastructure` peer, the old port 9090 and "6 policies".
- **Fix:** Corrected the comments. No render change from the 50 edit.
- **Committed in:** `5596c625`, `a2af7c41`.

**7. [Doc accuracy] The README no longer claims validate-networkpolicies.py runs in CI**
- **Issue:** `.github/` has 0 references to it, and `PRODUCTION_READINESS_REPORT.md` already says it is not wired. The new README states that.
- **Committed in:** `5596c625`.

---

**Total deviations:** 7 auto-handled.
- 3 blocking.
- 2 missing-critical strengthenings.
- 2 doc corrections.

**Impact on plan:** None of them widens scope. Each one either makes a gate falsifiable, or keeps every commit's gates self-consistent.

## TDD Gate Compliance

Task 3 (`tdd="true"`): the RED commit `ccfc2932` (test(29-02)) precedes the GREEN commit `9df744f8` (feat(29-02)). RED was classified `RED_EVIDENCE_OK`, and the semantic assessment is recorded above. No REFACTOR commit was needed. Tasks 1 and 2 are configuration tasks and carry no `tdd` flag.

## Issues Encountered

- **The old INV-7 VOIDs on the new topology.** It exits rc 2 because it requires `jtoye-infrastructure` egress ports, and the topology removes them by design. So between `5596c625` and `9df744f8`, `check-render-invariants.sh` exits 2 on the tree. This is an intended, short window inside this plan, closed by the GREEN commit. Bisecting across those two commits will show it.

## Threat Flags

None beyond the plan's register. T-29-04 (core-java ingress) and T-29-06 (a widened rule) are mitigated by INV-7, as the planted arms show. T-29-05 (Postgres egress to 0.0.0.0/0 on db.port) is accepted for staging and recorded in the 20-core-java.yaml header and the README.

## User Setup Required

None.

## Next Phase Readiness

- **Component plans 29-10, 29-12, 29-22, 29-23 and 29-24** must label their pod templates exactly as the contract says (README, "The pod-label contract"). A new policy, or a changed rule, edits `NETPOL_RULES_EXPECTED` and the README matrix in the same commit.
- **Plan 29-23** replaces `observability-placeholder` and its INV-7 row.
- **Plan 29-17** owns the live denial capture: the controller namespace to core-java:9090 must time out before this change and connect after, and the same pair for Prometheus to 9091.
- **Plan 29-03** installs Traefik into the `traefik` namespace, which is the value base declares.

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-18*

## Self-Check: PASSED

- FOUND: k8s/base/networkpolicies/60-acme-http01-solver.yaml, both evidence/29-02-t3-red-*.json records
- FOUND (ancestors of HEAD): 5596c625, a2af7c41, ccfc2932, 9df744f8
- commits measured: git rev-list --count 7c687487..HEAD = 4 at SUMMARY write; plan_head_after read from git, not typed
