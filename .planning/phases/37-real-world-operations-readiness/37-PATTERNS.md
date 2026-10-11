# Phase 37: Real-world operations readiness - Pattern Map

**Mapped:** 2026-10-07
**Tree:** `phase-37-ops-readiness` (post Phase 38 + 31.1 merge). Every analog path below was confirmed with `git ls-files` (tracked source; no `build/` or mirror paths).
**Files analyzed:** ~70 new/modified files across 7 sub-themes
**Analogs found:** 66 / ~70 (no-analog list at the end)

> Caller claims below that come from grep are marked *(unverified caller)*: text search cannot prove "who calls this" (Proof Standard 7). Line numbers drift; re-grep before quoting in a plan.

---

## File Classification

### Migrations (Flyway head = V75; reserve V76-V82 up front)

| New file | Role | Data flow | Closest analog | Match |
|---|---|---|---|---|
| `V76__staff_invite.sql` (+ optional `user_directory.realm_admin_seen_at`) | migration (new tenant table, token-digest) | CRUD | `V71__trader_identity.sql` (RLS shape) + `V75__dsar_access_export.sql` (token_sha256, expiry) | exact |
| `V77__order_void_ledger_reversal.sql` | migration (columns + `_aud` + index swap) | CRUD | `V73__order_allergy_note.sql` (cols + `orders_aud` same migration) | exact |
| `V78__financial_transactions_shop_id.sql` | migration (column + tenant-loop backfill) | batch | `V57__shop_staff_grant_source.sql` | exact |
| `V79__shop_opening_schedule_pause.sql` | migration (jsonb + enum col + tenant-loop backfill) | batch/transform | `V57__shop_staff_grant_source.sql` | exact |
| `V80__order_contact_verified.sql` (+ IP HMAC per D-27) | migration (nullable cols, no backfill) | CRUD | `V73__order_allergy_note.sql` | exact |
| `V81__integration_credential.sql` | migration (new tenant table +/- `_aud`) | CRUD | `V71__trader_identity.sql` | exact |
| `V82__shop_fulfilment_offered.sql` | migration (NOT NULL DEFAULT true + `_aud`) | CRUD | `V73__order_allergy_note.sql` (shape) | role-match |

### core-java

| New/Modified file | Role | Data flow | Closest analog | Match |
|---|---|---|---|---|
| `security/access/ShopAccessService.java` (flip posture log, `effectiveAccessFor`, read-path directory upsert) | service | request-response | itself: `canAccessShop` :463-482 | exact |
| `security/access/StaffManagementService.java` / `StaffController.java` (effective access list, invites) | service/controller | CRUD | themselves (`grant`/`persistNewGrant`/`revoke`) | exact |
| `security/access/StaffInvite{,Repository,Service}.java`, `StaffInviteController.java` (auth) + `PublicStaffInviteController.java` | model/service/controller | CRUD + token | `gdpr/DsarAccessExportService.java` (token issue) + `StaffController.java` | role-match |
| `security/access/StaffInviteMailer.java` (or `EmailNotificationService.sendStaffInvite`) | service (mail) | event-driven | `gdpr/DsarVerificationMailer.java` | exact |
| `tenant/keycloak/KeycloakAdminClient.java` (+ createUser, client CRUD, client-secret, SA user) | client | request-response | itself: `deleteUser` / `setUserEnabled` / `findUsersByEmail` | exact |
| `integration/credential/IntegrationCredential{,Service,Controller,Repository}.java` | service/controller | CRUD + external | `security/access/StaffController.java` + `KeycloakAdminClient` | role-match |
| `security/ClientIpResolver.java` → `@Component` with trusted CIDRs | utility | request-response | itself + `gdpr/DsarIntakeRateLimiter.java` `@Value` style | exact |
| `security/RateLimitInterceptor.java`, `gdpr/DsarIntakeRateLimiter.java` (inject resolver bean) | middleware | request-response | themselves (:322, :146) | exact |
| `order/OrderService.java` (delete guard, void, `KITCHEN_STATUSES` += PENDING, submit-on-create, limits, shop-less refuse) | service | CRUD | `acknowledgeAllergyNote` :618-645 | exact |
| `order/OrderController.java` (`POST /{id}/void`, `POST /bulk-cancel`) | controller | request-response | `acknowledgeAllergyNote` endpoint :391-421 | exact |
| `order/OrderLimits.java` (D-20) | utility/policy | transform | `order/FulfilmentPolicy.java` | exact |
| `storefront/ShopAvailabilityPolicy.java` (D-15) | policy | transform | `order/FulfilmentPolicy.java` | exact |
| `storefront/CashOrderCaps.java` (D-18) | service | CRUD (count query) | `gdpr/DsarIntakeRateLimiter.java` (config-declared axes) | role-match |
| `storefront/PublicStorefrontService.java` (basket diff in loop, availability policy, caps) | service | request-response | itself: 31.1 allergen-ack collect-then-throw in `placeGuestOrder` | exact |
| `storefront/dto/GuestOrderRequest.java`, `GuestOrderItemRequest.java` (expected prices) | DTO | — | `GuestOrderRequest.acknowledgedAllergenMask` :87-94 | exact |
| `order/dto/CreateOrderRequest.java` (`submit`) | DTO | — | same `@JsonInclude(NON_NULL)` field | exact |
| `exception/BasketChangedException.java`, `OrderNotDeletableException.java`, `OrderCapExceededException.java`, `OrderLimitExceededException.java`, `ShopClosedException.java`, `StaffInviteUnavailableException.java` | exception | — | `exception/AllergenAcknowledgementStaleException.java`, `exception/DsarExportUnavailableException.java` | exact |
| `common/GlobalExceptionHandler.java` (new handlers) | middleware | — | `handleAllergenAcknowledgementStale` :773-784, `handleDsarExportUnavailable` :793-804 | exact |
| `finance/FinancialTransaction{,Service,Repository,Controller}.java` (entry_kind, shop_id, summary?shopId, CSV) | service/controller | CRUD/batch | themselves; gate from `StaffController` (requireGroupAdmin) | exact |
| `review/ReviewService.java` (D-21 shop check, display name) | service | CRUD | itself :64-110 guard chain | exact |
| `sync/SyncService.java` (+ `SyncItem.shopId`, per-item results) | service | batch | itself + `ProductService.createProduct` | exact |
| `product/ProductMapper.java`, `CreateProductRequest.java`, `ProductController.java` (trackInventory, PATCH, CSV shopId, copy) | mapper/controller | CRUD | themselves | exact |
| `shop/ShopService.java`, `ShopDto`, `CreateShopRequest` (slug keep, fees, offered) | service/DTO | CRUD | themselves | exact |
| `notification/webhook/WebhookDeliveryWorker.java` (HELD, delivery-level count, pause email) | service | event-driven | itself + `DsarVerificationMailer` for the email | exact |
| `dev/DemoDataSeeder.java` (OPERATOR grant for `integration-orders-rw`, explicit hours) | config/seed | batch | `DemoDataSeederTraderIdentityIntegrationTest` shows seed proof | role-match |
| `src/main/resources/application.yml` (+ compose, `.env.example`, `k8s/base/configmap.yaml`, goldens) | config | — | `jtoye.access` block :194-213, `jtoye.gdpr.dsar.rate-limit.*` | exact |

### core-java tests

| New test | Analog | Match |
|---|---|---|
| `StaffInviteRlsIntegrationTest`, `IntegrationCredentialRlsIntegrationTest` (NOSUPERUSER) | `onboarding/TraderIdentityRlsIntegrationTest.java` | exact |
| `V78FinanceShopBackfillIntegrationTest`, `V79OpeningScheduleBackfillIntegrationTest` (stepwise Flyway, ≥2 tenants) | `security/access/V57GrantSourceBackfillIntegrationTest.java` | exact |
| `KeycloakAdminClientTest` additions | `tenant/keycloak/KeycloakAdminClientTest.java` | exact |
| `ClientIpResolverTest` rewrite | `security/ClientIpResolverTest.java` | exact |
| `FulfilmentPolicyTest`-style unit tests for `OrderLimits`, `ShopAvailabilityPolicy` | `order/FulfilmentPolicyTest.java` | exact |
| Fingerprint golden must stay green | `common/idempotency/IdempotencyFingerprintGoldenTest.java` | gate |
| RLS sweep | `security/RlsContractTest.java` (`EXEMPT_TABLES` :95 — do NOT add new tables) | gate |
| Tests using vendor tokens after D-06 | `testsupport/TenantJwts.java` | helper |

### frontend

| New/Modified file | Role | Closest analog | Match |
|---|---|---|---|
| `components/dashboard/kitchen/kds-new-lane.tsx`, `kds-sound.tsx`, `kds-board-stopped.tsx`, `KdsFulfilmentBadge`, `KdsCustomerNote`, `UnverifiedContactBadge`, `PauseOrdersControl`, `PausedBanner` | component | `components/dashboard/kitchen/allergy-note-block.tsx`, `kds-feed-status.tsx` | exact |
| `components/dashboard/kitchen/kds-feed-status.tsx` (+5 states) | component | itself (`PILL` record) | exact |
| `app/dashboard/kitchen/page.tsx` (lane, shared AudioContext, title, lapse claim) | page | itself | exact |
| `lib/kitchen-orders-api.ts` (`KITCHEN_STATUSES` += PENDING) | utility | itself :14 + server parity | exact |
| `lib/api-client.ts` (`jtoye:session-lapsed` event before redirect) | utility | itself :144-162 | exact |
| `app/auth/signin/page.tsx` (validated `callbackUrl`) | page | itself :104 | exact |
| `components/dashboard/confirm-action-dialog.tsx` (moved + `cancelLabel`) | component | `components/dashboard/webhooks/ConfirmActionDialog.tsx` | exact |
| `SecretRevealDialog` generalised | component | `components/dashboard/webhooks/SecretRevealDialog.tsx` | exact |
| `app/invite/[token]/page.tsx` + `invite-client.tsx` (B2) | page (public token) | `app/data-request/confirm/page.tsx` + `confirm-client.tsx` | exact |
| `components/dashboard/no-access-page.tsx` (B3) | component | `confirm-client.tsx` layout + `load-error-panel.tsx` | role-match |
| `components/dashboard/staff/*` + `app/dashboard/staff/page.tsx` | component/page | `app/dashboard/staff/page.tsx` | exact |
| `components/dashboard/orders/void-order-dialog.tsx`, `reject-orders-dialog.tsx` | component | `ConfirmActionDialog.tsx` | role-match |
| `components/storefront/basket-changed-panel.tsx` + `checkout/page.tsx` problem reader | component | `readStaleAllergenProblem` in `app/shop/[slug]/checkout/page.tsx` :111-140 | exact |
| `components/storefront/shop-availability-notice.tsx` | component | `components/storefront/seller-block.tsx` *(by naming/role; not read)* | role-match |
| `components/dashboard/shops/opening-schedule-editor.tsx` | component (form) | free-text editor in `app/dashboard/shops/page.tsx` :575-590 | partial |
| `components/dashboard/developers/*` + `app/dashboard/developers/page.tsx` | page | `app/dashboard/webhooks` page + `SecretRevealDialog` | role-match |
| per-route `layout.tsx` with `generateMetadata` (37-F UXT-084) | config | `app/track/layout.tsx` :34 | exact |

### mcp-server / edge-go

| File | Role | Analog | Match |
|---|---|---|---|
| `mcp-server/src/tools/create-order.ts` (send `submit: true`, description states PENDING) | tool | itself :172-189 | exact |
| `mcp-server/src/tools/list-products.ts` (`shopId`, `availableOnly`) | tool | itself :20-40 (allow-listed qs) | exact |
| `edge-go/cmd/edge/main.go` (per-tenant limiter after JWT, typed 429) | middleware | `rateLimiter` :166-200 | exact |
| `edge-go/internal/core/client.go`, `cmd/edge/handlers.go`, `types.go` (pass problem+json, `Results`) | client | themselves :137-153 | exact |

---

## Pattern Assignments

### New tenant table migrations: `V76__staff_invite.sql`, `V81__integration_credential.sql`

**Analog:** `core-java/src/main/resources/db/migration/V71__trader_identity.sql` (comment lines stripped below)

Table + ENABLE/FORCE + idempotent policy via `current_tenant_id()`:
```sql
CREATE TABLE IF NOT EXISTS trader_identity (
    id                UUID PRIMARY KEY,
    tenant_id         UUID         NOT NULL UNIQUE REFERENCES tenants(id),
    ...
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);
ALTER TABLE trader_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE trader_identity FORCE ROW LEVEL SECURITY;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='trader_identity' AND policyname='trader_identity_tenant_policy') THEN
    CREATE POLICY trader_identity_tenant_policy ON trader_identity
        FOR ALL
        USING      (tenant_id = current_tenant_id())
        WITH CHECK (tenant_id = current_tenant_id());
  END IF;
END $$;
```
`_aud` mirror (only if the entity is `@Audited`; V65 INSERT shape, SELECT tenant-scoped, NO UPDATE/DELETE policy):
```sql
CREATE POLICY trader_identity_aud_insert_policy ON trader_identity_aud
    FOR INSERT
    WITH CHECK ((tenant_id IS NULL) OR (tenant_id = current_tenant_id()));
```
Close with a `RAISE NOTICE 'V7x … applied: …'` block, as V71 does.

**Token column** (V75 rule: only the digest at rest). Copy the column shape `token_sha256 … UNIQUE` + `expires_at` from `V75__dsar_access_export.sql`; but unlike V75, `staff_invite` IS tenant-scoped (tenant id in the link, server pins it; RESEARCH 37-B.3), so it gets the V71 policy and must NOT be added to `RlsContractTest.EXEMPT_TABLES`.

---

### Column migrations: `V77`, `V80`, `V82`

**Analog:** `V73__order_allergy_note.sql`
```sql
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note        VARCHAR(500);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note_ack_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS allergy_note_ack_by VARCHAR(255);
ALTER TABLE orders ADD CONSTRAINT ck_orders_allergy_note_ack_pair
    CHECK ((allergy_note_ack_at IS NULL) = (allergy_note_ack_by IS NULL));
COMMENT ON COLUMN orders.allergy_note IS '…NULL means…';
ALTER TABLE orders_aud ADD COLUMN IF NOT EXISTS allergy_note        VARCHAR(500);
...
```
Rules to carry: `_aud` mirror in the SAME migration for `@Audited` entities (Order, Shop, FinancialTransaction); NULL = "not recorded" with no backfill/no DEFAULT for `contact_verified` (V80); V77 `voided_at/voided_by/void_reason` gets a pair CHECK like `ck_orders_allergy_note_ack_pair`. V77 also drops/recreates `uq_fin_tx_tenant_order` (V40 :99-101) as `(tenant_id, order_id, entry_kind) WHERE order_id IS NOT NULL`. V82 defaults `true` are legitimate (preserve today's behaviour).

---

### Backfill migrations: `V78`, `V79` (FORCE-RLS trap)

**Analog:** `V57__shop_staff_grant_source.sql`
```sql
DO $$
DECLARE
    t          RECORD;
    n          BIGINT;
    backfilled BIGINT := 0;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.current_tenant_id', t.id::text, true);
        UPDATE shop_staff
           SET grant_source = CASE WHEN created_by IS NULL THEN 'JIT' ELSE 'OPERATOR' END
         WHERE tenant_id = t.id
           AND grant_source IS NULL;
        GET DIAGNOSTICS n = ROW_COUNT;
        backfilled := backfilled + n;
    END LOOP;
    PERFORM set_config('app.current_tenant_id', '', true);
    RAISE NOTICE 'V57: backfilled grant_source on % pre-V57 shop_staff row(s).', backfilled;
END $$;
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'shop_staff_grant_source_check') THEN
    ALTER TABLE shop_staff ADD CONSTRAINT shop_staff_grant_source_check CHECK (grant_source IN ('JIT','OPERATOR'));
  END IF;
END $$;
ALTER TABLE shop_staff ALTER COLUMN grant_source SET DEFAULT 'JIT';
ALTER TABLE shop_staff ALTER COLUMN grant_source SET NOT NULL;
ALTER TABLE shop_staff_aud ADD COLUMN IF NOT EXISTS grant_source VARCHAR(16);
```
V79: `hours_source` CHECK `IN ('STRUCTURED','MIGRATED_FROM_TEXT','NEEDS_REVIEW','LEGACY_ALWAYS_OPEN')`; NULL/empty map → `LEGACY_ALWAYS_OPEN` (D-25); regex `(\d{2}):(\d{2})\s*-\s*(\d{2}):(\d{2})` matches `PublicStorefrontService.HOURS_PATTERN`. Beware `${…}` in comments (Flyway substitution trap).

**Test analog:** `core-java/src/test/java/uk/jtoye/core/security/access/V57GrantSourceBackfillIntegrationTest.java` — plain JUnit + `@Testcontainers` `PostgreSQLContainer("postgres:15")`, `Flyway.configure()….target(MigrationVersion.fromVersion("56"))` as superuser (:89-90), seed ≥2 tenants, then migrate to target as the RLS-bound `RLS_MIGRATOR` role (:144-145) and assert per-tenant row content.

---

### NOSUPERUSER RLS proofs (`staff_invite`, `integration_credential`)

**Analog:** `core-java/src/test/java/uk/jtoye/core/onboarding/TraderIdentityRlsIntegrationTest.java`
- `@SpringBootTest` + `@Testcontainers` (:71-77), NOT `@Transactional` (:67).
- Downgrade: `jdbc.execute("ALTER ROLE \"" + DOWNGRADED_APP_ROLE + "\" NOSUPERUSER")` (:111).
- Arms (:222-272): A's GUC → A visible, B hidden, UPDATE of B matches 0, INSERT stamped B refused SQLSTATE `42501`, each beside an own-tenant positive control.
- GUC pin in test: `SELECT set_config('app.current_tenant_id', ?, true)` (:576).

---

### Typed refusal exceptions + handlers (all new 4xx)

**Analog exception:** `core-java/src/main/java/uk/jtoye/core/exception/AllergenAcknowledgementStaleException.java`
```java
public class AllergenAcknowledgementStaleException extends RuntimeException {
    public record StaleLine(UUID productId, String productName, int allergenMask, List<String> allergens) {
        public StaleLine { allergens = List.copyOf(allergens); }
    }
    private final int currentMask;
    ...
    public AllergenAcknowledgementStaleException(int currentMask, List<String> currentAllergens,
                                                 int acknowledgedMask, List<StaleLine> lines) {
        super("The allergen information for your basket changed after you read it. "
                + "Read it again and confirm before placing the order.");
        this.lines = List.copyOf(lines);
    }
```
`BasketChangedException` copies this with `record BasketChange(kind, productId, name, expected, current, …)` and optionally carries the 4 allergen properties (compose, D-16).

**Analog handler:** `core-java/src/main/java/uk/jtoye/core/common/GlobalExceptionHandler.java` :773-784
```java
@ExceptionHandler(AllergenAcknowledgementStaleException.class)
public ProblemDetail handleAllergenAcknowledgementStale(AllergenAcknowledgementStaleException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    problem.setTitle("Allergen information changed");
    problem.setType(URI.create("https://jtoye.uk/errors/allergen-acknowledgement-stale"));
    problem.setProperty("code", "ALLERGEN_ACKNOWLEDGEMENT_STALE");
    problem.setProperty("currentAllergenMask", ex.getCurrentMask());
    ...
    return problem;
}
```
Non-disclosing single-body 404 (invite accept with wrong tenant/token/expired/used) copies :793-804 `handleDsarExportUnavailable` incl. `CacheControl.noStore()`.

Slugs to add: `basket-changed` (409), `basket-confirmation-required` (422), `order-not-deletable` (409), `order-cap-exceeded` (429 — must NOT be `rate-limited`; `lib/api-client.ts` :134-141 auto-retries that type), `order-limit-exceeded` (422), `shop-closed`, `staff-invite-unavailable` (404), credential "not configured" (400, #102 precedent). Throw basket/limit/cap refusals INSIDE `idempotencyService.executeWithoutStoringResponse(...)` so the reservation rolls back (31.1 T-31.1-10).

---

### `order/OrderService.java` + `OrderController.java` (D-10 void/delete guard, D-11, D-13, D-18 bulk cancel)

**Analog:** `OrderService.acknowledgeAllergyNote` (:618-645) and its controller (:391-421).
```java
public OrderDetailDto acknowledgeAllergyNote(UUID orderId) {
    Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    shopAccessService.require(order.getShopId(), ShopRole.STAFF);
    if (order.getAllergyNote() == null) {
        throw new InvalidStateTransitionException("This order has no allergy note to acknowledge");
    }
    if (order.getAllergyNoteAckAt() == null) {   // first write wins → idempotent by construction
        order.setAllergyNoteAckAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        order.setAllergyNoteAckBy(currentPrincipalName());
        order = orderRepository.save(order);
    }
    OrderDetailDto dto = orderMapper.toDetailDto(order);
    ...
}
```
Controller: `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` + `@PostMapping("/{id}/…")` + full `@ApiResponses` (200/400/401/403/404/409).
- Void: same shape, gate `shopAccessService.requireGroupAdmin()`, same reason → return existing, different reason → 409; reversing ledger row via `financialTransactionService` in the same transaction (see current completion write at :590-598 — `new CreateTransactionRequest(total, vatRate, "Order " + number, id)`).
- Delete guard: current `deleteOrder` (:659-667) has only `shopAccessService.require(order.getShopId(), ShopRole.SHOP_MANAGER)`; add status check → `OrderNotDeletableException`.
- `KITCHEN_STATUSES` (:63-64) += PENDING; frontend `lib/kitchen-orders-api.ts` :14 must agree (parity test).
- D-13 `submit`: DRAFT set at :144; run SUBMIT via `stateMachineService` + outbox `publishStateChange(DRAFT, PENDING)` as `PublicStorefrontService` COD path does (:1218-1221 per RESEARCH).

---

### `order/OrderLimits.java`, `storefront/ShopAvailabilityPolicy.java`

**Analog:** `core-java/src/main/java/uk/jtoye/core/order/FulfilmentPolicy.java` — the shared seam called by both writers (`resolve` :60, `requireDeliveryAddress` :82, `deliveryFeePennies` :108; public static, constant copy at :36). Callers per RESEARCH: `OrderService.java:188-191,265`, `PublicStorefrontService.java:975,1115` *(unverified caller)*. OrderLimits needs config values, so make it a `@Component` with `@Value("${jtoye.order-limits.max-line-quantity:50}")` etc. (style of `DsarIntakeRateLimiter` :93-119). Unit test analog: `order/FulfilmentPolicyTest.java`.

`ShopAvailabilityPolicy` replaces `PublicStorefrontService.validateShopIsOpen` (:1359-1395), whose fail-open arms are:
```java
if (hours == null || hours.isEmpty()) { return; }          // always open
...
if (!m.find()) { return; }                                  // unparseable → fail open
```
Keep the human wording ("{shop} is closed today. Please check opening hours…") inside the typed `shop-closed` problem (goods P2-CHA-14); `Europe/London` (`UK_ZONE`).

---

### Request DTO fields (expected prices, `submit`)

**Analog:** `storefront/dto/GuestOrderRequest.java` :87-94
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
@Min(value = 0, message = "…") @Max(value = 16383, message = "…")
@Schema(description = "… missing is refused 422 with code …; different … refused 409 …")
private Integer acknowledgedAllergenMask;
```
`@JsonInclude(NON_NULL)` is mandatory or `IdempotencyFingerprintGoldenTest` reds (`storefront.guest-order*`, `orders.create` rows).

---

### `security/ClientIpResolver.java` → bean with trusted CIDRs (D-19)

**Current** (static, first-hop trust — the bug):
```java
public static String resolveClientIp(HttpServletRequest request) {
    String forwarded = request.getHeader(XFF_HEADER);
    if (forwarded != null && !forwarded.isBlank()) {
        String firstHop = forwarded.split(",", 2)[0].trim();
        if (!firstHop.isEmpty()) { return firstHop; }
    }
    String remoteAddr = request.getRemoteAddr();
    return (remoteAddr != null && !remoteAddr.isBlank()) ? remoteAddr : UNKNOWN;
}
```
Callers to update: `security/RateLimitInterceptor.java:322`, `gdpr/DsarIntakeRateLimiter.java:146` (+ its javadoc :57-60). Config key `jtoye.security.trusted-proxies` (empty default) in `@Value` style. Test: rewrite `security/ClientIpResolverTest.java`.

---

### Staff invites (D-07, D-26)

**Token issue analog:** `gdpr/DsarAccessExportService.java` :543-561
```java
byte[] raw = new byte[TOKEN_BYTES];
SECURE_RANDOM.nextBytes(raw);
String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusHours(exportLinkTtlHours);
jdbcTemplate.update(UPSERT_SQL, UUID.randomUUID(), requestId, sha256Hex(token), payload, expiresAt);
return new IssuedToken(token, expiresAt);

static String sha256Hex(String token) {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
}
```
Tenant pinning for the anonymous accept: DsarAccessExportService javadoc :54-55 — `TenantContext.set`, own `TransactionTemplate`, GUC pinned, `SystemPrincipal.asSystem`, `TenantContext.clear()` in `finally`.

**Grant write analog:** `StaffManagementService.persistNewGrant` (:377-389)
```java
@Transactional(propagation = Propagation.REQUIRES_NEW)
public ShopStaff persistNewGrant(UUID tenantId, UUID userId, UUID shopId, ShopRole role, UUID createdBy) {
    ShopStaff row = new ShopStaff();
    row.setTenantId(tenantId); row.setUserId(userId); row.setShopId(shopId); row.setRole(role);
    row.setGrantSource(GrantSource.OPERATOR);
    row.setCreatedBy(createdBy);
    return shopStaffRepository.saveAndFlush(row);
}
```
Follow with `shopAccessService.evictMembershipAfterCommit(userId)` (:412-414). Do NOT call `grant()` as the invitee (it requires a directory row and `requireGroupAdmin()`). Same writer is reused for D-23 credential grants.

**Controller analog:** `security/access/StaffController.java` :43-115 — `@RestController @RequestMapping("/api/v1/staff") @Tag @SecurityRequirement(name = "bearer-jwt")`, 201 vs 200 replay via `ResponseEntity.status(result.created() ? CREATED : OK)`, `@ApiResponses` naming `shop-access-denied` / `last-group-admin`. Package is NOT in `WebConfig.API_V1_PACKAGES` (:40-41 javadoc).

**Mailer analog:** `gdpr/DsarVerificationMailer.java`
```java
@Value("${jtoye.gdpr.dsar.verify-base-url:http://localhost:3000/data-request/confirm}")
private String verifyBaseUrl;
...
if (!emailEnabled) { log.debug("event=dsar_verification_skipped reason=email_disabled …"); return; }
String link = verifyBaseUrl + "#token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
SimpleMailMessage message = new SimpleMailMessage();
message.setFrom(fromAddress); message.setTo(recipientEmail);
message.setSubject("…"); message.setText("""…%s…""".formatted(link, ttlHours));
try { mailSender.send(message); log.info("event=…_sent"); }   // never log address or token
catch (MailException e) { log.error("event=…_send_failed …"); }
```
Link host = frontend public URL (config key), not core's. CONTEXT says "sent through `EmailNotificationService`"; if the planner puts the method there, use its `@Async` + "No TenantContext" rule (javadoc :53) and its `emailEnabled` guard (:422-427).

---

### `tenant/keycloak/KeycloakAdminClient.java` additions (createUser, client CRUD, secret, SA user)

**Analog:** itself. Jackson 3 (`tools.jackson.databind.node.ObjectNode`) bodies only (:13-15, :46-55 lesson).
```java
public boolean deleteUser(String realm, String userId, String token) {
    try {
        restClient.delete()
                .uri("/admin/realms/{realm}/users/{id}", realm, userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity();
        return true;
    } catch (HttpClientErrorException.NotFound e) {
        return false;
    } catch (RestClientException e) {
        throw new KeycloakAdminException("Keycloak user delete failed for realm=" + realm, e);
    }
}
```
PUT with body: `setUserEnabled` :159-175 (`.contentType(MediaType.APPLICATION_JSON).body(payload)`). GET+parse: `findUsersByEmail` :187-215 (`jsonMapper.readTree`, `page.values()`, `instanceof ObjectNode`). Error messages name realm only, never email/password/secret. Test analog: `tenant/keycloak/KeycloakAdminClientTest.java` (MockRestServiceServer, by-content bodies).

---

### `security/access/ShopAccessService.java` (D-06, D-09)

- Flag :108-109 `@Value("${jtoye.access.strict-scoping:false}")` → `true`; posture log :169-181 inverts (WARN on explicit OFF override). Config sites: `application.yml:209`, `docker-compose.full-stack.yml:324`, `.env.example:156`, `k8s/base/configmap.yaml:391`, goldens.
- Effective access read: funnel through `isGroupAdminForUser` (:374-391) and `self().resolveMembership(userId)` exactly as `canAccessShop` (:463-482) does; include `isBootstrapAdmin` (:413-429) flag. Never derive in the browser.
- 11 test classes reset `setStrictScoping(false)` literally (RESEARCH list) — capture/restore booted value.

---

### Frontend: kitchen components

**Analog:** `frontend/components/dashboard/kitchen/allergy-note-block.tsx` — copy lives in a `lib/*-copy.ts` module (imports :7-13 from `@/lib/allergen-copy`), API helper in `lib/*-api.ts`, `inFlight` ref + `pending` state, 403 → specific copy, `role="alert"` inline error:
```tsx
} catch (e: unknown) {
  const status = (e as { response?: { status?: number } } | null)?.response?.status
  setError(status === 403 ? ALLERGY_NOTE_ACK_FORBIDDEN_COPY : ALLERGY_NOTE_ACK_FAILED_COPY)
} finally { inFlight.current = false; setPending(false) }
...
<p data-testid="allergy-note-text" className="mt-1 whitespace-pre-wrap break-words text-base font-semibold">{note}</p>
<Button type="button" onClick={markRead} disabled={pending} className="kds-press … min-h-11 …">
```
(UI-SPEC L6: customer note must NOT reuse the slate-900 fill; use `border-l-4 border-slate-400 bg-slate-50 px-4 py-2`.)

**Feed pill analog:** `kds-feed-status.tsx` — state table `PILL: Record<FeedState["status"], {dot,text,label,Icon}>`; extend with `signedOut`, `noAccess`, `stale`, `soundOff`, `muted`; precedence in `feed-state.ts#deriveFeedState`. Test: `components/dashboard/kitchen/__tests__/kds-feed-status.test.tsx`.

**Session lapse:** `frontend/lib/api-client.ts` :144-162 — replace bare `window.location.href = "/auth/signin"` with a dispatched cancellable `jtoye:session-lapsed` event, redirect only if unclaimed.

---

### Frontend: `app/invite/[token]/page.tsx` + client (B2)

**Analog:** `frontend/app/data-request/confirm/page.tsx` + `confirm-client.tsx`
```tsx
export const metadata: Metadata = {
  title: "Confirm your data request — J'Toye",
  description: "…",
  robots: { index: false, follow: false },
}
export default function ConfirmPage() { return (<PublicShell><ConfirmClient /></PublicShell>) }
```
Client: `Phase` union (`checking|ready|loading|failed|answered`), token read then `history.replaceState` to drop it, `inFlight` ref, `publicApiClient.post`, layout `mx-auto max-w-lg px-4 py-8 sm:py-12` card `rounded-xl border border-slate-200 bg-white p-6`, `h1 text-2xl font-semibold leading-tight`. Password field never stored client-side.

---

### Frontend: dialogs (void, reject, credential, invite cancel)

**Analog:** `frontend/components/dashboard/webhooks/ConfirmActionDialog.tsx` (props `open,onOpenChange,title,description,confirmLabel,destructive,onConfirm`; `pending` blocks close: `onOpenChange={(o) => !pending && onOpenChange(o)}`). UI-SPEC: move to `components/dashboard/confirm-action-dialog.tsx`, leave re-export, add required `cancelLabel`, confirm uses `Button` default variant (orange-700) not orange-500.

---

### Frontend: `BasketChangedPanel` + checkout reader

**Analog:** `readStaleAllergenProblem` in `frontend/app/shop/[slug]/checkout/page.tsx` (~:111-140) — branch on problem `type` (exact or `endsWith("/basket-changed")`) and 409 BEFORE `describeOrderError`; return `{changed:false}` or `{changed:true, changes|null}` for malformed bodies. Resubmit with the SAME Idempotency-Key; update cart prices without touching the ownership stamp.

---

### MCP tools

**Analog:** `mcp-server/src/tools/list-products.ts` :20-40 — raw Zod shape (not `z.object`), allow-listed `URLSearchParams` keys only (SSRF T-20-04), `coreGet`, `toToolError`, log tool name + status only:
```ts
export const listProductsInputSchema = {
  page: z.number().int().min(0).optional().describe("0-based page index"),
  size: z.number().int().min(1).max(100).optional().describe("page size (max 100)"),
};
...
if (args.page !== undefined) qs.set("page", String(args.page));
```
Add `shopId: z.string().uuid().optional()`, `availableOnly: z.boolean().optional()`. `create-order.ts` :172-189: add "The order is created as PENDING and appears in the kitchen's New lane" to `description`, send `submit: true`. Tests: sibling `*.test.ts` (vitest).

---

### Edge per-tenant limiter

**Analog:** `edge-go/cmd/edge/main.go` `rateLimiter` :166-200 (channel bucket, ctx-bound refill goroutine, `rateLimiterExemptPaths`). Untyped body today:
```go
c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{"error": "rate limit exceeded"})
```
New per-tenant middleware goes in the protected group after `jwtMiddleware.Validate()`; read `c.Get("tenant_id")` (set at `internal/middleware/jwt.go:213`); bounded map + lazy refill; respond `application/problem+json` with `type https://jtoye.uk/errors/rate-limited` and headers `Retry-After`, `X-RateLimit-Limit|Remaining|Reset` (names from `core-java/.../security/RateLimitInterceptor.java:54-57`).

---

## Shared Patterns

### Tenant isolation (every new table)
**Source:** `V71__trader_identity.sql` + `TraderIdentityRlsIntegrationTest.java`. ENABLE+FORCE, `current_tenant_id()` (never raw `::uuid` — `RlsContractTest.noPolicyUsesRawTenantGucCast`), NOSUPERUSER proof with 42501 arm. No new `EXEMPT_TABLES` entry.

### Authorization
**Source:** `ShopAccessService.require(shopId, ShopRole.X)` / `requireGroupAdmin()` (used at `OrderService.java:663`, `StaffManagementService.java:122,188,269`) + method `@PreAuthorize("hasAuthority('SCOPE_orders:write')")` (`OrderController.java:398`). Typed 403 `shop-access-denied` from `GlobalExceptionHandler.java:370-382` — the NoAccessPage/K2 trigger.

### Typed errors
**Source:** `GlobalExceptionHandler.java:755-804`. `type https://jtoye.uk/errors/<slug>`, `code` property, data properties for client re-render; exception classes in `uk.jtoye.core.exception`.

### Idempotency-Key fingerprint safety
**Source:** `GuestOrderRequest.java:87-94` `@JsonInclude(NON_NULL)`; gate `IdempotencyFingerprintGoldenTest.java`.

### Config-declared limits
**Source:** `DsarIntakeRateLimiter.java:93-119` (`@Value("${jtoye.….requests-per-hour:5}")`, bounded maps + overflow bucket). Every new key also lands in compose, `.env.example`, `k8s/base/configmap.yaml`, regenerated goldens, `check-env-contract.sh`.

### Post-commit side effects
**Source:** `ShopAccessService.evictMembershipAfterCommit` (via `StaffManagementService.evictAfterCommit` :412-414) and `REQUIRES_NEW` `persistNewGrant` :377-389. Use for directory upsert on reads and credential `last_used_at`.

### Secrets / PII in logs
**Source:** `DsarVerificationMailer` (never log address/token) and `KeycloakAdminClient` (messages name realm only). Applies to invite password, client secret, IP HMAC.

### Frontend copy + API split
**Source:** `allergy-note-block.tsx` imports copy from `@/lib/allergen-copy` and calls from `@/lib/allergy-note-api`. New surfaces: `lib/<feature>-copy.ts` + `lib/<feature>-api.ts`.

---

## No Analog Found

| File | Role | Data flow | Reason / fallback |
|---|---|---|---|
| `components/dashboard/kitchen/kds-sound.tsx` (`useKitchenAlert`, shared `AudioContext`) | hook | event-driven | Only `playBeep` (`kitchen/page.tsx:118-136`, per-call `new AudioContext()`) exists and is being removed; use UI-SPEC K1/K2 audio contract + RESEARCH 37-A.1 |
| `components/dashboard/shops/opening-schedule-editor.tsx` | component (form) | transform | Today is 7 free-text inputs (`shops/page.tsx:575-590`); no structured time-window editor in tree. Use UI-SPEC K4 |
| Keycloak client-management calls (create client, mappers, secret rotate) | client | request-response | Only user calls exist; HTTP pattern copies `KeycloakAdminClient`, payload shapes from RESEARCH 37-E.5 (several `[ASSUMED]`, prove live) |
| `realm-export.template.json` user-profile `tenant_id` admin-only attribute | config | — | No `UserProfileProvider` component in either realm; RESEARCH Pitfall 2/3 |
| Strict-scoping-default guard script (`scripts/check-strict-scoping-default.sh` or render invariant) | gate | — | Pick shape from `k8s/scripts/check-render-invariants.sh` *(not read this pass)*; must fail closed (exit 2 VOID) and be shown red |

## Metadata

**Analog search scope:** `core-java/src/main/java/uk/jtoye/core/{security,order,storefront,gdpr,notification,tenant,onboarding,common,exception}`, `core-java/src/main/resources/db/migration`, `core-java/src/test/java/uk/jtoye/core/{security,onboarding,order,tenant}`, `frontend/{app,components,lib}`, `mcp-server/src/tools`, `edge-go/cmd/edge`
**Files read for excerpts:** 22
**Pattern extraction date:** 2026-10-07
