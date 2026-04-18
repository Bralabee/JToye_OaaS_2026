# Phase 9 — Deferred Items

Items discovered during phase 9 execution that are real findings but belong in a different phase.

## D-1: `infra/keycloak/realm-export.json` contains dev-only hardcoded secrets

**Discovered:** 2026-04-15 during 09-02 gitleaks allowlist authoring
**File:** `infra/keycloak/realm-export.json`
**Finding:** Realm export used for local docker-compose Keycloak bootstrap contains:
- Hashed (PBKDF2) user password hashes for `demo-admin`, `demo-customer`, `demo-vendor` — `secretData` JSON blobs
- Plaintext OIDC client secrets for `core-api` and `frontend` clients (`clientAuthenticatorType: client-secret`)

**Impact:** These credentials only work against the local dev Keycloak. Any environment that imports this realm file in staging or prod would reuse dev secrets — clear incident. Today there's no evidence staging/prod uses this file (k8s uses separate realm provisioning), but nothing in the codebase prevents it.

**Why deferred (not fixed in phase 9):**
Phase 9 scope is Alertmanager + gitleaks enforcement. Rewriting the realm export to pull secrets from env vars requires:
1. A Keycloak realm bootstrap mechanism that supports `${VAR}` substitution (Keycloak 24 supports this via `kc.sh --spi-...` or by preprocessing the JSON at container start)
2. A new set of env vars in `.env.example`
3. Smoke tests to confirm local dev still boots cleanly
4. A strategy for rotating the dev-only secrets (they're in git history)

That's a standalone phase-worth of work.

**Workaround for now:** Added `infra/keycloak/realm-export.json` to `.gitleaks.toml` allowlist with a comment pointing at this file. The allowlist is tight — only this specific path, not `*.json` broadly.

**Proposed follow-up:** Add a requirement in milestone 4 or as a phase 9.1 hotfix:
- `SECR-08: Keycloak realm bootstrap via env-var substitution (remove committed dev client secrets)`

---

## (Add more as phase 9 progresses)
