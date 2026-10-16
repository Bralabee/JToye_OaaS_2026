# Phase 39 Plan 01: Horizon Gate Re-Check & H-5 Site Refresh Summary

**Completed:** 2026-10-16
**Plan:** `39-01-PLAN.md` (Wave 1, Track `39-A` Part 1 — zero compose rebuild)

## What Was Done

1. **Disarmed the `2026-10-26` `rabbitmq-k8s` Horizon Gate:**
   - Verified `rg -uu -n 'image:.*rabbitmq' k8s/` returns zero matches (`k8s/local/configmap-patch.yaml:136` routes the local minikube overlay to the host Compose broker via `host.minikube.internal`).
   - Verified `https://www.rabbitmq.com/release-information` (`2026-10-16`): `4.3.6` (`2026-09-16`) is the latest release (no `4.4` series published yet), and the vendor extended **4.3 End of Community Support to `31 Jan 2027` (`2027-01-31`)**.
   - Refreshed `rabbitmq-k8s.manual_review` in [`infra/dependency-horizons.yaml`](../../../infra/dependency-horizons.yaml) (`last_checked: "2026-10-16"`, `expires: "2027-01-16"`) and recorded the re-check in [`docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md`](../../../docs/architecture/decisions/ADR-0002-managed-vs-manifest-datastores.md).
2. **Refreshed All `manual_review` Rows (`H-6`) With `2026-10-16` Upstream Measurements:**
   - `azurite` (`v3.37.0`), `ollama`, `mailhog`, `alertmanager` (`v0.34.1`), `redis-exporter` (`v1.94.0`), `postgres-exporter` (`v0.20.1`).
   - All 5 `exemption` blocks (`DEFERRED-27` x4 and `#724` x1) were left untouched per SC-1.
3. **Cleared All 6 `H-5` Line-Number Drift Advisories (`pin-not-at-site=6` → `0`):**
   - Executed `bash scripts/check-dependency-horizons.sh --refresh` to align `azurite`, `ollama` (x2), `mailhog`, and `go-ci-setup` (x2) with their current line numbers in `docker-compose.full-stack.yml` and `.github/workflows/ci-cd.yaml`.
4. **Verified Break Arm & Gate Parity:**
   - Break arm (`rabbitmq-k8s.manual_review.expires: "2026-10-01"`): `check-dependency-horizons.sh` exited `rc=1` (`FAIL: H-6 rabbitmq-k8s: manual_review LAPSED on 2026-10-01`).
   - Restored state: `check-dependency-horizons.sh` exited `rc=0` (`pin-not-at-site=0`, `violations=0`), `check-doc-citations.sh` exited `rc=0` (46/46 verified), and `check-claims.sh` exited `rc=0` (50/50 matched).
