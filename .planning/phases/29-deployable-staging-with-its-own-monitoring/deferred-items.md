# Phase 29 — Deferred Items

Out-of-scope discoveries, logged by the plan that found them (executor scope boundary:
only issues caused by the current task are fixed in it).

## Deferred Items

- check-doc-citations.sh C-3 fails on k8s/LOCAL.md:606 (found during 29-03, 2026-10-18)
  status: open
  **What:** `bash scripts/check-doc-citations.sh` (conda env jtoye-ops) exits 1 with
  `FAIL: C-3 k8s/LOCAL.md:606 cites k8s/base/configmap.yaml:177, but that line says nothing
  the claim names`. Without the env it exits 2 (VOID, PyYAML not importable). Plan 29-03
  touches neither file. Measured: the gate exits 0 at 7c687487 (the base before 29-02),
  where line 177 is `stomp.broker.mode: "relay"`; on this branch that key is at line 199,
  moved by 29-02's k8s/base/configmap.yaml edit. The gate is CI-wired
  (`.github/workflows/ci-cd.yaml` ops-contracts), so the branch's CI is red on it.
  **Next:** re-point the citation in k8s/LOCAL.md:606 to `k8s/base/configmap.yaml:199`
  (a one-line doc fix) in the next commit that may touch that doc, then re-run the gate.
