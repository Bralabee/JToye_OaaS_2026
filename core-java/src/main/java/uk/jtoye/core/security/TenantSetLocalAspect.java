package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

@Aspect
@Component
public class TenantSetLocalAspect {

    private final EntityManager entityManager;

    public TenantSetLocalAspect(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Before("(@within(org.springframework.transaction.annotation.Transactional) || @annotation(org.springframework.transaction.annotation.Transactional))")
    public void setTenantOnConnection() {
        // Only attempt to set when a real transaction is active
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Optional<UUID> tenantOpt = TenantContext.get();
        if (tenantOpt.isPresent()) {
            applyTenant(tenantOpt.get());
        } else {
            resetTenant();
        }
    }

    // Also ensure tenant is applied just-in-time before common DB operations where
    // TenantContext might have been set later inside the transactional method
    @Before("execution(* org.springframework.data.repository.Repository+.*(..)) || " +
            "execution(* org.springframework.jdbc.core.JdbcTemplate.*(..))")
    public void setTenantBeforeDbOps() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        Optional<UUID> opt = TenantContext.get();
        if (opt.isPresent()) {
            applyTenant(opt.get());
        } else {
            resetTenant();
        }
    }

    private void applyTenant(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork((Connection connection) -> {
            // Note: PostgreSQL does not support parameter placeholders for SET LOCAL; use a literal.
            try (java.sql.Statement st = connection.createStatement()) {
                st.execute("SET LOCAL app.current_tenant_id = '" + tenantId + "'");
            }
        });
    }

    private void resetTenant() {
        Session session = entityManager.unwrap(Session.class);
        session.doWork((Connection connection) -> {
            try (java.sql.Statement st = connection.createStatement()) {
                // For custom GUCs, use SET LOCAL ... TO DEFAULT to clear the value in this transaction
                st.execute("SET LOCAL app.current_tenant_id TO DEFAULT");
            }
        });
    }
}
