package uk.jtoye.core.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.jtoye.core.customer.PhoneVerificationService;
import uk.jtoye.core.security.TenantContext;
import uk.jtoye.core.shop.Shop;
import uk.jtoye.core.storefront.PublicStorefrontService;

/**
 * Public storefront endpoints for customer phone OTP verification (#462) and single-use
 * expiring payment link redemption (#461 / PAY-04 / B5).
 *
 * <p>Canonical path is {@code /api/v1/public/**}; {@code /public/**} is kept as the legacy
 * storefront alias matching {@code PublicStorefrontController}.
 *
 * <h2>Payment links: the token travels in a JSON body</h2>
 *
 * Owner decision 2026-10-31 (plan 30-16 Task 1): an issued link is {@code /pay#token=...}. The fragment
 * never reaches a server, so the page posts the token in a body to {@code /payment-links/preview}
 * (read-only, never spends the link) and {@code /payment-links/redeem} (single use), the V75 / V77 rule:
 * the token is never in a request line, an access log or a {@code Referer}, and a link scanner that
 * prefetches the page cannot reach either endpoint. The path redeem
 * {@code /payment-links/{token}/redeem} stays for links already issued in the path shape.
 */
@RestController
@RequestMapping({"/public", "/api/v1/public"})
@Tag(name = "Public Storefront", description = "Public endpoints for customer-facing shop discovery, product browsing, order tracking, and reviews. Canonical prefix /api/v1/public; bare /public is a deprecated legacy alias.")
public class PublicPhoneAndPaymentLinkController {

    private final PublicStorefrontService storefrontService;
    private final PhoneVerificationService phoneVerificationService;
    private final OrderPaymentLinkService paymentLinkService;

    public PublicPhoneAndPaymentLinkController(
            PublicStorefrontService storefrontService,
            PhoneVerificationService phoneVerificationService,
            OrderPaymentLinkService paymentLinkService) {
        this.storefrontService = storefrontService;
        this.phoneVerificationService = phoneVerificationService;
        this.paymentLinkService = paymentLinkService;
    }

    public record SendPhoneVerificationRequest(
            @NotBlank String phone,
            String channel
    ) {}

    public record VerifyPhoneOtpRequest(
            @NotBlank String phone,
            @NotBlank String otp
    ) {}

    /** JSON body: the payment-link token travels here, never in the request line. */
    @Schema(description = "The single-use token from the payment link's #token= fragment.")
    public record PaymentLinkTokenRequest(@NotBlank String token) {}

    @PostMapping("/shops/{slug}/phone-verification/send")
    @Operation(
            summary = "Send customer phone OTP verification challenge",
            description = "Normalizes a UK or international mobile number to E.164 and sends a 6-digit OTP via SMS or WhatsApp (#462)."
    )
    public ResponseEntity<PhoneVerificationService.ChallengeResult> sendPhoneVerification(
            @PathVariable String slug,
            @Valid @RequestBody SendPhoneVerificationRequest request) {
        Shop shop = storefrontService.resolvePublicShopForSlug(slug);
        try {
            PhoneVerificationService.ChallengeResult result = phoneVerificationService.sendChallenge(
                    shop.getTenantId(), null, request.phone(), request.channel());
            return ResponseEntity.ok(result);
        } finally {
            TenantContext.clear();
        }
    }

    @PostMapping("/shops/{slug}/phone-verification/verify")
    @Operation(
            summary = "Verify customer phone OTP code",
            description = "Verifies a 6-digit OTP challenge for the shop's tenant and marks matching customer and open order records phone_verified = true (#462)."
    )
    public ResponseEntity<PhoneVerificationService.VerifyResult> verifyPhoneOtp(
            @PathVariable String slug,
            @Valid @RequestBody VerifyPhoneOtpRequest request) {
        Shop shop = storefrontService.resolvePublicShopForSlug(slug);
        try {
            PhoneVerificationService.VerifyResult result = phoneVerificationService.verifyOtp(
                    shop.getTenantId(), request.phone(), request.otp());
            return ResponseEntity.ok(result);
        } finally {
            TenantContext.clear();
        }
    }

    @PostMapping("/payment-links/{token}/redeem")
    @Operation(
            summary = "Redeem a single-use expiring payment link",
            description = "Marks the payment link token as used (single-use) and returns the live Stripe PaymentIntent client secret (#461 / PAY-04)."
    )
    public ResponseEntity<OrderPaymentLinkService.RedeemedPaymentLinkDto> redeemPaymentLink(
            @PathVariable String token) {
        return ResponseEntity.ok(paymentLinkService.redeemPaymentLink(token));
    }

    @PostMapping(value = "/payment-links/preview",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Preview a payment link without using it",
            description = "Read-only and safe to repeat: does NOT consume the single-use link and does not "
                    + "contact the payment provider. Returns the shop, order number, amount, currency, purpose, "
                    + "expiry and state (ACTIVE, USED, EXPIRED, REVOKED, PAID; PAID takes precedence over USED, "
                    + "USED over REVOKED, REVOKED over EXPIRED). The token is sent in the JSON body, never the URL "
                    + "(PAY-04, D-03)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The link's preview. Cache-Control: no-store."),
            @ApiResponse(responseCode = "400", description = "The token is missing or blank."),
            @ApiResponse(responseCode = "404", description = "No payment link has this token "
                    + "(type https://jtoye.uk/errors/not-found)."),
            @ApiResponse(responseCode = "429", description = "Per-IP public rate limit exceeded.")
    })
    public ResponseEntity<OrderPaymentLinkService.PaymentLinkPreviewDto> previewPaymentLinkByBody(
            @Valid @RequestBody PaymentLinkTokenRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(paymentLinkService.previewPaymentLink(body.token()));
    }

    @PostMapping(value = "/payment-links/redeem",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Redeem a single-use payment link (token in the body)",
            description = "The body-token form of the path redeem, with identical behaviour: marks the link used "
                    + "(single use) and returns the live Stripe PaymentIntent client secret. Idempotent by "
                    + "construction rather than replayable: a repeat is always refused 409, so an "
                    + "Idempotency-Key is not needed (PAY-04)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redeemed. Cache-Control: no-store."),
            @ApiResponse(responseCode = "400", description = "The token is missing or blank."),
            @ApiResponse(responseCode = "404", description = "No payment link has this token "
                    + "(type https://jtoye.uk/errors/not-found)."),
            @ApiResponse(responseCode = "409", description = "The link has already been used."),
            @ApiResponse(responseCode = "410", description = "The link was revoked or has expired."),
            @ApiResponse(responseCode = "429", description = "Per-IP public rate limit exceeded."),
            @ApiResponse(responseCode = "502", description = "The payment provider could not load the session.")
    })
    public ResponseEntity<OrderPaymentLinkService.RedeemedPaymentLinkDto> redeemPaymentLinkByBody(
            @Valid @RequestBody PaymentLinkTokenRequest body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(paymentLinkService.redeemPaymentLink(body.token()));
    }
}
