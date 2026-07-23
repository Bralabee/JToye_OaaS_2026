package uk.jtoye.core.security.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;
import java.util.UUID;

/**
 * The server-authoritative answer to "what is my effective access?" for the calling
 * user — returned by {@code GET /api/v1/staff/me} (Phase 23, CR-08 backend half).
 * Consumed by plan 23-13 to replace the frontend's client-side Keycloak realm-role
 * guess (which is WRONG for the day-one implicit-GROUP_ADMIN case) with this server truth.
 *
 * <p><strong>Empty-set sentinel resolved (the CR-08-in-a-new-form trap).</strong>
 * {@link uk.jtoye.core.security.access.ShopAccessService#grantedShopIds()} returns an
 * EMPTY set to mean TWO different things: "unrestricted" for a GROUP_ADMIN, and "no
 * access at all" for a scoped user. This DTO deliberately does NOT propagate that
 * ambiguity to the client:
 * <ul>
 *   <li>{@code groupAdmin == true}  → the caller may access EVERY shop in the tenant;
 *       {@code grantedShopIds} is {@code null} (the "unrestricted" case — a client MUST
 *       NOT read it as "no shops"). A GROUP_ADMIN has no finite shop-id set to enumerate.</li>
 *   <li>{@code groupAdmin == false} → {@code grantedShopIds} is the EXACT, possibly-empty
 *       set of shop ids the caller may access. An empty set here unambiguously means
 *       "no shop access".</li>
 * </ul>
 * So an empty {@code grantedShopIds} only ever occurs with {@code groupAdmin == false} and
 * always means "no access"; "unrestricted" is represented by {@code null} +
 * {@code groupAdmin == true}. The 23-13 frontend contract depends on this invariant.
 *
 * <p>{@code userId} is the caller's OWN Keycloak {@code sub}, so the client can identify
 * itself (e.g. the staff-page "you are removing your own access" check) without an
 * email round-trip. No other user's data is ever carried here.
 */
@Schema(description = "The caller's own effective vendor-scoped access. groupAdmin=true means "
        + "unrestricted access to all shops and grantedShopIds is null (NOT 'no shops'); "
        + "groupAdmin=false means grantedShopIds is the exact, possibly-empty set of accessible shops.")
public record MyAccessDto(
        @Schema(description = "The caller's own Keycloak subject (user id).")
        UUID userId,

        @Schema(description = "True if the caller has unrestricted (tenant-wide GROUP_ADMIN) access.")
        boolean groupAdmin,

        @Schema(description = "For a non-GROUP_ADMIN caller, the exact set of shop ids they may access "
                + "(empty = no access). Null for a GROUP_ADMIN — unrestricted; do NOT read as 'no shops'.",
                nullable = true)
        Set<UUID> grantedShopIds) {
}
