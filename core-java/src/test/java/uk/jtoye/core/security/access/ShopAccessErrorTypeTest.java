package uk.jtoye.core.security.access;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import uk.jtoye.core.common.GlobalExceptionHandler;
import uk.jtoye.core.exception.LastGroupAdminException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.exception.ShopAccessDeniedException;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VSA-02 (T-23-02-03) — proves the shop-scope 403 {@code type} URI is provably
 * DISTINCT from BOTH the RLS 404 and the generic {@code /forbidden} 403. Blurring
 * the shop-403 with the RLS-404 would leak the tenant-boundary signal (SPEC
 * §D-01); the frontend D-13 access-required state also keys on the distinct type.
 *
 * <p>A plain unit test (no Spring context) — it drives the real
 * {@link GlobalExceptionHandler} methods and asserts on the emitted
 * {@link ProblemDetail}s, so it exercises the exact production mapping without a
 * container. Runs under the default {@code test} task.
 */
class ShopAccessErrorTypeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void shopAccess403TypeIsDistinctFromRls404AndGeneric403() {
        URI shopAccess = handler
                .handleShopAccessDenied(new ShopAccessDeniedException(UUID.randomUUID(), ShopRole.STAFF))
                .getType();
        URI rlsNotFound = handler
                .handleResourceNotFound(new ResourceNotFoundException("hidden by RLS"))
                .getType();
        URI genericForbidden = handler
                .handleAccessDenied(new AccessDeniedException("denied"))
                .getType();

        // Pairwise distinct.
        assertThat(shopAccess)
                .as("shop-access 403 type must differ from the RLS 404 type")
                .isNotEqualTo(rlsNotFound);
        assertThat(shopAccess)
                .as("shop-access 403 type must differ from the generic /forbidden 403 type")
                .isNotEqualTo(genericForbidden);

        // Exact stable URIs (the machine contract downstream code keys on).
        assertThat(shopAccess.toString()).endsWith("/shop-access-denied");
        assertThat(rlsNotFound.toString()).endsWith("/not-found");
        assertThat(genericForbidden.toString()).endsWith("/forbidden");
    }

    @Test
    void shopAccess403Is403AndCarriesMachineParseableProps() {
        UUID shopId = UUID.randomUUID();
        ProblemDetail problem = handler
                .handleShopAccessDenied(new ShopAccessDeniedException(shopId, ShopRole.SHOP_MANAGER));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getProperties())
                .as("shopId + requiredRole are the agent-readiness machine props")
                .containsKey("shopId")
                .containsKey("requiredRole");
        assertThat(problem.getProperties().get("shopId")).isEqualTo(shopId);
        assertThat(problem.getProperties().get("requiredRole")).isEqualTo(ShopRole.SHOP_MANAGER);
    }

    @Test
    void groupAdminOnlyDenialOmitsShopIdButKeepsDistinctType() {
        // A GROUP_ADMIN-only action (e.g. shop create) has no shopId.
        ProblemDetail problem = handler
                .handleShopAccessDenied(new ShopAccessDeniedException(null, ShopRole.GROUP_ADMIN));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getType().toString()).endsWith("/shop-access-denied");
        assertThat(problem.getProperties() == null || !problem.getProperties().containsKey("shopId"))
                .as("no shopId property when the denied action is shopless")
                .isTrue();
    }

    @Test
    void lastGroupAdminIsAConflictWithItsOwnType() {
        ProblemDetail problem = handler
                .handleLastGroupAdmin(new LastGroupAdminException("cannot remove the last GROUP_ADMIN"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getType().toString()).endsWith("/last-group-admin");
    }
}
