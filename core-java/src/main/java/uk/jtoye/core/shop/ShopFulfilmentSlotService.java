package uk.jtoye.core.shop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.dto.CreateFulfilmentSlotRequest;
import uk.jtoye.core.shop.dto.FulfilmentSlotDto;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0007 / GAP-GTM-02: Manages capacity-bounded scheduled fulfilment slots for
 * pre-orders, batch drops (e.g. Saturday 2L bowls), and catering windows.
 */
@Service
@Transactional
public class ShopFulfilmentSlotService {

    private static final Logger log = LoggerFactory.getLogger(ShopFulfilmentSlotService.class);

    private final ShopFulfilmentSlotRepository slotRepository;
    private final ShopRepository shopRepository;
    private final ShopAccessService shopAccessService;
    private final Clock clock;

    public ShopFulfilmentSlotService(ShopFulfilmentSlotRepository slotRepository,
                                     ShopRepository shopRepository,
                                     ShopAccessService shopAccessService,
                                     Clock clock) {
        this.slotRepository = slotRepository;
        this.shopRepository = shopRepository;
        this.shopAccessService = shopAccessService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<FulfilmentSlotDto> listVendorSlots(UUID shopId) {
        shopAccessService.require(shopId, ShopRole.STAFF);
        UUID tenantId = requireTenantId();
        shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));
        return slotRepository.findByShopIdOrderByServiceDateAscWindowStartAsc(shopId).stream()
                .map(FulfilmentSlotDto::from)
                .toList();
    }

    public FulfilmentSlotDto createSlot(UUID shopId, CreateFulfilmentSlotRequest request) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);
        UUID tenantId = requireTenantId();
        shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        if (request.getWindowEnd() == null || request.getWindowStart() == null
                || !request.getWindowEnd().isAfter(request.getWindowStart())) {
            throw new IllegalArgumentException("windowEnd must be after windowStart");
        }

        ShopFulfilmentSlot slot = new ShopFulfilmentSlot();
        slot.setTenantId(tenantId);
        slot.setShopId(shopId);
        slot.setServiceDate(request.getServiceDate());
        slot.setWindowStart(request.getWindowStart());
        slot.setWindowEnd(request.getWindowEnd());
        slot.setCutoffAt(request.getCutoffAt());
        slot.setMaxOrders(request.getMaxOrders());
        slot.setMaxUnits(request.getMaxUnits());
        slot.setReservedOrders(0);
        slot.setReservedUnits(0);
        slot.setActive(true);
        slot.setUpdatedAt(OffsetDateTime.now(clock));

        slot = slotRepository.save(slot);
        log.info("Created scheduled fulfilment slot {} for shop {} on {}", slot.getId(), shopId, slot.getServiceDate());
        return FulfilmentSlotDto.from(slot);
    }

    public FulfilmentSlotDto deactivateSlot(UUID shopId, UUID slotId) {
        shopAccessService.require(shopId, ShopRole.SHOP_MANAGER);
        UUID tenantId = requireTenantId();
        shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        ShopFulfilmentSlot slot = slotRepository.findByIdAndShopId(slotId, shopId)
                .orElseThrow(() -> new ResourceNotFoundException("Fulfilment slot not found: " + slotId));
        slot.setActive(false);
        slot.setUpdatedAt(OffsetDateTime.now(clock));
        slot = slotRepository.save(slot);
        log.info("Deactivated scheduled fulfilment slot {} for shop {}", slotId, shopId);
        return FulfilmentSlotDto.from(slot);
    }

    /**
     * Lists active, pre-cutoff fulfilment slots with remaining capacity for a published shop.
     */
    @Transactional(readOnly = true)
    public List<FulfilmentSlotDto> listAvailablePublicSlots(UUID shopId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return slotRepository
                .findByShopIdAndActiveTrueAndCutoffAtAfterOrderByServiceDateAscWindowStartAsc(shopId, now)
                .stream()
                .filter(slot -> (slot.getMaxOrders() == null || slot.getReservedOrders() < slot.getMaxOrders())
                        && (slot.getMaxUnits() == null || slot.getReservedUnits() < slot.getMaxUnits()))
                .map(FulfilmentSlotDto::from)
                .toList();
    }

    /**
     * Validates and atomically reserves capacity on {@code slotId} for {@code order}
     * (ADR-0007 §2.2 / R-2). Snapshots {@code fulfilmentSlotId}, {@code scheduledForDate},
     * and {@code scheduledWindowLabel} onto {@code order}.
     */
    public ShopFulfilmentSlot reserveSlotForOrder(Shop shop, UUID slotId, Order order) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int units = resolveOrderUnits(order);

        ShopFulfilmentSlot slot = slotRepository.findByIdAndShopId(slotId, shop.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Fulfilment slot not found: " + slotId));

        if (!slot.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Selected fulfilment slot is no longer active.");
        }
        if (!slot.getCutoffAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Pre-order cutoff has passed for slot " + slot.formatWindowLabel() + ".");
        }

        int reserved = slotRepository.tryReserveCapacity(slotId, shop.getId(), units, now);
        if (reserved == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Selected fulfilment slot " + slot.formatWindowLabel() + " is full or past cutoff.");
        }

        order.setFulfilmentSlotId(slot.getId());
        order.setScheduledForDate(slot.getServiceDate());
        order.setScheduledWindowLabel(slot.formatWindowLabel());
        return slot;
    }

    /**
     * Releases reserved slot capacity when a scheduled order is cancelled or voided.
     */
    public void releaseSlotForOrder(Order order) {
        if (order == null || order.getFulfilmentSlotId() == null || order.getTenantId() == null) {
            return;
        }
        int units = resolveOrderUnits(order);
        slotRepository.releaseCapacity(order.getFulfilmentSlotId(), order.getTenantId(), units, OffsetDateTime.now(clock));
    }

    /**
     * Builds a shareable WhatsApp-to-Storefront pre-order link and pre-formatted broadcast/reply
     * message for a shop and optional scheduled fulfilment slot (ADR-0007 / GAP-GTM-06, #208).
     * Requires STAFF on the shop.
     */
    @Transactional(readOnly = true)
    public uk.jtoye.core.shop.dto.WhatsAppPreOrderLinkDto buildWhatsAppPreOrderLink(UUID shopId, UUID slotId) {
        shopAccessService.require(shopId, ShopRole.STAFF);
        UUID tenantId = requireTenantId();
        Shop shop = shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        String encodedSlug = org.springframework.web.util.UriUtils.encodePathSegment(
                shop.getSlug(), java.nio.charset.StandardCharsets.UTF_8);

        if (slotId != null) {
            ShopFulfilmentSlot slot = slotRepository.findByIdAndShopId(slotId, shopId)
                    .orElseThrow(() -> new ResourceNotFoundException("Fulfilment slot not found: " + slotId));
            if (!slot.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Selected fulfilment slot is no longer active.");
            }
            if (!slot.getCutoffAt().isAfter(OffsetDateTime.now(clock))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Pre-order cutoff has passed for the selected fulfilment slot.");
            }
            boolean ordersFull = slot.getMaxOrders() != null && slot.getReservedOrders() >= slot.getMaxOrders();
            boolean unitsFull = slot.getMaxUnits() != null && slot.getReservedUnits() >= slot.getMaxUnits();
            if (ordersFull || unitsFull) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Selected fulfilment slot is already at full capacity.");
            }
            String storefrontPath = "/shop/" + encodedSlug + "?slot=" + slot.getId();
            String windowLabel = slot.formatWindowLabel();
            String prefilledMessage = "Pre-order from " + shop.getName()
                    + " for " + slot.getServiceDate() + " (" + windowLabel + ")"
                    + " — check allergens & complete your secure pre-order here: " + storefrontPath;
            String waMeUrl = "https://wa.me/?text=" + org.springframework.web.util.UriUtils.encodeQueryParam(
                    prefilledMessage, java.nio.charset.StandardCharsets.UTF_8);
            return new uk.jtoye.core.shop.dto.WhatsAppPreOrderLinkDto(
                    shop.getId(),
                    shop.getSlug(),
                    shop.getName(),
                    slot.getId(),
                    slot.getServiceDate(),
                    windowLabel,
                    slot.getCutoffAt(),
                    storefrontPath,
                    prefilledMessage,
                    waMeUrl
            );
        }

        String storefrontPath = "/shop/" + encodedSlug;
        String prefilledMessage = "Order from " + shop.getName()
                + " — view the menu, check allergens & complete your secure checkout here: " + storefrontPath;
        String waMeUrl = "https://wa.me/?text=" + org.springframework.web.util.UriUtils.encodeQueryParam(
                prefilledMessage, java.nio.charset.StandardCharsets.UTF_8);
        return new uk.jtoye.core.shop.dto.WhatsAppPreOrderLinkDto(
                shop.getId(),
                shop.getSlug(),
                shop.getName(),
                null,
                null,
                null,
                null,
                storefrontPath,
                prefilledMessage,
                waMeUrl
        );
    }

    private static int resolveOrderUnits(Order order) {
        if (order.getUnitCount() != null && order.getUnitCount() > 0) {
            return order.getUnitCount();
        }
        if (order.getItemCount() != null && order.getItemCount() > 0) {
            return order.getItemCount();
        }
        return 1;
    }

    private static UUID requireTenantId() {
        return TenantContext.get()
                .orElseThrow(() -> new IllegalStateException("Tenant context not set"));
    }
}
