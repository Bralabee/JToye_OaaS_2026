package uk.jtoye.core.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.jtoye.core.notification.dispatch.NotificationMessage;
import uk.jtoye.core.notification.dispatch.WhatsAppSmsChannel;
import uk.jtoye.core.order.Order;
import uk.jtoye.core.order.OrderRepository;
import uk.jtoye.core.order.OrderStatus;
import uk.jtoye.core.storefront.CashOrderCaps;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Customer phone OTP challenge & verification service (Phase 30, #462 / #461 / PAY-04 / B5).
 *
 * <p>Validates and normalizes UK/E.164 phone numbers ({@code 07700 900123} -> {@code +447700900123}),
 * issues a 6-digit OTP stored only as a SHA-256 hex digest in {@code phone_verification_challenges},
 * dispatches via {@link WhatsAppSmsChannel}, and marks both {@code customers.phone_verified} and open
 * {@code orders.phone_verified} rows in the tenant upon successful verification.
 */
@Service
public class PhoneVerificationService {

    private static final Logger log = LoggerFactory.getLogger(PhoneVerificationService.class);
    private static final List<OrderStatus> OPEN_ORDER_STATUSES = List.of(
            OrderStatus.DRAFT, OrderStatus.PENDING, OrderStatus.CONFIRMED);

    private final PhoneVerificationChallengeRepository challengeRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final WhatsAppSmsChannel whatsAppSmsChannel;
    private final SecureRandom secureRandom = new SecureRandom();
    private final int ttlMinutes;
    private final int maxAttempts;
    private final boolean exposeOtpForTest;

    /**
     * The only {@code stripe.environment-tag} values under which the OTP test seam may start (30-17,
     * T-30-69, PR #1018 review finding 2). An ALLOW-list on purpose: refusing only {@code production}
     * failed OPEN on a tag nobody declared or somebody mistyped. Exact, case-sensitive members.
     */
    public static final Set<String> OTP_SEAM_ENVIRONMENTS = Set.of("local", "test", "staging");

    public PhoneVerificationService(
            PhoneVerificationChallengeRepository challengeRepository,
            CustomerRepository customerRepository,
            OrderRepository orderRepository,
            WhatsAppSmsChannel whatsAppSmsChannel,
            @Value("${jtoye.phone-verification.ttl-minutes:10}") int ttlMinutes,
            @Value("${jtoye.phone-verification.max-attempts:5}") int maxAttempts,
            @Value("${jtoye.phone-verification.expose-otp-for-test:false}") boolean exposeOtpForTest,
            // EMPTY fallback, never a guessed name: a runtime that declares nothing reads blank and,
            // with the seam on, is refused. Keyed on the tag and not the Spring profile, because
            // staging runs the prod profile too (#999).
            @Value("${stripe.environment-tag:}") String environmentTag) {
        if (exposeOtpForTest) {
            if (environmentTag == null || !OTP_SEAM_ENVIRONMENTS.contains(environmentTag)) {
                String found = environmentTag == null || environmentTag.isBlank()
                        ? "(not declared)"
                        : "'" + environmentTag + "'";
                throw new IllegalStateException("jtoye.phone-verification.expose-otp-for-test=true requires "
                        + "stripe.environment-tag to be declared as one of "
                        + new TreeSet<>(OTP_SEAM_ENVIRONMENTS) + "; found " + found
                        + ": the OTP would be returned to any caller");
            }
            log.warn("phone verification OTPs are returned in API responses — test seam enabled in {}",
                    environmentTag);
        }
        this.challengeRepository = challengeRepository;
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.whatsAppSmsChannel = whatsAppSmsChannel;
        this.ttlMinutes = ttlMinutes;
        this.maxAttempts = maxAttempts;
        this.exposeOtpForTest = exposeOtpForTest;
    }

    public record ChallengeResult(
            UUID challengeId,
            String phoneNormalized,
            String channel,
            OffsetDateTime expiresAt,
            String testPreviewOtp
    ) {}

    public record VerifyResult(
            boolean verified,
            String phoneNormalized,
            OffsetDateTime verifiedAt
    ) {}

    /**
     * Normalizes a UK or international phone string to E.164 format ({@code +<digits>}).
     * Rejects blank or malformed inputs (< 10 or > 15 digits) with {@link IllegalArgumentException}.
     */
    public static String normalizePhone(String rawPhone) {
        String digits = CashOrderCaps.normalisePhone(rawPhone);
        if (digits == null || digits.length() < 10 || digits.length() > 15) {
            throw new IllegalArgumentException(
                    "Phone number must be a valid UK or international mobile number (10 to 15 digits).");
        }
        return "+" + digits;
    }

    /**
     * Issue a new 6-digit OTP challenge for {@code rawPhone} in {@code tenantId} and dispatch via
     * {@link WhatsAppSmsChannel} ({@code SMS} or {@code WHATSAPP}).
     */
    @Transactional
    public ChallengeResult sendChallenge(UUID tenantId, UUID customerId, String rawPhone, String rawChannel) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant ID is required.");
        }
        String phoneNormalized = normalizePhone(rawPhone);
        String channel = normalizeChannel(rawChannel);

        int code = 100000 + secureRandom.nextInt(900000);
        String otp = String.format(Locale.ROOT, "%06d", code);
        String otpSha256 = sha256Hex(otp);

        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);

        PhoneVerificationChallenge challenge = new PhoneVerificationChallenge();
        challenge.setTenantId(tenantId);
        challenge.setCustomerId(customerId);
        challenge.setPhoneNormalized(phoneNormalized);
        challenge.setOtpSha256(otpSha256);
        challenge.setChannel(channel);
        challenge.setAttempts(0);
        challenge.setMaxAttempts(maxAttempts);
        challenge.setExpiresAt(expiresAt);
        challenge = challengeRepository.saveAndFlush(challenge);

        NotificationMessage message = new NotificationMessage(
                tenantId,
                phoneNormalized,
                "phone.verification.otp",
                null,
                "Your J'Toye verification code is: " + otp + " (valid for " + ttlMinutes + " minutes).",
                null,
                null
        );
        whatsAppSmsChannel.deliver(message);

        log.info("Issued phone verification challenge {} for tenant {} via {}",
                challenge.getId(), tenantId, channel);

        return new ChallengeResult(
                challenge.getId(),
                phoneNormalized,
                channel,
                expiresAt,
                exposeOtpForTest ? otp : null
        );
    }

    /**
     * Verify a 6-digit OTP code against the most recent active challenge for {@code (tenantId, rawPhone)}.
     *
     * <p>{@code noRollbackFor = IllegalArgumentException.class} ensures that failed attempt increments
     * commit to the database even when a wrong OTP code raises a 400.
     */
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public VerifyResult verifyOtp(UUID tenantId, String rawPhone, String rawOtp) {
        if (tenantId == null) {
            throw new IllegalArgumentException("Tenant ID is required.");
        }
        String phoneNormalized = normalizePhone(rawPhone);
        if (rawOtp == null || !rawOtp.trim().matches("^[0-9]{6}$")) {
            throw new IllegalArgumentException("Verification code must be a 6-digit number.");
        }
        String otp = rawOtp.trim();

        PhoneVerificationChallenge challenge = challengeRepository
                .findTopByTenantIdAndPhoneNormalizedAndVerifiedAtIsNullOrderByCreatedAtDesc(
                        tenantId, phoneNormalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No pending phone verification challenge found for this number."));

        OffsetDateTime now = OffsetDateTime.now();
        if (challenge.getExpiresAt().isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Verification code has expired. Please request a new code.");
        }
        if (challenge.getAttempts() >= challenge.getMaxAttempts()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Maximum verification attempts exceeded. Please request a new code.");
        }

        // A-4 (PR #949 round 1/2): the database decides whether an attempt is left. N parallel
        // guesses can all read the same in-memory count; only max_attempts of them get a row.
        // Never mutate the managed entity in memory so Hibernate auto-flush cannot overwrite
        // the atomic DB counter on commit.
        UUID challengeId = challenge.getId();
        byte[] expected = challenge.getOtpSha256().getBytes(StandardCharsets.UTF_8);
        byte[] actual = sha256Hex(otp).getBytes(StandardCharsets.UTF_8);

        if (challengeRepository.consumeAttemptIfAvailable(challengeId) != 1) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Maximum verification attempts exceeded. Please request a new code.");
        }

        if (!MessageDigest.isEqual(expected, actual)) {
            // The attempt is already committed by the conditional UPDATE above
            // (noRollbackFor keeps it); never write the in-memory count back.
            throw new IllegalArgumentException("Invalid verification code.");
        }

        challengeRepository.markVerified(challengeId, now);

        // Stamp any matching Customer rows in this tenant
        List<Customer> customers = customerRepository.findByTenantIdAndPhoneIsNotNull(tenantId);
        for (Customer customer : customers) {
            String custDigits = CashOrderCaps.normalisePhone(customer.getPhone());
            if (custDigits != null && ("+" + custDigits).equals(phoneNormalized)) {
                customer.setPhoneVerified(true);
                customer.setPhoneVerifiedAt(now);
                customer.setUpdatedAt(now);
                customerRepository.save(customer);
            }
        }

        // Stamp any open Order rows in this tenant carrying the verified phone
        List<Order> openOrders = orderRepository.findByTenantIdAndCustomerPhoneIsNotNullAndStatusIn(
                tenantId, OPEN_ORDER_STATUSES);
        for (Order order : openOrders) {
            String orderDigits = CashOrderCaps.normalisePhone(order.getCustomerPhone());
            if (orderDigits != null && ("+" + orderDigits).equals(phoneNormalized) && !order.isPhoneVerified()) {
                order.setPhoneVerified(true);
                order.setUpdatedAt(now);
                orderRepository.save(order);
            }
        }

        log.info("Verified phone number for challenge {} in tenant {}", challenge.getId(), tenantId);
        return new VerifyResult(true, phoneNormalized, now);
    }

    /**
     * Returns true if {@code rawPhone} has been verified in {@code tenantId} (either via a verified
     * challenge or a verified {@link Customer} row).
     */
    @Transactional(readOnly = true)
    public boolean isPhoneVerified(UUID tenantId, String rawPhone) {
        if (tenantId == null || rawPhone == null || rawPhone.isBlank()) {
            return false;
        }
        String digits = CashOrderCaps.normalisePhone(rawPhone);
        if (digits == null || digits.length() < 10 || digits.length() > 15) {
            return false;
        }
        String phoneNormalized = "+" + digits;
        if (challengeRepository.existsByTenantIdAndPhoneNormalizedAndVerifiedAtIsNotNull(
                tenantId, phoneNormalized)) {
            return true;
        }
        return customerRepository.findByTenantIdAndPhoneIsNotNull(tenantId).stream()
                .anyMatch(c -> c.isPhoneVerified()
                        && digits.equals(CashOrderCaps.normalisePhone(c.getPhone())));
    }

    private static String normalizeChannel(String rawChannel) {
        if (rawChannel == null || rawChannel.isBlank()) {
            return "SMS";
        }
        String upper = rawChannel.trim().toUpperCase(Locale.ROOT);
        if (!"SMS".equals(upper) && !"WHATSAPP".equals(upper)) {
            throw new IllegalArgumentException("Channel must be SMS or WHATSAPP.");
        }
        return upper;
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
