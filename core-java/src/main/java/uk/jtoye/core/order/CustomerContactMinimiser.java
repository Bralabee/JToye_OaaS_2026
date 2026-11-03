package uk.jtoye.core.order;

import uk.jtoye.core.order.dto.OrderDetailDto;
import uk.jtoye.core.order.dto.OrderDto;
import uk.jtoye.core.security.access.CustomerContactScope;

/**
 * Field-level minimisation of customer contact on order responses (issue #856 / SW-TND-08, owner
 * ruling 2026-11-03, UK GDPR Art 5(1)(c)).
 *
 * <p>When the caller does not hold SHOP_MANAGER or above on the order's shop (STAFF there), the
 * response carries no {@code customerEmail}, and a non-DELIVERY order (COLLECTION, or untyped)
 * carries no address. Kept, always: the customer's name and phone, notes, the allergy note, the
 * items and every allergen field, {@code contactVerified}, and a DELIVERY order's address (STAFF
 * performs the hand-off). A {@code null} scope minimises: a missing decision is minimisation,
 * never disclosure.
 *
 * <p>The field is kept and nulled, never dropped by a {@code JsonInclude}: {@code OrderDto}'s
 * serialised bytes are frozen by {@code IdempotencyFingerprintGoldenTest}.
 */
final class CustomerContactMinimiser {

    private CustomerContactMinimiser() {
    }

    static OrderDto apply(OrderDto dto, CustomerContactScope scope) {
        if (dto == null || (scope != null && scope.fullContactOn(dto.getShopId()))) {
            return dto;
        }
        dto.setCustomerEmail(null);
        return dto;
    }

    static OrderDetailDto apply(OrderDetailDto dto, CustomerContactScope scope) {
        if (dto == null || (scope != null && scope.fullContactOn(dto.getShopId()))) {
            return dto;
        }
        dto.setCustomerEmail(null);
        if (dto.getFulfilmentType() != FulfilmentType.DELIVERY) {
            dto.setAddressLine1(null);
            dto.setAddressLine2(null);
            dto.setAddressCity(null);
            dto.setAddressPostcode(null);
        }
        return dto;
    }
}
