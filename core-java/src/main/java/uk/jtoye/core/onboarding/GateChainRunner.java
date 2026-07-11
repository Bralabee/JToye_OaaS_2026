package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uk.jtoye.core.security.TenantContext;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The data-driven gate-chain registry + runner. Spring auto-collects every
 * {@link OnboardingGate} bean into {@code gates} — this IS the registry, so
 * 18-03/04/05 add a gate purely by adding a bean, with NO edit here. This slice
 * ships zero gate beans, so on a real {@code submit()} the recompute
 * short-circuits (no mandatory gate rows); the wiring exists so the concrete-gate
 * slices need not touch it.
 *
 * <p>Two responsibilities:
 * <ul>
 *   <li>{@link #materialise(VendorOnboarding)} — synchronous, called from
 *       {@code submit()} inside the request transaction: insert one PENDING gate
 *       row per registered bean (idempotent on {@code (onboarding_id, gate_type)}).</li>
 *   <li>{@link #runAndRecompute(UUID, UUID)} — {@code @Async}: evaluate automatic
 *       gates, then recompute over the gate ROWS and drive the state machine
 *       (GATES_PASSED / GATE_FAILED), consuming {@code onboarding.auto-approve}.</li>
 * </ul>
 *
 * <p><strong>Tenant propagation (threat T-18-02-E):</strong> {@code @EnableAsync}
 * is on but {@link TenantContext} is a plain {@code ThreadLocal} with NO
 * {@code TaskDecorator}, so the async worker thread does NOT inherit the request's
 * tenant and RLS writes would be denied. {@link #runAndRecompute} therefore takes
 * {@code tenantId} as a parameter and re-establishes it via
 * {@code TenantContext.set(tenantId)} inside a {@code try/finally}, so
 * {@code TenantSetLocalAspect} applies the {@code app.current_tenant_id} GUC for
 * the async transaction.
 */
@Component
public class GateChainRunner {

    private static final Logger log = LoggerFactory.getLogger(GateChainRunner.class);

    private final List<OnboardingGate> gates;
    private final VendorOnboardingRepository onboardingRepository;
    private final VendorOnboardingGateRepository gateRepository;
    private final OnboardingProperties onboardingProperties;
    private final VendorOnboardingService vendorOnboardingService;

    /**
     * @param vendorOnboardingService injected {@code @Lazy} to break the cycle
     *        (the service injects this runner for {@code submit()}); the runner
     *        drives GATES_PASSED / GATE_FAILED / APPROVE through the service's
     *        single canonical transition path.
     */
    public GateChainRunner(List<OnboardingGate> gates,
                           VendorOnboardingRepository onboardingRepository,
                           VendorOnboardingGateRepository gateRepository,
                           OnboardingProperties onboardingProperties,
                           @Lazy VendorOnboardingService vendorOnboardingService) {
        this.gates = gates;
        this.onboardingRepository = onboardingRepository;
        this.gateRepository = gateRepository;
        this.onboardingProperties = onboardingProperties;
        this.vendorOnboardingService = vendorOnboardingService;
    }

    /**
     * Insert one PENDING gate row per registered gate bean, skipping any type that
     * already has a row for this onboarding (V43 {@code UNIQUE(onboarding_id, gate_type)}).
     * With zero gate beans this inserts nothing.
     */
    public void materialise(VendorOnboarding onboarding) {
        for (OnboardingGate gate : gates) {
            if (gateRepository.findByOnboardingIdAndGateType(onboarding.getId(), gate.type()).isPresent()) {
                continue;
            }
            VendorOnboardingGate row = new VendorOnboardingGate();
            row.setTenantId(onboarding.getTenantId());
            row.setOnboardingId(onboarding.getId());
            row.setGateType(gate.type());
            row.setStatus(GateStatus.PENDING);
            row.setMandatory(gate.mandatory());
            gateRepository.save(row);
            log.debug("Materialised PENDING gate {} for onboarding {}", gate.type(), onboarding.getId());
        }
    }

    /**
     * Async gate evaluation + recompute. Re-establishes the tenant on the worker
     * thread, evaluates every automatic gate, then recomputes over the gate rows:
     * if there is ≥1 mandatory row AND all mandatory rows are PASSED/WAIVED, fire
     * GATES_PASSED and — when {@code onboarding.auto-approve} is true — immediately
     * fire APPROVE (the APPROVE guard still enforces the gates and can veto); if a
     * mandatory row FAILED, fire GATE_FAILED; otherwise leave it in VERIFYING for
     * webhooks/resubmit.
     */
    @Async
    @Transactional
    public void runAndRecompute(UUID onboardingId, UUID tenantId) {
        try {
            // CRITICAL: re-establish tenant on the async worker thread so
            // TenantSetLocalAspect applies the RLS GUC for this transaction.
            TenantContext.set(tenantId);

            VendorOnboarding onboarding = onboardingRepository.findById(onboardingId).orElse(null);
            if (onboarding == null) {
                log.warn("runAndRecompute: onboarding {} not found (tenant {})", onboardingId, tenantId);
                return;
            }

            // 1. Evaluate every automatic gate and record its result on the row.
            for (OnboardingGate gate : gates) {
                if (!gate.isAutomatic()) {
                    continue;
                }
                VendorOnboardingGate row = gateRepository
                        .findByOnboardingIdAndGateType(onboardingId, gate.type()).orElse(null);
                if (row == null) {
                    continue;
                }
                // Only (re)evaluate a gate row still PENDING. A row already in a
                // terminal state (PASSED / FAILED / WAIVED) — or one set out-of-band
                // by a future webhook gate — must NEVER be clobbered by a re-run:
                // doing so re-opens the "publish without a real gate pass" bypass
                // (threat T-18-06-T) and fires a redundant external API call on a
                // repeated recompute. A future RESUBMIT flow re-materialises / resets
                // rows to PENDING when it wants a fresh evaluation.
                if (row.getStatus() != GateStatus.PENDING) {
                    continue;
                }
                GateResult result = gate.evaluate(onboarding);
                row.setStatus(result.status());
                row.setEvidence(result.evidence());
                row.setExternalRef(result.externalRef());
                row.setReason(result.reason());
                row.setCheckedAt(OffsetDateTime.now());
                gateRepository.save(row);
            }

            // 2. Recompute over the materialised gate ROWS. A recompute only ever
            //    advances FROM VERIFYING — guard against illegal transitions if a
            //    prior recompute already moved the onboarding on.
            if (onboarding.getStatus() != OnboardingState.VERIFYING) {
                return;
            }
            List<VendorOnboardingGate> mandatory = gateRepository.findByOnboardingId(onboardingId).stream()
                    .filter(VendorOnboardingGate::isMandatory)
                    .toList();
            if (mandatory.isEmpty()) {
                return; // no mandatory gate rows -> nothing to advance (this slice's real path)
            }

            boolean allPassed = mandatory.stream().allMatch(this::passedOrWaived);
            boolean anyFailed = mandatory.stream().anyMatch(g -> g.getStatus() == GateStatus.FAILED);

            if (allPassed) {
                vendorOnboardingService.transition(onboardingId, OnboardingEvent.GATES_PASSED);
                // Consume onboarding.auto-approve: skip human review but NOT the
                // APPROVE guard (which re-checks all mandatory gates PASSED/WAIVED
                // and can still veto). Default false stops at PENDING_APPROVAL.
                if (onboardingProperties.isAutoApprove()) {
                    vendorOnboardingService.transition(onboardingId, OnboardingEvent.APPROVE);
                }
            } else if (anyFailed) {
                vendorOnboardingService.transition(onboardingId, OnboardingEvent.GATE_FAILED);
            }
            // else: still-PENDING gates await webhooks/resubmit -> leave in VERIFYING
        } finally {
            TenantContext.clear();
        }
    }

    private boolean passedOrWaived(VendorOnboardingGate gate) {
        return gate.getStatus() == GateStatus.PASSED || gate.getStatus() == GateStatus.WAIVED;
    }
}
