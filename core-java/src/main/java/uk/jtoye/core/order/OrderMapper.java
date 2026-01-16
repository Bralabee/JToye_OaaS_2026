package uk.jtoye.core.order;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.order.dto.OrderDto;

/**
 * MapStruct mapper for Order entity and DTOs.
 * Provides compile-time safe DTO mapping with automatic null checks.
 *
 * Benefits over manual mapping:
 * - Compile-time type safety (no reflection)
 * - 10-20% performance improvement
 * - Automatic null handling
 * - Easy custom mapping rules
 *
 * Note: componentModel = "spring" generates a Spring bean that can be injected.
 *
 * IMPORTANT: OrderDto does not include order items to keep the DTO lightweight.
 * If order items are needed, create a separate detailed DTO (e.g., OrderDetailDto).
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

    /**
     * Convert Order entity to OrderDto.
     * MapStruct will automatically map all matching fields.
     * Note: Order items are not included in the DTO (intentionally lightweight).
     */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "tenantId", source = "tenantId")
    @Mapping(target = "shopId", source = "shopId")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "customerName", source = "customerName")
    @Mapping(target = "customerEmail", source = "customerEmail")
    @Mapping(target = "customerPhone", source = "customerPhone")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "totalAmountPennies", source = "totalAmountPennies")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    OrderDto toDto(Order order);
}
