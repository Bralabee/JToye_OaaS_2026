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
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.shop.ShopService;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;
import uk.jtoye.core.sync.dto.SyncItem;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
     * can pin WHERE the gate is consulted (on the resolved product's owning shop, BEFORE any
     * setter runs) and that a denial saves nothing; the real decision against Postgres is
     * {@code SyncBatchAuthorizationIntegrationTest}.
     */
    @Mock
    private ShopAccessService shopAccessService;

    /** API-13: a shop CREATE is routed through the normal path (GROUP_ADMIN gate + slug + published=false). */
    @Mock
    private ShopService shopService;

    private SyncService syncService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        syncService = new SyncService(shopRepository, productRepository, cacheEvictor, shopAccessService, shopService);
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

        when(shopRepository.findByName("New Shop")).thenReturn(Optional.empty());

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getProcessedCount()).isEqualTo(1);

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
        when(shopRepository.findByName("Old Name")).thenReturn(Optional.of(existing));

        BatchSyncResponse response = syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(shopItem("Old Name", "New Address")))
                .build());

        assertThat(response.getProcessedCount()).isEqualTo(1);
        InOrder inOrder = inOrder(shopAccessService, shopRepository);
        inOrder.verify(shopAccessService).require(shopId, ShopRole.SHOP_MANAGER);
        inOrder.verify(shopRepository).save(existing);
        assertThat(existing.getAddress()).isEqualTo("New Address");
        verify(cacheEvictor).evictEntityAfterCommit("shops", "getShopById", shopId);
        verify(shopService, never()).createShop(any());
    }

    @Test
    void testProcessBatch_UpsertProduct() {
        // Arrange
        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("SKU123", "Cool Product", "Water, Sugar", 1, 500L)))
                .build();

        when(productRepository.findBySku("SKU123")).thenReturn(Optional.empty());

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getProcessedCount()).isEqualTo(1);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());

        Product savedProduct = productCaptor.getValue();
        assertThat(savedProduct.getSku()).isEqualTo("SKU123");
        assertThat(savedProduct.getTitle()).isEqualTo("Cool Product");
        assertThat(savedProduct.getPricePennies()).isEqualTo(500L);
        assertThat(savedProduct.getTenantId()).isEqualTo(tenantId);
        // a CREATE has no owning shop: it is a tenant-wide resource, gated GROUP_ADMIN-only (CR-04)
        verify(shopAccessService).require(isNull(), org.mockito.ArgumentMatchers.eq(ShopRole.SHOP_MANAGER));
    }

    /**
     * SEC-5 / #648: the gate is consulted on the RESOLVED product's owning shop, and it runs
     * before any setter — the order is what makes "denied" mean "untouched".
     */
    @Test
    void testProcessBatch_UpdateProduct_isGatedOnTheOwningShopBeforeTheWrite() {
        UUID shopId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Product existing = new Product();
        ReflectionTestUtils.setField(existing, "id", productId);
        existing.setSku("SKU-B");
        existing.setShopId(shopId);
        existing.setTitle("Original");
        when(productRepository.findBySku("SKU-B")).thenReturn(Optional.of(existing));

        syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("SKU-B", "Renamed", "Rice", 2, 700L)))
                .build());

        InOrder inOrder = inOrder(shopAccessService, productRepository);
        inOrder.verify(shopAccessService).require(shopId, ShopRole.SHOP_MANAGER);
        inOrder.verify(productRepository).save(existing);
        assertThat(existing.getTitle()).isEqualTo("Renamed");
        verify(cacheEvictor).evictEntityAfterCommit("products", "getProductById", productId);
    }

    /** A denial propagates as the typed shop-access exception and NOTHING is saved or evicted. */
    @Test
    void testProcessBatch_DeniedProductWrite_savesNothing() {
        UUID shopB = UUID.randomUUID();
        Product foreign = new Product();
        ReflectionTestUtils.setField(foreign, "id", UUID.randomUUID());
        foreign.setSku("B-SKU");
        foreign.setShopId(shopB);
        foreign.setTitle("Untouched");
        when(productRepository.findBySku("B-SKU")).thenReturn(Optional.of(foreign));
        doThrow(new ShopAccessDeniedException(shopB, ShopRole.SHOP_MANAGER))
                .when(shopAccessService).require(shopB, ShopRole.SHOP_MANAGER);

        assertThatThrownBy(() -> syncService.processBatch(BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem("B-SKU", "PWNED", "x", 1, 1L)))
                .build()))
                .isInstanceOf(ShopAccessDeniedException.class);

        assertThat(foreign.getTitle()).isEqualTo("Untouched");
        verify(productRepository, never()).save(any(Product.class));
        verify(cacheEvictor, never()).evictEntityAfterCommit(anyString(), anyString(), any(UUID.class));
    }

    @Test
    void testProcessBatch_MixedItems() {
        // Arrange
        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Arrays.asList(
                        shopItem("Shop 1", null),
                        SyncItem.builder().type("product").sku("SKU1").build(),
                        SyncItem.builder().type("unknown").build()))
                .build();

        when(shopRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(productRepository.findBySku(anyString())).thenReturn(Optional.empty());

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert — the unknown type is skipped, the shop create goes through ShopService, the product is saved
        assertThat(response.getProcessedCount()).isEqualTo(2);
        verify(shopService, times(1)).createShop(any(CreateShopRequest.class));
        verify(productRepository, times(1)).save(any(Product.class));
    }

    // ---- item builders (the typed SyncItem replaces the old Map<String,Object>; API-2) ----

    private static SyncItem shopItem(String name, String address) {
        return SyncItem.builder().type("shop").name(name).address(address).build();
    }

    private static SyncItem productItem(String sku, String title, String ingredients, int mask, long price) {
        return SyncItem.builder().type("product").sku(sku).title(title)
                .ingredientsText(ingredients).allergenMask(mask).pricePennies(price).build();
    }
}
