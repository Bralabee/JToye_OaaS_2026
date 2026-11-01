package uk.jtoye.core.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Test support (SW-PRY-06): REAL signed tokens for two realms, and a REAL {@link CustomerJwtVerifier}
 * that trusts only one of them.
 *
 * <p>The verifier under test is the production class with the production decoder shape
 * ({@code NimbusJwtDecoder} + {@code JwtValidators.createDefaultWithIssuer}); only the key source
 * differs, a local RSA public key instead of the realm's JWKS endpoint. So a token signed by the
 * staff realm's key, or stamped with the staff issuer, or carrying {@code email_verified=false}, is
 * refused by the same code that refuses it in production, not by a stub that was told to refuse it.
 */
public final class CustomerRealmTestTokens {

    public static final String CUSTOMERS_ISSUER = "http://keycloak.test/realms/jtoye-customers";
    public static final String STAFF_ISSUER = "http://keycloak.test/realms/jtoye-dev";

    private static final KeyPair CUSTOMERS_KEY = rsa();
    private static final KeyPair STAFF_KEY = rsa();

    private CustomerRealmTestTokens() {
    }

    /** The production verifier, trusting the customers realm only, with email_verified required. */
    public static CustomerJwtVerifier customersRealmVerifier() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) CUSTOMERS_KEY.getPublic())
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(CUSTOMERS_ISSUER));
        return new CustomerJwtVerifier(() -> decoder, true);
    }

    /** A customers-realm access token for a verified address. */
    public static String customerToken(String email) {
        return sign(CUSTOMERS_KEY, CUSTOMERS_ISSUER, email, true);
    }

    /** A customers-realm token whose address the realm has NOT verified. */
    public static String unverifiedCustomerToken(String email) {
        return sign(CUSTOMERS_KEY, CUSTOMERS_ISSUER, email, false);
    }

    /** A staff/vendor-realm token (its own key, its own issuer) carrying a verified address. */
    public static String staffRealmToken(String email) {
        return sign(STAFF_KEY, STAFF_ISSUER, email, true);
    }

    private static String sign(KeyPair key, String issuer, String email, boolean emailVerified) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
