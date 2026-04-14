# Quick Task 260414-inf — SUMMARY

**Status:** ✅ Complete
**Branch:** `fix/infra-hardening`
**Commits:** 4

| # | SHA | Subject |
|---|-----|---------|
| 1 | 0a33864 | fix(k8s): pin ghcr images to 2.0.0 instead of :latest |
| 2 | 228bf31 | fix(compose): parameterize minio/ollama image tags |
| 3 | 8629cb1 | fix(monitoring): remove hardcoded postgres-exporter creds and require SSL |
| 4 | 81d759f | docs(project): sync Flyway schema version V28 -> V30 in CLAUDE.md |

## Changes

- **k8s base manifests** (`core-java`, `edge-go`, `frontend` deployments) pinned to `ghcr.io/jtoye/*:2.0.0`. `imagePullPolicy: IfNotPresent` is now safe because the tag is versioned.
- **docker-compose.full-stack.yml**: all 4 `:latest` tags (minio, minio-init, ollama, ollama-init) replaced with `${MINIO_IMAGE_TAG:-latest}` / `${MINIO_MC_IMAGE_TAG:-latest}` / `${OLLAMA_IMAGE_TAG:-latest}` env var references. `.env.example` documents and prompts operators to pin for production.
- **postgres-exporter**: replaced hardcoded `postgresql://jtoye:secret@...?sslmode=disable` with env-sourced `${POSTGRES_EXPORTER_USER}:${POSTGRES_EXPORTER_PASSWORD:?must be set}@...?sslmode=${POSTGRES_EXPORTER_SSLMODE:-require}`. Password is required (fail-fast `${VAR:?}`); SSL defaults to `require`.
- **CLAUDE.md**: Flyway schema version corrected from `V28` → `V30` (two references, lines 107 and 301). When Phase 2 merges (adds V31 + V32), another bump to V32 will be needed.
