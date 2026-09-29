# API Coverage — Azure Blob Storage (data plane, Java SDK in core-java)

> Full coverage by default. Opt-outs are explicit, reasoned decisions.
> Detector: `api-coverage.cjs` fired on the phase scope (nouns `sdk`, `api`) at plan time 2026-09-28.
> Scope of this matrix: `com.azure:azure-storage-blob` 12.35.1 + `com.azure:azure-identity` 1.18.6 as used by
> core-java through `BlobObjectStore` / `AzureBlobObjectStore` (36-01, 36-06). Control-plane capabilities are listed
> because the phase depends on them; where the app does not call them, the row says who does.

| capability | decision | reason |
|---|---|---|
| upload-block-blob (put, overwrite) | INTEGRATE | |
| conditional-create (If-None-Match *) | INTEGRATE | |
| set-blob-http-headers (Content-Type, Cache-Control) | INTEGRATE | |
| download-blob | INTEGRATE | |
| delete-blob-if-exists | INTEGRATE | |
| blob-exists / get-blob-properties | INTEGRATE | |
| create-container-if-not-exists (with public access level) | INTEGRATE | |
| get-container-properties (public access level probe) | INTEGRATE | |
| container-exists | INTEGRATE | |
| anonymous-public-read (container access level blob) | INTEGRATE | |
| retry-policy (RequestRetryOptions) | INTEGRATE | |
| connection-string auth (Azurite emulator) | INTEGRATE | |
| workload-identity auth (WorkloadIdentityCredential) | INTEGRATE | |
| set-container-acl / change public access from the app | OPT-OUT | explicitly out of scope: Set Container ACL cannot be authorized with Entra ID; access levels are set by the operator (docs/runbooks/azure-blob-provisioning.md) and asserted at boot |
| list-blobs from core-java | OPT-OUT | not needed: the app addresses objects by server-generated key only; listing would also widen the credential's read surface |
| copy-blob / start-copy-from-url | OPT-OUT | not needed: copy-on-write is a database concept (media_asset rows), no object copy is ever performed |
| account-key / shared-key auth against a real account | OPT-OUT | explicitly out of scope (D-02): no account key in any runtime; shared-key access is disabled on the real accounts |
| SAS generation (service or user-delegation) | OPT-OUT | explicitly out of scope (D-02): no SAS stored or minted; uploads go through core-java's safe pipeline, never direct-to-storage |
| DefaultAzureCredential chain | OPT-OUT | not needed: explicit WorkloadIdentityCredential is deterministic and avoids IMDS probing blocked by the 443-only NetworkPolicy |
| staged/multipart upload (stageBlock/commitBlockList) | OPT-OUT | not needed: images are capped at 5 MB (storage.max-file-size-bytes) and the SDK's single-shot upload handles them |
| leases | OPT-OUT | not needed: concurrency is handled by the media_asset @Version lock (V59), not by blob leases |
| snapshots | OPT-OUT | not needed: objects are immutable by key construction (#489); no point-in-time object state is required |
| blob versioning | OPT-OUT | not needed yet: backups rely on container WORM + soft delete (D-01); unsupported by Azurite so untestable locally |
| soft delete (account/container property) | OPT-OUT | not called by the app: configured on the backup account by the operator in Phase 29 (runbook §4) |
| immutability policy / legal hold | OPT-OUT | not called by the app: container-level WORM on jtoye-db-backups is operator-configured in Phase 29, unlocked through the drill, then human-locked (D-01) |
| lifecycle management | OPT-OUT | not called by the app: backup retention rule is operator-configured (runbook §4); the quarantine horizon stays in the application sweep (Phase 27 contract unchanged) |
| blob index tags / metadata | OPT-OUT | not needed: all object facts live in Postgres (media_asset) |
| access tiers (hot/cool/archive) | OPT-OUT | not needed yet: default hot tier; revisit with real storage volume |
| CORS rules | OPT-OUT | not needed: the browser loads images with plain img elements, which do not require CORS |
| static website hosting | OPT-OUT | explicitly out of scope: images are served from the raw Blob endpoint (D-06) |
| change feed / event grid notifications | OPT-OUT | not needed: the media pipeline is driven by the transactional outbox and RabbitMQ, not storage events |
| batch delete | OPT-OUT | not needed: deletes are single-object and ref-counted (IMG-01) |
| append / page blobs | OPT-OUT | not needed: only block blobs are stored |
| query acceleration | OPT-OUT | not needed: blobs are images and dumps, never queried |
| object replication / geo-redundant read | OPT-OUT | not needed: D-01's regional separation is achieved by a separate backup account in another region |
| customer-managed encryption keys | OPT-OUT | not needed yet: Microsoft-managed encryption at rest; revisit before real customer data |
| private endpoints | OPT-OUT | explicitly out of scope for media (browsers must reach it, D-06); for the backup account, recorded as a Phase 29 option with its NetworkPolicy caveat |
| storage diagnostic logs / metrics | OPT-OUT | not needed yet: Phase 29 owns staging monitoring (DPLY-03) |

# API Coverage — Azure Blob Storage (data plane, Go SDK in blobctl)

> A second integration against the same need starts from the same full-coverage baseline; each
> capability is re-decided for the backup uploader (36-04, 36-08), not carried over.
> Scope: `github.com/Azure/azure-sdk-for-go/sdk/storage/azblob` v1.8.1 + `sdk/azidentity` v1.14.1.

| capability | decision | reason |
|---|---|---|
| blobctl: upload-file (block blob) | INTEGRATE | |
| blobctl: conditional-create (If-None-Match *) | INTEGRATE | |
| blobctl: list-blobs by prefix | INTEGRATE | |
| blobctl: download-blob | INTEGRATE | |
| blobctl: create-container-if-not-exists | INTEGRATE | |
| blobctl: connection-string auth (Azurite emulator only) | INTEGRATE | |
| blobctl: workload-identity auth (NewWorkloadIdentityCredential) | INTEGRATE | |
| blobctl: delete-blob | OPT-OUT | explicitly out of scope: retention is WORM + soft delete + lifecycle (D-01); a backup identity that cannot delete cannot destroy old dumps |
| blobctl: overwrite an existing blob | OPT-OUT | explicitly out of scope: WORM forbids overwrites and timestamped names are unique; a collision is an error (exit 3) |
| blobctl: create-container in workload-identity mode | OPT-OUT | explicitly out of scope: containers and their policies are operator-provisioned; the backup identity is write-only |
| blobctl: account-key / SAS auth against a real account | OPT-OUT | explicitly out of scope (D-02) |
| blobctl: set-container-acl | OPT-OUT | not needed: the backup container is private and operator-provisioned |
| blobctl: leases / snapshots / versioning / tags / tiers | OPT-OUT | not needed: dumps are write-once artifacts managed by container policy |
| blobctl: staged upload tuning (block size, concurrency) | OPT-OUT | not needed yet: SDK defaults for UploadFile; revisit if dump size makes upload time matter |
