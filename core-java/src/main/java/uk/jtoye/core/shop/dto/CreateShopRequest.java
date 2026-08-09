package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public class CreateShopRequest {
    // QA BE-2: bound free-text so over-length fails validation with a 400, not a
    // misleading 409. name matches the varchar(255) column; description is TEXT so the
    // cap is a generous abuse guard (mirrors the ingredientsText @Size precedent).
    @NotBlank
    @Size(max = 255)
    private String name;

    private String address;
    private String slug;
    @Size(max = 5000)
    private String description;
    private String logoUrl;
    private String bannerUrl;
    private String phone;
    private String email;
    // 33-05 / ASVS V5 (threat T-33-05-01): these two fields had NO validation, and the
    // generated ShopMapperImpl writes them onto the entity on create AND update — so a
    // request body {"latitude": 999} persisted, unremarked, and the shop then sorted as
    // nearer or further than every real shop on the platform depending on the sign.
    // Measured before the fix: POST with latitude 999 returned 201 Created.
    //
    // The bounds are the WGS84 domain, nothing cleverer. They are a RANGE, not a ban:
    // ShopServiceGeocodeTest asserts the exact boundaries (+/-90, +/-180) and a real
    // London coordinate are all accepted, because a constraint that rejects everything
    // would make all the rejection arms pass while breaking the field.
    //
    // The endpoint already runs @Valid, so a violation becomes an RFC 7807 typed 400
    // (type https://jtoye.uk/errors/validation, with the offending field named under
    // `errors`) via GlobalExceptionHandler — machine-parseable, per the agent-readiness
    // contract — instead of a persisted absurdity.
    @DecimalMin(value = "-90.0", message = "latitude must be between -90 and 90")
    @DecimalMax(value = "90.0", message = "latitude must be between -90 and 90")
    private Double latitude;

    @DecimalMin(value = "-180.0", message = "longitude must be between -180 and 180")
    @DecimalMax(value = "180.0", message = "longitude must be between -180 and 180")
    private Double longitude;

    // WR-02 (phase-33 code review): the two axes above are range-validated
    // INDEPENDENTLY, so a request carrying only one of them validated clean — and on
    // update the IGNORE-null ShopMapper then merged the client's half onto the entity
    // next to the PERSISTED other half, publishing a coordinate nobody supplied into
    // public distance ranking. A single axis is not a coordinate; the pair is atomic.
    //
    // @JsonIgnore keeps this derived property out of Jackson (de)serialisation AND
    // out of the springdoc request schema, so the committed OpenAPI snapshot is
    // unchanged by the constraint — the wire contract gains a validation rule, not a
    // field. The violation surfaces as the usual RFC 7807 typed 400 with
    // `errors.coordinatePaired` naming the rule (agent-readiness contract).
    @JsonIgnore
    @AssertTrue(message = "latitude and longitude must be supplied together")
    public boolean isCoordinatePaired() {
        return (latitude == null) == (longitude == null);
    }
    private Map<String, String> openingHours;
    private String deliveryInfo;
    private Long minimumOrderPennies;
    private Boolean published;
    private String tags;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Map<String, String> getOpeningHours() { return openingHours; }
    public void setOpeningHours(Map<String, String> openingHours) { this.openingHours = openingHours; }
    public String getDeliveryInfo() { return deliveryInfo; }
    public void setDeliveryInfo(String deliveryInfo) { this.deliveryInfo = deliveryInfo; }
    public Long getMinimumOrderPennies() { return minimumOrderPennies; }
    public void setMinimumOrderPennies(Long minimumOrderPennies) { this.minimumOrderPennies = minimumOrderPennies; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
