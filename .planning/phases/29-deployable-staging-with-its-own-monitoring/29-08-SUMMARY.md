---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 08
subsystem: deploy
tags: [keycloak, ingress, tls, acme, networkpolicy, oidc, seo, kustomize]
requires:
  - "29-07: the grafana ClusterIP Service, landed a plan early so INV-6 would let a rule name it"
  - "29-04: the ipBlock arm of INV-7 and the db.port/db.egress-cidr replacement mechanism"
  - "29-10: the keycloak-credentials Secret keys (db-username, db-password, frontend-client-secret) and the `keycloak` database on the managed server"
provides:
  - "an in-cluster Keycloak Deployment + ClusterIP Service on 8080, with metrics enabled"
  - "a per-environment realm import with no wildcard web origin in any environment"
  - "four staging host rules and four TLS SANs, every backend resolving in the same render"
  - "the cert-manager ClusterIssuer as a config key rather than a manifest literal"
  - "X-Robots-Tag: noindex, nofollow on the staging ingress"
affects:
  - "29-10: the four SANs are what its DNS records are for; staging now needs A records for auth- and grafana- too"
  - "29-11: the letsencrypt-staging -> letsencrypt-prod flip is now a one-key config change in k8s/staging/configmap-patch.yaml"
  - "29-12: two JVMs are scraped again — L-2b should expect Phase 27's F-3c shape (an alert binding to Keycloak's JVM while carrying service: core-java)"
  - "Phase 32: production's auth. host rule + SAN arrive with production DNS, in that order"
tech-stack:
  added: []
  patterns:
    - "template ConfigMap + initContainer render (the alertmanager-config idiom) for a config whose credential must not be a literal"
    - "__NAME__ placeholders rather than ${NAME} where the consuming product uses ${...} for its own tokens"
    - "kustomize replacements into an annotation key, with \\. escaping the dots in the key name"
key-files:
  created:
    - k8s/base/keycloak/keycloak-deployment.yaml
    - k8s/base/keycloak/realm-import-configmap.yaml
    - k8s/base/keycloak/keycloak-networkpolicy.yaml
    - k8s/staging/ingress-annotations-patch.yaml
  modified:
    - k8s/base/kustomization.yaml
    - k8s/base/configmap.yaml
    - k8s/base/ingress.yaml
    - k8s/base/sse-ingress.yaml
    - k8s/base/networkpolicies/50-observability.yaml
    - k8s/staging/configmap-patch.yaml
    - k8s/staging/kustomization.yaml
    - k8s/staging/ingress-hosts-patch.yaml
    - k8s/staging/sse-ingress-hosts-patch.yaml
    - k8s/production/configmap-patch.yaml
    - k8s/production/kustomization.yaml
    - k8s/local/configmap-patch.yaml
    - k8s/local/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/goldens/production.yaml
    - infra/keycloak/realm-export.template.json
    - infra/keycloak/README.md
    - docker-compose.full-stack.yml
decisions:
  - "start, not start-dev and not start --optimized: the upstream image is not pre-built with db/health/metrics, so --optimized fails at boot; the cost is a re-run augmentation on every start, allowed for by a 5-minute startupProbe"
  - "KC_HOSTNAME_STRICT=true — the compose false is a token-forgery surface on a public host"
  - "the cluster realm carries core-api ONLY; edge-api and the two integration clients are omitted with a reason (no staging secret source) rather than imported secretless"
  - "no seed users in the cluster realm — a committed-recipe account on a public IdP is a standing target"
  - "sslRequired external, not all: all would break the in-cluster #102 admin path the moment it is enabled"
  - "the k8s realm is a DERIVATION of infra/keycloak/realm-export.template.json, not a byte copy — the two must differ, so a parity gate is impossible and the relationship is stated instead"
  - "base keycloak.client-id frontend -> core-api, closing 29-02's onward-assigned production gap"
metrics:
  duration: "~3h"
  completed: 2026-08-11
---

# Phase 29 Plan 08: In-Cluster Keycloak + The Four Staging Hosts — Summary

Keycloak stops being an external managed IdP that does not exist and becomes a
workload this repository owns, on `auth-staging.olajay.co.uk`, with a realm whose
redirect URIs and web origins are per-environment values rather than a wildcard —
and `k8s/base/ingress.yaml`'s three-year-old standing instruction is executed and
marked executed.

## What shipped

**The workload.** A Deployment + ClusterIP Service on 8080 under
`k8s/base/keycloak/`, reusing the compose image pin verbatim (no new
supply-chain surface), backed by its own `keycloak` database on the managed
Flexible Server. `KC_DB_POOL_MAX_SIZE` is `"20"`, `KC_METRICS_ENABLED` is
`"true"` — the `keycloak` scrape job has been DOWN since `prometheus-config.yaml`
was written and now has a target.

**The realm.** A template ConfigMap plus an initContainer render, the same idiom
`alertmanager-config.yaml` uses and for the same reason: the client secret must
not be a ConfigMap literal, and `check-no-plaintext-secrets.sh` forbids a
`kind: Secret` in any build. Placeholders are `__NAME__`, not `${NAME}`, so
Keycloak's own `${authBaseUrl}`-family tokens cannot collide; the render greps its
own output for a surviving placeholder and refuses to write the file, and it
refuses a `*` or `+` web origin outright.

**The routes.** `auth-staging` → Service `keycloak:8080` and `grafana-staging` →
Service `grafana:3000`, appended with `op: add` so the existing positional
`op: replace` ops keep addressing rules[0] (api) and rules[1] (app). Both SANs and
both rules went into the **staging** overlay, not the base: the constraint that
removed the original Keycloak SAN in 26-04 is about DNS, and D-08 keeps every
production hostname unresolvable until Phase 32.

**The issuer, and the quota it protects.** `cert-manager.io/cluster-issuer` stops
being a literal on both Ingresses and is replaced from app-config. Staging issues
against `letsencrypt-staging`, because four SANs share one ACME order, one failed
HTTP-01 fails all of it, and the production endpoint charges each failure against
a weekly quota that then blocks the retry. The flip is one key in 29-11.

**Staging says noindex.** `X-Robots-Tag: noindex, nofollow` via `server-snippet`,
which composes with the base's `configuration-snippet` instead of forcing a
staging-only duplicate of the platform's six security headers. This is what makes
the phase's **SEO = N/A** honest rather than an unearned exemption.

## Assumption A7 — MEASURED, and it holds with one correction

A7: *"the realm can be imported with `--import-realm` at pod start from a
ConfigMap, as compose does from a bind mount."*

- **Arm 1 (the claim):** `start --import-realm` with a realm JSON present in
  `/opt/keycloak/data/import` → the realm is created.
- **Arm 2 (the control):** the same command with the directory EMPTY → no realm
  is created **and the server starts anyway, silently**.

Arm 2 is what licenses reading arm 1 as evidence: it proves the import step can
produce "no realm" without producing an error.

**The correction, and it drove the design:** a ConfigMap volume is **read-only**,
and the realm must be RENDERED before it is imported. So the ConfigMap is mounted
at a template path, an initContainer renders it, and the rendered file lands in an
emptyDir that *is* `/opt/keycloak/data/import`. A7 holds for the import; it does
not hold for rendering in place.

**Mechanism chosen: initContainer. NOT a Job** (the recorded fallback) — a Job
racing a Deployment has no ordering guarantee, so the server can come up before
the realm exists and answer 404 on the authorize endpoint. An initContainer is
ordered by construction, and its non-zero exit blocks the pod, which is what turns
arm 2's silent "no realm" into a visible `Init:CrashLoopBackOff`.

## Fail-direction arms — every one run, both directions recorded

| # | Arm | Break result | Clean result |
|---|-----|--------------|--------------|
| 1 | INV-7 ipBlock: remove the `[keycloak-allow]` entry from `NETPOL_IPBLOCK_EXPECTED` | **rc=1**, `FAIL … 'keycloak-allow' renders an ipBlock egress rule but has no entry` on **all four** targets | rc=0; restore verified by `git hash-object` (`5fb1b91…`) |
| 2 | envsubst name list short by one (`CORE_API_WEB_ORIGINS` removed) | envsubst still **rc=0** — the trap — literal `${CORE_API_WEB_ORIGINS}` survives at line 684, `jq` **rc=5** | full list: 0 surviving tokens, explicit origin list rendered, `jq` rc=0 |
| 3 | `check-connection-math.sh` blindness: set the **k8s** `KC_DB_POOL_MAX_SIZE` to 200 | **rc=0**, still prints `keycloak(20)` — the gate cannot see the k8s value | positive control: compose value → 77 gives `keycloak(77)` and **rc=1**, so the parser does work |
| 4 | INV-6: point `auth-staging` at `keycloak-broken-arm` | **rc=1**, names the Service and the host | rc=0; restore verified by hash (`66a67ad…`) |
| 5 | JSON6902 index shift: insert a well-formed rule ahead of base `rules[0]` | see below — **the documented guarantee is false** | restore verified by hash (`f4cea54…`) |
| 6 | cert-manager replacement non-vacuity: sentinel value in base app-config | both Ingress annotations tracked the sentinel | restore verified by hash (`c52b013…`) |

Arms 1–4 and 6 behaved as the code claims. Arm 5 did not.

### Arm 5 found a false safety claim, and it is fixed (Rule 1)

`k8s/staging/ingress-hosts-patch.yaml` asserted: *"If the base reorders or adds a
rule, `kustomize build` FAILS LOUDLY on a missing path rather than silently
mis-patching — which is the behaviour we want."*

**Measured false.** A well-formed rule inserted ahead of index 0 leaves every
JSON6902 path valid — there is nothing missing to fail on — so
`kubectl kustomize k8s/staging` exited **0** and rendered:

```
api-staging.olajay.co.uk      -> frontend      <- SWAPPED
app-staging.olajay.co.uk      -> core-java     <- SWAPPED
app.olajay.co.uk              -> frontend      <- PRODUCTION host, unpatched,
                                                  inside the staging render
```

Which gate caught it, measured in the same arm:

```
kubectl kustomize            rc=0   <- saw nothing
check-render-invariants.sh   rc=0   <- saw nothing (INV-6 only asks whether a
                                       backend RESOLVES; all four still did)
render-golden.sh             rc=1   <- CAUGHT IT
```

The guard is the **committed golden render**, not the patch mechanism. The header
now says so, names the measurement, and keeps the append-only rule with a reason
that is true. The practical consequence: regenerating a golden must always be a
reviewed diff with every changed line accounted for, never a reflex `--write`.

## Task 2 acceptance, asserted as sets and in both directions

- **Four SANs and four host rules, sorted set** (not a count): `api-staging`,
  `app-staging`, `auth-staging`, `grafana-staging` — identical sets on both sides.
- **rules[0].host / rules[1].host diffed explicitly** pre-change snapshot vs
  post-change render: `api-staging.olajay.co.uk` / `app-staging.olajay.co.uk`,
  unchanged.
- **X-Robots-Tag:** staging **1**, production **0** — and the staging hit is the
  positive control that makes the production 0 a statement about the render rather
  than about the pattern.
- **cluster-issuer:** staging `letsencrypt-staging` on both Ingresses, production
  `letsencrypt-prod` on both.
- **No staging hostname leaked into production:** 0.

## Goldens — every changed line accounted for

`--snapshot 29-08-pre` was taken **before** the first edit. `--diff-since` resolve
rc **0**, captured on the same statement; diff stdout non-empty (so the snapshot
was not taken after the change).

**Removed: 3 lines, all attributed by file and by name.**

| File | Line | Account |
|------|------|---------|
| staging | `cert-manager.io/cluster-issuer: letsencrypt-prod` | → `letsencrypt-staging`, jtoye-ingress (T-29-08-04) |
| staging | `cert-manager.io/cluster-issuer: letsencrypt-prod` | → `letsencrypt-staging`, jtoye-sse-ingress |
| production | `keycloak.client-id: frontend` | → `core-api` |

Production's two cluster-issuer lines do **not** appear at all, which is the
criterion "byte-identical to the literal it replaces" holding. Staging's
`keycloak.client-id` does not appear because 29-02 had already patched it.

**One more removal than the plan predicted.** The plan expected *"only the
hardcoded issuer literal". `keycloak.client-id: frontend` is the extra one — the
phantom OIDC client, which the environment brief explicitly assigned here ("if your
plan's tasks cover it, fix it"). It does: this plan ships the cluster realm, and
that realm contains exactly one client, so leaving the base at `frontend` would
have the base ConfigMap and the base realm disagreeing inside one render.

**Added: 1165 lines.** `grep '^>' | grep -c 'kind: Secret'` → **0**, with 8
`kind: ` lines in the same set as the positive control.

## Full static sweep — every rc individually

```
k8s/scripts/render-golden.sh              rc=0
k8s/scripts/check-render-invariants.sh    rc=0
k8s/scripts/check-no-plaintext-secrets.sh rc=0
k8s/scripts/check-env-contract.sh         rc=0
k8s/scripts/check-connection-math.sh      rc=0
scripts/check-alert-corpus-parity.sh      rc=0
scripts/check-dependency-horizons.sh      rc=0
scripts/check-gate-enforcement.sh         rc=0
```

**CLEAN STATE ASSERTED LAST**, after the final commit:
`render-golden.sh` **rc=0**, `check-render-invariants.sh` **rc=0`.

## Deviations from Plan

### Auto-fixed / auto-added

**1. [Rule 2 — missing critical functionality] `keycloak-allow` NetworkPolicy + Prometheus' egress rule**
- **Found during:** Task 1
- **Issue:** `00-default-deny.yaml` selects every pod with `podSelector: {}` and
  staging runs an enforcing Cilium dataplane, so a new Keycloak pod would have had
  **no network at all** — no DNS, no database, unreachable from the ingress
  controller. The symptom is a readiness probe timing out on a database
  connection, i.e. a network denial that reads as a database fault. The tree had
  already named this: `50-observability.yaml`'s "STILL OWED" section says
  *"keycloak (plan 29-08) … Prometheus needs an egress rule to `app: keycloak` on
  8080 and Keycloak needs the matching ingress. Both belong with the manifests
  that create it."*
- **Fix:** `k8s/base/keycloak/keycloak-networkpolicy.yaml` (ingress from
  ingress-nginx, prometheus and core-java on 8080; egress DNS + the derived
  managed-Postgres pair, and deliberately **no** 0.0.0.0/0:443), plus the matching
  egress rule appended to `prometheus-allow`. The "STILL OWED" bullet is marked
  PAID rather than deleted.
- **Also required:** the `db.port` / `db.egress-cidr` replacement passes in all
  four kustomizations, and the `[keycloak-allow]` entry in
  `NETPOL_IPBLOCK_EXPECTED` — INV-7 fails a policy with an ipBlock rule and no
  declaration, which it did, on all four targets (arm 1).
- **Commit:** a37fb4c6

**2. [Rule 2 — correctness] base `keycloak.client-id` `frontend` → `core-api`, and production's now-false comment**
- **Found during:** Task 1
- **Issue:** the environment brief assigned 29-02's onward-recorded production gap
  here. `k8s/production/configmap-patch.yaml` also carried a comment asserting
  production "continues to authenticate as exactly the same OIDC client identity
  it always has", whose premise this change makes false.
- **Fix:** base value corrected with the measurement and its positive control
  written into the file; the production comment rewritten to record the old reason
  and why it no longer applies (dated records are amended, not overwritten).
- **Commit:** a37fb4c6

**3. [Rule 1 — false documented safety property] the `ingress-hosts-patch.yaml` "FAILS LOUDLY" claim**
- **Found during:** Task 2, arm 5. See the arm-5 section above.
- **Commit:** 3b802ceb

**4. [Rule 2 — the wildcard in the OTHER realm] `infra/keycloak/realm-export.template.json`**
- **Issue:** the plan parameterises the CLUSTER realm's origins. The shipped
  developer template kept `"webOrigins": ["*"]` on `core-api` and `edge-api` — and
  that file is what every new environment gets copied from.
- **Fix:** both replaced by envsubst element lists, supplied from
  `docker-compose.full-stack.yml` as literals **derived** from each client's own
  `redirectUris` (nothing invented; port 3001 deliberately absent, it belongs to
  the customer realm). The envsubst name list was extended in the same change —
  arm 2 shows what happens if it is not.
- **Commit:** a37fb4c6

### Deviations from the plan's stated file list

`k8s/base/networkpolicies/50-observability.yaml`, `k8s/base/sse-ingress.yaml`,
`k8s/production/kustomization.yaml`, `k8s/production/configmap-patch.yaml`,
`k8s/local/*` and `k8s/scripts/check-render-invariants.sh` are not in
`files_modified`. Each is forced by the change rather than chosen: the NetworkPolicy
pair, the second Ingress that shares the same TLS Secret (two different issuers
across them would put two Certificates in contention for one Secret), the overlay
replacement passes that a base pass provably does not cover, and INV-7's declaration
which the gate demands in the same commit.

## Known Stubs

None. No hardcoded empty value, placeholder string or unwired component was
introduced. The one workload that will not start in an environment is the
local-overlay Keycloak, which is **not** a stub — it fails loudly at container
creation on a named missing Secret key, and it is recorded as DEF-29-3 with what
closing it requires.

## Threat Flags

None beyond the plan's `<threat_model>`. The new surfaces are the ones it already
enumerates: a public IdP (T-29-08-02), a public Grafana (D-19, INV-8 keeps
Prometheus and Alertmanager off the list), and Keycloak → managed Postgres
(T-29-08-01/04). The Keycloak egress rule carries no 0.0.0.0/0:443 precisely so an
unauthenticated public endpoint has no general internet egress — asserted by INV-7's
exact multiset, not merely intended.

## Onward — named, not dropped

- **29-10 / owner:** DNS A records are now needed for **four** names, not two.
  `auth-staging.olajay.co.uk` and `grafana-staging.olajay.co.uk` join api- and
  app-. All four share one ACME order; issuance cannot succeed for any of them
  until every one resolves.
- **29-10 / whoever next edits `scripts/staging-secrets.sh`:** `keycloak-credentials`
  carries one client secret (`frontend-client-secret`). The cluster realm therefore
  ships `core-api` only. `edge-api`, `integration-catalog-ro` and
  `integration-orders-rw` need a secret key each before they can be imported — a
  confidential client with a secret nobody holds is worse than an absent one.
- **29-11:** flip `cert-manager.cluster-issuer` to `letsencrypt-prod` **only after**
  a certificate has actually been produced against the staging endpoint. Also owns
  the served-response proofs this plan explicitly does not make: that
  `X-Robots-Tag` reaches a browser (which needs 29-05's `annotations-risk-level`),
  that `KC_PROXY_HEADERS=xforwarded` is accepted by 24.0.5, and that
  `keycloak.hostname` + `keycloak.realm.name` reconstruct the issuer the tokens
  actually carry.
- **29-12 (L-2b):** two JVMs are scraped again. Phase 27's F-3c — an alert binding
  to Keycloak's JVM while carrying `service: core-java` — is now reachable.
- **Phase 32:** production's `auth.` host rule and SAN, with production's DNS, in
  that order. `keycloak.realm.name`/`hostname`/origins are already
  production-correct in the base and are declarations, not live hosts (D-08).

## Self-Check: PASSED

Each arm carries a negative control, because a check observed only passing may be
incapable of failing.

**Files claimed as created — all 5 FOUND**
(`keycloak-deployment.yaml`, `realm-import-configmap.yaml`,
`keycloak-networkpolicy.yaml`, `ingress-annotations-patch.yaml`, this SUMMARY).
Negative control: `k8s/base/keycloak/this-file-does-not-exist.yaml` → MISSING, so
the test can report absence.

**Commits claimed — all 3 FOUND** (`a37fb4c6`, `3b802ceb`, `40fcf120`).
Negative control: `deadbee1` → MISSING.

### The commit arm failed as an INSTRUMENT first, and the fix is worth recording

The first attempt reported all three commits **MISSING** while they were plainly
in `git log --oneline -4`. The cause was the shape of the check, not the tree:

```bash
git log --oneline --all | grep -q "^$h"     # under set -o pipefail
```

`grep -q` exits at the first match, `git` takes SIGPIPE, and `pipefail` promotes
that to **141** — so a real match is read as a failure. This is the documented
`grep -q`/pipefail inversion, and it failed in the direction that would have made
me doubt real commits. Re-run with a here-string (`grep -q "^$h" <<< "$LOG"`),
which has no pipe and no writer to signal, all three resolve. Suspect the
instrument first.
