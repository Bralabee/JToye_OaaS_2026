package uk.jtoye.core.config;

import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Hibernate Envers audit functionality.
 * Envers is auto-configured via application.yml properties.
 * This class exists for future Envers customization if needed.
 */
@Configuration
public class EnversConfig {
    // Envers is auto-configured by Spring Boot when hibernate-envers is on the classpath.
    // Configuration properties are defined in application.yml under
    // spring.jpa.properties.org.hibernate.envers.* (Spring Boot passes spring.jpa.properties.*
    // to Hibernate verbatim; Envers reads the org.hibernate.envers.* keys, see EnversSettings):
    //   org.hibernate.envers.audit_table_suffix: _aud
    //   org.hibernate.envers.revision_field_name: rev
    //   org.hibernate.envers.revision_type_field_name: revtype
    //   org.hibernate.envers.store_data_at_delete: true
    //   org.hibernate.envers.default_schema: public
    // QA-council 20260902 N-3: this comment used to document a `hibernate.envers.*` block that
    // Envers never read. AuditTableInsertPolicyIntegrationTest reads the effective configuration
    // back out of the SessionFactory, so the claim above is executable rather than prose.
}
