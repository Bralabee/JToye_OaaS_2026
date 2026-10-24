package uk.jtoye.core.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.storage.StorageService;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The public storefront's primary-image resolution (29-29, D-34(b)): the vendor's own D-03a rule
 * ({@link ProductMediaRepository#findPrimaryActiveObjectKey} then {@link StorageService#urlForKey}),
 * batched over a menu, and restricted to what may be shown anonymously — an ACTIVE, unflagged
 * derivative of the pinned tenant.
 *
 * <p>Before this, the anonymous menu read only the flat {@code products.image_url} column, which the
 * Phase 24 pipeline never writes, so a photo a vendor uploaded showed on the dashboard and nowhere a
 * customer could see it.
 *
 * <p>Tenant scoping is the caller's pin plus V53's FORCE RLS policies, never a public-read policy: the
 * storefront resolves the shop by slug and pins its tenant before calling here. Hence the two guards —
 * {@link Propagation#MANDATORY}, because {@code TenantSetLocalAspect} applies the pin only inside a
 * transaction, and the explicit tenant check, because an unpinned read under RLS returns nothing and a
 * forgotten pin must fail loudly rather than show a menu with no photos.
 */
@Component
public class PublicProductImageResolver {
    private static final Logger log = LoggerFactory.getLogger(PublicProductImageResolver.class);

    private final ProductMediaRepository productMediaRepository;
    private final StorageService storageService;

    public PublicProductImageResolver(ProductMediaRepository productMediaRepository, StorageService storageService) {
        this.productMediaRepository = productMediaRepository;
        this.storageService = storageService;
    }

    /**
     * The public URL of each product's primary image, for the products that have a publishable one; a
     * product absent from the map falls back to its flat {@code image_url} (D-03a). One query for the
     * whole collection, none for an empty one.
     *
     * @throws IllegalStateException when no tenant is pinned (the codebase's "tenant not set" 500)
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public Map<UUID, String> primaryUrlsFor(Collection<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        if (TenantContext.get().isEmpty()) {
            throw new IllegalStateException("Tenant context is not set for the public product image read");
        }
        Map<UUID, String> urls = new HashMap<>();
        for (ProductMediaRepository.PrimaryObjectKey row : productMediaRepository.findPublicPrimaryObjectKeys(productIds)) {
            try {
                urls.put(row.getProductId(), storageService.urlForKey(row.getObjectKey()));
            } catch (IllegalArgumentException refused) {
                // A quarantine key on an ACTIVE row is corrupt data the pipeline never writes. Skip that one
                // image rather than fail the menu, and never log the key (it names a private object).
                log.warn("event=public_media_key_refused productId={}", row.getProductId());
            }
        }
        return urls;
    }
}
