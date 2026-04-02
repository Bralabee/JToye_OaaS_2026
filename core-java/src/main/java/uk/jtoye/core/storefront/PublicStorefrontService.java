package uk.jtoye.core.storefront;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderEventPublisher;
import uk.jtoye.core.order.OrderItem;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.product.Product;
import uk.jtoye.core.product.ProductRepository;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;
import uk.jtoye.core.storefront.dto.GuestOrderConfirmation;
import uk.jtoye.core.storefront.dto.GuestOrderItemRequest;
import uk.jtoye.core.storefront.dto.GuestOrderRequest;
import uk.jtoye.core.storefront.dto.PublicOrderStatus;
import uk.jtoye.core.storefront.dto.PublicProductDto;
import uk.jtoye.core.storefront.dto.PublicShopDto;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PublicStorefrontService {
    private static final Logger log = LoggerFactory.getLogger(PublicStorefrontService.class);

    private final ShopRepository shopRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderEventPublisher eventPublisher;
    private final EntityManager entityManager;

    public PublicStorefrontService(ShopRepository shopRepository, ProductRepository productRepository,
                                   OrderRepository orderRepository, OrderEventPublisher eventPublisher,
                                   EntityManager entityManager) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    /**
     * List published shops. The public RLS policy allows SELECT on published=true
     * without tenant context.
     */
    public Page<PublicShopDto> listPublishedShops(Pageable pageable) {
        log.debug("Listing published shops, page {}", pageable.getPageNumber());
        return shopRepository.findByPublishedTrue(pageable)
                .map(this::toPublicShopDto);
    }

    /**
     * Search published shops by name or tags.
     */
    public Page<PublicShopDto> searchPublishedShops(String query, Pageable pageable) {
        log.debug("Searching published shops: '{}'", query);
        return shopRepository.searchPublished(query, pageable)
                .map(this::toPublicShopDto);
    }

    /**
     * Get a single published shop by slug.
     */
    public PublicShopDto getShopBySlug(String slug) {
        log.debug("Fetching published shop: {}", slug);
        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));
        return toPublicShopDto(shop);
    }

    /**
     * Get available products for a published shop, grouped by category.
     * Sets TenantContext from the shop's tenant_id so RLS allows product queries.
     */
    public Map<String, List<PublicProductDto>> getShopProducts(String slug) {
        log.debug("Fetching products for shop: {}", slug);

        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        // Set tenant context so product queries work through RLS
        TenantContext.set(shop.getTenantId());
        try {
            List<Product> products = productRepository.findAvailableOrderedByCategory();

            // Group by category, preserving order; uncategorized items go under "Other"
            return products.stream()
                    .map(this::toPublicProductDto)
                    .collect(Collectors.groupingBy(
                            p -> p.getCategory() != null ? p.getCategory() : "Other",
                            LinkedHashMap::new,
                            Collectors.toList()
                    ));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Track a guest order by order number + email verification.
     * Sets RLS session variables so the tracking policy allows the SELECT.
     */
    public PublicOrderStatus trackOrder(String orderNumber, String email) {
        log.debug("Tracking order {} with email {}", orderNumber, email);

        // Set session variables for the RLS tracking policy
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_order_number', ?, true)")) {
                stmt.setString(1, orderNumber);
                stmt.execute();
            }
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tracking_email', ?, true)")) {
                stmt.setString(1, email);
                stmt.execute();
            }
        });

        Order order = orderRepository.findByOrderNumberAndCustomerEmail(orderNumber, email)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order not found. Check your order number and email address."));

        // Look up shop name
        String shopName = shopRepository.findById(order.getShopId())
                .map(Shop::getName)
                .orElse("Unknown shop");

        PublicOrderStatus status = new PublicOrderStatus();
        status.setOrderNumber(order.getOrderNumber());
        status.setStatus(order.getStatus().name());
        status.setShopName(shopName);
        status.setTotalAmountPennies(order.getTotalAmountPennies());
        status.setItemCount(order.getItems().size());
        status.setCreatedAt(order.getCreatedAt());
        status.setUpdatedAt(order.getUpdatedAt());
        return status;
    }

    /**
     * Create a guest order for a published shop.
     * Sets TenantContext, creates order as PENDING, recalculates prices server-side.
     */
    @Transactional
    public GuestOrderConfirmation createGuestOrder(String slug, GuestOrderRequest request) {
        log.debug("Creating guest order for shop: {}", slug);

        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        UUID tenantId = shop.getTenantId();
        TenantContext.set(tenantId);
        try {
            Order order = new Order();
            order.setTenantId(tenantId);
            order.setShopId(shop.getId());
            order.setOrderNumber(generateOrderNumber(tenantId));
            order.setStatus(OrderStatus.PENDING); // Skip DRAFT for guest orders
            order.setCustomerName(request.getCustomerName());
            order.setCustomerEmail(request.getCustomerEmail());
            order.setCustomerPhone(request.getCustomerPhone());
            order.setNotes(request.getNotes());
            order.setUpdatedAt(OffsetDateTime.now());

            // Add items with server-side price lookup
            for (GuestOrderItemRequest itemReq : request.getItems()) {
                Product product = productRepository.findById(itemReq.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product not found: " + itemReq.getProductId()));

                if (!Boolean.TRUE.equals(product.getAvailable())) {
                    throw new IllegalArgumentException("Product is not available: " + product.getTitle());
                }

                OrderItem item = new OrderItem(
                        product.getId(),
                        itemReq.getQuantity(),
                        product.getPricePennies() // Server-side price — never trust client
                );
                item.setTenantId(tenantId);
                order.addItem(item);
            }

            order.calculateTotal();
            order = orderRepository.save(order);

            log.info("Created guest order {} with {} items, total: {} pennies for shop {}",
                    order.getOrderNumber(), order.getItems().size(),
                    order.getTotalAmountPennies(), shop.getName());

            // Publish event AFTER transaction commits so the order is visible to the listener
            final Order savedOrder = order;
            final UUID finalTenantId = tenantId;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishStateChange(
                            savedOrder.getId(), finalTenantId, savedOrder.getOrderNumber(),
                            OrderStatus.DRAFT, OrderStatus.PENDING
                    );
                }
            });

            return new GuestOrderConfirmation(
                    order.getOrderNumber(),
                    order.getStatus().name(),
                    order.getTotalAmountPennies(),
                    shop.getName(),
                    order.getItems().size()
            );
        } finally {
            TenantContext.clear();
        }
    }

    private String generateOrderNumber(UUID tenantId) {
        String tenantPrefix = tenantId.toString().replace("-", "").substring(0, 8).toUpperCase();
        String datePart = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return String.format("ORD-%s-%s-%s", tenantPrefix, datePart, randomSuffix);
    }

    private PublicShopDto toPublicShopDto(Shop shop) {
        PublicShopDto dto = new PublicShopDto();
        dto.setSlug(shop.getSlug());
        dto.setName(shop.getName());
        dto.setDescription(shop.getDescription());
        dto.setAddress(shop.getAddress());
        dto.setLogoUrl(shop.getLogoUrl());
        dto.setBannerUrl(shop.getBannerUrl());
        dto.setPhone(shop.getPhone());
        dto.setEmail(shop.getEmail());
        dto.setLatitude(shop.getLatitude());
        dto.setLongitude(shop.getLongitude());
        dto.setOpeningHours(shop.getOpeningHours());
        dto.setDeliveryInfo(shop.getDeliveryInfo());
        dto.setMinimumOrderPennies(shop.getMinimumOrderPennies());
        dto.setTags(shop.getTags());
        return dto;
    }

    private PublicProductDto toPublicProductDto(Product product) {
        PublicProductDto dto = new PublicProductDto();
        dto.setId(product.getId());
        dto.setTitle(product.getTitle());
        dto.setDescription(product.getDescription());
        dto.setImageUrl(product.getImageUrl());
        dto.setIngredientsText(product.getIngredientsText());
        dto.setAllergenMask(product.getAllergenMask());
        dto.setPricePennies(product.getPricePennies());
        dto.setCategory(product.getCategory());
        dto.setDietaryTags(product.getDietaryTags());
        dto.setPreparationTimeMinutes(product.getPreparationTimeMinutes());
        dto.setFeatured(product.getFeatured());
        return dto;
    }
}
