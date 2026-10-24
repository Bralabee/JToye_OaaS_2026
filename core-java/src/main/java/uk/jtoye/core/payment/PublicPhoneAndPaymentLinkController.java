package uk.jtoye.core.payment;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
}
