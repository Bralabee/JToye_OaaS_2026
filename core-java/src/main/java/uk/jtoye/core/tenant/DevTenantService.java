package uk.jtoye.core.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DevTenantService {
    private final EntityManager em;

    public DevTenantService(EntityManager em) {
        this.em = em;
    }

    @Transactional
    public void ensureTenantExists(UUID tenantId, String name) {
        // Create row if not present (id is PK)
        Query q = em.createNativeQuery("INSERT INTO tenants (id, name) VALUES (:id, :name) ON CONFLICT (id) DO NOTHING");
        q.setParameter("id", tenantId);
        q.setParameter("name", name);
        q.executeUpdate();
    }
}
