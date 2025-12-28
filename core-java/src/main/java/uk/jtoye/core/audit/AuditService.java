package uk.jtoye.core.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.envers.AuditReader;
import org.hibernate.envers.AuditReaderFactory;
import org.hibernate.envers.query.AuditEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for querying entity audit history using Hibernate Envers.
 * All audit queries automatically respect tenant isolation via RLS policies on *_aud tables.
 */
@Service
@Transactional(readOnly = true)
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Get all revisions (versions) of a specific entity.
     *
     * @param entityClass The entity class (e.g., Shop.class, Product.class)
     * @param entityId    The entity ID
     * @return List of entity revisions in chronological order
     */
    public <T> List<T> getEntityHistory(Class<T> entityClass, UUID entityId) {
        log.debug("Fetching audit history for {} with id {}", entityClass.getSimpleName(), entityId);

        AuditReader auditReader = AuditReaderFactory.get(entityManager);

        List<Number> revisions = auditReader.getRevisions(entityClass, entityId);
        log.debug("Found {} revisions for {} {}", revisions.size(), entityClass.getSimpleName(), entityId);

        return revisions.stream()
                .map(rev -> auditReader.find(entityClass, entityId, rev))
                .filter(entity -> entity != null)
                .toList();
    }

    /**
     * Get a specific revision of an entity.
     *
     * @param entityClass The entity class
     * @param entityId    The entity ID
     * @param revision    The revision number
     * @return The entity at that revision, or null if not found
     */
    public <T> T getEntityAtRevision(Class<T> entityClass, UUID entityId, Number revision) {
        log.debug("Fetching {} {} at revision {}", entityClass.getSimpleName(), entityId, revision);

        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        return auditReader.find(entityClass, entityId, revision);
    }

    /**
     * Get all revisions for entities of a specific type (tenant-scoped via RLS).
     *
     * @param entityClass The entity class
     * @return List of all entity revisions for current tenant
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> getAllEntityRevisions(Class<T> entityClass) {
        log.debug("Fetching all audit revisions for {}", entityClass.getSimpleName());

        AuditReader auditReader = AuditReaderFactory.get(entityManager);

        return auditReader.createQuery()
                .forRevisionsOfEntity(entityClass, false, true)
                .addOrder(AuditEntity.revisionNumber().desc())
                .getResultList();
    }

    /**
     * Get the count of revisions for a specific entity.
     *
     * @param entityClass The entity class
     * @param entityId    The entity ID
     * @return Number of revisions
     */
    public <T> int getRevisionCount(Class<T> entityClass, UUID entityId) {
        AuditReader auditReader = AuditReaderFactory.get(entityManager);
        List<Number> revisions = auditReader.getRevisions(entityClass, entityId);
        return revisions.size();
    }
}
