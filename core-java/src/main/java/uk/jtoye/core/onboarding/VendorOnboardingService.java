package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.dto.GateDto;
import uk.jtoye.core.onboarding.dto.OnboardingDto;
import uk.jtoye.core.shop.ShopService;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Vendor-onboarding application service. Mirrors {@code OrderService}'s
 * transition ordering (load → {@code sendEvent} → set status → timestamp/side
 * effect → save) and owns the two write invariants for this slice:
 *
 * <ul>
 *   <li>the tenant is ALWAYS resolved server-side via {@link CurrentTenant#require()},
 *       never read from a request body (threat T-18-02-S);</li>
 *   <li>the GO_LIVE / SUSPEND / REINSTATE side effects are the ONLY path that
 *       flips {@code Shop.published}, via {@code ShopService.setPublished} — the
 *       state machine is the sole authorised writer of {@code published=true}
 *       (threat T-18-02-T).</li>
 * </ul>
 */
@Service
@Transactional
public class VendorOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(VendorOnboardingService.class);

    private final VendorOnboardingRepository onboardingRepository;
    private final VendorOnboardingGateRepository gateRepository;
    private final VendorOnboardingStateMachineService stateMachineService;
    private final ShopService shopService;
    private final GateChainRunner gateChainRunner;

    public VendorOnboardingService(VendorOnboardingRepository onboardingRepository,
                                   VendorOnboardingGateRepository gateRepository,
                                   VendorOnboardingStateMachineService stateMachineService,
                                   ShopService shopService,
                                   GateChainRunner gateChainRunner) {
        this.onboardingRepository = onboardingRepository;
        this.gateRepository = gateRepository;
        this.stateMachineService = stateMachineService;
        this.shopService = shopService;
        this.gateChainRunner = gateChainRunner;
    }

    /**
     * Create a DRAFT onboarding for the caller's tenant. A second create for the
     * same tenant violates {@code UNIQUE(tenant_id)}; {@code saveAndFlush} surfaces
     * that within the request as a {@code DataIntegrityViolationException} which
     * {@code GlobalExceptionHandler} maps to HTTP 409 (existing convention).
     */
    public OnboardingDto createOnboarding(OnboardingModel model, UUID shopId, String companyNumber) {
        UUID tenantId = CurrentTenant.require();
        log.info("Creating DRAFT onboarding for tenant {} (shop {})", tenantId, shopId);

        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(model);
        onboarding.setCompanyNumber(companyNumber);
        onboarding.setStatus(OnboardingState.DRAFT);

        // Flush now so UNIQUE(tenant_id) surfaces as a 409 inside this request
        // rather than at a post-response commit.
        onboarding = onboardingRepository.saveAndFlush(onboarding);
        return toDto(onboarding, List.of());
    }

    /**
     * Submit the caller's onboarding: DRAFT → VERIFYING (stamps {@code submitted_at}),
     * then materialise the gate rows and kick the async gate chain. With zero gate
     * beans this slice the async run short-circuits (no mandatory gate rows).
     */
    public OnboardingDto submit() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        transition(onboarding, OnboardingEvent.SUBMIT);

        gateChainRunner.materialise(onboarding);
        gateChainRunner.runAndRecompute(onboarding.getId(), tenantId);

        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    /** The caller-tenant's onboarding plus its per-gate breakdown. */
    @Transactional(readOnly = true)
    public OnboardingDto getMyOnboarding() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);
        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    /**
     * Advance an onboarding by id. Package-private: called by {@link GateChainRunner}
     * (same package) so the async recompute drives GATES_PASSED / GATE_FAILED /
     * APPROVE through this single canonical transition path.
     */
    void transition(UUID onboardingId, OnboardingEvent event) {
        VendorOnboarding onboarding = onboardingRepository.findById(onboardingId)
                .orElseThrow(() -> new ResourceNotFoundException("Onboarding not found: " + onboardingId));
        transition(onboarding, event);
    }

    /**
     * Canonical transition: validate via the state machine, set the new status,
     * stamp the milestone timestamp, run the GO_LIVE/SUSPEND/REINSTATE published
     * side effect, then save — mirroring {@code OrderService.transitionOrder}.
     */
    private void transition(VendorOnboarding onboarding, OnboardingEvent event) {
        OnboardingState oldState = onboarding.getStatus();
        OnboardingState newState = stateMachineService.sendEvent(onboarding.getId(), oldState, event);

        OffsetDateTime now = OffsetDateTime.now();
        onboarding.setStatus(newState);
        onboarding.setUpdatedAt(now);

        switch (event) {
            case SUBMIT -> onboarding.setSubmittedAt(now);
            case APPROVE -> onboarding.setApprovedAt(now);
            case GO_LIVE -> {
                onboarding.setWentLiveAt(now);
                if (onboarding.getShopId() != null) {
                    shopService.setPublished(onboarding.getShopId(), true);
                }
            }
            case SUSPEND -> {
                onboarding.setSuspendedAt(now);
                if (onboarding.getShopId() != null) {
                    shopService.setPublished(onboarding.getShopId(), false);
                }
            }
            case REINSTATE -> {
                if (onboarding.getShopId() != null) {
                    shopService.setPublished(onboarding.getShopId(), true);
                }
            }
            default -> {
                // GATES_PASSED, GATE_FAILED, RESUBMIT, REJECT, WITHDRAW: status only.
            }
        }

        onboardingRepository.save(onboarding);
        log.info("Onboarding {} transitioned {} -> {} via {}", onboarding.getId(), oldState, newState, event);
    }

    private VendorOnboarding requireOnboarding(UUID tenantId) {
        return onboardingRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No onboarding found for the current tenant"));
    }

    private OnboardingDto toDto(VendorOnboarding onboarding, List<VendorOnboardingGate> gates) {
        List<GateDto> gateDtos = gates.stream()
                .map(g -> new GateDto(g.getGateType(), g.getStatus(), g.isMandatory(), g.getReason(), g.getCheckedAt()))
                .toList();
        return new OnboardingDto(
                onboarding.getId(),
                onboarding.getStatus(),
                onboarding.getModel(),
                onboarding.getShopId(),
                onboarding.getCompanyNumber(),
                onboarding.getSubmittedAt(),
                onboarding.getApprovedAt(),
                onboarding.getWentLiveAt(),
                gateDtos);
    }
}
