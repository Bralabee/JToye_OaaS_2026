---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 24
subsystem: infra
tags: [alertmanager, grafana, smtp-auth, zoho, mailpit, kustomize, workload-identity, key-vault, render-gate, networkpolicy]
status: complete

requires:
  - phase: 29-22
    provides: Mailpit with required STARTTLS, the cert-manager cluster CA (Secret jtoye-staging-ca), mailpit-allow admitting app=alertmanager on 1025
  - phase: 29-23
    provides: k8s/components/monitoring (Prometheus, exporters), prometheus-allow admitting app=grafana and reaching app=alertmanager, copy-parity pairs
  - phase: 29-09
    provides: the alertmanager and grafana user-assigned identities, federated to their ServiceAccounts
provides:
  - SMTP auth and an extra email_config as optional composed blocks in the ONE canonical Alertmanager template/entrypoint (compose render byte-identical)
  - scripts/check-alertmanager-render.sh (10 arms + 6-plant selftest) wired in CI ops-contracts
  - staging Alertmanager (Zoho + Mailpit in one receiver, NoOrdersCreated mute, cluster-internal) and Grafana (public at grafana-staging.olajay.co.uk, admin from Key Vault)
  - SPCs alertmanager-kv and grafana-kv, their workload patches and identity replacements
  - staging-secrets external validators @email and @nonempty; the four alertmanager-smtp objects external, Grafana admin estate + random
  - check-alert-mute.sh M-5 reads overlay renders (staging may mute allow-listed names; production never)
affects: [29-25, 29-27, 29-28]

actuals:
  tokens: 43116
  tasks: 2
  commits: 6
plan_head_before: 1640fb7bac4d16b0ff5dfd63c9df5dcdcbbfc16f
plan_head_after: 66c99603e84a9dd1b92badb63f555b260c0dab30

tech-stack:
  added: [prom/alertmanager:v0.34.1 (k8s), grafana/grafana:13.2.4 (k8s)]
  patterns:
    - "A credential is scoped to the one integration that uses it (per-email_config auth), never global, because Alertmanager copies global smtp_auth_* into every email_config"
    - "A canonical file served to k8s as a byte copy may carry no runtime-specific literal: make the value a required input and let each runtime pass its own"
    - "A gate that must tell a READER of a variable from a SETTER reads the render structurally, never greps files"
    - "A render gate's golden is produced FIRST, by --write-golden from the unmodified files, and --write-golden refuses to overwrite"

key-files:
  created:
    - scripts/check-alertmanager-render.sh
    - infra/monitoring/alertmanager/compose-render.golden.yml
    - k8s/components/monitoring/alertmanager.yaml
    - k8s/components/monitoring/grafana.yaml
    - k8s/components/monitoring/alertmanager/alertmanager.yml.tmpl
    - k8s/components/monitoring/alertmanager/entrypoint.sh
    - k8s/components/monitoring/grafana/datasource-prometheus.yml
    - k8s/components/monitoring/grafana/dashboards-provider.yml
    - k8s/components/monitoring/grafana/stomp-dashboard.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-24-static.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-24-t1-red-render.json
  modified:
    - infra/monitoring/alertmanager/alertmanager.yml.tmpl
    - infra/monitoring/alertmanager/entrypoint.sh
    - infra/monitoring/docker-compose.monitoring.yml
    - .github/workflows/ci-cd.yaml
    - k8s/components/monitoring/kustomization.yaml
    - k8s/components/azure-keyvault/secretproviderclasses.yaml
    - k8s/components/azure-keyvault/workload-patches.yaml
    - k8s/staging/kustomization.yaml
    - k8s/copy-parity.conf
    - k8s/scripts/check-render-invariants.sh
    - k8s/goldens/staging.yaml
    - k8s/base/networkpolicies/README.md
    - scripts/lib/staging-secrets-common.sh
    - scripts/staging-secrets.sh
    - scripts/check-alert-mute.sh
    - infra/dependency-horizons.yaml
    - docs/ops/terminal-states.yaml
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md

key-decisions:
  - "SMTP auth renders on email_configs[0], not as global smtp_auth_*: Alertmanager v0.34.1 copies global auth into every email_config (config.go 406-409) and an empty value cannot opt out, so a global credential would also be offered to the Mailpit sink"
  - "SMTP credentials render only with ALERTMANAGER_SMTP_REQUIRE_TLS=true: Alertmanager's LOGIN auth sends the password in clear, and this machine's .env already carries USERNAME and PASSWORD with a Mailhog smarthost"
  - "ALERTMANAGER_WEB_EXTERNAL_URL is required with no default in the file: the hard-coded loopback URL put a localhost literal into the staging render (INV-4); compose passes the old value, staging the Service URL"
  - "check-alert-mute.sh M-5 reads renders: staging may set the mute as a literal of MUTE_ALLOWLIST names; base, local and production never"
  - "No Key Vault write and no deploy in this plan: 29-25 Task 1 owns --generate/--assign-access and the deploy; the preflight refuses a deploy from this tree until then (12 gaps, the 6 new objects and 6 grants)"
  - "The STOMP dashboard's datasource-uid defect is pre-existing and deferred: the one-line fix crashes Grafana on an existing store"

patterns-established:
  - "external validator kinds in staging-secrets (@email, @nonempty) beside the prefix kind"
  - "render-based mute policy: setting allowed per overlay, names from one allow-list"

requirements-completed: [DPLY-03, DPLY-02]

coverage:
  - id: D1
    description: "Canonical Alertmanager template/entrypoint gain optional SMTP-auth and extra-email_config blocks; compose render byte-identical to the pre-change golden"
    requirement: DPLY-03
    verification:
      - kind: other
        ref: "bash scripts/check-alertmanager-render.sh (10/10; compose sha256 f9b5b39f == golden; RED c054f5f5 staging-env failed, RED_EVIDENCE_OK)"
        status: pass
      - kind: other
        ref: "bash scripts/check-alertmanager-render.sh --selftest (6/6 planted holes caught)"
        status: pass
    human_judgment: false
  - id: D2
    description: "Staging render: auth on the Zoho integration only, Mailpit sink with the cluster CA, alertname-only NoOrdersCreated mute, no password string"
    requirement: DPLY-03
    verification:
      - kind: other
        ref: "check-alertmanager-render.sh arm staging-env (PyYAML structural; selftest global-auth plant reds it)"
        status: pass
      - kind: other
        ref: "render read-back: alertmanager PASSWORD env only the _FILE path; planted literal reds it"
        status: pass
    human_judgment: false
  - id: D3
    description: "Grafana and Alertmanager manifests: only grafana-staging public with its own TLS Secret and noindex; Prometheus/Alertmanager no Ingress"
    requirement: DPLY-03
    verification:
      - kind: other
        ref: "Task 2 verify 2: Ingress host set == the five D-06/D-33 names (unedited tree rc 1)"
        status: pass
      - kind: other
        ref: "check-render-invariants.sh INV-7/INV-15 with five break arms (rc 1 each), selftests inv7/15/16/17/18"
        status: pass
    human_judgment: false
  - id: D4
    description: "Own Workload Identity and SPC each; objects baseline+6, SPCs baseline+2; four Zoho objects external, Grafana pair estate/random"
    requirement: DPLY-02
    verification:
      - kind: other
        ref: "Task 2 verify 3: objects=36 spcs=11 diff 0 (unedited tree rc 1 naming the 8)"
        status: pass
      - kind: other
        ref: "staging-secrets.sh --list rc 0, 0 UNCLASSIFIED rows (HEAD table rc 1, 4 unclassified); --selftest 13/13 (planted @email arm 12/13)"
        status: pass
    human_judgment: false
  - id: D5
    description: "Live behaviour (Zoho delivery, Mailpit L-3, Grafana login, LE certificate for grafana-staging) — not deployed in this plan"
    requirement: DPLY-03
    verification: []
    human_judgment: true
    rationale: "The plan is static by design; the deploy, the owner's Zoho values and the real-inbox confirmation are plan 29-25"

duration: 38min
completed: 2026-10-19
---

# Phase 29 Plan 24: Alertmanager With Scoped SMTP Auth and a Mailpit Sink, and a Public Grafana, Declared for Staging

**The ONE Alertmanager template and entrypoint can now page an authenticated inbox and an inspectable sink on the same route, and compose renders byte for byte what it rendered before (sha256 f9b5b39f, against a golden taken from the unmodified files). A gate runs the real entrypoint inside the pinned image in CI. Staging declares an Alertmanager with two email_configs in one receiver: Zoho smtp.zoho.eu:587 with TLS required and auth_password_file from the CSI mount, and Mailpit with the cluster CA. It mutes NoOrdersCreated only. It also declares a Grafana 13.2.4 at grafana-staging.olajay.co.uk with its admin from Key Vault. Each runs as its own Workload Identity with its own SPC. Nothing was deployed and nothing was written to Key Vault: that is 29-25.**

## Performance

- **Duration:** 38 min (2026-10-19T16:09:30Z to 16:47Z)
- **Tasks:** 2 of 2
- **Commits:** 6, measured from the plan ledger (`1640fb7b..66c99603`)
- **Files:** 29 changed (3715 insertions, 115 deletions; most of it the regenerated staging golden)
- **Live estate touched:** read-only only, on kube-context `jtoye-stg-aks` and subscription c483d353:
  - `kubectl describe nodes`
  - `get networkpolicy` and `get secret` (type and keys only)
  - `az identity` and federated-credential reads through `az_read`
  - the preflight
  - the leak scan
  - No write, no deploy, no push.

## Accomplishments

- **Task 1** (TDD: c054f5f5 RED, 44119984 GREEN, 8f470cbc fix):
  - **Template:** two token lines.
    - `__SMTP_AUTH_BLOCK__` on the primary email_config.
    - `__EXTRA_EMAIL_CONFIGS_BLOCK__` in email-default.
    - Both render to nothing when unset.
  - **Entrypoint:**
    - auth_username and auth_password_file are scoped to email_configs[0].
    - Credentials render only with REQUIRE_TLS=true.
    - A password given in env goes to a mode-600 /tmp file and is unset before exec.
    - The password file and the sink CA must be readable.
    - New inputs: ALERTMANAGER_SINK_{SMARTHOST,TO,CA_FILE}, ALERTMANAGER_TEMPLATE/_RENDERED, ALERTMANAGER_RENDER_ONLY and the required ALERTMANAGER_WEB_EXTERNAL_URL.
  - **Compose** passes USERNAME and PASSWORD with empty defaults, and the external URL with its old value.
  - **Gate:** `scripts/check-alertmanager-render.sh`.
    - Reads the image pin from compose and runs `--network none`.
    - Deletes the image's default config before rendering.
    - Has 10 arms and a 6-plant selftest, and is wired in CI ops-contracts.
- **Task 2** (25c36765 baseline, 0cf35a5d M-5, 66c99603):
  - **Manifests:** `alertmanager.yaml` and `grafana.yaml`, plus five byte copies with their parity pairs and four content-hashed ConfigMaps.
  - **Key Vault:** SPCs alertmanager-kv (the password is mounted as a file and never synced) and grafana-kv, their workload patches and identity replacements.
  - **Policies:** alertmanager-allow and grafana-allow, their INV-7 rows, 587 added to the public ipBlock shape, and the README rows (17 policies in staging).
  - **staging-secrets:** the new classes and validators, with a selftest arm.
  - **Mute gate:** M-5 rewritten to read renders.
  - **Golden:** regenerated.

## Both Directions, Recorded (`evidence/29-24-static.txt`)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| Render gate, staging arm | unmodified entrypoint: rc 1, "1 email_configs, want 2; auth_username=None; auth_password_file=None" (RED_EVIDENCE_OK) | 10/10 rc 0 |
| Compose byte identity | selftest: drifted golden reds it; an unknown __TOKEN__ reds it | sha256 f9b5b39f == golden; this machine's real `.env` renders 51f026d4 with the old and the new files alike |
| Golden provenance | second `--write-golden`: rc 2 "already exists" | harness reproduces the LIVE compose render byte for byte (97a00e0a) |
| Scoped auth (T-29-87) | selftest plant `smtp_auth_username` in global: "global carries ['smtp_auth_username']" | auth on [0] only, sink has no auth_* |
| Gate wired in CI | references renamed: check-gate-enforcement rc 1 "invokes: docker" | rc 0 |
| Copy parity | one byte on the entrypoint copy: rc 1 "pair 6 has DRIFTED" | 9 pairs byte-equal |
| Verify 2, host set | unedited tree: rc 1, grafana-staging missing | rc 0, exactly the five names |
| Verify 3, objects/SPCs | unedited tree: rc 1 naming the 6 objects and 2 SPCs | rc 0, objects=36 spcs=11 |
| INV-7 / INV-15 | 5 arms, each rc 1: a missing row, SMTPS 465 in place of 587, grafana->alertmanager egress, noindex dropped, a shared TLS Secret | rc 0; selftests inv7/15/16/17/18 rc 0 |
| INV-4 | first run: rc 1, `localhost` from the entrypoint's exec line | rc 0 after the external URL became an input; compose argv identical (/proc/1/cmdline) |
| `--list` classes | HEAD table: rc 1, 4 unclassified, Zoho password classed `random` | rc 0, 4 external, estate + random |
| External validators | planted accept-all @email: 12/13 rc 1 | 13/13 |
| M-5 | old grep: rc 1 on 3 legitimate files; arms: mute in base, an unlisted name, valueFrom (rc 1 each); no kubectl rc 2 | rc 0, 1 staging setting |
| Render read-back | planted inline password: rc 1 | rc 0: PASSWORD_FILE only; Grafana password from a secretKeyRef |
| Citations | rc 1, TS-13 points at a stale line | rc 0, 46 verified |

**Capacity:** measured with `kubectl describe nodes`, read-only. All four nodes are B2s with 2787Mi allocatable.
- **Free memory per node:** node 0 605Mi, node 1 1423Mi, node 2 99Mi, node 3 1149Mi.
- **Will the new pods schedule?** Together they request 70m and 192Mi. That fits on node 1 or node 3, and not on node 2.
- **Can a 1Gi surge still roll?**
  - If both pods land on node 3, it keeps 957Mi, which is less than a 1Gi core-java or Keycloak surge.
  - Node 1 would still keep 1423Mi, so one 1Gi surge still fits.
- **The after-deploy figure** is 29-25's to measure.

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| Alertmanager render | c054f5f5: 3/9 pass; staging-env and 5 other new behaviours fail | 44119984: 9/9 (10/10 after 8f470cbc) | RED_EVIDENCE_OK (`29-24-t1-red-render.json`) |

**Semantic assessment:**
- **RED:** the target arm rendered completely (amtool SUCCESS appears in the entrypoint's log) and then failed on its planned assertions.
- **Control:** the two compose arms and the mute arm passed in the same run. Docker, PyYAML and the golden were therefore sound.

## Deviations from Plan

### Auto-fixed and adapted (recorded, not silent)

**1. [Rule 2 - Security] Auth on the email_config, not in global** (44119984)
- **The plan said** "in global, a line `__SMTP_AUTH_BLOCK__`", and its behaviour line names `smtp_auth_username`.
- **Why that is unsafe:** Alertmanager v0.34.1 copies global auth into every email_config, and an empty value cannot opt out. Measured in config.go 406-409. The Zoho credential would therefore also be offered to the Mailpit sink.
- **What renders instead:** `auth_username` and `auth_password_file` on email_configs[0].
- **How it is held:** the gate asserts global carries no `smtp_auth_*`, and a selftest plant proves that assertion can fail. The token name is unchanged.

**2. [Rule 2 - Security] Credentials only with REQUIRE_TLS=true**
- **Why:** this machine's `.env` holds an SMTP username and a 12-character password, with a Mailhog smarthost and no TLS. Mailhog advertises AUTH, and Alertmanager's LOGIN auth (email.go 389) sends the password in clear.
- **Without the guard,** the plan's compose pass-through would have sent the real password to Mailhog.
- **Result:** the arm `compose-credentials-without-tls` holds the render byte-identical.

**3. [Rule 3 - Blocking] The external URL** (8f470cbc)
- **Cause:** the byte-copied entrypoint's hard-coded loopback URL failed INV-4 in the staging render.
- **Fix:** it is now a required input. Compose's argv is unchanged (read from /proc/1/cmdline), and staging uses the Service URL.

**4. [Rule 3 - Blocking] check-alert-mute.sh M-5** (0cf35a5d; outside `files_modified`)
- **Conflict:** M-5 failed on any file under `k8s/` naming the variable. The plan's own must-have (the staging mute) and its byte-copied entrypoint could not both pass it.
- **Fix:** M-5 now reads renders. That is stronger in the production direction, and it now also catches valueFrom and names that are not on the allow-list.
- **First-draft defect:** a `set -e` silent exit. Fixed so that a failed read is a VOID.

**5. [Plan location] Classification is in `scripts/lib/staging-secrets-common.sh`** (the 29-13 move); the external validators are in `scripts/staging-secrets.sh`.

**6. [Rule 2] An explicit row for the Zoho password**
- **Why:** under the HEAD table, `*password*` classed it `random`, so `--generate` would have written a random Zoho password nobody can log in with.

**7. [Unfalsifiable as written] "Exactly 0 lines containing 'unclassified'"**
- **Why it cannot pass:** the summary line `0 unclassified` itself contains the word.
- **Replaced by:** the count of UNCLASSIFIED rows (0, and 4 with the HEAD table), plus rc. This is the same replacement 29-23 made.

**8. [Additions]**
- **Refusal arms** match their specific FATAL message, not any FATAL.
- **Grafana:**
  - The preinstall and plugin-update checks are off.
  - Read-only root, with emptyDirs at `/var/lib/grafana` and `/tmp`.
  - Measured locally first.
- **Alertmanager:** Recreate with one replica, so no duplicate notifications during a rollout.
- **Every new pod:** `enableServiceLinks: false`.
- **Citations shifted by the compose edits:**
  - TS-13 now cites line 193.
  - The two horizon sites now cite lines 174 and 194.

**Total:** 2 security fixes, 2 blocking fixes, 1 location adaptation, 1 missing-row fix, 1 criterion replaced, and the additions above. **Impact:** each one makes a plan claim true or safe. Scope is unchanged.

## Findings (deferred-items.md)

- **The STOMP dashboard is empty, in compose today and in staging.**
  - **Cause:** its panels name datasource uid `prometheus`, which nothing provisions.
  - **Measured:** the query returns "Data source not found".
  - **Trap for the fix:** adding `uid: prometheus` to the datasource makes Grafana exit 1 on an EXISTING store ("Datasource provisioning error: data source not found"), which is compose's `grafana_data` case. The fix needs `deleteDatasources`, or a change on the dashboard side, and must be proven on both a fresh and an existing store.
  - **Not fixed here:** the defect predates this plan.
- **The local compose Alertmanager is stale.**
  - It runs `prom/alertmanager:v0.27.0`, while compose pins v0.34.1.
  - Its environment differs from the current `.env`.
- **`.env.example` cites a compose line that was already wrong before this plan.**

## Open Items for 29-25

- **The preflight refuses a deploy from this tree.**
  - It reports rc 1 with 12 gaps: the 6 new objects and their 6 grants.
  - 29-25 Task 1 runs `--generate` and `--assign-access`. It leaves exactly the four Zoho objects, which the owner sets in Task 2 (stdin through `--set-external`).
- **Before deploying,** measure node memory again. The pods need 192Mi on node 1 or node 3.
- **NoOrdersCreated:** the staging mute withholds only the notification. The counter still vanishes on a core-java restart (29-23), so `check-alert-metrics` M-1 still needs `seed-order-metric.sh` after a roll. The mute just means nobody is paged meanwhile.
- **Liveness L-3:** it fails when `failed_total{email}` rises. With two integrations in one receiver, a bad Zoho password reds L-3 even though Mailpit receives the message. That is 29-25's intended break arm.
- **The certificate for grafana-staging** issues over HTTP-01 under the same acme-http01-solver-allow policy that mail-staging's used. DNS already answers 51.105.43.14 at both registrar servers.
- **Rotation:** `docs/runbooks/credential-rotation.md` has no section yet for the Zoho app password or the Grafana admin password. That is 29-27's runbook work.

## Threat Flags

None beyond the plan's threat model. How each threat is covered:
- **T-29-87:** password_file only; the render gate finds no password string; the read-back covers the env.
- **T-29-88:** the admin comes from Key Vault; anonymous access and sign-up are off (measured locally).
- **T-29-89:** the host set is exact.
- **T-29-90:** compose byte identity is checked in CI.
- **T-29-91:** the mute is alertname-only, enforced by the entrypoint and by M-5.

## Self-Check: PASSED

- **Files:** FOUND `scripts/check-alertmanager-render.sh`, `infra/monitoring/alertmanager/compose-render.golden.yml`, `k8s/components/monitoring/{alertmanager.yaml,grafana.yaml}`, the 5 byte copies, `evidence/29-24-static.txt` and `evidence/29-24-t1-red-render.json`.
- **Commits:** c054f5f5, 44119984, 25c36765, 8f470cbc, 0cf35a5d and 66c99603 are ancestors of HEAD. `git rev-list --count 1640fb7b..66c99603` = 6.
- **Leaks:**
  - `gitleaks dir` found none in either evidence file.
  - `staging-secrets.sh --leak-scan` on the static evidence: 24 objects checked, 0 found.
- **Final re-run (16:46Z):** T1 verify 1 and verify 2, T2 verify 1, verify 2 and verify 3, and M-5 all rc 0. No tracked file is left dirty.
