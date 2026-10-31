package uk.jtoye.core.payment;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.customer.PhoneVerificationService;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.notification.dispatch.NotificationMessage;
import uk.jtoye.core.notification.dispatch.WhatsAppSmsChannel;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.security.access.ShopRole;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Issues and redeems single-use expiring payment links for verified-phone orders
 * (Phase 30, #461 / #462 / PAY-04 / B5).
 *
 * <p>Enforces:
 * <ul>
 *   <li>Customer phone must be verified ({@code orders.phone_verified = true}) before any
 *       {@code UPFRONT}, {@code DEPOSIT}, or {@code BALANCE} payment link can be generated.</li>
 *   <li>Only the SHA-256 hex digest of the 32-byte URL-safe token is persisted.</li>
 *   <li>Prior unused links for the same order + purpose are automatically revoked on re-issue.</li>
 *   <li>Single-use redemption stamps {@code used_at} and returns the live Stripe {@code clientSecret}.</li>
 *   <li>A read-only preview (30-16, D-03) answers who is asking, how much and whether the link still
 *       works WITHOUT stamping {@code used_at} and without calling Stripe, so a link scanner or a
 *       curious tap cannot burn a single-use link.</li>
 * </ul>
 *
 * <p>The issued URL carries the token in the fragment, {@code <base>/pay#token=<raw>} (owner decision
 * 2026-10-31, plan 30-16 Task 1): no browser sends a fragment to a server, so the token never appears
 * in a request line or an access log, the V75 / V77 bearer-token rule. The page posts it in a JSON body.
 */
@Service
public class OrderPaymentLinkService {

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentLinkService.class);
    /** One body for every unknown token, on redeem and preview alike. */
    private static final String LINK_NOT_FOUND = "Payment link not found.";

    private final OrderPaymentLinkRepository linkRepository;
    private final OrderRepository orderRepository;
    private final PhoneVerificationService phoneVerificationService;
    private final PaymentService paymentService;
    private final ShopAccessService shopAccessService;
    private final WhatsAppSmsChannel whatsAppSmsChannel;
    private final EntityManager entityManager;
    private final ShopRepository shopRepository;
    private final StripeProperties stripeProperties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String baseUrl;
    private final int defaultTtlMinutes;

    public OrderPaymentLinkService(
            OrderPaymentLinkRepository linkRepository,
            OrderRepository orderRepository,
            PhoneVerificationService phoneVerificationService,
            PaymentService paymentService,
            ShopAccessService shopAccessService,
            WhatsAppSmsChannel whatsAppSmsChannel,
            EntityManager entityManager,
            ShopRepository shopRepository,
            StripeProperties stripeProperties,
            @Value("${jtoye.payment-links.base-url:${notification.email.tracking-base-url:http://localhost:3000}}") String baseUrl,
            @Value("${jtoye.payment-links.default-ttl-minutes:60}") int defaultTtlMinutes) {
        this.linkRepository = linkRepository;
        this.orderRepository = orderRepository;
        this.phoneVerificationService = phoneVerificationService;
        this.paymentService = paymentService;
        this.shopAccessService = shopAccessService;
        this.whatsAppSmsChannel = whatsAppSmsChannel;
        this.entityManager = entityManager;
        this.shopRepository = shopRepository;
        this.stripeProperties = stripeProperties;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.defaultTtlMinutes = defaultTtlMinutes;
    }

    public record IssuedPaymentLinkDto(
            UUID id,
            UUID orderId,
            String orderNumber,
            String purpose,
            long amountPennies,
            String paymentUrl,
            String token,
            String stripePaymentIntentId,
            String clientSecret,
            OffsetDateTime expiresAt
    ) {}

    public record RedeemedPaymentLinkDto(
            UUID linkId,
            UUID orderId,
            String purpose,
            long amountPennies,
            String stripePaymentIntentId,
            String clientSecret,
            OffsetDateTime usedAt,
            @Schema(description = "Connected Stripe account that owns the PaymentIntent; pass as stripeAccount to "
                    + "Stripe.js. Null for platform and destination charges.")
            String stripeAccountId
    ) {}

    /**
     * What a payment-link holder may see before deciding to pay (UI-SPEC S3 data contract 1): who is
     * asking, for how much, and whether the link still works. Carries no token, no client secret and no
     * customer data.
     */
    @Schema(description = "Read-only preview of a single-use payment link. Reading it does not use the link up.")
    public record PaymentLinkPreviewDto(
            @Schema(description = "The shop's trading name.") String shopName,
            String orderNumber,
            @Schema(description = "The amount this link asks for, in minor units.") long amountPennies,
            @Schema(description = "ISO 4217 currency, lower case, as the payment intent was created.", example = "gbp")
            String currency,
            @Schema(allowableValues = {"UPFRONT", "DEPOSIT", "BALANCE"}) String purpose,
            OffsetDateTime expiresAt,
            @Schema(description = "PAID beats USED beats REVOKED beats EXPIRED beats ACTIVE. PAID: the order's "
                    + "payment is CAPTURED (UPFRONT, DEPOSIT) or nothing is left to pay (BALANCE).",
                    allowableValues = {"ACTIVE", "USED", "EXPIRED", "REVOKED", "PAID"})
            String state
    ) {}

    /**
     * Issue a single-use expiring payment link for {@code orderId} and dispatch via SMS/WhatsApp.
     */
    @Transactional
    public IssuedPaymentLinkDto issuePaymentLink(UUID orderId, String rawPurpose, Integer ttlMinutesOverride) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        shopAccessService.require(order.getShopId(), ShopRole.STAFF);

        if (order.isVoided()
                || order.getStatus() == OrderStatus.CANCELLED
                || order.getStatus() == OrderStatus.REFUNDED
                || order.getStatus() == OrderStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order " + order.getOrderNumber() + " cannot accept a payment link in status " + order.getStatus());
        }

        // Check if phone is verified on the order or via a recent verified challenge / customer record
        boolean verified = order.isPhoneVerified();
        if (!verified && order.getCustomerPhone() != null) {
            verified = phoneVerificationService.isPhoneVerified(order.getTenantId(), order.getCustomerPhone());
            if (verified) {
                order.setPhoneVerified(true);
                order.setUpdatedAt(OffsetDateTime.now());
                orderRepository.saveAndFlush(order);
            }
        }
        if (!verified) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Customer phone number must be verified before issuing a payment link (#461 / #462).");
        }

        String purpose = normalizePurpose(rawPurpose);
        long amountPennies = resolveAmountForPurpose(order, purpose);

        int ttlMinutes = (ttlMinutesOverride != null && ttlMinutesOverride > 0)
                ? Math.min(ttlMinutesOverride, 10080) // cap at 7 days
                : defaultTtlMinutes;

        // Revoke any prior unused active links for the same order + purpose
        OffsetDateTime now = OffsetDateTime.now();
        List<OrderPaymentLink> existingLinks = linkRepository.findByTenantIdAndOrderIdOrderByCreatedAtDesc(
                order.getTenantId(), order.getId());
        for (OrderPaymentLink existing : existingLinks) {
            if (purpose.equals(existing.getPurpose())
                    && existing.getUsedAt() == null
                    && existing.getRevokedAt() == null) {
                existing.setRevokedAt(now);
                linkRepository.save(existing);
            }
        }

        if (!paymentService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Stripe payment processing is not configured.");
        }

        PaymentIntentResult intentResult;
        try {
            if ("BALANCE".equals(purpose)) {
                intentResult = paymentService.createBalancePaymentIntent(order);
            } else {
                intentResult = paymentService.createPaymentIntent(order);
                order.setPaymentReference(intentResult.paymentIntentId());
                if (order.getPaymentStatus() == null || order.getPaymentStatus() == PaymentStatus.NONE) {
                    order.setPaymentStatus(PaymentStatus.PENDING);
                }
                order.setUpdatedAt(now);
                orderRepository.saveAndFlush(order);
            }
        } catch (StripeException e) {
            log.error("Failed to create Stripe PaymentIntent for payment link on order {}: {}",
                    order.getOrderNumber(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Failed to initialize Stripe PaymentIntent for payment link.");
        }

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenSha256 = PhoneVerificationService.sha256Hex(rawToken);
        OffsetDateTime expiresAt = now.plusMinutes(ttlMinutes);

        OrderPaymentLink link = new OrderPaymentLink();
        link.setTenantId(order.getTenantId());
        link.setOrderId(order.getId());
        link.setShopId(order.getShopId());
        link.setTokenSha256(tokenSha256);
        link.setPurpose(purpose);
        link.setAmountPennies(amountPennies);
        link.setPhoneVerified(true);
        link.setStripePaymentIntentId(intentResult.paymentIntentId());
        link.setExpiresAt(expiresAt);
        link = linkRepository.saveAndFlush(link);

        String paymentUrl = baseUrl + "/pay#token=" + rawToken;
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            String phoneE164 = PhoneVerificationService.normalizePhone(order.getCustomerPhone());
            whatsAppSmsChannel.deliver(new NotificationMessage(
                    order.getTenantId(),
                    phoneE164,
                    "order.payment_link.issued",
                    null,
                    String.format(Locale.ROOT,
                            "Pay £%.2f (%s) for J'Toye order %s: %s (single-use link, expires at %s)",
                            amountPennies / 100.0, purpose, order.getOrderNumber(), paymentUrl, expiresAt),
                    null,
                    null
            ));
        }

        log.info("Issued single-use {} payment link {} for order {} ({} pennies, PI {})",
                purpose, link.getId(), order.getOrderNumber(), amountPennies, intentResult.paymentIntentId());

        return new IssuedPaymentLinkDto(
                link.getId(),
                order.getId(),
                order.getOrderNumber(),
                purpose,
                amountPennies,
                paymentUrl,
                rawToken,
                intentResult.paymentIntentId(),
                intentResult.clientSecret(),
                expiresAt
        );
    }

    /**
     * Redeem a single-use payment link token from the public storefront.
     *
     * <p>Sets {@code app.payment_link_token_sha256} for the initial RLS SELECT, then pins
     * {@code app.current_tenant_id} and {@link TenantContext} so the single-use {@code used_at}
     * stamp commits under the link's tenant.
     */
    @Transactional
    public RedeemedPaymentLinkDto redeemPaymentLink(String rawToken) {
        return withLinkTenant(rawToken, link -> {
            if (link.getRevokedAt() != null) {
                throw new ResponseStatusException(HttpStatus.GONE,
                        "This payment link has been revoked.");
            }
            if (link.getUsedAt() != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This single-use payment link has already been used.");
            }
            OffsetDateTime now = OffsetDateTime.now();
            if (link.getExpiresAt().isBefore(now)) {
                throw new ResponseStatusException(HttpStatus.GONE,
                        "This payment link has expired.");
            }

            // A-4 (PR #949 round 1): claim atomically. Two concurrent redemptions can both read
            // used_at IS NULL above; only one conditional UPDATE matches a row.
            int claimed = linkRepository.markUsedIfUnused(link.getId(), now);
            if (claimed != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This single-use payment link has already been used.");
            }
            link.setUsedAt(now);

            String clientSecret = null;
            String stripeAccountId = null;
            if (link.getStripePaymentIntentId() != null && paymentService.isConfigured()) {
                Order order = orderRepository.findById(link.getOrderId()).orElse(null);
                // 30-17 (UI-SPEC § S1): the account the secret below is retrieved on, from the same
                // routing rule (direct-charge header only), read under this link's pinned tenant.
                stripeAccountId = order == null ? null : paymentService.directChargeAccountFor(order).orElse(null);
                try {
                    // A-1: order-aware retrieve carries the Stripe-Account header for
                    // direct-charge / split-deposit intents on the vendor's connected account.
                    clientSecret = paymentService.retrieveClientSecret(order, link.getStripePaymentIntentId());
                } catch (StripeException e) {
                    log.error("Failed to retrieve Stripe client secret for payment link {}: {}",
                            link.getId(), e.getMessage());
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Unable to load payment session from provider.");
                }
            }

            log.info("Redeemed single-use payment link {} for order {} (purpose={})",
                    link.getId(), link.getOrderId(), link.getPurpose());

            return new RedeemedPaymentLinkDto(
                    link.getId(),
                    link.getOrderId(),
                    link.getPurpose(),
                    link.getAmountPennies(),
                    link.getStripePaymentIntentId(),
                    clientSecret,
                    now,
                    stripeAccountId
            );
        });
    }

    /**
     * Preview a payment link without spending it (30-16, D-03, UI-SPEC S3 data contract 1).
     *
     * <p>Resolves the token exactly as {@link #redeemPaymentLink} does (tenant-less bearer lookup under
     * the token-scoped RLS policy, then the link's own tenant pinned for the order and shop reads, the
     * caller's {@link TenantContext} restored afterwards), then reports the link's state. It writes
     * nothing: no {@code markUsedIfUnused}, no Stripe call. An unknown token is the same 404 as redeem.
     */
    @Transactional(readOnly = true)
    public PaymentLinkPreviewDto previewPaymentLink(String rawToken) {
        return withLinkTenant(rawToken, link -> {
            Order order = orderRepository.findById(link.getOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException(LINK_NOT_FOUND));
            String shopName = shopRepository.findById(link.getShopId())
                    .map(Shop::getName)
                    .orElseThrow(() -> new ResourceNotFoundException(LINK_NOT_FOUND));
            return new PaymentLinkPreviewDto(
                    shopName,
                    order.getOrderNumber(),
                    link.getAmountPennies(),
                    stripeProperties.getCurrency(),
                    link.getPurpose(),
                    link.getExpiresAt(),
                    previewState(link, order, OffsetDateTime.now()));
        });
    }

    /**
     * The preview state, in precedence order PAID &gt; USED &gt; REVOKED &gt; EXPIRED &gt; ACTIVE. PAID is
     * the order's truth, not the link's: an UPFRONT or DEPOSIT link is paid once the order's payment is
     * CAPTURED, a BALANCE link once nothing is left due (a captured deposit with a balance still owed is
     * not a paid balance). Expiry uses the same comparison as redeem, so the two never disagree.
     */
    static String previewState(OrderPaymentLink link, Order order, OffsetDateTime now) {
        boolean paid = "BALANCE".equals(link.getPurpose())
                ? order.getBalanceDuePennies() != null && order.getBalanceDuePennies() == 0L
                : order.getPaymentStatus() == PaymentStatus.CAPTURED;
        if (paid) {
            return "PAID";
        }
        if (link.getUsedAt() != null) {
            return "USED";
        }
        if (link.getRevokedAt() != null) {
            return "REVOKED";
        }
        if (link.getExpiresAt().isBefore(now)) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    /**
     * The bearer resolution shared by redeem and preview: find the link by its token's digest with no
     * tenant pinned, pin the link's tenant, run {@code work}, and restore the caller's context.
     */
    private <T> T withLinkTenant(String rawToken, Function<OrderPaymentLink, T> work) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Payment link token is required.");
        }
        String tokenSha256 = PhoneVerificationService.sha256Hex(rawToken.trim());

        // A-3 (PR #949 round 1): the bearer lookup must run tenant-less so the token-scoped
        // order_payment_links_public_select policy applies. An ambient TenantContext (an
        // authenticated user of another tenant clicking the link) would otherwise pin the GUC
        // to the wrong tenant and both policies would hide the row. Restored in finally.
        UUID previousTenant = TenantContext.get().orElse(null);
        TenantContext.clear();

        Session session = entityManager.unwrap(Session.class);
        try {
            session.doWork(connection -> {
                try (var stmt = connection.prepareStatement(
                        "SELECT set_config('app.current_tenant_id', '', true), "
                                + "set_config('app.payment_link_token_sha256', ?, true)")) {
                    stmt.setString(1, tokenSha256);
                    stmt.execute();
                }
            });

            OrderPaymentLink link = linkRepository.findByTokenSha256(tokenSha256)
                    .orElseThrow(() -> new ResourceNotFoundException(LINK_NOT_FOUND));

            TenantContext.set(link.getTenantId());
            session.doWork(connection -> {
                try (var stmt = connection.prepareStatement(
                        "SELECT set_config('app.current_tenant_id', ?, true)")) {
                    stmt.setString(1, link.getTenantId().toString());
                    stmt.execute();
                }
            });

            return work.apply(link);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private static String normalizePurpose(String rawPurpose) {
        if (rawPurpose == null || rawPurpose.isBlank()) {
            return "UPFRONT";
        }
        String upper = rawPurpose.trim().toUpperCase(Locale.ROOT);
        if (!"UPFRONT".equals(upper) && !"DEPOSIT".equals(upper) && !"BALANCE".equals(upper)) {
            throw new IllegalArgumentException("Payment link purpose must be UPFRONT, DEPOSIT, or BALANCE.");
        }
        return upper;
    }

    private static long resolveAmountForPurpose(Order order, String purpose) {
        if ("BALANCE".equals(purpose)) {
            if (order.getBalanceDuePennies() == null || order.getBalanceDuePennies() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Order " + order.getOrderNumber() + " has no remaining balance due.");
            }
            return order.getBalanceDuePennies();
        }
        if ("DEPOSIT".equals(purpose)) {
            if (order.getDepositAmountPennies() != null && order.getDepositAmountPennies() > 0) {
                return order.getDepositAmountPennies();
            }
        }
        if (order.getTotalAmountPennies() == null || order.getTotalAmountPennies() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order " + order.getOrderNumber() + " has a non-positive total amount.");
        }
        return order.getTotalAmountPennies();
    }
}
