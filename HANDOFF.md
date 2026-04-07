# Handoff: J'Toye OaaS — Batches 3-5 Complete, Batch 4 Ready for PR

**Generated**: 2026-04-07
**Branch**: `feat/batch4-infra` (ready for PR → main)
**Status**: Batch 4 complete. All tests passing.

## Completed (This Session)

### Batch 4 — Infrastructure & Process
- [x] **CORS from env vars** — CorsConfig reads `CORS_ALLOWED_ORIGINS` env var, comma-separated, defaults to localhost:3000
- [x] **Keycloak token lifespan** — access token 3600→300s, SSO max 36000→7200s, implicit 900→300s
- [x] **GDPR endpoints** — `/gdpr/customers/{id}/export` (Article 20) + `/gdpr/customers/{id}/erase` (Article 17). Anonymises PII across customers, orders, reviews. 6 unit tests
- [x] **K8s backup CronJob** — `pg-backup-cronjob.yaml` daily 02:00 UTC, pg_dump → S3, 30-day pruning

## Previously Completed
- Batch 3 (PR #21), Batch 5 (PR #22) — merged to main
- 383+ tests, 100% pass

## Not Yet Done

### Tier 2 — Reliability
- [ ] Resilience4j circuit breaker on Stripe/email/Ollama
- [ ] RabbitMQ dead letter queue + retry
- [ ] Custom business metrics (orders/hour, revenue/day)
- [ ] Scheduled cleanup jobs (stale DRAFT orders, orphaned images)

### Tier 3 — Enhancement
- [ ] Vendor dashboard UI for announcements/promotions (API exists, no UI)
- [ ] API versioning (/api/v1/ prefix)
- [ ] WebSocket for kitchen displays

### Remaining Test Gaps
- [ ] PaymentController (webhook endpoint)
- [ ] PublicStorefrontController (service tested, controller not)
- [ ] Security filters (JwtTenantFilter, TenantFilter)
- [ ] ReviewService (new, needs tests)

## Failed Approaches (Don't Repeat)
1. V23 migration: `SET NOT NULL` without `DEFAULT` fails on existing rows — use `NOT NULL DEFAULT 0`
2. Stale Docker: always rebuild ALL containers before E2E testing
3. Ollama healthcheck: no curl in image — use `ollama list`
4. JDK 25 + Gradle 8.10: incompatible — use `JAVA_HOME=/usr/lib/jvm/jdk-21.0.6-oracle-x64`
5. Native SQL `ORDER BY` + Spring Pageable: conflicts — add `countQuery`, use `Sort.unsorted()`

## Environment
- **Branch**: `feat/batch4-infra`
- **Java**: JDK 21 (`/usr/lib/jvm/jdk-21.0.6-oracle-x64`)
- **Migrations**: V1-V28 (no new migration needed for Batch 4)
- **Tests**: All passing

## Resume Instructions
1. Merge Batch 4 PR, then `git checkout main && git pull`
2. Next priority: Tier 2 reliability (circuit breakers, DLQ)
3. Stack: `docker compose -f docker-compose.full-stack.yml up -d` (rebuild first if code changed)
