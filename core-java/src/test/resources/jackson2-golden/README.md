# Jackson 2 golden fixtures (Phase 38, plan 38-01)

These files are the bytes that the **Boot 3.5.16 / Jackson 2** production serializers wrote for a
fixed set of sample objects. They are the oracle for every Jackson-3 compatibility proof in Phase 38
(Spring Boot 4.1 migration, issue #706, decision D-01).

Persisted and in-flight state crosses the Boot 4 deploy: Redis cache entries, queued AMQP messages,
outbox rows and idempotency rows. Once any Jackson-3 change lands, the old bytes can no longer be
produced from the tree. That is why they were captured first, on a tree with no Boot-4 change.

## Capture record

| Item | Value |
|------|-------|
| Capture commit (`CAPTURE_SHA`) | `e12177e1aac382bfa3c50f89a32d55091068d203` |
| Spring Boot | 3.5.16 (`core-java/build.gradle.kts` line 2) |
| jackson-databind (resolved, `runtimeClasspath`) | 2.21.7 (pinned via `jackson-bom.version`) |
| jackson-datatype-jsr310 | 2.21.7 |
| spring-web (ProblemDetail) | 6.2.19 |
| spring-amqp (`Jackson2JsonMessageConverter`) | 3.2.12 |
| spring-data-redis (`GenericJackson2JsonRedisSerializer`) | 3.5.13 |
| JDK | OpenJDK 25.0.4.1 |
| MANIFEST.tsv sha256 | `a46ac5deedbd7ad4e79a6a00504fc95a0c766024b76988c581cb1c27df27405f` |

Every byte here is the output of a **production serializer on that commit**. No file was written
or edited by hand:

- **JSON** (`idempotency/`, `responses/`, `outbox/`): Boot's auto-configured `ObjectMapper` bean on
  the `test` profile, which sets no `spring.jackson.*` key. The generator asserted that it was the
  same instance held by every writer captured here: `IdempotencyService`, `OrderEventPublisher`,
  `PaymentEventPublisher`, `RefundEventPublisher`, `OnboardingEventPublisher`, `MediaAssetService`,
  `WebhookFanoutListener`, `DsarIntakeService` and `ProblemDetailAuthenticationEntryPoint`.
  Idempotency hashes were computed by `IdempotencyService`'s own private `serialize` and `sha256Hex`.
- **AMQP** (`amqp/`): `new RabbitMQConfig().jsonMessageConverter()`. The generator asserted that the
  context's `RabbitTemplate` converter writes the same body bytes.
- **Redis** (`cache/`): `CacheConfig.jsonRedisSerializer()`, the serializer the cache manager uses.

The sample objects come from `uk.jtoye.core.boot4.GoldenSamples`, a deterministic factory that
imports nothing from Jackson. Two consecutive capture runs produced byte-identical trees.

## Families and consumers

| Directory | Contents | Consumed by |
|-----------|----------|-------------|
| `idempotency/` | 7 request fingerprints, `{endpoint-id}.request.json`, plus `request-hashes.tsv` (`endpoint-id TAB sha256`), the `request_hash` IdempotencyService stored | 38-10 (golden-hash + legacy replay) |
| `responses/` | 9 bodies: `OrderDto`, `CustomerDto`, `MediaAcceptDto`, `WebhookDeliveryView` (stored idempotency responses), `ProductDto`, `ShopDto`, `DsarIntakeAck`, `WebhookEventEnvelope`, `ProblemDetail-401` (public wire shapes) | 38-05 (wire contract), 38-07 (webhook envelope, ProblemDetail), 38-10 (stored responses) |
| `outbox/` | 6 outbox payload rows (`objectMapper.writeValueAsString(event)`, as the publishers write them) | 38-08 |
| `amqp/` | 6 message bodies (`*.body`) and 6 property tables (`*.headers.tsv`: `contentType`, `contentEncoding`, then every header, i.e. `__TypeId__`) | 38-08 |
| `cache/` | 3 Redis values: `products-ProductDto`, `shops-ShopDto`, `shopMembership-Membership` | 38-09 |
| `jsonb/` | Added by **38-02**, with its own `MANIFEST.tsv` (paths relative to `jsonb/`): the stored text (`SELECT col::text`) of `shops.opening_hours`, `products.allergen_spans` and `vendor_onboarding_gate.evidence`, written by Hibernate's JSON mapper on Boot 3.5.16. Key order is Postgres jsonb's normalised order, not Jackson's. The capture commit is named in `JsonbColumnsReadBackIntegrationTest`'s Javadoc | `JsonbColumnsReadBackIntegrationTest` (permanent read-back, BOOT4-08) |

Idempotency endpoint ids are the strings each adopter passes to `IdempotencyService`, except
`storefront.guest-order` and `storefront.guest-order.legacy`. Those two are the `requestBody`
(`GuestCheckoutIdentity`) and `legacyRequestBody` (`GuestOrderRequest`) of the stored endpoint
`storefront.orders.create`.

## Shapes worth knowing before you compare

- **AMQP bodies carry dates as epoch decimals and lose the offset.** For example,
  `"timestamp":1791117296.123456789` in `amqp/OrderStateChangeEvent-offset.body`, where the
  outbox row has `"2026-10-04T13:34:56.123456789+01:00"`. The converter builds its own mapper, which
  is not the Boot bean.
- **Cache values carry type ids** (default typing EVERYTHING), including the runtime collection
  class, such as `java.util.ArrayList`, `java.util.ImmutableCollections$ListN`,
  `java.util.LinkedHashMap` and `java.util.ImmutableCollections$Map1`.
- **The membership sample has one shop grant.** A `Map.copyOf` of two or more entries iterates in a
  per-JVM randomised order, so its bytes would not be reproducible.
- **Property order** in every JSON file is declaration order, as Jackson 2 wrote it. Jackson 3
  sorts properties alphabetically by default; that is the idempotency-hash hazard (38-RESEARCH,
  Pitfall 4).

## Integrity

`uk.jtoye.core.boot4.GoldenFixturesIntegrityTest` is permanent and imports nothing from Jackson. It
checks the following:

- every `MANIFEST.tsv` row (`relative-path TAB sha256 TAB byte-length`) against its file;
- that every file is listed exactly once (`MANIFEST.tsv` and this README are excluded by design);
- that each family holds exactly its expected files;
- that every `request-hashes.tsv` row is the sha256 of its request fixture;
- that every `GoldenSamples` factory is deterministic.

A changed, missing, extra or renamed file fails the build.

## Regenerating: from history only

The generator, `Jackson2GoldenCaptureTest`, has been **deleted from the tree**. It injects the
Jackson-2 `ObjectMapper` bean that plan 38-12 removes. **Re-running a capture on any Boot-4 tree
would re-capture these files under Jackson 3 and defeat their whole purpose.** It must never run
there.

To reproduce the bytes, use a checkout of the capture commit only:

```bash
git worktree add ../golden-capture e12177e1aac382bfa3c50f89a32d55091068d203
cd ../golden-capture
git show e12177e1aac382bfa3c50f89a32d55091068d203:core-java/src/test/java/uk/jtoye/core/boot4/Jackson2GoldenCaptureTest.java   # the generator, as it ran
JTOYE_GOLDEN_CAPTURE=true ./gradlew :core-java:cleanTest :core-java:test \
  --tests 'uk.jtoye.core.boot4.Jackson2GoldenCaptureTest' --no-daemon
sha256sum core-java/src/test/resources/jackson2-golden/MANIFEST.tsv   # expect a46ac5de...405f
```

Without `JTOYE_GOLDEN_CAPTURE=true`, the generator is skipped and writes nothing.
