package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.geo.PostcodeGeocoder;
import uk.jtoye.core.review.ReviewService;
import uk.jtoye.core.shop.DiscountType;
import uk.jtoye.core.storefront.dto.PublicAnnouncementDto;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicPromotionDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;
import uk.jtoye.core.storefront.dto.ShopConfigDto;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    // Issue #179: controller dependency for /public/orders/mine — not exercised
    // here (see PublicStorefrontControllerMyOrdersTest) but required to build
    // the controller in this slice.
    @MockitoBean
    private uk.jtoye.core.security.CustomerJwtVerifier customerJwtVerifier;

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
                .thenReturn(new PublicStorefrontService.SearchOutcome(
                        new PageImpl<>(List.of(shop)), SearchInterpretation.text()));

        mockMvc.perform(get("/public/shops").param("q", "jollof"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Jollof Palace"));

        verify(storefrontService).searchPublishedShops(eq("jollof"), any(Pageable.class));
    }

    // --- 33-08 / #619: the server-asserted search interpretation ---

    @Test
    @DisplayName("CA-C(api): a text match carries the literal header 'text' — never a proximity claim")
    void searchShops_textMatchDisclosesText() throws Exception {
        PublicShopDto shop = buildShopDto("jollof-palace", "Jollof Palace");
        when(storefrontService.searchPublishedShops(eq("jollof"), any(Pageable.class)))
                .thenReturn(new PublicStorefrontService.SearchOutcome(
                        new PageImpl<>(List.of(shop)), SearchInterpretation.text()));

        mockMvc.perform(get("/public/shops").param("q", "jollof"))
                .andExpect(status().isOk())
                .andExpect(header().string(SearchInterpretation.HEADER, "text"));
    }

    @Test
    @DisplayName("a proximity match publishes the full grammar 33-09's parser consumes")
    void searchShops_proximityMatchDisclosesTheGrammar() throws Exception {
        PublicShopDto shop = buildShopDto("peckham-jollof", "Peckham Jollof Co.");
        when(storefrontService.searchPublishedShops(eq("SE22"), any(Pageable.class)))
                .thenReturn(new PublicStorefrontService.SearchOutcome(
                        new PageImpl<>(List.of(shop)),
                        SearchInterpretation.proximity("SE22", PostcodeGeocoder.Precision.DISTRICT, 5.0)));

        mockMvc.perform(get("/public/shops").param("q", "SE22"))
                .andExpect(status().isOk())
                .andExpect(header().string(SearchInterpretation.HEADER,
                        "proximity; postcode=SE22; precision=district; radiusKm=5.0"));
    }

    @Test
    @DisplayName("the plain listing and the lat/lon distance path carry NO interpretation header")
    void unsearchedPathsCarryNoHeader() throws Exception {
        PublicShopDto shop = buildShopDto("test-shop", "Test Shop");
        when(storefrontService.listPublishedShops(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shop)));
        when(storefrontService.listPublishedShopsNear(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(shop)));

        // The header answers "how did you read my q?". With no q there is no question, and a
        // header asserting "text" on a plain listing would be an answer to one nobody asked.
        mockMvc.perform(get("/public/shops"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(SearchInterpretation.HEADER));

        mockMvc.perform(get("/public/shops").param("lat", "51.47").param("lon", "-0.07"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(SearchInterpretation.HEADER));
    }

    @Test
    @DisplayName("q combined with a coordinate is STILL a typed 400 — a derived coordinate is not a "
            + "caller-supplied one")
    void searchCombinedWithCoordinateIsStillATyped400() throws Exception {
        mockMvc.perform(get("/public/shops")
                        .param("q", "SE22").param("lat", "51.47").param("lon", "-0.07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://jtoye.uk/errors/invalid-argument"));
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
        config.setAnnouncements(List.of(new ShopConfigDto.AnnouncementSummary("Free delivery this week!", null, null)));
        config.setFeaturedProducts(List.of());
        config.setActivePromotions(List.of());
        when(storefrontService.getShopConfig("test-shop")).thenReturn(config);

        mockMvc.perform(get("/public/shops/test-shop/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcements[0].title").value("Free delivery this week!"))
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

    @Test
    void getShopPromotions_returns200WithActivePromotions() throws Exception {
        PublicPromotionDto promo = new PublicPromotionDto();
        promo.setLabel("Lunch special");
        promo.setDiscountType(DiscountType.PERCENTAGE);
        promo.setDiscountPercent(10);
        promo.setCategory("Mains");
        when(storefrontService.getActivePromotions("test-shop")).thenReturn(List.of(promo));

        mockMvc.perform(get("/public/shops/test-shop/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("Lunch special"))
                .andExpect(jsonPath("$[0].discountPercent").value(10))
                .andExpect(jsonPath("$[0].category").value("Mains"));

        verify(storefrontService).getActivePromotions("test-shop");
    }

    @Test
    void getShopPromotions_nonexistent_returns404() throws Exception {
        when(storefrontService.getActivePromotions("ghost"))
                .thenThrow(new ResourceNotFoundException("Shop not found: ghost"));

        mockMvc.perform(get("/public/shops/ghost/promotions"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getShopAnnouncements_returns200WithActiveAnnouncements() throws Exception {
        PublicAnnouncementDto announcement = new PublicAnnouncementDto();
        announcement.setTitle("Closed Sunday");
        announcement.setBody("Back Monday");
        when(storefrontService.getActiveAnnouncements("test-shop"))
                .thenReturn(List.of(announcement));

        mockMvc.perform(get("/public/shops/test-shop/announcements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Closed Sunday"))
                .andExpect(jsonPath("$[0].body").value("Back Monday"));

        verify(storefrontService).getActiveAnnouncements("test-shop");
    }

    @Test
    void getShopAnnouncements_nonexistent_returns404() throws Exception {
        when(storefrontService.getActiveAnnouncements("ghost"))
                .thenThrow(new ResourceNotFoundException("Shop not found: ghost"));

        mockMvc.perform(get("/public/shops/ghost/announcements"))
                .andExpect(status().isNotFound());
    }
}
