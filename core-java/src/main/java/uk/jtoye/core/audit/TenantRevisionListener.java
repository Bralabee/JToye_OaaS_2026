package uk.jtoye.core.audit;

import org.hibernate.envers.RevisionListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import uk.jtoye.core.security.TenantContext;

/**
 * Envers RevisionListener that automatically populates tenant_id and user_id
 * in the revinfo table for every audit revision.
 *
 * This ensures all audit history is traceable to specific tenants and users.
 */
public class TenantRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        RevInfo revInfo = (RevInfo) revisionEntity;

        // Capture tenant context from ThreadLocal
        TenantContext.get().ifPresent(revInfo::setTenantId);

        // Capture user ID from Spring Security context
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();

            // Extract user ID from JWT token
            if (principal instanceof Jwt jwt) {
                String userId = jwt.getSubject();
                revInfo.setUserId(userId);
            } else {
                revInfo.setUserId(authentication.getName());
            }
        }
    }
}
