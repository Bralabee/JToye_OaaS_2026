package uk.jtoye.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Validates database configuration at startup to ensure Row-Level Security (RLS) will function correctly.
 *
 * Critical validations:
 * 1. Application is NOT using a superuser account (superusers bypass RLS)
 * 2. RLS policies exist and are enabled on tenant-scoped tables
 * 3. Required database functions (current_tenant_id) exist
 * 4. Application user has correct permissions
 *
 * This validator runs after application context is ready and will FAIL FAST if configuration is insecure.
 *
 * @author J'Toye Engineering Team
 * @since 0.7.1
 */
@Component
public class DatabaseConfigurationValidator {
    private static final Logger log = LoggerFactory.getLogger(DatabaseConfigurationValidator.class);

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.datasource.username}")
    private String dbUsername;

    private static final String[] TENANT_SCOPED_TABLES = {
        "shops", "products", "orders", "customers", "financial_transactions"
    };

    public DatabaseConfigurationValidator(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Validates database configuration after application startup.
     * This runs BEFORE the application accepts traffic, ensuring security configuration is correct.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateDatabaseConfiguration() {
        log.info("========================================");
        log.info("DATABASE SECURITY CONFIGURATION CHECK");
        log.info("========================================");

        try {
            log.info("Database username: {}", dbUsername);

            // CRITICAL: Check if using superuser (bypasses RLS)
            validateNotSuperuser();

            // Check RLS policies exist and are enabled
            validateRlsPolicies();

            // Check current_tenant_id function exists
            validateTenantFunction();

            // Check user permissions
            validateUserPermissions();

            log.info("========================================");
            log.info("✅ DATABASE SECURITY VALIDATION PASSED");
            log.info("========================================");

        } catch (SecurityConfigurationException e) {
            log.error("========================================");
            log.error("❌ DATABASE SECURITY VALIDATION FAILED");
            log.error("========================================");
            log.error("CRITICAL SECURITY ERROR: {}", e.getMessage());
            log.error("The application cannot start with this configuration.");
            log.error("Please fix the configuration and restart.");
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during security validation", e);
            throw new SecurityConfigurationException(
                "Database security validation failed with unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * CRITICAL: Ensure application is NOT using a PostgreSQL superuser.
     * Superusers bypass Row-Level Security, making multi-tenant isolation impossible.
     */
    private void validateNotSuperuser() {
        log.info("Checking if database user is a superuser...");

        // Check if current user is superuser
        String sql = "SELECT usesuper FROM pg_user WHERE usename = CURRENT_USER";
        Boolean isSuperuser = jdbcTemplate.queryForObject(sql, Boolean.class);

        if (Boolean.TRUE.equals(isSuperuser)) {
            String error = String.format(
                "CRITICAL SECURITY ERROR: Application is using PostgreSQL superuser '%s'. " +
                "Superusers BYPASS Row-Level Security policies, making multi-tenant isolation IMPOSSIBLE. " +
                "This creates a critical data breach vulnerability. " +
                "Solution: Change DB_USER to 'jtoye_app' in your configuration. " +
                "Files to update: docker-compose.full-stack.yml, core-java/.env, core-java/src/main/resources/application.yml",
                dbUsername
            );
            throw new SecurityConfigurationException(error);
        }

        log.info("✅ User '{}' is NOT a superuser (RLS will be enforced)", dbUsername);
    }

    /**
     * Validate that RLS policies exist and are enabled on all tenant-scoped tables.
     */
    private void validateRlsPolicies() {
        log.info("Checking RLS policies on tenant-scoped tables...");

        for (String table : TENANT_SCOPED_TABLES) {
            // Check if RLS is enabled
            String rlsEnabledSql =
                "SELECT relrowsecurity FROM pg_class WHERE relname = ?";
            Boolean rlsEnabled = jdbcTemplate.queryForObject(rlsEnabledSql, Boolean.class, table);

            if (!Boolean.TRUE.equals(rlsEnabled)) {
                throw new SecurityConfigurationException(
                    String.format("RLS is NOT enabled on table '%s'. Multi-tenant isolation will fail.", table)
                );
            }

            // Check if policies exist
            String policiesSql =
                "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ?";
            Integer policyCount = jdbcTemplate.queryForObject(policiesSql, Integer.class, table);

            if (policyCount == null || policyCount == 0) {
                throw new SecurityConfigurationException(
                    String.format("No RLS policies found for table '%s'. Multi-tenant isolation will fail.", table)
                );
            }

            log.info("  ✅ Table '{}': RLS enabled with {} policy(ies)", table, policyCount);
        }

        log.info("✅ All tenant-scoped tables have RLS policies enabled");
    }

    /**
     * Validate that the current_tenant_id() function exists.
     * This function is used by RLS policies to filter data by tenant.
     */
    private void validateTenantFunction() {
        log.info("Checking if current_tenant_id() function exists...");

        String sql =
            "SELECT COUNT(*) FROM pg_proc WHERE proname = 'current_tenant_id'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);

        if (count == null || count == 0) {
            throw new SecurityConfigurationException(
                "Function current_tenant_id() does not exist. RLS policies cannot function. " +
                "This should have been created by Flyway migration V2__rls_policies.sql"
            );
        }

        // Test the function works
        try {
            String testSql = "SELECT current_tenant_id()";
            jdbcTemplate.queryForObject(testSql, String.class);
            log.info("✅ Function current_tenant_id() exists and is callable");
        } catch (Exception e) {
            log.warn("⚠️  Function current_tenant_id() exists but may have issues: {}", e.getMessage());
        }
    }

    /**
     * Validate that the application user has correct permissions.
     */
    private void validateUserPermissions() {
        log.info("Checking user permissions...");

        // Check if user can query tenant-scoped tables
        for (String table : TENANT_SCOPED_TABLES) {
            String sql = String.format(
                "SELECT has_table_privilege(CURRENT_USER, '%s', 'SELECT')", table);
            Boolean hasSelect = jdbcTemplate.queryForObject(sql, Boolean.class);

            if (!Boolean.TRUE.equals(hasSelect)) {
                throw new SecurityConfigurationException(
                    String.format("User '%s' does not have SELECT permission on table '%s'",
                        dbUsername, table)
                );
            }
        }

        log.info("✅ User has correct permissions on all tables");
    }

    /**
     * Get comprehensive security status for monitoring/health checks.
     */
    public Map<String, Object> getSecurityStatus() {
        try {
            boolean isSuperuser = Boolean.TRUE.equals(
                jdbcTemplate.queryForObject(
                    "SELECT usesuper FROM pg_user WHERE usename = CURRENT_USER",
                    Boolean.class
                )
            );

            List<Map<String, Object>> rlsStatus = jdbcTemplate.queryForList(
                "SELECT tablename, relrowsecurity FROM pg_class c " +
                "JOIN pg_tables t ON c.relname = t.tablename " +
                "WHERE t.schemaname = 'public' AND t.tablename = ANY(?)",
                (Object) TENANT_SCOPED_TABLES
            );

            return Map.of(
                "username", dbUsername,
                "isSuperuser", isSuperuser,
                "rlsEnabled", !isSuperuser,
                "tablesWithRls", rlsStatus.size(),
                "status", isSuperuser ? "INSECURE" : "SECURE"
            );
        } catch (Exception e) {
            return Map.of(
                "status", "ERROR",
                "error", e.getMessage()
            );
        }
    }

    /**
     * Exception thrown when database security configuration is invalid.
     */
    public static class SecurityConfigurationException extends RuntimeException {
        public SecurityConfigurationException(String message) {
            super(message);
        }

        public SecurityConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
