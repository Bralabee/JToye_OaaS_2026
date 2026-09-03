package uk.jtoye.core.order;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import uk.jtoye.core.order.OrderAllergenSnapshot.OrderAllergenView;
import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.order.dto.OrderItemDto;

import java.util.List;

/**
 * MapStruct mapper for Order entity and DTOs.
 *
 * OrderDto is lightweight (no items) for list views.
 * OrderDetailDto includes items for detail views.
 *
 * <h2>The allergen aggregate (LGL-03 / V63)</h2>
 *
 * <p>{@code OrderDetailDto} carries the order's allergen picture, rebuilt by
 * {@link OrderAllergenSnapshot#viewOf(java.util.Collection)} from the lines' write-time snapshot.
 * It is filled in an {@code @AfterMapping} hook rather than by a property mapping because
 * {@code Order} has no such property — the value is derived from its items, and deriving it in
 * ONE place is what stops the checkout panel and the kitchen display from disagreeing.
 *
 * <p>{@code toDto} deliberately does NOT carry it: that DTO is the list view and holds no items,
 * so deriving the aggregate there would lazily load one item collection per row. Measured on
 * Testcontainers Postgres — 7 orders, 7 extra prepared statements. See the note on
 * {@code OrderDto}.
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
    // COR-1: the vendor list must be able to SEE the classification and the fee. Scalar columns
    // on the order row — no extra query, no collection load.
    @Mapping(target = "fulfilmentType", source = "fulfilmentType")
    @Mapping(target = "deliveryFeePennies", source = "deliveryFeePennies")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    OrderDto toDto(Order order);

    @Mapping(target = "items", source = "items")
    @Mapping(target = "paymentStatus", source = "paymentStatus")
    @Mapping(target = "paymentReference", source = "paymentReference")
    @Mapping(target = "paymentMethod", source = "paymentMethod")
    // Fulfilment + delivery address (V45) so /dashboard/orders/[id] can render
    // how + where the order is fulfilled.
    @Mapping(target = "fulfilmentType", source = "fulfilmentType")
    @Mapping(target = "addressLine1", source = "addressLine1")
    @Mapping(target = "addressLine2", source = "addressLine2")
    @Mapping(target = "addressCity", source = "addressCity")
    @Mapping(target = "addressPostcode", source = "addressPostcode")
    // refunds is populated by OrderService.getOrderDetailById post-mapping —
    // the Refund aggregate lives in a different package and the mapper does
    // not depend on RefundService.
    @Mapping(target = "refunds", ignore = true)
    // Derived from the items — filled by fillAllergens below.
    @Mapping(target = "allergenMask", ignore = true)
    @Mapping(target = "allergenNames", ignore = true)
    @Mapping(target = "allergenFlags", ignore = true)
    OrderDetailDto toDetailDto(Order order);

    /**
     * The per-line declared mask maps by name; the names are resolved from the catalogue so the
     * two consumer surfaces cannot disagree about wording. Both are null for a pre-V63 line.
     */
    @Mapping(target = "allergenNames",
            expression = "java(uk.jtoye.core.order.OrderAllergenSnapshot.namesOf(orderItem))")
    OrderItemDto toItemDto(OrderItem orderItem);

    List<OrderItemDto> toItemDtoList(List<OrderItem> items);

    @AfterMapping
    default void fillAllergens(Order order, @MappingTarget OrderDetailDto dto) {
        OrderAllergenView view = OrderAllergenSnapshot.viewOf(order.getItems());
        dto.setAllergenMask(view.declaredMask());
        dto.setAllergenNames(view.declaredNames());
        dto.setAllergenFlags(view.flags());
    }
}
