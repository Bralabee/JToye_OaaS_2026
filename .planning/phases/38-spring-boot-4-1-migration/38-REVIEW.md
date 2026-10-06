---
phase: 38-spring-boot-4-1-migration
reviewed: 2026-10-05T19:28:19Z
depth: standard
files_reviewed: 244
files_reviewed_list:
  - AGENTS.md
  - build.gradle.kts
  - CLAUDE.md
  - core-java/build.gradle.kts
  - core-java/Dockerfile
  - core-java/src/main/java/uk/jtoye/core/ai/ImageAnalysisService.java
  - core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java
  - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java
  - core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyService.java
  - core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RabbitMQConfig.java
  - core-java/src/main/java/uk/jtoye/core/config/RateLimitConfig.java
  - core-java/src/main/java/uk/jtoye/core/dev/DemoImageManifest.java
  - core-java/src/main/java/uk/jtoye/core/gdpr/DsarIntakeService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaAssetService.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/media/MediaPendingReaper.java
  - core-java/src/main/java/uk/jtoye/core/onboarding/OnboardingEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/order/OrderEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventOutboxFlusher.java
  - core-java/src/main/java/uk/jtoye/core/payment/PaymentEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/payment/RefundEventPublisher.java
  - core-java/src/main/java/uk/jtoye/core/security/CustomerJwtVerifier.java
  - core-java/src/main/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPoint.java
  - core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java
  - core-java/src/main/java/uk/jtoye/core/security/RateLimitInterceptor.java
  - core-java/src/main/java/uk/jtoye/core/security/SecurityConfig.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClient.java
  - core-java/src/main/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionService.java
  - core-java/src/main/java/uk/jtoye/core/webhook/WebhookFanoutListener.java
  - core-java/src/main/resources/application-prod.yml
  - core-java/src/main/resources/application-staging.yml
  - core-java/src/main/resources/application-test.yml
  - core-java/src/main/resources/application.yml
  - core-java/src/test/java/uk/jtoye/core/ai/ImageAnalysisServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/AmqpJackson2CompatibilityTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/AmqpTypeIdDispatchIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/AutoConfigurationCensusIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/Boot4ModuleLivenessIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/CacheFormatIsolationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/ConfigKeyContractTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/GoldenFixturesIntegrityTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/GoldenSamples.java
  - core-java/src/test/java/uk/jtoye/core/boot4/InFlightFixtures.java
  - core-java/src/test/java/uk/jtoye/core/boot4/Jackson3WireContractTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/JacksonLineContractTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/JsonbColumnsReadBackIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/OutboxPayloadCompatibilityTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/RenamedConfigKeysBindingTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/RequestBodyConstraintEnforcementTest.java
  - core-java/src/test/java/uk/jtoye/core/boot4/StatemachineSecurityAccessTest.java
  - core-java/src/test/java/uk/jtoye/core/common/GlobalExceptionHandlerRequestShapeTest.java
  - core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyFingerprintGoldenTest.java
  - core-java/src/test/java/uk/jtoye/core/common/idempotency/IdempotencyLegacyHashReplayIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/common/InsufficientStockExceptionHandlerTest.java
  - core-java/src/test/java/uk/jtoye/core/common/IntegrityViolationDiscriminationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/common/InvalidReviewPhotoExceptionHandlerTest.java
  - core-java/src/test/java/uk/jtoye/core/common/OptimisticLockExceptionHandlerTest.java
  - core-java/src/test/java/uk/jtoye/core/config/CacheSerializerTypeAllowlistTest.java
  - core-java/src/test/java/uk/jtoye/core/config/MediaListenerConcurrencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/config/RabbitListenerContainerFactoryTest.java
  - core-java/src/test/java/uk/jtoye/core/config/RabbitMQConfigMessageConverterTest.java
  - core-java/src/test/java/uk/jtoye/core/config/RabbitMQListenerFactoryBehaviourTest.java
  - core-java/src/test/java/uk/jtoye/core/config/TenantHeaderAbsentDocumentTest.java
  - core-java/src/test/java/uk/jtoye/core/dev/DemoImageManifestTest.java
  - core-java/src/test/java/uk/jtoye/core/finance/FinancialSummaryGoldenFileTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/DsarAckCompatibilityTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/DsarFanoutIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/DsarIntakeIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/DsarSubjectAndGlobalRateLimitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/DsarVerificationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/gdpr/GdprControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/CustomerControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/FinancialTransactionControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/FreshChainMigrationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/LocationHeaderContractTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/OpenApiSnapshotTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/ShopControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/TypedNotFoundBodyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/integration/VanishedRowStatusIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/CowSafetyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/GateStrictnessTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaClaimLockIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaCopyOnWriteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaDedupAttachIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaDurabilityIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaPendingReaperTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaProcessingWorkerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaRedriveControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaSweepTenantScopeIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaTenantIsolationUnderConcurrencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaUploadControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/media/MediaUploadIdempotencyTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/consent/PublicUnsubscribeControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/consent/PublicUnsubscribeRequestShapeTest.java
  - core-java/src/test/java/uk/jtoye/core/notification/dispatch/UnsubscribeLinkReachesControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingAdminQueueIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingCompanyNumberUpdateIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingCompanyNumberValidationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingCreateCrossTenantIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingEventPublisherTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGateResolveIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingGoLiveIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingResubmitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingReviewQueueIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingStallOutboxIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingSubmitterResolverIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/OnboardingWithdrawIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingEndToEndIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/onboarding/VendorOnboardingStateMachineServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderControllerPaginationTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderControllerShopFilterTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderEventFanoutTopologyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderEventPublisherTest.java
  - core-java/src/test/java/uk/jtoye/core/order/OrderStateChangeListenerIdempotencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/OutboxTenantListingFailureTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherCrossTenantIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxFlusherTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventOutboxReliabilityIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/PaymentEventPublisherTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundEventPublisherTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundRepositoryTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/RefundWebhookHandlingIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/payment/StripeWebhookIdempotencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductControllerShopFilterTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductImageDeleteIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductLabelGoldenFileTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductPartialUpdateIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/product/ProductSearchFtsIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/resilience/RedisFaultInjectionIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/access/MembershipSerializerRoundTripTest.java
  - core-java/src/test/java/uk/jtoye/core/security/access/SystemPrincipalGuardTest.java
  - core-java/src/test/java/uk/jtoye/core/security/CrossTenantSpoofIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/JwtIssuerDecouplingTest.java
  - core-java/src/test/java/uk/jtoye/core/security/ManagementPortMetricsIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/OpenApiDevProfileGatingTest.java
  - core-java/src/test/java/uk/jtoye/core/security/OpenApiProdProfileGatingTest.java
  - core-java/src/test/java/uk/jtoye/core/security/ProblemDetailAuthenticationEntryPointTest.java
  - core-java/src/test/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilterTest.java
  - core-java/src/test/java/uk/jtoye/core/security/PublicRateLimitIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RateLimitIntegrationTest.java.disabled
  - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorFailOpenTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RateLimitInterceptorTest.java
  - core-java/src/test/java/uk/jtoye/core/security/RoleBasedAccessIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/ScopedCatalogAccessIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/ScopedWriteAccessIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersDevProfileTest.java
  - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/SecurityHeadersProdProfileTest.java
  - core-java/src/test/java/uk/jtoye/core/security/StagingActuatorPortIsolationTest.java
  - core-java/src/test/java/uk/jtoye/core/security/UnauthenticatedProblemDetailIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/MarketingControllerShopFilterTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/MarketingMissingRowStatusIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopImageCrossTenantIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/shop/ShopServiceGeocodeTest.java
  - core-java/src/test/java/uk/jtoye/core/storage/AzuriteStorageIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/GuestCheckoutIdempotencyIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicApiVersionAliasIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicOrdersPaginationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerIdorTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerMyOrdersTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontControllerTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontDistanceIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/storefront/PublicStorefrontPostcodeSearchIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/sync/SyncBatchAuthorizationIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/sync/SyncControllerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakAdminClientTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/keycloak/KeycloakDeprovisionServiceTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/TenantLifecycleAdminIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/tenant/TenantOffboardKeycloakHookIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/testsupport/BootJsonMapper.java
  - core-java/src/test/java/uk/jtoye/core/testsupport/MailhogAssertions.java
  - core-java/src/test/java/uk/jtoye/core/webhook/WebhookAuthzIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryLogIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/webhook/WebhookDeliveryWorkerIntegrationTest.java
  - core-java/src/test/java/uk/jtoye/core/webhook/WebhookFanoutListenerEnvelopeTest.java
  - core-java/src/test/java/uk/jtoye/core/webhook/WebhookSubscriptionControllerIntegrationTest.java
  - core-java/src/test/resources/application-test.yml
  - core-java/src/test/resources/dev-manifest-38-07/truncated-manifest.json
  - core-java/src/test/resources/jackson2-golden/amqp/MediaProcessingEvent.body
  - core-java/src/test/resources/jackson2-golden/amqp/MediaProcessingEvent.headers.tsv
  - core-java/src/test/resources/jackson2-golden/amqp/OnboardingStateChangeEvent.body
  - core-java/src/test/resources/jackson2-golden/amqp/OnboardingStateChangeEvent.headers.tsv
  - core-java/src/test/resources/jackson2-golden/amqp/OrderStateChangeEvent.body
  - core-java/src/test/resources/jackson2-golden/amqp/OrderStateChangeEvent.headers.tsv
  - core-java/src/test/resources/jackson2-golden/amqp/OrderStateChangeEvent-offset.body
  - core-java/src/test/resources/jackson2-golden/amqp/OrderStateChangeEvent-offset.headers.tsv
  - core-java/src/test/resources/jackson2-golden/amqp/PaymentEvent.body
  - core-java/src/test/resources/jackson2-golden/amqp/PaymentEvent.headers.tsv
  - core-java/src/test/resources/jackson2-golden/amqp/RefundEvent.body
  - core-java/src/test/resources/jackson2-golden/amqp/RefundEvent.headers.tsv
  - core-java/src/test/resources/jackson2-golden/cache/products-ProductDto.bin
  - core-java/src/test/resources/jackson2-golden/cache/shopMembership-Membership.bin
  - core-java/src/test/resources/jackson2-golden/cache/shops-ShopDto.bin
  - core-java/src/test/resources/jackson2-golden/idempotency/customers.create.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/media.reprocess.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/media.upload.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/orders.create.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/request-hashes.tsv
  - core-java/src/test/resources/jackson2-golden/idempotency/storefront.guest-order.legacy.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/storefront.guest-order.request.json
  - core-java/src/test/resources/jackson2-golden/idempotency/webhooks.replay.request.json
  - core-java/src/test/resources/jackson2-golden/jsonb/MANIFEST.tsv
  - core-java/src/test/resources/jackson2-golden/jsonb/products.allergen_spans.json
  - core-java/src/test/resources/jackson2-golden/jsonb/shops.opening_hours.json
  - core-java/src/test/resources/jackson2-golden/jsonb/vendor_onboarding_gate.evidence.json
  - core-java/src/test/resources/jackson2-golden/MANIFEST.tsv
  - core-java/src/test/resources/jackson2-golden/outbox/MediaProcessingEvent.json
  - core-java/src/test/resources/jackson2-golden/outbox/OnboardingStateChangeEvent.json
  - core-java/src/test/resources/jackson2-golden/outbox/OrderStateChangeEvent.json
  - core-java/src/test/resources/jackson2-golden/outbox/OrderStateChangeEvent-offset.json
  - core-java/src/test/resources/jackson2-golden/outbox/PaymentEvent.json
  - core-java/src/test/resources/jackson2-golden/outbox/RefundEvent.json
  - core-java/src/test/resources/jackson2-golden/README.md
  - core-java/src/test/resources/jackson2-golden/responses/CustomerDto.json
  - core-java/src/test/resources/jackson2-golden/responses/DsarIntakeAck.json
  - core-java/src/test/resources/jackson2-golden/responses/MediaAcceptDto.json
  - core-java/src/test/resources/jackson2-golden/responses/OrderDto.json
  - core-java/src/test/resources/jackson2-golden/responses/ProblemDetail-401.json
  - core-java/src/test/resources/jackson2-golden/responses/ProductDto.json
  - core-java/src/test/resources/jackson2-golden/responses/ShopDto.json
  - core-java/src/test/resources/jackson2-golden/responses/WebhookDeliveryView.json
  - core-java/src/test/resources/jackson2-golden/responses/WebhookEventEnvelope.json
  - docs/AI_CONTEXT.md
  - docs/api/openapi-snapshot.json
  - docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md
  - docs/architecture/ESSENTIAL_ARCHITECTURE.md
  - docs/guides/DEPLOYMENT_GUIDE.md
  - docs/guides/USER_GUIDE.md
  - docs/metrics.json
  - .github/workflows/ci-cd.yaml
  - .gitleaksignore
  - .gitleaks.toml
  - HANDOFF.md
  - infra/dependency-horizons.yaml
  - k8s/LOCAL.md
  - mcp-server/src/tools/create-customer.ts
  - mcp-server/src/tools/create-order.ts
  - README.md
  - scripts/check-boot-config-keys.sh
  - scripts/check-doc-versions.sh
findings:
  critical: 0
  warning: 2
  info: 4
  total: 6
status: issues_found
---

# Phase 38: Code Review Report

**Reviewed:** 2026-10-05T19:28:19Z
**Depth:** standard
**Files Reviewed:** 244
**Status:** issues_found

## Summary

I reviewed the Boot 3.5 → 4.1.1 diff (`origin/main...HEAD`, 143 commits). I read all 52 production, build, config, CI and script files in full. For the 192 test files, I read every change that touched an assertion: 47 removed assertion lines, almost all `asText()` → `asString()` swaps. I also checked the new boot4 suites for checks that can never fail. I did not run Gradle or the test suites.

The named risk areas held up under the probes I ran:

- **Idempotency hashes.** I compared `JsonMapper.builderWithJackson2Defaults()` feature by feature against Jackson 3's defaults on the 3.1.7 jar. The golden fixtures were captured from the real Boot-3.5 `ObjectMapper` bean at `e12177e1`, a commit whose root build is still on 3.5.16.
- **SEC-4 cache allowlist.** `GenericJacksonJsonRedisSerializer`'s `useForType` (disassembled) matches the javadoc's NON_FINAL claim. A `java.util.logging.FileHandler` type id is refused by Jackson's denylist, and `java.net.URI` by the validator.
- **AMQP converter.** The trusted packages are unchanged, and both directions are covered with negative controls. Nothing outside the JVM consumes AMQP (no Go or TS amqp client), so the move of the timestamp from epoch to ISO cannot break a consumer.
- **401 `resource_metadata` stripping.** It is wired on BOTH `exceptionHandling` and `oauth2ResourceServer`. In Security 7.1.1, `BearerTokenAccessDeniedHandler` (403) does not emit `resource_metadata`.
- **`/.well-known` suppression filter.** There is only one `SecurityFilterChain`, so the framework's metadata filter cannot be reached on another chain.
- **Keycloak offboarding PUT.** The body is a `tools.jackson` `ObjectNode`, and the by-content test checks the garbage-key signature.
- **Renamed config keys.** I found no Boot-3 key names left in k8s, compose or env-var form. There is no custom `logback-spring.xml` that would read the old rotation properties.
- **Jackson 3 deserialization defaults.** No request DTO has a primitive component, so `FAIL_ON_NULL_FOR_PRIMITIVES` changes nothing. No enum overrides `toString()`, so the ENUMS_USING_TO_STRING defaults change nothing. No `com.fasterxml.jackson.databind.annotation.*` is left in main code; Jackson 3 would silently ignore it.
- **Tenant isolation.** RLS and `TenantContext` code is untouched by this phase. The cache keys still carry the tenant segment under the new `v4:` prefix.

There are two warnings:

1. The versioned cache prefix stops evictions reaching across Boot versions during a rolling deploy. This includes the `shopMembership` authorization cache. The ADR says no flush is needed in either direction, and it does not mention this gap.
2. The "frozen" idempotency mapper is not a faithful copy of Boot 3.5's mapper: Boot 3.5's mapper detected constructor parameter names, and this one does not. The gap is latent today, but the mapper's own rule forbids editing it after ship, so it should be closed now.

## Warnings

### WR-01: The versioned cache prefix splits evictions across Boot versions during a rolling deploy and a rollback (stale authorization for up to 5 min)

**File:** `core-java/src/main/java/uk/jtoye/core/config/CacheConfig.java:97` (with `core-java/src/main/java/uk/jtoye/core/config/TenantCacheEvictor.java:150-151` and `docs/architecture/decisions/ADR-0006-spring-boot-4-migration.md:175-177,210-213`)

**Issue:**
- `computePrefixWith(cacheName -> "v4:" + cacheName + "::")` puts Boot-4 pods on `v4:{region}::tenant:…`, while Boot-3.5 pods stay on `{region}::tenant:…`.
- Every eviction goes through `Cache.evict(key)`, which applies only the evicting pod's own prefix (`TenantCacheEvictor.evictEntity`, and the grant/revoke + JIT-provision path for `shopMembership`).
- During the `RollingUpdate` window (`k8s/base/core-java-deployment.yaml`: replicas 3, maxSurge 1, maxUnavailable 0), a revoke handled by one generation does not evict the other generation's entry.
- The other generation's pods therefore keep serving the cached `Membership` grant until the 5-minute TTL. Products and shops likewise serve stale data for up to 10 and 15 minutes.
- Before this change the key was shared, so an eviction from any pod removed the entry for all pods.
- On rollback, 3.5 pods read any un-prefixed entry still inside its TTL. That entry was never evicted by writes the Boot-4 pods made.

The ADR states "No flush is needed in either direction" and lists only the upside: "a Boot-4 pod never reads a 3.5-era entry". `CacheConfig`'s Javadoc says "the two formats never meet", which is exactly why evictions no longer meet either. The `shopMembership` comment in `CacheConfig` says "an auth boundary must not carry a stale allow for long". During a deploy, the TTL backstop becomes the primary revocation mechanism.

**Fix:** Pick one of these and record it in the ADR Deploy and Rollback notes:

(a) For the transition release, evict both key forms. `TenantCacheEvictor` would also delete the legacy un-prefixed key:
```java
// TenantCacheEvictor.evictEntity, transitional (remove once no 3.5 pod can exist)
cache.evict(key);
if (redisTemplate != null) {
    redisTemplate.delete(cacheName + "::" + key);   // legacy Boot-3.5 key
}
```
Note that the 3.5 side cannot be taught to evict `v4:` keys. So also run (b) for the forward direction.

(b) Run a post-rollout operator step that deletes `v4:shopMembership::*` once the last 3.5 pod has terminated (and `shopMembership::*` after a rollback). State the bounded staleness explicitly in the ADR: "a shop-grant revoke made during the rollout can take up to 5 minutes to apply on pods of the other generation".

### WR-02: `IdempotencyJson` does not reproduce Boot 3.5's parameter-name detection, so it is not the "frozen Boot-3.5 format" it claims to be

**File:** `core-java/src/main/java/uk/jtoye/core/common/idempotency/IdempotencyJson.java:42-50`

**Issue:** Boot 3.5's auto-configured `ObjectMapper` registered `ParameterNamesModule` (part of `spring-boot-starter-json`, registered by `Jackson2ObjectMapperBuilder`). `JsonMapper.builderWithJackson2Defaults()` sets `MapperFeature.DETECT_PARAMETER_NAMES=false`. I diffed every Mapper, Serialization, Deserialization, Enum and DateTime feature against the plain Jackson 3 builder on the 3.1.7 jar. The frozen mapper never re-enables it. Measured on 3.1.7, with a `-parameters` class that has only an all-args constructor:
- `IdempotencyJson`-equivalent `readValue` → `InvalidDefinitionException`. With `DETECT_PARAMETER_NAMES` it reads.
- With a constructor parameter order different from the field order, the write bytes differ: `{"zeta":"z","alpha":1}` vs `{"alpha":1,"zeta":"z"}`. With parameter names the creator properties sort first (`SORT_CREATOR_PROPERTIES_FIRST`), which is what Boot 3.5 wrote.

All seven current fingerprints and all four stored response types are setter-style Lombok classes or records, so the golden test is green. The gap is latent, but the class's own rule is "never edit this mapper in place; a changed byte needs a dual-hash window". So the next adopter whose request or response DTO is constructor-only would either:
- fail replay with `IllegalStateException` → 500, or
- force an edit that this rule makes expensive.

The Javadoc's claim to reproduce the bytes of Boot 3.5's `ObjectMapper` is true only for the types the golden test happens to cover.

**Fix:** Close it now, while it is free. `IdempotencyFingerprintGoldenTest` proves that the 7 hashes and 4 bodies do not move:
```java
private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
        // Boot 3.5 registered ParameterNamesModule (spring-boot-starter-json).
        .enable(MapperFeature.DETECT_PARAMETER_NAMES)
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        ...
```
Add a golden-style case with a constructor-only type to pin it.

## Info

### IN-01: The CI gate step sets database env vars the script says it does not need

**File:** `.github/workflows/ci-cd.yaml:267-272`
**Issue:** The step exports `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`. Both `scripts/check-boot-config-keys.sh:39-40` and the step comment say the gate needs "no database, no broker, no network service". `ConfigKeyContractTest` reads only metadata and YAML, so the env block is dead. It invites a future reader to assume a DB dependency.
**Fix:** Drop the `env:` block, or add a one-line comment saying why it is there.

### IN-02: `spring-retry` is pinned by hand, outside every BOM and horizon row

**File:** `core-java/build.gradle.kts:254`
**Issue:** `org.springframework.retry:spring-retry:2.0.13` is no longer managed by Boot 4.1.1. It is a maintenance-mode library that Spring Framework 7 supersedes with its own `@Retryable`. It now carries a literal version that nothing tracks: dependabot only bumps it, and `infra/dependency-horizons.yaml` has no row for it. `StockService`'s optimistic-lock retry depends on it via `RetryConfig`'s `@EnableRetry`.
**Fix:** Add a horizons row (or a dated note), or migrate `StockService` to Spring Framework 7's `org.springframework.resilience.annotation.Retryable` in a follow-up.

### IN-03: The suppression filter's 404 `instance` uses the raw request URI

**File:** `core-java/src/main/java/uk/jtoye/core/security/ProtectedResourceMetadataSuppressionFilter.java:148-153`
**Issue:** `instanceOf` returns `request.getRequestURI()`, which includes the context path and is not decoded. The Javadoc claims "member for member" parity with Spring MVC's `NoResourceFoundException` document. Spring MVC fills `instance` from the request's path, which agrees today only because no context path is configured.
**Fix:** If parity is the contract, derive the value the same way MVC does (`UrlPathHelper` / `ServletServerHttpRequest#getURI().getRawPath()`). Otherwise document that the value assumes an empty context path.

### IN-04: HANDOFF.md's live block still says Phase 38 is "planned, ready to execute"

**File:** `HANDOFF.md:1-5,25-27`
**Issue:** The phase only bumped the gate count (46 → 47). The header and "Next, in order: 1. Execute Phase 38" still describe the pre-execution state of the branch that now contains the whole execution. The next session would resume from a wrong premise.
**Fix:** Add a dated delta block at phase close: Phase 38 executed, review status, and what remains (38-17 and 38-18 checkpoints, the post-merge Trivy gate).

---

_Reviewed: 2026-10-05T19:28:19Z_
_Reviewer: Claude (gsd-code-reviewer)_
_Depth: standard_
