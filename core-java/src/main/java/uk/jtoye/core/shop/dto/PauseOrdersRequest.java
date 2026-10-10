package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;

import java.util.Set;

/**
 * Body of {@code PUT /api/v1/shops/{id}/pause} (Phase 37-20, D-15): exactly one of a timed pause of 20 or 40
 * minutes, or a pause until the vendor reopens. The three choices are the whole contract; any other minutes
 * value, an empty body, or both choices at once is a typed 400 ({@code errors.oneValidChoice}).
 *
 * <p>The write is ABSOLUTE: it sets the pause state, it never adds to it. A repeated
 * {@code {"untilReopened": true}} leaves the same state; a repeated {@code {"minutes": 20}} re-arms the pause
 * to twenty minutes from the repeat. So a retry cannot stack pauses, which is why the endpoint needs no
 * Idempotency-Key (agent-readiness: provably idempotent in state).
 */
public class PauseOrdersRequest {

    /** The timed pauses D-15 offers. */
    public static final Set<Integer> ALLOWED_MINUTES = Set.of(20, 40);

    // No allowableValues: springdoc renders them as STRING enum values on this integer, a schema no integer
    // satisfies. The rule is in the description and enforced by isOneValidChoice (typed 400).
    @Schema(description = "Pause storefront orders for this many minutes from now: 20 or 40 (any other value is a 400).",
            nullable = true)
    private Integer minutes;

    @Schema(description = "Pause storefront orders until the vendor resumes them (DELETE /shops/{id}/pause).",
            nullable = true)
    private Boolean untilReopened;

    public PauseOrdersRequest() {
    }

    public PauseOrdersRequest(Integer minutes, Boolean untilReopened) {
        this.minutes = minutes;
        this.untilReopened = untilReopened;
    }

    /**
     * Exactly one valid choice. {@code @JsonIgnore} keeps this derived property out of Jackson and out of the
     * springdoc request schema (the CreateShopRequest.isCoordinatePaired precedent).
     */
    @JsonIgnore
    @AssertTrue(message = "send minutes 20 or 40, or untilReopened true, and not both")
    public boolean isOneValidChoice() {
        if (Boolean.TRUE.equals(untilReopened)) {
            return minutes == null;
        }
        return minutes != null && ALLOWED_MINUTES.contains(minutes);
    }

    /** True for "until I reopen". Only meaningful when {@link #isOneValidChoice()} holds. */
    @JsonIgnore
    public boolean isIndefinite() {
        return Boolean.TRUE.equals(untilReopened);
    }

    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
    public Boolean getUntilReopened() { return untilReopened; }
    public void setUntilReopened(Boolean untilReopened) { this.untilReopened = untilReopened; }
}
