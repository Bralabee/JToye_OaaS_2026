# Quick Task 260414-inf: Infrastructure Hardening (Audit Phase 4)

**Branch:** `fix/infra-hardening`
**Goal:** Close 3 verified infra findings from the audit.

## Findings

1. `:latest` on 7 images across compose + k8s with `imagePullPolicy: IfNotPresent` → stale deploys.
2. `infra/monitoring/docker-compose.monitoring.yml:64` plaintext creds + `sslmode=disable`.
3. `CLAUDE.md:107,301` says Flyway V28; real highest migration is V30.

## Fix strategy

- k8s: pin ghcr images to project version `2.0.0`.
- compose: env-var references with `:-latest` fallback for local dev; document in `.env.example`.
- monitoring: env-sourced creds with required password and `sslmode=require` default.
- CLAUDE.md: update both references.

## Exit criteria

4 atomic commits, SUMMARY.md written, no push, no PR.
