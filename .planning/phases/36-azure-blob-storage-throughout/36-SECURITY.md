---
phase: "36"
slug: "azure-blob-storage-throughout"
status: verified
# threats_open = count of OPEN threats at or above workflow.security_block_on severity (the blocking gate)
threats_open: 0
asvs_level: 2
block_on: medium
created: "2026-09-29"
---

# Phase 36 — Security

> Per-phase security contract: threat register, accepted risks, and audit trail.
> Register authored at plan time: all 18 plans carry a `<threat_model>` block (78 register rows, 62 unique threat IDs; `T-36-SC` recurs per plan and is listed per plan). Audited at ASVS L2 by gsd-security-auditor on `7eaa57ca` (code identical to CI-green `eefb46fa`): **SECURED, 62/62 closed**.

---

## Trust Boundaries

| Boundary | Description | Data Crossing |
|----------|-------------|---------------|
| browser -> public container | anonymous GET of image URLs; the only unauthenticated path into storage | plans 36-01, 36-12 |
| core-java -> Blob service | authenticated data-plane calls; the credential never leaves core-java | plans 36-01 |
| vendor upload -> quarantine | raw, un-stripped bytes (EXIF/GPS) cross into storage here | plans 36-01 |
| build -> image | new third-party jars enter the shipped image | plans 36-01 |
| registry -> local runtime | the emulator image is pulled and run with a data volume | plans 36-02 |
| host network -> Azurite port | 10000 is published to the host for the browser | plans 36-02 |
| .env -> containers | credentials reach services through the env contract | plans 36-02 |
| browser -> image origins | CSP img-src decides which origins the page may load images from | plans 36-03 |
| Next server -> remote image hosts | the /_next/image optimizer fetches server-side from remotePatterns hosts | plans 36-03 |
| backup job -> Blob account | the dump (tenant PII) leaves the cluster here | plans 36-04 |
| env -> blobctl | auth mode, endpoint and emulator string arrive by env | plans 36-04 |
| module proxy -> build | third-party Go modules enter the backup image | plans 36-04 |
| operator shell -> Azure control plane | az commands create accounts, identities and role assignments (Phase 29) | plans 36-05 |
| workload identity -> storage data plane | the only credentialed path to Blob in staging/production | plans 36-05 |
| internet -> media account | anonymous read of public images | plans 36-05 |
| environment -> core-java config | auth mode, connection string, endpoint and AZURE_* arrive by env | plans 36-06 |
| core-java -> container properties | the boot probe reads access levels from the data plane | plans 36-06 |
| client request -> persisted image URL | products.image_url, shop logo/banner and review photo URLs are client-supplied | plans 36-07 |
| tenant A request -> shared public container | one container holds every tenant's images | plans 36-07 |
| database -> backup job | the dump carries every tenant's PII | plans 36-08 |
| backup job -> Blob | the dump leaves the database host | plans 36-08 |
| drill script -> docker | credentials cross into containers | plans 36-08 |
| manifest -> pod env | what configuration and credentials reach core-java and pg-backup | plans 36-09 |
| pod -> Entra ID / Blob (443) | the workload-identity token exchange and data-plane calls | plans 36-09 |
| ServiceAccount -> Kubernetes API | the token a pod could present to the API server | plans 36-09 |
| operator -> local cluster | the bootstrap creates Secrets and roles on the shared dev Postgres | plans 36-10 |
| registry -> operator host | the bootstrap used to pull a third-party client image | plans 36-10 |
| operator shell -> shared dev Postgres | a bulk rewrite of tenant data | plans 36-11 |
| script -> RLS | the rewrite must go through the tenant wall, not around it | plans 36-11 |
| vendor upload -> quarantine -> public | the async pipeline boundary on the live stack | plans 36-12 |
| gate scripts -> dev DB / Azurite | read-only enumeration with superuser and emulator access | plans 36-12 |
| browser -> object store | the storefront's images are fetched cross-origin from the Blob endpoint | plans 36-13 |
| source text -> build | comment and literal edits must not alter compiled behaviour | plans 36-14 |
| docs -> operators and agents | documentation drives what people and agents run | plans 36-15 |
| pull request -> static gates | the only place residue can be stopped before merge | plans 36-16 |
| allowlist -> gate | exemptions must stay reviewed and minimal | plans 36-16 |
| this branch -> phase-29-research | a canonical paused branch that must never be modified | plans 36-17 |
| handoff -> Phase 29 operator | the provisioning instructions a human will run | plans 36-17 |
| local branch -> origin | publishing the branch | plans 36-18 |
| CI run -> verdict | the run's report, not its badge, is the evidence | plans 36-18 |

---

## Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation | Status |
|-----------|----------|-----------|----------|-------------|------------|--------|
| T-36-01 | Information disclosure | quarantine objects | high | mitigate | Quarantine keys route to the private jtoye-quarantine container, get no public URL (urlForKey refuses) and no cache header; an Azurite anonymous-GET test proves refusal and a routing-swap break arm proves the test can fail. Closes the pre-… | closed |
| T-36-02 | Information disclosure | public container listing (#626) | high | mitigate | Public container at access level blob (never container); Azurite anonymous-LIST test asserts not-200 and no EnumerationResults body; break arm at CONTAINER level goes red | closed |
| T-36-03 | Information disclosure | storage config logging | medium | mitigate | StorageConfig logs mode, endpoint host and container names only via endpointHostForLog(); no connection string or key is ever logged; no key literal in the repo (git grep AccountKey= is empty) | closed |
| T-36-04 | Tampering | persisted image URLs (split horizon) | medium | mitigate | urlForKey stays public-url + "/" + key from config; getBlobUrl() is never persisted; the tracer asserts the returned URL's prefix equals storage.blob.public-url | closed |
| T-36-05 | Tampering | stored Content-Type | medium | mitigate | Adapter writes only the detected/produced type passed by StorageService (T-24-02 unchanged); ported unit tests keep the content-type captor assertions | closed |
| T-36-SC (36-01) | Tampering | Maven dependency installs | high | mitigate | Explicit pinned versions of official com.azure coordinates checked against Maven Central and the Azure SDK repo (research audit; registry seam unsupported for Maven); msal4j-persistence-extension excluded to keep jna natives out; local Tri… | closed |
| T-36-06 | Tampering | Azurite image | medium | mitigate | Digest-pinned default in both compose files; horizons row with the exact pin; H-1/H-5 fail on drift (fail direction recorded) | closed |
| T-36-07 | Information disclosure | published port 10000 | medium | mitigate | Loopback-only bind via JTOYE_BIND_HOST default 127.0.0.1 (#441); jq assertion on host_ip | closed |
| T-36-08 | Information disclosure | emulator key | low | mitigate | UseDevelopmentStorage=true in compose, so no key literal exists in compose or .env; git grep AccountKey empty | closed |
| T-36-09 | Denial of service | hybrid runtime without an object store | low | mitigate | Azurite added to infra compose and create-containers exported, so the 36-06 check can stay on (D-08) instead of the host boot failing | closed |
| T-36-SC (36-02) | Tampering | npm/pip/cargo installs | low | accept | No package-manager install in this plan; the only new artefact is the digest-pinned image above | closed |
| T-36-10 | Information disclosure | /_next/image optimizer (SSRF surface) | medium | mitigate | remotePatterns holds one exact origin/path; a test fails on any wildcard hostname (break arm recorded) | closed |
| T-36-11 | Tampering | CSP img-src | medium | mitigate | Only the dev origin token is swapped; a test pins the wildcard allowlist to https://*.stripe.com and fails on *.blob.core.windows.net (break arm recorded) | closed |
| T-36-SC (36-03) | Tampering | npm installs | low | accept | No new npm dependency in this plan | closed |
| T-36-12 | Elevation of privilege | connection-string path | high | mitigate | Emulator-only parser refuses AccountName/AccountKey/SAS/BlobEndpoint; table test + break arm recorded (D-02) | closed |
| T-36-13 | Information disclosure | logs and errors | medium | mitigate | Config.String() redaction test; errors never include the connection string | closed |
| T-36-14 | Tampering | existing dumps | medium | mitigate | Upload is If-None-Match * (exit 3 on collision); no delete code path exists (git grep proof) | closed |
| T-36-15 | Information disclosure | gitleaks allowlist | medium | mitigate | One regex anchored to the exact published key; a random AccountKey in a scratch file still fails (arm recorded) | closed |
| T-36-SC (36-04) | Tampering | Go module installs | high | mitigate | Official Azure modules at pinned versions (checked on proxy.golang.org + the Azure repo); go.sum committed and go mod verify run in CI; Dependabot gomod entry added | closed |
| T-36-16 | Elevation of privilege | workload identities | high | mitigate | Container-scoped data roles only; backup identity write-only (custom role, A7 verified before use); no Storage Account Contributor, no listKeys, no account-scope role | closed |
| T-36-17 | Information disclosure | account keys | high | mitigate | --allow-shared-key-access false on every account; no key/SAS/connection string in the runbook (git grep proof) | closed |
| T-36-18 | Information disclosure | backup account (dumps hold PII) | critical | mitigate | --allow-blob-public-access false on backup accounts; jtoye-db-backups private; separate account and region from media (D-01) | closed |
| T-36-19 | Tampering | wrong Azure subscription (employer) | high | mitigate | Subscription resolved explicitly by id prefix to exactly one id; every az line carries --subscription (awk proof) | closed |
| T-36-20 | Denial of service | immutability lock with a wrong retention | medium | mitigate | Policy left unlocked through the Phase 29 drill; locking is a named human checkpoint; retention recorded as D-11 | closed |
| T-36-21 | Information disclosure | real account key in a runtime | high | mitigate | Emulator-only connection-string rule in every profile; unit arms incl. a break arm (D-02) | closed |
| T-36-22 | Denial of service | misconfig found at first upload | medium | mitigate | Shape validation at bean build + ApplicationReadyEvent probe; context-runner arm proves startup fails | closed |
| T-36-23 | Information disclosure | public container at CONTAINER level (#626) | high | mitigate | Probe refuses CONTAINER on every boot; Azurite arm + break arm recorded | closed |
| T-36-24 | Tampering | probe disabled in a runtime | medium | mitigate | validate-on-startup is a literal, not env-mapped; git grep proves no main profile sets false (D-08) | closed |
| T-36-SC (36-06) | Tampering | package installs | low | accept | No new dependency; the credential test only exercises 36-01's pinned azure-identity | closed |
| T-36-25 | Tampering | StorageService.delete(url) cross-tenant delete | high | mitigate | Tenant-segment == TenantContext guard (D-09); unit cases + Azurite IT; guard-removal break arm recorded | closed |
| T-36-26 | Information disclosure | quarantine bytes after processing | medium | mitigate | Pipeline IT asserts the quarantine object is gone after success and private throughout | closed |
| T-36-27 | Tampering | guard bypass when TenantContext is absent | medium | mitigate | Absent context refuses (fail-closed), unit case asserts no delete call | closed |
| T-36-SC (36-07) | Tampering | package installs | low | accept | No new dependency | closed |
| T-36-28 | Information disclosure | drill credentials | medium | mitigate | 0600 temp env-file passed with --env-file, deleted in an EXIT trap; awk proof that no docker run line carries a password | closed |
| T-36-29 | Tampering | existing dumps | high | mitigate | No prune, no delete in the script; blobctl never overwrites; WORM + soft delete in staging per the provisioning runbook (D-01) | closed |
| T-36-30 | Tampering | zero-row dump reported as a good backup | high | mitigate | Two-arm restore-and-count gate (arm A must restore 0, arm B must equal live > 0), fail arm recorded, nightly-wired | closed |
| T-36-31 | Denial of service | scratch databases left behind | low | mitigate | EXIT trap drops them; post-run pg_database count asserted 0 | closed |
| T-36-SC (36-08) | Tampering | golang builder image | medium | mitigate | Builder stage only (not shipped), pinned golang:1.27-alpine tracked by the golang horizons row with this new site | closed |
| T-36-32 | Information disclosure | storage credential in a staging/prod Secret | high | mitigate | INV-9 forbids storage secretKeyRefs, connection-string env and AWS_ env in staging/production renders; break arm recorded (D-02) | closed |
| T-36-33 | Denial of service | missing WI label -> pod without identity | medium | mitigate | INV-10 asserts label + serviceAccountName; core-java shape validation fails fast on a missing AZURE_CLIENT_ID | closed |
| T-36-34 | Information disclosure | emulator string rendered outside local | high | mitigate | INV-4 banned literals now include UseDevelopmentStorage, devstoreaccount1, AccountKey= | closed |
| T-36-35 | Elevation of privilege | broad 443 egress | low | accept | Pre-existing rule, required for Blob and login.microsoftonline.com whose IPs cannot be pinned; port 9000 removed narrows the policy | closed |
| T-36-36 | Elevation of privilege | ServiceAccount token automount | low | mitigate | automountServiceAccountToken false on both new ServiceAccounts (INV-10); A6 verified in Phase 29 | closed |
| T-36-SC (36-09) | Tampering | package installs | low | accept | No package install; the image tag change references 36-08's locally built image | closed |
| T-36-37 | Information disclosure | stale local credential Secrets | low | mitigate | Documented local-context-only kubectl delete for the two retired Secrets; the bootstrap no longer creates them | closed |
| T-36-38 | Tampering | unpullable/unmaintained client image in a bootstrap | medium | mitigate | The retired client image and its horizons row are removed with its last use | closed |
| T-36-39 | Information disclosure | docs instructing a storage Secret | medium | mitigate | Template and runbooks point to Workload Identity; INV-9 enforces no storage Secret in staging/production renders | closed |
| T-36-SC (36-10) | Tampering | package installs | low | accept | No package install | closed |
| T-36-40 | Tampering | reseed run against a non-dev database | critical | mitigate | Container/db/context guards, dry-run default, required --cutover, owner checkpoint before --apply, never a Flyway migration | closed |
| T-36-41 | Tampering | a bare UPDATE silently matching zero rows under FORCE RLS | high | mitigate | Tenant loop with set_config; visibility proof (pinned > 0, unpinned == 0); before-count refusal; after-count == 0 inside the transaction (D-05) | closed |
| T-36-42 | Elevation of privilege | superuser bypassing RLS | medium | mitigate | Runs as jtoye_app only (git grep proof); the superuser is never used | closed |
| T-36-43 | Repudiation | a dry run that differs from the apply | low | mitigate | Dry run executes the same transaction and rolls back; apply counts compared with the dry run's | closed |
| T-36-SC (36-11) | Tampering | package installs | low | accept | No package install | closed |
| T-36-44 | Tampering | non-image Content-Type on the public origin (stored XSS) | high | mitigate | Content-type gate re-targeted to Azurite's public container, allowlist unchanged, text/html arm recorded, nightly-wired | closed |
| T-36-45 | Repudiation | URL gate passing on nothing | medium | mitigate | N = 0 VOIDs; old-origin URLs FAIL rather than skip; delete-blob arm recorded | closed |
| T-36-46 | Information disclosure | vendor token / connection string in process args or logs | low | mitigate | Token held in a shell variable and never echoed; connection strings passed via env (awk proof) | closed |
| T-36-47 | Tampering | stale runtime reported as fixed | medium | mitigate | Rebuild with --build, freshness gate 0 unverified, application.yml read out of the running jar | closed |
| T-36-SC (36-12) | Tampering | package installs | low | accept | No package install (host az CLI already present) | closed |
| T-36-48 | Repudiation | a browser test passing on fallback tiles | medium | mitigate | Filter by the full storage-origin src, VOID on zero, stopped-Azurite arm recorded red | closed |
| T-36-49 | Information disclosure | vendor credentials in test output or screenshots | low | mitigate | Password read from env via vendor-credentials.ts, never logged; screenshots show no credential fields filled | closed |
| T-36-SC (36-13) | Tampering | package installs | low | accept | No new dependency (Playwright already present) | closed |
| T-36-50 | Denial of service | editing an applied Flyway migration (checksum mismatch at startup) | high | mitigate | V42 is excluded from every edit and asserted unchanged by a range diff | closed |
| T-36-51 | Tampering | a "comment-only" edit that changes code | medium | mitigate | awk over the main-code diff counts non-comment changed lines = 0; suites re-run | closed |
| T-36-SC (36-14) | Tampering | package installs | low | accept | No package install | closed |
| T-36-52 | Information disclosure | a doc leaking an account key or emulator key literal | medium | mitigate | Docs use UseDevelopmentStorage=true and name no key; git grep AccountKey over docs is empty; gitleaks runs on the PR | closed |
| T-36-53 | Tampering | docs steering operators to create a storage Secret | medium | mitigate | sealed-secrets and quick-start docs name Workload Identity; INV-9 enforces it in renders | closed |
| T-36-SC (36-15) | Tampering | package installs | low | accept | No package install | closed |
| T-36-54 | Repudiation | a residue gate that cannot fail | medium | mitigate | Self-test of every pattern class, planted-token arm, pre-phase-tree arm (> 50 violations), VOID on empty scope or missing PCRE | closed |
| T-36-55 | Repudiation | allowlist rot | low | mitigate | Blank reason, duplicate and stale entries fail (arms recorded) | closed |
| T-36-56 | Repudiation | a gate that runs nowhere | medium | mitigate | Wired in the creating change; check-gate-enforcement unwired arm recorded | closed |
| T-36-SC (36-16) | Tampering | package installs | low | accept | No package install | closed |
| T-36-57 | Tampering | phase-29-research branch | high | mitigate | Read only via git show / git diff; branch head sha recorded before and after | closed |
| T-36-58 | Repudiation | a handoff that misstates what shipped | medium | mitigate | Every handoff and REQUIREMENTS claim is sourced from a SUMMARY or a git command whose output is recorded | closed |
| T-36-59 | Information disclosure | secrets in the handoff | medium | mitigate | Value names only, never values; git grep for key/SAS shapes is empty | closed |
| T-36-SC (36-17) | Tampering | package installs | low | accept | No package install | closed |
| T-36-60 | Repudiation | a watch loop that hangs or reports green on the wrong run | medium | mitigate | Concrete run id resolved and createdAt checked after dispatch; bounded deadline; every end state printed; verdict read from the report artifact | closed |
| T-36-61 | Tampering | pushing without approval | medium | mitigate | Owner checkpoint before any push | closed |
| T-36-SC (36-18) | Tampering | package installs | low | accept | No package install | closed |

*Status: open · closed · open — below medium threshold (non-blocking)*
*Severity: critical > high > medium > low — only open threats at or above workflow.security_block_on count toward threats_open*
*Disposition: mitigate (implementation required) · accept (documented risk) · transfer (third-party)*

---

## Accepted Risks Log

| Risk ID | Threat Ref | Rationale | Accepted By | Date |
|---------|------------|-----------|-------------|------|
| AR-36-01 | T-36-SC (36-02) | No package-manager install in this plan; the only new artefact is the digest-pinned image above | plan-time threat model (36-02), audit-confirmed | 2026-09-29 |
| AR-36-02 | T-36-SC (36-03) | No new npm dependency in this plan | plan-time threat model (36-03), audit-confirmed | 2026-09-29 |
| AR-36-03 | T-36-SC (36-06) | No new dependency; the credential test only exercises 36-01's pinned azure-identity | plan-time threat model (36-06), audit-confirmed | 2026-09-29 |
| AR-36-04 | T-36-SC (36-07) | No new dependency | plan-time threat model (36-07), audit-confirmed | 2026-09-29 |
| AR-36-05 | T-36-35 | Pre-existing rule, required for Blob and login.microsoftonline.com whose IPs cannot be pinned; port 9000 removed narrows the policy | plan-time threat model (36-09), audit-confirmed | 2026-09-29 |
| AR-36-06 | T-36-SC (36-09) | No package install; the image tag change references 36-08's locally built image | plan-time threat model (36-09), audit-confirmed | 2026-09-29 |
| AR-36-07 | T-36-SC (36-10) | No package install | plan-time threat model (36-10), audit-confirmed | 2026-09-29 |
| AR-36-08 | T-36-SC (36-11) | No package install | plan-time threat model (36-11), audit-confirmed | 2026-09-29 |
| AR-36-09 | T-36-SC (36-12) | No package install (host az CLI already present) | plan-time threat model (36-12), audit-confirmed | 2026-09-29 |
| AR-36-10 | T-36-SC (36-13) | No new dependency (Playwright already present) | plan-time threat model (36-13), audit-confirmed | 2026-09-29 |
| AR-36-11 | T-36-SC (36-14) | No package install | plan-time threat model (36-14), audit-confirmed | 2026-09-29 |
| AR-36-12 | T-36-SC (36-15) | No package install | plan-time threat model (36-15), audit-confirmed | 2026-09-29 |
| AR-36-13 | T-36-SC (36-16) | No package install | plan-time threat model (36-16), audit-confirmed | 2026-09-29 |
| AR-36-14 | T-36-SC (36-17) | No package install | plan-time threat model (36-17), audit-confirmed | 2026-09-29 |
| AR-36-15 | T-36-SC (36-18) | No package install | plan-time threat model (36-18), audit-confirmed | 2026-09-29 |

*Accepted risks do not resurface in future audit runs.*

---

## Transferred Live Legs (Phase 29)

Three mitigations are verified in-repo, but their LIVE check needs Azure resources that do not exist yet. They are owner-ruled to Phase 29 and recorded in `36-VERIFICATION.md` frontmatter `deferred[0..2]` and in `36-PHASE29-HANDOFF.md`. They are not open threats.

- **T-36-02 / T-36-17 / T-36-18:** the #626 read-back and the no-shared-key / no-public-access posture on the real staging accounts (`docs/runbooks/azure-blob-provisioning.md` §8).
- **T-36-36 / T-36-33:** the live AKS Workload Identity webhook mutation and token exchange (`jtoye-staging-aks` has WI off and is stopped; WINDOWS.md #3).
- **T-36-16 / T-36-20 / T-36-29:** container-scoped RBAC, then the WORM lock after the Phase 29 restore drill (human checkpoint §4.4).

---

## Audit Residuals (non-blocking, recorded for the owner)

- **R1 (T-36-24 / T-36-23):** the startup storage probe can still be disabled from outside the config file: `STORAGE_BLOB_VALIDATEONSTARTUP=false` through relaxed binding, `SPRING_APPLICATION_JSON`, or a command-line argument. INV-9 does not ban these, so the comment at `application.yml:593-594` ("no runtime can switch it off through its environment") is not literally true. Nothing sets any of them today (rc=1 across k8s, infra, scripts, .github and compose). The same point is Warning 1 in `36-REVIEW.md`.
- **R2 (T-36-16):** the runbook fallback at `:346-349` (Storage Blob Data Contributor for the backup identity) would grant read and delete, relying on a WORM policy that stays unlocked until the §4.4 checkpoint. It is a Phase 29 decision point.
- **R3:** the plan text says "git grep AccountKey= is empty", but it is not. What remains is fake test fixtures, the gate's own banned-literal list, and the published Azurite key, which is allowlisted. None is a real credential.
- **Unregistered flag (36-08):** the restore drill creates a `jtoye_backup` BYPASSRLS LOGIN role in any database it runs against. It is intended (it matches `k8s-local-secrets.sh`), but no threat ID covers it.
- **Informational (36-04):** `apache/arrow-go/v18` arrives transitively in blobctl, and Dependabot's gomod entry watches it.

---

## Security Audit Trail

| Audit Date | Threats Total | Closed | Open | Run By |
|------------|---------------|--------|------|--------|
| 2026-09-29 | 62 unique (78 rows) | 62 | 0 | gsd-security-auditor (opus, ASVS L2, block_on medium) |

Gates the auditor ran itself (read-only): `k8s/scripts/check-render-invariants.sh` rc=0 (INV-4/8/9/10 OK on staging and production), `scripts/check-no-object-store-residue.sh` rc=0 (2560 files, 0 violations), `scripts/check-gate-enforcement.sh` rc=0, and `scripts/check-infra-exposure.sh` part A PASS (azurite on `127.0.0.1:10000`; the script's overall rc=2 was VOID only because Grafana, part C, was down, which is unrelated).

---

## Sign-Off

- [x] All threats have a disposition (mitigate / accept / transfer)
- [x] Accepted risks documented in Accepted Risks Log
- [x] `threats_open: 0` confirmed
- [x] `status: verified` set in frontmatter

**Approval:** verified 2026-09-29
