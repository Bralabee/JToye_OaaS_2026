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
    public List<GuestOrderItemRequest> getItems() { return items; }
    public void setItems(List<GuestOrderItemRequest> items) { this.items = items; }
}
