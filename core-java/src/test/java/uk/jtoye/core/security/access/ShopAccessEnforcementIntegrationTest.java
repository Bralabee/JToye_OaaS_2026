package uk.jtoye.core.security.access;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.IncompleteLabelDataException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.order.OrderService;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.dto.CreateOrderRequest;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemRequest;
import uk.jtoye.core.product.ProductLabelService;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.testsupport.IntegrationTestSupport;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * VSA-02 enforcement proof — the shop-access gate (23-02 {@code ShopAccessService})
 * inserted across the shop/product/order services (23-03) is exercised end-to-end
 * against real Postgres 15 via Testcontainers, under {@code strict-scoping ON} so a
 * scoped user is genuinely confined (no JIT GROUP_ADMIN auto-provision).
 *
 * <p>Four behaviours (RESEARCH §9 VSA-02 rows):
 * <ol>
 *   <li><b>shopManagerScopedToOneShop</b> — a SHOP_MANAGER granted shop A gets the
 *       typed shop-access 403 (NOT the RLS 404) on a write to shop B, and CAN write
 *       to shop A.</li>
 *   <li><b>staffReadOnly</b> — a STAFF user granted shop A can run an order state
 *       transition on shop A but is denied a catalogue write on shop A.</li>
 *   <li><b>readScopeNarrows</b> — a SHOP_MANAGER granted only shop A sees only
 *       shop-A products/shops from the list endpoints (server-side query narrowing,
 *       not the tenant-wide set).</li>
 *   <li><b>errorTypeDistinctFrom404</b> — the shop-403 RFC 7807 {@code type} is
 *       provably distinct from the RLS 404 {@code type}.</li>
 * </ol>
 *
 * <p>Fixtures are seeded through the real service layer as a realm-admin (implicit
 * GROUP_ADMIN, bypasses the gate even under strict-scoping) so the graph is valid;
 * {@code shop_staff} grants for the scoped principals are seeded directly. Each test
 * uses a fresh {@code tenant} so RLS-scoped list counts are deterministic. Strict
 * scoping is toggled on the proxy-unwrapped bean via {@link ReflectionTestUtils}.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("testcontainers")
class ShopAccessEnforcementIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
            .withDatabaseName("jtoye_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerPostgresTestProperties(registry, postgres);
    }

    @Autowired private ShopAccessService shopAccessService;
    @Autowired private ShopService shopService;
    @Autowired private ProductService productService;
    @Autowired private ProductLabelService productLabelService;
    @Autowired private OrderService orderService;
    @Autowired private GlobalExceptionHandler exceptionHandler;
    @Autowired private JdbcTemplate jdbc;

    private ShopAccessService targetService;

    @AfterEach
    void tearDown() {
        setStrictScoping(false);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    // --- behaviours ------------------------------------------------------

    @Test
    void shopManagerScopedToOneShop_getsTypedShop403OnCrossShopWrite() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        // Write to the UNGRANTED shop B → typed shop-access 403, thrown by the gate
        // BEFORE any RLS lookup (so it is NEVER the RLS 404).
        CreateShopRequest req = shopRequest("Renamed");
        ShopAccessDeniedException denied =
                catchThrowableOfType(() -> shopService.updateShop(shopB, req), ShopAccessDeniedException.class);
        assertThat(denied).as("cross-shop write must throw the typed shop-access 403").isNotNull();

        ProblemDetail pd = exceptionHandler.handleShopAccessDenied(denied);
        assertThat(pd.getType())
                .as("the shop gate produces the /shop-access-denied type, not the RLS /not-found")
                .isEqualTo(URI.create("https://jtoye.uk/errors/shop-access-denied"));
        assertThat(pd.getStatus()).isEqualTo(403);

        // Write to the GRANTED shop A must NOT be denied by the gate.
        assertThatCode(() -> shopService.updateShop(shopA, shopRequest("A Renamed")))
                .as("a SHOP_MANAGER may write to its own granted shop")
                .doesNotThrowAnyException();
    }

    @Test
    void staffReadOnly_canTransitionOrderButNotWriteCatalogue() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID productA = seedProduct(tenant, shopA, "A-SKU-1");
        UUID orderId = seedDraftOrder(tenant, shopA, productA);

        UUID staff = UUID.randomUUID();
        grantShopStaff(tenant, staff, shopA, "STAFF");

        setStrictScoping(true);
        authenticate(staff, false);
        TenantContext.set(tenant);

        // STAFF floor: an order state transition on the granted shop succeeds
        // (DRAFT → PENDING) — the gate permits STAFF at the transition chokepoint.
        assertThatCode(() -> orderService.submitOrder(orderId))
                .as("STAFF may run an order state transition on a granted shop")
                .doesNotThrowAnyException();
        assertThat(orderService.getOrderById(orderId))
                .isPresent().get()
                .extracting(OrderDto::getStatus)
                .isEqualTo(OrderStatus.PENDING);

        // STAFF is denied a catalogue write on the same shop (SHOP_MANAGER floor).
        assertThatThrownBy(() -> productService.createProduct(productRequest(shopA, "NEW-SKU")))
                .as("STAFF must NOT be able to create a product")
                .isInstanceOf(ShopAccessDeniedException.class);
    }

    @Test
    void readScopeNarrows_scopedUserSeesOnlyGrantedShopRows() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        seedProduct(tenant, shopA, "A-SKU-1");
        seedProduct(tenant, shopA, "A-SKU-2");
        seedProduct(tenant, shopB, "B-SKU-1");

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        // Products: the tenant has 3, but the scoped user sees only shop-A's 2 —
        // narrowed at the QUERY (findByShopIdIn), not a post-hoc UI filter.
        var products = productService.getAllProducts(PageRequest.of(0, 50));
        assertThat(products.getTotalElements())
                .as("scoped user's product list == granted-shop count, not the tenant-wide count")
                .isEqualTo(2);
        assertThat(products.getContent()).allSatisfy(p ->
                assertThat(p.getShopId()).isEqualTo(shopA));

        // Shops: the tenant has 2, the scoped user sees only shop A.
        var shops = shopService.getAllShops(PageRequest.of(0, 50));
        assertThat(shops.getTotalElements())
                .as("scoped user's shop list == granted shops, not all tenant shops")
                .isEqualTo(1);
        assertThat(shops.getContent()).extracting(ShopDto::getId).containsExactly(shopA);
    }

    @Test
    void nullShopLegacyProducts_remainVisibleAndOpenableToScopedUser() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID aProduct = seedProduct(tenant, shopA, "A-SKU-1");
        UUID bProduct = seedProduct(tenant, shopB, "B-SKU-1");
        UUID legacyProduct = seedNullShopProduct(tenant, "LEGACY-1");

        UUID sm = UUID.randomUUID();
        grantShopStaff(tenant, sm, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        authenticate(sm, false);
        TenantContext.set(tenant);

        // WR-08 list: a scoped user sees their granted-shop product AND the legacy
        // shop_id IS NULL product (tenant-wide), but NOT another shop's product.
        var products = productService.getAllProducts(PageRequest.of(0, 50));
        assertThat(products.getContent()).extracting(ProductDto::getId)
                .as("scoped user sees granted-shop + tenant-wide null-shop products, not shop B's")
                .contains(aProduct, legacyProduct)
                .doesNotContain(bProduct);
        assertThat(products.getTotalElements()).isEqualTo(2);

        // WR-08 / CR-04 by-id: the null-shop product is READABLE — no 403, no 500.
        assertThatCode(() -> productService.getProductById(legacyProduct))
                .as("a null-shop product is openable by a granted scoped user (no 403, no 500)")
                .doesNotThrowAnyException();
        assertThat(productService.getProductById(legacyProduct)).isPresent();

        // The label route matches the read route: the gate no longer 403s a null-shop
        // product; it fails PPDS validation (422) instead, because it has no business identity.
        assertThatThrownBy(() -> productLabelService.generateLabel(legacyProduct))
                .as("null-shop label is a 422 data problem, never a shop-access 403")
                .isInstanceOf(IncompleteLabelDataException.class);
    }

    @Test
    void zeroGrantUser_seesEmpty_evenWhenTenantWideProductsExist() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        seedNullShopProduct(tenant, "LEGACY-1");

        UUID ungranted = UUID.randomUUID();  // NO shop_staff grant at all

        setStrictScoping(true);
        authenticate(ungranted, false);
        TenantContext.set(tenant);

        // The null-shop read policy must NOT widen deny-by-default: a user with zero
        // grants still sees nothing, even though tenant-wide products exist.
        var products = productService.getAllProducts(PageRequest.of(0, 50));
        assertThat(products.getTotalElements())
                .as("a zero-grant user sees NOTHING, not tenant-wide products")
                .isZero();
    }

    @Test
    void errorTypeDistinctFrom404_shop403TypeIsNotTheRls404Type() {
        ProblemDetail shop403 = exceptionHandler.handleShopAccessDenied(
                new ShopAccessDeniedException(UUID.randomUUID(), ShopRole.SHOP_MANAGER));
        ProblemDetail rls404 = exceptionHandler.handleResourceNotFound(
                new ResourceNotFoundException("Shop not found"));

        assertThat(shop403.getType())
                .as("the shop-access 403 type must be provably distinct from the RLS 404 type")
                .isNotEqualTo(rls404.getType());
        assertThat(shop403.getType()).isEqualTo(URI.create("https://jtoye.uk/errors/shop-access-denied"));
        assertThat(rls404.getType()).isEqualTo(URI.create("https://jtoye.uk/errors/not-found"));
        assertThat(shop403.getStatus()).isEqualTo(403);
        assertThat(rls404.getStatus()).isEqualTo(404);
    }

    // --- canAccessShop: explicit-identity STOMP shop-read gate (23-11 / CR-02) ----------

    @Test
    void canAccessShop_realmAdminBridgePermitsAnyShopEvenUngranted() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID realmAdmin = UUID.randomUUID();  // NO shop_staff grant at all

        setStrictScoping(true);
        TenantContext.set(tenant);

        assertThat(shopAccessService.canAccessShop(tenant, realmAdmin, true, shopA))
                .as("a realm-admin subscriber reads any shop feed (D-03 bridge); grants irrelevant")
                .isTrue();
    }

    @Test
    void canAccessShop_tenantWideGroupAdminPermitsAnyShop() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID ga = UUID.randomUUID();
        grantGroupAdmin(tenant, ga);

        setStrictScoping(true);
        TenantContext.set(tenant);

        assertThat(shopAccessService.canAccessShop(tenant, ga, false, shopA))
                .as("a tenant-wide GROUP_ADMIN row permits any shop")
                .isTrue();
    }

    @Test
    void canAccessShop_scopedUserPermittedOnGrantedShopDeniedOnUngranted() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID staff = UUID.randomUUID();
        grantShopStaff(tenant, staff, shopA, "STAFF");

        setStrictScoping(true);
        TenantContext.set(tenant);

        // Genuine-grant proof (23-11 RLS note): a REAL shop_staff row comes back through the
        // tenant-pinned read — NOT a fail-open empty result — so shop A is permitted and shop
        // B (ungranted) is denied. If the read had silently returned zero rows, the strict-ON
        // deny below would still pass but shop A would ALSO be denied, so asserting the
        // permitted side is what makes this test falsify a fail-open regression.
        assertThat(shopAccessService.canAccessShop(tenant, staff, false, shopA))
                .as("a STAFF grant on shop A permits reading shop A's kitchen feed")
                .isTrue();
        assertThat(shopAccessService.canAccessShop(tenant, staff, false, shopB))
                .as("the same STAFF user is denied an ungranted shop B — CR-02 closed")
                .isFalse();
    }

    @Test
    void canAccessShop_zeroGrantUnderStrictScopingOffIsImplicitGroupAdmin() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID ungranted = UUID.randomUUID();  // NO shop_staff grant

        setStrictScoping(false);  // day-one default
        TenantContext.set(tenant);

        assertThat(shopAccessService.canAccessShop(tenant, ungranted, false, shopA))
                .as("day-one preservation: a zero-grant user under strict-scoping OFF still reads any shop")
                .isTrue();
    }

    @Test
    void canAccessShop_zeroGrantUnderStrictScopingOnIsDenied() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID ungranted = UUID.randomUUID();

        setStrictScoping(true);
        TenantContext.set(tenant);

        assertThat(shopAccessService.canAccessShop(tenant, ungranted, false, shopA))
                .as("under strict-scoping ON a zero-grant user is denied")
                .isFalse();
    }

    @Test
    void canAccessShop_nullShopIsGroupAdminOnly() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID ga = UUID.randomUUID();
        grantGroupAdmin(tenant, ga);
        UUID scoped = UUID.randomUUID();
        grantShopStaff(tenant, scoped, shopA, "SHOP_MANAGER");

        setStrictScoping(true);
        TenantContext.set(tenant);

        assertThat(shopAccessService.canAccessShop(tenant, ga, false, null))
                .as("a GROUP_ADMIN may read a null-shop (tenant-wide) feed")
                .isTrue();
        assertThat(shopAccessService.canAccessShop(tenant, scoped, false, null))
                .as("a scoped (non-GROUP_ADMIN) user is denied a null-shop feed (mirrors require() WRITE)")
                .isFalse();
    }

    // --- #564: the kitchen board read, which is a NEW caller-supplied-shopId surface ------

    @Test
    void kitchenBoard_deniedOnUngrantedShop_andPermittedOnTheGrantedOne() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shopA = seedShop(tenant, "Shop A");
        UUID shopB = seedShop(tenant, "Shop B");
        UUID staff = UUID.randomUUID();
        grantShopStaff(tenant, staff, shopA, "STAFF");

        setStrictScoping(true);
        authenticate(staff, false);
        TenantContext.set(tenant);

        // GET /orders/kitchen takes shopId from the CALLER. That is a BOLA surface, so the
        // denial is asserted directly rather than inferred from the fact that the service
        // calls require() — the same shape as the FC-1 cross-tenant write this repo shipped.
        assertThatThrownBy(() -> orderService.getKitchenBoard(shopB, PageRequest.of(0, 50)))
                .as("a STAFF user must not read the kitchen board of an ungranted shop")
                .isInstanceOf(ShopAccessDeniedException.class);

        // The permitted side is load-bearing, not a courtesy: if the grant lookup were
        // failing open to "no rows", the deny above would still pass while shop A was
        // ALSO denied — a gate that rejects everything looks identical to a correct one
        // from the deny direction alone.
        assertThatCode(() -> orderService.getKitchenBoard(shopA, PageRequest.of(0, 50)))
                .as("the same STAFF user CAN read the board of the shop they are granted")
                .doesNotThrowAnyException();
    }

    @Test
    void kitchenBoard_returnsOnlyActiveOrdersWithItems_andNeverAnotherTenantsShop() {
        UUID tenant = UUID.randomUUID();
        ensureTenant(tenant);
        UUID shop = seedShop(tenant, "Board Shop");
        UUID product = seedProduct(tenant, shop, "BOARD-SKU-1");

        // Two orders that belong on a board, one that does not. The DRAFT is the control:
        // without it, "returns the active ones" is satisfied by returning EVERYTHING.
        UUID confirmed = seedDraftOrder(tenant, shop, product);
        UUID preparing = seedDraftOrder(tenant, shop, product);
        UUID draft = seedDraftOrder(tenant, shop, product);
        asRealmAdmin(tenant, () -> {
            orderService.submitOrder(confirmed);
            orderService.confirmOrder(confirmed);
            orderService.submitOrder(preparing);
            orderService.confirmOrder(preparing);
            orderService.startPreparation(preparing);
            return null;
        });

        UUID ga = UUID.randomUUID();
        grantGroupAdmin(tenant, ga);
        setStrictScoping(true);
        authenticate(ga, false);
        TenantContext.set(tenant);

        var board = orderService.getKitchenBoard(shop, PageRequest.of(0, 50));

        assertThat(board.getContent()).extracting(OrderDetailDto::getId)
                .as("the board carries the active tickets and NOT the DRAFT")
                .containsExactlyInAnyOrder(confirmed, preparing)
                .doesNotContain(draft);

        // The whole point of the endpoint: detail arrives WITH the order, so the client
        // never issues a follow-up request per ticket. Reading items here also proves the
        // fetch-join populated them — a lazy collection would have thrown by now, since
        // the service's transaction is closed.
        assertThat(board.getContent()).allSatisfy(dto ->
                assertThat(dto.getItems())
                        .as("every ticket arrives with its line items attached")
                        .isNotEmpty());

        // Refunds are batched, not left null. The KDS does not render them, but the DTO
        // carries the field and an unfilled field is a lie the next consumer inherits.
        assertThat(board.getContent()).allSatisfy(dto ->
                assertThat(dto.getRefunds()).as("refunds populated (empty, not null)").isNotNull());

        // CROSS-TENANT. This was written expecting an EMPTY page (RLS returning no rows)
        // and the real answer is stronger: `require()` runs FC-1's
        // `requireShopInCallerTenant` first, so a foreign shopId 404s before any order
        // query happens — the caller cannot even tell the shop exists. The endpoint
        // inherits that guard by reusing `require()` rather than reasoning out its own
        // check, which is the whole argument for reusing it. Asserted as measured, not as
        // predicted; a test written to the guess would have failed a CORRECT system.
        UUID otherTenant = UUID.randomUUID();
        ensureTenant(otherTenant);
        UUID foreignShop = seedShop(otherTenant, "Foreign Shop");
        UUID foreignProduct = seedProduct(otherTenant, foreignShop, "FOREIGN-SKU-1");
        UUID foreignOrder = seedDraftOrder(otherTenant, foreignShop, foreignProduct);
        asRealmAdmin(otherTenant, () -> {
            orderService.submitOrder(foreignOrder);
            orderService.confirmOrder(foreignOrder);
            return null;
        });

        authenticate(ga, false);
        TenantContext.set(tenant);
        assertThatThrownBy(() -> orderService.getKitchenBoard(foreignShop, PageRequest.of(0, 50)))
                .as("another tenant's shopId is not readable — FC-1 404s it before any order query")
                .isInstanceOf(ResourceNotFoundException.class);

        // Non-vacuity for the line above: the foreign board is genuinely non-empty when
        // read from inside its own tenant. Without this, `isZero()` passes just as well
        // against a seed that never worked, and the trap is already recorded in this repo
        // (an unpinned query under RLS returns 0 rows on a full table).
        authenticate(ga, true);
        TenantContext.set(otherTenant);
        assertThat(orderService.getKitchenBoard(foreignShop, PageRequest.of(0, 50)).getTotalElements())
                .as("the foreign board really does hold a ticket — so the zero above is isolation, not an empty seed")
                .isEqualTo(1);
    }

    // --- seeding helpers (run as a realm-admin: implicit GROUP_ADMIN, bypasses the gate) ---

    private UUID seedShop(UUID tenant, String name) {
        return asRealmAdmin(tenant, () -> shopService.createShop(shopRequest(name)).getId());
    }

    private UUID seedProduct(UUID tenant, UUID shopId, String sku) {
        return asRealmAdmin(tenant, () -> productService.createProduct(productRequest(shopId, sku)).getId());
    }

    /** Seed a legacy tenant-wide product (shop_id IS NULL) as a realm-admin (WR-08 fixtures). */
    private UUID seedNullShopProduct(UUID tenant, String sku) {
        return asRealmAdmin(tenant, () -> productService.createProduct(productRequest(null, sku)).getId());
    }

    private UUID seedDraftOrder(UUID tenant, UUID shopId, UUID productId) {
        return asRealmAdmin(tenant, () -> {
            CreateOrderRequest req = new CreateOrderRequest();
            req.setShopId(shopId);
            req.setCustomerName("Test Customer");
            OrderItemRequest item = new OrderItemRequest();
            item.setProductId(productId);
            item.setQuantity(1);
            req.setItems(List.of(item));
            return orderService.createOrder(req).getId();
        });
    }

    /** Run a seeding action as a fresh realm-admin under the given tenant, then clear the context. */
    private <T> T asRealmAdmin(UUID tenant, java.util.function.Supplier<T> action) {
        boolean prevStrict = currentStrictScoping();
        authenticate(UUID.randomUUID(), true);
        TenantContext.set(tenant);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
            setStrictScoping(prevStrict);
        }
    }

    /** shops/products/orders carry an FK to {@code tenants}; seed the (RLS-free) tenant row first. */
    private void ensureTenant(UUID tenant) {
        // tenants.name is UNIQUE — derive it from the id so parallel test tenants never collide.
        jdbc.update("INSERT INTO tenants (id, name) VALUES (?, ?) ON CONFLICT (id) DO NOTHING",
                tenant, "Enforcement Test Tenant " + tenant);
    }

    private void grantShopStaff(UUID tenant, UUID userId, UUID shopId, String role) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, userId, shopId, role);
    }

    /** Seed a tenant-wide GROUP_ADMIN grant (shop_id NULL) — the implicit-admin shape. */
    private void grantGroupAdmin(UUID tenant, UUID userId) {
        jdbc.update("INSERT INTO shop_staff (id, tenant_id, user_id, shop_id, role, created_at) "
                        + "VALUES (?, ?, ?, NULL, 'GROUP_ADMIN', now())",
                UUID.randomUUID(), tenant, userId);
    }

    // --- request builders ------------------------------------------------

    private CreateShopRequest shopRequest(String name) {
        CreateShopRequest req = new CreateShopRequest();
        req.setName(name);
        req.setAddress("1 Test Street, London");
        return req;
    }

    private CreateProductRequest productRequest(UUID shopId, String sku) {
        CreateProductRequest req = new CreateProductRequest();
        req.setSku(sku);
        req.setTitle("Product " + sku);
        req.setIngredientsText("Test ingredients");
        req.setAllergenMask(0);
        req.setPricePennies(500L);
        req.setShopId(shopId);
        return req;
    }

    // --- auth + strict-scoping plumbing (mirrors ShopAccessJitProvisionTest) ---

    private void authenticate(UUID sub, boolean realmAdmin) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(sub.toString())
                .claim("email", "user-" + sub + "@example.com")
                .claim("name", "Test User " + sub)
                .build();
        List<GrantedAuthority> authorities = realmAdmin
                ? List.of(new SimpleGrantedAuthority("ROLE_admin"))
                : List.of();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private ShopAccessService target() {
        if (targetService == null) {
            targetService = AopTestUtils.getTargetObject(shopAccessService);
        }
        return targetService;
    }

    private void setStrictScoping(boolean value) {
        ReflectionTestUtils.setField(target(), "strictScoping", value);
    }

    private boolean currentStrictScoping() {
        return Boolean.TRUE.equals(ReflectionTestUtils.getField(target(), "strictScoping"));
    }
}
