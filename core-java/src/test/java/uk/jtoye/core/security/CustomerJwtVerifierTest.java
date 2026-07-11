package uk.jtoye.core.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #179 defect 1: {@link CustomerJwtVerifier} is the trust anchor for the
 * session-authenticated order-history endpoint ({@code GET /public/orders/mine}).
 * Every failure mode must collapse to a uniform 401 (no oracle for WHICH check
 * failed), and the email may only ever come from a decoded-and-validated token.
 */
class CustomerJwtVerifierTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("customer-sub")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static CustomerJwtVerifier verifier(JwtDecoder decoder, boolean requireVerified) {
        return new CustomerJwtVerifier(() -> decoder, requireVerified);
    }

    private static void assertUnauthorized(ThrowingCallable call) {
        assertThatThrownBy(call::call)
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @FunctionalInterface
    private interface ThrowingCallable {
        void call();
    }

    @Test
    @DisplayName("null token → 401 without ever touching the decoder")
    void nullToken_rejected_beforeDecode() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        CustomerJwtVerifier verifier = verifier(decoder, true);

        assertUnauthorized(() -> verifier.verifiedEmail(null));
        verify(decoder, never()).decode(anyString());
    }

    @Test
    @DisplayName("blank token → 401 without ever touching the decoder")
    void blankToken_rejected_beforeDecode() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        CustomerJwtVerifier verifier = verifier(decoder, true);

        assertUnauthorized(() -> verifier.verifiedEmail("   "));
        verify(decoder, never()).decode(anyString());
    }

    @Test
    @DisplayName("decoder rejection (bad signature / wrong issuer / expired) → 401")
    void invalidToken_rejected() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("forged")).thenThrow(new BadJwtException("bad signature"));

        assertUnauthorized(() -> verifier(decoder, true).verifiedEmail("forged"));
    }

    @Test
    @DisplayName("valid token without an email claim → 401")
    void missingEmailClaim_rejected() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of("email_verified", true)));

        assertUnauthorized(() -> verifier(decoder, true).verifiedEmail("valid"));
    }

    @Test
    @DisplayName("email_verified=false is rejected when verification is required (anti-impersonation gate)")
    void unverifiedEmail_rejected_whenRequired() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of(
                "email", "victim@example.com",
                "email_verified", false)));

        assertUnauthorized(() -> verifier(decoder, true).verifiedEmail("valid"));
    }

    @Test
    @DisplayName("missing email_verified claim is rejected when verification is required")
    void absentVerifiedClaim_rejected_whenRequired() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of("email", "alice@example.com")));

        assertUnauthorized(() -> verifier(decoder, true).verifiedEmail("valid"));
    }

    @Test
    @DisplayName("email_verified=false is accepted when the dev-profile relaxation is active")
    void unverifiedEmail_accepted_whenNotRequired() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of(
                "email", "alice@example.com",
                "email_verified", false)));

        assertThat(verifier(decoder, false).verifiedEmail("valid"))
                .isEqualTo("alice@example.com");
    }

    @Test
    @DisplayName("verified token → returns the email claim")
    void verifiedToken_returnsEmail() {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode("valid")).thenReturn(jwt(Map.of(
                "email", "alice@example.com",
                "email_verified", true)));

        assertThat(verifier(decoder, true).verifiedEmail("valid"))
                .isEqualTo("alice@example.com");
    }
}
