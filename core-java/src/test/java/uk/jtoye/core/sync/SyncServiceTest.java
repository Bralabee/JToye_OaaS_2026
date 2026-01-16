package uk.jtoye.core.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.sync.dto.BatchSyncRequest;
import uk.jtoye.core.sync.dto.BatchSyncResponse;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ProductRepository productRepository;

    private SyncService syncService;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        syncService = new SyncService(shopRepository, productRepository);
        TenantContext.set(tenantId);
    }

    @Test
    void testProcessBatch_UpsertShop() {
        // Arrange
        Map<String, Object> shopItem = new HashMap<>();
        shopItem.put("type", "shop");
        shopItem.put("name", "New Shop");
        shopItem.put("address", "123 Street");

        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Collections.singletonList(shopItem))
                .build();

        when(shopRepository.findByName("New Shop")).thenReturn(Optional.empty());

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert
        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getProcessedCount()).isEqualTo(1);

        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        verify(shopRepository).save(shopCaptor.capture());

        Shop savedShop = shopCaptor.getValue();
        assertThat(savedShop.getName()).isEqualTo("New Shop");
        assertThat(savedShop.getAddress()).isEqualTo("123 Street");
        assertThat(savedShop.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void testProcessBatch_UpsertProduct() {
        // Arrange
        Map<String, Object> productItem = new HashMap<>();
        productItem.put("type", "product");
        productItem.put("sku", "SKU123");
        productItem.put("title", "Cool Product");
        productItem.put("ingredientsText", "Water, Sugar");
        productItem.put("allergenMask", 1);
        productItem.put("pricePennies", 500);

        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Collections.singletonList(productItem))
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
    }

    @Test
    void testProcessBatch_MixedItems() {
        // Arrange
        Map<String, Object> shopItem = new HashMap<>();
        shopItem.put("type", "shop");
        shopItem.put("name", "Shop 1");

        Map<String, Object> productItem = new HashMap<>();
        productItem.put("type", "product");
        productItem.put("sku", "SKU1");

        Map<String, Object> unknownItem = new HashMap<>();
        unknownItem.put("type", "unknown");

        BatchSyncRequest request = BatchSyncRequest.builder()
                .items(Arrays.asList(shopItem, productItem, unknownItem))
                .build();

        when(shopRepository.findByName(anyString())).thenReturn(Optional.empty());
        when(productRepository.findBySku(anyString())).thenReturn(Optional.empty());

        // Act
        BatchSyncResponse response = syncService.processBatch(request);

        // Assert
        assertThat(response.getProcessedCount()).isEqualTo(2);
        verify(shopRepository, times(1)).save(any(Shop.class));
        verify(productRepository, times(1)).save(any(Product.class));
    }
}
