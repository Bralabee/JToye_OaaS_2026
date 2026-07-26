package uk.jtoye.core.product;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.ai.ImageAnalysisService;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.product.dto.ProductDto;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WR-04 (issue #280, plan 23-18). The products screen used to narrow to the selected shop
 * CLIENT-side, over one already-paginated page — so the count was really "matches on this
 * page", a shop whose rows began on page 2 rendered a false "No products in this shop", and
 * rows past page 1 were unreachable. The narrow now happens at the query.
 *
 * <p>This locks the controller half of that fix, mirroring
 * {@link uk.jtoye.core.order.OrderControllerShopFilterTest} which already covers the same
 * contract for orders.
 */
@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ProductControllerShopFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private ProductLabelService labelService;

    @MockitoBean
    private ImageAnalysisService imageAnalysisService;

    @MockitoBean
    private BulkImportService bulkImportService;

    private static final UUID SHOP_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private Page<ProductDto> singlePage(Pageable pageable) {
        ProductDto dto = new ProductDto();
        dto.setId(UUID.randomUUID());
        dto.setSku("WR04-SKU");
        dto.setShopId(SHOP_ID);
        return new PageImpl<>(List.of(dto), pageable, 1);
    }

    @Test
    @DisplayName("GET /products?shopId=... delegates to the shop-scoped query")
    void list_withShopId_delegatesToShopScopedQuery() throws Exception {
        when(productService.getProductsByShop(eq(SHOP_ID), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(1)));

        mockMvc.perform(get("/api/v1/products").param("shopId", SHOP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("WR04-SKU"))
                .andExpect(jsonPath("$.content[0].shopId").value(SHOP_ID.toString()));

        verify(productService).getProductsByShop(eq(SHOP_ID), any(Pageable.class));
        verify(productService, never()).getAllProducts(any(Pageable.class));
    }

    @Test
    @DisplayName("GET /products?shopId=... reports the SHOP's total, not the tenant's")
    void list_withShopId_totalElementsDescribesTheShopNotTheTenant() throws Exception {
        // The defect's signature: the pre-change screen showed a count derived from one
        // page of tenant-wide rows. The shop-scoped page owns its own totalElements, so a
        // shop with 37 products reports 37 even though only 20 fit on this page.
        when(productService.getProductsByShop(eq(SHOP_ID), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable p = inv.getArgument(1);
                    ProductDto dto = new ProductDto();
                    dto.setId(UUID.randomUUID());
                    dto.setSku("WR04-SKU");
                    dto.setShopId(SHOP_ID);
                    return new PageImpl<>(List.of(dto), p, 37);
                });

        mockMvc.perform(get("/api/v1/products").param("shopId", SHOP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(37));
    }

    @Test
    @DisplayName("GET /products without shopId keeps the unfiltered tenant-wide behaviour")
    void list_withoutShopId_keepsTenantWideBehaviour() throws Exception {
        when(productService.getAllProducts(any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(0)));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(productService).getAllProducts(any(Pageable.class));
        verify(productService, never()).getProductsByShop(any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /products?shopId=not-a-uuid is rejected with 400 before any service call")
    void list_malformedShopId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("shopId", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(productService, never()).getAllProducts(any(Pageable.class));
        verify(productService, never()).getProductsByShop(any(UUID.class), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /products/search?shopId=... passes the shop through to the search query")
    void search_withShopId_passesShopThrough() throws Exception {
        // Without this the switcher would silently stop applying the moment a vendor typed
        // two characters, because the screen swaps to /search at searchQuery.length >= 2.
        when(productService.search(eq("chick"), eq(SHOP_ID), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(2)));

        mockMvc.perform(get("/api/v1/products/search")
                        .param("q", "chick")
                        .param("shopId", SHOP_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("WR04-SKU"));

        verify(productService).search(eq("chick"), eq(SHOP_ID), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /products/search without shopId searches the whole grant set")
    void search_withoutShopId_searchesGrantSet() throws Exception {
        when(productService.search(eq("chick"), isNull(), any(Pageable.class)))
                .thenAnswer(inv -> singlePage(inv.getArgument(2)));

        mockMvc.perform(get("/api/v1/products/search").param("q", "chick"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sku").value("WR04-SKU"));

        verify(productService).search(eq("chick"), isNull(), any(Pageable.class));
    }
}
