---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 22
subsystem: infra
tags: [mailpit, starttls, cert-manager, truststore, keycloak, traefik, basic-auth, key-vault, live, d-20, d-22, d-30, d-33]
status: complete

requires:
  - phase: 29-16
    provides: "spring.mail starttls.enable/required literals under the prod and staging profiles (in the efab17d7 images)"
  - phase: 29-15
    provides: "the live edge gate and the Traefik annotation fidelity this route reuses"
  - phase: 29-14
    provides: "scripts/deploy.sh --context/--image-tag/preflight/DEPLOYED_SHA, the staging vendor and the vendor token path"
  - phase: 29-13
    provides: "scripts/staging-secrets.sh (write-once writer), the preflight, the Key Vault chain"
provides:
  - "In-cluster Mailpit v1.31.6 with STARTTLS required, its certificate from a cert-manager internal CA (jtoye-staging-ca), the only SMTP destination of staging app mail"
  - "core-java's JVM-wide truststore = JDK cacerts + cluster CA, built at pod start and checked passwordless (118 + 1); Keycloak trusts the CA through KC_TRUSTSTORE_PATHS"
  - "INV-16 (truststore shape) and INV-18 (the public mail UI render) with selftests wired into CI"
  - "https://mail-staging.olajay.co.uk: Mailpit's UI and /api behind one generated basic-auth credential (D-33), Send API locked, SMTP never published"
  - "scripts/staging-mail-ui.sh --list-kv-objects / --probe (UI-1..UI-8) / --selftest"
  - "keyvault-chain.py: cert-manager Certificates as Secret producers; Ingress auth-secret as a consumption secret-sync must keep synced"
affects: [29-18, 29-26, 29-23, 29-24, 29-25, 29-27, 29-28]

actuals:
  tokens: 54572
  tasks: 4
  commits: 12
plan_head_before: efab17d79483b01f4397c9f82f34a36d1a2b4c11
plan_head_after: 2b021754e95745ada67d7a6fd0511fd8303983eb

tech-stack:
  added:
    - "axllent/mailpit:v1.31.6 (staging only; horizon row axllent-mailpit, endoflife 404, manual review to 2027-01-19)"
  patterns:
    - "A JVM-wide truststore is a COPY of the JDK cacerts plus the CA, verified by the passwordless entry count the JVM will see; the pod fails closed otherwise"
    - "A gate's own evidence record must not plant the strings it later counts: pre-arm misses are recorded by index"
    - "A public read-only tool UI rides Traefik basic auth from a Key Vault-synced htpasswd Secret; the plaintext is in no SecretProviderClass"
    - "A third-party write API that cannot be switched off is locked with a random per-pod credential nobody holds"

key-files:
  created:
    - k8s/components/mailpit/kustomization.yaml
    - k8s/components/mailpit/mailpit.yaml
    - k8s/components/mailpit/cluster-ca.yaml
    - k8s/components/mailpit/mailpit-ui-ingress.yaml
    - k8s/components/cluster-ca-truststore/kustomization.yaml
    - k8s/components/cluster-ca-truststore/core-java-truststore-patch.yaml
    - k8s/components/cluster-ca-truststore/keycloak-truststore-patch.yaml
    - scripts/staging-mail-ui.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-mail-tls.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-mail-ui.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-t2-red-inv16.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-t3-red-cert-producer.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-t4-red-inv18.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-t4-red-mail-ui-probe.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-22-t4-red-selftests.txt
  modified:
    - k8s/staging/kustomization.yaml
    - k8s/scripts/check-render-invariants.sh
    - k8s/scripts/keyvault-chain.py
    - k8s/scripts/check-keyvault-chain.sh
    - k8s/components/azure-keyvault/secretproviderclasses.yaml
    - k8s/components/azure-keyvault/secret-sync.yaml
    - k8s/base/networkpolicies/README.md
    - k8s/goldens/staging.yaml
    - infra/dependency-horizons.yaml
    - scripts/lib/staging-secrets-common.sh
    - scripts/staging-secrets.sh
    - scripts/deploy.sh
    - .github/workflows/ci-cd.yaml
    - k8s/DEPLOYMENT.md
    - docs/guides/ENVIRONMENT_STRATEGY.md
    - docs/runbooks/credential-rotation.md

key-decisions:
  - "The D-30 break arm builds a PASSWORDLESS CA-only store: keytool -importcert into a new file encrypts the certificate bag, so the JVM (no store password) would read ZERO anchors and fail with an internal_error, not the PKIX failure D-30 describes"
  - "The truststore-builder fails the pod unless the passwordless entry count is the JDK count plus exactly one"
  - "A cert-manager Certificate is a recognised Secret producer in the Key Vault chain gate (its keys only, never also an SPC)"
  - "The jtoye-staging CA keeps its key across renewals (rotationPolicy Never), so a truststore built before a renewal still anchors leaves signed after it"
  - "Mailpit runs with no egress at all: MP_SMTP_DISABLE_RDNS and MP_DISABLE_VERSION_CHECK, so nothing needs DNS"
  - "Keycloak's testSMTPConnection needs the calling admin's email: a non-routable kc-smtp-test@staging.invalid was set for the arm and removed, read back byte for byte"

patterns-established:
  - "Deploy at an image tag whose app paths are unchanged since the build: check git diff --name-only <tag> HEAD -- core-java frontend edge-go is empty before each deploy"

requirements-completed: [DPLY-01]

coverage:
  - id: D1
    description: "Staging app mail reaches in-cluster Mailpit over REQUIRED STARTTLS, verified against the cluster CA (D-20, D-22 arm 1)"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-22-mail-tls.txt: POST /api/v1/staff/invites 201 -> Mailpit 1nLOiOjARQdr0JRuZ6f5vL to staff.invite.staging@olajay.co.uk, fragment link present; live Mailpit answers 530 to MAIL FROM before STARTTLS; openssl -starttls verified CA + host name, wrong host -> hostname mismatch"
        status: pass
      - kind: other
        ref: "Task 3 fourth verify verbatim: aggregate /actuator/health HTTP 200 UP with Mailpit TLS live"
        status: pass
    human_judgment: false
  - id: D2
    description: "With no STARTTLS offered the send fails and the aggregate health goes DOWN; restored by content (D-22 arm 2)"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "evidence/29-22-mail-tls.txt: staff_invite_email_failed MailSendException; MailHealthIndicator 'jakarta.mail.MessagingException: STARTTLS is required but host does not support STARTTLS'; nothing received in 75 s; health 503 DOWN; Ready-pod env count 0 then 1; template sha256 c323f207 before = after; resend received"
        status: pass
    human_judgment: false
  - id: D3
    description: "JVM-wide truststore keeps public TLS (D-30 arm 1) and a CA-only store breaks it (arm 2)"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "truststore-builder jdk-entries=118 entries=119; Ready 0 restarts; StorageStartupValidator passed; upload asset 14add455 -> ACTIVE; CA-only: 'PKIX path building failed' (managed Postgres verify-full first), CrashLoopBackOff, never Ready, old pod served (EndpointSlice, public API 200); template sha256 11710b24 before = after"
        status: pass
    human_judgment: false
  - id: D4
    description: "Keycloak realm SMTP over STARTTLS validates against the cluster CA via KC_TRUSTSTORE_PATHS"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "testSMTPConnection jtoye-customers HTTP 204, '[KEYCLOAK] - SMTP test message' in Mailpit; control host=ClusterIP -> 'No subject alternative names matching IP address' HTTP 500"
        status: pass
    human_judgment: false
  - id: D5
    description: "INV-16 and INV-18 render gates, shown catching planted holes"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "--selftest-inv16 4/4 (RED 0/4 RED_EVIDENCE_OK); --selftest-inv18 6/6 (RED 0/6 RED_EVIDENCE_OK); both run in k8s-validate"
        status: pass
    human_judgment: false
  - id: D6
    description: "D-33 testers' mail UI: authenticated, https only, SMTP unpublished, Mailpit sends nothing, fail-closed"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "scripts/staging-mail-ui.sh --probe 8/8 (UNAUTH-429=0); --selftest 14/14 (RED 3/14 RED_EVIDENCE_OK); Send API 200 before / 401 after the lock (edge and port-forward); UI-9 404 x2 + 'Cannot resolve auth secret, skipping ingress', restored, 8/8"
        status: pass
      - kind: e2e
        ref: "/tmp/claude-29-22/bin/ui-list.mjs: UI-LIST subject-visible=yes http=200 with the credential; no http=401 with an empty password"
        status: pass
    human_judgment: false
  - id: D7
    description: "The generated credential: written once, granted only as its hash, never printed"
    requirement: DPLY-01
    verification:
      - kind: other
        ref: "--generate 2 written / 29 exist; --assign-access 1 created (jtoye-staging-secret-sync-id on --auth), 0 role assignments at the --password scope; preflight 33 objects 36 grants 0 gaps; leak-scan 23 objects checked, 0 found on both evidence files"
        status: pass
    human_judgment: false

duration: 114min
completed: 2026-10-19
---

# Phase 29 Plan 22: Staging Mail Over Required STARTTLS, a JVM Truststore That Keeps the Internet, and the Testers' Mail UI

**Staging app mail now goes only to an in-cluster Mailpit that requires STARTTLS. Its certificate comes from a cert-manager internal CA, which core-java trusts through a JVM-wide store built at pod start from the JDK cacerts plus that CA, and Keycloak through KC_TRUSTSTORE_PATHS. Every claim was shown failing on the running cluster: a server offering no STARTTLS makes the send fail and the CI health aggregate go 503, and a CA-only store stops core-java starting with PKIX path building failed. Persona testers read the captured mail at https://mail-staging.olajay.co.uk behind one generated basic-auth credential. The probe passes 8/8, an unresolvable auth Secret fails closed, and the Send API is locked.**

## Performance

- **Duration:** 114 min (11:46Z to 13:41Z). About 66 min of that was the bounded wait for CI to build the efab17d7 images (the integration suite and then the image build). All offline work and the Task 4 drafts were done during the wait.
- **Tasks:** 4 of 4.
- **Commits:** 12, measured from the plan ledger (`efab17d7..2b021754`). All of them belong to this plan.
- **Live estate touched:** kube-context `jtoye-stg-aks` and Key Vault `jtoye-stg-kv` (subscription c483d353) only.
  - Five `deploy.sh` runs, all at `efab17d7`.
  - Three planned live breaks (Mailpit env, the core-java init command, the mail-UI auth-secret), each restored by deploy.sh and verified by content.
  - One temporary email on the Keycloak master admin, removed and read back.
  - Two Key Vault writes and one role assignment (D-33, `[az-ok]`).
  - No other Azure mutation. No push.

## Accomplishments

- **Task 1** (6a9e2797): `k8s/components/mailpit`.
  - **CA chain:** selfSigned Issuer, then CA Certificate `jtoye-staging-ca` (ECDSA P-256, 8760h, `rotationPolicy: Never`), then a CA Issuer, then `mailpit-tls` for the exact FQDN core-java dials.
  - **Mailpit:** runs non-root with a read-only root, `MP_SMTP_REQUIRE_STARTTLS`, and no relay or forward.
  - **`mailpit-allow`:** 1025 only from core-java, Keycloak and Alertmanager; no egress.
  - Also: the INV-7 row, the README rows and the horizon row.
  - **Measured locally first:** the probes answer 200, STARTTLS is required (530 before it), and no AUTH is advertised.
- **Task 2** (952c9ded RED, d2d70044 GREEN): `k8s/components/cluster-ca-truststore` and INV-16.
  - The builder copies `$JAVA_HOME/lib/security/cacerts`, imports the CA, and exits 1 unless the passwordless count is the JDK count plus 1.
  - `JAVA_TOOL_OPTIONS` is a literal. The Dockerfile's `JAVA_OPTS` is untouched (read in the running pod).
  - **INV-16 refuses:** a CA-only builder, any `JAVA_OPTS`, a Secret-sourced `JAVA_TOOL_OPTIONS`, and a staging render without the builder.
  - **INV-11** now counts init containers that run the core-java image. Before this change, a Secret-sourced JVM option on one passed (shown on a planted copy against the old gate).
- **Task 3** (8197ba71, `evidence/29-22-mail-tls.txt`): deployed at `efab17d7`.
  - The digest-parity gate passed against that tag.
  - All 10 markers and 3 raw patterns are present, each raw pattern backed by a real log or API line. The pre-arm run showed missing=13, recorded by index.
  - **SMTP AUTH (29-14 open item b):** Mailpit advertises no AUTH before or after STARTTLS. Jakarta Mail authenticates only when the server advertises AUTH, so core-java never offers its injected SMTP credentials.
- **Task 4** (f5b887ff RED, a5bd4af5 GREEN, 98e22683 docs, 2b021754 evidence; `evidence/29-22-mail-ui.txt`):
  - **Edge:** Ingress `mailpit-ui`; `mailpit-allow` admits traefik on 8025 only.
  - **Mailpit:** the send-api-lock init container and `MP_SEND_API_AUTH_FILE`.
  - **Key Vault chain:** SPC `mailpit-ui-kv` mounted by secret-sync; the chain gate's Ingress consumer.
  - **Writer:** the apr1 composite generator.
  - **Gates and docs:** INV-18, the probe, the CI wiring, and the tester note and rotation runbook.
  - **Live:** all 15 markers present; the header-only run showed missing=15, recorded by index.

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| INV-16 | 952c9ded (0/4 caught, clean rc 0) | d2d70044 (4/4) | RED_EVIDENCE_OK (`29-22-t2-red-inv16.json`) |
| Key Vault chain, cert-manager producer | 9745c00e (9/13: 2 new arms not caught; both clean arms red on the real render) | 5967aa48 (13/13) | RED_EVIDENCE_OK (`29-22-t3-red-cert-producer.json`) |
| D-33 (INV-18, chain arms, writer arm, probe) | f5b887ff (INV-18 0/6, chain 13/15, writer 11/12, probe 3/14; real render red on the new INV-7 row) | a5bd4af5 (6/6, 15/15, 12/12, 14/14) | RED_EVIDENCE_OK (`29-22-t4-red-inv18.json`, `29-22-t4-red-mail-ui-probe.json`) |

## Both directions, recorded

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| T1 issuers/certificates/FQDN | `mailpit.wrong…` planted in the render: rc 1 (the first plant used a wrong anchor, changed nothing, and was redone) | rc 0 |
| T1 horizon row | row removed: rc 1, "H-1 axllent/mailpit:v1.31.6 … has NO horizon row" | restored (sha256 equal): rc 0 |
| T1 INV-7 row | frontend planted as a 1025 peer: "undeclared rule(s): in:pod=app=frontend:1025" | rc 0 |
| T2 env counts | JAVA_OPTS planted: `JAVA_OPTS=1` | `JAVA_OPTS=0 JAVA_TOOL_OPTIONS=1` |
| deploy.sh selftest image assertion | init left at 2.1.0: off=1; frontend missing: at=2 | 8/8 |
| T3 marker check | missing=13 | missing=0 |
| T3 second verify (Mailpit env) | count 0, rc 1 during arm 2 | count 1 |
| T3 fourth verify (aggregate health) | HTTP 503 DOWN during arm 2 | HTTP 200 UP (before, after the restore, at the close) |
| T4 second verify (route and grants) | pre-task tree: `route=[]`, rc 1 | the exact route line, 1 --auth grant, 0 --password grants |
| T4 INV-7 widenings | 8025 from any namespace and 1025 from traefik: both rc 1 | rc 0 |
| T4 marker check | missing=15 | missing=0 |
| T4 fifth verify | pre-task docs 0/0/0 | see Deviation 7 |
| browser check | empty password: no, http=401 | yes, http=200 |

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug] deploy.sh's selftest counted image LINES** (ac47fff6)
- **Cause:** the truststore-builder runs the core-java image, so the render has four app-image lines at the tag, and the render-only and deployed-sha arms (exactly 3) went red. CI runs that selftest.
- **Fix:** the arms now assert three DISTINCT app images at the tag and zero app-image lines at another tag. This is stronger: an init container left on 2.1.0 was invisible before.

**2. [Rule 3 - Blocking] The Key Vault chain gate VOIDed the deploy preflight** (9745c00e RED, 5967aa48 GREEN)
- **Cause:** Mailpit reads `mailpit-tls`, and core-java and Keycloak read `jtoye-staging-ca`. cert-manager writes all three, and the gate knew only SPC producers.
- **Fix:** a Certificate's `spec.secretName` is now a producer.
  - Only `ca.crt`, `tls.crt` and `tls.key` may be read from it.
  - An SPC declaring the same Secret counts as a second producer.
- This was Task 4's file, pulled forward.

**3. [Rule 2 - Missing] INV-11 could not see init containers** (d2d70044)
- The plan's interface said an init container running the core-java image is a core-java container for INV-11.
- The path regex `::containers::` never matched `initContainers`. Shown on a planted copy against the old gate: rc 0.

**4. [Rule 2 - Missing] The new selftests ran in no CI job** (0394f5fb, a5bd4af5)
- `--selftest-inv16`, `--selftest-inv18` and `staging-mail-ui.sh --selftest` now run in k8s-validate.

**5. [Plan adaptation] The D-30 break arm's store is passwordless**
- **Measured on the core-java JRE base:** `keytool -importcert` into a NEW file encrypts the certificate bag under the store password.
  - The JVM opens a trust store with no password, so it read 0 anchors and failed with `SSLException (internal_error)`.
  - That is not the CA-only store D-30 means.
- The arm therefore used `-J-Dkeystore.pkcs12.certProtectionAlgorithm=NONE -J-Dkeystore.pkcs12.macAlgorithm=NONE`, so the CA itself IS readable (1 entry).
- **Result:** the first public TLS call at start-up failed with `PKIX path building failed`. That call was the managed Postgres connection (pgjdbc verify-full validates through the JVM default store), before the Blob/Entra probe was reached.
- The same store fails against login.microsoftonline.com off-cluster (measured).

**6. [Plan adaptation] Keycloak's SMTP test needs the admin's own email**
- The master admin had none.
- `kc-smtp-test@staging.invalid` was set for the arm (captured by Mailpit only). It was then removed and read back byte for byte.
- The first restore PUT got 401 because the 60 s master token had expired. The token was re-issued, and both lines are recorded.

**7. [Rule 3 - Blocking] Task 4's fifth verify cannot reach rc 0 as written**
- **First run:** the doc-citation gate scoped to the three docs exited 1. The cause is a PRE-EXISTING false citation: `credential-rotation.md` section 1 wrote `127.0.0.1:5433`, which the gate reads as a file and line. The committed runbook fails the same scoped run; it is not in the gate's default set, so CI never saw it.
- **After rewording it** to "`127.0.0.1`, port `5433`", the scoped run VOIDs (rc 2): the three docs carry zero citations, as the plan requires of the new prose.
- **Replacement, strictly stronger:** the same three docs plus `k8s/LOCAL.md` as a control. Result: rc 0, the three docs at 0 citations and 0 violations, and the control at 18 (11 verified). The default set: 46 verified, 0 violations. Both forms are recorded in the evidence.

**8. [Plan adaptation] Evidence that would have planted its own markers**
- Recording the pre-arm marker check verbatim writes "MISSING: ARM D22-1 PASS" and similar lines into the file, which the final check would then count. A defanged copy still dropped missing from 13 to 11, because bare exception class names match the raw patterns.
- Pre-arm results are therefore recorded by index. Every raw pattern is satisfied by a real log or API line (mail-tls lines 114, 142 and 161-163).

**9. [Plan adaptation] Additions beyond the plan's literal shape**
- **Mailpit:** `MP_SMTP_DISABLE_RDNS` and `MP_DISABLE_VERSION_CHECK` (no egress, so no DNS), `MP_DATABASE` on an emptyDir, and an own ServiceAccount with automount off.
- **CA:** `rotationPolicy: Never`.
- **INV-16:** a fourth arm (component-missing).
- **INV-18 / probe:** forbid the whole `MP_SMTP_RELAY_*` / `MP_SMTP_FORWARD_*` families, a superset of the four names.
- **Task 1:** the networkpolicies README rows went in with Task 1, the commit that first rendered the policy.

**Total deviations:** 4 auto-fixed (1 bug, 1 blocking, 2 missing), 1 blocking criterion replaced, and 4 plan adaptations. **Impact:** all were needed for the plan's own gates to stay green or truthful. The D-30 and Keycloak arms are stronger than written.

## Findings

- **The CA-only store breaks the database first.** pgjdbc's verify-full uses the JVM default trust store, so D-30's "a CA-only store breaks the public internet" covers Postgres too, not only Stripe, Entra and Blob.
- **A keytool-created PKCS12 is unreadable to a JVM given no password.** Any future JVM trust store built from scratch must be passwordless, or the JVM must be given the store password.
- **Traefik keeps serving the Let's Encrypt certificate** for a host whose route it skipped (UI-9). The skip is a 404 behind a valid certificate, not the default certificate.
- **HTTP-01 for the new host passed in about 30 s** with default-deny and `acme-http01-solver-allow` unchanged (same namespace).
- **`UNAUTH-429=0`:** failed guesses are not throttled. T-29-111's accepted residual stands as written.

## Open Items

- **mailpit-tls renewal (dated): staging mail may stop around 2027-01-17 if nothing restarts Mailpit.**
  - **Why:** cert-manager renews the 90-day leaf 15 days before expiry, but Mailpit reads its certificate only at start.
  - **Consequence:** if no Mailpit restart happens between the renewal and the old leaf's expiry (issued 2026-10-19), staging mail fails STARTTLS verification and the aggregate health goes DOWN.
  - **Options:** a reloader, a CronJob restart, or the 29-23 liveness alert catching it. This needs a decision before then.
- **The truststore-builder's image is outside digest parity.** The gate reads `containerStatuses` only. It is the same image reference as the main container, pulled at the same tag.
- **`staging-secrets.sh --selftest` runs in no CI job.** This is pre-existing, from 29-13, and was not widened here. Its new htpasswd arm has the same exposure.
- **The CA renews in about 8 months, keeping its key.** Truststores built before the renewal still anchor new leaves (the JDK anchors on name and key). A CA key rotation would need core-java and Keycloak restarts.
- **Testers can "Delete all" in the Mailpit UI.** Accepted: captured staging mail is disposable.

## Threat Flags

None beyond the plan's threat model.
- The new public surface (mail-staging) is T-29-107..113, each mitigated or accepted as recorded.
- The temporary Keycloak admin email was a planned arm step, restored by content.

## User Setup

None needed. The DNS record was already present at both nameservers (measured, both dig lines in the evidence). The owner retrieves the tester password with the command in the k8s/DEPLOYMENT.md tester note.

## Self-Check: PASSED

- FOUND: every key-files.created path (checked with `[ -f ]` before this file was written).
- FOUND, ancestors of HEAD: 6a9e2797, 952c9ded, d2d70044, ac47fff6, 9745c00e, 5967aa48, 0394f5fb, 8197ba71, f5b887ff, a5bd4af5, 98e22683, 2b021754.
- `git rev-list --count efab17d7..2b021754` = 12.
- Final gates: Task 4 verify 1 rc 0; verify 2 rc 0 (rc 1 on the pre-task tree); verify 3 missing=0 (both files); verify 4 probe 8/8 rc 0; Task 3 spec 1 passed; leak scans 0 found.
