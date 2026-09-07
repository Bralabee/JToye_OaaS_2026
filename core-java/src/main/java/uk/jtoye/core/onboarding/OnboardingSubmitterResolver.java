package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uk.jtoye.core.security.access.UserDirectory;
import uk.jtoye.core.security.access.UserDirectoryId;
import uk.jtoye.core.security.access.UserDirectoryRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the email of the user who submitted (or resubmitted) an onboarding — the INT-4
 * fallback recipient for onboarding notifications when {@code tenants.contact_email} is
 * blank (QA council 20260902-134741; measured: BOTH runtime tenants had it blank, so no
 * onboarding email ever left the platform while the vendor page promised one).
 *
 * <p>Two facts the system already records are joined; nothing new is stored and no field
 * is added to the {@code OnboardingStateChangeEvent}:
 * <ol>
 *   <li><b>who</b> — {@code revinfo.user_id} (the JWT {@code sub}) on the Envers revision
 *       that wrote {@code status = VERIFYING} on {@code vendor_onboarding_aud}
 *       ({@link VendorOnboardingRepository#findLatestSubmitterUserId});</li>
 *   <li><b>their address</b> — the tenant-scoped {@code user_directory} row for that
 *       {@code (tenant_id, user_id)}, which {@code VendorOnboardingService} refreshes from the
 *       caller's JWT at submit time so it is present exactly when it is needed.</li>
 * </ol>
 *
 * <p><b>Why not on the event.</b> {@code WebhookFanoutListener} forwards the whole
 * {@code OnboardingStateChangeEvent} as the envelope {@code data} to every subscribed
 * third-party URL; a staff email on the payload would leave the platform. Resolving it here,
 * at dispatch time, keeps the address inside the tenant boundary (both tables are FORCE RLS).
 *
 * <p>Fail-closed: any missing link — no request-thread revision, a non-UUID subject, no
 * directory row, a blank email — yields {@link Optional#empty()} and the dispatcher logs its
 * WARN. It never guesses another user.
 */
@Component
public class OnboardingSubmitterResolver {

    private static final Logger log = LoggerFactory.getLogger(OnboardingSubmitterResolver.class);

    private final VendorOnboardingRepository onboardingRepository;
    private final UserDirectoryRepository userDirectoryRepository;

    public OnboardingSubmitterResolver(VendorOnboardingRepository onboardingRepository,
                                       UserDirectoryRepository userDirectoryRepository) {
        this.onboardingRepository = onboardingRepository;
        this.userDirectoryRepository = userDirectoryRepository;
    }

    /**
     * The submitter's email for {@code onboardingId}, under the already-pinned {@code tenantId}
     * (callers run inside a tenant-pinned transaction — RLS decides what is visible).
     */
    public Optional<String> submitterEmail(UUID onboardingId, UUID tenantId) {
        if (onboardingId == null || tenantId == null) {
            return Optional.empty();
        }
        Optional<String> subject = onboardingRepository.findLatestSubmitterUserId(onboardingId);
        if (subject.isEmpty()) {
            log.debug("event=onboarding_submitter_unknown onboarding={} tenant={} reason=no_request_revision",
                    onboardingId, tenantId);
            return Optional.empty();
        }
        UUID userId;
        try {
            userId = UUID.fromString(subject.get());
        } catch (IllegalArgumentException e) {
            log.debug("event=onboarding_submitter_unknown onboarding={} tenant={} reason=non_uuid_subject",
                    onboardingId, tenantId);
            return Optional.empty();
        }
        return userDirectoryRepository.findById(new UserDirectoryId(tenantId, userId))
                .map(UserDirectory::getEmail)
                .filter(email -> email != null && !email.isBlank());
    }
}
