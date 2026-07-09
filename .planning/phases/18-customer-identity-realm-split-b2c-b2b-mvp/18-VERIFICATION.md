---
phase: 18-customer-identity-realm-split-b2c-b2b-mvp
verified: 2026-07-09T23:10:00Z
status: passed
score: 10/10 must-haves verified
overrides_applied: 0
---

# Phase 18: Customer Identity Realm Split (B2C/B2B) — MVP Verification Report

**Phase Goal:** As a storefront customer, I want to register and log in against a dedicated customer identity realm separate from staff/vendor accounts, so that customer and staff logins are isolated and the admin dashboard no longer offers customer self-registration.
**Verified:** 2026-07-09T23:10:00Z
**Status:** passed
**Re-verification:** No — initial verification

## MVP Mode — User Flow Coverage

Phase mode: `mvp`. User story: "As a storefront customer, I want to register and log in against a dedicated customer identity realm separate from staff/vendor accounts, so that customer and staff logins are isolated and the admin dashboard no longer offers customer self-registration."

| Step | Expected | Evidence | Status |
|---|---|---|---|
| Customer clicks "Sign in" on `/shop/orders` | Redirect targets Keycloak `jtoye-customers` realm, NOT `jtoye-dev` | Live E2E Scenario A: `Sign-in flow targets the jtoye-customers realm (saw: jtoye-customers)` PASS; `does NOT target the jtoye-dev staff realm` PASS | VERIFIED |
| Customer self-registers (email/password) | Account created in `jtoye-customers`, browser lands back on storefront | Live E2E Scenario A: registration form (`#firstName/#email/#password/#password-confirm`) submitted, `lands back on the storefront under /shop (/shop/orders)` PASS | VERIFIED |
| Session reflects authenticated customer | `GET /api/customer-auth/session` → `{authenticated:true, profile.email}` | Live E2E Scenario A: `GET .../session returns HTTP 200` PASS, `session authenticated === true` PASS, `session profile.email is a non-empty string` PASS | VERIFIED |
| Admin dashboard login unaffected | `/auth/signin` → `jtoye-dev` `core-api` → `/dashboard`, no Register link | Live E2E Scenario B: `admin sign-in targets the jtoye-dev staff realm` PASS, `staff login page shows NO Register/New-user link (found 0)` PASS, `admin-user still signs in and lands on /dashboard` PASS | VERIFIED |
| Customer/staff pools isolated | Test customer absent from `jtoye-dev`; `admin-user` absent from `jtoye-customers`; `storefront-client` absent from `jtoye-dev` | Live E2E Scenario C (admin-API): all three checks PASS. **Caveat:** a stale customer account from an earlier pre-hardening test run still exists in `jtoye-dev` — see Operational Finding below (non-blocking) | VERIFIED (with noted residue) |

## Goal Achievement

### Observable Truths

| # | Truth | Status | Evidence |
|---|---|---|---|
| 1 | `jtoye-customers` realm created reproducibly from a committed, gitignored-rendered template, survives `--import-realm` | ✓ VERIFIED | `infra/keycloak/realm-export-customers.template.json` committed (390 lines), contains `"realm" : "jtoye-customers"`, `"clientId" : "storefront-client"`, `S256`, `"registrationAllowed" : true`, env placeholders `${CUSTOMER_STOREFRONT_REDIRECT_URIS}` / `${CUSTOMER_STOREFRONT_WEB_ORIGINS}` / `${CUSTOMER_VERIFY_EMAIL}` (no hardcoded literals). Render sidecar (`docker-compose.full-stack.yml` L37-63) renders it via `envsubst` with an explicit var list (i18n placeholders survive). `git check-ignore infra/keycloak/realm-export-customers.json` confirms the rendered output is git-ignored. Live: `curl http://localhost:8085/realms/jtoye-customers/.well-known/openid-configuration` → 200. Rendered file on disk shows real redirect URIs (`http://localhost:3000/*`, `3001/*`, `3100/*`), proving `envsubst` executed correctly, not a placeholder leak. |
| 2 | `storefront-client` public, PKCE S256, standard flow, env-driven redirect URIs incl :3100, `customer` default role, self-registration ON | ✓ VERIFIED | Template: `publicClient:true`, `standardFlowEnabled:true`, `implicitFlowEnabled:false`, `directAccessGrantsEnabled:false`, `pkce.code.challenge.method:S256`. `customer` role defined and folded into `default-roles-jtoye-customers` composite. Live admin-API: `clients?clientId=storefront-client` on `jtoye-customers` → length 1; realm `registrationAllowed:true`. |
| 3 | Staff realm `jtoye-dev`: self-registration disabled, `storefront-client` removed, no Register link on admin login, `core-api`/`edge-api` + seed users unchanged | ✓ VERIFIED | Live admin-API: `jtoye-dev` `registrationAllowed:false`, `registrationEmailAsUsername:false`; `clients?clientId=storefront-client` → length 0; `clients?clientId=core-api` → length 1; `clients?clientId=edge-api` → length 1; users list includes `admin-user`, `tenant-a-user`, `tenant-b-user` (unchanged). Live E2E Scenario B confirms 0 Register-link anchors and `admin-user` login still lands on `/dashboard`. Committed `configure-keycloak.sh`: `storefront-client` grep count = 0, no `registrationAllowed...true`, `tenant-a-user`/`test-client` retained, `bash -n` clean. |
| 4 | Frontend `customer-auth.ts` (+ logout-url route) targets the customer realm via env; admin `frontend/auth.ts` stays on `jtoye-dev` | ✓ VERIFIED | `frontend/lib/customer-auth.ts` L22-23: `KC_BASE` prefers `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`. `frontend/app/api/customer-auth/logout-url/route.ts` L16-17: same pattern. `frontend/Dockerfile` L35-36: `ARG`/`ENV NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL`. `git diff --name-only $(git merge-base HEAD main)..HEAD -- frontend/auth.ts` → empty (untouched); `frontend/auth.ts` still reads `KEYCLOAK_ISSUER`/`KEYCLOAK_ISSUER_INTERNAL`/`KEYCLOAK_CLIENT_ID` (jtoye-dev/core-api path, unchanged). |
| 5 | Backend (core-java, edge-go) unchanged — no JWT-validation edits | ✓ VERIFIED | `git diff --name-only $(git merge-base HEAD main)..HEAD -- core-java edge-go` → 0 files. `SecurityConfig.java`/`AudienceValidator.java`/edge-go JWT files absent from any diff. |
| 6 | E2E on :3100 (a) customer self-register+login → `/shop`, (b) admin login works, no Register link, (c) pools disjoint | ✓ VERIFIED | Full script run live by verifier (not trusted from SUMMARY): `NODE_PATH=frontend/node_modules PLAYWRIGHT_BASE_URL=http://localhost:3100 node --env-file=.env frontend/e2e/customer-realm-split.verify.mjs` → exit 0, all 12 assertions PASS (Scenarios A+B+C). See raw output below. |

**Score:** 6/6 roadmap truths verified (10/10 counting PLAN-level must_have truths across 18-01/18-02).

### Required Artifacts

| Artifact | Expected | Status | Details |
|---|---|---|---|
| `infra/keycloak/realm-export-customers.template.json` | Committed jtoye-customers realm template | ✓ VERIFIED | Exists, 390 lines, all required fields present, env placeholders not hardcoded literals |
| `docker-compose.full-stack.yml` | Second realm render + import mount + frontend build arg/env | ✓ VERIFIED | `grep -c realm-export-customers` = 3 (render output line, echo, import mount); frontend service has `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` build arg + 3 runtime envs |
| `frontend/lib/customer-auth.ts` | `KC_BASE` repointed via `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` | ✓ VERIFIED | L22-23 confirmed |
| `frontend/e2e/customer-realm-split.verify.mjs` | Node Playwright verification, all 3 scenarios | ✓ VERIFIED | 301 lines, real browser automation (chromium, real form fills/clicks), real admin-API calls, no mocking; ran live by verifier, exit 0 |
| `infra/keycloak/configure-keycloak.sh` | jtoye-dev customer-signup branches removed | ✓ VERIFIED | `storefront-client` count 0, no `registrationAllowed...true`, `tenant-a-user`/`test-client` retained, `bash -n` clean |

### Key Link Verification

| From | To | Via | Status | Details |
|---|---|---|---|---|
| `frontend/lib/customer-auth.ts` | jtoye-customers token/auth endpoints | `NEXT_PUBLIC_CUSTOMER_KEYCLOAK_URL` | ✓ WIRED | Grep + live E2E proves the browser actually redirects to `/realms/jtoye-customers/...` |
| `keycloak-realm-render` sidecar | `keycloak --import-realm` | rendered `realm-export-customers.json` mount | ✓ WIRED | Compose mount confirmed; live realm serves 200; rendered file has real (non-placeholder) values |
| admin `core-api` login page (jtoye-dev) | `registrationAllowed:false` | no Register anchor rendered | ✓ WIRED | Live admin-API confirms flag false; live browser scenario confirms 0 anchors |
| jtoye-dev user pool | jtoye-customers user pool | admin-API per-realm lookup disjoint | ✓ WIRED (with noted historical residue — see below) | Live admin-API confirms current-run disjointness; one stale pre-hardening test account persists in jtoye-dev (non-blocking, see Operational Finding) |

### Behavioral Spot-Checks / Live Stack Checks (run directly by verifier, not sourced from SUMMARY)

| Behavior | Command | Result | Status |
|---|---|---|---|
| jtoye-dev well-known | `curl .../realms/jtoye-dev/.well-known/openid-configuration` | 200 | ✓ PASS |
| jtoye-customers well-known | `curl .../realms/jtoye-customers/.well-known/openid-configuration` | 200 | ✓ PASS |
| jtoye-dev `registrationAllowed` | admin-API GET realm | `false` | ✓ PASS |
| jtoye-dev `storefront-client` count | admin-API GET clients?clientId=storefront-client | `0` | ✓ PASS |
| jtoye-dev `core-api` count | admin-API GET clients?clientId=core-api | `1` | ✓ PASS |
| jtoye-dev `edge-api` count | admin-API GET clients?clientId=edge-api | `1` | ✓ PASS |
| jtoye-customers `storefront-client` count | admin-API GET clients?clientId=storefront-client | `1` | ✓ PASS |
| jtoye-customers `registrationAllowed` | admin-API GET realm | `true` | ✓ PASS |
| jtoye-customers `admin-user` lookup | admin-API GET users?username=admin-user | `0` | ✓ PASS |
| Full 3-scenario Playwright verify | `node --env-file=.env frontend/e2e/customer-realm-split.verify.mjs` | exit 0, 12/12 assertions PASS | ✓ PASS |
| `frontend` typecheck/build | `cd frontend && npm run build` | exit 0, all routes generated | ✓ PASS |
| Backend diff since merge-base(main) | `git diff --name-only ... -- core-java edge-go` | empty (0 files) | ✓ PASS |
| `frontend/auth.ts` diff since merge-base(main) | `git diff --name-only ... -- frontend/auth.ts` | empty (untouched) | ✓ PASS |
| docs-freshness gate | `bash scripts/docs-freshness.sh` | exit 0, `771` invocations match | ✓ PASS |
| `docs/metrics.json` counts | `jq .total_logical_invocations, .playwright_specs` | `771`, `5` (unchanged) | ✓ PASS |
| Secret-leak scan on verify script | grep for password/secret/KC_SEED in console.log | `NO-LEAK` | ✓ PASS |
| Debt-marker scan (TBD/FIXME/XXX/TODO/HACK) on 9 phase-modified files | grep | no matches | ✓ PASS |

**Raw live E2E output (captured by verifier, this run):**
```
Scenario A — customer self-register + login on jtoye-customers
  PASS  Sign-in flow targets the jtoye-customers realm (saw: jtoye-customers)
  PASS  Sign-in flow does NOT target the jtoye-dev staff realm
  PASS  lands back on the storefront under /shop (/shop/orders)
  PASS  GET /api/customer-auth/session returns HTTP 200
  PASS  session authenticated === true
  PASS  session profile.email is a non-empty string

Scenario B — admin login on jtoye-dev, NO Register link
  PASS  admin sign-in targets the jtoye-dev staff realm (saw: jtoye-dev)
  PASS  staff login page shows NO Register/New-user link (found 0)
  PASS  admin-user still signs in and lands on /dashboard

Scenario C — separate identity pools (admin-API)
  PASS  obtained a Keycloak master admin token
  PASS  test customer is ABSENT from jtoye-dev (found 0)
  PASS  admin-user is ABSENT from jtoye-customers (found 0)
  PASS  storefront-client is REMOVED from jtoye-dev (found 0)

RESULT: PASS (Scenarios A + B + C green)
EXIT CODE: 0
```

### Requirements Coverage

| Requirement | Source Plan | Description | Status | Evidence |
|---|---|---|---|---|
| CID-01 | 18-01, 18-02 | Customer Identity Separation (B2C/B2B) | ✓ SATISFIED | All 6 roadmap truths verified with live evidence; `REQUIREMENTS.md` L137 marks it Complete, and evidence confirms the claim independently of that self-report. |

No orphaned requirements: `grep "Phase 18" .planning/REQUIREMENTS.md` returns only the CID-01 row, and both plans declare `requirements: [CID-01]` in frontmatter.

### Anti-Patterns Found

None blocking. Scanned all 9 phase-modified files (`realm-export-customers.template.json`, `docker-compose.full-stack.yml`, `frontend/Dockerfile`, `frontend/lib/customer-auth.ts`, `frontend/app/api/customer-auth/logout-url/route.ts`, `.env.example`, `.gitignore`, `frontend/e2e/customer-realm-split.verify.mjs`, `infra/keycloak/configure-keycloak.sh`) for `TBD|FIXME|XXX|TODO|HACK|PLACEHOLDER` — zero matches. No secret literals logged in the verify script.

### Operational Finding (non-blocking, flagged for awareness)

**A stale customer account persists in the live `jtoye-dev` (staff) realm's user pool.** Live admin-API lookup (`GET /admin/realms/jtoye-dev/users`) returns 4 users: `admin-user`, `tenant-a-user`, `tenant-b-user`, and `customer-1783625174368@example.com` (firstName "Test", lastName "Customer"). This account was almost certainly created during Plan 18-02 Task 1's intentional RED-step run (executed *before* `jtoye-dev` was hardened, while self-registration and `storefront-client` were still live on that realm) and was never cleaned up afterward.

This does **not** invalidate the phase goal or any tested truth:
- The automated Scenario C check only verifies the *current* E2E run's test customer (tracked via `.last-customer-email`), which is correctly absent from `jtoye-dev` on every run — that check is not a false positive, it is testing exactly what it claims to test.
- Going forward, no new customer account can land in `jtoye-dev` — self-registration is disabled and `storefront-client` no longer exists there.
- The residual account carries no elevated role (realm role `customer` only, no `admin`/`user` roles) and cannot authenticate against `core-api` (no client to do so, and admin dashboard login requires a provisioned staff account with the right role/flow) — it is inert.

It is, however, literal counter-evidence to a blanket claim of "pools are disjoint" as an instantaneous fact, and is worth a one-line cleanup (`DELETE /admin/realms/jtoye-dev/users/653b8f7c-9213-43ee-b6a6-94617b1eabcb` or equivalent Admin Console action) so the live realm fully matches the committed reproducible source of truth. Recommend addressing in a follow-up quick task; does not block Phase 18 closure since it is pre-existing test residue, not a defect in the delivered mechanism.

### Human Verification Required

None. All must-haves were verifiable via live admin-API calls, a live full-browser E2E run (executed directly by the verifier, not sourced from SUMMARY), `npm run build`, and git diff. No visual-only or subjective checks remain outstanding for this phase's success criteria.

### Gaps Summary

No blocking gaps. All 5 ROADMAP success criteria and all `must_haves` truths/artifacts/key_links across both plans (18-01, 18-02) verified against the live running stack, not merely against the SUMMARY narrative. One non-blocking operational finding noted above (stale test-customer account in `jtoye-dev`) — recommend a quick cleanup task but it does not block phase completion or roadmap progression.

---

*Verified: 2026-07-09T23:10:00Z*
*Verifier: Claude (gsd-verifier)*
