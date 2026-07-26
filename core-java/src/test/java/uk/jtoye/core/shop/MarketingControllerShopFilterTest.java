package uk.jtoye.core.shop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.shop.dto.AnnouncementDto;
import uk.jtoye.core.shop.dto.PromotionDto;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WR-04 (issue #280, plan 23-18) — the marketing screen half.
 *
 * <p>"Marketing" is a two-tab UI over TWO independent domains, so the defect had two
 * endpoints, not one. Both narrowed to the selected shop client-side over a single
 * already-paginated page; both now narrow at the query.
 *
 * <p>Before this, neither {@code PromotionController} nor {@code AnnouncementController} had
 * any controller-level test at all.
 */
class MarketingControllerShopFilterTest {

    private static final UUID SHOP_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Nested
    @WebMvcTest(PromotionController.class)
    @AutoConfigureMockMvc(addFilters = false)
    @Import(GlobalExceptionHandler.class)
    class Promotions {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private PromotionService promotionService;

        private Page<PromotionDto> page(Pageable pageable, long total) {
            PromotionDto dto = new PromotionDto();
            dto.setId(UUID.randomUUID());
            dto.setShopId(SHOP_ID);
            return new PageImpl<>(List.of(dto), pageable, total);
        }

        @Test
        @DisplayName("GET /promotions?shopId=... delegates to the shop-scoped query and reports the shop's total")
        void list_withShopId_delegatesAndReportsShopTotal() throws Exception {
            when(promotionService.getPromotionsByShop(eq(SHOP_ID), any(Pageable.class)))
                    .thenAnswer(inv -> page(inv.getArgument(1), 24));

            mockMvc.perform(get("/api/v1/promotions").param("shopId", SHOP_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].shopId").value(SHOP_ID.toString()))
                    .andExpect(jsonPath("$.totalElements").value(24));

            verify(promotionService).getPromotionsByShop(eq(SHOP_ID), any(Pageable.class));
            verify(promotionService, never()).getAllPromotions(any(Pageable.class));
        }

        @Test
        @DisplayName("GET /promotions without shopId keeps the unfiltered tenant-wide behaviour")
        void list_withoutShopId_keepsTenantWideBehaviour() throws Exception {
            when(promotionService.getAllPromotions(any(Pageable.class)))
                    .thenAnswer(inv -> page(inv.getArgument(0), 1));

            mockMvc.perform(get("/api/v1/promotions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(promotionService).getAllPromotions(any(Pageable.class));
            verify(promotionService, never()).getPromotionsByShop(any(UUID.class), any(Pageable.class));
        }

        @Test
        @DisplayName("GET /promotions?shopId=not-a-uuid is rejected with 400 before any service call")
        void list_malformedShopId_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/promotions").param("shopId", "not-a-uuid"))
                    .andExpect(status().isBadRequest());

            verify(promotionService, never()).getAllPromotions(any(Pageable.class));
            verify(promotionService, never()).getPromotionsByShop(any(UUID.class), any(Pageable.class));
        }
    }

    @Nested
    @WebMvcTest(AnnouncementController.class)
    @AutoConfigureMockMvc(addFilters = false)
    @Import(GlobalExceptionHandler.class)
    class Announcements {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private AnnouncementService announcementService;

        private Page<AnnouncementDto> page(Pageable pageable, long total) {
            AnnouncementDto dto = new AnnouncementDto();
            dto.setId(UUID.randomUUID());
            dto.setShopId(SHOP_ID);
            return new PageImpl<>(List.of(dto), pageable, total);
        }

        @Test
        @DisplayName("GET /announcements?shopId=... delegates to the shop-scoped query and reports the shop's total")
        void list_withShopId_delegatesAndReportsShopTotal() throws Exception {
            // The total MUST exceed the default page size (20). PageImpl recomputes the total as
            // `offset + content.size()` whenever `offset + pageSize > total`, so a total of 13
            // alongside one row of content legitimately reports 1 — a fixture artefact, not a
            // defect. A >1-page total is also the case that actually matters: it is exactly the
            // shop-has-more-rows-than-fit-on-this-page scenario WR-04 got wrong.
            when(announcementService.getAnnouncementsByShop(eq(SHOP_ID), any(Pageable.class)))
                    .thenAnswer(inv -> page(inv.getArgument(1), 33));

            mockMvc.perform(get("/api/v1/announcements").param("shopId", SHOP_ID.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].shopId").value(SHOP_ID.toString()))
                    .andExpect(jsonPath("$.totalElements").value(33));

            verify(announcementService).getAnnouncementsByShop(eq(SHOP_ID), any(Pageable.class));
            verify(announcementService, never()).getAllAnnouncements(any(Pageable.class));
        }

        @Test
        @DisplayName("GET /announcements without shopId keeps the unfiltered tenant-wide behaviour")
        void list_withoutShopId_keepsTenantWideBehaviour() throws Exception {
            when(announcementService.getAllAnnouncements(any(Pageable.class)))
                    .thenAnswer(inv -> page(inv.getArgument(0), 1));

            mockMvc.perform(get("/api/v1/announcements"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1));

            verify(announcementService).getAllAnnouncements(any(Pageable.class));
            verify(announcementService, never()).getAnnouncementsByShop(any(UUID.class), any(Pageable.class));
        }

        @Test
        @DisplayName("GET /announcements?shopId=not-a-uuid is rejected with 400 before any service call")
        void list_malformedShopId_returns400() throws Exception {
            mockMvc.perform(get("/api/v1/announcements").param("shopId", "not-a-uuid"))
                    .andExpect(status().isBadRequest());

            verify(announcementService, never()).getAllAnnouncements(any(Pageable.class));
            verify(announcementService, never()).getAnnouncementsByShop(any(UUID.class), any(Pageable.class));
        }
    }
}
