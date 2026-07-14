# Phase 13 Deferred Items

Out-of-scope issues surfaced by the Phase 13 full-suite regression run (Task
13-01-05). Verified pre-existing against `git stash` of working tree —
reproduced on the untouched feature branch HEAD at `9c5309b`.

## 1. Local RabbitMQ broker authentication failure poisons ~40 Spring Boot integration tests

**Symptom:** `./gradlew :core-java:test -PincludeIntegration` fails ~40 tests
across `AuditIntegrationTest`, `CustomerControllerIntegrationTest`,
`FinancialTransactionControllerIntegrationTest`, `ShopControllerIntegrationTest`,
`OrderControllerIntegrationTest`, `MultiTenantIsolationIntegrationTest`,
`TenantSetLocalAspectTest` — all with the same cascade:

```
IllegalStateException: Failed to load ApplicationContext
Caused by: ApplicationContextException: Failed to start bean
  'org.springframework.amqp.rabbit.config.internalRabbitListenerEndpointRegistry'
Caused by: AmqpAuthenticationException: ACCESS_REFUSED - Login was refused using
  authentication mechanism PLAIN.
```

**Root cause:** The developer machine has a live RabbitMQ instance on
`localhost:5672` with non-default credentials. Tests that do NOT explicitly
stub RabbitMQ via `@DynamicPropertySource` (`spring.rabbitmq.port=0` +
`listener.simple.auto-startup=false`) attempt to authenticate against the real
broker and fail.

**Why out-of-scope for Phase 13:** Identical to Phase 12 Deviation #3. Phase 12
fixed it only for its newly-added tests by adding the stub-broker override;
the older tests predate Phase 12 and were never rewritten.

**Verification this is pre-existing:** Reproduced on commit `9c5309b`
(post-Task-13-01-04, pre-Task-13-01-05 modifications) via `git stash` —
`AuditIntegrationTest.shouldTrackShopCreationInAuditHistory` fails with the
same RabbitMQ PLAIN auth failure on the stashed working tree.

**Proposed fix (tracked for future phase):** Either (a) add a `.env.test` or
`application-test.yml` override that shifts `spring.rabbitmq.port` to 0 by
default, OR (b) add the three-line `@DynamicPropertySource` stub to each
affected test file. Option (a) is preferred — single-point-of-change.

**Impact on Phase 13 verification:** Zero — all Phase-13-scoped test methods
(6 in `CrossTenantSpoofIntegrationTest`, 4 new in `PublicStorefrontServiceTest`,
8 in fixed-up `ReviewServiceTest`) pass green against a fresh Testcontainers
Postgres. Phase 13 Success Criteria SC-1..SC-4 all verified.
