package uk.jtoye.core.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public class CreateOrderRequest {
    @NotNull
    private UUID shopId;

    private UUID customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;
    private String notes;

    /**
     * How this order is fulfilled — the enum-string form of
     * {@link uk.jtoye.core.order.FulfilmentType} (DELIVERY | COLLECTION).
     *
     * <p><b>OPTIONAL, and it defaults to COLLECTION</b> (COR-1 / adjudication A8 / owner ruling
     * E-1). Every existing caller omits it and is unaffected: a request with no fulfilment type
     * is a walk-in / phone collection ticket, which is what this endpoint's only UI — the vendor
     * dashboard's create-order dialog — has always actually captured. Before COR-1 the field did
     * not exist at all, the entity's V45 default stood, and every such order persisted as
     * DELIVERY with a £0 fee and no address.
     *
     * <p>Sending {@code DELIVERY} makes the address block below <b>conditionally required</b> and
     * applies the shop's delivery fee through the same rule the storefront uses
     * ({@code FulfilmentPolicy}). An unrecognised value is a 400 — never a silent default.
     */
    @Size(max = 20)
    @Schema(description = "How the order is fulfilled. Optional; defaults to COLLECTION for this "
            + "endpoint, because a vendor/API/MCP order captures no address unless one is sent. "
            + "DELIVERY requires addressLine1, addressCity and addressPostcode.",
            example = "COLLECTION", allowableValues = {"DELIVERY", "COLLECTION"})
    private String fulfilmentType;

    // UK delivery address (V45 column widths: 255/255/120/12). Nullable at the DTO level — a
    // COLLECTION order has none, and an address sent WITH a COLLECTION order is deliberately not
    // persisted: the fulfilment type decides, not the payload.
    @Size(max = 255)
    private String addressLine1;

    @Size(max = 255)
    private String addressLine2;

    @Size(max = 120)
    private String addressCity;

    @Size(max = 12)
    private String addressPostcode;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    public UUID getShopId() {
        return shopId;
    }

    public void setShopId(UUID shopId) {
        this.shopId = shopId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(UUID customerId) {
        this.customerId = customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public void setCustomerPhone(String customerPhone) {
        this.customerPhone = customerPhone;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getFulfilmentType() {
        return fulfilmentType;
    }

    public void setFulfilmentType(String fulfilmentType) {
        this.fulfilmentType = fulfilmentType;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressCity() {
        return addressCity;
    }

    public void setAddressCity(String addressCity) {
        this.addressCity = addressCity;
    }

    public String getAddressPostcode() {
        return addressPostcode;
    }

    public void setAddressPostcode(String addressPostcode) {
        this.addressPostcode = addressPostcode;
    }

    public List<OrderItemRequest> getItems() {
        return items;
    }

    public void setItems(List<OrderItemRequest> items) {
        this.items = items;
    }
}
