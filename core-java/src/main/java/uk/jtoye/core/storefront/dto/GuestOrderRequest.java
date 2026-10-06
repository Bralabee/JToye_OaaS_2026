package uk.jtoye.core.storefront.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class GuestOrderRequest {
    @NotBlank(message = "Name is required")
    @Size(max = 255)
    private String customerName;

    @NotBlank(message = "Email is required")
    @Email(message = "Valid email is required")
    @Size(max = 255)
    private String customerEmail;

    @NotBlank(message = "Phone is required")
    @Size(max = 50)
    private String customerPhone;

    @Size(max = 500)
    private String notes;

    @Size(max = 64)
    private String idempotencyKey;

    // NOTE: customerAllergenMask was removed 2026-07-30. It carried special-category
    // health data (UK GDPR Art. 9) over an UNAUTHENTICATED endpoint, with no Art. 9(2)
    // condition recorded and no consent capture anywhere in the flow — and no client
    // ever sent it, so nothing was lost by removing it (data minimisation, Art. 5(1)(c)).
    // See docs/legal/article-9-allergen-basis.md before reinstating any equivalent field.

    /**
     * How the order is fulfilled — the enum-string form of
     * {@link uk.jtoye.core.order.FulfilmentType} (DELIVERY | COLLECTION).
     * Required at the API boundary; the service parses + validates it and
     * enforces the conditional address requirement for DELIVERY.
     */
    @NotBlank(message = "Fulfilment type is required")
    @Size(max = 20)
    private String fulfilmentType;

    // UK delivery address — nullable at the DTO level (a COLLECTION order has
    // none). The service enforces line1/city/postcode as required for DELIVERY.
    // @Size caps match the V45 column widths (255/255/120/12).
    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 120)
    private String addressCity;

    @Size(max = 12)
    private String addressPostcode;

    @NotEmpty(message = "At least one item is required")
    @Valid
    private List<GuestOrderItemRequest> items;

    /**
     * The allergen set the checkout panel SHOWED the customer when they ticked the acknowledgement
     * (Phase 31.1 #784/#785, D-05): a 14-bit mask in the {@code AllergenCatalog} layout, the union
     * of the basket products' declared {@code allergenMask} values as the public menu served them.
     *
     * <p>Nullable at the DTO so an old client's body still binds, but REQUIRED by the service: a
     * storefront order without it is refused 422 {@code allergen-acknowledgement-required}, and one
     * that differs from the set the server reads for the basket at submit is refused 409
     * {@code allergen-acknowledgement-stale}. It is not health data: it describes the products, not
     * the customer.
     *
     * <p>{@code NON_NULL}: the idempotency fingerprint ({@code IdempotencyJson}, frozen at the
     * Boot 3.5 bytes by {@code IdempotencyFingerprintGoldenTest}) serialises this DTO. Absent when
     * null, so a request without the field hashes exactly as it did before V69, and a key reserved
     * by an older pod still matches; present when set, so a resubmit with a different acknowledgement
     * under the same key is a payload mismatch (422), never a replay.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Min(value = 0, message = "acknowledgedAllergenMask must be between 0 and 16383")
    @Max(value = 16383, message = "acknowledgedAllergenMask must be between 0 and 16383")
    @Schema(description = "The 14-bit allergen set (AllergenCatalog bits 0..13) shown to the customer and "
            + "acknowledged at checkout. Required for a storefront order: missing is refused 422 "
            + "allergen-acknowledgement-required; different from the basket's current declared set is "
            + "refused 409 allergen-acknowledgement-stale, which carries the current set.")
    private Integer acknowledgedAllergenMask;

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getFulfilmentType() { return fulfilmentType; }
    public void setFulfilmentType(String fulfilmentType) { this.fulfilmentType = fulfilmentType; }
    public String getAddressLine1() { return addressLine1; }
    public void setAddressLine1(String addressLine1) { this.addressLine1 = addressLine1; }
    public String getAddressLine2() { return addressLine2; }
    public void setAddressLine2(String addressLine2) { this.addressLine2 = addressLine2; }
    public String getAddressCity() { return addressCity; }
    public void setAddressCity(String addressCity) { this.addressCity = addressCity; }
    public String getAddressPostcode() { return addressPostcode; }
    public void setAddressPostcode(String addressPostcode) { this.addressPostcode = addressPostcode; }
    public List<GuestOrderItemRequest> getItems() { return items; }
    public void setItems(List<GuestOrderItemRequest> items) { this.items = items; }
    public Integer getAcknowledgedAllergenMask() { return acknowledgedAllergenMask; }
    public void setAcknowledgedAllergenMask(Integer acknowledgedAllergenMask) {
        this.acknowledgedAllergenMask = acknowledgedAllergenMask;
    }
}
