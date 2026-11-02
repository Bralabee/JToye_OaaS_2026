package uk.jtoye.core.storefront;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeaturedShopsService} (quick 261102-r6u, D-02): no query when nothing is featured, and the
 * skipped slugs logged once per distinct skipped set, never once per landing render.
 */
class FeaturedShopsServiceTest {

    private final PublicStorefrontService storefront = mock(PublicStorefrontService.class);
    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void attach() {
        serviceLogger = (Logger) LoggerFactory.getLogger(FeaturedShopsService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("nothing configured: [] and the storefront service is never called")
    void emptyListReturnsEmptyWithoutQuery() {
        FeaturedShopsService service = new FeaturedShopsService(new FeaturedShopsProperties(List.of()), storefront);

        assertThat(service.featuredShops()).isEmpty();
        verify(storefront, never()).listPublishedShopsBySlugs(anyList());
        assertThat(warns()).isEmpty();
    }

    @Test
    @DisplayName("returns what the storefront found, in its order")
    void returnsFoundShops() {
        FeaturedShopsService service = new FeaturedShopsService(
                new FeaturedShopsProperties(List.of("b", "a")), storefront);
        when(storefront.listPublishedShopsBySlugs(List.of("b", "a"))).thenReturn(List.of(dto("b"), dto("a")));

        assertThat(service.featuredShops()).extracting(PublicShopDto::getSlug).containsExactly("b", "a");
        assertThat(warns()).as("nothing skipped, nothing to warn about").isEmpty();
    }

    @Test
    @DisplayName("the same skipped set warns ONCE; a changed set warns again; a cleared set resets")
    void warnsOncePerDistinctSkippedSet() {
        FeaturedShopsService service = new FeaturedShopsService(
                new FeaturedShopsProperties(List.of("a", "ghost", "hidden")), storefront);

        when(storefront.listPublishedShopsBySlugs(anyList())).thenReturn(List.of(dto("a")));
        service.featuredShops();
        service.featuredShops();
        assertThat(warns()).hasSize(1);
        assertThat(warns().get(0).getFormattedMessage())
                .contains(FeaturedShopsProperties.PROPERTY).contains("ghost").contains("hidden")
                .contains("unknown or unpublished");

        // "hidden" is published now: the skipped set changed, so it warns again.
        when(storefront.listPublishedShopsBySlugs(anyList())).thenReturn(List.of(dto("a"), dto("hidden")));
        service.featuredShops();
        assertThat(warns()).hasSize(2);
        assertThat(warns().get(1).getFormattedMessage()).contains("ghost").doesNotContain("hidden");

        // Everything found: nothing to warn about, and the remembered set resets...
        when(storefront.listPublishedShopsBySlugs(anyList()))
                .thenReturn(List.of(dto("a"), dto("ghost"), dto("hidden")));
        service.featuredShops();
        assertThat(warns()).hasSize(2);

        // ...so the first set recurring warns again rather than staying silent for ever.
        when(storefront.listPublishedShopsBySlugs(anyList())).thenReturn(List.of(dto("a")));
        service.featuredShops();
        assertThat(warns()).hasSize(3);
    }

    private List<ILoggingEvent> warns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static PublicShopDto dto(String slug) {
        PublicShopDto dto = new PublicShopDto();
        dto.setSlug(slug);
        return dto;
    }
}
