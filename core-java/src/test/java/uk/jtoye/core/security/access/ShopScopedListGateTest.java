package uk.jtoye.core.security.access;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.jtoye.core.config.TenantCacheEvictor;
import uk.jtoye.core.exception.ShopAccessDeniedException;
import uk.jtoye.core.media.MediaAssetService;
import uk.jtoye.core.media.ProductMediaRepository;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductMapper;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.product.ProductService;
import uk.jtoye.core.shop.AnnouncementMapper;
import uk.jtoye.core.shop.AnnouncementService;
import uk.jtoye.core.shop.PromotionMapper;
import uk.jtoye.core.shop.PromotionService;
import uk.jtoye.core.shop.ShopAnnouncement;
import uk.jtoye.core.shop.ShopAnnouncementRepository;
import uk.jtoye.core.shop.ShopPromotion;
import uk.jtoye.core.shop.ShopPromotionRepository;
import uk.jtoye.core.storage.StorageService;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WR-04 (issue #280, plan 23-18) — the AUTHORIZATION half of the shop-scoped list fix.
 *
 * <p>Moving the shop narrow from the browser to the query introduced a new caller-supplied
 * parameter, and a caller-supplied {@code shopId} that is not gated is an in-tenant read
 * bypass: any authenticated user of the tenant could enumerate another shop's catalogue by
 * editing the query string. Each of the three new service methods therefore calls
 * {@code shopAccessService.require(shopId, ShopRole.STAFF)} FIRST.
 *
 * <p><strong>These are the falsifiable tests for acceptance criterion A2.</strong> Deleting the
 * {@code require(...)} line from any of the three services must turn the matching test in this
 * class RED — that is what makes the criterion evidence rather than decoration. The
 * {@code verify(..., never())} on the repository is the load-bearing part: it proves the gate
 * runs BEFORE the query, so an unauthorized shop is never probed for row existence.
 *
 * <p>The denial must be a typed 403 ({@link ShopAccessDeniedException}) and NOT an empty page —
 * an empty page would be indistinguishable from "this shop genuinely has nothing", which is
 * exactly the confusion WR-04 set out to remove.
 */
@ExtendWith(MockitoExtension.class)
class ShopScopedListGateTest {

    private static final UUID SHOP_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Pageable PAGE = PageRequest.of(0, 20);

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Products {

        @Mock private ProductRepository productRepository;
        @Mock private ProductMapper productMapper;
        @Mock private StorageService storageService;
        @Mock private TenantCacheEvictor cacheEvictor;
        @Mock private ShopAccessService shopAccessService;
        @Mock private ProductService.ProductCacheLoader productCacheLoader;
        @Mock private ProductMediaRepository productMediaRepository;
        @Mock private MediaAssetService mediaAssetService;

        @InjectMocks private ProductService productService;

        @Test
        @DisplayName("getProductsByShop denies an ungranted shop with a typed 403, before any query")
        void ungrantedShop_throwsTypedForbidden_andNeverQueries() {
            doThrow(new ShopAccessDeniedException(SHOP_ID, ShopRole.STAFF))
                    .when(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));

            assertThatThrownBy(() -> productService.getProductsByShop(SHOP_ID, PAGE))
                    .isInstanceOf(ShopAccessDeniedException.class);

            verify(productRepository, never()).findByShopId(any(UUID.class), any(Pageable.class));
        }

        @Test
        @DisplayName("getProductsByShop queries the single shop when the caller is granted")
        void grantedShop_queriesThatShopOnly() {
            when(productRepository.findByShopId(eq(SHOP_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Product>of(), PAGE, 0));

            assertThatCode(() -> productService.getProductsByShop(SHOP_ID, PAGE))
                    .doesNotThrowAnyException();

            verify(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));
            verify(productRepository).findByShopId(eq(SHOP_ID), any(Pageable.class));
        }

        @Test
        @DisplayName("search(shopId) denies an ungranted shop with a typed 403, before any query")
        void search_ungrantedShop_throwsTypedForbidden_andNeverQueries() {
            doThrow(new ShopAccessDeniedException(SHOP_ID, ShopRole.STAFF))
                    .when(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));

            assertThatThrownBy(() -> productService.search("chick", SHOP_ID, PAGE))
                    .isInstanceOf(ShopAccessDeniedException.class);

            verify(productRepository, never())
                    .searchFullTextByShop(any(), any(), any(UUID.class), any(Pageable.class));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Promotions {

        @Mock private ShopPromotionRepository promotionRepository;
        @Mock private PromotionMapper promotionMapper;
        @Mock private ShopAccessService shopAccessService;

        @InjectMocks private PromotionService promotionService;

        @Test
        @DisplayName("getPromotionsByShop denies an ungranted shop with a typed 403, before any query")
        void ungrantedShop_throwsTypedForbidden_andNeverQueries() {
            doThrow(new ShopAccessDeniedException(SHOP_ID, ShopRole.STAFF))
                    .when(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));

            assertThatThrownBy(() -> promotionService.getPromotionsByShop(SHOP_ID, PAGE))
                    .isInstanceOf(ShopAccessDeniedException.class);

            verify(promotionRepository, never())
                    .findByShopIdIn(any(Collection.class), any(Pageable.class));
        }

        @Test
        @DisplayName("getPromotionsByShop narrows to exactly the one requested shop")
        void grantedShop_queriesThatShopOnly() {
            when(promotionRepository.findByShopIdIn(eq(java.util.Set.of(SHOP_ID)), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<ShopPromotion>of(), PAGE, 0));

            assertThatCode(() -> promotionService.getPromotionsByShop(SHOP_ID, PAGE))
                    .doesNotThrowAnyException();

            verify(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));
            verify(promotionRepository).findByShopIdIn(eq(java.util.Set.of(SHOP_ID)), any(Pageable.class));
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    class Announcements {

        @Mock private ShopAnnouncementRepository announcementRepository;
        @Mock private AnnouncementMapper announcementMapper;
        @Mock private ShopAccessService shopAccessService;

        @InjectMocks private AnnouncementService announcementService;

        @Test
        @DisplayName("getAnnouncementsByShop denies an ungranted shop with a typed 403, before any query")
        void ungrantedShop_throwsTypedForbidden_andNeverQueries() {
            doThrow(new ShopAccessDeniedException(SHOP_ID, ShopRole.STAFF))
                    .when(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));

            assertThatThrownBy(() -> announcementService.getAnnouncementsByShop(SHOP_ID, PAGE))
                    .isInstanceOf(ShopAccessDeniedException.class);

            verify(announcementRepository, never())
                    .findByShopIdIn(any(Collection.class), any(Pageable.class));
        }

        @Test
        @DisplayName("getAnnouncementsByShop narrows to exactly the one requested shop")
        void grantedShop_queriesThatShopOnly() {
            when(announcementRepository.findByShopIdIn(eq(java.util.Set.of(SHOP_ID)), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<ShopAnnouncement>of(), PAGE, 0));

            assertThatCode(() -> announcementService.getAnnouncementsByShop(SHOP_ID, PAGE))
                    .doesNotThrowAnyException();

            verify(shopAccessService).require(eq(SHOP_ID), eq(ShopRole.STAFF));
            verify(announcementRepository).findByShopIdIn(eq(java.util.Set.of(SHOP_ID)), any(Pageable.class));
        }
    }
}
