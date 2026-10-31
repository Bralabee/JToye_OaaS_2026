package uk.jtoye.core.payment;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.customer.Customer;
import uk.jtoye.core.customer.CustomerRepository;
import uk.jtoye.core.customer.PhoneVerificationChallenge;
import uk.jtoye.core.customer.PhoneVerificationChallengeRepository;
import uk.jtoye.core.customer.PhoneVerificationService;
import uk.jtoye.core.notification.dispatch.WhatsAppSmsChannel;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.order.PaymentStatus;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.security.access.ShopAccessService;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.shop.ShopRepository;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PhoneVerificationService} (#462) and {@link OrderPaymentLinkService}
 * (#461 / PAY-04 / B5): OTP challenge/verify, verified-phone-only payment link issuance,
 * single-use token redemption, and prior-link revocation.
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationAndPaymentLinkTest {

    @Mock private PhoneVerificationChallengeRepository challengeRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private OrderPaymentLinkRepository linkRepository;
    @Mock private PaymentService paymentService;
    @Mock private ShopAccessService shopAccessService;
    @Mock private WhatsAppSmsChannel whatsAppSmsChannel;
    @Mock private EntityManager entityManager;
    @Mock private Session hibernateSession;
    @Mock private ShopRepository shopRepository;

    private PhoneVerificationService phoneVerificationService;
    private OrderPaymentLinkService paymentLinkService;

    private UUID tenantId;
    private UUID shopId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        shopId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        TenantContext.set(tenantId);

        phoneVerificationService = new PhoneVerificationService(
                challengeRepository,
                customerRepository,
                orderRepository,
                whatsAppSmsChannel,
                10,
                5,
                true,
                // 30-17: the seam is on here, so the runtime must declare an allow-listed environment.
                "test"
        );

        paymentLinkService = new OrderPaymentLinkService(
                linkRepository,
                orderRepository,
                phoneVerificationService,
                paymentService,
                shopAccessService,
                whatsAppSmsChannel,
                entityManager,
                shopRepository,
                new StripeProperties(),
                "https://shop.jtoye.co.uk",
                60
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("PhoneVerificationService normalizes UK numbers to E.164, verifies 6-digit OTP, and stamps Customer + open Order")
    void phoneVerification_challengeAndVerify_stampsCustomerAndOpenOrder() {
        AtomicReference<PhoneVerificationChallenge> savedChallenge = new AtomicReference<>();
        when(challengeRepository.saveAndFlush(any(PhoneVerificationChallenge.class))).thenAnswer(inv -> {
            PhoneVerificationChallenge c = inv.getArgument(0);
            if (c.getId() == null) {
                c.setId(UUID.randomUUID());
            }
            savedChallenge.set(c);
            return c;
        });

        PhoneVerificationService.ChallengeResult challenge = phoneVerificationService.sendChallenge(
                tenantId, null, "07700 900123", "whatsapp");

        assertThat(challenge.phoneNormalized()).isEqualTo("+447700900123");
        assertThat(challenge.channel()).isEqualTo("WHATSAPP");
        assertThat(challenge.testPreviewOtp()).matches("^[0-9]{6}$");
        verify(whatsAppSmsChannel).deliver(any());

        when(challengeRepository.findTopByTenantIdAndPhoneNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(
                tenantId, "+447700900123"))
                .thenReturn(Optional.of(savedChallenge.get()));
        lenient().when(challengeRepository.consumeAttemptIfAvailable(any(UUID.class))).thenReturn(1);
        lenient().when(challengeRepository.markVerified(any(UUID.class), any(OffsetDateTime.class))).thenReturn(1);

        Customer customer = new Customer("Ada Lovelace", "ada@example.com");
        customer.setTenantId(tenantId);
        customer.setPhone("07700 900123");
        when(customerRepository.findByTenantIdAndPhoneIsNotNull(tenantId)).thenReturn(List.of(customer));

        Order openOrder = buildOrder(OrderStatus.PENDING, 2500L, false);
        openOrder.setCustomerPhone("+44 7700 900123");
        when(orderRepository.findByTenantIdAndCustomerPhoneIsNotNullAndStatusIn(eq(tenantId), any()))
                .thenReturn(List.of(openOrder));

        // Fail first with a wrong OTP — consumeAttemptIfAvailable increments in DB, while the
        // managed entity must NOT be dirtied in memory (preventing Hibernate auto-flush overwrite)
        assertThatThrownBy(() -> phoneVerificationService.verifyOtp(tenantId, "+447700900123", "000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid verification code");
        assertThat(savedChallenge.get().getAttempts())
                .as("Managed challenge entity must not be dirtied in memory after atomic DB increment")
                .isZero();

        // Pass with the real OTP — markVerified updates verified_at atomically without saveAndFlush
        PhoneVerificationService.VerifyResult verified = phoneVerificationService.verifyOtp(
                tenantId, "07700900123", challenge.testPreviewOtp());

        assertThat(verified.verified()).isTrue();
        assertThat(verified.phoneNormalized()).isEqualTo("+447700900123");
        verify(challengeRepository).markVerified(eq(savedChallenge.get().getId()), any(OffsetDateTime.class));
        assertThat(customer.isPhoneVerified()).isTrue();
        assertThat(customer.getPhoneVerifiedAt()).isNotNull();
        assertThat(openOrder.isPhoneVerified()).isTrue();
    }

    @Test
    @DisplayName("A-4: a stale in-memory attempt count cannot bypass max_attempts — the atomic DB claim decides (429)")
    void verifyOtp_concurrentAttemptsExhausted_atomicClaimRefuses() {
        PhoneVerificationChallenge stale = new PhoneVerificationChallenge();
        stale.setId(UUID.randomUUID());
        stale.setTenantId(tenantId);
        stale.setPhoneNormalized("+447700900777");
        stale.setOtpSha256(PhoneVerificationService.sha256Hex("123456"));
        stale.setMaxAttempts(5);
        stale.setAttempts(0); // this thread read 0; parallel threads have since used all 5
        stale.setExpiresAt(OffsetDateTime.now().plusMinutes(10));

        when(challengeRepository.findTopByTenantIdAndPhoneNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(
                tenantId, "+447700900777")).thenReturn(Optional.of(stale));
        when(challengeRepository.consumeAttemptIfAvailable(stale.getId())).thenReturn(0);

        assertThatThrownBy(() -> phoneVerificationService.verifyOtp(tenantId, "+447700900777", "000000"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("A-4: a redemption that loses the atomic single-use claim answers 409, even though it read used_at IS NULL")
    void redeemPaymentLink_lostAtomicClaim_returnsConflict() {
        TenantContext.clear();
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        OrderPaymentLink link = new OrderPaymentLink();
        link.setId(UUID.randomUUID());
        link.setTenantId(tenantId);
        link.setShopId(shopId);
        link.setOrderId(orderId);
        link.setTokenSha256(PhoneVerificationService.sha256Hex("race-token"));
        link.setPurpose("UPFRONT");
        link.setAmountPennies(4200L);
        link.setPhoneVerified(true);
        link.setExpiresAt(OffsetDateTime.now().plusMinutes(30));
        // usedAt stays null: this request read the row before the winner's UPDATE committed

        when(linkRepository.findByTokenSha256(link.getTokenSha256())).thenReturn(Optional.of(link));
        when(linkRepository.markUsedIfUnused(eq(link.getId()), any(OffsetDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> paymentLinkService.redeemPaymentLink("race-token"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    @DisplayName("OrderPaymentLinkService refuses unverified phone (422) and issues single-use expiring link once verified (#461 / PAY-04)")
    void issueAndRedeemPaymentLink_enforcesVerifiedPhoneAndSingleUse() throws Exception {
        Order order = buildOrder(OrderStatus.PENDING, 4200L, false);
        order.setCustomerPhone("07700 900456");
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        // 1. Fail-before-pass: unverified phone is rejected with 422 UNPROCESSABLE_ENTITY
        when(challengeRepository.existsByTenantIdAndPhoneNormalizedAndVerifiedAtIsNotNull(
                tenantId, "+447700900456")).thenReturn(false);
        when(customerRepository.findByTenantIdAndPhoneIsNotNull(tenantId)).thenReturn(List.of());

        assertThatThrownBy(() -> paymentLinkService.issuePaymentLink(orderId, "UPFRONT", 30))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(rse.getReason()).contains("Customer phone number must be verified");
                });

        // 2. Once phone is verified, issuing a payment link succeeds and creates a Stripe PaymentIntent
        when(challengeRepository.existsByTenantIdAndPhoneNormalizedAndVerifiedAtIsNotNull(
                tenantId, "+447700900456")).thenReturn(true);
        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.isConfigured()).thenReturn(true);
        when(paymentService.createPaymentIntent(order))
                .thenReturn(new PaymentIntentResult("pi_live_link_01", "pi_live_link_01_secret_xyz", null));

        List<OrderPaymentLink> storedLinks = new ArrayList<>();
        when(linkRepository.findByTenantIdAndOrderIdOrderByCreatedAtDesc(tenantId, orderId))
                .thenAnswer(inv -> new ArrayList<>(storedLinks));
        when(linkRepository.saveAndFlush(any(OrderPaymentLink.class))).thenAnswer(inv -> {
            OrderPaymentLink l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID());
            }
            storedLinks.removeIf(existing -> existing.getId().equals(l.getId()));
            storedLinks.add(l);
            return l;
        });

        OrderPaymentLinkService.IssuedPaymentLinkDto issued =
                paymentLinkService.issuePaymentLink(orderId, "UPFRONT", 30);

        assertThat(issued.orderId()).isEqualTo(orderId);
        assertThat(issued.purpose()).isEqualTo("UPFRONT");
        assertThat(issued.amountPennies()).isEqualTo(4200L);
        assertThat(issued.stripePaymentIntentId()).isEqualTo("pi_live_link_01");
        // 30-16 owner decision (Task 1 = fragment): the token rides in the URL fragment, never a path segment,
        // so it is never in a request line or an access log (the V75 / V77 bearer-token rule).
        assertThat(issued.paymentUrl()).isEqualTo("https://shop.jtoye.co.uk/pay#token=" + issued.token());
        assertThat(issued.paymentUrl()).doesNotContain("/pay/");
        assertThat(order.isPhoneVerified()).isTrue();
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        // 3. Single-use redemption: clears ambient TenantContext during public lookup (A-3),
        // atomically marks used via markUsedIfUnused (A-4), and passes Order to retrieveClientSecret
        // for connected-account routing (A-1); second/concurrent call fails with 409 CONFLICT.
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        OrderPaymentLink savedLink = storedLinks.get(0);
        UUID callerAmbientTenant = UUID.randomUUID();
        TenantContext.set(callerAmbientTenant);

        when(linkRepository.findByTokenSha256(savedLink.getTokenSha256())).thenAnswer(inv -> {
            assertThat(TenantContext.get())
                    .as("Public token lookup must run with TenantContext cleared so RLS public policy applies")
                    .isEmpty();
            return Optional.of(savedLink);
        });
        when(linkRepository.markUsedIfUnused(eq(savedLink.getId()), any(OffsetDateTime.class)))
                .thenReturn(1)
                .thenReturn(0);
        when(paymentService.retrieveClientSecret(order, "pi_live_link_01"))
                .thenReturn("pi_live_link_01_secret_xyz");

        OrderPaymentLinkService.RedeemedPaymentLinkDto redeemed =
                paymentLinkService.redeemPaymentLink(issued.token());

        assertThat(redeemed.clientSecret()).isEqualTo("pi_live_link_01_secret_xyz");
        assertThat(savedLink.getUsedAt()).isNotNull();
        assertThat(TenantContext.get()).contains(callerAmbientTenant);

        // Reset in-memory usedAt to null to simulate a concurrent thread that read usedAt == null
        // before markUsedIfUnused returned 0 rows updated
        savedLink.setUsedAt(null);
        assertThatThrownBy(() -> paymentLinkService.redeemPaymentLink(issued.token()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("already been used");
                });
    }

    @Test
    @DisplayName("30-16: preview reads the link under its own tenant, never stamps used_at, never calls Stripe, and restores the caller's tenant")
    void previewPaymentLink_neverConsumes_neverCallsStripe_restoresCallerTenant() {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        OrderPaymentLink link = link("UPFRONT", OffsetDateTime.now().plusMinutes(30), null, null);
        Order order = buildOrder(OrderStatus.PENDING, 4200L, true);
        order.setPaymentStatus(PaymentStatus.PENDING);
        Shop shop = new Shop();
        shop.setName("Mama Tolu's Kitchen");

        UUID callerTenant = UUID.randomUUID();
        TenantContext.set(callerTenant);
        when(linkRepository.findByTokenSha256(link.getTokenSha256())).thenAnswer(inv -> {
            assertThat(TenantContext.get()).as("the bearer lookup runs tenant-less (A-3)").isEmpty();
            return Optional.of(link);
        });
        when(orderRepository.findById(orderId)).thenAnswer(inv -> {
            assertThat(TenantContext.get()).as("the order is read under the LINK's tenant").contains(tenantId);
            return Optional.of(order);
        });
        when(shopRepository.findById(shopId)).thenAnswer(inv -> {
            assertThat(TenantContext.get()).as("the shop is read under the LINK's tenant").contains(tenantId);
            return Optional.of(shop);
        });

        for (int i = 0; i < 2; i++) {
            OrderPaymentLinkService.PaymentLinkPreviewDto preview = paymentLinkService.previewPaymentLink("preview-token");
            assertThat(preview.shopName()).isEqualTo("Mama Tolu's Kitchen");
            assertThat(preview.orderNumber()).isEqualTo("ORD-P30-001");
            assertThat(preview.amountPennies()).isEqualTo(4200L);
            assertThat(preview.currency()).isEqualTo("gbp");
            assertThat(preview.purpose()).isEqualTo("UPFRONT");
            assertThat(preview.expiresAt()).isEqualTo(link.getExpiresAt());
            assertThat(preview.state()).isEqualTo("ACTIVE");
            assertThat(TenantContext.get()).as("the caller's tenant is restored").contains(callerTenant);
        }

        verify(linkRepository, never()).markUsedIfUnused(any(), any());
        verify(linkRepository, never()).save(any());
        verify(linkRepository, never()).saveAndFlush(any());
        verifyNoInteractions(paymentService);
        assertThat(link.getUsedAt()).isNull();
    }

    @Test
    @DisplayName("30-16: an unknown token previews as 404 'Payment link not found.' and the caller's tenant is restored")
    void previewPaymentLink_unknownToken_notFound_restoresTenant() {
        lenient().when(entityManager.unwrap(Session.class)).thenReturn(hibernateSession);
        UUID callerTenant = UUID.randomUUID();
        TenantContext.set(callerTenant);
        when(linkRepository.findByTokenSha256(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentLinkService.previewPaymentLink("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Payment link not found.");
        assertThat(TenantContext.get()).contains(callerTenant);
        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("30-16: preview state precedence PAID > USED > REVOKED > EXPIRED > ACTIVE; BALANCE is PAID only at 0 due")
    void previewState_precedence() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime future = now.plusMinutes(30);
        OffsetDateTime past = now.minusMinutes(1);

        Order pending = buildOrder(OrderStatus.PENDING, 4200L, true);
        pending.setPaymentStatus(PaymentStatus.PENDING);
        Order captured = buildOrder(OrderStatus.CONFIRMED, 4200L, true);
        captured.setPaymentStatus(PaymentStatus.CAPTURED);
        Order capturedOwing = buildOrder(OrderStatus.CONFIRMED, 4200L, true);
        capturedOwing.setPaymentStatus(PaymentStatus.CAPTURED);
        capturedOwing.setBalanceDuePennies(1500L);
        Order settled = buildOrder(OrderStatus.CONFIRMED, 4200L, true);
        settled.setPaymentStatus(PaymentStatus.CAPTURED);
        settled.setBalanceDuePennies(0L);

        // every flag at once on an unpaid order: USED wins
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", past, now, now), pending, now)).isEqualTo("USED");
        // PAID beats every link flag
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", past, now, now), captured, now)).isEqualTo("PAID");
        assertThat(OrderPaymentLinkService.previewState(link("DEPOSIT", future, now, null), captured, now)).isEqualTo("PAID");
        // REVOKED beats EXPIRED
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", past, null, now), pending, now)).isEqualTo("REVOKED");
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", past, null, null), pending, now)).isEqualTo("EXPIRED");
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", future, null, null), pending, now)).isEqualTo("ACTIVE");
        // an expiry exactly now is not yet expired, the same comparison redeem uses
        assertThat(OrderPaymentLinkService.previewState(link("UPFRONT", now, null, null), pending, now)).isEqualTo("ACTIVE");
        // BALANCE: a captured deposit is not a paid balance; 0 due is; NULL due is not recorded, not paid
        assertThat(OrderPaymentLinkService.previewState(link("BALANCE", future, null, null), capturedOwing, now)).isEqualTo("ACTIVE");
        assertThat(OrderPaymentLinkService.previewState(link("BALANCE", future, null, null), settled, now)).isEqualTo("PAID");
        assertThat(OrderPaymentLinkService.previewState(link("BALANCE", future, null, null), captured, now)).isEqualTo("ACTIVE");
    }

    private OrderPaymentLink link(String purpose, OffsetDateTime expiresAt, OffsetDateTime usedAt, OffsetDateTime revokedAt) {
        OrderPaymentLink link = new OrderPaymentLink();
        link.setId(UUID.randomUUID());
        link.setTenantId(tenantId);
        link.setShopId(shopId);
        link.setOrderId(orderId);
        link.setTokenSha256(PhoneVerificationService.sha256Hex("preview-token"));
        link.setPurpose(purpose);
        link.setAmountPennies(4200L);
        link.setPhoneVerified(true);
        link.setExpiresAt(expiresAt);
        link.setUsedAt(usedAt);
        link.setRevokedAt(revokedAt);
        return link;
    }

    private Order buildOrder(OrderStatus status, long totalAmountPennies, boolean phoneVerified) {
        Order order = new Order();
        try {
            Field idField = Order.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(order, orderId);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        order.setTenantId(tenantId);
        order.setShopId(shopId);
        order.setOrderNumber("ORD-P30-001");
        order.setStatus(status);
        order.setTotalAmountPennies(totalAmountPennies);
        order.setPhoneVerified(phoneVerified);
        return order;
    }
}
