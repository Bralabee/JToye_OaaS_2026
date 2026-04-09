package uk.jtoye.core.storefront;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;
import uk.jtoye.core.storefront.dto.ShopConfigDto;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicStorefrontController.class)
@AutoConfigureMockMvc(addFilters = false)
class PublicStorefrontControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicStorefrontService storefrontService;

    @MockitoBean
    private ReviewService reviewService;

    // --- Helper methods ---

    private PublicShopDto buildShopDto(String slug, String name) {
        PublicShopDto dto = new PublicShopDto();
        dto.setSlug(slug);
        dto.setName(name);
        dto.setDescription("A test shop");
        return dto;
    }

    private PublicProductDto buildProductDto(String title, long pricePennies, String category) {
        PublicProductDto dto = new PublicProductDto();
        dto.setTitle(title);
        dto.setPricePennies(pricePennies);
        dto.setCategory(category);
        dto.setInStock(true);
        return dto;
    }

    // --- Tests ---

    @Test
    void listShops_returns200WithPaginatedShops() throws Exception {
        PublicShopDto shop = buildShopDto("test-shop", "Test Shop");
        when(storefrontService.listPublishedShops(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shop)));

        mockMvc.perform(get("/public/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Test Shop"))
                .andExpect(jsonPath("$.content[0].slug").value("test-shop"));

        verify(storefrontService).listPublishedShops(any(Pageable.class));
    }

    @Test
    void searchShops_delegatesToSearchMethod() throws Exception {
        PublicShopDto shop = buildShopDto("jollof-palace", "Jollof Palace");
        when(storefrontService.searchPublishedShops(eq("jollof"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shop)));

        mockMvc.perform(get("/public/shops").param("q", "jollof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Jollof Palace"));

        verify(storefrontService).searchPublishedShops(eq("jollof"), any(Pageable.class));
    }

    @Test
    void getShopProducts_returns200WithCategoryMap() throws Exception {
        PublicProductDto product = buildProductDto("Jollof Rice", 1200L, "Mains");
        when(storefrontService.getShopProducts("test-shop"))
                .thenReturn(Map.of("Mains", List.of(product)));

        mockMvc.perform(get("/public/shops/test-shop/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Mains[0].title").value("Jollof Rice"))
                .andExpect(jsonPath("$.Mains[0].pricePennies").value(1200));

        verify(storefrontService).getShopProducts("test-shop");
    }

    @Test
    void getShopBySlug_returns200WithShopDetail() throws Exception {
        PublicShopDto shop = buildShopDto("test-shop", "Test Shop");
        when(storefrontService.getShopBySlug("test-shop")).thenReturn(shop);

        mockMvc.perform(get("/public/shops/test-shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test Shop"))
                .andExpect(jsonPath("$.slug").value("test-shop"));

        verify(storefrontService).getShopBySlug("test-shop");
    }

    @Test
    void getShopBySlug_nonexistent_returns404() throws Exception {
        when(storefrontService.getShopBySlug("nonexistent"))
                .thenThrow(new ResourceNotFoundException("Shop not found: nonexistent"));

        mockMvc.perform(get("/public/shops/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getShopConfig_returns200WithConfigData() throws Exception {
        ShopConfigDto config = new ShopConfigDto();
        config.setAnnouncements(List.of("Free delivery this week!"));
        config.setFeaturedProducts(List.of());
        config.setActivePromotions(List.of());
        when(storefrontService.getShopConfig("test-shop")).thenReturn(config);

        mockMvc.perform(get("/public/shops/test-shop/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcements[0]").value("Free delivery this week!"))
                .andExpect(jsonPath("$.featuredProducts").isArray())
                .andExpect(jsonPath("$.activePromotions").isArray());

        verify(storefrontService).getShopConfig("test-shop");
    }

    @Test
    void getShopProducts_nonexistentShop_returns404() throws Exception {
        when(storefrontService.getShopProducts("ghost-shop"))
                .thenThrow(new ResourceNotFoundException("Shop not found: ghost-shop"));

        mockMvc.perform(get("/public/shops/ghost-shop/products"))
                .andExpect(status().isNotFound());
    }
}
