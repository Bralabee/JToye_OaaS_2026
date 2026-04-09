# Handoff: J'Toye OaaS Milestone 2 — Tier 3 Enhancements Complete

**Generated**: 2026-04-09
**Branch**: `feat/tier3-enhancements`
**PR**: #27 (https://github.com/Bralabee/JToye_OaaS_2026/pull/27)
**Status**: Ready for Review — all 8 phases complete, PR open

## Goal

Complete Milestone 2 (Tier 3 Enhancements) for J'Toye OaaS: API versioning, vendor marketing dashboard, real-time kitchen display with WebSocket, and test coverage closure.

## Completed

- [x] **Phase 1-2: API Versioning** — `/api/v1/` prefix via `WebMvcConfigurer.configurePathMatch()` across Spring Boot (7 controller packages), Go edge gateway, and Next.js frontend. Webhooks/public/health exempt.
- [x] **Phase 3: Vendor Marketing Backend** — V29 Flyway migration (discount types + announcements table + RLS fix), `PromotionController` + `AnnouncementController` CRUD with scheduling, public storefront endpoints
- [x] **Phase 4: Vendor Dashboard UI** — `/dashboard/marketing` page with Promotions + Announcements tabs, status badges (active/upcoming/expired/disabled), client-side filtering, native datetime-local scheduling
- [x] **Phase 5: KDS Security & WebSocket** — `spring-boot-starter-websocket`, `WebSocketConfig` STOMP at `/ws`, `JwtHandshakeInterceptor` (query param JWT), `TenantChannelInterceptor` (`ExecutorChannelInterceptor`) with 3-phase security (CONNECT/SUBSCRIBE/SEND)
- [x] **Phase 6: KDS Event Pipeline** — `SimpMessagingTemplate.convertAndSend()` in `OrderStateChangeListener`, broadcasts to `/topic/kitchen/{tenantId}/{shopId}`, fire-and-forget error isolation
- [x] **Phase 7: Kitchen Display UI** — `/dashboard/kitchen` page with `useStomp` hook, order card grid, status bump buttons (CONFIRMED->PREPARING->READY->COMPLETED), age-based colour borders (green/yellow/red), Web Audio API beep alerts, shop selector, mute toggle
- [x] **Phase 8: Test Coverage** — PaymentController (4 tests), PublicStorefrontController (7 tests), JwtTenantFilter (6 tests), TenantFilter (5 tests), GdprController (5 tests)
- [x] **Bug fixes** — V28 RLS policy GUC name (`app.tenant_id` -> `app.current_tenant_id`), V30 `product_name` denormalization for kitchen display
- [x] **Housekeeping** — stale branches cleaned, Docker cache cleared, test compile error fixed, `@stomp/stompjs` installed
- [x] **PR #27 open** — pushed to `feat/tier3-enhancements`, ready for review

## Not Yet Done

- [ ] **Merge PR #27** to main (user decision)
- [ ] **E2E browser testing** — manually test marketing dashboard + kitchen display in browser
- [ ] **Swagger UI verification** — start services, check `/swagger-ui.html` shows `/api/v1/` paths
- [ ] **Docker healthcheck fix** — `docker-compose.full-stack.yml` frontend healthcheck uses `localhost` (IPv6) instead of `127.0.0.1` (Next.js binds IPv4 only). 1-line fix.
- [ ] **Docs freshness** — README test counts stale (claims 130/199, actual ~350+), CHANGELOG has no formal releases
- [ ] **Env parity** — add `CORS_ALLOWED_ORIGINS` to `.env.example`
- [ ] **Stale worktree** — `worktree-agent-a2494f82` branch exists, safe to delete: `git branch -D worktree-agent-a2494f82`

## Failed Approaches (Don't Repeat These)

1. **Worktree merge "Already up-to-date"**: `isolation="worktree"` executor agents create branches from `main` (not the feature branch HEAD). Merge says "Already up-to-date" because the worktree branch has no divergence from `main`. **Fix**: Cherry-pick commits by hash instead of merging the worktree branch. All 8 phases used this workaround successfully.

2. **V30 migration `p.name` column**: Products table uses `title` not `name`. The migration `UPDATE order_items SET product_name = p.name FROM products p` failed. **Fix**: Changed to `p.title`. Also needed manual DB fix because Flyway recorded partial state from first failed attempt.

3. **`Product.getName()` doesn't exist**: Java entity uses `getTitle()`. The `OrderService` call `product.getName()` caused compile error. **Fix**: Changed to `product.getTitle()`.

4. **JDK 25 + Gradle 8.10 incompatible**: System JDK is 25.0.2 but Gradle 8.10 requires JDK 21. **Fix**: Always set `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`.

5. **Ollama port 11434 conflict**: Local Ollama instance blocks Docker Ollama. Non-blocking — Ollama is optional for AI image analysis.

6. **Keycloak DB connection stale**: After hours of unhealthy state, Keycloak's connection pool corrupts permanently. **Fix**: Full `docker compose down` + `up` (not just restart).

7. **Host curl/wget to Docker containers unreliable**: Background `curl` commands time out. **Fix**: Use `docker exec jtoye-core-java sh -c 'wget -q -O - http://localhost:9090/...'` for reliable verification.

## Key Decisions

| Decision | Rationale |
|----------|-----------|
| `WebMvcConfigurer.configurePathMatch()` for API versioning | Single config, not per-controller rewrite. Spring Boot 3.4.2 has no built-in `spring.mvc.api-version` |
| WebSocket bypasses Go edge, direct to Spring Boot | Go edge has no WS upgrade support. K8s Ingress handles WSS |
| `ExecutorChannelInterceptor` not `ChannelInterceptor` | `afterMessageHandled()` guaranteed on handler thread for TenantContext cleanup |
| SSE kept for dashboard, WebSocket additive for KDS | No breaking change. SSE tenant-blind issue accepted for authenticated dashboard |
| `ShopPromotion` extended (not recreated) | Entity already existed with `discountPercent`. Added `discountType` enum + `discountAmountPennies` |
| `productName` denormalized on `order_items` | Kitchen display needs readable names. Stored at order time so correct even if product renamed |
| Cherry-pick over worktree merge | Worktree branches created from `main` not feature HEAD — merge always "Already up-to-date" |

## Current State

**Working**: All code compiles (Java + Go + Frontend). All non-Testcontainers tests pass. API versioning verified on running containers. PR #27 pushed.

**Broken**: Nothing known. Frontend Docker healthcheck shows false "unhealthy" (IPv6 issue, serves traffic fine on `127.0.0.1:3000`).

**Uncommitted**: `.claude/` directory (GSD task runner state — not tracked)

## Files to Know

| File | Why It Matters |
|------|----------------|
| `core-java/src/main/java/uk/jtoye/core/config/WebConfig.java` | API versioning — `addPathPrefix("/api/v1/")` with 7-package predicate |
| `core-java/src/main/java/uk/jtoye/core/websocket/TenantChannelInterceptor.java` | KDS security — JWT validation, tenant topic scoping, TenantContext lifecycle |
| `core-java/src/main/java/uk/jtoye/core/websocket/WebSocketConfig.java` | STOMP broker config at `/ws` |
| `core-java/src/main/java/uk/jtoye/core/order/OrderStateChangeListener.java` | Event pipeline — RabbitMQ consumer, SSE + WebSocket broadcast |
| `core-java/src/main/resources/db/migration/V29__vendor_marketing.sql` | Promotions schema + announcements extraction + RLS fix |
| `core-java/src/main/resources/db/migration/V30__order_item_product_name.sql` | productName denormalization |
| `frontend/app/dashboard/marketing/page.tsx` | Vendor marketing CRUD (1225 lines) |
| `frontend/app/dashboard/kitchen/page.tsx` | Kitchen display with WebSocket (484 lines) |
| `frontend/hooks/use-stomp.ts` | Reusable STOMP WebSocket hook |
| `.planning/ROADMAP.md` | GSD roadmap — all 8 phases complete |
| `.planning/REQUIREMENTS.md` | 22 requirements with traceability |

## Environment

- **Java**: JDK 21 (`/usr/lib/jvm/jdk-21.0.6-oracle-x64`) — NOT system JDK 25
- **Gradle**: 8.10+ (Kotlin DSL) — run from repo root: `./gradlew`
- **Node**: 20+ with npm
- **Go**: 1.22
- **Docker**: `docker-compose.full-stack.yml` — 9 services (postgres, keycloak, redis, rabbitmq, minio, mailhog, core-java, edge-go, frontend)
- **Flyway**: V1-V30 (all applied)
- **Containers**: Currently DOWN (cache cleared). Rebuild with `docker compose -f docker-compose.full-stack.yml up -d --build`

## Resume Instructions

1. **Rebuild containers**:
   ```bash
   docker compose -f docker-compose.full-stack.yml up -d --build
   ```
   Wait ~60s for Keycloak healthy, then core-java starts automatically.
   - Expected: All 9 containers healthy (frontend may show "unhealthy" — false positive, see IPv6 note)

2. **Verify API versioning**:
   ```bash
   docker exec jtoye-core-java sh -c 'wget -q -O - http://localhost:9090/health'
   ```
   - Expected: `OK`
   ```bash
   docker exec jtoye-core-java sh -c 'wget -q -O - http://localhost:9090/public/shops 2>/dev/null | head -c 100'
   ```
   - Expected: JSON with shop data

3. **Run Java tests**:
   ```bash
   JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64 ./gradlew :core-java:test --no-daemon
   ```
   - Expected: 280+ tests pass (excludes @Tag("testcontainers"))
   - If `JAVA_HOME` error: system JDK 25 is being used instead of 21

4. **Browser test marketing dashboard**: Navigate to `http://localhost:3000/dashboard/marketing`
   - Expected: Tabbed page with Promotions + Announcements CRUD

5. **Browser test kitchen display**: Navigate to `http://localhost:3000/dashboard/kitchen`
   - Expected: Shop selector, order card grid (empty if no active orders)

6. **Merge PR**: Review and merge `feat/tier3-enhancements` → `main` via #27

## Warnings

- **Never use system JDK 25 with Gradle** — always set `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
- **Always rebuild ALL containers after code changes** — stale images cause subtle failures
- **Flyway partial state**: If a migration fails halfway, manually clean `flyway_schema_history` (`DELETE WHERE success = false`) before retrying
- **Frontend healthcheck false positive**: `docker-compose.full-stack.yml` uses `localhost` which resolves to IPv6 `::1` in Alpine. Next.js binds IPv4 only. Fix: change to `127.0.0.1`
- **Host curl unreliable**: Use `docker exec` for endpoint verification, not host curl
