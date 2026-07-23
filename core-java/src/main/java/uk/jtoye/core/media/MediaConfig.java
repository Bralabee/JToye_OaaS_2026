package uk.jtoye.core.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Phase 24 media pipeline configuration. Mirrors
 * {@code storage/StorageConfig} — the project does not use
 * {@code @ConfigurationPropertiesScan}, so each {@code @ConfigurationProperties}
 * bean is registered explicitly via {@link EnableConfigurationProperties}.
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {
}
