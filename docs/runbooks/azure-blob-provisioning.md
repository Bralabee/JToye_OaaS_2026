# Azure Blob Estate Provisioning Runbook

The specification Phase 29 provisions the staging (and later production) Blob estate from. It covers
the storage accounts, containers and their access levels, backup data protection (soft delete,
WORM, lifecycle), Workload Identity, RBAC scopes, network notes, and the read-backs that prove each
setting took.

Related: [`backups.md`](./backups.md) (the pg-backup CronJob and restore drill),
[`sealed-secrets.md`](./sealed-secrets.md) (the operator secret list, which no longer carries any
storage credential), `k8s/DEPLOYMENT.md` (runtime-parity gates).

---

## 1. Purpose and ownership

- **Specified by Phase 36, executed by Phase 29** (36-CONTEXT.md D-03). Phase 36 changed the code,
  the manifests and the goldens. Everything in Azure below is created by the Phase 29 operator.
  **Nothing in this runbook was created on 2026-09-28.** Only read-only checks ran; their
  results are in section 2.1.
- **Supersedes Phase 29 D-11 and D-12** (AWS S3 for media, and AWS S3 "off-cluster AND off-Azure"
  for backups). Phase 36 D-01 records the trade-off: the backup account survives object
  deletion and a compromised app credential, but NOT the loss of the whole Azure subscription.
- The account names, regions, SKU and retention values are the owner's decision **D-11**
  (36-CONTEXT.md, 2026-09-28). The container names are **D-07**. Do not re-decide them here. If a
  value must change, section 9 applies.
- **Subscription:** J'Toye's subscription is `c483d353-5f61-4587-a790-addb9ab5fb94`
  ("JToye Digital Production"; tenant `b56df236-36b2-49ab-a25f-050cbaa9787c`), resource group
  `jtoye-rg`, region `uksouth`. **This machine's ambient CLI default is the EMPLOYER's
  subscription.** Every command below therefore passes `--subscription "$SUB"`, and `$SUB` is
  resolved and asserted first:

```bash
JTOYE_SUB_ID=c483d353-5f61-4587-a790-addb9ab5fb94
SUB=$(az account show --subscription "$JTOYE_SUB_ID" --query id -o tsv) || exit 2
STATE=$(az account show --subscription "$JTOYE_SUB_ID" --query state -o tsv) || exit 2
[ "$SUB" = "$JTOYE_SUB_ID" ] && [ "$STATE" = "Enabled" ] \
  || { echo "VOID: resolved '$SUB' state '$STATE', want $JTOYE_SUB_ID Enabled" >&2; exit 2; }
```

The equality assertion is load-bearing. It was measured on 2026-09-28: an EMPTY `--subscription ""`
does not fail. It silently resolves to the ambient default (the employer's subscription), and only
the `[ "$SUB" = "$JTOYE_SUB_ID" ]` comparison refuses it. An unknown id fails with rc=1 on its own.

---

## 2. Accounts

| Role | Staging | Production | Region | SKU | Anonymous blob access |
|---|---|---|---|---|---|
| Media (images + quarantine) | `jtoyestgmedia` | `jtoyeprodmedia` | `uksouth` (with AKS) | `Standard_LRS` | **allowed** (account level; per container below) |
| Backup (pg dumps) | `jtoyestgbackup` | `jtoyeprodbackup` | `ukwest` | `Standard_LRS` | **disallowed** |

The backup account is **separate** from the media account and sits in a **different region** from
staging (D-01). `uksouth` and `ukwest` are an Azure region pair, measured on 2026-09-28 through the
subscription-pinned Locations API (assumption A12 confirmed):

```bash
az rest --method get --subscription "$SUB" --url "https://management.azure.com/subscriptions/$SUB/locations?api-version=2022-12-01" --query "value[?name=='uksouth' || name=='ukwest'].{name:name,paired:metadata.pairedRegion[0].name}"
```

The Locations API is reached through `rest` because `account list-locations` rejects the
subscription flag. Unpinned, it would read the employer's subscription.

### 2.1 Before any create: the resource provider, then the name check

On 2026-09-28 the `Microsoft.Storage` resource provider was **NotRegistered** on this subscription,
and `check-name` answered `SubscriptionNotFound` (rc=3) for all four names. The name check
therefore returned **no verdict**. The only availability evidence recorded is DNS: all four
`<name>.blob.core.windows.net` names returned NXDOMAIN. The controls behaved as expected: a known
account (`azureopendatastorage`) resolved, and a random name returned NXDOMAIN. So on that date no
storage account held any of the four names. Phase 29 registers the provider and then gets the real
verdict immediately before creating anything:

```bash
az provider register --namespace Microsoft.Storage --wait --subscription "$SUB"
az provider show --namespace Microsoft.Storage --query registrationState -o tsv --subscription "$SUB"   # must print Registered
for n in jtoyestgmedia jtoyestgbackup jtoyeprodmedia jtoyeprodbackup; do
  az storage account check-name --name "$n" --query "{name:'$n',available:nameAvailable,reason:reason}" -o json --subscription "$SUB"
done
```

Any `available: false` means **stop**: go to section 9. Never create a substitute name on the spot.

### 2.2 Create (staging shown; production is the same with the production names)

```bash
MEDIA_ACCT=jtoyestgmedia; BACKUP_ACCT=jtoyestgbackup     # production: jtoyeprodmedia / jtoyeprodbackup

# Media account: anonymous blob access must be allowed EXPLICITLY. New accounts default to
# disallowing it (A10), and jtoye-images cannot be public otherwise.
az storage account create --subscription "$SUB" -g jtoye-rg -n "$MEDIA_ACCT" \
  --location uksouth --kind StorageV2 --sku Standard_LRS \
  --min-tls-version TLS1_2 --https-only true \
  --allow-shared-key-access false \
  --allow-blob-public-access true

# Backup account: never public. The dumps hold PII.
az storage account create --subscription "$SUB" -g jtoye-rg -n "$BACKUP_ACCT" \
  --location ukwest --kind StorageV2 --sku Standard_LRS \
  --min-tls-version TLS1_2 --https-only true \
  --allow-shared-key-access false \
  --allow-blob-public-access false
```

`--allow-shared-key-access false` is set on **both** accounts. The consequence: an account key is
useless even if it leaks, because the service refuses shared-key authorisation. Nothing in this
design uses a key. core-java and pg-backup authenticate with Workload Identity (section 5), browsers
read `jtoye-images` anonymously, and the operator works on the control plane with their Entra login.
**No account key, connection string or SAS token is ever written to a file, a Secret (sealed or
not), a ConfigMap or this runbook** (D-02). Do not run `keys list` against these accounts.

---

## 3. Containers

| Container | Account | Access level | Holds |
|---|---|---|---|
| `jtoye-images` | media | **`blob`** (anonymous GET by URL; no anonymous LIST) | WebP derivatives, thumbnails, seed images |
| `jtoye-quarantine` | media | **private** | raw uploads awaiting the worker (may carry EXIF/GPS) |
| `jtoye-db-backups` | backup | **private** | `backups/jtoye-backup-YYYYMMDD-HHMMSS.dump` |

`jtoye-images` is **never** access level `container`. `container` allows anonymous LIST, which would
enumerate every tenant's images (#626). The operator creates the containers and sets their access
levels. core-java never does this in staging or production: those overlays set
`storage.blob.create-containers: "false"` (36-09), and Set Container ACL cannot be authorised with an
Entra ID token at all. The containers are therefore created through the **control plane**
(`container-rm`, the Microsoft.Storage resource provider). It needs no data-plane role and no shared
key, both of which are deliberately absent. Login-based data-plane container creation (`--auth-mode
login`) is the fallback only if this route is unavailable, and it still cannot set an ACL.

```bash
az storage container-rm create --subscription "$SUB" -g jtoye-rg --storage-account "$MEDIA_ACCT" \
  --name jtoye-images --public-access blob
az storage container-rm create --subscription "$SUB" -g jtoye-rg --storage-account "$MEDIA_ACCT" \
  --name jtoye-quarantine --public-access off
az storage container-rm create --subscription "$SUB" -g jtoye-rg --storage-account "$BACKUP_ACCT" \
  --name jtoye-db-backups --public-access off
```

On `jtoye-quarantine` there is **no lifecycle rule**. The Phase 27 quarantine horizon stays in the
application sweep (`MediaQuarantineRetentionSweep`), because only the sweep knows which quarantined
assets can still be re-driven. A storage-side rule would delete bytes the application still expects
to find (27-01).

---

## 4. Backup account data protection (in this order)

The values are D-11's **recorded** values: retention 30 days (matching today's dump retention),
soft delete 14 days, lifecycle delete at 35 days. They are recorded, not locked.

**4.1 Blob soft delete first.** Microsoft's guidance is to enable soft delete before immutability.

```bash
az storage account blob-service-properties update --subscription "$SUB" -g jtoye-rg \
  --account-name "$BACKUP_ACCT" --enable-delete-retention true --delete-retention-days 14
```

**4.2 Container-level time-based immutability (WORM) policy, left UNLOCKED.**

```bash
az storage container immutability-policy create --subscription "$SUB" -g jtoye-rg \
  --account-name "$BACKUP_ACCT" --container-name jtoye-db-backups \
  --period 30 --allow-protected-append-writes false
```

A new policy starts **unlocked**. While unlocked it can be shortened, extended or deleted. Treat
the unlocked state as test-only: Microsoft's documentation disagrees with itself on whether an
unlocked policy blocks deletes. Keep it unlocked **through the Phase 29 restore drill**. Under WORM,
Put Blob may create new blobs but may never overwrite one. The dump filename is timestamped and
blobctl uploads with `If-None-Match: *`, so the no-overwrite rule never fires on a healthy run.

**4.3 Lifecycle management: delete dumps after 35 days** (past the 30-day retention, so the rule
acts only once WORM allows it):

```bash
cat > /tmp/jtoye-backup-lifecycle.json <<'EOF'
{
  "rules": [
    {
      "enabled": true,
      "name": "delete-db-backups-after-35d",
      "type": "Lifecycle",
      "definition": {
        "filters": { "blobTypes": ["blockBlob"], "prefixMatch": ["jtoye-db-backups/backups/"] },
        "actions": { "baseBlob": { "delete": { "daysAfterModificationGreaterThan": 35 } } }
      }
    }
  ]
}
EOF
az storage account management-policy create --subscription "$SUB" -g jtoye-rg \
  --account-name "$BACKUP_ACCT" --policy @/tmp/jtoye-backup-lifecycle.json
```

The backup script's own prune (`RETENTION_DAYS`, `aws s3 rm`) was removed in Phase 36. Under WORM a
delete within retention fails anyway. Expiry is now the lifecycle rule's job alone, and the
CronJob identity needs no delete permission (section 6).

**4.4 HUMAN CHECKPOINT: lock the policy.** Lock only when all of these hold:
(a) the Phase 29 restore drill has passed against this container;
(b) the owner has re-confirmed the 30-day retention;
(c) the read-back in section 8 shows `state: Unlocked` and `immutabilityPeriodSinceCreationInDays: 30`.

```bash
ETAG=$(az storage container immutability-policy show --subscription "$SUB" -g jtoye-rg --account-name "$BACKUP_ACCT" --container-name jtoye-db-backups --query etag -o tsv)
az storage container immutability-policy lock --subscription "$SUB" -g jtoye-rg \
  --account-name "$BACKUP_ACCT" --container-name jtoye-db-backups --if-match "$ETAG"
```

**Reversibility: costly** (D-01). A locked policy cannot be shortened or deleted until its retention
lapses. It allows at most 5 extensions. A container under a locked policy can be deleted only when
empty, and only through the control plane. Never lock as part of a script run. Locking is a
named human step.

**Limits of what can be proven locally:** WORM is **incompatible with point-in-time restore**, so do
not enable blob PITR on the backup account. **Azurite supports none of this**: soft delete, blob
versions, version-level WORM, immutability policies and legal holds are all absent from its support
matrix. The local restore drill (36-08) proves the dump → upload → download → `pg_restore` path
only. Section 4 is proven solely by the section 8 read-backs against the real account.

---

## 5. Workload Identity

**The staging cluster was created WITHOUT the Workload Identity webhook.**
`azure-staging-provision.sh` on `phase-29-research` passes only `--enable-oidc-issuer`. This was
measured read-only on 2026-09-28: `jtoye-staging-aks` reports `oidcIssuerProfile.enabled: true` and
`securityProfile.workloadIdentity: null`, with power state `Stopped`. Enable the webhook, pinned:

```bash
az aks update --subscription "$SUB" -g jtoye-rg -n jtoye-staging-aks --enable-workload-identity
az aks show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-aks --query securityProfile.workloadIdentity.enabled -o tsv   # must print true
```

If the update refuses a stopped cluster, start the cluster first. That is an owner cost decision:
a running cluster is billed.

**Identities and federated credentials** (one identity per workload, never shared):

```bash
AKS_OIDC_ISSUER=$(az aks show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-aks --query oidcIssuerProfile.issuerUrl -o tsv)

az identity create --subscription "$SUB" -g jtoye-rg -n jtoye-staging-media-id  --location uksouth
az identity create --subscription "$SUB" -g jtoye-rg -n jtoye-staging-backup-id --location uksouth

az identity federated-credential create --subscription "$SUB" -g jtoye-rg \
  --identity-name jtoye-staging-media-id --name core-java-jtoye-staging \
  --issuer "$AKS_OIDC_ISSUER" \
  --subject system:serviceaccount:jtoye-staging:core-java \
  --audiences api://AzureADTokenExchange
az identity federated-credential create --subscription "$SUB" -g jtoye-rg \
  --identity-name jtoye-staging-backup-id --name pg-backup-jtoye-staging \
  --issuer "$AKS_OIDC_ISSUER" \
  --subject system:serviceaccount:jtoye-staging:pg-backup \
  --audiences api://AzureADTokenExchange
```

(The CLI flag is `--audiences`, plural. The audience value is `api://AzureADTokenExchange`.)

**The ServiceAccount annotation.** Phase 36 (36-09) ships the ServiceAccounts `core-java` and
`pg-backup` (`automountServiceAccountToken: false`) and the pod-template label
`azure.workload.identity/use: "true"`. The one missing piece is the client-id annotation. Phase 29
commits it to `k8s/staging` as a patch once the identities exist. A client id is an identifier,
not a secret:

```bash
az identity show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-media-id  --query clientId -o tsv
az identity show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-backup-id --query clientId -o tsv
```

```yaml
# k8s/staging: ServiceAccount patch (Phase 29 commits it with the real client ids)
apiVersion: v1
kind: ServiceAccount
metadata:
  name: core-java            # and a second document for pg-backup with the backup identity's client id
  annotations:
    azure.workload.identity/client-id: "<clientId of jtoye-staging-media-id>"
```

Until that patch lands, core-java **fails fast at startup** on the missing `AZURE_CLIENT_ID` (36-01
shape validation). That is the intended, visible state for an overlay whose identity is not yet
wired. The webhook injects `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE` and
`AZURE_AUTHORITY_HOST` at admission. No manifest sets them.

**Production** uses the same shape against its own cluster, with the subjects
`system:serviceaccount:jtoye-production:core-java` and `system:serviceaccount:jtoye-production:pg-backup`.
The production accounts are decided (D-11), but no production cluster exists yet. The production
identities, their names and their FICs are provisioned when that cluster is. This runbook does not
provision them.

---

## 6. RBAC (data plane, container scope only)

```bash
MEDIA_PID=$(az identity show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-media-id --query principalId -o tsv)
BACKUP_PID=$(az identity show --subscription "$SUB" -g jtoye-rg -n jtoye-staging-backup-id --query principalId -o tsv)
ACCT_SCOPE="/subscriptions/$SUB/resourceGroups/jtoye-rg/providers/Microsoft.Storage/storageAccounts"
```

**Media identity: Storage Blob Data Contributor on EACH media container, never on the account.**

```bash
for c in jtoye-images jtoye-quarantine; do
  az role assignment create --subscription "$SUB" --assignee-object-id "$MEDIA_PID" --assignee-principal-type ServicePrincipal \
    --role "Storage Blob Data Contributor" \
    --scope "$ACCT_SCOPE/$MEDIA_ACCT/blobServices/default/containers/$c"
done
```

This role covers everything core-java does against Blob. It carries the `containers/read` action
that the startup probe's Get Container Properties call needs (read on 2026-09-28 from the role
definition), plus blob read, write and delete for the pipeline and the Phase 27 sweep.

**Backup identity: write-only custom role on `jtoye-db-backups`.** With no read permission, a
compromised CronJob cannot exfiltrate earlier dumps, which hold PII.

The two data actions were verified on 2026-09-28 to exist as `isDataAction: true` in the
Microsoft.Storage operations list. A random control name was absent. This settles assumption A7 for
the names. Phase 29 still proves the role is SUFFICIENT with the first real upload (section 8).

```bash
cat > /tmp/jtoye-backup-writer-role.json <<EOF
{
  "Name": "J'Toye Backup Blob Writer",
  "IsCustom": true,
  "Description": "Create new blobs in jtoye-db-backups only. No read, no list, no delete.",
  "Actions": [],
  "NotActions": [],
  "DataActions": [
    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write",
    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/add/action"
  ],
  "NotDataActions": [],
  "AssignableScopes": ["/subscriptions/$SUB/resourceGroups/jtoye-rg"]
}
EOF
az role definition create --subscription "$SUB" --role-definition @/tmp/jtoye-backup-writer-role.json
az role assignment create --subscription "$SUB" --assignee-object-id "$BACKUP_PID" --assignee-principal-type ServicePrincipal \
  --role "J'Toye Backup Blob Writer" \
  --scope "$ACCT_SCOPE/$BACKUP_ACCT/blobServices/default/containers/jtoye-db-backups"
```

**Fallback** if the custom role proves insufficient: use Storage Blob Data Contributor scoped to the
`jtoye-db-backups` container. The WORM policy (section 4) then still blocks deletes and overwrites
within retention. The cost is that the identity can read dumps. Record the reason if you take the
fallback. This matches 36-08, where the CronJob never lists after uploading, because a write-only
identity cannot list.

**Explicitly NOT granted to either identity:** Storage Account Contributor, Owner or Contributor at any
scope, `Microsoft.Storage/storageAccounts/listKeys/action`, and any role at account, resource-group
or subscription scope. **Restores** (reading a dump) are done by a human operator with a
time-bound Storage Blob Data Reader assignment on `jtoye-db-backups`, removed afterwards. They are
never done by the CronJob identity.

---

## 7. Network

- Blob (`*.blob.core.windows.net`) and the Entra token endpoint (`login.microsoftonline.com`) are
  public on **443**. Both are already allowed by the NetworkPolicies' `0.0.0.0/0` rule that
  excepts `10.0.0.0/8`, `172.16.0.0/12` and `192.168.0.0/16` on port 443:
  `k8s/base/networkpolicies/20-core-java.yaml` (core-java) and `40-datastores.yaml` (pg-backup).
  Workload Identity needs **no IMDS** egress. IMDS belongs to managed identity on the node, which
  this design does not use.
- A **private endpoint on the backup account** would take an RFC1918 address, and the `except`
  list above would block it. Choosing one requires a scoped `ipBlock` for that endpoint's
  address in `40-datastores.yaml`, reviewed as a NetworkPolicy change.
- The **media account cannot be private**, because browsers fetch `jtoye-images` directly (D-06:
  the raw Blob endpoint serves images, with no CDN in this phase).

---

## 8. Read-backs Phase 29 must record (both directions, raw output kept)

**The #626 rule stated for the real staging account.** Use the URL of an image that core-java has
actually written, such as a seeded or uploaded product image read from the database or the
storefront HTML. The operator holds no data-plane role by design, so the operator does not upload a
test blob.

```bash
IMG_URL="https://$MEDIA_ACCT.blob.core.windows.net/jtoye-images/<tenant>/<key-of-a-real-derivative>.webp"
curl -s -o /dev/null -w 'anonymous GET blob: %{http_code}\n' "$IMG_URL"                          # expect 200
LIST=$(curl -s -w '\nHTTP %{http_code}' "https://$MEDIA_ACCT.blob.core.windows.net/jtoye-images?restype=container&comp=list")
tail -n1 <<< "$LIST"                                                                               # expect NOT 200
grep -c '<EnumerationResults' <<< "$LIST"                                                           # expect 0
Q=$(curl -s -o /dev/null -w '%{http_code}' "https://$MEDIA_ACCT.blob.core.windows.net/jtoye-quarantine/<tenant>/quarantine/<any-key>")
echo "anonymous GET quarantine: $Q"                                                                 # expect NOT 200
```

A LIST that returns 200, or any `<EnumerationResults` in its body, means `jtoye-images` is at level
`container`. That is a #626 regression: fix it before anything else.

**Account and container settings (control plane):**

```bash
for a in "$MEDIA_ACCT" "$BACKUP_ACCT"; do
  az storage account show --subscription "$SUB" -g jtoye-rg -n "$a" --query "{name:name,location:primaryLocation,sku:sku.name,allowBlobPublicAccess:allowBlobPublicAccess,allowSharedKeyAccess:allowSharedKeyAccess,minTls:minimumTlsVersion,httpsOnly:enableHttpsTrafficOnly}" -o json
done
# expect: media allowBlobPublicAccess=true, backup=false; allowSharedKeyAccess=false on BOTH; TLS1_2; httpsOnly=true

for c in jtoye-images jtoye-quarantine; do
  az storage container-rm show --subscription "$SUB" -g jtoye-rg --storage-account "$MEDIA_ACCT" -n "$c" --query "{name:name,publicAccess:publicAccess}" -o json
done
az storage container-rm show --subscription "$SUB" -g jtoye-rg --storage-account "$BACKUP_ACCT" -n jtoye-db-backups --query "{name:name,publicAccess:publicAccess}" -o json
# expect: jtoye-images Blob; jtoye-quarantine None; jtoye-db-backups None

az storage container immutability-policy show --subscription "$SUB" -g jtoye-rg --account-name "$BACKUP_ACCT" --container-name jtoye-db-backups -o json
# expect: immutabilityPeriodSinceCreationInDays 30, state Unlocked (Locked only after the section 4.4 checkpoint)

az storage account blob-service-properties show --subscription "$SUB" -g jtoye-rg --account-name "$BACKUP_ACCT" --query deleteRetentionPolicy -o json
# expect: enabled true, days 14

az storage account management-policy show --subscription "$SUB" -g jtoye-rg --account-name "$BACKUP_ACCT" -o json
# expect: the one rule, prefix jtoye-db-backups/backups/, delete after 35 days
```

**RBAC (the container scopes carry the roles; nothing wider does):**

```bash
for c in jtoye-images jtoye-quarantine; do
  az role assignment list --subscription "$SUB" --scope "$ACCT_SCOPE/$MEDIA_ACCT/blobServices/default/containers/$c" -o table
done
az role assignment list --subscription "$SUB" --scope "$ACCT_SCOPE/$BACKUP_ACCT/blobServices/default/containers/jtoye-db-backups" -o table
az role assignment list --subscription "$SUB" --assignee "$MEDIA_PID"  --all -o table   # expect: only the two container scopes
az role assignment list --subscription "$SUB" --assignee "$BACKUP_PID" --all -o table   # expect: only the backup container scope
```

**Workload Identity inside the pod** (assumption A6: WI works with `automountServiceAccountToken:
false`, because the webhook projects its own token volume):

```bash
kubectl --context jtoye-staging -n jtoye-staging exec deploy/core-java -- ls /var/run/secrets/azure/tokens
# expect: azure-identity-token
kubectl --context jtoye-staging -n jtoye-staging exec deploy/core-java -- ls /var/run/secrets/kubernetes.io/serviceaccount
# expect: an error (no automounted API token)
```

Then the functional path (proof standard 5): core-java starts without the storage probe failing, an
upload travels quarantine → worker → WebP derivative, the storefront renders it
(`naturalWidth > 0`), and the first pg-backup run exits 0 and leaves exactly one new `.dump` under
`backups/`. That last run is what proves the write-only role is sufficient.

---

## 9. If a name is taken by the time Phase 29 runs

Account names are global, and D-11 only recorded them. Nothing reserves a name until the
account exists. Create the accounts promptly after the section 2.1 check. If any `check-name`
returns `nameAvailable: false`:

1. **Stop.** Do not pick a substitute. The names are embedded in every persisted staging and
   production image URL (D-06), so they are an owner decision (D-11, Reversibility: costly).
2. Get a new owner decision and record it as an amendment to D-11 with the new `check-name` output.
3. Update the overlays and goldens **in one change**: the account endpoints in
   `k8s/staging/configmap-patch.yaml` and `k8s/production/configmap-patch.yaml`, then
   `k8s/scripts/render-golden.sh --write`, then review the golden diff so that it shows only the
   endpoint change.
4. Before the first deploy, that is all. After it, a changed media account also needs a tenant-looped
   rewrite of the persisted absolute image URLs (D-05 recipe: loop tenants with `set_config` and
   prove a non-zero row count), plus the CSP `img-src` and `next.config.mjs` `remotePatterns`
   origins.
