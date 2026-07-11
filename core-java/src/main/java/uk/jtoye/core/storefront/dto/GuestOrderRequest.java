package uk.jtoye.core.storefront.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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

    private Integer customerAllergenMask;

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
    public Integer getCustomerAllergenMask() { return customerAllergenMask; }
    public void setCustomerAllergenMask(Integer customerAllergenMask) { this.customerAllergenMask = customerAllergenMask; }
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
}
