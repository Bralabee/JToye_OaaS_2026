# Phase 1: API Versioning — Backend - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-04-07
**Phase:** 01-api-versioning-backend
**Areas discussed:** Versioning strategy, Exemption policy, Test migration, Rollout approach

---

## Versioning Strategy

| Option | Description | Selected |
|--------|-------------|----------|
| WebMvcConfigurer path prefix | Single config change applies /api/v1/ to all @RestController classes automatically. Research recommends this. | ✓ |
| Rewrite each controller | Change every @RequestMapping to include /api/v1/. More explicit but 12+ files to touch. | |
| You decide | Claude picks the best approach based on codebase patterns | |

**User's choice:** WebMvcConfigurer path prefix
**Notes:** Research-recommended approach. Avoids touching 12+ controller files individually.

---

## Exemption Policy

| Option | Description | Selected |
|--------|-------------|----------|
| /public/payments (Stripe) | Stripe webhook — can't change atomically with deployment | ✓ |
| /public/** (storefront) | PublicStorefrontController — unauthenticated public endpoints | ✓ |
| Infrastructure paths | /health, /actuator/**, /swagger-ui/**, /v3/api-docs/** | ✓ |
| /dev/tenants | DevTenantController — dev-only, not a production API | ✓ |

**User's choice:** All exemptions selected
**Notes:** Comprehensive exemption list covering all non-API paths.

---

## Test Migration

| Option | Description | Selected |
|--------|-------------|----------|
| Update all test paths | Sweep all MockMvc/test paths to /api/v1/. Clean break, no backward compat. | ✓ |
| Add redirect layer | Old paths 301 redirect to /api/v1/ paths. Tests work either way, but adds complexity. | |
| You decide | Claude picks based on test structure | |

**User's choice:** Update all test paths
**Notes:** Clean break preferred — no backward compatibility redirects.

---

## Rollout Approach

| Option | Description | Selected |
|--------|-------------|----------|
| Big-bang (Recommended) | WebMvcConfigurer applies to all controllers at once. One PR, one switch. | ✓ |
| Incremental | Controller-by-controller migration. Safer but longer. | |

**User's choice:** Big-bang
**Notes:** Aligns naturally with WebMvcConfigurer approach which doesn't support incremental migration.

---

## Claude's Discretion

- WebMvcConfigurer predicate implementation details
- Custom annotation vs package-based filtering for exempt controllers
- SecurityConfig pattern update specifics

## Deferred Ideas

None — discussion stayed within phase scope
