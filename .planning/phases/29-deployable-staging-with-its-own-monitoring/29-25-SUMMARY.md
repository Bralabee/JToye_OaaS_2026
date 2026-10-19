---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 25
subsystem: infra
tags: [alertmanager, grafana, zoho, mailpit, alert-liveness, alert-metrics, key-vault, staging, dply-03]
status: complete

requires:
  - phase: 29-23
    provides: Prometheus + exporters in jtoye-staging; check-alert-liveness.sh PROM_EXEC/SINK=mailpit
  - phase: 29-24
    provides: Alertmanager (Zoho + Mailpit in one receiver, NoOrdersCreated mute) and Grafana manifests, SPCs, classes
provides:
  - Alertmanager and Grafana LIVE in jtoye-staging at 6e95ee6c; grafana-staging.olajay.co.uk with a Let's Encrypt certificate
  - DPLY-03 against staging - check-alert-metrics rc 0 and check-alert-liveness rc 0, timestamped
  - The owner's real-inbox receipt of probe-1792431424-1876833 (inbox, not spam)
  - A delivery failure on the human integration proven visible, and now caught by the liveness gate
  - ss_external_valid - one validator for the Key Vault writer and the preflight
affects: [29-27, 29-28]

actuals:
  tokens: 13400
  tasks: 4
  commits: 7
plan_head_before: 4945dc00c344586cdf84005803305a3860d5fabd
plan_head_after: ea63cdc248c8a3a468c28c18c9a1bbffab75a519

tech-stack:
  added: []
  patterns:
    - "A gate that proves one integration of a multi-integration receiver must outlast Alertmanager's retry horizon (group_interval, read from the running instance) before it can call the route healthy"
    - "Writer and preflight share one validator function, so they cannot disagree about what a valid value is"

key-files:
  created:
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-25-dply03.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-25-t1-red-preflight-validators.json
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-25-t3-red-liveness-settle.json
  modified:
    - scripts/staging-secrets-preflight.sh
    - scripts/staging-secrets.sh
    - scripts/lib/staging-secrets-common.sh
    - scripts/check-alert-liveness.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/deferred-items.md

key-decisions:
  - "L-3 of check-alert-liveness.sh now holds, after the sink hit, until route.group_interval + 30 s (read from /api/v2/status) and fails if failed_total{email} rises: the old 120 s L-3 PASSED a broken Zoho path because the Mailpit copy arrived and Alertmanager counts the failure only when the retry window closes (5 min)"
  - "The plan's break arm (unset the password file, set the env literal) is impossible in staging: the read-only pod has no /tmp, the entrypoint fails closed. The arm used the password FILE pointing at /etc/hostname - a wrong, non-secret value"
  - "The preflight reads an @-validator kind (@email, @nonempty) through ss_external_valid; an invalid value is INVALID-VALUE <object>"
  - "The three alert-mail addresses are Key Vault object values; evidence names them by object, so the leak-scan stays strict"

requirements-completed: [DPLY-03, DPLY-02]

coverage:
  - id: D1
    description: "check-alert-metrics.sh exits 0 against staging, timestamped"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "PROM_URL=http://localhost:19090 bash scripts/check-alert-metrics.sh -> rc 0 at 17:36:04Z and 18:09:14Z (PASS 19 live rules / 25 selectors); planted dead rule -> rc 1 naming it"
        status: pass
    human_judgment: false
  - id: D2
    description: "check-alert-liveness.sh exits 0 against staging, L-3 included, and fails when the human integration is broken"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "fixed gate rc 0 at 17:55:27Z and 18:09:18Z (failed 0 -> 0 -> 0 over the 330 s horizon); Zoho re-broken -> rc 1, FAIL L-3 failed_total rose 0 -> 1"
        status: pass
      - kind: unit
        ref: "bash scripts/check-alert-liveness.sh --selftest -> 10/10 (RED 8/10, RED_EVIDENCE_OK)"
        status: pass
    human_judgment: false
  - id: D3
    description: "The synthetic alert reaches the owner's real inbox through Zoho"
    requirement: DPLY-03
    verification:
      - kind: manual
        ref: "OWNER-INBOX-CONFIRMED: probe-1792431424-1876833, inbox not spam, body pasted verbatim (evidence 29-25-dply03.txt)"
        status: pass
    human_judgment: true
    rationale: "Only the owner can read the inbox; the confirmation is recorded with the owner's own text, matched by run id and start time"
  - id: D4
    description: "A delivery failure is visible: failed_total{email} rises while Mailpit still receives; restored and read back"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "break arm: 535 logged 17:45:45Z, failed_total 0 -> 1 at 17:50:45Z, Mailpit copy 17:45:45Z; restore: env == render (jq -S diff rc 0, control rc 1), failed_total flat over 400 s"
        status: pass
    human_judgment: false
  - id: D5
    description: "Routing: NoOrdersCreated mute only, everything else to email-default"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "amtool config routes test: NoOrdersCreated -> mute-null; StagingProbe info -> email-default; --verify.receivers wrong expectation rc 1; near names and severity/service -> email-default"
        status: pass
    human_judgment: false
  - id: D6
    description: "Grafana: Key Vault admin works, wrong password and anonymous refused, datasource OK, STOMP dashboard listed"
    requirement: DPLY-03
    verification:
      - kind: integration
        ref: "/api/user 200 / 401 / anonymous 401; datasource health OK (non-existent uid 500); search stomp -> STOMP Broker Relay (no-match control [])"
        status: pass
    human_judgment: false
  - id: D7
    description: "Key Vault complete: preflight 0 gaps before the deploy"
    requirement: DPLY-02
    verification:
      - kind: integration
        ref: "preflight 12 gaps rc 1 (17:27Z) -> 41 objects, 45 grants, 0 gaps rc 0 (17:32Z); preflight --selftest 17/17 (RED 8/17)"
        status: pass
    human_judgment: false

duration: 53min
completed: 2026-10-19
---

# Phase 29 Plan 25: Staging Alerting Live (DPLY-03), a Real Inbox Confirmed, and a Delivery Failure Made Visible

**Alertmanager and Grafana now run in jtoye-staging. Against staging, both DPLY-03 gates exit 0, with ISO-8601 timestamps. The L-3 probe probe-1792431424-1876833 reached the owner's real inbox through Zoho, and the owner confirmed it landed in the inbox, not spam. A broken Zoho password is shown raising `alertmanager_notifications_failed_total{email}` while the Mailpit copy still arrives. Running that break arm found that the liveness gate itself PASSED a broken human path. L-3 now holds through Alertmanager's retry horizon and fails on it.**

## Performance

- **Duration:** about 53 minutes (17:27Z to 18:20Z)
- **Tasks:**
  - Tasks 1 and 3 executed.
  - Task 2 was satisfied by the orchestrator's checkpoint resolution.
  - Task 4 was satisfied by the owner's in-session confirmation, which the coordinator relayed.
- **Commits:** 7, measured from the plan ledger (`4945dc00..ea63cdc2`).
- **Live estate touched:** kube-context `jtoye-stg-aks`, Key Vault `jtoye-stg-kv`, subscription c483d353.
  - **Key Vault:** `--generate` wrote 2 objects (the Grafana admin pair). `--assign-access` created 6 grants. Both commands carried `[az-ok] 29-25`.
  - **Deploys:** three `deploy.sh` runs at 6e95ee6c: the first deploy, then two restores.
  - **Break arms on the alertmanager Deployment:** three `kubectl set env` patches.
  - **Probe mail:** four synthetic alerts went to the owner's inbox, each with FIRING and RESOLVED mail: two probes from runs of the fixed gate, plus the break and restore probes.
  - **Not used:** the pre-approved order, because core-java never rolled.
  - **Not done:** no push.

## Accomplishments

- **Task 1** (f34f084d RED, 2382de1e GREEN, c56d9836 evidence)
  - **Fail direction:** before `--generate`, the preflight reported 12 gaps (rc 1).
    - 2 were missing Grafana objects, and 6 were missing grants.
    - The other 4 were NOT-TEST-MODE lines for the Zoho objects, and those came from a preflight defect (below), not from the vault.
  - **Pass direction:** after `--generate` and `--assign-access`, the preflight reported 41 objects, 45 grants and 0 gaps (rc 0).
- **Task 2:** satisfied by the checkpoint resolution. The four Zoho values were in Key Vault before this plan ran, so the plan's literal Task 1 verify ("missing == exactly the four Zoho objects") could no longer occur. The fail direction recorded instead is the live preflight taken before `--generate`.
- **Task 3** (b039fde2 RED, 2e17479a GREEN, 1758557c evidence)
  - **Deploy:** a pre-deploy diff showed only new objects, so core-java did not roll and the order counter survived. The Grafana certificate was Ready in about 30 s.
  - **Gates:** both gates are rc 0, and each was shown failing first.
  - **Routing:** both directions tested.
  - **Break and restore:** shown by counter, by Mailpit, and by reading back the pod env.
  - **Grafana:** 200 for the admin, 401 for a wrong password and for anonymous access, the datasource reports OK, and the STOMP dashboard is listed.
- **Task 4:** the result is recorded as `OWNER-INBOX-CONFIRMED`, with the owner's pasted body verbatim.
  - **Match:** the run id equals `L3-PROBE-ID`, and `Started 17:37:04Z` falls inside the 17:36:57Z to 17:37:36Z run.

## Both Directions, Recorded (`evidence/29-25-dply03.txt`)

| Check | Fail direction | Pass direction |
|-------|----------------|----------------|
| Preflight (live) | 12 gaps, rc 1 (17:27Z) | 0 gaps, rc 0 (17:32Z) |
| Preflight selftest | RED 8/17; planted accept-all @email gives 16/17 | 17/17; writer selftest 13/13 |
| check-alert-metrics | dead rule planted in a scratch copy: rc 1, "PlantedNoSeries29x25 matches ZERO series" | rc 0 at 17:36:04Z and at 18:09:14Z |
| check-alert-liveness (fixed) | Zoho re-broken: rc 1, "failed_total{email} rose 0 -> 1 300s after the probe reached Mailpit" | rc 0 at 17:55:27Z and at 18:09:18Z: 0 -> 0 -> 0 over the 330 s horizon |
| Liveness selftest | RED 8/10 (RED_EVIDENCE_OK) | 10/10 |
| Routing | `--verify.receivers=mute-null` for StagingProbe: rc 1 | NoOrdersCreated -> mute-null; StagingProbe -> email-default; NoOrdersCreatedX and XNoOrdersCreated -> email-default |
| Delivery-failure visibility | wrong password: 535, failed_total 0 -> 1, Mailpit copy received | restored: failed_total flat for 400 s, Mailpit FIRING + RESOLVED, 0 WARN/ERROR lines |
| Restore read-back | broken env vs render: diff rc 1 (control) | live env == render (`jq -S` diff rc 0); pod env shows only `_FILE` |
| Grafana | wrong password 401; anonymous 401; unknown datasource uid 500; no-match search [] | admin 200; health OK; STOMP Broker Relay listed |

**Capacity (D-34):**
- **Pod placement:**
  - Grafana landed on node 3.
  - Alertmanager's final pod is on node 1. The restores rescheduled it from node 3.
- **Requested memory after the deploy:** 2182, 1428, 2688 and 1766 Mi.
- **Rolling still works:** a 1Gi surge still fits on node 1 or node 3.

## TDD Gate Compliance

| Cycle | RED | GREEN | Classifier |
|-------|-----|-------|------------|
| Preflight @-validators | f34f084d: 8/17, target `invalid-external-value` | 2382de1e: 17/17 | RED_EVIDENCE_OK (`29-25-t1-red-preflight-validators.json`) |
| Liveness retry horizon | b039fde2: 8/10, target `late-integration-failure` | 2e17479a: 10/10 | RED_EVIDENCE_OK (`29-25-t3-red-liveness-settle.json`) |

**Semantic assessment:**
- **RED runs:** the target arms ran against the stubs and failed on their planned assertions. The pre-existing passing arms in the same runs show the harness was sound.
- **Liveness defect, live:** it was measured on staging before any code change. The old gate exited 0 against a broken Zoho path.

## Deviations from Plan

### Auto-fixed issues

**1. [Rule 3 - Blocking] The preflight could pass on no vault** (f34f084d, 2382de1e)
- **Cause:** 29-24 classed the owner values `external @email` and `external @nonempty`, but the preflight read the kind as a literal value prefix. Every well-formed value was therefore NOT-TEST-MODE.
- **How it stayed hidden:** its own selftest was 8/16 on HEAD, and no CI job runs it.
- **Fix:** `ss_external_valid` in `scripts/lib/staging-secrets-common.sh` is now the one rule, shared by the writer and the preflight. A failing @-kind value is reported as `INVALID-VALUE <object>`.

**2. [Rule 1 - Bug, gate] L-3 passed a broken human path** (b039fde2, 2e17479a)
- **How it showed:** with a wrong Zoho password, `check-alert-liveness.sh` printed "transport verified end-to-end" at 17:51Z.
- **Why it passed:** Alertmanager logged 535 at 17:45:45Z but counted `failed_total` only at 17:50:45Z ("notify retry canceled after 16 attempts", one group_interval later). The old 120 s L-3 had already passed on the Mailpit copy.
- **What 29-24 said:** its open item assumed a bad password "reds L-3". It did not.
- **Fix:** L-3 now reads `route.group_interval` from `/api/v2/status` (unreadable or unparseable means VOID), then polls `failed_total{email}` for group_interval + 30 s after the hit.
- **Cost:** a healthy run takes about 5.5 minutes longer, in compose too. The header documents this.

**3. [Plan shape impossible] The break arm**
- **The plan's shape:** unset `_FILE` and set the env literal.
- **What happened:** the pod crash-looped, because the entrypoint writes the literal to `/tmp` and the staging pod has a read-only root with no `/tmp`. It failed closed and was recorded.
- **Arm used instead:** the `_FILE` pointed at `/etc/hostname`. That is a wrong value and not a secret (T-29-94 holds).
- **Restore:** `deploy.sh`, both times.

**4. [Evidence hygiene] Alert-mail addresses redacted**
- **Why:** `--leak-scan` reports the from, to and username values, because they are Key Vault object values.
- **What changed:** the evidence names them by object, and leak-scan now finds 0 of 29.

**5. [Instrument] One invalid comparison**
- **What went wrong:** the first "live env == render" check used `yq`, which is not installed here, so it compared against nothing.
- **What replaced it:** a `jq -S` diff, with a control showing that the broken value produces a diff (rc 1). Both are recorded.

**Total:** 2 gate defects fixed test-first, 1 plan-shape adaptation, and 2 evidence corrections. **Impact:** DPLY-03 is now true of the human path, not only of the sink.

## Findings (deferred-items.md)

- **`deploy.sh` waits only on core-java, edge-go and frontend.** It printed `DEPLOYED_SHA` while Alertmanager and Grafana were still ContainerCreating.
- **Neither staging-secrets selftest runs in CI.** That is how defect 1 shipped.
- **The frontend has no scrape job** in compose or in k8s. #98 criterion 1 says "all three services", and 29-27 owns that disposition. core-java and edge-go are up, each scraped per pod.
- **Setting the env-literal SMTP password crashes Alertmanager in k8s.** This is fail-closed. It still needs a note in the runbook: rotate only through Key Vault.

**Observations from the persona swarm** (running since about 17:45Z):
- **Real alerts:** Alertmanager held a real `NoOrdersCreated`, active and routed to `mute-null`. That is live proof the mute withholds mail. No other rule was firing at 18:15Z.
- **Mailpit:** no non-probe FIRING mail since 17:30Z.

## Threat Flags

None beyond the plan's threat model.
- **T-29-92:** covered by L-3 end to end, the owner's confirmation, and the failure counter shown rising. The gate now enforces that last one.
- **T-29-93:** the admin password is random from Key Vault; wrong-password and anonymous requests get 401.
- **T-29-94:** the break arm used a wrong value that is not a secret. The real password never left the CSI mount.
- **D-19:** no Ingress for Prometheus or Alertmanager, and no DNS record for either name.

## Self-Check: PASSED

- **Files:** FOUND `evidence/29-25-dply03.txt`, both RED JSON records, and the four modified scripts.
- **Commits:** f34f084d, 2382de1e, c56d9836, b039fde2, 2e17479a, 1758557c and ea63cdc2 are ancestors of HEAD. `git rev-list --count 4945dc00..ea63cdc2` = 7.
- **Leaks:**
  - `staging-secrets.sh --leak-scan` found 0 in the evidence file and in both RED records.
  - `gitleaks dir` found no leaks.
- **Port-forwards:** all three were killed by PID, and none is alive.
