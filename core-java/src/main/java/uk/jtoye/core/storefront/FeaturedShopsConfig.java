package uk.jtoye.core.storefront;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link FeaturedShopsProperties} (quick 261102-r6u, D-02). Mirrors {@code media/MediaConfig}:
 * the project does not use {@code @ConfigurationPropertiesScan}, so each {@code @ConfigurationProperties}
 * bean is registered explicitly.
 */
@Configuration
@EnableConfigurationProperties(FeaturedShopsProperties.class)
public class FeaturedShopsConfig {
}
