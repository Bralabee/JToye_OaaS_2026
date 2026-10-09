package uk.jtoye.core.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The scope a finance read actually filtered on (Phase 37-11, RWO-027 / UXT-027), echoed in every summary,
 * list and export response so the dashboard's banner reads what the API did, never the switcher's local
 * value (say is not data).
 *
 * @param shopId   the shop the figures are for; null means every shop of the tenant, rows with no shop
 *                 (MANUAL entries) included
 * @param shopName that shop's name; null when {@code shopId} is null
 * @param from     first UK (Europe/London) day included; null means from the first row
 * @param to       last UK day included; null means up to now
 */
@Schema(description = "The scope this response was filtered on: one shop, or all shops (shopId null), "
        + "and an inclusive UK-day range (null ends are open).")
public record FinanceScopeDto(
        UUID shopId,
        String shopName,
        LocalDate from,
        LocalDate to
) {
}
