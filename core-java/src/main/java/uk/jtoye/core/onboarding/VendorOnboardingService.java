package uk.jtoye.core.onboarding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.jtoye.core.common.CurrentTenant;
import uk.jtoye.core.exception.InvalidStateTransitionException;
import uk.jtoye.core.exception.ResourceNotFoundException;
import uk.jtoye.core.onboarding.dto.AdminOnboardingDto;
import uk.jtoye.core.onboarding.dto.GateDto;
import uk.jtoye.core.onboarding.dto.OnboardingDto;
import uk.jtoye.core.onboarding.gate.AllergenCompletenessGate;
import uk.jtoye.core.security.access.UserDirectoryRepository;
import uk.jtoye.core.shop.Shop;
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
    private final UserDirectoryRepository userDirectoryRepository;

    public VendorOnboardingService(VendorOnboardingRepository onboardingRepository,
                                   VendorOnboardingGateRepository gateRepository,
                                   VendorOnboardingStateMachineService stateMachineService,
                                   ShopService shopService,
                                   ShopRepository shopRepository,
                                   GateChainRunner gateChainRunner,
                                   AllergenCompletenessGate allergenCompletenessGate,
                                   UserDirectoryRepository userDirectoryRepository) {
        this.onboardingRepository = onboardingRepository;
        this.gateRepository = gateRepository;
        this.stateMachineService = stateMachineService;
        this.shopService = shopService;
        this.shopRepository = shopRepository;
        this.gateChainRunner = gateChainRunner;
        this.allergenCompletenessGate = allergenCompletenessGate;
        this.userDirectoryRepository = userDirectoryRepository;
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
        recordSubmitterInDirectory(tenantId);

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
        recordSubmitterInDirectory(tenantId);

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

    /**
     * Withdraw the caller's onboarding (ONBD-01, D-05). Fires WITHDRAW through the
     * single canonical {@link #transition} path from any pre-live state (DRAFT /
     * VERIFYING / ACTION_REQUIRED / PENDING_APPROVAL / APPROVED → terminal
     * WITHDRAWN). WITHDRAW is a no-side-effect status change — it falls into the
     * {@code transition} {@code default} arm and never touches {@code Shop.published}
     * (the state machine stays the sole writer, threat T-21-01-03). A terminal
     * source (REJECTED / WITHDRAWN / LIVE / SUSPENDED) has no WITHDRAW transition, so
     * the state machine vetoes it → {@code InvalidStateTransitionException} → HTTP
     * 400. Withdrawal is terminal; a vendor who wants to try again starts a new
     * application.
     */
    public OnboardingDto withdraw() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        log.info("Vendor withdrawing onboarding {} (tenant {})", onboarding.getId(), tenantId);
        transition(onboarding, OnboardingEvent.WITHDRAW);

        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    /**
     * Correct the caller's onboarding company number (ONBD-02, D-06). A DATA EDIT
     * ONLY — it fires NO {@link OnboardingEvent}, so it never touches {@code status}
     * or {@code Shop.published} (the state machine stays the sole writer, threat
     * T-21-01-03). Permitted only in DRAFT or ACTION_REQUIRED — the states where the
     * vendor is still building / fixing the application; anywhere else the edit is
     * rejected with {@link InvalidStateTransitionException} → HTTP 400 (threat
     * T-21-01-04), so a company number cannot be mutated mid-verification or after a
     * terminal outcome. The value is re-validated at the boundary by
     * {@code UpdateOnboardingRequest} (identical {@code @Size}+{@code @Pattern} to
     * create) and normalised here: a blank/whitespace value becomes null (= sole
     * trader), matching create semantics. After correcting, the vendor triggers the
     * existing {@link #resubmit()} to re-run the gate chain against the fixed data.
     */
    public OnboardingDto updateCompanyNumber(String companyNumber) {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);

        if (onboarding.getStatus() != OnboardingState.DRAFT
                && onboarding.getStatus() != OnboardingState.ACTION_REQUIRED) {
            throw new InvalidStateTransitionException(
                    "Company number can only be changed while onboarding is in DRAFT or ACTION_REQUIRED "
                            + "(current: " + onboarding.getStatus() + ")");
        }

        onboarding.setCompanyNumber(normaliseCompanyNumber(companyNumber));
        onboardingRepository.save(onboarding);
        log.info("Vendor updated onboarding {} company number (tenant {})", onboarding.getId(), tenantId);

        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    /** The caller-tenant's onboarding plus its per-gate breakdown. */
    @Transactional(readOnly = true)
    public OnboardingDto getMyOnboarding() {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboarding(tenantId);
        return toDto(onboarding, gateRepository.findByOnboardingId(onboarding.getId()));
    }

    // --- Admin approve/reject queue (#178 slice 2) --------------------------------

    /**
     * Admin queue: every onboarding parked in PENDING_APPROVAL, oldest submission
     * first, with its gate breakdown and shop name. Runs under RLS, so the list is
     * scoped to the caller's tenant (see {@link OnboardingAdminController} for the
     * platform-wide follow-up note).
     */
    @Transactional(readOnly = true)
    public List<AdminOnboardingDto> listPendingApproval() {
        CurrentTenant.require();
        return onboardingRepository.findByStatusOrderBySubmittedAtAsc(OnboardingState.PENDING_APPROVAL).stream()
                .map(o -> toAdminDto(o, gateRepository.findByOnboardingId(o.getId())))
                .toList();
    }

    /**
     * The lifecycle states in which a MANUAL_REVIEW gate can exist and a reviewer can act
     * on it: VERIFYING (the park), and ACTION_REQUIRED (INT-1 — the same park when another
     * mandatory gate FAILED in the same run, because {@link GateChainRunner} fires
     * GATE_FAILED before it considers the MANUAL_REVIEW park). Shared by
     * {@link #listReviewPending()} and the {@link #resolveGate} guard so the queue never
     * lists an item whose only control would 400 (the "structural green over a dead
     * feature" trap).
     */
    static final List<OnboardingState> REVIEWABLE_STATES =
            List.of(OnboardingState.VERIFYING, OnboardingState.ACTION_REQUIRED);

    /**
     * Admin review queue (ONBD-03 / D-04): every onboarding carrying at least one
     * MANUAL_REVIEW gate row — i.e. a gate that needs a human. Membership is decided by the
     * PRESENCE of a parked gate, not by a single lifecycle state (INT-1, QA council
     * 20260902-134741 / A15): with no Companies House API key the BUSINESS_VERIFIED gate
     * always parks at MANUAL_REVIEW, and whenever another mandatory gate FAILED in the same
     * run the runner demotes to ACTION_REQUIRED first — under the old VERIFYING-only filter
     * that parked gate vanished from the reviewer's queue while the vendor page told the
     * vendor a reviewer was on it. The vendor-facing {@code reviewPending} flag on
     * {@code OnboardingDto} is a DIFFERENT predicate (VERIFYING only, no PENDING gate) and is
     * deliberately not widened — the vendor must never be told "in review" while a failed
     * check is theirs to fix.
     *
     * <p>This is the black-hole state the existing {@link #listPendingApproval() /pending}
     * approve/reject queue never showed; per D-04/A4 it is a NEW queue (Incremental
     * Betterment — the /pending contract is untouched). Runs under RLS, so the list is
     * scoped to the caller's tenant (same interim-resolver boundary as gate-resolve;
     * see {@link OnboardingAdminController}). Oldest submission first, mirroring
     * {@link #listPendingApproval()}.
     */
    @Transactional(readOnly = true)
    public List<AdminOnboardingDto> listReviewPending() {
        CurrentTenant.require();
        return onboardingRepository.findByStatusInOrderBySubmittedAtAsc(REVIEWABLE_STATES).stream()
                .filter(o -> gateRepository.existsByOnboardingIdAndStatus(o.getId(), GateStatus.MANUAL_REVIEW))
                .map(o -> toAdminDto(o, gateRepository.findByOnboardingId(o.getId())))
                .toList();
    }

    /**
     * Admin approval: fire APPROVE (PENDING_APPROVAL → APPROVED) through the single
     * canonical {@link #transition} path — never a direct status write. The APPROVE
     * guard still enforces that every mandatory gate is PASSED/WAIVED, so a human
     * approval of a no-longer-green application is vetoed →
     * {@code InvalidStateTransitionException} → HTTP 400.
     */
    public AdminOnboardingDto approve(UUID onboardingId) {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboardingById(onboardingId);

        log.info("Admin approving onboarding {} (tenant {})", onboardingId, tenantId);
        transition(onboarding, OnboardingEvent.APPROVE);

        return toAdminDto(onboarding, gateRepository.findByOnboardingId(onboardingId));
    }

    /**
     * Admin rejection: persist the REQUIRED human reason on the aggregate (audited
     * via Envers — the {@code vendor_onboarding_aud} mirror records who-when-what),
     * then fire REJECT through the canonical {@link #transition} path. The state
     * machine only accepts REJECT from VERIFYING / ACTION_REQUIRED /
     * PENDING_APPROVAL; anywhere else → {@code InvalidStateTransitionException} →
     * HTTP 400, and the rollback discards the reason write with it.
     */
    public AdminOnboardingDto reject(UUID onboardingId, String reason) {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboardingById(onboardingId);

        onboarding.setRejectionReason(reason.trim());
        log.info("Admin rejecting onboarding {} (tenant {})", onboardingId, tenantId);
        transition(onboarding, OnboardingEvent.REJECT);

        return toAdminDto(onboarding, gateRepository.findByOnboardingId(onboardingId));
    }

    /**
     * Admin gate-resolve (ONBD-03 / D-01): unstick a gate parked at MANUAL_REVIEW by
     * overriding its row status, then let the EXISTING recompute advance the state
     * machine. This method writes ONLY the gate row and registers the recompute — it
     * NEVER writes {@code status}/{@code Shop.published} directly (the state machine
     * stays the sole authority, threat T-21-03-03). It also NEVER calls
     * {@code runAndRecompute} inline: the recompute is dispatched
     * {@link #kickGateChainAfterCommit after this transaction commits} (CR-01), so the
     * async worker sees the committed gate write. {@code runAndRecompute} advances only
     * from VERIFYING and skips non-PENDING rows, so a PASS/WAIVE on the last blocking
     * gate fires GATES_PASSED (advancing out of VERIFYING) while a FAIL fires
     * GATE_FAILED (→ ACTION_REQUIRED), and the admin-set row survives the re-run.
     *
     * <p><strong>Interim resolver (D-01):</strong> the caller is the tenant's own
     * {@code admin} (RLS pins {@code requireOnboardingById} to the caller-tenant — a
     * foreign onboarding is a clean 404, no existence oracle). A real J'Toye
     * platform-operator console is a deferred phase.
     *
     * <p><strong>Review-window guard (WR-01, widened by INT-1 / A15):</strong> a gate can
     * be resolved only while the onboarding is in {@link #REVIEWABLE_STATES} — VERIFYING or
     * ACTION_REQUIRED. {@code runAndRecompute} advances the state machine ONLY from
     * VERIFYING, so resolving a gate once the onboarding has left the review window
     * (PENDING_APPROVAL / APPROVED / LIVE) would mutate a gate row the recompute can never
     * act on — silently stranding the application until a later {@code /approve} fails with
     * an unexplained gate-guard veto. That is still rejected with
     * {@link InvalidStateTransitionException} → HTTP 400, no gate row touched.
     * ACTION_REQUIRED is admitted because a MANUAL_REVIEW gate parked beside a FAILED one
     * lands there (the runner fires GATE_FAILED before the park), and the VERIFYING-only
     * guard then 400'd the reviewer's only control — the third lockout mechanism behind
     * the two-actor dead-end. Resolving in ACTION_REQUIRED is NOT stranding: the row is
     * written and audited, the after-commit recompute returns early (state unchanged), and
     * the vendor's {@link #resubmit()} — which resets only FAILED/MANUAL_REVIEW rows and
     * preserves PASSED/WAIVED — carries the reviewer's decision into the next VERIFYING run.
     *
     * <p>The gate write is Envers-audited automatically ({@code VendorOnboardingGate}
     * is {@code @Audited} → {@code vendor_onboarding_gate_aud}). A FAIL decision
     * REQUIRES a reason (A5); a blank one is an {@link IllegalArgumentException} → HTTP
     * 400. PASS/WAIVE reasons are optional.
     */
    public AdminOnboardingDto resolveGate(UUID onboardingId, GateType gateType,
                                          GateDecision decision, String reason) {
        UUID tenantId = CurrentTenant.require();
        VendorOnboarding onboarding = requireOnboardingById(onboardingId);

        // WR-01 (window widened by INT-1): gate resolution is valid only inside the review
        // window — VERIFYING, or ACTION_REQUIRED when a parked gate sits beside a FAILED one.
        // The recompute this method dispatches (GateChainRunner.runAndRecompute) advances the
        // state machine ONLY from VERIFYING; resolving a gate on an onboarding already at
        // PENDING_APPROVAL/APPROVED/LIVE would silently mutate a gate row the recompute can
        // never act on, stranding the onboarding and surfacing later as an unexplained
        // APPROVE guard veto. Reject up front instead.
        if (!REVIEWABLE_STATES.contains(onboarding.getStatus())) {
            throw new InvalidStateTransitionException(
                    "Gate " + gateType + " cannot be resolved while onboarding " + onboardingId
                    + " is in state " + onboarding.getStatus()
                    + " — gate resolution is only valid during manual review (VERIFYING or ACTION_REQUIRED)");
        }

        if (decision == GateDecision.FAIL && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A FAIL decision requires a reason");
        }

        VendorOnboardingGate row = gateRepository.findByOnboardingIdAndGateType(onboardingId, gateType)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Gate " + gateType + " not found for onboarding " + onboardingId));

        GateStatus newStatus = switch (decision) {
            case PASS -> GateStatus.PASSED;
            case WAIVE -> GateStatus.WAIVED;
            case FAIL -> GateStatus.FAILED;
        };
        row.setStatus(newStatus);
        row.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        row.setCheckedAt(OffsetDateTime.now());
        gateRepository.save(row);  // Envers auto-writes vendor_onboarding_gate_aud

        log.info("Admin resolved gate {} -> {} on onboarding {} (tenant {})",
                gateType, newStatus, onboardingId, tenantId);

        // CR-01: recompute AFTER commit — never inline. Reuses the existing advance
        // logic (GATES_PASSED / GATE_FAILED); the state machine remains the sole writer.
        kickGateChainAfterCommit(onboardingId, tenantId);

        return toAdminDto(onboarding, gateRepository.findByOnboardingId(onboardingId));
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
     * INT-4 (QA council 20260902-134741): make the submitter reachable. Envers already records
     * WHO submitted ({@code revinfo.user_id} = the JWT subject, via {@code TenantRevisionListener});
     * this refreshes the caller's tenant-scoped {@code user_directory} row from the same JWT so
     * {@link OnboardingSubmitterResolver} can turn that subject into an EMAIL when
     * {@code tenants.contact_email} is blank. The directory is otherwise populated only on
     * shop-scoped write paths ({@code ShopAccessService}), which a vendor who only ever calls the
     * onboarding endpoints never touches — so without this the fallback would be empty exactly
     * when it is needed. Same D-09 throttled upsert, same "only the caller's own {@code sub}"
     * property (T-23-02-01); {@code cutoff = now()} so the address is current at submit time.
     * Best-effort and fail-closed: no JWT principal, a non-UUID subject, or a missing/oversize
     * email claim means nothing is written — never a guess, never a failed submit.
     */
    private void recordSubmitterInDirectory(UUID tenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return;
        }
        UUID subject;
        try {
            subject = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank() || email.length() > 320) {
            return; // nothing usable to route to — the column is VARCHAR(320)
        }
        String displayName = jwt.getClaimAsString("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = jwt.getClaimAsString("preferred_username");
        }
        if (displayName != null && displayName.length() > 255) {
            displayName = displayName.substring(0, 255);
        }
        try {
            userDirectoryRepository.upsertSeen(tenantId, subject, email.trim(), displayName, OffsetDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("Submitter directory refresh skipped (best-effort) for tenant {}: {}", tenantId, ex.getMessage());
        }
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

    /**
     * WR-02 + INT-7/A14: canonical company number — trim, uppercase, blank → null (sole
     * trader), and left-zero-pad a purely numeric value to the 8-character register key
     * ({@code 445790} → {@code 00445790}). Delegates to {@link CompanyNumbers#normalise} so
     * the gate's lookup key and the stored aggregate can never disagree.
     */
    private static String normaliseCompanyNumber(String companyNumber) {
        return CompanyNumbers.normalise(companyNumber);
    }

    private VendorOnboarding requireOnboarding(UUID tenantId) {
        return onboardingRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("No onboarding found for the current tenant"));
    }

    /**
     * Admin-queue lookup by id. {@code findById} executes under RLS (V43 FORCE
     * policy), so a foreign tenant's onboarding is indistinguishable from a
     * nonexistent one — both 404, no cross-tenant existence oracle.
     */
    private VendorOnboarding requireOnboardingById(UUID onboardingId) {
        return onboardingRepository.findById(onboardingId)
                .orElseThrow(() -> new ResourceNotFoundException("Onboarding not found: " + onboardingId));
    }

    private AdminOnboardingDto toAdminDto(VendorOnboarding onboarding, List<VendorOnboardingGate> gates) {
        List<GateDto> gateDtos = gates.stream()
                .map(g -> new GateDto(g.getGateType(), g.getStatus(), g.isMandatory(), g.getReason(), g.getCheckedAt()))
                .toList();
        // Tenant-scoped shop lookup (the CR-02 finder) — never the RLS-only findById,
        // whose shops_public_read policy could read a foreign published shop.
        String shopName = onboarding.getShopId() == null ? null
                : shopRepository.findByIdAndTenantId(onboarding.getShopId(), onboarding.getTenantId())
                        .map(Shop::getName)
                        .orElse(null);
        return new AdminOnboardingDto(
                onboarding.getId(),
                onboarding.getStatus(),
                onboarding.getModel(),
                onboarding.getShopId(),
                shopName,
                onboarding.getCompanyNumber(),
                onboarding.getSubmittedAt(),
                onboarding.getApprovedAt(),
                onboarding.getRejectionReason(),
                gateDtos);
    }

    private OnboardingDto toDto(VendorOnboarding onboarding, List<VendorOnboardingGate> gates) {
        List<GateDto> gateDtos = gates.stream()
                .map(g -> new GateDto(g.getGateType(), g.getStatus(), g.isMandatory(), g.getReason(), g.getCheckedAt()))
                .toList();
        // ONBD-03 / D-03 exact predicate: "in review" is VERIFYING with at least one
        // MANUAL_REVIEW gate (a human is the blocker) AND no still-PENDING gate (the
        // automated checks have all landed). Derived here — the single site where the
        // gate list is already loaded — so the UI renders the flag and never re-derives
        // gate lifecycle logic.
        boolean reviewPending = onboarding.getStatus() == OnboardingState.VERIFYING
                && gates.stream().anyMatch(g -> g.getStatus() == GateStatus.MANUAL_REVIEW)
                && gates.stream().noneMatch(g -> g.getStatus() == GateStatus.PENDING);
        return new OnboardingDto(
                onboarding.getId(),
                onboarding.getStatus(),
                onboarding.getModel(),
                onboarding.getShopId(),
                onboarding.getCompanyNumber(),
                onboarding.getSubmittedAt(),
                onboarding.getApprovedAt(),
                onboarding.getWentLiveAt(),
                onboarding.getRejectionReason(),  // ONBD-05 / D-09 — already on the entity
                reviewPending,                    // ONBD-03 / D-03
                gateDtos);
    }
}
