---
quick_id: 260706-bsk
slug: fix-storefront-public-endpoint-409-rls-u
date: 2026-07-06
branch: feature/fix-storefront-rls-409
---

# Quick Task: Fix storefront public-endpoint 409 (RLS uuid-cast crash)

## Problem

The public storefront endpoints `/public/shops/{slug}/promotions`,
`/announcements`, and `/config` return **HTTP 409** ("Duplicate Entry / Data
integrity constraint violated"). The underlying Postgres error is `22P02`
— `invalid input syntax for type uuid: ""`.

## Root cause

`V33__fix_rls_policies.sql` defined three SELECT policies
(`shop_promotions_read`, `shop_announcements_read`, `reviews_tenant_read`)
using the raw cast `current_setting('app.current_tenant_id', true)::uuid`.
Postgres evaluates that constant sub-expression at query init — even on empty
tables — so an anonymous storefront request (no tenant context → GUC resets to
`''`) hits `''::uuid` and throws `22P02`. Spring maps the SQLException to
`DataIntegrityViolationException` → HTTP 409.

Every other table (`products`, `shops`) uses the safe helper
`current_tenant_id()`, which returns NULL for empty/`default`/unset instead of
crashing. `reviews` only avoided the crash because `ReviewService` sets tenant
context first.

## Fix

`V39__fix_storefront_rls_uuid_cast.sql` — drop and recreate the three policies
identically to V33 but with `current_tenant_id()` in place of the raw cast.
Semantics preserved: tenant rows match for authenticated tenants; published
shops stay publicly readable via the `EXISTS(published shop)` branch. No data
change.

## Tasks

1. [x] Write `core-java/.../db/migration/V39__fix_storefront_rls_uuid_cast.sql`
2. [x] Rebuild `core-java` image so Flyway applies V39 on boot
3. [x] Verify: 3 endpoints return 200 (were 409); `flyway_schema_history` shows V39
4. [x] Browser re-verify: storefront renders with zero 409s in console

## Verification

- `docker compose -f docker-compose.full-stack.yml -f docker-compose.frontend-3100.yml up -d --build core-java`
- `curl` each endpoint for `jollof-express-brixton-900b57a8` → expect 200
- Playwright storefront check → no 409 responses (401 on `/api/customer-auth/session` is expected for anonymous visitor)

## Out of scope (separate operational step)

Soft-disabling the 7 leftover E2E test products (`available=false`) — a dev-DB
data operation, not a schema/repo change.
