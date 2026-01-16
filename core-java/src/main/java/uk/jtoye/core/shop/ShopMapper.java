package uk.jtoye.core.shop;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.shop.dto.CreateShopRequest;
import uk.jtoye.core.shop.dto.ShopDto;

/**
 * MapStruct mapper for Shop entity and DTOs.
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
public interface ShopMapper {

    /**
     * Convert Shop entity to ShopDto.
     * MapStruct will automatically map all matching fields.
     */
    ShopDto toDto(Shop shop);

    /**
     * Convert CreateShopRequest to Shop entity.
     * Note: tenantId must be set manually in the service layer.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Shop toEntity(CreateShopRequest request);
}
