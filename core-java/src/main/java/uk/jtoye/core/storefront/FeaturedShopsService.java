package uk.jtoye.core.storefront;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The featured kitchens of this environment, as the public sees them (quick 261102-r6u, D-02/D-03).
 *
 * <p>Published shops only, in configured order: {@link PublicStorefrontService#listPublishedShopsBySlugs}
 * owns the published predicate (in the query, not only in RLS) and keeps the caller's order. A configured
 * slug that names no published shop is skipped, so the staging list can be deployed before the demo
 * kitchens are seeded, and a kitchen that unpublishes disappears on the next request (nothing is cached).
 *
 * <p>The landing page asks on every render, so the skipped slugs are logged ONCE per distinct skipped set,
 * never once per request: the last set warned about is remembered, an empty skipped set resets it, and a
 * recurrence warns again.
 */
@Service
public class FeaturedShopsService {
    private static final Logger log = LoggerFactory.getLogger(FeaturedShopsService.class);

    private final FeaturedShopsProperties properties;
    private final PublicStorefrontService storefrontService;
    private final AtomicReference<Set<String>> lastWarned = new AtomicReference<>(Set.of());

    public FeaturedShopsService(FeaturedShopsProperties properties, PublicStorefrontService storefrontService) {
        this.properties = properties;
        this.storefrontService = storefrontService;
    }

    /** The featured published shops, in configured order; empty, with no query, when none are configured. */
    public List<PublicShopDto> featuredShops() {
        List<String> configured = properties.featuredShopSlugs();
        if (configured.isEmpty()) {
            return List.of();
        }
        List<PublicShopDto> found = storefrontService.listPublishedShopsBySlugs(configured);
        Set<String> skipped = new LinkedHashSet<>(configured);
        found.forEach(dto -> skipped.remove(dto.getSlug()));
        warnOncePerSkippedSet(skipped);
        return found;
    }

    private void warnOncePerSkippedSet(Set<String> skipped) {
        Set<String> current = Set.copyOf(skipped);
        Set<String> previous = lastWarned.getAndSet(current);
        if (!skipped.isEmpty() && !current.equals(previous)) {
            log.warn("{} names {} that are unknown or unpublished and were skipped: {}",
                    FeaturedShopsProperties.PROPERTY, skipped.size() == 1 ? "a slug" : "slugs", skipped);
        }
    }
}
