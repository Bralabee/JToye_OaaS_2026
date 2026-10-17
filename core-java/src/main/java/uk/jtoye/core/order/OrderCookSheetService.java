package uk.jtoye.core.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.order.dto.CookSheetDto;
import uk.jtoye.core.order.dto.CookSheetDto.CookSheetAllergyAlert;
import uk.jtoye.core.order.dto.CookSheetDto.CookSheetItemRow;
import uk.jtoye.core.product.AllergenCatalog;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Consolidated kitchen "Cook Sheet" aggregator (V87, ADR-0007 / GAP-GTM-03).
 *
 * <p>Solves the batch-prep bottleneck where a kitchen preparing 40 weekend pre-orders or catering
 * tickets had to mentally sum 40 individual KDS cards to know whether to cook 55 or 80 litres of
 * Jollof Rice. Groups active, accepted, non-voided orders ({@code CONFIRMED}, {@code PREPARING},
 * {@code READY}) by product while surfacing every V73 customer {@code allergyNote} alongside the
 * batch totals so Natasha's Law and dietary safety are preserved during bulk prep.
 */
@Service
@Transactional(readOnly = true)
public class OrderCookSheetService {

    /**
     * Only orders the shop has ACCEPTED ({@code CONFIRMED}, {@code PREPARING}, {@code READY}) belong
     * on the cook sheet: unconfirmed {@code PENDING} orders have not yet been accepted into prep, and
     * {@code COMPLETED}/{@code CANCELLED}/voided orders are no longer being cooked.
     */
    static final List<OrderStatus> COOK_SHEET_STATUSES =
            List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    private final OrderRepository orderRepository;
    private final ShopAccessService shopAccessService;

    public OrderCookSheetService(OrderRepository orderRepository, ShopAccessService shopAccessService) {
        this.orderRepository = orderRepository;
        this.shopAccessService = shopAccessService;
    }

    /**
     * Build the consolidated cook sheet for {@code shopId}, optionally narrowed to {@code scheduledForDate}
     * and/or {@code fulfilmentSlotId}. Requires at least {@link ShopRole#STAFF} on the shop (VSA-02).
     */
    public CookSheetDto getCookSheet(UUID shopId, LocalDate scheduledForDate, UUID fulfilmentSlotId) {
        if (shopId == null) {
            throw new IllegalArgumentException("shopId is required");
        }
        shopAccessService.require(shopId, ShopRole.STAFF);

        List<Order> orders;
        if (fulfilmentSlotId != null) {
            orders = orderRepository.findCookSheetOrdersByShopAndSlot(shopId, COOK_SHEET_STATUSES, fulfilmentSlotId);
            if (scheduledForDate != null) {
                orders = orders.stream()
                        .filter(o -> scheduledForDate.equals(o.getScheduledForDate()))
                        .toList();
            }
        } else if (scheduledForDate != null) {
            orders = orderRepository.findCookSheetOrdersByShopAndDate(shopId, COOK_SHEET_STATUSES, scheduledForDate);
        } else {
            orders = orderRepository.findCookSheetOrdersByShop(shopId, COOK_SHEET_STATUSES);
        }

        Map<UUID, ItemAccumulator> byProduct = new LinkedHashMap<>();
        List<CookSheetAllergyAlert> allergyAlerts = new ArrayList<>();
        int totalUnits = 0;

        for (Order order : orders) {
            if (order.getAllergyNote() != null && !order.getAllergyNote().isBlank()) {
                allergyAlerts.add(new CookSheetAllergyAlert(
                        order.getId(),
                        order.getOrderNumber(),
                        order.getCustomerName(),
                        order.getAllergyNote(),
                        order.getAllergyNoteAckAt() != null
                ));
            }
            for (OrderItem item : order.getItems()) {
                int qty = item.getQuantity() != null ? item.getQuantity() : 0;
                totalUnits += qty;
                ItemAccumulator acc = byProduct.computeIfAbsent(
                        item.getProductId(),
                        id -> new ItemAccumulator(id, item.getProductName()));
                acc.add(order.getId(), qty, item.getAllergenMask());
            }
        }

        List<CookSheetItemRow> itemRows = byProduct.values().stream()
                .map(ItemAccumulator::toRow)
                .sorted(Comparator.comparingInt(CookSheetItemRow::totalQuantity).reversed()
                        .thenComparing(CookSheetItemRow::productName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        return new CookSheetDto(
                shopId,
                scheduledForDate,
                fulfilmentSlotId,
                orders.size(),
                totalUnits,
                itemRows,
                List.copyOf(allergyAlerts)
        );
    }

    private static final class ItemAccumulator {
        private final UUID productId;
        private final String productName;
        private int totalQuantity = 0;
        private final Set<UUID> orderIds = new HashSet<>();
        private boolean anyMaskMissing = false;
        private int unionMask = 0;

        private ItemAccumulator(UUID productId, String productName) {
            this.productId = productId;
            this.productName = productName;
        }

        private void add(UUID orderId, int quantity, Integer allergenMask) {
            this.totalQuantity += quantity;
            if (orderId != null) {
                this.orderIds.add(orderId);
            }
            if (allergenMask == null) {
                this.anyMaskMissing = true;
            } else {
                this.unionMask |= allergenMask;
            }
        }

        private CookSheetItemRow toRow() {
            Integer mask = anyMaskMissing ? null : unionMask;
            List<String> names = mask == null ? null : AllergenCatalog.namesFor(mask);
            return new CookSheetItemRow(
                    productId,
                    productName,
                    totalQuantity,
                    orderIds.size(),
                    mask,
                    names
            );
        }
    }
}
