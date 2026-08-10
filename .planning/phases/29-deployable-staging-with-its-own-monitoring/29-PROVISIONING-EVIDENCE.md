# Phase 29 — Staging provisioning evidence

Written by plan **29-10**, 2026-08-10. Every value below was read off the **running resource**, not
off the command that was supposed to create it. Each carries the command and the timestamp that
established it.

> **GLOBAL_RULE_6 — no literal credential value appears in this file.** Where a credential exists,
> this file records only that it exists and *where it lives*.

> ⚠ **`Prod - HS2 Ltd` (`8d1c4578-…`) is EMPLOYER infrastructure.** It is the **ambient default**
> subscription on this host — measured again at the top of this run — so every `az` invocation
> recorded here passed `--subscription c483d353-5f61-4587-a790-addb9ab5fb94` explicitly. The only
> pre-existing kube context on this host is `sipbihs2aks`, also the employer's; no command in this
> plan targeted it.

---

## 0. Subscription identity (the guard that makes everything below trustworthy)

```
$ az account show --subscription c483d353-5f61-4587-a790-addb9ab5fb94 \
    --query '{id:id,name:name,state:state}' -o tsv          # 2026-08-10T22:09Z
c483d353-5f61-4587-a790-addb9ab5fb94   Azure subscription 1   Enabled

$ az account show --query '{id:id,name:name}' -o tsv        # the AMBIENT default
8d1c4578-4129-40d5-a6be-fd24d96b7959   Prod - HS2 Ltd        <- EMPLOYER, never targeted
```

`AZURE_SUBSCRIPTION_ID` matches the decision record's value. The two ids differ, which is the whole
reason the provisioning script pins every call rather than trusting the ambient one.

---

## 1. The snackpass disposition (obligation O-4) — applied, with a correction to its baseline

`SNACKPASS_DISPOSITION = scale-to-zero` (29-OPERATOR-DECISIONS.md §3 Q1). The plan requires this to
run **FIRST**, before anything billable is created, so the ceiling is honoured rather than breached
and then apologised for.

### 1.1 The recorded before-state was STALE — measured, not assumed

29-OPERATOR-DECISIONS.md §2.2 records all six apps at `minReplicas: 1`, measured
2026-08-10T20:43:40Z. **That is not what this plan found at 22:09Z.** Read back off the running
resources:

```
$ az containerapp list -g jtoye-rg --subscription c483d353-… \
    --query "[].{name:name,min:properties.template.scale.minReplicas,\
                 max:properties.template.scale.maxReplicas}" -o table    # 2026-08-10T22:09Z

Name                     Min    Max
-----------------------  -----  -----
snackpass-redis            1      1     <- matches the record
snackpass-minio            1      1     <- matches the record
snackpass-python-vision    0      3     <- ALREADY 0, record says 1
snackpass-java-core        0      3     <- ALREADY 0, record says 1
snackpass-go-edge          0      5     <- ALREADY 0, record says 1
snackpass-webapp           0      1     <- ALREADY 0, record says 1
```

Confirmed against the raw `properties.template.scale` JSON, not just the table rendering, so this is
not a null-vs-zero display artefact.

**The Activity Log explains it, and the explanation is not "the record was wrong":**

```
$ az monitor activity-log list -g jtoye-rg --subscription c483d353-… \
    --start-time 2026-08-10T18:00:00Z \
    --query "[].{time:eventTimestamp,op:operationName.value,caller:caller,status:status.value}" -o table

2026-08-10T21:18:50Z  Microsoft.App/containerApps/write  admin@jtoyedigital.co.uk  Accepted
2026-08-10T21:18:36Z  Microsoft.App/containerApps/write  admin@jtoyedigital.co.uk  Accepted
2026-08-10T21:18:21Z  Microsoft.App/containerApps/write  admin@jtoyedigital.co.uk  Accepted
2026-08-10T21:18:07Z  Microsoft.App/containerApps/write  admin@jtoyedigital.co.uk  Accepted
```

Exactly **four** writes, all at ~21:18Z, all by the owner's identity — 35 minutes **after** 29-01's
20:43Z reading and ~51 minutes before this plan started. Four writes, four apps that now read `0`,
and the two that read `1` have revisions still dated 2026-07-21. The disposition was therefore
**partially applied out-of-band** between the two plans, and the record's §2.2 table is a correct
reading of a state that has since moved.

This is recorded rather than smoothed over: an executor that had trusted §2.2 as the live baseline
would have reported "changed 6 apps from 1 to 0" when it in fact changed two, and the after-state
would have looked identical either way.

### 1.2 What this plan changed

Only the two that were still at `1`:

```
$ az containerapp update -g jtoye-rg -n snackpass-redis --subscription c483d353-… --min-replicas 0
{"max": 1, "min": 0, "name": "snackpass-redis"}          # 2026-08-10T22:11:29Z

$ az containerapp update -g jtoye-rg -n snackpass-minio --subscription c483d353-… --min-replicas 0
{"max": 1, "min": 0, "name": "snackpass-minio"}          # 2026-08-10T22:11:45Z
```

### 1.3 After-state, read back off the running resources

```
$ az containerapp list -g jtoye-rg --subscription c483d353-… \
    --query "[].{name:name,min:…minReplicas,max:…maxReplicas}" -o table   # 2026-08-10T22:12Z

Name                     Min    Max
-----------------------  -----  -----
snackpass-redis            0      1
snackpass-minio            0      1
snackpass-python-vision    0      3
snackpass-java-core        0      3
snackpass-go-edge          0      5
snackpass-webapp           0      1
```

All six at `minReplicas: 0`. `snackpass-pg` was **not** touched (decision Q1 keeps it), and
`jtoye-bootcamp` — the unrelated Free-tier Static Web App the research missed — was not touched
either.

### 1.4 `minReplicas: 0` is a CONFIG value, not a bill. What actually bills is replicas.

This is obligation O-1's point and it is not a formality: the meters charge for **running
replicas**, so "the config reads 0" is exactly the structural-green-over-a-dead-feature shape. Every
app still reported `runningStatus: Running` immediately after the change. Replica counts, read per
revision:

| App | minReplicas set at | Active revision | Replicas at ~22:12Z | **Replicas at ~22:25Z** |
|---|---|---|---|---|
| snackpass-webapp | 21:18:13Z | `--0000006` | 0 | **0** |
| snackpass-go-edge | 21:18:27Z | `--0000006` | 0 | **0** |
| snackpass-java-core | 21:18:42Z | `--0000001` | 0 | **0** |
| snackpass-python-vision | 21:18:56Z | `--0000002` | 0 | **0** |
| snackpass-redis | 22:11:29Z | `--0000001` | 1 (within 300 s cooldown) | **0** |
| snackpass-minio | 22:11:45Z | `--0000001` | 1 (within 300 s cooldown) | **0** |

**All six apps are at 0 replicas — 6 of 6, measured.** This is not a projection: every app was read
back off its own revision list. The middle column is kept deliberately, because it is the reading
that would have been wrong: measured three minutes after the change, `snackpass-redis` and
`snackpass-minio` still showed **1**, and an executor that sampled once at that moment would have
recorded the disposition as half-failed. The `cooldownPeriod` is 300 s, so the correct reading
required waiting past it — the instrument needed time, not a different query.

A second observation worth recording, because it briefly looked like a defect: immediately after the
`update`, `snackpass-redis` showed **two** active revisions each with 1 replica (the 2026-07-21
original and the new one) — i.e. the change transiently *doubled* the replicas. The app is in
`activeRevisionsMode: Single`, so the old revision deactivated on its own; a re-read moments later
showed one revision. An executor that measured once, at the wrong moment, would have recorded a cost
increase.

**O-1 still stands and is NOT discharged here.** Replicas at 0 is the mechanism working; the
obligation is to re-run the Cost Management meter query (`Standard vCPU Idle Usage`,
`Standard Memory Idle Usage`) ~48 h from now and confirm the *money* followed. Replica count and
meter are different instruments, and only the second one is the bill.

---

## 2. What was provisioned — each fact read off the created resource

`scripts/azure-staging-provision.sh` was run for real (not `--dry-run`) at 2026-08-10T22:16Z, and
again at 22:30Z after two defects were fixed. Both defects are written up in §5.

### 2.1 AKS — `jtoye-staging-aks`

```
$ az aks show -g jtoye-rg -n jtoye-staging-aks --subscription c483d353-… \
    --query '{dataplane:networkProfile.networkDataplane,plugin:networkProfile.networkPlugin,
              mode:networkProfile.networkPluginMode,policy:networkProfile.networkPolicy,
              tier:sku.tier,nodeRG:nodeResourceGroup,k8s:currentKubernetesVersion}' -o json
{
  "dataplane": "cilium",
  "k8s": "1.35.6",
  "mode": "overlay",
  "nodeRG": "MC_jtoye-rg_jtoye-staging-aks_uksouth",
  "plugin": "azure",
  "policy": "cilium",
  "tier": "Free"
}
```

**Quoted, not summarised, per the acceptance criterion.** `networkDataplane: cilium` AND
`networkPolicy: cilium` — the ENFORCING dataplane that was requested. This is what makes DPLY-05
provable; a cluster created without it would have failed silently, because policies still *apply*
cleanly to an engine that does not enforce them.

```
AKS_NAME               : jtoye-staging-aks
AKS_KUBE_CONTEXT       : jtoye-staging          (created by this plan; NEVER `sipbihs2aks`)
AKS_NODE_RG            : MC_jtoye-rg_jtoye-staging-aks_uksouth
AKS_EGRESS_IP          : 20.26.28.17            <- the ONLY address the Postgres firewall admits
AKS_OIDC_ISSUER_URL    : https://uksouth.oic.prod-aks.azure.com/b56df236-36b2-49ab-a25f-050cbaa9787c/9f6ec1cb-8fdd-430c-bfda-9d53547834ef/
```

> ⚠ **The plan's own `<verify>` command reads the OIDC issuer as `oidcIssuerProfile.issuerURL` and
> that returns `null`.** The field is `issuerUrl` (lower-case `rl`), and JMESPath is case-sensitive.
> Run verbatim, the criterion yields `"oidc": null` on a cluster where OIDC is correctly enabled —
> a silent false negative that reads like a provisioning failure. The value above came from
> `--query 'oidcIssuerProfile'`, which returns `{"enabled": true, "issuerUrl": "https://…"}`.

### 2.2 Static ingress IP — the A-record target for Task 3

```
$ az network public-ip show -g MC_jtoye-rg_jtoye-staging-aks_uksouth \
    -n jtoye-staging-ingress-ip --subscription c483d353-… -o json
{ "alloc": "Static", "ip": "20.58.10.18", "sku": "Standard",
  "state": "Succeeded", "rg": "MC_jtoye-rg_jtoye-staging-aks_uksouth" }
```

**`INGRESS_STATIC_IP = 20.58.10.18`.** It lives in the AKS **node** resource group, which is
required for a LoadBalancer Service to claim it. Note this is a *different* address from the egress
IP (`20.26.28.17`) — inbound and outbound are separate, and confusing them would put the wrong
address in either the DNS records or the database firewall.

### 2.3 PostgreSQL Flexible Server — `jtoye-staging-pg`

```
$ az postgres flexible-server show -g jtoye-rg -n jtoye-staging-pg --subscription c483d353-… -o json
{ "fqdn": "jtoye-staging-pg.postgres.database.azure.com",
  "pna": "Enabled", "sku": "Standard_B2s", "tier": "Burstable",
  "state": "Ready", "storage": 32, "version": "16" }
```

**`PG_VERSION = 16`** — read off the server, not inferred from the `--version 16` argument. This is
Blocker C: at 15 or below, `CREATE ROLE jtoye_backup LOGIN BYPASSRLS` cannot succeed for a
non-admin role on Flexible Server, and every logical dump would silently capture ZERO rows from
every FORCE-RLS table. A `15` here would have failed the task outright.

```
PG_FQDN            : jtoye-staging-pg.postgres.database.azure.com
PG_SKU             : Standard_B2s (Burstable), 32 GiB, 7-day backup retention
PG_MAX_CONNECTIONS : 429   (default 429 — matches 29-01 §5's B2s prediction exactly)
PG_AZURE_EXTENSIONS: uuid-ossp
```

`max_connections = 429`. Azure reserves 15 for replication/monitoring, leaving 414 for users
against the 155-connection budget `k8s/scripts/check-connection-math.sh` asserts — it fits with
large margin. On B1ms (50/35) it would not have, which is why 29-01 ruled B1ms out.

**`azure.extensions`, read back AFTER setting it** — setting and assuming is the failure mode:

```
$ az postgres flexible-server parameter show -g jtoye-rg -s jtoye-staging-pg \
    -n azure.extensions --subscription c483d353-… -o json
{ "name": "azure.extensions", "source": "user-override", "value": "uuid-ossp" }
```

`uuid-ossp` is present, and the value is `user-override` rather than the empty default. This is the
precondition for `V1__base_schema.sql:6`; without it V1 fails and no migration runs at all. The live
`snackpass-pg` reads `vector,pgcrypto` — deliberately NOT copied, and it does not contain
`uuid-ossp`.

**Firewall — scoped to one address, with the contrast that makes "scoped" mean something:**

```
$ az postgres flexible-server firewall-rule list -g jtoye-rg -s jtoye-staging-pg …
Name                          Start        End
----------------------------  -----------  -----------
aks-jtoye-staging-aks-egress  20.26.28.17  20.26.28.17      <- ONE address, start == end

$ … -s snackpass-pg …    (the pre-existing server, for contrast — NOT modified)
runner                                              81.96.202.37  81.96.202.37
AllowAllAzureServicesAndResourcesWithinAzureIps_…   0.0.0.0       0.0.0.0    <- the WIDE shape
```

T-29-10-01 is mitigated and the mitigation is falsifiable: the staging server has exactly one rule
covering exactly one IP, while the neighbouring server in the same resource group carries the
allow-all-Azure rule. Asserting "the rule is narrow" without showing what wide looks like would have
been an unfalsifiable claim.

**Both databases exist (D-02):**

```
$ az postgres flexible-server db list -g jtoye-rg -s jtoye-staging-pg …
azure_maintenance / postgres / azure_sys      <- Azure's own
jtoye                                          <- the platform database
keycloak                                       <- Keycloak's own (D-02)
```

### 2.4 CI identity + GitHub federated credential (D-04, #99)

```
CI_IDENTITY_CLIENT_ID : ec29905d-231c-43eb-9385-c6f7f72409db
CI_IDENTITY_PRINCIPAL : 4cce597e-719e-4181-b045-8ba024f5bc67
CI_TENANT_ID          : b56df236-36b2-49ab-a25f-050cbaa9787c
CI_FEDERATED_SUBJECT  : repo:Bralabee/JToye_OaaS_2026:environment:staging
  issuer   : https://token.actions.githubusercontent.com
  audience : api://AzureADTokenExchange
```

**No client secret exists.** The subject is exact-match with no wildcards, and uses the
`environment:staging` form rather than `ref:refs/heads/main` because the deploy job declares
`environment: staging` — both correct and tighter.

These were created directly rather than by the script, because the run aborted at STEP 7 (Redis,
§4) before reaching STEP 8. The commands are the script's own, unchanged.

### 2.5 Redis — NOT CREATED. See §4.

---

## 3. Node allocatable — assumption A2, MEASURED

The research REASONED ~1.9 vCPU / ~2.4 GiB per B2s and the whole pool sizing rests on it. Measured
on real nodes, with an explicit context:

```
$ kubectl --context jtoye-staging get nodes -o custom-columns=…
NODE                                STATUS  CPU_CAP  CPU_ALLOC  MEM_CAP     MEM_ALLOC   PODS
aks-nodepool1-61843550-vmss000000   Ready   2        1900m      4005284Ki   2854308Ki   250
aks-nodepool1-61843550-vmss000001   Ready   2        1900m      4005276Ki   2854300Ki   250
aks-nodepool1-61843550-vmss000002   Ready   2        1900m      4005288Ki   2854312Ki   250
```

| Quantity | Research (reasoned) | **Measured** | Verdict |
|---|---|---|---|
| CPU allocatable / node | ~1.9 vCPU | **1900m** | exact |
| Memory allocatable / node | ~2.4 GiB | **2854308Ki = 2.72 GiB** | **research was ~13% CONSERVATIVE** |

**A2 holds, and errs in the safe direction on memory.** Cluster totals: **5700m CPU** and
**8362 MiB (8.17 GiB)** allocatable across three nodes.

### 3.1 Does the estate fit? The arithmetic, shown

System overhead already scheduled (`kubectl describe node`, Allocated resources, all three nodes):

```
node ...000000   cpu 587m (30%)   memory 808Mi (28%)
node ...000001   cpu 467m (24%)   memory 718Mi (25%)
node ...000002   cpu 470m (24%)   memory 686Mi (24%)
                 -------------    ----------------
     SYSTEM      cpu 1524m        memory 2212Mi
```

Application tier, summed from the actual render (`kubectl kustomize k8s/staging`), using each
workload's HPA floor because an HPA floor WINS over a Deployment's `replicas`:

```
workload     kind        reps   cpu/pod   mem/pod    cpuTOT   memTOT
core-java    Deployment     1      500m     1024Mi      500m   1024Mi
edge-go      Deployment     1      100m       64Mi      100m     64Mi
frontend     Deployment     1      200m      256Mi      200m    256Mi
                                                       ------   ------
TOTAL (3 pods)                                          800m   1344Mi
```

```
FREE AFTER SYSTEM   cpu 5700 - 1524 = 4176m      memory 8362 - 2212 = 6150Mi
APP TIER                              800m                            1344Mi
REMAINING                            3376m                            4806Mi
```

**The app tier fits with large margin — 19% of free CPU and 22% of free memory.** The 29-02
scale-patch is what makes this true: without it the base HPA floors (3/5/3) would schedule ELEVEN
app pods (~2.6 vCPU / ~4.1 GiB) and the fit would be marginal.

**What is NOT yet measurable, stated rather than estimated into the total:** Keycloak, Prometheus,
Grafana, Alertmanager, the RabbitMQ cluster, cert-manager and ingress-nginx are not in the
`k8s/staging` render today — they arrive in later plans (29-06, 29-07) and via
`scripts/staging-bootstrap.sh`. Their requests therefore **cannot be summed from the tree**, and
inventing a number for them would be exactly the kind of reasoned-not-measured figure this section
exists to replace. The headroom above (3376m CPU / 4806Mi memory) is what they must fit inside, and
**that check belongs to the plan that adds them.**

---

## 4. BLOCKER — Azure Cache for Redis cannot be created at all

STEP 7 failed, and not for a fixable reason:

```
$ az redis create -g jtoye-rg -n jtoye-staging-redis --location uksouth \
    --sku Basic --vm-size c0 --minimum-tls-version 1.2 --subscription c483d353-…

ERROR: (BadRequest) Azure Cache for Redis is retiring, create Azure Managed Redis
       instance instead. Learn more: https://aka.ms/AzureCacheForRedisRetirement
RequestID=0f2ed6cd-fe5d-4b16-9992-74a4dfed65ef
```

**The API refuses new Azure Cache for Redis resources outright.** 29-01 recorded the Basic
retirement as obligation **O-5**, a distant horizon dated **2028-09-30** requiring "no action this
phase". That is now falsified by measurement: creation is blocked *today*, more than two years
before the recorded date. A horizon row would not have caught this — the row's own date said there
was nothing to do.

This invalidates `REDIS_SKU = Azure Cache for Redis Basic C0` in the decision record, and with it
ADR-0002 / D-09. **It is an architectural change (deviation Rule 4) and is NOT auto-fixed here.**

### 4.1 Priced alternatives, from the live retail API (GBP, uksouth, 2026-08-10)

```
$ curl -s "https://prices.azure.com/api/retail/prices?currencyCode=GBP&\$filter=
    serviceName eq 'Redis Cache' and armRegionName eq 'uksouth' and type eq 'Consumption'"
  -> 88 items
```

| Option | product / SKU | Rate | **£/mo** | vs planned |
|---|---|---|---|---|
| **Azure Managed Redis — Balanced B0** | `Azure Managed Redis - Balanced` / `B0` | £0.0136/hr | **£9.93** | **−£5.55 CHEAPER** |
| Azure Managed Redis — Balanced B1 | `B1` | £0.0280/hr | £20.44 | +£4.96 |
| ~~Azure Cache for Redis Basic C0~~ | `Azure Redis Cache Basic` / `C0` | £0.0212/hr | £15.48 | **cannot be created** |
| In-cluster Redis (a k8s Deployment) | — | — | £0.00 | −£15.48, but consumes node headroom |

**The forced move is cheaper than the plan, not more expensive** — Managed Redis Balanced B0 at
£9.93/mo undercuts the blocked Basic C0 by £5.55/mo.

### 4.2 Why this still needs a decision rather than a substitution

Azure Managed Redis is a **different resource provider** with different runtime semantics, and three
things in this repo encode the old one:

1. **Port.** Azure Cache Basic serves TLS on **6380**; Azure Managed Redis serves on **10000**.
   `k8s/staging/configmap-patch.yaml` sets `redis.port: 6380`, and that value is fed by
   `replacements:` into the `core-java-allow` NetworkPolicy egress rule. Under the *enforcing*
   Cilium dataplane just provisioned, a wrong port is not a warning — every cache call is dropped.
2. **ADR-0002 / D-09** names Azure Cache for Redis explicitly, and `REDIS_SKU` in
   29-OPERATOR-DECISIONS.md is what `azure-staging-provision.sh` parses. Both must change together,
   or the script's own decision-record parse goes stale.
3. **The `az redis` CLI path differs** (`az redisenterprise` / `az redis enterprise`), so the
   script's STEP 7 needs rewriting, not re-parameterising.

None of that is a Rule 1–3 auto-fix. It is recorded here and raised as a checkpoint.

---

## 5. Two defects found in `scripts/azure-staging-provision.sh`, both fixed

Neither was reachable by plan 29-05's 20 falsification arms, because every one of those arms ran
against `--dry-run` or a throwaway PostgreSQL container. Both defects live exclusively on the real
path.

### 5.1 [SECURITY] The administrator password was disclosed in plaintext

`az postgres flexible-server create` prints its own JSON result, and that result contains **both** a
`password` field and a full `connectionString` with the password embedded:

```
{ "connectionString": "postgresql://jtoyeadmin:<SCRUBBED>@jtoye-staging-pg.postgres.database.azure.com/postgres?sslmode=require",
  "password": "<SCRUBBED>", "username": "jtoyeadmin", "version": "16" }
```

The script's `REDACT_FLAGS` mechanism redacts the rendered **command** in `--dry-run`. On a real run
it is the CLI's **output** that discloses, and nothing suppressed it. The value reached the run log
in plaintext (measured: **2 occurrences**).

**Remediation, in order:**
1. The password was **rotated** on the server (`az postgres flexible-server update --admin-password
   … --output none`) and the machine-local store updated. The disclosed value is dead.
2. The log was scrubbed — measured **2 occurrences before, 0 after**, so the scrub is demonstrated
   rather than assumed.
3. `--output none` added to the create invocation, with the reasoning in the script.

No credential ever reached a **tracked** artifact: the log lives in the session scratchpad, outside
the repository. GLOBAL_RULE_6 is intact, but only because the leak was off-repo — the mechanism was
still wrong and is now fixed.

### 5.2 [BUG] `--public-access None` disabled public access, so the firewall rule failed

```
firewall rule scoped to the AKS egress IP only: 20.26.28.17
ERROR: Firewall rule operations are not supported for a server without public access enabled.
```

The CLI's own help states that `None` *"sets the server in public access mode but does not create a
firewall rule"* — which is precisely the shape this estate wants. **The observed behaviour
contradicts the documentation**: the created server reported `network.publicNetworkAccess:
Disabled`.

Documentation and behaviour disagree, and the behaviour is what ships. Fixed to
`--public-access Enabled`, which creates the server in public-access mode with no rules of its own,
leaving the single scoped rule as the only way in. The existing server was corrected in place with
`az postgres flexible-server update --public-access Enabled` and read back as `Enabled`.

---

## 6. Cost — re-derived from the SKUs ACTUALLY created

Live retail API, GBP, `uksouth`, 2026-08-10 — not the research quoted back:

| Line | SKU | Measured rate | £/mo |
|---|---|---|---|
| AKS nodes | 3 × `Standard_B2s` | £0.0358/hr each | **78.40** |
| AKS control plane | `Free` tier | £0.00 (assumption A8) | **0.00** |
| PostgreSQL compute | `B2S` Burstable | £0.0576/hr | **42.05** |
| PostgreSQL storage | 32 GiB | £0.1008/GiB/mo | **3.23** |
| Static ingress IP | Standard IPv4 static | £0.0038/hr | **2.77** |
| AKS egress IP | Standard IPv4 static (managed outbound) | £0.0038/hr | **2.77** |
| **CREATED TODAY** | | | **£129.22** |
| Redis (BLOCKED, §4) | AMR Balanced B0, if adopted | £0.0136/hr | (9.93) |
| **PROJECTED COMPLETE ESTATE** | | | **£139.15** |

```
PROJECTED ESTATE      GBP 139.15/mo
CEILING (D-03)        GBP 150.00/mo
HEADROOM              GBP  10.85/mo
```

**Headroom improves from the planned £3.00 to £10.85**, because the Redis line the API forced us off
is £5.55/mo cheaper, and because the research's £147.00 slightly overstated the node/IP lines. Both
the node line (£0.0358/hr) and the PostgreSQL line (£42.05/mo) reproduce 29-01's figures exactly,
which is a useful cross-check that the instrument is the same one.

**Assumption A8 (`--tier free` incurs no control-plane charge) remains INFERRED, not verified.** It
is inferred from the absence of a Free line item in the retail price list, and the cluster is hours
old, so no bill line exists yet. It must be checked against a real invoice line after a day — that
is a follow-up, not something this plan can close.

`snackpass` contributes ~£0 once the idle meters follow the replica count to zero (O-1, ~48 h), and
`snackpass-pg` stays free until ~2027-07-21 (O-2).

### 6.1 Resource inventory after the run

```
$ az resource list -g jtoye-rg --subscription c483d353-… -o tsv
jtoye-bootcamp             Microsoft.Web/staticSites                     (unrelated, untouched)
snackpass-logs             Microsoft.OperationalInsights/workspaces
snackpass-env              Microsoft.App/managedEnvironments
snackpass-pg               Microsoft.DBforPostgreSQL/flexibleServers     (kept per decision Q1)
snackpass-redis            Microsoft.App/containerApps                   (minReplicas 0)
snackpass-minio            Microsoft.App/containerApps                   (minReplicas 0)
snackpass-python-vision    Microsoft.App/containerApps                   (minReplicas 0)
snackpass-java-core        Microsoft.App/containerApps                   (minReplicas 0)
snackpass-go-edge          Microsoft.App/containerApps                   (minReplicas 0)
snackpass-webapp           Microsoft.App/containerApps                   (minReplicas 0)
jtoye-staging-aks          Microsoft.ContainerService/managedClusters    <- NEW
jtoye-staging-pg           Microsoft.DBforPostgreSQL/flexibleServers     <- NEW
```

10 before, **12 after**. The static IP and the CI identity are not listed because the IP lives in
the node resource group `MC_jtoye-rg_jtoye-staging-aks_uksouth`; `jtoye-ci` is in `jtoye-rg` and
appears on a re-list. Nothing was deleted. The snackpass disposition is visibly `scale-to-zero`, not
`delete-snackpass`, matching the recorded decision.

---

## 7. DNS — baseline BEFORE Task 3, with the control that makes it meaningful

Measured **2026-08-10T22:37:01Z**, against two independent resolvers:

```
--- system default resolver ---
  api-staging.olajay.co.uk      -> (empty)
  app-staging.olajay.co.uk      -> (empty)
  auth-staging.olajay.co.uk     -> (empty)
  grafana-staging.olajay.co.uk  -> (empty)

--- production hosts (D-08: MUST stay empty until Phase 32) ---
  api.olajay.co.uk              -> (empty)   OK
  app.olajay.co.uk              -> (empty)   OK

--- zone delegation ---
  NS  : dns1-4.p05.nsone.net.
  SOA : dns1.p05.nsone.net. domains+netlify.netlify.com. 1634401895 …

--- POSITIVE CONTROL ---
  one.one.one.one               -> 1.0.0.1 1.1.1.1        (system resolver)
  one.one.one.one               -> 1.1.1.1 1.0.0.1        (@1.1.1.1)

--- SECOND RESOLVER @1.1.1.1, so a cached local answer cannot masquerade ---
  all four staging names        -> (empty)
```

D-05 re-confirmed and D-08 currently satisfied: the zone is live at NS1/Netlify with **zero** A
records, and nothing looks live that is not.

**One control failed and is recorded rather than quietly dropped.** `olajay.co.uk` (apex) was also
probed as a positive control and returned **empty** — the apex has no A record of its own. Had it
been the only control, its emptiness would have been read as "the resolver is broken" and the whole
measurement discarded. `one.one.one.one` is the valid control, and it resolves on both resolvers,
so every empty above is a genuine absence.

**`INGRESS_STATIC_IP = 20.58.10.18` is the value all four A records must point at.**

---

## 8. Status against the plan's success criteria

| Criterion | State |
|---|---|
| Estate exists in the owner's subscription, enforcing dataplane, PG16 server | **MET** — `cilium` + `version: 16`, both read off the resource |
| Node allocatable measured and the estate shown to fit | **MET** — 1900m / 2.72 GiB per node; app tier uses 19% of free CPU |
| `jtoye_backup` has BYPASSRLS, proven from the DB side with a failing arm | **NOT STARTED** — Task 2, blocked (§9) |
| All four staging names resolve, no production name resolves | **NOT STARTED** — Task 3, operator action (§9) |
| Redis provisioned | **BLOCKED** — §4, needs a decision |

---

## 9. What is blocked, and on what

| Item | Blocks | Why it cannot be auto-resolved |
|---|---|---|
| **Redis service choice** | Task 1 completion, 29-11+ | Rule 4 architectural: different provider, port 6380 → 10000, ADR-0002 + `REDIS_SKU` + configmap + NetworkPolicy all change together (§4) |
| **AWS keys ×4** (media + backup, eu-west-2) | **Task 2 entirely** | Recorded ABSENT (29-01 §7.2). Operator-only |
| **Gmail app password + From/To** | **Task 2 entirely** | Recorded ABSENT (29-01 §7.3). Operator-only |
| **Netlify DNS portal access** | Task 3 | UNCONFIRMED (29-01 §7.5); cert-manager has no Netlify solver, so there is no automation path (D-07) |

> **Task 2 is blocked ENTIRELY, not partially — this is a measured claim about the script, not an
> assumption.** `scripts/staging-secrets.sh` runs its 23-name value preflight at STEP 3 (lines
> 223–280), and that block sits **before** the `case "$MODE"` dispatch and is **not** gated by mode.
> So `--roles-only` and even the read-only `--verify-roles-only` refuse with
> `REFUSED [value-preflight]` until all 23 are present — including the four `AWS_*` and three
> `ALERTMANAGER_SMTP_*` values that are recorded ABSENT. There is no subset of Task 2 that can run
> today.

A second, independent blocker for Task 2 exists even once the credentials arrive: the server's only
firewall rule admits `20.26.28.17` (the AKS egress IP). `psql` from this host will be refused, so
the role bootstrap must either run from inside the cluster or have a temporary operator-IP rule
added and then removed.

### 9.0 The BYPASSRLS verification that Task 2 owes — NOT YET RUN

Recorded here so the contract is unambiguous when Task 2 resumes, and explicitly marked **NOT RUN**
so no reader mistakes a stated intention for a measurement:

```sql
-- run against the MANAGED server as the administrator, per k8s-local-secrets.sh:254-262
SELECT rolname, rolsuper, rolbypassrls, rolcanlogin
  FROM pg_roles
 WHERE rolname IN ('jtoye_app','jtoye_runtime','jtoye_backup')
 ORDER BY rolname;
```

| Role | Required attributes | Why |
|---|---|---|
| `jtoye_backup` | **`rolbypassrls = t`** | Without it the logical dump runs as a FORCE-RLS subject and captures **zero rows** from every tenant table — a green backup over an empty database (DPLY-04 arm A) |
| `jtoye_runtime` | `rolbypassrls = f` **AND** `rolsuper = f` | The negative assertion. If the role the application connects as could bypass RLS, the whole tenant wall is decorative |
| `jtoye_app` | `rolsuper = f` | Owner/migrator, not a superuser |

**Status: NOT RUN — no role has been created on `jtoye-staging-pg`, and `rolbypassrls` has not been
read from `pg_roles` on that server.** PostgreSQL **16** (§2.3, read off the server) is what makes
the `jtoye_backup` grant possible at all; that precondition is met, the verification itself is not.
The plan also requires a fail-direction arm — a scratch role created *without* BYPASSRLS, proving
the verification reports the failure — and a readable-table count recorded as `<readable> of
<total>` with both numbers (the Phase 28 defect #629 shape), never as "all tables readable".

### 9.1 The administrator credential

`PG_ADMIN_USER` / `PG_ADMIN_PASSWORD` were **generated by this plan** (authorised in the executor's
brief) and stored **outside the repository**, machine-local:

```
/home/sanmi/.jtoye/staging-admin.env      mode 0600
```

It contains exactly two keys and no value appears in this file, in any commit, or in any tracked
artifact. **The operator should move this into their password manager** — it is currently the only
copy, and the server is unusable without it (recoverable only by an admin-password reset).

> The contract in 29-05 says to generate with `openssl rand -hex 32`. **That would have been
> rejected by Azure.** Hex output is `[0-9a-f]` — digits plus lowercase, only **two** character
> categories, and Flexible Server requires at least **three** of {upper, lower, digit,
> non-alphanumeric}. The generated value keeps a 40-character alphanumeric random core (~238 bits)
> with affixes that satisfy the category rule without reducing that core's entropy. Recorded so the
> contract can be corrected rather than repeatedly tripped over.

---

*Written by plan 29-10, 2026-08-10. Every value above was read back off the running resource. Items
that are absent are named as ABSENT with what they block — an omitted blocker reads identically to a
satisfied one.*
