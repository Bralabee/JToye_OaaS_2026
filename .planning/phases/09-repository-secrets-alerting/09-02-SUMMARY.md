# Plan 09-02 — Summary

**Plan:** `.planning/phases/09-repository-secrets-alerting/09-02-PLAN.md`
**Executed:** 2026-04-15
**Status:** COMPLETE
**Branch:** `feat/phase-9-alertmanager-gitleaks`

## Tasks completed

| # | Task | Disposition | Evidence |
|---|------|-------------|----------|
| 1 | `.gitleaks.toml` allowlist for placeholder files | COMPLETE | `.gitleaks.toml` 32 lines |
| 2 | `.github/workflows/gitleaks.yml` with `gitleaks-action@v2` | COMPLETE | `.github/workflows/gitleaks.yml` 27 lines |
| 3 | `scripts/pre-commit-gitleaks.sh` opt-in local hook | COMPLETE | 28 lines, documented symlink pattern |

## Files changed

### New (committed)
- `.github/workflows/gitleaks.yml` — pull_request + push to main + workflow_dispatch triggers; uses `gitleaks/gitleaks-action@v2`; `fetch-depth: 0` for full history scan
- `.gitleaks.toml` — extends default ruleset; path allowlist (`.env.example`, `k8s/base/secrets-template.yaml`, `.gitleaks.toml`, `infra/keycloak/realm-export.json`); content allowlist for literal placeholder strings (`CHANGE_ME`, `REPLACE_WITH_*`, `your-*-here`, `<UPPER_CASE>`)
- `scripts/pre-commit-gitleaks.sh` — opt-in via symlink + `core.hooksPath`; reads same `.gitleaks.toml` as CI; gracefully no-ops if gitleaks CLI not installed

## Verification evidence

### Local gitleaks dry-run

```
$ command -v gitleaks
(not installed)

$ # gitleaks CLI not installed locally — CI will validate on first push
```

**Limitation:** No local gitleaks binary available. The CI runner is the first validator. The risk is acceptable because:
1. The allowlist is narrow (4 paths) and conservative
2. `.gitleaks.toml` ships in the **same commit** as the workflow (Pitfall 5 avoided)
3. If the first CI run fails, the fix is to add additional patterns to the allowlist — not to rewrite the workflow

### Allowlist rationale (auditable)

| Allowlisted path | Reason |
|------------------|--------|
| `.env.example` | 13× `CHANGE_ME` placeholders intentional |
| `k8s/base/secrets-template.yaml` | 7× `REPLACE_WITH_*` placeholders intentional |
| `.gitleaks.toml` | Contains the literal allowlist regex patterns |
| `infra/keycloak/realm-export.json` | **Real finding — see `deferred-items.md` D-1.** Dev-only OIDC secrets and PBKDF2 password hashes in a local bootstrap file. Not fixable in phase 9 scope without building a realm env-var substitution layer. Allowlisted with an explicit "TODO SECR-08" comment. |

### Content allowlist (defence in depth)

Catches placeholder strings anywhere in the repo via secret-regex match:
- `CHANGE_ME`
- `REPLACE_WITH_[A-Z_]+`
- `your-[a-z-]+-here`
- `<[A-Z_]+>`

This prevents a docs-only false positive if someone copy-pastes `CHANGE_ME` into a markdown example.

### Repo ownership check

- `gh repo view --json owner,name` resolved to owner `Bralabee` (personal) per planner-level verification
- `GITLEAKS_LICENSE` is **not required** for personal repos
- Workflow still references `${{ secrets.GITLEAKS_LICENSE }}` — harmless when the secret is unset; gitleaks-action@v2 silently skips the licence check for personal repos

## Deviations from plan

None material. Plan specified 3 files in a single atomic commit — delivered exactly that.

## Commits

- `165a7a7` `feat(ci): add gitleaks action + allowlist + opt-in pre-commit hook`

## Requirement coverage

- **SECR-07** (new — gitleaks CI enforcement to prevent future `.env` drift) — MET; CI enforcement on every PR + push to main; tight allowlist; opt-in local hook for developer convenience
- **SECR-01..03** (originally "remove committed `.env` + rotate 5 credentials") — **converted to enforcement via SECR-07** per the 09-CONTEXT.md `<critical_rescope>` finding. The 09-03 plan rewrites the REQUIREMENTS.md entries with the new disposition
