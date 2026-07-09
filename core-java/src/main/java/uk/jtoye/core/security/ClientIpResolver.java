package uk.jtoye.core.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the originating client IP for a request.
 *
 * <p>RED skeleton (issue #88 [P1-6], TDD): intentionally returns the
 * {@code "unknown"} sentinel unconditionally so {@code ClientIpResolverTest}
 * fails on every real case. The real X-Forwarded-For-first resolution is added
 * in the GREEN step.
 */
final class ClientIpResolver {

    private ClientIpResolver() {
    }

    static String resolveClientIp(HttpServletRequest request) {
        return "unknown";
    }
}
