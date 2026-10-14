package uk.jtoye.core.shop.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import uk.jtoye.core.shop.OpeningSchedule;

import java.util.List;
import java.util.Map;

public class CreateShopRequest {
    // QA BE-2: bound free-text so over-length fails validation with a 400, not a
    // misleading 409. name matches the varchar(255) column; description is TEXT so the
    // cap is a generous abuse guard (mirrors the ingredientsText @Size precedent).
    @NotBlank
    @Size(max = 255)
    private String name;

    private String address;

    /**
     * The shop's web address, {@code /shop/{slug}} (37-48, UXT-028). Absent or blank on update KEEPS the stored
     * slug (shared links and QR codes keep working); on create it means "generate one". An explicit value must be
     * lower-case letters, digits and single hyphens, at most 100 characters (the column), and is then refused if
     * reserved by a storefront route (422) or held by another shop (409 shop-slug-taken).
     */
    @Schema(description = "The shop's web address (/shop/{slug}). Absent or blank keeps the current one on update and "
            + "generates one on create. Lower-case letters, digits and single hyphens, at most 100 characters.",
            nullable = true, maxLength = 100)
    @Size(max = 100, message = "slug must be at most 100 characters")
    @Pattern(regexp = "^(\\s*|[a-z0-9]+(-[a-z0-9]+)*)$",
            message = "slug must be lower-case letters, digits and single hyphens")
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

    /**
     * Structured opening hours (37-20, D-15): {@code mon}..{@code sun}, each a list of up to 3
     * {@code {"open": "HH:MM", "close": "HH:MM"}} windows in UK time; a close at or before the open runs past
     * midnight. ABSENT keeps the stored schedule; {@code {}} (or a day's {@code []}) saves closed. Any save
     * makes the shop's hours STRUCTURED, so an empty schedule takes no storefront orders. Overlaps and more
     * than 3 windows a day are a 400 naming the day.
     */
    @Schema(description = "Structured opening hours, mon..sun -> up to 3 {open, close} windows (HH:MM, UK time; "
            + "close at or before open runs past midnight). Absent keeps the stored schedule; {} saves closed every day.",
            nullable = true)
    private Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule;

    private String deliveryInfo;
    private Long minimumOrderPennies;

    // ---- 37-48 (RWO-023 / UXT-023): what delivery costs and which fulfilment types the shop takes ----

    @Schema(description = "Delivery fee in pennies, charged on a DELIVERY order below the free-delivery threshold. "
            + "Absent keeps the stored fee (a new shop charges 0).", nullable = true)
    @Min(value = 0, message = "deliveryFeePennies must not be negative")
    private Long deliveryFeePennies;

    @Schema(description = "A DELIVERY order whose items reach this subtotal (pennies, inclusive) pays no delivery fee. "
            + "null means no free-delivery threshold. Absent keeps the stored threshold; an explicit null clears it.",
            nullable = true)
    @Min(value = 0, message = "freeDeliveryThresholdPennies must not be negative")
    private Long freeDeliveryThresholdPennies;

    /** True when the request carried the threshold key at all, null included: an explicit null clears it. */
    @JsonIgnore
    private boolean freeDeliveryThresholdPenniesSent;

    @Schema(description = "The shop takes DELIVERY orders. Absent keeps the stored value (a new shop takes both). "
            + "At least one of offersDelivery and offersCollection must be true.", nullable = true)
    private Boolean offersDelivery;

    @Schema(description = "The shop takes COLLECTION orders. Absent keeps the stored value (a new shop takes both). "
            + "At least one of offersDelivery and offersCollection must be true.", nullable = true)
    private Boolean offersCollection;

    /**
     * A shop that offers neither type could take no order at all (37-48, UXT-023). This catches both flags sent
     * false together; a single false merged with the OTHER stored false is refused by ShopService with the same
     * {@code fulfilmentOffered} key, and the database's {@code ck_shops_offers_some} is the last line. Hidden from
     * Jackson and springdoc like {@link #isCoordinatePaired()}: a rule, not a field.
     */
    @JsonIgnore
    @AssertTrue(message = FULFILMENT_OFFERED_MESSAGE)
    public boolean isFulfilmentOffered() {
        return !(Boolean.FALSE.equals(offersDelivery) && Boolean.FALSE.equals(offersCollection));
    }

    /** The 400 message for a shop offering neither type (UI-SPEC G1 copy). */
    public static final String FULFILMENT_OFFERED_MESSAGE = "Choose at least one: delivery or collection.";

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
    public Map<String, List<OpeningSchedule.TimeWindow>> getOpeningSchedule() { return openingSchedule; }
    public void setOpeningSchedule(Map<String, List<OpeningSchedule.TimeWindow>> openingSchedule) { this.openingSchedule = openingSchedule; }
    public String getDeliveryInfo() { return deliveryInfo; }
    public void setDeliveryInfo(String deliveryInfo) { this.deliveryInfo = deliveryInfo; }
    public Long getMinimumOrderPennies() { return minimumOrderPennies; }
    public void setMinimumOrderPennies(Long minimumOrderPennies) { this.minimumOrderPennies = minimumOrderPennies; }
    public Long getDeliveryFeePennies() { return deliveryFeePennies; }
    public void setDeliveryFeePennies(Long deliveryFeePennies) { this.deliveryFeePennies = deliveryFeePennies; }
    public Long getFreeDeliveryThresholdPennies() { return freeDeliveryThresholdPennies; }
    public void setFreeDeliveryThresholdPennies(Long freeDeliveryThresholdPennies) {
        this.freeDeliveryThresholdPennies = freeDeliveryThresholdPennies;
        this.freeDeliveryThresholdPenniesSent = true;
    }
    @JsonIgnore
    public boolean isFreeDeliveryThresholdPenniesSent() { return freeDeliveryThresholdPenniesSent; }
    public Boolean getOffersDelivery() { return offersDelivery; }
    public void setOffersDelivery(Boolean offersDelivery) { this.offersDelivery = offersDelivery; }
    public Boolean getOffersCollection() { return offersCollection; }
    public void setOffersCollection(Boolean offersCollection) { this.offersCollection = offersCollection; }
    public Boolean getPublished() { return published; }
    public void setPublished(Boolean published) { this.published = published; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
}
