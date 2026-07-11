package uk.jtoye.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestOperations;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Validates B2C customer access tokens minted by the {@code jtoye-customers}
 * Keycloak realm and extracts the proven customer email (issue #179 defect 1).
 *
 * <p>This is deliberately SEPARATE from the resource-server {@link JwtDecoder}
 * bean in {@link SecurityConfig}: that decoder trusts the staff/vendor realm
 * ({@code jtoye-dev}, audience {@code core-api}) and drives Spring Security
 * authentication. Customer tokens come from a different realm, carry no
 * {@code core-api} audience, and must NEVER authenticate as staff — so they
 * are presented on a custom header ({@code X-Customer-Token}, see
 * {@code PublicStorefrontController#getMyOrders}) and verified explicitly
 * here, never via the {@code Authorization} header (which the
 * BearerTokenAuthenticationFilter would intercept and reject against the
 * staff realm even on permitAll routes).
 *
 * <p>Verification: signature against the customer realm JWKS, issuer
 * (split-horizon aware, mirroring the issue #87 fix: JWKS is fetched from the
 * INTERNAL issuer-uri while tokens are stamped with the PUBLIC issuer),
 * timestamps, presence of an {@code email} claim, and — configurable —
 * {@code email_verified=true}. The verified-email requirement is the
 * anti-impersonation gate: without it, anyone could self-register in the
 * customers realm with a victim's address and list the victim's order
 * history. It defaults to ON and is only relaxed in dev profiles where the
 * realm itself has email verification disabled (CUSTOMER_VERIFY_EMAIL=false).
 *
 * <p>Every rejection is a uniform 401 with no detail about WHY (no
 * enumeration oracle); specifics go to the log at DEBUG.
 */
@Component
public class CustomerJwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(CustomerJwtVerifier.class);

    private final Supplier<JwtDecoder> decoderFactory;
    private final boolean requireVerifiedEmail;

    // Lazily initialised so application startup never touches the network;
    // NimbusJwtDecoder fetches the JWKS on first decode anyway.
    private volatile JwtDecoder decoder;

    @Autowired
    public CustomerJwtVerifier(
            @Value("${jtoye.security.customer-jwt.issuer-uri}") String issuerUri,
            @Value("${jtoye.security.customer-jwt.expected-issuer}") String expectedIssuer,
            @Value("${jtoye.security.customer-jwt.require-verified-email}") boolean requireVerifiedEmail,
            RestTemplateBuilder restTemplateBuilder) {
        this(() -> buildDecoder(issuerUri, expectedIssuer, restTemplateBuilder), requireVerifiedEmail);
    }

    /** Test seam: inject a stub decoder without any JWKS/network dependency. */
    CustomerJwtVerifier(Supplier<JwtDecoder> decoderFactory, boolean requireVerifiedEmail) {
        this.decoderFactory = decoderFactory;
        this.requireVerifiedEmail = requireVerifiedEmail;
    }

    /**
     * Verify a customer access token and return the email it proves.
     *
     * @param token raw JWT from the {@code X-Customer-Token} header (may be null)
     * @return the verified customer email
     * @throws ResponseStatusException 401 on ANY failure — missing/blank token,
     *         bad signature, wrong issuer, expired, missing email claim, or
     *         unverified email where verification is required
     */
    public String verifiedEmail(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("missing token");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder().decode(token);
        } catch (JwtException e) {
            log.debug("Customer token rejected: {}", e.getMessage());
            throw unauthorized("invalid token");
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw unauthorized("no email claim");
        }
        if (requireVerifiedEmail && !Boolean.TRUE.equals(jwt.getClaim("email_verified"))) {
            log.debug("Customer token rejected: email_verified is not true for sub={}", jwt.getSubject());
            throw unauthorized("email not verified");
        }
        return email;
    }

    private ResponseStatusException unauthorized(String debugReason) {
        log.debug("Customer token verification failed: {}", debugReason);
        // Uniform message — never disclose which check failed to the caller.
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Customer authentication required");
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder local = decoder;
        if (local == null) {
            synchronized (this) {
                local = decoder;
                if (local == null) {
                    decoder = local = decoderFactory.get();
                }
            }
        }
        return local;
    }

    private static JwtDecoder buildDecoder(String issuerUri, String expectedIssuer,
                                           RestTemplateBuilder restTemplateBuilder) {
        // Same timeout posture as SecurityConfig#jwtDecoder — a hung Keycloak
        // must not hang request threads on the public storefront surface.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
        RestOperations restOperations = restTemplateBuilder
                .requestFactory(() -> requestFactory)
                .build();

        NimbusJwtDecoder built = NimbusJwtDecoder
                .withJwkSetUri(issuerUri + "/protocol/openid-connect/certs")
                .restOperations(restOperations)
                .build();
        // Issuer + timestamp validation. No audience check: storefront-client is
        // a public PKCE client in a single-purpose customer realm, so Keycloak
        // does not stamp a core-api audience on these tokens; trust is scoped by
        // the realm-specific issuer instead (the realm exists ONLY for customer
        // identity — see infra/keycloak/realm-export-customers.template.json).
        built.setJwtValidator(JwtValidators.createDefaultWithIssuer(expectedIssuer));
        return built;
    }
}
