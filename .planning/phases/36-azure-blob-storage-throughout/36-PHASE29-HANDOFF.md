# Phase 36 → Phase 29 Handoff: the Blob estate, the shrunk operator-secret list, and the merge-conflict map

**Written:** 2026-09-29 by plan 36-17 (BLOB-10). **Audience:** whoever resumes Phase 29 on
`phase-29-research`. **Status of that branch:** read-only from Phase 36. Nothing here was written
to it. Its head was `ebee67fe` before and after this plan (`git log --oneline -1 phase-29-research`,
recorded in 36-17-SUMMARY).

`phase-29-research` is the canonical, paused Phase 29 body. It is not a cleanup candidate. Phase 29
consumes this document from `main` after the Phase 36 merge. It does not need to edit its own
branch to read it.

Every number below comes from a command shown next to it. Re-run the commands at merge time: this
document states what was true at `720be25d` (the 36-17 metrics commit), and `main` will have moved.

---

## 1. Decisions superseded

| Phase 29 decision (29-CONTEXT.md on `phase-29-research`) | Replaced by (36-CONTEXT.md) |
|---|---|
| **D-11**: media storage is real AWS S3 in eu-west-2 | **D-06** (raw Blob endpoint `https://<account>.blob.core.windows.net/<container>/...`, no CDN), **D-07** (containers `jtoye-images` public at level `blob`, `jtoye-quarantine` private), **D-11** (accounts: staging media `jtoyestgmedia`, uksouth) |
| **D-12**: the logical dump goes to a dedicated AWS S3 backup bucket, "off-cluster AND off-Azure" | **D-01** (a separate Azure backup account in another region, WORM + soft delete on its container), **D-07** (container `jtoye-db-backups`, prefix `backups/`), **D-11** (staging backup `jtoyestgbackup`, ukwest) |

**The trade D-01 records, and why it matters to Phase 29.** The Blob backup survives the deletion
of objects and the compromise of an application credential. It does **not** survive the loss of the
whole Azure subscription, and that was the case D-12 existed for. The owner accepted this on
2026-09-28. An off-Azure copy is listed as a deferred idea in 36-CONTEXT.md, to revisit before real
customer data if subscription-loss risk is re-weighed. Phase 29 D-10 is unchanged: provider PITR is
the first line, and the logical dump is the second.

Read the Phase 29 side with `git show phase-29-research:.planning/phases/29-deployable-staging-with-its-own-monitoring/29-CONTEXT.md`
(D-11 and D-12 are its lines 95-101 at `ebee67fe`).

---

## 2. The operator-secret list: 7 values become 3 (D-03)

**Measured on `phase-29-research`, read-only:**

```bash
git show phase-29-research:scripts/staging-secrets.sh \
  | sed -n '/^REQUIRED_VALUES=(/,/^)/p' | grep -oE '^\s+[A-Z_]+' | tr -d ' ' > /tmp/req
wc -l < /tmp/req                                         # 23  (every value the preflight requires)
grep -E '^(AWS_|ALERTMANAGER_SMTP_)' /tmp/req            # the 7 operator-only values
grep -E '^(AWS_|ALERTMANAGER_SMTP_)' /tmp/req | grep -vc '^AWS_'   # 3
```

The seven are the operator-only values that `~/.jtoye/staging-operator.env` holds (named in
`29-PROVISIONING-EVIDENCE.md` §9.2 on that branch, "populated=0 empty=7 of 7"):

| Value name (in `staging-secrets.sh`) | After Phase 36 |
|---|---|
| `AWS_MEDIA_ACCESS_KEY_ID` | **removed.** core-java authenticates with Workload Identity (D-02). No key exists. |
| `AWS_MEDIA_SECRET_ACCESS_KEY` | **removed** |
| `AWS_BACKUP_ACCESS_KEY_ID` | **removed.** pg-backup authenticates with Workload Identity. |
| `AWS_BACKUP_SECRET_ACCESS_KEY` | **removed** |
| `ALERTMANAGER_SMTP_PASSWORD` | kept |
| `ALERTMANAGER_SMTP_FROM` | kept |
| `ALERTMANAGER_SMTP_TO` | kept |

So `~/.jtoye/staging-operator.env` drops from **7** names to the **3** Alertmanager SMTP names, and
`REQUIRED_VALUES` drops from **23** to **19**.

**What Phase 29 must change in `scripts/staging-secrets.sh` (a file only its branch has):**
- Remove the four `AWS_*` entries from `REQUIRED_VALUES`, with their `# --- real AWS …` header.
- Remove both `apply_secret s3-media-credentials` and `apply_secret s3-backup-credentials` calls,
  and their two lines in the created-secrets listing (`s3-media-credentials)` /
  `s3-backup-credentials)`). A storage Secret must not exist in staging. The Phase 36 render
  invariant **INV-9** fails any staging or production render that references a Secret whose name
  matches `s3|storage|blob|azure` (`k8s/scripts/check-render-invariants.sh`).
- Rewrite the three comment lines that still describe the old store as the local path (lines 39,
  245 and 576 at `ebee67fe`). With the four removals above, these are the **7 lines** the 36-16
  residue gate would flag in this file after the merge (lines 39, 245, 576, 578, 582, 663, 664;
  see §6.2).

No key, connection string or SAS token replaces them. The client IDs in §3 are identifiers, not
secrets, and they are committed in a patch.

---

## 3. Provisioning: follow the runbook, in order

**The specification is `docs/runbooks/azure-blob-provisioning.md`** (written in 36-05, on `main`
after the merge). Run its sections in order. This handoff does not repeat the commands. It only
names the steps Phase 29 would otherwise miss.

1. **Pin the subscription first** (runbook §1). This machine's ambient CLI default is the
   EMPLOYER's subscription, and an empty `--subscription ""` silently resolves to it. Every `az`
   line passes `--subscription "$SUB"` after the equality assertion.
2. **Register `Microsoft.Storage` and re-run the name check** (runbook §2.1). On 2026-09-28,
   `az storage account check-name` gave no verdict for any of the four D-11 names, because the
   provider is NotRegistered (rc=3 `SubscriptionNotFound`). Only DNS evidence exists (NXDOMAIN).
   This is `.planning/WINDOWS.md` #1. If a name is taken, runbook §9 applies: a new owner decision,
   never a substitute name.
3. **Enable the Workload Identity webhook on the cluster** (runbook §5). The staging cluster was
   created with `--enable-oidc-issuer` only. `scripts/azure-staging-provision.sh` on
   `phase-29-research` has no `--enable-workload-identity` (measured: 0 occurrences; control:
   `--enable-oidc-issuer` 2 occurrences).

   ```bash
   az aks update --subscription "$SUB" -g jtoye-rg -n jtoye-staging-aks --enable-workload-identity
   az aks show   --subscription "$SUB" -g jtoye-rg -n jtoye-staging-aks --query securityProfile.workloadIdentity.enabled -o tsv   # must print true
   ```

   If the update refuses a stopped cluster, starting it is an owner cost decision.
   Phase 29 should also add `--enable-workload-identity` to its own `az aks create` in
   `azure-staging-provision.sh`. Otherwise a re-provision loses the webhook again.
4. **Create the accounts, containers, data protection, identities, federated credentials and
   container-scoped RBAC** (runbook §§2.2, 3, 4, 5, 6). Shared-key access is off on all accounts.
   The backup identity's custom role is write-only.
5. **Commit the ServiceAccount client-id patch to `k8s/staging`.** Phase 36 (36-09) ships the
   `core-java` and `pg-backup` ServiceAccounts (`automountServiceAccountToken: false`) and the
   pod-template label `azure.workload.identity/use: "true"`. The one missing piece is this
   annotation, with each identity's real client ID:

   ```yaml
   metadata:
     name: core-java          # second document: pg-backup, with the backup identity's client id
     annotations:
       azure.workload.identity/client-id: "<clientId of jtoye-staging-media-id>"
   ```

   **Until that patch lands, core-java crash-loops by design.** Its shape validation (36-01/36-06)
   fails at startup on the missing `AZURE_CLIENT_ID`, because only the webhook injects it. That is
   the intended, visible state for an overlay whose identity is not wired yet. Do not "fix" it by
   setting the variable in a manifest. Regenerate the staging golden after adding the patch
   (`k8s/scripts/render-golden.sh --write`).

---

## 4. Verification items Phase 29 must record (runbook §8, both directions, raw output kept)

| Item | What to prove | Why it is Phase 29's |
|---|---|---|
| **A6** | Workload Identity works with `automountServiceAccountToken: false`: `/var/run/secrets/azure/tokens/azure-identity-token` exists in the core-java pod, and `/var/run/secrets/kubernetes.io/serviceaccount` does not | Needs the live AKS admission webhook. `.planning/WINDOWS.md` #3 |
| **A7** | The custom backup role's data-action names are SUFFICIENT: the first real pg-backup run exits 0 and leaves exactly one new `.dump` under `backups/` | The names were verified to exist (36-05). Only a real upload proves the role is enough. |
| **A10** | Anonymous blob access is explicitly **enabled on the media account only** (`allowBlobPublicAccess=true` on the media account, `false` on the backup account). New accounts default to disallowing it | A control-plane read-back against the real accounts |
| **A12** | The backup account sits in the region pair: uksouth ↔ ukwest. This was measured on 2026-09-28 through the subscription-pinned Locations API; Phase 29 records `primaryLocation` of each created account | Confirms the created accounts match D-11 |
| **#626 read-back** | On the real staging media account: anonymous GET of a real derivative URL = 200; anonymous container LIST refused (not 200, zero `<EnumerationResults`); anonymous GET on `jtoye-quarantine` refused | Proven against Azurite by 36-01/36-06/36-12. The staging half of BLOB-02 |
| **WORM checkpoint** | The 30-day container-level time-based retention on `jtoye-db-backups` stays **UNLOCKED** through the Phase 29 restore drill (read-back `state: Unlocked`). A **human** then locks it (runbook §4.4) | D-01 reversibility is **costly**: a locked policy cannot be shortened or removed until retention lapses. Never lock in a script run. |
| **Functional path** | core-java starts with the storage probe passing; an upload goes quarantine → worker → WebP derivative; the storefront renders it (`naturalWidth > 0`) | Proof standard 5: the structural checks above can pass while the function is broken |

Soft delete (14 days) and the lifecycle rule (delete `backups/` at 35 days) are read back the same
way (runbook §8).

---

## 5. Network caveat

Blob (`*.blob.core.windows.net`) and the Entra token endpoint are public on 443. Both are already
allowed by the NetworkPolicies' `0.0.0.0/0` rule, which excepts RFC1918 on port 443 (runbook §7).
Workload Identity needs no IMDS egress.

**A private endpoint on the backup account would need a scoped `ipBlock`.** It takes an RFC1918
address, which the `except` list blocks. Choosing one means adding a scoped `ipBlock` for that
endpoint's address in `k8s/base/networkpolicies/40-datastores.yaml`, declaring it in
`check-render-invariants.sh`'s INV-7 second arm (`NETPOL_IPBLOCK_EXPECTED`, which Phase 29 created),
and reviewing it as a NetworkPolicy change. The media account cannot be private: browsers fetch
`jtoye-images` directly (D-06).

---

## 6. Merge-conflict map

### 6.1 How it was computed (git only)

```bash
MB29=$(git merge-base origin/main phase-29-research)   # bb2ae65d (Phase 28, #630)
MB36=$(git merge-base HEAD origin/main)                # db725c94 (#757)
git rev-list --count $MB29..phase-29-research          # 96
git diff --name-only $MB29 phase-29-research | sort > /tmp/p29   # 112 files
git diff --name-only $MB36..HEAD            | sort > /tmp/p36   # 219 files (at 720be25d)
comm -12 /tmp/p29 /tmp/p36                              # 35 files, the table below
```

The plan's objective quoted "46 of this phase's files" at plan time. The measured intersection with
the plan's own command is **35**. The table uses the measurement. Re-run it at merge time: 36-18 and
anything merged to `main` after 36-17 can add rows.

This map covers files **both** sides changed since their merge-bases. Phase 29 also diverged from
everything `main` gained between `bb2ae65d` and `db725c94`. Those files merge textually as ordinary
catch-up, and they are outside this table.

### 6.2 Standing rules for the whole merge

- **Generated files are never hand-merged.** Take either side, then regenerate:
  `k8s/scripts/render-golden.sh --write` for `k8s/goldens/*.yaml` (commit the result with the
  source change), and `scripts/docs-freshness.sh --write` for `docs/metrics.json`. Then update the
  prose counts until `scripts/check-doc-metrics.sh` and `scripts/check-test-count-oracle.sh` pass.
- **The retired object store stays retired.** After the merge, `scripts/check-no-object-store-residue.sh`
  (36-16, in ops-contracts CI) will flag what Phase 29 added. Measured by matching the gate's three
  pattern classes (R-1, R-1b, R-2) against every line `phase-29-research` added since `bb2ae65d`:
  **115 lines in 25 files**. Of those, the 71 lines in `.planning/` records are already
  path-allowlisted ("planning history"). The 24 lines in the two goldens disappear on
  regeneration, because their hits came from base manifests that Phase 36 rewrote. That leaves
  **20 lines in 6 files** to rewrite, not to allowlist:
  `scripts/staging-secrets.sh` (7, see §2), `k8s/base/networkpolicies/40-datastores.yaml` (5),
  `docs/architecture/SYSTEM_DESIGN_V2.md` (4), `k8s/base/networkpolicies/20-core-java.yaml` (2),
  `infra/dependency-horizons.yaml` (1), `k8s/local/rabbitmq-cluster-delete-patch.yaml` (1).
  Widening the allowlist to absorb them is the wrong fix. Its entries are line-level and reasoned,
  and these lines describe the live system, so they would be false.
- **Line-level allowlist entries move when lines move.** Phase 29's edits to `k8s/LOCAL.md` are
  same-line replacements (four hunks, +4/-4), and its ADR-0002 edit above line 21 is line 3 only.
  So the entries at `ADR-0002…:21` and `k8s/LOCAL.md:139…2009` hold as measured. Run the residue
  gate after the merge regardless: `stale_entries` must be 0.
- **Close-out sweep after the merge:** `k8s/scripts/check-render-invariants.sh`,
  `k8s/scripts/check-env-contract.sh`, `k8s/scripts/check-no-plaintext-secrets.sh`,
  `scripts/check-dependency-horizons.sh`, `scripts/check-no-object-store-residue.sh`,
  `scripts/check-doc-citations.sh` (baseline: 5 pre-existing `.planning/codebase` failures),
  `scripts/check-gate-enforcement.sh`, `scripts/check-handoff-contract.sh`, and actionlint on the
  workflows. Show each one failing on a planted break before trusting its pass.

### 6.3 The 35 files

| # | File | Phase 29 change | Phase 36 change | Resolution rule |
|---|---|---|---|---|
| 1 | `.github/workflows/ci-cd.yaml` | +137/-3: deploy-job steps (federated Azure login, AKS credentials, NetworkPolicy denial proof, running-digest assertion, alert-corpus drift) | +48/-2: blobctl test/gofmt/vet steps, residue-gate step | Key-local: keep both sets of steps. Then actionlint and `check-gate-enforcement.sh`. |
| 2 | `.planning/ROADMAP.md` | +43/-3: Phase 29 plan rows and progress | +66/-0: Phase 36 section and plan rows | Take `main`'s file and re-apply Phase 29's own rows. Each phase owns its own section. |
| 3 | `.planning/STATE.md` | +76/-8: Phase 29 pause state | +25/-13: Phase 36 progress | Hand-merge. The `gsd_run query state.*` verbs were measured destructive on this project: never use them to "resolve" it. |
| 4 | `.planning/codebase/INTEGRATIONS.md` | +5/-5: line-citation refresh, incl. the retired object-store bullet | +4/-5: object-store bullet rewritten to Blob/Azurite | Take Phase 36's storage prose (Phase 29's edits there cite a service that no longer exists). Re-derive every `file:line` against the merged tree. `check-doc-citations.sh`. |
| 5 | `.planning/codebase/STACK.md` | +4/-4: compose line citations | +3/-3: storage lines | Same as #4: Phase 36 prose, citations re-derived from the merged compose file. |
| 6 | `AGENTS.md` | +1/-1: Testing counts (2812) | +10/-9: storage prose, counts (4130) | Take Phase 36. Both sides' counts are superseded by the regenerated `docs/metrics.json` (#11). `check-doc-metrics.sh`, `check-claims.sh`. |
| 7 | `CLAUDE.md` | +1/-1: Testing counts (2812) | +8/-7: storage prose, counts (4130) | Same as #6. Then re-run the residue gate: its CLAUDE.md coverage is line-sensitive. |
| 8 | `HANDOFF.md` | +211/-2: Phase 29 deltas | +47/-6: 2026-09-28/29 deltas | Keep every dated delta from both sides, newest first. Re-derive the `EXPECT N` gate count. `check-handoff-contract.sh`. |
| 9 | `README.md` | +3/-3: test badge (2812) | +6/-6: badge and test list (4130) | Counts from the regenerated manifest (#11). `check-doc-metrics.sh`. |
| 10 | `core-java/src/main/resources/application.yml` | +31/-1: `sslMode=${DB_SSL_MODE:prefer}` on the datasource URL; `spring.data.redis.ssl.enabled` | +20/-7: `storage.blob.*` replaces `storage.s3.*` | Key-local: keep both. No `storage.s3.*` key survives. Run the core-java unit suite (the storage shape tests read this file). |
| 11 | `docs/metrics.json` | +2/-2: 2807 → 2812 | regenerated 4042 → 4130 | **Generated.** `scripts/docs-freshness.sh --write` on the merged tree. Never hand-merge. |
| 12 | `docker-compose.full-stack.yml` | +30/-1: Keycloak `CORE_API_WEB_ORIGINS`/`EDGE_API_WEB_ORIGINS` and the envsubst name list | +61/-85: retired store and init container removed, Azurite added | Key-local: keep both. No retired-store service. Then re-derive `infra/dependency-horizons.yaml` `sites:` line numbers (#15). |
| 13 | `docs/architecture/SYSTEM_DESIGN_V2.md` | +52/-1: ADR-0002 hybrid backup narrative, "logical dump to AWS S3" | +13/-11: storage on Azure Blob | Keep Phase 29's structure and rewrite its backup destination as the Azure backup account (D-01). These are 4 of the 19 residue lines. |
| 14 | `docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md` | +90/-1: Status line → Accepted (line 3), plus an appended block after line 78 | +24/-0: the 2026-09-28 supersession note, appended after line 78 | **Guaranteed textual conflict (both append at line 78).** The ADR is append-only: keep both blocks in date order, Phase 29's 2026-08-10 block before Phase 36's 2026-09-28 note. Take Phase 29's line 3. |
| 15 | `infra/dependency-horizons.yaml` | +289/-38: new rows (cert-manager, rabbitmq-cluster-operator, ingress-nginx, agnhost), a schema header, many `sites:` line refreshes | +42/-94: rows `minio`/`minio-mc` removed, `azurite` added | Keep Phase 29's new rows and header, and Phase 36's removals and `azurite`. **Re-derive every `sites:` line number** against the merged compose, monitoring and CI files: both sides moved them. Rewrite the one comment line that names the old store. `check-dependency-horizons.sh`. |
| 16 | `docs/runbooks/backups.md` | +28/-0: the scripted managed-PITR drill section (`staging-pitr-drill.sh`) | +249/-162: destination rewritten to Blob; the script no longer prunes | Take Phase 36's file and re-insert Phase 29's PITR section. That section is about the first line (PITR) and does not name the dump's destination. |
| 17 | `k8s/DEPLOYMENT.md` | +56/-0: staging deploy material | +40/-9: storage via Workload Identity and the provisioning runbook | Keep both. Drop any Phase 29 text that tells an operator to create a storage Secret. |
| 18 | `k8s/LOCAL.md` | +4/-4: four cross-file line citations refreshed | +86/-31: Azurite path; Phase 26 evidence fenced under a pre-Phase-36 banner | Take Phase 36, re-apply Phase 29's four citation lines, then re-derive them against the merged files (they point at lines both sides moved). Residue gate `stale_entries` 0. |
| 19 | `k8s/QUICK_START.md` | +87/-0: staging quick start | +16/-14: storage secrets replaced by Workload Identity | Same as #17. |
| 20 | `k8s/base/configmap.yaml` | +337/-12: `db.ssl-mode`, `db.egress-cidr`, `redis.ssl`, `redis.egress-cidr`, `keycloak.*`, `alerting.*`, `cert-manager.*`, and more | +51/-21: `storage.blob.*`, `backup.blob.*`; all `s3.*` removed | The key sets are disjoint: keep both. No `s3.*` key survives. Rewrite any Phase 29 comment that describes `s3.*` as live. `check-env-contract.sh`, then regenerate goldens. |
| 21 | `k8s/base/core-java-deployment.yaml` | +86/-0: env `DB_SSL_MODE`, `REDIS_PORT`, `REDIS_SSL`, `NOTIFICATION_UNSUBSCRIBE_ONE_CLICK_BASE_URL`, and others | +30/-25: storage env from `storage.blob.*`; `serviceAccountName: core-java`; no storage Secret ref | Keep both env sets. **No `s3-media-credentials` reference** (Phase 29 inherited it from the old base; it did not add it). INV-9 and INV-10 must pass. |
| 22 | `k8s/base/kustomization.yaml` | +270/-0: monitoring resources (Prometheus, exporters, Alertmanager, Grafana), Keycloak, `rabbitmq-cluster.yaml`, the `prometheus-alerts` generator, and replacements (cert-manager issuer annotation, egress ports) | +3/-0: `serviceaccounts.yaml` | Keep both resource lists and all of Phase 29's replacements. |
| 23 | `k8s/base/networkpolicies/20-core-java.yaml` | +180/-13: ipBlock egress for the managed Postgres/Redis, header rewrite | +13/-9: port 9000 removed | Keep Phase 29's ipBlock rules. Remove 9000 (Phase 36). Rewrite the 2 comment lines that still name the old store on 9000. |
| 24 | `k8s/base/networkpolicies/40-datastores.yaml` | +235/-8: ipBlock rules, monitoring exporters, rewritten header | +10/-14: the old store's mirror policy and port 9000 removed | Keep Phase 29's rules. Keep Phase 36's removal of the mirror. Rewrite the 5 comment lines that name the old store. See §5 for a future private endpoint. |
| 25 | `k8s/goldens/production.yaml` | +3487/-450 | +73/-54 | **Generated.** `k8s/scripts/render-golden.sh --write` after every source row is merged. Never hand-merge. |
| 26 | `k8s/goldens/staging.yaml` | +3711/-449 | +73/-54 | **Generated.** Same as #25, and again after the §3 client-id patch lands. |
| 27 | `k8s/local/configmap-patch.yaml` | +53/-0: `keycloak.*`, `notification.*` | +59/-22: Azurite `storage.blob.*`/`backup.blob.*`, `s3.*` removed | Disjoint keys: keep both. LOC-7 must pass. |
| 28 | `k8s/local/kustomization.yaml` | +95/-0: `rabbitmq-cluster-delete-patch.yaml`, and replacements for NetworkPolicy egress ports and ipBlock CIDRs | +10/-4: `storage-env-patch.yaml`; header names Azurite | Keep both patch lists and Phase 29's replacements. Its egress-index selectors (`spec.egress.N`) must still point at the right rules once Phase 36's port-9000 removal (#23, #24) is merged: re-render and check that each replaced value lands where intended. The Phase 29 file `rabbitmq-cluster-delete-patch.yaml` has 1 comment line naming the old store: rewrite it. |
| 29 | `k8s/production/configmap-patch.yaml` | +22/-10: comments only (the reason `keycloak.client-id` is not overridden, rewritten for plan 29-08) | +18/-3: production `storage.blob.*`/`backup.blob.*` (`jtoyeprodmedia`, `jtoyeprodbackup`) | Different regions of the file: keep Phase 29's comment and Phase 36's keys. INV-8 must pass. |
| 30 | `k8s/production/kustomization.yaml` | +112/-8 | +3/-0: `workload-identity-patch.yaml` | Keep both. |
| 31 | `k8s/scripts/check-env-contract.sh` | +1/-1: one reviewed-omission reason (`EDGE_MANAGEMENT_PORT`, #550) | +13/-8: storage entries | Disjoint entries: keep both. |
| 32 | `k8s/scripts/check-render-invariants.sh` | +787/-14: INV-7 second arm `NETPOL_IPBLOCK_EXPECTED` (with `__REDIS_PORT__`; staging `redis.port` is **10000**, Azure Managed Redis since 2026-08-10), monitoring policies, `NETPOL_INFRA_EXPECTED` core-java `__DB_PORT__ 6379 9000 9093` / pg-backup `__DB_PORT__ 9000` | +432/-44: INV-8/9/10, LOC-7, INV-4 literal list; 9000 dropped from `NETPOL_INFRA_EXPECTED` | **Keep both sides' invariants.** `NETPOL_INFRA_EXPECTED` becomes Phase 29's lists with 9000 removed: core-java-allow `__DB_PORT__ 6379 9093`, pg-backup-allow `__DB_PORT__`. Keep Phase 29's second arm, so staging Redis on 10000 stays declared. Keep INV-8/9/10 and LOC-7. Azurite's 10000 is local-only and does not collide: INV-4 forbids localhost-family literals, not the port. Arms: planting 9000 on a policy must FAIL INV-7 on every target; an undeclared ipBlock must FAIL the second arm. |
| 33 | `k8s/staging/configmap-patch.yaml` | +250/-11: staging hostnames, `redis.port: "10000"`, `redis.ssl`, `db.ssl-mode`, `smtp.*`, `keycloak.*`, `cert-manager.*`, and more; a comment "Media storage (s3.*) is deliberately NOT overridden here" | +16/-3: `storage.blob.endpoint`/`public-url` (`jtoyestgmedia`), `backup.blob.endpoint` (`jtoyestgbackup`) | Keep Phase 29's staging values and Phase 36's storage and backup keys. **Phase 29's "not overridden" comment is now false** (staging does override storage), so rewrite it. INV-8 must pass. |
| 34 | `k8s/staging/kustomization.yaml` | +175/-7 | +3/-0: `workload-identity-patch.yaml` | Keep both. Phase 29 then adds the §3 ServiceAccount client-id patch here. |
| 35 | `scripts/gates/gate-enforcement.conf` | +62/-0: comment block only (plan 29-05: why the three staging provisioning scripts are not listed) | +7/-1: the content-types gate's exemption row removed (it is now wired into the nightly) | Different regions: keep Phase 29's comment block and Phase 36's removal. Do not re-add a `check-media-content-types.sh` exemption. `check-gate-enforcement.sh`. |

### 6.4 Files only Phase 29 has that still need a Phase 36 edit

These are not conflicts, because Phase 36 never touched them. They are wrong after the merge anyway:

- `scripts/staging-secrets.sh`: see §2.
- `scripts/azure-staging-provision.sh`: add `--enable-workload-identity` to `az aks create` (§3 step 3).
- `k8s/local/rabbitmq-cluster-delete-patch.yaml`: 1 comment line (§6.2).
- Phase 29's plan files for 29-11 onward, where they name the AWS values or buckets. They are
  `.planning/` records, allowlisted as history, but a plan that is still to execute must describe
  Blob. Update them on Phase 29's branch when it resumes, not from here.
