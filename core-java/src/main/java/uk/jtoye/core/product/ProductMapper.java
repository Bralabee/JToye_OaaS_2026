package uk.jtoye.core.product;

import org.mapstruct.AfterMapping;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uk.jtoye.core.order.OrderAllergenAggregator;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductAllergenWarning;
import uk.jtoye.core.product.dto.ProductDto;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    // `media` (IMG-04, 24-05) is NOT client/entity-mapped: ProductService populates it
    // post-mapping from the product_media join (a MapStruct mapper must not do DB lookups —
    // 24-02 convention). Ignored here so the asset-first media list is a deliberate,
    // service-owned enrichment, not an accidental unmapped null.
    @Mapping(target = "media", ignore = true)
    // #787 (D-09): derived from title, mask and ingredients in fillAllergenWarnings below,
    // never copied from a stored value.
    @Mapping(target = "allergenWarnings", ignore = true)
    // 37-42 (UXT-040): derived from allergenMask in fillAllergenNames below.
    @Mapping(target = "allergens", ignore = true)
    ProductDto toDto(Product product);

    /**
     * Phase 37-42 (RWO-040 / UXT-040): the declared allergens in words, from the ONE catalogue
     * ({@link AllergenCatalog#namesFor(int)}), in bit order, so no consumer needs its own copy of the 14
     * names. The declaration only: may-contain and the reconciliation warnings stay separate (D-16). A
     * null mask (not recorded) gives no list rather than an invented empty one.
     */
    @AfterMapping
    default void fillAllergenNames(Product product, @MappingTarget ProductDto dto) {
        Integer mask = product.getAllergenMask();
        dto.setAllergens(mask == null ? null : AllergenCatalog.namesFor(mask));
    }

    /**
     * #787 (D-09): every product response says when the ingredients text emphasises an
     * allergen the declared mask omits. The answer comes from the ONE reconciliation the
     * order snapshot and the public menu also use ({@link OrderAllergenAggregator}), so the
     * vendor form, the storefront and the kitchen ticket cannot disagree. Pure and
     * dependency-free (no DB lookup in the mapper, 24-02 convention). The declared mask on
     * the DTO and the entity is left exactly as the vendor sent it.
     */
    @AfterMapping
    default void fillAllergenWarnings(Product product, @MappingTarget ProductDto dto) {
        int declaredMask = product.getAllergenMask() == null ? 0 : product.getAllergenMask();
        List<ProductAllergenWarning> warnings = new ArrayList<>();
        for (OrderAllergenAggregator.ReconciliationFlag flag : OrderAllergenAggregator.aggregate(List.of(
                new OrderAllergenAggregator.ItemAllergens(
                        product.getTitle(), declaredMask, product.getIngredientsText()))).flags()) {
            warnings.add(ProductAllergenWarning.undeclaredIngredientAllergen(
                    flag.allergenBit(), flag.allergenName()));
        }
        dto.setAllergenWarnings(warnings);
    }

    // allergenSpans is not client-supplied: ProductService parses ingredientsText
    // and sets it after mapping, so ignore it on both write paths (V41, Issue #82).
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "additionalImageUrls", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "allergenSpans", ignore = true)
    Product toEntity(CreateProductRequest request);

    // QA-council cluster P1 (API-1): partial update — null/absent request fields must NOT
    // overwrite existing values. Without IGNORE, an edit that omitted displayOrder/available/
    // featured nulled a NOT NULL DEFAULT column (SQLState 23502), and one that omitted
    // shelfLifeDays/durabilityType silently wiped the PPDS/Natasha's-Law label fields. Mirrors
    // ShopMapper.updateEntity's existing @BeanMapping for QA-council BE-02 (same failure mode,
    // same fix).
    //
    // quantityInStock follows the same IGNORE rule as every other field (Phase 37-39, RWO-016 /
    // UXT-016, P0). It used to be the one SET_TO_NULL exception, because the dashboard spelled "stop
    // tracking" as an explicit null — and a DTO cannot tell that null from an absent field, so every
    // integrator's partial PUT that did not mention stock silently turned tracking OFF. Stopping
    // tracking is now its own field, CreateProductRequest.trackInventory, applied by
    // ProductService.applyStockTracking AFTER this mapping: null keeps stock and tracking, false
    // clears the stored quantity, true requires a quantity. trackInventory has no Product property,
    // so MapStruct never maps it.
    //
    // mayContainMask (#861, D-16) maps by name on toDto, toEntity and here, under the bean-level
    // IGNORE: an edit form that does not render it keeps the stored value, and 0 is the explicit
    // "no cross-contact risk declared". It is copied as sent and never combined with allergenMask.
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "additionalImageUrls", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "allergenSpans", ignore = true)
    void updateEntity(CreateProductRequest request, @MappingTarget Product product);
}
