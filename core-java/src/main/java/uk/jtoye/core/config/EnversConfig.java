package uk.jtoye.core.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Hibernate Envers audit functionality.
 * Envers is auto-configured via application.yml properties.
 * This class exists for future Envers customization if needed.
 */
@Configuration
public class EnversConfig {
    // Envers is auto-configured by Spring Boot when hibernate-envers is on the classpath
    // Configuration properties are defined in application.yml:
    //   hibernate.envers.audit_table_suffix: _aud
    //   hibernate.envers.revision_field_name: rev
    //   hibernate.envers.revision_type_field_name: revtype
    //   hibernate.envers.store_data_at_delete: true
}
