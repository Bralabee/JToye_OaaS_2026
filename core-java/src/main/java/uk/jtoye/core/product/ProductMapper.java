package uk.jtoye.core.product;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
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

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "additionalImageUrls", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "allergenSpans", ignore = true)
    void updateEntity(CreateProductRequest request, @MappingTarget Product product);
}
