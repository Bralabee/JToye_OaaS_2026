package uk.jtoye.core.sync.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * What {@code POST /sync/batch} did with ONE item (Phase 37-40, RWO-037 / UXT-037).
 *
 * <p>The batch used to skip an item it could not use (an unknown {@code type}, a product without a
 * SKU) and still answer {@code SUCCESS}, so an integrator could not tell a synced catalogue from a
 * half-synced one. Every item now gets exactly one result, in request order, carrying its
 * {@code index} in the request's {@code items} array: {@code CREATED} or {@code UPDATED} with the
 * id of the row it wrote, or {@code REJECTED} with a stable machine-readable {@code code} and a
 * human-readable {@code message}. A rejected item wrote nothing; the other items are unaffected
 * (each item runs in its own transaction).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The outcome of one batch item. Exactly one per item, in request order.")
public class SyncItemResult {

    /** What happened to the item. */
    public enum Outcome { CREATED, UPDATED, REJECTED }

    /**
     * Why an item was refused. Stable codes: integrators may branch on them.
     */
    public enum Code {
        /** {@code type} is missing or is neither {@code shop} nor {@code product}. */
        UNKNOWN_TYPE,
        /** A product item without a {@code sku}. */
        SKU_REQUIRED,
        /** A shop item without a {@code name}. */
        NAME_REQUIRED,
        /** A product item without a {@code shopId}: every product belongs to one shop. */
        SHOP_REQUIRED,
        /** The SKU already exists in this tenant under a different shop than the item names. */
        SHOP_MISMATCH,
        /** The caller does not hold the role the write needs on the shop (SHOP_MANAGER; GROUP_ADMIN to create a shop). */
        SHOP_ACCESS_DENIED,
        /** The named shop does not exist in the caller's tenant. */
        SHOP_NOT_FOUND,
        /** A field required to create the product is missing, or two stock fields contradict each other. */
        INVALID_FIELDS,
        /** The write collided with a concurrent one (for example the same SKU created twice); retry the item. */
        WRITE_CONFLICT,
        /** The write failed for an unexpected reason; nothing was stored for this item. */
        WRITE_FAILED
    }

    @Schema(description = "Position of the item in the request's items array (0-based)", example = "0")
    private int index;

    @Schema(description = "The item's type as sent", example = "product")
    private String type;

    @Schema(description = "The item's upsert key: the sku for a product, the name for a shop", example = "YAM-5KG")
    private String key;

    @Schema(description = "CREATED, UPDATED or REJECTED")
    private Outcome outcome;

    @Schema(description = "Id of the product or shop written (absent when REJECTED)")
    private UUID id;

    @Schema(description = "Why the item was rejected (absent unless REJECTED)")
    private Code code;

    @Schema(description = "Human-readable detail for a rejection (absent unless REJECTED)")
    private String message;

    public static SyncItemResult created(int index, String type, String key, UUID id) {
        return SyncItemResult.builder().index(index).type(type).key(key).outcome(Outcome.CREATED).id(id).build();
    }

    public static SyncItemResult updated(int index, String type, String key, UUID id) {
        return SyncItemResult.builder().index(index).type(type).key(key).outcome(Outcome.UPDATED).id(id).build();
    }

    public static SyncItemResult rejected(int index, String type, String key, Code code, String message) {
        return SyncItemResult.builder().index(index).type(type).key(key).outcome(Outcome.REJECTED)
                .code(code).message(message).build();
    }

    /** True for CREATED and UPDATED. */
    @JsonIgnore
    public boolean isWritten() {
        return outcome == Outcome.CREATED || outcome == Outcome.UPDATED;
    }
}
