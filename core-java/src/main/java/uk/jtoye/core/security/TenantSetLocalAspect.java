package uk.jtoye.core.security;

import jakarta.persistence.EntityManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.UUID;

@Aspect
@Component
public class TenantSetLocalAspect {
    private static final Logger log = LoggerFactory.getLogger(TenantSetLocalAspect.class);

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
        session.doWork(connection -> {
            // Use PreparedStatement for defense-in-depth (UUID format is already validated, but this is best practice)
            try (PreparedStatement stmt = connection.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
                log.debug("Set RLS tenant context: {}", tenantId);
            }
        });
    }

    private void resetTenant() {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.createStatement()) {
                stmt.execute("SET LOCAL app.current_tenant_id TO DEFAULT");
            }
        });
    }
}
