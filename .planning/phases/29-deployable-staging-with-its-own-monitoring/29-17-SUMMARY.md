---
phase: 29-deployable-staging-with-its-own-monitoring
plan: 17
subsystem: infra
tags: [networkpolicy, cilium, aks, dply-05, enforcement, agnhost, live, staging, blocker-f]
status: complete

requires:
  - phase: 29-14
    provides: "Staging live at DEPLOYED_SHA a784e7e2, with default-deny and the allow-lists applied"
  - phase: 29-15
    provides: "The live edge (Traefik -> core-java), used as the external read of Blocker F"
  - phase: 29-03
    provides: "The agnhost 2.66.1 tag+digest pin in k8s/platform/acme-probe/probe.yaml"
provides:
  - "scripts/check-networkpolicy-enforcement.sh: a live probe of NetworkPolicy enforcement. Ten arms in a fixed order, a policy-free namespace control, run-id hygiene, --only ARM, and a --selftest with 22 cases. Registered in gate-enforcement.conf"
  - "evidence/29-17-enforcement.txt: #297's closing evidence. Captured denials with their controls, Blocker F shown in both directions, and run-id hygiene shown live"
affects: [29-23, 29-26, 29-27]

estimate:
  tokens: 70000
  tasks: 2
actuals:
  tokens: 13200
  tasks: 2
  commits: 3
plan_head_before: 9fb61b70e60d140de097ebbc4832626de487c8b6
plan_head_after: 4b2ecd7d4ef448e98ebb214386dcc1a2e98ca8ff

tech-stack:
  added: []
  patterns:
    - "A denial means something only when two things connect. The same client must connect in a namespace with no policy (P0), and a labelled control must connect on the same path. If either fails, the run is VOID"
    - "A run-scoped egress policy, selecting only the probe pods, opens the probes' own egress. That way a denial is the target's ingress rule and not the probe's default-deny"
    - "Every scratch object is named and labelled with the RUN_ID. Before creating anything, the run refuses if it finds another run's objects. After creating them, the listing must count them all. On exit, a trap deletes them by name and prints CLEANUP remaining=N"

key-files:
  created:
    - scripts/check-networkpolicy-enforcement.sh
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-17-enforcement.txt
    - .planning/phases/29-deployable-staging-with-its-own-monitoring/evidence/29-17-t1-red-classifier.json
  modified:
    - scripts/gates/gate-enforcement.conf

key-decisions:
  - "E2 probes 1.1.1.1:80, not the plan's :25. Port 25 also times out from a namespace with no policy, so a denial there would be Azure's or the remote host's, not the policy's"
  - "Blocker F was restored by re-adding the saved rule with a JSON patch, not by deploy.sh. The restored spec was then compared by content against both the saved copy and the branch's kustomize render. The lane forbade an app redeploy, and a deploy.sh run of the current branch could roll workloads"
  - "Probes are long-lived agnhost pause pods, and each arm is a kubectl exec. The classifier removes kubectl's trailing 'command terminated with exit code N' line and requires N to equal the exit status"
  - "A run-scoped egress policy, np-probe-<RUN_ID>-egress, lets the probe pods reach app=core-java on 9090 and 9091 and app=keycloak on 8080. Without it, M1 could never connect, because no Prometheus egress policy exists before 29-23. B, M2 and K2 would then be denied by the probe's own egress rule and not by core-java's or Keycloak's ingress rule"

requirements-completed: [DPLY-05]

coverage:
  - id: D1
    description: "On jtoye-stg-aks (Cilium), an unlabelled pod reaching core-java:9090 times out while an app=frontend pod connects. Timestamps captured"
    requirement: DPLY-05
    verification:
      - kind: other
        ref: "evidence 29-17-enforcement.txt, opening and closing runs: ARM A CONNECTED 11:37:12Z, ARM B DENIED 11:37:17Z (TIMEOUT, exit 1); plan verify 1 verbatim exit 0"
        status: pass
    human_judgment: false
  - id: D2
    description: "The same unlabelled client connects in a fresh namespace with no NetworkPolicy"
    requirement: DPLY-05
    verification:
      - kind: other
        ref: "ARM P0 CONNECTED in every run (target 8080 and 1.1.1.1:80). With the plan's port 25, P0 reads DENIED and the run is VOID (rc 2)"
        status: pass
    human_judgment: false
  - id: D3
    description: "Blocker F live in both directions"
    requirement: DPLY-05
    verification:
      - kind: other
        ref: "Rule removed: --only F1 gives ARM F1 DENIED, rc 1, and the public API curl returns crc 28. Restored: spec cmp 0 against both the saved copy and the kustomize render (both were 1 while removed), --only F1 gives CONNECTED rc 0, and curl returns 200"
        status: pass
    human_judgment: false
  - id: D4
    description: "Port 9091 is reachable only from app=prometheus; core-java egress allows 443 and denies a port outside its allow-list"
    requirement: DPLY-05
    verification:
      - kind: other
        ref: "M1 CONNECTED / M2 DENIED on the serving pod's IP:9091 (taken from the EndpointSlice); E1 1.1.1.1:443 CONNECTED / E2 1.1.1.1:80 DENIED"
        status: pass
    human_judgment: false
  - id: D5
    description: "An interrupted or concurrent run leaves no probe behind and never deletes another run's objects"
    requirement: DPLY-05
    verification:
      - kind: unit
        ref: "--selftest cases 20-22 (foreign artefacts refused, own artefacts allowed, artefact count mismatch VOID)"
        status: pass
      - kind: other
        ref: "Live: a concurrent run Y was REFUSED (rc 1), X's 4 objects were untouched, and X then passed. A SIGTERM'd run exited rc 143 with CLEANUP remaining=0. Plan verify 2 verbatim: empty listing, with the control namespace/jtoye-staging found"
        status: pass
    human_judgment: false
  - id: D6
    description: "The classifier's verdicts are tested. A reset is never a denial"
    requirement: DPLY-05
    verification:
      - kind: unit
        ref: "RED a83a29be: 3 of 21 failed, 'expected RESET, got DENIED', RED_EVIDENCE_OK. GREEN ab7d6841: 22 of 22"
        status: pass
    human_judgment: false

duration: 16min
completed: 2026-10-19
---

# Phase 29 Plan 17: NetworkPolicy Enforcement, Captured as a Denial With Its Controls

**On the staging AKS cluster (Cilium), the gate captured live denials. Unlabelled pods time out on core-java:9090, on core-java's management port 9091 and on keycloak:8080, and core-java's own pods time out on public port 80. In the same run, the matching labelled controls connect, and the same client connects in a namespace that has no policy. Blocker F was shown live in both directions. With the traefik rule removed, the controller namespace's probe timed out and the public API timed out. After the restore, both connect again, and the restored policy matches the committed render byte for byte.**

## Performance

- **Duration:** about 16 min (11:25:55Z to 11:41:46Z)
- **Tasks:** 2 of 2
- **Commits:** 3, measured from the plan ledger (9fb61b70..4b2ecd7d): a83a29be (RED), ab7d6841 (GREEN) and 4b2ecd7d (evidence)
- **Live estate touched:** kube-context `jtoye-stg-aks` only. No Azure calls, no app redeploy and no image change. The writes were:
  - Seven gate runs, each creating a probe namespace, probe pods and a scratch egress policy, then deleting them. Every run ended with `CLEANUP remaining=0`.
  - One live policy edit, the Blocker-F removal arm. It was restored and read back.

## Accomplishments

- **Task 1, `scripts/check-networkpolicy-enforcement.sh`**
  - **Carried from the branch:** the header reasoning, the "WHAT NOT TO FIX" list, the rule that controls run first and void the run, and the always-failing readinessProbe.
  - **Added:**
    - The classifier, with a 22-case TAP selftest. It was written only after agnhost 2.66.1's actual output was measured: `TIMEOUT`, `REFUSED`, empty output, and `DNS: ...`.
    - P0, run in a policy-free namespace.
    - The F1, M1/M2, E1/E2 and K1/K2 arms.
    - `--only ARM`.
    - Run-id naming and labelling, the foreign-artefact refusal, the `ARTEFACTS seen=n expected=n` listing control, and the EXIT/INT/TERM/HUP trap with `CLEANUP remaining=N`.
    - The shared staging kube guard.
    - The image, read from the acme-probe pin, with exactly one digest-pinned line required.
  - **RED (a83a29be):** the classifier put "refused" in the TIMEOUT branch. Cases 4-6 failed with "expected RESET, got DENIED", rc 1, and the classifier check returned `RED_EVIDENCE_OK`. The gate registration went into the same commit.
  - **GREEN (ab7d6841):** the selftest passes 22 of 22. Without `--context` the gate exits 2 with a USAGE ERROR line. It refuses `sipbihs2aks` and the stale `jtoye-staging` context (rc 1). Removing the registration line makes check-gate-enforcement exit 1; it was restored and the blob hash checked.
- **Task 2, live (4b2ecd7d)**
  - **Opening and closing clean runs:** both returned the exact plan sequence `P0=CONNECTED A=CONNECTED B=DENIED F1=CONNECTED M1=CONNECTED M2=DENIED E1=CONNECTED E2=DENIED K1=CONNECTED K2=DENIED`. Both had rc 0, `ARTEFACTS seen=9 expected=9` and `CLEANUP remaining=0`. Plan verify 1 and verify 2, run verbatim, each exit 0.
  - **Blocker-F arm:**
    - A guarded JSON patch (`test` that the rule is the traefik namespace, then `remove /spec/ingress/1`) took effect at 11:34:29Z.
    - `--only F1` then gave `ARM F1 DENIED` and rc 1, and `curl https://api-staging.olajay.co.uk/api/v1/public/shops` gave code 000, crc 28.
    - The restore re-added the saved rule at 11:35:39Z.
    - Read back, the live spec matched both the saved spec and the render (cmp 0); both comparisons had been 1 while the rule was removed. Annotations and labels also match (cmp 0).
    - `--only F1` then gave CONNECTED, rc 0, and the curl returned 200.
  - **Run-id hygiene, live:**
    - A second run started while the first held its objects. It printed REFUSED, listed the 4 foreign objects and exited 1. The first run's objects were still present, and it then passed.
    - A full run killed with SIGTERM after creating its objects exited 143 with `CLEANUP remaining=0`, and an independent listing found nothing.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 1 - Bug in the plan's arm] E2 uses 1.1.1.1:80, not :25**
- **Found during:** Task 2, by measurement.
- **Issue:** with `NP_EGRESS_DENY_PORT=25`, P0's policy-free leg timed out on 1.1.1.1:25 from a namespace with no NetworkPolicy, and the run was VOID (rc 2, recorded). The cause is Azure's outbound-25 block or no listener at that address. Either way, a TIMEOUT on 25 is not the policy's, and the plan's E2 would have reported a denial on a cluster that enforces nothing.
- **Fix:** the default is port 80, which connects with no policy present and is outside core-java's egress allow-list. P0 now also connects to the E2 port, which is what exposed the problem, and the header forbids port 25.
- **Commit:** ab7d6841 (the port is configurable through NP_EGRESS_DENY_PORT); the measurement is in 4b2ecd7d.

**2. [Lane constraint] Blocker F restored by a JSON patch, not `deploy.sh`**
- **Plan:** restore by `bash scripts/deploy.sh staging --image-tag <current tag>`.
- **Lane authority:** no app redeploy. CI is building new images, and running core-java is deliberately one STARTTLS literal behind the branch until 29-22. `deploy.sh` re-renders the current branch and can roll Deployments.
- **Done instead:**
  - The saved rule was re-added with a JSON patch.
  - The restore was verified by content against the saved spec, as the plan asks.
  - It was also verified against the spec rendered from the branch (`kubectl kustomize k8s/staging`), which shows the cluster is back on its committed policy.
  - Both comparisons were shown to fail (cmp 1) while the rule was removed.
- **Commit:** 4b2ecd7d (evidence).

**3. [Rule 2 - Missing for correctness] The run-scoped probe egress policy**
- **Issue:** default-deny also blocks every probe pod's egress. M1 could never connect, because no Prometheus egress policy exists until 29-23. B, M2 and K2 would then measure the probe's own egress denial, not core-java's or Keycloak's ingress allow-list.
- **Fix:** `np-probe-<RUN_ID>-egress` selects only the run's pods and allows egress only to app=core-java on 9090/9091 and app=keycloak on 8080. It has no ipBlock and no ingress rule. It is counted in ARTEFACTS and deleted by the trap. This is not a widening of any workload's policy, because no workload carries the run label.
- **Commit:** a83a29be / ab7d6841

### Plan adaptations

- **Probes are long-lived pause pods probed with `kubectl exec`.** The branch used run-to-completion pods, one per arm. The exec form needs the kubectl trailer handling described above, and it is covered by selftest cases 2, 6 and 11.
- **ClusterIP targets.** A, B, F1, K1 and K2 target the Service ClusterIP, because the unlabelled probes have no DNS egress. M1 and M2 target the serving pod's IP, read from the EndpointSlice, because the Service does not expose 9091. The pod IP is never chosen with `-l app=core-java`, since the E probe carries that label.
- **The ARTEFACTS and CLEANUP listings include networkpolicies.** The plan listed only `ns,pods`, and the run also creates a policy.
- **Two live arms beyond the plan:** the concurrent-run refusal and the SIGTERM cleanup. Both use only the gate itself, and both make the backstop truth a live observation.

**Total deviations:** 3 (1 bug, 1 lane constraint, 1 missing), plus 4 adaptations. **Impact:** the proof is stronger than the plan's literal form. Each denial is attributable to the target's policy, and the vacuous E2 was caught before it could be recorded as a pass.

## Findings

- **The plan's E2 could not fail.** 1.1.1.1:25 times out whether or not a policy exists. Only P0's policy-free leg caught it. This is the "a check observed only passing" class again, caught by running the fail direction first.
- **Under Cilium a policy denial is a drop, not a reset.** Every denied arm printed `TIMEOUT` (exit 1) and none printed `REFUSED`, so the classifier's RESET branch never fired live.
- **The Blocker-F outage is real and immediate.** About 70 s of staging API outage, with curl timing out at 15 s and no 502 or 504 before that. Traefik holds the connection open, so a missing traefik rule appears to users as a hang, not an error.

## Open Items

- **#297's closing comment** is plan 29-27's to post. This evidence file is what it cites.
- **The k8s/base/networkpolicies/README.md wording** ("plan 29-17's ... including the Blocker-F pair") is now true. A pointer to the evidence file could be added when 29-27 closes #297. This plan did not touch the README, which is outside its files_modified.
- **M1 uses a probe-scoped egress rule.** The real Prometheus egress policy is 29-23's. When it ships, re-run this gate. The scratch policy then becomes redundant for M1 but stays harmless.

## Threat Flags

| Flag | File | Description |
|------|------|-------------|
| threat_flag: transient-pod | scripts/check-networkpolicy-enforcement.sh | Each run creates probe pods in jtoye-staging labelled app=frontend, app=prometheus and app=core-java, and one in the traefik namespace. None has a token mount, all are restricted-PSS compliant, and their readinessProbe always fails, so they are never Service endpoints (the Service selectors also need kustomize labels the probes lack). They live for one run and are deleted by name. |
| threat_flag: transient-policy | scripts/check-networkpolicy-enforcement.sh | np-probe-<RUN_ID>-egress opens egress for this run's pods only, to core-java 9090/9091 and keycloak 8080. It is deleted by the trap. |

T-29-62..64 are mitigated as planned:
- **T-29-62:** P0, the controls and VOID-on-failed-control are all live.
- **T-29-63:** the always-failing readinessProbe on every probe.
- **T-29-64:** a single guarded policy edit, restored and read back by content against both the saved copy and the committed render.

## TDD Gate Compliance

| Plan | RED | GREEN | REFACTOR | Status |
|------|-----|-------|----------|--------|
| 29-17 Task 1 | a83a29be test(29-17), RED_EVIDENCE_OK, target classify-REFUSED-is-reset (semantic: the target ran and failed on the reset-as-denial assertion; 18 of 21 others passed) | ab7d6841 feat(29-17), 22 of 22 | none | compliant |

## Self-Check: PASSED

- FOUND: scripts/check-networkpolicy-enforcement.sh, evidence/29-17-enforcement.txt, evidence/29-17-t1-red-classifier.json
- FOUND (ancestors of HEAD): a83a29be, ab7d6841, 4b2ecd7d
- `git rev-list --count 9fb61b70..4b2ecd7d` = 3
- After the closing run, the cluster holds no np-probe-labelled namespace, pod or networkpolicy (plan verify 2, exit 0)
