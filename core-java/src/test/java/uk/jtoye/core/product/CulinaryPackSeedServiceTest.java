package uk.jtoye.core.product;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.order.OrderAllergenAggregator;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.CulinaryDishTemplateDto;
import uk.jtoye.core.product.dto.CulinaryPackDto;
import uk.jtoye.core.product.dto.CulinaryPackSeedRequest;
import uk.jtoye.core.product.dto.CulinaryPackSeedResult;
import uk.jtoye.core.product.dto.ProductDto;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CulinaryPackSeedService} (GTM-BLK-02).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>All 10 regional cuisine packs and 52 Pan-African / Caribbean dishes load from
 *       {@code seed/oaas_culinary_catalog.json}.</li>
 *   <li>Every dish template's {@code allergenMask} and {@code ingredientsText} produce ZERO
 *       {@link OrderAllergenAggregator} reconciliation flags (100% Natasha's Law / D-09 parity).</li>
 *   <li>Seeding a cuisine pack requires {@code SHOP_MANAGER} on the target shop, locks the shop row,
 *       creates products as drafts ({@code available = false}) by default with {@code USE_BY} and
 *       {@code shelfLifeDays} set so PPDS labels work immediately, and is idempotent on repeat calls.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CulinaryPackSeedServiceTest {

    @Mock
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ShopAccessService shopAccessService;

    private CulinaryPackSeedService seedService;
    private UUID tenantId;
    private UUID shopId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001");
        TenantContext.set(tenantId);
        seedService = new CulinaryPackSeedService(
                productService, productRepository, shopRepository, shopAccessService, JsonMapper.builder().build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("listPacks loads all 10 cuisine packs and 52 Pan-African & Caribbean dish templates with zero allergen reconciliation warnings")
    void listPacksLoadsAll52DishesWithZeroAllergenDiscrepancies() {
        List<CulinaryPackDto> packs = seedService.listPacks();

        assertThat(packs).hasSize(10);
        int totalDishes = packs.stream().mapToInt(CulinaryPackDto::dishCount).sum();
        assertThat(totalDishes).isEqualTo(52);

        for (CulinaryPackDto pack : packs) {
            assertThat(pack.dishes()).hasSize(pack.dishCount());
            for (CulinaryDishTemplateDto dish : pack.dishes()) {
                assertThat(dish.slug()).isNotBlank();
                assertThat(dish.name()).isNotBlank();
                assertThat(dish.ingredientsText()).isNotBlank();
                assertThat(dish.shelfLifeDays()).isPositive();
                assertThat(dish.allergenNames()).isEqualTo(AllergenCatalog.namesFor(dish.allergenMask()));
                assertThat(dish.mayContainNames()).isEqualTo(AllergenCatalog.namesFor(dish.mayContainMask()));

                // Every dish's inline **bold** emphasis must reconcile with its declared allergenMask
                var agg = OrderAllergenAggregator.aggregate(List.of(
                        new OrderAllergenAggregator.ItemAllergens(
                                dish.name(), dish.allergenMask(), dish.ingredientsText())));
                assertThat(agg.flags())
                        .as("Dish %s (%s) must have zero undeclared allergen emphasis warnings", dish.slug(), dish.name())
                        .isEmpty();
            }
        }
    }

    @Test
    @DisplayName("seedPack creates all 6 Nigerian dishes as drafts with PPDS shelf-life and skips already-existing dishes")
    void seedPackCreatesNigerianDishesAndSkipsExisting() {
        Shop shop = new Shop();
        when(shopRepository.lockByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));

        // Simulate "Jollof Rice" already existing in the target shop by title
        Product existingJollof = new Product();
        existingJollof.setSku("CUSTOM-JOLLOF-01");
        existingJollof.setTitle("jollof rice");
        when(productRepository.findByTenantIdAndShopIdOrderByDisplayOrderAscCreatedAtAsc(tenantId, shopId))
                .thenReturn(List.of(existingJollof));
        when(productRepository.existsByTenantIdAndSku(any(), any())).thenReturn(false);

        when(productService.createProduct(any(CreateProductRequest.class))).thenAnswer(inv -> {
            CreateProductRequest req = inv.getArgument(0);
            ProductDto dto = new ProductDto();
            dto.setId(UUID.randomUUID());
            dto.setSku(req.getSku());
            dto.setTitle(req.getTitle());
            return dto;
        });

        CulinaryPackSeedResult result = seedService.seedPack(new CulinaryPackSeedRequest(
                shopId, List.of("nigerian"), null, null, null));

        verify(shopAccessService).require(shopId, ShopRole.SHOP_MANAGER);
        assertThat(result.created()).isEqualTo(5);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.items()).hasSize(6);

        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(productService, times(5)).createProduct(captor.capture());

        for (CreateProductRequest createdReq : captor.getAllValues()) {
            assertThat(createdReq.getShopId()).isEqualTo(shopId);
            assertThat(createdReq.getAvailable()).isFalse(); // Draft by default until FBO confirms recipe
            assertThat(createdReq.getDurabilityType()).isEqualTo("USE_BY");
            assertThat(createdReq.getShelfLifeDays()).isPositive();
            assertThat(createdReq.getPricePennies()).isPositive();
            assertThat(createdReq.getSku()).startsWith("CKB-").endsWith("-A1B2C3D4");
        }
    }

    @Test
    @DisplayName("seedPack rejects an empty selection of cuisineKeys and dishSlugs with IllegalArgumentException")
    void seedPackRejectsEmptySelection() {
        assertThatThrownBy(() -> seedService.seedPack(
                new CulinaryPackSeedRequest(shopId, List.of(), List.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cuisineKeys or dishSlugs");
        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("seedPack prices soup and stew courses at 950p ('Soups & Stews') and breads at 550p ('Breads')")
    void seedPackAssignsMainPriceAndCategoryToSoupsStewsAndBreads() {
        Shop shop = new Shop();
        when(shopRepository.lockByIdAndTenantId(shopId, tenantId)).thenReturn(Optional.of(shop));
        when(productRepository.findByTenantIdAndShopIdOrderByDisplayOrderAscCreatedAtAsc(tenantId, shopId))
                .thenReturn(List.of());
        when(productRepository.existsByTenantIdAndSku(any(), any())).thenReturn(false);
        when(productService.createProduct(any(CreateProductRequest.class))).thenAnswer(inv -> {
            CreateProductRequest req = inv.getArgument(0);
            ProductDto dto = new ProductDto();
            dto.setId(UUID.randomUUID());
            dto.setSku(req.getSku());
            dto.setTitle(req.getTitle());
            return dto;
        });

        seedService.seedPack(new CulinaryPackSeedRequest(
                shopId, null, List.of("groundnut-soup", "guyanese-pepperpot", "injera"), null, null));

        ArgumentCaptor<CreateProductRequest> captor = ArgumentCaptor.forClass(CreateProductRequest.class);
        verify(productService, times(3)).createProduct(captor.capture());

        List<CreateProductRequest> created = captor.getAllValues();
        assertThat(created).extracting(CreateProductRequest::getCategory)
                .containsExactly("Soups & Stews", "Soups & Stews", "Breads");
        assertThat(created).extracting(CreateProductRequest::getPricePennies)
                .containsExactly(950L, 950L, 550L);
    }
}
