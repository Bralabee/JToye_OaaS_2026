---
phase: 29
slug: deployable-staging-with-its-own-monitoring
status: draft
nyquist_compliant: false
wave_0_complete: false
created: 2026-08-10
---

# Phase 29 — Validation Strategy

> Per-phase validation contract for feedback sampling during execution.
> Source: `29-RESEARCH.md` § "Validation Architecture" — every DPLY criterion mapped to a
> command with a fail-direction arm; 9 Wave-0 gaps enumerated there.

---

## Test Infrastructure

| Property | Value |
|----------|-------|
| **Framework** | bash gate scripts (`scripts/check-*.sh`, `k8s/scripts/*`) + kustomize build/dry-run + JUnit 5 (Gradle) where code changes |
| **Config file** | `.github/workflows/ci-cd.yaml` (k8s-validate job), `gate-enforcement.conf` |
| **Quick run command** | `kubectl kustomize k8s/staging > /dev/null && k8s/scripts/check-no-plaintext-secrets.sh` |
| **Full suite command** | k8s-validate gate set + `scripts/check-alert-liveness.sh` + `scripts/check-alert-metrics.sh` against the staging target |
| **Estimated runtime** | ~60–300 seconds (cluster-facing checks dominate) |

---

## Sampling Rate

- **After every task commit:** Run the quick command (render + secret guard)
- **After every plan wave:** Run the full gate set relevant to the wave's surface
- **Before `/gsd:verify-work`:** Full suite must be green against the LIVE staging target
- **Max feedback latency:** 300 seconds

---

## Per-Task Verification Map

| Task ID | Plan | Wave | Requirement | Threat Ref | Secure Behavior | Test Type | Automated Command | File Exists | Status |
|---------|------|------|-------------|------------|-----------------|-----------|-------------------|-------------|--------|
| — | — | — | DPLY-01..05 | — | — | — | *populated by planner from RESEARCH § Validation Architecture* | ❌ W0 | ⬜ pending |

*Status: ⬜ pending · ✅ green · ❌ red · ⚠️ flaky*

---

## Wave 0 Requirements

From `29-RESEARCH.md` § Validation Architecture (9 gaps, each with a fail-direction arm) — the
load-bearing ones:

- [ ] `scripts/check-alert-liveness.sh` k8s-capable extension (today: 13 docker refs; L-0/L-3 exit 2 VOID against a cluster)
- [ ] Alert-gate target wiring for port-forward/CI cluster access (`check-alert-metrics.sh` is already port-forward-clean — verify, don't assume)
- [ ] Staging seed path for DPLY-01's "real seeded rows" (none exists in the repo today)
- [ ] Runtime-parity analogue for k8s (`check-runtime-freshness.sh` can only VOID against a cluster — digest comparison after rollout, stated plainly)
- [ ] Denied-connection capture harness for DPLY-05 (probe pod + timeout, with a positive-control arm proving the probe CAN connect when allowed)

---

## Manual-Only Verifications

| Behavior | Requirement | Why Manual | Test Instructions |
|----------|-------------|------------|-------------------|
| Vendor login through ingress lands on rendered dashboard | DPLY-01 | Real browser + Keycloak redirect over public DNS/TLS | Phase 26-08 L7 template: verbatim URL with client_id + encoded redirect_uri → rendered dashboard |
| Alert email reaches a real inbox | DPLY-03/#112 | Terminal receiver is a human mailbox (Gmail) | Fire a test alert; confirm receipt; record headers |
| Owner disposition of the live snackpass-* estate in jtoye-rg | D-03 budget | Cost decision on a running estate is the owner's | Surface before provisioning; record decision |

---

## Validation Sign-Off

- [ ] All tasks have `<automated>` verify or Wave 0 dependencies
- [ ] Sampling continuity: no 3 consecutive tasks without automated verify
- [ ] Wave 0 covers all MISSING references
- [ ] No watch-mode flags
- [ ] Feedback latency < 300s
- [ ] `nyquist_compliant: true` set in frontmatter

**Approval:** pending
