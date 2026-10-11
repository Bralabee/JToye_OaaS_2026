package uk.jtoye.core.finance.dto;

import uk.jtoye.core.finance.EntryKind;
import uk.jtoye.core.finance.VatRate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPQL constructor-target for one CSV export line (Phase 37-11, RWO-027, roadmap SC-2): a ledger row with
 * its order's number and its shop's name resolved DB-side, so the export is one query however many rows.
 *
 * @param orderNumber null for a row with no order (MANUAL)
 * @param shopName    null for a row with no shop (MANUAL, or a legacy order-less row)
 */
public record FinanceExportRow(
        UUID id,
        OffsetDateTime createdAt,
        String reference,
        String orderNumber,
        EntryKind entryKind,
        long amountPennies,
        VatRate vatRate,
        String shopName
) {
}
