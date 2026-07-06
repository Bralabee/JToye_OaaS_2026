package uk.jtoye.core.order;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemDto;

import java.util.List;

/**
 * MapStruct mapper for Order entity and DTOs.
 *
 * OrderDto is lightweight (no items) for list views.
 * OrderDetailDto includes items for detail views.
 */
@Mapper(componentModel = "spring")
public interface OrderMapper {

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

    @Mapping(target = "items", source = "items")
    @Mapping(target = "paymentStatus", source = "paymentStatus")
    @Mapping(target = "paymentReference", source = "paymentReference")
    @Mapping(target = "paymentMethod", source = "paymentMethod")
    // refunds is populated by OrderService.getOrderDetailById post-mapping —
    // the Refund aggregate lives in a different package and the mapper does
    // not depend on RefundService.
    @Mapping(target = "refunds", ignore = true)
    OrderDetailDto toDetailDto(Order order);

    OrderItemDto toItemDto(OrderItem orderItem);

    List<OrderItemDto> toItemDtoList(List<OrderItem> items);
}
