package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.dto.GateDto;
import uk.jtoye.core.onboarding.dto.OnboardingDto;
import uk.jtoye.core.onboarding.gate.AllergenCompletenessGate;
import uk.jtoye.core.shop.ShopRepository;
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
    private final ShopRepository shopRepository;
    private final GateChainRunner gateChainRunner;
    private final AllergenCompletenessGate allergenCompletenessGate;

    public VendorOnboardingService(VendorOnboardingRepository onboardingRepository,
                                   VendorOnboardingGateRepository gateRepository,
                                   VendorOnboardingStateMachineService stateMachineService,
                                   ShopService shopService,
                                   ShopRepository shopRepository,
                                   GateChainRunner gateChainRunner,
                                   AllergenCompletenessGate allergenCompletenessGate) {
        this.onboardingRepository = onboardingRepository;
        this.gateRepository = gateRepository;
        this.stateMachineService = stateMachineService;
        this.shopService = shopService;
        this.shopRepository = shopRepository;
        this.gateChainRunner = gateChainRunner;
        this.allergenCompletenessGate = allergenCompletenessGate;
    }

    /**
     * Create a DRAFT onboarding for the caller's tenant. A second create for the
     * same tenant violates {@code UNIQUE(tenant_id)}; {@code saveAndFlush} surfaces
     * that within the request as a {@code DataIntegrityViolationException} which
     * {@code GlobalExceptionHandler} maps to HTTP 409 (existing convention).
     */
    public OnboardingDto createOnboarding(OnboardingModel model, UUID shopId, String companyNumber) {
        UUID tenantId = CurrentTenant.require();

        // CR-02: the caller must own the shop. The V43 FK shop_id -> shops(id) is
        // checked by Postgres referential-integrity, which BYPASSES RLS, so an INSERT
        // referencing another tenant's (publicly-discoverable) shop would otherwise
        // succeed — binding the onboarding cross-tenant and letting the FHRS gate
        // record hygiene evidence against a foreign FSA establishment. A tenant-scoped
        // lookup (the same finder ShopService.getShopById uses) rejects a missing OR
        // foreign shop with a clean 404, instead of a later FK
        // DataIntegrityViolationException that GlobalExceptionHandler misreports as a
        // 409 "Duplicate Entry" (also a shop-UUID existence oracle).
        shopRepository.findByIdAndTenantId(shopId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Shop not found: " + shopId));

        log.info("Creating DRAFT onboarding for tenant {} (shop {})", tenantId, shopId);

        VendorOnboarding onboarding = new VendorOnboarding();
        onboarding.setTenantId(tenantId);
        onboarding.setShopId(shopId);
        onboarding.setModel(model);
        // WR-02: normalise the company number so the stored aggregate matches what the
        // CompaniesHouseGate looks up (it trims + the register is case-insensitive), and
        // a blank/whitespace value persists as null (sole trader -> gate WAIVED).
        onboarding.setCompanyNumber(normaliseCompanyNumber(companyNumber));
        onboarding.setStatus(OnboardingState.DRAFT);

        // Flush now so UNIQUE(tenant_id) surfaces as a 409 inside this request
        // rather than at a post-response commit.
        onboarding = onboardingRepository.saveAndFlush(onboarding);
        return toDto(onboarding, List.of());
    }

    /**
     * Submit the caller's onboarding: DRAFT → VERIFYING (stamps {@code submitted_at}),
     * then materialise the gate rows and kick the async gate chain <em>after this
     * transaction commits</em> (CR-01 — see {@link #kickGateChainAfterCommit}).
     */
    public OnboardingDto submit() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        transition(onboarding, OnboardingEvent.SUBMIT);

        gateChainRunner.materialise(onboarding);
        kickGateChainAfterCommit(onboarding.getId(), tenantId);

        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    /**
     * Resubmit the caller's onboarding after ACTION_REQUIRED (CR-03): ACTION_REQUIRED
     * → VERIFYING, then reset every FAILED / MANUAL_REVIEW gate row to PENDING (PASSED
     * / WAIVED rows stay trusted and are never re-run), and re-kick the async gate
     * chain after commit. The runner only (re)evaluates PENDING rows, so resetting the
     * flagged rows is what makes a re-run actually re-check them. The state machine
     * rejects RESUBMIT from any state other than ACTION_REQUIRED →
     * {@code InvalidStateTransitionException} → HTTP 400.
     */
    public OnboardingDto resubmit() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        transition(onboarding, OnboardingEvent.RESUBMIT);

        UUID onboardingId = onboarding.getId();
        for (VendorOnboardingGate gate : gateRepository.findByOnboardingId(onboardingId)) {
            if (gate.getStatus() == GateStatus.FAILED || gate.getStatus() == GateStatus.MANUAL_REVIEW) {
                gate.setStatus(GateStatus.PENDING);
                gate.setEvidence(null);
                gate.setExternalRef(null);
                gate.setReason(null);
                gate.setCheckedAt(null);
                gateRepository.save(gate);
            }
        }

        kickGateChainAfterCommit(onboardingId, tenantId);

        return toDto(onboarding, gateRepository.findByOnboardingId(onboardingId));
    }

    /**
     * Take the caller's onboarding LIVE (APPROVED → LIVE). Fires GO_LIVE through
     * the single canonical {@link #transition} path; the GO_LIVE guard (18-02)
     * requires every mandatory gate PASSED/WAIVED AND a PASSED
     * {@code ALLERGEN_DATA_COMPLETE} row, so a guard veto surfaces as
     * {@code InvalidStateTransitionException} → HTTP 400. The transition's side
     * effect flips {@code Shop.published=true} via {@link ShopService#setPublished}
     * — the sole authorised writer of {@code published=true} (threat T-18-05-T) —
     * and stamps {@code went_live_at}.
     */
    public OnboardingDto goLive() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        transition(onboarding, OnboardingEvent.GO_LIVE);

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
        // WR-03: the ALLERGEN_DATA_COMPLETE gate row is evaluated once during the async
        // run after submit, but GO_LIVE/REINSTATE can fire hours/days later (auto-approve
        // is off, so onboardings park at PENDING_APPROVAL awaiting a human). A vendor can
        // add or blank a product's allergen data in that window, so the stored PASSED row
        // is a TOCTOU on the "before publish" Natasha's Law check. Re-evaluate the allergen
        // gate here — a cheap same-DB read, no external API — BEFORE sendEvent, so the
        // go-live guard reads FRESH data. FHRS/CH rows are deliberately NOT re-run (external
        // calls; their evidence is trusted as recorded).
        if (event == OnboardingEvent.GO_LIVE || event == OnboardingEvent.REINSTATE) {
            refreshAllergenGate(onboarding);
        }

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

    /**
     * CR-01: dispatch the async gate chain only AFTER the current transaction
     * commits. {@link GateChainRunner#runAndRecompute} is {@code @Async @Transactional}
     * — it opens its own connection on a worker thread. Firing it while the submit
     * (or resubmit) transaction is still open races the worker against the commit:
     * under READ COMMITTED the worker cannot see the uncommitted VERIFYING status or
     * the freshly-materialised PENDING gate rows, so it early-returns and the
     * onboarding is left stuck in VERIFYING with every gate PENDING forever.
     * Registering an {@code afterCommit} synchronization guarantees the worker sees
     * committed state. If no synchronization is active (e.g. a direct call outside a
     * transaction) fall back to an immediate kick so the chain still runs.
     */
    private void kickGateChainAfterCommit(UUID onboardingId, UUID tenantId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    gateChainRunner.runAndRecompute(onboardingId, tenantId);
                }
            });
        } else {
            gateChainRunner.runAndRecompute(onboardingId, tenantId);
        }
    }

    /**
     * WR-03: re-evaluate the ALLERGEN_DATA_COMPLETE gate row against current product
     * data so the GO_LIVE/REINSTATE guard cannot trust a stale PASSED row. If the guard
     * then vetoes, this row update rolls back with the transaction — the security outcome
     * (publish blocked) is what matters. No-op if the row is absent (the guard then vetoes
     * on the missing allergen gate anyway).
     */
    private void refreshAllergenGate(VendorOnboarding onboarding) {
        gateRepository.findByOnboardingIdAndGateType(onboarding.getId(), GateType.ALLERGEN_DATA_COMPLETE)
                .ifPresent(row -> {
                    GateResult result = allergenCompletenessGate.evaluate(onboarding);
                    row.setStatus(result.status());
                    row.setEvidence(result.evidence());
                    row.setExternalRef(result.externalRef());
                    row.setReason(result.reason());
                    row.setCheckedAt(OffsetDateTime.now());
                    gateRepository.save(row);
                });
    }

    /** WR-02: trim + uppercase a company number; a blank/whitespace value becomes null. */
    private static String normaliseCompanyNumber(String companyNumber) {
        if (companyNumber == null) {
            return null;
        }
        String normalised = companyNumber.trim().toUpperCase();
        return normalised.isEmpty() ? null : normalised;
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
