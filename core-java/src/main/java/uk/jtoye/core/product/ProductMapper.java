package uk.jtoye.core.product;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.product.dto.CreateProductRequest;
import uk.jtoye.core.product.dto.ProductDto;

/**
 * MapStruct mapper for Product entity and DTOs.
 * Provides compile-time safe DTO mapping with automatic null checks.
 *
 * Benefits over manual mapping:
 * - Compile-time type safety (no reflection)
 * - 10-20% performance improvement
 * - Automatic null handling
 * - Easy custom mapping rules
 *
 * Note: componentModel = "spring" generates a Spring bean that can be injected.
 */
@Mapper(componentModel = "spring")
public interface ProductMapper {

    /**
     * Convert Product entity to ProductDto.
     * MapStruct will automatically map all matching fields.
     */
    ProductDto toDto(Product product);

    /**
     * Convert CreateProductRequest to Product entity.
     * Note: tenantId must be set manually in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Product toEntity(CreateProductRequest request);
}
