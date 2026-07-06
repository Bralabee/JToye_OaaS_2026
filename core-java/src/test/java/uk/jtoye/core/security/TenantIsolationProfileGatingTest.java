package uk.jtoye.core.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import uk.jtoye.core.tenant.DevTenantController;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audit remediation (MEDIUM): fail-closed tenant isolation in production.
 *
 * <p>Encodes the security contract that production tenancy derives solely from
 * the authenticated JWT:
 * <ul>
 *   <li>{@link TenantFilter} (header {@code X-Tenant-Id} → tenant mapping) is
 *       gated to non-production profiles, so a spoofed header has no effect in
 *       {@code prod}. In prod the bean is absent and {@link JwtTenantFilter}
 *       is the sole tenant source.</li>
 *   <li>{@link DevTenantController} no longer activates under the {@code default}
 *       profile, so a missing {@code SPRING_PROFILES_ACTIVE} cannot expose the
 *       tenant-creation endpoint in production.</li>
 * </ul>
 *
 * <p>Asserted via annotation reflection rather than a full prod-profile
 * application context (which would require live prod infrastructure) — this
 * keeps the guard deterministic and fast while directly pinning the profile
 * lists a regression would have to change.
 */
class TenantIsolationProfileGatingTest {

    private static List<String> profilesOf(Class<?> type) {
        Profile profile = type.getAnnotation(Profile.class);
        assertThat(profile)
                .as("%s must be @Profile-gated", type.getSimpleName())
                .isNotNull();
        return Arrays.asList(profile.value());
    }

    @Test
    void tenantFilterIsGatedToNonProductionProfiles() {
        List<String> profiles = profilesOf(TenantFilter.class);
        assertThat(profiles).containsExactlyInAnyOrder("dev", "local", "test");
        assertThat(profiles).doesNotContain("prod", "default");
    }

    @Test
    void devTenantControllerNotActiveUnderDefaultOrProd() {
        List<String> profiles = profilesOf(DevTenantController.class);
        assertThat(profiles).containsExactlyInAnyOrder("dev", "local");
        assertThat(profiles)
                .as("'default' would expose tenant creation when SPRING_PROFILES_ACTIVE is unset")
                .doesNotContain("default", "prod");
    }
}
