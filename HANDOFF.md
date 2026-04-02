# Handoff: J'Toye OaaS — Post Public Storefront

**Generated**: 2026-04-02
**Branch**: `feat/public-storefront` (PR #19 open)
**Tag**: `v1.3.0`
**Status**: PR #19 ready for merge

## Goal

Multi-tenant UK retail SaaS platform. This session delivered the full public customer storefront: shop discovery, product browsing, cart, guest checkout, order tracking, email notifications, and customer auth.

## Completed (This Session)

- [x] Public storefront — Deliveroo-style shop discovery, product catalog with categories/dietary badges
- [x] Shop + product enrichment — V16 migration (13 shop + 8 product new columns, public RLS)
- [x] Cart — React context + localStorage, add-to-cart UI, floating cart bar, cart page
- [x] Guest checkout — checkout form, server-side price recalculation, order confirmation
- [x] Order tracking — V17 RLS for secure lookup, live 5-step progress tracker, 15s auto-refresh
- [x] Customer order history — V18 RLS for email-based history, /shop/orders page, automatic tracking
- [x] Email notifications — all 6 state transitions, tracking links, Mailhog for dev
- [x] Customer auth — Keycloak storefront-client, self-service registration, PKCE OAuth, Sign in/out
- [x] Vendor dashboard — updated shops + products pages with all new storefront fields
- [x] Housekeeping — security fixes, env parity, docs freshness, artifact cleanup

## Not Yet Done

- [ ] Payments (Stripe) — FinancialTransaction is record-keeping only
- [ ] Delivery management — entirely new domain
- [ ] Customer order history for logged-in users (Keycloak session → auto-populate)
- [ ] Keycloak branding — login page uses default theme
- [ ] Token refresh in customer-auth.ts (MVP — expired sessions re-login)
- [ ] Edge-go public routes — storefront currently calls core-java directly
- [ ] Unit tests for PublicStorefrontService and PublicStorefrontController
- [ ] Tailwind CSS 3→4 migration
- [ ] Jest 29→30 migration

## Environment

- **Java**: `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Docker**: 8 containers (postgres, keycloak, redis, rabbitmq, core-java, edge-go, frontend, mailhog)
- **Tests**: 131 Java unit, 43 Jest, 20 Go tests (194 total, all passing)
- **Keycloak**: `tenant-a-user` / `password123`, client: `test-client` (vendor), `storefront-client` (customer)
- **Node**: v20.19.3, React 19, Next.js 16.2.2, 0 npm vulnerabilities
- **Mailhog**: http://localhost:8025 (email testing)
- **Storefront**: http://localhost:3000/shop

## Resume Instructions

1. Merge PR #19: `gh pr merge 19 --squash --delete-branch`
2. `git checkout main && git pull`
3. Verify: `./gradlew test` → BUILD SUCCESSFUL (131 tests)
4. Verify: `cd edge-go && go test ./...` → 4/4 ok
5. Verify: `cd frontend && npx jest --watchAll=false` → 43/43
6. Docker: `docker compose -f docker-compose.full-stack.yml up -d`
7. Storefront: http://localhost:3000/shop → browse, add to cart, checkout, track order
