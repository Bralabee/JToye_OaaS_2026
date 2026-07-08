package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fail-fast guard against silently-misconfigured Spring profiles.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} at
 * {@code ApplicationEnvironmentPreparedEvent} — i.e. BEFORE any bean or DB
 * initialisation — for every boot, including {@code @SpringBootTest} contexts.
 * If {@code SPRING_PROFILES_ACTIVE} names a profile that matches no
 * {@code application-<name>.yml} / {@code @Profile} annotation, the application
 * aborts startup with a non-zero exit instead of silently downgrading its
 * security posture.
 *
 * <p>Motivating incident (Issue #78 [P0-2]): the k8s deployment set
 * {@code SPRING_PROFILES_ACTIVE=production}, which matches no profile, so
 * {@code application-prod.yml} never loaded and every {@code @Profile("!prod")}
 * bean (including Swagger via {@code OpenApiConfig}) stayed active in production.
 * A phantom profile must be loud, not silent.
 *
 * @author J'Toye Engineering Team
 */
public class ActiveProfileValidator implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ActiveProfileValidator.class);

    /**
     * Single source of truth for the set of profiles this codebase understands.
     *
     * <p>Each member is backed by an on-disk {@code application-<name>.yml} and/or
     * one or more {@code @Profile} annotations. This set varies with the codebase
     * (not the runtime environment), so it is an acceptable hardcoded constant:
     * adding a new profile is a deliberate, reviewed change that must update BOTH
     * the yml/annotations AND this set. Matching is exact and case-sensitive
     * because only an exact name activates its {@code application-<name>.yml} and
     * {@code @Profile} beans — {@code "Prod"} / {@code "PRODUCTION"} would
     * silently no-op.
     */
    static final Set<String> KNOWN_PROFILES =
            new LinkedHashSet<>(Arrays.asList("local", "dev", "test", "staging", "prod"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment.getActiveProfiles());
    }

    /**
     * Pure validation rule (no Spring types) so it can be unit-tested with plain
     * {@code String[]} arrays.
     *
     * <ul>
     *   <li>An empty array is the Spring {@code default} profile and is allowed
     *       (many tests and the local default boot this way).</li>
     *   <li>Every active profile must be an EXACT, case-sensitive member of
     *       {@link #KNOWN_PROFILES}.</li>
     *   <li>Any unknown profile throws {@link IllegalStateException} naming the
     *       offending profile, the valid set, and {@code SPRING_PROFILES_ACTIVE}.</li>
     * </ul>
     *
     * @param activeProfiles the resolved active profiles ({@code null} treated as empty)
     * @throws IllegalStateException if any profile is outside {@link #KNOWN_PROFILES}
     */
    static void validate(String[] activeProfiles) {
        if (activeProfiles == null || activeProfiles.length == 0) {
            return; // Spring "default" profile — always valid.
        }

        for (String profile : activeProfiles) {
            if (!KNOWN_PROFILES.contains(profile)) {
                String message = String.format(
                        "Unknown Spring profile '%s' is active. Valid profiles are %s. "
                                + "Check SPRING_PROFILES_ACTIVE — matching is exact and case-sensitive, so a typo "
                                + "or case variant (e.g. 'production' vs 'prod') would silently load no "
                                + "application-<profile>.yml and disable no @Profile beans, downgrading security. "
                                + "Aborting startup instead of booting a phantom profile.",
                        profile, KNOWN_PROFILES);
                log.error("========================================");
                log.error("❌ ACTIVE PROFILE VALIDATION FAILED");
                log.error("========================================");
                log.error(message);
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * Run early so a bad profile is rejected before other post-processors that
     * assume a valid environment execute.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
