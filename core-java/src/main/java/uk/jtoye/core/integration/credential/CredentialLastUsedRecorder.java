package uk.jtoye.core.integration.credential;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;
import uk.jtoye.core.exception.CredentialRevokedException;
import uk.jtoye.core.security.TenantContext;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Records when an integrator credential is used, and refuses a revoked one (D-24, Phase 37-45).
 *
 * <p>Keycloak keeps no cheap per-client last-use, so core records it. A request whose token's {@code azp} is a
 * credential clientId ({@code jtoye-int-…}, the prefix V82 pins) is looked up in its own tenant, and
 * {@code last_used_at} is stamped when it is absent or older than {@code jtoye.credentials.last-used-interval}
 * (default PT15M): at most one write per credential per interval, conditional in SQL so two pods do not both
 * write it.
 *
 * <ul>
 *   <li><strong>Best-effort.</strong> The lookup and the stamp each run in their OWN transaction
 *       ({@code REQUIRES_NEW}), so they work under a read-only request and cannot poison it; any failure is
 *       logged by type and swallowed, and the request goes on. It never lives in
 *       {@code ShopAccessService.onRequest()}, which skips read-only transactions.</li>
 *   <li><strong>Through FORCE RLS.</strong> The tenant GUC is pinned by {@code TenantSetLocalAspect} from the
 *       token's tenant inside each transaction; a stamp with no tenant pinned would match zero rows and report
 *       success. {@code IntegrationCredentialRlsIntegrationTest} proves the stamp lands under a NOSUPERUSER
 *       role.</li>
 *   <li><strong>A revoked credential is refused at once</strong> with 403 {@code credential-revoked}, rather
 *       than for the rest of its token's lifetime. Revocation already deletes the service account's grant, so
 *       every shop-gated endpoint refuses it; this also covers the endpoints no shop gate guards. A lookup
 *       failure lets the request through (best-effort): the grant deletion is the wall, this is depth.</li>
 * </ul>
 *
 * <p>Runs as a servlet filter after the Spring Security chain (order 300; {@code JwtTenantFilter} is 200), so
 * the authentication and the tenant are both established. A token whose {@code azp} is not a credential
 * clientId costs one string comparison.
 */
@Component
@Order(300)
public class CredentialLastUsedRecorder extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CredentialLastUsedRecorder.class);

    private final IntegrationCredentialRepository repository;
    private final TransactionTemplate requiresNew;
    private final TransactionTemplate requiresNewReadOnly;
    private final JsonMapper jsonMapper;
    private final Duration interval;

    /**
     * The repository and the transaction manager are resolved through {@link ObjectProvider}, the
     * {@code JwtTenantFilter} precedent: a filter bean is part of every {@code @WebMvcTest} slice, which has no
     * JPA. Without both the filter passes every request through untouched; in the application both are always
     * present.
     */
    public CredentialLastUsedRecorder(ObjectProvider<IntegrationCredentialRepository> repository,
                                      ObjectProvider<PlatformTransactionManager> transactionManager,
                                      JsonMapper jsonMapper,
                                      @Value("${jtoye.credentials.last-used-interval:PT15M}") Duration interval) {
        this.repository = repository.getIfAvailable();
        PlatformTransactionManager tm = transactionManager.getIfAvailable();
        if (tm != null) {
            this.requiresNew = new TransactionTemplate(tm);
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewReadOnly = new TransactionTemplate(tm);
            this.requiresNewReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewReadOnly.setReadOnly(true);
        } else {
            this.requiresNew = null;
            this.requiresNewReadOnly = null;
        }
        this.jsonMapper = jsonMapper;
        this.interval = interval;
    }

    /** What the filter needs from the row, read inside its own transaction (no entity leaves it). */
    private record Use(UUID id, boolean revoked, OffsetDateTime lastUsedAt) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientId = credentialClientId();
        Optional<UUID> tenant = TenantContext.get();
        if (clientId == null || tenant.isEmpty() || repository == null || requiresNew == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<Use> use = lookup(tenant.get(), clientId);
        if (use.isPresent() && use.get().revoked()) {
            log.info("event=integration_client_revoked_refused tenant={} clientId={}", tenant.get(), clientId);
            writeRevoked(response);
            return;
        }
        use.ifPresent(u -> stampIfStale(tenant.get(), clientId, u));
        chain.doFilter(request, response);
    }

    private Optional<Use> lookup(UUID tenantId, String clientId) {
        try {
            return requiresNewReadOnly.execute(status -> repository.findByTenantIdAndKcClientId(tenantId, clientId)
                    .map(c -> new Use(c.getId(), c.isRevoked(), c.getLastUsedAt())));
        } catch (RuntimeException e) {
            log.warn("event=integration_client_lookup_skipped tenant={} clientId={} cause={}",
                    tenantId, clientId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void stampIfStale(UUID tenantId, String clientId, Use use) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime cutoff = now.minus(interval);
        if (use.lastUsedAt() != null && use.lastUsedAt().isAfter(cutoff)) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> repository.touchLastUsed(tenantId, use.id(), now, cutoff));
        } catch (RuntimeException e) {
            log.warn("event=integration_client_last_used_skipped tenant={} clientId={} cause={}",
                    tenantId, clientId, e.getClass().getSimpleName());
        }
    }

    /** The token's {@code azp} when it names an integrator credential, else null. */
    private static String credentialClientId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        String azp = jwt.getClaimAsString("azp");
        return azp != null && azp.startsWith(IntegrationCredentialService.CLIENT_ID_PREFIX) ? azp : null;
    }

    private void writeRevoked(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, CredentialRevokedException.DETAIL);
        problem.setTitle("Credential revoked");
        problem.setType(URI.create(CredentialRevokedException.TYPE));
        problem.setProperty("code", "CREDENTIAL_REVOKED");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }
}
