package uk.jtoye.core.product;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    // `media` (IMG-04, 24-05) is NOT client/entity-mapped: ProductService populates it
    // post-mapping from the product_media join (a MapStruct mapper must not do DB lookups —
    // 24-02 convention). Ignored here so the asset-first media list is a deliberate,
    // service-owned enrichment, not an accidental unmapped null.
    @Mapping(target = "media", ignore = true)
    ProductDto toDto(Product product);

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
