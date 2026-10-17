---
phase: 39-platform-runtime-upgrades
plan: 06
subsystem: infra
tags: [redis-8, lettuce, spring-cache, bucket4j, testcontainers]
requires:
  - phase: 39-01
    provides: dependency-horizons baseline
provides:
  - Redis 8.10 (redis:8.10-alpine) across compose and all 8 Redis Testcontainers integration tests
  - Updated infra/dependency-horizons.yaml row for redis:8.10-alpine (cycle 8.10, eol: false)
affects: [core-java, infra, docs]
tech-stack:
  added: []
  patterns: []
key-files:
  created:
    - .planning/phases/39-platform-runtime-upgrades/evidence/39-06-redis8-upgrade.txt
  modified:
    - docker-compose.full-stack.yml
    - infra/dependency-horizons.yaml
    - core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
    - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
decisions:
  - "Pin redis:8.10-alpine across compose and Testcontainers per owner licence ruling (2026-10-04)."
metrics:
  completed_date: "2026-10-16"
---

# Phase 39 Plan 06: Redis 8.10 Upgrade Summary

Upgraded Redis from `7-alpine` (`7.4`) to `8.10-alpine` (`8.10.2`) across `docker-compose.full-stack.yml`, `infra/dependency-horizons.yaml`, and all 8 Redis Testcontainers integration tests in `core-java/src/test/java/`.

## Key Changes

1. **Runtime & Dependency Horizons**:
   - Updated `docker-compose.full-stack.yml` (`image: redis:8.10-alpine`) and `infra/dependency-horizons.yaml` (`pin: "redis:8.10-alpine"`, `eol_cycle: "8.10"`, `resolved_cycle: "8.10"`, `eol_date: "false"`).
2. **Testcontainers & Lettuce 7 / Spring Cache / Bucket4j Parity**:
   - Updated all 8 Redis Testcontainers test files (`CacheFormatIsolationIntegrationTest`, `RedisCacheErrorHandlerIntegrationTest`, `ShopAccessCacheKeyCollisionIntegrationTest`, `TenantStatusLookupCacheIntegrationTest`, `PublicRateLimitIntegrationTest`, `RateLimitIntegrationTest`, `RedisRateLimitDegradationTest`, `RedisFaultInjectionIntegrationTest`) from `"redis:7-alpine"` to `"redis:8.10-alpine"`.
   - Verified `uk.jtoye.core.boot4.CacheFormatIsolationIntegrationTest`, `uk.jtoye.core.security.PublicRateLimitIntegrationTest`, and `uk.jtoye.core.resilience.RedisFaultInjectionIntegrationTest` pass (`rc=0`) against `redis:8.10-alpine`, plus verified `redis-server --appendonly yes --requirepass` runtime startup (`redis_version:8.10.2`, `evidence/39-06-redis8-upgrade.txt`).
