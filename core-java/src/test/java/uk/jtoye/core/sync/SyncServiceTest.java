package uk.jtoye.core.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;
import uk.jtoye.core.sync.dto.SyncItem;
import uk.jtoye.core.sync.dto.SyncItemResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ProductRepository productRepository;

    /**
     * Issue #483: the batch's cache invalidation moved from a cross-tenant
     * {@code @CacheEvict(allEntries = true)} annotation to per-touched-id calls on this
     * collaborator. Mocked here because this class is about the upsert mapping; the eviction
     * RADIUS is proven against a real cache by {@code SyncServiceTenantCacheScopeTest}.
     */
    @Mock
    private TenantCacheEvictor cacheEvictor;

    /**
     * QA-council 20260902 Cluster A (SEC-5 / #648): the per-shop write gate. Mocked so this class
     * can pin WHERE the gate is consulted (on the item's shop, BEFORE the SKU lookup and any write)
     * and that a denial writes nothing; the real decision against Postgres is
     * {@code SyncBatchAuthorizationIntegrationTest}.
     */
    @Mock
    private ShopAccessService shopAccessService;

    /** API-13: a shop CREATE is routed through the normal path (GROUP_ADMIN gate + slug + published=false). */
    @Mock
    private ShopService shopService;

    /** 37-40 (RWO-017): products are written through the dashboard's own create/update path. */
    @Mock
    private ProductService productService;

    /** 37-40 (RWO-037): one transaction per item. A mock manager lets the tests count them. */
    @Mock
    private PlatformTransactionManager transactionManager;

    private SyncService syncService;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID shopA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        syncService = new SyncService(shopRepository, productRepository, cacheEvictor, shopAccessService,
                shopService, productService, transactionManager);
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testProcessBatch_UpsertShop() {
        // Arrange — an unknown name is a CREATE, which goes through ShopService.createShop (API-13)
        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Collections.singletonList(shopItem("New Shop", "123 Street")))
                .build();

        // M1: the lookup is (name, tenant) — never name alone
        when(shopRepository.findByNameAndTenantId("New Shop", tenantId)).thenReturn(Optional.empty());
        UUID newShopId = UUID.randomUUID();
        when(shopService.createShop(any(CreateShopRequest.class))).thenReturn(shopDto(newShopId));

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getProcessedCount()).isEqualTo(1);
        assertThat(response.getResults()).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo(SyncItemResult.Outcome.CREATED);
            assertThat(r.getId()).isEqualTo(newShopId);
            assertThat(r.getKey()).isEqualTo("New Shop");
        });

        ArgumentCaptor<CreateShopRequest> captor = ArgumentCaptor.forClass(CreateShopRequest.class);
        verify(shopService).createShop(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("New Shop");
        assertThat(captor.getValue().getAddress()).isEqualTo("123 Street");
        // never a bare save: that is the path that could not set `slug` and 400'd on every attempt
        verify(shopRepository, never()).save(any(Shop.class));
    }

    @Test
    void testProcessBatch_UpdateShop_isGatedOnThatShopBeforeTheWrite() {
        UUID shopId = UUID.randomUUID();
        Shop existing = new Shop();
        existing.setId(shopId);
        existing.setName("Old Name");
        existing.setAddress("Old Address");
        when(shopRepository.findByNameAndTenantId("Old Name", tenantId)).thenReturn(Optional.of(existing));

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(shopItem("Old Name", "New Address")))
                .build());

        assertThat(response.getProcessedCount()).isEqualTo(1);
        assertThat(response.getResults().get(0).getOutcome()).isEqualTo(SyncItemResult.Outcome.UPDATED);
        InOrder inOrder = inOrder(shopAccessService, shopRepository);
        inOrder.verify(shopAccessService).require(shopId, ShopRole.SHOP_MANAGER);
        inOrder.verify(shopRepository).save(existing);
        assertThat(existing.getAddress()).isEqualTo("New Address");
        verify(cacheEvictor).evictEntityAfterCommit("shops", "getShopById", shopId);
        verify(shopService, never()).createShop(any());
    }

    /**
     * 37-40 (UXT-017): a new SKU is created through ProductService.createProduct, in the shop the item
     * names, after the gate on that shop.
     */
    @Test
    void testProcessBatch_CreateProduct_goesThroughProductServiceInTheItemsShop() {
        when(productRepository.findByTenantIdAndSkuAndShopId(tenantId, "SKU123", shopA)).thenReturn(Optional.empty());
        UUID newId = UUID.randomUUID();
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(productDto(newId));

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("SKU123", shopA, "Cool Product", "Water, **milk**", 1, 500L)))
                .build());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getProcessedCount()).isEqualTo(1);
        assertThat(response.getResults().get(0).getOutcome()).isEqualTo(SyncItemResult.Outcome.CREATED);
        assertThat(response.getResults().get(0).getId()).isEqualTo(newId);

        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        InOrder inOrder = inOrder(shopAccessService, productRepository, productService);
        inOrder.verify(shopAccessService).require(shopA, ShopRole.SHOP_MANAGER);
        inOrder.verify(productRepository).findByTenantIdAndSkuAndShopId(tenantId, "SKU123", shopA);
        inOrder.verify(productService).createProduct(captor.capture());
        CreateProductRequest sent = captor.getValue();
        assertThat(sent.getShopId()).isEqualTo(shopA);
        assertThat(sent.getSku()).isEqualTo("SKU123");
        assertThat(sent.getTitle()).isEqualTo("Cool Product");
        assertThat(sent.getIngredientsText()).isEqualTo("Water, **milk**");
        assertThat(sent.getAllergenMask()).isEqualTo(1);
        assertThat(sent.getPricePennies()).isEqualTo(500L);
        // never a direct repository write: the create rules (spans, defaults, stock) live in ProductService
        verify(productRepository, never()).save(any(Product.class));
    }

    /**
     * SEC-5 / #648: the gate is consulted on the item's shop, and it runs before the write — the order is
     * what makes "denied" mean "untouched". The update goes through ProductService.updateProduct and the
     * cached DTO is evicted after commit.
     */
    @Test
    void testProcessBatch_UpdateProduct_isGatedOnTheShopBeforeTheWrite() {
        UUID productId = UUID.randomUUID();
        Product existing = existingProduct(productId, "SKU-B", shopA);
        when(productRepository.findByTenantIdAndSkuAndShopId(tenantId, "SKU-B", shopA)).thenReturn(Optional.of(existing));

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("SKU-B", shopA, "Renamed", "Rice", 2, 700L)))
                .build());

        assertThat(response.getResults().get(0).getOutcome()).isEqualTo(SyncItemResult.Outcome.UPDATED);
        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        InOrder inOrder = inOrder(shopAccessService, productService);
        inOrder.verify(shopAccessService).require(shopA, ShopRole.SHOP_MANAGER);
        inOrder.verify(productService).updateProduct(eq(productId), captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Renamed");
        verify(cacheEvictor).evictEntityAfterCommit("products", "getProductById", productId);
    }

    /** A denial is reported for that item, nothing is looked up, written or evicted, and its transaction rolls back. */
    @Test
    void testProcessBatch_DeniedProductWrite_isRejectedAndWritesNothing() {
        UUID shopB = UUID.randomUUID();
        doThrow(new ShopAccessDeniedException(shopB, ShopRole.SHOP_MANAGER))
                .when(shopAccessService).require(shopB, ShopRole.SHOP_MANAGER);

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("B-SKU", shopB, "PWNED", "x", 1, 1L)))
                .build());

        assertThat(response.getResults()).singleElement().satisfies(r -> {
            assertThat(r.getOutcome()).isEqualTo(SyncItemResult.Outcome.REJECTED);
            assertThat(r.getCode()).isEqualTo(SyncItemResult.Code.SHOP_ACCESS_DENIED);
        });
        assertThat(response.getProcessedCount()).isZero();
        verify(productRepository, never()).findByTenantIdAndSkuAndShopId(any(), anyString(), any());
        verifyNoInteractions(productService);
        verify(cacheEvictor, never()).evictEntityAfterCommit(anyString(), anyString(), any(UUID.class));
        verify(transactionManager).rollback(any());
    }

    /** 37-40 (UXT-017): a product item without a shop is refused before the gate or any lookup. */
    @Test
    void testProcessBatch_ProductWithoutShop_isRejectedShopRequired() {
        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("NO-SHOP", null, "Title", "Rice", 0, 100L)))
                .build());

        assertThat(response.getResults().get(0).getCode()).isEqualTo(SyncItemResult.Code.SHOP_REQUIRED);
        assertThat(response.getResults().get(0).getKey()).isEqualTo("NO-SHOP");
        verifyNoInteractions(shopAccessService, productService, productRepository);
    }

    /**
     * PR #726 review M7: every {@link SyncItem} field is optional on the wire, and on an UPDATE an absent
     * title/ingredientsText means "unchanged". The update request carries nulls for them, which
     * ProductService.updateProduct maps IGNORE-null (ProductMapper.updateEntity).
     */
    @Test
    void testProcessBatch_UpdateProduct_absentTitleAndIngredientsAreLeftAlone() {
        UUID productId = UUID.randomUUID();
        Product existing = existingProduct(productId, "SKU-P", shopA);
        when(productRepository.findByTenantIdAndSkuAndShopId(tenantId, "SKU-P", shopA)).thenReturn(Optional.of(existing));

        syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(
                        SyncItem.builder().type("product").sku("SKU-P").shopId(shopA).pricePennies(750L).build()))
                .build());

        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(productService).updateProduct(eq(productId), captor.capture());
        assertThat(captor.getValue().getTitle()).as("an absent title means 'unchanged', not 'clear it'").isNull();
        assertThat(captor.getValue().getIngredientsText()).isNull();
        assertThat(captor.getValue().getPricePennies()).isEqualTo(750L);
    }

    /**
     * M7, the CREATE half: a brand-new SKU must carry every field CreateProductRequest requires. It is
     * refused for that item, naming the fields, before anything is written.
     */
    @Test
    void testProcessBatch_CreateProduct_withoutRequiredFieldsIsRejected_andWritesNothing() {
        when(productRepository.findByTenantIdAndSkuAndShopId(tenantId, "NEW-NO-TITLE", shopA)).thenReturn(Optional.empty());

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(
                        SyncItem.builder().type("product").sku("NEW-NO-TITLE").shopId(shopA).pricePennies(100L).build()))
                .build());

        SyncItemResult result = response.getResults().get(0);
        assertThat(result.getCode()).isEqualTo(SyncItemResult.Code.INVALID_FIELDS);
        assertThat(result.getMessage()).contains("title").contains("ingredientsText").contains("allergenMask")
                .doesNotContain("pricePennies");
        verify(productService, never()).createProduct(any());
    }

    @Test
    void testProcessBatch_MixedItems() {
        // Arrange
        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Arrays.asList(
                        shopItem("Shop 1", null),
                        productItem("SKU1", shopA, "Product 1", "Rice", 0, 100L),
                        SyncItem.builder().type("unknown").build()))
                .build();

        when(shopRepository.findByNameAndTenantId(anyString(), any(UUID.class))).thenReturn(Optional.empty());
        when(shopService.createShop(any(CreateShopRequest.class))).thenReturn(shopDto(UUID.randomUUID()));
        when(productRepository.findByTenantIdAndSkuAndShopId(any(), anyString(), any())).thenReturn(Optional.empty());
        when(productService.createProduct(any(CreateProductRequest.class))).thenReturn(productDto(UUID.randomUUID()));

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert — the unknown type is REPORTED, not skipped, and the batch says so
        assertThat(response.getProcessedCount()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo("PARTIAL");
        assertThat(response.getResults()).extracting(SyncItemResult::getIndex).containsExactly(0, 1, 2);
        assertThat(response.getResults().get(2).getCode()).isEqualTo(SyncItemResult.Code.UNKNOWN_TYPE);
        verify(shopService, times(1)).createShop(any(CreateShopRequest.class));
        verify(productService, times(1)).createProduct(any(CreateProductRequest.class));
        // one transaction per shop/product item; the unknown type never opens one
        verify(transactionManager, times(2)).getTransaction(any());
    }

    /** UXT-037: a batch in which no item was written says REJECTED, never SUCCESS or PARTIAL. */
    @Test
    void testProcessBatch_NothingWritten_isRejected() {
        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Arrays.asList(
                        SyncItem.builder().type("unknown").build(),
                        productItem("NO-SHOP", null, "T", "Rice", 0, 1L)))
                .build());

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(response.getProcessedCount()).isZero();
        assertThat(response.getResults()).hasSize(2);
    }

    /** An empty batch refused nothing and wrote nothing: SUCCESS with no results (nothing was skipped). */
    @Test
    void testProcessBatch_Empty_isSuccessWithNoResults() {
        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder().items(java.util.List.of()).build());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getResults()).isEmpty();
        verifyNoInteractions(transactionManager);
    }

    // ---- item builders (the typed SyncItem replaces the old Map<String,Object>; API-2) ----

    private static SyncItem shopItem(String name, String address) {
        return SyncItem.builder().type("shop").name(name).address(address).build();
    }

    private static SyncItem productItem(String sku, UUID shopId, String title, String ingredients, int mask, long price) {
        return SyncItem.builder().type("product").sku(sku).shopId(shopId).title(title)
                .ingredientsText(ingredients).allergenMask(mask).pricePennies(price).build();
    }

    private static Product existingProduct(UUID id, String sku, UUID shopId) {
        Product existing = new Product();
        ReflectionTestUtils.setField(existing, "id", id);
        existing.setSku(sku);
        existing.setShopId(shopId);
        existing.setTitle("Original");
        return existing;
    }

    private static ShopDto shopDto(UUID id) {
        ShopDto dto = new ShopDto();
        dto.setId(id);
        return dto;
    }

    private static ProductDto productDto(UUID id) {
        ProductDto dto = new ProductDto();
        dto.setId(id);
        return dto;
    }
}
