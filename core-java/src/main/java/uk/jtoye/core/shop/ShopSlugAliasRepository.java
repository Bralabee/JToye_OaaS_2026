package uk.jtoye.core.shop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ShopSlugAliasRepository extends JpaRepository<ShopSlugAlias, String> {

    boolean existsByOldSlugAndShopIdNot(String oldSlug, UUID shopId);

    @Modifying
    @Query("DELETE FROM ShopSlugAlias a WHERE a.oldSlug = :oldSlug AND a.shopId = :shopId")
    void deleteByOldSlugAndShopId(@Param("oldSlug") String oldSlug, @Param("shopId") UUID shopId);

    /**
     * Resolve a historical {@code oldSlug} to the current canonical {@code shops.slug} of its
     * target shop, if and only if that target shop is {@code published = true}.
     *
     * <p>An unpublished shop returns {@link Optional#empty()} so the public storefront answers
     * HTTP 404 rather than leaking the unpublished shop's existence or current slug.
     */
    @Query("SELECT s.slug FROM ShopSlugAlias a, Shop s WHERE a.oldSlug = :oldSlug AND s.id = a.shopId AND s.published = true")
    Optional<String> findPublishedCanonicalSlugByOldSlug(@Param("oldSlug") String oldSlug);
}
