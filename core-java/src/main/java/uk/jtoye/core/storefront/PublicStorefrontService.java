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
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.finance.VatRate;
import uk.jtoye.core.payment.PaymentService;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final PaymentService paymentService;

    public PublicStorefrontService(ShopRepository shopRepository, ProductRepository productRepository,
                                   OrderRepository orderRepository, OrderEventPublisher eventPublisher,
                                   EntityManager entityManager, PaymentService paymentService) {
        this.shopRepository = shopRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
        this.paymentService = paymentService;
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
     * Filters to products assigned to this shop (or unassigned = tenant-wide).
     */
    public Map<String, List<PublicProductDto>> getShopProducts(String slug) {
        log.debug("Fetching products for shop: {}", slug);

        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        // Set tenant context so product queries work through RLS
        TenantContext.set(shop.getTenantId());
        try {
            // Filter: products assigned to this shop OR unassigned (shop_id IS NULL = tenant-wide)
            List<Product> products = productRepository.findAvailableByShopOrderedByCategory(shop.getId());

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
     * List all orders for a customer email. Sets session variable for RLS policy.
     */
    public List<PublicOrderStatus> getCustomerOrders(String email) {
        log.debug("Fetching order history for {}", email);

        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.customer_email', ?, true)")) {
                stmt.setString(1, email);
                stmt.execute();
            }
        });

        List<Order> orders = orderRepository.findByCustomerEmailOrderByCreatedAtDesc(email);

        return orders.stream().map(order -> {
            String shopName = shopRepository.findById(order.getShopId())
                    .map(Shop::getName)
                    .orElse("Unknown shop");

            PublicOrderStatus status = new PublicOrderStatus();
            status.setOrderNumber(order.getOrderNumber());
            status.setStatus(order.getStatus().name());
            status.setPaymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "NONE");
            status.setShopName(shopName);
            status.setSubtotalPennies(order.getSubtotalPennies());
            status.setVatRate(order.getVatRate() != null ? order.getVatRate().name() : "ZERO");
            status.setVatAmountPennies(order.getVatAmountPennies() != null ? order.getVatAmountPennies() : 0L);
            status.setTotalAmountPennies(order.getTotalAmountPennies());
            status.setItemCount(order.getItemCount() != null ? order.getItemCount() : 0);
            status.setCreatedAt(order.getCreatedAt());
            status.setUpdatedAt(order.getUpdatedAt());
            return status;
        }).toList();
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
        status.setPaymentStatus(order.getPaymentStatus() != null ? order.getPaymentStatus().name() : "NONE");
        status.setShopName(shopName);
        status.setTotalAmountPennies(order.getTotalAmountPennies());
        status.setItemCount(order.getItemCount() != null ? order.getItemCount() : 0);
        status.setCreatedAt(order.getCreatedAt());
        status.setUpdatedAt(order.getUpdatedAt());
        return status;
    }

    /**
     * Create a guest order for a published shop.
     * Creates order as DRAFT, creates a Stripe PaymentIntent, and returns the client secret.
     * Order transitions to PENDING only after successful payment via webhook.
     */
    @Transactional
    public GuestOrderConfirmation createGuestOrder(String slug, GuestOrderRequest request) {
        log.debug("Creating guest order for shop: {}", slug);

        Shop shop = shopRepository.findBySlugAndPublishedTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + slug));

        // Enforce opening hours — reject orders when shop is closed
        validateShopIsOpen(shop);

        UUID tenantId = shop.getTenantId();
        TenantContext.set(tenantId);
        try {
            Order order = new Order();
            order.setTenantId(tenantId);
            order.setShopId(shop.getId());
            order.setOrderNumber(generateOrderNumber(tenantId));
            order.setStatus(OrderStatus.DRAFT);
            order.setPaymentStatus(PaymentStatus.PENDING);
            order.setCustomerName(request.getCustomerName());
            order.setCustomerEmail(request.getCustomerEmail());
            order.setCustomerPhone(request.getCustomerPhone());
            order.setNotes(request.getNotes());
            order.setVatRate(VatRate.STANDARD);
            order.setUpdatedAt(OffsetDateTime.now());

            // Add items with server-side price lookup + allergen cross-check
            List<String> allergenWarnings = new ArrayList<>();
            Integer customerAllergenMask = request.getCustomerAllergenMask();

            for (GuestOrderItemRequest itemReq : request.getItems()) {
                Product product = productRepository.findById(itemReq.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product not found: " + itemReq.getProductId()));

                if (!Boolean.TRUE.equals(product.getAvailable())) {
                    throw new IllegalArgumentException("Product is not available: " + product.getTitle());
                }

                // Validate stock
                if (!product.hasStock(itemReq.getQuantity())) {
                    throw new IllegalArgumentException(
                            "Insufficient stock for '" + product.getTitle() + "': requested "
                                    + itemReq.getQuantity() + ", available " + product.getQuantityInStock());
                }

                // Cross-check allergens if customer provided restrictions
                if (customerAllergenMask != null && customerAllergenMask != 0
                        && product.getAllergenMask() != null && product.getAllergenMask() != 0) {
                    int conflict = customerAllergenMask & product.getAllergenMask();
                    if (conflict != 0) {
                        allergenWarnings.add(product.getTitle() + " contains allergens you've flagged: "
                                + describeAllergens(conflict));
                    }
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

            // Create Stripe PaymentIntent
            String clientSecret;
            try {
                clientSecret = paymentService.createPaymentIntent(order);
            } catch (com.stripe.exception.StripeException e) {
                log.error("Failed to create PaymentIntent for order {}", order.getOrderNumber(), e);
                throw new RuntimeException("Payment processing unavailable. Please try again later.");
            }

            log.info("Created guest order {} with {} items, total: {} pennies for shop {} (awaiting payment)",
                    order.getOrderNumber(), order.getItems().size(),
                    order.getTotalAmountPennies(), shop.getName());

            return new GuestOrderConfirmation(
                    order.getOrderNumber(),
                    order.getStatus().name(),
                    order.getSubtotalPennies(),
                    order.getVatRate().name(),
                    order.getVatAmountPennies(),
                    order.getTotalAmountPennies(),
                    shop.getName(),
                    order.getItems().size(),
                    clientSecret,
                    allergenWarnings
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

    private static final String[] ALLERGEN_NAMES = {
            "Gluten", "Crustaceans", "Eggs", "Fish", "Peanuts", "Soybeans",
            "Milk", "Nuts", "Celery", "Mustard", "Sesame", "Sulphites", "Lupin", "Molluscs"
    };

    private static String describeAllergens(int mask) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ALLERGEN_NAMES.length; i++) {
            if ((mask & (1 << i)) != 0) {
                names.add(ALLERGEN_NAMES[i]);
            }
        }
        return String.join(", ", names);
    }

    private static final Pattern HOURS_PATTERN = Pattern.compile("(\\d{2}):(\\d{2})\\s*-\\s*(\\d{2}):(\\d{2})");
    private static final Map<DayOfWeek, String> DAY_KEYS = Map.of(
            DayOfWeek.MONDAY, "mon", DayOfWeek.TUESDAY, "tue", DayOfWeek.WEDNESDAY, "wed",
            DayOfWeek.THURSDAY, "thu", DayOfWeek.FRIDAY, "fri", DayOfWeek.SATURDAY, "sat",
            DayOfWeek.SUNDAY, "sun"
    );

    private void validateShopIsOpen(Shop shop) {
        Map<String, String> hours = shop.getOpeningHours();
        if (hours == null || hours.isEmpty()) {
            // No hours configured = always open
            return;
        }

        String dayKey = DAY_KEYS.get(LocalDate.now().getDayOfWeek());
        String todayHours = hours.get(dayKey);
        if (todayHours == null || todayHours.equalsIgnoreCase("closed")) {
            throw new IllegalArgumentException(
                    shop.getName() + " is closed today. Please check opening hours and try again later.");
        }

        Matcher m = HOURS_PATTERN.matcher(todayHours);
        if (!m.find()) {
            // Unparseable hours format — allow the order (fail open)
            return;
        }

        LocalTime open = LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        LocalTime close = LocalTime.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));
        LocalTime now = LocalTime.now();

        if (now.isBefore(open) || !now.isBefore(close)) {
            throw new IllegalArgumentException(
                    shop.getName() + " is currently closed. Opening hours today: " + todayHours + ". Please try again later.");
        }
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
        dto.setInStock(product.hasStock());

        // Build combined image URLs list: primary first, then additional
        List<String> allImages = new ArrayList<>();
        if (product.getImageUrl() != null && !product.getImageUrl().isBlank()) {
            allImages.add(product.getImageUrl());
        }
        if (product.getAdditionalImageUrls() != null) {
            allImages.addAll(product.getAdditionalImageUrls());
        }
        dto.setImageUrls(allImages);

        return dto;
    }
}
