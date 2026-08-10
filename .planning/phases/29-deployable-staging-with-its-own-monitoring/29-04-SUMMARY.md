---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 04
subsystem: deploy-manifests
tags: [k8s, networkpolicy, egress, kustomize, cilium, azure, connection-budget, blocker-d]
requires:
  - k8s/base/networkpolicies (Phase 15 / INF-01)
  - the issue #271 replacements + INV-7 mechanism (Phase 26 / plan 26-01)
  - app-config redis.port / db.max-connections (plan 29-02)
  - PG_ACCESS_MODE + PG_SERVER_SKU decisions (plan 29-01, 29-OPERATOR-DECISIONS.md)
provides:
  - per-datastore egress rules for the out-of-cluster managed Postgres and Redis
  - redis.port and both egress CIDRs derived from config in base and all three overlays
  - INV-7 second arm — an exact <cidr>:<port> multiset over the whole ipBlock egress surface
  - except-within-cidr containment assertion (a failed apply becomes a CI failure)
  - a connection budget computed per target against that target's own declared server
affects:
  - plan 29-05 / 29-10 (provisioning + the /32 narrowing of db.egress-cidr, and the server-side firewall)
  - plan 29-14 (the two-arm agnhost enforcement proof, and k8s/base/networkpolicies/README.md)
  - Phase 32 (production's RESERVED must move 3 -> 15 in the same change that moves it to a managed server)
tech-stack:
  added: []
  patterns:
    - render-time declaration keys consumed by gates, not by containers (db.port precedent, extended to addresses)
    - one peer + one port per egress rule, appended at stable indices so no fieldPath retargets
    - a declared expectation map per addressing MODE, not per policy alone
    - fail-closed parse guards where "found zero" is exit 2, never a pass
key-files:
  created: []
  modified:
    - k8s/base/networkpolicies/20-core-java.yaml
    - k8s/base/networkpolicies/40-datastores.yaml
    - k8s/base/configmap.yaml
    - k8s/base/kustomization.yaml
    - k8s/staging/kustomization.yaml
    - k8s/production/kustomization.yaml
    - k8s/local/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/scripts/check-connection-math.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
decisions:
  - "The egress CIDR is a config-derived value (db.egress-cidr / redis.egress-cidr), not an authored literal — otherwise narrowing to the resolved IP would re-create #271's shape for the address half"
  - "No empty namespaceSelector {} / podSelector {} peer beside the ipBlock: the endpoints are genuinely out-of-cluster under PG_ACCESS_MODE=public-with-firewall, and an empty selector would permit egress to every pod in every namespace on the datastore port"
  - "The 0.0.0.0/0:443 rules are asserted 443-ONLY rather than widened — the shortcut Blocker D invites is forbidden by T-29-04-01 and is now a gate, not a review note"
  - "RESERVED is per target with its reason attached (15 Azure / 3 stock PostgreSQL); production stays 3 because it is still in-cluster, and that is written down as a Phase 32 obligation rather than pre-emptively changed"
  - "check-connection-math.sh now requires kubectl and VOIDs (exit 2) without it; CI already installs it for the sibling gates"
metrics:
  duration: ~75 min
  tasks: 3
  commits: 3
  completed: 2026-08-10
---

# Phase 29 Plan 04: Blocker D — Egress for the Managed Datastores Summary

Closed Blocker D: under an enforcing CNI the committed NetworkPolicies denied every
managed datastore D-09 moves out of the cluster, and both the invariant that guards
the egress allow-list and the gate that guards the connection budget were incapable
of noticing.

## What Shipped

**Three new egress rules, one peer and one port each.** `core-java-allow` gains
`spec.egress.4` (managed Postgres) and `spec.egress.5` (managed Redis);
`pg-backup-allow` gains `spec.egress.4` (managed Postgres, for the nightly dump).
All three are **appended**, so `spec.egress.1` is still the in-cluster Postgres rule
the existing replacement targets — kustomize preserves list order, and inserting
ahead of index 1 would have silently retargeted it.

**Both halves of each rule are derived, neither is authored.** The port comes from
app-config (`db.port`, `redis.port`); the address comes from two new render-time
declarations (`db.egress-cidr`, `redis.egress-cidr`) in the same shape and for the
same reason as `db.port`. `redis.port` being routed is the "clean follow-up" that
`20-core-java.yaml:110-115` named by hand — taken up now because Azure Cache Basic
serves TLS on 6380 and disables plaintext 6379, so the value varies per environment
for the first time.

| Target | egress.4 (Postgres) | egress.5 (Redis) | source |
|---|---|---|---|
| base | 5432 | 6379 | base app-config |
| staging | 5432 | **6380** | staging patches `redis.port` |
| production | 5432 | 6379 | inherits base |
| local | **5433** | 6379 | local patches `db.port` |

That table is read out of the render, not asserted about it — `k8s/goldens/staging.yaml`
now carries `port: 6380` and `k8s/goldens/production.yaml` `port: 6379` from the same
base file.

## The #271 Trap, Reproduced On Demand (Task 1 fail-direction arm)

Removed the `redis.port` replacement from `k8s/staging/kustomization.yaml` **only**
and re-rendered:

```
app-config: redis.port=6380
core-java-allow  egress.5  ports=[6379/TCP]  to=[ipBlock(0.0.0.0/0 …)]
```

Two places encoding one fact, and only one of them config — the exact failure shape
of #271, arriving through a different door. Under an enforcing CNI every cache
connection is denied while app-config says the right thing.

Restored, verified **by content**: `git hash-object k8s/staging/kustomization.yaml`
= `709a34e9156b80acae61e74a4d82f50479fa2352`, matching the pre-arm value. Re-rendered
as the clean-state-last assertion: `egress.5 ports=[6380/TCP]` returned.

Also asserted on the 443 rules: `port: 443` occurrences in the staging render are
**4 before and 4 after**, and the structural dump shows each 443 rule carrying only
443 — no 5432 or 6380 was smuggled in (T-29-04-01).

## INV-7 Was Blind, Measured Before It Was Fixed

The strongest datum in this plan. At commit `92541af5` — after the two new egress
holes existed — the **unmodified** INV-7 reported:

```
OK   [k8s/staging]: … | INV-7 OK (2 policy/policies, db.port=5432 honoured)
inv rc=0
```

Two brand-new `0.0.0.0/0` holes on 5432 and 6380, across all four targets, and the
egress invariant printed OK. It keys entirely on the `jtoye-infrastructure`
namespaceSelector and therefore cannot see an `ipBlock`-addressed rule at all. An
invariant that cannot see the new rules is worse than no invariant, because it reads
as coverage — T-29-04-03, and it was true on this tree rather than in principle.

The second arm declares the complete `<cidr>:<port>` multiset per policy with
`__DB_PORT__` / `__REDIS_PORT__` / `__DB_CIDR__` / `__REDIS_CIDR__` substituted from
the rendered app-config, and adds two properties the first arm never had: a policy
with an ipBlock rule and **no** declaration FAILS, and every `except:` entry must be
strictly **within** its rule's `cidr`.

Post-change, the message now reports numbers that move with the tree:

```
INV-7 OK (2 infra policy/policies db.port=5432; 4 ipBlock policy/policies
          redis.port=6380, 21 except entry/entries contained)
```

21 = core-java 3 rules + pg-backup 2 + frontend 1 + edge-go 1, times 3 excepts each.

## Connection Budget — Before And After, Beside Each Other

Recorded pre-change baseline (rc=0), byte-for-byte what the plan quoted:

```
Postgres budget: max_connections=200, reserved=3, app-usable=197, 80% budget line=157
k8s staging (HPA max+surge)  11 replicas x pool 12  + keycloak(20)+backup(1)+exporter(2) = 155  -> OK (<= 157)
```

Post-change (rc=0):

```
reserved=15 for k8s/staging — Azure … reserves 15 for replication/monitoring …
k8s/staging (HPA max+surge)  max_conn=429 reserved=15 usable=414 budget=331 | … = 155  -> OK (<= 331)
reserved=3 for k8s/production — still an in-cluster PostgreSQL …
k8s/production (HPA max+surge) max_conn=200 reserved=3  usable=197 budget=157 | … = 155  -> OK (<= 157)
compose dev (--scale 2)        max_conn=200 reserved=3  usable=197 budget=157 | … = 64   -> OK (<= 157)
```

Staging now budgets against its **own** declared server; production is unchanged at
157 because it correctly inherits the base 200 and is still in-cluster.

## Falsification — Every Arm Run, With A Control, Clean State Asserted Last

| Arm | Broken input | Result | Restore verified by content |
|---|---|---|---|
| Task 1 | `redis.port` replacement deleted from staging only | staging rendered **6379** while app-config said 6380 | `709a34e9…` ✓ |
| A | undeclared extra port 11211 on the new Redis rule | INV-7 **rc=1**, naming `0.0.0.0/0:11211` as unexpected | `cb5266d7…` ✓ |
| B1 (as planned) | `redis.port: "redis-tls"` in staging patch | **rc=2** — but see "Criterion Corrected" below | `ac449d18…` ✓ |
| B2 (reachable form) | `port: "443"` quoted in the 443 rule | INV-7 **rc=1**: *"A STRING port is a NAMED port: it renders, it applies, and it matches NO traffic"* | `cb5266d7…` ✓ |
| B3 (as planned) | `redis.egress-cidr` key renamed | **rc=2** — but kustomize fired first, see below | `91497ac5…` ✓ |
| B3b (reachable form) | key **and** its base replacement both removed | **my** guard: `PARSE ERROR: … found no app-config key 'redis.egress-cidr' … would pass vacuously` | `91497ac5…` + `bd8c667b…` ✓ |
| D (new assertion) | `db.egress-cidr` narrowed to `20.108.1.5/32`, excepts left | INV-7 **rc=1** on all three excepts, naming the API rejection | `91497ac5…` ✓ |
| C | staging `db.max-connections: "50"` (the B1ms figure) | conn **rc=1**: budget 28, needs 155 | `ac449d18…` ✓ |
| **C control** | the **same** broken tree, run against the **pre-change** gate | **rc=0**, `155 -> OK (<= 157)` | temp script removed, tree clean ✓ |

**The C control is the point.** Against an identical tree declaring a 50-connection
server, the old gate exits 0 and prints a green line; the new one exits 1. Without
that arm, "arm C failed" would only have shown that *some* gate can fail, not that
this change is what made it able to.

**CLEAN STATE ASSERTED LAST:** after every restore — `check-render-invariants.sh`
rc=0, `check-connection-math.sh` rc=0, `git status --short` empty.

## Criterion Corrected, Not Silently Substituted

Two of the plan's fail-direction criteria named a mechanism that **cannot be
reached**, and both were replaced with a strictly stronger reachable form rather
than reported as satisfied.

**Arm B** predicted that a non-numeric `redis.port` would reach my parse guard and
be named "a NAMED port that matches no traffic". It cannot: kustomize refuses to
write a non-integer into an int-typed field and aborts the render first —
`error: map[string]interface {}(nil): yaml: cannot decode !!str 'redis-tls' as a !!int`.
The outcome the criterion demanded (exit 2, never 0) holds, but it is **kustomize's**
type check firing, not mine, so it falsifies nothing about this change. Arm **B2**
is the reachable form: quoting a port that no replacement overwrites reaches the new
numeric assertion directly, and it fired with the intended message.

**Arm B3** predicted the same for a missing `redis.egress-cidr`. Also unreachable
while the replacement exists — kustomize errors on the missing replacement source.
Arm **B3b** is the reachable form, and it is the realistic scenario anyway (someone
tidies up a key they believe is unused, replacement and all): there my guard is the
thing that fires, at exit 2, naming the vacuity it prevents.

Net effect: the mechanism is **doubly** fail-closed, and the half I added has been
shown to fire on its own.

**Arm D is an addition, not a substitution.** It falsifies an assertion this plan
introduced (`except` ⊂ `cidr`) that no criterion asked for. It matters because the
containment check would otherwise be untested in the only direction that can fail:
on the clean tree the cidr is `0.0.0.0/0`, which contains everything, so a function
that always returned "contained" would look identical. Arm D shows it distinguishes.

## Goldens — Additive Only, Every Line Attributed

Snapshot `29-04-pre` taken **before Task 1**. `--diff-since` `resolve_exit=0`
(a 2 is VOID, not a pass), diff non-empty.

```
removed(<) = 0     added(>) = 64     1602 -> 1634 lines per target
```

| Added | Count/target | What |
|---|---|---|
| `db.egress-cidr: 0.0.0.0/0` | 1 | new render-time declaration |
| `redis.egress-cidr: 0.0.0.0/0` | 1 | new render-time declaration |
| core-java-allow egress.4 + .5 | 20 | managed Postgres + managed Redis |
| pg-backup-allow egress.4 | 10 | managed Postgres for the dump |

`kind: Secret` in added lines = **0**, and the counter was shown able to fire: the
same awk over the same lines plus one synthetic `> kind: Secret` returns 1.

## Gate Results — Every rc Recorded Individually

| Gate | rc | Note |
|---|---|---|
| `check-no-plaintext-secrets.sh` | 0 | k8s/staging: 23 resources, 0 plaintext |
| `check-render-invariants.sh` | 0 | INV-1..7 × 4 targets + LOC-1..6 |
| `render-golden.sh` (byte-compare) | 0 | both goldens match, 1634 lines each |
| `check-env-contract.sh` | 0 | core-java 66 injected / 150 read |
| `check-connection-math.sh` | 0 | 2 k8s targets from the render + compose at 200 |
| `check-dependency-horizons.sh` | 0 | every pin in-window or deferred |
| `check-no-create-extension.sh` | 0 | 61 migrations, 1 exempted occurrence |
| `check-gate-enforcement.sh` | 0 | 36 gates, 6 workflows, 6 declared exempt |
| `validate-networkpolicies.py` | 0 | 6 files, all pod-label refs resolve |

`render-golden.sh` re-run **after** the final commit: rc=0, tree clean.

## Deviations from Plan

### Auto-fixed / design decisions taken inside the plan's intent

**1. [Rule 2 — missing critical functionality] The egress CIDR is config-derived, not authored**

- **Found during:** Task 1
- **Issue:** the plan said to address the endpoints "by `ipBlock` on the resolved service IP range". No server exists yet — 29-05 provisions it — so the resolved IP cannot be authored today. Hardcoding `0.0.0.0/0` in the policy would have made the later narrowing a *manifest edit*, which is #271's shape for the address half of exactly the coupling this plan exists to remove.
- **Fix:** two new render-time declarations (`db.egress-cidr`, `redis.egress-cidr`) routed by `replacements:` into `spec.egress.N.to.0.ipBlock.cidr`, in base and all three overlays. Narrowing to `<ip>/32` in 29-10 is now a one-key ConfigMap change.
- **Commit:** `92541af5`

**2. [Rule 4-adjacent — recorded, not silently applied] No empty selectors beside the ipBlock**

- **Found during:** Task 1
- **Issue:** the plan cites Microsoft's documented Cilium workaround — add `namespaceSelector: {}` / `podSelector: {}` beside the `ipBlock` because `ipBlock` cannot select pod or node IPs. Applied to *these* rules it would permit egress to **every pod in every namespace** on 5432 and 6380, a far larger hole than the one being closed, and it contradicts this plan's own T-29-04-01.
- **Resolution:** not added. The plan itself conditions the workaround on "where a rule must also reach in-cluster peers", and `PG_ACCESS_MODE = public-with-firewall` (29-OPERATOR-DECISIONS.md §1) means both endpoints are genuinely outside the cluster. Written into the policy file as a decision with the condition under which it reverses (a move to VNet-injected private access, which would also require dropping the RFC1918 `except:` list).
- **Commit:** `92541af5`

**3. [Rule 2 — missing critical functionality] `except` ⊂ `cidr` containment is asserted**

- **Found during:** Task 1, while deciding whether to keep the RFC1918 `except:` list
- **Issue:** the Kubernetes API rejects an `except` entry that is not strictly within `cidr`, so the intended narrowing to a `/32` would have produced a **failed deploy** rather than a caught mistake — discovered at `kubectl apply`, mid-deploy.
- **Fix:** INV-7's ipBlock arm asserts containment on the render (IPv4 only; anything else exits 2 rather than being skipped). Falsified as arm D.
- **Commit:** `3b217f41`

**4. [Rule 1 — bug avoided in new code] Two exit-status traps in the gate I wrote**

- **Found during:** Task 2, before running anything
- **Issue:** `awk … | grep -q .` under the script's `set -o pipefail` inverts on match (SIGPIPE → 141), so that fail-closed guard would have fired on the CLEAN case and stayed silent on the broken one — failing **open**. Separately, reading `$?` after an `if cidr4_contains …` would have reported the wrong command's status.
- **Fix:** count into a variable (no pipe), and `|| _rc=$?` on the same statement. Both are commented in place with the reason.
- **Commit:** `3b217f41`

**5. [Scope, recorded] `check-connection-math.sh` now requires `kubectl`**

- Its header previously advertised "no cluster access, no kubectl". Reading the budget out of the render is what makes it read the right server, so kubectl became a hard requirement and a missing binary is **exit 2 (VOID), never a pass**. CI's `k8s-validate` job already installs kubectl for the sibling gates, so no workflow change was needed — verified in `.github/workflows/ci-cd.yaml`.

### Instrument note

`validate-networkpolicies.py` could not be run under bare `python3` on this host (a
machine-local guard blocks the base conda env). It was run in the project's
`jtoye-ops` env: **rc=0**. The plan already labels it a supporting check and not the
proof, and it parses the RAW files rather than the render.

## Known Gaps (recorded, not dropped)

- **`k8s/base/networkpolicies/README.md` is now incomplete, and was deliberately not edited.** Plan **29-14** owns that file (its `files_modified` lists it). Two claims are incomplete rather than wrong: the Files table row for `20-core-java.yaml` still describes egress as "infra namespace … + public 443" with no mention of the two managed-datastore rules, and §5 "The Postgres port is DERIVED" is now also true of `redis.port` and of both egress CIDRs. 29-14 should fold this in; it is not a pass.
- **`db.egress-cidr` / `redis.egress-cidr` default to `0.0.0.0/0`, narrowed to one port each.** This is the *cluster-side* half. The durable address-side control is the server's own firewall scoped to the AKS egress IP, which **29-10** owns (T-29-10-01). Narrowing the CIDR to a `/32` is available and is a one-key change — but it must drop the RFC1918 `except:` entries in the same change, which INV-7 arm (c) now enforces. An Azure Flexible Server's public IP is not a documented stable value, so a pinned `/32` is an operational tradeoff for 29-10 to make deliberately, not a default this plan should have taken.
- **`db.max-connections: "429"` for staging remains UNCONFIRMED against a provisioned server**, exactly as plan 29-02 flagged. This plan made the gate *read* it; it did not measure it. `SHOW max_connections;` on the real server is still owed — and it now matters more, because the number is load-bearing rather than inert.
- **Production's `RESERVED = 3` is correct today and will be wrong the moment production moves to a managed server.** Recorded in the script beside the value: it must become 15 in the same change as the Phase 32 cutover. Not pre-emptively changed, because a reserved count that does not describe the running server is the defect this plan removed.
- **`except` ⊂ `cidr` API rejection is a documented Kubernetes validation, not a measurement taken here.** No cluster was available (the only kube context on this host is the employer's `sipbihs2aks`, explicitly not to be touched), so `--dry-run=server` was not run. The gate makes the claim moot in the direction that matters — it fails first, in CI — but the underlying API behaviour is cited, not observed.
- **Nothing in this plan proves ENFORCEMENT.** Every assertion here is render-level. Whether the CNI actually denies what these rules omit is DPLY-05 and belongs to **29-14**'s two-arm agnhost run, with the denied arm run first against a non-enforcing baseline so a broken probe cannot read as a perfect posture.

## Known Stubs

None. Both new config keys are consumed by `replacements:` in four kustomizations
and asserted per target by INV-7; neither is an inert declaration.

## Threat Flags

None — no security surface was introduced that the plan's `<threat_model>` does not
already cover. All six dispositions were `mitigate` and all six were applied:

| ID | Applied as |
|---|---|
| T-29-04-01 | 443 rules asserted 443-only by INV-7's ipBlock multiset; the widening shortcut is refused in the policy comments and in the gate's failure text |
| T-29-04-02 | policy and declaration in the same commit (`3b217f41` follows `92541af5` in the same plan); arm A proves the friction fires |
| T-29-04-03 | new declared expectation for ipBlock peers; undeclared-policy rule; `pol_seen > 0 \|\| parse_fail` idiom copied throughout; arm B3b proves the parser is not blind |
| T-29-04-04 | `db.max-connections` is a parsed per-target input, `RESERVED` is per-target with its reason; arm C reds at 50 and the control shows the old gate did not |
| T-29-04-05 | empty selectors beside the ipBlock deliberately NOT added, with the reasoning and the reversal condition recorded in-file; the real proof remains 29-14's two-arm run |
| T-29-04-SC | no package installs in this plan |

## Commits

| Hash | Type | What |
|---|---|---|
| `92541af5` | feat | three per-datastore egress rules + two egress-CIDR declarations + replacements in base and all three overlays |
| `3b217f41` | feat | INV-7 ipBlock arm (exact multiset, undeclared-policy rule, except containment) + per-target connection budget |
| `9354ccd3` | chore | goldens regenerated against the pre-edit snapshot, 0 removed / 64 added |

## Self-Check: PASSED

Files verified present and carrying the declared content:

- `k8s/base/networkpolicies/20-core-java.yaml` — 8 × `ipBlock` (must_haves `contains`)
- `k8s/scripts/check-render-invariants.sh` — 4 × `NETPOL_INFRA_EXPECTED` (must_haves `contains`), plus `NETPOL_IPBLOCK_EXPECTED`
- `redis\.port` key_link present in all four kustomizations: base 4, staging 3, production 3, local 2 occurrences
- `k8s/goldens/staging.yaml` / `production.yaml` — 1634 lines each, `render-golden.sh` rc=0 against them

Commits verified in history: `92541af5`, `3b217f41`, `9354ccd3`. No commit deleted a
tracked file (`git diff --diff-filter=D` empty for all three). No untracked files left
behind (the temporary pre-change control script was removed and `git status --short`
is empty).

**Not updated by design (worktree mode):** `STATE.md` and `ROADMAP.md` are owned by
the orchestrator.

**Merge note for the orchestrator:** this plan touches `k8s/base/configmap.yaml` and
all four `kustomization.yaml` files, which wave-1's 29-02 also touched (already
merged into this base at `8519345e`, and re-read before editing). The likeliest
cross-branch conflict in this wave is another plan adding app-config keys to
`k8s/base/configmap.yaml` or editing `k8s/goldens/*`. Resolve goldens by re-running
`k8s/scripts/render-golden.sh --write` after the merge — never by hand-editing a
golden — and then re-run `check-render-invariants.sh` and `check-connection-math.sh`,
both of which read the merged render rather than any committed artifact.
