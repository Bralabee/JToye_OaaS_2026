# Phase 29 — Operator decisions, credential state and re-measured facts

Owner: the repository owner (`admin@jtoyedigital.co.uk`, tenant `JToye Digital`). Written by plan
29-01, 2026-08-10. Every owner answer below was given that day through the `/gsd:execute-phase`
orchestrator's `AskUserQuestion` gate, with auto-mode **off** (`workflow.auto_advance=false`, read
via `gsd-sdk query config-get`) — so these are genuine human selections, not auto-approvals.

This file exists so that **no downstream plan assumes a value it did not create or was not given.**
Every key carries the date and the command output that established it. Items that are absent are
named as ABSENT with what they block — never omitted, because an omitted blocker reads identically
to a satisfied one.

> **GLOBAL_RULE_6 — no literal credential value appears in this file, in any commit message, or in
> any tracked artifact.** This file records only the *fact* that a credential exists and *where it
> lives*. Values flow through the machine-local, gitignored `.env` and the k8s secret layer, along
> the path `docs/runbooks/credential-rotation.md` already defines.

> ⚠ **`Prod - HS2 Ltd` is EMPLOYER infrastructure — never touch it.** Every `az` invocation
> recorded here passed `--subscription c483d353-5f61-4587-a790-addb9ab5fb94` explicitly
> (threat T-29-01-01). The only kube context on this host is `sipbihs2aks`, which is also the
> employer's; no `kubectl` was run.

---

## 1. Decision keys

Downstream plans grep for these exact key names. All 10 are defined.

| Key | Value | Established by |
|---|---|---|
| `AZURE_SUBSCRIPTION_ID` | `c483d353-5f61-4587-a790-addb9ab5fb94` | `az account show` 2026-08-10 → `Azure subscription 1`, tenant `b56df236…` (`JToye Digital`), state Enabled |
| `AZURE_RESOURCE_GROUP` | `jtoye-rg` | `az resource list -g jtoye-rg` 2026-08-10 returned 10 resources (below) |
| `AZURE_LOCATION` | `uksouth` | every `snackpass-*` resource reports `uksouth`; the RG itself is uksouth |
| `AKS_CLUSTER_NAME` | `jtoye-staging-aks` | fixed by 29-01 `<interfaces>`; no cluster exists in the subscription yet |
| `NODE_VM_SIZE` | `Standard_B2s` | **owner decision 2026-08-10** (see §3, Q2) |
| `NODE_COUNT` | `3` | **owner decision 2026-08-10** (see §3, Q2) |
| `PG_SERVER_VERSION` | `16` | **NOT optional** — Blocker C. See §5 |
| `PG_SERVER_SKU` | `Standard_B2s` Burstable | implied by the owner accepting the £147.00 costed estate unchanged (§3, Q2). **B1ms is ruled out** — see §5 |
| `PG_ACCESS_MODE` | `public-with-firewall` | research default; read by 29-04 Task 1. The firewall rule must be scoped to the AKS egress IP — the wide rule on `snackpass-pg` is explicitly NOT the shape to copy (29-10 T-29-10-01) |
| `REDIS_SKU` | Azure Cache for Redis `Basic C0` | ADR-0002 / D-09; £15.48/mo line of the costed estate |
| `SNACKPASS_DISPOSITION` | `scale-to-zero` | **owner decision 2026-08-10** (see §3, Q1) |
| `MONTHLY_CEILING_GBP` | `150` | D-03 **unchanged** — the owner did not supersede it (see §3, Q2) |

---

## 2. The re-measured estate (2026-08-10T20:43:40Z)

The research figures were dated 2026-08-10 and the research itself said to re-measure cost before
relying on it. This is the re-measurement, not the research quoted back.

### 2.1 `az resource list -g jtoye-rg` — 10 resources

```
Name                     Location    Type
-----------------------  ----------  -----------------------------------------
jtoye-bootcamp           westeurope  Microsoft.Web/staticSites
snackpass-logs           uksouth     Microsoft.OperationalInsights/workspaces
snackpass-env            uksouth     Microsoft.App/managedEnvironments
snackpass-pg             uksouth     Microsoft.DBforPostgreSQL/flexibleServers
snackpass-redis          uksouth     Microsoft.App/containerApps
snackpass-minio          uksouth     Microsoft.App/containerApps
snackpass-python-vision  uksouth     Microsoft.App/containerApps
snackpass-java-core      uksouth     Microsoft.App/containerApps
snackpass-go-edge        uksouth     Microsoft.App/containerApps
snackpass-webapp         uksouth     Microsoft.App/containerApps
```

**The research's inventory was incomplete: `jtoye-bootcamp` was not in it.** It is a **Free**-tier
Static Web App in West Europe backed by `github.com/Bralabee/jtoye-bootcamp-site` — £0, unrelated
to this platform, and **not** part of the snackpass disposition. Recorded so that a future cleanup
does not encounter it as a surprise and does not sweep it up by resource-group scope.

### 2.2 Container Apps — BEFORE state (the baseline the disposition is measured against)

```
Name                     minReplicas  maxReplicas  Status   cpu   mem
snackpass-redis                    1            1  Running  0.25  0.5Gi
snackpass-minio                    1            1  Running  0.5   1Gi
snackpass-python-vision            1            3  Running  1.0   2Gi
snackpass-java-core                1            3  Running  1.0   2Gi
snackpass-go-edge                  1            5  Running  0.5   1Gi
snackpass-webapp                   1            1  Running  1.0   2Gi
```

**AFTER state: not applied by this plan.** 29-01 Task 1 says in terms *"Create, delete or scale NO
Azure resource in this task"*, and plan **29-10** owns execution (*"Execute the snackpass
disposition recorded in `29-OPERATOR-DECISIONS.md` FIRST — before creating…"*). The six
`minReplicas: 1` values above are the **before** reading 29-10 must record its **after** against.

### 2.3 Cost Management, MonthToDate, GBP

```
Azure Container Apps            30.0885713210671   GBP
Azure Database for PostgreSQL   0.0                GBP
Log Analytics                   0.0                GBP
```

Daily granularity — the breakdown the research did not take, and the one that corrects it:

```
2026-08-01  1.6925   <- partial      2026-08-06  3.3348
2026-08-02  3.3179                   2026-08-07  3.3159
2026-08-03  3.3166                   2026-08-08  3.3173
2026-08-04  3.3173                   2026-08-09  3.3159
2026-08-05  3.3159                   2026-08-10  1.8442   <- partial (mid-day)
```

Steady state over the 8 full days (2–9 Aug): **£3.3190/day** (min 3.3159, max 3.3348).

### 2.4 The arithmetic

```
RUN-RATE (measured)   GBP 101.02/mo    (3.3190/day x 30.4375)
NEW ESTATE (costed)   GBP 147.00/mo
TOTAL                 GBP 248.02/mo
CEILING (D-03)        GBP 150.00/mo
OVERRUN               GBP  98.02/mo    (1.65x the ceiling)
```

**The research understated the run-rate.** It said ≈£3.17/day → ≈£95/mo; the measured steady state
is £3.3190/day → **£101.02/mo**, about £6/month more. The new-estate side spot-checks clean: a live
pull of the Azure retail price API (`prices.azure.com`, `currencyCode=GBP`, `armRegionName=uksouth`)
returns `Standard_B2s` Linux at **£0.0358/hr**, so 3 × 0.0358 × 730 = **£78.40** — matching the
research's node line exactly.

With the snackpass spend removed: `0 + 147.00 = £147.00` against a £150.00 ceiling —
**£3.00/month of headroom.** Thin, and knowingly accepted (§3, Q2).

---

## 3. Owner decisions, recorded verbatim

Answered 2026-08-10 via the orchestrator's `AskUserQuestion` gate, auto-mode off.

### Q1 — What happens to the live `snackpass-*` estate?

> **`scale-to-zero`** — the recommended option. Set `minReplicas: 0` on all six snackpass Container
> Apps, keep `snackpass-pg` (free tier). The owner accepted the F1 caveat that this is a mechanism
> claim: the honest verification is re-measuring the two idle meters ~48h after the change, and if
> they have not dropped the estate must be revisited.

`SNACKPASS_DISPOSITION = scale-to-zero`. Reversible; destroys nothing. **This is not a supersession
of D-03** — the ceiling holds.

### Q2 — Node pool / headroom

> **Keep 3× B2s** (£147.00 total, ~£3.00/month headroom accepted). No re-sizing, no replanning of
> connection math or scale patches.

`NODE_VM_SIZE = Standard_B2s`, `NODE_COUNT = 3`, `MONTHLY_CEILING_GBP = 150` (unchanged). The
alternatives offered and declined: 2 × B2ms £104.39 (more RAM, *less* CPU — 3.8 vCPU allocatable vs
5.7), 2 × D2as_v5 £110.67, 2 × D2s_v3 £128.33.

### Q3 — The `snackpass-pg` free-window expiry

> **Yes, record it** as a dated horizon row — ~2027-07-21 (creation 2026-07-21 + 12 months) until
> the exact date can be confirmed from the portal, ~£21/month B1ms impact when it closes. It
> breaches the ceiling with no deploy, so it must be foreseen rather than discovered on an invoice.

Carried as obligation **O-2** in §6.

---

## 4. Three findings that corrected the plan's own premises

### F1 — 99.88% of the Container Apps spend is IDLE, not active

Cost Management grouped by Meter, filtered to `Azure Container Apps`, MonthToDate:

```
Standard Memory Idle Usage     GBP 20.2097   (6,713,908 GiB-s)
Standard vCPU Idle Usage       GBP 10.1048   (3,356,937 vCPU-s)
Standard Memory Active Usage   GBP  0.0070   (    2,335)
Standard vCPU Active Usage     GBP  0.0299   (    1,167)
```

The estate serves essentially nothing — 1,167 vCPU-seconds of active usage across six apps over ten
days is consistent with health probes and no real traffic. Idle metering accrues against **running
replicas**, so `minReplicas: 0` should collapse nearly the whole £101.02. This made `scale-to-zero`
materially stronger than the option table's stated con ("Container Apps still bills some idle").

**This is a mechanism claim, not a measurement.** It is a statement about the future and must not be
recorded as proven. See obligation **O-1** in §6.

### F2 — assumption A3 is RESOLVED by measurement: the £0.00 is a free-tier offer

The same meter query filtered to `Azure Database for PostgreSQL` returns meters named literally:

```
B1MS Compute - Free          231.0 hrs      GBP 0.00
Storage Data Stored - Free     9.94 GB-mo   GBP 0.00
```

So the zero is **zero-rated usage, not deferred billing** — A3 is settled by a command rather than a
portal look-up. Three consequences:

- The allowance is **B1ms-shaped**. The staging server is `Standard_B2s`, charged in full — the
  £42.05 line of the costed estate stands. The free tier **cannot** be redirected to staging,
  because Blocker C's connection math rules B1ms out (50 total / 35 user connections against a
  155-connection budget — short by a factor of 3).
- Keeping `snackpass-pg` does **not** subsidise staging in any way.
- The free window closes ~12 months after the server was created. `az postgres flexible-server show`
  gives `createdAt: 2026-07-21T15:27:43+00:00`, so **~2027-07-21**, at which point `snackpass-pg`
  begins billing at roughly £21/month for B1ms. Obligation **O-2**.

### F3 — the estate is NOT a prior deployment of J'Toye

The research inferred from the app names that this was "a prior Container Apps deployment of THIS
platform", and that inference framed all three options. The image references refute it:

```
snackpass-java-core      ghcr.io/bralabee/snackpass-java-core:deploy
snackpass-go-edge        ghcr.io/bralabee/snackpass-go-edge:deploy
snackpass-webapp         ghcr.io/bralabee/snackpass-webapp:deploy
snackpass-python-vision  ghcr.io/bralabee/snackpass-python-vision:deploy
snackpass-redis          redis:7-alpine
snackpass-minio          minio/minio:RELEASE.2025-09-07T16-13-09Z
```

These are a separate `snackpass` codebase with its own GHCR packages — including a `python-vision`
service J'Toye has never had (this platform uses Ollama for image analysis, and its services are
`core-java` / `edge-go` / `frontend` / `mcp-server`). J'Toye's own images are `jtoye-core-java`,
`jtoye-edge-go`, `jtoye-frontend`. **Deleting this would have destroyed a different project's
deployment, not a redundant copy of this one** — which is precisely why assumption A4 was the
owner's to answer, and why reversibility carried the decision.

Supporting detail on what is actually at stake:

- `snackpass-pg` holds a user database named `snackpass` beside the three Azure system databases
  (`azure_maintenance`, `postgres`, `azure_sys`). Whether it holds rows is **not** readable without
  DB credentials and a firewall rule; neither was attempted.
- `snackpass-minio` has **no volumes and no volumeMounts** — ephemeral container storage only. Any
  objects it holds are already lost on every restart or revision change, so the object store is not
  durable state today. `scale-to-zero` therefore destroys nothing there that was not already
  volatile.
- Revision names (`snackpass-go-edge--pr48-173431`, `snackpass-webapp--fix1784830503`) indicate
  CI-driven deploys at some point in the past.

---

## 5. `PG_SERVER_VERSION = 16` is a requirement, not a preference

`infra/backups/create-backup-role.sql` runs `CREATE ROLE jtoye_backup LOGIN BYPASSRLS`, and its own
header notes that BYPASSRLS can only be granted by a superuser. On Azure Flexible Server the admin
login `azure_pg_admin` is a **pseudo**-superuser; Microsoft holds the real one. On **PostgreSQL 15
and earlier you cannot create non-admin users with BYPASSRLS**; **PostgreSQL 16 removed the
superuser requirement**, so `azure_pg_admin` can create such roles there.

Without `jtoye_backup`, the logical dump runs as a role subject to FORCE RLS and captures **zero
rows from every tenant-scoped table** — a green backup over an empty database, which is exactly the
defect DPLY-04's arm A exists to catch.

This is corroborated in-place: the pre-existing `snackpass-pg` in this same resource group already
reports `version: 16`.

**This is a deliberate, staging-only deviation** from `CLAUDE.md`'s "PostgreSQL 15" tech-stack line
and from the `postgres:15-alpine` pin that `infra/dependency-horizons.yaml` carries for compose. It
is written down here and in ADR-0002 rather than slipped in behind a SKU choice. Obligation **O-3**.

Two further measured constraints on the same server shape:

- `azure.extensions` on the live `snackpass-pg` reads `vector,pgcrypto`. `V1__base_schema.sql:6`
  runs `CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`. **`uuid-ossp` must be added to the
  `azure.extensions` allowlist before the first Flyway run**, or V1 fails and nothing else runs.
  (Plan 29-10 already carries this; do not copy `snackpass-pg`'s value verbatim.)
- Azure reserves **15** connections for replication/monitoring, while
  `k8s/scripts/check-connection-math.sh` assumes `RESERVED=3`. On B2s (429 total / 414 user) the
  155-connection budget still fits; on B1ms (50/35) it does not.

---

## 6. Follow-up obligations created by these decisions

| # | Obligation | Owner plan | Why it cannot be skipped |
|---|---|---|---|
| **O-1** | ~48h after 29-10 applies `minReplicas: 0`, re-run the Container Apps meter query and confirm `Standard vCPU Idle Usage` and `Standard Memory Idle Usage` have dropped toward zero. If they have not, `scale-to-zero` did not work and the estate must be revisited. | 29-10 (apply) → a later verification | F1 is a mechanism claim about the future. Recording it as proven would be exactly the "structural green over a dead feature" failure — the config would read `minReplicas: 0` while the meters kept billing. |
| **O-2** | Land a dated horizon row for the `snackpass-pg` free-window expiry: **~2027-07-21** (creation 2026-07-21 + 12 months), impact ~£21/month B1ms, **exact date to be confirmed from Azure Portal → Cost Management → Credits + offers**. The *reason* is already written into ADR-0002's 2026-08-10 section, so it survives independently of the row. | **29-09** (it already edits `infra/dependency-horizons.yaml` for the `rabbitmq-k8s` row — 29-01 deliberately does not touch that file, to avoid a parallel-wave conflict) | It breaches the £150 ceiling with **no deploy and no code change**. Unrecorded, it is discovered on an invoice. |
| **O-3** | ~~Carry the PG16-vs-PG15 skew in ADR-0002 with its evidence, naming both the `CLAUDE.md` PostgreSQL 15 line and the `postgres:15-alpine` compose pin it diverges from.~~ **DONE 2026-08-10** — ADR-0002 § "2026-08-10 — Signed…", subsection "PostgreSQL **16** is a requirement of this decision, not a preference". | 29-01 Task 3 ✓ | A version skew that lives only in a SKU argument is indistinguishable from an accident. |
| **O-5** | Land a horizon row for the **Azure Cache for Redis Basic retirement, 2028-09-30** (Enterprise 2027-03-30). Recorded in ADR-0002's 2026-08-10 section; long horizon, no action this phase. | 29-09 | Accepting a managed service means accepting its retirement clock. |
| **O-4** | 29-10 must record the **after** replica state for all six apps against the **before** table in §2.2. | 29-10 | "The disposition was applied" is not observable without both readings. |

---

## 7. Credential presence

Measured 2026-08-10 by 29-01 Task 2. Two items are machine-measurable and were measured; two
require the operator and are recorded ABSENT-pending-operator rather than assumed either way.

| Key | State | Established by |
|---|---|---|
| `GHCR_VISIBILITY` | **PRESENT** — all three packages PUBLIC, no `imagePullSecret` needed | §7.1 (anonymous registry probe, falsified) |
| `AWS_CREDENTIALS` | **ABSENT** | §7.2 — `aws sts get-caller-identity` → "Unable to locate credentials" |
| `GMAIL_APP_PASSWORD` | **ABSENT** | §7.3 — not present on this host; only the operator can supply it |
| `GRAFANA_ADMIN_PASSWORD` | **SELF-SUPPLIABLE — not an operator blocker** | §7.4 |
| `NETLIFY_DNS_ACCESS` | **UNCONFIRMED** (zone state measured; portal access is not machine-checkable) | §7.5 |

### 7.1 `GHCR_VISIBILITY` — assumption A5 CONFIRMED by measurement (2026-08-10)

Anonymous registry probe, no `docker login` and no PAT. A public package yields an anonymous token
that authorises `/v2/<repo>/tags/list` (HTTP 200); a package that is not anonymously accessible
yields no token at all.

```
bralabee/jtoye-core-java: PUBLIC (anon tags/list HTTP 200)
bralabee/jtoye-edge-go:   PUBLIC (anon tags/list HTTP 200)
bralabee/jtoye-frontend:  PUBLIC (anon tags/list HTTP 200)
```

**The check was shown able to fail, and the first control used was not good enough — recorded
because the weak control is the interesting part.** Probing a nonexistent repo
(`bralabee/definitely-not-a-real-package-29-01`) returns `NO-TOKEN`, which only distinguishes public
from *absent* — it says nothing about private. The valid negative control is the `snackpass-*`
packages: those provably **exist** (Container Apps is pulling them right now, with a `ghcr.io`
registry credential configured) and they also return `NO-TOKEN`:

```
=== negative control: existing but NOT anonymously pullable ===
bralabee/snackpass-java-core:     NO-TOKEN
bralabee/snackpass-go-edge:       NO-TOKEN
bralabee/snackpass-webapp:        NO-TOKEN
bralabee/snackpass-python-vision: NO-TOKEN

=== positive control: known-public repo ===
homebrew/core/jq: PUBLIC (anon tags/list HTTP 200)
```

So an existing-but-private package reads not-public, an existing-public one reads 200, and the three
`jtoye-*` targets read 200. **Assumption A5 holds: no `imagePullSecret` is required.**

### 7.2 `AWS_CREDENTIALS` — ABSENT (2026-08-10)

```
$ aws sts get-caller-identity
Unable to locate credentials. You can configure credentials by running "aws configure".
```

The `aws` CLI itself is present (1.45.46); only the credentials are missing. **Nothing about the
`jtoye-images` bucket could therefore be verified** — not its existence, not its region, not its
policy shape. The four facts #294 demands are all still unmeasured; see §8.

### 7.3 `GMAIL_APP_PASSWORD` — ABSENT (2026-08-10)

Not present on this host, and not machine-obtainable: an app password is minted by a human in
Google Account → Security → 2-Step Verification → App passwords. Per GLOBAL_RULE_6 the value must
never enter this file, a commit message or any tracked artifact — only the fact that it exists and
that it reached the secret layer. The `From` and `To` addresses are needed alongside it.

### 7.4 `GRAFANA_ADMIN_PASSWORD` — self-suppliable, not an operator blocker

D-19 routes the Grafana admin credential through `docs/runbooks/credential-rotation.md`, whose
established pattern is to **generate** values with the system CSPRNG (`openssl rand`) and read them
back by identity rather than by printing. So this is not an input the operator must find — it is
generated at deploy time by the plan that stands Grafana up. Recorded here so that a later reader
does not mistake its absence today for a missing prerequisite. It is deliberately **not** one of
Task 2's four items.

### 7.5 `NETLIFY_DNS_ACCESS` — zone measured, portal access UNCONFIRMED (2026-08-10T21:14:46Z)

The zone state is machine-measurable and was measured. Whether the owner can *log in and add
records* is not, and is the only part still open.

```
=== zone delegation ===
NS : dns1.p05.nsone.net. dns2.p05.nsone.net. dns3.p05.nsone.net. dns4.p05.nsone.net.
SOA: dns1.p05.nsone.net. domains+netlify.netlify.com. 1634401895 43200 7200 1209600 3600

=== the four staging hostnames (expected empty today) ===
  api-staging.olajay.co.uk     -> (empty)
  app-staging.olajay.co.uk     -> (empty)
  auth-staging.olajay.co.uk    -> (empty)
  grafana-staging.olajay.co.uk -> (empty)

=== production hostnames (D-08: must stay unresolvable until Phase 32) ===
  api.olajay.co.uk -> (empty)  OK
  app.olajay.co.uk -> (empty)  OK

=== POSITIVE CONTROL ===
  one.one.one.one -> 1.0.0.1 / 1.1.1.1   (resolver works, so the empties above are real)
```

**The control is what makes the empties mean anything.** An empty `dig` answer is also what a broken
resolver returns, so "no A records" and "DNS is not working here" are otherwise indistinguishable.
The control resolves, so the empties are genuine absences. D-05 is re-confirmed (zone live at
NS1/Netlify, zero A records) and D-08 is currently satisfied — nothing looks live that is not.

---

## 8. What is still ABSENT and what it blocks

Nothing here is omitted or softened: an unrecorded blocker reads exactly like a satisfied one.

| Item | State | Blocks | Measurement |
|---|---|---|---|
| AWS credentials (eu-west-2) | **ABSENT** | **29-13 / #294 bucket verification (D-11)** and the D-12 backup bucket. Note D-11 requires this **before first deploy**, so an unresolved ABSENT here is a scope decision, not a scheduling one | `aws sts get-caller-identity` → "Unable to locate credentials" (§7.2) |
| #294's four bucket facts | **UNMEASURED** (blocked by the above) | 29-13. The four are: bucket exists; region is eu-west-2; derivative prefix is public-read; **quarantine prefix is NOT public** — the last is a security check, since a public quarantine prefix is a stored-XSS primitive on the storefront's own origin | cannot run without credentials |
| D-12 dedicated backup bucket | **UNKNOWN** — neither confirmed to exist nor confirmed absent | 29-13's restore drill has nowhere to upload | cannot run without credentials |
| Gmail SMTP app password + From/To | **ABSENT** | 29-07, 29-12 (D-17 — "alerts a human" is the phase's entire point) | not present on this host (§7.3) |
| Netlify DNS portal access | **UNCONFIRMED** | 29-10 record creation, and therefore Let's Encrypt HTTP-01 issuance | zone measured live, access not machine-checkable (§7.5) |
| DNS A records for `*-staging.olajay.co.uk` | **ABSENT** (expected today) | 29-10, HTTP-01 | all four empty against a working resolver (§7.5) |
| An AKS cluster | **ABSENT** | everything downstream | no cluster in the subscription |
| `Microsoft.ContainerService` / `Microsoft.Cache` / `Microsoft.Network` | **NotRegistered** | 29-05 provisioning | **has a fallback** — `az provider register`, idempotent and free. Not a blocker |

---

*Written by plan 29-01, 2026-08-10. Decisions §3 are the owner's, given through the orchestrator's
`AskUserQuestion` gate with auto-mode off. §7 and §8 record Task 2's measured half; the two
operator-supplied credentials remain open at the time of writing.*
