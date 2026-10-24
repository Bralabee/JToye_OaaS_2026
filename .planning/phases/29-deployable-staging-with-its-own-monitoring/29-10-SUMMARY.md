---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 10
subsystem: infra
tags: [kubernetes, kustomize, components, keycloak, redis, rabbitmq, cluster-operator, networkpolicy, ingress, traefik, copy-parity, dependency-horizons, render-gate, bash, tdd]

requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-02: INV-7 exact per-policy rule table, the pod-label contract (app: keycloak, app: redis, app.kubernetes.io/name: rabbitmq), same-namespace platform peers in the base allow-lists"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-03: Traefik controller namespace traefik, supported-annotations.txt, ClusterIssuer letsencrypt-prod, the RabbitMQ cluster operator v2.23.1 bootstrap stage, K8S_GLOB_DIRS"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-04: staging-realm.jq + staging-realm-import.sh (realm import over a port-forward, readiness on :9000/health/ready)"
  - phase: 29-deployable-staging-with-its-own-monitoring
    provides: "29-08: internal issuer keycloak.jtoye-staging.svc:8080 + public iss, in-namespace redis/rabbitmq hosts, INV-15 (per-host TLS, one owner per Secret, staging noindex)"
provides:
  - "k8s/components/keycloak (kind: Component): ServiceAccount, Deployment, Service keycloak (8080 http, 9000 management), Ingress keycloak-public, NetworkPolicy keycloak-allow, configMapGenerator keycloak-theme"
  - "keycloak-public: auth-staging.olajay.co.uk, exactly /realms/jtoye-staging, /realms/jtoye-customers, /resources/ -> keycloak:8080; TLS Secret jtoye-staging-auth-tls; staging noindex snippet"
  - "k8s/components/redis: Deployment/Service redis:6379 (no persistence), NetworkPolicy redis-allow"
  - "k8s/components/rabbitmq: RabbitmqCluster rabbitmq (rabbitmq:4.3.6-management-alpine, rabbitmq_stomp, externalSecret rabbitmq-default-user), NetworkPolicy rabbitmq-allow"
  - "staging app-config keys keycloak.hostname, keycloak.db.url; keycloak-allow egress.1 in the staging db.port replacement"
  - "scripts/check-k8s-copy-parity.sh (+ --selftest, 13 arms) over k8s/copy-parity.conf, wired into ci-cd.yaml ops-contracts"
  - "INV-7 rows keycloak-allow, redis-allow, rabbitmq-allow @k8s/staging"
  - "Horizons: K8S_GLOB_DIRS includes k8s/components; rabbitmq-k8s pinned at the CR site, manual_review retired; keycloak/redis rows carry their component sites"
affects: [29-12, 29-13, 29-14, 29-15, 29-17, 29-22, 29-23, 29-25, phase-32]

actuals:
  tokens: 26740
  tasks: 3
  commits: 4
plan_head_before: 6771f89a552249c65a9829084d57303e62c49780
plan_head_after: 288c4102ceeb40f523f04c06fb58b6274a08d159

tech-stack:
  added: ["quay.io/keycloak/keycloak:26.8.1 (in-cluster, start mode)", "redis:8.10-alpine (in-cluster)", "RabbitmqCluster CR (cluster operator v2.23.1) running rabbitmq:4.3.6-management-alpine"]
  patterns:
    - "Staging-only platform services are kustomize Components at k8s/components/<name>/ (depth 3: never a gate target, always judged inside the staging render)"
    - "An Ingress host returns in the SAME file as its Service and Deployment (#296 order rule, INV-6)"
    - "A file both runtimes serve is a committed copy declared in k8s/copy-parity.conf and md5-gated in CI"
    - "A manifest literal that must agree with app-config is declared in INV-7 by placeholder (__INGRESS_NS__), so a disagreement fails the render gate"

key-files:
  created:
    - k8s/components/keycloak/kustomization.yaml
    - k8s/components/keycloak/keycloak.yaml
    - k8s/components/keycloak/theme/theme.properties
    - k8s/components/keycloak/theme/jtoye.css
    - k8s/components/keycloak/theme/work-sans-latin.woff2
    - k8s/components/redis/kustomization.yaml
    - k8s/components/redis/redis.yaml
    - k8s/components/rabbitmq/kustomization.yaml
    - k8s/components/rabbitmq/rabbitmq-cluster.yaml
    - k8s/copy-parity.conf
    - scripts/check-k8s-copy-parity.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-10-t2-red-one-byte-edit.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-10-t2-red-missing-copy.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-10-t2-red-no-md5sum.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-10-t2-red-empty-conf.json
  modified:
    - k8s/staging/kustomization.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/base/ingress.yaml
    - k8s/base/networkpolicies/README.md
    - .github/workflows/ci-cd.yaml
    - infra/dependency-horizons.yaml
    - scripts/check-dependency-horizons.sh
    - k8s/DEPLOYMENT.md
    - docs/runbooks/rabbitmq-broker-upgrade.md
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md
    - .planning/WINDOWS.md

key-decisions:
  - "Keycloak runs with KC_CACHE=local: one replica, and keycloak-allow admits no JGroups 7800, so the default jdbc-ping stack would only look for peers it cannot reach. Scaling out needs ispn plus a same-label 7800 rule"
  - "JAVA_OPTS_KC_HEAP MaxRAMPercentage=60 inside the plan's 1Gi limit: the image default 70% plus ~300 MB non-heap (Keycloak sizing guide) is ~1017Mi, at the limit. Assumed; 29-14 measures it"
  - "keycloak-allow names the controller namespace as the literal traefik (plan), but its INV-7 row uses __INGRESS_NS__, so the literal cannot silently drift from staging's app-config"
  - "The plan's 'H-1 names 4.3.6 before the row update' arm cannot fail (H-1 is pin-set membership and the compose rabbitmq row already carries the identical pin); replaced by a planted unrowed tag that is invisible before the K8S_GLOB_DIRS widening and rc 1 after, plus a one-sided bump that is rc 2 after the row update"
  - "The rabbitmq-k8s row becomes kind image, owner maintainer, eol_source vendor (2027-01-31), manual_review retired; it shares the compose row's pin and horizon, so both enter the 90-day window together on ~2026-11-02"
  - "The rabbitmq component carries no labels transformer: the operator drops every app.kubernetes.io/* CR label (internal/metadata/label.go v2.23.1)"
  - "The copy-parity gate refuses symlinks, self-pairs, '..'/absolute paths and duplicate copies as VOID: each would let parity pass over a file compared with itself or outside the repo"

patterns-established:
  - "kustomize Component per staging-only platform service, with the base label shape (D-17) where the workload is ours"
  - "Generic copy-parity gate: one conf of <copy> <canonical> pairs, md5 equality, VOID on any unevaluable input, planted-arm selftest in CI"

requirements-completed: [DPLY-01, DPLY-02]

coverage:
  - id: D1
    description: "Keycloak 26.8.1 component: start mode, full public KC_HOSTNAME from app-config, backchannel dynamic, xforwarded, health/metrics on 9000, own keycloak database over verify-full, credentials only from keycloak-server-credentials; theme mounted from byte-equal copies"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "bash k8s/scripts/check-render-invariants.sh && bash k8s/scripts/render-golden.sh && bash k8s/scripts/check-no-plaintext-secrets.sh (rc 0; staging INV-6 6 refs -> 5 Services, INV-7 10 policies / 74 tuples, INV-15 3 Ingresses / 4 tls entries)"
        status: pass
      - kind: other
        ref: "rendered keycloak-theme ConfigMap: binaryData work-sans-latin.woff2 (50524 bytes decoded, md5 4890688e…), data theme.properties (md5 2813bc4b…) and jtoye.css (md5 621758c1…), each equal to infra/keycloak/themes/jtoye/login/*"
        status: pass
    human_judgment: false
  - id: D2
    description: "keycloak-public routes exactly /realms/jtoye-customers, /realms/jtoye-staging, /resources/ (no master realm, no /admin/), own TLS Secret, staging noindex; INV-6 order rule live"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "Task 1 exact-path verify: rc 0 on the real render; rc 1 with a scratch first path /realms/; rc 1 on a render with no keycloak-public (empty list)"
        status: pass
      - kind: other
        ref: "INV-6 arm: scratch copy with the keycloak Service deleted -> rc 1 'Ingress keycloak-public publishes host auth-staging.olajay.co.uk and routes it to Service keycloak, which does NOT exist'; clean copy rc 0"
        status: pass
      - kind: other
        ref: "arms in scratch copies: noindex removed -> INV-15 rc 1; secretName shared with jtoye-staging-api-tls -> INV-15 rc 1; namespace literal ingress-nginx -> INV-7 rc 1; extra egress port -> INV-7 rc 1"
        status: pass
    human_judgment: false
  - id: D3
    description: "Byte-parity gate over k8s/copy-parity.conf, in CI, unable to pass over nothing"
    requirement: "DPLY-02"
    verification:
      - kind: unit
        ref: "bash scripts/check-k8s-copy-parity.sh --selftest (13/13; RED against the stub 1/13, RED_EVIDENCE_OK x4)"
        status: pass
      - kind: other
        ref: "real tree 'PASS: 3 pairs, all byte-equal.'; one byte of the real jtoye.css copy changed -> rc 1 naming pair 2 and both md5s; restored, md5 = committed"
        status: pass
      - kind: other
        ref: "check-gate-enforcement.sh rc 0; with the two ci-cd.yaml steps removed in a scratch copy rc 1 naming check-k8s-copy-parity.sh; grep -c in ci-cd.yaml = 2"
        status: pass
    human_judgment: false
  - id: D4
    description: "Redis 8.10 single pod, no persistence, requirepass from redis-server-credentials; RabbitmqCluster rabbitmq pinned 4.3.6-management-alpine, STOMP, explicit resources, externalSecret rabbitmq-default-user; INV-7 rows for both"
    requirement: "DPLY-01"
    verification:
      - kind: other
        ref: "staging render read back: exactly 1 RabbitmqCluster, spec.image rabbitmq:4.3.6-management-alpine, requests 250m/512Mi, limit 1Gi, plugins [rabbitmq_stomp], externalSecret rabbitmq-default-user; redis args --save '' --appendonly no --maxmemory 96mb allkeys-lru --requirepass $(REDIS_PASSWORD)"
        status: pass
      - kind: other
        ref: "INV-7 arms: redis ingress widened to every namespace -> rc 1; rabbitmq 61613 dropped -> rc 1; rabbitmq component removed from staging -> rc 1 (declared policy absent)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Horizons gate discovers k8s/components; rabbitmq-k8s row pinned at the CR site with its manual_review retired"
    requirement: "DPLY-02"
    verification:
      - kind: other
        ref: "bash scripts/check-dependency-horizons.sh (jtoye-ops env): rc 0, missing-row=0, pin-not-at-site=0 (was 2), UNKNOWN rows 9 (was 10, rabbitmq-k8s gone)"
        status: pass
      - kind: other
        ref: "planted rabbitmq:4.3.7-management-alpine in the CR: rc 0 before the widening (invisible), rc 1 H-1 after; after the row update a one-sided bump is rc 2 (H-1 + H-5 VOID naming rabbitmq-k8s)"
        status: pass
    human_judgment: false
  - id: D6
    description: "Local and production renders unchanged; staging changed"
    verification:
      - kind: other
        ref: "sha256 of kubectl kustomize before the first edit vs after the last commit: production 5ab9b27a… = 5ab9b27a…, local 5153d485… = 5153d485…, production golden identical; staging 35680870… -> 2a12edde… (control moved)"
        status: pass
    human_judgment: false
  - id: D7
    description: "The live halves: Keycloak boots in start mode on B2s and passes /health/ready, verify-full to the managed keycloak database, /admin/ and /realms/master are 404 at the edge, heap headroom under the 1Gi limit, the broker's peer discovery need for 443, the theme rendered on the login page"
    requirement: "DPLY-01"
    verification: []
    human_judgment: true
    rationale: "No cluster exists and the owner forbids applying these components to any cluster in this plan. The live proofs belong to plan 29-14 (tracer: boot, readiness, TLS, heap, broker egress) and 29-15 (edge: 404 for /admin/, headers)."

duration: 23min
completed: 2026-10-19
status: complete
---

# Phase 29 Plan 10: In-Cluster Platform Layer Summary

**Staging now declares the three in-cluster services the login path needs, as kustomize Components only `k8s/staging` includes. Keycloak 26.8.1 runs in start mode on its own verify-full database, behind an Ingress that publishes exactly three path prefixes on auth-staging.olajay.co.uk. Redis 8.10 runs with no persistence. The RabbitMQ broker is an operator-run `RabbitmqCluster` pinned to `rabbitmq:4.3.6-management-alpine`. A new CI gate md5-checks every committed copy against its canonical file. The broker's horizon row is finally pinned in-repo.**

## Performance

- **Duration:** 23 min
- **Started:** 2026-10-19T00:07:17Z
- **Completed:** 2026-10-19T00:30:24Z
- **Tasks:** 3
- **Files modified:** 26 across 4 commits, plus deferred-items.md, WINDOWS.md and this SUMMARY

## Accomplishments

- **Keycloak (D-02, #296 render half).**
  - It is one file: `keycloak.yaml` holds the ServiceAccount, Deployment, Service, Ingress and NetworkPolicy. So the auth host can only return together with its backend, and INV-6 fails the render otherwise (shown).
  - `KC_HOSTNAME` is the full public URL taken from app-config, so tokens keep `iss = https://auth-staging.olajay.co.uk/realms/<realm>`. Backchannel-dynamic lets pods use the internal Service that 29-08 configured.
  - Health and metrics run only on 9000. The legacy 8080 observability flag is not set.
- **The public surface is exactly three prefixes (T-29-35):** `/realms/jtoye-staging`, `/realms/jtoye-customers` and `/resources/`. There is no blanket `/realms/`, no master realm and no `/admin/`. The auth host has its own Secret, `jtoye-staging-auth-tls`, and the staging noindex snippet. INV-15 passes on 3 Ingresses.
- **Login theme.** It is mounted from committed copies through a hash-suffixed ConfigMap, so a theme edit rolls the pod. The woff2 is stored as `binaryData`. The rendered bytes of all three files equal the canonical md5s.
- **Redis (D-24).**
  - Cache-only: no RDB, no AOF, `maxmemory 96mb allkeys-lru` inside a 128Mi limit.
  - Read-only root, uid 999.
  - The cost of a restart or eviction is recorded in the file header: rate-limit buckets reset.
- **RabbitMQ (D-09).**
  - The CR pins the compose tag, enables `rabbitmq_stomp` with an explicit 61613 listener, and sets explicit 250m/512Mi with a 1Gi limit. The operator's default is 1-2 CPU and 2Gi.
  - The default user comes from `rabbitmq-default-user` with the keys the operator's own Secret uses, which I read in the v2.23.1 source.
- **INV-7.** Rows `keycloak-allow`, `redis-allow` and `rabbitmq-allow @k8s/staging` give 10 policies and 74 tuples exact. The README flow matrix carries the same rows.
- **Copy-parity gate (T-29-36).** `scripts/check-k8s-copy-parity.sh` carries over the branch gate's byte-equality and "which side is canonical" reasoning, generalised to a list of pairs. It is wired into ops-contracts with its selftest.
- **Horizons.**
  - `k8s/components` is scanned, and that is shown to be load-bearing.
  - `rabbitmq-k8s` now pins the CR image at its site. Its manual_review is retired, per runbook §7 step 4.
  - The keycloak and redis rows gain their component sites, so H-5 sees a one-sided bump.

## Task Commits

1. **Task 1: Keycloak component with the restricted public Ingress and the mounted theme**: `3bb5be5b` (feat)
2. **Task 2: Generic byte-parity gate (TDD)**
   - RED `fdcc31f5` (test): harness and conf, with a stub comparison. Includes 4 RED evidence records.
   - GREEN `758f8793` (feat): the real comparison and the CI wiring.
3. **Task 3: Redis and RabbitMQ components, INV-7 rows, horizon pin**: `288c4102` (feat)

**Plan metadata:** the docs commit that carries this SUMMARY.

## Evidence (both directions recorded)

### Task 1

- **Verify 1** (render invariants, golden, no-plaintext-secrets): rc 0.
  - Before `--write`, render-golden gave rc 1 ("no longer matches its committed golden").
  - After `--write`: `WROTE [staging]` and `UNCHANGED [production]`, then rc 0.
- **Verify 2** (exact keycloak-public paths):
  - The real render prints `/realms/jtoye-customers`, `/realms/jtoye-staging`, `/resources/`, rc 0.
  - **Fail direction:** a scratch copy whose first path is `/realms/` prints `/realms/`, `/realms/jtoye-customers`, `/resources/`, rc 1.
  - A render with no keycloak-public (production) gives an empty list, rc 1.
- **INV-6 AC:** with the keycloak Service deleted from a scratch copy, rc 1, with three `FAIL [k8s/staging] INV-6: Ingress 'keycloak-public' … routes it to Service 'keycloak', which does NOT exist`. The clean copy gives rc 0.
- **More arms**, each in a scratch copy and each applied (checksum changed):

  | Arm | rc | What failed |
  |-----|----|-------------|
  | egress port 25 added | 1 | INV-7 `undeclared rule(s): eg:pod=app=mailpit:25` |
  | noindex line removed | 1 | INV-15 `serves no X-Robots-Tag noindex` |
  | namespace literal `ingress-nginx` | 1 | INV-7 missing `in:ns=traefik:8080`, undeclared `in:ns=ingress-nginx:8080` |
  | secretName `jtoye-staging-api-tls` | 1 | INV-15 `requested by 2 tls entries` |

- **db.port replacement:**
  - Planting `db.port: "6432"` makes keycloak-allow's egress.1 port 6432. The real render has 5432.
  - With the keycloak-allow replacement target removed, the planted 6432 still renders 5432. So the target is load-bearing.
- **Theme AC:** in the rendered ConfigMap the woff2 is under `binaryData` and the other two files are under `data`. Decoded md5s match the canonical files:

  | File | md5 (copy = canonical) |
  |------|------------------------|
  | `theme.properties` | `2813bc4ba3c3adf651148282dab9ea45` |
  | `jtoye.css` | `621758c1f4b6e60bd072a3aed14560e3` |
  | `work-sans-latin.woff2` | `4890688e64367257c794ab3ad38ce580` |

- `--selftest-inv7` still gives 5/5 and `--selftest-inv15` 8/8, each with CLEAN rc 0. `check-doc-citations` (jtoye-ops env) gives PASS, 46 verified.

### Task 2 (TDD)

- **RED** (`fdcc31f5`): `--selftest` rc 1.
  - All 12 failure arms gave `caught=no ... got rc=0: PASS: 0 pairs, all byte-equal (STUB: nothing was compared)`. No arm reported "plant did not apply". CLEAN rc 0.
  - Classifier (TAP, 13 tests, 1 pass / 12 fail): `RED_EVIDENCE_OK target_test_failed` for one-byte-edit, missing-copy, no-md5sum and empty-conf. Records are in `evidence/29-10-t2-red-*.json`.
  - **Semantic assessment:** each target arm ran its plant, and the gate exited 0 only because `run_check` was a stub. The clean arm in the same run was ok, so the harness was sound.
- **GREEN** (`758f8793`): 13/13. The 12 arms are one-byte-edit (binary), missing-copy, missing-canonical, no-md5sum, empty-conf, comment-only-conf, missing-conf, malformed-line, self-pair, symlink-copy, escaping-path and duplicate-copy, plus CLEAN rc 0.
- **Real tree:** `PASS: 3 pairs, all byte-equal.`, rc 0.
- **Real-tree break arm** (clean, arm, clean; the copy was committed in `3bb5be5b`):

  | Step | Copy md5 | Gate |
  |------|----------|------|
  | CLEAN-BEFORE | `621758c1…` (= committed) | rc 0 |
  | ARM: first byte of the k8s `jtoye.css` changed | `28373891…` | rc 1, `FAIL: pair 2 has DRIFTED`, copy and CANONICAL md5s both printed |
  | CLEAN-AFTER | `621758c1…` | rc 0 |

- **Gate enforcement:**
  - On the real tree, rc 0. `command grep -c 'check-k8s-copy-parity.sh' ci-cd.yaml` = 2.
  - In a scratch copy with both steps removed (0 refs), rc 1: `FAIL: 1 gate(s) are referenced by no workflow … check-k8s-copy-parity.sh`.

### Task 3

- **Verify 1** (render invariants, golden, env-contract, no-plaintext-secrets, connection-math): rc 0, 0 FAIL or PARSE ERROR lines. render-golden gave rc 1 before `--write`.
- **Verify 2** (horizons, jtoye-ops env): rc 0.
  - `missing-row=0`.
  - `pin-not-at-site=0`, down from 2 at baseline (go-ci-setup, re-pointed to 1005/1435).
  - `UNKNOWN rows=9`, down from 10: rabbitmq-k8s is no longer printed.
- **H-1 break arm over k8s/components** (the plan's AC, made falsifiable):
  - **The plan's literal form cannot fail.** The widened gate, with the old row and the real 4.3.6 pin, gives rc 0 and `missing-row=0`. H-1 tests pin-set membership, and the compose `rabbitmq` row already holds `rabbitmq:4.3.6-management-alpine`.
  - **The stronger form**, a planted unrowed tag `rabbitmq:4.3.7-management-alpine` in the CR:
    - With K8S_GLOB_DIRS still `base, platform`: rc 0. The tag is invisible.
    - With `k8s/components` added: rc 1, `FAIL: H-1 rabbitmq:4.3.7-management-alpine is pinned … but has NO horizon row`.
  - **After the row update:** a one-sided CR bump gives rc 2. H-1 names the tag, and the H-5 VOID names `rabbitmq-k8s: declared pin 'rabbitmq:4.3.6-management-alpine' NOT FOUND … rabbitmq-cluster.yaml:37`.
- **RabbitmqCluster AC:** the staging render holds exactly 1 RabbitmqCluster (`rabbitmq`, ns `jtoye-staging`, image `rabbitmq:4.3.6-management-alpine`). Production and local hold 0.
- **INV-7 arms:**

  | Arm | rc | What failed |
  |-----|----|-------------|
  | redis ingress widened to `namespaceSelector: {}` | 1 | missing core-java/exporter tuples, undeclared `in:ns=*:6379` |
  | rabbitmq 61613 dropped | 1 | missing `in:pod=app=core-java:61613` |
  | rabbitmq component removed from staging | 1 | `declared policy 'rabbitmq-allow' … is absent from the render` |

- **validate-networkpolicies.py** (jtoye-ops env): `PASS: 7 files`, rc 0. It reads only `k8s/base/networkpolicies/`, so the component policies are not seen at all. I recorded this and did not edit the script (deferred-items.md).
- **Render-unchanged AC** (sha256 of `kubectl kustomize`, before the first edit and after the last commit, all rc 0 and non-empty):

  | Target | Before | After | Result |
  |--------|--------|-------|--------|
  | production | `5ab9b27a0e881e15…` (1851 lines) | `5ab9b27a0e881e15…` | IDENTICAL |
  | local | `5153d4855d927e3d…` (1807 lines) | `5153d4855d927e3d…` | IDENTICAL |
  | `k8s/goldens/production.yaml` | `5ab9b27a…` | `5ab9b27a…` | IDENTICAL |
  | **staging (control)** | `35680870bcdabe99…` (1860 lines) | `2a12eddedf4d666f…` (3993 lines) | DIFFERS, so the instrument moved |

- **Stub scan:** `rg -uu` for TODO, FIXME, "coming soon", "not available" and STUB over the new files gives rc 1 (none). Control: the RED commit's script has 2 `STUB` hits.

## Files Created/Modified

- `k8s/components/keycloak/{kustomization.yaml,keycloak.yaml,theme/*}`: the Keycloak component and the byte-equal theme copies.
- `k8s/components/redis/{kustomization.yaml,redis.yaml}`: the Redis component.
- `k8s/components/rabbitmq/{kustomization.yaml,rabbitmq-cluster.yaml}`: the RabbitmqCluster and its policy.
- `k8s/staging/kustomization.yaml`: the `components:` list (keycloak, redis, rabbitmq), and keycloak-allow in the db.port replacement.
- `k8s/staging/configmap-patch.yaml`: `keycloak.hostname` and `keycloak.db.url`.
- `k8s/scripts/check-render-invariants.sh`: three INV-7 rows.
- `k8s/goldens/staging.yaml`: regenerated in `3bb5be5b` and in `288c4102`.
- `k8s/base/ingress.yaml`: comment only. The "IF AN IN-CLUSTER KEYCLOAK" note now says staging does have one, so it is no longer false. The render is unchanged.
- `k8s/base/networkpolicies/README.md`: the "Staging-only component policies" matrix and the policy counts.
- `k8s/copy-parity.conf`, `scripts/check-k8s-copy-parity.sh`: the parity gate.
- `.github/workflows/ci-cd.yaml`: two ops-contracts steps.
- `scripts/check-dependency-horizons.sh`: `k8s/components` in K8S_GLOB_DIRS, with the measured reason.
- `infra/dependency-horizons.yaml`:
  - the `rabbitmq-k8s` row;
  - component sites on the keycloak and redis rows;
  - the go-ci-setup sites re-pointed.
- `k8s/DEPLOYMENT.md`, `docs/runbooks/rabbitmq-broker-upgrade.md` §7: both now say the staging broker is in-repo. The production text is kept.
- `deferred-items.md`: three entries. `WINDOWS.md`: one deviation entry.

## Decisions Made

See `key-decisions` in the frontmatter.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing critical] `KC_CACHE=local` and `JAVA_OPTS_KC_HEAP` on Keycloak**
- **Found during:** Task 1.
- **Issue:** With `start` and no cache setting, Keycloak uses distributed caches over jdbc-ping. The pod registers in the database and looks for peers on 7800, which keycloak-allow does not admit, at every rolling update.
- **Heap:** the image default heap is 70% of the limit. Add ~300 MB non-heap (Keycloak's sizing guide) and the total is ~1017Mi against the plan's 1Gi limit.
- **Fix:**
  - local cache, for the single replica;
  - `MaxRAMPercentage=60`;
  - the plan's 500m/1Gi kept unchanged.
- **Recorded as assumed:** WINDOWS deviation entry; 29-14 measures it.
- **Commit:** `3bb5be5b`.

**2. [Unfalsifiable criterion, replaced] Task 3's H-1 break arm**
- **Issue:** As written ("before the row update the widened gate exits 1 naming rabbitmq:4.3.6…"), the arm exits 0. Measured: rc 0, `missing-row=0`. The compose row carries the same pin.
- **Fix:**
  - Replaced by the planted unrowed tag: rc 0 without the widening, rc 1 with it.
  - Added the after-update one-sided bump: rc 2.
- Both runs are recorded above, next to the original.
- **Commit:** `288c4102`.

**3. [Rule 2 - Missing critical] More VOID arms in the copy-parity gate**
- **Issue:** The four behaviours the plan names leave three vacuous passes open:
  - a symlinked copy;
  - a self-pair, where a file is compared with itself;
  - a `..` or absolute path.
- **Fix:** each of these exits 2, and so do a duplicate copy and a malformed line. Each has its own selftest arm (13 in all).
- **Commits:** `fdcc31f5`, `758f8793`.

**4. [Rule 2 - Missing critical] Component sites on the keycloak and redis horizon rows**
- **Issue:** With only compose sites declared, H-5 could not see a bump of the k8s copy of the same image.
- **Fix:** added `k8s/components/keycloak/keycloak.yaml:70` and `k8s/components/redis/redis.yaml:74`. The gate gives rc 0.
- **Commit:** `288c4102`.

**5. [Rule 3 - Blocking] The go-ci-setup horizon sites were re-pointed**
- **Issue:** The Task 2 CI insert moved a cited `go-version` line. 29-08 had already moved the other one.
- **Fix:** 998/1412 changed to 1005/1435, the measured lines. `pin-not-at-site` went from 2 to 0.
- **Commit:** `288c4102`.

**6. [Doc accuracy] Statements that became false were updated in place**
- `k8s/base/ingress.yaml` comment: "Keycloak is an external managed IdP".
- `k8s/DEPLOYMENT.md`: the rabbitmq-k8s "live example".
- Runbook §7: "not deployed from this repository" now carries a dated staging note.
- The networkpolicies README: the matrix and counts, which the INV-7 table comment requires in the same commit.
- None of these changes a render. The production hashes above are identical.
- **Commits:** `3bb5be5b`, `288c4102`.

**7. [Structure, recorded] The CI wiring landed with GREEN, not RED**
- This follows the 29-02 and 29-08 precedent.
- Between `fdcc31f5` and `758f8793`, `check-gate-enforcement.sh` would name the unwired gate. That is a short window inside this plan, closed by the GREEN commit.

---

**Total deviations:** 7.
- 3 missing-critical strengthenings.
- 1 unfalsifiable criterion replaced.
- 1 blocking.
- 1 doc-accuracy batch.
- 1 recorded structure choice.

**Impact on plan:** Every must-have truth holds. Nothing the plan specified was dropped. Every addition strictly narrows or falsifies.

## TDD Gate Compliance

- **Task 2:** RED `fdcc31f5` (`test(29-10)`) precedes GREEN `758f8793` (`feat(29-10)`). RED_EVIDENCE_OK was given for 4 target arms. No refactor was needed.
- **Tasks 1 and 3:** `type="auto"`, with no tdd flag.

## Issues Encountered

- **The horizons gate needs network access** (endoflife.date, read-only) and PyYAML. It ran under the jtoye-ops env. A bare host `python3` is refused by the shim.
- **The CI kubectl is v1.33.3, with an older kustomize than the local v5.7.1.** The goldens were written locally, as in earlier plans. The woff2 `binaryData` base64 line wrapping is the one new render shape whose cross-version stability is not proven here: CI's render-golden step is the check.

## Known Stubs

None. The RED stub `run_check` was replaced in `758f8793` (control: the RED commit's script has 2 `STUB` hits, the current one 0).

## Threat Flags

None beyond the register:

| Threat | Mitigated by |
|--------|--------------|
| T-29-35 | the exact three-path check and the INV-6/INV-15 arms |
| T-29-36 | the parity gate |
| T-29-38 | externalSecret only, with no committed value |

T-29-37 and T-29-39 are accepted as planned.

One residual is recorded rather than fixed: `KC_PROXY_TRUSTED_ADDRESSES` is unset, so Keycloak believes forwarded headers from any peer. That is limited to the peers keycloak-allow admits. It waits on the Traefik pod CIDR, which 29-14 measures (deferred-items.md).

## User Setup Required

None. Nothing was applied to any cluster, and no Azure call was made.

## Next Phase Readiness

| Plan | What it needs from 29-10 |
|------|--------------------------|
| 29-12 | Supply `keycloak-server-credentials` {db-password, bootstrap-admin-username, bootstrap-admin-password}, `redis-server-credentials` {password}, and `rabbitmq-default-user` {username, password, default_user.conf}. Add WI labels, annotations and SPC volumes to the `keycloak` and `redis` ServiceAccounts and Deployments, which exist now. |
| 29-14 | Boot Keycloak in start mode on B2s and record time-to-`/health/started`. Measure the heap working set against 1Gi. Prove verify-full to the `keycloak` database. Check whether the broker ever uses its 443 egress, and drop the rule if not. Measure the Traefik pod CIDR for `KC_PROXY_TRUSTED_ADDRESSES`. |
| 29-15 | At the live edge, `/admin/` and `/realms/master` must be 404 on auth-staging. The noindex and security headers must be present there. |
| 29-23 | Add the redis-exporter (`app: redis-exporter` is admitted on 6379), and the Prometheus scrapes of `keycloak:9000` and `rabbitmq:15692`. Add the alert and dashboard pairs to `k8s/copy-parity.conf`. |
| 29-25 | `/realms/jtoye-customers` is already routed. |
| 29-22 | Mailpit `app: mailpit` on 1025 is already admitted from Keycloak. |

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Completed: 2026-10-19*

## Self-Check: PASSED

- FOUND: all 9 component files, `k8s/copy-parity.conf`, `scripts/check-k8s-copy-parity.sh`, and the 4 `evidence/29-10-t2-red-*.json` records.
- FOUND (ancestors of HEAD): `3bb5be5b`, `fdcc31f5`, `758f8793`, `288c4102`.
- Commits were measured with `git rev-list --count 6771f89a..HEAD` = 4 at SUMMARY write. `plan_head_after` was read from git.
