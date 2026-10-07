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
    ProductDto toDto(Product product);

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
    // quantityInStock is the ONE deliberate exception: the frontend always sends it explicitly
    // (`trackInventory ? qty : null`) and reconstructs "is tracking on" from whether it is null,
    // so under blanket IGNORE a vendor could never turn tracking off again. SET_TO_NULL overrides
    // the bean-level IGNORE for this field alone, so an explicit null still clears it.
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
    @Mapping(target = "quantityInStock",
            nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.SET_TO_NULL)
    void updateEntity(CreateProductRequest request, @MappingTarget Product product);
}
