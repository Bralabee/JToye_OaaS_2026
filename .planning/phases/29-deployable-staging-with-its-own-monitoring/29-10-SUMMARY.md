---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 10
subsystem: infra
tags: [azure, aks, cilium, postgresql, redis, dns, cost, secrets, provisioning]
status: CHECKPOINT (2nd) — Task 1 COMPLETE; Task 2 credentials still empty (verified against a working control); Task 3 zone holds zero A records — edits landing in a different zone

# Dependency graph
requires:
  - phase: 29-deployable-staging-with-its-own-monitoring
    plan: 01
    provides: "29-OPERATOR-DECISIONS.md — the 12 decision keys, the snackpass before-state table, the PG16 requirement"
  - phase: 29-deployable-staging-with-its-own-monitoring
    plan: 05
    provides: "scripts/azure-staging-provision.sh + scripts/staging-secrets.sh — the scripts this plan RUNS"
  - phase: 29-deployable-staging-with-its-own-monitoring
    plan: 02
    provides: "k8s/staging/scale-patch.yaml — the HPA floors that make the app tier fit the pool"
provides:
  - "A live staging estate in the owner's subscription: AKS (cilium, enforcing), PostgreSQL 16, Azure Managed Redis on 10000, static ingress IP, CI federated identity"
  - "scripts/azure-staging-provision.sh proven end-to-end on the REAL path (rc=0), after four defects no dry-run arm could reach"
  - "The 6380 -> 10000 sweep, with INV-7 shown to catch a silent fallback to the base 6379"
  - "29-PROVISIONING-EVIDENCE.md — every endpoint, the measured node allocatable, the DNS baseline and the cost re-derivation"
  - "Assumption A2 MEASURED: 1900m CPU / 2.72 GiB allocatable per B2s (research's ~2.4 GiB was ~13% conservative)"
  - "Obligation O-4 discharged: all six snackpass apps at 0 running replicas, measured not projected"
  - "A measured refutation of obligation O-5: Azure Cache for Redis cannot be created TODAY, not 2028"
  - "Two real-path defects fixed in azure-staging-provision.sh, neither reachable by 29-05's dry-run falsification"
affects: [29-11, 29-12, 29-13, 29-14, 29-15, 29-16]

# Tech tracking
tech-stack:
  added:
    - "AKS 1.35.6, Cilium dataplane, tier Free (jtoye-staging-aks)"
    - "Azure Database for PostgreSQL Flexible Server 16, Standard_B2s Burstable (jtoye-staging-pg)"
    - "Azure user-assigned managed identity + GitHub OIDC federated credential (jtoye-ci)"
  patterns:
    - "Read the fact off the running resource, never off the create command's exit status"
    - "A config value is not a bill: minReplicas 0 was verified by replica COUNT, after the cooldown"
    - "Prove a narrow firewall rule by contrast with a wide one in the same resource group"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/29-PROVISIONING-EVIDENCE.md
  modified:
    - scripts/azure-staging-provision.sh

key-decisions:
  - "Generated the PG administrator credential with 3+ character categories, NOT `openssl rand -hex 32` — hex is two categories and Azure rejects it"
  - "Stored that credential machine-local at ~/.jtoye/staging-admin.env (0600), outside the repo; no value in any tracked artifact"
  - "Did NOT substitute Azure Managed Redis for the blocked Azure Cache for Redis — different provider and port, so Rule 4, raised as a checkpoint"
  - "Rotated the administrator password after the CLI disclosed it in its own output, rather than only scrubbing the log"

requirements-completed: []
requirements-partial: [DPLY-01]

# Metrics
duration: 90min
completed: 2026-08-10
---

# Phase 29 Plan 10: Provision the Staging Estate Summary

**The staging estate is fully provisioned in the owner's subscription — AKS on an enforcing Cilium dataplane, PostgreSQL 16, and Azure Managed Redis on port 10000 — with every fact read back off the running resource, and the provisioning script now proven end-to-end on the real path rather than only in dry-run.**

## Performance

- **Duration:** ~90 min (2026-08-10T22:04Z → 23:33Z)
- **Tasks:** Task 1 COMPLETE; Tasks 2 and 3 blocked on operator input
- **Azure resources created:** 6 (AKS, PostgreSQL server, static public IP, managed identity + federated credential, Managed Redis cluster + database)
- **Azure resources deleted:** 0
- **Provisioning script:** `rc=0` end-to-end after 4 real-path defects fixed

## Task Commits

| Task | Name | Commit | Files |
|------|------|--------|-------|
| 1a | Provision the estate + apply the snackpass disposition | `0776b3c1` | `29-PROVISIONING-EVIDENCE.md`, `scripts/azure-staging-provision.sh` |
| 1b | Move staging to Azure Managed Redis, port 6380 → 10000 | `b8a85f15` | 13 files — configmap-patch, goldens, base comments, decision record, provisioning script |
| 1c | Managed Redis live; provisioning script rc=0 end-to-end | `5f0f7018` | `29-PROVISIONING-EVIDENCE.md`, `scripts/azure-staging-provision.sh` |
| — | merge 29-06's monitoring manifests + goldens | `(merge)` | `e9e39c19` merged conflict-free before any edit |
| 2 | Databases, three roles, BYPASSRLS proof | — | **BLOCKED** — 7 operator values still empty |
| 3 | DNS A records at Netlify | — | **BLOCKED** — records absent at the authoritative NS |

## What exists now

| Fact | Value | Read from |
|---|---|---|
| `AKS_NAME` | `jtoye-staging-aks` | `az aks show` |
| dataplane | **`cilium`** (and `networkPolicy: cilium`) | `az aks show` |
| `AKS_NODE_RG` | `MC_jtoye-rg_jtoye-staging-aks_uksouth` | `az aks show` |
| `AKS_EGRESS_IP` | `20.26.28.17` | `az network public-ip show` |
| `AKS_OIDC_ISSUER_URL` | `https://uksouth.oic.prod-aks.azure.com/b56df236-…/9f6ec1cb-…/` | `az aks show --query oidcIssuerProfile` |
| `INGRESS_STATIC_IP` | **`20.58.10.18`** | `az network public-ip show` |
| `PG_FQDN` | `jtoye-staging-pg.postgres.database.azure.com` | `az postgres flexible-server show` |
| `PG_VERSION` | **`16`** | `az postgres flexible-server show` |
| `PG_MAX_CONNECTIONS` | `429` | `az postgres flexible-server parameter show` |
| `PG_AZURE_EXTENSIONS` | `uuid-ossp` (`source: user-override`) | read back AFTER setting |
| `NODE_ALLOCATABLE` | `1900m` CPU, `2854308Ki` (2.72 GiB) × 3 | `kubectl get nodes` |
| `CI_IDENTITY_CLIENT_ID` | `ec29905d-231c-43eb-9385-c6f7f72409db` | `az identity create` |
| `REDIS_HOST` | `jtoye-staging-redis.uksouth.redis.azure.net` | `az redisenterprise show` |
| `REDIS_SSL_PORT` | **`10000`** (`Encrypted`, `Balanced_B0`) | `az redisenterprise database show` |

## Accomplishments

- **The snackpass disposition is applied and PROVEN, discharging O-4.** All six apps read `minReplicas: 0`, and — the part that matters — all six were measured at **0 running replicas**. The config value is not the bill.
- **Assumption A2 is measured rather than reasoned.** 1900m CPU per B2s matches the research exactly; 2.72 GiB memory beats its ~2.4 GiB estimate by ~13%. The app tier (800m / 1344 MiB, summed from the real render) uses 19% of free CPU after system overhead.
- **Blocker C is closed by version choice, verified from the server:** `version: 16`.
- **The `azure.extensions` precondition is satisfied and read back**, not assumed — `uuid-ossp`, the one `V1__base_schema.sql:6` needs.
- **T-29-10-01 is mitigated falsifiably:** one firewall rule, `start == end == 20.26.28.17`, shown against `snackpass-pg`'s `AllowAllAzureServicesAndResourcesWithinAzureIps` in the same resource group.
- **Cost re-derived from live retail prices:** £129.22/mo created, £139.15/mo projected — headroom **improves** from the planned £3.00 to £10.85.
- **Zero interaction with employer infrastructure.** The ambient default subscription is `Prod - HS2 Ltd` and the only pre-existing kube context is `sipbihs2aks`; every call was explicitly pinned, and the new `jtoye-staging` context was named explicitly.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 2 - Missing Critical / SECURITY] The administrator password was disclosed by the CLI's own output**

- **Found during:** Task 1, first real run
- **Issue:** `az postgres flexible-server create` prints a JSON result containing both a `password` field and a full `connectionString` with the password embedded. The script's `REDACT_FLAGS` mechanism redacts the rendered **command** in `--dry-run`; on a real run it is the **output** that discloses, and nothing suppressed it. Measured: 2 plaintext occurrences in the run log.
- **Fix:** password **rotated** on the server (the disclosed value is dead), log scrubbed, and `--output none` added to the create with the reasoning recorded in the script.
- **Verification:** scrub measured 2 occurrences before → 0 after. Separately, a canary-first scan proved the detector works and then found 0 hits across 2097 tracked files.
- **Commit:** `0776b3c1`

**2. [Rule 1 - Bug] `--public-access None` disabled public access, so the firewall rule could not be created**

- **Found during:** Task 1, STEP 6
- **Issue:** The run died with `Firewall rule operations are not supported for a server without public access enabled`. The CLI's own help says `None` *"sets the server in public access mode but does not create a firewall rule"* — exactly the intended shape — but the created server reported `publicNetworkAccess: Disabled`. **Documentation and behaviour disagree.**
- **Fix:** `--public-access Enabled` in the script; the existing server corrected in place and read back as `Enabled`.
- **Commit:** `0776b3c1`

**3. [Rule 3 - Blocking] `openssl rand -hex 32` would have been rejected by Azure**

- **Found during:** Task 1 setup
- **Issue:** 29-05's contract specifies `openssl rand -hex 32` for the administrator password. Hex is `[0-9a-f]` — digits plus lowercase, **two** character categories. Flexible Server requires at least **three** of {upper, lower, digit, non-alphanumeric}, so the documented contract produces a value the create call refuses.
- **Fix:** generated with a 40-character alphanumeric random core (~238 bits) plus affixes satisfying the category rule. Recorded in the evidence file so the contract can be corrected rather than re-tripped.
- **Commit:** `0776b3c1`

**4. [Rule 1 - Bug] A `pipefail` + `head` SIGPIPE killed the credential generator silently**

- **Found during:** Task 1 setup
- **Issue:** `tr -dc … < /dev/urandom | head -c 40` under `set -o pipefail` returns **141**: `head` exits at 40 bytes, `tr` takes SIGPIPE. The generator died before writing anything, and the only signal was a bare exit code.
- **Fix:** bound the **input** (`head -c 4096 /dev/urandom`) and trim with parameter expansion, so no writer is killed mid-pipe.

**5. [Rule 1 - Bug] `--public-network-access` is required TODAY, not in Nov 2026**

- **Found during:** the first Managed Redis create
- **Issue:** `ERROR: (BadRequest) 'properties.publicNetworkAccess' is required in API version 2025-07-01`, while the CLI only warns the argument "will become required in next breaking change release (2.92.0) scheduled for Nov 2026".
- **Fix:** passed explicitly. Also pinned `--access-keys-auth Enabled` rather than inheriting it, because the CLI announces that default **flips to Disabled** in 2.92.0 — the app authenticates with an access key, so inheriting the flip would break every cache connection on a CLI upgrade with nothing in this repo changing.
- **Commit:** `b8a85f15`

**6. [Rule 1 - Bug] `az redisenterprise database create` rejects `-n`**

- **Found during:** the second Managed Redis create — **after the cluster had already been created**
- **Issue:** `ERROR: unrecognized arguments: -n default`. Managed Redis allows exactly one database per cluster and names it `default` itself; `show`/`create` accept only `--cluster-name` and `-g`.
- **Fix:** dropped the name argument from both. The mid-step failure is precisely why the cluster and database are separately guarded check-then-creates rather than one combined `az redisenterprise create`, which the CLI's own help calls "overwrite/recreate, with potential downtime" on a re-run.
- **Verification:** the re-run completed the whole script at **rc=0**.
- **Commit:** `5f0f7018`

### Rule 4 — raised as a checkpoint, then executed on the owner's approval

**7. Azure Cache for Redis cannot be created at all — the recorded 2028 horizon is already binding**

- `az redis create` returns `BadRequest: Azure Cache for Redis is retiring, create Azure Managed Redis instance instead`.
- 29-01 carried this as obligation **O-5**, dated **2028-09-30**, "long horizon, no action this phase". That is falsified: creation is blocked **today**, ~2 years early. A horizon row could not have caught it, because the row's own date said there was nothing to do.
- Priced live: **Azure Managed Redis Balanced B0 = £9.93/mo**, against the blocked Basic C0's £15.48 — the forced move is **£5.55/mo cheaper**.
- Not substituted unilaterally, because it is a different resource provider on **port 10000 rather than 6380**, and `ADR-0002`/`D-09`, `REDIS_SKU` in the decision record, `k8s/staging/configmap-patch.yaml` and the `core-java-allow` NetworkPolicy egress rule all encode the old one. Under the enforcing Cilium dataplane just provisioned, a wrong port silently drops every cache call.
- **The owner approved Balanced B0 including the billable creation**, and the sweep was then executed: `redis.port` `"6380"` → `"10000"`, goldens regenerated, `REDIS_SKU` superseded, O-5 marked FALSIFIED, provisioning STEP 7 rewritten for `az redisenterprise`, and six prose sites corrected where they named the retired service. `redis.ssl` stayed `"true"` and untouched — Managed Redis is TLS-only exactly as Basic was, so 29-02's TLS work carried over whole.
- **INV-7 needed no edit, and that was verified rather than assumed:** its `NETPOL_IPBLOCK_EXPECTED` map uses a `__REDIS_PORT__` substitution, and the gate now reports `redis.port=10000` for staging while base/local/production still report `6379`.
- The goldens diff is **exactly two lines, both staging** — the app-config key and the egress port — which is itself the proof that the kustomize `replacements:` chain still carries the value into the NetworkPolicy. Production rendered byte-identical.

## Two claims I got wrong, and the instruments that caught them

**1. My first fail-direction arm proved the wrong thing, and I kept the record of it.**
To test the port move I deleted the `redis.port` replacement from `k8s/staging/kustomization.yaml`
with a pattern-range `sed`. It mangled the YAML: the render came out **empty** and INV-7 returned
`rc=2` (VOID). Non-zero — and worthless. It proved "INV-7 VOIDs on a broken file", not the claim
under test, which is that INV-7 catches a **silent fallback** to the base `6379`. The broken-file
failure is the easy one; the silent fallback is the dangerous one. Attempt 2 deleted exactly the
ten-line stanza, leaving **valid YAML**: the tree still rendered, app-config still said `10000`, the
egress rule fell back to `6379`, and INV-7 returned **`rc=1`** — the right verdict for the right
reason. Restore verified by `git hash-object`; clean asserted last, golden re-checked.

**3. My leading DNS theory was also wrong, and I tested it rather than repeating it.**
§7.2 named a doubled name (`api-staging.olajay.co.uk.olajay.co.uk`) as the likeliest cause. On the
second attempt I queried those doubled names directly: all four `NXDOMAIN`. Falsified. The real
finding came from a probe I had not thought to run — the zone holds **no A records at all**, for any
name, while serving Zoho MX and SPF TXT quite happily. Guessing a cause twice and testing it twice
cost four `dig` calls; asserting it once would have sent the owner to fix the wrong thing.

**2. My DNS diagnosis was wrong, and a better instrument overturned it.**
When the four names did not resolve, I wrote that the likely cause was the zone's 3600 s
negative-cache TTL masking correctly-created records — "created but not yet visible". A recursive
resolver genuinely cannot separate that from "absent". The zone's **authoritative** nameserver can,
and it says:

```
dig @dns1.p05.nsone.net A app-staging.olajay.co.uk   -> status=NXDOMAIN, flags: qr aa
dig @dns1.p05.nsone.net NS olajay.co.uk              -> status=NOERROR,  4 answers   (control)
```

An `aa`-flagged NXDOMAIN from the zone's own nameserver is not a caching artefact — the records are
not in the zone. The control is what licenses that reading: the same server returns data for a name
that does exist. The speculation is left in the evidence file next to its refutation rather than
edited out, because "propagation delay" is a comfortable answer that would have had the next plan
waiting instead of checking.

## Corrections to inherited records

**The snackpass before-state in 29-OPERATOR-DECISIONS.md §2.2 was stale.** It records all six apps at `minReplicas: 1` (measured 20:43:40Z). At 22:09Z four already read `0`. The Activity Log shows exactly four `Microsoft.App/containerApps/write` operations at ~21:18Z by the owner's identity — between the two plans. So this plan changed **two** apps, not six, and the record's table is a correct reading of a state that has since moved. Recorded rather than smoothed over: the after-state looks identical either way, so an executor trusting §2.2 would have reported six changes truthfully-sounding and wrongly.

**The plan's own `<verify>` command has a silent false negative.** It reads `oidcIssuerProfile.issuerURL`, which returns `null` — the field is `issuerUrl`, and JMESPath is case-sensitive. Run verbatim on a correctly-configured cluster, the criterion reports `"oidc": null`, which reads like a provisioning failure. The correct value was obtained via `--query 'oidcIssuerProfile'`.

## Issues Encountered

**A transient double-count nearly became a false cost finding.** Immediately after `az containerapp update`, `snackpass-redis` showed **two** active revisions with 1 replica each — the change appeared to *double* replicas. The app is `activeRevisionsMode: Single`, so the old revision deactivated on its own moments later. Measuring once, at the wrong moment, would have recorded a cost increase caused by a cost-reduction change.

**The 300 s cooldown makes an early reading wrong, not the mechanism.** Three minutes after the change both newly-scaled apps still showed 1 replica; after the cooldown both read 0. Both readings are in the evidence file, because the middle one is the one that would have been reported as a half-failure.

**A positive control failed and was replaced rather than dropped.** `olajay.co.uk` (apex) was probed as a DNS control and returned empty — it has no apex A record. Had it been the only control, every empty staging answer would have been discarded as "resolver broken". `one.one.one.one` resolves on both resolvers and is the valid control.

**Base-env Python is blocked on this host** (same guard 29-01 hit); `jq` and `awk` were used for the price arithmetic instead.

## Round 2: both "it's fixed" reports were checked, and both were still open

The owner reported DNS fixed and secrets filled. Both were re-measured rather than taken on trust,
and both were still outstanding — reported here precisely because "the owner says it's done" is the
easiest possible place to stop measuring.

| Reported | Re-measured | How the negative was made trustworthy |
|---|---|---|
| DNS records re-entered and saved | all four still `aa`-NXDOMAIN | control (`NS olajay.co.uk`) returns NOERROR + 4 answers from the same server |
| Seven secrets filled | `populated=0 empty=7` | a filled scratch copy reports `7 of 7`, so the checker can see values; mtime + byte size identical to creation, so the file was never edited |

Neither result is a criticism of the operator — the DNS probe in particular shows the edits are
going somewhere real, just not to the zone being served. What matters is that neither was recorded
as done on the strength of the report.

## A pattern worth naming: three constraints that bind before their documented date

This plan hit the same shape three times, and the usual defence — read the docs, note the date, move
on — failed against all three:

| Thing | What the documentation said | What was measured |
|---|---|---|
| Azure Cache for Redis Basic | retires **2028-09-30** (29-01 obligation O-5, "no action this phase") | `az redis create` **refuses today** |
| `--public-access None` (Postgres) | "sets the server in public access mode but does not create a firewall rule" | server came back `publicNetworkAccess: Disabled` |
| `--public-network-access` (Managed Redis) | "will become required in next breaking change release (2.92.0), **Nov 2026**" | API **already** rejects without it |

A dated horizon row is re-read on its **own expiry**, so a row whose reason becomes false early
survives unexamined — O-5's date said there was nothing to check. The generalisation, consistent
with this repo's recorded traps: a deferral must be re-measured against the world, not re-read
against the calendar.

## Known Stubs

None in code. What has **not** happened:

- **No database role exists on `jtoye-staging-pg`.** `rolbypassrls` has not been read from `pg_roles` on that server. The precondition (PG16) is met; the verification is not.
- **No Kubernetes workload is deployed.** No namespace, no Secret, no platform component.
- **Nothing has connected to the cache.** The resource exists and its port matches the render; that is not the same as a working round-trip. See the OSSCluster flag below.
- **No DNS record exists in the zone** — confirmed at the authoritative nameserver, not merely unresolved.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: credential-disclosure | `scripts/azure-staging-provision.sh` | The CLI's own output disclosed the DB administrator password on the real path — a surface the dry-run threat analysis could not see. Fixed (`--output none`) and the value rotated, but the class is worth carrying: **any** `az … create` that returns a secret-bearing field has the same shape, and only this one has been audited. |
| threat_flag: internet-reachable-datastore | `jtoye-staging-pg` | `publicNetworkAccess: Enabled` is now required for the firewall-rule model to work at all. Exposure is one IP (`20.26.28.17`), verified, but the server is on the public internet and its only wall is that rule. |
| threat_flag: internet-reachable-datastore | `jtoye-staging-redis` | Managed Redis was created with `publicNetworkAccess: Enabled` (the API requires the flag and Disabled would need a private endpoint, which is not in this phase's budget or scope). Unlike Postgres it has **no IP allow-list configured** — its wall today is TLS 1.2 plus the access key. Narrowing it is a follow-up worth carrying, not something this plan closed. |
| flag: untested-compatibility | `jtoye-staging-redis` | `clusteringPolicy: OSSCluster` (Azure's default) against a **standalone** Lettuce client. Single-shard B0 should not emit `MOVED` redirects — untested. 29-11 must accept on a real round-trip, not on the resource existing; remedy if it fails is `EnterpriseCluster`. |

## Cross-Cutting Quality Contracts

- **Web performance** — N/A (no user-facing page touched).
- **SEO / discoverability** — N/A (no public surface; deliberately, D-08 keeps production names unresolvable).
- **AI agent-readiness** — N/A (no API surface).
- **Security** — the two threat flags above; T-29-10-01 (narrow firewall) and T-29-10-04 (never the employer's subscription/context) mitigated and evidenced. T-29-10-02/03 (BYPASSRLS posture) are **not yet provable** — Task 2 is blocked.
- **Falsifiable evidence + runtime parity** — **(a)** the credential scan was proven able to match a planted canary *before* its 0-hits result was trusted; the log scrub was measured 2→0; the narrow firewall rule is shown against a wide one; the DNS empties are backed by a control that resolves, and a control that failed is recorded. **(b)** Runtime parity: this plan builds and deploys no artifact — it creates cloud resources and reads them back. `check-runtime-freshness.sh` is deliberately not run from a worktree (the directory name changes the compose project name and would VOID it).

## Next Phase Readiness

**Task 1 is COMPLETE.** The estate exists and the provisioning script runs clean end-to-end at
`rc=0`. Two blockers remain, both operator-only. Details in `29-PROVISIONING-EVIDENCE.md` §7.2, §9.

1. **Task 2 is blocked ENTIRELY, and that is measured rather than assumed.** `scripts/staging-secrets.sh` runs its 23-name value preflight at STEP 3 (lines 223–280), **before** the `case "$MODE"` dispatch and **not** gated by it — so `--roles-only` and even read-only `--verify-roles-only` refuse until all 23 variables are present. Seven are operator-only, and after the values were reported filled they measured **`populated=0 empty=7`** again. That negative was falsified first: a filled scratch copy reports `7 of 7`, so the checker is not blind; and the file's mtime (`00:11:09`) and size (1766 B) are byte-identical to creation, so it was never edited. No other `*.env` under `$HOME` was modified either.
2. **Task 3: the four A records are absent, and the cause is now diagnosed.** The zone that actually serves `olajay.co.uk` — delegation confirmed at the `.co.uk` registry — holds **zero A records anywhere**: not the four staging names, not their doubled variants (that theory was tested and falsified), not `www`/`mail`/`staging`/`test`, and the apex returns NODATA for A. It does serve Zoho MX and an SPF TXT. So the edits are reaching a different zone from the one being served.

**Re-check for Task 3** — one query, owes nothing to propagation:
`dig @dns1.p05.nsone.net A api-staging.olajay.co.uk` (expect `20.58.10.18`).

**Discriminator for finding the right zone, no tooling needed:** the serving zone contains the Zoho
MX records (`mx.zoho.eu`, `mx2.zoho.eu`, `mx3.zoho.eu`) and `v=spf1 include:zoho.eu ~all`. A panel
that does not show those is the wrong zone, and records added there will never take effect.

**Second, independent Task 2 blocker:** the Postgres firewall admits only the AKS egress IP, so
`psql` from this host is refused. The role bootstrap must run from inside the cluster, or a
temporary operator-IP rule must be added and then removed.

A second, independent Task 2 blocker: the server's only firewall rule admits the AKS egress IP, so `psql` from this host is refused. The role bootstrap must run from inside the cluster, or a temporary operator-IP rule must be added and removed.

**The administrator credential is the only copy** and lives at `~/.jtoye/staging-admin.env` (0600, outside the repo). It should be moved into the operator's password manager.

**Two follow-ups this plan cannot close:** obligation **O-1** (re-measure the Container Apps idle meters ~48 h out — replica count and meter are different instruments) and assumption **A8** (the Free control plane is inferred from a price list, not seen on an invoice; the cluster is hours old).

## Self-Check: PASSED

- **Files exist:** `29-PROVISIONING-EVIDENCE.md` and `scripts/azure-staging-provision.sh` both present; `bash -n` on the script → rc=0.
- **Commits exist:** `0776b3c1`, `b8a85f15`, `5f0f7018` plus the `e9e39c19` merge — all on `worktree-agent-acedd037d6b3648d0`, none on a protected ref.
- **No deletions:** `git diff --diff-filter=D` empty for each commit.
- **`must_haves` contains-assertion:** `rolbypassrls` present in the evidence file (§9.0), recorded as the **NOT YET RUN** contract with its expected attributes — not as a satisfied claim.
- **`key_links` pattern:** `app-staging.olajay.co.uk` present (§7).
- **No credential in any tracked artifact:** canary arm matched first (instrument proven able to fail), then 0 hits across 2106 tracked files.
- **Gates green on the final tree:** `check-render-invariants.sh` rc=0 across all 4 targets; `render-golden.sh` rc=0 (renders match committed goldens).
- **Merge ordering honoured:** `e9e39c19` (29-06's monitoring manifests + regenerated goldens) merged conflict-free **before** any edit, so the goldens regenerated here build on 29-06's rather than colliding with them.
- **Shared orchestrator artifacts untouched:** `STATE.md` and `ROADMAP.md` are not in this plan's diff. `infra/dependency-horizons.yaml` deliberately untouched — 29-09 owns it (it did change in the merge, as 29-06's content, not by my edit).

---
*Phase: 29-deployable-staging-with-its-own-monitoring*
*Status: CHECKPOINT — awaiting a Redis decision and two operator credential sets*
