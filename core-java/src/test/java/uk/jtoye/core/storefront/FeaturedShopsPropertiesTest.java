package uk.jtoye.core.storefront;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jtoye.storefront.featured-shop-slugs} binds to a validated, ordered, immutable list
 * (quick 261102-r6u, D-02, T-r6u-02). Driven through a real {@link ApplicationContextRunner} with
 * {@link FeaturedShopsConfig}, so the binding (comma splitting, trimming, empty values) is Spring's
 * own, not a constructor call this test made up.
 */
class FeaturedShopsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FeaturedShopsConfig.class);

    @Test
    @DisplayName("a comma-separated value binds in order, each entry trimmed")
    void bindsTrimmedInOrder() {
        runner.withPropertyValues("jtoye.storefront.featured-shop-slugs=a, b,c")
                .run(ctx -> assertThat(ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs())
                        .containsExactly("a", "b", "c"));
    }

    @Test
    @DisplayName("an empty value and an absent value both bind to an empty list (the inert state)")
    void emptyAndAbsentBindEmpty() {
        runner.withPropertyValues("jtoye.storefront.featured-shop-slugs=")
                .run(ctx -> assertThat(ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs()).isEmpty());
        runner.run(ctx -> assertThat(ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs()).isEmpty());
    }

    @Test
    @DisplayName("sibling keys under jtoye.storefront (the cash caps) are ignored")
    void siblingKeysIgnored() {
        runner.withPropertyValues("jtoye.storefront.cash-caps.per-email=3",
                        "jtoye.storefront.featured-shop-slugs=mama-ades-kitchen")
                .run(ctx -> assertThat(ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs())
                        .containsExactly("mama-ades-kitchen"));
    }

    @Test
    @DisplayName("the bound list is immutable")
    void boundListIsImmutable() {
        runner.withPropertyValues("jtoye.storefront.featured-shop-slugs=a,b")
                .run(ctx -> {
                    List<String> slugs = ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> slugs.add("c"))
                            .isInstanceOf(UnsupportedOperationException.class);
                });
    }

    @Test
    @DisplayName("a duplicate refuses to start, naming the key and the entry")
    void duplicateRefused() {
        assertRefused("a,b,a", "twice", "'a'");
    }

    @Test
    @DisplayName("a blank entry (two commas together) refuses to start, naming the key")
    void blankEntryRefused() {
        assertRefused("a,,b", "blank entry");
    }

    @Test
    @DisplayName("a value that is not a slug refuses to start, naming the key and the entry")
    void nonSlugRefused() {
        assertRefused("Mama Ades", "'Mama Ades'", "not a shop slug");
    }

    @Test
    @DisplayName("more than 12 entries refuses to start, naming the key")
    void tooManyRefused() {
        String thirteen = IntStream.rangeClosed(1, 13).mapToObj(i -> "shop-" + i).collect(Collectors.joining(","));
        assertRefused(thirteen, "13", "at most 12");
        // Control: exactly 12 is accepted, so the bound is 12 and not something smaller.
        String twelve = IntStream.rangeClosed(1, 12).mapToObj(i -> "shop-" + i).collect(Collectors.joining(","));
        runner.withPropertyValues("jtoye.storefront.featured-shop-slugs=" + twelve)
                .run(ctx -> assertThat(ctx.getBean(FeaturedShopsProperties.class).featuredShopSlugs()).hasSize(12));
    }

    private void assertRefused(String value, String... expectedFragments) {
        runner.withPropertyValues("jtoye.storefront.featured-shop-slugs=" + value)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String messages = causeChainMessages(ctx.getStartupFailure());
                    assertThat(messages).contains(FeaturedShopsProperties.PROPERTY);
                    for (String fragment : expectedFragments) {
                        assertThat(messages).contains(fragment);
                    }
                });
    }

    private static String causeChainMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
