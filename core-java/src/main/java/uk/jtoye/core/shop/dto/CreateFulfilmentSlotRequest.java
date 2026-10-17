package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * ADR-0007 / GAP-GTM-02: Request payload for creating a capacity-bounded scheduled
 * fulfilment slot on a shop.
 */
public class CreateFulfilmentSlotRequest {

    @NotNull(message = "serviceDate is required")
    @Schema(description = "Service date for the fulfilment window (YYYY-MM-DD)")
    private LocalDate serviceDate;

    @NotNull(message = "windowStart is required")
    @Schema(description = "Start time of the fulfilment window (HH:mm, UK time)")
    private LocalTime windowStart;

    @NotNull(message = "windowEnd is required")
    @Schema(description = "End time of the fulfilment window (HH:mm, UK time; must be after windowStart)")
    private LocalTime windowEnd;

    @NotNull(message = "cutoffAt is required")
    @Schema(description = "Pre-order cutoff timestamp after which new orders for this slot are refused")
    private OffsetDateTime cutoffAt;

    @Min(value = 1, message = "maxOrders must be at least 1 when specified")
    @Schema(description = "Optional maximum number of orders accepted in this slot", nullable = true, minimum = "1")
    private Integer maxOrders;

    @Min(value = 1, message = "maxUnits must be at least 1 when specified")
    @Schema(description = "Optional maximum number of units/bowls accepted in this slot", nullable = true, minimum = "1")
    private Integer maxUnits;

    @JsonIgnore
    @AssertTrue(message = "windowEnd must be after windowStart")
    public boolean isWindowValid() {
        if (windowStart == null || windowEnd == null) {
            return true;
        }
        return windowEnd.isAfter(windowStart);
    }

    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }
    public LocalTime getWindowStart() { return windowStart; }
    public void setWindowStart(LocalTime windowStart) { this.windowStart = windowStart; }
    public LocalTime getWindowEnd() { return windowEnd; }
    public void setWindowEnd(LocalTime windowEnd) { this.windowEnd = windowEnd; }
    public OffsetDateTime getCutoffAt() { return cutoffAt; }
    public void setCutoffAt(OffsetDateTime cutoffAt) { this.cutoffAt = cutoffAt; }
    public Integer getMaxOrders() { return maxOrders; }
    public void setMaxOrders(Integer maxOrders) { this.maxOrders = maxOrders; }
    public Integer getMaxUnits() { return maxUnits; }
    public void setMaxUnits(Integer maxUnits) { this.maxUnits = maxUnits; }
}
